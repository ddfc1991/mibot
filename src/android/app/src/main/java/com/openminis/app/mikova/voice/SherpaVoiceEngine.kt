package com.openminis.app.mikova.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** sherpa-onnx (Apache-2.0). Kotlin API is optional; no AAR/native runtime means system fallback. */
class SherpaVoiceEngine(private val appContext: Context) : VoiceEngine {
    override val id get() = VoiceEngineId.SHERPA
    override val displayName get() = "离线 sherpa-onnx"
    override val supportsPartialResults get() = true
    private val closed = AtomicBoolean(false)
    private val degradedFlag = AtomicBoolean(false)
    override val degraded get() = degradedFlag.get()
    override fun markDegraded() { degradedFlag.set(true) }
    override fun clearDegraded() { degradedFlag.set(false) }
    override val isAvailable get() = !closed.get() && !degraded && isUsable(appContext)

    private val playback = PcmPlayback()
    override val speaking get() = playback.speaking
    override val outputLevel get() = playback.outputLevel
    private val status = MutableStateFlow<String?>(null)
    override val ttsStatus get() = status
    override val canSynthesize get() = !closed.get() && voices.isNotEmpty() && VoiceModelManager.hasTtsModel(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ttsMutex = Mutex()
    private var tts: Any? = null
    private val loadingVoices = AtomicBoolean(false)
    @Volatile private var voices = emptyList<TtsVoiceChoice>()
    private val prefs = appContext.getSharedPreferences("mikova_voice", Context.MODE_PRIVATE)
    @Volatile private var selectedVoice = prefs.getString("aishell3_voice_id", null)
        ?: "aishell3:${prefs.getInt("aishell3_speaker", 0)}"

    override fun ttsVoices(context: Context): List<TtsVoiceChoice> {
        if (!VoiceModelManager.hasTtsModel(appContext)) voices = emptyList()
        if (!closed.get() && loadingVoices.compareAndSet(false, true)) {
            scope.launch { try { loadTtsVoices(context) } finally { loadingVoices.set(false) } }
        }
        return voices
    }

    override suspend fun loadTtsVoices(context: Context): List<TtsVoiceChoice> = withContext(Dispatchers.IO) {
        ttsMutex.withLock {
            try {
                if (prepareTts() == null) emptyList() else voices
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Prepare TTS failed", e)
                voices = emptyList()
                status.value = "离线合成初始化失败：${e.cause?.message ?: e.message ?: e.javaClass.simpleName}"
                emptyList()
            }
        }
    }

    /** Called only under ttsMutex on an IO worker. Never loads a large model on a UI list read. */
    private fun prepareTts(): Any? {
        if (closed.get()) return null
        if (!SherpaJni.load()) {
            voices = emptyList()
            status.value = "本版本不含可用离线运行库，请选择系统朗读"
            return null
        }
        if (!VoiceModelManager.hasTtsModel(appContext)) {
            tts?.let(SherpaJni::release)
            tts = null
            voices = emptyList()
            status.value = "缺少完整离线合成模型；语音识别与朗读独立"
            return null
        }
        val engine = tts ?: SherpaJni.buildOfflineTts(ttsConfig()).also { tts = it }
        val count = SherpaJni.numSpeakers(engine)
        require(count in 1..10_000) { "Invalid runtime speaker count: $count" }
        voices = (0 until count).map { sid ->
            TtsVoiceChoice("aishell3:$sid", "Speaker ${sid + 1}", "AISHELL3 · 本地离线", false)
        }
        status.value = null
        return engine
    }

    override fun setTtsVoice(context: Context, voiceId: String?) {
        selectedVoice = voiceId ?: "aishell3:0"
        prefs.edit().putString("aishell3_voice_id", voiceId).apply()
    }

    private class Listening(val listener: VoiceEngine.Listener) {
        val cancelled = AtomicBoolean(false)
        val finishing = AtomicBoolean(false)
        var recorder: AudioRecord? = null // access under captureLock only
    }
    private val captureLock = Any()
    private val captureWorker = Executors.newSingleThreadExecutor { Thread(it, "sherpa-asr") }
    private var listening: Listening? = null
    @Volatile private var finalSink: ((String) -> Unit)? = null

    /** Legacy VoiceScreen hook. Persistent across cancel/interrupt; takes precedence over onFinal. */
    fun onFinalSink(sink: (String) -> Unit) { finalSink = sink }

    override fun startListen(locale: VoiceEngine.LocaleCompat, listener: VoiceEngine.Listener) {
        cancel()
        if (!isAvailable) {
            listener.onError(VoiceEngine.VoiceError.UNAVAILABLE,
                if (!SherpaJni.load()) "本版本不含可用离线运行库，请使用系统识别" else "缺少完整离线识别模型")
            return
        }
        val session = Listening(listener)
        synchronized(captureLock) {
            if (closed.get()) return
            listening = session
            captureWorker.execute { capture(session) }
        }
    }

    private fun emit(session: Listening, action: () -> Unit) = synchronized(captureLock) {
        if (listening === session && !session.cancelled.get()) action()
    }

    @SuppressLint("MissingPermission")
    private fun capture(session: Listening) {
        var recognizer: Any? = null
        var stream: Any? = null
        var recorder: AudioRecord? = null
        var text: String? = null
        var failure: Throwable? = null
        try {
            if (session.cancelled.get()) return
            val r = SherpaJni.buildOnlineRecognizer(asrConfig())
            recognizer = r
            val st = SherpaJni.createStream(r)
            stream = st
            if (session.cancelled.get()) return
            // Mono is the reliable baseline. Stereo energy balance cannot establish physical direction.
            val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "Cannot allocate microphone buffer: $min" }
            val mic = AudioRecord(MediaRecorder.AudioSource.MIC, 16_000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 6_400))
            recorder = mic
            check(mic.state == AudioRecord.STATE_INITIALIZED && mic.channelCount == 1) { "Cannot initialize mono microphone" }
            synchronized(captureLock) {
                if (session.cancelled.get() || session.finishing.get()) return@synchronized
                session.recorder = mic
                mic.startRecording()
                check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            }
            emit(session) { session.listener.onDirection(null) }
            val pcm = ShortArray(1_600)
            while (!session.cancelled.get() && !session.finishing.get()) {
                val n = mic.read(pcm, 0, pcm.size)
                if (session.cancelled.get() || session.finishing.get()) break
                check(n > 0) { "Microphone read failed: $n" }
                var square = 0.0
                val samples = FloatArray(n) { i ->
                    (pcm[i] / 32768f).also { square += it.toDouble() * it }
                }
                emit(session) { session.listener.onLevel(VoiceLevels.rmsToDb(kotlin.math.sqrt(square / n))) }
                SherpaJni.acceptWaveform(st, samples, 16_000)
                while (!session.cancelled.get() && SherpaJni.isReady(r, st)) SherpaJni.decode(r, st)
                val partial = SherpaJni.resultText(r, st)
                if (partial.isNotBlank()) emit(session) { session.listener.onPartial(partial) }
                // Endpoint reset would discard earlier text during press-and-hold; finish only on stop.
            }
            if (!session.cancelled.get()) {
                SherpaJni.acceptWaveform(st, FloatArray(4_800), 16_000)
                SherpaJni.inputFinished(st)
                while (!session.cancelled.get() && SherpaJni.isReady(r, st)) SherpaJni.decode(r, st)
                text = SherpaJni.resultText(r, st)
            }
        } catch (e: Throwable) {
            failure = e
            Log.w(TAG, "Offline recognition failed", e)
        } finally {
            synchronized(captureLock) {
                session.recorder = null
                try { recorder?.stop() } catch (e: Exception) { Log.d(TAG, "Microphone already stopped", e) }
                try { recorder?.release() } catch (e: Exception) { Log.w(TAG, "Release microphone failed", e) }
            }
            // Native release stays on the owning worker, after every read/decode has finished.
            listOf(stream, recognizer).forEach { resource ->
                try { resource?.let(SherpaJni::release) } catch (e: Throwable) {
                    Log.w(TAG, "Release recognizer resource failed", e)
                    if (failure == null) failure = e
                }
            }
            emit(session) {
                listening = null
                val error = failure
                if (error != null) {
                    session.listener.onError(if (error is SecurityException) VoiceEngine.VoiceError.PERMISSION else VoiceEngine.VoiceError.UNAVAILABLE,
                        error.cause?.message ?: error.message ?: error.javaClass.simpleName)
                } else if (text.isNullOrBlank()) session.listener.onError(VoiceEngine.VoiceError.NO_MATCH)
                else finalSink?.invoke(text.orEmpty()) ?: session.listener.onFinal(text.orEmpty())
            }
        }
    }

    override fun stopListen() = synchronized(captureLock) {
        listening?.let { session ->
            session.finishing.set(true)
            try { session.recorder?.stop() } catch (e: Exception) { Log.w(TAG, "Stop microphone failed", e) }
        }
        Unit
    }

    override fun cancel() = synchronized(captureLock) {
        listening?.let { session ->
            session.cancelled.set(true)
            try { session.recorder?.stop() } catch (e: Exception) { Log.w(TAG, "Cancel microphone failed", e) }
        }
        listening = null
    }

    override suspend fun transcribeFile(wavPath: String): String? = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext null
        var rec: Any? = null
        var stream: Any? = null
        try {
            // The installed model is streaming: do not pass its weights to OfflineRecognizer.
            val r = SherpaJni.buildOnlineRecognizer(asrConfig())
            rec = r
            val st = SherpaJni.createStream(r)
            stream = st
            val samples = WavUtil.readMono16k(wavPath)
            for (offset in samples.indices step 1_600) {
                currentCoroutineContext().ensureActive()
                SherpaJni.acceptWaveform(st, samples.copyOfRange(offset, minOf(offset + 1_600, samples.size)), 16_000)
                while (SherpaJni.isReady(r, st)) {
                    currentCoroutineContext().ensureActive()
                    SherpaJni.decode(r, st)
                }
            }
            SherpaJni.acceptWaveform(st, FloatArray(4_800), 16_000)
            SherpaJni.inputFinished(st)
            while (SherpaJni.isReady(r, st)) {
                currentCoroutineContext().ensureActive()
                SherpaJni.decode(r, st)
            }
            SherpaJni.resultText(r, st)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w(TAG, "File transcription failed", e); null }
        finally {
            listOf(stream, rec).forEach { resource ->
                try { resource?.let(SherpaJni::release) } catch (e: Exception) { Log.w(TAG, "Release failed", e) }
            }
        }
    }

    override suspend fun synthesize(text: String, play: Boolean, voiceId: String?): ByteArray? {
        val token = playback.beginRequest()
        try {
            return withContext(Dispatchers.IO) {
                ttsMutex.withLock {
                    if (closed.get() || text.isBlank() || !playback.isCurrent(token)) return@withLock null
                    val engine = prepareTts() ?: return@withLock null
                    currentCoroutineContext().ensureActive()
                    if (!playback.isCurrent(token)) return@withLock null
                    val selected = voiceId ?: selectedVoice
                    val sid = selected.takeIf { it.startsWith("aishell3:") }?.substringAfter(':')?.toIntOrNull()
                    val count = SherpaJni.numSpeakers(engine)
                    require(sid != null && sid in 0 until count) { "音色已失效，请重新选择 ($selected)" }
                    val (samples, rate) = SherpaJni.generate(engine, text, sid)
                    currentCoroutineContext().ensureActive()
                    if (!playback.isCurrent(token)) return@withLock null
                    val wav = WavUtil.encode(samples, rate)
                    if (play) playback.play(PcmAudio(WavUtil.pcmBytes(samples), rate, 1), token)
                    wav
                }
            }
        } catch (e: CancellationException) { playback.cancel(token); throw e }
        catch (e: Exception) {
            playback.cancel(token)
            Log.w(TAG, "Offline synthesis failed", e)
            status.value = "离线合成失败：${e.cause?.message ?: e.message ?: e.javaClass.simpleName}"
            return null
        }
    }

    override fun stopSpeaking() { playback.stop() }

    override fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        cancel()
        finalSink = null
        stopSpeaking()
        captureWorker.shutdown() // queued cancelled sessions run their finally blocks
        scope.launch {
            ttsMutex.withLock {
                try { tts?.let(SherpaJni::release) } catch (e: Exception) { Log.w(TAG, "Release TTS failed", e) }
                tts = null
                voices = emptyList()
            }
            scope.cancel()
        }
    }

    private fun asrConfig(): Map<String, String> {
        val dir = VoiceModelManager.asrModelDir(appContext)
        return mapOf("tokens" to File(dir, "tokens.txt").absolutePath,
            "encoder" to modelFile(dir, "encoder"), "decoder" to modelFile(dir, "decoder"),
            "joiner" to modelFile(dir, "joiner"))
    }

    private fun ttsConfig(): Map<String, String> {
        val dir = VoiceModelManager.ttsModelDir(appContext)
        return mapOf("model" to File(dir, "model.onnx").absolutePath,
            "tokens" to File(dir, "tokens.txt").absolutePath,
            "lexicon" to File(dir, "lexicon.txt").absolutePath,
            "ruleFsts" to listOf("phone.fst", "date.fst", "number.fst").map { File(dir, it) }
                .filter { it.isFile }.joinToString(",") { it.absolutePath })
    }

    private fun modelFile(dir: File, prefix: String): String = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
        .sortedWith(compareByDescending<File> { it.name.endsWith(".int8.onnx") }.thenBy { it.name })
        .firstOrNull()?.absolutePath ?: error("Missing $prefix model")

    companion object {
        private const val TAG = "SherpaVoice"
        @JvmStatic fun isUsable(context: Context): Boolean =
            SherpaJni.load() && VoiceModelManager.hasAsrModel(context.applicationContext)
        fun isNativeLoaded(): Boolean = SherpaJni.load()
        fun sdk(): Int = Build.VERSION.SDK_INT
    }
}

/** Official sherpa-onnx/kotlin-api signatures, verified against upstream OnlineRecognizer.kt / Tts.kt.
 * Reflection errors propagate to the operation boundary; a failed call must not masquerade as silence.
 */
internal object SherpaJni {
    private const val PKG = "com.k2fsa.sherpa.onnx."
    @Volatile private var nativeReady: Boolean? = null
    @Synchronized fun load(): Boolean {
        nativeReady?.let { return it }
        val ready = try {
            System.loadLibrary("sherpa-onnx-jni")
            Class.forName(PKG + "OnlineRecognizer")
            Class.forName(PKG + "OfflineTts")
            true
        } catch (e: LinkageError) { Log.w("SherpaJni", "Offline runtime unavailable", e); false }
        catch (e: ReflectiveOperationException) { Log.w("SherpaJni", "Offline API unavailable", e); false }
        nativeReady = ready
        return ready
    }
    private fun config(name: String): Any = Class.forName(PKG + name).getDeclaredConstructor().newInstance()
    private fun set(target: Any, name: String, value: Any) {
        // Kotlin var properties expose setters; missing properties are errors, never silently ignored.
        val setter = "set" + name.replaceFirstChar { it.uppercaseChar() }
        val method = target.javaClass.methods.single { it.name == setter && it.parameterTypes.size == 1 }
        method.invoke(target, value)
    }
    private fun construct(name: String, cfg: Any): Any = Class.forName(PKG + name)
        .getConstructor(AssetManager::class.java, cfg.javaClass).newInstance(null, cfg)

    fun buildOnlineRecognizer(paths: Map<String, String>): Any {
        val transducer = config("OnlineTransducerModelConfig")
        listOf("encoder", "decoder", "joiner").forEach { set(transducer, it, paths.getValue(it)) }
        val model = config("OnlineModelConfig")
        set(model, "transducer", transducer)
        set(model, "tokens", paths.getValue("tokens"))
        set(model, "numThreads", 2)
        set(model, "modelType", "zipformer")
        val cfg = config("OnlineRecognizerConfig")
        set(cfg, "modelConfig", model)
        set(cfg, "enableEndpoint", false)
        set(cfg, "decodingMethod", "greedy_search")
        return construct("OnlineRecognizer", cfg)
    }
    fun createStream(rec: Any): Any = rec.javaClass.getMethod("createStream", String::class.java).invoke(rec, "")
    fun acceptWaveform(stream: Any, samples: FloatArray, rate: Int) {
        stream.javaClass.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
            .invoke(stream, samples, rate)
    }
    fun inputFinished(stream: Any) { stream.javaClass.getMethod("inputFinished").invoke(stream) }
    fun isReady(rec: Any, stream: Any): Boolean = rec.javaClass.getMethod("isReady", stream.javaClass).invoke(rec, stream) as Boolean
    fun decode(rec: Any, stream: Any) { rec.javaClass.getMethod("decode", stream.javaClass).invoke(rec, stream) }
    fun resultText(rec: Any, stream: Any): String {
        val result = rec.javaClass.getMethod("getResult", stream.javaClass).invoke(rec, stream)
        return result.javaClass.getMethod("getText").invoke(result) as String
    }
    fun release(resource: Any) { resource.javaClass.getMethod("release").invoke(resource) }

    fun buildOfflineTts(paths: Map<String, String>): Any {
        val vits = config("OfflineTtsVitsModelConfig")
        listOf("model", "tokens", "lexicon").forEach { set(vits, it, paths.getValue(it)) }
        val model = config("OfflineTtsModelConfig")
        set(model, "vits", vits)
        set(model, "numThreads", 2)
        val cfg = config("OfflineTtsConfig")
        set(cfg, "model", model)
        set(cfg, "ruleFsts", paths.getValue("ruleFsts"))
        return construct("OfflineTts", cfg)
    }
    fun numSpeakers(tts: Any): Int = (tts.javaClass.getMethod("numSpeakers").invoke(tts) as Number).toInt()
    fun generate(tts: Any, text: String, sid: Int): Pair<FloatArray, Int> {
        val audio = tts.javaClass.getMethod("generate", String::class.java, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .invoke(tts, text, sid, 1f)
        return (audio.javaClass.getMethod("getSamples").invoke(audio) as FloatArray) to
            (audio.javaClass.getMethod("getSampleRate").invoke(audio) as Number).toInt()
    }
}
