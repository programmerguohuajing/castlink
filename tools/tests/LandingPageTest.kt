import com.castlink.receiver.protocol.FilePushServer
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files

/**
 * v0.3.3：落地页测试 — GET / 返回 HTML（含 PIN、中/英指引、上传表单）；
 * 二维码改为 http://<ip>:8756/ 后 iPhone 相机可识别打开。
 * 纯 JVM（不依赖 android.jar）。
 */
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("ok: $name") }
        else { failed++; println("FAIL: $name") }
    }

    val dir = Files.createTempDirectory("landing-test").toFile()
    val server = FilePushServer(
        port = 18758,
        targetDir = dir,
        pinProvider = { "6832" },
    )
    server.start()
    Thread.sleep(300)

    fun get(lang: String): String {
        Socket(InetAddress.getByName("::1"), 18758).use { s ->
            s.soTimeout = 5000
            val out = s.getOutputStream()
            out.write(
                ("GET / HTTP/1.1\r\nHost: x\r\nAccept-Language: $lang\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
            )
            out.flush()
            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    val zh = get("zh-CN,zh;q=0.9")
    check("zh 200", zh.startsWith("HTTP/1.1 200"))
    check("zh text/html", zh.contains("text/html"))
    check("zh PIN", zh.contains("6 8 3 2"))
    check("zh iOS 指引", zh.contains("屏幕镜像"))
    check("zh 上传表单", zh.contains("/push/") && zh.contains("fetch("))

    val en = get("en-US,en;q=0.9")
    check("en 200", en.startsWith("HTTP/1.1 200"))
    check("en Screen Mirroring", en.contains("Screen Mirroring"))
    check("en PIN", en.contains("6 8 3 2"))

    val dft = get("")
    check("无 Accept-Language 默认英文", dft.contains("Screen Mirroring"))

    server.stop()
    println("LandingPageTest: passed=$passed failed=$failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
