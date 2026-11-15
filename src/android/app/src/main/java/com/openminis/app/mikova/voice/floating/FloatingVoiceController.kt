package com.openminis.app.mikova.voice.floating

import android.content.Context
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.mikova.voice.PlatformVoiceEngine
import com.openminis.app.mikova.voice.VoiceEngine
import com.openminis.app.mikova.voice.VoiceEngineId
import com.openminis.app.mikova.voice.VoiceEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [mikova/voice/floating] 浮层语音 —— 参照 deepdeep 的桌面悬浮语音对话窗。
 *
 * 单例持有整个浮层会话的状态：消息列表、识别引擎、聊天 sessionId、TTS。
 * 发消息走 [HeadlessChatRunner.prompt]（真实聊天回路），识别走 [PlatformVoiceEngine]，
 * 回复合成后直接朗读。与主语音页共用 VoiceEngine 抽象，但浮层优先平台引擎
 * （浮层要跟聊天同屏，sherpa 离线库本版不含，直接用系统识别最可靠）。
 */
object FloatingVoiceController {

    /** 一条浮层消息：role=user 右对齐气泡，role=assistant 左对齐卡片。 */
    data class Message(
        val role: String, // "user" | "assistant" | "system"
        val text: String,
        val time: Long = System.currentTimeMillis(),
    )

    private lateinit var scope: CoroutineScope
    private var engine: VoiceEngine? = null
    private var appContext: Context? = null
    private var sessionId: String? = null
    private var initialized = false

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _modelPath = MutableStateFlow("")
    val modelPath: StateFlow<String> = _modelPath

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    /** 首次显示浮层时初始化引擎与会话。 */
    fun ensureInit(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        appContext = app
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        engine = VoiceEngineFactory.build(VoiceEngineId.PLATFORM, app)
        _modelPath.value = describeModel(app)
        seedGreeting()
        scope.launch { ensureSession() }
    }

    private fun seedGreeting() {
        if (_messages.value.isNotEmpty()) return
        _messages.value = listOf(
            Message("system", "友好问候 · 浮层语音已就绪"),
            Message("assistant", "你好，我是浮层语音助手。点下方麦克风说话，或直接输入问题。"),
        )
    }

    private suspend fun ensureSession() {
        val ctx = appContext ?: return
        val sid = sessionId ?: HeadlessChatRunner.ensureSession(ctx).also { sessionId = it }
        if (_modelPath.value.isBlank()) _modelPath.value = describeModel(ctx)
    }

    /** 输入框文字变化（UI 双向绑定）。 */
    fun setInput(text: String) { _input.value = text }

    /** 发送一条用户消息，走真实聊天回路，流式回复（字幕双工），完成后朗读。 */
    fun send() {
        val text = _input.value.trim()
        if (text.isEmpty() || _thinking.value) return
        _input.value = ""
        _error.value = null
        _messages.value = _messages.value + Message("user", text)
        _thinking.value = true
        val ctx = appContext ?: run { _thinking.value = false; return }
        scope.launch {
            val sid = sessionId ?: HeadlessChatRunner.ensureSession(ctx).also { sessionId = it }
            // 1) 立即提交（不等待），让流式增量实时上屏
            try {
                HeadlessChatRunner.prompt(
                    context = ctx, sessionId = sid, text = text, wait = false, timeoutMs = 120_000L,
                )
            } catch (t: Throwable) {
                _thinking.value = false
                _error.value = "请求失败：${t.message ?: t.javaClass.simpleName}"
                return@launch
            }
            // 2) 订阅流式增量 → 实时更新最后一个 assistant 气泡（字幕）
            val vm = runCatching { HeadlessChatRunner.viewModel(ctx, sid) }.getOrNull()
            val deltaJob = if (vm != null) scope.launch {
                vm.streamingById.collect { byId ->
                    val delta = byId[sid] ?: return@collect
                    val partial = delta.content
                    if (partial.isNotBlank()) {
                        // 更新或追加最后一条 assistant 气泡为实时字幕
                        _messages.value = updateLastAssistant(partial)
                    }
                }
            } else null
            // 3) 等流结束，取最终文本朗读
            var finalText: String? = null
            val doneJob = if (vm != null) scope.launch {
                // 等流开始再等结束（避免提交瞬间就读到 false）
                withTimeoutOrNull(5000L) { vm.isStreaming.first { it } }
                withTimeoutOrNull(120_000L) { vm.isStreaming.first { !it } }
                finalText = latestAssistantText()
            } else null
            doneJob?.join()
            deltaJob?.cancel()
            _thinking.value = false
            val reply = finalText ?: latestAssistantText()
            if (reply.isNullOrBlank()) {
                _error.value = "没有收到回复，请稍后重试"
            } else {
                _messages.value = updateLastAssistant(reply)
                speak(reply)
            }
        }
    }

    /** 用流式增量替换/追加最后一条 assistant 气泡。 */
    private fun updateLastAssistant(partial: String): List<Message> {
        val msgs = _messages.value
        val last = msgs.lastOrNull()
        return if (last != null && last.role == "assistant" && last.text != partial) {
            msgs.dropLast(1) + last.copy(text = partial)
        } else if (last != null && last.role == "assistant") {
            msgs
        } else {
            msgs + Message("assistant", partial)
        }
    }

    /** 从消息列表取最后一条 assistant 的完整文本。 */
    private fun latestAssistantText(): String? =
        _messages.value.lastOrNull { it.role == "assistant" }?.text?.takeIf { it.isNotBlank() }

    /** 识别回调：听写中 → 实时进输入框；终态 → 自动发送。 */
    private fun onRecognized(final: String, isFinal: Boolean) {
        if (final.isBlank()) return
        _input.value = final
        _listening.value = false
        if (isFinal) send()
    }

    /** 开始聆听。系统识别服务不可用时通过 [VoiceEngine.Listener.onError] 上报。 */
    fun startListen() {
        val eng = engine ?: return
        if (_listening.value) { eng.stopListen(); _listening.value = false; return }
        _error.value = null
        val listener = object : VoiceEngine.Listener {
            override fun onPartial(text: String) { onRecognized(text, false) }
            override fun onFinal(text: String) { onRecognized(text, true) }
            override fun onError(code: VoiceEngine.VoiceError, message: String?) {
                _listening.value = false
                _error.value = message ?: when (code) {
                    VoiceEngine.VoiceError.NO_MATCH -> "没有听清，请重试"
                    VoiceEngine.VoiceError.PERMISSION -> "缺少麦克风权限"
                    VoiceEngine.VoiceError.UNAVAILABLE -> "语音识别不可用：${message ?: "未设置系统识别服务"}"
                    else -> "语音识别失败，请重试"
                }
            }
            override fun onLevel(db: Float) {}
        }
        if (!eng.isAvailable) {
            _error.value = "语音识别不可用：${com.openminis.app.mikova.voice.PlatformVoiceEngine.recognitionBlockers(appContext!!).firstOrNull() ?: "未设置系统识别服务"}"
            return
        }
        _listening.value = true
        try {
            eng.startListen(VoiceEngine.LocaleCompat("zh-CN"), listener)
        } catch (t: Throwable) {
            _listening.value = false
            _error.value = "无法打开麦克风：${t.message ?: t.javaClass.simpleName}"
        }
    }

    /** 朗读一段文字（当前引擎的 TTS）。 */
    fun speak(text: String) {
        val eng = engine ?: return
        if (text.isBlank()) return
        scope.launch {
            _speaking.value = true
            try { eng.synthesize(text, play = true) } catch (_: Throwable) {}
            _speaking.value = false
        }
    }

    /** 停止朗读。 */
    fun stopSpeaking() { engine?.stopSpeaking(); _speaking.value = false }

    /** 清除错误条。 */
    fun clearError() { _error.value = null }

    /** 关闭浮层时释放会话相关资源（不杀引擎，进程级复用）。 */
    fun onOverlayClosed() { engine?.stopSpeaking(); _listening.value = false }

    private fun describeModel(ctx: Context): String {
        return try {
            val app = ctx.applicationContext as? com.openminis.app.MinisApp
            val entry = app?.providerRepository?.allVisibleEntries()?.firstOrNull()
            val model = entry?.baseModel?.id
            val provider = entry?.baseModel?.displayName ?: entry?.providerInstanceId
            listOfNotNull(provider, model).joinToString(" · ").ifBlank { "未配置模型" }
        } catch (_: Throwable) { "未配置模型" }
    }
}
