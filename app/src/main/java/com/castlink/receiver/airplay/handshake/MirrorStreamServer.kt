/*
 * Ported from PhairPlay (https://github.com/mazer666/PhairPlay, Apache-2.0);
 * package renamed to com.castlink.receiver.airplay.*. See THIRD-PARTY-NOTICES.
 */
package com.castlink.receiver.airplay.handshake

import android.view.Surface
import com.castlink.receiver.airplay.StreamStats
import com.castlink.receiver.airplay.VideoDecoder
import com.castlink.receiver.airplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * MirrorStreamServer — receives and decodes the AirPlay 2 mirroring video stream.
 *
 * macOS connects to [dataPort] and sends [128-byte header][payload] packets:
 *  - payload_size = little-endian int at header offset 0
 *  - payload_type = little-endian short at header offset 4, low byte
 *      • type 1: unencrypted avcC (SPS/PPS) → (re)configure the decoder
 *      • type 0: AES-CTR-encrypted H.264 (AVCC) → decrypt, reassemble complete NAL
 *        units (a NAL may span packets), convert to Annex-B, decode
 *
 * Architecture: a network thread reads + decrypts packets (keeping the AES-CTR keystream strictly
 * ordered) and pushes work onto a bounded queue; a separate decoder thread consumes it. This way
 * the socket is always drained fast — if the decoder can't keep up (this SoC is modest, and the
 * audio codec competes for resources), frames are dropped from the queue instead of stalling the
 * socket, which previously caused macOS to drop the whole session.
 *
 * Reference: RPiPlay lib/raop_rtp_mirror.c (raop_rtp_mirror_thread).
 */
class MirrorStreamServer(
    aesKey: ByteArray,
    ecdhSecret: ByteArray,
    streamConnectionId: Long,
    private val surfaceProvider: () -> Surface?,
    private val width: Int = 1920,
    private val height: Int = 1080,
) {
    private sealed class Item
    private class Config(val sps: ByteArray, val pps: ByteArray) : Item()
    private class Frame(val annexB: ByteArray) : Item()

    private val cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, streamConnectionId)
    // AVCC 重组缓冲（仅 reader 线程使用）：一个 NAL 单元可能被拆到多个 type-0 包里
    // （1080p 关键帧常超单包）。旧代码按包直转 Annex-B：前半包因长度不够整包丢弃，
    // 后半包把续传数据当长度读也是垃圾 → 关键帧永远到不了，之后 P 帧缺参考帧被
    // 解码器静默丢弃（实测 in=300 out=10 后永久卡住）。这里先攒解密字节、按长度
    // 字段拼出完整 NAL 再入队；不完整的一律留待后续包补齐。
    private val pendingAvcc = java.io.ByteArrayOutputStream()
    // avcC 记录 byte 4 低 2 位 = lengthSizeMinusOne（iOS 固定 4；无配置前默认 4）
    private var nalLengthSize = 4
    private var reassembledNals = 0
    // OS-assigned free port, explicit IPv4 wildcard — see RtspHandler.bindRtspSocket() for why.
    private val serverSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("0.0.0.0"))
    private val queue = ArrayBlockingQueue<Item>(QUEUE_CAPACITY)

    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    @Volatile private var decoder: VideoDecoder? = null   // owned by the decoder thread
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null
    // The Surface the current decoder was built against. The SurfaceView destroys its Surface when
    // the app backgrounds and creates a NEW one on return, so we watch for the identity changing
    // and rebuild the decoder — otherwise video stays black after foregrounding.
    @Volatile private var configuredSurface: Surface? = null
    private var framePtsUs = 0L
    private var framesIn = 0
    private var framesDropped = 0
    private var lastStatMs = 0L
    // Set by the reader thread when a frame is dropped under load; the decoder thread then skips
    // frames until the next keyframe (IDR) so it never decodes a reference-broken, corrupt stream.
    @Volatile private var awaitingKeyframe = false
    // Type-5 diagnostic (v0.1.51): type-5 payloads are ignored today (same as the reference
    // 0/1-only handling), but their size jumps to a constant ~25 KB exactly when output
    // freezes — log the raw head of the first few per session so the next test can tell
    // whether they are keyframes the receiver is throwing away. Raw bytes only, no cipher
    // touch: type-5 is not in the video cipher stream (video rendered past early type-5s),
    // so reading it cannot desync decryption.
    private var type5Logged = 0
    // 输出卡死看门狗（reader 线程置位、decoder 线程执行）：解码器一旦进入“吃输入不
    // 出帧”的 wedged 状态（实测 in=600 out=101，队列/Surface 均正常，无报错无丢帧），
    // 换播也救不回来；此时重建解码器并在下一个 IDR 处重同步，把永久冻帧降级为一次
    // 短暂闪断。5s 冷却避免码流本身不可解时反复重建刷屏。
    @Volatile private var stallRecreateRequested = false
    @Volatile private var lastStallRequestMs = 0L
    private var lastStatsOut = -1L
    // 统计区间内喂给解码线程的 IDR / 带内 SPS 数量（decoder 线程写、reader 线程读）
    // ——下次冻帧时直接区分“手机没发关键帧”与“解码器 wedged”。
    @Volatile private var idrFed = 0
    @Volatile private var spsFed = 0

    /** The OS-assigned TCP port macOS should connect to (returned in the SETUP response). */
    val dataPort: Int get() = serverSocket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) { runReader() }
        scope.launch(Dispatchers.IO) { runDecoder() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { serverSocket.close() }
        queue.clear()
        Logger.i("MirrorStreamServer stopped")
    }

    // ─── Network reader thread: read + decrypt (ordered), enqueue, never block on decode ──────
    private fun runReader() {
        try {
            Logger.i("MirrorStreamServer listening on data port $dataPort")
            val socket = serverSocket.accept().also { client = it }
            Logger.i("Mirror data connection from ${socket.inetAddress.hostAddress}")
            val input = socket.getInputStream()
            val header = ByteArray(128)
            while (running && !socket.isClosed) {
                if (!readFully(input, header, 128)) break
                val payloadSize = leInt(header, 0)
                val payloadType = leShort(header, 4) and 0xFF
                if (payloadSize <= 0 || payloadSize > MAX_PAYLOAD) {
                    Logger.w("Mirror: bad payloadSize=$payloadSize type=$payloadType — stopping")
                    break
                }
                val payload = ByteArray(payloadSize)
                if (!readFully(input, payload, payloadSize)) break
                when (payloadType) {
                    0 -> {
                        // ALWAYS advance the AES-CTR keystream, in order, for every video payload —
                        // skipping any packet desyncs the keystream and corrupts all later frames.
                        // 解密后不直接转 Annex-B：先经重组缓冲拼出完整 NAL（见 pendingAvcc 注释）。
                        val annexB = extractCompleteNals(cipher.update(payload))
                        if (annexB.isNotEmpty()) enqueue(Frame(annexB))
                    }
                    1 -> parseConfig(payload)?.let { enqueue(it) }
                    else -> {
                        if (payloadType == 5 && type5Logged < 3) {
                            type5Logged++
                            val head = payload.take(8).joinToString(" ") { "%02x".format(it) }
                            Logger.i("Mirror: type 5 payload #$type5Logged head=[$head] size=$payloadSize")
                        } else {
                            Logger.v("Mirror: ignoring payload type $payloadType ($payloadSize B)")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror reader error", e)
        } finally {
            running = false
            Logger.i("Mirror data connection ended")
        }
    }

    /** Bounded enqueue — if the decoder is behind, drop the oldest item to keep latency bounded. */
    private fun enqueue(item: Item) {
        framesIn++
        if (!queue.offer(item)) {
            queue.poll()
            queue.offer(item)
            framesDropped++
            awaitingKeyframe = true        // a frame was lost — resync the decoder at the next IDR
        }
        StreamStats.videoQueue = queue.size
        if (framesIn % 300 == 0) {
            val now = System.currentTimeMillis()
            if (lastStatMs != 0L) StreamStats.videoFps = (300_000L / (now - lastStatMs).coerceAtLeast(1)).toInt()
            lastStatMs = now
            StreamStats.videoDropPct = framesDropped * 100 / framesIn
            // out= 是解码器真正渲染到 Surface 的帧数：in 涨而 out 不涨 = 解码器/Surface
            // 环节卡死（画面冻住但网络日志一切正常），这是定位显示类冻帧的关键判据。
            val out = decoder?.framesOut ?: -1L
            val stall = if (out >= 0 && out < framesIn - 120) " NO-OUTPUT-STALL" else ""
            // pend= 重组缓冲残留（拆包 NAL 暂存）；surf= 当前 Surface 是否存活——
            // out 冻结时先看 surf：若 surface 已死（例如用户切到了日志页），out 不涨是
            // 预期行为而非解码器故障。
            val surf = if (surfaceProvider() != null) "ok" else "lost"
            // 看门狗：out 在整整一个统计区间（300 帧）里纹丝不动 = 真卡死，请求
            // decoder 线程重建（冷却期内只请求一次）。
            if (out >= 0 && out < framesIn - 120 && out == lastStatsOut &&
                System.currentTimeMillis() - lastStallRequestMs > STALL_RECREATE_COOLDOWN_MS) {
                stallRecreateRequested = true
                lastStallRequestMs = System.currentTimeMillis()
                Logger.w("Mirror: output stall (in=$framesIn out=$out, unchanged) — requesting decoder recreate")
            }
            lastStatsOut = out
            Logger.i("Video stats: in=$framesIn out=$out dropped=$framesDropped " +
                "(${StreamStats.videoDropPct}%) queue=${queue.size}/$QUEUE_CAPACITY " +
                "${StreamStats.videoFps}fps nal=$reassembledNals pend=${pendingAvcc.size()}B surf=$surf " +
                "idr=$idrFed sps=$spsFed$stall")
            idrFed = 0
            spsFed = 0
        }
    }

    /**
     * 从解密 AVCC 字节流中提取全部完整 NAL（转 Annex-B）。不完整的尾巴留在
     * [pendingAvcc] 里等后续包补齐；长度字段损坏时清缓冲并等下一个关键帧重同步，
     * 避免一次损坏永久卡住重组。
     */
    private fun extractCompleteNals(decrypted: ByteArray): ByteArray {
        pendingAvcc.write(decrypted)
        val buf = pendingAvcc.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        val ls = nalLengthSize
        var i = 0
        while (i + ls <= buf.size) {
            var len = 0
            for (k in 0 until ls) len = (len shl 8) or (buf[i + k].toInt() and 0xFF)
            if (len <= 0 || len > MAX_PAYLOAD) {
                Logger.w("Mirror: bad NAL length $len (lenSize=$ls) — dropping ${buf.size}B " +
                    "pending, awaiting keyframe")
                pendingAvcc.reset()
                awaitingKeyframe = true
                return out.toByteArray()
            }
            if (i + ls + len > buf.size) break // NAL 被拆包：等后续包补齐
            out.write(MirrorCrypto.START_CODE)
            out.write(buf, i + ls, len)
            reassembledNals++
            i += ls + len
        }
        pendingAvcc.reset()
        if (i < buf.size) pendingAvcc.write(buf, i, buf.size - i)
        if (pendingAvcc.size() > MAX_PAYLOAD) {
            Logger.w("Mirror: pending NAL never completed (${pendingAvcc.size()}B) — dropping, " +
                "awaiting keyframe")
            pendingAvcc.reset()
            awaitingKeyframe = true
        }
        return out.toByteArray()
    }

    private fun parseConfig(payload: ByteArray): Config? = try {
        // avcC byte 4 低 2 位 = NAL 长度字段字节数 - 1（标准 4；异常值时保持默认 4）
        val lsFromConfig = (payload[4].toInt() and 0x03) + 1
        if (lsFromConfig == 1 || lsFromConfig == 2 || lsFromConfig == 4) {
            nalLengthSize = lsFromConfig
        }
        val spsSize = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        val sps = payload.copyOfRange(8, 8 + spsSize)
        val ppsLenOffset = 8 + spsSize + 1                   // skip the 1-byte PPS count
        val ppsSize = ((payload[ppsLenOffset].toInt() and 0xFF) shl 8) or
            (payload[ppsLenOffset + 1].toInt() and 0xFF)
        Config(sps, payload.copyOfRange(ppsLenOffset + 2, ppsLenOffset + 2 + ppsSize))
    } catch (e: Exception) {
        Logger.e("Mirror: failed to parse SPS/PPS", e); null
    }

    // ─── Decoder thread: consume the queue; the only thread that touches the decoder ──────────
    private fun runDecoder() {
        try {
            while (running) {
                if (stallResetRequested) {
                    stallResetRequested = false
                    queue.clear()
                    rebuildDecoder(surfaceProvider())
                    Logger.i("Mirror: decoder reset after output stall (awaiting keyframe)")
                }
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                when (item) {
                    is Config -> configureDecoder(item.sps, item.pps)
                    is Frame -> decodeFrame(item.annexB)
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror decoder thread error", e)
        } finally {
            decoder?.release()
            decoder = null
        }
    }

    private fun configureDecoder(sps: ByteArray, pps: ByteArray) {
        // New SPS/PPS (or first config) — cache it and (re)build against the current surface.
        val d = decoder
        val surface = awaitSurface()
        if (d != null && d.isHealthy && sps.contentEquals(lastSps) && pps.contentEquals(lastPps) &&
            surface === configuredSurface) return
        lastSps = sps
        lastPps = pps
        rebuildDecoder(surface)
    }

    /**
     * (Re)creates the decoder for [surface] from the cached SPS/PPS. Safe to call on a surface
     * change (app background→foreground): releases the old decoder and resyncs at the next keyframe.
     * If the surface or config isn't available yet, leaves the decoder null and retries on a later
     * frame (frames are dropped until then).
     */
    private fun rebuildDecoder(surface: Surface?) {
        decoder?.release()
        decoder = null
        configuredSurface = surface
        val sps = lastSps ?: return
        val pps = lastPps ?: return
        if (surface == null) return                            // backgrounded — wait for the surface to return
        val sc = MirrorCrypto.START_CODE
        decoder = VideoDecoder(surface).also { it.initialize(sc + sps, sc + pps, width, height) }
        awaitingKeyframe = true                                // a fresh decoder must start at an IDR
        StreamStats.videoRes = "${width}x${height}"
        Logger.i("Mirror decoder (re)built for surface (sps=${sps.size}B pps=${pps.size}B)")
    }

    private fun decodeFrame(annexB: ByteArray) {
        // Re-attach to the live Surface if it changed (the app was backgrounded and returned, so the
        // SurfaceView made a new Surface). Without this, video stays black after foregrounding.
        val liveSurface = surfaceProvider()
        if (liveSurface !== configuredSurface) {
            Logger.i("Mirror: surface ${if (liveSurface == null) "lost" else "changed"} — re-attaching decoder")
            rebuildDecoder(liveSurface)
        }
        // 看门狗：输出卡死时重建解码器（见字段注释；decoder 线程执行）。
        if (stallRecreateRequested) {
            stallRecreateRequested = false
            Logger.i("Mirror: recreating decoder after output stall " +
                "(in=$framesIn out=${decoder?.framesOut})")
            rebuildDecoder(liveSurface)
        }
        // NAL 普查：记 IDR / 带内 SPS，并检测带内 SPS 变化。换播或旋转导致编码参数
        // 变化时，手机可能只在流内发新 SPS 而不发 type-1 配置；旧解码器按旧参数解新流
        // 会静默不出帧——此时用新 SPS/PPS 重建才是正解。SPS 相同是常态，不触发。
        // 普查只是诊断，绝不能抛异常杀死解码线程。
        runCatching {
            val census = censusNals(annexB)
            if (census.idr > 0) idrFed += census.idr
            val newSps = census.sps
            if (newSps != null && newSps.isNotEmpty()) {
                spsFed++
                val cur = decoder
                if (cur != null && lastSps != null && !newSps.contentEquals(lastSps)) {
                    Logger.i("Mirror: in-band SPS changed (${lastSps!!.size}B -> ${newSps.size}B) — rebuilding decoder")
                    lastSps = newSps
                    if (census.pps != null) lastPps = census.pps
                    rebuildDecoder(liveSurface)
                }
            }
        }
        val d = decoder ?: return                              // need surface + SPS/PPS first
        if (!d.isHealthy) {                                    // error state — drop, await next config
            Logger.w("Mirror: decoder unhealthy — dropping, awaiting new SPS/PPS")
            d.release(); decoder = null; configuredSurface = null; lastSps = null; lastPps = null
            return
        }
        if (awaitingKeyframe) {
            // After a dropped frame the stream is reference-broken; skip until the next IDR so we
            // don't feed the decoder predicted frames with missing references (which smear/blocky).
            if (!isKeyframe(annexB)) return
            awaitingKeyframe = false
            Logger.i("Mirror: resynced on keyframe after a dropped frame")
        }
        if (framePtsUs == 0L) Logger.i("Mirror: first video frame fed to decoder (${annexB.size}B)")
        d.decodeNalUnit(annexB, framePtsUs)
        framePtsUs += FRAME_INTERVAL_US
    }

    /** 单次扫描 Annex-B 块的结果：IDR 数量、首个 SPS 与首个 PPS 的原始字节。 */
    private class NalCensus(val idr: Int, val sps: ByteArray?, val pps: ByteArray?)

    /**
     * 单次扫描 Annex-B 块，统计 IDR 数量并提取首个 SPS/PPS。起始码按 00 00 01 识别
     * （4 字节码的后 3 字节同样命中，类型字节位置不变）；提取的 SPS/PPS 会去掉尾部
     * 0x00——4 字节起始码的首字节会被算进上一个 NAL 的尾巴，RBSP 尾部停比特保证
     * 真实 NAL 末字节恒非零，所以尾部 0x00 只可能是起始码前缀或 cabac 填充，去掉
     * 后比较才不会把同一个 SPS 误判为“变化”。
     */
    private fun censusNals(annexB: ByteArray): NalCensus {
        var idr = 0
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var curHeader = -1
        var curType = -1
        fun flush(end: Int) {
            if (curHeader < 0 || end <= curHeader) return
            when (curType) {
                5 -> idr++
                7 -> if (sps == null) sps = trimTrailingZeros(annexB.copyOfRange(curHeader, end))
                8 -> if (pps == null) pps = trimTrailingZeros(annexB.copyOfRange(curHeader, end))
            }
        }
        var i = 0
        while (i + 2 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                flush(i)
                val header = i + 3
                curHeader = header
                curType = if (header < annexB.size) annexB[header].toInt() and 0x1F else -1
                i = header
            } else {
                i++
            }
        }
        flush(annexB.size)
        return NalCensus(idr, sps, pps)
    }

    private fun trimTrailingZeros(data: ByteArray): ByteArray {
        var end = data.size
        while (end > 0 && data[end - 1].toInt() == 0) end--
        return if (end == data.size) data else data.copyOf(end)
    }

    /** True if the Annex-B frame contains an IDR NAL unit (type 5) — a decodable resync point. */
    private fun isKeyframe(annexB: ByteArray): Boolean {
        var i = 0
        while (i + 3 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                if ((annexB[i + 3].toInt() and 0x1F) == 5) return true   // IDR slice
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    /** The streaming Surface appears shortly after CONNECTED is emitted; poll briefly. */
    private fun awaitSurface(): Surface? {
        repeat(SURFACE_WAIT_TRIES) {
            if (!running) return null
            surfaceProvider()?.let { return it }
            try { Thread.sleep(SURFACE_WAIT_MS) } catch (_: InterruptedException) { return null }
        }
        return surfaceProvider()
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n == -1) return false
            read += n
        }
        return true
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    companion object {
        private const val MAX_PAYLOAD = 8 * 1024 * 1024        // 8 MB sanity cap per frame
        private const val FRAME_INTERVAL_US = 1_000_000L / 60  // monotonic PTS hint (~60fps)
        private const val QUEUE_CAPACITY = 90                  // ~1.5s @60fps before dropping
        private const val STALL_RECREATE_COOLDOWN_MS = 5000L   // 看门狗重建冷却（见字段注释）
        private const val SURFACE_WAIT_TRIES = 50
        private const val SURFACE_WAIT_MS = 100L
    }
}
