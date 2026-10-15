package com.castlink.receiver.dlna.service

/**
 * UPnP ConnectionManager:1 服务实现（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 * DMR 固定单路 http-get 输入连接。
 */
class ConnectionManagerService {

    companion object {
        const val SERVICE_TYPE = "urn:schemas-upnp-org:service:ConnectionManager:1"
        const val CONTROL_PATH = "/control/cm"

        /** Sink 协议声明：HLS(m3u8) + MP4/常见容器。 */
        const val SINK_PROTOCOL_INFO =
            "http-get:*:video/mp4:*," +
                "http-get:*:video/x-m4v:*," +
                "http-get:*:video/3gpp:*," +
                "http-get:*:video/quicktime:*," +
                "http-get:*:video/x-matroska:*," +
                "http-get:*:application/x-mpegURL:*," +
                "http-get:*:application/vnd.apple.mpegurl:*"
    }

    fun handle(action: String, @Suppress("UNUSED_PARAMETER") args: Map<String, String>): Map<String, String> {
        return when (action) {
            "GetProtocolInfo" -> mapOf(
                "Source" to "",
                "Sink" to SINK_PROTOCOL_INFO,
            )
            "GetCurrentConnectionIDs" -> mapOf("ConnectionIDs" to "0")
            "GetCurrentConnectionInfo" -> mapOf(
                "RcsID" to "0",
                "AVTransportID" to "0",
                "ProtocolInfo" to SINK_PROTOCOL_INFO.substringBefore(','),
                "PeerConnectionManager" to "/",
                "PeerConnectionID" to "-1",
                "Direction" to "Input",
                "Status" to "OK",
            )
            else -> throw UpnpError(401, "Invalid Action")
        }
    }
}
