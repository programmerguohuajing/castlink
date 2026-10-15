package com.castlink.receiver.dlna.service

import com.castlink.receiver.dlna.util.DlnaTime
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DIDL-Lite 元数据解析（纯 Kotlin/Java，无 Android 依赖，可 JVM 单测）。
 *
 * 文档 §3.3.3：从 SetAVTransportURI 的 CurrentURIMetaData 中提取
 * `<dc:title>`（片名）、`<upnp:albumArtURI>`（海报）、`<res duration>`（预设片长），
 * 送 OSD 浮层展示。
 */
data class DidlInfo(
    val title: String = "",
    val albumArtUri: String = "",
    /** res@duration 解析出的毫秒数；无则为 0 */
    val durationMs: Long = 0,
)

object DidlParser {

    fun parse(didlXml: String?): DidlInfo {
        if (didlXml.isNullOrBlank()) return DidlInfo()
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            // XXE 防护：纯本地解析，禁用外部实体
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            val doc = factory.newDocumentBuilder()
                .parse(ByteArrayInputStream(didlXml.toByteArray(Charsets.UTF_8)))
            val root = doc.documentElement ?: return DidlInfo()

            fun firstText(tagLocalName: String): String {
                val nodes = root.getElementsByTagName("*")
                for (i in 0 until nodes.length) {
                    val el = nodes.item(i) as? Element ?: continue
                    val local = el.tagName.substringAfter(':')
                    if (local.equals(tagLocalName, ignoreCase = true)) {
                        return el.textContent?.trim().orEmpty()
                    }
                }
                return ""
            }

            var durationMs = 0L
            val nodes = root.getElementsByTagName("*")
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                if (el.tagName.substringAfter(':').equals("res", ignoreCase = true)) {
                    durationMs = DlnaTime.parseDidlDuration(el.getAttribute("duration"))
                    if (durationMs > 0) break
                }
            }

            DidlInfo(
                title = firstText("title"),
                albumArtUri = firstText("albumArtURI"),
                durationMs = durationMs,
            )
        } catch (e: Exception) {
            DidlInfo()
        }
    }
}
