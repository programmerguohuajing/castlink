package com.castlink.receiver.airplay.handshake

import kotlin.math.abs
import kotlin.math.ceil

/**
 * AvSyncController — 根据 [AvSync] 测得的声画偏差，决定音频要延后多少 / 要不要丢音频追赶。
 * 纯逻辑（无 Android 依赖），音频播放线程每写一块 PCM 调一次 [decide]（内部按 [EVAL_MS] 节流）。
 *
 * 闭环：skew = dv − da 是在“已包含当前音频延后量”的 da 上测得的，所以新目标 = 当前延后量 + skew。
 * 每次调整后音频样本作废（[AvSync.resetAudioSamples]），等新样本攒够再评估，避免延后量还没
 * 生效就重复叠加造成过冲。
 *
 * - skew > 0（画面晚）：增大音频延后量（AudioDelayLine 到点才放，期间 AudioTrack 欠载播静音）。
 * - skew < 0（声音晚）：减小延后量；已经是 0 还不够，就从音频队列头部丢帧追上（一次小跳变）。
 * - 队列棘轮：音频抖动队列在一次卡顿/补洞后被突发灌满，而下游（AudioTrack）只能实时消费，
 *   这份积压会永远留着（实时汇点追不上）。持续偏深就裁到浅水位——不依赖时间戳也生效。
 * - 时间戳测不出来（映射没到/视频样本不足）时，开头 [FALLBACK_AFTER_MS] 后退回用画面管线
 *   估计延迟（Little 定律，见 MirrorStreamServer.updatePipelineLatency）做初值。
 */
internal class AvSyncController(private val frameMs: Double) {

    class Decision(val newDelayMs: Long?, val dropFrames: Int)

    private var lastEvalMs = Long.MIN_VALUE
    private var firstEvalMs = Long.MIN_VALUE
    private var everValid = false
    private var highQueueStreak = 0

    fun decide(nowMs: Long, skewMs: Double?, currentDelayMs: Long, queueFrames: Int, littleMs: Int): Decision {
        if (lastEvalMs != Long.MIN_VALUE && nowMs - lastEvalMs < EVAL_MS) return NONE
        lastEvalMs = nowMs
        if (firstEvalMs == Long.MIN_VALUE) firstEvalMs = nowMs

        var drop = 0
        // 队列棘轮：连续 3 次评估（~1.5s）都深 → 裁到浅水位。
        if (queueFrames > QUEUE_RATCHET_FRAMES) highQueueStreak++ else highQueueStreak = 0
        if (highQueueStreak >= 3) {
            drop = queueFrames - QUEUE_KEEP_FRAMES
            highQueueStreak = 0
        }

        var newDelay: Long? = null
        if (skewMs != null && abs(skewMs) <= MAX_PLAUSIBLE_SKEW_MS) {
            everValid = true                              // 只有“可信”的偏差才算测到了；离谱的不算，照样走估算兜底
            if (abs(skewMs) >= MIN_STEP_MS) {
                val want = currentDelayMs + skewMs
                val clamped = want.coerceIn(0.0, AudioDelayLine.MAX_DELAY_MS.toDouble())
                newDelay = clamped.toLong()
                if (want < 0 && -want >= DROP_MIN_MS) {
                    // 延后量已经归零还嫌声音晚：从队列头丢帧追上。
                    drop = maxOf(drop, minOf(queueFrames, ceil(-want / frameMs).toInt()))
                }
            }
        } else if (!everValid && littleMs > 0 && nowMs - firstEvalMs >= FALLBACK_AFTER_MS) {
            newDelay = littleMs.toLong().coerceIn(0L, AudioDelayLine.MAX_DELAY_MS)
        }
        return if (newDelay == null && drop == 0) NONE else Decision(newDelay, drop)
    }

    companion object {
        const val EVAL_MS = 500L
        const val MIN_STEP_MS = 40.0                 // 人对口型的可察觉阈值约 40~45ms，死区取同量级
        const val DROP_MIN_MS = 100.0
        const val MAX_PLAUSIBLE_SKEW_MS = 5000.0     // 超过这个量多半是时间戳语义不对，不跟
        const val QUEUE_RATCHET_FRAMES = 24
        const val QUEUE_KEEP_FRAMES = 6
        const val FALLBACK_AFTER_MS = 3000L
        private val NONE = Decision(null, 0)
    }
}
