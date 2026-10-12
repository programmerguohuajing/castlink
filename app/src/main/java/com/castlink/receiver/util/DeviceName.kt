package com.castlink.receiver.util

import android.content.Context
import com.castlink.receiver.R

/**
 * 统一的设备名解析：待机页、原生协议 mDNS、AirPlay mDNS 广播、
 * AirPlay `/info` 响应都走这里，保证各处看到的是同一个名字。
 *
 * 优先级：用户在设置里保存的名字 > 默认名（CastLink-TV）
 * > 系统机型名（最后兜底，几乎不会走到）。
 *
 * 背景：新装机上 prefs 里没有 device_name，StandbyActivity 只读不存，
 * AirPlayService 拿到空串后退到系统机型名（如 E900V22C），导致 iPhone
 * 在隔空播放列表里看到的是机型名而不是 CastLink-TV，容易选错设备。
 */
object DeviceName {
    private const val PREFS = "castlink_prefs"
    private const val KEY_DEVICE_NAME = "device_name"

    /** 解析当前应广播的设备名，永不返回空串。 */
    fun resolve(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_DEVICE_NAME, null)?.trim()
        if (!saved.isNullOrEmpty()) {
            // 迁移：之前所有设备都存了统一默认名 "CastLink-TV"，导致 iPhone
            // AirPlay 列表里出现两个同名设备无法区分。非 TV 设备改成带机型的新默认名。
            val oldDefault = context.getString(R.string.default_device_name).trim()
            if (saved == oldDefault && !isTelevision(context)) {
                val newDefault = defaultNameFor(context)
                prefs.edit().putString(KEY_DEVICE_NAME, newDefault).apply()
                return newDefault
            }
            return saved
        }
        val def = defaultNameFor(context)
        if (def.isNotEmpty()) return def
        return android.os.Build.MODEL
    }

    /** TV 用 CastLink-TV；手机/平板用 CastLink-机型，避免 AirPlay 列表重名。 */
    private fun defaultNameFor(context: Context): String {
        if (isTelevision(context)) {
            val def = context.getString(R.string.default_device_name).trim()
            if (def.isNotEmpty()) return def
            return "CastLink-TV"
        }
        val model = android.os.Build.MODEL?.trim().orEmpty().take(24)
        return if (model.isNotEmpty()) "CastLink-$model" else "CastLink-Phone"
    }

    private fun isTelevision(context: Context): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE)
            as? android.app.UiModeManager
        if (uiMode?.currentModeType ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = context.packageManager
        // TV 盒子常不上报 TV 模式；有 leanback 即为 TV
        if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) return true
        // 无电话功能→非手机，按 TV/盒子处理（Skyworth E900V22C 等盒子走这里）
        return !pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)
    }

    /**
     * 首次启动时把解析出的名字写进 prefs，保证各组件（包括先于
     * StandbyActivity 启动的 AirPlayService）读到一致的值。
     * 已有用户自定义名字时不覆盖。
     */
    fun ensurePersisted(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_DEVICE_NAME)) {
            prefs.edit().putString(KEY_DEVICE_NAME, resolve(context)).apply()
        }
    }
}
