package com.castlink.receiver.sender

import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 信令客户端（纯 java.net，无 Android 依赖，可在 JVM 单测中直接验证）。
 *
 * 行协议（与 protocol/SignalingServer 严格对齐）：
 * - 发送 `HELLO <info>`，期望回 `OK tier=<tier> rtp=<port>`；
 * - 发送 `PIN <pin>`，回 `PIN_OK` / `PIN_FAIL` /
 *   `PIN_LOCKED <剩余秒>`（接收端开启输错锁定时）；
 * - 发送 `BYE` 结束会话。
 */
data class HelloResult(val tier: String, val rtpPort: Int)

/**
 * PIN 校验结果（与 protocol/SignalingServer 的回复严格对齐）。
 */
sealed interface PinResult {
    data object Ok : PinResult
    data object Fail : PinResult
    data class Locked(val remainingSeconds: Long) : PinResult
}

class SignalingClient private constructor(
    private val host: String?,
    private val port: Int,
    reader: BufferedReader?,
    writer: PrintWriter?
) : Closeable {

    /** 常规构造：之后调用 [connect] 建立 TCP 连接。 */
    constructor(host: String, port: Int = 8755) : this(host, port, null, null)

    /**
     * 测试用构造：直接注入已建立的字符流，跳过 TCP 建连
     * （沙箱禁 loopback socket 时，仍可验证行协议本身）。
     */
    constructor(reader: BufferedReader, writer: PrintWriter) : this(null, -1, reader, writer)

    private var socket: Socket? = null
    private var reader: BufferedReader? = reader
    private var writer: PrintWriter? = writer

    @Synchronized
    fun connect(timeoutMs: Int = 5000) {
        val h = host ?: throw IOException("client was built with injected streams; connect() N/A")
        close()
        val s = Socket()
        s.tcpNoDelay = true
        s.soTimeout = 10_000
        s.connect(InetSocketAddress(h, port), timeoutMs)
        socket = s
        reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        writer = PrintWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), true)
    }

    /**
     * 发送 HELLO 并解析服务端的 OK 应答。
     * @throws IOException 连接未建立、服务端无应答或应答格式非法时抛出
     */
    @Synchronized
    fun hello(info: String = "castlink-sender android"): HelloResult {
        val w = writer ?: throw IOException("not connected")
        val r = reader ?: throw IOException("not connected")
        w.println("HELLO $info")
        val line = r.readLine() ?: throw IOException("no HELLO response")
        return parseHello(line)
    }

    /**
     * 发送 PIN；返回服务端的校验结果。
     * @throws IOException 连接未建立、服务端无应答或应答格式非法时抛出
     */
    @Synchronized
    fun pin(pin: String): PinResult {
        val w = writer ?: throw IOException("not connected")
        val r = reader ?: throw IOException("not connected")
        w.println("PIN $pin")
        val line = r.readLine() ?: throw IOException("no PIN response")
        val trimmed = line.trim()
        return when {
            trimmed == "PIN_OK" -> PinResult.Ok
            trimmed == "PIN_FAIL" -> PinResult.Fail
            trimmed.startsWith("PIN_LOCKED") -> {
                val secs = trimmed.removePrefix("PIN_LOCKED").trim().toLongOrNull() ?: 0L
                PinResult.Locked(secs)
            }
            else -> throw IOException("unexpected PIN response: $line")
        }
    }

    /** 发送 BYE（尽力而为，不抛异常）。 */
    @Synchronized
    fun bye() {
        try {
            writer?.println("BYE")
        } catch (e: Exception) {
            // 忽略：会话结束阶段的发送失败不影响语义
        }
    }

    @Synchronized
    override fun close() {
        try {
            reader?.close()
        } catch (e: Exception) {
            // 忽略关闭异常
        }
        try {
            writer?.close()
        } catch (e: Exception) {
            // 忽略关闭异常
        }
        try {
            socket?.close()
        } catch (e: Exception) {
            // 忽略关闭异常
        }
        reader = null
        writer = null
        socket = null
    }

    companion object {
        private val HELLO_RE = Regex("""^OK\s+tier=(\S+)\s+rtp=(\d+)\s*$""")

        /** 解析 `OK tier=mainstream rtp=8760` 这类应答；格式非法抛 IOException。 */
        fun parseHello(line: String): HelloResult {
            val m = HELLO_RE.matchEntire(line.trim())
                ?: throw IOException("unexpected HELLO response: $line")
            return HelloResult(
                tier = m.groupValues[1],
                rtpPort = m.groupValues[2].toIntOrNull()
                    ?: throw IOException("bad rtp port in: $line")
            )
        }
    }
}
