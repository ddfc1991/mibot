package com.openminis.app.mikova.selfhost

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.mibot.design.ChipKind
import com.openminis.app.mibot.design.GlassField
import com.openminis.app.mibot.design.Glyph
import com.openminis.app.mibot.design.LineIcons
import com.openminis.app.mibot.design.MibotButton
import com.openminis.app.mibot.design.MibotCard
import com.openminis.app.mibot.design.MibotFootnote
import com.openminis.app.mibot.design.MibotIcon
import com.openminis.app.mibot.design.MibotRadius
import com.openminis.app.mibot.design.MibotSectionHeader
import com.openminis.app.mibot.design.MibotScaffold
import com.openminis.app.mibot.design.MibotSpace
import com.openminis.app.mibot.design.MibotSpacer
import com.openminis.app.mibot.design.MibotSwitch
import com.openminis.app.mibot.design.MibotTokens
import com.openminis.app.mibot.design.MibotType
import com.openminis.app.mibot.design.StatusChip
import com.openminis.app.mibot.design.t
import com.openminis.app.ui.glass.FrostLevel
import com.openminis.app.ui.glass.FrostedSurface
import kotlinx.coroutines.launch

/** 导航路由（在 AppNavigation.kt 注册，见 INTEGRATION.md）。 */
object SelfHostRoutes { const val SCREEN = "mikova_selfhost" }

/** Docker 一键部署示例（复制即用；端口 / 镜像 / 密钥按你的环境改）。 */
private const val DEPLOY_DOCKER = """docker run -d --name mibot-server \
  --restart unless-stopped \
  -p 8899:8899 \
  -v mibot-data:/data \
  -e MIBOT_API_KEY=<PH:YOUR_API_KEY> \
  ghcr.io/openminis/mibot-server:latest"""

/**
 * 自托管配置页（glass 深色毛玻璃）。
 *
 * - 数据自托管开关：本地（只存本机）↔ 远端（指向你自己的服务器）。
 * - 服务器地址 + API Key：密钥经 EncryptedSharedPreferences 加密落盘，不回读明文。
 * - 测试连接：先落盘当前输入，再 GET 服务器（带 Bearer Key），显示 HTTP 状态 + 延迟。
 * - 一键部署指南：折叠卡片，展开显示 docker 命令，一键复制。
 */
@Composable
fun SelfHostScreen(onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val cfg = remember { SelfHostConfig.shared(androidx.compose.ui.platform.LocalContext.current) }
    val state by cfg.config.collectAsState()

    var serverUrl by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var apiKey by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SelfHostTestResult?>(null) }
    var testing by remember { mutableStateOf(false) }
    var guideOpen by remember { mutableStateOf(false) }

    MibotScaffold(title = t("数据自托管", "Self-hosting"), onBack = onBack) {
        // ------------------------------------------------------- 开关卡
        MibotSectionHeader(t("数据自托管", "Self-hosting"))
        MibotCard {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = MibotSpace.M + 2.dp, vertical = MibotSpace.S),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.enabled) t("使用远程服务器", "Use remote server") else t("仅存本机", "Local only"),
                        fontSize = MibotType.Body,
                        fontWeight = MibotType.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(MibotSpace.XS))
                    Text(
                        if (state.enabled) t("数据与能力指向你自己的服务器", "Data & capabilities point to your own server")
                        else t("关闭后数据只保存在这台设备", "Off = data stays on this device"),
                        fontSize = MibotType.Secondary,
                        lineHeight = MibotType.SecondaryLine,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MibotSwitch(checked = state.enabled, onCheckedChange = { cfg.setEnabled(it) })
            }
        }
        MibotFootnote(
            t(
                "自托管只改变数据落在哪，不改变权限模型：敏感操作仍按本体的权限与自动化设置逐项确认。",
                "Self-hosting only changes where data lives, not the permission model: sensitive actions are still confirmed per your automation settings.",
            ),
        )

        // ------------------------------------------------------- 服务器 & Key
        MibotSectionHeader(t("服务器", "Server"))
        MibotCard(padding = PaddingValues(14.dp)) {
            GlassField(
                serverUrl, { serverUrl = it },
                t("服务器地址", "Server URL"),
                placeholder = "https://mibot.example.com",
            )
            Spacer(Modifier.height(10.dp))
            GlassField(
                apiKey, { apiKey = it },
                t("API Key（可选）", "API key (optional)"),
                placeholder = if (state.hasApiKey) t("已保存，留空保持不变", "Saved — leave blank to keep") else "sk-…",
                secret = true,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                MibotButton(
                    if (testing) t("测试中…", "Testing…") else t("测试连接", "Test connection"),
                    onClick = {
                        testing = true
                        result = null
                        scope.launch {
                            cfg.setServerUrl(serverUrl)
                            if (apiKey.isNotBlank()) cfg.setApiKey(apiKey)
                            result = cfg.testConnection()
                            testing = false
                        }
                    },
                    enabled = !testing,
                    tonal = true,
                )
                if (result != null) {
                    val r = result!!
                    StatusChip(
                        if (r.ok) "${t("通过", "OK")} · ${r.latencyMs}ms" else t("失败", "Failed"),
                        if (r.ok) ChipKind.OK else ChipKind.ERR,
                    )
                }
            }
            result?.let { r ->
                Spacer(Modifier.height(MibotSpace.S))
                Text(
                    r.message,
                    fontSize = MibotType.Secondary,
                    lineHeight = MibotType.SecondaryLine,
                    color = if (r.ok) MibotTokens.okColor() else MibotTokens.errColor(),
                )
            }
        }
        MibotFootnote(
            t(
                "测试连接会把你填的地址与密钥先保存到本机加密存储，再发起一次只读请求（GET /）。",
                "Testing first saves the URL & key to this device's encrypted storage, then issues one read-only GET / request.",
            ),
        )

        // ------------------------------------------------------- 一键部署指南
        MibotSectionHeader(t("一键部署指南", "One-click deploy"))
        MibotCard(padding = PaddingValues(0.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { guideOpen = !guideOpen }
                    .padding(horizontal = MibotSpace.M + 2.dp, vertical = MibotSpace.S)
                    .height(MibotSpace.RowMin),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t("Docker 部署", "Docker deploy"), fontSize = MibotType.Body, fontWeight = MibotType.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text(t("一行命令把服务端跑起来", "Run the server with one command"), fontSize = MibotType.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Glyph(
                    LineIcons.ChevronDown,
                    modifier = Modifier.rotate(if (guideOpen) 180f else 0f),
                    size = MibotIcon.Small,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (guideOpen) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                    FrostedSurface(
                        Modifier.fillMaxWidth().clip(MibotRadius.RowShape),
                        level = FrostLevel.Card,
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                DEPLOY_DOCKER,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(MibotSpace.M))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MibotButton(
                                    t("复制命令", "Copy"),
                                    onClick = { clipboard.setText(AnnotatedString(DEPLOY_DOCKER)) },
                                    tonal = true,
                                    compact = true,
                                )
                            }
                        }
                    }
                }
                MibotFootnote(
                    t(
                        "把上面的 <PH:YOUR_API_KEY> 换成你在服务器上设置的密钥；默认监听 8899，按需改端口映射。",
                        "Replace <PH:YOUR_API_KEY> with the key you set on the server; it listens on 8899 by default — adjust the port mapping as needed.",
                    ),
                )
            }
        }

        Spacer(Modifier.height(MibotSpace.L))
    }
}
