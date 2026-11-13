package com.openminis.app.mibot.v7

/**
 * [destiny/v7] One shared action bundle handed to every V7 screen, so each
 * design screen can reach the real chat / voice / servers / MCP features.
 *
 * NOTE: The full 56-screen V7 design package was referenced from
 * [com.openminis.app.ui.navigation.AppNavigation] but never landed in the
 * repo, which broke `:app:compileGithubDebugKotlin`. These are minimal
 * compiling stubs that keep every V7 route + the action bundle intact until
 * the real screens are built. Each callback keeps its original meaning.
 */
data class V7Actions(
    val onAction: (String) -> Unit = {},
    val onVoice: () -> Unit = {},
    val onServers: () -> Unit = {},
    val onNewChat: () -> Unit = {},
    val onFeed: () -> Unit = {},
    val onAgents: () -> Unit = {},
    val onMcp: () -> Unit = {},
    val onLegacy: () -> Unit = {},
    val onIndex: () -> Unit = {},
    val onPhoneControl: () -> Unit = {},
    val onWechat: () -> Unit = {},
    val onAppAccess: () -> Unit = {},
    val onWallet: () -> Unit = {},
    val onSandbox: () -> Unit = {},
    val onMonitor: () -> Unit = {},
    val onOpenSession: (String) -> Unit = {},
    val onNavigate: (String) -> Unit = {},
)
