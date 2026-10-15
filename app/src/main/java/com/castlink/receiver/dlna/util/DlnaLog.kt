package com.castlink.receiver.dlna.util

import android.util.Log
import com.castlink.receiver.airplay.util.Logger

/**
 * DLNA 日志桥：同时写 logcat（保留各模块原 TAG）和应用内日志查看器的内存缓冲。
 *
 * 背景：LogViewerActivity 只显示 AirPlay [Logger] 的内存缓冲（最近 500 条），
 * DLNA 原来直接调 android.util.Log，导致 TV 端"设置 → 查看日志"里看不到任何
 * DLNA 行（v0.1.43 前）。以后 DLNA 新增日志统一走这里。
 */
object DlnaLog {
    fun v(tag: String, msg: String) {
        Log.v(tag, msg)
        Logger.v("$tag $msg")
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        Logger.d("$tag $msg")
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        Logger.i("$tag $msg")
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        Logger.w("$tag $msg")
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        Logger.e("$tag $msg", t)
    }
}
