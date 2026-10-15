package com.castlink.receiver.dlna.server

import com.castlink.receiver.dlna.service.SoapCodec

/**
 * /description.xml 动态生成（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 注册三大标准 UPnP 服务（文档 §3.2）：
 * - AVTransport:1      控制 /control/avt，SCPD /scpd/avt.xml
 * - RenderingControl:1 控制 /control/rc， SCPD /scpd/rc.xml
 * - ConnectionManager:1 控制 /control/cm， SCPD /scpd/cm.xml
 *
 * @param udn 形如 "uuid:xxxxxxxx-xxxx-..." 的持久化设备标识
 */
object DeviceDescription {

    fun build(localIp: String, port: Int, udn: String, friendlyName: String): String {
        val name = SoapCodec.escapeXml(friendlyName.ifBlank { "CastLink-TV" })
        val base = "http://$localIp:$port"
        return """<?xml version="1.0" encoding="utf-8"?>""" +
            """<root xmlns="urn:schemas-upnp-org:device-1-0">""" +
            "<specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<URLBase>$base</URLBase>" +
            "<device>" +
            "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>" +
            "<friendlyName>$name</friendlyName>" +
            "<manufacturer>CastLink</manufacturer>" +
            "<manufacturerURL>https://github.com/programmerguohuajing/castlink</manufacturerURL>" +
            "<modelDescription>CastLink DLNA Digital Media Renderer</modelDescription>" +
            "<modelName>CastLink-TV</modelName>" +
            "<modelNumber>0.1.41</modelNumber>" +
            "<UDN>$udn</UDN>" +
            "<serviceList>" +
            serviceXml(
                "urn:schemas-upnp-org:service:AVTransport:1",
                "urn:upnp-org:serviceId:AVTransport",
                "/control/avt", "/event/avt", "/scpd/avt.xml",
            ) +
            serviceXml(
                "urn:schemas-upnp-org:service:RenderingControl:1",
                "urn:upnp-org:serviceId:RenderingControl",
                "/control/rc", "/event/rc", "/scpd/rc.xml",
            ) +
            serviceXml(
                "urn:schemas-upnp-org:service:ConnectionManager:1",
                "urn:upnp-org:serviceId:ConnectionManager",
                "/control/cm", "/event/cm", "/scpd/cm.xml",
            ) +
            "</serviceList>" +
            "</device></root>"
    }

    private fun serviceXml(
        serviceType: String,
        serviceId: String,
        controlUrl: String,
        eventUrl: String,
        scpdUrl: String,
    ): String = "<service>" +
        "<serviceType>$serviceType</serviceType>" +
        "<serviceId>$serviceId</serviceId>" +
        "<controlURL>$controlUrl</controlURL>" +
        "<eventSubURL>$eventUrl</eventSubURL>" +
        "<SCPDURL>$scpdUrl</SCPDURL>" +
        "</service>"
}
