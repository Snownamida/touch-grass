package com.snownamida.touchgrass.overlay

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.snownamida.touchgrass.AppState
import com.snownamida.touchgrass.R
import com.snownamida.touchgrass.service.WatcherService

/** 捕捉模式悬浮面板：显示当前前台页面，提供 ✓/✕ 采样与生成规则按钮。 */
class CaptureOverlay(private val service: WatcherService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var statusText: TextView? = null
    private var countsText: TextView? = null

    fun show() {
        if (view != null) return
        val v = LayoutInflater.from(service).inflate(R.layout.overlay_capture, null)
        statusText = v.findViewById(R.id.text_status)
        countsText = v.findViewById(R.id.text_counts)
        v.findViewById<View>(R.id.btn_positive).setOnClickListener { service.captureSample(true) }
        v.findViewById<View>(R.id.btn_negative).setOnClickListener { service.captureSample(false) }
        v.findViewById<View>(R.id.btn_generate).setOnClickListener { service.generateRuleFromSamples() }
        v.findViewById<View>(R.id.btn_close).setOnClickListener {
            AppState.setMode(service, AppState.Mode.OFF)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val density = service.resources.displayMetrics.density
        lp.x = (12 * density).toInt()
        lp.y = (160 * density).toInt()

        DragHelper.makeDraggable(v.findViewById(R.id.drag_handle), v, lp, wm)
        wm.addView(v, lp)
        view = v
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        statusText = null
        countsText = null
    }

    fun updateStatus(pkg: String?, activity: String?) {
        statusText?.text = if (pkg == null) {
            "等前台应用出现…去目标 app 随便滑一下"
        } else {
            "${service.appLabel(pkg)} · $pkg\n${activity ?: "未知页面"}"
        }
    }

    fun updateCounts(pos: Int, neg: Int) {
        countsText?.text = "正 $pos · 负 $neg"
    }
}
