package com.openminis.app.mikova.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [mikova/voice] sherpa-onnx(k2-fsa,MIT)离线中文 ASR + TTS 引擎。
 *
 * 设计要点 —— **编译期不依赖 sherpa AAR**:
 * 本文件对 `com.k2fsa.sherpa.onnx.*` 全部走反射([SherpaJni]),所以没把 AAR 放进 `libs/`、
 * 或 native so 加载失败时,这个类照样编译、照样实例化,只是 [isAvailable] 返回 false、
 * [startListen] 直接报 [VoiceEngine.VoiceError.UNAVAILABLE],由 [VoiceEngineFactory]
 * 一行落回 [PlatformVoiceEngine]。降级链因此保证"一行切换"。
 *
 * 反射目标(真实 sherpa-onnx Android Kotlin API,见 INTEGRATION.md 的 AAR 下载 URL):
 *  - 流式 ASR:`OnlineRecognizer(OnlineRecognizerConfig)` + `createStream()` /
 *    `stream.acceptWaveform(FloatArray, sampleRate)` / `isReady` / `decode` / `isEndpoint` /
 *    `reset` / `result.text`;配置只设 `modelConfig.tokens` 与 transducer 的 encoder/decoder/joiner。
 *  - 一次性文件转写:`OfflineRecognizer` + `recognize(samples, sampleRate).text`。
 *  - TTS:`OfflineTts(OfflineTtsConfig)` + `generate(text, sid, speed)` → `GeneratedAudio.samples/sampleRate`。
 * 任何一步反射失败都被 runCatching 吃掉并返回 null → 调用方降级,不崩。
 */
class SherpaVoiceEngine(private val appContext: Context) : VoiceEngine {

    override val id get() = VoiceEngineId.SHERPA
    override val displayName get() = "离线 sherpa-onnx"
    override val supportsPartialResults get() = true

    private val degradedFlag = AtomicBoolean(false)
    override val degraded get() = degradedFlag.get()

    @Volatile private var nativeLoaded = false
    @Volatile private var recognizer: Any? = null // OnlineRecognizer
    @Volatile private var stream: Any? = null     // OnlineRecognizerStream
    @Volatile private var tts: Any? = null        // OfflineTts
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var capturing = false
    @Volatile private var playback: AudioTrack? = null

    override val isAvailable: Boolean
        get() = nativeLoaded && VoiceModelManager.hasAsrModel(appContext)

    override fun markDegraded() { degradedFlag.set(true) }
    override fun clearDegraded() { degradedFlag.set(false) }

    /** 探测 native so + ASR 模型就绪;工厂据此决定给不给 sherpa。廉价(只查类和文件)。 */
    private fun ensureNative(): Boolean {
        if (nativeLoaded) return true
        nativeLoaded = SherpaJni.load()
        return nativeLoaded
    }

    @SuppressLint("MissingPermission")
    override fun startListen(locale: VoiceEngine.LocaleCompat, listener: VoiceEngine.Listener) {
        if (!ensureNative()) {
            listener.onError(VoiceEngine.VoiceError.UNAVAILABLE, "离线引擎未就绪(so 或模型缺失)")
            return
        }
        if (!VoiceModelManager.hasAsrModel(appContext)) {
            listener.onError(VoiceEngine.VoiceError.UNAVAILABLE, "未下载 ASR 模型")
            return
        }
        if (capturing) return

        // 每次听都新建 recognizer/stream,避免上次的端点/状态串进来;失败就地降级。
        val rec = SherpaJni.buildOnlineRecognizer(asrConfig(appContext))
        if (rec == null) {
            markDegraded()
            listener.onError(VoiceEngine.VoiceError.UNAVAILABLE, "初始化流式识别失败")
            return
        }
        recognizer = rec
        stream = SherpaJni.createStream(rec)

        capturing = true
        val sampleRate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            capturing = false
            listener.onError(VoiceEngine.VoiceError.UNAVAILABLE, "无法打开麦克风缓冲")
            return
        }
        val rec2 = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        } catch (t: Throwable) {
            capturing = false
            listener.onError(VoiceEngine.VoiceError.PERMISSION, t.message)
            return
        }
        recorder = rec2
        rec2.startRecording()

        Thread({
            captureLoop(rec2, sampleRate, listener)
        }, "sherpa-asr").start()
    }

    private fun captureLoop(rec: AudioRecord, sampleRate: Int, listener: VoiceEngine.Listener) {
        val chunk = 1_600 // 100ms of 16k mono
        val pcm = ShortArray(chunk)
        val floats = FloatArray(chunk)
        val st = stream ?: return
        while (capturing) {
            val n = rec.read(pcm, 0, chunk)
            if (n <= 0) continue
            var sumSq = 0.0
            for (i in 0 until n) {
                val f = pcm[i] / 32768f
                floats[i] = f
                sumSq += f.toDouble() * f
            }
            val rms = kotlin.math.sqrt(sumSq / n)
            listener.onLevel((20.0 * kotlin.math.log10(maxOf(rms, 1e-6))).toFloat())
            SherpaJni.acceptWaveform(st, floats.copyOf(n), sampleRate)
            val r = recognizer ?: break
            while (SherpaJni.isReady(r)) SherpaJni.decode(r)
            if (SherpaJni.isEndpoint(r)) {
                SherpaJni.decode(r)
                SherpaJni.reset(r)
            }
            val partial = SherpaJni.resultText(st)
            if (!partial.isNullOrBlank()) listener.onPartial(partial)
        }
    }

    override fun stopListen() {
        capturing = false
        val rec = recorder
        recorder = null
        try { rec?.stop() } catch (_: Throwable) {}
        runCatching { rec?.release() }
        // 收尾:送入 inputFinished,吐最终结果。
        val st = stream
        val r = recognizer
        if (st != null && r != null) {
            SherpaJni.inputFinished(st)
            var guard = 0
            while (SherpaJni.isReady(r) && guard++ < 64) SherpaJni.decode(r)
            val text = SherpaJni.resultText(st)
            onFinalText = text.orEmpty()
        }
        stream = null
        recognizer = null
        // 最终结果经 pendingFinal 回调(在调用线程之外不好直接回调,用一次性回调钩子)。
        finalSink?.invoke(onFinalText)
        finalSink = null
    }

    /** stopListen 吐出的最终文本,供 [stopListen] 的调用方经 [onFinalSink] 取。 */
    @Volatile private var onFinalText: String = ""
    @Volatile private var finalSink: ((String) -> Unit)? = null

    /**
     * 平台/离线两条引擎都在"回调线程"吐终态。这里给一个一次性的终态 sink,
     * 让 VoiceScreen 不必自己起协程等 stopListen 的返回。
     */
    fun onFinalSink(sink: (String) -> Unit) { finalSink = sink }

    override fun cancel() {
        capturing = false
        finalSink = null
        val rec = recorder
        recorder = null
        try { rec?.stop() } catch (_: Throwable) {}
        runCatching { rec?.release() }
        stream = null
        recognizer = null
    }

    override suspend fun transcribeFile(wavPath: String): String? = withContext(Dispatchers.IO) {
        if (!ensureNative()) return@withContext null
        val samples = WavUtil.readMono16k(wavPath) ?: return@withContext null
        val rec = SherpaJni.buildOfflineRecognizer(asrConfig(appContext)) ?: return@withContext null
        val text = SherpaJni.recognizeOffline(rec, samples, 16_000)
        runCatching { SherpaJni.releaseOffline(rec) }
        text
    }

    override suspend fun synthesize(text: String, play: Boolean): ByteArray? = withContext(Dispatchers.IO) {
        if (!ensureNative() || !VoiceModelManager.hasTtsModel(appContext)) return@withContext null
        val engine = tts ?: SherpaJni.buildOfflineTts(ttsConfig(appContext))?.also { tts = it } ?: return@withContext null
        val audio = SherpaJni.generate(engine, text) ?: return@withContext null
        val (samples, rate) = audio
        val wav = WavUtil.encode(samples, rate)
        if (play) playPcm(samples, rate)
        wav
    }

    private fun playPcm(samples: FloatArray, sampleRate: Int) {
        stopSpeaking()
        val bytes = WavUtil.pcmBytes(samples)
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bytes.size,
            AudioTrack.MODE_STATIC,
        )
        track.write(bytes, 0, bytes.size)
        playback = track
        track.play()
    }

    override fun stopSpeaking() {
        val t = playback
        playback = null
        runCatching { t?.stop() }
        runCatching { t?.release() }
    }

    override fun shutdown() {
        cancel()
        stopSpeaking()
        tts?.let { runCatching { SherpaJni.releaseTts(it) } }
        tts = null
    }

    /** sherpa OnlineRecognizerConfig(反射构造),字段名见 [SherpaJni]。 */
    private fun asrConfig(ctx: Context): Map<String, String> {
        val dir = VoiceModelManager.asrModelDir(ctx)
        return mapOf(
            "dir" to dir.absolutePath,
            "tokens" to File(dir, "tokens.txt").absolutePath,
            "encoder" to findFile(dir, "encoder", listOf(".onnx", ".int8.onnx")),
            "decoder" to findFile(dir, "decoder", listOf(".onnx", ".int8.onnx")),
            "joiner" to findFile(dir, "joiner", listOf(".onnx", ".int8.onnx")),
        )
    }

    private fun ttsConfig(ctx: Context): Map<String, String> {
        val dir = VoiceModelManager.ttsModelDir(ctx)
        return mapOf(
            "dir" to dir.absolutePath,
            "model" to findFile(dir, "", listOf(".onnx")),
            "tokens" to findFile(dir, "tokens", listOf(".txt")),
            "lexicon" to findFile(dir, "lexicon", listOf(".txt")),
        )
    }

    private fun findFile(dir: File, prefix: String, suffixes: List<String>): String {
        if (!dir.isDirectory) return ""
        val names = dir.list().orEmpty()
        for (suffix in suffixes) {
            val hit = names.firstOrNull { it.endsWith(suffix) && (prefix.isEmpty() || it.startsWith(prefix)) }
            if (hit != null) return File(dir, hit).absolutePath
        }
        return ""
    }

    /** 单例加载 native + 探测可用性(工厂只看类和文件,不建 recognizer,廉价)。 */
    companion object {
        private const val TAG = "SherpaVoice"

        /** native so 是否加载成功(不含模型是否下载)。 */
        @JvmStatic
        fun isUsable(context: Context): Boolean =
            SherpaJni.load() && VoiceModelManager.hasAsrModel(context.applicationContext)

        fun isNativeLoaded(): Boolean = SherpaJni.load()
        fun sdk(): Int = Build.VERSION.SDK_INT
    }
}

/**
 * sherpa-onnx 的反射调用层。**所有方法都可失败**(返回 null/false/空),调用方自行降级。
 * 目标类名/字段名是 sherpa-onnx Android Kotlin 包的真实形状(k2-fsa/sherpa-onnx,
 * `sherpa-onnx/kotlin-api`);AAR 一旦放进 libs/,这些反射就是普通调用的等价物。
 */
internal object SherpaJni {

    private const val TAG = "SherpaJni"

    @Volatile private var loaded = false
    private val lock = Any()

    /** System.loadLibrary + Class 探测;成功过一次就记住。 */
    fun load(): Boolean {
        if (loaded) return true
        synchronized(lock) {
            if (loaded) return true
            val ok = runCatching {
                System.loadLibrary("sherpa-onnx-jni")
                Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
                Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
            }.isSuccess
            if (ok) loaded = true else Log.w(TAG, "sherpa-onnx native not available; will fall back to platform engine")
            return ok
        }
    }

    // ---- 流式 ASR ----

    /** 用给定路径构造 OnlineRecognizer;任一步失败返回 null。 */
    fun buildOnlineRecognizer(cfg: Map<String, String>): Any? = runCatching {
        val pkg = "com.k2fsa.sherpa.onnx."
        val transducerCls = Class.forName(pkg + "OnlineTransducerModelConfig")
        val transducer = transducerCls.getDeclaredConstructor().newInstance()
        setField(transducer, "encoder", cfg["encoder"] ?: "")
        setField(transducer, "decoder", cfg["decoder"] ?: "")
        setField(transducer, "joiner", cfg["joiner"] ?: "")

        val modelCls = Class.forName(pkg + "OnlineModelConfig")
        val model = modelCls.getDeclaredConstructor().newInstance()
        setField(model, "transducer", transducer)
        setField(model, "tokens", cfg["tokens"] ?: "")
        setField(model, "numThreads", 2)
        setField(model, "debug", false)

        val featCls = Class.forName(pkg + "FeatureConfig")
        val feat = featCls.getDeclaredConstructor().newInstance()

        val configCls = Class.forName(pkg + "OnlineRecognizerConfig")
        val config = configCls.getDeclaredConstructor().newInstance()
        setField(config, "featConfig", feat)
        setField(config, "modelConfig", model)
        setField(config, "enableEndpoint", true)
        setField(config, "decodingMethod", "modified_beam_search")

        val recCls = Class.forName(pkg + "OnlineRecognizer")
        recCls.getDeclaredConstructor(configCls).newInstance(config)
    }.onFailure { Log.w(TAG, "buildOnlineRecognizer failed: ${it.message}") }.getOrNull()

    fun createStream(recognizer: Any): Any? = runCatching {
        recognizer.javaClass.getMethod("createStream").invoke(recognizer)
    }.getOrNull()

    fun acceptWaveform(stream: Any, samples: FloatArray, sampleRate: Int): Boolean = runCatching {
        stream.javaClass.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
            .invoke(stream, samples, sampleRate)
        true
    }.getOrDefault(false)

    fun inputFinished(stream: Any): Boolean = runCatching {
        stream.javaClass.getMethod("inputFinished").invoke(stream)
        true
    }.getOrDefault(false)

    fun isReady(recognizer: Any): Boolean = runCatching {
        recognizer.javaClass.getMethod("isReady").invoke(recognizer) as Boolean
    }.getOrDefault(false)

    fun decode(recognizer: Any): Boolean = runCatching {
        recognizer.javaClass.getMethod("decode").invoke(recognizer)
        true
    }.getOrDefault(false)

    fun isEndpoint(recognizer: Any): Boolean = runCatching {
        recognizer.javaClass.getMethod("isEndpoint").invoke(recognizer) as Boolean
    }.getOrDefault(false)

    fun reset(recognizer: Any): Boolean = runCatching {
        recognizer.javaClass.getMethod("reset").invoke(recognizer)
        true
    }.getOrDefault(false)

    /** 当前流的识别文本;还没有就空串。 */
    fun resultText(stream: Any): String? = runCatching {
        val result = stream.javaClass.getMethod("getResult").invoke(stream)
        result?.javaClass?.getMethod("getText")?.invoke(result) as? String
    }.getOrNull()

    // ---- 一次性文件转写 ----

    fun buildOfflineRecognizer(cfg: Map<String, String>): Any? = runCatching {
        val pkg = "com.k2fsa.sherpa.onnx."
        val transducerCls = Class.forName(pkg + "OfflineTransducerModelConfig")
        val transducer = transducerCls.getDeclaredConstructor().newInstance()
        setField(transducer, "encoder", cfg["encoder"] ?: "")
        setField(transducer, "decoder", cfg["decoder"] ?: "")
        setField(transducer, "joiner", cfg["joiner"] ?: "")
        val modelCls = Class.forName(pkg + "OfflineModelConfig")
        val model = modelCls.getDeclaredConstructor().newInstance()
        setField(model, "transducer", transducer)
        setField(model, "tokens", cfg["tokens"] ?: "")
        val configCls = Class.forName(pkg + "OfflineRecognizerConfig")
        val config = configCls.getDeclaredConstructor().newInstance()
        setField(config, "modelConfig", model)
        val recCls = Class.forName(pkg + "OfflineRecognizer")
        recCls.getDeclaredConstructor(configCls).newInstance(config)
    }.onFailure { Log.w(TAG, "buildOfflineRecognizer failed: ${it.message}") }.getOrNull()

    fun recognizeOffline(recognizer: Any, samples: FloatArray, sampleRate: Int): String? = runCatching {
        val result = recognizer.javaClass.getMethod("recognize", FloatArray::class.java, Int::class.javaPrimitiveType)
            .invoke(recognizer, samples, sampleRate)
        result?.javaClass?.getMethod("getText")?.invoke(result) as? String
    }.getOrNull()

    fun releaseOffline(recognizer: Any): Boolean = runCatching {
        recognizer.javaClass.getMethod("release").invoke(recognizer)
        true
    }.getOrDefault(false)

    // ---- TTS ----

    fun buildOfflineTts(cfg: Map<String, String>): Any? = runCatching {
        val pkg = "com.k2fsa.sherpa.onnx."
        val vitsCls = Class.forName(pkg + "OfflineTtsVitsModelConfig")
        val vits = vitsCls.getDeclaredConstructor().newInstance()
        setField(vits, "model", cfg["model"] ?: "")
        setField(vits, "tokens", cfg["tokens"] ?: "")
        setField(vits, "lexicon", cfg["lexicon"] ?: "")
        setField(vits, "dataDir", cfg["dir"] ?: "")

        val modelCls = Class.forName(pkg + "OfflineTtsModelConfig")
        val model = modelCls.getDeclaredConstructor().newInstance()
        setField(model, "vits", vits)
        setField(model, "numThreads", 2)

        val configCls = Class.forName(pkg + "OfflineTtsConfig")
        val config = configCls.getDeclaredConstructor().newInstance()
        setField(config, "modelConfig", model)

        val ttsCls = Class.forName(pkg + "OfflineTts")
        ttsCls.getDeclaredConstructor(configCls).newInstance(config)
    }.onFailure { Log.w(TAG, "buildOfflineTts failed: ${it.message}") }.getOrNull()

    /** 合成;返回 (samples, sampleRate)。失败返回 null。 */
    fun generate(tts: Any, text: String): Pair<FloatArray, Int>? = runCatching {
        val audio = tts.javaClass.getMethod("generate", String::class.java, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .invoke(tts, text, 0, 1.0f)
        if (audio == null) return null
        val samples = audio.javaClass.getField("samples").get(audio) as FloatArray
        val rate = audio.javaClass.getField("sampleRate").getInt(audio)
        samples to rate
    }.getOrNull()

    fun releaseTts(tts: Any): Boolean = runCatching {
        tts.javaClass.getMethod("release").invoke(tts)
        true
    }.getOrDefault(false)

    // ---- 反射小工具 ----

    private fun setField(target: Any, name: String, value: Any) {
        val cls = target.javaClass
        var c: Class<*>? = cls
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                f.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
    }
}

/** WAV(mono PCM16)读写:sherpa ASR 吃 16k mono float,TTS 吐的 float 也要封成 wav。 */
internal object WavUtil {

    private const val RATE = 16_000

    /** 读一个 wav 文件成 mono 16k 的 float 采样(任意声道/位深/采样率都重采样到 16k mono)。 */
    fun readMono16k(path: String): FloatArray? = runCatching {
        val bytes = File(path).readBytes()
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF") return null
        // 走 data 块(有的 wav 在 fmt 和 data 之间有 LIST/INFO)。
        var i = 12
        var data: ByteArray? = null
        var rate = RATE
        var channels = 1
        var bits = 16
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4)
            val size = leInt(bytes, i + 4)
            if (id == "fmt ") {
                channels = leShort(bytes, i + 10)
                rate = leInt(bytes, i + 12)
                bits = leShort(bytes, i + 22)
            } else if (id == "data") {
                data = bytes.copyOfRange(i + 8, minOf(i + 8 + size, bytes.size))
                break
            }
            i += 8 + size + (size and 1)
        }
        val raw = data ?: return null
        val mono = when (bits) {
            16 -> {
                val n = raw.size / 2 / channels
                FloatArray(n) { idx ->
                    var sum = 0f
                    for (c in 0 until channels) {
                        val off = (idx * channels + c) * 2
                        val s = (raw[off].toInt() and 0xFF) or (raw[off + 1].toInt() shl 8)
                        sum += s.toShort() / 32768f
                    }
                    sum / channels
                }
            }
            else -> null
        } ?: return null
        if (rate == RATE) mono else resample(mono, rate, RATE)
    }.getOrNull()

    /** 线性插值重采样(够 ASR 用;离线一次,精度不是瓶颈)。 */
    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from <= 0 || to <= 0 || input.isEmpty()) return input
        val ratio = from.toDouble() / to.toDouble()
        val outLen = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = pos - i0
            out[i] = input[i0] + (input[i1] - input[i0]) * frac.toFloat()
        }
        return out
    }

    /** float 采样 → 16-bit PCM 小端字节。 */
    fun pcmBytes(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** 把 float 采样封成 wav 字节(mono PCM16,[sampleRate])。 */
    fun encode(samples: FloatArray, sampleRate: Int): ByteArray {
        val pcm = pcmBytes(samples)
        val out = ByteArrayOutputStream(44 + pcm.size)
        val sr = sampleRate.coerceAtLeast(1)
        val byteRate = sr * 2
        fun le32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun ascii(s: String) { out.write(s.toByteArray(Charsets.US_ASCII)) }
        ascii("RIFF"); le32(36 + pcm.size); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(1); le32(sr); le32(byteRate); le16(2); le16(16)
        ascii("data"); le32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}
