package com.castlink.receiver.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/**
 * ThemeHelper — 浅色 / 深色 / 跟随系统。
 *
 * 做法与 [LocaleHelper] 一致：在 Activity 的 attachBaseContext 里覆盖 uiMode 的“夜间”位，
 * 资源（values / values-night）随即按所选主题解析，无需 AppCompat。
 * 默认值：电视 / 机顶盒上默认深色（客厅暗光下浅色屏幕刺眼，而且这类设备一般不会上报“夜间”）；
 * 手机默认跟随系统。用户在设置里选过之后以所选为准。
 */
object ThemeHelper {

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    private const val PREFS = "castlink_prefs"
    private const val KEY_THEME = "app_theme"

    private fun isTelevision(context: Context): Boolean {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** 已存的选择；从没选过则电视默认深色、其它默认跟随系统。 */
    fun getTheme(context: Context): String {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, null)
        if (stored != null) return stored
        return if (isTelevision(context)) THEME_DARK else THEME_SYSTEM
    }

    fun setTheme(context: Context, theme: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, theme).apply()
    }

    /** 按所选主题包装 Context；跟随系统时原样返回。 */
    fun applyTheme(base: Context): Context {
        val night = when (getTheme(base)) {
            THEME_LIGHT -> Configuration.UI_MODE_NIGHT_NO
            THEME_DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> return base
        }
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(config)
    }
}
