package com.castlink.receiver.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.castlink.receiver.R
import com.castlink.receiver.airplay.AirPlayUiBridge
import com.castlink.receiver.model.Tier
import com.castlink.receiver.player.LoopbackTest

/**
 * 投屏控制页（UI-02）：对目标设备做模式切换、数据面板展示与本地回环自测。
 */
class ControlActivity : BaseActivity() {

    private lateinit var surfaceHolder: SurfaceHolder
    private lateinit var btnLoopback: Button
    private var surfaceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control)

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val tier = intent.getStringExtra(EXTRA_TIER)?.let { raw ->
            runCatching { Tier.valueOf(raw) }.getOrNull()
        } ?: Tier.MAINSTREAM

        findViewById<TextView>(R.id.tv_control_status).text = getString(
            R.string.control_status,
            name,
            tierName(tier),
            getString(R.string.unknown_value)
        )

        val surfaceView = findViewById<SurfaceView>(R.id.sv_preview)
        surfaceHolder = surfaceView.holder
        surfaceHolder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                btnLoopback.isEnabled = true
                // v0.3.10：AirPlay surface 改由 StandbyActivity 提供，这里不再覆盖，
                // 避免监控页关闭时把待机页的 provider 置空。
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // 无需处理
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                btnLoopback.isEnabled = false
            }
        })

        // 模式切换：游戏 / 影院 / 均衡，单选效果
        val modeGame = findViewById<Button>(R.id.btn_mode_game)
        val modeCinema = findViewById<Button>(R.id.btn_mode_cinema)
        val modeBalanced = findViewById<Button>(R.id.btn_mode_balanced)
        val modes = listOf(modeGame, modeCinema, modeBalanced)
        modes.forEach { button ->
            button.setOnClickListener {
                modes.forEach { it.isSelected = it == button }
            }
        }
        modeBalanced.isSelected = true

        val tvResolution = findViewById<TextView>(R.id.tv_value_resolution)
        val tvFramerate = findViewById<TextView>(R.id.tv_value_framerate)
        val unknown = getString(R.string.unknown_value)
        tvResolution.text = unknown
        tvFramerate.text = unknown
        findViewById<TextView>(R.id.tv_value_bitrate).text = unknown
        findViewById<TextView>(R.id.tv_value_latency).text = unknown

        findViewById<Button>(R.id.btn_disconnect).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.disconnect_title)
                .setMessage(getString(R.string.disconnect_msg, name))
                .setPositiveButton(R.string.disconnect_yes) { _, _ -> finish() }
                .setNegativeButton(R.string.disconnect_cancel, null)
                .show()
        }

        btnLoopback = findViewById(R.id.btn_loopback)
        btnLoopback.isEnabled = false
        btnLoopback.setOnClickListener {
            if (!surfaceReady) return@setOnClickListener
            btnLoopback.isEnabled = false
            tvFramerate.text = getString(R.string.loopback_running)
            LoopbackTest.run(this, surfaceHolder) { result ->
                runOnUiThread {
                    tvFramerate.text = result
                    tvResolution.text = getString(R.string.loopback_resolution, 1920, 1080)
                    btnLoopback.isEnabled = surfaceReady
                }
            }
        }
    }

    private fun tierName(tier: Tier): String = when (tier) {
        Tier.FLAGSHIP -> getString(R.string.tier_flagship_name)
        Tier.MAINSTREAM -> getString(R.string.tier_mainstream_name)
        Tier.LEGACY -> getString(R.string.tier_legacy_name)
    }

    companion object {
        const val EXTRA_NAME = "extra_device_name"
        const val EXTRA_IP = "extra_device_ip"
        const val EXTRA_TIER = "extra_device_tier"
    }
}
