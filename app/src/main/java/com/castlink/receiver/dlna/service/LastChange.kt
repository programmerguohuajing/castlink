package com.castlink.receiver.dlna.service

import com.castlink.receiver.dlna.server.EventManager

/**
 * GENA 事件体构建（纯 Kotlin，可 JVM 单测）。
 *
 * AVTransport / RenderingControl 用 LastChange 机制：把变化的变量包成一段
 * `<Event>` XML，再整体转义后作为 `<LastChange>` 元素文本放进 propertyset。
 * ConnectionManager 不走 LastChange，变量以直属 property 发送。
 */
object LastChange {

    /** AVTransport 事件 body：当前状态/URI/可用动作。属性值必须 XML 转义（URI 含 &、DIDL 是整段 XML）。 */
    fun avtBody(snap: PlaybackSnapshot, actions: String): String {
        val esc = EventManager.Companion::escapeXmlAttr
        val inner = buildString {
            append("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\">")
            append("<InstanceID val=\"0\">")
            append("<TransportState val=\"").append(esc(snap.state.upnp)).append("\"/>")
            append("<TransportStatus val=\"OK\"/>")
            append("<AVTransportURI val=\"").append(esc(snap.trackUri)).append("\"/>")
            append("<AVTransportURIMetaData val=\"").append(esc(snap.trackMetaData)).append("\"/>")
            append("<CurrentTrack val=\"1\"/>")
            append("<NumberOfTracks val=\"1\"/>")
            append("<CurrentTrackURI val=\"").append(esc(snap.trackUri)).append("\"/>")
            append("<CurrentTransportActions val=\"").append(esc(actions)).append("\"/>")
            append("</InstanceID></Event>")
        }
        return EventManager.wrapLastChange(inner)
    }

    /** RenderingControl 事件 body：音量（0-100）与静音。 */
    fun rcBody(volumePercent: Int, muted: Boolean): String {
        val inner = buildString {
            append("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\">")
            append("<InstanceID val=\"0\">")
            append("<Volume channel=\"Master\" val=\"").append(volumePercent.coerceIn(0, 100)).append("\"/>")
            append("<Mute channel=\"Master\" val=\"").append(if (muted) "1" else "0").append("\"/>")
            append("</InstanceID></Event>")
        }
        return EventManager.wrapLastChange(inner)
    }

    /** ConnectionManager 事件 body：协议信息为直属 property（无 LastChange）。 */
    fun cmBody(sourceProtocolInfo: String, sinkProtocolInfo: String): String =
        "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
            "<e:property><SourceProtocolInfo>" +
            EventManager.escapeXmlText(sourceProtocolInfo) +
            "</SourceProtocolInfo></e:property>" +
            "<e:property><SinkProtocolInfo>" +
            EventManager.escapeXmlText(sinkProtocolInfo) +
            "</SinkProtocolInfo></e:property>" +
            "<e:property><CurrentConnectionIDs>0</CurrentConnectionIDs></e:property>" +
            "</e:propertyset>"
}
