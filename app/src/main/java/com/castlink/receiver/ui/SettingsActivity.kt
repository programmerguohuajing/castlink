package com.castlink.receiver.ui

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.castlink.receiver.R
import com.castlink.receiver.update.UpdateManager
import com.castlink.receiver.update.UpdatePrefs
import com.castlink.receiver.util.LocaleHelper

/**
 * 设置页（UI-04）：语言切换、本机能力卡、画质单选、开关项、设备名。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvLangSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // 语言：副标题显示当前生效语言，点击弹单选对话框
        tvLangSummary = findViewById(R.id.tv_lang_summary)
        tvLangSummary.text = LocaleHelper.getDisplayName(this)
        findViewById<LinearLayout>(R.id.row_language).setOnClickListener {
            showLanguageDialog()
        }

        // 画质单选（持久化）
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

        // 优先 5GHz（持久化）——TV 遥控器用 Button 开关
        val btnPrefer5g = findViewById<Button>(R.id.btn_prefer_5g)
        fun refreshPrefer5g() {
            val on = prefs.getBoolean(KEY_PREFER_5G, false)
            btnPrefer5g.text = getString(R.string.setting_prefer_5g) + "：" + getString(if (on) R.string.on else R.string.off)
        }
        refreshPrefer5g()
        btnPrefer5g.setOnClickListener {
            prefs.edit().putBoolean(KEY_PREFER_5G, !prefs.getBoolean(KEY_PREFER_5G, false)).apply()
            refreshPrefer5g()
        }

        // Legacy Android 4 接收模式（限 720p）——TV 遥控器用 Button 开关
        val btnLegacy = findViewById<Button>(R.id.btn_legacy_mode)
        fun refreshLegacy() {
            val on = prefs.getBoolean(KEY_LEGACY_MODE, false)
            btnLegacy.text = getString(R.string.setting_legacy_mode) + "：" + getString(if (on) R.string.on else R.string.off)
        }
        refreshLegacy()
        btnLegacy.setOnClickListener {
            prefs.edit().putBoolean(KEY_LEGACY_MODE, !prefs.getBoolean(KEY_LEGACY_MODE, false)).apply()
            refreshLegacy()
        }

        // 设备名（持久化，供 mDNS 广播用）
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

        // v0.3.12：在线更新
        setupUpdateSection()

        // v0.1.1 (issue #1)：AirPlay 配对
        setupAirPlaySection()

        // v0.1.26: 手动 D-pad 焦点控制（TV 上系统焦点搜索不可靠，经常跳过按钮）
        setupManualDpad()
    }

    /**
     * v0.1.26: 手动 D-pad 焦点链。按布局从上到下顺序，UP/DOWN 移动焦点，
     * CENTER/ENTER 触发点击。彻底绕开系统的焦点搜索算法。
     */
    private var dpadFocusIndex = 0
    private lateinit var dpadFocusList: List<android.view.View>

    private fun setupManualDpad() {
        dpadFocusList = listOfNotNull(
            findViewById<android.view.View>(R.id.row_language),
            findViewById<android.view.View>(R.id.btn_prefer_5g),
            findViewById<android.view.View>(R.id.btn_legacy_mode),
            findViewById<android.view.View>(R.id.et_device_name),
            findViewById<android.view.View>(R.id.btn_save_device_name),
            findViewById<android.view.View>(R.id.btn_skip_pair_verify),
            findViewById<android.view.View>(R.id.btn_clear_pairings),
            findViewById<android.view.View>(R.id.btn_auto_update),
            findViewById<android.view.View>(R.id.btn_check_update),
            findViewById<android.view.View>(R.id.btn_save_update_cfg)
        ).filter { it.visibility == android.view.View.VISIBLE }
        // 初始焦点给第一个
        dpadFocusList.firstOrNull()?.let {
            it.requestFocus()
            dpadFocusIndex = 0
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (!::dpadFocusList.isInitialized || dpadFocusList.isEmpty()) {
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                dpadFocusIndex = (dpadFocusIndex + 1) % dpadFocusList.size
                dpadFocusList[dpadFocusIndex].requestFocus()
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                dpadFocusIndex = (dpadFocusIndex - 1 + dpadFocusList.size) % dpadFocusList.size
                dpadFocusList[dpadFocusIndex].requestFocus()
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER -> {
                dpadFocusList.getOrNull(dpadFocusIndex)?.performClick()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** v0.1.1 (issue #1)：可信局域网跳过配对校验 + 清除已配对设备 */
    private fun setupAirPlaySection() {
        val airPlayPrefs = com.castlink.receiver.airplay.AirPlayPrefs(this)
        val btnSkip = findViewById<Button>(R.id.btn_skip_pair_verify)
        fun refreshSkip() {
            btnSkip.text = getString(R.string.setting_skip_pair_verify) + "：" + getString(if (airPlayPrefs.skipPairVerify) R.string.on else R.string.off)
        }
        refreshSkip()
        btnSkip.setOnClickListener {
            airPlayPrefs.skipPairVerify = !airPlayPrefs.skipPairVerify
            refreshSkip()
            Toast.makeText(this, R.string.update_cfg_saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_clear_pairings).setOnClickListener {
            // 清除所有已配对设备的 LTPK（castlink_pairings prefs）
            getSharedPreferences("castlink_pairings", MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this, R.string.pairings_cleared, Toast.LENGTH_LONG).show()
        }
    }

    /** v0.3.12：更新源配置 + 检查更新 + 下载安装 */
    private fun setupUpdateSection() {
        val updatePrefs = UpdatePrefs(this)
        val tvVersion = findViewById<TextView>(R.id.tv_update_version)
        val spMirror = findViewById<Spinner>(R.id.sp_update_mirror)

        val pkg = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val vc = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode.toInt() else pkg.versionCode
        tvVersion.text = getString(R.string.update_current_version, pkg.versionName, vc)

        val mirrorNames = UpdateManager.MIRRORS.map { it.removePrefix("https://").removeSuffix("/") }
        spMirror.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mirrorNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spMirror.setSelection(updatePrefs.mirrorIndex.coerceIn(mirrorNames.indices))
        val btnAuto = findViewById<Button>(R.id.btn_auto_update)
        fun refreshAuto() {
            btnAuto.text = getString(R.string.setting_auto_update) + "：" + getString(if (updatePrefs.autoCheck) R.string.on else R.string.off)
        }
        refreshAuto()
        btnAuto.setOnClickListener {
            updatePrefs.autoCheck = !updatePrefs.autoCheck
            refreshAuto()
        }

        findViewById<Button>(R.id.btn_save_update_cfg).setOnClickListener {
            updatePrefs.mirrorIndex = spMirror.selectedItemPosition
            Toast.makeText(this, R.string.update_cfg_saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_check_update).setOnClickListener {
            checkUpdate(manual = true)
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
        // recreate() 后副标题刷新
        tvLangSummary.text = LocaleHelper.getDisplayName(this)
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
