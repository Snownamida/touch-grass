package com.snownamida.touchgrass

import android.content.Context
import com.snownamida.touchgrass.service.WatcherService

object AppState {
    enum class Mode { OFF, CAPTURE, WATCH }

    private const val PREFS = "touchgrass"
    private const val KEY_MODE = "mode"

    fun getMode(context: Context): Mode = try {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, Mode.OFF.name)!!
        Mode.valueOf(name)
    } catch (e: Exception) {
        Mode.OFF
    }

    fun setMode(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
        WatcherService.instance?.applyMode(mode)
    }

    private const val KEY_NOTIF = "notifTimer"

    fun isNotifTimer(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NOTIF, true)

    fun setNotifTimer(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NOTIF, enabled).apply()
    }

    private const val KEY_DEBUG = "debugOverlay"

    fun isDebug(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DEBUG, false)

    fun setDebug(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEBUG, enabled).apply()
        WatcherService.instance?.applyDebug(enabled)
    }
}
