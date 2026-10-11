package com.castlink.receiver.util

/**
 * PinLockout — CastLink 自有协议 PIN 输错锁定（对标 AirPlay 侧 PairingStore 的
 * failedAttempts 持久化思路，但采用"3 次错 → 锁定 60 秒"的限时策略）。
 *
 * 纯 Kotlin，不依赖 android.jar：持久化经 [Store] 接口注入（生产环境用
 * SharedPreferences 适配，单元测试用内存实现），时钟经 [nowMs] 注入以便测试。
 *
 * 状态机：
 *   verifyOk()          → 成功：清零失败计数并解除锁定
 *   recordFailure()     → 输错：计数+1；达到 MAX_ATTEMPTS 时进入锁定 60 秒，返回 true
 *   isLocked()          → 锁定期内 true；过期后自动清除锁定标记并返回 false
 *   remainingLockSeconds() → 剩余锁定秒数（未锁定返回 0）
 */
class PinLockout(
    private val store: Store,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /** 持久化后端：生产环境见 StandbyActivity 的 SharedPreferences 适配。 */
    interface Store {
        fun getInt(key: String, def: Int): Int
        fun putInt(key: String, value: Int)
        fun getLong(key: String, def: Long): Long
        fun putLong(key: String, value: Long)
        fun remove(key: String)
    }

    /** PIN 校验成功：清零并解除锁定。 */
    fun verifyOk() {
        store.remove(KEY_FAILS)
        store.remove(KEY_LOCK_UNTIL)
    }

    /**
     * 记录一次输错。
     * @return true 表示本次输错触发了锁定（调用方应提示用户并刷新 UI）
     */
    fun recordFailure(): Boolean {
        val n = store.getInt(KEY_FAILS, 0) + 1
        return if (n >= MAX_ATTEMPTS) {
            store.putLong(KEY_LOCK_UNTIL, nowMs() + LOCK_MS)
            store.remove(KEY_FAILS)
            true
        } else {
            store.putInt(KEY_FAILS, n)
            false
        }
    }

    /** 锁定期内返回 true；锁定过期后自动清除标记并返回 false。 */
    fun isLocked(): Boolean {
        val until = store.getLong(KEY_LOCK_UNTIL, 0L)
        if (until == 0L) return false
        if (nowMs() >= until) {
            store.remove(KEY_LOCK_UNTIL)
            return false
        }
        return true
    }

    /** 剩余锁定秒数（未锁定返回 0）。 */
    fun remainingLockSeconds(): Long {
        val until = store.getLong(KEY_LOCK_UNTIL, 0L)
        if (until == 0L) return 0L
        return ((until - nowMs()) / 1000L).coerceAtLeast(0L)
    }

    /** 当前累计输错次数（未达锁定阈值时）。 */
    fun failedAttempts(): Int = store.getInt(KEY_FAILS, 0)

    companion object {
        /** 连续输错阈值（对标需求：3 次）。 */
        const val MAX_ATTEMPTS = 3

        /** 锁定秒数（对标需求：60 秒）。 */
        const val LOCK_SECONDS = 60L

        private const val LOCK_MS = LOCK_SECONDS * 1000L
        private const val KEY_FAILS = "native_pin_failed_attempts"
        private const val KEY_LOCK_UNTIL = "native_pin_lock_until_ms"
    }
}
