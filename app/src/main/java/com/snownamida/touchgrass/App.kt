package com.snownamida.touchgrass

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.snownamida.touchgrass.rules.RuleStore
import com.snownamida.touchgrass.track.StatsStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android 12+ 跟随系统壁纸动态取色，低版本回落到 Material3 默认配色
        DynamicColors.applyToActivitiesIfAvailable(this)

        // 一次性迁移：统计从按 ruleId 记账改为按包名记账
        val prefs = getSharedPreferences("touchgrass", MODE_PRIVATE)
        if (!prefs.getBoolean("statsKeyedByPackage", false)) {
            prefs.edit().putBoolean("statsKeyedByPackage", true).apply()
            StatsStore.migrateKeys(this, RuleStore.load(this).associate { it.id to it.packageName })
        }
    }
}
