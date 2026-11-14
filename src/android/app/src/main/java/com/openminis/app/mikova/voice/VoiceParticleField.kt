package com.openminis.app.mikova.voice

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.mibot.design.Glyph
import com.openminis.app.mibot.design.LineIcons
import com.openminis.app.ui.glass.BackdropMotion
import com.openminis.app.ui.glass.FrostLevel
import com.openminis.app.ui.glass.GlassPrefs
import com.openminis.app.ui.glass.ambientMotionAllowed
import com.openminis.app.ui.glass.frosted
import com.openminis.app.ui.theme.LocalMibotDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A single bounded canvas. Motion conveys input/output, never a fabricated bearing. */
@Composable
internal fun VoiceParticleField(
    listening: Boolean,
    speaking: Boolean,
    busy: Boolean,
    inputLevelDb: Float,
    outputLevel: Float,
    balance: Float?,
    onMic: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val settings by remember(context) { GlassPrefs.flow(context) }.collectAsState()
    val allowed by produceState(ambientMotionAllowed(context), owner, settings.motion) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) { value = ambientMotionAllowed(context); delay(1_000) }
        }
    }
    val animated = (listening || speaking || busy) && allowed && settings.motion == BackdropMotion.FULL
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animated, owner) {
        if (!animated) { phase = 0f; return@LaunchedEffect }
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val start = SystemClock.elapsedRealtime()
            while (isActive) {
                phase = ((SystemClock.elapsedRealtime() - start) % 6_000) / 6_000f
                delay(33)
            }
        }
    }
    val dark = LocalMibotDark.current
    val inputColor = if (dark) Color(0xFF9ADAC9) else Color(0xFF286A63)
    val color = if (listening) inputColor else MaterialTheme.colorScheme.primary
    val energy = when {
        listening -> ((inputLevelDb + 60f) / 60f).coerceIn(0f, 1f)
        speaking -> (sqrt(outputLevel.coerceIn(0f, 1f)) * 1.5f).coerceIn(0f, 1f)
        else -> 0f
    }
    val state = when {
        listening -> "正在聆听"
        speaking -> "正在朗读"
        busy -> "处理中"
        else -> "就绪"
    }
    Box(Modifier.fillMaxWidth().height(248.dp).semantics {
        contentDescription = "Quantum voice field"
        stateDescription = state
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(232.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outer = size.minDimension * 0.44f
            drawCircle(color.copy(alpha = if (dark) 0.1f else 0.05f), outer * 0.65f, center)
            drawCircle(color.copy(alpha = 0.17f), outer * 0.77f, center, style = Stroke(1.dp.toPx()))
            for (i in 0 until 72) {
                val base = i / 72f
                val angle = base * (2f * PI.toFloat()) + phase * 0.35f
                val travel = (phase * 2f + (i % 9) / 9f) % 1f
                val lane = if (listening) 1f - travel else travel
                val idleRadius = 0.75f + 0.09f * sin(i * 1.7f)
                val radius = outer * if (animated) (0.51f + lane * 0.45f) else idleRadius
                val wave = sin(angle * 3f + phase * PI.toFloat() * 2f) * energy * outer * 0.05f
                val x = center.x + cos(angle) * (radius + wave)
                val y = center.y + sin(angle) * (radius + wave)
                val alpha = if (animated) 0.22f + sin(lane * PI.toFloat()) * 0.46f else 0.32f
                val dot = (1.0f + (i % 3) * 0.4f + energy * 0.8f).dp.toPx()
                drawCircle(color.copy(alpha = alpha.coerceIn(0f, 0.85f)), dot, Offset(x, y))
            }
        }
        IconButton(
            onClick = onMic,
            modifier = Modifier.size(84.dp).frosted(CircleShape, FrostLevel.Card, elevation = 2.dp)
                .semantics {
                    contentDescription = "Mikova Voice Mic"
                    stateDescription = if (listening) "结束聆听" else "开始聆听，打断正在朗读的内容"
                },
        ) { Glyph(LineIcons.Mic, size = 36.dp, tint = color, duotone = listening) }
        if (listening || speaking || busy) {
            TextButton(onClick = onStop,
                modifier = Modifier.align(Alignment.BottomCenter).heightIn(min = 48.dp)
                    .semantics { contentDescription = "Mikova Voice Stop" }) { Text("停止") }
        } else if (balance != null) {
            Text("声道 1 / 声道 2 平衡：${(balance.coerceIn(-1f, 1f) * 100).toInt()}",
                modifier = Modifier.align(Alignment.BottomCenter), style = MaterialTheme.typography.labelSmall)
        }
    }
}
