package com.castlink.receiver.ui

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.castlink.receiver.R
import com.castlink.receiver.player.VideoPipeline
import com.castlink.receiver.protocol.NativeStreamBridge
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * v0.3.10：原生协议投屏播放页（全屏）。
 *
 * StandbyActivity 收到首个 RTP NAL 时自动拉起本页；NAL 经 [NativeStreamBridge]
 * 进入队列，喂给 [VideoPipeline] 解码渲染。5 秒收不到 NAL 看门狗自动退出；
 * 右下角"断开"按钮手动退出。
 */
class StreamPlayerActivity : BaseActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var tvStatus: TextView

    private val nalQueue = LinkedBlockingQueue<ByteArray>()
    private val running = AtomicBoolean(false)
    private var pipeline: VideoPipeline? = null
    private var feedThread: Thread? = null
    private var drainThread: Thread? = null
    @Volatile private var lastNalTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // v0.1.14: 沉浸式全屏，隐藏状态栏/导航栏
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        setContentView(R.layout.activity_stream_player)

        surfaceView = findViewById(R.id.sv_stream)
        tvStatus = findViewById(R.id.tv_stream_status)
        findViewById<Button>(R.id.btn_stream_close).setOnClickListener { finish() }

        tvStatus.text = getString(R.string.stream_connecting)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = startPipeline(holder)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) = stopPipeline()
        })

        NativeStreamBridge.nalListener = { nal ->
            lastNalTime = SystemClock.elapsedRealtime()
            // 队列满时丢最旧，避免无限堆积
            if (!nalQueue.offer(nal)) {
                nalQueue.poll()
                nalQueue.offer(nal)
            }
        }
        NativeStreamBridge.endListener = { runOnUiThread { finish() } }
    }

    private fun startPipeline(holder: SurfaceHolder) {
        val surface = holder.surface ?: return
        if (!surface.isValid) return
        val pipe = VideoPipeline(surface)
        // 发送端为 720p H.264；SPS/PPS 走带内（in-band），解码器从码流中获取
        pipe.configure("video/avc", 1280, 720)
        pipeline = pipe
        running.set(true)
        lastNalTime = SystemClock.elapsedRealtime()

        feedThread = thread(name = "StreamFeed", isDaemon = true) {
            val startCode = byteArrayOf(0, 0, 0, 1)
            var ptsUs = 0L
            while (running.get()) {
                try {
                    val nal = nalQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    // 裸 NAL 加 Annex-B 起始码
                    val annexb = startCode + nal
                    pipe.feedSample(annexb, ptsUs)
                    ptsUs += 33_333L // 30fps
                    runOnUiThread { tvStatus.visibility = View.GONE }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "feed failed", e)
                }
            }
        }

        drainThread = thread(name = "StreamDrain", isDaemon = true) {
            while (running.get()) {
                try {
                    pipe.drainOutput(20_000L)
                    // 看门狗：5 秒无 NAL 视为流结束
                    if (SystemClock.elapsedRealtime() - lastNalTime > WATCHDOG_MS) {
                        Log.i(TAG, "watchdog: no NAL for 5s, finishing")
                        runOnUiThread { finish() }
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "drain failed", e)
                }
            }
        }
        Log.i(TAG, "pipeline started")
    }

    private fun stopPipeline() {
        running.set(false)
        feedThread?.interrupt()
        try {
            feedThread?.join(1000)
            drainThread?.join(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        feedThread = null
        drainThread = null
        pipeline?.release()
        pipeline = null
        nalQueue.clear()
    }

    override fun onDestroy() {
        NativeStreamBridge.clear()
        stopPipeline()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StreamPlayer"
        private const val WATCHDOG_MS = 15_000L
    }
}
