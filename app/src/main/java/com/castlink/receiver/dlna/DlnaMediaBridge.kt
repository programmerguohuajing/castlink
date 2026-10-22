package com.castlink.receiver.dlna

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.castlink.receiver.dlna.util.DlnaLog
import com.castlink.receiver.dlna.service.DidlInfo
import com.castlink.receiver.dlna.service.DlnaControl
import com.castlink.receiver.dlna.service.PlaybackSnapshot
import com.castlink.receiver.dlna.service.TransportState
import com.castlink.receiver.dlna.ui.DlnaPlayerActivity

/**
 * DlnaMediaBridge — SOAP 控制层与 ExoPlayer 播放页的双向中继（文档 §二 架构图 BridgeLayer）。
 *
 * - 实现 [DlnaControl]，供 AvTransportService / RenderingControlService 调用
 * - 播放页（DlnaPlayerActivity）实现 [DlnaPlayerController] 并在此注册，
 *   通过 onPlayer* 回调上报状态/进度
 * - SetAVTransportURI 到达时若播放页未存活，负责拉起它
 *
 * 线程：SOAP 在 DlnaHttpServer 的 worker 线程回调；播放页在主线程回调；
 * 内部状态用 @Volatile + 细锁保护。
 */
interface DlnaPlayerController {
    fun openMedia(uri: String, title: String)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    /** 远端 Stop / 返回键：停止并退出播放页 */
    fun stopAndFinish()
}

object DlnaMediaBridge : DlnaControl {

    private const val TAG = "DlnaBridge"

    @Volatile private var appContext: Context? = null
    @Volatile private var controller: DlnaPlayerController? = null

    @Volatile private var state: TransportState = TransportState.NO_MEDIA_PRESENT
    @Volatile private var trackUri: String = ""
    @Volatile private var trackMetaXml: String = ""
    @Volatile private var trackTitle: String = ""
    @Volatile private var durationMs: Long = 0
    @Volatile private var positionMs: Long = 0

    /** 上次尝试拉起播放页的时间（防抖 + 供 StandbyActivity 补拉） */
    @Volatile private var lastLaunchAttemptMs: Long = 0

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 把控制器调用切到主线程执行。SOAP 回调在 dlna-http-worker 线程，而 ExoPlayer
     * 绑定创建线程（主线程）、View 只能主线程触摸——此前直调会抛 wrong-thread /
     * CalledFromWrongThread 异常，SOAP 层捕获后回 501，爱奇艺遂把设备判为离线、
     * 反复重发 SetAVTransportURI。桥内状态机在调用前已先行更新，SOAP 应答不依赖
     * 播放器执行结果，故异步投递不改变协议语义。
     */
    private fun dispatchToPlayer(block: (DlnaPlayerController) -> Unit) {
        val c = controller ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block(c)
        } else {
            mainHandler.post { if (controller === c) block(c) }
        }
    }

    // ---------- DlnaControl：SOAP → 播放器 ----------

    override fun setUri(uri: String, meta: DidlInfo, rawDidlXml: String) {
        trackUri = uri
        trackMetaXml = rawDidlXml
        trackTitle = meta.title.ifEmpty { uri.substringAfterLast('/').substringBefore('?') }
        durationMs = meta.durationMs
        positionMs = 0
        // 文档 §3.3.1：收到合法 URL 立即 TRANSITIONING，严禁回 STOPPED
        state = TransportState.TRANSITIONING
        DlnaLog.i(TAG, "setUri: $uri title=$trackTitle")
        if (controller != null) {
            dispatchToPlayer { it.openMedia(uri, trackTitle) }
        } else {
            launchPlayer()
        }
    }

    override fun play() {
        if (controller == null) {
            if (trackUri.isNotEmpty()) {
                state = TransportState.TRANSITIONING
                launchPlayer()
            }
            return
        }
        if (state == TransportState.PAUSED_PLAYBACK || state == TransportState.STOPPED) {
            state = TransportState.TRANSITIONING
        }
        dispatchToPlayer { it.play() }
    }

    override fun pause() {
        if (state == TransportState.PLAYING) state = TransportState.PAUSED_PLAYBACK
        dispatchToPlayer { it.pause() }
    }

    override fun stop() {
        state = TransportState.STOPPED
        positionMs = 0
        dispatchToPlayer { it.stopAndFinish() }
    }

    override fun seekTo(positionMs: Long) {
        this.positionMs = positionMs.coerceAtLeast(0)
        if (controller == null) {
            if (trackUri.isNotEmpty()) launchPlayer()
            return
        }
        val target = this.positionMs
        dispatchToPlayer { it.seekTo(target) }
    }

    override fun snapshot(): PlaybackSnapshot = PlaybackSnapshot(
        state = state,
        positionMs = positionMs.coerceAtLeast(0),
        durationMs = durationMs.coerceAtLeast(0),
        trackUri = trackUri,
        trackMetaData = trackMetaXml,
        trackTitle = trackTitle,
    )

    override fun currentTransportActions(): String = when (state) {
        TransportState.STOPPED -> "Play"
        TransportState.PLAYING -> "Pause,Stop,Seek,Next,Previous"
        TransportState.PAUSED_PLAYBACK -> "Play,Stop,Seek,Next,Previous"
        // TRANSITIONING / NO_MEDIA_PRESENT：只允许 Stop（或无动作）
        else -> "Stop"
    }

    // ---------- DlnaControl：音量（映射系统媒体音量） ----------

    private fun audioManager(): AudioManager? =
        appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun setVolumePercent(percent: Int) {
        val am = audioManager() ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        am.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (percent.coerceIn(0, 100) * max / 100).coerceIn(0, max),
            0,
        )
    }

    override fun getVolumePercent(): Int {
        val am = audioManager() ?: return 50
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max).coerceIn(0, 100)
    }

    override fun setMuted(muted: Boolean) {
        val am = audioManager() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0,
            )
        } else {
            @Suppress("DEPRECATION")
            am.setStreamMute(AudioManager.STREAM_MUSIC, muted)
        }
    }

    override fun isMuted(): Boolean {
        val am = audioManager() ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.isStreamMute(AudioManager.STREAM_MUSIC)
        } else {
            getVolumePercent() == 0
        }
    }

    // ---------- 播放页 → 桥（状态/进度上报，主线程） ----------

    /** 播放页创建时注册；返回待播媒体（uri/title），无则 null */
    fun attachController(c: DlnaPlayerController): Pair<String, String>? {
        controller = c
        return if (trackUri.isNotEmpty() &&
            (state == TransportState.TRANSITIONING || state == TransportState.PLAYING ||
                state == TransportState.PAUSED_PLAYBACK)
        ) {
            trackUri to trackTitle
        } else null
    }

    fun detachController(c: DlnaPlayerController) {
        if (controller === c) controller = null
    }

    /** ExoPlayer STATE_BUFFERING：DMR 保持 TRANSITIONING（文档 §3.4.3） */
    fun onPlayerBuffering() {
        if (state == TransportState.PLAYING) state = TransportState.TRANSITIONING
    }

    /** ExoPlayer STATE_READY */
    fun onPlayerReady(playing: Boolean, duration: Long) {
        if (duration > 0) durationMs = duration
        state = if (playing) TransportState.PLAYING else TransportState.PAUSED_PLAYBACK
    }

    /** 播放页定时上报（约 500ms） */
    fun onPositionUpdate(position: Long, duration: Long) {
        positionMs = position.coerceAtLeast(0)
        if (duration > 0) durationMs = duration
    }

    /** ExoPlayer STATE_ENDED：DMR → STOPPED（文档 §3.4.3） */
    fun onPlayerEnded() {
        state = TransportState.STOPPED
        positionMs = durationMs
    }

    fun onPlayerError(msg: String) {
        DlnaLog.w(TAG, "player error: $msg")
        state = TransportState.STOPPED
    }

    // ---------- 播放页拉起 ----------

    /**
     * 是否需要播放页但当前没活着（供 StandbyActivity.onResume 补拉：
     * 后台启动 Activity 受限时，回到前台的可⻅ Activity 来拉最稳）。
     */
    fun needsPlayer(): Boolean {
        if (controller != null) return false
        return state == TransportState.TRANSITIONING ||
            state == TransportState.PLAYING ||
            state == TransportState.PAUSED_PLAYBACK
    }

    fun launchPlayer() {
        val ctx = appContext ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastLaunchAttemptMs < 1500) return // 防抖
        lastLaunchAttemptMs = now
        runCatching {
            val intent = Intent(ctx, DlnaPlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            DlnaLog.i(TAG, "DlnaPlayerActivity launch requested (foreground=${isAppForeground(ctx)})")
        }.onFailure { e ->
            DlnaLog.w(TAG, "launchPlayer failed: ${e.message}")
        }
    }

    private fun isAppForeground(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val me = Process.myPid()
            am.runningAppProcesses?.any {
                it.pid == me && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } == true
        } catch (e: Exception) {
            false
        }
    }
}
