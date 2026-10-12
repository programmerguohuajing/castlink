package com.castlink.receiver.legacy

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.castlink.receiver.R
import com.castlink.receiver.protocol.FilePushServer
import com.castlink.receiver.protocol.RtpReceiver
import com.castlink.receiver.util.QrUtil
import kotlin.concurrent.thread

/**
 * LegacyStandbyActivity — CastLink Legacy 待机页（API 19+）。
 *
 * 显示设备名 / PIN / 二维码 / IP / 状态。收到原生协议投屏流时，
 * SurfaceView 切到可见并解码显示；流断开 3 秒后藏回。
 */
class LegacyStandbyActivity : Activity() {

    private lateinit var app: LegacyApp
    private lateinit var surfaceView: SurfaceView
    private lateinit var tvPin: TextView
    private lateinit var tvIp: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceName: TextView
    private lateinit var ivQr: ImageView

    private var decoder: LegacyDecoder? = null
    private var rtpReceiver: RtpReceiver? = null
    private var surfaceReady = false
    private var streaming = false
    private var lastNalTime = 0L
    private var ptsUs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            if (streaming && SystemClock.elapsedRealtime() - lastNalTime > 3000) {
                stopStream()
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏常亮（接收器场景）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_legacy_standby)

        app = application as LegacyApp

        surfaceView = findViewById(R.id.sv_video)
        tvPin = findViewById(R.id.tv_pin)
        tvIp = findViewById(R.id.tv_ip)
        tvStatus = findViewById(R.id.tv_status)
        tvDeviceName = findViewById(R.id.tv_device_name)
        ivQr = findViewById(R.id.iv_qr)

        // PIN：每次启动重新生成
        app.pin = (1000..9999).random().toString()
        tvPin.text = app.pin
        tvDeviceName.text = app.deviceName

        val ip = LegacyNetUtil.getLocalIp(this)
        tvIp.text = if (ip.isNotEmpty()) "http://$ip:${FilePushServer.DEFAULT_PORT}/" else "--"
        updateQr(ip)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                stopStream()
            }
        })

        startRtpReceiver()
        handler.post(watchdog)
        Log.i(TAG, "Legacy standby ready, pin=${app.pin}")
    }

    private fun updateQr(ip: String) {
        thread(isDaemon = true) {
            val url = if (ip.isNotEmpty()) "http://$ip:${FilePushServer.DEFAULT_PORT}/" else null
            val bmp: Bitmap? = url?.let { QrUtil.encode(it, 400) }
            handler.post {
                if (bmp != null) ivQr.setImageBitmap(bmp)
            }
        }
    }

    private fun startRtpReceiver() {
        rtpReceiver = RtpReceiver(8760) { nal ->
            onNal(nal)
        }.also { it.start() }
    }

    private fun onNal(nal: ByteArray) {
        lastNalTime = SystemClock.elapsedRealtime()
        if (!streaming) {
            streaming = true
            handler.post {
                surfaceView.visibility = View.VISIBLE
                tvStatus.text = getString(R.string.legacy_streaming)
            }
            // 在 Surface 就绪后配置解码器（简单起见用 720p，SPS 会纠正实际尺寸）
            if (surfaceReady) {
                decoder = LegacyDecoder(surfaceView.holder.surface).also {
                    it.configure(1280, 720)
                }
            }
            Log.i(TAG, "legacy stream started")
        }
        ptsUs += 33_333 // ~30fps
        decoder?.feedNal(nal, ptsUs)
    }

    private fun stopStream() {
        if (!streaming) return
        streaming = false
        decoder?.release()
        decoder = null
        handler.post {
            surfaceView.visibility = View.INVISIBLE
            tvStatus.text = getString(R.string.legacy_waiting)
        }
        Log.i(TAG, "legacy stream stopped")
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        rtpReceiver?.stop()
        decoder?.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LegacyStandby"
    }
}
