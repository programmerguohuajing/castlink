package com.castlink.receiver.ui

import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.castlink.receiver.R
import com.castlink.receiver.protocol.FilePushServer
import com.castlink.receiver.util.FileAction
import java.io.File

/**
 * PlayerActivity — 文件投放（基础版）的本地播放器。
 *
 * - 无 EXTRA_PATH：列出文件投放目录（cache/filepush）收到的文件，点击播放；
 * - 有 EXTRA_PATH：直接播放该文件；
 * - 视频（mp4/3gp/webm 等）经 SurfaceView 播放，纯音频（mp3/aac/flac/wav 等）
 *   无画面仅出声；统一走 android.media.MediaPlayer。
 *
 * 支持列表（如实，以 MediaPlayer 原生能力为准）：
 *   可播：MP4(H.264) / 3GP / WebM(VP8·VP9) / MP3 / AAC / FLAC / WAV
 *   不承诺：MKV / HEVC（H.265）—— 部分设备可播，本页不保证。
 */
class PlayerActivity : BaseActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var surfaceView: SurfaceView
    private lateinit var svFileList: ScrollView
    private lateinit var llFileList: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var btnStop: Button

    private var player: MediaPlayer? = null
    private var pendingVideo: File? = null     // surface 就绪前暂存的待播视频
    private var surfaceReady = false

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceReady = true
            pendingVideo?.let { startPlayback(it, holder) }
            pendingVideo = null
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        tvTitle = findViewById(R.id.tv_player_title)
        surfaceView = findViewById(R.id.sv_player)
        svFileList = findViewById(R.id.sv_file_list)
        llFileList = findViewById(R.id.ll_file_list)
        tvStatus = findViewById(R.id.tv_player_status)
        btnStop = findViewById(R.id.btn_player_stop)
        findViewById<Button>(R.id.btn_player_back).setOnClickListener { finish() }
        btnStop.setOnClickListener { stopToList() }

        surfaceView.holder.addCallback(surfaceCallback)

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path != null) {
            val f = File(path)
            if (f.isFile) playFile(f) else showList()
        } else {
            showList()
        }
    }

    override fun onDestroy() {
        releasePlayer()
        surfaceView.holder.removeCallback(surfaceCallback)
        super.onDestroy()
    }

    /** 列表模式：展示已收到的文件。 */
    private fun showList() {
        releasePlayer()
        pendingVideo = null
        surfaceView.visibility = View.GONE
        svFileList.visibility = View.VISIBLE
        btnStop.visibility = View.GONE
        tvTitle.setText(R.string.player_title)
        tvStatus.text = ""
        llFileList.removeAllViews()
        val dir = FilePushServer.dirFor(cacheDir)
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) {
            tvStatus.setText(R.string.player_empty)
            return
        }
        files.forEach { f ->
            val b = Button(this).apply {
                text = "${f.name}  (${f.length() / 1024} KB)"
                // v0.3.5：媒体播放；APK/其他走安装确认或系统查看器
                setOnClickListener {
                    if (FileAction.isMedia(f)) playFile(f) else FileAction.openExternal(this@PlayerActivity, f)
                }
            }
            llFileList.addView(b)
        }
    }

    /** 播放单个文件：视频走 Surface，音频仅出声。 */
    private fun playFile(file: File) {
        tvTitle.text = file.name
        svFileList.visibility = View.GONE
        btnStop.visibility = View.VISIBLE
        if (isVideo(file)) {
            surfaceView.visibility = View.VISIBLE
            tvStatus.setText(R.string.player_buffering)
            val holder = surfaceView.holder
            if (surfaceReady) startPlayback(file, holder) else pendingVideo = file
        } else {
            surfaceView.visibility = View.GONE
            tvStatus.text = getString(R.string.player_audio_playing, file.name)
            startPlayback(file, null)
        }
    }

    private fun startPlayback(file: File, holder: SurfaceHolder?) {
        releasePlayer()
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                if (holder != null) setDisplay(holder)
                setOnPreparedListener {
                    tvStatus.text = ""
                    it.start()
                    Log.i(TAG, "playing ${file.name}")
                }
                setOnCompletionListener {
                    tvStatus.setText(R.string.player_completed)
                    Log.i(TAG, "completed ${file.name}")
                }
                setOnErrorListener { _, what, extra ->
                    tvStatus.setText(R.string.player_error)
                    Log.w(TAG, "play error ${file.name} what=$what extra=$extra")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            tvStatus.setText(R.string.player_error)
            Log.w(TAG, "play failed ${file.name}", e)
        }
    }

    private fun stopToList() {
        showList()
    }

    private fun releasePlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    companion object {
        private const val TAG = "PlayerActivity"

        /** 直接播放指定文件：intent.putExtra(EXTRA_PATH, file.absolutePath)。 */
        const val EXTRA_PATH = "extra_path"

        private val VIDEO_EXTS = setOf(
            "mp4", "m4v", "3gp", "3g2", "webm", "mkv", "mov", "ts", "avi", "flv"
        )

        /** 扩展名判定是否为视频；其余按音频处理（MediaPlayer 无画面也可出声）。 */
        fun isVideo(file: File): Boolean =
            VIDEO_EXTS.contains(file.extension.lowercase())
    }
}
