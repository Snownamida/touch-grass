package com.snownamida.touchgrass.rules

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 规则持久化：filesDir/rules.json，纯本地。
 * v2 格式：{ "version": 2, "rules": [...] }；兼容读取裸数组的旧导出。
 */
object RuleStore {
    private const val FILE = "rules.json"
    private const val VERSION = 2

    @Synchronized
    fun load(context: Context): List<Rule> = try {
        val f = context.filesDir.resolve(FILE)
        if (!f.exists()) emptyList() else normalize(parseRules(f.readText()))
    } catch (e: Exception) {
        emptyList()
    }

    /** 兼容两种格式：裸数组（旧导出）/ 带版本号的对象。 */
    private fun parseRules(text: String): List<Rule> {
        val trimmed = text.trim()
        val arr = if (trimmed.startsWith("[")) JSONArray(trimmed)
        else JSONObject(trimmed).optJSONArray("rules") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { Rule.fromJson(arr.getJSONObject(it)) }
    }

    /** 同一包名的多条规则（老版本会产生）合并成一条。 */
    private fun normalize(rules: List<Rule>): List<Rule> =
        rules.groupBy { it.packageName }.map { (_, group) ->
            group.first().copy(matchers = Matcher.consolidate(group.flatMap { it.matchers }))
        }

    @Synchronized
    fun save(context: Context, rules: List<Rule>) {
        context.filesDir.resolve(FILE).writeText(rulesJson(rules))
    }

    private fun rulesJson(rules: List<Rule>): String {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        return JSONObject().put("version", VERSION).put("rules", arr).toString(2)
    }

    /** 该包已有规则则并入新条件组，否则建新规则。返回 (结果规则, 是否为合并)。 */
    @Synchronized
    fun addOrMerge(context: Context, pkg: String, appLabel: String, matchers: List<Matcher>): Pair<Rule, Boolean> {
        val rules = load(context)
        val existing = rules.firstOrNull { it.packageName == pkg }
        return if (existing == null) {
            val rule = Rule(
                id = UUID.randomUUID().toString(),
                name = "$appLabel 短视频",
                packageName = pkg,
                matchers = Matcher.consolidate(matchers),
                createdAt = System.currentTimeMillis(),
            )
            save(context, rules + rule)
            rule to false
        } else {
            val updated = existing.copy(matchers = Matcher.consolidate(existing.matchers + matchers))
            save(context, rules.map { if (it.id == existing.id) updated else it })
            updated to true
        }
    }

    fun remove(context: Context, ruleId: String) =
        save(context, load(context).filterNot { it.id == ruleId })

    @Synchronized
    fun exportJson(context: Context): String = rulesJson(load(context))

    /** 导入规则 JSON（裸数组或带版本号的对象）；同包名的并入现有规则。返回导入条数，解析失败返回 -1。 */
    @Synchronized
    fun importJson(context: Context, text: String): Int {
        val imported = try {
            parseRules(text)
        } catch (e: Exception) {
            return -1
        }
        if (imported.isEmpty()) return 0
        var rules = load(context)
        for (r in imported) {
            val existing = rules.firstOrNull { it.packageName == r.packageName }
            rules = if (existing == null) rules + r
            else rules.map {
                if (it.id == existing.id)
                    it.copy(matchers = Matcher.consolidate(it.matchers + r.matchers))
                else it
            }
        }
        save(context, rules)
        return imported.size
    }
}
