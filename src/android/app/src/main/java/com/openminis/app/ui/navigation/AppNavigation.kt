package com.openminis.app.ui.navigation

import com.openminis.app.ui.glass.frostSource
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.ui.settings.KEY_LAUNCH_SESSION
import com.openminis.app.ui.settings.getAppearancePrefs
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.chat.ChatScreen
import com.openminis.app.ui.sessions.SessionListScreen
import com.openminis.app.ui.settings.AboutScreen
import com.openminis.app.ui.settings.AddAgentLoopGroupsScreen
import com.openminis.app.ui.settings.AddAgentLoopModelsScreen
import com.openminis.app.ui.settings.AddCustomModelScreen
import com.openminis.app.ui.settings.BackgroundSettingsScreen
import com.openminis.app.ui.settings.AddModelsToGroupScreen
import com.openminis.app.ui.settings.ShadowVoiceDetailScreen
import com.openminis.app.ui.settings.AddProviderScreen
import com.openminis.app.ui.settings.ModelEntryDetailScreen
import com.openminis.app.ui.settings.ModelGroupDetailScreen
import com.openminis.app.ui.settings.ModelGroupsScreen
import com.openminis.app.ui.settings.ProviderDetailScreen
import com.openminis.app.ui.settings.ProviderListScreen
import com.openminis.app.ui.sandbox.FileBrowserScreen
import com.openminis.app.ui.sandbox.FileBrowserViewModel
import com.openminis.app.ui.sandbox.FileItem
import com.openminis.app.ui.sandbox.FilePreviewScreen
import com.openminis.app.ui.sandbox.RootfsManagementScreen
import com.openminis.app.ui.settings.EnvironmentVariablesScreen
import com.openminis.app.ui.settings.AppearanceScreen
import com.openminis.app.ui.settings.SettingsScreen
import com.openminis.app.ui.settings.SystemPermissionsScreen
import com.openminis.app.ui.settings.SessionStorageDetailScreen
import com.openminis.app.ui.settings.SkillDetailScreen
import com.openminis.app.ui.settings.StorageManagementScreen
import com.openminis.app.ui.settings.SkillFileViewerScreen
import com.openminis.app.ui.settings.UsageStatsScreen
import com.openminis.app.ui.settings.MinisSkillsBrowserScreen
import com.openminis.app.ui.settings.MountDetailScreen
import com.openminis.app.ui.settings.MountedFoldersScreen
import com.openminis.app.ui.settings.SharedFolderDetailScreen
import com.openminis.app.ui.settings.SharedFoldersScreen
import com.openminis.app.ui.settings.SkillsManagementScreen
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.settings.LogDetailScreen
import com.openminis.app.ui.settings.LogManagementScreen
import com.openminis.app.ui.settings.MemoryFileEditScreen
import com.openminis.app.ui.settings.MemoryManagementScreen
import com.openminis.app.ui.settings.OffloadPermissionScreen
import com.openminis.app.ui.settings.ShizukuPermissionScreen
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.TerminalSession
import com.openminis.app.ui.terminal.TerminalScreen
import com.openminis.app.ui.onboarding.OnboardingModelSelectionScreen
import kotlinx.coroutines.flow.first
import com.openminis.app.ui.glass.navigateToGlassTab

// T342: Material 3 motion easing curves. Compose-Material3 (1.3.x) ships
// `MotionScheme` only in 1.4-alpha; mirror the spec values directly so we
// don't take a dependency-bump tax just for two CubicBezierEasing instances.
// Source: m3.material.io/styles/motion/easing-and-duration/tokens-specs
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

object Routes {
    const val SESSION_LIST = "sessions"
    const val CHAT = "chat/{sessionId}"
    const val SETTINGS = "settings"
    const val PROVIDER_LIST = "providers"
    const val ADD_PROVIDER = "add_provider"
    const val PROVIDER_DETAIL = "provider/{instanceId}"
    /** [T-android-provider-voice] Read-only shadow Voice Service detail. */
    const val SHADOW_VOICE_DETAIL = "voice_service/{instanceId}"
    const val MODEL_GROUPS = "model_groups"
    const val MODEL_GROUP_DETAIL = "model_group/{groupId}"
    const val ADD_MODELS_TO_GROUP = "add_models_to_group/{groupId}"
    /** T185: picker that adds model *entries* to the agent-loop set. */
    const val ADD_MODELS_TO_AGENT_LOOP = "add_models_to_agent_loop"
    /** T185: picker that adds model *groups* to the agent-loop set. */
    const val ADD_GROUPS_TO_AGENT_LOOP = "add_groups_to_agent_loop"
    /** T171→T182: AGENT_LOOP_MODELS deprecated (the screen lived inside
     *  Settings, now the picker is a section inside ModelGroupsScreen).
     *  Route declared so any back-compat deep-link string from preview
     *  builds pops back instead of crashing. */
    const val AGENT_LOOP_MODELS = "agent_loop_models"
    const val MODEL_ENTRY_DETAIL = "model_entry/{instanceId}/{entryId}"
    const val ADD_CUSTOM_MODEL = "add_custom_model/{instanceId}"
    const val STORAGE = "storage"
    const val BACKUP = "backup"
    /**
     * [T-onboarding-restore-link] One-shot key the caller sets on its own
     * savedStateHandle before navigating to [BACKUP] to open on a given tab.
     * A handle value rather than a route argument: the BACKUP route string is
     * matched verbatim by getBackStackEntry / pop-to-BACKUP in the restore
     * browse flow, and an optional argument would change its pattern.
     */
    const val BACKUP_INITIAL_TAB_KEY = "backup_initial_tab"
    const val BACKUP_DESTINATIONS = "backup_destinations"
    const val BACKUP_HISTORY_DETAIL = "backup_history_detail"
    const val BACKUP_DESTINATION_BROWSE = "backup_destination_browse"
    const val RESTORE_BROWSE = "restore_browse"
    // [T-android-restore-server-list] The servers you can restore FROM, plus
    // an Add Server entry. Separate from BACKUP_DESTINATIONS, which is the
    // editable management screen for the same servers.
    const val RESTORE_SERVERS = "restore_servers"
    const val SESSION_STORAGE_DETAIL = "session_storage/{sessionId}"
    const val ROOTFS_MANAGEMENT = "rootfs_management"
    const val MIRROR_CATEGORY_DETAIL = "mirror_category/{categoryKey}"
    fun mirrorCategoryDetail(categoryKey: String) = "mirror_category/$categoryKey"
    const val FILE_BROWSER = "file_browser"
    const val FILE_PREVIEW = "file_preview"
    const val ENV_VARS = "env_vars"
    const val NETWORK = "network"
    // [FEAT-261021-04] The other pages of Settings > Network.
    const val NETWORK_TAILSCALE = "network_tailscale"
    const val NETWORK_PROXIES = "network_proxies"
    const val CONTAINER_DNS = "container_dns"
    const val SKILLS = "skills"
    const val SKILL_DETAIL = "skill/{skillId}"
    const val SKILL_FILE = "skill_file/{skillId}/{relativePath}"
    const val MINIS_SKILLS_BROWSER = "minis_skills_browser"

    fun skillDetail(skillId: String) = "skill/$skillId"
    fun skillFile(skillId: String, relativePath: String = "SKILL.md"): String {
        // Path may contain `/`, which the nav library treats as a route
        // separator. URL-encode so subdirectory paths survive a round-trip.
        val encoded = java.net.URLEncoder.encode(relativePath, "UTF-8").replace("+", "%20")
        return "skill_file/$skillId/$encoded"
    }
    const val TERMINAL = "terminal?initCommand={initCommand}&sessionId={sessionId}"
    fun terminal(initCommand: String? = null, sessionId: String? = null): String {
        // URLEncoder follows application/x-www-form-urlencoded — spaces become `+`.
        // Nav library only %-decodes the route, so `+` would reach the screen literally.
        // Replace `+` with `%20` so Nav decodes it back to a space.
        fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        val params = buildList {
            if (initCommand != null) add("initCommand=${enc(initCommand)}")
            if (sessionId != null) add("sessionId=${enc(sessionId)}")
        }
        return if (params.isEmpty()) "terminal" else "terminal?${params.joinToString("&")}"
    }
    /** Chat-files browser: opens FileBrowser rooted at /var/minis for the session. */
    const val CHAT_FILES = "chat_files/{sessionId}?path={path}"
    /** [path]: optional Linux folder to open at (a tapped minis:// folder link). */
    fun chatFiles(sessionId: String, path: String? = null) =
        if (path == null) "chat_files/$sessionId" else "chat_files/$sessionId?path=${android.net.Uri.encode(path)}"
    /** [T-agent-transcript-page] Full-screen read-only agent (child) transcript. */
    const val AGENT_TRANSCRIPT = "agent_transcript/{sessionId}"
    fun agentTranscript(sessionId: String) = "agent_transcript/$sessionId"
    const val MEMORY = "memory"
    /** [T-p2-agent-settings] Settings › Agents. */
    const val AGENTS = "agents"
    // [T-sub-agents-v1] The roster the main model delegates to by name.
    const val SUB_AGENTS = "sub_agents"
    const val SUB_AGENT_DETAIL = "sub_agent/{agentId}"
    /** [T-tools-granular-switches] Settings > Agent Runtime > Tools. */
    const val AGENT_TOOLS = "agent_tools"
    /** [T-mcp-integration-android] MCP Integrations management screen. */
    const val MCP = "mcp"
    /** [T-soul-md] SOUL.md editor. */
    const val SOUL = "soul"
    const val MEMORY_FILE_EDIT = "memory_file/{fileName}/{isGlobal}"
    const val PERMISSIONS = "permissions"
    /**
     * T322 / [T-android-privileged-backend]: Shizuku-protocol manager
     * walkthrough — handles both Shizuku and AXManager (they share the same
     * binder protocol + client SDK).
     */
    const val SHIZUKU = "shizuku"
    /** T323: System Permissions (Accessibility service status, etc.). */
    const val SYSTEM_PERMISSIONS = "system_permissions"
    const val USAGE_STATS = "usage_stats"
    const val LOGS = "logs"
    const val LOG_DETAIL = "log_detail/{fileName}"
    /** [T-config-readable] Logs → Config Changes → one record. */
    const val CONFIG_AUDIT_DETAIL = "config_audit_detail/{auditId}"
    const val APPEARANCE = "appearance"
    const val BACKGROUND = "background"
    const val ABOUT = "about"
    const val ONBOARDING_MODELS = "onboarding_models"
    /** T219-2: Mount external folders settings + detail. */
    const val MOUNTED_FOLDERS = "mounted_folders"
    const val MOUNTED_FOLDERS_DETAIL = "mounted_folders_detail/{mountId}"
    fun mountedFoldersDetail(mountId: String) = "mounted_folders_detail/$mountId"
    /** T235: Shared folders (Shared / Skills / Memory) — fixed list. */
    const val SHARED_FOLDERS = "shared_folders"
    const val SHARED_FOLDERS_DETAIL = "shared_folders_detail/{folderId}"
    fun sharedFoldersDetail(folderId: String) = "shared_folders_detail/$folderId"
    /** [T-android-scheduled-tasks-design] Scheduled tasks list + editor. */
    const val SCHEDULED_TASKS = "scheduled_tasks"
    const val SCHEDULED_TASK_EDIT = "scheduled_tasks/edit?taskId={taskId}"
    fun scheduledTaskEdit(taskId: String? = null): String =
        if (taskId == null) "scheduled_tasks/edit" else "scheduled_tasks/edit?taskId=$taskId"
    // [T-android-scheduled-tasks-run-records] per-task execution log.
    const val SCHEDULED_TASK_RUNS = "scheduled_tasks/runs/{taskId}"

    // [destiny/mibot-capability] 5 个能力页 Screen（DestinyCapabilityScreens.kt）。
    const val MIBOT_WECHAT = "mibot_wechat"
    const val MIBOT_APP_ACCESS = "mibot_app_access"
    const val MIBOT_WALLET = "mibot_wallet"
    const val MIBOT_VOICE = "mibot_voice"
    const val MIBOT_TASK_QUEUE = "mibot_task_queue"

    fun scheduledTaskRuns(taskId: String): String = "scheduled_tasks/runs/$taskId"

    fun logDetail(fileName: String) = "log_detail/$fileName"
    fun configAuditDetail(auditId: String) = "config_audit_detail/$auditId"
    fun sessionStorageDetail(sessionId: String) = "session_storage/$sessionId"
    fun memoryFileEdit(fileName: String, isGlobal: Boolean) = "memory_file/$fileName/$isGlobal"
    fun chat(sessionId: String) = "chat/$sessionId"
    fun providerDetail(instanceId: String) = "provider/$instanceId"
    fun shadowVoiceDetail(instanceId: String) = "voice_service/$instanceId"
    fun modelGroupDetail(groupId: String) = "model_group/$groupId"
    fun addModelsToGroup(groupId: String) = "add_models_to_group/$groupId"
    // [T-android-model-entry-route-slash-crash] entryId is a composite key
    // "<instanceId>/<modelId>" (compositeEntryKey) — it CONTAINS a '/'. Left
    // raw, that slash splits the route into an extra path segment, so the
    // built route no longer matches the registered MODEL_ENTRY_DETAIL pattern
    // (model_entry/{instanceId}/{entryId}) and navigate() throws
    // IllegalArgumentException "destination … cannot be found" — a guaranteed
    // crash on tapping any model whose id carries a '/'. URL-encode it so the
    // slash becomes %2F (one segment); the receiver decodes it back.
    fun modelEntryDetail(instanceId: String, entryId: String) =
        "model_entry/${android.net.Uri.encode(instanceId)}/${android.net.Uri.encode(entryId)}"
    fun addCustomModel(instanceId: String) = "add_custom_model/$instanceId"
}

/** Holder for file preview navigation state (not serializable via nav args). */
internal object FilePreviewHolder {
    var currentItem: FileItem? = null
    /**
     * The chat the previewed file was opened from, when there is one: a
     * minis:// link inside a previewed markdown document resolves against
     * that session's attachments / workspace, as it does in the chat.
     */
    var currentSessionId: String? = null
    var fileBrowserViewModel: FileBrowserViewModel? = null
}

@Composable
fun AppNavigation(
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    envVarRepository: EnvVarRepository? = null,
    skillRepository: SkillRepository? = null,
    mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
    memoryRepository: MemoryRepository? = null,
    navController: NavHostController = rememberNavController(),
    initialDeepLink: DeepLinkAction? = null,
) {
    val context = LocalContext.current
    val voicePrefs = remember(context) { context.getSharedPreferences("mikova_voice", android.content.Context.MODE_PRIVATE) }
    var voicePreference by remember(voicePrefs) {
        mutableStateOf(runCatching { com.openminis.app.mikova.voice.VoiceEngineId.valueOf(voicePrefs.getString("engine", "SHERPA") ?: "SHERPA") }
            .getOrDefault(com.openminis.app.mikova.voice.VoiceEngineId.SHERPA))
    }

    // T219-5: use the application-scoped singleton from MinisApp so UI
    // add/remove shares state with PRootKernel and the lifecycle re-probe
    // path. Pre-T219-5 this `remember { MountedFoldersStore(...) }` created
    // a SECOND independent instance — UI list updated but PRoot never
    // saw the change because PRootKernel.mountedFoldersStore pointed at
    // the application-scoped singleton in MinisApp.
    val mountedFoldersStore = remember {
        (context.applicationContext as com.openminis.app.MinisApp).mountedFoldersStore
    }

    // Handle initial deep link after composition
    LaunchedEffect(initialDeepLink) {
        when (initialDeepLink) {
            is DeepLinkAction.OpenTerminal -> {
                navController.safeNavigate(Routes.terminal(initialDeepLink.initCommand))
            }
            is DeepLinkAction.OpenSession -> {
                // T-double-chat-fix: on process-death recreation NavController
                // auto-restores [SESSION_LIST, chat/<id>] AND MainActivity
                // synthesizes an OpenSession deep-link from saved state. A
                // bare navigate() pushed a SECOND chat entry, forcing the
                // user to press back twice. launchSingleTop collapses the
                // duplicate; popUpTo(SESSION_LIST, saveState=true) +
                // restoreState=true preserves chat-screen state across the
                // hop. When the synthesized deep-link is a no-op (chat
                // already on top) launchSingleTop short-circuits.
                navController.safeNavigate(Routes.chat(initialDeepLink.sessionId)) {
                    popUpTo(Routes.SESSION_LIST) {
                        inclusive = false
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            is DeepLinkAction.CreateEnvironmentVariable -> {
                DeepLinkCoordinator.setPendingEnvVarCreate(
                    initialDeepLink.key,
                    initialDeepLink.value,
                    initialDeepLink.note,
                )
                navController.safeNavigate(Routes.ENV_VARS)
            }
            // T183: any settings screen reachable by route string.
            // [destiny/v7-deeplink] Handled by startDestination below: the
            // NavHost mounts DIRECTLY on the target route, so this branch
            // must not navigate again (it would stack a duplicate entry).
            // The old safeNavigate() here was dead code on cold start —
            // the start entry is not RESUMED yet mid-composition, the
            // guard dropped the call, and minis://settings/voice (and
            // every other settings link) silently landed on home.
            is DeepLinkAction.OpenSettingsScreen -> {}
            is DeepLinkAction.OpenPermissionSettings -> {
                navController.safeNavigate(Routes.PERMISSIONS)
            }
            // OpenHtmlPreview is handled by setting startDestination
            // (see below) so the NavHost mounts directly into the right
            // chat — no safeNavigate dance, no sessions-list flash.
            is DeepLinkAction.OpenHtmlPreview -> {}
            // App-icon quick actions: all three open a fresh draft chat.
            // Voice/camera additionally seed DeepLinkCoordinator.pendingChatAction
            // (done up-front in startDestination block below so the seed
            // lands before ChatScreen's first compose).
            is DeepLinkAction.NewChat,
            is DeepLinkAction.NewVoiceChat,
            is DeepLinkAction.NewCameraChat -> {
                // Navigation handled by startDestination = chat/<__new__…>
                // when the launch intent carries one of these actions.
                // Nothing to do here — see startDestination block below.
            }
            else -> {}
        }
    }

    // [destiny/v7-deeplink] Warm-start deep links no longer need a Compose-side
    // hook: MainActivity.onNewIntent → handleDeepLink already navigates
    // synchronously for OpenSettingsScreen (no RESUMED guard on that path),
    // and cold-start links are handled by startDestination below. Anything
    // registered here only duplicated those navigations and stacked
    // duplicate back-stack entries.

    // Resolve launch session preference once into a deferred navigation target.
    // T314: this LaunchedEffect fires the same frame the NavHost mounts, so
    // the SESSION_LIST start-destination's NavBackStackEntry is still in
    // STARTED state when we'd otherwise call navigate(). safeNavigate() —
    // designed to defang the back-then-tap race — early-returns whenever
    // the current entry isn't RESUMED, which silently dropped every
    // launch-session navigation and left the user on the home screen
    // regardless of mode 1 / 2 / 0. Wait for the start destination to
    // settle into RESUMED before navigating; for mode 3 (Home) we don't
    // need to navigate at all so we can skip the wait entirely.
    LaunchedEffect(Unit) {
        val hasDeepLink = initialDeepLink != null && initialDeepLink !is DeepLinkAction.Unknown
        if (hasDeepLink) return@LaunchedEffect
        val hasPendingShare =
            com.openminis.app.share.ShareCoordinator.bufferVersion.value > 0
        // [T-android-share-routing] A cold start that carries a share always
        // opens a NEW draft addressed to it, whatever the launch mode (mirrors
        // iOS). Auto mode used to resume a recent chat and pour the share
        // into it - the cross-session mix-up - and Auto with no sessions yet
        // returned early and stranded it on the list. Decided first, before
        // any suspending read, so this NavHost navigation wins the race with
        // the scaffold's router (which waits for RESUMED); if the scaffold
        // did route it, a chat is already open for it: navigate nowhere.
        if (hasPendingShare) {
            val draft = newDraftSessionId()
            if (com.openminis.app.share.ShareCoordinator.addressTo(draft)) {
                navController.navigate(Routes.chat(draft)) {
                    popUpTo(Routes.SESSION_LIST) { inclusive = false }
                }
            }
            return@LaunchedEffect
        }
        val rawMode = getAppearancePrefs(context).getInt(KEY_LAUNCH_SESSION, 0)
        // Hang-detector circuit breaker: if the previous launches racked up
        // ≥3 main-thread hangs, force mode = 3 (home) so we don't reopen
        // the session/chat that was hanging. The user clears this either
        // by completing a chat session quietly (HangDetector.markHealthyTick
        // on ChatScreen) or by tapping the manual reset in Settings.
        //
        // Crash-frequency circuit breaker: same effect, different trigger.
        // When CrashFrequencyDetector tripped on the previous boot it set
        // a 1-hour force-home grace window — within that window every
        // launch lands on Home regardless of the user's Launch Session
        // preference, even after they dismissed the share dialog. Avoids
        // re-entering the session that may have been the crash trigger.
        //
        // [T-android-larky-longsession-followup] Beacon-driven restart-count
        // gate: the previous cycle ended in crash_or_stall AND the rolling
        // count of consecutive crashed launches exceeds the threshold
        // (default 3). Catches the "process repeatedly killed re-entering
        // the same monster session" pattern that the existing
        // HangDetector breaker misses when hangs short-circuit before the
        // SharedPreferences counter increments. Resets automatically the
        // moment the user has ANY non-crash_or_stall cycle (clean_exit /
        // silent_kill / first_launch) — see LaunchCycleBeacon.lastRestartCount.
        val mode = if (
            com.openminis.app.diagnostics.HangDetector.shouldForceHomeOnLaunch(context) ||
            com.openminis.app.crash.CrashFrequencyDetector.shouldForceHomeOnLaunch(context) ||
            com.openminis.app.diagnostics.LaunchCycleBeacon.shouldForceHomeOnLaunch()
        ) 3 else rawMode
        val autoThresholdMs = 15L * 60 * 1000
        // [T-android-first-launch-lands-home] Read once, before the mode
        // branches: "is this device set up at all?" is the question that
        // outranks the launch preference.
        val hasAnySession = chatRepository.dao.listSessions().isNotEmpty()
        // [T-android-hang-false-positive] Wait for the provider config to load
        // by SUSPENDING, bounded, instead of blocking the main thread: the
        // getter used to take configLock and waited up to 21 s behind a
        // saveConfig. If the load never completes (unreadable store), fall
        // through after the bound and read what is published, as before.
        kotlinx.coroutines.withTimeoutOrNull(2_000) { providerRepository.awaitConfigLoaded() }
        val hasAnyProvider = providerRepository.instances.isNotEmpty()
        // [XSessionDiag] Hypothesis 1 context: which launch mode actually applied.
        // rawMode is the user's preference (0 = auto, the default); mode is what
        // survived the force-home circuit breakers. Without this line a log that
        // lacks the "launch/auto" entry below is ambiguous — it could mean the
        // auto branch was not taken, or that this whole resolver never ran.
        com.openminis.app.logging.AppLogger.info(
            "XSessionDiag",
            "[XSessionDiag] launch/mode: rawMode=$rawMode effectiveMode=$mode " +
                "hasPendingShare=$hasPendingShare hasAnySession=$hasAnySession " +
                "hasAnyProvider=$hasAnyProvider",
        )
        val target: String? = when {
            // [T-android-first-launch-lands-home] Nothing configured AND no
            // history: land on Home whatever the mode says. Mode 2 ("always a
            // new chat") is a real preference, but on a device with no provider
            // it opens a composer that cannot send — and hides the onboarding
            // steps that explain why. Once the user has either a session or a
            // provider, their preference is honoured again.
            !hasAnySession && !hasAnyProvider -> null
            mode == 1 -> chatRepository.dao.listSessions().firstOrNull { !it.isChild }?.let { Routes.chat(it.id) }
            mode == 2 -> Routes.chat("__new__${java.util.UUID.randomUUID()}")
            mode == 3 -> null
            else -> {
                // [T-android-first-launch-lands-home] No history → Home, not a
                // fresh draft. Auto mode is the DEFAULT, and it used to fall
                // through to a new chat here: a device with providers but no
                // sessions yet opened straight into an empty composer instead
                // of the list. "Resume what I was doing" has nothing to resume
                // when nothing was ever done.
                // [T-child-session-leak] "Resume what I was doing" must never
                // land in a hidden agent child session.
                val latest = chatRepository.dao.listSessions().firstOrNull { !it.isChild }
                    ?: return@LaunchedEffect
                val fresh = System.currentTimeMillis() - latest.updatedAt < autoThresholdMs
                // [XSessionDiag] Hypothesis 1: auto mode silently RESUMES the most
                // recently updated session, so a restored backup whose updatedAt is
                // the iPhone's own timestamp can look "fresh" and be presented as
                // the landing chat — the user believes they are in a new chat.
                // Logs the exact inputs to the freshness test and where it navigated.
                // Runs once per cold start; no measurable cost.
                run {
                    val now = System.currentTimeMillis()
                    com.openminis.app.logging.AppLogger.info(
                        "XSessionDiag",
                        "[XSessionDiag] launch/auto: candidate=${latest.id.take(8)} " +
                            "title=${latest.title?.take(24)} " +
                            "updatedAt=${latest.updatedAt} now=$now " +
                            "ageMs=${now - latest.updatedAt} thresholdMs=$autoThresholdMs " +
                            "fresh=$fresh -> ${if (fresh) "RESUME_EXISTING" else "NEW_DRAFT"}",
                    )
                }
                if (fresh) Routes.chat(latest.id) else Routes.chat("__new__${java.util.UUID.randomUUID()}")
            }
        }
        if (target != null) {
            // T314: navigate directly without the safeNavigate guard.
            // safeNavigate exists to defang back-then-tap-settings races
            // where a popped destination is mid-tear-down — it requires
            // RESUMED to be safe. But this LaunchedEffect fires on the
            // first composition pass, when the start destination is
            // STARTED but not yet RESUMED. There's no race here: we're
            // the deterministic startup dispatcher, the start destination
            // hasn't even rendered, and we want to navigate to it BEFORE
            // it shows. Calling navigate() unconditionally produces the
            // intended cold-start dispatch (mode 1 → last session, mode 2
            // → new chat, mode 0 → auto). Pre-T314 the safeNavigate guard
            // silently dropped this navigation and stranded the user on
            // the SESSION_LIST start destination regardless of mode.
            navController.navigate(target) {
                popUpTo(Routes.SESSION_LIST) { inclusive = false }
            }
        }
    }

    // [T-android-share-routing] Warm-share fallback for screens that do NOT
    // host the chat scaffold (settings, editors, ...). The two scaffold routes
    // (`sessions`, `chat/{id}`) route a share themselves from what is actually
    // visible - see ChatSplitScaffold. Here the user is elsewhere, so open a
    // new draft addressed to the share.
    //
    // T185 decided this from `route == sessions`, which the two-pane layout
    // broke (the list can show under `chat/{id}`), and went through
    // safeNavigate, which DROPS the call unless the entry is RESUMED - the
    // state an onNewIntent share often arrives in. Wait for RESUMED instead.
    val shareBufferVersion by com.openminis.app.share.ShareCoordinator.bufferVersion.collectAsState()
    LaunchedEffect(shareBufferVersion) {
        if (shareBufferVersion == 0) return@LaunchedEffect
        if (!com.openminis.app.share.ShareCoordinator.hasUnaddressedShare()) return@LaunchedEffect
        val entry = navController.currentBackStackEntry ?: return@LaunchedEffect
        val route = entry.destination.route
        if (route == Routes.SESSION_LIST || route == Routes.CHAT) return@LaunchedEffect
        entry.lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
        val draft = newDraftSessionId()
        if (com.openminis.app.share.ShareCoordinator.addressTo(draft)) {
            navController.navigate(Routes.chat(draft)) {
                popUpTo(Routes.SESSION_LIST) { inclusive = false }
            }
        }
    }

    // Pinned-shortcut cold start: when launched via
     // `minis://session/<id>/<resource-path>`, set the pending HTML
     // preview synchronously and start NavHost directly at the matching
     // chat so ChatScreen's LaunchedEffect consumes the pending state on
     // first composition — no sessions-list flash, no launch-session
     // preference detour.
    val htmlShortcut = initialDeepLink as? DeepLinkAction.OpenHtmlPreview
    // App-icon quick action cold start: mount NavHost directly at a fresh
    // draft chat, seeding the pending action so ChatScreen consumes it on
    // its first LaunchedEffect tick. Mirrors the htmlShortcut path —
    // avoids a sessions-list flash and a duplicate back-stack entry.
    val quickActionStart: String? = when (initialDeepLink) {
        is DeepLinkAction.NewVoiceChat -> {
            DeepLinkCoordinator.setPendingChatAction(
                DeepLinkCoordinator.ChatAction.START_VOICE,
            )
            Routes.chat("__new__${java.util.UUID.randomUUID()}")
        }
        is DeepLinkAction.NewCameraChat -> {
            DeepLinkCoordinator.setPendingChatAction(
                DeepLinkCoordinator.ChatAction.OPEN_CAMERA,
            )
            Routes.chat("__new__${java.util.UUID.randomUUID()}")
        }
        is DeepLinkAction.NewChat -> Routes.chat("__new__${java.util.UUID.randomUUID()}")
        else -> null
    }
    val startDestination = when {
        htmlShortcut != null -> {
            // Seed coordinator before NavHost composition so ChatScreen sees
            // the pending state on its very first LaunchedEffect tick.
            DeepLinkCoordinator.setPendingHtmlPreview(
                htmlShortcut.sessionId,
                htmlShortcut.resourcePath,
                htmlShortcut.title,
            )
            Routes.chat(htmlShortcut.sessionId)
        }
        quickActionStart != null -> quickActionStart
        // [destiny/v7-deeplink] Settings-screen deep links (voice / servers /
        // home / v7-screen/<id> / spec) mount the NavHost DIRECTLY on the
        // target route. Navigating after composition (the old
        // LaunchedEffect → safeNavigate path) was silently swallowed by
        // safeNavigate's RESUMED guard: during cold start the freshly
        // mounted start entry is not RESUMED yet, so every minis://settings
        // link landed on the home screen instead. No navigation needed now,
        // so no timing window can drop it.
        initialDeepLink is DeepLinkAction.OpenSettingsScreen -> initialDeepLink.route
        // Keep the original Mibot chat shell and glass tabs as the app home.
        // The original native chat scaffold is the default home.
        else -> Routes.SESSION_LIST
    }
    // [fork/mibot-v2] One shared backdrop behind every screen; the Mibot
    // scheme makes Scaffold containers transparent so it shows through.
    val mibotOn = com.openminis.app.ui.theme.LocalMibotActive.current
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    if (mibotOn) com.openminis.app.ui.glass.MibotBackdrop(Modifier.frostSource())
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground,
    ) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        // T153: paint the in-app theme color underneath every transition
        // frame. Without this the NavHost's transition surface is
        // transparent and the window background bleeds through during
        // enter/exit animations — on dark mode the base Light window
        // background flashes white between screens. Tying the host to
        // the active Material colorScheme also keeps the first frame
        // correct on cold start.
        modifier = Modifier
            .fillMaxSize()
            .then(if (mibotOn) Modifier else Modifier.background(MaterialTheme.colorScheme.background)),
        // T342: Material 3 motion — shared-axis X transition.
        // Spec (m3.material.io/styles/motion/transitions):
        //   - enter uses EmphasizedDecelerate (cubic-bezier 0.05, 0.7, 0.1, 1.0)
        //     so the new destination eases in confidently
        //   - exit uses EmphasizedAccelerate (cubic-bezier 0.3, 0.0, 0.8, 0.15)
        //     so the leaving destination clears out fast
        //   - both legs together feel like a single 300ms motion (200ms
        //     exit overlapping 300ms enter), short enough that a rapid second
        //     tap still lands on the next destination once safeNavigate's
        //     RESUMED guard releases
        //   - the small slide distance (~SlideDirection default ≈ container
        //     width ÷ N — Compose's slideIntoContainer already picks a
        //     subtle distance) plus fade reads as a single coordinated
        //     motion rather than a hard cut, matching Settings/Files in
        //     Material You system apps.
        // RESUMED guard via safeNavigate + the colorScheme.background
        // modifier above (T333) still defend against rapid-tap white
        // flashes; the slightly longer enter spec (300ms vs 220ms) is
        // covered by the same guard.
        enterTransition = {
            if (mibotOn) androidx.compose.animation.fadeIn(tween(220, delayMillis = 90)) +
                androidx.compose.animation.scaleIn(tween(220, delayMillis = 90), initialScale = 0.97f)
            else slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(300, easing = EmphasizedDecelerate),
            ) + fadeIn(animationSpec = tween(300, easing = EmphasizedDecelerate))
        },
        exitTransition = {
            if (mibotOn) androidx.compose.animation.fadeOut(tween(90))
            else slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(200, easing = EmphasizedAccelerate),
            ) + fadeOut(animationSpec = tween(200, easing = EmphasizedAccelerate))
        },
        popEnterTransition = {
            if (mibotOn) androidx.compose.animation.fadeIn(tween(220, delayMillis = 90)) +
                androidx.compose.animation.scaleIn(tween(220, delayMillis = 90), initialScale = 0.97f)
            else slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(300, easing = EmphasizedDecelerate),
            ) + fadeIn(animationSpec = tween(300, easing = EmphasizedDecelerate))
        },
        popExitTransition = {
            if (mibotOn) androidx.compose.animation.fadeOut(tween(90))
            else slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(200, easing = EmphasizedAccelerate),
            ) + fadeOut(animationSpec = tween(200, easing = EmphasizedAccelerate))
        },
    ) {
        // [T-android-tablet-split] SESSION_LIST and CHAT both render the same
        // ChatSplitScaffold; they differ only in which session it opens with.
        //
        // Keeping BOTH routes (rather than collapsing to one) is deliberate:
        // `Routes.chat(id)` is navigated to from many places — deep links,
        // share handoff, Move-to, the widget, notifications — and every one of
        // them keeps working unchanged. On a phone the scaffold resolves to a
        // single pane, so those pushes look and behave exactly as before.
        composable(Routes.SESSION_LIST) {
            ChatSplitScaffoldRoute(
                initialSessionId = null,
                navController = navController,
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                memoryRepository = memoryRepository,
                skillRepository = skillRepository,
                mcpRepository = mcpRepository,
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            ChatSplitScaffoldRoute(
                initialSessionId = sessionId,
                navController = navController,
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                memoryRepository = memoryRepository,
                skillRepository = skillRepository,
                mcpRepository = mcpRepository,
            )
        }

        // [fork/glass-ui] Top-level tab pages + backdrop settings.
        composable(com.openminis.app.ui.glass.GlassRoutes.FEED) {
            com.openminis.app.ui.glass.GlassTabShell(
                current = com.openminis.app.ui.glass.GlassTab.FEED,
                onSelect = { navController.navigateToGlassTab(it) },
                scrollUnder = true,
            ) {
                // [destiny/v7] 动态 is three states: 在看 / 想到的 / 能领的.
                // Ideas moved in from the removed bottom tab; free quotas live
                // in 能领的. Every state is a real, persisted screen.
                com.openminis.app.mibot.feed.FeedPage(
                    onDiscuss = { text -> com.openminis.app.mibot.feed.openChatWithDraft(navController, text) },
                    onOpenSession = { id -> navController.safeNavigate(Routes.chat(id)) },
                    onOpenGroup = { id -> navController.safeNavigate(com.openminis.app.mibot.groups.GroupRoutes.chat(id)) },
                )
            }
        }
        composable(com.openminis.app.ui.glass.GlassRoutes.IDEAS) {
            com.openminis.app.ui.glass.GlassTabShell(
                // [destiny/v7] IDEAS has no tab anymore — keep 动态 highlighted
                // if a deep link still lands on this legacy route.
                current = com.openminis.app.ui.glass.GlassTab.FEED,
                onSelect = { navController.navigateToGlassTab(it) },
                scrollUnder = true,
            ) {
                // [fork/mibot-ideas] Real 点子 (filesDir/ideas).
                com.openminis.app.mibot.ideas.IdeasPage(
                    onChat = { text -> com.openminis.app.mibot.feed.openChatWithDraft(navController, text) },
                )
            }
        }
        // [fork/mibot-ui-tabs] Bottom tab 群聊 (was Bots).
        composable(com.openminis.app.ui.glass.GlassRoutes.GROUPS) {
            com.openminis.app.ui.glass.GlassTabShell(
                current = com.openminis.app.ui.glass.GlassTab.GROUPS,
                onSelect = { navController.navigateToGlassTab(it) },
                scrollUnder = true,
            ) {
                com.openminis.app.mibot.groups.GroupChatsPage(onOpen = { id ->
                    navController.safeNavigate(com.openminis.app.mibot.groups.GroupRoutes.chat(id))
                })
            }
        }
        // [fork/mibot-groups] One bot group chat (from the 群聊 tab or the drawer).
        composable(
            route = com.openminis.app.mibot.groups.GroupRoutes.CHAT,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { entry ->
            val gid = entry.arguments?.getString("groupId") ?: return@composable
            com.openminis.app.mibot.groups.GroupChatScreen(gid, onBack = { navController.safePopBackStack() })
        }
        // [fork/mibot-ui-tabs] Settings › Bots: a plain pushed page, Back
        // returns to Settings.
        composable(com.openminis.app.ui.glass.GlassRoutes.BOTS) {
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxSize().navigationBarsPadding(),
            ) {
                com.openminis.app.mibot.design.MibotTopBar("Bots", onBack = { navController.safePopBackStack() })
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    com.openminis.app.mibot.bots.BotsPage(
                        onPlugins = { navController.safeNavigate(com.openminis.app.mibot.plugins.PluginRoutes.MARKET) },
                        onOpen = { id -> navController.navigate("sub_agent/$id") },
                        onNew = { navController.navigate("sub_agent/${com.openminis.app.ui.settings.DRAFT_AGENT_ID}") },
                        onAgentSettings = { navController.safeNavigate(Routes.AGENTS) },
                        showTitle = false,
                    )
                }
            }
        }
        composable(com.openminis.app.ui.glass.GlassRoutes.BACKDROP) {
            com.openminis.app.mibot.settings.BackgroundThemeScreen(
                onBack = { navController.safePopBackStack() },
                onMoreAppearance = { navController.safeNavigate(Routes.APPEARANCE) },
            )
        }

        composable(Routes.SETTINGS) {
          val glassOn = com.openminis.app.ui.glass.glassEnabled()
          val glassContext = LocalContext.current
          GlassSettingsFrame(glassOn, navController) {
            if (glassOn) {
                val go: (String) -> () -> Unit = { r -> { navController.safeNavigate(r) } }
                com.openminis.app.mibot.settings.MibotSettingsScreen(
                    com.openminis.app.mibot.settings.MibotSettingsActions(
                        vault = go(com.openminis.app.mibot.vault.VaultRoutes.LIST),
                        providers = go(Routes.PROVIDER_LIST),
                        gateway = go(com.openminis.app.mibot.gateway.GatewayRoutes.GATEWAY),
                        modelGroups = go(Routes.MODEL_GROUPS),
                        backdrop = go(com.openminis.app.ui.glass.GlassRoutes.BACKDROP),
                        appearance = go(Routes.APPEARANCE),
                        bots = go(com.openminis.app.ui.glass.GlassRoutes.BOTS),
                        soul = go(Routes.SOUL),
                        skills = go(Routes.SKILLS),
                        memory = go(Routes.MEMORY),
                        mcp = go(Routes.MCP),
                        tools = go(Routes.AGENT_TOOLS),
                        scheduled = go(Routes.SCHEDULED_TASKS),
                        sandbox = go(com.openminis.app.mibot.sandbox.SandboxRoutes.SANDBOX),
                        terminal = go(Routes.terminal()),
                        files = { mibotOpenRootfsFiles(glassContext, navController) },
                        network = go(Routes.NETWORK),
                        tailscale = go(Routes.NETWORK_TAILSCALE),
                        // [destiny/v7] 服务器管理 sits in the Tailscale group.
                        proxies = go(Routes.NETWORK_PROXIES),
                        permissions = go(Routes.PERMISSIONS),
                        background = go(Routes.BACKGROUND),
                        backup = go(Routes.BACKUP),
                        diagnostics = go(com.openminis.app.mibot.settings.DiagnosticsRoutes.DIAGNOSTICS),
                        reportBug = { com.openminis.app.ui.glass.ForkBugReport.open(glassContext) },
                        about = go(Routes.ABOUT),
                        virtualDisplay = go("mikova_vd"),
                        plugins = go(com.openminis.app.mibot.plugins.PluginRoutes.MARKET),
                        mail = { com.openminis.app.mibot.mail.MailRoutes.pendingTab = "全部"; navController.safeNavigate(com.openminis.app.mibot.mail.MailRoutes.LEDGER) },
                        aiMail = { com.openminis.app.mibot.mail.MailRoutes.pendingTab = "AI 邮箱"; navController.safeNavigate(com.openminis.app.mibot.mail.MailRoutes.LEDGER) },
                        capabilities = go(com.openminis.app.mibot.settings.CapRoutes.PAGE),
                        connectedAccounts = go(Routes.MIBOT_APP_ACCESS),
                        voice = go(Routes.MIBOT_VOICE),
                        taskQueue = go(Routes.MIBOT_TASK_QUEUE),
                        wechat = go(Routes.MIBOT_WECHAT),
                        wallet = go(Routes.MIBOT_WALLET),
                        selfHost = go("destiny_servers"),
                    ),
                )
            } else
            SettingsScreen(
                showBack = !glassOn,
                onBackdropClick = { navController.safeNavigate(com.openminis.app.ui.glass.GlassRoutes.BACKDROP) },
                onReportBugClick = { com.openminis.app.ui.glass.ForkBugReport.open(glassContext) },
                onBack = { navController.safePopBackStack() },
                onProvidersClick = { navController.safeNavigate(Routes.PROVIDER_LIST) },
                onModelGroupsClick = { navController.safeNavigate(Routes.MODEL_GROUPS) },
                onRootfsClick = { navController.safeNavigate(Routes.STORAGE) },
                onBackupClick = { navController.safeNavigate(Routes.BACKUP) },
                onEnvVarsClick = { navController.safeNavigate(Routes.ENV_VARS) },
                onNetworkClick = { navController.safeNavigate(Routes.NETWORK) },
                onTailscaleClick = { navController.safeNavigate(Routes.NETWORK_TAILSCALE) },
                onProxiesClick = { navController.safeNavigate(Routes.NETWORK_PROXIES) },
                onContainerDnsClick = { navController.safeNavigate(Routes.CONTAINER_DNS) },
                onSkillsClick = { navController.safeNavigate(Routes.SKILLS) },
                onTerminalClick = { navController.safeNavigate(Routes.terminal()) },
                onMemoryClick = { navController.safeNavigate(Routes.MEMORY) },
                onAgentsClick = { navController.safeNavigate(Routes.AGENTS) },
                onAgentToolsClick = { navController.safeNavigate(Routes.AGENT_TOOLS) },
                onMcpClick = { navController.safeNavigate(Routes.MCP) },
                onSoulClick = { navController.safeNavigate(Routes.SOUL) },
                onPermissionsClick = { navController.safeNavigate(Routes.PERMISSIONS) },
                onUsageClick = { navController.safeNavigate(Routes.USAGE_STATS) },
                onAppearanceClick = { navController.safeNavigate(Routes.APPEARANCE) },
                onBackgroundClick = { navController.safeNavigate(Routes.BACKGROUND) },
                onLogsClick = { navController.safeNavigate(Routes.LOGS) },
                onAboutClick = { navController.safeNavigate(Routes.ABOUT) },
                onMountedFoldersClick = { navController.safeNavigate(Routes.MOUNTED_FOLDERS) },
                onSharedFoldersClick = { navController.safeNavigate(Routes.SHARED_FOLDERS) },
            )
          }
        }

        // [destiny/v7] 远程接入 · 服务器 (design screens 45b / 45c).
        // Reached from Settings › Network, right below Tailscale.
        composable("destiny_servers") {
            com.openminis.app.mikova.selfhost.SelfHostScreen(onBack = { navController.safePopBackStack() })
        }

        // [destiny/v7] Screen 34 语音: real press-and-hold ASR + TTS.
        listOf("destiny_voice", "mikova/voice").forEach { voiceRoute ->
            composable(voiceRoute) {
                com.openminis.app.mikova.voice.VoiceScreen(
                    onBack = { navController.safePopBackStack() },
                    onSend = { text -> com.openminis.app.mibot.feed.openChatWithDraft(navController, text) },
                    prefer = voicePreference,
                    onOpenSettings = { navController.safeNavigate("mikova/voice-settings") },
                )
            }
        }

        // [destiny/honest-state] 没接后端的页（微信 / 钱包）如实说"未接入"，
        // 不再显示写死的账号与金额。
        composable(
            route = "destiny_not_wired/{which}",
            arguments = listOf(navArgument("which") { type = NavType.StringType }),
        ) { entry ->
            when (entry.arguments?.getString("which")) {
                "wallet" -> com.openminis.app.mikova.wallet.WalletScreen(onBack = { navController.safePopBackStack() })
                "app-access" -> com.openminis.app.mikova.accounts.ConnectedAccountsScreen(
                    onBack = { navController.safePopBackStack() },
                    onOpenOAuth = { sid -> navController.safeNavigate(com.openminis.app.mikova.oauth.OAuthRoutes.container(sid, com.openminis.app.mikova.oauth.OAuthMode.OAUTH)) },
                    onOpenBrowser = { sid -> navController.safeNavigate(com.openminis.app.mikova.oauth.OAuthRoutes.container(sid, com.openminis.app.mikova.oauth.OAuthMode.BROWSER)) },
                )
                else -> com.openminis.app.mikova.wechat.WeChatBindScreen(
                    onBack = { navController.safePopBackStack() },
                    onOpenPermissions = { navController.safeNavigate(Routes.PERMISSIONS) },
                )
            }
        }
        // [destiny/queue-real] 真实任务队列页（AgentJobRegistry），设置 › 任务队列
        // 与对话页「查看全部」都落到这里。
        listOf("destiny_queue", Routes.MIBOT_TASK_QUEUE).forEach { taskRoute ->
            composable(taskRoute) {
                com.openminis.app.mibot.queue.TaskQueueScreen(
                    onBack = { navController.safePopBackStack() },
                    onOpenSession = { id -> navController.safeNavigate(Routes.chat(id)) },
                    onNewTask = { navController.safeNavigate(Routes.chat(com.openminis.app.ui.chat.ChatViewModelStore.newChatDraftId())) },
                    onScheduled = { navController.safeNavigate(Routes.SCHEDULED_TASKS) },
                )
            }
        }
        // Legacy V7 routes return to the original Mibot chat shell.
        listOf("destiny_v7_home", "destiny_spec").forEach { route ->
            composable(route) {
                ChatSplitScaffoldRoute(null, navController, chatRepository, providerRepository, memoryRepository, skillRepository, mcpRepository)
            }
        }
        listOf("destiny_v7_screen/{id}", "destiny_spec/{id}").forEach { route ->
            composable(route, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                ChatSplitScaffoldRoute(null, navController, chatRepository, providerRepository, memoryRepository, skillRepository, mcpRepository)
            }
        }

        composable(Routes.BACKUP) { entry ->
            // Read once per entry and consumed, so a later visit from Settings
            // opens on Backup again.
            val initialTab = remember(entry) {
                navController.previousBackStackEntry?.savedStateHandle
                    ?.remove<Int>(Routes.BACKUP_INITIAL_TAB_KEY) ?: 0
            }
            com.openminis.app.ui.settings.backup.BackupAndRestoreScreen(
                initialTab = initialTab,
                onBack = { navController.safePopBackStack() },
                onManageDestinations = { navController.safeNavigate(Routes.BACKUP_DESTINATIONS) },
                onChooseRestoreServer = { navController.safeNavigate(Routes.RESTORE_SERVERS) },
                onOpenHistoryRecord = { id ->
                    navController.safeNavigate("${Routes.BACKUP_HISTORY_DETAIL}/$id")
                },
                onBrowseDestination = { name ->
                    navController.safeNavigate(
                        "${Routes.RESTORE_BROWSE}/" + android.net.Uri.encode(name),
                    )
                },
            )
        }

        composable(
            "${Routes.BACKUP_HISTORY_DETAIL}/{recordId}",
            arguments = listOf(navArgument("recordId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("recordId").orEmpty()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val history = remember { com.openminis.app.backup.BackupHistory.get(ctx) }
            val record = remember(id) { history.records().firstOrNull { it.id == id } }
            if (record == null) {
                // The record was removed (or pruned) while this screen was on
                // the stack; there is nothing to show, so leave rather than
                // render an empty shell.
                LaunchedEffect(Unit) { navController.safePopBackStack() }
            } else {
                // The screen needs a ViewModel for the delete-with-files path
                // (it talks to rclone); scoped to this entry so it dies with
                // the screen.
                val vm: com.openminis.app.ui.settings.backup.BackupViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel()
                com.openminis.app.ui.settings.backup.BackupHistoryDetailScreen(
                    record = record,
                    onBack = { navController.safePopBackStack() },
                    onRemove = {
                        history.remove(id)
                        navController.safePopBackStack()
                    },
                    // [T-android-backup-delete-files-feedback] Await the
                    // delete and hand the screen its outcome. This used to pop
                    // on the same frame it called the ViewModel — and since the
                    // ViewModel is scoped to THIS nav entry, popping cleared it
                    // and cancelled `viewModelScope` before the IO block ran.
                    // Nothing was deleted and nothing was said, which is the
                    // "button does nothing" the user reported. The screen now
                    // pops itself via onRemoved, once the record is really gone.
                    onRemoveWithFiles = { vm.removeHistoryRecordWithFiles(id) },
                    onRemoved = { navController.safePopBackStack() },
                    onOpenDestination = { name ->
                        navController.safeNavigate(
                            "${Routes.BACKUP_DESTINATION_BROWSE}/" +
                                android.net.Uri.encode(name),
                        )
                    },
                )
            }
        }

        composable(
            "${Routes.BACKUP_DESTINATION_BROWSE}/{remoteName}",
            arguments = listOf(navArgument("remoteName") { type = NavType.StringType }),
        ) { entry ->
            val name = entry.arguments?.getString("remoteName").orEmpty()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val remote = remember(name) {
                com.openminis.app.backup.remote.RcloneRemoteStore(ctx).remote(name)
            }
            if (remote == null) {
                // The destination was removed since the record was written.
                LaunchedEffect(Unit) { navController.safePopBackStack() }
            } else {
                val vm: com.openminis.app.ui.settings.backup.BackupViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel()
                com.openminis.app.ui.settings.backup.BackupDestinationBrowseScreen(
                    remote = remote,
                    vm = vm,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        // [T-android-restore-server-list] Reached from the restore tab's
        // "Choose from Server…" — always, configured or not. Picking a row
        // (or finishing an add) continues to that server's package browser.
        composable(Routes.RESTORE_SERVERS) {
            com.openminis.app.ui.settings.backup.RestoreServersScreen(
                onBack = { navController.safePopBackStack() },
                onPickServer = { name ->
                    navController.safeNavigate(
                        "${Routes.RESTORE_BROWSE}/" + android.net.Uri.encode(name),
                    )
                },
            )
        }

        composable(
            "${Routes.RESTORE_BROWSE}/{remoteName}",
            arguments = listOf(navArgument("remoteName") { type = NavType.StringType }),
        ) { entry ->
            val name = entry.arguments?.getString("remoteName").orEmpty()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val remote = remember(name) {
                com.openminis.app.backup.remote.RcloneRemoteStore(ctx).remote(name)
            }
            if (remote == null) {
                LaunchedEffect(Unit) { navController.safePopBackStack() }
            } else {
                // Scoped to the BACKUP entry so the browser and the restore
                // screen share one ViewModel — the picked package has to be
                // visible to the screen that restores it.
                val parent = remember(entry) { navController.getBackStackEntry(Routes.BACKUP) }
                val vm: com.openminis.app.ui.settings.backup.BackupViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(parent)
                com.openminis.app.ui.settings.backup.RestoreBrowseScreen(
                    remote = remote,
                    vm = vm,
                    onBack = { navController.safePopBackStack() },
                    // [T-android-restore-picked-lands-home] Pop to BACKUP, not
                    // one level up.
                    //
                    // This screen is reachable at two different depths:
                    // BACKUP → RESTORE_BROWSE when a destination is tapped on
                    // the restore tab, but BACKUP → RESTORE_SERVERS →
                    // RESTORE_BROWSE when the user came through "Choose from
                    // Server…". A single pop is right for the first and one
                    // short for the second, so after a multi-GB download and
                    // extract the user landed back on the server list — the
                    // step BEFORE the one they just completed — with no sign
                    // of the package they had waited for. It is on the Backup
                    // screen, one more pop away, that the opened package and
                    // its category checkboxes actually render.
                    //
                    // Popping to a named destination is depth-independent, so
                    // a future third entry point cannot reintroduce this.
                    onPicked = {
                        navController.safePopBackStack(
                            route = Routes.BACKUP,
                            inclusive = false,
                        )
                    },
                )
            }
        }

        composable(Routes.BACKUP_DESTINATIONS) {
            com.openminis.app.ui.settings.backup.RcloneDestinationsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.SHARED_FOLDERS) {
            SharedFoldersScreen(
                onBack = { navController.safePopBackStack() },
                onFolderClick = { folderId ->
                    navController.safeNavigate(Routes.sharedFoldersDetail(folderId))
                },
            )
        }

        composable(
            route = Routes.SHARED_FOLDERS_DETAIL,
            arguments = listOf(navArgument("folderId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId") ?: return@composable
            val ctx = androidx.compose.ui.platform.LocalContext.current
            SharedFolderDetailScreen(
                folderId = folderId,
                onBack = { navController.safePopBackStack() },
                onBrowseFiles = {
                    val rootfs = RootfsManager.getInstance(ctx.applicationContext)
                    val hostPath = java.io.File(rootfs.rootfsDir, "var/minis/$folderId")
                    val label = when (folderId) {
                        "shared" -> ctx.getString(com.openminis.app.R.string.shared_folder_name_shared)
                        "skills" -> ctx.getString(com.openminis.app.R.string.shared_folder_name_skills)
                        "memory" -> ctx.getString(com.openminis.app.R.string.shared_folder_name_memory)
                        else -> folderId
                    }
                    FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                        rootPath = hostPath,
                        rootLabel = label,
                        // Route reads through PRoot bind mounts so the host
                        // dirs that back /var/minis/{shared,skills,memory}
                        // resolve, matching how chat-files browse works.
                        linuxRootPath = "/var/minis/$folderId",
                        appContext = ctx.applicationContext,
                    )
                    navController.safeNavigate(Routes.FILE_BROWSER)
                },
            )
        }

        composable(Routes.MOUNTED_FOLDERS) {
            MountedFoldersScreen(
                store = mountedFoldersStore,
                onBack = { navController.safePopBackStack() },
                onMountClick = { mountId ->
                    navController.safeNavigate(Routes.mountedFoldersDetail(mountId))
                },
            )
        }

        composable(
            route = Routes.MOUNTED_FOLDERS_DETAIL,
            arguments = listOf(navArgument("mountId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val mountId = backStackEntry.arguments?.getString("mountId") ?: return@composable
            val context = androidx.compose.ui.platform.LocalContext.current
            MountDetailScreen(
                store = mountedFoldersStore,
                mountId = mountId,
                onBack = { navController.safePopBackStack() },
                onBrowseFiles = {
                    val entry = mountedFoldersStore.entries.value.firstOrNull { it.id == mountId }
                    val hostPath = entry?.resolvedHostPath
                    if (hostPath != null) {
                        FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                            rootPath = java.io.File(hostPath),
                            rootLabel = entry.name,
                        )
                        navController.safeNavigate(Routes.FILE_BROWSER)
                    } else {
                        // resolvedHostPath null = SAF tree from a non-externalstorage
                        // provider (cloud / Drive). Picker normally rejects these at
                        // add time, so this is a defensive fallback.
                        android.widget.Toast.makeText(
                            context,
                            "Mount path unavailable",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }

        composable(Routes.PROVIDER_LIST) {
            ProviderListScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onAddProvider = { navController.safeNavigate(Routes.ADD_PROVIDER) },
                onProviderClick = { instanceId ->
                    navController.safeNavigate(Routes.providerDetail(instanceId))
                },
                onVoiceServiceClick = { instanceId ->
                    navController.safeNavigate(Routes.shadowVoiceDetail(instanceId))
                },
            )
        }

        composable(
            route = Routes.SHADOW_VOICE_DETAIL,
            arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val instanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
            ShadowVoiceDetailScreen(
                instanceId = instanceId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.ADD_PROVIDER) {
            AddProviderScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onSaved = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.PROVIDER_DETAIL,
            arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val instanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
            ProviderDetailScreen(
                instanceId = instanceId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onModelEntryClick = { entryId ->
                    navController.safeNavigate(Routes.modelEntryDetail(instanceId, entryId))
                },
                onAddCustomModel = {
                    navController.safeNavigate(Routes.addCustomModel(instanceId))
                },
                onVoiceServiceClick = { id ->
                    navController.safeNavigate(Routes.shadowVoiceDetail(id))
                },
            )
        }

        composable(Routes.MODEL_GROUPS) {
            ModelGroupsScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onGroupClick = { groupId ->
                    navController.safeNavigate(Routes.modelGroupDetail(groupId))
                },
                onAddAgentLoopModels = {
                    navController.safeNavigate(Routes.ADD_MODELS_TO_AGENT_LOOP)
                },
                onAddAgentLoopGroups = {
                    navController.safeNavigate(Routes.ADD_GROUPS_TO_AGENT_LOOP)
                },
            )
        }

        composable(
            route = Routes.MODEL_GROUP_DETAIL,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            ModelGroupDetailScreen(
                groupId = groupId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onAddModels = {
                    navController.safeNavigate(Routes.addModelsToGroup(groupId))
                },
            )
        }

        composable(
            route = Routes.ADD_MODELS_TO_GROUP,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            AddModelsToGroupScreen(
                groupId = groupId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        // T185: full-screen picker for adding entries to the agent-loop
        // usable set (replaces the T182 ModalBottomSheet so the visual
        // matches AddModelsToGroupScreen — same shared
        // modelEntryPickerItems composable in ui/components/).
        composable(Routes.ADD_MODELS_TO_AGENT_LOOP) {
            AddAgentLoopModelsScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        // T185: companion picker for adding model groups to the agent-loop
        // set. Simpler layout (no per-provider sectioning) but same
        // selection/confirm semantics as the entries picker.
        composable(Routes.ADD_GROUPS_TO_AGENT_LOOP) {
            AddAgentLoopGroupsScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        // T182: AgentLoopModels is no longer a standalone screen. The
        // picker now lives as the last section inside ModelGroupsScreen
        // (mirrors iOS Views/Providers/ModelGroupsView.swift L77-79's
        // `AgentLoopModelsSection`). Routes.AGENT_LOOP_MODELS is left
        // declared for back-compat with any deep-link string we may
        // have shipped to early users; safePopBackStack lands them on
        // the prior screen if it ever fires.
        composable(Routes.AGENT_LOOP_MODELS) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                navController.safePopBackStack()
            }
        }

        composable(
            route = Routes.MODEL_ENTRY_DETAIL,
            arguments = listOf(
                navArgument("instanceId") { type = NavType.StringType },
                navArgument("entryId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            // [T-android-model-entry-route-slash-crash] Decode back the
            // %2F-encoded composite ids so ModelEntryDetailScreen can match
            // entry.id == "<instanceId>/<modelId>" (which carries a literal '/')
            // against the repo. Mirrors the encode in Routes.modelEntryDetail.
            val instanceId = backStackEntry.arguments?.getString("instanceId")
                ?.let { android.net.Uri.decode(it) } ?: return@composable
            val entryId = backStackEntry.arguments?.getString("entryId")
                ?.let { android.net.Uri.decode(it) } ?: return@composable
            ModelEntryDetailScreen(
                instanceId = instanceId,
                entryId = entryId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.ADD_CUSTOM_MODEL,
            arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val instanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
            AddCustomModelScreen(
                instanceId = instanceId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.STORAGE) {
            // [T-android-storage-usage-cache] Scoped to this entry: it survives a
            // trip to a session's detail page and back (no re-measure), and is
            // dropped when the user leaves Storage.
            val storageContext = androidx.compose.ui.platform.LocalContext.current
            val storageVm: com.openminis.app.ui.settings.StorageUsageViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel(
                    factory = com.openminis.app.ui.settings.StorageUsageViewModel.factory(storageContext, chatRepository),
                )
            StorageManagementScreen(
                vm = storageVm,
                onBack = { navController.safePopBackStack() },
                onRootfsClick = { navController.safeNavigate(Routes.ROOTFS_MANAGEMENT) },
                onSessionClick = { sessionId ->
                    navController.safeNavigate(Routes.sessionStorageDetail(sessionId))
                },
            )
        }

        composable(
            route = Routes.SESSION_STORAGE_DETAIL,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            // [T-android-storage-usage-cache] The list's ViewModel, when Storage
            // is below us on the stack: this page's measurements update its row.
            val storageVm = remember(backStackEntry) {
                runCatching { navController.getBackStackEntry(Routes.STORAGE) }.getOrNull()
            }?.let { parent ->
                androidx.lifecycle.viewmodel.compose.viewModel<com.openminis.app.ui.settings.StorageUsageViewModel>(
                    viewModelStoreOwner = parent,
                    factory = com.openminis.app.ui.settings.StorageUsageViewModel.factory(
                        androidx.compose.ui.platform.LocalContext.current, chatRepository,
                    ),
                )
            }
            SessionStorageDetailScreen(
                sessionId = sessionId,
                chatDao = chatRepository.dao,
                onBack = { navController.safePopBackStack() },
                onMeasured = { id, minis, media -> storageVm?.onSessionMeasured(id, minis, media) },
                onBrowseFiles = { rootPath ->
                    // [T-android-copy-abs-path-fullpath] This browser is rooted at
                    // the per-session host dir (filesDir/minis-sessions/<sid>),
                    // whose immediate children (workspace/ attachments/ offloads/
                    // browser/) are exactly the PRoot /var/minis/* subdirs. The
                    // host listing already resolves correctly so we keep rootPath
                    // host-based (no linuxRootPath re-routing — that would redirect
                    // /var/minis to the global/empty placeholder dir). We only pass
                    // displayLinuxPrefix = "/var/minis" so "Copy Absolute Path"
                    // emits the agent-visible /var/minis/workspace/foo.py instead of
                    // the opaque /data/user/0/.../minis-sessions/<sid>/... host path.
                    FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                        rootPath = java.io.File(rootPath),
                        rootLabel = "Session Files",
                        displayLinuxPrefix = "/var/minis",
                    )
                    navController.safeNavigate(Routes.FILE_BROWSER)
                },
            )
        }

        composable(Routes.ROOTFS_MANAGEMENT) {
            val context = androidx.compose.ui.platform.LocalContext.current
            RootfsManagementScreen(
                onBack = { navController.safePopBackStack() },
                onBrowseFiles = {
                    val rootfs = RootfsManager.getInstance(context.applicationContext)
                    FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                        rootPath = rootfs.rootfsDir,
                        rootLabel = "/",
                        // [T-filebrowser-delete-symlink-target] The rows ARE the
                        // Linux tree, so name them by their Linux path: delete
                        // then knows /proc, /var/minis/skills… are bind targets,
                        // and Copy Absolute Path gives what the shell sees.
                        displayLinuxPrefix = "/",
                    )
                    navController.safeNavigate(Routes.FILE_BROWSER)
                },
                // [T-android-mirror-manual-select] Without this callback the
                // mirror rows fall back to the declaration-site no-op default
                // and MirrorCategoryDetailScreen (manual mirror selection,
                // iOS MirrorCategoryDetailView parity) was unreachable.
                onMirrorCategoryClick = { category ->
                    navController.safeNavigate(Routes.mirrorCategoryDetail(category.key))
                },
            )
        }

        composable(
            route = Routes.MIRROR_CATEGORY_DETAIL,
            arguments = listOf(navArgument("categoryKey") { type = NavType.StringType }),
        ) { backStackEntry ->
            val categoryKey = backStackEntry.arguments?.getString("categoryKey") ?: return@composable
            val category = com.openminis.app.ui.sandbox.MirrorCatalog.categoryFromKey(categoryKey)
                ?: return@composable
            com.openminis.app.ui.sandbox.MirrorCategoryDetailScreen(
                category = category,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.FILE_BROWSER) {
            // [T-filebrowser-standalone] Settings → 文件浏览器 navigates here
            // without any chat session having primed FilePreviewHolder, which
            // used to `return@composable` and paint a blank white screen. Build
            // a rootfs-rooted view model on demand so the entry always renders
            // a real directory listing.
            val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
            val vm = FilePreviewHolder.fileBrowserViewModel ?: remember(appCtx) {
                val rootfs = RootfsManager.getInstance(appCtx)
                val varMinis = java.io.File(rootfs.rootfsDir, "var/minis")
                FileBrowserViewModel(
                    rootPath = rootfs.rootfsDir,
                    initialPath = varMinis.takeIf { it.exists() },
                    rootLabel = "/",
                    linuxRootPath = "/",
                    sessionId = null,
                    appContext = appCtx,
                ).also { FilePreviewHolder.fileBrowserViewModel = it }
            }
            FileBrowserScreen(
                viewModel = vm,
                onBack = { navController.safePopBackStack() },
                onPreviewFile = { item ->
                    FilePreviewHolder.currentItem = item
                    FilePreviewHolder.currentSessionId = null
                    navController.safeNavigate(Routes.FILE_PREVIEW)
                },
            )
        }

        // Browse Chat Files (iOS parity: open FileBrowser rooted at the full
        // Linux root, focused on /var/minis. Matches AIChatView.swift L490:
        //   FileBrowserView(rootPath: dataPath, initialPath: dataPath/var/minis,
        //                   rootLabel: "/")
        // so the user can navigate up out of /var/minis into the broader rootfs.
        composable(
            route = Routes.CHAT_FILES,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("path") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val context = androidx.compose.ui.platform.LocalContext.current
            val rootfs = RootfsManager.getInstance(context.applicationContext)
            val varMinis = java.io.File(rootfs.rootfsDir, "var/minis")
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            // [T-minis-folder-link] A tapped folder link opens at that folder.
            // The browser is rooted at the rootfs and lists through the PRoot
            // resolver, so the Linux path maps onto the rootfs layout directly;
            // the folder need not exist inside the rootfs tarball itself.
            val folderPath = backStackEntry.arguments?.getString("path")
                ?.trim('/')?.takeIf { it.isNotEmpty() }
            val vm = remember(rootfs.rootfsDir.absolutePath, varMinis.absolutePath, sessionId, folderPath) {
                FileBrowserViewModel(
                    rootPath = rootfs.rootfsDir,
                    initialPath = folderPath?.let { java.io.File(rootfs.rootfsDir, it) }
                        ?: varMinis.takeIf { it.exists() },
                    rootLabel = "/",
                    // T121: route directory listings through PRootKernel bind
                    // mounts so /var/minis/{skills,memory,shared} resolve to
                    // their backing host dirs (filesDir/minis-global/<subdir>).
                    // Without this the browser walks the rootfs tarball
                    // directly and shows the empty placeholder dirs that ship
                    // inside Alpine's var/minis/ — every subdir reads as
                    // "Empty folder" even though the agent has files there.
                    linuxRootPath = "/",
                    // T147: scope per-session subdirs (attachments / workspace
                    // / offloads / browser) to THIS chat's host dir even when
                    // another session was the last to boot a PRoot — that
                    // global bindMounts state is last-writer-wins and would
                    // otherwise hide the agent's generated files for the
                    // session the user is looking at.
                    sessionId = sessionId,
                    appContext = context.applicationContext,
                )
            }
            FileBrowserScreen(
                viewModel = vm,
                onBack = { navController.safePopBackStack() },
                onPreviewFile = { item ->
                    FilePreviewHolder.currentItem = item
                    FilePreviewHolder.currentSessionId = sessionId
                    navController.safeNavigate(Routes.FILE_PREVIEW)
                },
            )
        }

        // Rendered as a Dialog destination (not a `composable`) so the
        // underlying screen — typically ChatScreen — stays in the composition
        // while preview is open. With `composable()`, NavHost unmounts the
        // previous entry, which detaches the chat LazyColumn from layout;
        // when the user pops back, LazyListState re-anchors to (0, 0) and
        // the user loses their scroll position (plus a white-flash on the
        // first frame before the list remeasures). `dialog()` keeps the
        // back entry's composition alive — listState retains both
        // firstVisibleItemIndex/Offset and its layoutInfo cache, so the
        // chat paints its previous viewport on the first frame after pop.
        // `usePlatformDefaultWidth=false` lets the dialog fill the screen
        // edge-to-edge, matching the prior full-screen composable feel;
        // `decorFitsSystemWindows=false` lets FilePreviewScreen handle its
        // own insets exactly like before.
        dialog(
            route = Routes.FILE_PREVIEW,
            dialogProperties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            val item = FilePreviewHolder.currentItem ?: return@dialog
            FilePreviewScreen(
                item = item,
                sessionId = FilePreviewHolder.currentSessionId,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.NETWORK) {
            com.openminis.app.ui.settings.NetworkSettingsScreen(
                onBack = { navController.safePopBackStack() },
                providers = providerRepository.instances.map { com.openminis.app.ui.settings.NetworkProviderRow(it.id, it.label) },
            )
        }
        composable(Routes.NETWORK_TAILSCALE) {
            com.openminis.app.ui.settings.NetworkSettingsScreen(
                onBack = { navController.safePopBackStack() },
                page = com.openminis.app.ui.settings.NetworkPage.TAILSCALE,
            )
        }
        composable(Routes.NETWORK_PROXIES) {
            com.openminis.app.ui.settings.NetworkSettingsScreen(
                onBack = { navController.safePopBackStack() },
                page = com.openminis.app.ui.settings.NetworkPage.PROXIES,
            )
        }
        composable(Routes.CONTAINER_DNS) {
            com.openminis.app.ui.sandbox.ContainerDnsScreen(onBack = { navController.safePopBackStack() })
        }

        composable(Routes.ENV_VARS) {
            if (envVarRepository != null) {
                EnvironmentVariablesScreen(
                    envVarRepository = envVarRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(Routes.SKILLS) {
            if (skillRepository != null) {
                SkillsManagementScreen(
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                    onSkillClick = { skillId -> navController.safeNavigate(Routes.skillDetail(skillId)) },
                    onMinisSkillsClick = { navController.safeNavigate(Routes.MINIS_SKILLS_BROWSER) },
                )
            }
        }

        composable(
            route = Routes.SKILL_DETAIL,
            arguments = listOf(navArgument("skillId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val skillId = backStackEntry.arguments?.getString("skillId") ?: return@composable
            if (skillRepository != null) {
                SkillDetailScreen(
                    skillId = skillId,
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                    onFileClick = { id, relativePath ->
                        navController.safeNavigate(Routes.skillFile(id, relativePath))
                    },
                )
            }
        }

        composable(
            route = Routes.SKILL_FILE,
            arguments = listOf(
                navArgument("skillId") { type = NavType.StringType },
                navArgument("relativePath") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val skillId = backStackEntry.arguments?.getString("skillId") ?: return@composable
            val rawPath = backStackEntry.arguments?.getString("relativePath") ?: "SKILL.md"
            val relativePath = java.net.URLDecoder.decode(rawPath, "UTF-8")
            if (skillRepository != null) {
                SkillFileViewerScreen(
                    skillId = skillId,
                    relativePath = relativePath,
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(Routes.MINIS_SKILLS_BROWSER) {
            if (skillRepository != null) {
                MinisSkillsBrowserScreen(
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(
            Routes.TERMINAL,
            arguments = listOf(
                androidx.navigation.navArgument("initCommand") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                androidx.navigation.navArgument("sessionId") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val context = androidx.compose.ui.platform.LocalContext.current
            val initCommand = backStackEntry.arguments?.getString("initCommand")
            val sessionId = backStackEntry.arguments?.getString("sessionId")
            val session = remember { TerminalSession(context.applicationContext) }
            TerminalScreen(
                terminalSession = session,
                onBack = { navController.safePopBackStack() },
                initCommand = initCommand,
                sessionId = sessionId,
            )
        }

        composable(Routes.MEMORY) {
            if (memoryRepository != null) {
                MemoryManagementScreen(
                    memoryRepository = memoryRepository,
                    onBack = { navController.safePopBackStack() },
                    onFileClick = { fileName, isGlobal ->
                        navController.safeNavigate(Routes.memoryFileEdit(fileName, isGlobal))
                    },
                )
            }
        }

        // [T-mcp-integration-android] MCP Integrations management screen.
        composable(Routes.MCP) {
            if (mcpRepository != null) {
                com.openminis.app.ui.settings.MCPIntegrationsScreen(
                    mcpRepository = mcpRepository,
                    onBack = { navController.safePopBackStack() },
                    envVarRepository = envVarRepository,
                )
            }
        }

        // [T-soul-md] SOUL.md editor.
        composable(Routes.SOUL) {
            com.openminis.app.ui.settings.SoulSettingsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.MEMORY_FILE_EDIT,
            arguments = listOf(
                navArgument("fileName") { type = NavType.StringType },
                navArgument("isGlobal") { type = NavType.BoolType },
            ),
        ) { backStackEntry ->
            val fileName = backStackEntry.arguments?.getString("fileName") ?: return@composable
            val isGlobal = backStackEntry.arguments?.getBoolean("isGlobal") ?: false
            if (memoryRepository != null) {
                MemoryFileEditScreen(
                    fileName = fileName,
                    isGlobal = isGlobal,
                    memoryRepository = memoryRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(Routes.PERMISSIONS) {
            OffloadPermissionScreen(
                onBack = { navController.safePopBackStack() },
                // [T-android-privileged-backend] Single Shizuku-protocol screen
                // handles both Shizuku and AXManager managers; the old
                // multi-backend screen was retired in favour of a single
                // surface (the two managers share one binder slot, so a
                // multi-backend abstraction was misleading).
                onOpenPrivilegedBackend = { navController.safeNavigate(Routes.SHIZUKU) },
            )
        }

        composable(Routes.SHIZUKU) {
            ShizukuPermissionScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.SYSTEM_PERMISSIONS) {
            SystemPermissionsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.USAGE_STATS) {
            UsageStatsScreen(
                chatDao = chatRepository.dao,
                providerConfig = providerRepository.config.value,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.APPEARANCE) {
            AppearanceScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.BACKGROUND) {
            BackgroundSettingsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        // [T-android-subagent-page-parity] AGENTS resolves to the roster page
        // itself, not to a wrapper around it.
        //
        // The old AgentSettingsScreen held a link to this page plus a READ-ONLY
        // mirror of the sub-model group — a setting iOS configures under
        // Providers > Model Groups, not here. Once the switch moved onto the
        // roster page (e905140cb), the wrapper carried nothing of its own and
        // was purely an extra tap between the user and the thing they picked.
        //
        // Kept as a route rather than deleted: `minis://settings/agents` is a
        // published deep link, so it must keep resolving.
        composable(Routes.AGENTS) {
            com.openminis.app.ui.settings.SubAgentsScreen(
                onBack = { navController.safePopBackStack() },
                onOpen = { id -> navController.navigate("sub_agent/$id") },
            )
        }

        composable(Routes.SUB_AGENTS) {
            com.openminis.app.ui.settings.SubAgentsScreen(
                onBack = { navController.safePopBackStack() },
                onOpen = { id -> navController.navigate("sub_agent/$id") },
            )
        }

        composable(
            Routes.SUB_AGENT_DETAIL,
            arguments = listOf(navArgument("agentId") { type = NavType.StringType }),
        ) { entry ->
            com.openminis.app.ui.settings.SubAgentDetailScreen(
                agentId = entry.arguments?.getString("agentId").orEmpty(),
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.AGENT_TOOLS) {
            com.openminis.app.ui.settings.AgentToolsSettingsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.AGENT_TRANSCRIPT,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            com.openminis.app.ui.chat.AgentTranscriptScreen(
                sessionId = sessionId,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.safePopBackStack() })
        }

        composable(Routes.LOGS) {
            LogManagementScreen(
                onBack = { navController.safePopBackStack() },
                onLogFileClick = { fileName ->
                    navController.safeNavigate(Routes.logDetail(fileName))
                },
                onConfigAuditEntryClick = { auditId ->
                    navController.safeNavigate(Routes.configAuditDetail(auditId))
                },
            )
        }

        composable(
            route = Routes.CONFIG_AUDIT_DETAIL,
            arguments = listOf(navArgument("auditId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val auditId = backStackEntry.arguments?.getString("auditId") ?: return@composable
            com.openminis.app.ui.settings.ConfigAuditDetailScreen(
                entryId = auditId,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.LOG_DETAIL,
            arguments = listOf(navArgument("fileName") { type = NavType.StringType }),
        ) { backStackEntry ->
            val fileName = backStackEntry.arguments?.getString("fileName") ?: return@composable
            LogDetailScreen(
                fileName = fileName,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.ONBOARDING_MODELS) {
            OnboardingModelSelectionScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onAddCustomModel = { instanceId ->
                    navController.safeNavigate(Routes.addCustomModel(instanceId))
                },
            )
        }

        // [T-android-scheduled-tasks-design] Scheduled tasks list + editor.
        composable(Routes.SCHEDULED_TASKS) {
            com.openminis.app.ui.scheduled.ScheduledTasksScreen(
                onBack = { navController.safePopBackStack() },
                onEditTask = { taskId ->
                    navController.safeNavigate(Routes.scheduledTaskEdit(taskId))
                },
                onViewRuns = { taskId ->
                    navController.safeNavigate(Routes.scheduledTaskRuns(taskId))
                },
                onOpenSession = { sessionId ->
                    navController.safeNavigate(Routes.chat(sessionId))
                },
            )
        }
        // [T-android-scheduled-tasks-run-records] per-task run records.
        composable(
            route = Routes.SCHEDULED_TASK_RUNS,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId") ?: return@composable
            com.openminis.app.ui.scheduled.ScheduledTaskRunsScreen(
                taskId = taskId,
                onBack = { navController.safePopBackStack() },
                onOpenSession = { sessionId ->
                    navController.safeNavigate(Routes.chat(sessionId))
                },
            )
        }
        composable(
            route = Routes.SCHEDULED_TASK_EDIT,
            arguments = listOf(
                navArgument("taskId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId")
            com.openminis.app.ui.scheduled.ScheduledTaskEditScreen(
                taskId = taskId,
                onBack = { navController.safePopBackStack() },
                onOpenSession = { sessionId ->
                    navController.safeNavigate(Routes.chat(sessionId))
                },
            )
        }

        // ---------------------------------------------------------------
        // [fork/mibot-v2] Mibot pages.
        composable(com.openminis.app.mibot.vault.VaultRoutes.LIST) {
            com.openminis.app.mibot.vault.VaultListScreen(
                onBack = { navController.safePopBackStack() },
                onOpen = { id -> navController.safeNavigate(com.openminis.app.mibot.vault.VaultRoutes.edit(id)) },
                onNew = { navController.safeNavigate(com.openminis.app.mibot.vault.VaultRoutes.edit("new")) },
                onAudit = { navController.safeNavigate(com.openminis.app.mibot.vault.VaultRoutes.AUDIT) },
                onDiscover = { navController.safeNavigate(com.openminis.app.mibot.vault.VaultRoutes.DISCOVER) },
                onService = { key -> navController.safeNavigate(com.openminis.app.mibot.vault.VaultRoutes.service(key)) },
            )
        }
        // [fork/mibot-v4] Auto-discover & connect, and one service's accounts.
        composable(com.openminis.app.mibot.vault.VaultRoutes.DISCOVER) {
            com.openminis.app.mibot.vault.VaultDiscoverScreen(
                onBack = { navController.safePopBackStack() },
                onManual = { navController.safeNavigate(com.openminis.app.mibot.vault.VaultRoutes.edit("new")) },
            )
        }
        composable(
            com.openminis.app.mibot.vault.VaultRoutes.SERVICE,
            arguments = listOf(navArgument("sid") { type = NavType.StringType }),
        ) { entry ->
            com.openminis.app.mibot.vault.VaultServiceScreen(
                key = entry.arguments?.getString("sid").orEmpty(),
                onBack = { navController.safePopBackStack() },
                onOpen = { id -> navController.safeNavigate(com.openminis.app.mibot.vault.VaultRoutes.edit(id)) },
                onDiscover = { navController.safeNavigate(com.openminis.app.mibot.vault.VaultRoutes.DISCOVER) },
            )
        }
        composable(
            com.openminis.app.mibot.vault.VaultRoutes.EDIT,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            com.openminis.app.mibot.vault.VaultEditScreen(
                id = entry.arguments?.getString("id") ?: "new",
                onBack = { navController.safePopBackStack() },
            )
        }
        composable(com.openminis.app.mibot.vault.VaultRoutes.AUDIT) {
            com.openminis.app.mibot.vault.VaultAuditScreen(onBack = { navController.safePopBackStack() })
        }
        // [fork/mibot-v5] 插件市场 (SkillHub DSH plugins) + plugin detail / settings.
        composable(com.openminis.app.mibot.plugins.PluginRoutes.MARKET) {
            com.openminis.app.mibot.plugins.PluginMarketScreen(
                onBack = { navController.safePopBackStack() },
                onOpen = { name -> navController.safeNavigate(com.openminis.app.mibot.plugins.PluginRoutes.detail(name)) },
            )
        }
        // [fork/mibot-v5b2] 邮箱台账 / AI 邮箱 (one screen, tab), one mailbox, 权限与自动化.
        composable(com.openminis.app.mibot.mail.MailRoutes.LEDGER) {
            com.openminis.app.mibot.mail.MailLedgerScreen(
                onBack = { navController.safePopBackStack() },
                onOpen = { id -> navController.safeNavigate(com.openminis.app.mibot.mail.MailRoutes.box(id)) },
            )
        }
        composable(
            com.openminis.app.mibot.mail.MailRoutes.BOX,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            com.openminis.app.mibot.mail.MailBoxScreen(
                id = entry.arguments?.getString("id") ?: "",
                onBack = { navController.safePopBackStack() },
            )
        }
        composable(com.openminis.app.mibot.settings.CapRoutes.PAGE) {
            com.openminis.app.mibot.settings.CapabilitiesScreen(onBack = { navController.safePopBackStack() })
        }
        composable(com.openminis.app.mibot.plugins.PluginRoutes.DETAIL) { entry ->
            com.openminis.app.mibot.plugins.PluginDetailScreen(
                fullName = android.net.Uri.decode(entry.arguments?.getString("name") ?: ""),
                onBack = { navController.safePopBackStack() },
            )
        }
        composable(com.openminis.app.mibot.gateway.GatewayRoutes.GATEWAY) {
            com.openminis.app.mibot.gateway.GatewayScreen(
                onBack = { navController.safePopBackStack() },
                onProvider = { id -> navController.safeNavigate(Routes.providerDetail(id)) },
                onModelGroups = { navController.safeNavigate(Routes.MODEL_GROUPS) },
                onUsage = { navController.safeNavigate(Routes.USAGE_STATS) },
                onAddProvider = { navController.safeNavigate(Routes.ADD_PROVIDER) },
            )
        }
        composable(com.openminis.app.mibot.settings.DiagnosticsRoutes.DIAGNOSTICS) {
            val diagContext = LocalContext.current
            com.openminis.app.mibot.settings.DiagnosticsScreen(
                onBack = { navController.safePopBackStack() },
                onLogs = { navController.safeNavigate(Routes.LOGS) },
                onUsage = { navController.safeNavigate(Routes.USAGE_STATS) },
                onStorage = { navController.safeNavigate(Routes.STORAGE) },
                onGateway = { navController.safeNavigate(com.openminis.app.mibot.gateway.GatewayRoutes.GATEWAY) },
                onReportBug = { com.openminis.app.ui.glass.ForkBugReport.open(diagContext) },
            )
        }
        // [fork/mibot-merge] VdScreen composable removed with the mibot/phone stack.
        composable(com.openminis.app.mibot.sandbox.SandboxRoutes.SANDBOX) {
            val sbContext = LocalContext.current
            com.openminis.app.mibot.sandbox.SandboxScreen(
                onBack = { navController.safePopBackStack() },
                onTerminal = { navController.safeNavigate(Routes.terminal()) },
                onFiles = { mibotOpenRootfsFiles(sbContext, navController) },
                onDns = { navController.safeNavigate(Routes.CONTAINER_DNS) },
                onNetwork = { navController.safeNavigate(Routes.NETWORK) },
                onEnvVars = { navController.safeNavigate(Routes.ENV_VARS) },
                onMounts = { navController.safeNavigate(Routes.MOUNTED_FOLDERS) },
                onShared = { navController.safeNavigate(Routes.SHARED_FOLDERS) },
                onStorage = { navController.safeNavigate(Routes.BACKUP) },
                onManage = { navController.safeNavigate(Routes.ROOTFS_MANAGEMENT) },
                onMirrors = { navController.safeNavigate(Routes.ROOTFS_MANAGEMENT) },
                onOpenTerminalCommand = { cmd -> navController.safeNavigate(Routes.terminal(cmd)) },
            )
        }

        // [destiny/capabilities-real] 能力页全部接真实实现。
        //
        // 旧版这 5 条路由指向 DestinyCapabilityScreens.kt：微信（已连接 destiny_bot +
        // 两条写死的最近消息）、钱包（¥1,284.50 / 尾号 8842）、语音（静态音色文案）、
        // 手机控制（未分类·169 + 4 个只改本地变量的 Switch）。那些是 HTML 复刻带过来
        // 的假页，已整文件删除。现在：
        //   · 微信 / 钱包 → 如实「未接入」，不显示任何编造的账号与金额
        //   · 语音 → 真实 SpeechRecognizer 页面
        //   · 手机控制 → 真读系统无障碍状态 + 如实说明运行时未接入
        //   · 任务队列 → 派单作业表 + 落盘执行队列，两张真表并列
        composable(Routes.MIBOT_WECHAT) {
            com.openminis.app.mikova.wechat.WeChatBindScreen(
                onBack = { navController.safePopBackStack() },
                onOpenPermissions = { navController.safeNavigate(Routes.PERMISSIONS) },
            )
        }
        composable(Routes.MIBOT_APP_ACCESS) {
            com.openminis.app.mikova.accounts.ConnectedAccountsScreen(
                onBack = { navController.safePopBackStack() },
                onOpenOAuth = { sid -> navController.safeNavigate(com.openminis.app.mikova.oauth.OAuthRoutes.container(sid, com.openminis.app.mikova.oauth.OAuthMode.OAUTH)) },
                onOpenBrowser = { sid -> navController.safeNavigate(com.openminis.app.mikova.oauth.OAuthRoutes.container(sid, com.openminis.app.mikova.oauth.OAuthMode.BROWSER)) },
            )
        }
        composable(Routes.MIBOT_WALLET) {
            com.openminis.app.mikova.wallet.WalletScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        // ---- Mikova module routes ----
        composable(com.openminis.app.mikova.accounts.AccountRoutes.CENTER) {
            com.openminis.app.mikova.accounts.ConnectedAccountsScreen(
                onBack = { navController.safePopBackStack() },
                onOpenOAuth = { sid ->
                    navController.safeNavigate(com.openminis.app.mikova.oauth.OAuthRoutes.container(sid, com.openminis.app.mikova.oauth.OAuthMode.OAUTH))
                },
                onOpenBrowser = { sid ->
                    navController.safeNavigate(com.openminis.app.mikova.oauth.OAuthRoutes.container(sid, com.openminis.app.mikova.oauth.OAuthMode.BROWSER))
                },
            )
        }
        composable(
            com.openminis.app.mikova.oauth.OAuthRoutes.CONTAINER,
            arguments = listOf(
                navArgument("sid") { type = NavType.StringType },
                navArgument("mode") { type = NavType.StringType },
            ),
        ) { entry ->
            com.openminis.app.mikova.oauth.OAuthContainerScreen(
                serviceId = entry.arguments?.getString("sid").orEmpty(),
                mode = runCatching {
                    com.openminis.app.mikova.oauth.OAuthMode.valueOf(entry.arguments?.getString("mode") ?: "OAUTH")
                }.getOrDefault(com.openminis.app.mikova.oauth.OAuthMode.OAUTH),
                onBack = { navController.safePopBackStack() },
            )
        }
        composable(com.openminis.app.mikova.selfhost.SelfHostRoutes.SCREEN) {
            com.openminis.app.mikova.selfhost.SelfHostScreen(
                onBack = { navController.safePopBackStack() },
            )
        }
        composable("mikova_vd") {
            val app = LocalContext.current.applicationContext as com.openminis.app.MinisApp
            com.openminis.app.mikova.vd.VdPoolScreen(
                onBack = { navController.safePopBackStack() },
                manager = app.vdManager,
                controller = app.vdController,
            )
        }
        composable("mikova/voice-settings") {
            com.openminis.app.mikova.voice.VoiceSettingsScreen(
                onBack = { navController.safePopBackStack() },
                prefer = voicePreference,
                onPreferChange = { preference ->
                    voicePreference = preference
                    voicePrefs.edit().putString("engine", preference.name).apply()
                },
            )
        }
        composable(Routes.MIBOT_VOICE) {
            com.openminis.app.mikova.voice.VoiceScreen(
                onBack = { navController.safePopBackStack() },
                onSend = { text -> com.openminis.app.mibot.feed.openChatWithDraft(navController, text) },
                prefer = voicePreference,
                onOpenSettings = { navController.safeNavigate("mikova/voice-settings") },
            )
        }
    }
    }
    }
}

/** [fork/mibot-v2] Open the file browser at the sandbox's Linux root. */
private fun mibotOpenRootfsFiles(context: android.content.Context, navController: NavHostController) {
    val rootfs = RootfsManager.getInstance(context.applicationContext)
    FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
        rootPath = rootfs.rootfsDir,
        rootLabel = "/",
        displayLinuxPrefix = "/",
    )
    navController.safeNavigate(Routes.FILE_BROWSER)
}

/** [fork/glass-ui] Settings is the last tab when the glass UI is on. */
@Composable
private fun GlassSettingsFrame(glass: Boolean, navController: NavHostController, content: @Composable () -> Unit) {
    if (!glass) {
        content()
        return
    }
    com.openminis.app.ui.glass.GlassTabShell(
        current = com.openminis.app.ui.glass.GlassTab.SETTINGS,
        onSelect = { navController.navigateToGlassTab(it) },
        scrollUnder = true,
        showBackdrop = false,
    ) { content() }
}
