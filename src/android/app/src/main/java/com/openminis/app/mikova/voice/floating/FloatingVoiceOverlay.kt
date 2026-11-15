package com.openminis.app.mikova.voice.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView

/**
 * [mikova/voice/floating] 浮层语音的窗口管理 —— 参照 deepdeep 的悬浮对话窗。
 *
 * 用非模态 Dialog(自带 LifecycleOwner)承载 Compose 界面,window 设为
 * TYPE_APPLICATION_OVERLAY + NOT_TOUCH_MODAL + NOT_FOCUSABLE,既浮在桌面上又不抢焦点,
 * 可拖动、可收起、可关闭。收起=缩成顶部胶囊条,展开=完整对话卡。
 */
object FloatingVoiceOverlay {

    private const val TAG = "FloatingVoice"

    private var dialog: ComponentDialog? = null
    private var collapsed = false
    private var viewWidth = 0
    private var viewHeight = 0
    private var startX = 0
    private var startY = 0
    private var touchX = 0f
    private var touchY = 0f

    @Volatile
    var visible: Boolean = false
        private set

    /** 是否可显示(悬浮窗权限已授予)。 */
    fun canShow(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /** 显示(或展开)浮层。无悬浮窗权限时返回 false,调用方应引导用户授权。 */
    fun show(context: Context): Boolean {
        if (dialog?.isShowing == true) { collapsed = false; refresh(); return true }
        if (!canShow(context)) return false
        FloatingVoiceController.ensureInit(context)
        val dlg = ComponentDialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        val window = dlg.window ?: return false
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        // NOT_TOUCH_MODAL lets touches outside the overlay pass through, while
        // NOT_FOCUSABLE is intentionally NOT set so the overlay can take focus:
        // the uiautomator smoke probe can then see it (and its close button),
        // and BACK dismisses the overlay first instead of popping the shell.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0f)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        // 初始位置:屏幕上方留 48dp
        val dm = context.resources.displayMetrics
        window.setLayout(
            (dm.widthPixels * 0.94f).toInt(),
            (dm.heightPixels * 0.62f).toInt(),
        )
        val lp = window.attributes
        lp.y = (dm.density * 48).toInt()
        lp.title = "FloatingVoice"
        window.attributes = lp

        val compose = ComposeView(context)
        compose.setContent {
            FloatingVoiceApp(
                onClose = { hide() },
                onCollapse = { collapsed = true; refresh() },
            )
        }
        makeRounded(compose, context)
        dlg.setContentView(compose)
        // BACK dismisses the overlay first (it now takes focus). Also allow
        // cancel-on-back even though NOT_TOUCH_MODAL keeps outside touches live.
        dlg.setCancelable(true)
        dlg.setOnDismissListener { visible = false; FloatingVoiceController.onOverlayClosed() }
        dlg.show()
        dialog = dlg
        visible = true
        return true
    }

    /** 收起/展开切换。 */
    fun toggleCollapse(context: Context) {
        collapsed = !collapsed
        refresh()
    }

    /** 隐藏并释放浮层。 */
    fun hide() {
        try { dialog?.dismiss() } catch (_: Throwable) {}
        dialog = null
        visible = false
        FloatingVoiceController.onOverlayClosed()
    }

    private fun refresh() {
        val dlg = dialog ?: return
        val window = dlg.window ?: return
        val context = dlg.context
        val dm = context.resources.displayMetrics
        if (collapsed) {
            window.setLayout((dm.widthPixels * 0.94f).toInt(), (dm.density * 56).toInt())
        } else {
            window.setLayout((dm.widthPixels * 0.94f).toInt(), (dm.heightPixels * 0.62f).toInt())
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeRounded(view: View, context: Context) {
        val shape = GradientDrawable().apply {
            cornerRadius = context.resources.displayMetrics.density * 24f
            setColor(Color.TRANSPARENT)
        }
        view.background = shape
        val dm = context.resources.displayMetrics
        view.setOnTouchListener { v, event ->
            val lp = (dialog?.window?.attributes) ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    lp.x = startX + dx
                    lp.y = startY + dy
                    // 限制不拖出屏幕
                    lp.x = lp.x.coerceIn(-(dm.widthPixels / 2), dm.widthPixels / 2)
                    lp.y = lp.y.coerceIn(0, dm.heightPixels - (dm.density * 120).toInt())
                    dialog?.window?.attributes = lp
                    true
                }
                else -> false
            }
        }
    }
}
