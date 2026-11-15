package com.openminis.app.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.navigation.Routes
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

/** [fork/glass-ui] Routes owned by the fork's glass UI. */
object GlassRoutes {
    const val FEED = "glass_feed"
    const val IDEAS = "glass_ideas"
    /** [fork/mibot-ui-tabs] Settings › Bots (no longer a bottom tab). */
    const val BOTS = "glass_bots"
    /** [fork/mibot-ui-tabs] Bottom tab 群聊. */
    const val GROUPS = "glass_groups"
    const val BACKDROP = "glass_backdrop"
}

@Composable
fun glassEnabled(): Boolean {
    val context = LocalContext.current
    val s by remember(context) { GlassPrefs.flow(context) }.collectAsState()
    return s.enabled
}

/**
 * [fork/mibot-ui-tabs] The chat the 对话 tab opens: the last chat shown in
 * this process, else the most recent top-level session, else a new draft.
 * Read-only over the chat store: nothing here writes sessions or prefs.
 */
object GlassChatTarget {
    /** Last chat the detail pane showed (raw id; may be a `__new__` draft). */
    @Volatile var lastShown: String? = null

    /** True when the list route showed the (onboarding) list, not a chat. */
    @Volatile var listIsHome: Boolean = false

    internal val scope = kotlinx.coroutines.MainScope()

    /**
     * Null means "show the list as before": a device with neither a session
     * nor a provider keeps the onboarding steps the list carries.
     */
    suspend fun resolve(context: android.content.Context): String? {
        val app = context.applicationContext as? com.openminis.app.MinisApp ?: return null
        if (!app.subsystemsReady()) return null
        val chats = app.chatRepository
        lastShown?.let { raw ->
            val id = com.openminis.app.ui.chat.ChatViewModelStore.resolvePersistedId(raw)
            if (com.openminis.app.ui.navigation.isDraftSessionId(id)) return id
            val row = runCatching { chats.getSession(id) }.getOrNull()
            if (row != null && !row.isChild) return id
        }
        // Same breakers the cold-start resolver honours: after repeated
        // hangs/crashes do not re-enter the session that may be the trigger.
        val forceFresh = runCatching {
            com.openminis.app.diagnostics.HangDetector.shouldForceHomeOnLaunch(context) ||
                com.openminis.app.crash.CrashFrequencyDetector.shouldForceHomeOnLaunch(context) ||
                com.openminis.app.diagnostics.LaunchCycleBeacon.shouldForceHomeOnLaunch()
        }.getOrDefault(false)
        val sessions = runCatching { chats.dao.listSessions() }.getOrDefault(emptyList())
        if (sessions.isEmpty() && app.providerRepository.instances.isEmpty()) return null
        if (!forceFresh) sessions.firstOrNull { !it.isChild }?.let { return it.id }
        return com.openminis.app.ui.chat.ChatViewModelStore.newChatDraftId()
    }
}

/**
 * Switch top-level tab. Tabs sit one level above the chat list so Back from
 * any tab returns to Chats, and each tab keeps its state (save/restore).
 *
 * [fork/mibot-ui-tabs] 对话 opens a chat directly (see [GlassChatTarget]);
 * the conversation list lives in the left drawer.
 */
fun NavHostController.navigateToGlassTab(tab: GlassTab) {
    val route = when (tab) {
        GlassTab.CHATS -> Routes.SESSION_LIST
        GlassTab.FEED -> GlassRoutes.FEED
        GlassTab.IDEAS -> GlassRoutes.IDEAS
        GlassTab.GROUPS -> GlassRoutes.GROUPS
        GlassTab.SETTINGS -> Routes.SETTINGS
    }
    if (currentDestination?.route == route) return
    if (tab == GlassTab.CHATS) {
        if (currentDestination?.route == Routes.CHAT) return
        val nav = this
        GlassChatTarget.scope.launch {
            val target = GlassChatTarget.resolve(nav.context)
            if (target == null) {
                if (!nav.popBackStack(Routes.SESSION_LIST, inclusive = false)) {
                    nav.navigate(Routes.SESSION_LIST) { launchSingleTop = true }
                }
            } else {
                nav.navigate(Routes.chat(target)) {
                    popUpTo(Routes.SESSION_LIST) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
        return
    }
    navigate(route) {
        popUpTo(Routes.SESSION_LIST) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Wraps the chat list/detail pair in a modal drawer whose only job is the
 * recent list (ChatGPT-style). The pair is the Haze source the frosted sheet
 * blurs.
 */
@Composable
fun GlassDrawerHost(
    navController: NavHostController,
    chatRepository: ChatRepository,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val haze = rememberHazeState()
    val sessions by remember(chatRepository) { chatRepository.observeSessions() }
        .collectAsState(initial = emptyList())
    // [fork/mibot-groups] Bot group chats ride the same mixed Recent list.
    val context = LocalContext.current
    val groupStore = remember(context) { com.openminis.app.mibot.groups.GroupChats.store(context) }
    val groups by groupStore.groups.collectAsState()
    val items = remember(sessions, groups) {
        if (GlassDemo.enabled) GlassDemo.items() else {
            val names = com.openminis.app.mibot.groups.GroupChats.roster(context).associate { it.id to it.displayName(context) }
            val groupRows = groups.map { g ->
                RecentItem.Group(g.id, g.title, g.updatedAt, g.memberBotIds.mapNotNull { names[it] })
            }
            RecentItems.merge(RecentItems.fromSessions(sessions), groupRows, "")
        }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        scrimColor = Color.Black.copy(alpha = 0.16f),
        drawerContent = {
          GlassStyleTheme {
            GlassSidebar(
                items = items,
                haze = haze,
                onOpen = { item ->
                    scope.launch { drawerState.close() }
                    if (item is RecentItem.Chat && !item.id.startsWith("demo-")) {
                        navController.navigate(Routes.chat(item.id)) {
                            popUpTo(Routes.SESSION_LIST) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                    if (item is RecentItem.Group && !item.id.startsWith("demo-")) {
                        navController.navigate(com.openminis.app.mibot.groups.GroupRoutes.chat(item.id)) {
                            launchSingleTop = true
                        }
                    }
                },
                onNewChat = {
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.chat("__new__${java.util.UUID.randomUUID()}")) {
                        popUpTo(Routes.SESSION_LIST) { inclusive = false }
                    }
                },
                onOpenArchived = {
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.ARCHIVED) { launchSingleTop = true }
                },
                onArchive = { item ->
                    // 长按会话行 → 归档（仅 Chat 类型；Group 无单会话 id）
                    if (item is RecentItem.Chat && !item.id.startsWith("demo-")) {
                        scope.launch {
                            chatRepository.archiveSession(item.id)
                            drawerState.close()
                        }
                    }
                },
            )
          }
        },
    ) {
        Box(Modifier.fillMaxSize().hazeSource(haze)) {
            content { scope.launch { drawerState.open() } }
        }
    }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
}
