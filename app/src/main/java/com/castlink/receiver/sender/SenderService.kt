package com.castlink.receiver.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.castlink.receiver.R
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 发送端前台 Service：
 * 1. 信令：TCP 连接收端 8755，走 HELLO / PIN / BYE 行协议（[SignalingClient]）；
 * 2. 采集：MediaProjection → VirtualDisplay(1280x720) → MediaCodec H.264 编码器
 *    （Surface 输入，2Mbps，30fps，关键帧间隔 2s；启动时强制 IDR 并在 IDR 前补 SPS/PPS）；
 * 3. 传输：编码输出按 Annex-B 切 NAL → [RtpPacketizer] 打包（单 NAL / FU-A）
 *    → UDP 发往接收端 8760（以 HELLO 应答中的 rtp 端口为准）。
 *
 * 启动方式：SenderActivity 在拿到录屏授权后 startForegroundService，
 * extras 携带目标 IP / PIN / 授权结果（resultCode + data Intent）。
 */
class SenderService : Service() {

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v0.1.1 (issue #1)：ACTION_STOP 经通知栏 PendingIntent.getService 发出，
        // 走的是 startService() 而非 startForegroundService()，没有 FGS 超时要求。
        // 必须在 startForegroundCompat() 之前处理：Android 14+（尤其 15/16）上，
        // 以 MEDIA_PROJECTION 类型 startForeground 需要有效的录屏授权，
        // 停止时授权已释放/正在释放 → SecurityException 直接崩溃。
        if (intent?.action == ACTION_STOP) {
            stopCasting()
            stopSelf()
            return START_NOT_STICKY
        }
        // v0.3.9：startForeground 必须在任何 early return 之前调用——否则
        // startForegroundService 后只调 stopSelf() 会触发
        // ForegroundServiceDidNotStartInTimeException（Fold3 实测）。
        startForegroundCompat(buildNotification())
        if (running.get()) return START_STICKY

        val targetIp = intent?.getStringExtra(EXTRA_TARGET_IP).orEmpty()
        val pin = intent?.getStringExtra(EXTRA_PIN).orEmpty()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val projectionData = resolveProjectionData(intent)
        if (targetIp.isEmpty() || pin.isEmpty() || projectionData == null) {
            Log.w(TAG, "missing extras, not starting")
            broadcastStatus(STATUS_ERROR, "missing extras")
            stopSelf()
            return START_NOT_STICKY
        }

        running.set(true)
        val signalingPort = intent?.getIntExtra(EXTRA_SIGNALING_PORT, 8755) ?: 8755
        worker = thread(name = "SenderService", isDaemon = true) {
            try {
                runSession(targetIp, signalingPort, pin, resultCode, projectionData)
            } catch (e: Exception) {
                Log.e(TAG, "cast session died", e)
                broadcastStatus(STATUS_ERROR, e.message.orEmpty())
            } finally {
                running.set(false)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun resolveProjectionData(intent: Intent?): Intent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }
    }

    private fun stopCasting() {
        running.set(false)
        try {
            worker?.join(3000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        worker = null
        broadcastStatus(STATUS_STOPPED)
        stopSelf()
    }

    override fun onDestroy() {
        running.set(false)
        try {
            worker?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- 会话主流程 ----------------

    private fun runSession(
        targetIp: String,
        signalingPort: Int,
        pin: String,
        resultCode: Int,
        projectionData: Intent
    ) {
        broadcastStatus(STATUS_CONNECTING, targetIp)

        // 1) 信令握手
        val signaling = SignalingClient(targetIp, signalingPort)
        val rtpPort: Int
        try {
            signaling.connect(5000)
            val hello = signaling.hello("castlink-sender android/${Build.VERSION.SDK_INT}")
            rtpPort = hello.rtpPort
            Log.i(TAG, "hello ok: tier=${hello.tier} rtp=$rtpPort")
            when (val pinResult = signaling.pin(pin)) {
                is PinResult.Ok -> { /* 继续投屏 */ }
                is PinResult.Fail -> {
                    Log.w(TAG, "PIN rejected")
                    broadcastStatus(STATUS_PIN_FAILED)
                    signaling.bye()
                    return
                }
                is PinResult.Locked -> {
                    Log.w(TAG, "PIN locked, remaining=${pinResult.remainingSeconds}s")
                    broadcastStatus(STATUS_PIN_FAILED, "locked:${pinResult.remainingSeconds}")
                    signaling.bye()
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "signaling failed", e)
            broadcastStatus(STATUS_ERROR, e.message.orEmpty())
            runCatching { signaling.close() }
            return
        }

        // 2) MediaProjection
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(resultCode, projectionData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            broadcastStatus(STATUS_ERROR, e.message.orEmpty())
            runCatching { signaling.bye() }
            runCatching { signaling.close() }
            return
        }

        // 3) H.264 编码器（Surface 输入）
        val encoder: MediaCodec
        val inputSurface: android.view.Surface
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()
            // 启动时强制 IDR，保证接收端能尽快解码出第一帧
            val reqSync = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            runCatching { encoder.setParameters(reqSync) }
        } catch (e: Exception) {
            Log.e(TAG, "encoder setup failed", e)
            broadcastStatus(STATUS_ERROR, e.message.orEmpty())
            runCatching { projection.stop() }
            runCatching { signaling.bye() }
            runCatching { signaling.close() }
            return
        }

        // 4) VirtualDisplay 绑定编码 Surface
        // Android 14+ 强制要求先 registerCallback，否则抛
        // "Must register a callback before starting capture"
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by system")
                runCatching { stopSelf() }
            }
        }
        projection.registerCallback(projectionCallback, android.os.Handler(mainLooper))
        val virtualDisplay = try {
            projection.createVirtualDisplay(
                "CastLinkSender",
                WIDTH, HEIGHT,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            broadcastStatus(STATUS_ERROR, e.message.orEmpty())
            releaseEncoder(encoder, inputSurface)
            runCatching { projection.stop() }
            runCatching { signaling.bye() }
            runCatching { signaling.close() }
            return
        }

        broadcastStatus(STATUS_STARTED, targetIp)
        Log.i(TAG, "casting to $targetIp:$rtpPort")

        // 5) 编码 → RTP 发送循环
        val packetizer = RtpPacketizer()
        val rtpSocket = DatagramSocket()
        val target = InetSocketAddress(targetIp, rtpPort)
        val spsPps = mutableListOf<ByteArray>()
        try {
            drainAndSend(encoder, packetizer, rtpSocket, target, spsPps)
        } finally {
            runCatching { rtpSocket.close() }
            runCatching { virtualDisplay.release() }
            releaseEncoder(encoder, inputSurface)
            runCatching { projection.stop() }
            runCatching { signaling.bye() }
            runCatching { signaling.close() }
            broadcastStatus(STATUS_STOPPED)
            Log.i(TAG, "cast session ended")
        }
    }

    /**
     * 取编码器输出：Annex-B 切 NAL，IDR 前补 SPS/PPS，RTP 打包后 UDP 发送。
     * SPS/PPS 来源：编码器的 BUFFER_FLAG_CODEC_CONFIG 输出，或
     * INFO_OUTPUT_FORMAT_CHANGED 时的 csd-0 / csd-1。
     */
    private fun drainAndSend(
        encoder: MediaCodec,
        packetizer: RtpPacketizer,
        rtpSocket: DatagramSocket,
        target: InetSocketAddress,
        spsPps: MutableList<ByteArray>
    ) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val index = try {
                encoder.dequeueOutputBuffer(info, 50_000L)
            } catch (e: Exception) {
                Log.w(TAG, "dequeueOutputBuffer failed", e)
                break
            }
            when {
                index >= 0 -> {
                    try {
                        val outBuf = encoder.getOutputBuffer(index)
                        if (outBuf != null && info.size > 0) {
                            val data = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.get(data)
                            val isConfig =
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (isConfig) {
                                spsPps.clear()
                                spsPps.addAll(packetizer.splitAnnexB(data))
                                Log.i(TAG, "codec config captured: ${spsPps.size} nals")
                            } else {
                                sendFrame(data, info, packetizer, rtpSocket, target, spsPps)
                            }
                        }
                    } finally {
                        runCatching { encoder.releaseOutputBuffer(index, false) }
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // 从输出 format 取 csd-0/csd-1（裸 NAL，无起始码）
                    spsPps.clear()
                    val outFormat = runCatching { encoder.outputFormat }.getOrNull()
                    if (outFormat != null) {
                        for (key in arrayOf("csd-0", "csd-1")) {
                            val bb = runCatching { outFormat.getByteBuffer(key) }.getOrNull()
                            if (bb != null && bb.remaining() > 0) {
                                val raw = ByteArray(bb.remaining())
                                bb.get(raw)
                                spsPps.add(raw)
                            }
                        }
                        Log.i(TAG, "csd from format: ${spsPps.size} nals")
                    }
                }
                // INFO_TRY_AGAIN_LATER：继续轮询
            }
        }
    }

    private fun sendFrame(
        data: ByteArray,
        info: MediaCodec.BufferInfo,
        packetizer: RtpPacketizer,
        rtpSocket: DatagramSocket,
        target: InetSocketAddress,
        spsPps: List<ByteArray>
    ) {
        val nals = packetizer.splitAnnexB(data).toMutableList()
        if (nals.isEmpty()) return
        val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        if (isKeyFrame && spsPps.isNotEmpty()) {
            // IDR 前置 SPS/PPS，保证接收端解码器能初始化
            nals.addAll(0, spsPps)
        }
        // pts(μs) → 90kHz RTP 时间戳
        val rtpTs = ((info.presentationTimeUs * 90L) / 1000L) and 0xFFFFFFFFL
        for (packet in packetizer.packetizeFrame(nals, rtpTs)) {
            try {
                rtpSocket.send(DatagramPacket(packet, packet.size, target))
            } catch (e: Exception) {
                Log.w(TAG, "rtp send failed", e)
                return
            }
        }
    }

    private fun releaseEncoder(encoder: MediaCodec, inputSurface: android.view.Surface) {
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        runCatching { inputSurface.release() }
    }

    // ---------------- 前台服务 / 通知 ----------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sender_notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION") // Notification.Builder.addAction(int, …)：无 AndroidX 下的兼容写法
    private fun buildNotification(): Notification {        val stopIntent = Intent(this, SenderService::class.java).setAction(ACTION_STOP)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val stopPending = PendingIntent.getService(this, 0, stopIntent, pendingFlags)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.sender_notif_title))
            .setContentText(getString(R.string.sender_notif_text))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.sender_stop),
                stopPending
            )
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------------- 状态广播 ----------------

    private fun broadcastStatus(status: String, detail: String = "") {
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_DETAIL, detail)
        }
        sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "SenderService"

        const val EXTRA_TARGET_IP = "extra_target_ip"
        const val EXTRA_SIGNALING_PORT = "extra_signaling_port"
        const val EXTRA_PIN = "extra_pin"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"

        const val ACTION_STOP = "com.castlink.receiver.sender.action.STOP"
        const val ACTION_STATUS = "com.castlink.receiver.sender.action.STATUS"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_DETAIL = "extra_detail"

        const val STATUS_CONNECTING = "connecting"
        const val STATUS_STARTED = "started"
        const val STATUS_PIN_FAILED = "pin_failed"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_ERROR = "error"

        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "castlink_sender"

        private const val WIDTH = 1280
        private const val HEIGHT = 720
        private const val BITRATE = 1_500_000
        private const val FRAME_RATE = 25
        private const val I_FRAME_INTERVAL_SEC = 2
    }
}
