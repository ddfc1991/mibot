package com.openminis.app.mikova.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.mibot.design.*
import com.openminis.app.ui.glass.LocalGlassBottomInset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The existing Mibot voice subpage, with one input/output field and real engine state. */
@Composable
fun VoiceScreen(
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    prefer: VoiceEngineId = VoiceEngineId.SHERPA,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("mikova_voice", Context.MODE_PRIVATE) }
    val engine = remember(prefer, context) { VoiceEngineFactory.build(prefer, context) }
    val scope = rememberCoroutineScope()
    val bottom = LocalGlassBottomInset.current
    var transcript by rememberSaveable { mutableStateOf(prefs.getString("transcript_draft", "").orEmpty()) }
    var speakText by rememberSaveable { mutableStateOf(prefs.getString("tts_draft", "").orEmpty()) }
    var live by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var decoding by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(-60f) }
    var balance by remember { mutableStateOf<Float?>(null) }
    var speakBusy by remember { mutableStateOf(false) }
    var speakJob by remember { mutableStateOf<Job?>(null) }
    var requestEpoch by remember { mutableStateOf(0) }
    var listenEpoch by remember { mutableStateOf(0) }
    var previewId by remember { mutableStateOf<String?>(null) }
    var showVoices by rememberSaveable { mutableStateOf(false) }
    var loadingVoices by remember(engine) { mutableStateOf(true) }
    var refreshVoices by remember { mutableStateOf(0) }
    var voices by remember(engine) { mutableStateOf<List<TtsVoiceChoice>>(emptyList()) }
    val voiceKey = if (engine.id == VoiceEngineId.SHERPA) "aishell3_voice_id" else "system_voice_id"
    var selectedVoice by remember(engine) { mutableStateOf(prefs.getString(voiceKey, null)) }
    val speakingFlow = remember(engine) { engine.speaking ?: MutableStateFlow(false) }
    val outputFlow = remember(engine) { engine.outputLevel ?: MutableStateFlow(0f) }
    val speaking by speakingFlow.collectAsState()
    val outputLevel by outputFlow.collectAsState()
    val currentTranscript by rememberUpdatedState(transcript)
    val currentSpeakText by rememberUpdatedState(speakText)
    var alive by remember(engine) { mutableStateOf(true) }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    fun saveDrafts() { prefs.edit().putString("transcript_draft", currentTranscript).putString("tts_draft", currentSpeakText).apply() }
    fun stopOutput() {
        requestEpoch++
        speakJob?.cancel()
        speakJob = null
        engine.stopSpeaking()
        speakBusy = false
        previewId = null
    }
    fun cancelInput() {
        listenEpoch++
        engine.cancel()
        listening = false
        decoding = false
        live = ""
        level = -60f
        balance = null
    }
    fun leave(action: () -> Unit) { saveDrafts(); stopOutput(); cancelInput(); action() }
    DisposableEffect(engine) {
        alive = true
        onDispose {
            alive = false
            saveDrafts()
            speakJob?.cancel()
            engine.shutdown()
        }
    }
    LaunchedEffect(transcript, speakText) { delay(250); saveDrafts() }
    LaunchedEffect(engine, refreshVoices) {
        loadingVoices = true
        try { voices = engine.loadTtsVoices(context) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { voices = emptyList() }
        finally { loadingVoices = false }
    }
    LaunchedEffect(decoding) {
        if (decoding) {
            delay(12_000)
            if (decoding) { cancelInput(); status = "识别等待超时，已保留原文字，可再次聆听。" }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = if (granted) "麦克风权限已开启，点按声场开始聆听。" else "麦克风权限未开启，原文字已保留；可到设置开启后重试。"
    }
    fun toggleListening() {
        if (listening) {
            listening = false; decoding = true; level = -60f; balance = null
            status = "正在整理识别文字…"
            engine.stopListen()
            return
        }
        stopOutput()
        cancelInput()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        if (!engine.isAvailable) {
            status = "当前识别引擎不可用，打开右上角设置检查语音服务或离线模型。"; return
        }
        val epoch = ++listenEpoch
        val listener = object : VoiceEngine.Listener {
            private fun update(action: () -> Unit) { handler.post { if (alive && epoch == listenEpoch) action() } }
            override fun onPartial(text: String) = update { live = text }
            override fun onFinal(text: String) = update {
                listening = false; decoding = false; level = -60f; balance = null; live = ""
                if (text.isNotBlank()) {
                    transcript = listOf(transcript.trim(), text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
                    status = "识别完成，可编辑文字或带入对话。"
                } else status = "没有识别出文字，靠近麦克风后再试一次。"
            }
            override fun onError(code: VoiceEngine.VoiceError, message: String?) = update {
                listening = false; decoding = false; level = -60f; balance = null; live = ""
                status = when (code) {
                    VoiceEngine.VoiceError.PERMISSION -> "麦克风权限不可用，请在系统权限页开启后重试。"
                    VoiceEngine.VoiceError.NETWORK -> "系统识别服务连接中断，文字已保留；检查网络后重试。"
                    VoiceEngine.VoiceError.SPEECH_TIMEOUT, VoiceEngine.VoiceError.NO_MATCH -> "没有听清，原文字已保留，再点一次开始。"
                    else -> (message ?: "识别引擎暂不可用") + "；文字已保留，可在设置切换引擎。"
                }
            }
            override fun onLevel(db: Float) = update { level = db.coerceIn(-60f, 0f) }
            override fun onDirection(horizontal: Float?) = update { balance = horizontal?.coerceIn(-1f, 1f) }
        }
        listening = true; status = "正在聆听…"
        try { engine.startListen(VoiceEngine.LocaleCompat("zh-CN"), listener) }
        catch (_: Exception) { cancelInput(); status = "无法打开麦克风，检查权限和系统语音服务后重试。" }
    }
    fun speak(text: String, voiceId: String?, preview: Boolean = false) {
        stopOutput(); cancelInput()
        if (text.isBlank()) return
        val epoch = requestEpoch
        previewId = if (preview) voiceId else null
        speakBusy = true; status = "正在准备语音，可随时停止。"
        speakJob = scope.launch {
            try {
                val audio = engine.synthesize(text, play = true, voiceId = voiceId)
                if (audio != null) {
                    while (alive && epoch == requestEpoch && engine.speaking?.value == true) delay(60)
                }
                if (alive && epoch == requestEpoch) status = if (audio == null)
                    engine.ttsStatus?.value ?: "合成未完成，文字已保留；检查音色或语音设置后重试。"
                else "朗读结束，可继续输入或切换音色。"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (alive && epoch == requestEpoch) status = "语音合成暂不可用，文字已保留，可切换音色重试。" }
            finally { if (alive && epoch == requestEpoch) { speakBusy = false; previewId = null } }
        }
    }
    MibotScaffold(
        title = "语音",
        onBack = { leave(onBack) },
        actions = {
            IconButton(onClick = { leave(onOpenSettings) }, modifier = Modifier.size(48.dp)
                .semantics { contentDescription = "Mikova Voice Settings" }) {
                Glyph(LineIcons.Settings, size = 24.dp, tint = MaterialTheme.colorScheme.onSurface)
            }
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(if (engine.id == VoiceEngineId.SHERPA) "本机离线引擎" else "系统语音服务", ChipKind.NEUTRAL)
            StatusChip(when { listening -> "正在聆听"; speaking -> "正在朗读"; speakBusy || decoding -> "处理中"; else -> "就绪" },
                if (listening || speaking) ChipKind.ACCENT else ChipKind.NEUTRAL)
        }
        VoiceParticleField(
            listening = listening,
            speaking = speaking,
            busy = speakBusy || decoding,
            inputLevelDb = level,
            outputLevel = outputLevel,
            balance = balance,
            onMic = { toggleListening() },
            onStop = { stopOutput(); cancelInput(); status = "已停止，文字草稿保留。" },
        )
        Text(
            when {
                listening -> "再点一次结束聆听"
                speaking -> "点麦克风打断并开始聆听"
                speakBusy || decoding -> "处理中，可随时停止"
                else -> "点按开始聆听"
            }, modifier = Modifier.align(Alignment.CenterHorizontally),
            fontSize = MibotType.Body, color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (balance == null) "当前无声源方位数据 · 粒子仅显示音量与收发状态"
            else "显示两个录音声道的能量差，不代表物理声源方位",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            fontSize = MibotType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status.isNotBlank()) Text(status, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            fontSize = MibotType.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MibotSectionHeader("识别文字")
        MibotCard(padding = PaddingValues(16.dp)) {
            OutlinedTextField(
                value = transcript, onValueChange = { transcript = it },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Mikova Voice Transcript" },
                placeholder = { Text("听到的内容会显示在这里，也可以直接输入。") },
                minLines = 2, maxLines = 5,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            )
            if (live.isNotBlank()) Text(live, modifier = Modifier.padding(vertical = 8.dp),
                fontSize = MibotType.Body, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { speakText = transcript; status = "已填入朗读区，可以编辑后播放。" },
                    enabled = transcript.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("填入朗读") }
                TextButton(onClick = { leave { onSend(transcript) } }, enabled = transcript.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Mikova Voice To Chat" }) { Text("带入对话") }
            }
        }
        Spacer(Modifier.height(12.dp))
        MibotSectionHeader("朗读与音色")
        MibotCard(padding = PaddingValues(16.dp)) {
            OutlinedTextField(
                value = speakText, onValueChange = { speakText = it },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Mikova Voice TTS Input" },
                placeholder = { Text("输入要朗读的文字") }, minLines = 2, maxLines = 4,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(voices.firstOrNull { it.id == selectedVoice }?.label ?: "当前引擎默认音色",
                fontSize = MibotType.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MibotButton(
                    when { speaking -> "停止朗读"; speakBusy -> "取消合成"; else -> "朗读" },
                    onClick = { if (speaking || speakBusy) { stopOutput(); status = "已停止，文字保留。" } else speak(speakText, selectedVoice) },
                    enabled = speaking || speakBusy || speakText.isNotBlank(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Mikova Voice Speak" },
                )
                MibotButton("选择音色", { showVoices = true }, tonal = true,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Mikova Voice Choices" })
            }
        }
        MibotFootnote(if (engine.id == VoiceEngineId.SHERPA)
            "当前使用本机语音模型。点按麦克风可打断朗读；这不是持续监听的免触控全双工通话。"
        else "当前使用系统语音服务，是否联网取决于识别服务和所选音色；不能保证离线。点按可打断朗读。")
        Spacer(Modifier.height(bottom))
    }
    if (showVoices) {
        AlertDialog(
            onDismissRequest = { stopOutput(); showVoices = false },
            title = { Text("选择中文音色") },
            text = {
                Column {
                    if (loadingVoices) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在读取当前引擎的音色…", modifier = Modifier.padding(vertical = 12.dp))
                    } else if (voices.isEmpty()) {
                        Text(if (engine.id == VoiceEngineId.SHERPA) "模型或离线运行库未就绪，请到语音设置检查。"
                            else "系统没有提供可用的中文音色。请安装系统中文语音包，或配置离线引擎。")
                        TextButton(onClick = { refreshVoices++ }) { Text("重新读取") }
                        TextButton(onClick = { stopOutput(); showVoices = false; leave(onOpenSettings) }) { Text("语音设置") }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(voices, key = { it.id }) { choice ->
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) {
                                        MibotRadioRow(choice.label, choice.detail, selectedVoice == choice.id, {
                                            selectedVoice = choice.id
                                            prefs.edit().putString(voiceKey, choice.id).apply()
                                        })
                                    }
                                    TextButton(onClick = {
                                        if (previewId == choice.id && (speakBusy || speaking)) stopOutput()
                                        else speak("你好，我是你的语音助手。", choice.id, preview = true)
                                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text(if (previewId == choice.id && (speakBusy || speaking)) "停止" else "试听")
                                    }
                                }
                                if (choice.online) Text("此音色会使用系统提供的在线服务", fontSize = MibotType.Caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (voices.isNotEmpty()) Text("只列出当前引擎实际提供的音色。试听会使用该引擎。",
                        fontSize = MibotType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { stopOutput(); showVoices = false }) { Text("完成") } },
        )
    }
}
