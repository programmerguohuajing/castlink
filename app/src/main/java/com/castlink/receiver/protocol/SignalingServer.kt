package com.castlink.receiver.protocol

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * 信令服务器骨架实现：
 * - ServerSocket 单线程 accept，每条连接起一个线程处理行协议；
 * - 协议：收到 "HELLO ..." 回 "OK tier=mainstream rtp=8760"；
 *   收到 "PIN xxxx" 与当前 PIN 比对回 "PIN_OK" / "PIN_FAIL"；
 *   收到 "BYE" 关闭该连接。
 * - PIN 由外部以 provider 形式传入（支持 PIN 定时刷新而无需重启服务）。
 */
class SignalingServer(
    private val port: Int = 8755,
    private val pinProvider: () -> String,
    /**
     * 可选的 PIN 校验器：非空时 "PIN xxxx" 走 verifier（含输错锁定），
     * 为空时回退到 pinProvider() 直接比对（既有行为不变）。
     */
    private val pinVerifier: PinVerifier? = null,
) {

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        acceptThread = thread(name = "SignalingServer", isDaemon = true) {
            var server: ServerSocket? = null
            try {
                server = ServerSocket(port).apply { soTimeout = 1000 }
                serverSocket = server
                Log.i(TAG, "listening on tcp port $port")
                while (running) {
                    try {
                        val client = server.accept()
                        thread(name = "SignalingClient", isDaemon = true) {
                            handleClient(client)
                        }
                    } catch (e: SocketTimeoutException) {
                        // 超时仅用于周期性检查 running 标记
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "signaling loop died", e)
            } finally {
                try {
                    server?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "server socket close failed", e)
                }
                serverSocket = null
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), true)
                while (running) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("HELLO") -> {
                            writer.println("OK tier=mainstream rtp=8760")
                        }
                        trimmed.startsWith("PIN ") -> {
                            val pin = trimmed.removePrefix("PIN ").trim()
                            writer.println(verifyPin(pin))
                        }
                        trimmed == "BYE" -> break
                        else -> writer.println("ERR unknown")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "client handler died", e)
        }
    }

    /**
     * PIN 校验：verifier 非空时走其状态机（含 3 次错 → 锁定 60 秒），
     * 回复 PIN_OK / PIN_FAIL / PIN_LOCKED <剩余秒>；verifier 为空时保持既有
     * pinProvider() 直接比对行为（仅 PIN_OK / PIN_FAIL）。
     */
    private fun verifyPin(pin: String): String {
        val v = pinVerifier ?: return if (pin == pinProvider()) "PIN_OK" else "PIN_FAIL"
        return when (val r = v.verify(pin)) {
            PinVerifier.Result.Ok -> "PIN_OK"
            is PinVerifier.Result.Wrong -> "PIN_FAIL"
            is PinVerifier.Result.Locked -> "PIN_LOCKED ${r.remainingSeconds}"
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "server socket close failed", e)
        }
        try {
            acceptThread?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        acceptThread = null
    }

    companion object {
        private const val TAG = "SignalingServer"
    }
}
