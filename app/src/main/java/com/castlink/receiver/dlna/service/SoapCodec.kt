package com.castlink.receiver.dlna.service

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SOAP 1.1 信封编解码（纯 Kotlin/Java，无 Android 依赖，可 JVM 单测）。
 *
 * UPnP 控制走 SOAP over HTTP POST：请求头 SOAPAction 形如
 * `"urn:schemas-upnp-org:service:AVTransport:1#Play"`（两侧可能带引号），
 * Body 为 `<u:Play xmlns:u="...">` 参数 XML。
 */
object SoapCodec {

    /** 从 SOAPAction 头提取 action 名：去引号、取 '#' 后。 */
    fun actionFromHeader(soapAction: String?): String {
        if (soapAction.isNullOrBlank()) return ""
        return soapAction.trim().trim('"').substringAfter('#', "")
    }

    /**
     * 从 SOAP Body XML 提取某 action 的参数（按标签本地名匹配，忽略命名空间前缀）。
     * 参数名按原样（首字母大写，如 InstanceID / CurrentURI）返回。
     */
    fun parseArgs(bodyXml: String): Map<String, String> {
        if (bodyXml.isBlank()) return emptyMap()
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            val doc = factory.newDocumentBuilder()
                .parse(ByteArrayInputStream(bodyXml.toByteArray(Charsets.UTF_8)))
            val result = LinkedHashMap<String, String>()
            // action 元素是 Body 的第一个元素子节点
            val body = findElement(doc.documentElement, "Body") ?: return emptyMap()
            val actionEl = firstChildElement(body) ?: return emptyMap()
            var child = actionEl.firstChild
            while (child != null) {
                if (child is Element) {
                    val name = child.tagName.substringAfter(':')
                    // DIDL-Lite 参数本身是 XML，转义后塞进文本即可；这里取原始文本
                    result[name] = child.textContent?.trim().orEmpty()
                }
                child = child.nextSibling
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** 构造成功响应信封。outArgs 为空时返回空 action 元素。 */
    fun successEnvelope(serviceType: String, action: String, outArgs: Map<String, String>): String {
        val argsXml = outArgs.entries.joinToString("") { (k, v) ->
            "<$k>${escapeXml(v)}</$k>"
        }
        return """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            "<s:Body>" +
            """<u:${action}Response xmlns:u="$serviceType">$argsXml</u:${action}Response>""" +
            "</s:Body></s:Envelope>"
    }

    /** 构造 UPnP 错误信封（401 Invalid Action / 402 Invalid Args / 501 Action Failed 等）。 */
    fun faultEnvelope(errorCode: Int, errorDescription: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            "<s:Body><s:Fault>" +
            "<faultcode>s:Client</faultcode>" +
            "<faultstring>UPnPError</faultstring>" +
            "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
            "<errorCode>$errorCode</errorCode>" +
            "<errorDescription>${escapeXml(errorDescription)}</errorDescription>" +
            "</UPnPError></detail>" +
            "</s:Fault></s:Body></s:Envelope>"
    }

    private fun findElement(root: Element?, localName: String): Element? {
        if (root == null) return null
        val nodes = root.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            if (el.tagName.substringAfter(':').equals(localName, ignoreCase = true)) return el
        }
        return null
    }

    private fun firstChildElement(parent: Element): Element? {
        var child = parent.firstChild
        while (child != null) {
            if (child is Element) return child
            child = child.nextSibling
        }
        return null
    }

    fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
