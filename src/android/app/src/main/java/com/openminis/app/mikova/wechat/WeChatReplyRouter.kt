package com.openminis.app.mikova.wechat

import com.openminis.app.mibot.queue.TaskKind
import com.openminis.app.mibot.queue.TaskRunQueue

/**
 * [mikova/wechat] 私聊文本 → 任务解析 → 回复 的简单路由。
 *
 * 微信消息桥（[WeChatBridgeService]）把 a11y 通知事件里识别到的私聊文本丢进来，
 * 这里做指令识别并给出要回执的话。指令约定（对用户透明，发到微信私聊里即触发）：
 *
 *   「任务: <目标>」 / 「任务：<目标>」 → 排入任务队列（[TaskRunQueue.enqueue]）
 *   「状态」 / 「状态?」              → 报告当前任务与队列长度
 *   「取消」 / 「取消任务」            → 取消当前 RUNNING 任务（自动取下一个）
 *
 * 其它文本返回 null：普通消息，桥接层不消费、不回执。解析是纯函数式的——副作用只
 * 发生在显式的队列调用里，便于单测；白名单过滤与「付款需确认」都不在这里做，它们
 * 属于桥接层（[WeChatBridgeService]）与支付卡（mikova.wallet.PaymentRequestCard）。
 */
object WeChatReplyRouter {

    /**
     * @param sender 消息发送方标识（联系人名或包名；白名单过滤在桥接层做，这里不关心）。
     * @param text   私聊文本正文（纯消息，去掉时间/系统前缀后的内容）。
     * @param queue  任务队列（入队 / 状态 / 取消的落点）。
     * @return 要回给发送方的话；null = 不回复（普通消息或空文本）。
     */
    fun route(sender: String, text: String, queue: TaskRunQueue): String? {
        val cmd = text.trim()
        return when {
            cmd.isEmpty() -> null

            taskGoal(cmd) != null -> {
                val goal = taskGoal(cmd)!!
                if (goal.isEmpty()) {
                    "任务指令缺目标，请写「任务: 要做的事」"
                } else {
                    val run = queue.enqueue(goal, TaskKind.CHAT)
                    "已排入任务队列：「$goal」（#${run.id.take(8)}，待办 ${queue.queue().size} 条）"
                }
            }

            cmd == "状态" || cmd == "状态?" -> {
                val cur = queue.current()
                val curLine = cur?.let { "正在做「${it.goal}」（${it.state}）" } ?: "空闲"
                "当前：$curLine · 待办：${queue.queue().size} 条"
            }

            cmd == "取消" || cmd == "取消任务" -> {
                val cur = queue.current()
                if (cur == null) "当前没有进行中的任务"
                else {
                    queue.cancel(cur.id)
                    "已取消「${cur.goal}」，自动接下一个"
                }
            }

            else -> null
        }
    }

    /** 判断一条文本是不是控制指令（任务/状态/取消），桥接层用于决定要不要单独回执。 */
    fun isCommand(text: String): Boolean {
        val cmd = text.trim()
        return taskGoal(cmd) != null || cmd == "状态" || cmd == "状态?" || cmd == "取消" || cmd == "取消任务"
    }

    /** 命中「任务:」/「任务：」前缀时返回目标文本（可能为空串），否则 null。 */
    private fun taskGoal(cmd: String): String? {
        val prefix = TASK_COLON.firstOrNull { cmd.startsWith(it) } ?: return null
        return cmd.removePrefix(prefix).trim()
    }

    private val TASK_COLON = listOf("任务:", "任务：")
}
