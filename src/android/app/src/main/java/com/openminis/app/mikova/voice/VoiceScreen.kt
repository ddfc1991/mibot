package com.openminis.app.mikova.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
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

/**
 * [mikova/voice] 语音页(glass):大麦克风按钮(按住说 / 松手转文字)、实时识别文本流、
 * TTS 说话按钮、引擎状态 chip(sherpa / 平台降级)。
 *
 * 引擎由 [VoiceEngineFactory.build] 按 [prefer] 给出:sherpa 优先,不可用自动落平台 —— 调用方
 * 只看 [VoiceEngine],底下实现换掉这里一行不改。
 *
 * 按住说话:大按钮上 awaitFirstDown → 开始,waitForUpOrCancellation → 结束。终态文本两条路汇进同一个
 * [finalize]:平台引擎走 [VoiceEngine.Listener.onFinal],sherpa 走 stopListen 后的 onFinalSink。
 */
@Composable
fun VoiceScreen(
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    prefer: VoiceEngineId = VoiceEngineId.SHERPA,
    onOpenSettings: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val bottom = LocalGlassBottomInset.current

    val engine = remember(prefer) { VoiceEngineFactory.build(prefer, ctx) }
    var transcript by remember { mutableStateOf("") }
    var live by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("按住说话 · 松手转文字") }
    var level by remember { mutableStateOf(0f) }
    var speakText by remember { mutableStateOf("") }
    var speakBusy by remember { mutableStateOf(false) }
    var micGranted by remember {
        mutableStateOf(
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val reqPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (!granted) status = "需要麦克风权限才能识别"
    }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) { onDispose { engine.shutdown() } }

    // 引擎回调可能来自 ASR 线程(sherpa)或主线程(平台);统一 post 到主线程再写 Compose 状态。
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    fun onMain(block: () -> Unit) { main.post(block) }

    // 把一段终态文本收进 transcript(sherpa 与平台共用)。
    fun finalize(text: String) {
        live = ""
        if (text.isBlank()) {
            status = "没听清,再按一次"
            return
        }
        transcript = if (transcript.isBlank()) text else "$transcript $text"
        status = "已转文字"
    }

    val listener = remember {
        object : VoiceEngine.Listener {
            override fun onPartial(text: String) {
                onMain {
                    live = text
                    status = text.take(40)
                }
            }

            override fun onFinal(text: String) {
                onMain { finalize(text) }
            }

            override fun onError(code: VoiceEngine.VoiceError, message: String?) {
                onMain {
                    listening = false
                    live = ""
                    status = when (code) {
                        VoiceEngine.VoiceError.NO_MATCH -> "没听清,再按一次"
                        VoiceEngine.VoiceError.SPEECH_TIMEOUT -> "没听到声音"
                        VoiceEngine.VoiceError.PERMISSION -> "需要麦克风权限"
                        VoiceEngine.VoiceError.NETWORK -> "识别服务没连上"
                        VoiceEngine.VoiceError.UNAVAILABLE -> message ?: "引擎不可用"
                        else -> message ?: "识别没成功"
                    }
                }
            }

            override fun onLevel(db: Float) {
                onMain { level = db.coerceIn(-60f, 0f) }
            }
        }
    }

    // sherpa 的终态从 stopListen 的线程出来,经 sink 汇进同一个 finalize。
    LaunchedEffect(engine) {
        (engine as? SherpaVoiceEngine)?.onFinalSink { text -> onMain { finalize(text) } }
    }

    fun beginListen() {
        if (listening) return
        if (!micGranted) {
            reqPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!engine.isAvailable) {
            status = if (prefer == VoiceEngineId.SHERPA) "离线引擎未就绪,请到设置下载模型" else "这台设备不支持语音识别"
            return
        }
        live = ""
        status = "在听…"
        listening = true
        engine.startListen(VoiceEngine.LocaleCompat("zh-CN"), listener)
    }

    fun endListen() {
        if (!listening) return
        listening = false
        status = "识别中…"
        engine.stopListen()
    }

    MibotScaffold(
        title = "语音",
        onBack = onBack,
        actions = {
            Box(
                Modifier
                    .size(MibotIcon.TopButton)
                    .frosted(CircleShape, FrostLevel.Bar, elevation = 2.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            waitForUpOrCancellation()
                            onOpenSettings()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(LineIcons.Settings, size = MibotIcon.Dense, tint = MaterialTheme.colorScheme.onSurface)
            }
        },
    ) {
        // 引擎状态行
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MibotSpace.Screen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val usingSherpa = engine.id == VoiceEngineId.SHERPA && engine.isAvailable
            StatusChip(
                text = if (usingSherpa) "离线 sherpa" else "系统识别",
                kind = if (usingSherpa) ChipKind.OK else ChipKind.NEUTRAL,
            )
            if (prefer == VoiceEngineId.SHERPA && !engine.isAvailable) {
                StatusChip(text = VoiceEngineFactory.fallbackNotice ?: "已降级", kind = ChipKind.WARN)
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (engine.isAvailable) engine.displayName else "引擎不可用",
                fontSize = MibotType.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(MibotSpace.L))

        // 大麦克风按钮(按住说 / 松手停)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = MibotSpace.Screen),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(if (listening) 132.dp else 112.dp)
                    .frosted(CircleShape, FrostLevel.Card, elevation = if (listening) 6.dp else 2.dp, tint = MibotTokens.selected())
                    .pointerInput(engine.isAvailable) {
                        awaitEachGesture {
                            awaitFirstDown()
                            beginListen()
                            waitForUpOrCancellation()
                            endListen()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Glyph(LineIcons.Mic, size = 40.dp, tint = MaterialTheme.colorScheme.primary, duotone = listening)
                    if (listening) {
                        Spacer(Modifier.height(8.dp))
                        val bars = ((level + 60f) / 60f * 5).toInt().coerceIn(1, 5)
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            repeat(5) { i ->
                                Box(
                                    Modifier
                                        .size(width = 5.dp, height = if (i < bars) 14.dp else 6.dp)
                                        .frosted(CircleShape, FrostLevel.Card, border = false, tint = MibotTokens.accent()),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(MibotSpace.M))
            Text(status, fontSize = MibotType.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 实时识别文本流
        Spacer(Modifier.height(MibotSpace.L))
        MibotSectionHeader(
            "识别文本",
            actionLabel = if (transcript.isNotBlank()) "发出" else null,
            onAction = if (transcript.isNotBlank()) {
                { onSend(transcript) }
            } else null,
        )
        MibotCard {
            Column(Modifier.padding(MibotSpace.M)) {
                BasicTextField(
                    value = transcript,
                    onValueChange = { transcript = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    textStyle = TextStyle(
                        fontSize = MibotType.Body,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = MibotType.BodyLine,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
                if (live.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        live,
                        fontSize = MibotType.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // TTS
        Spacer(Modifier.height(MibotSpace.L))
        MibotSectionHeader("朗读(TTS)")
        MibotCard {
            Column(Modifier.padding(MibotSpace.M)) {
                BasicTextField(
                    value = speakText,
                    onValueChange = { speakText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    textStyle = TextStyle(
                        fontSize = MibotType.Body,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = MibotType.BodyLine,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box {
                            if (speakText.isBlank()) {
                                Text(
                                    "输入要朗读的文字",
                                    fontSize = MibotType.Body,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(MibotSpace.M))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MibotButton(
                        text = if (speakBusy) "合成中…" else "说话",
                        onClick = {
                            if (speakText.isBlank()) return@MibotButton
                            speakBusy = true
                            scope.launch {
                                engine.synthesize(speakText, play = true)
                                speakBusy = false
                            }
                        },
                        enabled = speakText.isNotBlank() && !speakBusy && engine.isAvailable,
                    )
                    MibotButton(text = "停", onClick = { engine.stopSpeaking() }, tonal = true)
                }
            }
        }

        Spacer(Modifier.height(MibotSpace.M))
        MibotFootnote(
            if (engine.isAvailable) {
                "离线模式:识别与合成都在本机完成,不出网。切换引擎或管理模型请点右上角设置。"
            } else {
                "离线引擎未就绪(缺 native so 或模型)。当前用系统识别兜底;到设置页下载模型后可切回离线。"
            },
        )
        Spacer(Modifier.height(bottom))
    }
}
