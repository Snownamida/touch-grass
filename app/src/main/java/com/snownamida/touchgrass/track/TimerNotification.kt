package com.snownamida.touchgrass.track

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import com.snownamida.touchgrass.R
import org.json.JSONObject

/**
 * 会话进行中的常驻通知：
 * - 所有机型：通知栏/锁屏显示自走秒的计时（chronometer）
 * - HyperOS：附带焦点通知/超级岛参数（miui.focus.param）。官方协议公开但
 *   渲染上岛需要小米授予权限，没有权限时系统按普通通知显示——优雅降级。
 */
object TimerNotification {
    private const val CHANNEL_ID = "timer"
    private const val NOTIF_ID = 42
    private var sequence = 0L

    fun show(context: Context, ruleName: String, elapsedSec: Long) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "刷屏计时", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val minutes = elapsedSec / 60
        val content = if (minutes > 0) "这一波已刷 $minutes 分钟" else "开始计时…"

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_grass)
            .setContentTitle("咋还在刷 · $ruleName")
            .setContentText(content)
            .setWhen(System.currentTimeMillis() - elapsedSec * 1000)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        val pics = Bundle()
        pics.putParcelable(
            "miui.focus.pic_grass",
            Icon.createWithResource(context, R.drawable.ic_stat_grass)
        )
        val extras = Bundle()
        extras.putBundle("miui.focus.pics", pics)
        builder.addExtras(extras)

        val notification = builder.build()
        notification.extras.putString("miui.focus.param", islandParam(ruleName, content))
        // 用户没给通知权限时 notify 会被系统拒绝，静默即可（悬浮计时药丸不受影响）
        runCatching { nm.notify(NOTIF_ID, notification) }
    }

    fun hide(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }

    /** HyperOS 焦点通知/超级岛协议（param_v2），字段参考小米官方与阿里云 EMAS 文档。 */
    private fun islandParam(ruleName: String, content: String): String {
        sequence += 1
        val picInfo = JSONObject().put("type", 1).put("pic", "miui.focus.pic_grass")
        val textInfo = JSONObject().put("title", "咋还在刷").put("content", content)
        return JSONObject().put(
            "param_v2",
            JSONObject()
                .put("protocol", 1)
                .put("business", "timer")
                .put("updatable", true)
                .put("sequence", sequence)
                .put("baseInfo", JSONObject().put("type", 1).put("title", "咋还在刷 · $ruleName").put("content", content))
                .put(
                    "param_island",
                    JSONObject()
                        .put("islandProperty", 1)
                        .put("smallIslandArea", JSONObject().put("picInfo", picInfo))
                        .put(
                            "bigIslandArea",
                            JSONObject().put(
                                "imageTextInfoLeft",
                                JSONObject().put("type", 1).put("picInfo", picInfo).put("textInfo", textInfo)
                            )
                        )
                        .put("shareData", JSONObject().put("title", "咋还在刷计时"))
                )
        ).toString()
    }
}
