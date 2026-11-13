package com.openminis.app.mikova.wechat

import android.content.Context
import com.mibot.cap.phone.a11y.ScreenAccessibility
import com.openminis.app.mibot.queue.TaskRunQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * [mikova/wechat] 微信消息 → 任务桥。
 *
 * 复用 Mika 的无障碍事件管道（[ScreenAccessibility] 的 [ScreenAccessibility.notificationEvents]
 * 热流，companion 级跨实例保留）：微信 App（com.tencent.mm）的通知事件被识别为「新消息」，
 * 交给 [WeChatReplyRouter] 解析成任务/状态/取消指令，落到 [TaskRunQueue]（P0 已桥好的
 * 任务队列，与 Mibot 任务页同一张表）。
 *
 * 只读桥：本服务不做任何写回微信的动作（回复文本通过 [WeChatReplyRouter] 算出后返回给
 * 调用方；真正把文本注入微信聊天框需要 a11y 文本注入，见 INTEGRATION.md 的不确定点）。
 *
 * 启动方式：宿主在 Application onCreate 里调用 [start]（传入一个长生命周期
 * CoroutineScope，如 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`），关闭时 [stop]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
object WeChatBridgeService {

    /** 微信主包名（a11y 事件与通知按它过滤）。 */
    const val WECHAT_PACKAGE = "com.tencent.mm"

    private var prefs: WeChatPrefs? = null
    private var job: Job? = null
    private val activity = mutableListOf<String>()
    private var onLog: ((String) -> Unit)? = null

    /** 桥是否在收集（服务已 start 且 job 活跃）。 */
    val active: Boolean get() = job?.isActive == true

    /** 最近活动（含时间戳），供绑定页展示。 */
    fun activityLog(): List<String> = synchronized(activity) { activity.toList() }

    /** 绑定页可挂一个日志监听，实时刷「最近活动」列表。 */
    fun setLogListener(l: ((String) -> Unit)?) {
        onLog = l
    }

    /** 开始收集微信通知事件。重复调用无害（job 已活跃则直接返回）。 */
    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        prefs = WeChatPrefs(app)
        job = scope.launch {
            ScreenAccessibility.instance
                .flatMapLatest { svc -> svc?.notificationEvents() ?: emptyFlow() }
                .filter { it.packageName == WECHAT_PACKAGE }
                .collect { ev ->
                    val text = ev.text?.trim().orEmpty()
                    if (text.isEmpty()) return@collect
                    onMessage(app, text)
                }
        }
    }

    /** 停止收集。 */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * 处理一条微信私聊文本：总开关 → 白名单 → 路由。
     * 返回要回执的话（null = 不回）。路由里的任务入队/状态/取消副作用在
     * [WeChatReplyRouter.route] 内完成。
     */
    fun onMessage(context: Context, text: String): String? {
        val p = prefs ?: WeChatPrefs(context.applicationContext).also { prefs = it }
        if (!p.enabled) return null
        // 白名单为空 = 接受所有联系人；非空时只处理名单里的人。
        val allowed = p.whitelist.isEmpty() || p.whitelist.any { text.contains(it) }
        if (!allowed) return null
        val queue = TaskRunQueue.get(context.applicationContext)
        val reply = WeChatReplyRouter.route(WECHAT_PACKAGE, text, queue)
        if (reply != null) log("↩ $reply")
        return reply
    }

    private fun log(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        synchronized(activity) {
            activity.add("$stamp $line")
            if (activity.size > 50) activity.removeAt(0)
        }
        onLog?.invoke(line)
    }
}

/**
 * [mikova/wechat] 微信桥的持久化设置：总开关 + 白名单联系人。
 * SharedPreferences 存储（与 TaskRunQueue 同款），进程重启不丢。
 */
class WeChatPrefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("mikova_wechat", Context.MODE_PRIVATE)

    /** 是否接收微信消息并派单。 */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }

    /** 白名单联系人名（空集 = 接收所有）。 */
    var whitelist: Set<String>
        get() = sp.getStringSet("whitelist", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("whitelist", v).apply() }
}
