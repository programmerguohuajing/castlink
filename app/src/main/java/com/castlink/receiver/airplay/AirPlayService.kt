package com.castlink.receiver.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.castlink.receiver.R
import com.castlink.receiver.airplay.util.Logger
import com.castlink.receiver.dlna.DlnaServer

/**
 * AirPlayService — CastLink 自有代码（非移植）。前台 Service，持有移植来的
 * [AirPlayReceiver] 实例（mDNS 广播 _airplay._tcp/_raop._tcp + RTSP 7000 + 媒体管线）。
 *
 * 由 MainActivity.onCreate 启动（START_STICKY），进程被杀后系统会尝试重建。
 * pinAuthEnabled=true：配对 PIN + 输错锁定走移植的 RtspHandler/PairingStore 逻辑。
 */
class AirPlayService : Service() {

    private var receiver: AirPlayReceiver? = null
    // v0.1.41：DLNA DMR 引擎与 AirPlay 同一前台服务托管（DLNA-DMR-INTEGRATION-PLAN.md §3.1）
    private var dlnaServer: DlnaServer? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()

        // 与待机页/设置页保持一致：无用户自定义名时用默认名 CastLink-TV，
        // 不再退到系统机型名（新装机上曾广播成 E900V22C，导致 iPhone 选错设备）
        val deviceName = com.castlink.receiver.util.DeviceName.resolve(this)

        // DLNA DMR：SSDP 发现（UDP 1900）+ HTTP 描述/控制（TCP 8758）
        dlnaServer = DlnaServer(this).also { it.start(deviceName) }

        receiver = AirPlayReceiver(
            context = this,
            displayName = deviceName,
            // v0.1.23: 临时关闭 PIN（SRP 握手有 bug 导致 iOS PIN 死循环，修好前先让 iOS 能连上）
            pinAuthEnabled = false,
            audioEnabled = true,
            videoSurfaceProvider = { AirPlayUiBridge.provideSurface() },
            onStateChanged = { state -> AirPlayUiBridge.emitState(state) },
            onSenderNameChanged = { name -> AirPlayUiBridge.emitSenderName(name) },
            onPinChanged = { pin -> AirPlayUiBridge.emitPin(pin) },
            onPhotoReceived = { bytes, _ -> AirPlayUiBridge.emitPhoto(bytes) },
            onPhotoCleared = { AirPlayUiBridge.emitPhoto(ByteArray(0)) }
        ).also { it.start() }
        Logger.i("AirPlayService created (pinAuth=true)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        // DLNA 先下线：ssdp:byebye 宣告，避免手机端残留已消失的设备
        runCatching { dlnaServer?.stop() }
        dlnaServer = null
        runCatching { receiver?.stop() }
        receiver = null
        Logger.i("AirPlayService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.airplay_notif_channel), NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.airplay_notif_title))
            .setContentText(getString(R.string.airplay_notif_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "castlink_airplay"
        private const val NOTIFICATION_ID = 7001
    }
}
