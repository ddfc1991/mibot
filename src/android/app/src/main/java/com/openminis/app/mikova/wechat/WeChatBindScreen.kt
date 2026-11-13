package com.openminis.app.mikova.wechat

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.mibot.design.ChipKind
import com.openminis.app.mibot.design.GlassField
import com.openminis.app.mibot.design.Glyph
import com.openminis.app.mibot.design.LineIcons
import com.openminis.app.mibot.design.MibotButton
import com.openminis.app.mibot.design.MibotCard
import com.openminis.app.mibot.design.MibotIcon
import com.openminis.app.mibot.design.MibotRow
import com.openminis.app.mibot.design.MibotScaffold
import com.openminis.app.mibot.design.MibotSectionHeader
import com.openminis.app.mibot.design.MibotSpace
import com.openminis.app.mibot.design.MibotSwitch
import com.openminis.app.mibot.design.MibotTokens
import com.openminis.app.mibot.design.MibotType
import com.openminis.app.mibot.design.StatusChip
import com.openminis.app.mibot.design.t

/**
 * [mikova/wechat] 微信接入绑定页（本体 glass 风格）。
 *
 * 显示绑定状态（开关 / 无障碍管道 / 微信通道）、开启/关闭、白名单联系人（可添加）、
 * 任务权限说明（私聊文本任务 / 追问 / 通知）、以及「付款需手机接管确认」说明。
 *
 * 与现有 Routes.MIBOT_WECHAT 的关系：见 INTEGRATION.md —— 该路由目前指向
 * DestinyV7NotWired(which="wechat") 的「未接入」占位页，本页是它的替代实现，
 * 由集成方把路由改指本页（不改共享文件，只写 INTEGRATION.md 说明）。
 */
@Composable
fun WeChatBindScreen(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { WeChatPrefs(ctx) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    val whitelist = remember { mutableStateListOf<String>().apply { addAll(prefs.whitelist) } }
    var newContact by remember { mutableStateOf("") }
    val a11y = remember(ctx) { isA11yEnabled(ctx) }
    val bridge = WeChatBridgeService
    val log = remember { mutableStateListOf<String>().apply { addAll(bridge.activityLog().takeLast(6)) } }

    DisposableEffect(Unit) {
        bridge.setLogListener { line ->
            log.add(0, line)
            if (log.size > 6) log.removeAt(log.size - 1)
        }
        onDispose { bridge.setLogListener(null) }
    }

    MibotScaffold(title = t("微信接入", "WeChat bridge"), onBack = onBack) {

        // ── 绑定状态 ────────────────────────────────────────────────
        MibotSectionHeader(t("绑定状态", "Bind status"))
        MibotCard {
            MibotRow(
                title = t("微信通道", "WeChat channel"),
                subtitle = if (enabled) t("已开启 · 接收 com.tencent.mm 通知里的私聊消息", "On · listening to com.tencent.mm notifications")
                else t("未开启 · 只显示说明，不接收消息", "Off · shows only, does not listen"),
                icon = LineIcons.MessageCircle.outline,
                color = if (enabled) MibotTokens.Green else MibotTokens.Slate,
                trailing = {
                    StatusChip(if (enabled) t("已开启", "On") else t("已关闭", "Off"), if (enabled) ChipKind.OK else ChipKind.NEUTRAL)
                },
                showDivider = true,
            )
            MibotRow(
                title = t("无障碍管道", "a11y pipeline"),
                subtitle = if (a11y) t("ScreenAccessibility 已连接 · 通知事件可订阅", "ScreenAccessibility connected · notifications subscribable")
                else t("未连接 · 收不到微信消息（去开启）", "Not connected · cannot see WeChat messages"),
                icon = LineIcons.Accessibility.outline,
                color = if (a11y) MibotTokens.Green else MibotTokens.Warn,
                onClick = onOpenPermissions,
                showDivider = true,
            )
            MibotRow(
                title = t("接收并派单", "Receive & dispatch"),
                subtitle = t("总开关：新消息 → 任务解析 → TaskRunQueue", "Master switch: message → task parse → TaskRunQueue"),
                icon = LineIcons.Bell.outline,
                color = MibotTokens.Brick,
                trailing = {
                    MibotSwitch(enabled, { enabled = it; prefs.enabled = it })
                },
            )
        }

        // ── 白名单联系人 ────────────────────────────────────────────
        MibotSectionHeader(t("白名单联系人", "Allow-listed contacts"))
        MibotCard {
            if (whitelist.isEmpty()) {
                Row(Modifier.padding(horizontal = MibotSpace.M, vertical = MibotSpace.S), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(LineIcons.Users, size = MibotIcon.Dense, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(MibotSpace.S))
                    Column {
                        Text(t("未设置白名单", "No allow-list yet"),
                            fontSize = MibotType.Body, color = MaterialTheme.colorScheme.onSurface)
                        Text(t("空 = 接收所有联系人的消息（仍受总开关控制）", "Empty = accept everyone (still gated by the master switch)"),
                            fontSize = MibotType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                whitelist.forEach { contact ->
                    MibotRow(
                        title = contact,
                        subtitle = t("白名单 · 该联系人消息会被解析派单", "Allow-listed · messages are parsed & dispatched"),
                        icon = LineIcons.User.outline,
                        color = MibotTokens.Teal,
                        showDivider = true,
                        trailing = {
                            Box(
                                Modifier
                                    .clickable {
                                        whitelist.remove(contact)
                                        prefs.whitelist = whitelist.toSet()
                                    }
                                    .padding(4.dp),
                            ) {
                                Glyph(LineIcons.Trash2, size = MibotIcon.Small, tint = MibotTokens.errColor())
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(MibotSpace.S))
            GlassField(
                value = newContact,
                onValueChange = { newContact = it },
                label = t("添加联系人", "Add contact"),
                placeholder = t("输入联系人备注名，如「张三」", "Contact name, e.g. Zhang San"),
            )
            Spacer(Modifier.height(MibotSpace.M))
            MibotButton(
                text = t("添加到白名单", "Add to allow-list"),
                onClick = {
                    val name = newContact.trim()
                    if (name.isNotEmpty() && name !in whitelist) {
                        whitelist.add(name)
                        prefs.whitelist = whitelist.toSet()
                        newContact = ""
                    }
                },
                enabled = newContact.isNotBlank(),
                tonal = true,
                compact = true,
            )
        }

        // ── 任务权限说明 ────────────────────────────────────────────
        MibotSectionHeader(t("任务权限", "Task permissions"))
        MibotCard(padding = androidx.compose.foundation.layout.PaddingValues(MibotSpace.M)) {
            PermLine(LineIcons.MessageSquareWarning, t("私聊文本任务", "Private-chat text tasks"),
                t("「任务: <目标>」会排进任务队列执行", "\"task: <goal>\" is enqueued and run"))
            PermLine(LineIcons.CornerDownLeft, t("追问", "Follow-up"),
                t("普通消息不消费；执行中的任务遇新消息会被置 HELD 记断点", "Plain messages are not consumed; running tasks are HELD on new messages"))
            PermLine(LineIcons.Bell, t("通知", "Notifications"),
                t("需要常驻通知权限才能收到微信新消息", "Notification access is needed to see new WeChat messages"))
            PermLine(LineIcons.ShieldCheck, t("状态/取消", "Status & cancel"),
                t("「状态」查当前任务，「取消」停掉进行中的任务", "\"status\" reports the running task; \"cancel\" stops it"))
        }

        // ── 付款说明 ────────────────────────────────────────────────
        MibotSectionHeader(t("付款", "Payments"))
        MibotCard {
            MibotRow(
                title = t("付款需手机接管确认", "Payments need phone-handover confirmation"),
                subtitle = t("微信内付款不会由 AI 代付：金额请求只弹确认卡，等你本人在手机上确认后才放行（只问不付）", "AI never pays for you: a request only asks, you confirm on-device before it proceeds (ask-only, no pay)"),
                icon = LineIcons.Hand.outline,
                color = MibotTokens.Warn,
            )
        }

        // ── 最近活动 ────────────────────────────────────────────────
        MibotSectionHeader(t("最近活动", "Recent activity"))
        MibotCard(padding = androidx.compose.foundation.layout.PaddingValues(MibotSpace.M)) {
            if (log.isEmpty()) {
                Text(t("暂无活动 · 开启开关后，微信新消息会出现在这里", "No activity yet · turn the switch on and WeChat messages will appear here"),
                    fontSize = MibotType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                log.forEach { line ->
                    Text(line, fontSize = MibotType.Caption, lineHeight = MibotType.SecondaryLine,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 1.dp))
                }
            }
        }
    }
}

@Composable
private fun PermLine(glyph: com.openminis.app.mibot.design.LineGlyph, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = MibotSpace.S), verticalAlignment = Alignment.CenterVertically) {
        Glyph(glyph, size = MibotIcon.Dense, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(MibotSpace.M))
        Column {
            Text(title, fontSize = MibotType.Body, fontWeight = MibotType.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = MibotType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 读系统里已开启的无障碍服务里是否有 MinisAccessibilityService（ScreenAccessibility 实例）。 */
private fun isA11yEnabled(ctx: Context): Boolean = runCatching {
    val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    flat.split(':').any { it.equals(MinisAccessibilityService.SERVICE_ID, true) }
}.getOrDefault(false)
