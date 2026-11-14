package com.openminis.app.mikova.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.stateDescription
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
    val voicePrefs = remember(ctx) { ctx.getSharedPreferences("mikova_voice", android.content.Context.MODE_PRIVATE) }
    var ttsVoiceId by remember(prefer) {
        mutableStateOf(voicePrefs.getString(if (prefer == VoiceEngineId.SHERPA) "aishell3_voice_id" else "system_voice_id", null))
    }
    var voiceChoices by remember(engine.id) { mutableStateOf(engine.ttsVoices(ctx)) }
    LaunchedEffect(engine.id) {
        repeat(8) {
            if (voiceChoices.isNotEmpty()) return@LaunchedEffect
            kotlinx.coroutines.delay(250)
            voiceChoices = engine.ttsVoices(ctx)
        }
    }
    var showVoicePicker by remember { mutableStateOf(false) }
    var previewingVoice by remember { mutableStateOf<String?>(null) }
    val speaking by (engine.speaking ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val playbackLevel by (engine.outputLevel ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0f) }).collectAsState()
    var directionAvailable by remember { mutableStateOf(false) }
    var directionPan by remember { mutableStateOf<Float?>(null) }
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

            override fun onDirection(horizontal: Float?) {
                onMain {
                    directionAvailable = horizontal != null
                    directionPan = horizontal?.coerceIn(-1f, 1f)
                }
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
        engine.interrupt()
        speakBusy = false
        engine.interrupt()
        speakBusy = false
        directionPan = null
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
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(48.dp)
                    .frosted(CircleShape, FrostLevel.Bar, elevation = 2.dp)
                    .semantics { contentDescription = "Mikova Voice Settings" },
            ) {
                Glyph(LineIcons.Settings, size = MibotIcon.Dense, tint = MaterialTheme.colorScheme.onSurface)
            }
        },
    ) {
        // 引擎状态行与真实方向能力
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
            StatusChip(
                text = if (directionAvailable) "左右估计" else "单声道 · 无方向",
                kind = if (directionAvailable) ChipKind.ACCENT else ChipKind.NEUTRAL,
            )
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
                    .pointerInput(engine.isAvailable, speaking) {
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
            VoiceParticleField(
                active = listening || speaking,
                listening = listening,
                inputLevelDb = level,
                outputLevel = playbackLevel,
                pan = directionPan.takeIf { directionAvailable },
                onTap = if (speaking) { { engine.stopSpeaking(); speakBusy = false } } else null,
            )
            Text(
                when {
                    listening && directionAvailable -> "正在听 · 左右为麦克风声道能量估计"
                    listening -> "正在听 · 设备仅提供单声道，不显示方向"
                    speaking -> "正在朗读 · 粒子随实际播放音量扩散"
                    else -> "按住开始聆听；也可点击粒子场打断朗读"
                },
                fontSize = MibotType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                        text = when {
                            speakBusy && !speaking -> "合成中…"
                            speaking -> "停止朗读"
                            else -> "说话"
                        },
                        onClick = {
                            if (speaking || speakBusy) {
                                engine.stopSpeaking()
                                speakBusy = false
                            } else if (speakText.isNotBlank()) {
                                engine.interrupt()
                                speakBusy = true
                                scope.launch {
                                    try {
                                        engine.synthesize(speakText, play = true, voiceId = ttsVoiceId)
                                    } finally {
                                        speakBusy = false
                                    }
                                }
                            }
                        },
                        enabled = speakText.isNotBlank() && engine.isAvailable,
                    )
                    MibotButton(text = "音色", onClick = { showVoicePicker = true }, tonal = true,
                        enabled = voiceChoices.isNotEmpty())
                }
            }
        }

        if (showVoicePicker) {
            val selectedId = ttsVoiceId
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showVoicePicker = false },
                title = { Text(if (engine.id == VoiceEngineId.SHERPA) "AISHELL3 中文音色" else "系统中文音色") },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (voiceChoices.isEmpty()) {
                            Text(if (engine.id == VoiceEngineId.SHERPA) "先到语音设置下载 AISHELL3 模型，启用 174 个离线说话人。"
                                else "正在读取系统中文语音；若列表为空，请在系统文字转语音设置安装中文语音包。",
                                fontSize = MibotType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        voiceChoices.forEach { choice ->
                            val isPreviewing = previewingVoice == choice.id
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            com.openminis.app.mibot.design.MibotRadioRow(
                                title = choice.label,
                                subtitle = choice.detail + if (choice.online) " · 在线" else " · 本机",
                                selected = selectedId == choice.id || (selectedId == null && choice.id.endsWith(":0")),
                                onClick = {
                                    ttsVoiceId = choice.id
                                    engine.setTtsVoice(ctx, choice.id)
                                    ctx.getSharedPreferences("mikova_voice", android.content.Context.MODE_PRIVATE).edit()
                                        .putString(if (engine.id == VoiceEngineId.SHERPA) "aishell3_voice_id" else "system_voice_id", choice.id)
                                        .apply()
                                },
                            )
                            TextButton(onClick = {
                                if (isPreviewing) {
                                    engine.stopSpeaking()
                                    previewingVoice = null
                                } else {
                                    engine.setTtsVoice(ctx, choice.id)
                                    previewingVoice = choice.id
                                    scope.launch {
                                        try {
                                            engine.synthesize("你好，我是你的语音助手。", play = true, voiceId = choice.id)
                                        } finally {
                                            if (previewingVoice == choice.id) previewingVoice = null
                                        }
                                    }
                                }
                            }, enabled = !choice.online || engine.isAvailable, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (isPreviewing) "停止" else "试听")
                            }
                            }
                        }
                        Text("音色来自当前引擎；系统语音需本机安装，离线状态按语音包实际属性显示。",
                            fontSize = MibotType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = { TextButton(onClick = { showVoicePicker = false }) { Text("完成") } },
            )
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

@Composable
private fun VoiceParticleField(
    active: Boolean,
    listening: Boolean,
    inputLevelDb: Float,
    outputLevel: Float,
    pan: Float?,
    onTap: (() -> Unit)?,
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        val power = context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        val animator = runCatching {
            android.provider.Settings.Global.getFloat(context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        power || animator == 0f
    }
    val transition = rememberInfiniteTransition(label = "voiceParticles")
    val animatedPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "particlePhase",
    )
    val phase = if (active && !reduceMotion) animatedPhase else 0f
    val accent = MaterialTheme.colorScheme.primary
    val directionText = pan?.let { if (it > 0.12f) "右声道较强" else if (it < -0.12f) "左声道较强" else "左右声道接近" }
        ?: "单声道 · 无方向数据"
    Box(
        Modifier.fillMaxWidth().height(172.dp)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .semantics {
                contentDescription = "Quantum voice field"
                stateDescription = when {
                    listening -> "正在聆听；$directionText"
                    active -> "正在朗读"
                    else -> "静音"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 260.dp, height = 172.dp)) {
            val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
            val maxRadius = size.height * 0.44f
            val energy = if (listening) ((inputLevelDb + 60f) / 60f).coerceIn(0f, 1f)
                else outputLevel.coerceIn(0f, 1f)
            val safePhase = if (reduceMotion) 0f else phase
            drawCircle(accent.copy(alpha = 0.12f + energy * 0.18f), maxRadius * 0.65f, center)
            drawCircle(accent.copy(alpha = 0.16f + energy * 0.22f), maxRadius * 0.72f,
                center, style = Stroke(width = 1.2.dp.toPx()))
            for (i in 0 until 36) {
                val baseAngle = (Math.PI * 2.0 * i / 36.0).toFloat()
                val wave = (kotlin.math.sin(safePhase + i * 0.79f) + 1f) * 0.5f
                val amount = if (active) 0.13f + energy * 0.72f else 0.055f + wave * 0.05f
                // Stereo pan is a coarse left/right energy balance, never an absolute bearing.
                val panShift = (pan ?: 0f) * maxRadius * 0.22f
                val angle = baseAngle
                val radius = if (listening) maxRadius * (0.63f - amount * 0.28f + wave * 0.035f)
                    else maxRadius * (0.62f + amount * 0.34f + wave * 0.025f)
                val x = center.x + kotlin.math.cos(angle) * radius + if (kotlin.math.cos(angle) >= 0) panShift else -panShift
                val y = center.y + kotlin.math.sin(angle) * radius
                val alpha = (0.32f + energy * 0.55f + wave * 0.1f).coerceIn(0f, 0.95f)
                drawCircle(accent.copy(alpha = alpha), (1.4f + energy * 2.2f) * density,
                    androidx.compose.ui.geometry.Offset(x, y))
            }
        }
        if (active) {
            Text(if (listening) "LISTENING" else "SPEAKING",
                fontSize = 10.dp.value.sp, letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.88f))
        }
    }
}
