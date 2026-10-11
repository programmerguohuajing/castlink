package com.castlink.receiver.util

import android.content.Context
import android.content.res.Configuration
import com.castlink.receiver.R
import java.util.Locale

object LocaleHelper {

    const val LANG_SYSTEM = "system"
    const val LANG_ZH = "zh"
    const val LANG_EN = "en"

    private const val PREFS = "castlink_prefs"
    private const val KEY_LANGUAGE = "app_language"

    fun getLanguage(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
    }

    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, lang)
            .apply()
    }

    /**
     * 根据用户设置包装 Context。system 直接返回原 Context；
     * zh/en 通过 Configuration.setLocale + createConfigurationContext 返回新 Context。
     * 仅使用 API 26 可用的 API（setLocale 自 API 17 起可用）。
     */
    fun applyLocale(base: Context): Context {
        val lang = getLanguage(base)
        if (lang == LANG_SYSTEM) return base
        val locale = when (lang) {
            LANG_ZH -> Locale.SIMPLIFIED_CHINESE
            LANG_EN -> Locale.ENGLISH
            else -> return base
        }
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * 返回如 "跟随系统 · 中文" 的展示文案；已手动指定语言时直接返回语言名。
     */
    fun getDisplayName(context: Context): String {
        val res = context.resources
        return when (getLanguage(context)) {
            LANG_ZH -> res.getString(R.string.lang_name_zh)
            LANG_EN -> res.getString(R.string.lang_name_en)
            else -> {
                val locale = res.configuration.locales.get(0)
                val langName = if (locale != null && locale.language == "zh") {
                    res.getString(R.string.lang_name_zh)
                } else {
                    res.getString(R.string.lang_name_en)
                }
                res.getString(R.string.lang_follow_system) +
                    res.getString(R.string.lang_name_separator) + langName
            }
        }
    }
}
