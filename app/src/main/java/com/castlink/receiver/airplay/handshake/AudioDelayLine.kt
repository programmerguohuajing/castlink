package com.castlink.receiver.airplay.handshake

import java.util.ArrayDeque
import kotlin.math.abs

/**
 * AudioDelayLine — 把音频整体往后推 [delayMs]，让声音与“落后”的画面对齐。
 *
 * 为什么：镜像画面要经过 解码队列 + 解码器内部缓冲 才上屏，延迟几百毫秒；而音频几乎实时
 * （AudioTrack 只有 ~85ms）。画面落后声音就是“音画不同步”。画面延迟压不到 0 时，把音频
 * 也延后同样的量才是真正的对齐。延迟量由视频侧实测（见 MirrorStreamServer.updatePipelineLatency）。
 *
 * 用法（仅播放线程，非线程安全）：每解出一块 PCM 调 [offer]，再用 [drainDue] 取出“到点”的块
 * 去写 AudioTrack；音频暂停没新帧时也要定期调 [drainDue]，否则尾巴出不来。
 * - 增大延迟：到点之前不出块，AudioTrack 自然欠载播静音，之后等量的音频被推后。
 * - 减小延迟：队首块已超期 [DROP_SLACK_MS] 以上就直接丢弃（一次小跳变），追上新延迟——
 *   实时音频的下游是“实时”的，不丢就永远追不上。
 */
internal class AudioDelayLine(private val nowMs: () -> Long = { System.currentTimeMillis() }) {

    private class Chunk(val pcm: ByteArray, val atMs: Long, val tag: Long)

    private val q = ArrayDeque<Chunk>()

    var delayMs = 0L
        private set
    var droppedChunks = 0
        private set

    val isEmpty: Boolean get() = q.isEmpty()
    val size: Int get() = q.size

    /**
     * 设定目标延迟。变化不足 [RETARGET_MIN_MS] 时不动（避免来回抖动造成频繁跳变）；
     * 目标太小（< RETARGET_MIN_MS）按 0 处理。返回是否真的改了。
     */
    fun retarget(targetMs: Long): Boolean {
        val t = targetMs.coerceIn(0L, MAX_DELAY_MS).let { if (it < RETARGET_MIN_MS) 0L else it }
        if (t == delayMs) return false
        if (t != 0L && abs(t - delayMs) < RETARGET_MIN_MS) return false
        val shrinking = t < delayMs
        delayMs = t
        // 减小延迟时必须当场把多出来的那段丢掉：下游 AudioTrack 是实时汇点，提前“放出”的块并不会
        // 播得更快，只会排在缓冲里，实际延迟降不下来（否则宽限多大，延迟就白白多留多大）。
        if (shrinking) trimToDelay()
        return true
    }

    /** 丢弃已等待超过 delayMs（+一点点余量）的队首块，让队首年龄回到目标延迟附近。 */
    private fun trimToDelay() {
        val now = nowMs()
        while (true) {
            val head = q.peekFirst() ?: return
            if (now - head.atMs < delayMs + SHRINK_SLACK_MS) return
            q.pollFirst()
            droppedChunks++
        }
    }

    /** [tag] 随块带着走（这里放该块的 RTP 时间戳，写出时据此算媒体时间）。 */
    fun offer(pcm: ByteArray, tag: Long = 0L) {
        q.addLast(Chunk(pcm, nowMs(), tag))
    }

    /** 依次把已到点的块交给 [sink]（超期太久的直接丢弃）。 */
    fun drainDue(sink: (ByteArray, Long) -> Unit) {
        while (true) {
            val head = q.peekFirst() ?: return
            val age = nowMs() - head.atMs
            if (age >= delayMs + DROP_SLACK_MS) {
                q.pollFirst()
                droppedChunks++
            } else if (age >= delayMs) {
                q.pollFirst()
                sink(head.pcm, head.tag)
            } else return
        }
    }

    fun clear() = q.clear()

    companion object {
        const val MAX_DELAY_MS = 1500L
        const val RETARGET_MIN_MS = 30L
        const val DROP_SLACK_MS = 150L
        const val SHRINK_SLACK_MS = 25L
    }
}
