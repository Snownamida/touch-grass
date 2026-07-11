package com.snownamida.touchgrass.track

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 计时统计：filesDir/stats.json。
 * v1 格式：{ "version": 1, "days": { "yyyy-MM-dd": { 包名: 秒数 } } }
 * 兼容读取旧格式（天直接在根上、按 ruleId 记账——后者由 [migrateKeys] 转换）。
 */
object StatsStore {
    private const val FILE = "stats.json"
    private const val VERSION = 1

    private fun dayKey() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    @Synchronized
    fun addSeconds(context: Context, key: String, seconds: Long) {
        if (seconds <= 0) return
        val days = loadDays(context)
        val day = days.optJSONObject(dayKey()) ?: JSONObject()
        day.put(key, day.optLong(key, 0) + seconds)
        days.put(dayKey(), day)
        persist(context, days)
    }

    @Synchronized
    fun todayByKey(context: Context): Map<String, Long> {
        val day = loadDays(context).optJSONObject(dayKey()) ?: return emptyMap()
        val result = mutableMapOf<String, Long>()
        for (key in day.keys()) result[key] = day.optLong(key, 0)
        return result
    }

    fun todayTotalSeconds(context: Context): Long = todayByKey(context).values.sum()

    /** 本周（周一起）累计秒数。 */
    @Synchronized
    fun weekTotalSeconds(context: Context): Long {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val monday = fmt.format(cal.time)
        val today = dayKey()
        val days = loadDays(context)
        var sum = 0L
        for (key in days.keys()) {
            if (key in monday..today) {
                val day = days.optJSONObject(key) ?: continue
                for (k in day.keys()) sum += day.optLong(k, 0)
            }
        }
        return sum
    }

    /** 最近 [days] 天（含今天）每天的总秒数，按时间升序。 */
    @Synchronized
    fun totalsByDay(context: Context, days: Int): List<Pair<String, Long>> {
        val all = loadDays(context)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        val out = mutableListOf<Pair<String, Long>>()
        repeat(days) {
            val key = fmt.format(cal.time)
            val day = all.optJSONObject(key)
            var sum = 0L
            if (day != null) for (k in day.keys()) sum += day.optLong(k, 0)
            out.add(key to sum)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    /** 老版本按 ruleId 记账，按 [mapping]（ruleId → 包名）迁移一次。 */
    @Synchronized
    fun migrateKeys(context: Context, mapping: Map<String, String>) {
        if (mapping.isEmpty()) return
        val days = loadDays(context)
        var changed = false
        for (d in days.keys().asSequence().toList()) {
            val day = days.optJSONObject(d) ?: continue
            for (old in day.keys().asSequence().toList()) {
                val new = mapping[old] ?: continue
                day.put(new, day.optLong(new, 0) + day.optLong(old, 0))
                day.remove(old)
                changed = true
            }
        }
        if (changed) persist(context, days)
    }

    private fun loadDays(context: Context): JSONObject = try {
        val f = context.filesDir.resolve(FILE)
        if (!f.exists()) JSONObject()
        else {
            val root = JSONObject(f.readText())
            root.optJSONObject("days") ?: root // 旧格式：天直接在根上
        }
    } catch (e: Exception) {
        JSONObject()
    }

    private fun persist(context: Context, days: JSONObject) {
        context.filesDir.resolve(FILE).writeText(
            JSONObject().put("version", VERSION).put("days", days).toString()
        )
    }
}
