import com.castlink.receiver.protocol.FilePushServer
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files

/**
 * FilePushServer 边缘用例（JVM，真实生产类，IPv6 loopback）：
 *   - 超长文件名（>200 字符）→ 400 且不落盘（净化上限 128，见 sanitizeFileName）；
 *   - 边界：128 字符名通过、129 字符名拒绝；
 *   - 重名文件：第二次 PUT 直接覆盖，内容以后者为准；
 *   - 空文件（Content-Length: 0）→ 200 落盘 0 字节，回调仍触发；
 *   - GET /push/<name> → 404（仅允许 PUT）。
 */
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("ok: $name") }
        else { failed++; println("FAIL: $name") }
    }

    val dir = Files.createTempDirectory("filepush-edge").toFile()
    val received = mutableListOf<File>()
    val server = FilePushServer(
        port = 18757,
        targetDir = dir,
        onFileReceived = { synchronized(received) { received.add(it) } },
        log = {},
    )
    server.start()
    Thread.sleep(300)

    fun request(method: String, path: String, body: ByteArray): String {
        Socket(InetAddress.getByName("::1"), 18757).use { s ->
            s.soTimeout = 5000
            val out = s.getOutputStream()
            val head = "$method $path HTTP/1.1\r\nHost: x\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(body)
            out.flush()
            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
    fun put(path: String, body: ByteArray) = request("PUT", path, body)

    // ── 1. 超长文件名（300 字符）→ 400，不落盘 ────────────────────────────
    val longName = "a".repeat(300)
    val respLong = put("/push/$longName", "x".toByteArray())
    check("300-char name -> 400", respLong.startsWith("HTTP/1.1 400"))
    check("300-char name not on disk", dir.listFiles()?.isEmpty() == true)

    // ── 2. sanitize 边界：128 通过 / 129 拒绝（单元 + 走线） ───────────────
    check("sanitize 128 ok", FilePushServer.sanitizeFileName("b".repeat(128))?.length == 128)
    check("sanitize 129 rejected", FilePushServer.sanitizeFileName("b".repeat(129)) == null)
    check("sanitize 300 rejected", FilePushServer.sanitizeFileName("c".repeat(300)) == null)
    val resp128 = put("/push/${"b".repeat(128)}", "z".toByteArray())
    check("wire: 128-char name -> 200", resp128.startsWith("HTTP/1.1 200"))
    check("128-char file on disk", File(dir, "b".repeat(128)).isFile)
    check("wire: 129-char name -> 400", put("/push/${"b".repeat(129)}", "z".toByteArray()).startsWith("HTTP/1.1 400"))

    // ── 3. 重名覆盖：第二次 PUT 内容替换第一次 ─────────────────────────────
    val body1 = "first-version".toByteArray()
    val body2 = "second-version-longer".toByteArray()
    check("overwrite#1 -> 200", put("/push/dup.mp4", body1).startsWith("HTTP/1.1 200"))
    check("overwrite#2 -> 200", put("/push/dup.mp4", body2).startsWith("HTTP/1.1 200"))
    val dup = File(dir, "dup.mp4")
    check("overwrite content replaced", dup.isFile && dup.readBytes().contentEquals(body2))
    check("overwrite no extra file", dir.listFiles { f -> f.name == "dup.mp4" }?.size == 1)

    // ── 4. 空文件：Content-Length 0 → 200，落盘 0 字节，回调触发 ───────────
    val before = synchronized(received) { received.size }
    check("empty body -> 200", put("/push/empty.bin", ByteArray(0)).startsWith("HTTP/1.1 200"))
    val empty = File(dir, "empty.bin")
    check("empty file on disk", empty.isFile)
    check("empty file size 0", empty.isFile && empty.length() == 0L)
    val gotCallback = synchronized(received) { received.size } > before &&
        synchronized(received) { received.any { it.name == "empty.bin" } }
    check("empty file callback fired", gotCallback)

    // ── 5. GET /push/<name> → 404 ─────────────────────────────────────────
    check("GET /push/x -> 404", request("GET", "/push/x", ByteArray(0)).startsWith("HTTP/1.1 404"))

    server.stop()
    dir.deleteRecursively()
    println(if (failed == 0) "\nALL FILEPUSH EDGE TESTS PASSED ($passed)" else "\n$failed FAILURES")
    if (failed > 0) kotlin.system.exitProcess(1)
}
