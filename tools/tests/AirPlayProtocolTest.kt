package com.castlink.receiver.airplay.handshake

import com.castlink.receiver.airplay.SdpParser
import com.castlink.receiver.airplay.util.Base64Util
import com.dd.plist.NSArray
import com.dd.plist.BinaryPropertyListWriter
import javax.crypto.Cipher

/**
 * AirPlay 协议 JVM 自测（不引 android.jar；airplay.util.Logger 用 tools/tests/stubs 桩）。
 *
 * 重建 v0.2.0 的"50 项协议测试"（BUILD-INSTRUCTIONS.md 只留了文字记录，测试文件未存档），
 * 覆盖与当初记录一致的四个维度：FairPlay fp-setup 字节向量、PlistCodec 编解码 roundtrip、
 * SdpParser（视频+音频 / 纯音频 / 畸形输入）、Base64Util roundtrip；
 * 另加 RaopRsa RSA 路径与 LegacyPairSetupPin 基础行为（当初未单测的 JVM 安全部分）。
 *
 * FairPlay.decrypt() 的 native 路径（libplayfair.so 为 Android ELF）在沙箱 JVM 下无法执行，
 * 仅断言未 handshake 前抛 IllegalStateException；端到端解密仍需真机验证。
 */
private var passed = 0
private fun check(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("FAIL: $name")
    passed++
    println("ok: $name")
}
private fun expectThrow(name: String, block: () -> Unit) {
    try {
        block()
        throw AssertionError("FAIL: $name (no exception)")
    } catch (e: AssertionError) { throw e }
    catch (_: Exception) { passed++; println("ok: $name") }
}
private fun bytesEq(a: ByteArray, b: ByteArray) = a.contentEquals(b)

private val rnd = java.util.Random(20261011)

fun main() {
    testBase64Util()
    testSdpParser()
    testFairPlay()
    testPlistCodec()
    testRaopRsa()
    testLegacyPairSetupPin()
    println("\nALL AIRPLAY PROTOCOL TESTS PASSED ($passed)")
}

// ─── Base64Util ──────────────────────────────────────────────────────────────

private fun testBase64Util() {
    println("== Base64Util ==")
    check("known: Man->TWFu", Base64Util.encode("Man".toByteArray()) == "TWFu")
    check("known: Ma->TWE=", Base64Util.encode("Ma".toByteArray()) == "TWE=")
    check("known: M->TQ==", Base64Util.encode("M".toByteArray()) == "TQ==")
    check("decode known", bytesEq(Base64Util.decode("TWFu"), "Man".toByteArray()))
    check("empty encode", Base64Util.encode(ByteArray(0)) == "")
    check("empty decode", Base64Util.decode("").isEmpty())
    check("whitespace tolerated", bytesEq(Base64Util.decode("TW\nFu\r\n T\tWFu"), "ManMan".toByteArray()))
    val big = ByteArray(512).also { rnd.nextBytes(it) }
    check("512B roundtrip", bytesEq(Base64Util.decode(Base64Util.encode(big)), big))
    val all = ByteArray(256) { it.toByte() }
    check("all-bytes roundtrip", bytesEq(Base64Util.decode(Base64Util.encode(all)), all))
    for (n in 1..5) {
        val b = ByteArray(n) { (it * 7 + 1).toByte() }
        check("len $n roundtrip", bytesEq(Base64Util.decode(Base64Util.encode(b)), b))
    }
    expectThrow("invalid char throws") { Base64Util.decode("TW@u") }
}

// ─── SdpParser ───────────────────────────────────────────────────────────────

private val SPS = byteArrayOf(0x67, 0x64, 0x00, 0x20, 0xAC.toByte(), 0x2B.toByte(), 0x40, 0x28)
private val PPS = byteArrayOf(0x68, 0xE9.toByte(), 0x7B, 0xCB.toByte())

private fun fullSdp(
    rsaaeskeyB64: String? = null,
    fpaeskeyB64: String? = null,
    aesivB64: String = Base64Util.encode(ByteArray(16)),
    sprop: String = "${Base64Util.encode(SPS)},${Base64Util.encode(PPS)}",
): String = buildString {
    appendLine("v=0")
    appendLine("o=AirTunes AA:BB:CC:DD:EE:FF 1 IN IP4 192.168.1.10")
    appendLine("s=AirTunes")
    appendLine("t=0 0")
    appendLine("m=video 0 RTP/AVP 96")
    appendLine("a=rtpmap:96 H264/90000")
    appendLine("a=fmtp:96 packetization-mode=1;profile-level-id=640020;sprop-parameter-sets=$sprop")
    appendLine("m=audio 0 RTP/AVP 96")
    appendLine("a=rtpmap:96 AppleLossless")
    appendLine("a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100")
    if (rsaaeskeyB64 != null) appendLine("a=rsaaeskey:$rsaaeskeyB64")
    if (fpaeskeyB64 != null) appendLine("a=fpaeskey:$fpaeskeyB64")
    appendLine("a=aesiv:$aesivB64")
}

private fun testSdpParser() {
    println("== SdpParser ==")
    val key16 = ByteArray(16) { (it + 1).toByte() }
    val d = SdpParser.parse(fullSdp(rsaaeskeyB64 = Base64Util.encode(key16)))!!
    check("video+audio: hasVideo", d.hasVideo)
    check("video+audio: hasAudio", d.hasAudio)
    check("codec ALAC", d.audioCodec == com.castlink.receiver.airplay.AudioCodec.ALAC)
    check("sampleRate 44100", d.sampleRate == 44100)
    check("channels 2", d.channels == 2)
    check("sps matches", d.spsBytes?.let { bytesEq(it, SPS) } == true)
    check("pps matches", d.ppsBytes?.let { bytesEq(it, PPS) } == true)
    check("profile-level-id", d.h264ProfileLevelId == "640020")
    check("alac frames 352", d.alacFramesPerPacket == 352)
    check("rsaaeskey passthrough 16B", d.aesKey?.let { bytesEq(it, key16) } == true)
    check("isAudioEncrypted", d.isAudioEncrypted)

    // RSA 路径：用内建公钥加密 → SDP → 解析 → RaopRsa 解出原 key
    val pub = RaopRsa.publicKeyForTest()
    val enc = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        .apply { init(Cipher.ENCRYPT_MODE, pub) }.doFinal(key16)
    check("rsa blob 256B", enc.size == 256)
    val dRsa = SdpParser.parse(fullSdp(rsaaeskeyB64 = Base64Util.encode(enc)))!!
    check("rsa decrypt roundtrip", dRsa.aesKey?.let { bytesEq(it, key16) } == true)

    // fpaeskey（FairPlay 包裹，72B 原样保留）
    val fp = ByteArray(72) { 0x5A }
    val dFp = SdpParser.parse(fullSdp(fpaeskeyB64 = Base64Util.encode(fp)))!!
    check("fpaeskey 72B", dFp.fpAesKey?.let { bytesEq(it, fp) } == true)

    // 纯音频 AAC-ELD
    val aac = SdpParser.parse(
        "v=0\r\nm=audio 0 RTP/AVP 96\r\na=rtpmap:96 mpeg4-generic/48000/1\r\n"
    )!!
    check("aac: hasAudio", aac.hasAudio)
    check("aac: codec AAC_ELD", aac.audioCodec == com.castlink.receiver.airplay.AudioCodec.AAC_ELD)
    check("aac: 48000/1", aac.sampleRate == 48000 && aac.channels == 1)
    check("aac: isAudioOnly", aac.isAudioOnly)

    // 畸形输入
    check("blank -> null", SdpParser.parse("   \n  ") == null)
    check("garbage -> null", SdpParser.parse("hello world") == null)
    check("video w/o sps -> null", SdpParser.parse("v=0\nm=video 0 RTP/AVP 96\na=rtpmap:96 H264/90000\n") == null)
    check("bad sprop base64 -> video dropped, audio kept",
        SdpParser.parse(fullSdp(sprop = "!!!,???"))?.let { !it.hasVideo && it.hasAudio } == true)
    check("rsaaeskey garbage (256B) -> aesKey null",
        SdpParser.parse(fullSdp(rsaaeskeyB64 = Base64Util.encode(ByteArray(256) { 9 })))!!.aesKey == null)
    val dBadIv = SdpParser.parse(fullSdp(aesivB64 = Base64Util.encode(ByteArray(8))))!!
    check("short iv parsed", dBadIv.aesIv?.size == 8)
    check("short iv -> not encrypted", !dBadIv.isAudioEncrypted)
}

// ─── FairPlay ────────────────────────────────────────────────────────────────

private fun fpReq(version: Int, mode: Int): ByteArray =
    ByteArray(16).also { it[4] = version.toByte(); it[14] = mode.toByte() }

private fun testFairPlay() {
    println("== FairPlay ==")
    val fp = FairPlay()
    val replies = (0..3).map { fp.setup(fpReq(0x03, it)) }
    replies.forEachIndexed { i, r ->
        check("v3 mode $i -> 142B", r.size == 142)
        check("v3 mode $i magic FPLY", r[0] == 0x46.toByte() && r[1] == 0x50.toByte()
            && r[2] == 0x4C.toByte() && r[3] == 0x59.toByte())
    }
    check("v3 modes distinct", !bytesEq(replies[0], replies[1]))
    check("v3 negotiatedVersion==3", fp.negotiatedVersion == 3)
    // 返回的是拷贝：篡改一次结果不影响下一次（reply[12] 原值 0x02）
    replies[0][12] = 0x7F
    check("v3 reply is a copy", fp.setup(fpReq(0x03, 0))[12] == 0x02.toByte())

    val v2 = fp.setup(fpReq(0x02, 2))
    check("v2 -> 142B", v2.size == 142)
    check("v2 mode patched at [13]", v2[13] == 2.toByte())
    check("v2 negotiatedVersion==2", fp.negotiatedVersion == 2)

    expectThrow("setup: 15B throws") { fp.setup(ByteArray(15)) }
    expectThrow("setup: mode 4 throws") { fp.setup(fpReq(0x03, 4)) }
    expectThrow("setup: version 0x04 throws") { fp.setup(fpReq(0x04, 0)) }

    // phase 2 handshake：12 字节头（FPLY xx 01 04 …，版本字节回填）+ 请求末 20 字节原样回显
    val hs3 = ByteArray(164).also { it[4] = 0x03; rnd.nextBytes(it) }.also { it[4] = 0x03 }
    val rep3 = fp.handshake(hs3)
    check("handshake -> 32B", rep3.size == 32)
    check("handshake v3 echo", rep3[4] == 0x03.toByte())
    check("handshake magic", rep3[0] == 0x46.toByte() && rep3[3] == 0x59.toByte())
    check("handshake tail echo", bytesEq(rep3.copyOfRange(12, 32), hs3.copyOfRange(144, 164)))
    val hs2 = ByteArray(164).also { it[4] = 0x02 }
    check("handshake v2 echo", fp.handshake(hs2)[4] == 0x02.toByte())
    expectThrow("handshake: 100B throws") { fp.handshake(ByteArray(100)) }
    expectThrow("decrypt before handshake throws") { FairPlay().decrypt(ByteArray(72)) }
}

// ─── PlistCodec ──────────────────────────────────────────────────────────────

private fun testPlistCodec() {
    println("== PlistCodec ==")
    val map = mapOf(
        "name" to "CastLink",
        "int" to 42,
        "long" to 1234567890123L,
        "bool" to true,
        "dbl" to 1.5,
        "blob" to byteArrayOf(1, 2, 3),
        "nested" to mapOf("a" to "b"),
        "list" to listOf("x", 7),
    )
    val bin = PlistCodec.encode(map)
    check("binary magic bplist00", bin.copyOf(8).toString(Charsets.US_ASCII) == "bplist00")
    val dec = PlistCodec.decode(bin)
    check("str", dec["name"] == "CastLink")
    check("int -> Long", dec["int"] == 42L)
    check("long", dec["long"] == 1234567890123L)
    check("bool", dec["bool"] == true)
    check("double", dec["dbl"] == 1.5)
    check("blob", (dec["blob"] as? ByteArray)?.let { bytesEq(it, byteArrayOf(1, 2, 3)) } == true)
    @Suppress("UNCHECKED_CAST")
    check("nested", (dec["nested"] as? Map<String, Any?>)?.get("a") == "b")
    @Suppress("UNCHECKED_CAST")
    check("list", (dec["list"] as? List<Any?>)?.let { it[0] == "x" && it[1] == 7L } == true)

    val xml = PlistCodec.encodeXml(mapOf("k" to "v"))
    val xmlStr = xml.toString(Charsets.UTF_8)
    check("xml contains plist", xmlStr.contains("<plist"))
    check("xml roundtrip", PlistCodec.decode(xml)["k"] == "v")

    val nonDict = BinaryPropertyListWriter.writeToArray(NSArray(com.dd.plist.NSString("x")))
    expectThrow("decode non-dict throws") { PlistCodec.decode(nonDict) }
}

// ─── RaopRsa ─────────────────────────────────────────────────────────────────

private fun testRaopRsa() {
    println("== RaopRsa ==")
    val pub = RaopRsa.publicKeyForTest()
    val key = ByteArray(16) { it.toByte() }
    val blob = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        .apply { init(Cipher.ENCRYPT_MODE, pub) }.doFinal(key)
    check("encrypt->decrypt roundtrip", RaopRsa.decryptAesKey(blob)?.let { bytesEq(it, key) } == true)
    check("garbage -> null", RaopRsa.decryptAesKey(ByteArray(256) { 7 }) == null)
    check("16B blob -> null", RaopRsa.decryptAesKey(key) == null)
}

// ─── LegacyPairSetupPin ──────────────────────────────────────────────────────

private fun testLegacyPairSetupPin() {
    println("== LegacyPairSetupPin ==")
    val srv = LegacyPairSetupPin("2468", ByteArray(32))
    val bad = srv.handle(mapOf("foo" to "bar"))
    check("unrecognized -> failed", bad.failed && bad.reply == null)

    val r1 = srv.handle(mapOf("method" to "pin", "user" to "tester"))
    check("step1 -> pk+salt", r1.reply?.get("pk") is ByteArray && (r1.reply?.get("salt") as? ByteArray)?.size == 16)
    check("step1 not complete", !r1.complete && !r1.failed)

    val r2 = srv.handle(mapOf("pk" to ByteArray(10)))
    check("step2 missing proof -> failed", r2.failed)

    val salt = r1.reply!!["salt"] as ByteArray
    val pk = r1.reply!!["pk"] as ByteArray
    check("salt 16B pk non-empty", salt.size == 16 && pk.isNotEmpty())
}
