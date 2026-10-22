package com.castlink.receiver.dlna.server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

/**
 * GENA 事件订阅管理（UPnP Device Architecture §4 / GENA）。
 *
 * 此前 SUBSCRIBE 只回一个假 SID、从不推送事件：事件驱动的控制点（爱奇艺等）
 * 收不到订阅后必须立即发送的「初始事件」与后续 LastChange 状态通知，会一直
 * 停在「投屏连接中」并周期性重发 SetAVTransportURI（电视端表现为循环重播开头）。
 *
 * 本类负责：
 * - 新订阅登记（解析 CALLBACK 的 <url> 列表）→ 应答后立即发初始事件（SEQ=0）
 * - 带 SID 的续订（不重发初始事件）、UNSUBSCRIBE 注销、超时清理
 * - publish(path, lastChangeXml)：向该服务全部订阅者 NOTIFY（SEQ 递增），
 *   发送失败即注销该订阅
 *
 * 初始事件内容由 [initialEventProvider] 按路径提供（订阅那一刻的当前状态）。
 * NOTIFY 用裸 Socket 发送（HttpURLConnection 不允许 NOTIFY 方法）。
 */
class EventManager(
    private val logger: (String) -> Unit = {},
    private val sender: (callbackUrl: String, sid: String, seq: Long, body: String) -> Boolean =
        { url, sid, seq, body -> defaultNotifySend(url, sid, seq, body) },
) {
    private class Subscription(
        val sid: String,
        val path: String,
        val callbacks: List<String>,
        @Volatile var seq: Long,
        @Volatile var expiresAtMs: Long,
    )

    private val lock = Any()
    private val subs = LinkedHashMap<String, Subscription>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dlna-event").apply { isDaemon = true }
    }

    /** 按路径返回订阅初始事件的完整 propertyset body（AVT/RC 为 LastChange 包装，CM 为直属属性）。 */
    @Volatile
    var initialEventProvider: ((path: String) -> String?)? = null

    /**
     * 处理一次 SUBSCRIBE，返回 (SID, 授予秒数)。
     * 带已知 SID 的请求视为续订；否则新建订阅并异步发送初始事件。
     */
    fun subscribe(path: String, headers: Map<String, String>): Pair<String, Int> {
        val timeoutSec = parseTimeout(headers["timeout"])
        val now = System.currentTimeMillis()
        val renewSid = headers["sid"]?.trim()
        if (!renewSid.isNullOrEmpty()) {
            synchronized(lock) {
                val sub = subs[renewSid]
                if (sub != null && sub.path == path) {
                    sub.expiresAtMs = now + timeoutSec * 1000L
                    logger("DLNA SUBSCRIBE renew $path sid=$renewSid")
                    return renewSid to timeoutSec
                }
            }
        }
        val callbacks = parseCallbacks(headers["callback"])
        val sid = "uuid:" + UUID.randomUUID().toString()
        if (callbacks.isEmpty()) {
            logger("DLNA SUBSCRIBE $path without CALLBACK — sid=$sid (no events will be sent)")
            return sid to timeoutSec
        }
        val sub = Subscription(sid, path, callbacks, seq = 0, expiresAtMs = now + timeoutSec * 1000L)
        synchronized(lock) {
            pruneExpiredLocked(now)
            subs[sid] = sub
        }
        logger("DLNA SUBSCRIBE $path sid=$sid cb=${callbacks.firstOrNull()} timeout=${timeoutSec}s")
        // 初始事件：UPnP 要求订阅成功后立即把全部被事件变量的当前值推给订阅方，
        // 许多控制点的会话状态机就卡在这条事件上（收不到则永远「连接中」）。
        val initial = initialEventProvider?.invoke(path)
        if (initial != null) {
            executor.execute { deliver(sub, initial) }
        }
        return sid to timeoutSec
    }

    fun unsubscribe(headers: Map<String, String>) {
        val sid = headers["sid"]?.trim().orEmpty()
        if (sid.isEmpty()) return
        synchronized(lock) { subs.remove(sid) }?.let {
            logger("DLNA UNSUBSCRIBE ${it.path} sid=$sid")
        }
    }

    /** 向某服务（如 /event/avt）的全部有效订阅者推送一条事件（完整 propertyset body）。 */
    fun publish(path: String, propertysetBody: String) {
        val targets: List<Subscription>
        synchronized(lock) {
            pruneExpiredLocked(System.currentTimeMillis())
            targets = subs.values.filter { it.path == path }
        }
        if (targets.isEmpty()) return
        executor.execute {
            for (sub in targets) deliver(sub, propertysetBody)
        }
    }

    /** 当前订阅数（单测/诊断用）。 */
    fun subscriptionCount(): Int = synchronized(lock) { subs.size }

    fun shutdown() {
        synchronized(lock) { subs.clear() }
        executor.shutdownNow()
    }

    private fun deliver(sub: Subscription, payload: String) {
        val seq = sub.seq
        var anyOk = false
        for (cb in sub.callbacks) {
            if (sender(cb, sub.sid, seq, payload)) { anyOk = true; break }
        }
        if (anyOk) {
            sub.seq = seq + 1
        } else {
            synchronized(lock) { subs.remove(sub.sid) }
            logger("DLNA NOTIFY to ${sub.callbacks.firstOrNull()} failed — subscription ${sub.sid} dropped")
        }
    }

    private fun pruneExpiredLocked(now: Long) {
        val it = subs.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.expiresAtMs <= now) it.remove()
        }
    }

    companion object {
        const val MAX_TIMEOUT_SEC = 1800

        /** SUBSCRIBE 的 TIMEOUT 头："Second-1800" / "Second-infinite" / 缺失。 */
        fun parseTimeout(raw: String?): Int {
            val v = raw?.trim()?.lowercase().orEmpty()
            if (v.isEmpty() || v.contains("infinite")) return MAX_TIMEOUT_SEC
            val num = v.removePrefix("second-").trim().toIntOrNull() ?: return MAX_TIMEOUT_SEC
            return num.coerceIn(60, MAX_TIMEOUT_SEC)
        }

        /** CALLBACK 头：`<http://host:port/path>`，可逗号分隔多个。 */
        fun parseCallbacks(raw: String?): List<String> =
            raw.orEmpty().split(',').mapNotNull { part ->
                val t = part.trim()
                val start = t.indexOf('<')
                val end = t.indexOf('>', start + 1)
                if (start >= 0 && end > start) t.substring(start + 1, end).trim()
                    .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                else null
            }

        /** LastChange 内层 XML 包进 GENA propertyset（内层 XML 需整体转义后作为元素文本）。 */
        fun wrapLastChange(lastChangeXml: String): String =
            "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
                "<e:property><LastChange>" + escapeXmlText(lastChangeXml) +
                "</LastChange></e:property></e:propertyset>"

        fun escapeXmlText(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        fun escapeXmlAttr(s: String): String = escapeXmlText(s)
            .replace("\"", "&quot;")

        /** 裸 Socket 发 NOTIFY（HttpURLConnection 的方法白名单不含 NOTIFY）。 */
        fun defaultNotifySend(callbackUrl: String, sid: String, seq: Long, body: String): Boolean {
            return try {
                val url = URL(callbackUrl)
                val host = url.host ?: return false
                val port = if (url.port >= 0) url.port else url.defaultPort
                val path = url.file.ifEmpty { "/" }
                val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 3_000)
                    socket.soTimeout = 3_000
                    val out = socket.getOutputStream()
                    val head = buildString {
                        append("NOTIFY $path HTTP/1.1\r\n")
                        append("Host: $host:$port\r\n")
                        append("Content-Type: text/xml; charset=\"utf-8\"\r\n")
                        append("NT: upnp:event\r\n")
                        append("NTS: upnp:propchange\r\n")
                        append("SID: $sid\r\n")
                        append("SEQ: $seq\r\n")
                        append("Content-Length: ${bodyBytes.size}\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }
                    out.write(head.toByteArray(StandardCharsets.UTF_8))
                    out.write(bodyBytes)
                    out.flush()
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    val statusLine = reader.readLine() ?: return false
                    val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: return false
                    code in 200..299
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}
