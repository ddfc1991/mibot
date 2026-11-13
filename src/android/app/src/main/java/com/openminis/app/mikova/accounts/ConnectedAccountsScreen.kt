package com.openminis.app.mikova.accounts

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.mibot.design.GlassField
import com.openminis.app.mibot.design.Glyph
import com.openminis.app.mibot.design.IconChip
import com.openminis.app.mibot.design.LineIcons
import com.openminis.app.mibot.design.ChipKind
import com.openminis.app.mibot.design.MibotButton
import com.openminis.app.mibot.design.MibotCard
import com.openminis.app.mibot.design.MibotFootnote
import com.openminis.app.mibot.design.MibotIcon
import com.openminis.app.mibot.design.MibotRadius
import com.openminis.app.mibot.design.MibotRow
import com.openminis.app.mibot.design.MibotScaffold
import com.openminis.app.mibot.design.MibotSectionHeader
import com.openminis.app.mibot.design.MibotSpace
import com.openminis.app.mibot.design.MibotSpacer
import com.openminis.app.mibot.design.MibotTokens
import com.openminis.app.mibot.design.MibotType
import com.openminis.app.mibot.design.StatusChip
import com.openminis.app.mibot.design.t
import com.openminis.app.ui.glass.FrostLevel
import com.openminis.app.ui.glass.FrostedSurface

/** 导航路由（在 AppNavigation.kt 注册，见 INTEGRATION.md）。 */
object AccountRoutes { const val CENTER = "mikova_accounts" }

/**
 * 账号连接中心 —— 连接域入口页（glass 深色毛玻璃）。
 *
 * 结构（对标 toki 的连接体验）：
 *   1. 已连接应用：每个已连接应用一张卡（图标 + 名称 + 该应用下账号列表 + 状态 chip），
 *      同应用多账号（如两个 Gmail 账号）并列展示，可单独断开。
 *   2. + 添加账号：展开「可连接应用」目录。
 *   3. 可连接应用：按 AI 服务 / 通讯 / 工具 / 其他 分类的目录，逐项可连接。
 *
 * 授权交互（toki 蓝本）：
 *   - OAuth / 浏览器捕获：先弹「权限说明 + 隐私承诺」浮层，用户点「继续连接」才进入 OAuth 容器
 *     （[onOpenOAuth] / [onOpenBrowser] 交由导航层路由到 oauth 模块）。
 *   - API Key：弹录入浮层，填完直接本地加密保存，不经网络。
 */
@Composable
fun ConnectedAccountsScreen(
    onBack: () -> Unit,
    onOpenOAuth: (String) -> Unit,
    onOpenBrowser: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val store = remember { AccountStore.shared(ctx) }
    val accounts by store.accounts.collectAsState()

    var showCatalog by remember { mutableStateOf(false) }
    var consentFor by remember { mutableStateOf<AppTemplate?>(null) }
    var apikeyFor by remember { mutableStateOf<AppTemplate?>(null) }

    fun startConnect(tpl: AppTemplate) {
        when (tpl.authType) {
            AuthType.OAUTH -> consentFor = tpl
            AuthType.BROWSER -> consentFor = tpl
            AuthType.APIKEY -> apikeyFor = tpl
        }
    }

    MibotScaffold(title = t("账号连接中心", "Account hub"), onBack = onBack) {
        // ------------------------------------------------------- 已连接应用
        val connected = AppCatalog.all.filter { tpl -> accounts.any { it.serviceId == tpl.id } }
        MibotSectionHeader(t("已连接应用", "Connected apps"))
        if (connected.isEmpty()) {
            MibotCard(padding = PaddingValues(14.dp)) {
                Text(
                    t("还没有连接任何应用。点下方「+ 添加账号」开始连接。", "No apps connected yet. Tap “Add account” below to start."),
                    fontSize = MibotType.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            connected.forEach { tpl ->
                val list = accounts.filter { it.serviceId == tpl.id }
                MibotCard(padding = PaddingValues(vertical = 6.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = MibotSpace.M + 2.dp, vertical = MibotSpace.S),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconChip(tpl.icon, tpl.color, size = 36.dp, filled = true)
                        Spacer(Modifier.width(MibotSpace.M))
                        Text(tpl.name, fontSize = MibotType.TitleSmall, fontWeight = MibotType.Semi, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(MibotSpace.S))
                        StatusChip("${t("已连接", "Connected")} · ${list.size}", ChipKind.OK)
                    }
                    list.forEachIndexed { i, acc ->
                        val expired = acc.expiresAt > 0 && acc.expiresAt < System.currentTimeMillis()
                        MibotRow(
                            title = acc.displayName.ifBlank { tpl.name },
                            subtitle = t("连接于 ", "Connected ") + ago(acc.connectedAt) +
                                (if (expired) " · " + t("已过期", "expired") else ""),
                            icon = Icons.Outlined.Key,
                            color = tpl.color,
                            onClick = null,
                            showDivider = i < list.lastIndex,
                            trailing = {
                                StatusChip(kindLabel(acc.kind), ChipKind.NEUTRAL)
                                Spacer(Modifier.width(MibotSpace.S))
                                Box(
                                    Modifier
                                        .clip(MibotRadius.Pill)
                                        .clickable {
                                            store.remove(acc.id)
                                            Toast.makeText(ctx, com.openminis.app.mibot.design.tc(ctx, "已断开连接", "Disconnected"), Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 3.dp),
                                ) {
                                    Text(t("断开", "Remove"), fontSize = MibotType.Caption, color = MibotTokens.errColor(), fontWeight = MibotType.Semi)
                                }
                            },
                            chevron = false,
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------- 添加账号
        MibotSpacer(MibotSpace.S)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MibotTokens.ScreenPadding),
            horizontalArrangement = Arrangement.End,
        ) {
            MibotButton(if (showCatalog) t("收起目录", "Collapse") else t("+ 添加账号", "+ Add account"), onClick = { showCatalog = !showCatalog }, tonal = true)
        }

        // ------------------------------------------------------- 可连接应用
        if (showCatalog) {
            AppCatalog.grouped().forEach { (cat, tpls) ->
                MibotSectionHeader(cat.zh)
                MibotCard(padding = PaddingValues(vertical = 6.dp)) {
                    tpls.forEachIndexed { i, tpl ->
                        val count = accounts.count { it.serviceId == tpl.id }
                        MibotRow(
                            title = if (count > 0) "${tpl.name}（再添加）" else tpl.name,
                            subtitle = tpl.summary,
                            icon = tpl.icon,
                            color = tpl.color,
                            onClick = { startConnect(tpl) },
                            showDivider = i < tpls.lastIndex,
                            trailing = {
                                if (count > 0) StatusChip(t("已连接", "Connected"), ChipKind.OK)
                                else StatusChip(authLabel(tpl.authType), ChipKind.NEUTRAL)
                            },
                        )
                    }
                }
            }
        }

        MibotFootnote(
            t(
                "账号与密钥只保存在本机（Android Keystore 加密），不会上传；连接后每个应用的可用范围见上方权限说明。",
                "Accounts and keys stay on this device (encrypted with the Android Keystore) and never leave it. See each app's scope note above.",
            ),
        )
    }

    // --------------------------------------------------------- 授权前权限说明浮层
    consentFor?.let { tpl ->
        ConsentSheet(
            tpl = tpl,
            onDismiss = { consentFor = null },
            onConfirm = {
                consentFor = null
                when (tpl.authType) {
                    AuthType.BROWSER -> onOpenBrowser(tpl.id)
                    else -> onOpenOAuth(tpl.id)
                }
            },
        )
    }

    // --------------------------------------------------------- API Key 录入浮层
    apikeyFor?.let { tpl ->
        ApiKeySheet(
            tpl = tpl,
            onDismiss = { apikeyFor = null },
            onSave = { name, key ->
                val s = AccountStore.shared(ctx)
                s.add(
                    ConnectedAccount(
                        id = s.newId(),
                        serviceId = tpl.id,
                        displayName = name.trim(),
                        connectedAt = System.currentTimeMillis(),
                        scopes = tpl.scopes,
                        secretEnc = s.seal(key.trim()),
                        kind = "apikey",
                    ),
                )
                apikeyFor = null
                Toast.makeText(ctx, com.openminis.app.mibot.design.tc(ctx, "已保存（仅本机）", "Saved on this device"), Toast.LENGTH_SHORT).show()
            },
        )
    }
}

private fun ago(ts: Long): String {
    if (ts <= 0) return ""
    val mins = ((System.currentTimeMillis() - ts) / 60000L).coerceAtLeast(0)
    return when {
        mins < 1 -> "刚刚"
        mins < 60 -> "$mins 分钟前"
        mins < 1440 -> "${mins / 60} 小时前"
        else -> "${mins / 1440} 天前"
    }
}

private fun kindLabel(kind: String): String = when (kind) {
    "apikey" -> "API Key"
    "cookie" -> "浏览器凭据"
    else -> "OAuth Token"
}

private fun authLabel(type: AuthType): String = when (type) {
    AuthType.OAUTH -> "OAuth"
    AuthType.APIKEY -> "API Key"
    AuthType.BROWSER -> "浏览器"
}

@Composable
private fun PermissionBullet(text: String, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text("•", color = accent, fontSize = MibotType.Body, fontWeight = MibotType.Semi, modifier = Modifier.padding(end = 8.dp))
        Text(text, fontSize = MibotType.Secondary, lineHeight = MibotType.SecondaryLine, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 授权前的「权限说明 + 隐私承诺」浮层。用 [FrostedSurface] 拼在页面内（对话框窗口
 * 拿不到共享毛玻璃 backdrop，会退化成平面半透明，所以在页内铺更稳）。
 */
@Composable
private fun ConsentSheet(tpl: AppTemplate, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        FrostedSurface(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp).clip(MibotRadius.CardShape),
            level = FrostLevel.Sheet,
            elevation = 4.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip(tpl.icon, tpl.color, size = 40.dp, filled = true)
                    Spacer(Modifier.width(MibotSpace.M))
                    Column {
                        Text("连接 ${tpl.name}", fontSize = MibotType.Title, fontWeight = MibotType.Semi, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            when (tpl.authType) {
                                AuthType.BROWSER -> "浏览器登录捕获"
                                AuthType.APIKEY -> "API Key"
                                else -> "OAuth 授权"
                            },
                            fontSize = MibotType.Secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(MibotSpace.M))
                Text(tpl.summary, fontSize = MibotType.Body, lineHeight = MibotType.BodyLine, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(MibotSpace.M))
                Text("它将获得", fontSize = MibotType.Secondary, fontWeight = MibotType.Semi, color = MaterialTheme.colorScheme.onSurfaceVariant)
                tpl.scopes.forEach { s -> PermissionBullet(s, MibotTokens.okColor()) }
                Spacer(Modifier.height(MibotSpace.M))
                Text("隐私承诺", fontSize = MibotType.Secondary, fontWeight = MibotType.Semi, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(
                    "凭据只加密存本机（Android Keystore），Mibot 不上传、不同步到任何服务器。",
                    "Mibot 只在你明确发起时才使用该连接，敏感操作（发信、写入、删除）每次都会向你确认。",
                    "你随时可在连接中心一键断开，断开后本地凭据立即删除。",
                ).forEach { p -> PermissionBullet(p, MibotTokens.accent()) }
                Spacer(Modifier.height(MibotSpace.L))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MibotButton("取消", onClick = onDismiss, modifier = Modifier.weight(1f), tonal = true)
                    MibotButton("继续连接", onClick = onConfirm, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** API Key 录入浮层：key + 账号名，保存即本地加密落盘。 */
@Composable
private fun ApiKeySheet(
    tpl: AppTemplate,
    onDismiss: () -> Unit,
    onSave: (name: String, key: String) -> Unit,
) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        FrostedSurface(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp).clip(MibotRadius.CardShape),
            level = FrostLevel.Sheet,
            elevation = 4.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip(tpl.icon, tpl.color, size = 40.dp, filled = true)
                    Spacer(Modifier.width(MibotSpace.M))
                    Text("连接 ${tpl.name}", fontSize = MibotType.Title, fontWeight = MibotType.Semi, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(MibotSpace.M))
                Text(tpl.summary, fontSize = MibotType.Secondary, lineHeight = MibotType.SecondaryLine, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(MibotSpace.M))
                GlassField(key, { key = it }, "API Key", placeholder = "sk-...", secret = true)
                Spacer(Modifier.height(MibotSpace.S))
                GlassField(name, { name = it }, "账号名称（可选）", placeholder = "如 work / personal")
                Spacer(Modifier.height(MibotSpace.M))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(LineIcons.ShieldCheck, size = MibotIcon.Standard, tint = MibotTokens.okColor())
                    Spacer(Modifier.width(MibotSpace.S))
                    Text("仅加密保存在本机，不会上传。", fontSize = MibotType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(MibotSpace.L))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MibotButton("取消", onClick = onDismiss, modifier = Modifier.weight(1f), tonal = true)
                    MibotButton(
                        "保存",
                        onClick = {
                            if (key.trim().length < 8) {
                                Toast.makeText(ctx, "API Key 太短", Toast.LENGTH_SHORT).show()
                            } else onSave(name.ifBlank { tpl.name }, key)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = key.isNotBlank(),
                    )
                }
            }
        }
    }
}
