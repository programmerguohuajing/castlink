package com.castlink.receiver.protocol

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * FilePushServer — 极简自研 HTTP 文件接收服务（基础版，不引入 NanoHTTPD 等第三方）。
 *
 * - 监听 TCP 端口（默认 8756）；
 * - 仅支持 `PUT /push/<文件名>`：按 Content-Length 把请求体原样写入 targetDir；
 * - 文件名经净化（拒绝空名 / ".." / 路径分隔符 / 控制字符），重名直接覆盖；
 * - 收完经 [onFileReceived] 回调（I/O 线程回调，更新 UI 请自行切线程）。
 *
 * 纯 java.* 实现，不依赖 android，可在 JVM 单测（见 tools/tests）。
 */
class FilePushServer(
    private val port: Int = DEFAULT_PORT,
    private val targetDir: File,
    private val onFileReceived: (File) -> Unit = {},
    private val log: (String) -> Unit = {},
    /**
     * v0.3.3：落地页展示用 PIN（TV 四格当前显示值；AirPlay 配对时为 iPhone 侧 PIN）。
     * 按请求求值，PIN 轮换后页面自动跟上。
     */
    private val pinProvider: () -> String = { "" },
) {

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        targetDir.mkdirs()
        acceptThread = thread(name = "FilePushServer", isDaemon = true) {
            var server: ServerSocket? = null
            try {
                server = ServerSocket(port).apply { soTimeout = 1000 }
                serverSocket = server
                log("file push listening on tcp port $port → ${targetDir.absolutePath}")
                while (running) {
                    try {
                        val client = server.accept()
                        thread(name = "FilePushClient", isDaemon = true) {
                            runCatching { handleClient(client) }
                                .onFailure { log("file push client error: ${it.message}") }
                        }
                    } catch (_: SocketTimeoutException) {
                        // 超时仅用于周期性检查 running 标记
                    }
                }
            } catch (e: Exception) {
                if (running) log("file push loop died: ${e.message}")
            } finally {
                runCatching { server?.close() }
                serverSocket = null
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        try {
            acceptThread?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        acceptThread = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = s.getInputStream()
            val output = s.getOutputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                respond(output, 400, "Bad Request"); return
            }
            val method = parts[0].uppercase()
            val rawPath = parts[1]
            var contentLength = -1L
            var acceptLang = ""
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break   // 空行 = 头部结束
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val hName = line.substring(0, idx).trim()
                    val hVal = line.substring(idx + 1).trim()
                    if (hName.equals("Content-Length", ignoreCase = true)) {
                        contentLength = hVal.toLongOrNull() ?: -1L
                    } else if (hName.equals("Accept-Language", ignoreCase = true)) {
                        acceptLang = hVal
                    }
                }
            }
            if (method == "GET" && (rawPath == "/" || rawPath.isEmpty())) {
                // v0.3.3：落地页（中/英按 Accept-Language 选择）：PIN + iOS/Android 指引 + 文件上传。
                // 二维码即本页 URL，iPhone 相机扫码后用 Safari 打开可直接用。
                val zh = acceptLang.lowercase().startsWith("zh")
                respondHtml(output, 200, "OK", landingHtml(zh, pinProvider()))
                return
            }
            if (method != "PUT" || !rawPath.startsWith("/push/")) {
                respond(output, 404, "Not Found"); return
            }
            val decoded = runCatching {
                URLDecoder.decode(rawPath.removePrefix("/push/").substringBefore("?"), "UTF-8")
            }.getOrNull()
            val safeName = sanitizeFileName(decoded)
            if (safeName == null) {
                respond(output, 400, "Bad filename"); return
            }
            if (contentLength < 0) {
                respond(output, 411, "Length Required"); return
            }
            if (contentLength > MAX_FILE_BYTES) {
                respond(output, 413, "Payload Too Large"); return
            }
            val dest = File(targetDir, safeName)
            var remaining = contentLength
            val buf = ByteArray(32 * 1024)
            dest.outputStream().buffered().use { out ->
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
            if (remaining != 0L) {
                dest.delete()
                respond(output, 400, "Truncated body")
                return
            }
            log("file push received: $safeName (${contentLength}B)")
            respond(output, 200, "OK")
            runCatching { onFileReceived(dest) }
        }
    }

    /**
     * 从裸 InputStream 按行读取（\n 结尾，去掉尾部 \r），避免 BufferedReader
     * 预读吞掉请求体字节。
     */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun respond(output: OutputStream, code: Int, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $message\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun respondHtml(output: OutputStream, code: Int, message: String, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $message\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    /**
     * v0.3.3 落地页：扫码（iPhone 相机 / Android 浏览器）打开即用。
     * 含当前 PIN、iOS（控制中心 → 屏幕镜像）与 Android 指引、文件上传（JS fetch PUT /push/）。
     * 纯字符串模板（无 Android 资源依赖，保持 JVM 可单测）；中/英按 Accept-Language 选择。
     */
    private fun landingHtml(zh: Boolean, pin: String): String {
        val pinShown = if (pin.isNotBlank()) pin.toCharArray().joinToString(" ") else "----"
        return if (zh) """
            <!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>CastLink 无线投屏</title><style>
            body{font-family:-apple-system,sans-serif;max-width:560px;margin:0 auto;padding:24px 16px;color:#1a1a1a;background:#f2f2f5}
            .card{background:#fff;border-radius:12px;padding:18px;margin:12px 0}
            .pin{font-size:42px;letter-spacing:12px;text-align:center;font-weight:700;margin:6px 0}
            h1{font-size:22px}h2{font-size:16px;margin:0 0 6px}ol{margin:6px 0;padding-left:20px}
            li{margin:6px 0;line-height:1.6}button{background:#0a84ff;color:#fff;border:0;border-radius:8px;padding:10px 18px;font-size:15px}
            .hint{color:#666;font-size:13px}</style></head><body>
            <h1>📺 CastLink 无线投屏</h1>
            <div class="card"><h2>投屏 PIN（5 分钟刷新）</h2><div class="pin">$pinShown</div></div>
            <div class="card"><h2>🍎 iPhone / iPad</h2><ol>
            <li>连入与电视相同的 Wi-Fi</li>
            <li>打开控制中心 → 屏幕镜像</li>
            <li>选择本设备，输入上方 4 位 PIN</li></ol></div>
            <div class="card"><h2>🤖 Android 手机</h2><ol>
            <li>连入与电视相同的 Wi-Fi</li>
            <li>在 CastLink App 点"把本机屏幕投出去"</li>
            <li>选择本设备，输入上方 4 位 PIN</li></ol></div>
            <div class="card"><h2>📁 文件投放到电视</h2>
            <input type="file" id="f"><br><br><button onclick="up()">上传</button>
            <p class="hint" id="msg"></p></div>
            <script>function up(){var f=document.getElementById('f').files[0];if(!f)return;
            var m=document.getElementById('msg');m.textContent='上传中…';
            fetch('/push/'+encodeURIComponent(f.name),{method:'PUT',body:f}).then(function(r){
            m.textContent=r.ok?'✅ 上传成功，在电视上打开播放':'❌ 上传失败 '+r.status;}).catch(function(e){m.textContent='❌ '+e;});}</script>
            </body></html>
        """.trimIndent() else """
            <!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>CastLink Wireless Cast</title><style>
            body{font-family:-apple-system,sans-serif;max-width:560px;margin:0 auto;padding:24px 16px;color:#1a1a1a;background:#f2f2f5}
            .card{background:#fff;border-radius:12px;padding:18px;margin:12px 0}
            .pin{font-size:42px;letter-spacing:12px;text-align:center;font-weight:700;margin:6px 0}
            h1{font-size:22px}h2{font-size:16px;margin:0 0 6px}ol{margin:6px 0;padding-left:20px}
            li{margin:6px 0;line-height:1.6}button{background:#0a84ff;color:#fff;border:0;border-radius:8px;padding:10px 18px;font-size:15px}
            .hint{color:#666;font-size:13px}</style></head><body>
            <h1>📺 CastLink Wireless Cast</h1>
            <div class="card"><h2>Cast PIN (refreshes every 5 min)</h2><div class="pin">$pinShown</div></div>
            <div class="card"><h2>🍎 iPhone / iPad</h2><ol>
            <li>Join the same Wi-Fi as the TV</li>
            <li>Open Control Center → Screen Mirroring</li>
            <li>Select this device and enter the 4-digit PIN above</li></ol></div>
            <div class="card"><h2>🤖 Android</h2><ol>
            <li>Join the same Wi-Fi as the TV</li>
            <li>In the CastLink app tap the sender entry</li>
            <li>Select this device and enter the 4-digit PIN above</li></ol></div>
            <div class="card"><h2>📁 Push a file to the TV</h2>
            <input type="file" id="f"><br><br><button onclick="up()">Upload</button>
            <p class="hint" id="msg"></p></div>
            <script>function up(){var f=document.getElementById('f').files[0];if(!f)return;
            var m=document.getElementById('msg');m.textContent='Uploading…';
            fetch('/push/'+encodeURIComponent(f.name),{method:'PUT',body:f}).then(function(r){
            m.textContent=r.ok?'✅ Uploaded — open it on the TV':'❌ Failed '+r.status;}).catch(function(e){m.textContent='❌ '+e;});}</script>
            </body></html>
        """.trimIndent()
    }

    companion object {
        const val DEFAULT_PORT = 8756

        /** 单文件上限 2 GiB（基础版保护）。 */
        const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024

        /** 文件投放统一存放目录：cacheDir/filepush。 */
        fun dirFor(cacheDir: File): File = File(cacheDir, "filepush")

        /**
         * 文件名净化：拒绝空名 / ".." / 任意路径分隔符 / 控制字符 / 过长；
         * 返回 null 表示非法。
         */
        fun sanitizeFileName(name: String?): String? {
            if (name.isNullOrBlank() || name.length > 128) return null
            if (name.contains("..")) return null
            if (name.any { it == '/' || it == '\\' || it.code < 0x20 }) return null
            return name
        }
    }
}
