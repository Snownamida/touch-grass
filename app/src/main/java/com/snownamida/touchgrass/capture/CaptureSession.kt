package com.snownamida.touchgrass.capture

import com.snownamida.touchgrass.model.Snapshot

/** 捕捉模式下的样本暂存（仅内存，生成规则后即清空）。 */
object CaptureSession {
    private val samples = mutableListOf<Snapshot>()

    var lastSampledPackage: String? = null
        private set

    @Synchronized
    fun add(sample: Snapshot) {
        samples.add(sample)
        lastSampledPackage = sample.packageName
    }

    /** 返回 (正样本, 负样本)。 */
    @Synchronized
    fun samplesFor(pkg: String): Pair<List<Snapshot>, List<Snapshot>> {
        val forPkg = samples.filter { it.packageName == pkg }
        return forPkg.filter { it.positive } to forPkg.filter { !it.positive }
    }

    @Synchronized
    fun countsFor(pkg: String): Pair<Int, Int> {
        val (pos, neg) = samplesFor(pkg)
        return pos.size to neg.size
    }

    @Synchronized
    fun clearFor(pkg: String) {
        samples.removeAll { it.packageName == pkg }
        if (lastSampledPackage == pkg) lastSampledPackage = samples.lastOrNull()?.packageName
    }
}
