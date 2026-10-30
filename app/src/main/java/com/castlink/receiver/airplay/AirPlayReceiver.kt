/*
 * Ported from PhairPlay (https://github.com/mazer666/PhairPlay, Apache-2.0);
 * package renamed to com.castlink.receiver.airplay.*. See THIRD-PARTY-NOTICES.
 */
package com.castlink.receiver.airplay

import android.content.Context
import android.view.Surface
import com.castlink.receiver.airplay.handshake.AirPlayNtpClient
import com.castlink.receiver.airplay.handshake.AudioStreamServer
import com.castlink.receiver.airplay.handshake.BufferedAudioServer
import com.castlink.receiver.airplay.handshake.MirrorStreamServer
import com.castlink.receiver.airplay.ProtocolState
import com.castlink.receiver.airplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket

/**
 * AirPlayReceiver — Top-level orchestrator for the AirPlay 2 receiver pipeline.
 *
 * WHY: Coordinates all AirPlay components into a single lifecycle:
 * - [MdnsService]: mDNS advertising (makes device visible in sender pickers)
 * - [RtspHandler]: RTSP handshake (OPTIONS → ANNOUNCE → SETUP → RECORD)
 * - [VideoDecoder]: H.264 hardware decode via MediaCodec → SurfaceView
 * - [AudioPlayer]: AES-128-CTR decrypt + AAC/ALAC decode → AudioTrack
 *
 * HOW: [PhairPlayService] creates this receiver and calls [start]/[stop].
 * The pipeline activates lazily — VideoDecoder and AudioPlayer are created
 * only after RECORD is received, when [SessionDescription] is available.
 *
 * For audio-only streams (music, podcasts), only [AudioPlayer] is started —
 * no [VideoDecoder] and no fullscreen streaming surface is needed.
 *
 * State changes are reported via [onStateChanged] to [PhairPlayService].
 *
 * Example:
 *   val receiver = AirPlayReceiver(
 *       context = context,
 *       displayName = settings.effectiveDisplayName,
 *       videoSurfaceProvider = { streamingScreen.getSurface() },
 *       onStateChanged = { state -> /* update UI */ }
 *   )
 *   receiver.start()
 *   receiver.stop()
 */
class AirPlayReceiver(
    private val context: Context,
    /** User-configured display name from Settings (blank = use system device name). */
    private val displayName: String = "",
    /** Advertised mirroring resolution (from the "high resolution" setting). */
    private val mirrorWidth: Int = 1920,
    private val mirrorHeight: Int = 1080,
    /** Whether to accept the mirroring audio stream (experimental — see AppSettings.mirrorAudioEnabled). */
    private val audioEnabled: Boolean = false,
    /** Require HomeKit-style SRP PIN pairing before streaming (AppSettings.airPlayPinAuthEnabled). */
    private val pinAuthEnabled: Boolean = false,
    /** Lazy Surface provider — called only for video streams when RECORD arrives. */
    private val videoSurfaceProvider: () -> Surface?,
    private val onStateChanged: (ProtocolState) -> Unit,
    /**
     * Called with the sender name when a streaming session starts (RECORD received).
     *
     * The name is extracted from the RTSP `User-Agent` header. The caller
     * ([PhairPlayService]) uses this to update the [ActiveConnection] and notification
     * text with the real sender identifier instead of the generic "AirPlay Sender".
     *
     * Guaranteed to be called BEFORE [onStateChanged] is called with [ProtocolState.CONNECTED].
     */
    private val onSenderNameChanged: (String) -> Unit = {},
    /** Called when iOS/macOS sends a JPEG/PNG to the `/photo` endpoint. */
    private val onPhotoReceived: (bytes: ByteArray, imageType: PhotoImageType) -> Unit = { _, _ -> },
    /** Called when iOS/macOS clears the currently displayed `/photo`. */
    private val onPhotoCleared: () -> Unit = {},
    /**
     * Called with the actual mDNS-registered name after [start].
     *
     * The name may differ from [displayName] if another device on the network already uses
     * the same name — NsdManager resolves the collision by appending " (2)", " (3)", etc.
     * The UI can use this callback to show the user the real registered name.
     */
    private val onActualNameRegistered: (String) -> Unit = {},
    /**
     * Audio-only "now playing" state. Emits a [NowPlayingInfo] when audio is streaming WITHOUT video
     * (system audio, Apple Music, podcasts) so the UI can show a now-playing card instead of a black
     * surface; emits null when video is mirroring (the video screen takes over) or audio stops.
     */
    private val onNowPlayingChanged: (NowPlayingInfo?) -> Unit = {},
    /** Pairing PIN to show ([pin]) or hide (null) on the TV during SRP pair-setup. */
    private val onPinChanged: (pin: String?) -> Unit = {}
) {

    // Persistent store of paired controllers (for PIN access control / pair-verify).
    private val pairingStore = com.castlink.receiver.airplay.handshake.PairingStore(context)

    // SupervisorJob: child coroutine failures don't propagate to siblings.
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // Child components
    private var mdnsService: MdnsService? = null
    private var rtspHandler: RtspHandler? = null
    private var timingHandler: TimingHandler? = null
    private var videoDecoder: VideoDecoder? = null
    private var audioPlayer: AudioPlayer? = null

    // UDP socket for receiving audio RTP packets — opened after RECORD, closed on TEARDOWN
    @Volatile private var audioSocket: DatagramSocket? = null

    // AirPlay 2 mirroring: data stream server + event channel + keys (set during SETUP).
    @Volatile private var mirrorServer: MirrorStreamServer? = null
    @Volatile private var audioServer: AudioStreamServer? = null
    @Volatile private var bufferedAudioServer: BufferedAudioServer? = null
    @Volatile private var urlVideoPlayer: AirPlayVideoPlayer? = null

    // Reverse remote control (TV → sender). Created lazily once a sender advertises DACP-ID.
    private val dacpClient = DacpClient(context)
    @Volatile private var ntpClient: AirPlayNtpClient? = null
    @Volatile private var eventSocket: ServerSocket? = null
    @Volatile private var eventClientSocket: java.net.Socket? = null
    @Volatile private var mirrorAesKey: ByteArray? = null
    @Volatile private var mirrorEcdhSecret: ByteArray? = null
    @Volatile private var mirrorAesIv: ByteArray? = null

    // ─── Now-playing (audio-only) state ──────────────────────────────────────
    // The now-playing card shows only when audio plays WITHOUT video. We track both stream kinds
    // plus the latest DMAP metadata/artwork and recompute on every change (see [emitNowPlaying]).
    @Volatile private var audioPlaying = false
    @Volatile private var videoPlaying = false
    @Volatile private var npSenderName = "AirPlay"
    @Volatile private var npTitle: String? = null
    @Volatile private var npArtist: String? = null
    @Volatile private var npAlbum: String? = null
    @Volatile private var npArtwork: ByteArray? = null

    /**
     * Starts the AirPlay receiver.
     *
     * 1. Starts mDNS advertising with the configured display name.
     * 2. Opens the RTSP server socket (port 7000).
     * 3. Emits [ProtocolState.ADVERTISING] once both mDNS services are registered.
     *
     * Non-blocking — all network work runs in background coroutines.
     */
    fun start() {
        Logger.i("AirPlayReceiver starting (displayName='$displayName')")
        // 回填“本机硬解不行”的持久化标记（以往运行确认过）：/info 据此从第一次连接
        // 就广播 720p、新会话直用软解，不必每个进程都先楔一次硬解 + 跑一段 1080p 软解。
        if (context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .getBoolean(PREF_SOFTWARE_DECODE, false)) {
            MirrorStreamServer.setHardwareKnownBad(true)
            Logger.i("AirPlay: persisted software-decode preference loaded (hardware decode known bad on this device)")
        }
        scope.launch {
            try {
                startTimingHandler()
                startMdnsService()
                startRtspHandler()
            } catch (e: Exception) {
                Logger.e("Failed to start AirPlayReceiver", e)
                emitState(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Stops the AirPlay receiver and releases all resources.
     *
     * Stops RTSP handler, mDNS advertising, video decoder, and audio player.
     * Cancels all background coroutines.
     *
     * MUST be called when [PhairPlayService] stops or is destroyed.
     */
    fun stop() {
        Logger.i("AirPlayReceiver stopping")
        try {
            rtspHandler?.stop()
            timingHandler?.stop()
            mdnsService?.stop()
            dacpClient.stop()
            releaseMediaComponents()
        } catch (e: Exception) {
            Logger.e("Error during AirPlayReceiver stop", e)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Sends a DACP transport command (see [DacpClient] constants) from the TV remote back to the
     * AirPlay sender — e.g. play/pause or skip what the Mac/iPhone is streaming. No-op if no sender
     * has advertised a DACP identity yet.
     */
    fun sendRemoteCommand(command: String) = dacpClient.sendCommand(command)

    /** True once a sender has advertised DACP reverse-control (so the TV remote can drive playback). */
    fun isRemoteControlAvailable(): Boolean = dacpClient.isAvailable

    /**
     * Re-registers the mDNS advertisement without touching the RTSP/timing servers.
     *
     * For when the network came back under us — the TV waking from standby being the case that
     * prompted this. An NsdManager registration does not survive its interface going down, and
     * nothing brings it back on its own, so the receiver keeps listening on port 7000 while
     * being invisible in every AirPlay picker. Restarting the whole receiver would do it too,
     * but that needlessly drops the sockets; only the advertisement is stale.
     *
     * No-op when the receiver was never started.
     */
    fun readvertise() {
        val mdns = mdnsService ?: return
        Logger.i("Re-advertising AirPlay over mDNS (network returned)")
        scope.launch {
            try {
                mdns.restart(displayName.ifBlank { null })
            } catch (e: Exception) {
                Logger.e("Failed to re-advertise mDNS", e)
            }
        }
    }

    // ─── Private: startup ────────────────────────────────────────────────────

    private fun startTimingHandler() {
        timingHandler = TimingHandler().also { it.start(scope) }
        Logger.d("Timing handler started on UDP port ${TimingHandler.TIMING_PORT}")
    }

    private fun startMdnsService() {
        mdnsService = MdnsService(
            context = context,
            onStateChange = { state -> emitState(state) },
            onActualNameRegistered = { actualName -> onActualNameRegistered(actualName) }
        ).also { it.start(displayName.ifBlank { null }) }
        Logger.d("mDNS service started")
    }

    private fun startRtspHandler() {
        // v0.1.1 (issue #1)：读取可信局域网跳过配对校验开关
        val skipPairVerify = AirPlayPrefs(context).skipPairVerify
        rtspHandler = RtspHandler(
            context = context,
            displayWidth = mirrorWidth,
            displayHeight = mirrorHeight,
            audioEnabled = audioEnabled,
            videoSurfaceProvider = videoSurfaceProvider,
            onStreamingStarted = { session -> onStreamingStarted(session) },
            onStreamingStopped = { onStreamingStopped() },
            onPhotoReceived = { bytes, imageType -> onPhotoReceived(bytes, imageType) },
            onPhotoCleared = { onPhotoCleared() },
            onMirrorSetupKeys = { aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort ->
                startMirrorKeys(aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort)
            },
            onMirrorStreamStart = { streamConnectionId -> startMirrorStream(streamConnectionId) },
            onMirrorAudioStart = { sampleRate, channels, ct, spf -> startMirrorAudio(sampleRate, channels, ct, spf) },
            onMirrorAudioStop = { stopMirrorAudio() },
            onMirrorVideoStop = { stopMirrorVideo() },
            onBufferedAudioStart = { startBufferedAudio() },
            onBufferedAudioStop = { stopBufferedAudio() },
            onVolume = { v -> audioServer?.setVolume(v) },
            onNowPlayingMetadata = { title, artist, album ->
                npTitle = title; npArtist = artist; npAlbum = album
                emitNowPlaying()
            },
            onArtwork = { bytes ->
                npArtwork = bytes.takeIf { it.isNotEmpty() }
                emitNowPlaying()
            },
            onVideoPlay = { url, start -> startUrlVideo(url, start) },
            onVideoRate = { rate -> urlVideoPlayer?.setRate(rate) },
            onVideoScrub = { pos -> urlVideoPlayer?.scrub(pos) },
            onVideoStop = { stopUrlVideo() },
            onPlaybackInfo = { urlVideoPlayer?.info() },
            onRemoteControlInfo = { dacpId, activeRemote -> dacpClient.configure(dacpId, activeRemote) },
            pinAuthEnabled = pinAuthEnabled,
            skipPairVerify = skipPairVerify,
            pairingStore = pairingStore,
            onShowPin = { pin -> onPinChanged(pin) }
        ).also { it.start(scope) }
        Logger.i("RTSP handler started on port 7000 (audioEnabled=$audioEnabled pinAuth=$pinAuthEnabled)")
    }

    // ─── Private: streaming lifecycle ────────────────────────────────────────

    /**
     * Called by [RtspHandler] when RECORD is received and [SessionDescription] is ready.
     *
     * Wires the media pipeline:
     * - video stream: creates [VideoDecoder] + wires [RtspHandler.onVideoNalUnit]
     * - audio stream: creates [AudioPlayer]
     * - audio-only:   only [AudioPlayer], app stays on HomeScreen
     */
    private fun onStreamingStarted(session: SessionDescription) {
        Logger.i("[AirPlay 7/9] Streaming started — video=${session.hasVideo} audio=${session.hasAudio} codec=${session.videoCodec} " +
                 "audioOnly=${session.isAudioOnly}")

        scope.launch {
            try {
                if (session.hasVideo) startVideoDecoder(session)
                if (session.hasAudio) startAudioPlayer(session)
                // Legacy (SDP) session: reflect its stream kinds into now-playing state so an
                // audio-only RAOP session shows the now-playing card.
                npSenderName = session.senderName.ifBlank { npSenderName }
                videoPlaying = session.hasVideo
                audioPlaying = session.hasAudio
                emitNowPlaying()
                // Notify PhairPlayService of the sender name BEFORE emitting CONNECTED,
                // so the name is ready when the ActiveConnection is created.
                onSenderNameChanged(session.senderName)
                emitState(ProtocolState.CONNECTED)
            } catch (e: Exception) {
                Logger.e("Failed to start media pipeline", e)
                emitState(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Called when streaming ends (TEARDOWN received or socket closed).
     *
     * Releases media components and re-advertises so the device reappears
     * in sender pickers immediately.
     */
    private fun onStreamingStopped() {
        Logger.i("Streaming stopped — releasing media components")
        releaseMediaComponents()
        emitState(ProtocolState.ADVERTISING)

        scope.launch {
            try {
                mdnsService?.restart(displayName.ifBlank { null })
            } catch (e: Exception) {
                Logger.e("Failed to restart mDNS after streaming", e)
            }
        }
    }

    // ─── Private: media pipeline ──────────────────────────────────────────────

    /**
     * Initializes [VideoDecoder] with SPS/PPS from the [SessionDescription].
     *
     * Resolution hint: AirPlay SDP does not include width/height — the actual
     * resolution is embedded in the SPS NAL unit. We pass [DEFAULT_VIDEO_WIDTH] ×
     * [DEFAULT_VIDEO_HEIGHT] as a hint; MediaCodec reads the real size from SPS.
     *
     * [RtspHandler.onVideoNalUnit] is wired here so RTP interleaved NAL units
     * flow directly into [VideoDecoder.decodeNalUnit].
     */
    private fun startVideoDecoder(session: SessionDescription) {
        // v0.3.10：等 surface 而不是直接放弃——待机页收到 CONNECTED 后会拉起
        // StreamPlayerActivity 并提供 surface，最多等 10 秒。
        var surface = videoSurfaceProvider()
        if (surface == null) {
            Logger.i("VideoDecoder: waiting for surface...")
            var waited = 0
            while (surface == null && waited < 10_000) {
                Thread.sleep(200)
                waited += 200
                surface = videoSurfaceProvider()
            }
        }
        val s = surface ?: run {
            Logger.w("VideoDecoder: no surface available — skipping video pipeline")
            return
        }
        val spsFromSdp = session.spsBytes
        val ppsFromSdp = session.ppsBytes

        videoDecoder = VideoDecoder(s, session.videoCodec).also { decoder ->
            // v0.1.12: SDP 可能不带 SPS/PPS（如 iPhone），此时等带内参数集。
            // 先设置 NAL 监听，收到 SPS/PPS 后再初始化解码器。
            var pendingSps = spsFromSdp
            var pendingPps = ppsFromSdp
            fun tryInit() {
                val sps = pendingSps
                val pps = pendingPps
                if (sps != null && pps != null && !decoder.isInitialized) {
                    try {
                        decoder.initialize(sps, pps, DEFAULT_VIDEO_WIDTH, DEFAULT_VIDEO_HEIGHT, session.vpsBytes)
                        Logger.i("[AirPlay 8/9] VideoDecoder initialized (${session.videoCodec}) from " + if (spsFromSdp != null) "SDP" else "in-band")
                    } catch (e: Exception) {
                        Logger.e("VideoDecoder init failed", e)
                    }
                }
            }
            // SDP 自带则直接初始化
            tryInit()
            rtspHandler?.onVideoNalUnit = { nalUnit, ptsUs ->
                // 从带内 NAL 提取 SPS/PPS（H.264 NAL type 7=SPS, 8=PPS）
                if (nalUnit.isNotEmpty()) {
                    if (session.videoCodec == VideoCodec.HEVC) {
                        // HEVC NAL type 在 byte0 的 bit1-6
                        val nalType = (nalUnit[0].toInt() shr 1) and 0x3F
                        if (nalType == 33 && pendingSps == null) {
                            pendingSps = nalUnit.copyOf()
                            Logger.i("VideoDecoder: got in-band HEVC SPS")
                        } else if (nalType == 34 && pendingPps == null) {
                            pendingPps = nalUnit.copyOf()
                            Logger.i("VideoDecoder: got in-band HEVC PPS")
                        }
                    } else {
                        val nalType = nalUnit[0].toInt() and 0x1F
                        if (nalType == 7 && pendingSps == null) {
                            pendingSps = nalUnit.copyOf()
                            Logger.i("VideoDecoder: got in-band SPS")
                        } else if (nalType == 8 && pendingPps == null) {
                            pendingPps = nalUnit.copyOf()
                            Logger.i("VideoDecoder: got in-band PPS")
                        }
                    }
                    tryInit()
                }
                if (decoder.isInitialized) {
                    decoder.decodeNalUnit(nalUnit, ptsUs)
                }
                // 未初始化时丢弃视频帧（等 SPS/PPS），避免解码器报错
            }
        }
        Logger.i("VideoDecoder pipeline set up (${DEFAULT_VIDEO_WIDTH}x${DEFAULT_VIDEO_HEIGHT} hint)")
    }

    /**
     * Initializes [AudioPlayer] with codec and encryption params from [SessionDescription].
     *
     * When the SDP contains no AES key/IV (unencrypted or missing keys), null is passed —
     * [AudioPlayer.initialize] skips cipher setup entirely and writes audio payload directly.
     * This prevents a zero-key cipher from producing garbage audio (S6-4 fix).
     */
    private fun startAudioPlayer(session: SessionDescription) {
        audioPlayer = AudioPlayer().also { player ->
            player.initialize(
                aesKey     = session.aesKey.takeIf { session.isAudioEncrypted },
                aesIv      = session.aesIv.takeIf  { session.isAudioEncrypted },
                sampleRate = session.sampleRate,
                channels   = session.channels,
                codec      = session.audioCodec,
                alacFramesPerPacket = session.alacFramesPerPacket
            )
        }
        Logger.i("AudioPlayer started (${session.sampleRate}Hz × ${session.channels}ch, " +
                 "codec=${session.audioCodec}, encrypted=${session.isAudioEncrypted})")

        startAudioUdpReceiver()
    }

    /**
     * Opens a UDP socket on [AUDIO_RTP_PORT] and feeds every received packet to
     * [AudioPlayer.playAudioPacket].
     *
     * WHY UDP: AirPlay audio is sent as RTP over UDP — low latency is more important
     * than guaranteed delivery. A missing packet produces a brief audio glitch,
     * which is far less disruptive than the buffering delays that TCP would introduce.
     *
     * The socket is closed in [releaseMediaComponents] when streaming ends.
     */
    private fun startAudioUdpReceiver() {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(AUDIO_RTP_PORT)
                audioSocket = socket
                Logger.i("Audio UDP receiver listening on port $AUDIO_RTP_PORT")

                val buf    = ByteArray(MAX_AUDIO_PACKET_BYTES)
                val packet = DatagramPacket(buf, buf.size)

                while (isActive) {
                    socket.receive(packet)
                    // copyOf trims to actual packet length before passing to the player
                    audioPlayer?.playAudioPacket(packet.data.copyOf(packet.length))
                }
            } catch (e: Exception) {
                // SocketException thrown when audioSocket.close() is called — expected
                if (audioSocket != null) {
                    Logger.e("Audio UDP receiver error (unexpected)", e)
                } else {
                    Logger.d("Audio socket closed (expected during shutdown)")
                }
            }
        }
    }

    // ─── Private: AirPlay 2 mirroring ─────────────────────────────────────────

    /**
     * Mirror SETUP msg 1: stash the decrypted AES key + pairing secret, open the event
     * channel (macOS connects to it), and switch the UI to the streaming surface.
     * @return the event channel's TCP port.
     */
    private fun startMirrorKeys(
        aesKey: ByteArray,
        ecdhSecret: ByteArray,
        aesIv: ByteArray,
        remoteAddress: java.net.InetAddress,
        senderTimingPort: Int,
    ): Pair<Int, Int> {
        mirrorAesKey = aesKey
        mirrorEcdhSecret = ecdhSecret
        mirrorAesIv = aesIv
        // Explicit IPv4 wildcard — see RtspHandler.bindRtspSocket() for why.
        val event = ServerSocket(0, 50, java.net.InetAddress.getByName("0.0.0.0"))
        eventSocket = event
        // Accept + drain the event connection. We don't act on events yet, but macOS expects
        // the advertised event port to be connectable, so keep it open and readable.
        scope.launch(Dispatchers.IO) {
            try {
                event.accept().use { s ->
                    eventClientSocket = s
                    Logger.i("Event channel: macOS connected from ${s.inetAddress.hostAddress}")
                    val buf = ByteArray(4096)
                    val input = s.getInputStream()
                    while (isActive && input.read(buf) != -1) { /* drain */ }
                }
            } catch (e: Exception) {
                if (eventSocket != null) Logger.d("Event channel closed")
            } finally {
                eventClientSocket = null
            }
        }
        // AirPlay 2 NTP is receiver-initiated: poll the sender's timing port so macOS proceeds.
        val ntp = AirPlayNtpClient(remoteAddress, senderTimingPort).also { ntpClient = it; it.start(scope) }
        onSenderNameChanged("AirPlay")
        emitState(ProtocolState.CONNECTED)
        Logger.i("Mirror keys set; eventPort=${event.localPort} timingPort=${ntp.localPort}")
        return event.localPort to ntp.localPort
    }

    /**
     * Mirror SETUP msg 2: start the data-stream server for the requested stream.
     * @return the data server's TCP port (macOS connects here to send H.264).
     */
    private fun startMirrorStream(streamConnectionId: Long): Int {
        val aesKey = mirrorAesKey ?: run { Logger.e("mirror stream start before keys set"); return 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0
        return MirrorStreamServer(aesKey, ecdhSecret, streamConnectionId, videoSurfaceProvider, mirrorWidth, mirrorHeight,
            onSoftwareConfirmed = {
                // 软解确认可用 → 持久化“本机硬解不行”，重启后 start() 回填。
                context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit()
                    .putBoolean(PREF_SOFTWARE_DECODE, true).apply()
                Logger.i("AirPlay: software-decode preference persisted")
            })
            .also { mirrorServer = it; it.start(scope); videoPlaying = true; emitNowPlaying() }
            .dataPort
            .also { Logger.i("Mirror data server started on port $it") }
    }

    /** Mirror SETUP audio stream (type 96): start the AAC-ELD / AAC-LC / ALAC audio server. @return (dataPort, controlPort). */
    private fun startMirrorAudio(sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int): Pair<Int, Int> {
        val aesKey = mirrorAesKey ?: run { Logger.e("audio start before keys set"); return 0 to 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0 to 0
        val aesIv = mirrorAesIv ?: return 0 to 0
        val server = AudioStreamServer(aesKey, ecdhSecret, aesIv, sampleRate, channels, codecType, framesPerPacket)
            .also { audioServer = it; it.start(scope) }
        audioPlaying = true
        emitNowPlaying()
        Logger.i("Mirror audio server started: dataPort=${server.dataPort} controlPort=${server.controlPort}")
        return server.dataPort to server.controlPort
    }

    /** Stops ONLY the mirror audio stream (macOS dynamic-stream TEARDOWN) — video keeps running. */
    private fun stopMirrorAudio() {
        audioServer?.stop()
        audioServer = null
        audioPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
        Logger.i("Mirror audio stream stopped (video mirroring continues)")
    }

    /** Stops ONLY the mirror video stream (macOS dynamic-stream TEARDOWN) — audio keeps playing. */
    private fun stopMirrorVideo() {
        mirrorServer?.stop()
        mirrorServer = null
        videoPlaying = false
        emitNowPlaying()   // audio may still be playing → now-playing card can take over
        Logger.i("Mirror video stream stopped (audio playback continues)")
    }

    /**
     * AirPlay video URL mode (non-mirroring): show the streaming surface and hand the URL to
     * [AirPlayVideoPlayer], which fetches + plays it via MediaPlayer onto the same Surface.
     */
    private fun startUrlVideo(url: String, startFraction: Double) {
        onSenderNameChanged("AirPlay")
        emitState(ProtocolState.CONNECTED)   // shows StreamingScreen → Surface becomes available
        val player = urlVideoPlayer ?: AirPlayVideoPlayer(
            surfaceProvider = videoSurfaceProvider,
            onEnded = { stopUrlVideo() }
        ).also { urlVideoPlayer = it }
        player.play(url, startFraction)
        Logger.i("AirPlay URL video started: $url (start=$startFraction)")
    }

    /** Stops AirPlay video URL playback (POST /stop or end-of-media) and ends the session. */
    private fun stopUrlVideo() {
        urlVideoPlayer?.release()
        urlVideoPlayer = null
        onStreamingStopped()
        Logger.i("AirPlay URL video stopped")
    }

    /** Starts the AirPlay 2 buffered audio-only stream (type 103, Apple Music → TV); returns its TCP port. */
    private fun startBufferedAudio(): Int {
        bufferedAudioServer?.stop()
        val server = BufferedAudioServer().also { bufferedAudioServer = it; it.start(scope) }
        audioPlaying = true   // buffered audio (type 103) is always audio-only
        emitNowPlaying()
        Logger.i("Buffered audio server started: dataPort=${server.dataPort}")
        return server.dataPort
    }

    /** Stops the buffered audio-only stream (type 103 TEARDOWN). */
    private fun stopBufferedAudio() {
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
        audioPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
        Logger.i("Buffered audio stream stopped")
    }

    /** Clears the video NAL callback, closes the audio socket, and releases media components. */
    private fun releaseMediaComponents() {
        rtspHandler?.onVideoNalUnit = null
        try { audioSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        audioSocket = null
        mirrorServer?.stop()
        mirrorServer = null
        audioServer?.stop()
        audioServer = null
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
        urlVideoPlayer?.release()
        urlVideoPlayer = null
        ntpClient?.stop()
        ntpClient = null
        try { eventClientSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        eventClientSocket = null
        try { eventSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        eventSocket = null
        // Clear the FairPlay/ECDH keys on FULL teardown only. This method runs on a genuine session
        // end (last-stream / session TEARDOWN, or control-connection close) — NOT on a per-stream
        // teardown, which goes through stopMirrorAudio/stopMirrorVideo and leaves the keys intact so
        // macOS can re-add a dynamic stream on the same live session without re-sending keys (that
        // dynamic-readd path is why the keys must survive a stream stop). Clearing here prevents a
        // brand-new control connection from reusing a previous session's stale keys.
        mirrorAesKey = null
        mirrorEcdhSecret = null
        mirrorAesIv = null
        videoDecoder?.release()
        videoDecoder = null
        audioPlayer?.release()
        audioPlayer = null
        // Session fully torn down — clear now-playing so the UI leaves the audio card.
        audioPlaying = false
        videoPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
    }

    /** Pushes the current now-playing state out: a [NowPlayingInfo] when audio plays without video, else null. */
    private fun emitNowPlaying() {
        val show = audioPlaying && !videoPlaying
        onNowPlayingChanged(
            if (show) NowPlayingInfo(npSenderName, npTitle, npArtist, npAlbum, npArtwork) else null
        )
    }

    /** Drops stale track metadata/artwork when an audio stream ends (so it can't bleed into the next). */
    private fun clearNowPlayingMetadata() {
        npTitle = null; npArtist = null; npAlbum = null; npArtwork = null
    }

    // ─── Private: state emission ─────────────────────────────────────────────

    /** Dispatches [state] on the Main thread (Android UI rule). */
    private fun emitState(state: ProtocolState) {
        scope.launch {
            withContext(Dispatchers.Main) {
                onStateChanged(state)
            }
        }
    }

    companion object {
        // Hint dimensions for MediaCodec configuration.
        // Real resolution is encoded in the H.264 SPS NAL unit.
        private const val DEFAULT_VIDEO_WIDTH  = 1920
        private const val DEFAULT_VIDEO_HEIGHT = 1080

        // “本机硬解不行 → 用软解 + 广播 720p”的持久化（与 App 其他设置同文件）。
        private const val PREFS_FILE = "castlink_prefs"
        private const val PREF_SOFTWARE_DECODE = "airplay_software_decode_v2"  // v2: 0.1.63 起 SPS 已补低延迟声明，重新探测一次硬解

        /**
         * UDP port for receiving audio RTP packets.
         * Advertised in the RTSP SETUP response so the sender knows where to send audio.
         * Must not conflict with the RTSP port (7000) or timing port ([TimingHandler.TIMING_PORT]).
         */
        internal const val AUDIO_RTP_PORT = 6001

        /**
         * Maximum UDP audio packet size in bytes.
         * ALAC frames are typically ≤ 8 KB. 16 KB is a safe upper bound.
         */
        private const val MAX_AUDIO_PACKET_BYTES = 16 * 1024
    }
}
