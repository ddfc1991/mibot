package com.openminis.app.mibot.servers

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
 * [destiny/v7] 远程接入 · 服务器 (design screens 45b / 45c). Referenced from
 * [com.openminis.app.ui.navigation.AppNavigation] but never implemented. Minimal
 * compiling stub; wire to the real server vault when built.
 */
@Composable
fun ServerVaultPage(
    onBack: () -> Unit = {},
    onDiscuss: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("服务器", style = MaterialTheme.typography.headlineMedium)
        Text(
            "服务器管理占位。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack) { Text("返回") }
    }
}
