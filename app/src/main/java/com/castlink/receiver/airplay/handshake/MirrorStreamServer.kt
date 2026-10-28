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
    // reader 线程写、decoder 线程的看门狗读——@Volatile 保证可见性（单写者，无竞态）。
    @Volatile private var framesIn = 0
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
    // 输出卡死看门狗（decoder 线程，时间驱动，见 checkStallWatchdog）：解码器一旦
    // 进入“吃输入不出帧”的 wedged 状态（实测 in=600 out=101，队列/Surface 均正常，
    // 无报错无丢帧），换播也救不回来；此时重建解码器并在下一个 IDR 处重同步，把
    // 永久冻帧降级为一次短暂闪断。时间驱动而非帧数驱动——静态画面发帧少时，300 帧
    // 一次的统计行可能很久不打印，帧数驱动的看门狗永远不会触发。5s 冷却避免码流
    // 本身不可解时反复重建刷屏。
    private var lastWatchdogOut = -1L
    private var lastOutAdvanceMs = 0L
    private var lastWatchdogRecreateMs = 0L
    private var lastPeriodicStatMs = 0L
    // 软解兜底：硬解看门狗重建两次仍无输出（且本会话手机不发 IDR，见 idr=0），
    // 说明问题可能在硬解本身，改走系统软解再试；软解初始化失败则回落硬解。
    private var useSoftwareDecoder = false
    private var watchdogRebuildCount = 0
    // 本会话累计 IDR 数（不清零）：看门狗触发时据此判断“手机到底发没发过关键帧”。
    private var idrTotal = 0
    // 已打印 NAL 构成的包数（decoder 线程）：只打前 3 个包，确认首包结构。
    private var loggedBlobCount = 0
    // 是否已打印首个 IDR 的头字节（decoder 线程）：只打一次。
    private var firstIdrLogged = false
    // 视频重连是否已触发（decoder 线程）：软解也救不回来时，关闭视频数据连接
    // 一次，迫使手机重新 SETUP 视频流、发新关键帧；音频是独立服务，不受影响。
    // 每会话一次；重 SETUP 会建全新 MirrorStreamServer，自带新 flag。
    private var videoRestartAttempted = false
    // 解码器侧丢帧数（输入缓冲超限/超时/异常），与队列丢帧 framesDropped 区分打印。
    @Volatile private var decoderDropped = 0
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
            logStats()
        }
    }

    /**
     * 时间驱动的卡死看门狗 + 周期统计（decoder 线程，每轮循环调用）。
     * 触发条件：解码器已存在、out 落后 in 超 120 帧、且 out 已 2s 以上纹丝不动——
     * 静态画面发帧少时帧数驱动的统计行可能很久不打印，时间驱动才能覆盖这种
     * 情况。触发后重建解码器并在下一个 IDR 处重同步（rebuildDecoder 置
     * awaitingKeyframe）；5s 冷却避免码流本身不可解时反复重建刷屏。
     * 周期统计：静态画面下 300 帧一次的统计行可能很久不打印，每 10s 补一行，
     * 避免“没有 in=600 行”被误读为流停了。
     */
    private fun checkStallWatchdog() {
        val now = System.currentTimeMillis()
        val d = decoder
        if (d == null) {
            // 解码器为空（初始化失败或 Surface 丢失后）：有配置 + Surface 时定期重试
            // 重建，否则一次初始化失败就永久黑屏。
            if (lastSps != null && surfaceProvider() != null &&
                now - lastWatchdogRecreateMs > STALL_RECREATE_COOLDOWN_MS) {
                lastWatchdogRecreateMs = now
                Logger.i("Mirror: decoder missing — retrying rebuild")
                rebuildDecoder(surfaceProvider())
            }
            return
        }
        val out = d.framesOut
        if (out != lastWatchdogOut) {
            lastWatchdogOut = out
            lastOutAdvanceMs = now
        } else if (framesIn - out > STALL_GAP_FRAMES && now - lastOutAdvanceMs > STALL_QUIET_MS &&
            now - lastWatchdogRecreateMs > STALL_RECREATE_COOLDOWN_MS) {
            lastWatchdogRecreateMs = now
            watchdogRebuildCount++
            if (watchdogRebuildCount >= 2 && !useSoftwareDecoder) {
                // 硬解已两次重建无果：换软解再试（软解初始化失败时 rebuildDecoder
                // 会自动回落硬解）。
                useSoftwareDecoder = true
                Logger.w("Mirror: output stall (in=$framesIn out=$out, sessionIdr=$idrTotal) — hardware decoder stalled twice, switching to software AVC decoder")
            } else {
                Logger.w("Mirror: output stall (in=$framesIn out=$out, quiet ${now - lastOutAdvanceMs}ms, sessionIdr=$idrTotal) — recreating decoder")
            }
            if (watchdogRebuildCount >= 4 && !videoRestartAttempted) {
                // 软解已重建两次仍无输出：参考帧本身已坏（截断 IDR + 无第二 IDR），
                // 再重建解码器没有意义。关闭视频数据连接是接收端唯一能让手机重新
                // SETUP 视频流、发新关键帧的触发；音频独立，不受影响。本会话一次。
                // 若手机不重 SETUP，视频维持原状（本来就已冻住），不更差。
                videoRestartAttempted = true
                Logger.w("Mirror: video frozen after software fallback (in=$framesIn out=$out, sessionIdr=$idrTotal) — closing video data connection to force phone to re-SETUP video with a fresh keyframe")
                runCatching { client?.close() }
                return
            }
            rebuildDecoder(surfaceProvider())
        }
        if (lastPeriodicStatMs == 0L) lastPeriodicStatMs = now
        if (now - lastPeriodicStatMs > PERIODIC_STAT_MS && framesIn > 0) {
            lastPeriodicStatMs = now
            logStats()
        }
    }

    /**
     * 打印一次视频统计行。out= 是解码器真正渲染到 Surface 的帧数：in 涨而 out
     * 不涨 = 解码器/Surface 环节卡死（画面冻住但网络日志一切正常），这是定位显示
     * 类冻帧的关键判据。pend= 重组缓冲残留；surf= 当前 Surface 是否存活（若已死，
     * 例如用户切到了日志页，out 不涨是预期行为而非解码器故障）。
     */
    private fun logStats() {
        val out = decoder?.framesOut ?: -1L
        val stall = if (out >= 0 && out < framesIn - 120) " NO-OUTPUT-STALL" else ""
        val surf = if (surfaceProvider() != null) "ok" else "lost"
        Logger.i("Video stats: in=$framesIn out=$out dropped=$framesDropped decDrop=$decoderDropped " +
            "(${StreamStats.videoDropPct}%) queue=${queue.size}/$QUEUE_CAPACITY " +
            "${StreamStats.videoFps}fps nal=$reassembledNals pend=${pendingAvcc.size()}B surf=$surf " +
            "idr=$idrFed sps=$spsFed$stall")
        idrFed = 0
        spsFed = 0
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
                // 时间驱动的卡死看门狗 + 周期统计（见 checkStallWatchdog 注释）。
                checkStallWatchdog()
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
        // 先无阻塞判断“配置未变”：重复的 type-1 配置是常态，绝不能每次都进
        // awaitSurface()——Surface 短暂为 null 时它会在解码线程上阻塞最长 5s，
        // 期间 reader 继续填 90 槽队列并丢最老项（常是 IDR），还置 awaitingKeyframe。
        // 只有真正要重建时才等 Surface；Surface 已在手时直接用，不等待。
        val d = decoder
        val surface = surfaceProvider()
        if (d != null && d.isHealthy && sps.contentEquals(lastSps) && pps.contentEquals(lastPps) &&
            surface === configuredSurface) return
        lastSps = sps
        lastPps = pps
        rebuildDecoder(surface ?: awaitSurface())
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
        try {
            // csd 必须是裸 NAL（RAOP 路径就是这么传的）；镜像路径之前传的是带起始码
            // 的形式，严格的解码器可能因此认不出配置、永远解不出帧——这正是本会话
            // 全程 out=0 的最可能解释。改成裸 NAL，与 RAOP 路径一致。
            decoder = VideoDecoder(surface, preferSoftware = useSoftwareDecoder).also { it.initialize(sps, pps, width, height) }
        } catch (e: Exception) {
            // 初始化失败（例如软解不支持该格式）绝不能杀死解码线程：保持 decoder=null，
            // 看门狗的空闲重建会定期重试；软解失败时下次重试回落硬解。
            Logger.e("Mirror: decoder initialize failed — will retry", e)
            if (useSoftwareDecoder) {
                useSoftwareDecoder = false
                Logger.w("Mirror: software decoder init failed — falling back to hardware on retry")
            }
            decoder = null
            return
        }
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
        // NAL 普查：记 IDR / 带内 SPS，并检测带内 SPS 变化。换播或旋转导致编码参数
        // 变化时，手机可能只在流内发新 SPS 而不发 type-1 配置；旧解码器按旧参数解新流
        // 会静默不出帧——此时用新 SPS/PPS 重建才是正解。SPS 相同是常态，不触发。
        // 普查只是诊断，绝不能抛异常杀死解码线程。
        runCatching {
            val census = censusNals(annexB)
            if (census.idr > 0) { idrFed += census.idr; idrTotal += census.idr }
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
        // 首个 IDR 的头字节（诊断用）：v0.1.54 实测开流 IDR 仅 418B，而用户播的
        // 是视频——418B 对 1080p 来说连纯黑画面都不够，这个 IDR 一定有问题。
        // 头字节可手算 slice header（slice_type / pps_id / first_mb），判断它是
        // 真 IDR、畸形数据，还是引用了未提供的 PPS。只打一次。
        if (!firstIdrLogged) {
            runCatching {
                val hex = firstIdrHeadHex(annexB)
                if (hex != null) {
                    firstIdrLogged = true
                    Logger.i("Mirror: first IDR head bytes: $hex")
                }
            }
        }
        // 前 3 个包的 NAL 构成（诊断用）：确认首个包里到底有没有 SPS+PPS+IDR，
        // 避免下次再靠猜。扫描方式同 censusNals。
        if (loggedBlobCount < 3) {
            loggedBlobCount++
            runCatching {
                Logger.i("Mirror: blob #$loggedBlobCount NALs: ${nalInfo(annexB)} (${annexB.size}B)")
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
        // IDR 前补一份带内 SPS/PPS（标准码流本就如此）：即使解码器不认 csd，
        // 也能从流内拿到配置。IDR 本来就稀少（本会话仅 1 个），开销可忽略。
        val feedBlob = if (isKeyframe(annexB)) {
            val sps = lastSps
            val pps = lastPps
            if (sps != null && pps != null) {
                val sc = MirrorCrypto.START_CODE
                sc + sps + sc + pps + annexB
            } else annexB
        } else annexB
        // 解码器丢帧（超限/超时/异常）即断参考链：必须等下一个 IDR，否则后续 P 帧
        // 只会让解码器静默不出帧。
        if (!d.decodeNalUnit(feedBlob, framePtsUs)) {
            decoderDropped++
            awaitingKeyframe = true
        }
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

    /** 首个 IDR NAL 的前 16 字节 hex（诊断用）：可手算 slice header，判断它是真 IDR
     * 还是畸形数据。找不到 IDR 时返回 null。注意字节要 & 0xFF，否则 %02x 会把负字节
     * 打成 ffffff88 这种符号扩展形式。 */
    private fun firstIdrHeadHex(annexB: ByteArray): String? {
        var curHeader = -1
        var i = 0
        while (i + 2 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                if (curHeader >= 0 && annexB[curHeader].toInt() and 0x1F == 5) {
                    val end = minOf(i, curHeader + 16)
                    return annexB.slice(curHeader until end).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
                }
                val header = i + 3
                curHeader = if (header < annexB.size) header else -1
                i = header
            } else {
                i++
            }
        }
        if (curHeader >= 0 && annexB[curHeader].toInt() and 0x1F == 5) {
            val end = minOf(annexB.size, curHeader + 16)
            return annexB.slice(curHeader until end).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        }
        return null
    }

    /** 把 Annex-B 块的 NAL 构成格式化为 "7:18B, 8:4B, 5:9123B, ..."（诊断首包结构用）。 */
    private fun nalInfo(annexB: ByteArray): String {
        val parts = ArrayList<String>()
        var curHeader = -1
        var i = 0
        fun flush(end: Int) {
            if (curHeader < 0 || end <= curHeader) return
            val type = annexB[curHeader].toInt() and 0x1F
            parts.add("$type:${end - curHeader}B")
        }
        while (i + 2 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                flush(i)
                val header = i + 3
                curHeader = if (header < annexB.size) header else -1
                i = header
            } else {
                i++
            }
        }
        flush(annexB.size)
        return if (parts.isEmpty()) "(no start codes)" else parts.joinToString(", ")
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
        private const val STALL_GAP_FRAMES = 120               // 看门狗：out 落后 in 的帧数阈值
        private const val STALL_QUIET_MS = 2000L               // 看门狗：out 纹丝不动的时长阈值
        private const val STALL_RECREATE_COOLDOWN_MS = 5000L   // 看门狗重建冷却（见字段注释）
        private const val PERIODIC_STAT_MS = 10000L            // 周期统计间隔（静态画面补统计行）
        private const val SURFACE_WAIT_TRIES = 50
        private const val SURFACE_WAIT_MS = 100L
    }
}
