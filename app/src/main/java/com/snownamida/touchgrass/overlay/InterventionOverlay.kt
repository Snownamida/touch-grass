package com.snownamida.touchgrass.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.snownamida.touchgrass.R

/** 超额干预全屏遮罩：先强制冷静一段时间，再给「不刷了 / 再刷 5 分钟」的选择。 */
class InterventionOverlay(
    private val service: AccessibilityService,
    private val onLeave: () -> Unit,
    private val onMore: () -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var countdown = 0

    val isShowing: Boolean get() = view != null

    private val tick = object : Runnable {
        override fun run() {
            val v = view ?: return
            countdown--
            val cd = v.findViewById<TextView>(R.id.text_countdown)
            if (countdown > 0) {
                cd.text = "先冷静 $countdown 秒…"
                handler.postDelayed(this, 1000L)
            } else {
                cd.text = "想好了吗？"
                v.findViewById<View>(R.id.row_choices).visibility = View.VISIBLE
            }
        }
    }

    fun show(reason: String) {
        view?.let {
            it.findViewById<TextView>(R.id.text_reason).text = reason
            return
        }
        val v = LayoutInflater.from(service).inflate(R.layout.overlay_intervention, null)
        v.findViewById<TextView>(R.id.text_reason).text = reason
        v.findViewById<View>(R.id.row_choices).visibility = View.INVISIBLE
        v.findViewById<View>(R.id.btn_leave).setOnClickListener { onLeave() }
        v.findViewById<View>(R.id.btn_more).setOnClickListener { onMore() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(v, lp)
        view = v

        countdown = CALM_SECONDS
        v.findViewById<TextView>(R.id.text_countdown).text = "先冷静 $countdown 秒…"
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000L)
    }

    fun hide() {
        handler.removeCallbacks(tick)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    companion object {
        private const val CALM_SECONDS = 30
    }
}
