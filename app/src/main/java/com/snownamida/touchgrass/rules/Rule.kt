package com.snownamida.touchgrass.rules

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一组匹配条件：
 * - [activities] 非空时，当前 Activity 必须在其中（空 = 不限页面，整个应用都算）
 * - [requiredIds] 里的 resource-id 必须全部出现在当前窗口
 * - [forbiddenIds] 里的 resource-id 必须全部不出现
 */
data class Matcher(
    val activities: Set<String>,
    val requiredIds: List<String>,
    val forbiddenIds: List<String>,
) {
    val needsNodeCheck: Boolean
        get() = requiredIds.isNotEmpty() || forbiddenIds.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("activities", JSONArray(activities.toList()))
        put("requiredIds", JSONArray(requiredIds))
        put("forbiddenIds", JSONArray(forbiddenIds))
    }

    companion object {
        fun fromJson(o: JSONObject): Matcher = Matcher(
            activities = o.optJSONArray("activities").toStringList().toSet(),
            requiredIds = o.optJSONArray("requiredIds").toStringList(),
            forbiddenIds = o.optJSONArray("forbiddenIds").toStringList(),
        )

        /**
         * 整理条件组：去重；不查控件树的组之间合并 Activity 集合
         * （多组纯 Activity 条件在匹配上就是 OR，合成一组更清爽）。
         * 若存在「整个应用」的组（activities 为空且无控件条件），它吞掉其余纯 Activity 组。
         */
        fun consolidate(matchers: List<Matcher>): List<Matcher> {
            val nodeFree = matchers.filter { !it.needsNodeCheck }
            val withNodes = matchers.filter { it.needsNodeCheck }.distinct()
            val mergedFree = when {
                nodeFree.isEmpty() -> emptyList()
                nodeFree.any { it.activities.isEmpty() } ->
                    listOf(Matcher(emptySet(), emptyList(), emptyList()))
                else ->
                    listOf(Matcher(nodeFree.flatMap { it.activities }.toSet(), emptyList(), emptyList()))
            }
            return mergedFree + withNodes
        }

        internal fun JSONArray?.toStringList(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }
    }
}

/**
 * 一条规则对应一个应用，持有若干组条件（任一组命中即算命中）。
 * 同一应用再次生成规则时新条件并入本条，不产生重复规则。
 */
data class Rule(
    val id: String,
    val name: String,
    val packageName: String,
    val matchers: List<Matcher>,
    val createdAt: Long,
) {
    val needsNodeCheck: Boolean
        get() = matchers.any { it.needsNodeCheck }

    fun describe(): String {
        val acts = matchers.flatMap { it.activities }.toSet().size
        val parts = mutableListOf(packageName)
        parts.add(if (acts > 0) "页面 $acts 个" else "整个应用")
        val nodeGroups = matchers.count { it.needsNodeCheck }
        if (nodeGroups > 0) parts.add("控件条件 $nodeGroups 组")
        return parts.joinToString(" · ")
    }

    /** 高级用户看的完整详情。 */
    fun detailText(): String {
        val sb = StringBuilder("包名：$packageName")
        matchers.forEachIndexed { i, m ->
            sb.append("\n\n—— 条件组 ${i + 1}（组内全满足，组间任一命中）——\n")
            if (m.activities.isEmpty()) {
                sb.append("Activity：不限（整个应用）")
            } else {
                sb.append("Activity（任一）：\n")
                sb.append(m.activities.joinToString("\n") { "· $it" })
            }
            if (m.requiredIds.isNotEmpty()) {
                sb.append("\n必须出现的控件 id：\n")
                sb.append(m.requiredIds.joinToString("\n") { "· $it" })
            }
            if (m.forbiddenIds.isNotEmpty()) {
                sb.append("\n必须不出现的控件 id：\n")
                sb.append(m.forbiddenIds.joinToString("\n") { "· $it" })
            }
        }
        return sb.toString()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("packageName", packageName)
        put("matchers", JSONArray().also { arr -> matchers.forEach { arr.put(it.toJson()) } })
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Rule? = try {
            val matchers = if (o.has("matchers")) {
                val arr = o.getJSONArray("matchers")
                (0 until arr.length()).map { Matcher.fromJson(arr.getJSONObject(it)) }
            } else {
                // 旧格式（v0.1，条件平铺在规则里）：包装成单组
                listOf(Matcher.fromJson(o))
            }
            Rule(
                id = o.getString("id"),
                name = o.getString("name"),
                packageName = o.getString("packageName"),
                matchers = matchers,
                createdAt = o.optLong("createdAt"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
