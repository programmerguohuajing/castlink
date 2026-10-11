package com.castlink.receiver.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import com.castlink.receiver.R
import kotlin.concurrent.thread

/**
 * 本地回环自测：
 * AVC 编码器（COLOR_FormatSurface 输入，在 input Surface 上用 Canvas 画测试画面）
 *  -> 编码输出直接喂给 [VideoPipeline] 解码并渲染到 [holder] 的 surface。
 *
 * 注意：本自测只在真机（或具备可用 AVC 编解码器的设备）上能跑；
 * 沙箱 / 无硬件编解码器的环境会走错误回调，属预期行为。
 */
object LoopbackTest {

    private const val TAG = "LoopbackTest"
    private const val WIDTH = 1920
    private const val HEIGHT = 1080
    private const val FRAME_COUNT = 120
    private const val FRAME_RATE = 30
    private const val BITRATE = 8_000_000

    /**
     * 在子线程执行自测；完成或失败后通过 [callback] 回传
     * strings 资源格式化后的结果文案（调用方负责切回主线程更新 UI）。
     */
    fun run(context: Context, holder: SurfaceHolder, callback: (String) -> Unit) {
        thread(name = "LoopbackTest", isDaemon = true) {
            val message = try {
                doRun(context, holder)
            } catch (e: Exception) {
                Log.e(TAG, "loopback failed", e)
                context.getString(R.string.loopback_error, e.message ?: e.toString())
            }
            callback(message)
        }
    }

    private fun doRun(context: Context, holder: SurfaceHolder): String {
        val surface = holder.surface
        if (!surface.isValid) throw IllegalStateException("preview surface not ready")

        val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        // 等编码器输出 format（含 SPS/PPS），用它配置解码器。
        // v0.3.5 修复：多数硬件编码器在收到首帧输入后才上报 format，边等边喂帧。
        val outFormat = awaitEncoderFormat(encoder, inputSurface)
        val pipeline = VideoPipeline(surface)
        pipeline.configure(outFormat)

        val paint = Paint()
        val startNs = SystemClock.elapsedRealtimeNanos()
        try {
            for (i in 0 until FRAME_COUNT) {
                drawFrame(inputSurface, i, paint)
                drainEncoder(encoder, pipeline, expectEos = false)
            }
            encoder.signalEndOfInputStream()
            drainEncoder(encoder, pipeline, expectEos = true)

            pipeline.queueEndOfStream()
            var eos = false
            var guard = 0
            while (!eos && guard++ < 1000) {
                eos = pipeline.drainOutput(20_000L)
            }

            val decoded = pipeline.renderedFrameCount
            val elapsedNs = SystemClock.elapsedRealtimeNanos() - startNs
            val fps = if (elapsedNs > 0) (decoded * 1_000_000_000L / elapsedNs).toInt() else 0
            return context.getString(R.string.loopback_result, decoded, fps)
        } finally {
            try {
                encoder.stop()
            } catch (e: Exception) {
                Log.w(TAG, "encoder stop failed", e)
            }
            try {
                encoder.release()
            } catch (e: Exception) {
                Log.w(TAG, "encoder release failed", e)
            }
            try {
                inputSurface.release()
            } catch (e: Exception) {
                Log.w(TAG, "inputSurface release failed", e)
            }
            pipeline.release()
        }
    }

    private fun awaitEncoderFormat(encoder: MediaCodec, inputSurface: Surface): MediaFormat {
        val info = MediaCodec.BufferInfo()
        val paint = Paint()
        var guard = 0
        var frame = 0
        while (guard++ < 200) {
            // 先喂一帧再取输出：否则部分编码器永不上报 INFO_OUTPUT_FORMAT_CHANGED
            drawFrame(inputSurface, frame++, paint)
            val index = encoder.dequeueOutputBuffer(info, 50_000L)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                return encoder.outputFormat
            } else if (index >= 0) {
                encoder.releaseOutputBuffer(index, false)
            }
        }
        throw IllegalStateException("encoder output format not available")
    }

    /**
     * 取出编码器输出；非 csd 数据喂给解码管线，同时顺手排空解码器输出做渲染。
     * @return 是否看到了编码器 EOS
     */
    private fun drainEncoder(encoder: MediaCodec, pipeline: VideoPipeline, expectEos: Boolean): Boolean {
        val info = MediaCodec.BufferInfo()
        var sawEos = false
        var guard = 0
        while (guard++ < 300) {
            val index = encoder.dequeueOutputBuffer(info, 20_000L)
            when {
                index >= 0 -> {
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig && info.size > 0) {
                        val buf = encoder.getOutputBuffer(index)
                        if (buf != null) {
                            val data = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(data)
                            pipeline.feedSample(data, info.presentationTimeUs)
                        }
                    }
                    sawEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    encoder.releaseOutputBuffer(index, false)
                    pipeline.drainOutput(0L)
                    if (sawEos || !expectEos) break
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // 已在 awaitEncoderFormat 处理
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!expectEos) break
                }
            }
        }
        return sawEos
    }

    private fun drawFrame(surface: Surface, index: Int, paint: Paint) {
        val canvas: Canvas = surface.lockCanvas(null) ?: return
        try {
            // 背景：随帧数旋转的色相
            val hue = (index * 3) % 360
            paint.color = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.8f, 0.9f))
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
            // 六色彩条
            val barW = WIDTH / 6f
            val colors = intArrayOf(
                Color.RED, Color.GREEN, Color.BLUE,
                Color.YELLOW, Color.CYAN, Color.MAGENTA
            )
            for (b in colors.indices) {
                paint.color = colors[b]
                canvas.drawRect(
                    b * barW, HEIGHT * 0.6f,
                    (b + 1) * barW, HEIGHT * 0.8f, paint
                )
            }
            // 帧计数（测试图案文字，非 UI 文案）
            paint.color = Color.WHITE
            paint.textSize = 96f
            canvas.drawText("frame $index", 80f, 200f, paint)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }
}
