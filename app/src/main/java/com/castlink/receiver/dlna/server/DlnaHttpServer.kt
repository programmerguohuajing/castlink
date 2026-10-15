package com.castlink.receiver.dlna.server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * DLNA HTTP 服务（TCP 8758，java.net 实现，无 Android 依赖，可 JVM 单测）。
 *
 * 路由：
 * - GET  /description.xml   → 设备描述
 * - GET  /scpd/avt.xml | /scpd/rc.xml | /scpd/cm.xml → 服务描述
 * - POST /control/avt|rc|cm → SOAPAction 分发（soapHandler）
 * - SUBSCRIBE /event/avt 等   → 最小事件订阅应答（200 + SID，不主动推送事件）
 *
 * HTTP/1.1 keep-alive：同一连接循环读请求，直到客户端 close / 超时 / 出错。
 */
class DlnaHttpServer(
    val port: Int,
    private val descriptionProvider: () -> String,
    private val scpdProvider: (kind: String) -> String?,
    private val soapHandler: (path: String, soapAction: String?, body: String) -> Pair<Int, String>,
    private val logger: (String) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "dlna-http-worker").apply { isDaemon = true }
    }

    @Volatile private var boundOk = false
    val isBound: Boolean get() = boundOk
    /** 实际绑定的端口（构造传 0 时为系统分配的临时端口，单测用） */
    val localPort: Int get() = serverSocket?.localPort ?: -1

    fun start() {
        if (running.getAndSet(true)) return
        try {
            val ss = ServerSocket(port)
            ss.soTimeout = 2000
            serverSocket = ss
            boundOk = true
            logger("DLNA HTTP listening on :$port")
        } catch (e: Exception) {
            running.set(false)
            logger("DLNA HTTP bind :$port failed: ${e.message}")
            return
        }
        acceptThread = thread(name = "dlna-http-accept", isDaemon = true) {
            while (running.get()) {
                try {
                    val client = serverSocket?.accept() ?: break
                    pool.execute { handleConnection(client) }
                } catch (e: java.net.SocketTimeoutException) {
                    // accept 心跳
                } catch (e: Exception) {
                    if (running.get()) logger("DLNA HTTP accept error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        runCatching { acceptThread?.interrupt() }
        pool.shutdownNow()
        boundOk = false
        logger("DLNA HTTP stopped")
    }

    /** 可独立单测的请求模型（无需 socket） */
    data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    /** 路由结果：HTTP 状态码 / Content-Type / 响应体 */
    data class RouteResult(val code: Int, val contentType: String, val body: String)

    /**
     * 纯路由逻辑（无 socket，可 JVM 单测）：
     * GET /description.xml、GET /scpd/ 下的 xml、POST /control/ 下路径（SOAP）、
     * SUBSCRIBE /event/ 下路径（最小订阅应答）、UNSUBSCRIBE。
     */
    fun route(req: Request): RouteResult {
        return when (req.method) {
            "GET", "HEAD" -> {
                val (code, body, mime) = when {
                    req.path == "/description.xml" ->
                        Triple(200, descriptionProvider(), "text/xml; charset=\"utf-8\"")
                    req.path.startsWith("/scpd/") && req.path.endsWith(".xml") -> {
                        val kind = req.path.removePrefix("/scpd/").removeSuffix(".xml")
                        val xml = scpdProvider(kind)
                        if (xml != null) Triple(200, xml, "text/xml; charset=\"utf-8\"")
                        else Triple(404, "Not Found", "text/plain")
                    }
                    else -> Triple(404, "Not Found", "text/plain")
                }
                RouteResult(code, mime, if (req.method == "HEAD") "" else body)
            }
            "POST" -> {
                val (code, respBody) = soapHandler(req.path, req.headers["soapaction"], req.body)
                RouteResult(code, "text/xml; charset=\"utf-8\"", respBody)
            }
            "SUBSCRIBE", "UNSUBSCRIBE" -> RouteResult(200, "text/plain", "")
            else -> RouteResult(405, "text/plain", "Method Not Allowed")
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15_000
            val input = s.getInputStream()
            val output = s.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
            while (running.get()) {
                val req = readRequest(reader) ?: break
                val keepAlive = req.headers["connection"]?.equals("close", ignoreCase = true) != true
                serve(req, output, keepAlive)
                output.flush()
                if (!keepAlive) break
            }
        }
    }

    private fun readRequest(reader: BufferedReader): Request? {
        // 跳过 keep-alive 之间的空行
        var requestLine: String?
        do {
            requestLine = try {
                reader.readLine()
            } catch (e: Exception) {
                return null
            } ?: return null
        } while (requestLine!!.isBlank())

        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawPath = parts[1].substringBefore('?')

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = try {
                reader.readLine()
            } catch (e: Exception) {
                return null
            } ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }

        var body = ""
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength > 0) {
            if (contentLength > 512 * 1024) return null // SOAP/DIDL 不会这么大，防炸弹
            val chars = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = try {
                    reader.read(chars, read, contentLength - read)
                } catch (e: Exception) {
                    return null
                }
                if (n < 0) break
                read += n
            }
            body = String(chars, 0, read)
        }
        return Request(method, rawPath, headers, body)
    }

    private fun serve(req: Request, output: OutputStream, keepAlive: Boolean) {
        // 最小事件订阅：应答 200 + SID，不主动推送事件（客户端轮询即可工作）
        if (req.method == "SUBSCRIBE") {
            val sid = "uuid:${java.util.UUID.randomUUID()}"
            val head = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("SID: $sid\r\n")
                append("TIMEOUT: Second-1800\r\n")
                append("Content-Length: 0\r\n")
                append(if (keepAlive) "Connection: keep-alive\r\n" else "Connection: close\r\n")
                append("\r\n")
            }
            output.write(head.toByteArray(StandardCharsets.UTF_8))
            return
        }
        val result = route(req)
        writeResponse(output, result.code, result.contentType, result.body, keepAlive)
    }

    private fun writeResponse(
        output: OutputStream,
        code: Int,
        contentType: String,
        body: String,
        keepAlive: Boolean,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val status = when (code) {
            200 -> "200 OK"
            404 -> "404 Not Found"
            405 -> "405 Method Not Allowed"
            500 -> "500 Internal Server Error"
            else -> "$code Unknown"
        }
        val head = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("EXT:\r\n")
            append("Server: Linux/5.15 UPnP/1.0 CastLink/0.1.41\r\n")
            append(if (keepAlive) "Connection: keep-alive\r\n" else "Connection: close\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
    }
}
