package com.openminis.app.mikova.wallet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.mibot.design.ChipKind
import com.openminis.app.mibot.design.Glyph
import com.openminis.app.mibot.design.LineIcons
import com.openminis.app.mibot.design.MibotButton
import com.openminis.app.mibot.design.MibotCard
import com.openminis.app.mibot.design.MibotIcon
import com.openminis.app.mibot.design.MibotSpace
import com.openminis.app.mibot.design.MibotTokens
import com.openminis.app.mibot.design.MibotType
import com.openminis.app.mibot.design.StatusChip
import com.openminis.app.mibot.design.t
import com.openminis.app.mibot.queue.TaskRun
import com.openminis.app.mibot.queue.TaskRunQueue

/**
 * [mikova/wallet] 一笔支付请求：agent 要花钱时由宿主弹这张卡。
 *
 * 原则是「只问不付」：AI 永远不代付。卡片只把金额与用途展示出来，等你本人确认；
 * 确认后仍需你在微信/支付 App 里自己完成付款动作，AI 只负责把对应任务放行
 * （resume）或取消（cancel），对应任务队列的 HELD 状态。
 */
data class PaymentRequest(
    /** 金额（分）。 */
    val amountCents: Long,
    /** 用途说明（要买什么、为什么）。 */
    val purpose: String,
    /** 谁在要钱（agent / 任务名 / 模块）。 */
    val from: String,
    /** 对应任务队列里的 runId；null = 落到当前 RUNNING 任务。 */
    val runId: String? = null,
)

/**
 * [mikova/wallet] 支付请求 → 任务队列的桥。
 *
 * 宿主在弹出 [PaymentRequestCard] 前调用 [holdForPayment] 把当前任务置 HELD
 * （记断点，不取消）；用户确认/拒绝后卡片调 [settle]：确认=resume 从断点续，
 * 拒绝=cancel 标终态并自动取下一个。
 */
object PaymentGuard {

    /** 挂起任务（置 HELD）等用户确认付款。返回新的 HELD run；无任务可挂返回 null。 */
    fun holdForPayment(runId: String?, queue: TaskRunQueue): TaskRun? {
        val id = runId ?: queue.current()?.id ?: return null
        return queue.hold(id)
    }

    /** 用户就付款做了决定：approved=true 从断点续跑，false 取消任务。 */
    fun settle(runId: String?, queue: TaskRunQueue, approved: Boolean): TaskRun? {
        val id = runId ?: queue.current()?.id ?: return null
        return if (approved) queue.resume(id) else queue.cancel(id)
    }
}

/**
 * 支付请求卡（glass 卡片）。只展示与询问，不做任何代付；确认/拒绝回写任务队列。
 *
 * @param request   要问的这笔钱。
 * @param queue     任务队列（settle 的落点，对应 HELD 状态）。
 * @param onSettled 结算回调（携带放行/取消后的任务；可用于刷新 UI）。
 */
@Composable
fun PaymentRequestCard(
    request: PaymentRequest,
    queue: TaskRunQueue,
    modifier: Modifier = Modifier,
    onSettled: (TaskRun?) -> Unit = {},
) {
    MibotCard(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Glyph(LineIcons.Bell, size = MibotIcon.Standard, tint = MibotTokens.warnColor())
            Spacer(Modifier.width(MibotSpace.S))
            Column(Modifier.weight(1f)) {
                Text(t("支付请求", "Payment request"), fontSize = MibotType.Body, fontWeight = MibotType.Semi, color = MaterialTheme.colorScheme.onSurface)
                Text(t("来自 $request.from", "from ${request.from}"), fontSize = MibotType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusChip(t("只问不付", "Ask-only"), ChipKind.WARN)
        }
        Spacer(Modifier.height(MibotSpace.M))
        Text(formatCents(request.amountCents), fontSize = MibotType.Hero, fontWeight = MibotType.Semi, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(request.purpose, fontSize = MibotType.Body, lineHeight = MibotType.BodyLine, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(MibotSpace.M))
        Text(
            t("不会代付：确认后仍需你在微信/支付 App 里自己完成这笔付款，AI 只放行任务。",
                "AI never pays: after you confirm, you still make the payment yourself in WeChat/pay app; AI only releases the task."),
            fontSize = MibotType.Caption, lineHeight = MibotType.SecondaryLine, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MibotSpace.M))
        Row(Modifier.fillMaxWidth()) {
            MibotButton(
                text = t("拒绝", "Reject"),
                onClick = { onSettled(PaymentGuard.settle(request.runId, queue, approved = false)) },
                modifier = Modifier.weight(1f),
                tonal = true,
            )
            Spacer(Modifier.width(MibotSpace.M))
            MibotButton(
                text = t("确认（我本人在微信里付）", "Confirm (I'll pay myself)"),
                onClick = { onSettled(PaymentGuard.settle(request.runId, queue, approved = true)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 分 → 「¥1,284.50」；负值带负号（红色由调用方决定）。 */
fun formatCents(cents: Long): String =
    "¥%,.2f".format(java.util.Locale.CHINA, cents / 100.0)
