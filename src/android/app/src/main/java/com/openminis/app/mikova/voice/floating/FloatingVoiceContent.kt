package com.openminis.app.mikova.voice.floating

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore

/** deepdeep 风格浮层语音主界面。由 FloatingVoiceOverlay 的 ComposeView 承载。 */
@Composable
fun FloatingVoiceApp(
    onClose: () -> Unit,
    onCollapse: () -> Unit,
) {
    val messages by FloatingVoiceController.messages.collectAsState()
    val thinking by FloatingVoiceController.thinking.collectAsState()
    val input by FloatingVoiceController.input.collectAsState()
    val error by FloatingVoiceController.error.collectAsState()
    val listening by FloatingVoiceController.listening.collectAsState()
    val modelPath by FloatingVoiceController.modelPath.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(10.dp),
    ) {
        // 顶部:Home 标签 + 标题 + 头像 + 收起/关闭
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Home", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                "友好问候",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            // 头像
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("🤖", fontSize = 20.sp)
            }
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onCollapse) { Icon(Icons.Filled.ExpandMore, "收起", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        // 消息列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { m ->
                when (m.role) {
                    "user" -> UserBubble(m.text)
                    "assistant" -> AssistantBubble(m.text)
                    else -> SystemLine(m.text)
                }
            }
            // 思考状态
            if (thinking) {
                item { ThinkingLine() }
            }
        }

        // 错误条
        error?.let { err ->
            ErrorBar(err, onRetry = { FloatingVoiceController.clearError() })
        }

        // 模型路径
        if (modelPath.isNotBlank()) {
            Text(
                modelPath,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        // 底部胶囊输入
        ComposerBar(input, thinking, listening, onInput = FloatingVoiceController::setInput, onMic = FloatingVoiceController::startListen, onSend = FloatingVoiceController::send)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun AssistantBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun SystemLine(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

@Composable
private fun ThinkingLine() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("正在深度思考", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text("…", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ErrorBar(message: String, onRetry: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("⚠️", fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        Text("重试", fontSize = 13.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ComposerBar(
    input: String,
    thinking: Boolean,
    listening: Boolean,
    onInput: (String) -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        // 麦克风
        IconButton(onClick = onMic) {
            Icon(
                Icons.Filled.Mic,
                "语音",
                tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 输入
        BasicTextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.weight(1f),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
            ),
            decorationBox = { inner ->
                if (input.isEmpty()) {
                    Text("问我任何问题…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
                inner()
            },
        )
        // 发送 / 停止
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (thinking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (thinking) Icons.Filled.Stop else Icons.Filled.KeyboardArrowUp,
                if (thinking) "停止" else "发送",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun IconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
