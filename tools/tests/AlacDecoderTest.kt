import com.castlink.receiver.airplay.handshake.AlacDecoder

/**
 * 纯 JVM 测试：AlacDecoder 的参数校验路径（不依赖 android.jar）。
 *
 * 注意：真解码需要设备上的 libalac.so，本测试仅覆盖：
 *  1. buildMagicCookie 的 24 字节大端布局；
 *  2. 无 native 库时构造失败的行为（JVM 上预期 UnsatisfiedLinkError；
 *     真机上 jniLibs/<abi>/libalac.so 随 APK 安装，loadLibrary 成功）。
 */
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("ok: $name") }
        else { failed++; println("FAIL: $name") }
    }

    // 1. magic cookie 布局：frameLength=352 BE32, bitDepth=16, channels=2, sr=44100 BE32
    val c = AlacDecoder.buildMagicCookie(44100, 2, 352)
    check("cookie 24 bytes", c.size == 24)
    check("frameLength BE32 == 352",
        c[0] == 0.toByte() && c[1] == 0.toByte() && c[2] == 0x01.toByte() && c[3] == 0x60.toByte())
    check("compatibleVersion == 0", c[4] == 0.toByte())
    check("bitDepth == 16", c[5] == 16.toByte())
    check("pb/mb/kb == 40/10/14", c[6] == 40.toByte() && c[7] == 10.toByte() && c[8] == 14.toByte())
    check("numChannels == 2", c[9] == 2.toByte())
    check("maxRun BE16 == 255", c[10] == 0.toByte() && c[11] == 255.toByte())
    check("maxFrameBytes/avgBitRate == 0",
        c.slice(12..19).all { it == 0.toByte() })
    check("sampleRate BE32 == 44100",
        c[20] == 0.toByte() && c[21] == 0.toByte() && c[22] == 0xAC.toByte() && c[23] == 0x44.toByte())
    // 与 host C 测试的 replica 一致（Init 已验证接受此布局）
    val c2 = AlacDecoder.buildMagicCookie(48000, 1, 1024)
    check("mono cookie channels==1", c2[9] == 1.toByte())
    check("frameLength == 1024", c2[2] == 0x04.toByte() && c2[3] == 0x00.toByte())

    // 2. 无 libalac.so 时构造失败：JVM 上预期 UnsatisfiedLinkError（干净失败，不崩溃）
    var threwUnsatisfied = false
    try {
        AlacDecoder(44100, 2, 352)
    } catch (e: UnsatisfiedLinkError) {
        threwUnsatisfied = true
    } catch (e: Throwable) {
        println("  (unexpected ${e.javaClass.name}: ${e.message})")
    }
    check("constructor fails cleanly without native lib (UnsatisfiedLinkError)", threwUnsatisfied)

    println(if (failed == 0) "\nALL ALACDECODER JVM TESTS PASSED ($passed)" else "\n$failed FAILURES")
    println("NOTE: 真解码路径（nativeDecode）需真机 + libalac.so，本轮未验证。")
    if (failed > 0) kotlin.system.exitProcess(1)
}
