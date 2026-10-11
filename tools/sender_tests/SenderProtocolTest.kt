package com.castlink.receiver.sender

import com.castlink.receiver.protocol.RtpReceiver
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * 发送端 JVM 协议自测（不引 android.jar）。
 *
 * 沙箱限制：禁止 loopback TCP/UDP socket，因此本测试不用真实 socket，
 * 但双方用的都是真实生产代码：
 *
 * A. 信令：真实的 sender/SignalingClient（注入 Piped 流）←→ 测试桩服务端。
 *    桩服务端逐字节复刻 protocol/SignalingServer.handleClient 的回复语义
 *    （"OK tier=mainstream rtp=8760" / "PIN_OK" / "PIN_FAIL" /
 *    "PIN_LOCKED <秒>" / "BYE" 断开），断言客户端发出的行字节精确一致、
 *    各种回复解析正确、畸形回复抛 IOException。
 *    （真实的 SignalingServer.kt 同样参与编译，Log 走 android.util 桩。）
 * B. RTP：真实的 sender/RtpPacketizer 输出 → 反射调用真实的
 *    protocol/RtpReceiver.handlePacket（私有解析 + FU-A 重组状态机）→
 *    onNal 回调，断言重组 NAL 与原 NAL 字节一致，并断言 RTP 头字段
 *    （V/PT/seq/timestamp/SSRC/Marker）语义。
 * C. Annex-B 切分与 FU-A 头字段语义（含 MTU 边界）。
 *
 * 失败时抛 AssertionError 并以非 0 退出；全部通过打印 PASS 汇总。
 */
private var passed = 0

private fun check(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("FAIL: $name")
    passed++
    println("PASS: $name")
}

private fun bytesEqual(a: ByteArray, b: ByteArray) = a.contentEquals(b)

private fun syntheticNal(header: Int, size: Int, seed: Long): ByteArray {
    val rnd = Random(seed)
    val nal = ByteArray(size)
    nal[0] = header.toByte()
    for (i in 1 until size) nal[i] = rnd.nextInt(256).toByte()
    // 避免 NAL 体内意外出现起始码
    for (i in 1 until size - 2) {
        if (nal[i] == 0.toByte() && nal[i + 1] == 0.toByte() && nal[i + 2] == 1.toByte()) {
            nal[i + 2] = 2
        }
    }
    return nal
}

private fun getU16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
private fun getU32(b: ByteArray, off: Int): Long =
    ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
        ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

fun main() {
    testSignalingRoundtrip()
    testSignalingLockout()
    testSignalingMalformed()
    testRtpRoundtrip()
    testAnnexBSplit()
    testFuAHeaders()
    println("\nALL SENDER PROTOCOL TESTS PASSED ($passed assertions)")
}

// ---------------- A. 信令往返（Piped 流 + 复刻服务端语义的桩） ----------------

/**
 * 启动一个桩服务端线程：语义逐行复刻
 * protocol/SignalingServer.handleClient（正确 PIN=2468）。
 * @param pinResponder 对 "PIN xxxx" 的回复函数
 * @return Pair(客户端注入用的 reader/writer, 客户端发出的原始行列表)
 */
private fun startStubServer(pinResponder: (String) -> String):
    Triple<BufferedReader, PrintWriter, MutableList<String>> {
    val clientToServer = PipedOutputStream()
    val serverIn = PipedInputStream(clientToServer)
    val serverToClient = PipedOutputStream()
    val clientIn = PipedInputStream(serverToClient)

    val receivedLines = CopyOnWriteArrayList<String>()
    thread(name = "StubSignalingServer", isDaemon = true) {
        val reader = BufferedReader(InputStreamReader(serverIn, Charsets.UTF_8))
        val writer = PrintWriter(OutputStreamWriter(serverToClient, Charsets.UTF_8), true)
        while (true) {
            val line = reader.readLine() ?: break
            val trimmed = line.trim()
            receivedLines.add(trimmed)
            when {
                // 与 SignalingServer.handleClient 完全一致的分支与回复
                trimmed.startsWith("HELLO") -> writer.println("OK tier=mainstream rtp=8760")
                trimmed.startsWith("PIN ") ->
                    writer.println(pinResponder(trimmed.removePrefix("PIN ").trim()))
                trimmed == "BYE" -> break
                else -> writer.println("ERR unknown")
            }
        }
        writer.close()
    }
    val clientReader = BufferedReader(InputStreamReader(clientIn, Charsets.UTF_8))
    val clientWriter = PrintWriter(OutputStreamWriter(clientToServer, Charsets.UTF_8), true)
    return Triple(clientReader, clientWriter, receivedLines)
}

private fun testSignalingRoundtrip() {
    println("== signaling roundtrip ==")
    val (reader, writer, sentLines) = startStubServer { pin ->
        if (pin == "2468") "PIN_OK" else "PIN_FAIL"
    }
    val client = SignalingClient(reader, writer)

    val hello = client.hello("castlink-sender test")
    check("hello tier=mainstream", hello.tier == "mainstream")
    check("hello rtp=8760", hello.rtpPort == 8760)
    check("wire: HELLO line exact", sentLines[0] == "HELLO castlink-sender test")

    check("wrong pin -> Fail", client.pin("0000") is PinResult.Fail)
    check("wire: PIN line exact", sentLines[1] == "PIN 0000")
    check("right pin -> Ok", client.pin("2468") is PinResult.Ok)
    check("wire: PIN line exact", sentLines[2] == "PIN 2468")

    client.bye()
    client.close()
    Thread.sleep(200)
    check("wire: BYE line exact", sentLines.last() == "BYE")
}

private fun testSignalingLockout() {
    println("== signaling pin_locked ==")
    // 复刻 verifier 非空时的服务端行为：锁定中回 "PIN_LOCKED <剩余秒>"
    val (reader, writer, _) = startStubServer { _ -> "PIN_LOCKED 42" }
    val client = SignalingClient(reader, writer)
    client.hello("castlink-sender test")
    val res = client.pin("2468")
    check(
        "locked -> PinResult.Locked(42), no exception",
        res is PinResult.Locked && res.remainingSeconds == 42L
    )
    client.bye()
    client.close()
}

private fun testSignalingMalformed() {
    println("== signaling malformed responses ==")
    // 畸形 HELLO 回复 → parseHello 抛 IOException
    var threw = false
    try {
        SignalingClient.parseHello("ERR unknown")
    } catch (e: Exception) {
        threw = true
    }
    check("parseHello rejects 'ERR unknown'", threw)

    threw = false
    try {
        SignalingClient.parseHello("OK tier=mainstream") // 缺 rtp=
    } catch (e: Exception) {
        threw = true
    }
    check("parseHello rejects missing rtp=", threw)

    // 畸形 PIN 回复 → pin() 抛 IOException
    val (r2, w2, _) = startStubServer { pin -> "BOGUS $pin" }
    val client2 = SignalingClient(r2, w2)
    var threw2 = false
    try {
        client2.pin("1234")
    } catch (e: Exception) {
        threw2 = true
    }
    check("pin() rejects bogus response", threw2)
    client2.close()
}

// ---------------- B. RTP 往返（真实 RtpReceiver.handlePacket，经反射） ----------------

private fun testRtpRoundtrip() {
    println("== rtp roundtrip via real RtpReceiver.handlePacket ==")
    val received = CopyOnWriteArrayList<ByteArray>()
    // 不调用 start()（沙箱禁 UDP bind）；直接驱动私有的解析 + 重组状态机
    val receiver = RtpReceiver(port = 8760, onNal = { received.add(it.copyOf()) })
    val handlePacket = RtpReceiver::class.java.getDeclaredMethod(
        "handlePacket", ByteArray::class.java,
        Int::class.javaPrimitiveType, MutableList::class.java
    ).apply { isAccessible = true }
    val fuBuffer = mutableListOf<Byte>()

    val pack = RtpPacketizer(
        initialSequence = 1000,
        initialTimestamp = 90000L,
        timestampStep = 3000L
    )

    val idrSmall = syntheticNal(0x65, 120, seed = 11L)      // 单包 IDR
    val bigNal = syntheticNal(0x41, 5000, seed = 22L)       // FU-A 分片
    val pSmall = syntheticNal(0x41, 60, seed = 33L)         // 单包 P 帧

    val frame1 = pack.packetizeFrame(listOf(idrSmall))               // 1 包
    val frame2 = pack.packetizeFrame(listOf(bigNal))                 // 5000B -> 5 分片
    val frame3 = pack.packetizeFrame(listOf(idrSmall, pSmall))       // 2 包

    check("frame1 single packet", frame1.size == 1)
    check("frame2 5000B -> 5 fragments", frame2.size == 5)
    check("frame3 two packets", frame3.size == 2)

    val all = frame1 + frame2 + frame3
    checkRtpHeaders(all, pack.ssrc)

    // 真实接收端解析 + FU-A 重组
    for (pkt in all) {
        handlePacket.invoke(receiver, pkt, pkt.size, fuBuffer)
    }

    check("receiver reassembled 4 nals", received.size == 4)
    check("nal[0] == idrSmall", bytesEqual(received[0], idrSmall))
    check("nal[1] == bigNal (FU-A reassembled)", bytesEqual(received[1], bigNal))
    check("nal[2] == idrSmall", bytesEqual(received[2], idrSmall))
    check("nal[3] == pSmall", bytesEqual(received[3], pSmall))
}

private fun checkRtpHeaders(packets: List<ByteArray>, ssrc: Long) {
    // 布局：f1(1包) ts=90000 seq=1000；f2(5包) ts=93000 seq=1001..1005；f3(2包) ts=96000 seq=1006..1007
    val expectedTs = listOf(90000L, 93000L, 93000L, 93000L, 93000L, 93000L, 96000L, 96000L)
    val expectedMarker = listOf(true, false, false, false, false, true, false, true)
    check("8 packets total", packets.size == 8)
    packets.forEachIndexed { i, p ->
        val b0 = p[0].toInt() and 0xFF
        val b1 = p[1].toInt() and 0xFF
        check("pkt[$i] V=2", (b0 shr 6) == 2)
        check("pkt[$i] PT=96", (b1 and 0x7F) == 96)
        check("pkt[$i] marker=${expectedMarker[i]}", ((b1 and 0x80) != 0) == expectedMarker[i])
        check("pkt[$i] seq=${1000 + i}", getU16(p, 2) == 1000 + i)
        check("pkt[$i] ts=${expectedTs[i]}", getU32(p, 4) == expectedTs[i])
        check("pkt[$i] ssrc stable", getU32(p, 8) == ssrc)
    }
}

// ---------------- C. Annex-B 切分 ----------------

private fun testAnnexBSplit() {
    println("== annex-b split ==")
    val pack = RtpPacketizer()
    val a = syntheticNal(0x67, 20, 1L)
    val b = syntheticNal(0x68, 30, 2L)
    val c = syntheticNal(0x65, 40, 3L)
    val sc4 = byteArrayOf(0, 0, 0, 1)
    val sc3 = byteArrayOf(0, 0, 1)
    val stream = sc4 + a + sc3 + b + sc4 + c
    val nals = pack.splitAnnexB(stream)
    check("split -> 3 nals", nals.size == 3)
    check("nal[0] == sps", bytesEqual(nals[0], a))
    check("nal[1] == pps", bytesEqual(nals[1], b))
    check("nal[2] == idr", bytesEqual(nals[2], c))

    // 边界：恰好 maxPayloadSize 走单包；+1 走 FU-A
    val exact = syntheticNal(0x41, pack.maxPayloadSize, 4L)
    val over = syntheticNal(0x41, pack.maxPayloadSize + 1, 5L)
    check("exact mtu -> single packet", pack.packetizeFrame(listOf(exact)).size == 1)
    val overPkts = pack.packetizeFrame(listOf(over))
    check("mtu+1 -> 2 fragments", overPkts.size == 2)
}

// ---------------- D. FU-A 头字段语义 ----------------

private fun testFuAHeaders() {
    println("== fu-a headers ==")
    val pack = RtpPacketizer(initialSequence = 0, initialTimestamp = 0L, timestampStep = 3000L)
    // NAL 头 0x65：F=0, NRI=0x60, type=5
    val big = syntheticNal(0x65, 3000, 6L)
    val frags = pack.packetizeFrame(listOf(big))
    check("3000B -> 3 fragments", frags.size == 3)
    var reassembled = byteArrayOf()
    frags.forEachIndexed { i, pkt ->
        val payload = pkt.copyOfRange(12, pkt.size)
        val nalType = payload[0].toInt() and 0x1F
        check("frag[$i] nalType=28 (FU-A)", nalType == 28)
        val fuIndicator = payload[0].toInt() and 0xFF
        check("frag[$i] F|NRI preserved (0x60)", (fuIndicator and 0xE0) == 0x60)
        val fuHeader = payload[1].toInt() and 0xFF
        check("frag[$i] S=${i == 0}", ((fuHeader and 0x80) != 0) == (i == 0))
        check("frag[$i] E=${i == frags.lastIndex}", ((fuHeader and 0x40) != 0) == (i == frags.lastIndex))
        check("frag[$i] type=5", (fuHeader and 0x1F) == 5)
        if (i == 0) {
            reassembled += byteArrayOf(((fuIndicator and 0xE0) or (fuHeader and 0x1F)).toByte())
        }
        reassembled += payload.copyOfRange(2, payload.size)
    }
    check("FU-A manual reassembly == original", bytesEqual(reassembled, big))
    frags.forEachIndexed { i, pkt ->
        check("frag[$i] payload <= maxPayloadSize", pkt.size - 12 <= pack.maxPayloadSize)
    }
}
