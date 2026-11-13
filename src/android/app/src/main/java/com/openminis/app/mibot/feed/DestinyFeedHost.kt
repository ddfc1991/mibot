package com.openminis.app.mibot.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * [destiny/v7] 动态 host: three states (在看 / 想到的 / 能领的). The real feed
 * host was referenced from [com.openminis.app.ui.navigation.AppNavigation] but
 * never landed. Minimal compiling stub; wire to the real feed store when built.
 */
@Composable
fun DestinyFeedHost(
    onDiscuss: (String) -> Unit = {},
    onOpenSession: (String) -> Unit = {},
    onOpenGroup: (String) -> Unit = {},
    onChat: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("动态", style = MaterialTheme.typography.headlineMedium)
        Text(
            "动态信息流占位 · 点下方进入对话",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { onChat("") }) { Text("开始对话") }
        OutlinedButton(onClick = { onDiscuss("") }) { Text("讨论") }
    }
}
