package com.castlink.receiver.legacy

import android.app.Application
import android.content.Context
import android.util.Log
import com.castlink.receiver.discovery.NsdHelper
import com.castlink.receiver.protocol.FilePushServer
import com.castlink.receiver.protocol.SignalingServer
import java.io.File

/**
 * LegacyApp — CastLink Legacy（API 19+）纯接收器。
 *
 * 启动时拉起：信令服务（8755）+ 文件推送服务（8756）+ mDNS 注册（_castlink._tcp）。
 * 不含 AirPlay、不含发送端、不含在线更新。
 */
class LegacyApp : Application() {

    var signalingServer: SignalingServer? = null
        private set
    var filePushServer: FilePushServer? = null
        private set

    private val nsdHelper = NsdHelper()

    /** 当前 PIN（LegacyStandbyActivity 负责生成/轮换）。 */
    @Volatile var pin: String = "0000"

    /** 设备名。 */
    @Volatile var deviceName: String = "CastLink-TV"

    override fun onCreate() {
        super.onCreate()
        // 崩溃日志写到外部存储，方便 4.4 设备上抓取（adb 不便时）
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val logFile = File(getExternalFilesDir(null), "legacy_crash.log")
                logFile.writeText("thread=${t.name}\n${Log.getStackTraceString(e)}")
            } catch (_: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        deviceName = prefs.getString(KEY_DEVICE_NAME, "CastLink-TV") ?: "CastLink-TV"
        Log.i(TAG, "LegacyApp starting (device=$deviceName)")
        startServices()
    }

    private fun startServices() {
        // 信令：PIN 校验走 pinProvider 直接比对（Legacy 不做输错锁定，保持简单）
        signalingServer = SignalingServer(8755, { pin }).also { it.start() }
        // 文件推送
        val dir = FilePushServer.dirFor(cacheDir).also { it.mkdirs() }
        filePushServer = FilePushServer(
            FilePushServer.DEFAULT_PORT,
            dir,
            onFileReceived = { f -> Log.i(TAG, "file received: ${f.name}") },
            log = { Log.i(TAG, it) },
            pinProvider = { pin },
        ).also { it.start() }
        // mDNS 注册，供发送端发现
        nsdHelper.registerAll(this, deviceName, pin)
        Log.i(TAG, "Legacy services started")
    }

    override fun onTerminate() {
        signalingServer?.stop()
        filePushServer?.stop()
        super.onTerminate()
    }

    fun saveDeviceName(name: String) {
        deviceName = name
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
    }

    private lateinit var prefs: android.content.SharedPreferences

    companion object {
        private const val TAG = "LegacyApp"
        private const val PREFS = "castlink_legacy"
        private const val KEY_DEVICE_NAME = "device_name"
    }
}
