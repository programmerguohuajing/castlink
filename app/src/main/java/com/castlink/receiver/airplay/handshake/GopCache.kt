package com.castlink.receiver.airplay.handshake

/**
 * GopCache — 缓存"最近一个 IDR 起、至今收到的全部视频块"，让解码器重建后能**重放**到
 * 当前画面，而不必等手机再发关键帧。
 *
 * 为什么需要：iPhone 镜像开流后只发 1 个 IDR（实测 40 多秒内 sessionIdr=1），协议里接收端
 * 也没有索要关键帧的消息。此前任何“重建解码器”（看门狗、Surface 切换、解码器丢帧）
 * 之后都要等下一个 IDR——等不到就是永久冻帧。缓存了 IDR 起的全部块，重建后按序重放
 * 就能恢复到当前状态。
 *
 * 有效性：缓存必须以 IDR 开头且中间无缺口，否则重放出来的是参考帧缺失的花屏流。
 * 码流出现缺口（队列溢出丢帧、NAL 长度损坏）、参数集变化、或超出 [maxBytes]/[maxFrames]
 * 上限时调用 [invalidate]，之后只能等下一个 IDR（[startGop]）重新开始。
 *
 * 线程模型：非线程安全，仅 decoder 线程使用（统计线程只读 [size]/[byteCount]，容忍旧值）。
 */
internal class GopCache(
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val maxFrames: Int = DEFAULT_MAX_FRAMES,
) {
    private val frames = ArrayList<ByteArray>()
    private var bytes = 0
    private var valid = false

    /** 当前缓存的块数（无效时为 0）。 */
    val size: Int get() = frames.size

    /** 当前缓存的总字节数（无效时为 0）。 */
    val byteCount: Int get() = bytes

    /** 缓存是否可用于重放：以 IDR 开头、无缺口、非空。 */
    fun canReplay(): Boolean = valid && frames.isNotEmpty()

    /** 新 IDR 到达：丢弃旧 GOP，以这个块作为新 GOP 的起点。 */
    fun startGop(idrBlob: ByteArray) {
        frames.clear()
        bytes = 0
        if (idrBlob.size > maxBytes) {
            valid = false
            return
        }
        frames.add(idrBlob)
        bytes = idrBlob.size
        valid = true
    }

    /** 非 IDR 块：缓存有效时按序追加；超上限则整体作废（释放内存，等下一个 IDR）。 */
    fun append(blob: ByteArray) {
        if (!valid) return
        if (frames.size + 1 > maxFrames || bytes.toLong() + blob.size > maxBytes) {
            invalidate()
            return
        }
        frames.add(blob)
        bytes += blob.size
    }

    /** 码流有缺口 / 参数集变化 / 超限：缓存作废并释放内存。 */
    fun invalidate() {
        frames.clear()
        bytes = 0
        valid = false
    }

    /** 重放用的按序快照；缓存无效时返回空列表。 */
    fun snapshot(): List<ByteArray> = if (canReplay()) ArrayList(frames) else emptyList()

    companion object {
        /** 16 MB：1080p 低码率镜像约 0.5–1 MB/s，足够覆盖开流后的前 15–30 秒。 */
        const val DEFAULT_MAX_BYTES = 16 * 1024 * 1024

        /** 360 块：24fps 约 15 秒、60fps 约 6 秒。 */
        const val DEFAULT_MAX_FRAMES = 360
    }
}

/** 看门狗判定卡死后的动作。 */
internal enum class StallAction {
    /** 换软解并从 GOP 缓存重放（无需新 IDR）。 */
    SWITCH_TO_SOFTWARE,

    /** 关闭视频数据连接，迫使手机重新 SETUP 视频流并发新 IDR（最后手段，每会话一次）。 */
    RESTART_VIDEO,

    /** 无事可做（只保留现状，不做破坏性重建）。 */
    NONE,
}

/**
 * 卡死后的升级策略（纯函数，便于单测）：
 *  1. 缓存可重放且还没试过软解 → 软解 + 重放（不依赖手机再发 IDR）；
 *  2. 否则若还没用过“关闭视频连接”→ 用它向手机要新 IDR；
 *  3. 否则什么都不做——没有新 IDR 的盲目重建只会把冻帧变成永久冻帧。
 */
internal fun decideStallAction(
    canReplay: Boolean,
    softwareTried: Boolean,
    restartAttempted: Boolean,
): StallAction = when {
    canReplay && !softwareTried -> StallAction.SWITCH_TO_SOFTWARE
    !restartAttempted -> StallAction.RESTART_VIDEO
    else -> StallAction.NONE
}
