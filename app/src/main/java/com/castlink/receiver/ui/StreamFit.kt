package com.castlink.receiver.ui

/**
 * StreamFit — 原生协议投屏播放页的画面缩放数学（纯函数，便于 JVM 单测）。
 *
 * 关键事实：TextureView 的内容**默认被拉伸到铺满整个 View**，[android.view.TextureView.setTransform]
 * 的矩阵是作用在这个“已拉伸”的内容上的。所以缩放比必须是**相对 View 尺寸**的
 * （显示宽 / View 宽、显示高 / View 高），而不是相对视频尺寸。
 *
 * 旧实现直接 setScale(max(viewW/videoW, viewH/videoH))：只有 View 尺寸恰好等于视频尺寸时才对。
 * 例如 1920x1080 的电视收 1280x720 的流，scale=1.5，等于把本已铺满的画面再放大 1.5 倍并
 * 锚在左上角——只能看到左上 2/3；View 比视频小（如 1024x600）时则缩成左上角一小块。
 * 这就是“接收端一直无法全屏”。
 */
internal object StreamFit {

    /**
     * 自动选择填充(true) / 适应(false) 的宽高比失配上限：视频与 View 的宽高比相差在此倍数
     * 以内用填充（裁掉少量边缘，真正铺满）；失配更大（典型：竖屏视频投到横屏电视，
     * 相差约 3.2 倍）填充会裁掉绝大部分内容，改用适应（等比缩放、两侧留黑边）。
     * 20:9 的手机横屏投 16:9 电视相差 1.25 倍，仍走填充。
     */
    const val AUTO_FILL_MAX_MISMATCH = 1.35f

    /** 自动模式：宽高比失配不大就填充，否则适应。 */
    fun autoFill(viewW: Float, viewH: Float, videoW: Float, videoH: Float): Boolean {
        if (viewW <= 0f || viewH <= 0f || videoW <= 0f || videoH <= 0f) return true
        val viewAspect = viewW / viewH
        val videoAspect = videoW / videoH
        val mismatch = if (videoAspect > viewAspect) videoAspect / viewAspect else viewAspect / videoAspect
        return mismatch <= AUTO_FILL_MAX_MISMATCH
    }

    /**
     * 返回 (sx, sy)：以 View 中心为锚点的 X/Y 缩放比（相对“已拉伸铺满”的内容）。
     * [fill]=true：等比缩放到完全覆盖 View（裁边）；false：等比缩放到完整放进 View（留黑边）。
     * 显示出来的视频尺寸 = (sx * viewW, sy * viewH)，宽高比恒等于视频宽高比。
     */
    fun scaleFor(viewW: Float, viewH: Float, videoW: Float, videoH: Float, fill: Boolean): Pair<Float, Float> {
        if (viewW <= 0f || viewH <= 0f || videoW <= 0f || videoH <= 0f) return 1f to 1f
        val s = if (fill) maxOf(viewW / videoW, viewH / videoH) else minOf(viewW / videoW, viewH / videoH)
        return (videoW * s / viewW) to (videoH * s / viewH)
    }
}
