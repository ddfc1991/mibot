package com.openminis.app.mikova.selfhost

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.mibot.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SelfHostRoutes { const val SCREEN = "mikova_selfhost" }

/** Connection settings, not a remote execution engine or a data migration switch. */
@Composable
fun SelfHostScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cfg = remember(context) { SelfHostConfig.shared(context) }
    val state by cfg.config.collectAsState()
    val scope = rememberCoroutineScope()
    var serverUrl by rememberSaveable { mutableStateOf(state.serverUrl) }
    var apiKey by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SelfHostTestResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var guide by rememberSaveable { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var epoch by remember { mutableStateOf(0) }
    val validation = EndpointPolicy.error(serverUrl, apiKey.isNotBlank() || state.hasApiKey)
    val valid = validation == null
    val normalized = if (valid) EndpointPolicy.normalize(serverUrl, apiKey.isNotBlank() || state.hasApiKey) else null
    val dirty = normalized != state.serverUrl || apiKey.isNotBlank()
    fun invalidate() { epoch++; job?.cancel(); job = null; busy = false; result = null }
    fun save() {
        if (!valid) return
        invalidate()
        val url = serverUrl
        val key = apiKey.trim().takeIf { it.isNotEmpty() }
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { cfg.save(url, key) }
                serverUrl = cfg.config.value.serverUrl
                apiKey = ""
                notice = "连接配置已保存，数据仍在本机。"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "保存未完成，请重试；输入已保留。" }
            finally { saving = false }
        }
    }
    MibotScaffold(title = t("自托管连接", "Self-host connection"), onBack = onBack) {
        MibotCard(padding = PaddingValues(16.dp)) {
            StatusChip(t("连接诊断", "Connection diagnostics"), ChipKind.NEUTRAL)
            Spacer(Modifier.height(8.dp))
            Text(t("当前支持保存地址、加密保存密钥和测试 HTTP 连通性。远端任务执行、数据同步和云端电脑尚未接通，数据继续保存在本机。",
                "Save a server URL and encrypted key, then test HTTP connectivity. Remote task execution, data sync and cloud computer use are not connected; data remains on this device."),
                fontSize = MibotType.Body, lineHeight = MibotType.BodyLine, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MibotSectionHeader(t("服务器连接", "Server connection"))
        MibotCard(padding = PaddingValues(16.dp)) {
            GlassField(serverUrl, { serverUrl = it; invalidate(); notice = "" },
                t("服务器地址", "Server URL"), placeholder = "https://your-server.example/health",
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.semantics { contentDescription = "Mikova SelfHost URL" })
            if (serverUrl.isNotBlank() && validation != null) Text(validation,
                modifier = Modifier.padding(vertical = 8.dp), fontSize = MibotType.Caption,
                color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            GlassField(apiKey, { apiKey = it; invalidate() }, t("API Key（可选）", "API key (optional)"),
                placeholder = if (state.hasApiKey) t("已保存；留空不变", "Saved; leave empty to retain") else t("仅在 HTTPS 下发送", "Sent over HTTPS only"),
                secret = true, modifier = Modifier.semantics { contentDescription = "Mikova SelfHost Key" })
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MibotButton(if (saving) t("保存中…", "Saving…") else t("保存配置", "Save config"), { save() },
                    enabled = valid && !saving && !busy && dirty,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Mikova SelfHost Save" })
                MibotButton(if (busy) t("取消检查", "Cancel") else t("测试连接", "Test connection"), {
                    if (busy) { invalidate(); notice = "检查已取消，配置保留。" }
                    else {
                        val own = ++epoch
                        busy = true; result = null; notice = ""
                        job = scope.launch {
                            try {
                                val tested = cfg.testConnection()
                                if (own == epoch) result = tested
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { if (own == epoch) notice = "连接检查暂不可用，请重试。" }
                            finally { if (own == epoch) busy = false }
                        }
                    }
                }, tonal = true, enabled = busy || (!dirty && valid && !saving),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Mikova SelfHost Test" })
            }
            if (dirty) Text(t("先保存当前输入，再测试已保存的地址。", "Save the current input before testing the saved endpoint."),
                modifier = Modifier.padding(top = 8.dp), fontSize = MibotType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            if (notice.isNotBlank()) Text(notice, modifier = Modifier.padding(top = 12.dp),
                fontSize = MibotType.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            result?.let { value ->
                Text("${value.message} · ${value.latencyMs} ms", modifier = Modifier.padding(top = 12.dp),
                    fontSize = MibotType.Secondary, color = if (value.ok) MibotTokens.okColor() else MaterialTheme.colorScheme.error)
            }
            if (state.hasApiKey) TextButton(onClick = { clearConfirm = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(t("移除已保存密钥", "Remove saved key"))
            }
        }
        MibotSectionHeader(t("如何接入", "Connection guide"))
        MibotCard(padding = PaddingValues(16.dp)) {
            TextButton(onClick = { guide = !guide }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { contentDescription = "Mikova SelfHost Guide" }) {
                Text(if (guide) t("收起连接说明", "Collapse guide") else t("展开连接说明", "Show guide"))
            }
            if (guide) {
                Text(t("填写你自己服务的健康检查地址，默认使用 HTTPS。测试只发一次 GET 请求，返回 HTTP 200 只证明地址可达；它不会把任务发给服务器，也不会上传聊天、记忆或账号。",
                    "Use your service's health endpoint, preferably HTTPS. This sends one GET request; HTTP 200 proves only that the endpoint responds. It does not dispatch tasks or upload chats, memories or accounts."),
                    fontSize = MibotType.Body, lineHeight = MibotType.BodyLine, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text(t("远端执行还需要确定服务端协议并接入执行器。当前没有已验证的一键部署镜像，因此不提供未经核实的 Docker 命令。",
                    "Remote execution still needs an agreed server protocol and executor integration. No verified one-click server image is available in this version."),
                    fontSize = MibotType.Body, lineHeight = MibotType.BodyLine, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MibotFootnote(t("API Key 在本机加密保存，不回显。检查不会自动跟随重定向；更换服务器时请重新填写密钥。",
            "Keys are encrypted locally and never shown. Tests do not follow redirects; enter a new key when changing servers."))
    }
    if (clearConfirm) AlertDialog(
        onDismissRequest = { clearConfirm = false },
        title = { Text(t("移除服务器密钥？", "Remove server key?")) },
        text = { Text(t("地址会保留，需要再次输入密钥才能通过认证。", "The URL is retained. Enter the key again to authenticate.")) },
        confirmButton = { TextButton(onClick = { invalidate(); cfg.clearApiKey(); clearConfirm = false }) { Text(t("移除", "Remove")) } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text(t("取消", "Cancel")) } },
    )
}
