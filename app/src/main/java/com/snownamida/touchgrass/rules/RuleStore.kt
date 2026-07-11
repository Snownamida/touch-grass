package com.snownamida.touchgrass.rules

import android.content.Context
import org.json.JSONArray
import java.util.UUID

/** 规则持久化：filesDir/rules.json，纯本地。 */
object RuleStore {
    private const val FILE = "rules.json"

    @Synchronized
    fun load(context: Context): List<Rule> = try {
        val f = context.filesDir.resolve(FILE)
        if (!f.exists()) emptyList()
        else {
            val arr = JSONArray(f.readText())
            normalize((0 until arr.length()).mapNotNull { Rule.fromJson(arr.getJSONObject(it)) })
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** 同一包名的多条规则（老版本会产生）合并成一条。 */
    private fun normalize(rules: List<Rule>): List<Rule> =
        rules.groupBy { it.packageName }.map { (_, group) ->
            group.first().copy(matchers = Matcher.consolidate(group.flatMap { it.matchers }))
        }

    @Synchronized
    fun save(context: Context, rules: List<Rule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        context.filesDir.resolve(FILE).writeText(arr.toString(2))
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
    fun exportJson(context: Context): String {
        val arr = JSONArray()
        load(context).forEach { arr.put(it.toJson()) }
        return arr.toString(2)
    }

    /** 导入规则 JSON 数组；同包名的并入现有规则。返回导入条数，解析失败返回 -1。 */
    @Synchronized
    fun importJson(context: Context, text: String): Int {
        val imported = try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { Rule.fromJson(arr.getJSONObject(it)) }
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
