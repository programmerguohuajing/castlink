import com.castlink.receiver.protocol.PinVerifier
import com.castlink.receiver.protocol.SignalingServer
import com.castlink.receiver.sender.PinResult
import com.castlink.receiver.sender.SignalingClient
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import java.net.InetAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 信令 PIN_LOCKED 端到端（JVM，真实生产类）：
 *   真实 protocol/SignalingServer（走 TCP ServerSocket，pinVerifier 非空） ←字节泵→
 *   真实 sender/SignalingClient（注入内存 Piped 流，跳过自身建连）。
 *
 * 覆盖：
 *   - HELLO/OK 往返、输错 1/2 次 → PIN_FAIL；
 *   - 第 3 次输错 → client 收到 PIN_LOCKED <剩余秒>；
 *   - 锁定期内正确 PIN 也被拒绝（服务器拒绝后续尝试，含新连接）；
 *   - 锁过期后正确 PIN 恢复通过；失败计数重置。
 *
 * 沙箱走 IPv6 loopback (::1)（FilePushServerTest 同理可行）。Byte pump 把
 * Piped 流与真实 socket 桥接，协议两端都是真实业务代码（服务端走真实
 * SignalingServer.verifyPin 的 "PIN_LOCKED <秒>" 组包，客户端走真实解析）。
 */
private var passed = 0
private var failed = 0
private fun check(name: String, cond: Boolean) {
    if (cond) { passed++; println("ok: $name") }
    else { failed++; println("FAIL: $name") }
}

/** 测试用 PinVerifier：语义复刻 StandbyActivity.pinVerifier + PinLockout
 *  （3 次错 → 锁定 lockSeconds 秒；锁定期内任何 PIN 直接拒绝），但锁时长可注入。 */
private class TestPinVerifier(
    private val correctPin: String,
    private val lockSeconds: Long,
) : PinVerifier {
    private var fails = 0
    private var lockUntil = 0L

    override fun verify(inputPin: String): PinVerifier.Result {
        val now = System.currentTimeMillis()
        if (now < lockUntil) {
            return PinVerifier.Result.Locked(((lockUntil - now) / 1000L).coerceAtLeast(0L))
        }
        return if (inputPin == correctPin) {
            fails = 0
            PinVerifier.Result.Ok
        } else {
            fails++
            if (fails >= 3) {
                lockUntil = now + lockSeconds * 1000L
                fails = 0
                PinVerifier.Result.Locked(lockSeconds)
            } else {
                PinVerifier.Result.Wrong(3 - fails)
            }
        }
    }
}

/** 把一条 Piped 对桥接到真实 TCP 连接：client 用内存 Piped 流，server 走真实 socket。 */
private class PipedBridge(port: Int) {
    private val socket = Socket(InetAddress.getByName("::1"), port).apply { soTimeout = 5000 }
    val clientReader: BufferedReader
    val clientWriter: PrintWriter
    private val pumpThreads = mutableListOf<Thread>()

    init {
        // client → server
        val c2sClient = PipedOutputStream()
        val c2sBridge = PipedInputStream(c2sClient)
        // server → client
        val s2cBridge = PipedOutputStream()
        val s2cClient = PipedInputStream(s2cBridge)
        clientReader = BufferedReader(InputStreamReader(s2cClient, Charsets.UTF_8))
        clientWriter = PrintWriter(OutputStreamWriter(c2sClient, Charsets.UTF_8), true)

        pumpThreads += thread(name = "pump-c2s", isDaemon = true) {
            runCatching {
                val buf = ByteArray(8192)
                val out = socket.getOutputStream()
                while (true) {
                    val n = c2sBridge.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); out.flush()
                }
            }
        }
        pumpThreads += thread(name = "pump-s2c", isDaemon = true) {
            runCatching {
                val buf = ByteArray(8192)
                val input = socket.getInputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    s2cBridge.write(buf, 0, n); s2cBridge.flush()
                }
            }
            runCatching { s2cBridge.close() }
        }
    }

    fun close() {
        runCatching { socket.close() }
    }
}

fun main() {
    val port = 18758
    // lockSeconds=2：语义与生产（60s）一致，仅缩短以便在测试里等锁过期
    val verifier = TestPinVerifier(correctPin = "2468", lockSeconds = 2L)
    val server = SignalingServer(port = port, pinProvider = { "2468" }, pinVerifier = verifier)
    server.start()
    Thread.sleep(300)

    val bridge = PipedBridge(port)
    val client = SignalingClient(bridge.clientReader, bridge.clientWriter)

    // 1. HELLO 往返
    val hello = client.hello("castlink-sender e2e")
    check("hello tier=mainstream", hello.tier == "mainstream")
    check("hello rtp=8760", hello.rtpPort == 8760)

    // 2. 输错 1/2 次 → PIN_FAIL
    check("wrong#1 -> Fail", client.pin("0000") is PinResult.Fail)
    check("wrong#2 -> Fail", client.pin("1111") is PinResult.Fail)

    // 3. 第 3 次输错 → PIN_LOCKED（端到端：服务端组包 → 客户端解析）
    val locked = client.pin("2222")
    check("wrong#3 -> Locked", locked is PinResult.Locked)
    val secs = (locked as? PinResult.Locked)?.remainingSeconds ?: -1L
    check("lock remaining 1..2s", secs in 1L..2L)

    // 4. 锁定期内正确 PIN 也被拒绝（服务器拒绝后续尝试）
    val correctWhileLocked = client.pin("2468")
    check("correct pin while locked -> Locked", correctWhileLocked is PinResult.Locked)

    // 5. 新连接在锁定期内同样被拒
    val bridge2 = PipedBridge(port)
    val client2 = SignalingClient(bridge2.clientReader, bridge2.clientWriter)
    client2.hello("castlink-sender e2e-2")
    check("new conn: correct pin while locked -> Locked", client2.pin("2468") is PinResult.Locked)
    client2.bye(); client2.close(); bridge2.close()

    // 6. 锁过期后正确 PIN 恢复通过，计数重置（输错一次不再是锁定）
    Thread.sleep(2600)
    check("after expiry: correct pin -> Ok", client.pin("2468") is PinResult.Ok)
    check("after expiry: wrong pin -> Fail (not locked)", client.pin("9999") is PinResult.Fail)

    // 7. BYE 正常结束
    client.bye()
    client.close()
    bridge.close()
    server.stop()

    println(if (failed == 0) "\nALL SIGNAL-LOCKOUT E2E TESTS PASSED ($passed)" else "\n$failed FAILURES")
    if (failed > 0) kotlin.system.exitProcess(1)
}
