package com.castlink.receiver.dlna.ssdp

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * SSDP 报文编解码（纯 Kotlin/Java，无 Android 依赖，可 JVM 单测）。
 *
 * 覆盖 DMR 需要的最小集合：
 * - 解析手机端发来的 M-SEARCH（取 ST / MX）
 * - 构造 200 OK 单播响应（LOCATION 指向上 :8758 的 description.xml）
 * - 构造 ssdp:alive / ssdp:byebye 组播宣告
 */
object SsdpPacket {

    const val MULTICAST_ADDR = "239.255.255.250"
    const val MULTICAST_PORT = 1900

    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val ROOT_DEVICE = "upnp:rootdevice"

    data class MSearch(val st: String, val mx: Int)

    /** 判断该 M-SEARCH 是否与本 DMR 相关（ssdp:all / rootdevice / uuid / MediaRenderer）。 */
    fun isRelevant(st: String, udn: String): Boolean {
        val s = st.trim()
        return s.equals("ssdp:all", ignoreCase = true) ||
            s.equals(ROOT_DEVICE, ignoreCase = true) ||
            s.equals(udn, ignoreCase = true) ||
            s.equals(DEVICE_TYPE, ignoreCase = true)
    }

    /** 解析 M-SEARCH 请求；不是 M-SEARCH 或缺 ST 时返回 null。 */
    fun parseMSearch(data: ByteArray, length: Int): MSearch? {
        return try {
            val text = String(data, 0, length, Charsets.UTF_8)
            val lines = text.split("\r\n")
            if (lines.isEmpty()) return null
            val requestLine = lines[0].trim()
            if (!requestLine.startsWith("M-SEARCH", ignoreCase = true)) return null
            var st: String? = null
            var mx = 1
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                when {
                    name.equals("ST", ignoreCase = true) -> st = value
                    name.equals("MX", ignoreCase = true) -> mx = value.toIntOrNull()?.coerceIn(1, 5) ?: 1
                }
            }
            if (st.isNullOrEmpty()) null else MSearch(st, mx)
        } catch (e: Exception) {
            null
        }
    }

    private fun httpDate(): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        return fmt.format(Date())
    }

    private fun serverHeader(): String = "Linux/5.15 UPnP/1.0 CastLink/0.1.41"

    /**
     * 构造 M-SEARCH 的 200 OK 单播响应。
     * @param st 响应对应的 ST（ssdp:all 时按 NT 分别回多条，调用方循环调用）
     * @param usnSuffix USN 后缀，如 "::upnp:rootdevice"；uuid 本体查询时传 ""
     */
    fun buildOkResponse(
        localIp: String,
        httpPort: Int,
        udn: String,
        st: String,
        usnSuffix: String,
        maxAge: Int = 1800,
    ): String {
        val location = "http://$localIp:$httpPort/description.xml"
        return buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=$maxAge\r\n")
            append("DATE: ${httpDate()}\r\n")
            append("EXT:\r\n")
            append("LOCATION: $location\r\n")
            append("SERVER: ${serverHeader()}\r\n")
            append("ST: $st\r\n")
            append("USN: $udn$usnSuffix\r\n")
            append("\r\n")
        }
    }

    /** ssdp:all 查询的标准三连回包（rootdevice / uuid / device-type）。 */
    fun buildOkResponsesForAll(
        localIp: String,
        httpPort: Int,
        udn: String,
        maxAge: Int = 1800,
    ): List<String> = listOf(
        buildOkResponse(localIp, httpPort, udn, ROOT_DEVICE, "::$ROOT_DEVICE", maxAge),
        buildOkResponse(localIp, httpPort, udn, udn, "", maxAge),
        buildOkResponse(localIp, httpPort, udn, DEVICE_TYPE, "::$DEVICE_TYPE", maxAge),
    )

    /** ssdp:alive 定时保活宣告（组播）。 */
    fun buildNotifyAlive(
        localIp: String,
        httpPort: Int,
        udn: String,
        maxAge: Int = 1800,
    ): List<String> {
        val location = "http://$localIp:$httpPort/description.xml"
        fun one(nt: String, usnSuffix: String) = buildString {
            append("NOTIFY * HTTP/1.1\r\n")
            append("HOST: $MULTICAST_ADDR:$MULTICAST_PORT\r\n")
            append("CACHE-CONTROL: max-age=$maxAge\r\n")
            append("LOCATION: $location\r\n")
            append("NT: $nt\r\n")
            append("NTS: ssdp:alive\r\n")
            append("SERVER: ${serverHeader()}\r\n")
            append("USN: $udn$usnSuffix\r\n")
            append("\r\n")
        }
        return listOf(
            one(ROOT_DEVICE, "::$ROOT_DEVICE"),
            one(udn, ""),
            one(DEVICE_TYPE, "::$DEVICE_TYPE"),
        )
    }

    /** ssdp:byebye 下线宣告（组播，服务停止时发送）。 */
    fun buildNotifyByebye(udn: String): List<String> {
        fun one(nt: String, usnSuffix: String) = buildString {
            append("NOTIFY * HTTP/1.1\r\n")
            append("HOST: $MULTICAST_ADDR:$MULTICAST_PORT\r\n")
            append("NT: $nt\r\n")
            append("NTS: ssdp:byebye\r\n")
            append("USN: $udn$usnSuffix\r\n")
            append("\r\n")
        }
        return listOf(
            one(ROOT_DEVICE, "::$ROOT_DEVICE"),
            one(udn, ""),
            one(DEVICE_TYPE, "::$DEVICE_TYPE"),
        )
    }
}
