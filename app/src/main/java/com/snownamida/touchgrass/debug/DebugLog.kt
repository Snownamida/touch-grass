package com.snownamida.touchgrass.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 判定日志环形缓冲：留最近 300 条，供主界面查看/复制。 */
object DebugLog {
    private const val MAX = 300
    private val buf = ArrayDeque<String>()

    @Synchronized
    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        buf.addLast("$t $msg")
        while (buf.size > MAX) buf.removeFirst()
    }

    @Synchronized
    fun dump(): String = buf.joinToString("\n")

    @Synchronized
    fun clear() = buf.clear()
}
