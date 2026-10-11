package com.castlink.receiver.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.NetworkInterface

object NetUtil {

    /**
     * 遍历 NetworkInterface，优先取 wlan0/eth0 的 IPv4 地址；取不到则回退到任意非回环 IPv4。
     */
    fun getLocalIp(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return ""
            val names = mutableListOf<java.net.NetworkInterface>()
            while (ifaces.hasMoreElements()) {
                names.add(ifaces.nextElement())
            }
            // 先找 wlan0 / eth0
            for (iface in names) {
                val name = iface.name ?: continue
                if (name != "wlan0" && name != "eth0") continue
                val ip = firstIpv4(iface)
                if (ip.isNotEmpty()) return ip
            }
            // 回退：任意非回环 IPv4
            for (iface in names) {
                val ip = firstIpv4(iface)
                if (ip.isNotEmpty()) return ip
            }
        } catch (e: Exception) {
            // 忽略，返回空字符串
        }
        return ""
    }

    private fun firstIpv4(iface: java.net.NetworkInterface): String {
        return try {
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr.isLoopbackAddress) continue
                val host = addr.hostAddress ?: continue
                if (host.contains(":")) continue // 跳过 IPv6
                return host
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 取当前 Wi-Fi 连接的 SSID（去引号）；取不到或为 unknown 时返回空字符串。
     */
    fun getWifiSsid(context: Context): String {
        return try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo ?: return ""
            var ssid = info.ssid ?: return ""
            if (ssid.length > 1 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length - 1)
            }
            if (ssid == "<unknown ssid>") "" else ssid
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * v0.3.5：Wi-Fi 传输是否连通（ConnectivityManager，不依赖 SSID）。
     * Android 10+ 无定位权限时 getWifiSsid() 恒返回 ""，此前被误判为"未连接"。
     * 需 ACCESS_NETWORK_STATE（普通权限，无需运行时申请）。
     */
    fun isWifiConnected(context: Context): Boolean {
        return try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            false
        }
    }
}
