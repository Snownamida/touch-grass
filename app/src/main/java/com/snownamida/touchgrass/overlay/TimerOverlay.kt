package com.snownamida.touchgrass.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
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
            // 记住上次拖到的位置；没拖过则默认顶部居中
            val prefs = service.getSharedPreferences("touchgrass", Context.MODE_PRIVATE)
            val savedX = prefs.getInt("timerOverlayX", Int.MIN_VALUE)
            val savedY = prefs.getInt("timerOverlayY", Int.MIN_VALUE)
            if (savedX != Int.MIN_VALUE && savedY != Int.MIN_VALUE) {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = savedX
                lp.y = savedY
            } else {
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.y = dp(48)
            }

            DragHelper.makeDraggable(tv, tv, lp, wm) {
                // 落点统一换算成 TOP|START 坐标系再记忆
                val centered =
                    (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL
                val absX = if (centered) {
                    (service.resources.displayMetrics.widthPixels - tv.width) / 2 + lp.x
                } else lp.x
                prefs.edit().putInt("timerOverlayX", absX).putInt("timerOverlayY", lp.y).apply()
            }
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
