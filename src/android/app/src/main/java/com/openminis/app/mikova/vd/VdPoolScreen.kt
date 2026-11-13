package com.openminis.app.mikova.vd

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.mibot.design.ChipKind
import com.openminis.app.mibot.design.Glyph
import com.openminis.app.mibot.design.LineIcons
import com.openminis.app.mibot.design.MibotButton
import com.openminis.app.mibot.design.MibotCard
import com.openminis.app.mibot.design.MibotFootnote
import com.openminis.app.mibot.design.MibotIcon
import com.openminis.app.mibot.design.MibotScaffold
import com.openminis.app.mibot.design.MibotSectionHeader
import com.openminis.app.mibot.design.MibotSpace
import com.openminis.app.mibot.design.MibotTokens
import com.openminis.app.mibot.design.MibotType
import com.openminis.app.mibot.design.StatusChip
import com.openminis.app.ui.glass.FrostLevel
import com.openminis.app.ui.glass.LocalGlassBottomInset
import com.openminis.app.ui.glass.frosted
import kotlinx.coroutines.launch

/**
 * [mikova/vd] 多屏管理页(glass):屏幕卡片列表(每块 displayId + 任务名 + 缩略截图 + 状态)、
 * 新建屏、关闭、切换焦点;外加"把某个包启动到焦点屏"。
 *
 * 数据全来自 [MikovaDisplayManager](屏池 + 实时预览);操作走 [MikovaVdController]
 * (注入/启动/截屏)。顶部一条 chip 显示当前能开几块 + 走哪条后端(shell / 本进程)。
 */
@Composable
fun VdPoolScreen(
    onBack: () -> Unit,
    manager: MikovaDisplayManager,
    controller: MikovaVdController,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bottom = LocalGlassBottomInset.current
    val screens by manager.screens
    val maxScreens = remember { manager.maxScreens() }
    var focusedId by remember { mutableStateOf<Int?>(null) }
    var newTaskName by remember { mutableStateOf("") }
    var launchPackage by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val shellUp = remember { manager.shellAvailable() }

    LaunchedEffect(screens) {
        if (focusedId != null && screens.none { it.displayId == focusedId }) focusedId = null
        if (focusedId == null) focusedId = screens.firstOrNull()?.displayId
    }

    MibotScaffold(title = "虚拟屏多开", onBack = onBack) {
        // 状态行
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MibotSpace.Screen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(text = "${screens.size} / $maxScreens 块", kind = ChipKind.ACCENT)
            StatusChip(
                text = if (shellUp) "可信屏(助手)" else "本进程屏",
                kind = if (shellUp) ChipKind.OK else ChipKind.NEUTRAL,
            )
            if (!shellUp) {
                StatusChip(text = "无焦点·不能跑第三方 App", kind = ChipKind.WARN)
            }
        }
        if (maxScreens == 1) {
            Spacer(Modifier.height(MibotSpace.S))
            Text(
                "低内存设备:并行上限自动降为 1 块。",
                modifier = Modifier.padding(horizontal = MibotSpace.Screen),
                fontSize = MibotType.Secondary,
                color = MibotTokens.warnColor(),
            )
        }

        // 新建屏
        Spacer(Modifier.height(MibotSpace.L))
        MibotSectionHeader("新建屏")
        MibotCard {
            Column(Modifier.padding(MibotSpace.M)) {
                GlassField(
                    value = newTaskName,
                    onValueChange = { newTaskName = it },
                    placeholder = "任务名(留空则自动命名)",
                )
                Spacer(Modifier.height(MibotSpace.M))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MibotButton(
                        text = "新建虚拟屏",
                        onClick = {
                            runCatching {
                                val name = newTaskName.ifBlank { "任务 ${screens.size + 1}" }
                                val s = manager.create(name)
                                newTaskName = ""
                                focusedId = s.displayId
                                message = "已建屏 displayId=${s.displayId}(${if (s.backend == VdBackend.SHELL) "可信" else "本进程"})"
                            }.onFailure { message = it.message }
                        },
                        enabled = screens.size < maxScreens,
                    )
                    MibotButton(
                        text = "全部关闭",
                        onClick = {
                            manager.closeAll()
                            focusedId = null
                            message = null
                        },
                        tonal = true,
                        enabled = screens.isNotEmpty(),
                    )
                }
            }
        }

        // 启动应用到焦点屏
        Spacer(Modifier.height(MibotSpace.L))
        MibotSectionHeader("启动应用到焦点屏", actionLabel = if (focusedId != null) "焦点 d${focusedId}" else null)
        MibotCard {
            Column(Modifier.padding(MibotSpace.M)) {
                GlassField(
                    value = launchPackage,
                    onValueChange = { launchPackage = it },
                    placeholder = "包名,如 com.android.calculator2",
                )
                Spacer(Modifier.height(MibotSpace.M))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MibotButton(
                        text = "启动",
                        onClick = {
                            val id = focusedId
                            if (id == null) {
                                message = "先选一块焦点屏"
                            } else {
                                scope.launch {
                                    val code = controller.startApp(id, launchPackage.trim())
                                    message = when (code) {
                                        0 -> "已在 d$id 启动 ${launchPackage.trim()}"
                                        1 -> "用户正在主屏用这个 App,没启动(不打扰主屏)"
                                        2 -> "没找到可启动入口"
                                        else -> "启动失败(code=$code);本进程屏不能跑第三方 App"
                                    }
                                }
                            }
                        },
                        enabled = launchPackage.isNotBlank() && focusedId != null && shellUp,
                    )
                    MibotButton(
                        text = "返回键",
                        onClick = {
                            val id = focusedId ?: return@MibotButton
                            scope.launch { controller.key(id, android.view.KeyEvent.KEYCODE_BACK) }
                        },
                        tonal = true,
                        enabled = focusedId != null,
                    )
                }
            }
        }

        // 屏幕卡片列表
        Spacer(Modifier.height(MibotSpace.L))
        MibotSectionHeader("屏幕池(${screens.size})")
        screens.forEach { screen ->
            VdScreenCard(
                screen = screen,
                focused = screen.displayId == focusedId,
                onFocus = { focusedId = screen.displayId },
                onClose = {
                    manager.close(screen.displayId)
                    message = "已关闭 d${screen.displayId}"
                },
            )
            Spacer(Modifier.height(MibotSpace.S))
        }

        if (screens.isEmpty()) {
            MibotCard {
                Column(Modifier.padding(MibotSpace.M)) {
                    Text(
                        "还没有虚拟屏。点上面「新建虚拟屏」开一块;低内存设备只能开 1 块。",
                        fontSize = MibotType.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        message?.let {
            Spacer(Modifier.height(MibotSpace.M))
            MibotFootnote(it)
        }
        Spacer(Modifier.height(MibotSpace.M))
        MibotFootnote(
            if (shellUp) {
                "可信屏:由内置助手(uid 2000)经无线调试拉起,能托管第三方 App、跨屏注入、逐屏截屏。"
            } else {
                "本进程屏:没接手机控制运行时(PhoneServices.Owner)时只有 own-content 屏,能渲染本应用内容,不能跑第三方 App。见模块 INTEGRATION.md。"
            },
        )
        Spacer(Modifier.height(bottom))
    }
}

/** 一块虚拟屏卡片:缩略截图 + 任务名 + displayId/后端 + 设焦点 + 关闭。 */
@Composable
private fun VdScreenCard(
    screen: VdScreen,
    focused: Boolean,
    onFocus: () -> Unit,
    onClose: () -> Unit,
) {
    val frame by screen.frames.collectAsState()
    MibotCard(
        onClick = onFocus,
        padding = androidx.compose.foundation.layout.PaddingValues(MibotSpace.M),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 缩略截图
            Box(
                Modifier
                    .size(width = 56.dp, height = 96.dp)
                    .frosted(RoundedCornerShape(8.dp), FrostLevel.Card, tint = MibotTokens.field()),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = frame
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "display ${screen.displayId} 预览",
                        modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height.toFloat()),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Glyph(LineIcons.Monitor, size = 22.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(MibotSpace.M))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        screen.taskName,
                        fontSize = MibotType.Body,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (focused) StatusChip(text = "焦点", kind = ChipKind.ACCENT)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "displayId ${screen.displayId} · ${screen.width}×${screen.height}",
                    fontSize = MibotType.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(MibotSpace.S))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip(
                        text = if (screen.backend == VdBackend.SHELL) "可信屏" else "本进程",
                        kind = if (screen.backend == VdBackend.SHELL) ChipKind.OK else ChipKind.NEUTRAL,
                    )
                    StatusChip(
                        text = if (frame != null) "有画面" else "等待画面",
                        kind = if (frame != null) ChipKind.OK else ChipKind.WARN,
                    )
                }
            }
            // 关闭按钮
            Box(
                Modifier
                    .size(MibotIcon.TopButton)
                    .frosted(RoundedCornerShape(10.dp), FrostLevel.Card)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            waitForUpOrCancellation()
                            onClose()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(LineIcons.X, size = MibotIcon.Dense, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** glass 输入框(卡片内的字段)。 */
@Composable
private fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .frosted(RoundedCornerShape(MibotTokens.RowRadius), FrostLevel.Card, border = false, tint = MibotTokens.field())
            .padding(horizontal = MibotSpace.M, vertical = MibotSpace.S),
    ) {
        if (value.isBlank()) {
            Text(placeholder, fontSize = MibotType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(
                fontSize = MibotType.Body,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = MibotType.BodyLine,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}
