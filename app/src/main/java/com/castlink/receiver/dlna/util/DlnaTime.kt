package com.castlink.receiver.dlna.util

/**
 * DLNA 时间格式工具（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 文档 §3.3.2：GetPositionInfo 的 TrackDuration / RelTime / AbsTime 必须严格
 * 遵循 HH:mm:ss 三段格式（如 00:45:12），不得输出毫秒小数或两位表示（如 45:12），
 * 否则腾讯视频等客户端进度条会卡死。
 */
object DlnaTime {

    /** 毫秒 → "HH:mm:ss"（至少两位，小时可超 99）。负数按 0 处理。 */
    fun format(ms: Long): String {
        val totalSec = (if (ms < 0) 0 else ms) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    /**
     * "HH:mm:ss"（兼容 H:MM:SS、MM:SS、纯秒数、小数秒）→ 毫秒。
     * 非法输入返回 -1。
     */
    fun parse(t: String?): Long {
        if (t.isNullOrBlank()) return -1
        return try {
            val clean = t.trim().split(".")[0] // 去掉小数秒
            val parts = clean.split(":").map { it.trim() }
            val secs: Long = when (parts.size) {
                3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
                2 -> parts[0].toLong() * 60 + parts[1].toLong()
                1 -> parts[0].toLong()
                else -> return -1
            }
            if (secs < 0) -1 else secs * 1000
        } catch (e: NumberFormatException) {
            -1
        }
    }

    /**
     * DIDL-Lite `<res duration="...">` → 毫秒。
     * 常见格式：秒数（"245.000"）或 "HH:mm:ss[.xxx]"。非法返回 0。
     */
    fun parseDidlDuration(duration: String?): Long {
        if (duration.isNullOrBlank()) return 0
        val s = duration.trim()
        // 纯数字（含小数）→ 秒
        if (s.matches(Regex("[0-9]+(\\.[0-9]+)?"))) {
            return (s.toDouble() * 1000).toLong().coerceAtLeast(0)
        }
        val ms = parse(s)
        return if (ms < 0) 0 else ms
    }
}
