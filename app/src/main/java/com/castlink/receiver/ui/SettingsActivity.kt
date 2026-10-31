package com.castlink.receiver.ui

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.castlink.receiver.R
import com.castlink.receiver.update.UpdateManager
import com.castlink.receiver.update.UpdatePrefs
import com.castlink.receiver.util.LocaleHelper
import com.castlink.receiver.util.ThemeHelper

/**
 * 设置页（UI-04）：语言切换、本机能力卡、画质单选、开关项、设备名与系统维护。
 *
 * 采用 NEBULA 深空玻璃态 (Glassmorphic) 设计系统，全量定制卡片、胶囊分段器与开关项，
 * 并支持触屏与 TV 电视遥控器 D-pad 双模交互。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvLangSummary: TextView
    private lateinit var tvThemeSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // 返回按钮
        findViewById<ImageButton>(R.id.btn_settings_back)?.setOnClickListener {
            finish()
        }

        // 语言：副标题显示当前生效语言，点击弹单选对话框
        tvLangSummary = findViewById(R.id.tv_lang_summary)
        tvLangSummary.text = LocaleHelper.getDisplayName(this)
        findViewById<LinearLayout>(R.id.row_language).setOnClickListener {
            showLanguageDialog()
        }

        // 外观：浅色 / 深色 / 跟随系统
        tvThemeSummary = findViewById(R.id.tv_theme_summary)
        tvThemeSummary.text = themeLabel(ThemeHelper.getTheme(this))
        findViewById<LinearLayout>(R.id.row_theme).setOnClickListener { showThemeDialog() }

        // 画质分段胶囊单选（持久化）
        val rgQuality = findViewById<RadioGroup>(R.id.rg_quality)
        val quality = prefs.getString(KEY_QUALITY, QUALITY_AUTO) ?: QUALITY_AUTO
        rgQuality.check(
            when (quality) {
                QUALITY_FLAGSHIP -> R.id.rb_quality_flagship
                QUALITY_1080P -> R.id.rb_quality_1080p
                QUALITY_720P -> R.id.rb_quality_720p
                else -> R.id.rb_quality_auto
            }
        )
        rgQuality.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.rb_quality_flagship -> QUALITY_FLAGSHIP
                R.id.rb_quality_1080p -> QUALITY_1080P
                R.id.rb_quality_720p -> QUALITY_720P
                else -> QUALITY_AUTO
            }
            prefs.edit().putString(KEY_QUALITY, value).apply()
        }

        // 优先 5GHz（持久化）——支持整行点击与胶囊按钮点击
        val btnPrefer5g = findViewById<Button>(R.id.btn_prefer_5g)
        fun refreshPrefer5g() {
            val on = prefs.getBoolean(KEY_PREFER_5G, false)
            updatePillState(btnPrefer5g, on)
        }
        refreshPrefer5g()
        val togglePrefer5g = {
            val current = prefs.getBoolean(KEY_PREFER_5G, false)
            prefs.edit().putBoolean(KEY_PREFER_5G, !current).apply()
            refreshPrefer5g()
        }
        btnPrefer5g.setOnClickListener { togglePrefer5g() }
        findViewById<View>(R.id.row_prefer_5g)?.setOnClickListener { togglePrefer5g() }

        // Legacy Android 4 接收模式（限 720p）——支持整行点击与胶囊按钮点击
        val btnLegacy = findViewById<Button>(R.id.btn_legacy_mode)
        fun refreshLegacy() {
            val on = prefs.getBoolean(KEY_LEGACY_MODE, false)
            updatePillState(btnLegacy, on)
        }
        refreshLegacy()
        val toggleLegacy = {
            val current = prefs.getBoolean(KEY_LEGACY_MODE, false)
            prefs.edit().putBoolean(KEY_LEGACY_MODE, !current).apply()
            refreshLegacy()
        }
        btnLegacy.setOnClickListener { toggleLegacy() }
        findViewById<View>(R.id.row_legacy_mode)?.setOnClickListener { toggleLegacy() }

        // 设备名（持久化，供 mDNS / AirPlay / DLNA 广播用）
        val etDeviceName = findViewById<EditText>(R.id.et_device_name)
        etDeviceName.setText(
            prefs.getString(KEY_DEVICE_NAME, getString(R.string.default_device_name))
        )
        findViewById<Button>(R.id.btn_save_device_name).setOnClickListener {
            val name = etDeviceName.text.toString().trim()
            if (name.isNotEmpty()) {
                prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
                Toast.makeText(this, R.string.device_name_saved, Toast.LENGTH_SHORT).show()
            }
        }

        // 在线更新
        setupUpdateSection()

        // AirPlay 配对
        setupAirPlaySection()

        // 手动 D-pad 焦点控制（兼容 TV 遥控器）
        setupManualDpad()
    }

    /** 更新开/关状态胶囊样式 */
    private fun updatePillState(button: Button, on: Boolean) {
        button.text = getString(if (on) R.string.on else R.string.off)
        if (on) {
            button.setBackgroundResource(R.drawable.bg_toggle_pill_on)
            button.setTextColor(getColor(R.color.nebula_cyan))
        } else {
            button.setBackgroundResource(R.drawable.bg_toggle_pill_off)
            button.setTextColor(getColor(R.color.nebula_text_2ry))
        }
    }

    private fun updateTrimStyle(button: Button, active: Boolean) {
        if (active) {
            button.setBackgroundResource(R.drawable.bg_toggle_pill_on)
            button.setTextColor(getColor(R.color.nebula_cyan))
        } else {
            button.setBackgroundResource(R.drawable.bg_toggle_pill_off)
            button.setTextColor(getColor(R.color.nebula_text_2ry))
        }
    }

    /**
     * 手动 D-pad 焦点链。按布局从上到下顺序，UP/DOWN 移动焦点，
     * CENTER/ENTER 触发点击。
     */
    private var dpadFocusIndex = 0
    private lateinit var dpadFocusList: List<View>

    private fun setupManualDpad() {
        // 显式 <View>：否则元素类型会被下面的 RadioButton 元素带偏推断成 RadioButton，
        // 无类型的 findViewById（如 ImageButton 的返回键）存入数组时抛 ArrayStoreException
        dpadFocusList = listOfNotNull<View>(
            findViewById(R.id.btn_settings_back),
            findViewById(R.id.et_device_name),
            findViewById(R.id.btn_save_device_name),
            findViewById(R.id.row_prefer_5g),
            findViewById<RadioGroup>(R.id.rg_quality)?.let { rg ->
                rg.findViewById<RadioButton>(rg.checkedRadioButtonId) ?: findViewById(R.id.rb_quality_auto)
            },
            findViewById(R.id.row_legacy_mode),
            findViewById(R.id.row_skip_pair_verify),
            findViewById(R.id.row_av_trim),
            findViewById(R.id.btn_clear_pairings),
            findViewById(R.id.row_language),
            findViewById(R.id.row_theme),
            findViewById(R.id.sp_update_mirror),
            findViewById(R.id.btn_save_update_cfg),
            findViewById(R.id.row_auto_update),
            findViewById(R.id.btn_view_logs),
            findViewById(R.id.btn_check_update)
        ).filter { it.visibility == View.VISIBLE }

        dpadFocusList.firstOrNull()?.let {
            it.requestFocus()
            dpadFocusIndex = 0
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!::dpadFocusList.isInitialized || dpadFocusList.isEmpty()) {
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                dpadFocusIndex = (dpadFocusIndex + 1) % dpadFocusList.size
                dpadFocusList[dpadFocusIndex].requestFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                dpadFocusIndex = (dpadFocusIndex - 1 + dpadFocusList.size) % dpadFocusList.size
                dpadFocusList[dpadFocusIndex].requestFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                dpadFocusList.getOrNull(dpadFocusIndex)?.performClick()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // 水平按键让系统焦点（如 RadioGroup 内部切换）自行处理
                return super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 可信局域网跳过配对校验 + 清除已配对设备 */
    private fun setupAirPlaySection() {
        val airPlayPrefs = com.castlink.receiver.airplay.AirPlayPrefs(this)
        val btnSkip = findViewById<Button>(R.id.btn_skip_pair_verify)
        fun refreshSkip() {
            updatePillState(btnSkip, airPlayPrefs.skipPairVerify)
        }
        refreshSkip()
        val toggleSkip = {
            airPlayPrefs.skipPairVerify = !airPlayPrefs.skipPairVerify
            refreshSkip()
            Toast.makeText(this, R.string.update_cfg_saved, Toast.LENGTH_SHORT).show()
        }
        btnSkip.setOnClickListener { toggleSkip() }
        findViewById<View>(R.id.row_skip_pair_verify)?.setOnClickListener { toggleSkip() }

        // 声画微调：-300 … +500 ms，每次点击进一档（100ms），到头回到 -300。
        val btnTrim = findViewById<Button>(R.id.btn_av_trim)
        val trimSteps = intArrayOf(-300, -200, -100, 0, 100, 200, 300, 400, 500)
        fun refreshTrim() {
            val v = airPlayPrefs.avTrimMs
            btnTrim.text = (if (v > 0) "+$v" else "$v") + " ms"
            updateTrimStyle(btnTrim, v != 0)
        }
        refreshTrim()
        val cycleTrim = {
            val cur = airPlayPrefs.avTrimMs
            val idx = trimSteps.indexOfFirst { it > cur }
            val next = if (idx < 0) trimSteps.first() else trimSteps[idx]
            airPlayPrefs.avTrimMs = next
            com.castlink.receiver.airplay.StreamStats.avTrimMs = next   // 同进程，立即生效
            refreshTrim()
        }
        btnTrim.setOnClickListener { cycleTrim() }
        findViewById<View>(R.id.row_av_trim)?.setOnClickListener { cycleTrim() }

        findViewById<Button>(R.id.btn_clear_pairings).setOnClickListener {
            getSharedPreferences("castlink_pairings", MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this, R.string.pairings_cleared, Toast.LENGTH_LONG).show()
        }
    }

    /** 在线更新 */
    private fun setupUpdateSection() {
        val updatePrefs = UpdatePrefs(this)
        val tvVersion = findViewById<TextView>(R.id.tv_update_version)
        val spMirror = findViewById<Spinner>(R.id.sp_update_mirror)

        val pkg = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val vc = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode.toInt() else pkg.versionCode
        tvVersion.text = getString(R.string.update_current_version, pkg.versionName, vc)

        val mirrorNames = UpdateManager.MIRRORS.map { it.removePrefix("https://").removeSuffix("/") }
        spMirror.adapter = ArrayAdapter(this, R.layout.item_spinner, mirrorNames).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spMirror.setSelection(updatePrefs.mirrorIndex.coerceIn(mirrorNames.indices))

        val btnAuto = findViewById<Button>(R.id.btn_auto_update)
        fun refreshAuto() {
            updatePillState(btnAuto, updatePrefs.autoCheck)
        }
        refreshAuto()
        val toggleAuto = {
            updatePrefs.autoCheck = !updatePrefs.autoCheck
            refreshAuto()
        }
        btnAuto.setOnClickListener { toggleAuto() }
        findViewById<View>(R.id.row_auto_update)?.setOnClickListener { toggleAuto() }

        findViewById<Button>(R.id.btn_save_update_cfg).setOnClickListener {
            updatePrefs.mirrorIndex = spMirror.selectedItemPosition
            Toast.makeText(this, R.string.update_cfg_saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_check_update).setOnClickListener {
            checkUpdate(manual = true)
        }
        runCatching {
            findViewById<Button>(R.id.btn_view_logs).setOnClickListener {
                startActivity(android.content.Intent(this, LogViewerActivity::class.java))
            }
        }
    }

    /** 检查更新；manual=true 时无更新也 toast 提示 */
    fun checkUpdate(manual: Boolean = false) {
        val tvVersion = findViewById<TextView>(R.id.tv_update_version)
        tvVersion.text = getString(R.string.update_checking)
        UpdateManager.check(this, object : UpdateManager.Callback {
            override fun onResult(info: UpdateManager.UpdateInfo?) {
                runOnUiThread {
                    UpdatePrefs(this@SettingsActivity).lastCheckTime = System.currentTimeMillis()
                    if (info == null) {
                        tvVersion.text = getString(R.string.update_latest)
                        if (manual) Toast.makeText(this@SettingsActivity,
                            R.string.update_latest, Toast.LENGTH_SHORT).show()
                    } else {
                        tvVersion.text = getString(R.string.update_found,
                            info.versionName, info.versionCode)
                        showUpdateDialog(info)
                    }
                }
            }
            override fun onError(msg: String) {
                runOnUiThread {
                    tvVersion.text = getString(R.string.update_check_failed,
                        if (msg == "not_configured") getString(R.string.update_not_configured) else msg)
                    if (manual) Toast.makeText(this@SettingsActivity,
                        R.string.update_check_failed_short, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun showUpdateDialog(info: UpdateManager.UpdateInfo) {
        val progressTv = TextView(this).apply {
            setPadding(48, 16, 48, 16)
            text = getString(R.string.update_ready)
            setTextColor(getColor(R.color.nebula_text))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_dialog_title, info.versionName))
            .setMessage(info.changelog.ifBlank { getString(R.string.update_no_changelog) })
            .setView(progressTv)
            .setPositiveButton(R.string.update_now, null)
            .setNegativeButton(R.string.update_later, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            it.isEnabled = false
            UpdateManager.download(this, info, object : UpdateManager.DownloadCallback {
                override fun onProgress(percent: Int) {
                    runOnUiThread {
                        progressTv.text = getString(R.string.update_downloading, percent)
                    }
                }
                override fun onDone(file: java.io.File) {
                    runOnUiThread {
                        dialog.dismiss()
                        UpdateManager.install(this@SettingsActivity, file)
                    }
                }
                override fun onError(msg: String) {
                    runOnUiThread {
                        it.isEnabled = true
                        progressTv.text = getString(R.string.update_download_failed, msg)
                    }
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        tvLangSummary.text = LocaleHelper.getDisplayName(this)
    }

    private fun themeLabel(theme: String): String = getString(
        when (theme) {
            ThemeHelper.THEME_LIGHT -> R.string.theme_light
            ThemeHelper.THEME_DARK -> R.string.theme_dark
            else -> R.string.theme_system
        }
    )

    private fun showThemeDialog() {
        val values = arrayOf(ThemeHelper.THEME_SYSTEM, ThemeHelper.THEME_LIGHT, ThemeHelper.THEME_DARK)
        val labels = Array(values.size) { themeLabel(values[it]) }
        val checked = values.indexOf(ThemeHelper.getTheme(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_theme_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                ThemeHelper.setTheme(this, values[which])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.disconnect_cancel, null)
            .show()
    }

    private fun showLanguageDialog() {
        val labels = arrayOf(
            getString(R.string.lang_follow_system),
            getString(R.string.lang_zh),
            getString(R.string.lang_en)
        )
        val values = arrayOf(
            LocaleHelper.LANG_SYSTEM,
            LocaleHelper.LANG_ZH,
            LocaleHelper.LANG_EN
        )
        val current = LocaleHelper.getLanguage(this)
        var checked = values.indexOf(current)
        if (checked < 0) checked = 0
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_language_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                LocaleHelper.setLanguage(this, values[which])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.disconnect_cancel, null)
            .show()
    }

    companion object {
        private const val PREFS = "castlink_prefs"
        private const val KEY_QUALITY = "quality"
        private const val KEY_PREFER_5G = "prefer_5g"
        private const val KEY_LEGACY_MODE = "legacy_mode"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val QUALITY_AUTO = "auto"
        private const val QUALITY_FLAGSHIP = "flagship"
        private const val QUALITY_1080P = "1080p"
        private const val QUALITY_720P = "720p"
    }
}
