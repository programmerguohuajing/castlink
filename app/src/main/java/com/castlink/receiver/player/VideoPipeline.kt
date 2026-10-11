package com.castlink.receiver.player

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/**
 * 解码管线：把收到的 NAL/采样数据喂给 MediaCodec 解码并渲染到 [surface]。
 */
class VideoPipeline(private val surface: Surface) {

    private var codec: MediaCodec? = null

    /** 已渲染到 surface 的输出帧计数（只增）。 */
    var renderedFrameCount: Int = 0
        private set

    fun configure(mime: String = "video/avc", width: Int = 1920, height: Int = 1080) {
        configure(MediaFormat.createVideoFormat(mime, width, height))
    }

    /**
     * 使用完整 format（含 csd-0/csd-1）配置解码器。
     * 回环自测用编码器输出的 format 调用此重载，以保证 SPS/PPS 正确。
     */
    fun configure(format: MediaFormat) {
        try {
            release()
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            val c = MediaCodec.createDecoderByType(mime)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            renderedFrameCount = 0
        } catch (e: Exception) {
            Log.e(TAG, "configure failed", e)
        }
    }

    fun feedSample(data: ByteArray, ptsUs: Long) {
        val c = codec ?: return
        try {
            val index = c.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = c.getInputBuffer(index) ?: return
                buffer.clear()
                val n = minOf(data.size, buffer.remaining())
                buffer.put(data, 0, n)
                c.queueInputBuffer(index, 0, n, ptsUs, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "feedSample failed", e)
        }
    }

    /** 向解码器送入流结束标记。 */
    fun queueEndOfStream() {
        val c = codec ?: return
        try {
            val index = c.dequeueInputBuffer(10_000)
            if (index >= 0) {
                c.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (e: Exception) {
            Log.w(TAG, "queueEndOfStream failed", e)
        }
    }

    /**
     * 取出一个输出 buffer 并渲染到 surface。
     * @return true 表示已到达流末尾（EOS）。
     */
    fun drainOutput(timeoutUs: Long = 10_000L): Boolean {
        val c = codec ?: return true
        return try {
            val info = MediaCodec.BufferInfo()
            val index = c.dequeueOutputBuffer(info, timeoutUs)
            when {
                index >= 0 -> {
                    c.releaseOutputBuffer(index, true)
                    renderedFrameCount++
                    (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "decoder output format changed: ${c.outputFormat}")
                    false
                }
                else -> false // INFO_TRY_AGAIN_LATER 等
            }
        } catch (e: Exception) {
            Log.w(TAG, "drainOutput failed", e)
            true
        }
    }

    fun release() {
        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "codec stop failed", e)
        }
        try {
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "codec release failed", e)
        }
        codec = null
    }

    companion object {
        private const val TAG = "VideoPipeline"
    }
}
