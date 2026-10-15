package com.castlink.receiver.dlna.ssdp

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * SSDP 发现服务（java.net 实现，无 Android 依赖；组播锁由调用方 [DlnaServer] 持有）。
 *
 * - UDP 1900 组播监听 M-SEARCH，命中时单播回 200 OK（LOCATION 指向上 :8758）
 * - 启动/运行中定时组播 ssdp:alive 保活；stop() 时组播 ssdp:byebye
 *
 * 注意：1900 端口需 SO_REUSEADDR（与其它 DLNA 应用共存）。
 */
class SsdpServer(
    private val httpPort: Int,
    /** UDN，形如 "uuid:xxxxxxxx-..." */
    private val udn: String,
    private val localIpProvider: () -> String,
    private val logger: (String) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private var socket: MulticastSocket? = null
    private var recvThread: Thread? = null
    private var notifyThread: Thread? = null

    @Volatile private var boundOk = false
    val isBound: Boolean get() = boundOk

    fun start() {
        if (running.getAndSet(true)) return
        try {
            val group = InetAddress.getByName(SsdpPacket.MULTICAST_ADDR)
            val s = MulticastSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(SsdpPacket.MULTICAST_PORT))
            // 尽量绑到有 IPv4 的网卡，避免只收到回环组播
            runCatching {
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces.hasMoreElements()) {
                    val iface = ifaces.nextElement()
                    if (!iface.isUp || iface.isLoopback) continue
                    runCatching { s.joinGroup(InetSocketAddress(group, 0), iface) }
                }
            }
            // 兜底：默认 join（部分 ROM 上按接口 join 会抛，靠这个保底）
            runCatching { s.joinGroup(group) }
            s.soTimeout = 2000
            socket = s
            boundOk = true
            logger("DLNA SSDP listening on ${SsdpPacket.MULTICAST_PORT}")
        } catch (e: Exception) {
            running.set(false)
            logger("DLNA SSDP bind failed: ${e.message}")
            return
        }

        sendAlive()

        recvThread = thread(name = "dlna-ssdp-recv", isDaemon = true) { recvLoop() }
        notifyThread = thread(name = "dlna-ssdp-notify", isDaemon = true) {
            // max-age=1800，取 1/3 周期保活
            while (running.get()) {
                try {
                    Thread.sleep(600_000)
                } catch (e: InterruptedException) {
                    break
                }
                if (running.get()) sendAlive()
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { sendByebye() }
        runCatching { recvThread?.interrupt() }
        runCatching { notifyThread?.interrupt() }
        runCatching {
            val group = InetAddress.getByName(SsdpPacket.MULTICAST_ADDR)
            runCatching { socket?.leaveGroup(group) }
            socket?.close()
        }
        socket = null
        boundOk = false
        logger("DLNA SSDP stopped")
    }

    private fun recvLoop() {
        val buf = ByteArray(2048)
        while (running.get()) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                socket?.receive(pkt) ?: break
                handlePacket(pkt)
            } catch (e: java.net.SocketTimeoutException) {
                // 心跳超时，继续
            } catch (e: Exception) {
                if (running.get()) logger("DLNA SSDP recv error: ${e.message}")
                else break
            }
        }
    }

    private fun handlePacket(pkt: DatagramPacket) {
        val msearch = SsdpPacket.parseMSearch(pkt.data, pkt.length) ?: return
        if (!SsdpPacket.isRelevant(msearch.st, udn)) return
        val ip = localIpProvider().ifEmpty { return }
        val responses = if (msearch.st.equals("ssdp:all", ignoreCase = true)) {
            SsdpPacket.buildOkResponsesForAll(ip, httpPort, udn)
        } else {
            val (st, suffix) = when {
                msearch.st.equals(SsdpPacket.ROOT_DEVICE, ignoreCase = true) ->
                    SsdpPacket.ROOT_DEVICE to "::${SsdpPacket.ROOT_DEVICE}"
                msearch.st.equals(udn, ignoreCase = true) -> udn to ""
                else -> SsdpPacket.DEVICE_TYPE to "::${SsdpPacket.DEVICE_TYPE}"
            }
            listOf(SsdpPacket.buildOkResponse(ip, httpPort, udn, st, suffix))
        }
        try {
            // MX 抖动：ssdp:all 的多条响应之间小间隔，避免组播风暴
            val sender = DatagramSocket()
            for (resp in responses) {
                val bytes = resp.toByteArray(Charsets.UTF_8)
                sender.send(DatagramPacket(bytes, bytes.size, pkt.address, pkt.port))
                if (responses.size > 1) Thread.sleep(30)
            }
            sender.close()
            logger("DLNA SSDP answered M-SEARCH ST=${msearch.st} from ${pkt.address.hostAddress}")
        } catch (e: Exception) {
            logger("DLNA SSDP respond failed: ${e.message}")
        }
    }

    private fun sendAlive() {
        multicast(SsdpPacket.buildNotifyAlive(localIpProvider().ifEmpty { return }, httpPort, udn))
    }

    private fun sendByebye() {
        multicast(SsdpPacket.buildNotifyByebye(udn))
        // byebye 发两遍，提高送达率
        runCatching { Thread.sleep(200) }
        multicast(SsdpPacket.buildNotifyByebye(udn))
    }

    private fun multicast(messages: List<String>) {
        try {
            val group = InetAddress.getByName(SsdpPacket.MULTICAST_ADDR)
            val sender = DatagramSocket()
            for (msg in messages) {
                val bytes = msg.toByteArray(Charsets.UTF_8)
                sender.send(DatagramPacket(bytes, bytes.size, group, SsdpPacket.MULTICAST_PORT))
            }
            sender.close()
        } catch (e: Exception) {
            logger("DLNA SSDP multicast failed: ${e.message}")
        }
    }
}
