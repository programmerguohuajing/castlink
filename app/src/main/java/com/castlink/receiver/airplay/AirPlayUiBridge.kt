package com.castlink.receiver.airplay

import android.os.Handler
import android.os.Looper
import android.view.Surface

/**
 * AirPlayUiBridge — CastLink 自有代码（非移植），连接移植来的 AirPlayReceiver 与现有 UI。
 *
 * AirPlayReceiver 跑在前台 Service 里（进程存活），UI（StandbyActivity 四格 PIN、
 * ControlActivity 的 Surface、状态显示）通过这里注册回调。所有回调都在主线程派发。
 */
object AirPlayUiBridge {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 配对 PIN 显示：非空=显示 PIN，null=隐藏（配对完成/失败/锁定）。 */
    @Volatile var pinListener: ((String?) -> Unit)? = null

    /** 协议状态变化（ADVERTISING / CONNECTED / ERROR ...）。 */
    @Volatile var stateListener: ((ProtocolState) -> Unit)? = null

    /** 发送端名称（RECORD 到达时先于 CONNECTED 触发）。 */
    @Volatile var senderNameListener: ((String) -> Unit)? = null

    /** 视频 Surface 提供者，由 ControlActivity 在 surface 就绪时注册、销毁时置空。 */
    @Volatile var surfaceProvider: (() -> Surface?)? = null

    internal fun emitPin(pin: String?) {
        val l = pinListener ?: return
        mainHandler.post { l(pin) }
    }

    internal fun emitState(state: ProtocolState) {
        val l = stateListener ?: return
        mainHandler.post { l(state) }
    }

    internal fun emitSenderName(name: String) {
        val l = senderNameListener ?: return
        mainHandler.post { l(name) }
    }

    internal fun provideSurface(): Surface? = try {
        surfaceProvider?.invoke()
    } catch (e: Exception) {
        null
    }
}
