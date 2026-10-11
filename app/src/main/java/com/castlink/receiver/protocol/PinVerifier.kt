package com.castlink.receiver.protocol

/**
 * PinVerifier — 自有协议 PIN 校验的可插拔接口。
 *
 * SignalingServer 优先使用 verifier（为 null 时回退到构造时的 pinProvider 直接比对，
 * 保持既有接收端行为不变）。verifier 内部自行处理输错计数 / 锁定 / 当前 PIN 获取。
 */
interface PinVerifier {

    /** PIN 校验结果。 */
    sealed interface Result {
        /** 校验通过。 */
        data object Ok : Result

        /** PIN 错误（未达锁定阈值）。[attemptsLeft] 为剩余可试次数。 */
        data class Wrong(val attemptsLeft: Int) : Result

        /** 锁定中。[remainingSeconds] 为剩余锁定秒数。 */
        data class Locked(val remainingSeconds: Long) : Result
    }

    fun verify(inputPin: String): Result
}
