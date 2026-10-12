package com.castlink.receiver.legacy

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * LegacyDecoder — API 19 兼容的 H.264 同步解码器。
 *
 * 用已废弃但 API 19 可用的 getInputBuffers()/getOutputBuffers() 数组接口，
 * 不碰 API 21+ 的 getInputBuffer(index)。
 */
class LegacyDecoder(private val surface: Surface) {

    private var codec: MediaCodec? = null
    private var inputBuffers: Array<ByteBuffer>? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false

    @Volatile var renderedFrames: Int = 0
        private set

    fun configure(width: Int = 1280, height: Int = 720) {
        release()
        try {
            val format = MediaFormat.createVideoFormat("video/avc", width, height)
            val c = MediaCodec.createDecoderByType("video/avc")
            c.configure(format, surface, null, 0)
            c.start()
            @Suppress("DEPRECATION")
            inputBuffers = c.inputBuffers
            codec = c
            renderedFrames = 0
            running = true
            drainThread = Thread({ drainLoop() }, "LegacyDecoder-Drain").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "decoder configured ${width}x$height")
        } catch (e: Exception) {
            Log.e(TAG, "configure failed", e)
        }
    }

    fun feedNal(nal: ByteArray, ptsUs: Long) {
        val c = codec ?: return
        val bufs = inputBuffers ?: return
        try {
            val index = c.dequeueInputBuffer(10_000)
            if (index >= 0 && index < bufs.size) {
                val buf = bufs[index]
                buf.clear()
                buf.put(nal, 0, minOf(nal.size, buf.remaining()))
                c.queueInputBuffer(index, 0, minOf(nal.size, buf.capacity()), ptsUs, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "feedNal failed", e)
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val c = codec ?: break
            try {
                val index = c.dequeueOutputBuffer(info, 10_000)
                when {
                    index >= 0 -> {
                        c.releaseOutputBuffer(index, true)
                        renderedFrames++
                    }
                    index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        @Suppress("DEPRECATION")
                        c.outputBuffers
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.i(TAG, "output format: ${c.outputFormat}")
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "drain failed", e)
                break
            }
        }
    }

    fun release() {
        running = false
        try { drainThread?.join(500) } catch (_: Exception) {}
        drainThread = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        inputBuffers = null
    }

    companion object {
        private const val TAG = "LegacyDecoder"
    }
}
