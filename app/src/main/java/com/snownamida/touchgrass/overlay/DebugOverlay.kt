package com.snownamida.touchgrass.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.snownamida.touchgrass.R

/** 调试悬浮条：监测模式下实时显示「前台/判定结果」，可拖动。 */
class DebugOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: TextView? = null

    fun show() {
        if (view != null) return
        val density = service.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val tv = TextView(service)
        tv.setBackgroundResource(R.drawable.bg_overlay_panel)
        tv.setTextColor(Color.parseColor("#B5F2C5"))
        tv.textSize = 10f
        tv.setPadding(dp(10), dp(5), dp(10), dp(5))
        tv.maxWidth = dp(280)
        tv.text = "调试：等待判定…"

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(8)
        lp.y = dp(100)

        DragHelper.makeDraggable(tv, tv, lp, wm)
        wm.addView(tv, lp)
        view = tv
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    fun setText(s: String) {
        view?.text = s
    }
}
