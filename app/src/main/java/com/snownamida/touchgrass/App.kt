package com.snownamida.touchgrass

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android 12+ 跟随系统壁纸动态取色，低版本回落到 Material3 默认配色
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
