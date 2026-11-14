package com.openminis.app.mikova.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [mikova/voice] 平台引擎:android.speech.SpeechRecognizer(听) + TextToSpeech(说)。
 * sherpa 的兜底 —— native so 加载失败、模型缺失,或设备没有离线引擎时用。
 * 行为与 [SherpaVoiceEngine] 一致(同一 [VoiceEngine] 接口),换实现就是降级。
 */
class PlatformVoiceEngine(private val appContext: Context) : VoiceEngine {

    override val id get() = VoiceEngineId.PLATFORM
    override val displayName get() = "系统识别"
    override val supportsPartialResults get() = true

    private val degradedFlag = AtomicBoolean(false)
    override val degraded get() = degradedFlag.get()
    override fun markDegraded() { degradedFlag.set(true) }
    override fun clearDegraded() { degradedFlag.set(false) }

    private val closed = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadingVoices = AtomicBoolean(false)
    private val listenGeneration = VoiceGeneration()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsInit: CompletableDeferred<Boolean>? = null
    private val ttsLock = Any()
    private val initMutex = Mutex()
    private val synthesisMutex = Mutex()
    private var pending: CompletableDeferred<ByteArray?>? = null
    private val playback = PcmPlayback()
    @Volatile private var voices = emptyList<TtsVoiceChoice>()
    @Volatile private var selectedVoice: String? = appContext.getSharedPreferences("mikova_voice", Context.MODE_PRIVATE)
        .getString("system_voice_id", null)
    private val status = MutableStateFlow<String?>(null)
    override val ttsStatus get() = status
    override val canSynthesize get() = !closed.get() && voices.isNotEmpty()
    override val speaking get() = playback.speaking
    override val outputLevel get() = playback.outputLevel

    override val isAvailable: Boolean
        get() = !closed.get() && !degraded && SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun ttsVoices(context: Context): List<TtsVoiceChoice> {
        if (!closed.get() && loadingVoices.compareAndSet(false, true)) {
            scope.launch { try { loadTtsVoices(context) } finally { loadingVoices.set(false) } }
        }
        return voices
    }

    override suspend fun loadTtsVoices(context: Context): List<TtsVoiceChoice> = withContext(Dispatchers.IO) {
        val engine = ensureTts() ?: return@withContext emptyList()
        try {
            voices = engine.voices.orEmpty().filter {
                it.locale.language == "zh" && "notInstalled" !in it.features.orEmpty()
            }.sortedWith(compareBy<android.speech.tts.Voice> { it.isNetworkConnectionRequired }.thenBy { it.name })
                .map { voice ->
                    TtsVoiceChoice(
                        "system:${voice.name}", voice.name,
                        "${voice.locale.toLanguageTag()} · ${if (voice.isNetworkConnectionRequired) "在线" else "设备端"}",
                        voice.isNetworkConnectionRequired,
                    )
                }
            status.value = if (voices.isEmpty()) "系统未提供已安装中文音色，请在系统文字转语音设置安装；朗读与语音识别独立" else null
            voices
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            voices = emptyList()
            Log.w(TAG, "Read TTS voices failed", e)
            status.value = "读取系统音色失败，可重试；朗读与语音识别独立"
            emptyList()
        }
    }

    override fun setTtsVoice(context: Context, voiceId: String?) {
        selectedVoice = voiceId
        context.getSharedPreferences("mikova_voice", Context.MODE_PRIVATE).edit()
            .putString("system_voice_id", voiceId).apply()
    }

    override fun startListen(locale: VoiceEngine.LocaleCompat, listener: VoiceEngine.Listener) {
        val token = listenGeneration.next()
        main.post {
            if (!listenGeneration.isCurrent(token)) return@post
            destroyRecognizer()
            if (!isAvailable) {
                listener.onError(VoiceEngine.VoiceError.UNAVAILABLE, "这台设备不支持语音识别；系统朗读仍可单独使用")
                return@post
            }
            try {
                val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
                recognizer = sr
                fun emit(action: () -> Unit) {
                    if (recognizer === sr) listenGeneration.runIfCurrent(token, action)
                }
                fun finish(action: () -> Unit) {
                    emit { destroyRecognizer(); action() }
                }
                listener.onDirection(null)
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = emit { listener.onLevel(VoiceLevels.systemRmsToDb(rmsdB)) }
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onError(error: Int) = finish { listener.onError(mapError(error)) }
                    override fun onResults(results: Bundle?) = finish {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (text.isBlank()) listener.onError(VoiceEngine.VoiceError.NO_MATCH) else listener.onFinal(text)
                    }
                    override fun onPartialResults(partialResults: Bundle?) = emit {
                        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!text.isNullOrBlank()) listener.onPartial(text)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.tag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                })
            } catch (e: Exception) {
                destroyRecognizer()
                Log.w(TAG, "Start recognition failed", e)
                listenGeneration.runIfCurrent(token) {
                    listener.onError(if (e is SecurityException) VoiceEngine.VoiceError.PERMISSION else VoiceEngine.VoiceError.UNAVAILABLE, e.message)
                }
            }
        }
    }

    override fun stopListen() {
        val token = listenGeneration.current()
        main.post {
            listenGeneration.runIfCurrent(token) {
                try { recognizer?.stopListening() } catch (e: Exception) { Log.w(TAG, "Stop recognition failed", e) }
            }
        }
    }

    override fun cancel() {
        val cancelled = listenGeneration.next()
        main.post { listenGeneration.runIfCurrent(cancelled) { destroyRecognizer() } }
    }

    private fun destroyRecognizer() {
        val old = recognizer
        recognizer = null
        try { old?.cancel() } catch (e: Exception) { Log.w(TAG, "Cancel recognition failed", e) }
        try { old?.destroy() } catch (e: Exception) { Log.w(TAG, "Destroy recognition failed", e) }
    }

    /** 平台引擎没有"喂文件"的公开接口:明确不支持。 */
    override suspend fun transcribeFile(wavPath: String): String? = null

    override suspend fun synthesize(text: String, play: Boolean, voiceId: String?): ByteArray? {
        // Capture before dispatch/initialization, so stop during either phase invalidates this request.
        val token = synchronized(ttsLock) {
            val request = playback.beginRequest()
            pending?.complete(null)
            try { tts?.stop() } catch (e: Exception) { Log.w(TAG, "Stop previous synthesis failed", e) }
            request
        }
        try {
            return withContext(Dispatchers.IO) {
                synthesisMutex.withLock {
                    if (closed.get() || text.isBlank() || !playback.isCurrent(token)) return@withLock null
                    val engine = ensureTts() ?: return@withLock null
                    if (!playback.isCurrent(token)) return@withLock null
                    val choices = loadTtsVoices(appContext)
                    val chosen = voiceId ?: selectedVoice ?: choices.firstOrNull()?.id
                    if (chosen == null || choices.none { it.id == chosen }) {
                        status.value = "所选中文音色不可用，请重新选择；朗读与识别服务独立"
                        return@withLock null
                    }
                    val out = File(appContext.cacheDir, "tts_${UUID.randomUUID()}.wav")
                    val utterance = UUID.randomUUID().toString()
                    val result = CompletableDeferred<ByteArray?>()
                    try {
                        synchronized(ttsLock) {
                            playback.ifCurrent(token) {
                                pending = result
                                val voice = engine.voices.orEmpty().firstOrNull { "system:${it.name}" == chosen }
                                check(voice != null && engine.setVoice(voice) == TextToSpeech.SUCCESS) { "Cannot select TTS voice" }
                                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                    override fun onStart(utteranceId: String?) = Unit
                                    override fun onDone(utteranceId: String?) {
                                        if (utteranceId != utterance) return
                                        try {
                                            result.complete(if (playback.isCurrent(token) && out.isFile) out.readBytes() else null)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Read synthesized WAV failed", e)
                                            result.complete(null)
                                        } finally { out.delete() }
                                    }
                                    @Deprecated("Deprecated in Java")
                                    override fun onError(utteranceId: String?) {
                                        if (utteranceId == utterance) result.complete(null)
                                    }
                                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                                        if (utteranceId == utterance) result.complete(null)
                                    }
                                })
                                if (engine.synthesizeToFile(text, null, out, utterance) != TextToSpeech.SUCCESS) result.complete(null)
                            }
                        }
                        if (!playback.isCurrent(token)) return@withLock null
                        val bytes = withTimeoutOrNull(120_000) { result.await() }
                        currentCoroutineContext().ensureActive()
                        if (!playback.isCurrent(token)) return@withLock null
                        if (bytes == null) status.value = "系统合成失败或超时，可重试"
                        else {
                            val pcm = WavUtil.decode(bytes)
                            if (play) playback.play(pcm, token)
                            status.value = null
                        }
                        bytes
                    } finally {
                        synchronized(ttsLock) {
                            if (pending === result) {
                                pending = null
                                if (!result.isCompleted) engine.stop()
                            }
                        }
                        out.delete()
                    }
                }
            }
        } catch (e: CancellationException) {
            playback.cancel(token)
            throw e
        } catch (e: Exception) {
            playback.cancel(token)
            Log.w(TAG, "Synthesis failed", e)
            status.value = "系统合成失败：${e.message}"
            return null
        }
    }

    /** A timeout discards the instance; the next request gets a fresh initialization promise. */
    private suspend fun ensureTts(): TextToSpeech? = initMutex.withLock {
        if (closed.get()) return@withLock null
        val init = withContext(Dispatchers.Main) {
            synchronized(ttsLock) {
                ttsInit ?: CompletableDeferred<Boolean>().also { ready ->
                    ttsInit = ready
                    try { tts = TextToSpeech(appContext) { ready.complete(it == TextToSpeech.SUCCESS) } }
                    catch (e: Exception) { Log.w(TAG, "TTS init failed", e); ready.complete(false) }
                }
            }
        }
        val ready = withTimeoutOrNull(8_000) { init.await() } == true
        synchronized(ttsLock) {
            if (!ready || closed.get()) {
                tts?.shutdown()
                tts = null
                ttsInit = null
                voices = emptyList()
                status.value = "系统文字转语音服务未就绪，可重试；与语音识别可用性独立"
                null
            } else tts
        }
    }

    override fun stopSpeaking() {
        synchronized(ttsLock) {
            playback.stop()
            pending?.complete(null)
            pending = null
            try { tts?.stop() } catch (e: Exception) { Log.w(TAG, "Stop synthesis failed", e) }
        }
    }

    override fun shutdown() {
        closed.set(true)
        cancel()
        stopSpeaking()
        scope.cancel()
        synchronized(ttsLock) {
            ttsInit?.complete(false)
            tts?.shutdown()
            tts = null
            ttsInit = null
            voices = emptyList()
        }
    }

    private fun mapError(code: Int): VoiceEngine.VoiceError = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> VoiceEngine.VoiceError.NO_MATCH
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceEngine.VoiceError.SPEECH_TIMEOUT
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceEngine.VoiceError.NETWORK
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceEngine.VoiceError.BUSY
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceEngine.VoiceError.PERMISSION
        else -> VoiceEngine.VoiceError.UNKNOWN
    }

    private companion object {
        const val TAG = "PlatformVoice"
    }
}
