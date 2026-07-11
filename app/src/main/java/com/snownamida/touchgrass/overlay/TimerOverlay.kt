package com.snownamida.touchgrass.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.snownamida.touchgrass.R

/** 计时药丸：命中规则时悬浮在屏幕顶部，每秒刷新，可拖动。 */
class TimerOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var textProvider: (() -> String)? = null

    private val tick = object : Runnable {
        override fun run() {
            val v = view ?: return
            v.text = textProvider?.invoke() ?: ""
            handler.postDelayed(this, 1000)
        }
    }

    fun show(provider: () -> String) {
        textProvider = provider
        if (view == null) {
            val density = service.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val tv = TextView(service)
            tv.setBackgroundResource(R.drawable.bg_overlay_pill)
            tv.setTextColor(Color.WHITE)
            tv.textSize = 13f
            tv.setPadding(dp(16), dp(7), dp(16), dp(7))

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = dp(48)

            DragHelper.makeDraggable(tv, tv, lp, wm)
            wm.addView(tv, lp)
            view = tv
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun hide() {
        handler.removeCallbacks(tick)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
