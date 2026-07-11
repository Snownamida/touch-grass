package com.snownamida.touchgrass.rules

import com.snownamida.touchgrass.model.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun snap(act: String?, ids: Set<String>, positive: Boolean = true) =
    Snapshot("pkg", act, ids, ids.size, positive)

class RuleGeneratorTest {

    @Test
    fun `activity 可区分且有负样本时附加控件指纹兜底组`() {
        val pos = listOf(
            snap("StoryActivity", setOf("p:id/like", "p:id/danmaku", "p:id/title1")),
            snap("StoryActivity", setOf("p:id/like", "p:id/danmaku", "p:id/title2")),
        )
        val neg = listOf(snap("DetailActivity", setOf("p:id/comment")))
        val r = RuleGenerator.generate(pos, neg) as RuleGenerator.Result.Ok
        assertEquals(2, r.matchers.size)
        assertEquals(setOf("StoryActivity"), r.matchers[0].activities)
        assertTrue(r.matchers[0].requiredIds.isEmpty())
        // 兜底组：不限 activity，指纹是所有正样本共有且负样本没有的 id
        assertTrue(r.matchers[1].activities.isEmpty())
        assertEquals(listOf("p:id/danmaku", "p:id/like"), r.matchers[1].requiredIds)
    }

    @Test
    fun `没有负样本时只按 activity 匹配`() {
        val pos = listOf(snap("StoryActivity", setOf("p:id/like")))
        val r = RuleGenerator.generate(pos, emptyList()) as RuleGenerator.Result.Ok
        assertEquals(1, r.matchers.size)
        assertEquals(setOf("StoryActivity"), r.matchers[0].activities)
    }

    @Test
    fun `同 activity 时用控件差集区分`() {
        val pos = listOf(
            snap("Feed", setOf("p:id/video", "p:id/common", "p:id/dyn1")),
            snap("Feed", setOf("p:id/video", "p:id/common", "p:id/dyn2")),
        )
        val neg = listOf(snap("Feed", setOf("p:id/image", "p:id/common")))
        val r = RuleGenerator.generate(pos, neg) as RuleGenerator.Result.Ok
        assertEquals(1, r.matchers.size)
        assertEquals(setOf("Feed"), r.matchers[0].activities)
        assertEquals(listOf("p:id/video"), r.matchers[0].requiredIds)
        assertEquals(listOf("p:id/image"), r.matchers[0].forbiddenIds)
    }

    @Test
    fun `framework 通用 id 不参与指纹`() {
        val pos = listOf(snap("Feed", setOf("android:id/content", "p:id/video")))
        val neg = listOf(snap("Feed", setOf("p:id/image")))
        val r = RuleGenerator.generate(pos, neg) as RuleGenerator.Result.Ok
        assertEquals(listOf("p:id/video"), r.matchers[0].requiredIds)
    }

    @Test
    fun `无可区分差异时失败`() {
        val pos = listOf(snap("Feed", setOf("p:id/same")))
        val neg = listOf(snap("Feed", setOf("p:id/same")))
        assertTrue(RuleGenerator.generate(pos, neg) is RuleGenerator.Result.Fail)
    }

    @Test
    fun `没有正样本时失败`() {
        assertTrue(RuleGenerator.generate(emptyList(), emptyList()) is RuleGenerator.Result.Fail)
    }
}

class MatcherConsolidateTest {

    @Test
    fun `纯 activity 组合并 activity 集合`() {
        val merged = Matcher.consolidate(
            listOf(
                Matcher(setOf("A"), emptyList(), emptyList()),
                Matcher(setOf("B"), emptyList(), emptyList()),
            )
        )
        assertEquals(1, merged.size)
        assertEquals(setOf("A", "B"), merged[0].activities)
    }

    @Test
    fun `整应用组吞并其它纯 activity 组`() {
        val merged = Matcher.consolidate(
            listOf(
                Matcher(setOf("A"), emptyList(), emptyList()),
                Matcher(emptySet(), emptyList(), emptyList()),
            )
        )
        assertEquals(1, merged.size)
        assertTrue(merged[0].activities.isEmpty())
    }

    @Test
    fun `控件条件组去重且不与 activity 组合并`() {
        val node = Matcher(emptySet(), listOf("p:id/x"), emptyList())
        val merged = Matcher.consolidate(
            listOf(Matcher(setOf("A"), emptyList(), emptyList()), node, node)
        )
        assertEquals(2, merged.size)
        assertEquals(setOf("A"), merged[0].activities)
        assertEquals(node, merged[1])
    }
}

class RuleEngineTest {
    private val rule = Rule(
        id = "r1", name = "测试", packageName = "pkg",
        matchers = listOf(
            Matcher(setOf("Story"), emptyList(), emptyList()),
            Matcher(emptySet(), listOf("pkg:id/video"), listOf("pkg:id/image")),
        ),
        createdAt = 0,
    )

    @Test
    fun `activity 命中`() {
        assertTrue(RuleEngine.matches(rule, "Story") { _, _ -> null })
    }

    @Test
    fun `activity 未知时控件指纹兜底`() {
        assertTrue(RuleEngine.matches(rule, null) { _, id -> id == "pkg:id/video" })
    }

    @Test
    fun `排除 id 出现时不命中`() {
        assertFalse(RuleEngine.matches(rule, null) { _, _ -> true })
    }

    @Test
    fun `拿不到窗口且 activity 不符时不命中`() {
        assertFalse(RuleEngine.matches(rule, "Other") { _, _ -> null })
    }

    @Test
    fun `explainMiss 说明失败环节`() {
        val explain = RuleEngine.explainMiss(rule, "Other") { _, _ -> false }
        assertTrue(explain.contains("activity不符"))
        assertTrue(explain.contains("缺video"))
    }
}

class RuleJsonTest {

    @Test
    fun `新格式 roundtrip`() {
        val rule = Rule(
            id = "id1", name = "名字", packageName = "pkg",
            matchers = listOf(Matcher(setOf("A"), listOf("pkg:id/x"), listOf("pkg:id/y"))),
            createdAt = 123L,
        )
        assertEquals(rule, Rule.fromJson(rule.toJson()))
    }

    @Test
    fun `v1 平铺格式迁移为单条件组`() {
        val legacy = org.json.JSONObject(
            """{"id":"a","name":"n","packageName":"pkg",
                "activities":["A"],"requiredIds":["pkg:id/x"],"forbiddenIds":[],"createdAt":1}"""
        )
        val rule = Rule.fromJson(legacy)!!
        assertEquals(1, rule.matchers.size)
        assertEquals(setOf("A"), rule.matchers[0].activities)
        assertEquals(listOf("pkg:id/x"), rule.matchers[0].requiredIds)
    }
}
