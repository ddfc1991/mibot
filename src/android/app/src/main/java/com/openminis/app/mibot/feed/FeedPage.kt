package com.openminis.app.mibot.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.mibot.design.Glyph
import com.openminis.app.mibot.design.LineGlyph
import com.openminis.app.mibot.design.LineIcons
import com.openminis.app.mibot.design.MibotCard
import com.openminis.app.ui.glass.FrostLevel
import com.openminis.app.mibot.design.MibotPageTitle
import com.openminis.app.mibot.design.MibotTokens
import com.openminis.app.mibot.design.MibotType
import com.openminis.app.mibot.design.t
import com.openminis.app.ui.glass.LocalGlassBottomInset
import kotlinx.coroutines.delay

private data class KindStyle(val glyph: LineGlyph, val color: Color, val zh: String, val en: String)

private fun kindStyle(kind: String): KindStyle = when (kind) {
    FeedKind.TASK_RESULT -> KindStyle(LineIcons.CalendarCheck, MibotTokens.Sky, "定时任务", "Scheduled task")
    FeedKind.KEY_ACQUIRED -> KindStyle(LineIcons.KeyRound, MibotTokens.Amber, "保险库", "Vault")
    FeedKind.PLUGIN_EVENT -> KindStyle(LineIcons.Puzzle, MibotTokens.Purple, "插件", "Plugin")
    FeedKind.FEIYU_DIARY -> KindStyle(LineIcons.Pencil, MibotTokens.Green, "日记", "Diary")
    FeedKind.GROUP_SUMMARY -> KindStyle(LineIcons.Users, MibotTokens.Teal, "群聊", "Group chat")
    else -> KindStyle(LineIcons.Activity, MibotTokens.Brick, "动态", "Activity")
}

/**
 * [fork/mibot-feed] 动态 tab: real entries from [Feed] (newest first), like,
 * 讨论 (new chat prefilled), delete with confirm. Tapping a card opens its
 * source chat / group when it has one.
 */
@Composable
fun FeedPage(
    onDiscuss: (String) -> Unit,
    onOpenSession: (String) -> Unit = {},
    onOpenGroup: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val bottom = LocalGlassBottomInset.current
    val store = remember { Feed.store(ctx) }
    val entries by store.entries.collectAsState()
    var confirmDelete by remember { mutableStateOf<FeedEntry?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val zh = LocalConfiguration.current.locales.let { !it.isEmpty && it[0].language == "zh" }
    LaunchedEffect(Unit) {
        store.refresh()
        while (true) { delay(60_000); now = System.currentTimeMillis() }
    }
    val groups = remember(entries) { dayGroups(entries) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { MibotPageTitle(t("动态", "Feed"), t("应用里发生的事，最新的在最上面", "What happened in the app, newest first")) }
        if (entries.isEmpty()) {
            item { FeedEmpty() }
        } else {
            groups.forEach { (label, list) ->
                item(key = "h_$label") {
                    Text(
                        label,
                        fontSize = MibotType.Section,
                        fontWeight = MibotType.Semi,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
                    )
                }
                items(list, key = { it.id }) { e ->
                    FeedCard(
                        e = e,
                        timeLabel = FeedStore.relativeTime(e.ts, now, zh),
                        onLike = { store.toggleLike(e.id) },
                        onDiscuss = { onDiscuss(listOf(e.title, e.body).filter { it.isNotBlank() }.joinToString("\n")) },
                        onDelete = { confirmDelete = e },
                        onOpen = sourceOpener(e.sourceRef.orEmpty(), onOpenSession, onOpenGroup),
                    )
                }
            }
        }
    }

    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(t("删除这条动态？", "Delete this entry?")) },
            text = { Text(e.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { store.delete(e.id); confirmDelete = null }) {
                    Text(t("删除", "Delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(t("取消", "Cancel")) } },
        )
    }
}

private fun sourceOpener(ref: String, onSession: (String) -> Unit, onGroup: (String) -> Unit): (() -> Unit)? = when {
    ref.startsWith("session:") -> ({ onSession(ref.removePrefix("session:")) })
    ref.startsWith("group:") -> ({ onGroup(ref.removePrefix("group:")) })
    else -> null
}

private fun dayGroups(entries: List<FeedEntry>): List<Pair<String, List<FeedEntry>>> {
    val cal = java.util.Calendar.getInstance()
    val now = System.currentTimeMillis()
    cal.timeInMillis = now
    val today = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
    cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
    val yesterday = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
    val fmt = java.text.SimpleDateFormat("MM月dd日", java.util.Locale.getDefault())
    return entries.groupBy { e ->
        val c = java.util.Calendar.getInstance().apply { timeInMillis = e.ts }
        val day = c.get(java.util.Calendar.YEAR) * 1000 + c.get(java.util.Calendar.DAY_OF_YEAR)
        when (day) {
            today -> "今天"
            yesterday -> "昨天"
            else -> fmt.format(java.util.Date(e.ts))
        }
    }.toList()
}

@Composable
private fun FeedEmpty() {
    MibotCard(Modifier.padding(top = 4.dp), frostLevel = FrostLevel.Content) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Glyph(LineIcons.Newspaper, size = 40.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Text(t("还没有动态", "Nothing here yet"), fontSize = MibotType.Title, fontWeight = MibotType.Semi, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                t("这里会自动出现：", "Things that will show up here:"),
                fontSize = MibotType.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            listOf(FeedKind.TASK_RESULT, FeedKind.KEY_ACQUIRED, FeedKind.PLUGIN_EVENT, FeedKind.GROUP_SUMMARY).forEach { k ->
                val s = kindStyle(k)
                val line = when (k) {
                    FeedKind.TASK_RESULT -> t("定时任务跑完后的结果", "Results when a scheduled task finishes")
                    FeedKind.KEY_ACQUIRED -> t("保险库里新保存的密钥（只显示服务名）", "Keys saved to the vault (service name only)")
                    FeedKind.PLUGIN_EVENT -> t("插件安装、启动、停止", "Plugins installed, started, stopped")
                    else -> t("群聊里 Bot 们的新回复", "New bot replies in group chats")
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    KindDot(s, 24)
                    Spacer(Modifier.width(10.dp))
                    Text(line, fontSize = MibotType.Secondary, lineHeight = MibotType.SecondaryLine, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                t("点 讨论 可以把任意一条带进新对话。", "Tap Discuss to take any entry into a new chat."),
                fontSize = MibotType.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun KindDot(s: KindStyle, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(s.color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Glyph(s.glyph, size = (size * 0.6f).dp, tint = s.color)
    }
}

@Composable
private fun FeedCard(
    e: FeedEntry,
    timeLabel: String,
    onLike: () -> Unit,
    onDiscuss: () -> Unit,
    onDelete: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    val s = kindStyle(e.kind)
    var expanded by rememberSaveable(e.id) { mutableStateOf(false) }
    val tint = MibotTokens.iconTint()
    MibotCard(onClick = onOpen, padding = PaddingValues(14.dp), frostLevel = FrostLevel.Content) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindDot(s, 30)
            Spacer(Modifier.width(10.dp))
            Text(t(s.zh, s.en), fontSize = MibotType.Secondary, fontWeight = MibotType.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(timeLabel, fontSize = MibotType.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Text(e.title, fontSize = MibotType.Body, fontWeight = MibotType.Semi, color = MaterialTheme.colorScheme.onSurface)
        if (e.body.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                e.body,
                fontSize = MibotType.Body,
                lineHeight = MibotType.BodyLine,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val heart = if (e.liked) MibotTokens.Brick else tint
            ActionPill(LineIcons.Heart, if (e.liked) t("已赞", "Liked") else t("赞", "Like"), heart, filled = e.liked, onClick = onLike)
            ActionPill(LineIcons.MessageCircle, t("讨论", "Discuss"), tint, onClick = onDiscuss)
            Spacer(Modifier.weight(1f))
            ActionPill(LineIcons.Trash2, t("删除", "Delete"), tint, onClick = onDelete)
        }
    }
}

@Composable
private fun ActionPill(glyph: LineGlyph, label: String, tint: Color, filled: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(glyph, size = 16.dp, tint = tint, duotone = filled)
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = MibotType.Secondary, color = tint)
    }
}
