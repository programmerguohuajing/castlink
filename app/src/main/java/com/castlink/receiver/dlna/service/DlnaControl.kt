package com.castlink.receiver.dlna.service

/**
 * SOAP 控制层与播放器之间的契约（纯接口，无 Android 依赖，可 JVM 单测）。
 *
 * 生产实现：[com.castlink.receiver.dlna.DlnaMediaBridge]（桥接 ExoPlayer 播放页）；
 * 单测：FakeDlnaControl。
 */
interface DlnaControl {

    /** SetAVTransportURI：装载媒体，状态立即转 TRANSITIONING（文档 §3.3.1）。 */
    fun setUri(uri: String, meta: DidlInfo, rawDidlXml: String)

    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)

    /** 供 GetTransportInfo / GetPositionInfo / GetMediaInfo 轮询的快照。 */
    fun snapshot(): PlaybackSnapshot

    // ---- RenderingControl（映射系统媒体音量） ----
    fun setVolumePercent(percent: Int)
    fun getVolumePercent(): Int
    fun setMuted(muted: Boolean)
    fun isMuted(): Boolean
}

/** UPnP AVTransport TransportState（文档 §3.3.1 状态机）。 */
enum class TransportState(val upnp: String) {
    NO_MEDIA_PRESENT("NO_MEDIA_PRESENT"),
    STOPPED("STOPPED"),
    TRANSITIONING("TRANSITIONING"),
    PLAYING("PLAYING"),
    PAUSED_PLAYBACK("PAUSED_PLAYBACK"),
}

/** 播放快照：AvTransportService 据此构造各查询响应。 */
data class PlaybackSnapshot(
    val state: TransportState = TransportState.NO_MEDIA_PRESENT,
    /** 当前播放位置毫秒；未知为 0 */
    val positionMs: Long = 0,
    /** 媒体总时长毫秒；未知为 0 */
    val durationMs: Long = 0,
    val trackUri: String = "",
    /** 原始 DIDL-Lite XML（GetPositionInfo / GetMediaInfo 回显用） */
    val trackMetaData: String = "",
    val trackTitle: String = "",
)
