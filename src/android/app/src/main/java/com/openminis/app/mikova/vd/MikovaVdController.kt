package com.openminis.app.mikova.vd

import android.graphics.Path
import android.os.Bundle
import android.util.Log
import com.mibot.cap.phone.a11y.ScreenAccessibility
import com.mibot.cap.phone.shell.ShellService
import com.mibot.shell.IMikaShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [mikova/vd] 对单块虚拟屏的操作:输入注入、启动 App、截屏、跑 shell 命令。
 *
 * **注入可行性结论(见报告)**:a11y 手势**支持**跨屏 —— `GestureDescription.Builder.setDisplayId(displayId)`
 * 是 API 30(安卓 11)起的公开 API,本仓 [ScreenAccessibility.gesture] 已经用它对 Agent 虚拟屏下发单指手势,
 * 且 manifest 里 `canPerformGestures="true"` + `flagRetrieveInteractiveWindows`。所以注入**首选 a11y**;
 * a11y 服务没连(用户没在系统设置里开)时降级 shell `input -d <displayId>`(经 [IMikaShell.runSystemCommand],复用 cap.phone 的 shell 通道)。
 *
 * 降级一行切换:[InjectionMode.AUTO] 先试 a11y,拿不到服务就 shell。调用方只调 [tap]/[swipe]/[key],
 * 用哪个通道由这里决定,并把实际通道经 [lastInjectionPath] 暴露给界面/诊断。
 */
class MikovaVdController(
    private val context: android.content.Context,
    private val displays: MikovaDisplayManager,
) {
    /** 注入通道:a11y 手势(跨屏,API 30+)/ shell `input -d`(兜底)/ AUTO(先 a11y 后 shell)。 */
    enum class InjectionMode { AUTO, A11Y, SHELL }

    /** 上一次注入实际走的通道;诊断/界面显示用。 */
    @Volatile var lastInjectionPath: InjectionMode? = null
        private set

    private val app = context.applicationContext

    // ---- a11y 通道 ----

    /** 连着的无障碍服务;没开为 null。 */
    private fun a11y(): ScreenAccessibility? = ScreenAccessibility.instance.value

    // ---- 输入注入 ----

    /**
     * 在 [displayId] 上点一下 (x, y)。AUTO:先 a11y 手势(单点一笔),服务没连或失败再 shell `input -d tap`。
     * 挂起:等手势完成/失败再返回。返回 true 表示确认送达。
     */
    suspend fun tap(displayId: Int, x: Float, y: Float, mode: InjectionMode = InjectionMode.AUTO): Boolean {
        if (mode == InjectionMode.A11Y || mode == InjectionMode.AUTO) {
            val path = Path().apply { moveTo(x, y); lineTo(x, y) }
            val ok = a11y()?.gesture(displayId, path, TAP_MS) ?: false
            if (ok) {
                lastInjectionPath = InjectionMode.A11Y
                return true
            }
            if (mode == InjectionMode.A11Y) return false
        }
        lastInjectionPath = InjectionMode.SHELL
        return runShell(displayId, "input -d $displayId tap ${x.toInt()} ${y.toInt()}") == 0
    }

    /**
     * 在 [displayId] 上从 (x1,y1) 滑到 (x2,y2),历时 [durationMs]。
     * AUTO:先 a11y 手势(一笔拖动),失败再 shell `input -d swipe`。
     */
    suspend fun swipe(
        displayId: Int,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = 300,
        mode: InjectionMode = InjectionMode.AUTO,
    ): Boolean {
        if (mode == InjectionMode.A11Y || mode == InjectionMode.AUTO) {
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val ok = a11y()?.gesture(displayId, path, durationMs) ?: false
            if (ok) {
                lastInjectionPath = InjectionMode.A11Y
                return true
            }
            if (mode == InjectionMode.A11Y) return false
        }
        lastInjectionPath = InjectionMode.SHELL
        return runShell(
            displayId,
            "input -d $displayId swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs",
        ) == 0
    }

    /**
     * 在 [displayId] 上发一个按键([keyCode],如 KEYCODE_BACK)。
     * 按键走 shell 通道更稳([IMikaShell.injectKey] 等系统定投给谁);a11y 不发按键,所以按键一律 shell。
     */
    fun key(displayId: Int, keyCode: Int, mode: InjectionMode = InjectionMode.AUTO): Boolean {
        val shell = displays.shell()
        if (shell != null) {
            val ok = runCatching { shell.injectKey(displayId, keyCode) }.getOrDefault(false)
            if (ok) {
                lastInjectionPath = InjectionMode.SHELL
                return true
            }
        }
        lastInjectionPath = InjectionMode.SHELL
        return runShell(displayId, "input -d $displayId keyevent $keyCode") == 0
    }

    // ---- 启动 App ----

    /**
     * 在 [displayId] 上启动 [packageName]。
     * 首选 [IMikaShell.startApp](它做占用检查:用户在主屏用着就不打扰、并自动全屏窗口模式);
     * shell 不可用时退 `am start --display`。返回 [IMikaShell.START_OK] 等码(见 AIDL 注释)。
     */
    suspend fun startApp(displayId: Int, packageName: String): Int = withContext(Dispatchers.IO) {
        val shell = displays.shell()
        if (shell != null) {
            return@withContext runCatching { shell.startApp(displayId, packageName) }
                .onFailure { Log.w(TAG, "startApp failed: ${it.message}") }
                .getOrDefault(START_ERROR)
        }
        // 没有可信屏(本进程 local 屏)时 am start 不会落到它上面:这里只对 shell-backed 屏有意义。
        if (runShell(displayId, "am start --display $displayId $(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $packageName | tail -1)") == 0) {
            START_OK
        } else {
            START_ERROR
        }
    }

    // ---- 截屏 ----

    /**
     * 取 [displayId] 的 PNG 截图。
     * 首选这块屏自己的 ImageReader 回流帧([VdScreen.png],不经系统截屏、不受防截屏影响);
     * 没有帧(刚建还没画面)时退 `screencap -d <id>`(经 shell)。返回 PNG 字节或 null。
     */
    suspend fun screenshotPng(displayId: Int): ByteArray? = withContext(Dispatchers.IO) {
        val screen = displays.screens.value.firstOrNull { it.displayId == displayId }
        screen?.png?.invoke()?.takeIf { it.isNotEmpty() }
            ?: runShellBytes(displayId, "screencap -p -d $displayId")
    }

    // ---- shell 命令 ----

    /**
     * 在 [displayId] 的上下文里以 shell 身份跑一条命令(经 [IMikaShell.runSystemCommand],
     * 助手进程以 uid 2000 执行,返回 exitCode/output)。返回退出码;-1 表示 shell 不可用。
     */
    fun runShell(displayId: Int, command: String): Int {
        val shell = displays.shell() ?: return -1
        return runCatching {
            val bundle: Bundle = shell.runSystemCommand(displayId, command)
            bundle.getInt("exitCode", -1)
        }.getOrDefault(-1)
    }

    /** 同 [runShell],但要 stdout 字节(截屏用)。 */
    private fun runShellBytes(displayId: Int, command: String): ByteArray? {
        val shell = displays.shell() ?: return null
        return runCatching {
            val bundle: Bundle = shell.runSystemCommand(displayId, command)
            val code = bundle.getInt("exitCode", -1)
            if (code != 0) null else bundle.getString("output")?.toByteArray()
        }.getOrNull()
    }

    /** 这块屏是否还能注入(有 a11y 服务或有 shell 通道)。 */
    fun injectable(displayId: Int): Boolean =
        a11y() != null || displays.shell() != null

    /** 连着的 a11y 服务的 GestureResultCallback 是否被系统支持(恒真;保留以便诊断)。 */
    fun a11yGestureSupported(): Boolean = a11y() != null

    private companion object {
        const val TAG = "MikovaVdCtl"
        const val TAP_MS = 50L
        const val START_OK = ShellService.START_OK
        const val START_ERROR = ShellService.START_ERROR
    }
}
