package com.openminis.app.ui.sessions

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.launch

/**
 * [fork/archive] 归档会话列表：已归档会话在此查看，可恢复或彻底删除。
 * 归档数据保留在 DB（backup/storage 仍可见），仅从主列表隐藏。
 */
@Composable
fun ArchivedSessionsScreen(
    chatRepository: ChatRepository,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<ChatSessionEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        sessions = chatRepository.listArchivedSessions()
        loaded = true
    }

    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF101216))) {
        // 顶部栏：返回 + 标题
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            Text(
                com.openminis.app.mibot.design.t("归档会话", "Archived sessions"),
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (loaded && sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    com.openminis.app.mibot.design.t("没有归档的会话", "No archived sessions"),
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    ArchivedRow(
                        session = session,
                        onRestore = {
                            scope.launch {
                                chatRepository.unarchiveSession(session.id)
                                sessions = chatRepository.listArchivedSessions()
                            }
                        },
                        onDelete = {
                            scope.launch {
                                chatRepository.deleteSession(session.id)
                                sessions = chatRepository.listArchivedSessions()
                            }
                        },
                        onOpen = { onOpenSession(session.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchivedRow(
    session: ChatSessionEntity,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            session.title ?: com.openminis.app.mibot.design.t("未命名会话", "Untitled"),
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onRestore) {
            Icon(
                Icons.Outlined.Restore,
                contentDescription = com.openminis.app.mibot.design.t("恢复", "Restore"),
                tint = androidx.compose.ui.graphics.Color(0xFF8AB4F8),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = com.openminis.app.mibot.design.t("删除", "Delete"),
                tint = androidx.compose.ui.graphics.Color(0xFFF28B82),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
