package com.castlink.receiver.ui

import android.app.AlertDialog
import com.castlink.receiver.update.UpdateManager
import com.castlink.receiver.update.UpdatePrefs
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import com.castlink.receiver.protocol.FilePushServer
import com.castlink.receiver.util.FileAction
import com.castlink.receiver.R
import com.castlink.receiver.airplay.AirPlayService
import com.castlink.receiver.discovery.NsdHelper
import com.castlink.receiver.model.Device
import com.castlink.receiver.model.Tier
import com.castlink.receiver.sender.SenderActivity
import com.castlink.receiver.util.CrashReporter
import com.castlink.receiver.util.NetUtil

class MainActivity : BaseActivity() {

    private lateinit var flipper: ViewFlipper
    private lateinit var tabCast: Button
    private lateinit var tabMedia: Button
    private lateinit var tabMine: Button
    private lateinit var tvWifiStatus: TextView
    private lateinit var lvDevices: ListView
    private lateinit var deviceAdapter: DeviceAdapter
    // v0.3.11：媒体页文件列表
    private lateinit var lvMediaFiles: ListView
    private lateinit var mediaAdapter: ArrayAdapter<String>
    private val mediaFiles = mutableListOf<java.io.File>()

    private val devices = mutableListOf<Device>()
    private val nsdHelper = NsdHelper()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v0.3.6：若上次崩溃有日志，先弹窗展示堆栈（放 setContentView 之前，保证 crash-loop 也能看到）
        CrashReporter.showPending(this)
        setContentView(R.layout.activity_main)

        flipper = findViewById(R.id.flipper)
        tabCast = findViewById(R.id.tab_btn_cast)
        tabMedia = findViewById(R.id.tab_btn_media)
        tabMine = findViewById(R.id.tab_btn_mine)
        tvWifiStatus = findViewById(R.id.tv_wifi_status)
        lvDevices = findViewById(R.id.lv_devices)
        lvMediaFiles = findViewById(R.id.lv_media_files)

        // v0.3.11：媒体页——收到的投放文件列表，点击用 FileAction 打开
        mediaAdapter = ArrayAdapter(this, R.layout.item_dark_simple, mutableListOf())
        lvMediaFiles.adapter = mediaAdapter
        lvMediaFiles.setOnItemClickListener { _, _, position, _ ->
            if (mediaFiles.isEmpty()) return@setOnItemClickListener
            val file = mediaFiles[position]
            FileAction.openExternal(this, file)
        }

        tabCast.setOnClickListener { selectTab(0) }
        tabMedia.setOnClickListener { selectTab(1) }
        tabMine.setOnClickListener { selectTab(2) }
        selectTab(0)
        // TV 遥控器：默认聚焦第一个按钮，打开即有可见焦点
        tabCast.post { tabCast.requestFocus() }

        findViewById<Button>(R.id.btn_start_cast).setOnClickListener {
            startActivity(Intent(this, StandbyActivity::class.java))
        }
        // 发送端入口：发现接收端设备 → 选设备 → 输 PIN → 录屏授权 → 投屏
        findViewById<Button>(R.id.btn_start_sender).setOnClickListener {
            startActivity(Intent(this, SenderActivity::class.java))
        }
        // v0.3.11：手动输入接收端 IP 直连
        findViewById<Button>(R.id.btn_manual_ip).setOnClickListener {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                hint = getString(R.string.manual_ip_hint)
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.manual_ip_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val ip = input.text.toString().trim()
                    if (ip.isNotEmpty()) {
                        startActivity(
                            Intent(this, SenderActivity::class.java)
                                .putExtra(SenderActivity.EXTRA_MANUAL_IP, ip)
                        )
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_help).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_content)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        deviceAdapter = DeviceAdapter()
        lvDevices.adapter = deviceAdapter
        lvDevices.emptyView = findViewById(R.id.empty_view)
        lvDevices.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                val device = devices[position]
                // v0.3.4：点设备不再直跳监控页（用户误以为已开始投屏）——弹窗明确意图。
                AlertDialog.Builder(this)
                    .setTitle(device.name)
                    .setItems(
                        arrayOf(
                            getString(R.string.device_action_cast),
                            getString(R.string.device_action_monitor),
                        )
                    ) { _, which ->
                        if (which == 0) {
                            // 投屏到此设备：走发送端流程（选设备 → 输 PIN → 录屏授权）
                            startActivity(Intent(this, SenderActivity::class.java))
                        } else {
                            val intent = Intent(this, ControlActivity::class.java).apply {
                                putExtra(ControlActivity.EXTRA_NAME, device.name)
                                putExtra(ControlActivity.EXTRA_IP, device.ip)
                                putExtra(ControlActivity.EXTRA_TIER, device.tier.name)
                            }
                            startActivity(intent)
                        }
                    }
                    .show()
            }

        // v0.3.15：演示数据已删除，只显示真实发现的设备
        deviceAdapter.notifyDataSetChanged()

        updateWifiStatus()

        // 启动 AirPlay 前台服务（mDNS 广播 + RTSP 握手 + PIN 配对，START_STICKY）
        startForegroundService(Intent(this, AirPlayService::class.java))

        nsdHelper.discover(this) { device ->
            runOnUiThread {
                val index = devices.indexOfFirst { it.ip == device.ip }
                if (index >= 0) {
                    devices[index] = device.copy(isFavorite = devices[index].isFavorite)
                } else {
                    devices.add(device)
                }
                deviceAdapter.notifyDataSetChanged()
            }
        }
    }

    /** v0.3.5：SSID 拿不到（Android 10+ 无定位权限）时退化为"已连接"而非"未连接"。 */
    private fun updateWifiStatus() {
        val ssid = NetUtil.getWifiSsid(this)
        tvWifiStatus.text = when {
            ssid.isNotEmpty() -> getString(R.string.wifi_connected_ssid, ssid)
            NetUtil.isWifiConnected(this) -> getString(R.string.wifi_connected)
            else -> getString(R.string.wifi_disconnected)
        }
    }

    override fun onResume() {
        super.onResume()
        updateWifiStatus()
        refreshMediaFiles()
        autoCheckUpdate()
    }

    /** v0.3.12：启动时自动检查更新（每天最多一次） */
    private fun autoCheckUpdate() {
        val prefs = UpdatePrefs(this)
        if (!prefs.autoCheck) return
        if (System.currentTimeMillis() - prefs.lastCheckTime < 24 * 3600_000L) return
        prefs.lastCheckTime = System.currentTimeMillis()
        UpdateManager.check(this, object : UpdateManager.Callback {
            override fun onResult(info: UpdateManager.UpdateInfo?) {
                if (info != null) {
                    runOnUiThread { showUpdateAvailableDialog(info) }
                }
            }
            override fun onError(msg: String) { /* 静默失败，不打扰用户 */ }
        })
    }

    private fun showUpdateAvailableDialog(info: UpdateManager.UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_dialog_title, info.versionName))
            .setMessage(info.changelog.ifBlank { getString(R.string.update_no_changelog) })
            .setPositiveButton(R.string.update_now) { _, _ ->
                // 跳到设置页走完整下载流程
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    /** v0.3.11：刷新媒体页——列出收到的投放文件 */
    private fun refreshMediaFiles() {
        if (!::mediaAdapter.isInitialized) return
        val dir = FilePushServer.dirFor(cacheDir)
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        mediaFiles.clear()
        mediaFiles.addAll(files)
        mediaAdapter.clear()
        if (files.isEmpty()) {
            mediaAdapter.add(getString(R.string.media_empty))
        } else {
            files.forEach { mediaAdapter.add(it.name) }
        }
        mediaAdapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        nsdHelper.stopDiscovery()
        super.onDestroy()
    }

    private fun selectTab(index: Int) {
        flipper.displayedChild = index
        // NEBULA：选中 tab 青色高亮
        val cyan = getColor(R.color.nebula_cyan)
        val dim = getColor(R.color.nebula_text_2ry)
        tabCast.setTextColor(if (index == 0) cyan else dim)
        tabMedia.setTextColor(if (index == 1) cyan else dim)
        tabMine.setTextColor(if (index == 2) cyan else dim)
    }

    private inner class DeviceAdapter : BaseAdapter() {

        override fun getCount(): Int = devices.size

        override fun getItem(position: Int): Device = devices[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.item_device, parent, false)
            val device = devices[position]

            view.findViewById<TextView>(R.id.tv_device_name).text = device.name
            view.findViewById<TextView>(R.id.tv_device_ip).text = device.ip
            view.findViewById<TextView>(R.id.tv_device_signal).text =
                getString(R.string.signal_level, getString(signalText(device.signalLevel)))

            val badge = view.findViewById<TextView>(R.id.tv_device_badge)
            when (device.tier) {
                Tier.FLAGSHIP -> {
                    badge.setText(R.string.tier_flagship_badge)
                    badge.setBackgroundResource(R.drawable.bg_badge_flagship)
                }
                Tier.MAINSTREAM -> {
                    badge.setText(R.string.tier_mainstream_badge)
                    badge.setBackgroundResource(R.drawable.bg_badge_mainstream)
                }
                Tier.LEGACY -> {
                    badge.setText(R.string.tier_legacy_badge)
                    badge.setBackgroundResource(R.drawable.bg_badge_legacy)
                }
            }

            val favorite = view.findViewById<Button>(R.id.btn_favorite)
            favorite.setText(if (device.isFavorite) R.string.favorite_on else R.string.favorite_off)
            favorite.contentDescription = getString(R.string.favorite_desc)
            favorite.setOnClickListener {
                devices[position] = device.copy(isFavorite = !device.isFavorite)
                notifyDataSetChanged()
            }
            return view
        }

        private fun signalText(level: Int): Int = when (level) {
            4 -> R.string.signal_strong
            3 -> R.string.signal_good
            2 -> R.string.signal_fair
            1 -> R.string.signal_weak
            else -> R.string.signal_none
        }
    }
}
