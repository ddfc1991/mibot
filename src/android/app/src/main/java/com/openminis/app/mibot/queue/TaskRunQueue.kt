package com.openminis.app.mibot.queue

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * [destiny/task-run-queue] 任务执行队列。
 *
 * 解决「1 个任务接 1 个任务，上面那个就不做了」：新消息不再 cancel 当前任务，
 * 而是把它 suspend（记断点）入 HELD，新任务 enqueue 排队；当前做完自动取下一个，
 * 从断点续（复用 AgentLoop 的 Held / RunProgress.Replay / interruptedStep）。
 *
 * 队列状态持久化到 SharedPreferences（同 ScheduledTaskStore 的做法），进程重启不丢。
 *
 * 状态机：QUEUED → RUNNING → (HELD ⇄ RUNNING) → DONE / FAILED
 */
enum class TaskRunState { QUEUED, RUNNING, HELD, DONE, FAILED }

enum class TaskKind { CHAT, TOOL, SCHEDULED, VOICE }

data class TaskRun(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val kind: TaskKind = TaskKind.CHAT,
    val priority: Int = 0,             // >0 插到同优先组前面（用户插队）
    val state: TaskRunState = TaskRunState.QUEUED,
    val breakpoint: Int? = null,       // AgentLoop.interruptedStep 断点
    val runId: String? = null,         // AgentLoop runId（恢复用）
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val doneAt: Long? = null,
)

/**
 * FIFO 调度器。current() 是正在跑的；enqueue 排队（priority>0 插队）；
 * suspendCurrent 把当前任务置 HELD 记断点（不取消）；complete 做完自动取下一个。
 */
class TaskRunQueue(private val prefs: android.content.SharedPreferences) {

    private val _runs = MutableStateFlow<List<TaskRun>>(emptyList())
    val runs: StateFlow<List<TaskRun>> = _runs.asStateFlow()

    init { _runs.value = load() }

    fun current(): TaskRun? = _runs.value.firstOrNull { it.state == TaskRunState.RUNNING }

    fun queue(): List<TaskRun> = _runs.value.filter { it.state in setOf(TaskRunState.QUEUED, TaskRunState.HELD) }

    /** 入队。priority>0 插到同优先组前面，否则排队尾。 */
    @Synchronized
    fun enqueue(goal: String, kind: TaskKind = TaskKind.CHAT, priority: Int = 0, breakpoint: Int? = null): TaskRun {
        val run = TaskRun(goal = goal, kind = kind, priority = priority, breakpoint = breakpoint)
        val list = _runs.value.toMutableList()
        val insertAt = if (priority > 0) {
            // 插到第一个 priority <= this.priority 之前（同优先组内 FIFO）
            list.indexOfFirst { it.state != TaskRunState.DONE && it.state != TaskRunState.FAILED && it.priority <= priority }
                .let { if (it < 0) list.size else it }
        } else list.size
        list.add(insertAt, run)
        commit(list)
        return run
    }

    /** 取下一个排队任务置为 RUNNING，返回它；队列空返回 null。 */
    @Synchronized
    fun startNext(): TaskRun? {
        val next = _runs.value.firstOrNull { it.state == TaskRunState.QUEUED } ?: return null
        val list = _runs.value.map { if (it.id == next.id) it.copy(state = TaskRunState.RUNNING, startedAt = System.currentTimeMillis()) else it }
        commit(list)
        return next.copy(state = TaskRunState.RUNNING)
    }

    /** 新消息来了：当前任务不取消，置 HELD 记断点。 */
    @Synchronized
    fun suspendCurrent(breakpoint: Int?): TaskRun? {
        val cur = current() ?: return null
        val list = _runs.value.map { if (it.id == cur.id) it.copy(state = TaskRunState.HELD, breakpoint = breakpoint) else it }
        commit(list)
        return cur.copy(state = TaskRunState.HELD, breakpoint = breakpoint)
    }

    /** 从断点续（AgentLoop.Replay(breakpoint)）。 */
    @Synchronized
    fun resume(runId: String): TaskRun? {
        val run = _runs.value.firstOrNull { it.id == runId && it.state == TaskRunState.HELD } ?: return null
        val list = _runs.value.map { if (it.id == runId) it.copy(state = TaskRunState.RUNNING, startedAt = System.currentTimeMillis()) else it }
        commit(list)
        return run.copy(state = TaskRunState.RUNNING)
    }

    /** 做完/失败：标终态，返回下一个可执行的（自动取下一个）。 */
    @Synchronized
    fun complete(runId: String, failed: Boolean = false): TaskRun? {
        val fin = if (failed) TaskRunState.FAILED else TaskRunState.DONE
        val list = _runs.value.map { if (it.id == runId) it.copy(state = fin, doneAt = System.currentTimeMillis()) else it }
        commit(list)
        return startNext()
    }

    /**
     * 取消一个任务（Mika TaskService.cancel 的落点）：标 FAILED、自动取下一个。
     * 与 [complete] 的区别是语义——cancel 是「这个任务被放弃/终止」，不是「做完了」；
     * 状态机里两者都进 FAILED 终态（队列不留 CANCELLED），但 cancel 不依赖任务当前在跑。
     */
    @Synchronized
    fun cancel(runId: String): TaskRun? {
        val list = _runs.value.map { if (it.id == runId && it.state != TaskRunState.DONE && it.state != TaskRunState.FAILED) it.copy(state = TaskRunState.FAILED, doneAt = System.currentTimeMillis()) else it }
        commit(list)
        return startNext()
    }

    /**
     * 把某个任务置 HELD（Mika TaskService.handBack / putAside 的落点）。
     * [breakpoint] 为 null 是「交回/暂离」(handBack)，带上断点是「记断点续跑」(putAside)。
     * 与 [suspendCurrent] 的区别：suspendCurrent 只作用于当前 RUNNING 的那个；
     * 这里按 [runId] 指定（runKey 映射到 TaskRun.id）。
     */
    @Synchronized
    fun hold(runId: String, breakpoint: Int? = null): TaskRun? {
        val run = _runs.value.firstOrNull { it.id == runId && it.state != TaskRunState.DONE && it.state != TaskRunState.FAILED } ?: return null
        val list = _runs.value.map { if (it.id == runId) it.copy(state = TaskRunState.HELD, breakpoint = breakpoint) else it }
        commit(list)
        return run.copy(state = TaskRunState.HELD, breakpoint = breakpoint)
    }

    /** 用户插队：把某个 HELD/QUEUED 任务提到最前并立即跑。 */
    @Synchronized
    fun promote(runId: String): TaskRun? {
        val run = _runs.value.firstOrNull { it.id == runId && it.state != TaskRunState.DONE && it.state != TaskRunState.FAILED } ?: return null
        val rest = _runs.value.filter { it.id != runId }
        val list = listOf(run.copy(priority = Int.MAX_VALUE, state = TaskRunState.QUEUED)) + rest
        commit(list)
        return startNext()
    }

    /** 清掉终态条目，保持队列干净。 */
    @Synchronized
    fun prune() {
        val live = _runs.value.filter { it.state == TaskRunState.QUEUED || it.state == TaskRunState.RUNNING || it.state == TaskRunState.HELD }
        commit(live)
    }

    // ---- 持久化（SharedPreferences，同 ScheduledTaskStore 做法） ----
    private fun commit(list: List<TaskRun>) {
        _runs.value = list
        prefs.edit().putString(KEY, encode(list)).apply()
    }

    private fun load(): List<TaskRun> = runCatching { decode(prefs.getString(KEY, null)) }.getOrElse { emptyList() }

    private fun encode(list: List<TaskRun>): String {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("goal", r.goal); put("kind", r.kind.name)
                put("priority", r.priority); put("state", r.state.name)
                put("breakpoint", r.breakpoint ?: JSONObject.NULL); put("runId", r.runId ?: JSONObject.NULL)
                put("createdAt", r.createdAt); put("startedAt", r.startedAt ?: JSONObject.NULL); put("doneAt", r.doneAt ?: JSONObject.NULL)
            })
        }
        return arr.toString()
    }

    private fun decode(s: String?): List<TaskRun> {
        if (s.isNullOrBlank()) return emptyList()
        val arr = JSONArray(s)
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                TaskRun(
                    id = o.getString("id"), goal = o.getString("goal"),
                    kind = runCatching { TaskKind.valueOf(o.getString("kind")) }.getOrDefault(TaskKind.CHAT),
                    priority = o.optInt("priority", 0),
                    state = runCatching { TaskRunState.valueOf(o.getString("state")) }.getOrDefault(TaskRunState.QUEUED),
                    breakpoint = if (o.isNull("breakpoint")) null else o.getInt("breakpoint"),
                    runId = if (o.isNull("runId")) null else o.getString("runId"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    startedAt = if (o.isNull("startedAt")) null else o.getLong("startedAt"),
                    doneAt = if (o.isNull("doneAt")) null else o.getLong("doneAt"),
                )
            }.getOrNull()
        }
    }

    companion object {
        private const val KEY = "task_run_queue"
        fun get(context: Context): TaskRunQueue = TaskRunQueue(
            context.getSharedPreferences("mibot_queue", Context.MODE_PRIVATE)
        )
    }
}