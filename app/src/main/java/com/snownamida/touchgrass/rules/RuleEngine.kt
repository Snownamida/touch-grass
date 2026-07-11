package com.snownamida.touchgrass.rules

/**
 * 规则匹配引擎。纯逻辑、可单测：对无障碍的依赖收敛为一个
 * [hasNode] 查询函数——某 resource-id 是否存在于目标包的当前窗口，
 * 返回 null 表示拿不到目标窗口。
 */
object RuleEngine {

    fun matches(rule: Rule, activity: String?, hasNode: (pkg: String, viewId: String) -> Boolean?): Boolean =
        rule.matchers.any { matcherMatches(rule.packageName, it, activity, hasNode) }

    private fun matcherMatches(
        pkg: String,
        m: Matcher,
        activity: String?,
        hasNode: (String, String) -> Boolean?,
    ): Boolean {
        if (m.activities.isNotEmpty()) {
            if (activity == null || activity !in m.activities) return false
        }
        if (!m.needsNodeCheck) return true
        for (id in m.requiredIds) if (hasNode(pkg, id) != true) return false
        for (id in m.forbiddenIds) if (hasNode(pkg, id) != false) return false
        return true
    }

    /** 未命中时给出每个条件组失败在哪一步，调试用。 */
    fun explainMiss(rule: Rule, activity: String?, hasNode: (String, String) -> Boolean?): String {
        val reasons = rule.matchers.mapIndexed { i, m ->
            val tag = "组${i + 1}"
            when {
                m.activities.isNotEmpty() && activity == null -> "$tag:未知activity"
                m.activities.isNotEmpty() && activity !in m.activities -> "$tag:activity不符"
                m.needsNodeCheck -> explainNodeMiss(tag, rule.packageName, m, hasNode)
                else -> "$tag:?"
            }
        }
        return "未命中(${reasons.joinToString(";")})"
    }

    private fun explainNodeMiss(
        tag: String,
        pkg: String,
        m: Matcher,
        hasNode: (String, String) -> Boolean?,
    ): String {
        for (id in m.requiredIds) {
            when (hasNode(pkg, id)) {
                null -> return "$tag:拿不到窗口"
                false -> return "$tag:缺${id.substringAfterLast('/')}"
                true -> {}
            }
        }
        for (id in m.forbiddenIds) {
            when (hasNode(pkg, id)) {
                null -> return "$tag:拿不到窗口"
                true -> return "$tag:命中排除${id.substringAfterLast('/')}"
                false -> {}
            }
        }
        return "$tag:?"
    }
}
