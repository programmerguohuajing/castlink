package com.castlink.receiver.dlna.ui

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.castlink.receiver.dlna.util.DlnaLog
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.castlink.receiver.R
import com.castlink.receiver.dlna.DlnaMediaBridge
import com.castlink.receiver.dlna.DlnaPlayerController
import com.castlink.receiver.dlna.util.DlnaTime
import com.castlink.receiver.ui.BaseActivity

/**
 * DLNA DMR 全屏播放页（文档 §3.4 模块四：基于 ExoPlayer 的流媒体播放引擎）。
 *
 * - ExoPlayer/Media3 核心：HLS(m3u8) 多码率自适应 + MP4 渐进式
 * - 防盗链：DefaultHttpDataSource 注入 UA/Referer（Referer 按分片域名动态生成）
 * - OSD：片名/进度条/加载圈/错误提示，5 秒无操作自动隐藏
 * - 遥控器：确定=播放/暂停，左右=±10s，快进快退呼出 OSD；返回=停止并回待机
 */
class DlnaPlayerActivity : BaseActivity(), DlnaPlayerController {

    companion object {
        private const val TAG = "DlnaPlayer"
        private const val OSD_HIDE_MS = 5000L
        private const val SEEK_STEP_MS = 10_000L
        private const val POSITION_UPDATE_MS = 500L
    }

    private lateinit var textureView: TextureView
    private lateinit var loading: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var osd: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var seekBar: SeekBar

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var osdVisible = false
    private var currentTitle = ""
    private var scrubbing = false

    private val osdHideRunnable = Runnable { hideOsd() }
    private val positionRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            val pos = p.currentPosition.coerceAtLeast(0)
            val dur = p.duration.takeIf { it > 0 } ?: 0
            DlnaMediaBridge.onPositionUpdate(pos, dur)
            if (osdVisible) updateProgressUi(pos, dur)
            handler.postDelayed(this, POSITION_UPDATE_MS)
        }
    }

    // ---------- 生命周期 ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        setContentView(R.layout.activity_dlna_player)

        textureView = findViewById(R.id.dlna_texture)
        loading = findViewById(R.id.dlna_loading)
        tvError = findViewById(R.id.dlna_error)
        osd = findViewById(R.id.dlna_osd)
        tvTitle = findViewById(R.id.dlna_title)
        tvPos = findViewById(R.id.dlna_pos)
        tvDur = findViewById(R.id.dlna_dur)
        seekBar = findViewById(R.id.dlna_seek)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val p = player ?: return
                val dur = p.duration.takeIf { it > 0 } ?: return
                val target = (dur * progress / 1000).coerceIn(0, dur)
                tvPos.text = DlnaTime.format(target)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                scrubbing = true
                handler.removeCallbacks(osdHideRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                scrubbing = false
                val p = player ?: return
                val dur = p.duration.takeIf { it > 0 } ?: return
                val target = (dur * sb!!.progress / 1000).coerceIn(0, dur)
                p.seekTo(target)
                DlnaMediaBridge.onPositionUpdate(target, dur)
                scheduleOsdHide()
            }
        })

        initPlayer()

        // 注册到桥；取待播媒体（SetAVTransportURI 先到、页后拉起的场景）
        val pending = DlnaMediaBridge.attachController(this)
        if (pending != null) {
            openMedia(pending.first, pending.second)
        } else {
            // 无待播媒体（如用户手动打开）：直接退出，避免空页面挂起
            DlnaLog.w(TAG, "no pending media, finish")
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        DlnaMediaBridge.detachController(this)
        runCatching { player?.release() }
        player = null
        super.onDestroy()
    }

    // ---------- 播放器 ----------

    private fun initPlayer() {
        val dataSourceFactory = RefererDataSourceFactory(
            DefaultHttpDataSource.Factory()
                .setUserAgent(
                    "Mozilla/5.0 (Linux; Android 10; CastLink-DMR) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/108.0.0.0 Mobile Safari/537.36",
                )
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)
                .setDefaultRequestProperties(mapOf("Accept" to "*/*")),
        )
        val p = ExoPlayer.Builder(this).build()
        p.setVideoTextureView(textureView)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        loading.visibility = View.VISIBLE
                        DlnaMediaBridge.onPlayerBuffering()
                    }
                    Player.STATE_READY -> {
                        loading.visibility = View.GONE
                        DlnaMediaBridge.onPlayerReady(p.playWhenReady, p.duration.takeIf { it > 0 } ?: 0)
                    }
                    Player.STATE_ENDED -> {
                        DlnaMediaBridge.onPlayerEnded()
                        finish()
                    }
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // READY + playWhenReady 的权威组合在 onPlaybackStateChanged 处理；
                // 这里补暂停/播放切换时的即时状态（遥控器操作后手机端秒级同步）
                if (p.playbackState == Player.STATE_READY) {
                    DlnaMediaBridge.onPlayerReady(isPlaying, p.duration.takeIf { it > 0 } ?: 0)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = "${error.errorCodeName}: ${error.message}"
                DlnaLog.w(TAG, "player error: $msg")
                loading.visibility = View.GONE
                tvError.text = "播放失败\n$msg"
                tvError.visibility = View.VISIBLE
                showOsd()
                DlnaMediaBridge.onPlayerError(msg)
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                // 等比铺满：TextureView + transform 矫正（竖屏视频不拉伸）
                applyCenterCrop(videoSize.width, videoSize.height)
            }
        })
        player = p
        handler.post(positionRunnable)
    }

    /** 等比 centerCrop：参考 StreamPlayerActivity 的竖屏铺满逻辑 */
    private fun applyCenterCrop(videoW: Int, videoH: Int) {
        if (videoW <= 0 || videoH <= 0) return
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        if (viewW <= 0 || viewH <= 0) return
        val scale = maxOf(viewW / videoW, viewH / videoH)
        val matrix = android.graphics.Matrix()
        val dx = (viewW - videoW * scale) / 2f
        val dy = (viewH - videoH * scale) / 2f
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        textureView.setTransform(matrix)
    }

    private fun isHls(uri: String): Boolean {
        val path = uri.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.contains(".m3u8/")
    }

    // ---------- DlnaPlayerController ----------

    override fun openMedia(uri: String, title: String) {
        val p = player ?: return
        currentTitle = title
        tvTitle.text = title.ifEmpty { uri.substringAfterLast('/').substringBefore('?') }
        tvError.visibility = View.GONE
        loading.visibility = View.VISIBLE
        val mediaSource = if (isHls(uri)) {
            HlsMediaSource.Factory(refererFactory()).createMediaSource(MediaItem.fromUri(uri))
        } else {
            ProgressiveMediaSource.Factory(refererFactory()).createMediaSource(MediaItem.fromUri(uri))
        }
        p.stop()
        p.setMediaSource(mediaSource)
        p.prepare()
        p.play()
        showOsd()
        DlnaLog.i(TAG, "openMedia: $uri (hls=${isHls(uri)})")
    }

    override fun play() {
        player?.play()
        showOsd()
    }

    override fun pause() {
        player?.pause()
        showOsd()
    }

    override fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0))
        showOsd()
    }

    override fun stopAndFinish() {
        runCatching { player?.stop() }
        finish()
    }

    // ---------- OSD ----------

    private fun showOsd() {
        osd.visibility = View.VISIBLE
        osdVisible = true
        val p = player
        if (p != null) updateProgressUi(p.currentPosition.coerceAtLeast(0), p.duration.takeIf { it > 0 } ?: 0)
        scheduleOsdHide()
    }

    private fun hideOsd() {
        osd.visibility = View.GONE
        osdVisible = false
    }

    private fun scheduleOsdHide() {
        handler.removeCallbacks(osdHideRunnable)
        handler.postDelayed(osdHideRunnable, OSD_HIDE_MS)
    }

    private fun updateProgressUi(pos: Long, dur: Long) {
        tvPos.text = DlnaTime.format(pos)
        tvDur.text = DlnaTime.format(dur)
        if (!scrubbing && dur > 0) {
            seekBar.progress = ((pos * 1000 / dur).toInt()).coerceIn(0, 1000)
        }
    }

    // ---------- 遥控器（文档 §3.4.2） ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> {
                val p = player ?: return super.onKeyDown(keyCode, event)
                if (p.isPlaying) {
                    p.pause()
                    DlnaMediaBridge.onPlayerReady(false, p.duration.takeIf { it > 0 } ?: 0)
                } else {
                    p.play()
                    DlnaMediaBridge.onPlayerReady(true, p.duration.takeIf { it > 0 } ?: 0)
                }
                showOsd()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                val p = player ?: return super.onKeyDown(keyCode, event)
                val target = (p.currentPosition - SEEK_STEP_MS).coerceAtLeast(0)
                p.seekTo(target)
                showOsd()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val p = player ?: return super.onKeyDown(keyCode, event)
                val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                val target = (p.currentPosition + SEEK_STEP_MS).coerceAtMost(dur)
                p.seekTo(target)
                showOsd()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                // 返回键：停止投屏，向手机端汇报 STOPPED 并回待机（文档 §3.4.2）
                DlnaMediaBridge.stop()
                finish()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // 上下键仅呼出 OSD（不做音量，避免与系统音量冲突）
                showOsd()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- 防盗链：按分片域名动态注入 Referer（文档 §3.4.1） ----------

    private var cachedFactory: DataSource.Factory? = null

    private fun refererFactory(): DataSource.Factory {
        return cachedFactory ?: RefererDataSourceFactory(
            DefaultHttpDataSource.Factory()
                .setUserAgent(
                    "Mozilla/5.0 (Linux; Android 10; CastLink-DMR) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/108.0.0.0 Mobile Safari/537.36",
                )
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)
                .setDefaultRequestProperties(mapOf("Accept" to "*/*")),
        ).also { cachedFactory = it }
    }

    private class RefererDataSourceFactory(
        private val delegate: DefaultHttpDataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val ds = delegate.createDataSource()
            return object : DataSource by ds {
                override fun open(dataSpec: DataSpec): Long {
                    val inner = ds as? DefaultHttpDataSource
                    val host = dataSpec.uri.host
                    if (inner != null && !host.isNullOrEmpty()) {
                        // 依据分片域名自动补全防盗链 Referer
                        inner.setRequestProperty("Referer", "${dataSpec.uri.scheme}://$host/")
                    }
                    return ds.open(dataSpec)
                }
            }
        }
    }
}
