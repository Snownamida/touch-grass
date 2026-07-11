package com.snownamida.touchgrass.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.snownamida.touchgrass.AppState
import com.snownamida.touchgrass.capture.CaptureSession
import com.snownamida.touchgrass.capture.SnapshotTaker
import com.snownamida.touchgrass.debug.DebugLog
import com.snownamida.touchgrass.overlay.CaptureOverlay
import com.snownamida.touchgrass.overlay.DebugOverlay
import com.snownamida.touchgrass.overlay.InterventionOverlay
import com.snownamida.touchgrass.overlay.KeepAliveOverlay
import com.snownamida.touchgrass.overlay.ReminderOverlay
import com.snownamida.touchgrass.overlay.TimerOverlay
import com.snownamida.touchgrass.rules.Matcher
import com.snownamida.touchgrass.rules.Rule
import com.snownamida.touchgrass.rules.RuleGenerator
import com.snownamida.touchgrass.rules.RuleStore
import com.snownamida.touchgrass.track.SessionTracker

class WatcherService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: WatcherService? = null
            private set

        private const val RESOLVE_DELAY_MS = 250L
        private const val CONTENT_THROTTLE_MS = 1000L
        private const val SNAPSHOT_DELAY_MS = 400L

        /** 监测模式的兜底心跳：就算系统吞了无障碍事件（MIUI 后台管控），也每 3 秒主动查一次前台。 */
        private const val HEARTBEAT_MS = 3000L
    }

    private val handler = Handler(Looper.getMainLooper())

    private var mode = AppState.Mode.OFF
    private var rules: List<Rule> = emptyList()
    private lateinit var tracker: SessionTracker

    var currentPackage: String? = null
        private set
    var currentActivity: String? = null
        private set
    private val lastActivityByPkg = HashMap<String, String>()
    private val activityClassCache = HashMap<String, Boolean>()

    private var captureOverlay: CaptureOverlay? = null
    private var timerOverlay: TimerOverlay? = null
    private var debugOverlay: DebugOverlay? = null
    private var interventionOverlay: InterventionOverlay? = null
    private var reminderOverlay: ReminderOverlay? = null
    private var keepAliveOverlay: KeepAliveOverlay? = null
    private var debugEnabled = false
    private var lastContentResolve = 0L
    private var lastLoggedFg: String? = null
    private var lastEvalLine: String? = null

    private val heartbeat = object : Runnable {
        override fun run() {
            if (mode != AppState.Mode.WATCH) return
            resolveForegroundAndEvaluate()
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (::tracker.isInitialized) tracker.forceEnd()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tracker = SessionTracker(this)
        rules = RuleStore.load(this)
        debugEnabled = AppState.isDebug(this)
        DebugLog.log("服务已连接")
        ContextCompat.registerReceiver(
            this, screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        applyMode(AppState.getMode(this))
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { unregisterReceiver(screenOffReceiver) }
        if (::tracker.isInitialized) tracker.forceEnd()
        captureOverlay?.hide()
        timerOverlay?.hide()
        debugOverlay?.hide()
        interventionOverlay?.hide()
        reminderOverlay?.hide()
        keepAliveOverlay?.hide()
        handler.removeCallbacksAndMessages(null)
        DebugLog.log("服务已断开")
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---------- 事件入口 ----------

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (mode == AppState.Mode.OFF) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 事件只用来记录「某包最近处于哪个 Activity」，
                // 前台是谁交给 resolveForeground 从窗口焦点判断——
                // 截屏、安全中心这类系统悬浮窗的事件会污染 packageName，不能直接信
                val cls = event.className?.toString()
                if (cls != null && isActivityClass(pkg, cls)) {
                    lastActivityByPkg[pkg] = cls
                }
                scheduleResolve(RESOLVE_DELAY_MS)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 限频地重新确认前台并评估：既能在悬浮窗噪声后自我纠正，
                // 也覆盖了需要查控件树的规则
                val now = SystemClock.uptimeMillis()
                if (now - lastContentResolve > CONTENT_THROTTLE_MS) {
                    lastContentResolve = now
                    scheduleResolve(150L)
                }
            }
        }
    }

    /** 类名能被 PackageManager 解析成 Activity 才算页面切换（过滤掉弹窗/自定义窗口类）。 */
    private fun isActivityClass(pkg: String, cls: String): Boolean {
        val key = "$pkg/$cls"
        return activityClassCache.getOrPut(key) {
            try {
                packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    // ---------- 前台判定 + 规则匹配 ----------

    private val resolveRunnable = Runnable { resolveForegroundAndEvaluate() }

    private fun scheduleResolve(delayMs: Long) {
        handler.removeCallbacks(resolveRunnable)
        handler.postDelayed(resolveRunnable, delayMs)
    }

    private fun resolveForegroundAndEvaluate() {
        pickForegroundPackage()?.let { fg ->
            currentPackage = fg
            currentActivity = lastActivityByPkg[fg]
        }
        if (currentPackage != lastLoggedFg) {
            lastLoggedFg = currentPackage
            DebugLog.log("前台→ $currentPackage [${windowsBrief()}]")
        }
        captureOverlay?.updateStatus(currentPackage, currentActivity)
        if (mode == AppState.Mode.WATCH) evaluate()
    }

    /** 真正的前台 = 持有焦点的应用窗口；退而求其次取活跃窗口、最顶层应用窗口。 */
    private fun pickForegroundPackage(): String? {
        var focused: String? = null
        var active: String? = null
        var top: String? = null
        var topLayer = Int.MIN_VALUE
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val p = w.root?.packageName?.toString() ?: continue
            if (focused == null && w.isFocused) focused = p
            if (active == null && w.isActive) active = p
            if (w.layer > topLayer) {
                topLayer = w.layer
                top = p
            }
        }
        return focused ?: active ?: top
    }

    /** 各应用窗口一览，*F=焦点 *A=活跃，调试用。 */
    private fun windowsBrief(): String = windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        .joinToString(",") { w ->
            val p = w.root?.packageName?.toString()?.substringAfterLast('.') ?: "?"
            buildString {
                append(p)
                if (w.isFocused) append("*F")
                if (w.isActive) append("*A")
            }
        }

    private fun evaluate() {
        val pkg = currentPackage
        var matched: Rule? = null
        val reason: String
        if (pkg == null) {
            reason = "前台未知"
        } else {
            val rule = rules.firstOrNull { it.packageName == pkg }
            if (rule == null) {
                reason = "无规则"
            } else {
                matched = if (ruleMatches(rule)) rule else null
                reason = if (matched != null) "命中「${rule.name}」" else explainMiss(rule)
            }
        }
        val line = "${pkg?.substringAfterLast('.') ?: "?"}/" +
            "${currentActivity?.substringAfterLast('.') ?: "?"} → $reason"
        if (line != lastEvalLine) {
            lastEvalLine = line
            DebugLog.log(line)
        }
        debugOverlay?.setText(line)
        tracker.onEvaluated(matched)
    }

    /** 未命中时给出每个条件组失败在哪一步，调试用。 */
    private fun explainMiss(rule: Rule): String {
        val act = currentActivity
        val reasons = rule.matchers.mapIndexed { i, m ->
            val tag = "组${i + 1}"
            when {
                m.activities.isNotEmpty() && act == null -> "$tag:未知activity"
                m.activities.isNotEmpty() && act !in m.activities -> "$tag:activity不符"
                m.needsNodeCheck -> {
                    val root = findRootFor(rule.packageName)
                    if (root == null) "$tag:拿不到窗口"
                    else {
                        val missing = m.requiredIds.firstOrNull {
                            root.findAccessibilityNodeInfosByViewId(it).isNullOrEmpty()
                        }
                        if (missing != null) "$tag:缺${missing.substringAfterLast('/')}"
                        else "$tag:命中了排除id"
                    }
                }
                else -> "$tag:?"
            }
        }
        return "未命中(${reasons.joinToString(";")})"
    }

    private fun ruleMatches(rule: Rule): Boolean =
        rule.matchers.any { matcherMatches(rule.packageName, it) }

    private fun matcherMatches(pkg: String, m: Matcher): Boolean {
        if (m.activities.isNotEmpty()) {
            val act = currentActivity ?: return false
            if (act !in m.activities) return false
        }
        if (!m.needsNodeCheck) return true

        val root = findRootFor(pkg) ?: return false
        for (id in m.requiredIds) {
            if (root.findAccessibilityNodeInfosByViewId(id).isNullOrEmpty()) return false
        }
        for (id in m.forbiddenIds) {
            if (!root.findAccessibilityNodeInfosByViewId(id).isNullOrEmpty()) return false
        }
        return true
    }

    private fun findRootFor(pkg: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.let {
            if (it.packageName?.toString() == pkg) return it
        }
        return windows.firstOrNull { w ->
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                w.root?.packageName?.toString() == pkg
        }?.root
    }

    // ---------- 模式切换 ----------

    fun applyMode(newMode: AppState.Mode) {
        handler.post {
            mode = newMode
            DebugLog.log("模式→ $newMode")
            when (newMode) {
                AppState.Mode.OFF -> {
                    captureOverlay?.hide()
                    tracker.forceEnd()
                    handler.removeCallbacks(heartbeat)
                    keepAliveOverlay?.hide()
                }
                AppState.Mode.CAPTURE -> {
                    tracker.forceEnd()
                    showCaptureOverlay()
                    handler.removeCallbacks(heartbeat)
                    keepAliveOverlay?.hide()
                    scheduleResolve(100L)
                }
                AppState.Mode.WATCH -> {
                    captureOverlay?.hide()
                    scheduleResolve(100L)
                    handler.removeCallbacks(heartbeat)
                    handler.postDelayed(heartbeat, HEARTBEAT_MS)
                    if (keepAliveOverlay == null) keepAliveOverlay = KeepAliveOverlay(this)
                    keepAliveOverlay!!.show()
                    DebugLog.log("保活窗已挂载")
                }
            }
            updateDebugOverlay()
        }
    }

    fun applyDebug(enabled: Boolean) {
        handler.post {
            debugEnabled = enabled
            updateDebugOverlay()
        }
    }

    private fun updateDebugOverlay() {
        if (debugEnabled && mode == AppState.Mode.WATCH) {
            if (debugOverlay == null) debugOverlay = DebugOverlay(this)
            debugOverlay!!.show()
        } else {
            debugOverlay?.hide()
        }
    }

    fun reloadRules() {
        rules = RuleStore.load(this)
        scheduleResolve(100L)
    }

    private fun showCaptureOverlay() {
        if (captureOverlay == null) captureOverlay = CaptureOverlay(this)
        captureOverlay!!.show()
        captureOverlay!!.updateStatus(currentPackage, currentActivity)
        val pkg = CaptureSession.lastSampledPackage ?: currentPackage
        if (pkg != null) {
            val (p, n) = CaptureSession.countsFor(pkg)
            captureOverlay!!.updateCounts(p, n)
        }
    }

    // ---------- 捕捉模式动作（由悬浮窗按钮触发）----------

    fun captureSample(positive: Boolean) {
        val pkg = currentPackage
        if (pkg == null) {
            toast("还没检测到前台应用，先去目标页面随便滑一下")
            return
        }
        if (pkg == packageName) {
            toast("当前前台是本应用自己，去目标 app 再采样")
            return
        }
        val act = currentActivity
        // 稍等界面稳定再抓，避免抓到滚动动画中途的树
        handler.postDelayed({
            val snap = SnapshotTaker.take(this, pkg, act)
            if (snap == null) {
                toast("采集失败：读不到 ${appLabel(pkg)} 的窗口内容")
                return@postDelayed
            }
            CaptureSession.add(snap.copy(positive = positive))
            val (p, n) = CaptureSession.countsFor(pkg)
            captureOverlay?.updateCounts(p, n)
            toast("已采${if (positive) "正" else "负"}样本：${appLabel(pkg)}（${snap.ids.size} 个控件 id）")
        }, SNAPSHOT_DELAY_MS)
    }

    fun generateRuleFromSamples() {
        val pkg = CaptureSession.lastSampledPackage
        if (pkg == null) {
            toast("还没有样本：短视频页点 ✓、普通页点 ✕，再来生成")
            return
        }
        val (pos, neg) = CaptureSession.samplesFor(pkg)
        when (val result = RuleGenerator.generate(pos, neg)) {
            is RuleGenerator.Result.Ok -> {
                val (rule, merged) = RuleStore.addOrMerge(this, pkg, appLabel(pkg), result.matchers)
                CaptureSession.clearFor(pkg)
                reloadRules()
                captureOverlay?.updateCounts(0, 0)
                val head = if (merged) "已并入规则「${rule.name}」（现 ${rule.matchers.size} 组条件）"
                else "规则已保存：${rule.name}"
                toast("$head\n${result.note.orEmpty()}".trim())
            }
            is RuleGenerator.Result.Fail -> toast("生成失败：${result.reason}")
        }
    }

    // ---------- 计时悬浮窗（由 SessionTracker 调用）----------

    fun showTimerOverlay(textProvider: () -> String) {
        if (timerOverlay == null) timerOverlay = TimerOverlay(this)
        timerOverlay!!.show(textProvider)
    }

    fun hideTimerOverlay() {
        timerOverlay?.hide()
    }

    // ---------- 超额干预与周期提醒（由 SessionTracker 调用）----------

    fun showIntervention(reason: String) {
        if (interventionOverlay == null) interventionOverlay = InterventionOverlay(
            this,
            onLeave = {
                // 「不刷了」：给 60 秒离开页面；赖着不走会再次被拦
                tracker.snooze(60_000L)
                hideIntervention()
            },
            onMore = {
                tracker.snooze(5 * 60_000L)
                hideIntervention()
            },
        )
        interventionOverlay!!.show(reason)
    }

    fun hideIntervention() {
        interventionOverlay?.hide()
    }

    fun isInterventionShowing(): Boolean = interventionOverlay?.isShowing == true

    fun flashReminder(text: String) {
        if (reminderOverlay == null) reminderOverlay = ReminderOverlay(this)
        reminderOverlay!!.flash(text)
    }

    // ---------- 工具 ----------

    fun appLabel(pkg: String): String = try {
        val ai = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(ai).toString()
    } catch (e: Exception) {
        pkg
    }

    fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
