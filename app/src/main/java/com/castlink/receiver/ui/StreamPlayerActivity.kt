package com.castlink.receiver.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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
    // 用户点按切换的显示模式：true=填充(裁边铺满) false=适应(黑边)；null=按宽高比自动选。
    @Volatile private var userFill: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 刘海/挖孔屏：横屏时允许内容延伸到刘海区域，否则那一侧会留一条黑边，视频铺不满全屏。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        // v0.1.14: 沉浸式全屏，隐藏状态栏/导航栏
        applyImmersive()
        setContentView(R.layout.activity_stream_player)

        textureView = findViewById(R.id.tv_stream)
        tvStatus = findViewById(R.id.tv_stream_status)
        findViewById<Button>(R.id.btn_stream_close).setOnClickListener { finish() }
        // 点按画面切换 填充/适应：自动选择不合心意时可手动改（竖屏视频默认适应，点一下可强制铺满）。
        textureView.setOnClickListener { toggleFillMode() }

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
     * 沉浸式全屏：隐藏状态栏/导航栏。某些机型在弹窗、通知栏下拉后会丢失沉浸状态，
     * 所以拿到焦点时再设一次（见 [onWindowFocusChanged]）。
     */
    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    /** 当前是否按“填充”显示：用户点按过就用用户的选择，否则按宽高比自动选。 */
    private fun currentFill(): Boolean =
        userFill ?: StreamFit.autoFill(textureView.width.toFloat(), textureView.height.toFloat(), videoW, videoH)

    private fun toggleFillMode() {
        val next = !currentFill()
        userFill = next
        updateCropTransform()
        Toast.makeText(
            this,
            if (next) R.string.stream_mode_fill else R.string.stream_mode_fit,
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 画面缩放：填充(centerCrop，裁边铺满) 或 适应(fit-center，黑边)，见 [StreamFit]。
     *
     * 注意 TextureView 的内容默认已被拉伸到铺满 View，矩阵作用在这个已拉伸的内容上，
     * 所以缩放比是相对 View 的（旧实现按视频尺寸算，导致画面被放大/缩小而不是铺满）。
     * 视频尺寸用解码器输出的真实尺寸（Fold3 等设备不是 1280x720）。
     */
    private fun updateCropTransform() {
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        if (viewW <= 0 || viewH <= 0) return
        val vw = this.videoW
        val vh = this.videoH
        val fill = currentFill()
        val (sx, sy) = StreamFit.scaleFor(viewW, viewH, vw, vh, fill)
        val matrix = Matrix()
        matrix.setScale(sx, sy, viewW / 2f, viewH / 2f)
        textureView.setTransform(matrix)
        AirPlayLogger.i("StreamFit: view=${viewW.toInt()}x${viewH.toInt()} video=${vw.toInt()}x${vh.toInt()} " +
            "mode=${if (fill) "fill" else "fit"}${if (userFill != null) "(user)" else ""} sx=$sx sy=$sy")
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
