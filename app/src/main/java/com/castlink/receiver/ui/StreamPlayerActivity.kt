package com.castlink.receiver.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.castlink.receiver.R
import com.castlink.receiver.player.VideoPipeline
import com.castlink.receiver.airplay.util.Logger as AirPlayLogger
import com.castlink.receiver.protocol.NativeStreamBridge
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * v0.3.10：原生协议投屏播放页（全屏）。
 * v0.1.22: SurfaceView -> TextureView，支持 centerCrop 铺满（修竖屏视频黑边）。
 *
 * StandbyActivity 收到首个 RTP NAL 时自动拉起本页；NAL 经 [NativeStreamBridge]
 * 进入队列，喂给 [VideoPipeline] 解码渲染。15 秒收不到 NAL 看门狗自动退出；
 * 右下角"断开"按钮手动退出。
 */
class StreamPlayerActivity : BaseActivity() {

    private lateinit var textureView: TextureView
    private lateinit var tvStatus: TextView

    private val nalQueue = LinkedBlockingQueue<ByteArray>()
    private val running = AtomicBoolean(false)
    private var pipeline: VideoPipeline? = null
    private var feedThread: Thread? = null
    private var drainThread: Thread? = null
    @Volatile private var lastNalTime = 0L
    @Volatile private var videoW = 1280f
    @Volatile private var videoH = 720f

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

        textureView = findViewById(R.id.tv_stream)
        tvStatus = findViewById(R.id.tv_stream_status)
        findViewById<Button>(R.id.btn_stream_close).setOnClickListener { finish() }

        tvStatus.text = getString(R.string.stream_connecting)
        // v0.1.22: TextureView 监听 SurfaceTexture，用 centerCrop 铺满
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                startPipeline(Surface(st))
                updateCropTransform()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                updateCropTransform()
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                stopPipeline()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

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

    private fun startPipeline(surface: Surface) {
        if (!surface.isValid) return
        val pipe = VideoPipeline(surface)
        // v0.1.33: 注册输出尺寸回调，用解码器真实尺寸算 centerCrop（Fold3 竖屏不是 1280x720）
        pipe.onOutputSizeChanged = { w, h ->
            videoW = w.toFloat()
            videoH = h.toFloat()
            runOnUiThread { updateCropTransform() }
        }
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

    /**
     * v0.1.22: centerCrop 变换，让视频铺满 TextureView（裁掉多余部分）。
     * 视频 1280x720，View 可能是竖屏或横屏，取最大缩放比铺满。
     */
    private fun updateCropTransform() {
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        if (viewW <= 0 || viewH <= 0) return
        // 视频尺寸：用解码器输出的真实尺寸（Fold3 等设备不是 1280x720）
        val videoW = this.videoW
        val videoH = this.videoH

        val scaleX = viewW / videoW
        val scaleY = viewH / videoH
        // centerCrop: 取大者，铺满并裁剪
        val scale = maxOf(scaleX, scaleY)

        val matrix = Matrix()
        // 先缩放到铺满，再平移到中心
        val dx = (viewW - videoW * scale) / 2f
        val dy = (viewH - videoH * scale) / 2f
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        textureView.setTransform(matrix)
        AirPlayLogger.i("CropTransform: view=${viewW}x${viewH} video=${videoW}x${videoH} scale=$scale dx=$dx dy=$dy")
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
        private const val WATCHDOG_MS = 30_000L
    }
}
