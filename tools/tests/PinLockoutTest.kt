import com.castlink.receiver.util.PinLockout

/** 纯 JVM 测试：PinLockout 状态机（内存 Store + 虚拟时钟，不依赖 android.jar）。 */
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("ok: $name") }
        else { failed++; println("FAIL: $name") }
    }

    val mem = HashMap<String, Any>()
    val store = object : PinLockout.Store {
        override fun getInt(k: String, d: Int) = mem[k] as? Int ?: d
        override fun putInt(k: String, v: Int) { mem[k] = v }
        override fun getLong(k: String, d: Long) = mem[k] as? Long ?: d
        override fun putLong(k: String, v: Long) { mem[k] = v }
        override fun remove(k: String) { mem.remove(k) }
    }
    var now = 1_000_000L
    val lock = PinLockout(store) { now }

    // 初始：未锁定
    check("initially not locked", !lock.isLocked())
    check("initial remaining 0", lock.remainingLockSeconds() == 0L)

    // 3 次输错 → 第 3 次触发锁定
    check("fail#1 no lock", !lock.recordFailure())
    check("fail#2 no lock", !lock.recordFailure())
    check("attempts==2", lock.failedAttempts() == 2)
    check("fail#3 triggers lock", lock.recordFailure())
    check("locked now", lock.isLocked())
    check("remaining ~60s", lock.remainingLockSeconds() in 59L..60L)

    // 锁定期内保持锁定；59 秒后仍锁定
    now += 59_000
    check("still locked at 59s", lock.isLocked())

    // 60 秒后自动解锁
    now += 2_000
    check("unlocked after 61s", !lock.isLocked())
    check("remaining 0 after unlock", lock.remainingLockSeconds() == 0L)
    check("lock marker cleared", !mem.containsKey("native_pin_lock_until_ms"))

    // 解锁后计数清零，可重新累计
    check("attempts reset after lock", lock.failedAttempts() == 0)
    check("fail#1 after unlock no lock", !lock.recordFailure())
    check("not locked", !lock.isLocked())

    // 成功清零：2 次错后成功 → 计数清零
    lock.recordFailure()
    lock.verifyOk()
    check("verifyOk clears attempts", lock.failedAttempts() == 0)
    check("verifyOk not locked", !lock.isLocked())

    // 成功可解除锁定中状态
    lock.recordFailure(); lock.recordFailure(); lock.recordFailure()
    check("locked again", lock.isLocked())
    lock.verifyOk()
    check("verifyOk clears active lock", !lock.isLocked())

    // 常量符合需求
    check("MAX_ATTEMPTS==3", PinLockout.MAX_ATTEMPTS == 3)
    check("LOCK_SECONDS==60", PinLockout.LOCK_SECONDS == 60L)

    println(if (failed == 0) "\nALL PINLOCKOUT TESTS PASSED ($passed)" else "\n$failed FAILURES")
    if (failed > 0) kotlin.system.exitProcess(1)
}
