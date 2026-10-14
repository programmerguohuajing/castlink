/*
 * Ported from PhairPlay (https://github.com/mazer666/PhairPlay, Apache-2.0);
 * package renamed to com.castlink.receiver.airplay.*. See THIRD-PARTY-NOTICES.
 */
package com.castlink.receiver.airplay.util

import android.util.Log

/**
 * Logger — thin wrapper around android.util.Log (ported from PhairPlay; Timber backend removed
 * because CastLink has no Timber dependency).
 *
 * Same call surface as the original: v/d/i/w/e.
 */
object Logger {
    private const val TAG = "CastLink-AirPlay"

    // v0.1.34: 内存日志缓冲（应用内查看用，不导出文件），最多 500 条
    private val buffer = ArrayDeque<String>(500)
    private val lock = Any()
    fun getLogs(): List<String> = synchronized(lock) { buffer.toList() }
    fun clearLogs() = synchronized(lock) { buffer.clear() }
    private fun append(level: String, msg: String) {
        val line = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} $level $msg"
        synchronized(lock) {
            if (buffer.size >= 500) buffer.removeFirst()
            buffer.addLast(line)
        }
    }

    fun v(message: String) { Log.v(TAG, message); append("V", message) }
    fun d(message: String) { Log.d(TAG, message); append("D", message) }
    fun i(message: String) { Log.i(TAG, message); append("I", message) }
    fun w(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
        append("W", message + (throwable?.let { " | ${it.message}" } ?: ""))
    }
    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
        append("E", message + (throwable?.let { " | ${it.message}" } ?: ""))
    }
}
