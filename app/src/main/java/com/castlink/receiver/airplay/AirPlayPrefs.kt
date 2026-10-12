package com.castlink.receiver.airplay

import android.content.Context

/**
 * AirPlayPrefs — AirPlay 配对相关设置（v0.1.1，issue #1）。
 *
 * - skipPairVerify：可信局域网环境下跳过 pair-verify 的 PIN 前置校验。
 *   当配对缓存疑似损坏导致握手后密钥协商失败时，打开此开关可绕过配对校验。
 * - clearPairings()：清除所有已配对设备的长期密钥（LTPK），下次连接重新走 PIN 配对。
 */
class AirPlayPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 可信局域网：跳过 pair-verify 的 PIN 配对前置要求。默认关闭。 */
    var skipPairVerify: Boolean
        get() = prefs.getBoolean(KEY_SKIP_PAIR_VERIFY, false)
        set(v) = prefs.edit().putBoolean(KEY_SKIP_PAIR_VERIFY, v).apply()

    companion object {
        private const val PREFS = "castlink_airplay"
        private const val KEY_SKIP_PAIR_VERIFY = "skip_pair_verify"
    }
}
