package com.openminis.app.mikova.voice

/**
 * [mikova/voice] 语音引擎抽象 —— 把 ASR(听)和 TTS(说)统一到一两个方法里,
 * 让 [SherpaVoiceEngine](离线 sherpa-onnx)和 [PlatformVoiceEngine](系统 SpeechRecognizer / TextToSpeech)
 * 在调用方看来完全一样。降级链就是换一个实现:`VoiceEngineFactory` 先试 sherpa,
 * native so 加载失败或模型缺失时 `fallback()` 一行切到平台引擎。
 *
 * 与本体 `com.openminis.app.speech.SpeechRecognitionEngine`(system / provider 云识别)是平行的:
 * 那条接口管"流式听写 + 云一次性转写";这条接口额外带上 [synthesize](离线 TTS),并把自己当成
 * mikova 语音页面的唯一入口。若日后要并入本体选择器,可让 [SherpaVoiceEngine] 额外实现
 * `SpeechRecognitionEngine`(它已经满足 start/stop/cancel/Listener 的形状)。
 */
data class TtsVoiceChoice(
    val id: String,
    val label: String,
    val detail: String,
    val online: Boolean,
)

interface VoiceEngine {
    /** 稳定标识,存偏好用("sherpa" / "platform")。 */
    val id: VoiceEngineId

    /** 选择器里显示的名字。 */
    val displayName: String

    /** 廉价可用性检查(so 是否加载、模型是否就绪);true 不保证 start 一定成功,错误走 [Listener.onError]。 */
    val isAvailable: Boolean

    /** ASR 是否吐中间结果(实时文本流)。sherpa 流式为 true,平台带 partialResults 也为 true。 */
    val supportsPartialResults: Boolean

    /**
     * 开始听。引擎自己抓音频(RECORD_AUDIO 须已授权),直到 [stopListen] 或终态错误为止,
     * 期间回调 [Listener]。
     */
    fun startListen(locale: LocaleCompat, listener: Listener)

    /** 停止抓音;最终结果(或错误)仍经 [Listener] 送达。 */
    fun stopListen()

    /** 放弃这次听;之后不再回调。 */
    fun cancel()

    /** Stop the active modality before starting the other one. */
    fun interrupt() { stopSpeaking(); cancel() }

    /**
     * 转写一段已录好的音频文件(wav,mono PCM16)。sherpa 能离线转;
     * 平台引擎没有"喂文件"的公开接口,默认报不支持。
     */
    suspend fun transcribeFile(wavPath: String): String? = null

    /**
     * 把文字合成成语音(wav 字节)并(默认)直接播。返回本次合成的 wav 字节,
     * 便于上层缓存/分享;合成不了返回 null。
     */
    suspend fun synthesize(text: String, play: Boolean = true, voiceId: String? = null): ByteArray?

    /** Current bundled speaker choices; legacy/platform implementations may expose none. */
    fun ttsVoices(context: android.content.Context): List<TtsVoiceChoice> = emptyList()

    /** Select a concrete local speaker id; null selects the engine default. */
    fun setTtsVoice(context: android.content.Context, voiceId: String?) = Unit

    /** Current playback lifecycle state and normalized output level. */
    val speaking: kotlinx.coroutines.flow.StateFlow<Boolean>?
        get() = null
    val outputLevel: kotlinx.coroutines.flow.StateFlow<Float>?
        get() = null

    /** Stop current playback immediately. */
    fun stopSpeaking()

    /** 释放底层资源(so / recognizer / tts / recognizer 实例)。释放后引擎不再可用。 */
    fun shutdown()

    /** 是否正被降级标记过(引擎坏了一次就先当不可用,见 [markDegraded])。 */
    val degraded: Boolean get() = false

    /** 标记不可用(上次 start 终态失败后由管理器调)。 */
    fun markDegraded() {}

    /** 解除降级(用户重新进入语音页或选中本引擎时调)。 */
    fun clearDegraded() {}

    interface Listener {
        /** 增量中间结果;仅 [supportsPartialResults] 时触发。 */
        fun onPartial(text: String)

        /** 终态 —— 抓音干净结束。 */
        fun onFinal(text: String)

        /** 终态 —— 失败。 */
        fun onError(code: VoiceError, message: String? = null)

        /** 实时输入音量(dB)，用于声场动画。 */
        fun onLevel(db: Float) {}

        /** 有硬件双声道采集时给出左右能量偏差 [-1, 1]；无数据时为 null。 */
        fun onDirection(horizontal: Float?) {}
    }

    /** BCP-47 语言标签的轻量包装(避免各处直接拼 Locale)。 */
    data class LocaleCompat(val tag: String = "zh-CN") {
        override fun toString(): String = tag
    }

    enum class VoiceError { NO_MATCH, SPEECH_TIMEOUT, NETWORK, BUSY, PERMISSION, UNAVAILABLE, UNKNOWN }
}

/** 引擎选择:sherpa 优先,平台兜底。存偏好里的是这个枚举的名字。 */
enum class VoiceEngineId { SHERPA, PLATFORM }

/**
 * 引擎工厂:按偏好建 sherpa 或平台引擎,并在 sherpa 不可用时**自动**落回平台。
 * 降级链就是 [build] 里的一次 `?: fallback()`。调用方只在 [fallbackNotice] 非 null 时提示一句
 * "已用平台识别兜底",其余照常。
 */
object VoiceEngineFactory {

    /** 降级原因(只在真的落回平台时非 null);供状态 chip / 诊断显示。 */
    @Volatile
    var fallbackNotice: String? = null
        private set

    /**
     * 建一个可用引擎。流程:sherpa 可用就给 sherpa;否则平台;平台也不可用就给一个
     * 永远不可用的占位(界面显示"这台设备不支持语音")。
     */
    fun build(prefer: VoiceEngineId, context: android.content.Context): VoiceEngine {
        val app = context.applicationContext
        val sherpa = prefer == VoiceEngineId.SHERPA
        val native = sherpa && SherpaVoiceEngine.isUsable(app)
        if (native) {
            fallbackNotice = null
            return SherpaVoiceEngine(app)
        }
        val platform = PlatformVoiceEngine(app)
        fallbackNotice = if (prefer == VoiceEngineId.SHERPA) {
            "离线引擎不可用,已用系统识别兜底"
        } else {
            null
        }
        // 平台也不行时仍返回平台对象:它的 isAvailable=false,界面据此显示占位文案。
        return platform
    }

    /** 仅当偏好 sherpa 且 native 不就绪时才真的降级。 */
    fun isFallingBack(prefer: VoiceEngineId, context: android.content.Context): Boolean =
        prefer == VoiceEngineId.SHERPA && !SherpaVoiceEngine.isUsable(context.applicationContext)
}

/**
 * 已安装模型的一行信息(模型管理页列表用)。sherpa-onnx 的模型是"配置 + onnx 权重",
 * 全部落在 `filesDir/models/<id>/` 下。
 */
data class VoiceModelInfo(
    val kind: VoiceModelKind,
    val id: String,
    val filename: String,
    val url: String,
    val approxBytes: Long,
    val title: String,
    val subtitle: String,
)

enum class VoiceModelKind { ASR, TTS }
