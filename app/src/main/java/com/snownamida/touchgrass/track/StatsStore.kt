package com.snownamida.touchgrass.track

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 计时统计：filesDir/stats.json，结构 { "yyyy-MM-dd": { ruleId: 秒数 } }。 */
object StatsStore {
    private const val FILE = "stats.json"

    private fun dayKey() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    @Synchronized
    fun addSeconds(context: Context, ruleId: String, seconds: Long) {
        if (seconds <= 0) return
        val json = load(context)
        val day = json.optJSONObject(dayKey()) ?: JSONObject()
        day.put(ruleId, day.optLong(ruleId, 0) + seconds)
        json.put(dayKey(), day)
        context.filesDir.resolve(FILE).writeText(json.toString())
    }

    @Synchronized
    fun todayByRule(context: Context): Map<String, Long> {
        val day = load(context).optJSONObject(dayKey()) ?: return emptyMap()
        val result = mutableMapOf<String, Long>()
        for (key in day.keys()) result[key] = day.optLong(key, 0)
        return result
    }

    fun todayTotalSeconds(context: Context): Long = todayByRule(context).values.sum()

    /** 本周（周一起）累计秒数。 */
    @Synchronized
    fun weekTotalSeconds(context: Context): Long {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val monday = fmt.format(cal.time)
        val today = dayKey()
        val json = load(context)
        var sum = 0L
        for (key in json.keys()) {
            if (key in monday..today) {
                val day = json.optJSONObject(key) ?: continue
                for (k in day.keys()) sum += day.optLong(k, 0)
            }
        }
        return sum
    }

    /** 最近 [days] 天（含今天）每天的总秒数，按时间升序。 */
    @Synchronized
    fun totalsByDay(context: Context, days: Int): List<Pair<String, Long>> {
        val json = load(context)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        val out = mutableListOf<Pair<String, Long>>()
        repeat(days) {
            val key = fmt.format(cal.time)
            val day = json.optJSONObject(key)
            var sum = 0L
            if (day != null) for (k in day.keys()) sum += day.optLong(k, 0)
            out.add(key to sum)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    private fun load(context: Context): JSONObject = try {
        val f = context.filesDir.resolve(FILE)
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }
}
