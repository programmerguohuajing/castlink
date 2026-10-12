package com.castlink.receiver.legacy

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * LegacyNetUtil — API 19 安全的网络工具。
 * 不用 NetworkCapabilities（API 21+），只用 WifiManager（API 1+）。
 */
object LegacyNetUtil {

    fun getLocalIp(context: Context): String {
        // 优先走 WifiManager（最可靠）
        try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wm?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return "%d.%d.%d.%d".format(
                    ipInt and 0xFF,
                    (ipInt shr 8) and 0xFF,
                    (ipInt shr 16) and 0xFF,
                    (ipInt shr 24) and 0xFF
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "WifiManager ip failed", e)
        }
        // 回退：枚举网卡
        try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()?.forEach { nif ->
                nif.inetAddresses?.asSequence()?.forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NetworkInterface enum failed", e)
        }
        return ""
    }

    fun isWifiConnected(context: Context): Boolean {
        return try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.connectionInfo?.networkId != -1
        } catch (e: Exception) {
            false
        }
    }

    private const val TAG = "LegacyNetUtil"
}
