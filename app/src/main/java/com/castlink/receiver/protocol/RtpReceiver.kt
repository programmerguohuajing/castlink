package com.castlink.receiver.protocol

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * RTP 接收骨架实现：
 * - 子线程 DatagramSocket 循环收包；
 * - 解析 RTP 固定头 12 字节（版本号 / 载荷类型 / Marker / 序列号 / 时间戳）；
 * - 只处理单 NAL（nalType 1..23）与 FU-A 分片重组（nalType 28），重组后经 onNal 回调；
 * - 未做 SRTP 解密、抖动缓冲（jitter buffer）、丢包重传、RTCP。
 */
class RtpReceiver(
    private val port: Int = 8760,
    private val onNal: (ByteArray) -> Unit
) {

    @Volatile
    private var running = false
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "RtpReceiver", isDaemon = true) {
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket(port).apply { soTimeout = 1000 }
                socket = s
                Log.i(TAG, "listening on udp port $port")
                val buf = ByteArray(65535)
                val packet = DatagramPacket(buf, buf.size)
                val fuBuffer = mutableListOf<Byte>()
                while (running) {
                    try {
                        s.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    handlePacket(buf, packet.length, fuBuffer)
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "receiver loop died", e)
            } finally {
                try {
                    s?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "socket close failed", e)
                }
                socket = null
            }
        }
    }

    private fun handlePacket(buf: ByteArray, len: Int, fuBuffer: MutableList<Byte>) {
        if (len < 12) {
            Log.w(TAG, "short rtp packet: $len")
            return
        }
        val b0 = buf[0].toInt() and 0xFF
        if ((b0 shr 6) != 2) {
            Log.w(TAG, "bad rtp version")
            return
        }
        val b1 = buf[1].toInt() and 0xFF
        val marker = (b1 and 0x80) != 0
        val payloadType = b1 and 0x7F
        val seq = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        val timestamp = ((buf[4].toInt() and 0xFF).toLong() shl 24) or
            ((buf[5].toInt() and 0xFF).toLong() shl 16) or
            ((buf[6].toInt() and 0xFF).toLong() shl 8) or
            (buf[7].toInt() and 0xFF).toLong()

        val payload = buf.copyOfRange(12, len)
        if (payload.isEmpty()) return
        val nalType = payload[0].toInt() and 0x1F
        when (nalType) {
            28 -> { // FU-A 分片
                if (payload.size < 2) return
                val fuHeader = payload[1].toInt() and 0xFF
                val start = (fuHeader and 0x80) != 0
                val end = (fuHeader and 0x40) != 0
                if (start) {
                    fuBuffer.clear()
                    val reconstructed = (payload[0].toInt() and 0xE0) or (fuHeader and 0x1F)
                    fuBuffer.add(reconstructed.toByte())
                }
                fuBuffer.addAll(payload.copyOfRange(2, payload.size).toList())
                if (end) {
                    onNal(fuBuffer.toByteArray())
                    fuBuffer.clear()
                }
            }
            in 1..23 -> onNal(payload) // 单 NAL 包
            else -> Log.d(TAG, "unhandled nalType=$nalType pt=$payloadType seq=$seq ts=$timestamp")
        }
        if (marker) {
            // Marker 置位通常表示一帧的最后一个包；骨架实现暂不做帧组装
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "socket close failed", e)
        }
        try {
            worker?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        worker = null
    }

    companion object {
        private const val TAG = "RtpReceiver"
    }
}
