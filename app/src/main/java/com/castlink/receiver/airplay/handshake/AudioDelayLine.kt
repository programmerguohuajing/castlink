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

    private class Chunk(val pcm: ByteArray, val atMs: Long)

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
        delayMs = t
        return true
    }

    fun offer(pcm: ByteArray) {
        q.addLast(Chunk(pcm, nowMs()))
    }

    /** 依次把已到点的块交给 [sink]（超期太久的直接丢弃）。 */
    fun drainDue(sink: (ByteArray) -> Unit) {
        while (true) {
            val head = q.peekFirst() ?: return
            val age = nowMs() - head.atMs
            if (age >= delayMs + DROP_SLACK_MS) {
                q.pollFirst()
                droppedChunks++
            } else if (age >= delayMs) {
                q.pollFirst()
                sink(head.pcm)
            } else return
        }
    }

    fun clear() = q.clear()

    companion object {
        const val MAX_DELAY_MS = 1500L
        const val RETARGET_MIN_MS = 60L
        const val DROP_SLACK_MS = 150L
    }
}
