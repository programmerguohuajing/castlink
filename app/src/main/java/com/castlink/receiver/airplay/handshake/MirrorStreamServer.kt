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
 * ordered) and pushes work onto a bounded queue; a separate decoder thread consumes it. When the
 * decoder can't keep up, the reader BLOCKS on the full queue (backpressure): TCP flow control
 * throttles the sender to our consumption rate, and any frame skipping happens inside the sender's
 * encoder, which keeps the encoded chain gapless. The old behaviour (drop the oldest queued frame)
 * broke the reference chain — and with only one IDR per session plus a keyframe gate, a single
 * dropped frame froze the picture forever (software decode ~25fps vs ~30fps input made the queue
 * grow to full, drop once, then gate everything).
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
    // 软解被确认可用时回调一次（AirPlayReceiver 据此把“本机硬解不行”持久化，重启后仍生效）。
    private val onSoftwareConfirmed: (() -> Unit)? = null,
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
    // PTS 取“真实到达时间”（自喂第一帧起的微秒数，严格递增）。此前用固定 60fps 步长
    // （16.7ms/帧）合成，而 iPhone 实际约 24fps：合成 PTS 越来越落后于挂钟，部分 TV 解码器
    // 会把“迟到太多”的帧直接丢弃——表现正是开头出十几帧后永久无输出（实测 out=10/13）。
    // 用挂钟时间则帧永远不“迟到”。重放缓存帧时同样按重放当下的挂钟取 PTS。
    private var ptsBaseNs = 0L
    private var lastPtsUs = 0L
    // reader 线程写、decoder 线程的看门狗读——@Volatile 保证可见性（单写者，无竞态）。
    @Volatile private var framesIn = 0
    private var framesDropped = 0
    private var lastStatMs = 0L
    // 码流缺口标志（reader 线程置位、decoder 线程消费）：NAL 长度损坏、重组缓冲超限
    // 意味着流里少了东西——GOP 缓存必须作废（不可重放）。解码器本身不再等 IDR：
    // 本协议整会话只有 1 个 IDR，“等关键帧”等于永久冻帧（v0.1.58 实测）；缺参考的
    // P 帧照喂，靠解码器纠错 + 帧内刷新逐步恢复，看门狗/重放兜底。
    @Volatile private var streamBroken = false
    // 最近一个 IDR 起的全部视频块（decoder 线程专用）：解码器重建后据此重放，不依赖手机
    // 再发 IDR（见 GopCache）。
    private val gop = GopCache()
    // Type-5 diagnostic (v0.1.51): type-5 payloads are ignored today (same as the reference
    // 0/1-only handling), but their size jumps to a constant ~25 KB exactly when output
    // freezes — log the raw head of the first few per session so the next test can tell
    // whether they are keyframes the receiver is throwing away. Raw bytes only, no cipher
    // touch: type-5 is not in the video cipher stream (video rendered past early type-5s),
    // so reading it cannot desync decryption.
    private var type5Logged = 0
    // 输出卡死看门狗（decoder 线程，时间驱动，见 checkStallWatchdog）：解码器进入“吃输入
    // 不出帧”的 wedged 状态（实测 fed 涨而 out 停在 10~13，队列/Surface 均正常，无报错
    // 无丢帧）时，换软解并从 GOP 缓存重放——不依赖手机再发 IDR（它整个会话只发 1 个）。
    // 时间驱动而非帧数驱动——静态画面发帧少时统计行可能很久不打印。5s 冷却避免码流本身
    // 不可解时反复重建刷屏。
    private var lastWatchdogOut = -1L
    @Volatile private var lastOutAdvanceMs = 0L   // decoder 线程写；logStats 也从 reader 线程读
    private var lastWatchdogRecreateMs = 0L
    private var lastPeriodicStatMs = 0L
    // 软解兜底：硬解卡死（喂进去不少帧却长时间无输出）时，换系统软解并从 GOP 缓存重放，
    // 无需手机再发 IDR；软解初始化失败则回落硬解。
    private var useSoftwareDecoder = hardwareKnownBad
    // 本会话是否已尝试过“换软解”（每会话只换一次，避免硬/软来回乒乓）。
    private var softwareTried = false
    // 软解是否由卡死触发（用于确认软解真的出帧后，记住“这台设备硬解不行”）。
    private var softwareFromStall = false
    // 本会话视频卡死次数（日志用）。
    private var stallCount = 0
    // 当前解码器累计成功送入 MediaCodec 的帧数（含重放；decoder 线程写、统计线程读）。
    // 卡死判据用它而不是 framesIn：framesIn 还包含被关键帧门/空解码器扣下的帧，会把
    // “没喂进去”误判成“喂了不出帧”。
    @Volatile private var decoderFed = 0
    // 解码器为空（无 Surface/未配置）时扣下、没送进解码器的帧数（日志用）。
    @Volatile private var gatedFrames = 0
    private var firstFrameLogged = false
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

    /**
     * 入队（背压）：队列满时阻塞 reader 线程，直到解码线程腾出位置——TCP 流控会把
     * 发送端压到我们的消费速度；发送端要跳帧也是在编码前跳，码流参考链不断。
     * 旧做法（丢最老帧）一旦丢帧就断链：软解 25fps 跟不上 30fps 输入时队列积压到
     * 满、丢 1 帧，之后全部帧被关键帧门挡住而永久冻帧（实测）。
     * 轮询 offer + 检查 running，保证 stop()/断线时阻塞中的 reader 能退出。
     */
    private fun enqueue(item: Item) {
        framesIn++
        while (running) {
            if (queue.offer(item, 100, TimeUnit.MILLISECONDS)) break
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
     *
     * 卡死判据：解码器**已被喂进**至少 [STALL_GAP_FED] 帧却没渲染出来，且 out 已 [STALL_QUIET_MS]
     * 纹丝不动。用 decoderFed（真正送入 MediaCodec 的帧数）而非 framesIn：framesIn 还含被
     * 关键帧门扣下的帧——没喂进去的帧不能算“解码器吃了不吐”。静态画面发帧少时喂入数
     * 涨不上去，自然不会误判；时间驱动也保证帧少时看门狗照样工作。
     *
     * 触发后按 [decideStallAction] 升级：换软解并从 GOP 缓存重放 → 关闭视频连接让手机
     * 重发流。**不再做“重建后等下一个 IDR”的盲目重建**：iPhone 整个会话只发 1 个 IDR，
     * 那样的重建必然把卡顿变成永久冻帧（实测第 2、3 个解码器 fed=0 out=0）。
     *
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
            if (softwareFromStall && !hardwareKnownBad && out >= SW_CONFIRM_FRAMES) {
                hardwareKnownBad = true
                Logger.i("Mirror: software decoder is rendering (out=$out) — later sessions will start with software")
                onSoftwareConfirmed?.invoke()      // 持久化：重启后第一次连接就走软解 + 720p
            }
        } else if (decoderFed - out >= STALL_GAP_FED && now - lastOutAdvanceMs > STALL_QUIET_MS &&
            now - lastWatchdogRecreateMs > STALL_RECREATE_COOLDOWN_MS) {
            lastWatchdogRecreateMs = now
            handleStall(d, out)
            return
        }
        if (lastPeriodicStatMs == 0L) lastPeriodicStatMs = now
        if (now - lastPeriodicStatMs > PERIODIC_STAT_MS && framesIn > 0) {
            lastPeriodicStatMs = now
            logStats()
        }
    }

    /** 卡死处理：按 [decideStallAction] 升级（见 checkStallWatchdog 注释）。decoder 线程。 */
    private fun handleStall(d: VideoDecoder, out: Long) {
        stallCount++
        val replayable = gop.canReplay()
        val action = decideStallAction(replayable, softwareTried, videoRestartAttempted)
        Logger.w("Mirror: output stall #$stallCount (codec=${d.codecName} fed=$decoderFed out=$out " +
            "in=$framesIn sessionIdr=$idrTotal gop=${gop.size}f/${gop.byteCount}B replayable=$replayable) → $action")
        when (action) {
            StallAction.SWITCH_TO_SOFTWARE -> {
                softwareTried = true
                softwareFromStall = true
                useSoftwareDecoder = true
                // rebuildDecoder 会用 GOP 缓存重放：软解从 IDR 起重新吃一遍，画面恢复到当前。
                rebuildDecoder(surfaceProvider())
            }
            StallAction.RESTART_VIDEO -> {
                // 软解 + 重放仍无输出（或无缓存可重放）：参考帧本身有问题，再重建没有意义。
                // 关闭视频数据连接是接收端唯一能让手机重新 SETUP 视频流、发新 IDR 的触发；
                // 音频独立，不受影响。本会话一次。
                videoRestartAttempted = true
                Logger.w("Mirror: video frozen (in=$framesIn fed=$decoderFed out=$out, sessionIdr=$idrTotal) " +
                    "— closing video data connection to force phone to re-SETUP video with a fresh keyframe")
                runCatching { client?.close() }
            }
            StallAction.NONE -> {
                Logger.w("Mirror: still stalled; nothing left to try (keeping current decoder)")
            }
        }
    }

    /**
     * 打印一次视频统计行。out= 是解码器真正渲染到 Surface 的帧数。各计数的关系：
     * in（收到）= fed（送入解码器）+ gated（空解码器扣下）+ dropped（背压后队列不再丢帧，恒 0）；
     * fed 涨而 out 不涨 = 解码器吃进去不出帧；in 涨而 fed 不涨 = 帧被扣在门外（看 gated）。
     * pend= 重组缓冲残留；surf= 当前 Surface 是否存活（若已死，例如用户切到了日志页，
     * out 不涨是预期行为而非解码器故障）；gop= 可重放缓存的块数。
     */
    private fun logStats() {
        val out = decoder?.framesOut ?: -1L
        // 与看门狗同判据（缺口 + out 静默超时）才标 STALL：软解正常管线深度就有约
        // 13 帧，只看缺口会每行误报（v0.1.61 实测）。
        val stall = if (out >= 0 && decoderFed - out >= STALL_GAP_FED &&
            System.currentTimeMillis() - lastOutAdvanceMs > STALL_QUIET_MS) " NO-OUTPUT-STALL" else ""
        val surf = if (surfaceProvider() != null) "ok" else "lost"
        Logger.i("Video stats: in=$framesIn fed=$decoderFed out=$out gated=$gatedFrames dropped=$framesDropped " +
            "decDrop=$decoderDropped (${StreamStats.videoDropPct}%) queue=${queue.size}/$QUEUE_CAPACITY " +
            "${StreamStats.videoFps}fps nal=$reassembledNals pend=${pendingAvcc.size()}B surf=$surf " +
            "idr=$idrFed sps=$spsFed gop=${gop.size}f codec=${decoder?.codecName ?: "-"}$stall")
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
                    "pending, stream marked broken")
                pendingAvcc.reset()
                streamBroken = true
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
                "stream marked broken")
            pendingAvcc.reset()
            streamBroken = true
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
                val item = queue.poll(200, TimeUnit.MILLISECONDS)
                if (item == null) {
                    // 空闲：排空解码器里压着的已完成帧（静态画面输入稀少时，最后几帧否则要
                    // 等下一帧到来才上屏）。
                    decoder?.drainOutput()
                    continue
                }
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
            gop.invalidate()
        }
    }

    private fun configureDecoder(sps: ByteArray, pps: ByteArray) {
        // 先无阻塞判断“配置未变”：重复的 type-1 配置是常态，绝不能每次都进
        // awaitSurface()——Surface 短暂为 null 时它会在解码线程上阻塞最长 5s，
        // 期间 reader 继续填队列并丢最老项（常是 IDR），还会标记码流缺口。
        // 只有真正要重建时才等 Surface；Surface 已在手时直接用，不等待。
        val d = decoder
        val surface = surfaceProvider()
        if (d != null && d.isHealthy && sps.contentEquals(lastSps) && pps.contentEquals(lastPps) &&
            surface === configuredSurface) return
        // 参数集变了：旧 GOP 缓存对新参数无效，作废（重建后等新 IDR）。
        if (!sps.contentEquals(lastSps) || !pps.contentEquals(lastPps)) gop.invalidate()
        lastSps = sps
        lastPps = pps
        rebuildDecoder(surface ?: awaitSurface())
    }

    /**
     * (Re)creates the decoder for [surface] from the cached SPS/PPS. Safe to call on a surface
     * change (app background→foreground): releases the old decoder and resyncs.
     *
     * 重新同步方式：GOP 缓存可用（有 IDR 起的完整无缺口序列）时，把缓存按序**重放**给新解码器，
     * 立即恢复到当前画面，不需要手机再发 IDR；无缓存时直播帧直喂新解码器（无关键帧门，
     * 见 decodeFrame）——iPhone 整个会话只发 1 个 IDR，“等 IDR”等于永久冻帧。
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
        val nd = VideoDecoder(surface, preferSoftware = useSoftwareDecoder)
        try {
            // csd 必须是裸 NAL（RAOP 路径就是这么传的）。
            nd.initialize(sps, pps, width, height)
        } catch (e: Exception) {
            // 初始化失败（例如软解不支持该格式）绝不能杀死解码线程：保持 decoder=null，
            // 看门狗的空闲重建会定期重试；软解失败时下次重试回落硬解。
            Logger.e("Mirror: decoder initialize failed — will retry", e)
            nd.release()
            if (useSoftwareDecoder) {
                useSoftwareDecoder = false
                softwareFromStall = false
                Logger.w("Mirror: software decoder init failed — falling back to hardware on retry")
            }
            decoder = null
            return
        }
        decoder = nd
        decoderFed = 0
        lastWatchdogOut = -1L
        lastOutAdvanceMs = System.currentTimeMillis()
        StreamStats.videoRes = "${width}x${height}"
        Logger.i("Mirror decoder (re)built for surface (sps=${sps.size}B pps=${pps.size}B " +
            "codec=${nd.codecName} software=$useSoftwareDecoder)")
        if (gop.canReplay()) {
            replayGop(nd)
        }
        // 无缓存可重放时也不等 IDR：新解码器直接吃直播 P 帧（画面先花，靠帧内刷新
        // 逐步恢复），好过永久冻帧——本协议整会话只有 1 个 IDR 可等。
    }

    /**
     * 把 GOP 缓存（IDR 起的全部块）按序喂给新解码器。decoder 线程内同步完成；期间 reader 线程
     * 在队列满时阻塞等待（背压），重放结束后再接着消费——顺序不乱、无缺口。
     * 任何一块被解码器拒收则放弃重放并作废缓存（直播帧照常喂，无关键帧门）。
     */
    private fun replayGop(d: VideoDecoder) {
        val frames = gop.snapshot()
        var fed = 0
        for (blob in frames) {
            if (!running) return
            // 软解消费慢：刚建好的解码器输入缓冲少（约 4 个），连喂几帧就会短暂
            // 拿不到缓冲（decodeNalUnit 等 100ms 后返回 false）——这不是拒收，等它
            // 解完一帧就有缓冲了。v0.1.60 实测重放在 8/84 处因此整段放弃。改为
            // 排空输出 + 短等后重试同一帧，单帧 3 秒内喂不进（或解码器转入错误态）
            // 才放弃整段重放。
            val deadline = System.currentTimeMillis() + REPLAY_FEED_DEADLINE_MS
            var fedThis = false
            while (!fedThis && System.currentTimeMillis() < deadline) {
                if (d.decodeNalUnit(blob, nextPtsUs())) { fedThis = true; break }
                if (!d.isHealthy) break
                d.drainOutput()
                try { Thread.sleep(REPLAY_RETRY_SLEEP_MS) } catch (_: InterruptedException) { return }
            }
            if (!fedThis) {
                Logger.w("Mirror: GOP replay aborted at $fed/${frames.size} (decoder rejected input)")
                gop.invalidate()
                return
            }
            decoderFed++
            fed++
        }
        lastOutAdvanceMs = System.currentTimeMillis()          // 重放耗时不算“无输出”
        Logger.i("Mirror: replayed $fed cached frames (${gop.byteCount}B) from the last IDR into the new decoder")
    }

    /** 下一帧的 PTS：自首次调用起的真实微秒数，严格递增（见字段注释）。 */
    private fun nextPtsUs(): Long {
        val nowNs = System.nanoTime()
        if (ptsBaseNs == 0L) ptsBaseNs = nowNs
        val us = (nowNs - ptsBaseNs) / 1000
        lastPtsUs = if (us > lastPtsUs) us else lastPtsUs + 1
        return lastPtsUs
    }

    private fun decodeFrame(annexB: ByteArray) {
        // 码流缺口（NAL 损坏/重组超限）：缓存作废（不可重放）；解码器不设门限，照常喂。
        if (streamBroken) {
            streamBroken = false
            gop.invalidate()
        }
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
                    gop.invalidate()                           // 旧 GOP 对新参数无效
                    rebuildDecoder(liveSurface)
                }
            }
        }
        // 首个 IDR 的头字节（诊断用）：可手算 slice header（slice_type / pps_id / first_mb），
        // 判断它是真 IDR、畸形数据，还是引用了未提供的 PPS。只打一次。
        if (!firstIdrLogged) {
            runCatching {
                val hex = firstIdrHeadHex(annexB)
                if (hex != null) {
                    firstIdrLogged = true
                    Logger.i("Mirror: first IDR head bytes: $hex")
                }
            }
        }
        // 前 3 个包的 NAL 构成（诊断用）：确认首个包里到底有没有 SPS+PPS+IDR。
        if (loggedBlobCount < 3) {
            loggedBlobCount++
            runCatching {
                Logger.i("Mirror: blob #$loggedBlobCount NALs: ${nalInfo(annexB)} (${annexB.size}B)")
            }
        }
        val key = isKeyframe(annexB)
        // IDR 前补一份带内 SPS/PPS（标准码流本就如此）：即使解码器不认 csd，
        // 也能从流内拿到配置。IDR 本来就稀少（本会话仅 1 个），开销可忽略。
        val feedBlob = if (key) {
            val sps = lastSps
            val pps = lastPps
            if (sps != null && pps != null) {
                val sc = MirrorCrypto.START_CODE
                sc + sps + sc + pps + annexB
            } else annexB
        } else annexB
        // GOP 缓存：与解码器状态无关，只跟码流走——IDR 起新 GOP，其余块按序追加。
        // 必须在“解码器为空 / 关键帧门”这些提前返回之前做，这样 Surface 丢失期间到达的帧
        // 也在缓存里，Surface 回来后能整段重放。
        if (key) gop.startGop(feedBlob) else gop.append(feedBlob)
        val d = decoder
        if (d == null) { gatedFrames++; return }               // need surface + SPS/PPS first
        if (!d.isHealthy) {
            // 解码器进了不可恢复的错误态：用缓存重建并重放（保留 SPS/PPS，不再清空配置后等
            // 手机重发——iPhone 不会重发）。冷却期内只丢帧不重建，避免错误循环刷屏。
            val now = System.currentTimeMillis()
            if (now - lastWatchdogRecreateMs > STALL_RECREATE_COOLDOWN_MS) {
                lastWatchdogRecreateMs = now
                Logger.w("Mirror: decoder unhealthy — rebuilding (gop replayable=${gop.canReplay()})")
                rebuildDecoder(liveSurface)
            } else {
                gatedFrames++
            }
            return
        }
        // 注意：这里刻意没有“等关键帧”门限——缺参考的 P 帧也照喂（见 streamBroken 注释）。
        if (!firstFrameLogged) {
            firstFrameLogged = true
            Logger.i("Mirror: first video frame fed to decoder (${annexB.size}B)")
        }
        if (d.decodeNalUnit(feedBlob, nextPtsUs())) {
            decoderFed++
        } else {
            // 解码器丢了这一帧（输入缓冲超限/超时/异常）：参考链断了。缓存里有完整序列（含这一帧）
            // 时直接重建并重放恢复；否则继续喂后续帧（无关键帧门，靠纠错/帧内刷新
            // 逐步恢复），不再等一个整会话都不会再来的 IDR。
            decoderDropped++
            val now = System.currentTimeMillis()
            if (gop.canReplay() && now - lastWatchdogRecreateMs > STALL_RECREATE_COOLDOWN_MS) {
                lastWatchdogRecreateMs = now
                Logger.w("Mirror: decoder dropped a frame — rebuilding and replaying the GOP")
                rebuildDecoder(liveSurface)
            }
        }
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
        // 10（30fps 约 0.35 秒）：背压下队列深度就是画面延迟下限的一部分——30 时
        // 软解稳态积压 30 帧 + 管线约 13 帧 ≈ 1.7 秒声画偏移（v0.1.60 实测）。
        // 背压保证小队列不丢帧（reader 阻塞等待），重放期间同样靠阻塞扛过去。
        private const val QUEUE_CAPACITY = 10
        // 看门狗：已喂入却未输出的帧数阈值。30 时若输入是涓流（静态画面/手机缓冲），
        // 攒满 30 帧要半分钟，卡死恢复被拖到 40 秒（v0.1.60 实测 out=54 后 31 秒才
        // 切换）；健康播放时 out 持续前进不会误触发（还要叠加 1.5 秒静默条件），故降到 10。
        private const val STALL_GAP_FED = 10
        private const val STALL_QUIET_MS = 1500L               // 看门狗：out 纹丝不动的时长阈值
        private const val STALL_RECREATE_COOLDOWN_MS = 5000L   // 看门狗/丢帧重建冷却（见 checkStallWatchdog）
        private const val PERIODIC_STAT_MS = 10000L            // 周期统计间隔（静态画面补统计行）
        private const val SW_CONFIRM_FRAMES = 5L               // 软解出到这么多帧即确认“软解可用”
        private const val REPLAY_FEED_DEADLINE_MS = 3000L      // 重放单帧喂入的重试期限（等输入缓冲）
        private const val REPLAY_RETRY_SLEEP_MS = 20L          // 重放重试间隔（期间排空输出）
        private const val SURFACE_WAIT_TRIES = 50
        private const val SURFACE_WAIT_MS = 100L

        // 进程内记忆：本进程里已确认“硬解卡死、软解能出帧”后，后续会话直接用软解，免得每次
        // 先卡 2–3 秒再切换。另由 AirPlayReceiver 持久化（确认时经 onSoftwareConfirmed
        // 回调写入 prefs，启动时 setHardwareKnownBad 回填）——这是设备属性，只靠进程
        // 记忆时每次重启 App/电视都要重新楔一次硬解 + 跑一段 1080p 软解（v0.1.61 实测）。
        @Volatile private var hardwareKnownBad = false

        /** 本进程是否已确认“硬解卡死、软解可用”——RtspHandler 的 /info 据此广播 720p。 */
        fun isHardwareKnownBad(): Boolean = hardwareKnownBad

        /** 启动回填：AirPlayReceiver 从持久化存储读到“硬解不行”时调用。 */
        fun setHardwareKnownBad(value: Boolean) { hardwareKnownBad = value }
    }
}
