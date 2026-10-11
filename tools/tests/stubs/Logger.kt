package com.castlink.receiver.airplay.util

/** JVM 单测桩：替代 android.util.Log 后端的 Logger（仅供 tools/tests 纯 JVM 编译）。 */
object Logger {
    fun d(message: String) = Unit
    fun i(message: String) = Unit
    fun w(message: String, throwable: Throwable? = null) = Unit
    fun e(message: String, throwable: Throwable? = null) = Unit
}
