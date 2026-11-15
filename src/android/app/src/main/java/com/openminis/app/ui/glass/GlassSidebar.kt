package com.openminis.app.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

/**
 * [fork/glass-ui] The sidebar has one job: the recent list. Chats and bot
 * group chats sit together in a single "Recent" list; the only other controls
 * are search and new chat. Every other feature lives on its own page behind
 * the bottom navigation bar.
 */
@Composable
fun GlassSidebar(
    items: List<RecentItem>,
    haze: HazeState,
    onOpen: (RecentItem) -> Unit,
    onNewChat: () -> Unit,
    onOpenArchived: () -> Unit = {},
    onArchive: (RecentItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val style = LocalGlassStyle.current
    val dark = style == GlassStyle.DUSK
    val fg = if (dark) GlassPalette.DuskOn else GlassPalette.WarmOn
    val fgMuted = fg.copy(alpha = 0.72f) // [fork/frost] ≥4.5:1 on the sheet
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(items, query) { RecentItems.merge(items, emptyList(), query) }

    val hairline = GlassStyles.hairline(style)
    Box(
        modifier
            .width(minOf(LocalConfiguration.current.screenWidthDp * 0.84f, 340f).dp)
            .fillMaxHeight()
            .hazeEffect(state = haze, style = GlassStyles.sheet(style))
            .drawBehind {
                drawLine(hairline, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = 1f)
            },
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it }, fg, fgMuted, dark, Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onNewChat) {
                    Icon(Icons.Outlined.EditNote, contentDescription = stringResource(R.string.glass_new_chat), tint = fg)
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.glass_recent),
                color = fgMuted,
                fontSize = com.openminis.app.mibot.design.MibotType.Section,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
            )
            if (shown.isEmpty()) {
                Text(
                    stringResource(if (query.isBlank()) R.string.glass_empty_recent else R.string.glass_no_results),
                    color = fgMuted,
                    fontSize = com.openminis.app.mibot.design.MibotType.Body,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(shown, key = { it.id }) { item ->
                    RecentRow(item, fg, fgMuted, dark, onOpen = { onOpen(item) }, onLongPress = { onArchive(item) })
                }
            }
            // [fork/archive] 底部归档入口：收进归档的会话不占主列表，可在此查看。
            Spacer(Modifier.height(8.dp))
            ArchivedEntry(fg, fgMuted, dark, onClick = onOpenArchived)
        }
    }
}

/** [fork/archive] 侧边栏底部的「归档」入口。 */
@Composable
private fun ArchivedEntry(fg: Color, fgMuted: Color, dark: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Inventory2,
            contentDescription = null,
            tint = fgMuted,
            modifier = Modifier.size(com.openminis.app.mibot.design.MibotIcon.Dense),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            com.openminis.app.mibot.design.t("归档", "Archive"),
            color = fg,
            fontSize = com.openminis.app.mibot.design.MibotType.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            com.openminis.app.mibot.design.t("长按会话可归档", "Long-press a chat to archive"),
            color = fgMuted,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    fg: Color,
    fgMuted: Color,
    dark: Boolean,
    modifier: Modifier,
) {
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.05f))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = fgMuted, modifier = Modifier.size(com.openminis.app.mibot.design.MibotIcon.Dense))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(stringResource(R.string.glass_search_hint), color = fgMuted, fontSize = com.openminis.app.mibot.design.MibotType.Body)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = fg, fontSize = com.openminis.app.mibot.design.MibotType.Body),
                cursorBrush = SolidColor(fg),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RecentRow(item: RecentItem, fg: Color, fgMuted: Color, dark: Boolean, onOpen: () -> Unit, onLongPress: () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.title ?: stringResource(R.string.glass_untitled),
            color = fg,
            fontSize = com.openminis.app.mibot.design.MibotType.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (item is RecentItem.Group) {
            Spacer(Modifier.width(8.dp))
            GroupBadge(fgMuted, dark)
            if (item.members.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                StackedAvatars(item.members, dark)
            }
        }
    }
}

/** [fork/mibot-groups] Small 群聊 pill on group rows in the Recent list. */
@Composable
private fun GroupBadge(fgMuted: Color, dark: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(com.openminis.app.mibot.design.t("群聊", "Group"), color = fgMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

private val AvatarColors = listOf(
    // [fork/mibot-v5] Muted identity tones (see MibotTokens).
    Color(0xFF66778A), Color(0xFFA9604F), Color(0xFF6C8A74), Color(0xFF7F7591), Color(0xFFA68B5B),
)

/** Up to three small overlapping bot avatars (initials), for group rows. */
@Composable
private fun StackedAvatars(members: List<String>, dark: Boolean) {
    val shown = members.take(3)
    val ring = if (dark) Color(0xFF1B1D22) else Color.White
    Box(Modifier.width((18 + 11 * (shown.size - 1)).dp).height(18.dp)) {
        shown.forEachIndexed { i, name ->
            Box(
                Modifier
                    .offset(x = (11 * i).dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(AvatarColors[(name.hashCode() and 0x7fffffff) % AvatarColors.size])
                    .border(1.5.dp, ring, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1).uppercase(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
