package com.castlink.receiver.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import com.castlink.receiver.dlna.util.DlnaLog
import com.castlink.receiver.dlna.server.DeviceDescription
import com.castlink.receiver.dlna.server.DlnaHttpServer
import com.castlink.receiver.dlna.server.ScpdDescriptions
import com.castlink.receiver.dlna.service.AvTransportService
import com.castlink.receiver.dlna.service.ConnectionManagerService
import com.castlink.receiver.dlna.service.RenderingControlService
import com.castlink.receiver.dlna.service.SoapDispatcher
import com.castlink.receiver.dlna.ssdp.SsdpServer
import com.castlink.receiver.util.NetUtil
import java.util.UUID

/**
 * DLNA DMR 总控制器（文档 §五 DlnaServer.kt）。
 *
 * 组合 [SsdpServer]（UDP 1900 发现）与 [DlnaHttpServer]（TCP 8758 描述/控制），
 * 由统一前台服务（AirPlayService）托管：onCreate 启动，onDestroy 平滑释放
 * （组播 ssdp:byebye 下线宣告）。
 *
 * 组播锁：文档 §3.2.2 —— TV 盒子休眠时 Wi-Fi 芯片会丢组播包，必须持有
 * WifiManager.MulticastLock（需 CHANGE_WIFI_MULTICAST_STATE，Manifest 已声明）。
 */
class DlnaServer(private val appContext: Context) {

    companion object {
        private const val TAG = "DlnaServer"
        const val HTTP_PORT = 8758
        private const val PREFS = "castlink_prefs"
        private const val KEY_UDN = "dlna_udn"
    }

    /** 持久化 UDN（文档 §3.2.3）：基于 ANDROID_ID 生成，存 prefs，重启不变 */
    val udn: String = loadOrCreateUdn(appContext)

    private var ssdp: SsdpServer? = null
    private var http: DlnaHttpServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start(deviceName: String) {
        if (ssdp != null || http != null) return
        DlnaMediaBridge.init(appContext)

        val dispatcher = SoapDispatcher(
            AvTransportService(DlnaMediaBridge),
            RenderingControlService(DlnaMediaBridge),
            ConnectionManagerService(),
            logger = { DlnaLog.i(TAG, it) },
        )

        http = DlnaHttpServer(
            port = HTTP_PORT,
            descriptionProvider = {
                DeviceDescription.build(
                    NetUtil.getLocalIp().ifEmpty { "127.0.0.1" },
                    HTTP_PORT, udn, deviceName,
                )
            },
            scpdProvider = { ScpdDescriptions.get(it) },
            soapHandler = { path, soapAction, body ->
                dispatcher.dispatch(path, soapAction, body)
            },
            logger = { DlnaLog.i(TAG, it) },
        ).also { it.start() }

        runCatching {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("CastLink_SSDP").apply {
                setReferenceCounted(true)
                acquire()
            }
            DlnaLog.i(TAG, "multicast lock acquired")
        }.onFailure { e ->
            DlnaLog.w(TAG, "multicast lock failed: ${e.message}")
        }

        ssdp = SsdpServer(
            httpPort = HTTP_PORT,
            udn = udn,
            localIpProvider = { NetUtil.getLocalIp() },
            logger = { DlnaLog.i(TAG, it) },
        ).also { it.start() }

        DlnaLog.i(TAG, "started (udn=$udn httpBound=${http?.isBound} ssdpBound=${ssdp?.isBound})")
    }

    fun stop() {
        runCatching { ssdp?.stop() }
        ssdp = null
        runCatching { http?.stop() }
        http = null
        runCatching {
            val lock = multicastLock
            if (lock?.isHeld == true) lock.release()
        }
        multicastLock = null
        DlnaLog.i(TAG, "stopped")
    }

    private fun loadOrCreateUdn(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_UDN, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        val seed = if (androidId.isNotBlank()) "castlink-dlna-$androidId"
        else "castlink-dlna-${UUID.randomUUID()}"
        val udn = "uuid:" + UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()
        prefs.edit().putString(KEY_UDN, udn).apply()
        return udn
    }
}
