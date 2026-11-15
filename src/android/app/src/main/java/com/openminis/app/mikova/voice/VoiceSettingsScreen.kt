package com.openminis.app.mikova.voice

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.mibot.design.ChipKind
import com.openminis.app.mibot.design.Glyph
import com.openminis.app.mibot.design.LineIcons
import com.openminis.app.mibot.design.MibotButton
import com.openminis.app.mibot.design.MibotCard
import com.openminis.app.mibot.design.MibotFootnote
import com.openminis.app.mibot.design.MibotIcon
import com.openminis.app.mibot.design.MibotRadioRow
import com.openminis.app.mibot.design.MibotScaffold
import com.openminis.app.mibot.design.MibotSectionHeader
import com.openminis.app.mibot.design.MibotSpace
import com.openminis.app.mibot.design.MibotTokens
import com.openminis.app.mibot.design.MibotType
import com.openminis.app.mibot.design.StatusChip
import com.openminis.app.ui.glass.FrostLevel
import com.openminis.app.ui.glass.frosted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [mikova/voice] 语音设置(glass):引擎选择(sherpa 优先 / 平台兜底)+ 模型管理
 * (已下载列表 / 下载进度 / 删除)。
 *
 * [prefer] 由外部(偏好存取)传进来,[onPreferChange] 回写;这里不碰共享偏好文件,存取在调用方。
 * 模型进度订阅 [VoiceModelManager.progressFlow]。
 */
@Composable
fun VoiceSettingsScreen(
    onBack: () -> Unit,
    prefer: VoiceEngineId,
    onPreferChange: (VoiceEngineId) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by VoiceModelManager.progressFlow.collectAsState()
    // 已安装集合:下载/删除后刷新。
    var installedIds by remember { mutableStateOf(VoiceModelManager.installed(ctx).map { it.id }.toSet()) }
    fun refreshInstalled() {
        installedIds = VoiceModelManager.installed(ctx).map { it.id }.toSet()
    }

    var nativeReady by remember { mutableStateOf<Boolean?>(null) }
    var deleteCandidate by remember { mutableStateOf<VoiceModelInfo?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    // 系统识别服务的具体不可用原因;空 = 可用。sherpa 通常不可用,此诊断对两条引擎链都适用。
    val recognitionIssue by remember(ctx) { mutableStateOf(PlatformVoiceEngine.recognitionBlockers(ctx).firstOrNull()) }
    fun openSystemSetting(action: String, label: String) {
        try {
            ctx.startActivity(Intent(action))
        } catch (_: Exception) {
            try { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { deleteError = "无法打开系统${label}设置" }
        }
    }
    LaunchedEffect(Unit) {
        nativeReady = withContext(Dispatchers.IO) { SherpaVoiceEngine.isNativeLoaded() }
        refreshInstalled()
    }
    deleteCandidate?.let { info ->
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteCandidate = null },
            title = { Text("删除语音模型？") },
            text = { Text(deleteError ?: "将删除“${info.title}”的本地文件。再次使用离线能力需要重新下载。") },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    scope.launch {
                        try {
                            val ok = withContext(Dispatchers.IO) { VoiceModelManager.delete(ctx, info) }
                            refreshInstalled()
                            if (ok) deleteCandidate = null else deleteError = "删除失败，请重试"
                        } finally { deleting = false }
                    }
                }) { Text(if (deleting) "删除中…" else "删除") }
            },
            dismissButton = { TextButton(enabled = !deleting, onClick = { deleteCandidate = null }) { Text("取消") } },
        )
    }

    MibotScaffold(title = "语音设置", onBack = onBack) {
        // 引擎选择
        MibotSectionHeader("识别引擎")
        MibotCard {
            Column(Modifier.padding(vertical = MibotSpace.S)) {
                MibotRadioRow(
                    title = "离线 sherpa-onnx(优先)",
                    subtitle = "本机识别 + 合成,不出网;需下载模型。不可用时自动落回系统识别。",
                    selected = prefer == VoiceEngineId.SHERPA,
                    onClick = { onPreferChange(VoiceEngineId.SHERPA) },
                    showDivider = true,
                )
                MibotRadioRow(
                    title = "系统识别(兜底)",
                    subtitle = "用安卓 SpeechRecognizer / TextToSpeech;依赖系统服务,可能出网。",
                    selected = prefer == VoiceEngineId.PLATFORM,
                    onClick = { onPreferChange(VoiceEngineId.PLATFORM) },
                )
            }
        }
        MibotFootnote(
            when (nativeReady) {
                null -> "正在检查离线运行库…"
                false -> "本版本不含可用离线运行库。仅下载模型不能启用离线能力，请使用系统语音；系统朗读与识别独立。"
                true -> "离线运行库可用；仍需完整识别/合成模型。缺少模型时识别回退系统服务，可能联网。"
            },
        )

        // 模型管理
        Spacer(Modifier.height(MibotSpace.L))
        MibotSectionHeader("模型管理")
        VoiceModelManager.CATALOG.forEach { info ->
            val isInstalled = info.id in installedIds
            val st = progress[info.id] ?: VoiceModelManager.DownloadState.Idle
            MibotCard(padding = androidx.compose.foundation.layout.PaddingValues(vertical = MibotSpace.S)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = MibotSpace.M),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(MibotIcon.RowChip)
                            .frosted(RoundedCornerShape(10.dp), FrostLevel.Card),
                        contentAlignment = Alignment.Center,
                    ) {
                        Glyph(
                            when (info.kind) {
                                VoiceModelKind.ASR -> LineIcons.AudioLines
                                VoiceModelKind.TTS -> LineIcons.Volume2
                            },
                            size = MibotIcon.Standard,
                            tint = MibotTokens.iconTint(),
                        )
                    }
                    Spacer(Modifier.size(MibotSpace.M))
                    Column(Modifier.weight(1f)) {
                        Text(
                            info.title,
                            fontSize = MibotType.Body,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            info.subtitle,
                            fontSize = MibotType.Secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when (st) {
                            is VoiceModelManager.DownloadState.Downloading -> {
                                Spacer(Modifier.height(6.dp))
                                ProgressBar(st.percent)
                                Text(
                                    "下载中 ${st.percent}% · ${st.bytesSoFar / (1024 * 1024)}MB / ${st.total / (1024 * 1024)}MB",
                                    fontSize = MibotType.Caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            VoiceModelManager.DownloadState.Extracting -> {
                                Spacer(Modifier.height(6.dp))
                                Text("解包中…", fontSize = MibotType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            is VoiceModelManager.DownloadState.Failed -> {
                                Spacer(Modifier.height(6.dp))
                                Text("失败:${st.message}", fontSize = MibotType.Caption, color = MibotTokens.errColor())
                            }
                            else -> Unit
                        }
                    }
                    Spacer(Modifier.size(MibotSpace.S))
                    Column(horizontalAlignment = Alignment.End) {
                        StatusChip(
                            text = if (isInstalled) "已安装" else "未安装",
                            kind = if (isInstalled) ChipKind.OK else ChipKind.NEUTRAL,
                        )
                        Spacer(Modifier.height(MibotSpace.S))
                        if (isInstalled) {
                            MibotButton(
                                text = "删除",
                                onClick = {
                                    deleteError = null
                                    deleteCandidate = info
                                },
                                tonal = true,
                                compact = true,
                            )
                        } else {
                            MibotButton(
                                text = when (st) {
                                    is VoiceModelManager.DownloadState.Downloading -> "下载中"
                                    VoiceModelManager.DownloadState.Extracting -> "解包中"
                                    else -> "下载"
                                },
                                onClick = {
                                    scope.launch {
                                        VoiceModelManager.download(ctx, info)
                                        refreshInstalled()
                                    }
                                },
                                enabled = st !is VoiceModelManager.DownloadState.Downloading &&
                                    st != VoiceModelManager.DownloadState.Extracting,
                                compact = true,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(MibotSpace.S))
        }

        Spacer(Modifier.height(MibotSpace.M))
        MibotSectionHeader("系统语音服务")
        MibotCard {
            Column(Modifier.padding(vertical = MibotSpace.S)) {
                Text(
                    if (recognitionIssue == null) "系统识别服务可用；朗读与识别是否联网取决于所选系统服务。"
                    else recognitionIssue!!,
                    fontSize = MibotType.Secondary,
                    color = if (recognitionIssue == null) MaterialTheme.colorScheme.onSurfaceVariant else MibotTokens.errColor(),
                )
                Spacer(Modifier.height(MibotSpace.S))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MibotButton(
                        text = "系统语音输入设置",
                        onClick = { openSystemSetting(Settings.ACTION_VOICE_INPUT_SETTINGS, "语音输入") },
                        tonal = true,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .semantics { contentDescription = "Mikova System Voice Settings" },
                    )
                    MibotButton(
                        text = "系统文字转语音设置",
                        onClick = { openSystemSetting("com.android.settings.TTS_SETTINGS", "文字转语音") },
                        tonal = true,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .semantics { contentDescription = "Mikova System TTS Settings" },
                    )
                }
            }
        }
        Spacer(Modifier.height(MibotSpace.M))
        MibotFootnote(
            "模型来自 k2-fsa/sherpa-onnx 官方 Releases，下载到应用私有目录。两包共约 542.8MB，解包需更多空间，建议 Wi-Fi。sherpa-onnx 代码许可为 Apache-2.0；模型许可独立。",
        )
        Spacer(Modifier.height(MibotSpace.L))
    }
}

/** 细进度条(模型下载用)。 */
@Composable
private fun ProgressBar(percent: Int) {
    val frac = percent.coerceIn(0, 100) / 100f
    Row(
        Modifier.fillMaxWidth().heightIn(min = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .frosted(RoundedCornerShape(50), FrostLevel.Card, border = false, tint = MibotTokens.field()),
        )
        Box(
            Modifier
                .weight(frac.coerceAtLeast(0.01f))
                .height(6.dp)
                .frosted(RoundedCornerShape(50), FrostLevel.Card, border = false, tint = MibotTokens.accent()),
        )
        Spacer(Modifier.weight((1f - frac).coerceAtLeast(0.001f)))
    }
}
