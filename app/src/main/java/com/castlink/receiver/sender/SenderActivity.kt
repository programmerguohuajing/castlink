package com.castlink.receiver.sender

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.castlink.receiver.R
import com.castlink.receiver.discovery.NsdHelper
import com.castlink.receiver.model.Device
import com.castlink.receiver.model.Tier
import com.castlink.receiver.ui.BaseActivity
import com.castlink.receiver.util.NetUtil

/**
 * 发送端入口 Activity：
 * 1. 用 NsdHelper 发现 `_castlink._tcp` 接收端设备并列表展示（过滤本机）；
 * 2. 点击设备 → 输入接收端 PIN → 申请录屏权限（startActivityForResult 标准流程）；
 * 3. 授权通过 → startForegroundService 启动 [SenderService] 开始投屏。
 */
class SenderActivity : BaseActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var lvDevices: ListView
    private lateinit var btnStop: Button
    private lateinit var listAdapter: ArrayAdapter<String>

    private val devices = mutableListOf<Device>()
    private val nsdHelper = NsdHelper()
    private val selfIp: String by lazy { NetUtil.getLocalIp() }

    private var pendingIp: String? = null
    private var pendingPin: String? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // v0.3.9：录屏授权是系统弹窗，Activity 可能被销毁重建（Fold 折叠/切后台）——
        // pending 数据丢了会导致 SenderService 拿到空 extras 而崩溃。
        outState.putString(STATE_PENDING_IP, pendingIp)
        outState.putString(STATE_PENDING_PIN, pendingPin)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(SenderService.EXTRA_STATUS) ?: return
            val detail = intent.getStringExtra(SenderService.EXTRA_DETAIL).orEmpty()
            tvStatus.text = when (status) {
                SenderService.STATUS_CONNECTING ->
                    getString(R.string.sender_status_connecting, detail)
                SenderService.STATUS_STARTED ->
                    getString(R.string.sender_status_started, detail)
                SenderService.STATUS_PIN_FAILED ->
                    getString(R.string.sender_status_pin_failed)
                SenderService.STATUS_STOPPED ->
                    getString(R.string.sender_status_stopped)
                SenderService.STATUS_ERROR ->
                    getString(R.string.sender_status_error, detail)
                else -> status
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingIp = savedInstanceState?.getString(STATE_PENDING_IP) ?: pendingIp
        pendingPin = savedInstanceState?.getString(STATE_PENDING_PIN) ?: pendingPin
        setContentView(R.layout.activity_sender)

        tvStatus = findViewById(R.id.tv_sender_status)
        lvDevices = findViewById(R.id.lv_sender_devices)
        btnStop = findViewById(R.id.btn_sender_stop)

        tvStatus.text = getString(R.string.sender_discovering)
        listAdapter = ArrayAdapter(this, R.layout.item_dark_simple, mutableListOf())
        lvDevices.adapter = listAdapter
        lvDevices.setOnItemClickListener { _, _, position, _ ->
            askPinAndStart(devices[position])
        }
        btnStop.setOnClickListener {
            startService(
                Intent(this, SenderService::class.java)
                    .setAction(SenderService.ACTION_STOP)
            )
        }

        // v0.3.11：手动输入 IP 直连——跳过发现，直接弹 PIN 框
        intent.getStringExtra(EXTRA_MANUAL_IP)?.takeIf { it.isNotBlank() }?.let { ip ->
            val device = Device(
                name = getString(R.string.sender_manual_device, ip),
                ip = ip.trim(),
                signalLevel = 0,
                tier = Tier.MAINSTREAM
            )
            lvDevices.post { askPinAndStart(device) }
        }

        nsdHelper.discover(this) { device ->
            runOnUiThread {
                // 过滤本机（本机也可能在跑接收端待机页并广播自己）
                if (device.ip == selfIp) return@runOnUiThread
                if (devices.none { it.ip == device.ip }) {
                    devices.add(device)
                    refreshList()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(SenderService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            // 状态广播只在本包内发送（SenderService 用 setPackage 限定），无需导出
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        nsdHelper.stopDiscovery()
        super.onDestroy()
    }

    private fun refreshList() {
        val rows = devices.map { "${it.name}\n${it.ip}" }
        listAdapter.clear()
        listAdapter.addAll(rows)
        listAdapter.notifyDataSetChanged()
        if (devices.isEmpty()) {
            tvStatus.text = getString(R.string.sender_no_devices)
        }
    }

    private fun askPinAndStart(device: Device) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.sender_pin_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sender_pin_title, device.name))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pendingIp = device.ip
                pendingPin = input.text.toString()
                requestCapturePermission()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestCapturePermission() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, SenderService::class.java).apply {
                putExtra(SenderService.EXTRA_TARGET_IP, pendingIp)
                putExtra(SenderService.EXTRA_PIN, pendingPin)
                putExtra(SenderService.EXTRA_RESULT_CODE, resultCode)
                putExtra(SenderService.EXTRA_PROJECTION_DATA, data)
            }
            startForegroundService(serviceIntent)
            tvStatus.text = getString(R.string.sender_status_connecting, pendingIp.orEmpty())
        } else {
            Toast.makeText(this, R.string.sender_capture_denied, Toast.LENGTH_SHORT).show()
        }
        pendingIp = null
        pendingPin = null
    }

    companion object {
        private const val REQ_CAPTURE = 2001
        private const val STATE_PENDING_IP = "pending_ip"
        private const val STATE_PENDING_PIN = "pending_pin"
        /** v0.3.11：手动输入的接收端 IP，直连跳过 mDNS 发现 */
        const val EXTRA_MANUAL_IP = "manual_ip"
    }
}
