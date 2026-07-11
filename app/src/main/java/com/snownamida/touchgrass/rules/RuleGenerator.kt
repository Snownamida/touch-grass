package com.snownamida.touchgrass.rules

import com.snownamida.touchgrass.model.Snapshot

/**
 * 由正负样本自动生成一组匹配条件：
 * - 正负样本的 Activity 无交集 → 只按 Activity 区分（最稳，B 站竖屏就是这种）
 * - Activity 相同（混排 feed）→ 求控件 id 差集：
 *   requiredIds = 所有正样本都有、任何负样本都没有的 id
 *   forbiddenIds = 所有负样本都有、任何正样本都没有的 id
 */
object RuleGenerator {

    sealed class Result {
        data class Ok(val matchers: List<Matcher>, val note: String?) : Result()
        data class Fail(val reason: String) : Result()
    }

    fun generate(pos: List<Snapshot>, neg: List<Snapshot>): Result {
        if (pos.isEmpty()) return Result.Fail("没有正样本，先在短视频页面点 ✓")

        val posActs = pos.mapNotNull { it.activity }.toSet()
        val negActs = neg.mapNotNull { it.activity }.toSet()

        // 控件 id 差集：所有正样本都有、任何负样本都没有（framework 通用 id 除外）
        val commonPosIds = pos.map { it.ids }.reduce { a, b -> a.intersect(b) }
        val negUnionIds = neg.flatMap { it.ids }.toSet()
        val diffIds = (commonPosIds - negUnionIds)
            .filterNot { it.startsWith("android:") }
            .sorted().take(3)

        if (posActs.isNotEmpty() && posActs.intersect(negActs).isEmpty()) {
            val matchers = mutableListOf(Matcher(posActs, emptyList(), emptyList()))
            val note: String
            if (neg.isNotEmpty() && diffIds.isNotEmpty()) {
                // 兜底组：Activity 信息缺失（比如开监测时人已在页面里）也能靠控件指纹命中
                matchers.add(Matcher(emptySet(), diffIds, emptyList()))
                note = "按 Activity 区分 + 控件指纹兜底"
            } else if (neg.isEmpty()) {
                note = "未采负样本，仅按页面（Activity）匹配；建议补采负样本，可解锁控件指纹兜底"
            } else {
                note = "正负样本页面不同，按 Activity 区分"
            }
            return Result.Ok(matchers, note)
        }

        val commonNegIds =
            if (neg.isEmpty()) emptySet() else neg.map { it.ids }.reduce { a, b -> a.intersect(b) }
        val posUnionIds = pos.flatMap { it.ids }.toSet()
        val forbidden = (commonNegIds - posUnionIds)
            .filterNot { it.startsWith("android:") }
            .sorted().take(2)

        if (diffIds.isEmpty() && forbidden.isEmpty()) {
            return Result.Fail(
                "正负样本的控件树没有可区分的差异（该应用可能是自绘界面，控件信息稀疏），" +
                    "只能删掉负样本后按整页/整应用计时"
            )
        }

        return Result.Ok(
            listOf(Matcher(posActs, diffIds, forbidden)),
            "按控件特征区分：需含 ${diffIds.size} 个 id、排除 ${forbidden.size} 个 id"
        )
    }
}
