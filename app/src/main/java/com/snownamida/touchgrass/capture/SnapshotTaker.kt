package com.snownamida.touchgrass.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.snownamida.touchgrass.model.Snapshot

object SnapshotTaker {
    private const val MAX_NODES = 4000
    private const val MAX_DEPTH = 60

    /** 抓取目标包当前窗口的控件树快照；拿不到窗口返回 null。 */
    fun take(service: AccessibilityService, targetPackage: String, activity: String?): Snapshot? {
        val root = pickRoot(service, targetPackage) ?: return null
        val ids = LinkedHashSet<String>()
        var count = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= MAX_NODES || depth > MAX_DEPTH) return
            count++
            node.viewIdResourceName?.let { ids.add(it) }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }

        walk(root, 0)
        if (count == 0) return null
        return Snapshot(targetPackage, activity, ids, count)
    }

    /** 优先 rootInActiveWindow，否则在全部窗口里找目标包的应用窗口（排除自己的悬浮窗）。 */
    private fun pickRoot(service: AccessibilityService, pkg: String): AccessibilityNodeInfo? {
        service.rootInActiveWindow?.let {
            if (it.packageName?.toString() == pkg) return it
        }
        return service.windows.firstOrNull { w ->
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                w.root?.packageName?.toString() == pkg
        }?.root
    }
}
