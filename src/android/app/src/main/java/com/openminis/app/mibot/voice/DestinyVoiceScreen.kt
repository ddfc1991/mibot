package com.openminis.app.mibot.voice

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
 * [destiny/v7] Screen 34 语音 (press-and-hold ASR + TTS). Referenced from
 * [com.openminis.app.ui.navigation.AppNavigation] but never implemented. Minimal
 * compiling stub; the real voice engine lives in
 * `com.openminis.app.mikova.voice`. Replace with the real design screen when
 * built.
 */
@Composable
fun DestinyVoiceScreen(
    onBack: () -> Unit = {},
    onSend: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("语音", style = MaterialTheme.typography.headlineMedium)
        Text(
            "语音界面占位。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { onSend("") }) { Text("发送") }
        OutlinedButton(onClick = onBack) { Text("返回") }
    }
}
