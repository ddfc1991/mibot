package com.openminis.app.mikova.vd

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.openminis.app.service.OverlayPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlin.math.abs
import kotlin.math.roundToInt

/** One explicitly shown, independently controlled preview for one virtual display. */
internal class VdIsland(
    context: Context,
    private val screen: VdScreen,
    private val controller: MikovaVdController,
    private val onVisibilityChanged: (Boolean) -> Unit,
    private val onFeedback: (String) -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val app = context.applicationContext
    private val wm = app.getSystemService(WindowManager::class.java)
    private val prefs = app.getSharedPreferences("mikova_vd_island", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val expandedKey = "expanded_${screen.displayId}"
    private val scaleKey = "scale_${screen.displayId}"
    private var expanded = prefs.getBoolean(expandedKey, true)
    private var scale = prefs.getFloat(scaleKey, 1f).coerceIn(MIN_SCALE, MAX_SCALE)
    private var root: LinearLayout? = null
    private var preview: ImageView? = null
    private var label: TextView? = null
    private var resizeHandle: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var disposed = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var startWidth = 0
    private var dragging = false
    private var gestureStart: VdIslandTouch? = null
    private val slop = 8 * app.resources.displayMetrics.density

    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(app)

    /** Returns null when shown, or a user-visible explanation when not. */
    @SuppressLint("ClickableViewAccessibility")
    fun show(): String? {
        if (disposed) return "虚拟屏 d${screen.displayId} 已关闭"
        if (!hasOverlayPermission()) {
            onVisibilityChanged(false)
            return "需要“显示在其他应用上层”权限才能显示 d${screen.displayId} 流云体"
        }
        if (attached) return null
        return try {
            val density = app.resources.displayMetrics.density
            val frame = LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xEE171B1C.toInt())
                    cornerRadius = 18 * density
                    setStroke((1 * density).roundToInt().coerceAtLeast(1), 0x668A9B98.toInt())
                }
                clipToOutline = true
                elevation = 8 * density
            }
            val header = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((10 * density).roundToInt(), 0, (6 * density).roundToInt(), 0)
                setBackgroundColor(0xFF252C2B.toInt())
            }
            val title = TextView(app).apply {
                text = "d${screen.displayId} · ${screen.taskName}"
                setTextColor(Color.WHITE)
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
            }
            val collapse = actionText(if (expanded) "收起" else "展开")
            collapse.setOnClickListener { setExpanded(!expanded) }
            val close = actionText("关闭")
            close.setOnClickListener { onDismiss() }
            header.addView(title)
            header.addView(collapse)
            header.addView(close)

            val image = ImageView(app).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
                contentDescription = "虚拟屏 d${screen.displayId} 实时画面，触摸将发送至同一屏"
            }
            val footer = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                setBackgroundColor(0xFF252C2B.toInt())
            }
            val status = TextView(app).apply {
                text = "d${screen.displayId} · ${if (screen.backend == VdBackend.LOCAL) "本进程屏，仅限本应用内容" else "可信屏"}"
                setTextColor(0xFFD5DDDA.toInt())
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, dp(28), 1f)
            }
            val resize = View(app).apply {
                contentDescription = "拖动缩放预览"
                background = GradientDrawable().apply {
                    setColor(0xFF77C9AE.toInt())
                    cornerRadius = 4 * density
                }
            }
            footer.addView(status)
            footer.addView(resize, LinearLayout.LayoutParams(dp(18), dp(18)))
            frame.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)))
            frame.addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, previewHeight()))
            frame.addView(footer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)))

            val lp = WindowManager.LayoutParams(
                previewWidth(), previewHeight() + dp(64),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.title = "MikovaIsland-${screen.displayId}"
            }
            root = frame
            preview = image
            label = status
            resizeHandle = resize
            params = lp
            wm.addView(frame, lp)
            attached = true
            applyExpanded()
            place(lp)
            attachDrag(header, frame, lp)
            attachResize(resize, frame, lp)
            attachTouch(image)
            scope.launch {
                screen.frames.collect { bitmap ->
                    if (attached && !disposed && preview === image && bitmap?.isRecycled == false) {
                        image.setImageBitmap(bitmap)
                    }
                }
            }
            onVisibilityChanged(true)
            null
        } catch (failure: Throwable) {
            detachWindow()
            onVisibilityChanged(false)
            val reason = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
            "无法显示 d${screen.displayId} 流云体：$reason"
        }
    }

    fun hide() {
        disposed = true
        onVisibilityChanged(false)
        handler.removeCallbacksAndMessages(null)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        detachWindow()
    }

    private fun actionText(value: String): TextView = TextView(app).apply {
        text = value
        setTextColor(0xFFB7EAD8.toInt())
        textSize = 11f
        gravity = Gravity.CENTER
        setPadding(dp(7), 0, dp(7), 0)
        isClickable = true
        isFocusable = true
    }

    private fun dp(value: Int): Int = (value * app.resources.displayMetrics.density).roundToInt()

    private fun previewWidth(): Int = (app.resources.displayMetrics.widthPixels * BASE_WIDTH_FRACTION * scale)
        .roundToInt().coerceAtLeast(dp(120))

    private fun previewHeight(): Int = (previewWidth().toFloat() * screen.height / screen.width).roundToInt()

    private fun setExpanded(value: Boolean) {
        expanded = value
        prefs.edit().putBoolean(expandedKey, value).apply()
        applyExpanded()
    }

    private fun applyExpanded() {
        val frame = root ?: return
        val lp = params ?: return
        frame.getChildAt(1).visibility = if (expanded) View.VISIBLE else View.GONE
        frame.getChildAt(2).visibility = if (expanded) View.VISIBLE else View.GONE
        (frame.getChildAt(0) as? LinearLayout)?.getChildAt(1)?.let {
            (it as? TextView)?.text = if (expanded) "收起" else "展开"
        }
        lp.width = if (expanded) previewWidth() else dp(210)
        lp.height = if (expanded) previewHeight() + dp(64) else dp(36)
        updateWindow(frame, lp)
    }

    private fun place(lp: WindowManager.LayoutParams) {
        val dm = app.resources.displayMetrics
        val size = root ?: return
        val point = OverlayPosition.displayed(
            positionStore(),
            OverlayPosition.Point(dm.widthPixels - lp.width - dp(12), statusBarHeight() + dp(12)),
            dm.widthPixels, dm.heightPixels, lp.width, lp.height,
            OverlayPosition.Avoid(top = statusBarHeight()),
        )
        lp.x = point.x
        lp.y = point.y
        if (attached) updateWindow(size, lp)
    }

    private fun updateWindow(view: View, lp: WindowManager.LayoutParams) {
        if (!attached) return
        try {
            wm.updateViewLayout(view, lp)
        } catch (failure: Throwable) {
            onFeedback("d${screen.displayId} 流云体窗口更新失败：${failure.message ?: failure.javaClass.simpleName}")
            detachWindow()
            onVisibilityChanged(false)
        }
    }

    private fun attachDrag(handle: View, frame: View, lp: WindowManager.LayoutParams) {
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        updateWindow(frame, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val dm = app.resources.displayMetrics
                        OverlayPosition.dragEnded(positionStore(), dm.widthPixels, dm.heightPixels, OverlayPosition.Point(lp.x, lp.y))
                    }
                    dragging = false
                    true
                }
                else -> true
            }
        }
    }

    private fun attachResize(handle: View, frame: View, lp: WindowManager.LayoutParams) {
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    startWidth = lp.width
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (event.rawX - downX).toInt()
                    val base = (app.resources.displayMetrics.widthPixels * BASE_WIDTH_FRACTION).roundToInt()
                    scale = ((startWidth + delta).toFloat() / base).coerceIn(MIN_SCALE, MAX_SCALE)
                    prefs.edit().putFloat(scaleKey, scale).apply()
                    lp.width = previewWidth()
                    lp.height = previewHeight() + dp(64)
                    frame.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    place(lp)
                    true
                }
                else -> true
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(image: ImageView) {
        image.setOnTouchListener { view, event ->
            if (!expanded) return@setOnTouchListener true
            val frame = image.drawable ?: return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStart = VdIslandTouchMapper.map(
                        displayId = screen.displayId,
                        viewX = event.x,
                        viewY = event.y,
                        viewWidth = view.width,
                        viewHeight = view.height,
                        frameWidth = frame.intrinsicWidth,
                        frameHeight = frame.intrinsicHeight,
                        displayWidth = screen.width,
                        displayHeight = screen.height,
                    )
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val start = gestureStart
                    gestureStart = null
                    val point = VdIslandTouchMapper.map(
                        displayId = screen.displayId,
                        viewX = event.x,
                        viewY = event.y,
                        viewWidth = view.width,
                        viewHeight = view.height,
                        frameWidth = frame.intrinsicWidth,
                        frameHeight = frame.intrinsicHeight,
                        displayWidth = screen.width,
                        displayHeight = screen.height,
                    ) ?: return@setOnTouchListener true
                    if (start == null || start.displayId != point.displayId) return@setOnTouchListener true
                    scope.launch {
                        val ok = if (abs(point.x - start.x) < TAP_SLOP && abs(point.y - start.y) < TAP_SLOP) {
                            controller.tap(start.displayId, point.x, point.y)
                        } else {
                            controller.swipe(start.displayId, start.x, start.y, point.x, point.y)
                        }
                        onFeedback(
                            if (ok) "d${point.displayId} 触控已发送"
                            else "d${point.displayId} 触控失败；未转发到主屏，请检查无障碍或助手连接",
                        )
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_MOVE -> {
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) gestureStart = null
                    true
                }
                else -> true
            }
        }
    }

    private fun statusBarHeight(): Int {
        val id = app.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) app.resources.getDimensionPixelSize(id) else 0
    }

    private fun positionStore(): OverlayPosition.Store = object : OverlayPosition.Store {
        override fun get(orientation: OverlayPosition.Orientation): OverlayPosition.Point? {
            val x = prefs.getInt("x_${orientation.name}_${screen.displayId}", Int.MIN_VALUE)
            val y = prefs.getInt("y_${orientation.name}_${screen.displayId}", Int.MIN_VALUE)
            return if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) null else OverlayPosition.Point(x, y)
        }

        override fun set(orientation: OverlayPosition.Orientation, point: OverlayPosition.Point) {
            prefs.edit().putInt("x_${orientation.name}_${screen.displayId}", point.x)
                .putInt("y_${orientation.name}_${screen.displayId}", point.y).apply()
        }
    }

    private fun detachWindow() {
        val frame = root
        root = null
        preview = null
        label = null
        resizeHandle = null
        params = null
        if (attached && frame != null) runCatching { wm.removeViewImmediate(frame) }
        attached = false
    }

    companion object {
        private const val BASE_WIDTH_FRACTION = 0.32f
        private const val MIN_SCALE = 0.5f
        private const val MAX_SCALE = 1.5f
        private const val TAP_SLOP = 12f
    }
}
