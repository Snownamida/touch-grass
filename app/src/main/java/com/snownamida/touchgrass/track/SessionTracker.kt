package com.snownamida.touchgrass.track

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.snownamida.touchgrass.debug.DebugLog
import com.snownamida.touchgrass.rules.Rule
import com.snownamida.touchgrass.service.WatcherService

/**
 * 计时会话：规则命中即开始，失配后留 2.5 秒缓冲再结束——
 * 短视频之间上滑切换时的瞬时状态不会打断计时。
 * 会话进行中每秒检查一次周期提醒与额度（单次/每日/每周），超额触发全屏干预。
 */
class SessionTracker(private val service: WatcherService) {
    private val handler = Handler(Looper.getMainLooper())

    private var activeRule: Rule? = null
    private var sessionStartMs = 0L
    private var todayBaseSec = 0L
    private var weekBaseSec = 0L
    private var pendingEnd: Runnable? = null
    private var snoozeUntilMs = 0L
    private var nextRemindSec = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (activeRule == null) return
            checkReminderAndLimits()
            handler.postDelayed(this, 1000L)
        }
    }

    fun onEvaluated(rule: Rule?) {
        if (rule != null) {
            pendingEnd?.let { handler.removeCallbacks(it) }
            pendingEnd = null
            val active = activeRule
            when {
                active == null -> start(rule)
                active.id != rule.id -> { end(); start(rule) }
            }
        } else if (activeRule != null && pendingEnd == null) {
            val r = Runnable {
                pendingEnd = null
                end()
            }
            pendingEnd = r
            handler.postDelayed(r, GRACE_MS)
        }
    }

    /** 熄屏、关闭模式、服务销毁时立即结束。 */
    fun forceEnd() {
        pendingEnd?.let { handler.removeCallbacks(it) }
        pendingEnd = null
        end()
    }

    /** 干预弹层上的选择产生的豁免期：期间不再触发干预。 */
    fun snooze(ms: Long) {
        snoozeUntilMs = SystemClock.elapsedRealtime() + ms
    }

    private fun start(rule: Rule) {
        activeRule = rule
        sessionStartMs = SystemClock.elapsedRealtime()
        todayBaseSec = StatsStore.todayTotalSeconds(service)
        weekBaseSec = StatsStore.weekTotalSeconds(service)
        nextRemindSec = LimitConfig.load(service).remindMin.toLong() * 60
        DebugLog.log("▶ 开始计时「${rule.name}」")
        service.showTimerOverlay { overlayText() }
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000L)
    }

    private fun end() {
        val rule = activeRule ?: return
        activeRule = null
        handler.removeCallbacks(ticker)
        val sec = (SystemClock.elapsedRealtime() - sessionStartMs) / 1000
        DebugLog.log("⏹ 结束计时「${rule.name}」+${sec}s")
        StatsStore.addSeconds(service, rule.id, sec)
        service.hideTimerOverlay()
        service.hideIntervention()
    }

    private fun sessionSec() = (SystemClock.elapsedRealtime() - sessionStartMs) / 1000

    private fun checkReminderAndLimits() {
        if (service.isInterventionShowing()) return
        val cfg = LimitConfig.load(service)
        val s = sessionSec()
        val today = todayBaseSec + s
        val week = weekBaseSec + s

        if (SystemClock.elapsedRealtime() >= snoozeUntilMs) {
            val reason = when {
                cfg.sessionMin > 0 && s >= cfg.sessionMin * 60L ->
                    "这一波已经连刷 ${s / 60} 分钟（单次上限 ${cfg.sessionMin} 分钟）"
                cfg.dailyMin > 0 && today >= cfg.dailyMin * 60L ->
                    "今天已经刷了 ${today / 60} 分钟（每日上限 ${cfg.dailyMin} 分钟）"
                cfg.weeklyMin > 0 && week >= cfg.weeklyMin * 60L ->
                    "本周已经刷了 ${week / 60} 分钟（每周上限 ${cfg.weeklyMin} 分钟）"
                else -> null
            }
            if (reason != null) {
                DebugLog.log("⛔ 触发干预：$reason")
                service.showIntervention(reason)
                return
            }
        }

        if (cfg.remindMin > 0 && s >= nextRemindSec) {
            nextRemindSec += cfg.remindMin * 60L
            DebugLog.log("🔔 周期提醒：连刷 ${s / 60} 分钟")
            service.flashReminder("🌱 这一波已经刷了 ${s / 60} 分钟")
        }
    }

    private fun overlayText(): String {
        val cur = sessionSec()
        return "本次 ${fmt(cur)} · 今日 ${fmt(todayBaseSec + cur)}"
    }

    private fun fmt(sec: Long): String =
        if (sec >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
        else String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60)

    companion object {
        private const val GRACE_MS = 2500L
    }
}
