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

/** 周期提醒：屏幕中央闪现一条提示，几秒后自动消失。 */
class ReminderOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null

    private val hideRunnable = Runnable { hide() }

    fun flash(text: String) {
        if (view == null) {
            val density = service.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val tv = TextView(service)
            tv.setBackgroundResource(R.drawable.bg_overlay_panel)
            tv.setTextColor(Color.WHITE)
            tv.textSize = 16f
            tv.setPadding(dp(22), dp(14), dp(22), dp(14))

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.CENTER
            wm.addView(tv, lp)
            view = tv
        }
        view!!.text = text
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 3000L)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
