package com.snownamida.touchgrass.overlay

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

object DragHelper {
    /** 让 handle 拖动整个悬浮窗。handle 自身不能再挂点击事件（触摸被这里消费）。 */
    @SuppressLint("ClickableViewAccessibility")
    fun makeDraggable(
        handle: View,
        root: View,
        lp: WindowManager.LayoutParams,
        wm: WindowManager,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                    true
                }
                else -> true
            }
        }
    }
}
