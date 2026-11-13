package com.openminis.app.mibot.v7

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.data.repository.ChatRepository

/**
 * [destiny/v7] The 56-screen design spec was meant to be the app's real
 * interface, but the implementation package never landed in the repo. This is
 * a minimal compiling stub for the V7 home / spec index that surfaces the real
 * feature entry points through [V7Actions] so the start destination is
 * navigable. Replace with the real [DestinyV7Home] when the design package is
 * built.
 */
@Composable
fun DestinyV7Home(
    actions: V7Actions,
    initialScreen: String = "01",
    initialIndex: Boolean = false,
    chatRepository: ChatRepository,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            if (initialIndex) "功能索引" else "Mibot V7",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "设计稿 V7（$initialScreen）占位 · 下方直达真实功能",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = actions.onNewChat) { Text("新建对话") }
        OutlinedButton(onClick = actions.onFeed) { Text("动态") }
        OutlinedButton(onClick = actions.onVoice) { Text("语音") }
        OutlinedButton(onClick = actions.onServers) { Text("服务器") }
        OutlinedButton(onClick = actions.onAgents) { Text("智能体") }
        OutlinedButton(onClick = actions.onMcp) { Text("MCP") }
        OutlinedButton(onClick = actions.onWechat) { Text("微信") }
        OutlinedButton(onClick = actions.onWallet) { Text("钱包") }
        OutlinedButton(onClick = actions.onAppAccess) { Text("应用接入") }
        OutlinedButton(onClick = actions.onSandbox) { Text("沙箱") }
        OutlinedButton(onClick = actions.onMonitor) { Text("监控") }
        OutlinedButton(onClick = actions.onLegacy) { Text("原功能") }
    }
}

/**
 * [destiny/v7] Honest "not wired to a backend yet" screen (微信 / 钱包 etc.).
 * Stub.
 */
@Composable
fun DestinyV7NotWired(
    which: String,
    onBack: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    onOpenProviders: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("「$which」尚未接入", style = MaterialTheme.typography.headlineSmall)
        Text(
            "此页设计稿已就位，后端尚未接入，敬请期待。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack) { Text("返回") }
    }
}

/**
 * [destiny/queue-real] Real task queue page (AgentJobRegistry). Stub renders a
 * placeholder; wire to AgentJobRegistry when the queue UI is built.
 */
@Composable
fun DestinyV7QueueScreen(onBack: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("任务队列", style = MaterialTheme.typography.headlineSmall)
        Text(
            "队列界面占位。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack) { Text("返回") }
    }
}

/**
 * [destiny/v7] App-access (应用接入) screen. Stub.
 */
@Composable
fun DestinyV7AppAccessScreen(onBack: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("应用接入", style = MaterialTheme.typography.headlineSmall)
        Text(
            "应用接入界面占位。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onBack) { Text("返回") }
    }
}
