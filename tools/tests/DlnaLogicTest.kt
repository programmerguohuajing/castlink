import com.castlink.receiver.dlna.server.DeviceDescription
import com.castlink.receiver.dlna.server.DlnaHttpServer
import com.castlink.receiver.dlna.server.ScpdDescriptions
import com.castlink.receiver.dlna.service.AvTransportService
import com.castlink.receiver.dlna.service.ConnectionManagerService
import com.castlink.receiver.dlna.service.DidlInfo
import com.castlink.receiver.dlna.service.DidlParser
import com.castlink.receiver.dlna.service.DlnaControl
import com.castlink.receiver.dlna.service.PlaybackSnapshot
import com.castlink.receiver.dlna.service.RenderingControlService
import com.castlink.receiver.dlna.service.SoapCodec
import com.castlink.receiver.dlna.service.SoapDispatcher
import com.castlink.receiver.dlna.service.TransportState
import com.castlink.receiver.dlna.service.UpnpError
import com.castlink.receiver.dlna.ssdp.SsdpPacket
import com.castlink.receiver.dlna.util.DlnaTime

/**
 * 纯 JVM 测试：DLNA DMR 逻辑层（SSDP 报文 / DIDL 解析 / SOAP 编解码 /
 * AVTransport 状态机 / HTTP roundtrip），不依赖 android.jar。
 */
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("ok: $name") }
        else { failed++; println("FAIL: $name") }
    }

    // ---------- DlnaTime ----------
    check("time format 0", DlnaTime.format(0) == "00:00:00")
    check("time format 4512000", DlnaTime.format(4_512_000) == "01:15:12")
    check("time format no millis", !DlnaTime.format(4_512_999).contains("."))
    check("time format 100h+", DlnaTime.format(360_000_000L) == "100:00:00")
    check("time parse HH:mm:ss", DlnaTime.parse("00:45:12") == 2_712_000L)
    check("time parse MM:SS", DlnaTime.parse("45:12") == 2_712_000L)
    check("time parse with fraction", DlnaTime.parse("00:00:10.500") == 10_000L)
    check("time parse invalid", DlnaTime.parse("abc") == -1L)
    check("time parse empty", DlnaTime.parse("") == -1L)
    check("didl duration seconds", DlnaTime.parseDidlDuration("245.000") == 245_000L)
    check("didl duration hms", DlnaTime.parseDidlDuration("00:04:05") == 245_000L)
    check("didl duration empty", DlnaTime.parseDidlDuration("") == 0L)

    // ---------- SsdpPacket ----------
    val msearchRaw = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ns=01; ns=01\"\r\nMX: 3\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
    val ms = SsdpPacket.parseMSearch(msearchRaw.toByteArray(), msearchRaw.length)
    check("msearch parsed", ms != null && ms.st == "urn:schemas-upnp-org:device:MediaRenderer:1" && ms.mx == 3)
    check("msearch non-msearch null",
        SsdpPacket.parseMSearch("NOTIFY * HTTP/1.1\r\n\r\n".toByteArray(), 22) == null)
    val udn = "uuid:12345678-1234-1234-1234-123456789abc"
    check("relevant ssdp:all", SsdpPacket.isRelevant("ssdp:all", udn))
    check("relevant rootdevice", SsdpPacket.isRelevant("upnp:rootdevice", udn))
    check("relevant uuid", SsdpPacket.isRelevant(udn, udn))
    check("relevant device type",
        SsdpPacket.isRelevant("urn:schemas-upnp-org:device:MediaRenderer:1", udn))
    check("irrelevant other", !SsdpPacket.isRelevant("urn:schemas-upnp-org:device:MediaServer:1", udn))
    val okResp = SsdpPacket.buildOkResponse("192.168.1.10", 8758, udn,
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "::urn:schemas-upnp-org:device:MediaRenderer:1")
    check("ok has LOCATION", okResp.contains("LOCATION: http://192.168.1.10:8758/description.xml"))
    check("ok has USN", okResp.contains("USN: $udn::urn:schemas-upnp-org:device:MediaRenderer:1"))
    check("ok has ST", okResp.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1"))
    check("all -> 3 responses", SsdpPacket.buildOkResponsesForAll("192.168.1.10", 8758, udn).size == 3)
    val alive = SsdpPacket.buildNotifyAlive("192.168.1.10", 8758, udn)
    check("alive 3 msgs", alive.size == 3 && alive.all { it.contains("NTS: ssdp:alive") })
    val byebye = SsdpPacket.buildNotifyByebye(udn)
    check("byebye 3 msgs", byebye.size == 3 && byebye.all { it.contains("NTS: ssdp:byebye") })

    // ---------- DidlParser ----------
    val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
        """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
        """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
        """<item id="1" parentID="0" restricted="1">""" +
        """<dc:title>测试视频 第1集</dc:title>""" +
        """<upnp:albumArtURI>http://cdn.example.com/poster.jpg</upnp:albumArtURI>""" +
        """<res protocolInfo="http-get:*:video/mp4:*" duration="00:45:12">http://cdn.example.com/v.mp4</res>""" +
        """</item></DIDL-Lite>"""
    val info = DidlParser.parse(didl)
    check("didl title", info.title == "测试视频 第1集")
    check("didl art", info.albumArtUri == "http://cdn.example.com/poster.jpg")
    check("didl duration", info.durationMs == 2_712_000L)
    check("didl empty", DidlParser.parse("") == DidlInfo())
    check("didl garbage", DidlParser.parse("not xml") == DidlInfo())

    // ---------- SoapCodec ----------
    check("soapaction quoted",
        SoapCodec.actionFromHeader("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"") == "Play")
    check("soapaction bare",
        SoapCodec.actionFromHeader("urn:schemas-upnp-org:service:AVTransport:1#Pause") == "Pause")
    val playBody = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">""" +
        """<s:Body><u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">""" +
        """<InstanceID>0</InstanceID><Speed>1</Speed></u:Play></s:Body></s:Envelope>"""
    val playArgs = SoapCodec.parseArgs(playBody)
    check("parseArgs Play", playArgs["InstanceID"] == "0" && playArgs["Speed"] == "1")
    val env = SoapCodec.successEnvelope("urn:schemas-upnp-org:service:AVTransport:1",
        "GetTransportInfo", mapOf("CurrentTransportState" to "PLAYING"))
    check("success envelope", env.contains("<u:GetTransportInfoResponse") &&
        env.contains("<CurrentTransportState>PLAYING</CurrentTransportState>"))
    val fault = SoapCodec.faultEnvelope(402, "Invalid Args")
    check("fault envelope", fault.contains("<errorCode>402</errorCode>") &&
        fault.contains("UPnPError"))
    check("escapeXml", SoapCodec.escapeXml("<a>&\"'") == "&lt;a&gt;&amp;&quot;&apos;")

    // ---------- AvTransportService（FakeDlnaControl） ----------
    val fake = FakeDlnaControl()
    val avt = AvTransportService(fake)

    // 初始：NO_MEDIA_PRESENT
    check("initial state", avt.handle("GetTransportInfo", emptyMap())["CurrentTransportState"] == "NO_MEDIA_PRESENT")

    // 文档 §3.3.1：SetAVTransportURI 后立即 TRANSITIONING（严禁 STOPPED）
    avt.handle("SetAVTransportURI", mapOf(
        "InstanceID" to "0",
        "CurrentURI" to "http://cdn.example.com/v.m3u8",
        "CurrentURIMetaData" to didl,
    ))
    check("seturi -> TRANSITIONING",
        avt.handle("GetTransportInfo", emptyMap())["CurrentTransportState"] == "TRANSITIONING")
    check("seturi stored uri", fake.lastUri == "http://cdn.example.com/v.m3u8")
    check("seturi stored title", fake.lastMeta.title == "测试视频 第1集")

    // Play → PLAYING
    avt.handle("Play", mapOf("InstanceID" to "0", "Speed" to "1"))
    fake.state = TransportState.PLAYING; fake.positionMs = 65_000; fake.durationMs = 2_712_000
    val pos = avt.handle("GetPositionInfo", mapOf("InstanceID" to "0"))
    check("position RelTime HH:mm:ss", pos["RelTime"] == "00:01:05")
    check("position AbsTime == RelTime", pos["AbsTime"] == pos["RelTime"])
    check("position TrackDuration", pos["TrackDuration"] == "00:45:12")
    check("position TrackURI", pos["TrackURI"] == "http://cdn.example.com/v.m3u8")
    val media = avt.handle("GetMediaInfo", mapOf("InstanceID" to "0"))
    check("media duration format", media["MediaDuration"] == "00:45:12")
    check("media PlayMedium", media["PlayMedium"] == "NETWORK")

    // Pause / Seek / Stop
    avt.handle("Pause", mapOf("InstanceID" to "0"))
    check("pause called", fake.paused)
    avt.handle("Seek", mapOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to "00:10:00"))
    check("seek 10min", fake.lastSeekMs == 600_000L)
    try {
        avt.handle("Seek", mapOf("InstanceID" to "0", "Unit" to "X_ABS", "Target" to "1"))
        check("seek bad unit -> 402", false)
    } catch (e: UpnpError) {
        check("seek bad unit -> 402", e.code == 402)
    }
    try {
        avt.handle("SetAVTransportURI", mapOf("InstanceID" to "0", "CurrentURI" to ""))
        check("seturi empty -> 402", false)
    } catch (e: UpnpError) {
        check("seturi empty -> 402", e.code == 402)
    }
    try {
        avt.handle("SetAVTransportURI", mapOf("InstanceID" to "0", "CurrentURI" to "rtsp://x/y"))
        check("seturi rtsp -> 402", false)
    } catch (e: UpnpError) {
        check("seturi rtsp -> 402", e.code == 402)
    }
    try {
        avt.handle("Frobnicator", emptyMap())
        check("unknown action -> 401", false)
    } catch (e: UpnpError) {
        check("unknown action -> 401", e.code == 401)
    }
    avt.handle("Stop", mapOf("InstanceID" to "0"))
    check("stop called", fake.stopped)

    // ---------- RenderingControl / ConnectionManager ----------
    val rc = RenderingControlService(fake)
    check("rc getvolume", rc.handle("GetVolume", emptyMap())["CurrentVolume"] == "42")
    rc.handle("SetVolume", mapOf("DesiredVolume" to "80"))
    check("rc setvolume", fake.vol == 80)
    rc.handle("SetMute", mapOf("DesiredMute" to "1"))
    check("rc mute", fake.mute && rc.handle("GetMute", emptyMap())["CurrentMute"] == "1")

    val cm = ConnectionManagerService()
    val proto = cm.handle("GetProtocolInfo", emptyMap())
    check("cm sink has mpegurl", (proto["Sink"] ?: "").contains("application/x-mpegURL"))
    check("cm conn ids", cm.handle("GetCurrentConnectionIDs", emptyMap())["ConnectionIDs"] == "0")

    // ---------- SoapDispatcher ----------
    val dispatcher = SoapDispatcher(avt, rc, cm)
    val (code, body) = dispatcher.dispatch("/control/avt",
        "\"urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\"", "<xml/>")
    check("dispatcher 200", code == 200 && body.contains("GetTransportInfoResponse"))
    val (code2, body2) = dispatcher.dispatch("/control/nope",
        "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", "<xml/>")
    check("dispatcher bad path -> 401", code2 == 500 && body2.contains("<errorCode>401</errorCode>"))
    val (code3, body3) = dispatcher.dispatch("/control/avt",
        "\"urn:schemas-upnp-org:service:AVTransport:1#Nope\"", "<xml/>")
    check("dispatcher bad action -> 401", code3 == 500 && body3.contains("<errorCode>401</errorCode>"))

    // ---------- DeviceDescription / SCPD ----------
    val desc = DeviceDescription.build("192.168.1.10", 8758, udn, "CastLink-TV")
    check("desc has UDN", desc.contains(udn))
    check("desc has friendlyName", desc.contains("<friendlyName>CastLink-TV</friendlyName>"))
    check("desc has 3 services", desc.split("<service>").size == 4)
    check("desc control urls", desc.contains("<controlURL>/control/avt</controlURL>"))
    check("scpd avt", (ScpdDescriptions.get("avt") ?: "").contains("<name>SetAVTransportURI</name>"))
    check("scpd rc", (ScpdDescriptions.get("rc") ?: "").contains("<name>SetVolume</name>"))
    check("scpd cm", (ScpdDescriptions.get("cm") ?: "").contains("<name>GetProtocolInfo</name>"))
    check("scpd unknown null", ScpdDescriptions.get("xx") == null)

    // ---------- DlnaHttpServer 路由（纯 route()，沙箱禁 raw TCP，用无 socket 方式测） ----------
    val server = DlnaHttpServer(
        port = 0,
        descriptionProvider = { DeviceDescription.build("127.0.0.1", 8758, udn, "CastLink-TV") },
        scpdProvider = { ScpdDescriptions.get(it) },
        soapHandler = { path, action, b -> dispatcher.dispatch(path, action, b) },
    )
    fun req(method: String, path: String, headers: Map<String, String> = emptyMap(), body: String = "") =
        DlnaHttpServer.Request(method, path, headers, body)

    val rDesc = server.route(req("GET", "/description.xml"))
    check("route GET description.xml 200",
        rDesc.code == 200 && rDesc.body.contains("<friendlyName>CastLink-TV</friendlyName>"))
    check("route HEAD no body",
        server.route(req("HEAD", "/description.xml")).let { it.code == 200 && it.body.isEmpty() })
    check("route GET scpd/avt.xml 200", server.route(req("GET", "/scpd/avt.xml")).code == 200)
    check("route GET scpd/xx.xml 404", server.route(req("GET", "/scpd/xx.xml")).code == 404)
    check("route GET /nope 404", server.route(req("GET", "/nope")).code == 404)
    check("route SUBSCRIBE 200", server.route(req("SUBSCRIBE", "/event/avt")).code == 200)
    check("route DELETE 405", server.route(req("DELETE", "/description.xml")).code == 405)
    // SOAP POST 路由：GetTransportInfo
    val soapBody = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">""" +
        """<s:Body><u:GetTransportInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">""" +
        """<InstanceID>0</InstanceID></u:GetTransportInfo></s:Body></s:Envelope>"""
    val rSoap = server.route(req("POST", "/control/avt",
        mapOf("soapaction" to "\"urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\""), soapBody))
    check("route POST SOAP 200",
        rSoap.code == 200 && rSoap.body.contains("GetTransportInfoResponse"))
    // SOAPAction 头大小写不敏感（http server 已转小写 key）
    val rSoap2 = server.route(req("POST", "/control/rc",
        mapOf("soapaction" to "\"urn:schemas-upnp-org:service:RenderingControl:1#GetVolume\""),
        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<u:GetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">""" +
            """<InstanceID>0</InstanceID><Channel>Master</Channel></u:GetVolume></s:Body></s:Envelope>"""))
    // 注意：前面 RC 单测已 SetVolume(80)，此处期望 80
    check("route POST RC GetVolume",
        rSoap2.code == 200 && rSoap2.body.contains("<CurrentVolume>80</CurrentVolume>"))

    println("\n=== DLNA 逻辑测试: passed=$passed failed=$failed ===")
    if (failed > 0) kotlin.system.exitProcess(1)
}

/** 单测替身：记录调用，状态可外部驱动 */
class FakeDlnaControl : DlnaControl {
    var state = TransportState.NO_MEDIA_PRESENT
    var positionMs = 0L
    var durationMs = 0L
    var lastUri = ""
    var lastMeta = DidlInfo()
    var lastSeekMs = -1L
    var paused = false
    var stopped = false
    var vol = 42
    var mute = false

    override fun setUri(uri: String, meta: DidlInfo, rawDidlXml: String) {
        lastUri = uri; lastMeta = meta
        durationMs = meta.durationMs; positionMs = 0
        state = TransportState.TRANSITIONING
    }
    override fun play() { state = TransportState.PLAYING }
    override fun pause() { paused = true; state = TransportState.PAUSED_PLAYBACK }
    override fun stop() { stopped = true; state = TransportState.STOPPED }
    override fun seekTo(positionMs: Long) { lastSeekMs = positionMs; this.positionMs = positionMs }
    override fun snapshot() = PlaybackSnapshot(state, positionMs, durationMs, lastUri, "", lastMeta.title)
    override fun setVolumePercent(percent: Int) { vol = percent }
    override fun getVolumePercent() = vol
    override fun setMuted(muted: Boolean) { mute = muted }
    override fun isMuted() = mute
}
