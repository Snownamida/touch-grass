package com.snownamida.touchgrass.model

/**
 * 一次界面采样：某个包在某个 Activity 下，当前窗口控件树里出现过的全部 resource-id。
 * 指纹生成只依赖 id 集合与 Activity 名；文本、坐标等随内容变化的信息刻意不采。
 */
data class Snapshot(
    val packageName: String,
    val activity: String?,
    val ids: Set<String>,
    val nodeCount: Int,
    val positive: Boolean = true,
)
