package com.castlink.receiver.sender

import java.util.Random

/**
 * RTP 打包器（纯 Kotlin，无 Android 依赖，可在 JVM 单测中直接验证）。
 *
 * - 输入：Annex-B 字节流或已切分的 NAL 列表（每个 NAL 含 NAL 头字节，不含起始码）；
 * - 输出：完整 UDP 数据报（12 字节 RTP 固定头 + 载荷）；
 * - 小 NAL：单个 RTP 包直接承载；大 NAL：按 FU-A 分片；
 * - RTP 头字段与 protocol/RtpReceiver 的解析逻辑严格对齐：
 *   V=2，PT=96，seq 自增（16 bit 回绕），timestamp 90kHz 时钟，SSRC 随机固定；
 *   每一帧的最后一个包置 Marker=1。
 */
class RtpPacketizer(
    val payloadType: Int = 96,
    ssrc: Long = (Random().nextInt().toLong() and 0xFFFFFFFFL),
    initialSequence: Int = Random().nextInt(65536),
    initialTimestamp: Long = (Random().nextInt().toLong() and 0xFFFFFFFFL),
    /** 每帧时间戳步进（90kHz 时钟；30fps 对应 3000）。 */
    val timestampStep: Long = 3000L,
    /**
     * 单个 RTP 载荷上限（字节，含 FU-A 的 2 字节分片头）。
     * 保证 UDP 数据报远小于常见 MTU，避免 IP 分片。
     */
    val maxPayloadSize: Int = 1200
) {

    val ssrc: Long = ssrc and 0xFFFFFFFFL

    private var sequence: Int = initialSequence and 0xFFFF
    private var timestamp: Long = initialTimestamp and 0xFFFFFFFFL

    /**
     * Annex-B 切分：按 0x000001 / 0x00000001 起始码切分 NAL。
     * 返回的每个 NAL 包含 NAL 头字节，不含起始码；空 NAL 会被丢弃。
     */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var nalStart = -1
        var i = 0
        while (i < data.size) {
            val startCodeLen = when {
                i + 3 < data.size &&
                    data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                i + 2 < data.size &&
                    data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte() -> 3
                else -> 0
            }
            if (startCodeLen > 0) {
                if (nalStart >= 0 && i > nalStart) {
                    result.add(data.copyOfRange(nalStart, i))
                }
                nalStart = i + startCodeLen
                i = nalStart
            } else {
                i++
            }
        }
        if (nalStart >= 0 && nalStart < data.size) {
            result.add(data.copyOfRange(nalStart, data.size))
        } else if (nalStart < 0 && data.isNotEmpty()) {
            // 没有起始码：整体视为单个 NAL（兼容裸 NAL 输入）
            result.add(data.copyOf())
        }
        return result
    }

    /**
     * 把一帧的 NAL 列表打包成 RTP 数据报；同一帧共用一个 timestamp，
     * 帧的最后一个包 Marker=1；调用后内部 timestamp 按 [timestampStep] 推进。
     */
    fun packetizeFrame(nals: List<ByteArray>): List<ByteArray> {
        val ts = timestamp
        timestamp = (timestamp + timestampStep) and 0xFFFFFFFFL
        return packetizeFrame(nals, ts)
    }

    /**
     * 同上，但使用调用方指定的 timestamp（例如由编码器 pts 换算而来）。
     */
    fun packetizeFrame(nals: List<ByteArray>, timestamp: Long): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val nonEmpty = nals.filter { it.isNotEmpty() }
        nonEmpty.forEachIndexed { index, nal ->
            val isLastNal = index == nonEmpty.lastIndex
            if (nal.size <= maxPayloadSize) {
                packets.add(buildPacket(nal, marker = isLastNal, timestamp = timestamp))
            } else {
                val frags = fragmentFuA(nal)
                frags.forEachIndexed { fragIndex, frag ->
                    packets.add(
                        buildPacket(
                            frag,
                            marker = isLastNal && fragIndex == frags.lastIndex,
                            timestamp = timestamp
                        )
                    )
                }
            }
        }
        return packets
    }

    /**
     * FU-A 分片：FU indicator = (F|NRI) | 28；FU header = S|E|R | 原 NAL type；
     * 分片数据为原 NAL 去掉头字节后的部分。
     */
    private fun fragmentFuA(nal: ByteArray): List<ByteArray> {
        val nalHeader = nal[0].toInt() and 0xFF
        val fuIndicator = ((nalHeader and 0xE0) or 28).toByte()
        val nalType = nalHeader and 0x1F
        val maxFragData = maxPayloadSize - 2 // 去掉 FU indicator + FU header
        val body = nal.copyOfRange(1, nal.size)
        val frags = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < body.size) {
            val end = minOf(offset + maxFragData, body.size)
            val isFirst = offset == 0
            val isLast = end == body.size
            val fuHeader = (
                (if (isFirst) 0x80 else 0) or
                    (if (isLast) 0x40 else 0) or
                    nalType
                ).toByte()
            val frag = ByteArray(2 + (end - offset))
            frag[0] = fuIndicator
            frag[1] = fuHeader
            body.copyInto(frag, 2, offset, end)
            frags.add(frag)
            offset = end
        }
        return frags
    }

    private fun buildPacket(payload: ByteArray, marker: Boolean, timestamp: Long): ByteArray {
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte() // V=2, P=0, X=0, CC=0
        packet[1] = ((if (marker) 0x80 else 0) or (payloadType and 0x7F)).toByte()
        packet[2] = (sequence shr 8).toByte()
        packet[3] = sequence.toByte()
        val ts = timestamp and 0xFFFFFFFFL
        packet[4] = (ts shr 24).toByte()
        packet[5] = (ts shr 16).toByte()
        packet[6] = (ts shr 8).toByte()
        packet[7] = ts.toByte()
        packet[8] = (ssrc shr 24).toByte()
        packet[9] = (ssrc shr 16).toByte()
        packet[10] = (ssrc shr 8).toByte()
        packet[11] = ssrc.toByte()
        payload.copyInto(packet, 12)
        sequence = (sequence + 1) and 0xFFFF
        return packet
    }
}
