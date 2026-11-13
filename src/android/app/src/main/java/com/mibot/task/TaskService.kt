package com.mibot.task

import android.content.Context
import com.openminis.app.mibot.queue.TaskRunQueue

/**
 * 任务服务：Mika 的 TaskService.kt(1208 行)挂了一整棵依赖(聊天/确认/记忆/接管/agent),
 * cap 只用它的四条静态命令(交回/自己完成/取消/暂离)。这四条现在桥接到宿主侧的
 * [TaskRunQueue](Mibot 的任务执行队列),不再留空桩。
 *
 * 键映射([fork/mibot-merge],需对 Mika 原版 TaskService 复核):
 *  - Mika 的 [runKey](String) → [TaskRunQueue] 的任务标识(TaskRun.id,complete/cancel/hold
 *    都按它匹配);runKey 为 null 时落到当前 RUNNING 的那个任务。
 *  - Mika 的 [waitKey](Int) → [TaskRunQueue] 的 breakpoint(AgentLoop.interruptedStep),
 *    putAside 记断点用;handBack 不带断点。
 */
object TaskService {
    fun cancel(context: Context, runKey: String? = null) {
        val q = TaskRunQueue.get(context)
        val id = runKey ?: q.current()?.id ?: return
        q.cancel(id)
    }

    fun handBack(context: Context, waitKey: Int? = null, runKey: String? = null) {
        val q = TaskRunQueue.get(context)
        // 交回控制权:置 HELD、不记断点(waitKey 在此语义下不用)。
        if (runKey != null) q.hold(runKey) else q.suspendCurrent(null)
    }

    fun finishedMyself(context: Context, waitKey: Int? = null, runKey: String? = null) {
        val q = TaskRunQueue.get(context)
        // 自己完成:标 DONE、自动取下一个。runKey 为 null 时完成当前 RUNNING 的那个。
        val id = runKey ?: q.current()?.id ?: return
        q.complete(id)
    }

    fun putAside(context: Context, waitKey: Int? = null, runKey: String? = null) {
        val q = TaskRunQueue.get(context)
        // 暂离:置 HELD 并记断点(waitKey → breakpoint),从断点续跑。
        if (runKey != null) q.hold(runKey, breakpoint = waitKey) else q.suspendCurrent(waitKey)
    }
}
