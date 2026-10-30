package com.castlink.receiver.airplay.handshake

/**
 * AvSync — 用 iPhone 给音视频打的“媒体时间戳”测量声画实际呈现的相对偏差。
 *
 * 背景：iPhone 给每个视频帧（镜像包头 bytes 8..15）和每个音频包（RTP 时间戳 + 控制通道 0x54
 * 同步包里的 RTP↔NTP 对应关系）都打了同一个时钟（iPhone 的 NTP 形式时钟）上的“采集时刻”。Apple TV
 * 就是按它对齐声画的。此前接收端完全不看这些时间戳，只按“包到了就放”，iPhone 侧编码/发送
 * 的先后差、网络抖动、解码队列、音频抖动队列带来的偏差一概没人管。
 *
 * 测量：
 *   dv = 画面实际上屏的本机时刻 − 该帧媒体时间      （画面相对媒体时间落后多少）
 *   da = 声音实际播出的本机时刻 − 该音频块媒体时间   （声音相对媒体时间落后多少）
 *   skew = dv − da   > 0：画面比声音晚，需要把声音再往后推 skew；< 0：声音比画面晚。
 * iPhone 时钟与本机时钟的未知偏移在相减时抵消，所以不需要 NTP 对时。
 *
 * 线程：视频解码线程写 dv，音频播放线程写 da 并读 skew，全部 synchronized。
 */
internal object AvSync {

    private val lock = Any()

    // 音频 RTP→媒体时间映射（来自 0x54 同步包：RTP 时间戳 rtpNow 对应 NTP 时刻 ntpUs）
    private var haveMap = false
    private var rtpRef = 0L
    private var ntpRefUs = 0L
    private var sampleRate = 44100

    private class Ema(val alpha: Double) {
        var base = 0L          // 第一个样本（Long，保精度）；后续只对 (样本 − base) 做 EMA
        var ema = 0.0
        var n = 0
        var lastNs = 0L
        fun add(sampleUs: Long, nowNs: Long) {
            if (n == 0) { base = sampleUs; ema = 0.0 }
            else ema += alpha * ((sampleUs - base) - ema)
            n++
            lastNs = nowNs
        }
        fun valueUs(): Long = base + ema.toLong()
        fun reset() { n = 0; ema = 0.0; base = 0L; lastNs = 0L }
    }

    private val dv = Ema(0.06)     // 每帧一个样本（~30/s）
    private val da = Ema(0.03)     // 每个音频块一个样本（~90/s）

    fun reset() = synchronized(lock) {
        haveMap = false
        dv.reset(); da.reset()
    }

    /** 只清音频侧样本（音频延迟刚调整过，旧样本不再代表现状）。 */
    fun resetAudioSamples() = synchronized(lock) { da.reset() }

    fun setAudioMap(rtpNow: Long, ntpUs: Long, fs: Int) = synchronized(lock) {
        rtpRef = rtpNow and 0xFFFFFFFFL
        ntpRefUs = ntpUs
        sampleRate = fs
        haveMap = true
    }

    /** RTP 时间戳 → 媒体时间（µs）；映射未到时返回 null。32 位回绕按有符号差处理。 */
    fun audioMediaUs(rtp: Long): Long? = synchronized(lock) {
        if (!haveMap) return null
        val diff = ((rtp - rtpRef) and 0xFFFFFFFFL).toInt().toLong()      // signed 32-bit delta
        ntpRefUs + diff * 1_000_000L / sampleRate
    }

    fun onVideoRendered(mediaUs: Long, localUs: Long) = synchronized(lock) {
        dv.add(localUs - mediaUs, System.nanoTime())
    }

    fun onAudioPlayout(mediaUs: Long, localUs: Long) = synchronized(lock) {
        da.add(localUs - mediaUs, System.nanoTime())
    }

    /**
     * 当前声画偏差 skew = dv − da（毫秒）；任一侧样本不足/过期则返回 null。
     * 视频样本允许较旧（静止画面时 iPhone 不发帧，延迟本身是稳定的）；音频是连续流，必须新鲜。
     */
    fun skewMs(minAudioSamples: Int = MIN_AUDIO_SAMPLES): Double? = synchronized(lock) {
        val now = System.nanoTime()
        if (dv.n < MIN_VIDEO_SAMPLES || da.n < minAudioSamples) return null
        if (now - da.lastNs > AUDIO_STALE_NS || now - dv.lastNs > VIDEO_STALE_NS) return null
        (dv.valueUs() - da.valueUs()) / 1000.0
    }

    fun snapshot(): String = synchronized(lock) {
        "map=$haveMap dvN=${dv.n} daN=${da.n}" +
            (if (dv.n > 0 && da.n > 0) " skew=${"%.0f".format((dv.valueUs() - da.valueUs()) / 1000.0)}ms" else "")
    }

    private const val MIN_VIDEO_SAMPLES = 10
    const val MIN_AUDIO_SAMPLES = 40
    private const val AUDIO_STALE_NS = 2_000_000_000L
    private const val VIDEO_STALE_NS = 30_000_000_000L
}
