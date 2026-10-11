package com.castlink.receiver.protocol

/**
 * v0.3.10：原生协议视频流桥接。
 *
 * StandbyActivity 的 RtpReceiver 收到 NAL 后经此转发；StreamPlayerActivity
 * 注册监听并解码渲染。Activity 与 Service/网络线程解耦。
 */
object NativeStreamBridge {

    /** 收到完整 NAL（裸 NAL，不含起始码）。网络线程回调。 */
    @Volatile var nalListener: ((ByteArray) -> Unit)? = null

    /** 流结束（由播放页的看门狗或信令触发）。 */
    @Volatile var endListener: (() -> Unit)? = null

    fun emitNal(nal: ByteArray) {
        nalListener?.invoke(nal)
    }

    fun emitEnded() {
        endListener?.invoke()
    }

    fun clear() {
        nalListener = null
        endListener = null
    }
}
