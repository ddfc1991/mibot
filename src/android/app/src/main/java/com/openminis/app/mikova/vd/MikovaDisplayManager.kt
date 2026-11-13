package com.openminis.app.mikova.vd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.mibot.cap.phone.display.AgentDisplay
import com.mibot.cap.phone.shell.ShellBinding
import com.mibot.shell.IMikaShell
import com.mibot.task.ParallelCapability
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.StateFlow

/**
 * [mikova/vd] 多虚拟屏管理:同时最多 [MAX_SCREENS] 块,每块跑一个任务;低内存设备自动降为 1 块。
 *
 * 两种后端,**创建时按 shell 可用性自动选**:
 *  - **shell-backed**([com.mibot.cap.phone.display.AgentDisplay]):内置助手已配对、[IMikaShell] 连上时用。
 *    屏建在 shell(uid 2000)进程里、带 TRUSTED,能 `am start --display` 跑任意 App、`input -d` 注入、
 *    画面经 ImageReader 回流到本进程 —— 这是 Mika 的正路。
 *  - **local**(app 进程 `DisplayManager.createVirtualDisplay`,OWN_CONTENT_ONLY):没助手时用。
 *    只能渲染本应用自己的内容(没法 TRUSTED → 不能托管第三方 App / 没有焦点),用于"多开本应用内容"的轻量场景。
 *
 * displayId 池:屏放掉就 release(虚拟屏 id 不能真复用,新任务优先占用一块已建的屏,而不是再建)。
 * 低内存判定走 [ParallelCapability.isLowRamDevice](系统 low-RAM 标志 + 可用内存阈值)。
 */
class MikovaDisplayManager(private val context: Context) {

    private val app = context.applicationContext
    private val displayManager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val _screens = mutableStateOf<List<VdScreen>>(emptyList())
    /** 当前所有虚拟屏(Compose 用 [State] 读,非 Compose 用 [screensFlow])。 */
    val screens: State<List<VdScreen>> = _screens

    private val counter = AtomicInteger(0)
    private var shellBinding: ShellBinding? = null

    /** 上限:低内存 1,否则 3。也受系统 SDK 影响(见 [ParallelCapability])。 */
    fun maxScreens(): Int {
        val lowRam = ParallelCapability.isLowRamDevice(app)
        return ParallelCapability.detect(
            sdk = android.os.Build.VERSION.SDK_INT,
            lowRam = lowRam,
            userMax = MAX_SCREENS,
            secondDisplayFailed = false,
        ).max.coerceIn(1, MAX_SCREENS)
    }

    /** 屏满没满。 */
    fun canCreate(): Boolean = _screens.value.size < maxScreens()

    /**
     * 新建一块虚拟屏跑 [taskName]。返回 [VdScreen],失败抛 [IllegalStateException](带原因)。
     * 先试 shell-backed;shell 没连上(助手未配对)就退 local;local 也建不出再抛。
     */
    @Synchronized
    fun create(taskName: String): VdScreen {
        if (!canCreate()) {
            val limit = maxScreens()
            throw IllegalStateException("已达虚拟屏上限($limit 块${if (limit == 1) ",低内存设备" else ""})")
        }
        val index = counter.incrementAndGet()
        val display = tryShellDisplay() ?: tryLocalDisplay(index)
            ?: throw IllegalStateException("建屏失败:shell 未连且本进程建虚拟屏被拒")

        val screen = VdScreen(
            displayId = display.displayId,
            taskName = taskName,
            backend = display.backend,
            width = display.width,
            height = display.height,
            frames = display.frames,
            png = display.png,
            release = display.release,
            ordinal = index,
        )
        _screens.value = _screens.value + screen
        return screen
    }

    /** 给一块屏改任务名(复用屏给新任务时用)。 */
    @Synchronized
    fun rename(displayId: Int, taskName: String) {
        _screens.value = _screens.value.map { if (it.displayId == displayId) it.copy(taskName = taskName) else it }
    }

    /** 关掉一块屏(放掉虚拟屏 + 从池里摘掉)。 */
    @Synchronized
    fun close(displayId: Int) {
        val target = _screens.value.firstOrNull { it.displayId == displayId } ?: return
        runCatching { target.release() }
        _screens.value = _screens.value.filterNot { it.displayId == displayId }
    }

    /** 全部关掉 + 解绑 shell 连接。 */
    @Synchronized
    fun closeAll() {
        _screens.value.forEach { runCatching { it.release() } }
        _screens.value = emptyList()
        shellBinding?.let { runCatching { it.unbind() } }
        shellBinding = null
    }

    /** shell 是否可用(助手已配对、[IMikaShell] 连上)。决定建屏走哪条后端。 */
    fun shellAvailable(): Boolean = acquireShell()?.shell?.value != null

    /** 当前连着的 [IMikaShell];没连上(未配对 / 未接 Owner)为 null。注入、启动、命令都经它。 */
    fun shell(): IMikaShell? = acquireShell()?.shell?.value

    // ---- 后端 ----

    private data class DisplayHandle(
        val displayId: Int,
        val backend: VdBackend,
        val width: Int,
        val height: Int,
        val frames: StateFlow<Bitmap?>,
        val png: () -> ByteArray?,
        val release: () -> Unit,
    )

    /** 试建 shell-backed 屏(经 [IMikaShell.createDisplay],画面回流本进程 ImageReader)。 */
    private fun tryShellDisplay(): DisplayHandle? {
        val shell = acquireShell()?.shell?.value ?: return null
        return runCatching {
            val ad = AgentDisplay(
                shell = shell,
                bufferWidth = SCREEN_WIDTH,
                bufferHeight = SCREEN_HEIGHT,
                dpi = SCREEN_DPI,
                displayManager = displayManager,
            )
            DisplayHandle(
                displayId = ad.displayId,
                backend = VdBackend.SHELL,
                width = ad.width,
                height = ad.height,
                frames = ad.frames,
                png = { ad.latestFramePng() },
                release = { ad.release() },
            )
        }.onFailure { Log.w(TAG, "shell display create failed: ${it.message}") }.getOrNull()
    }

    /** 本进程 own-content 虚拟屏(没 shell 时用;只能渲染本应用内容)。 */
    private fun tryLocalDisplay(index: Int): DisplayHandle? = runCatching {
        val thread = HandlerThread("mikova-vd-$index").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(SCREEN_WIDTH, SCREEN_HEIGHT, PixelFormat.RGBA_8888, 2)
        // OWN_CONTENT_ONLY(1 shl 3)| SUPPORTS_TOUCH(1 shl 6)。不带 TRUSTED:本进程无 ADD_TRUSTED_DISPLAY。
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 6) // OWN_CONTENT_ONLY | SUPPORTS_TOUCH
        val vd = displayManager.createVirtualDisplay(
            "mikova-vd-$index", SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_DPI, reader.surface, flags,
        ) ?: return null
        val state = kotlinx.coroutines.flow.MutableStateFlow<Bitmap?>(null)
        val lock = Any()
        var latest: Bitmap? = null
        var released = false

        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val strideW = plane.rowStride / plane.pixelStride
                val bmp = Bitmap.createBitmap(strideW, SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer.rewind())
                val preview = scaleTo(bmp, PREVIEW_SHORT)
                synchronized(lock) {
                    latest?.recycle()
                    latest = bmp
                    state.value = preview
                }
            } finally {
                image.close()
            }
        }, handler)

        DisplayHandle(
            displayId = vd.display.displayId,
            backend = VdBackend.LOCAL,
            width = SCREEN_WIDTH,
            height = SCREEN_HEIGHT,
            frames = state,
            png = {
                synchronized(lock) { latest?.let { scaleTo(it, PREVIEW_SHORT) } }?.let { scaled ->
                    java.io.ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 90, it) }.toByteArray()
                }
            },
            release = {
                released = true
                runCatching { vd.release() }
                handler.post {
                    runCatching { reader.close() }
                    synchronized(lock) { latest?.recycle(); latest = null }
                    state.value = null
                }
                thread.quitSafely()
            },
        )
    }.onFailure { Log.w(TAG, "local display create failed: ${it.message}") }.getOrNull()

    /**
     * 拿一条 shell 连接(进程内共用 [com.mibot.cap.phone.PhoneServices])。
     * **本包未接 [com.mibot.cap.phone.PhoneServices.Owner] 时返回 null**(见 INTEGRATION.md)——
     * 那就整条链路退到 local 后端,不崩。
     */
    private fun acquireShell(): ShellBinding? {
        if (shellBinding != null) return shellBinding
        val binding = runCatching {
            val phone = com.mibot.cap.phone.PhoneServices.of(app)
            phone.sharedShell.acquire()
        }.onFailure { Log.w(TAG, "PhoneServices unavailable (Owner not wired): ${it.message}") }.getOrNull()
        shellBinding = binding
        return binding
    }

    private fun scaleTo(src: Bitmap, shortSide: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w == 0 || h == 0) return src
        val scale = shortSide.toFloat() / minOf(w, h)
        if (scale >= 1f) return src
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, tw, th, true)
    }

    companion object {
        private const val TAG = "MikovaVd"
        const val MAX_SCREENS = 3
        const val SCREEN_WIDTH = 720
        const val SCREEN_HEIGHT = 1280
        const val SCREEN_DPI = 160
        const val PREVIEW_SHORT = 360
    }
}

/** 屏后端:shell(uid 2000,可信,能跑第三方 App) / local(本进程 own-content)。 */
enum class VdBackend { SHELL, LOCAL }

/**
 * 一块虚拟屏的只读快照。[frames] 是实时预览(缩到短边 ≤360);[png] 取整幅截图;[release] 放屏。
 * 由 [MikovaDisplayManager] 建,界面只读。
 */
data class VdScreen(
    val displayId: Int,
    val taskName: String,
    val backend: VdBackend,
    val width: Int,
    val height: Int,
    val frames: StateFlow<Bitmap?>,
    val png: () -> ByteArray?,
    val release: () -> Unit,
    val ordinal: Int,
)
