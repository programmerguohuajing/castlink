import com.castlink.receiver.protocol.FilePushServer
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 纯 JVM 测试：FilePushServer HTTP 文件接收 roundtrip（不依赖 android.jar）。 */
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("ok: $name") }
        else { failed++; println("FAIL: $name") }
    }

    val dir = Files.createTempDirectory("filepush-test").toFile()
    val received = CountDownLatch(1)
    var receivedFile: File? = null
    val server = FilePushServer(
        port = 18756,
        targetDir = dir,
        onFileReceived = { receivedFile = it; received.countDown() },
        log = {},
    )
    server.start()
    Thread.sleep(300)

    fun put(path: String, body: ByteArray): String {
        Socket(InetAddress.getByName("::1"), 18756).use { s ->
            s.soTimeout = 5000
            val out = s.getOutputStream()
            val head = "PUT $path HTTP/1.1\r\nHost: x\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(body)
            out.flush()
            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    // 1. 正常 roundtrip：1 MiB 随机内容
    val payload = ByteArray(1024 * 1024) { (it * 31 % 251).toByte() }
    val resp1 = put("/push/hello.mp4", payload)
    check("PUT 200", resp1.startsWith("HTTP/1.1 200"))
    check("callback fired", received.await(5, TimeUnit.SECONDS))
    val f = File(dir, "hello.mp4")
    check("file exists", f.isFile)
    check("content identical", f.isFile && f.readBytes().contentEquals(payload))
    check("callback path matches", receivedFile?.absolutePath == f.absolutePath)

    // 2. 中文文件名（URL 编码）
    val resp2 = put("/push/%E6%B5%8B%E8%AF%95.mp3", "audio".toByteArray())
    check("utf8 name 200", resp2.startsWith("HTTP/1.1 200"))
    check("utf8 file exists", File(dir, "测试.mp3").isFile)

    // 3. 非法文件名 → 400
    check("path traversal 400", put("/push/..%2Fevil", "x".toByteArray()).startsWith("HTTP/1.1 400"))
    check("no evil file", !File(dir, "evil").exists() && dir.listFiles()?.none {
        it.name.contains("..")
    } == true)
    check("empty name 400", put("/push/", "x".toByteArray()).startsWith("HTTP/1.1 400"))

    // 4. 非 PUT → 404；GET / → 200 说明
    val getResp = Socket(InetAddress.getByName("::1"), 18756).use { s ->
        s.soTimeout = 5000
        s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
        s.getInputStream().readBytes().toString(Charsets.UTF_8)
    }
    check("GET / 200", getResp.startsWith("HTTP/1.1 200"))

    // 5. sanitizeFileName 单元
    check("sanitize ok", FilePushServer.sanitizeFileName("a b.mp4") == "a b.mp4")
    check("sanitize rejects ..", FilePushServer.sanitizeFileName("..") == null)
    check("sanitize rejects slash", FilePushServer.sanitizeFileName("a/b") == null)
    check("sanitize rejects blank", FilePushServer.sanitizeFileName("  ") == null)

    server.stop()
    dir.deleteRecursively()
    println(if (failed == 0) "\nALL FILEPUSH TESTS PASSED ($passed)" else "\n$failed FAILURES")
    if (failed > 0) kotlin.system.exitProcess(1)
}
