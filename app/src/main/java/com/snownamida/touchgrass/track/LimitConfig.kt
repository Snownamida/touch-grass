package com.snownamida.touchgrass.track

import android.content.Context

/** 各项额度，单位分钟，0 = 不限/关闭。 */
data class Limits(
    val sessionMin: Int,
    val dailyMin: Int,
    val weeklyMin: Int,
    val remindMin: Int,
)

object LimitConfig {
    private const val PREFS = "touchgrass"

    fun load(context: Context): Limits {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Limits(
            sessionMin = p.getInt("limitSessionMin", 15),
            dailyMin = p.getInt("limitDailyMin", 45),
            weeklyMin = p.getInt("limitWeeklyMin", 300),
            remindMin = p.getInt("remindEveryMin", 5),
        )
    }

    fun save(context: Context, limits: Limits) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("limitSessionMin", limits.sessionMin)
            .putInt("limitDailyMin", limits.dailyMin)
            .putInt("limitWeeklyMin", limits.weeklyMin)
            .putInt("remindEveryMin", limits.remindMin)
            .apply()
    }
}
