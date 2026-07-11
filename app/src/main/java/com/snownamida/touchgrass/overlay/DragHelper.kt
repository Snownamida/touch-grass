package com.snownamida.touchgrass.overlay

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

object DragHelper {
    /**
     * 让 handle 拖动整个悬浮窗。handle 自身不能再挂点击事件（触摸被这里消费）。
     * [onDragEnd] 在一次实际发生位移的拖动结束时回调，可用于持久化位置。
     */
    @SuppressLint("ClickableViewAccessibility")
    fun makeDraggable(
        handle: View,
        root: View,
        lp: WindowManager.LayoutParams,
        wm: WindowManager,
        onDragEnd: (() -> Unit)? = null,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (moved || abs(dx) > 8 || abs(dy) > 8) {
                        moved = true
                        lp.x = startX + dx
                        lp.y = startY + dy
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) onDragEnd?.invoke()
                    true
                }
                else -> true
            }
        }
    }
}
