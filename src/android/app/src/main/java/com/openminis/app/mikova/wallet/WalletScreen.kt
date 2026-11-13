package com.openminis.app.mikova.wallet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.mibot.design.ChipKind
import com.openminis.app.mibot.design.Glyph
import com.openminis.app.mibot.design.LineIcons
import com.openminis.app.mibot.design.MibotCard
import com.openminis.app.mibot.design.MibotIcon
import com.openminis.app.mibot.design.MibotRow
import com.openminis.app.mibot.design.MibotScaffold
import com.openminis.app.mibot.design.MibotSectionHeader
import com.openminis.app.mibot.design.MibotSpace
import com.openminis.app.mibot.design.MibotTokens
import com.openminis.app.mibot.design.MibotType
import com.openminis.app.mibot.design.StatusChip
import com.openminis.app.mibot.design.t
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [mikova/wallet] 钱包页（本体 glass 风格）。
 *
 * 余额卡（大数字）+ 流水列表（每条：图标 + 名称 + 时间 + 金额正负色）+ 充值入口
 * （置灰显示「待支付通道」）。
 *
 * 与现有 Routes.MIBOT_WALLET 的关系：见 INTEGRATION.md —— 该路由目前指向
 * DestinyV7NotWired(which="wallet") 的「未接入」占位页，本页是它的替代实现。
 * 本版不接支付通道：余额与流水来自本地台账（[WalletStore]），如实显示，不编造数字。
 */
@Composable
fun WalletScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { WalletStore.get(ctx) }
    val state by store.state.collectAsState()
    val balance = state.balanceCents
    val txs = state.transactions

    MibotScaffold(title = t("钱包", "Wallet"), onBack = onBack) {

        // ── 余额卡 ──────────────────────────────────────────────────
        MibotSectionHeader(t("余额", "Balance"))
        MibotCard(padding = androidx.compose.foundation.layout.PaddingValues(MibotSpace.L)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Glyph(LineIcons.Gem, size = MibotIcon.Standard, tint = MibotTokens.accent())
                Spacer(Modifier.width(MibotSpace.S))
                Text(t("可用余额", "Available balance"), fontSize = MibotType.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                StatusChip(t("未接支付通道", "No pay channel"), ChipKind.NEUTRAL)
            }
            Spacer(Modifier.height(MibotSpace.M))
            Text(
                formatCents(balance),
                fontSize = MibotType.Hero,
                fontWeight = MibotType.Semi,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(MibotSpace.XS))
            Text(
                t("本版没有接支付通道：无绑卡、无真实账单。余额为本地台账，如实显示。", "No payment channel in this build: no card, no real billing. Balance is the local ledger, shown as-is."),
                fontSize = MibotType.Caption,
                lineHeight = MibotType.SecondaryLine,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 充值入口（置灰）────────────────────────────────────────
        MibotSectionHeader(t("充值", "Top up"))
        MibotCard {
            MibotRow(
                title = t("充值 / 提现", "Top up / withdraw"),
                subtitle = t("支付通道未接入 · 待支付通道（暂不可用）", "No pay channel wired yet · awaiting a payment channel"),
                icon = LineIcons.Plus.outline,
                color = MibotTokens.Slate,
                trailing = { StatusChip(t("待支付通道", "Awaiting channel"), ChipKind.WARN) },
            )
        }

        // ── 流水列表 ────────────────────────────────────────────────
        MibotSectionHeader(
            text = t("流水", "Transactions"),
            actionLabel = if (txs.isNotEmpty()) t("清空", "Clear") else null,
            onAction = { store.reset() },
        )
        MibotCard {
            if (txs.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(MibotSpace.L), contentAlignment = Alignment.Center) {
                    Text(
                        t("暂无流水 · 充值通道接入后，收支会记在这里", "No transactions yet · once a pay channel is wired, they show up here"),
                        fontSize = MibotType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                txs.forEachIndexed { i, tx ->
                    MibotRow(
                        title = tx.name,
                        subtitle = tx.note.ifEmpty { formatTime(tx.time) },
                        icon = if (tx.amountCents >= 0) LineIcons.CircleCheck.outline else LineIcons.Hand.outline,
                        color = if (tx.amountCents >= 0) MibotTokens.Green else MibotTokens.Red,
                        showDivider = i < txs.lastIndex,
                        trailing = {
                            Text(
                                formatSigned(tx.amountCents),
                                fontSize = MibotType.Body,
                                fontWeight = MibotType.Semi,
                                color = if (tx.amountCents >= 0) MibotTokens.okColor() else MibotTokens.errColor(),
                            )
                        },
                    )
                }
            }
        }
    }
}

/** 流水行的时间戳：MM-dd HH:mm。 */
private fun formatTime(time: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))

/** 金额带符号：+12.00 / -3.50。 */
private fun formatSigned(cents: Long): String {
    val s = formatCents(kotlin.math.abs(cents))
    return if (cents >= 0) "+$s" else "-$s"
}
