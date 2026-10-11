package com.castlink.receiver.update

import android.content.Context

/**
 * v0.3.12：更新源配置（SharedPreferences 持久化）。
 *
 * owner/repo 为 GitHub 仓库（放 version.json + Release APK）；
 * mirrorIndex 选用下载镜像（见 [UpdateManager.MIRRORS]）。
 */
class UpdatePrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var owner: String
        get() = prefs.getString(KEY_OWNER, "").orEmpty().ifBlank { UpdateManager.DEFAULT_OWNER }
        set(v) = prefs.edit().putString(KEY_OWNER, v.trim()).apply()

    var repo: String
        get() = prefs.getString(KEY_REPO, "").orEmpty().ifBlank { UpdateManager.DEFAULT_REPO }
        set(v) = prefs.edit().putString(KEY_REPO, v.trim()).apply()

    var mirrorIndex: Int
        get() = prefs.getInt(KEY_MIRROR, 0)
        set(v) = prefs.edit().putInt(KEY_MIRROR, v).apply()

    var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO, v).apply()

    var lastCheckTime: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_CHECK, v).apply()

    companion object {
        private const val PREFS = "castlink_update"
        private const val KEY_OWNER = "owner"
        private const val KEY_REPO = "repo"
        private const val KEY_MIRROR = "mirror"
        private const val KEY_AUTO = "auto_check"
        private const val KEY_LAST_CHECK = "last_check"
    }
}
