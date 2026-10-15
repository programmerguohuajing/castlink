package com.castlink.receiver.dlna.service

import com.castlink.receiver.dlna.util.DlnaTime

/**
 * UPnP AVTransport:1 服务实现（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 关键兼容点（文档 §3.3）：
 * 1. SetAVTransportURI 收到合法 URL 后状态立即 TRANSITIONING —— 严禁回 STOPPED，
 *    否则爱奇艺/优酷 100ms 内的 GetTransportInfo 轮询看到 STOPPED 会直接弹"投屏失败"。
 * 2. GetPositionInfo 的 TrackDuration / RelTime / AbsTime 严格 HH:mm:ss 三段格式。
 *
 * 约定：handle 返回 out 参数表；参数非法/动作不支持时抛 [UpnpError]，
 * 由 SoapDispatcher 转成 500 + UPnP 错误信封。
 */
class UpnpError(val code: Int, message: String) : Exception(message)

class AvTransportService(private val control: DlnaControl) {

    companion object {
        const val SERVICE_TYPE = "urn:schemas-upnp-org:service:AVTransport:1"
        const val CONTROL_PATH = "/control/avt"
    }

    fun handle(action: String, args: Map<String, String>): Map<String, String> {
        return when (action) {
            "SetAVTransportURI" -> setAVTransportURI(args)
            // SetNextAVTransportURI 参数名不同（NextURI/NextURIMetaData），映射后复用装载逻辑
            "SetNextAVTransportURI" -> setAVTransportURI(mapOf(
                "InstanceID" to (args["InstanceID"] ?: "0"),
                "CurrentURI" to (args["NextURI"] ?: ""),
                "CurrentURIMetaData" to (args["NextURIMetaData"] ?: ""),
            ))
            "GetMediaInfo" -> getMediaInfo()
            "GetTransportInfo" -> getTransportInfo()
            "GetPositionInfo" -> getPositionInfo()
            "GetDeviceCapabilities" -> getDeviceCapabilities()
            "GetTransportSettings" -> getTransportSettings()
            "Play" -> run { control.play(); emptyMap<String, String>() }
            "Pause" -> run { control.pause(); emptyMap<String, String>() }
            "Stop" -> run { control.stop(); emptyMap<String, String>() }
            "Seek" -> seek(args)
            "Next", "Previous" -> throw UpnpError(701, "Transition not available")
            else -> throw UpnpError(401, "Invalid Action")
        }
    }

    private fun setAVTransportURI(args: Map<String, String>): Map<String, String> {
        val uri = args["CurrentURI"].orEmpty().trim()
        if (uri.isEmpty()) throw UpnpError(402, "Invalid Args: empty CurrentURI")
        // 只接受 http(s) 流媒体；file/rtsp 等直接 402，避免 ExoPlayer 侧无意义报错
        if (!uri.startsWith("http://", ignoreCase = true) &&
            !uri.startsWith("https://", ignoreCase = true)
        ) {
            throw UpnpError(402, "Invalid Args: unsupported URI scheme")
        }
        val rawDidl = args["CurrentURIMetaData"].orEmpty()
        val meta = DidlParser.parse(rawDidl)
        control.setUri(uri, meta, rawDidl)
        return emptyMap()
    }

    private fun getMediaInfo(): Map<String, String> {
        val snap = control.snapshot()
        return mapOf(
            "NrTracks" to "1",
            "MediaDuration" to DlnaTime.format(snap.durationMs),
            "CurrentURI" to snap.trackUri,
            "CurrentURIMetaData" to snap.trackMetaData,
            "NextURI" to "",
            "NextURIMetaData" to "",
            "PlayMedium" to "NETWORK",
            "RecordMedium" to "NOT_IMPLEMENTED",
            "WriteStatus" to "NOT_IMPLEMENTED",
        )
    }

    private fun getTransportInfo(): Map<String, String> {
        val snap = control.snapshot()
        return mapOf(
            "CurrentTransportState" to snap.state.upnp,
            "CurrentTransportStatus" to "OK",
            "CurrentSpeed" to "1",
        )
    }

    private fun getPositionInfo(): Map<String, String> {
        val snap = control.snapshot()
        val relTime = DlnaTime.format(snap.positionMs)
        return mapOf(
            "Track" to "1",
            "TrackDuration" to DlnaTime.format(snap.durationMs),
            "TrackMetaData" to snap.trackMetaData,
            "TrackURI" to snap.trackUri,
            "RelTime" to relTime,
            // AbsTime：单轨场景与 RelTime 一致（严格三段格式）
            "AbsTime" to relTime,
            "RelCount" to "2147483647",
            "AbsCount" to "2147483647",
        )
    }

    private fun getDeviceCapabilities(): Map<String, String> = mapOf(
        "PlayMedia" to "NETWORK",
        "RecMedia" to "NOT_IMPLEMENTED",
        "RecQualityModes" to "NOT_IMPLEMENTED",
    )

    private fun getTransportSettings(): Map<String, String> = mapOf(
        "PlayMode" to "NORMAL",
        "RecQualityMode" to "NOT_IMPLEMENTED",
    )

    private fun seek(args: Map<String, String>): Map<String, String> {
        val unit = args["Unit"].orEmpty().trim()
        val target = args["Target"].orEmpty().trim()
        when {
            unit.equals("REL_TIME", ignoreCase = true) -> {
                val ms = DlnaTime.parse(target)
                if (ms < 0) throw UpnpError(402, "Invalid Args: bad REL_TIME target")
                control.seekTo(ms)
            }
            // TRACK_NR：单轨，只接受 "1"
            unit.equals("TRACK_NR", ignoreCase = true) -> {
                if (target != "1") throw UpnpError(402, "Invalid Args: only track 1")
            }
            else -> throw UpnpError(402, "Invalid Args: unsupported seek unit")
        }
        return emptyMap()
    }
}
