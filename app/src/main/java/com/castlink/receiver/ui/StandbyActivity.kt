package com.castlink.receiver.ui

import android.content.Intent
import android.os.SystemClock
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.castlink.receiver.R
import com.castlink.receiver.airplay.AirPlayUiBridge
import com.castlink.receiver.airplay.ProtocolState
import com.castlink.receiver.discovery.NsdHelper
import com.castlink.receiver.protocol.FilePushServer
import com.castlink.receiver.protocol.NativeStreamBridge
import com.castlink.receiver.protocol.PinVerifier
import com.castlink.receiver.protocol.RtpReceiver
import com.castlink.receiver.protocol.SignalingServer
import com.castlink.receiver.util.FileAction
import com.castlink.receiver.util.NetUtil
import com.castlink.receiver.util.PinLockout
import com.castlink.receiver.util.QrUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TV 待机页（UI-03）：横屏深色全屏，展示二维码 + 4 位 PIN，
 * 本机进入待机广播模式（mDNS 注册 + 信令服务 + RTP 接收骨架）。
 *
 * v0.2.0：四格 PIN 在 AirPlay 配对时显示 iPhone 侧 PIN（移植的 RtspHandler 经
 * AirPlayService → AirPlayUiBridge 回调），PIN 输错锁定走 PairingStore
 *（MAX_PAIR_ATTEMPTS）真实逻辑；状态栏映射 ProtocolState。
 *
 * v0.3.0：
 * - CastLink 自有协议 PIN 输错 3 次锁定 60 秒（PinLockout，SharedPreferences 持久化，
 *   锁定期回 PIN_LOCKED 并在 TV 端提示"已锁定，请 60 秒后重试或扫码"）；
 * - 30 秒无操作自动降亮至 0.05 防烧屏（触摸恢复；任一投屏链路激活时恢复常亮）；
 * - 文件投放：FilePushServer（:8756，PUT /push/<文件名>）+ PlayerActivity 播放。
 */
class StandbyActivity : BaseActivity() {

    private lateinit var tvWifiName: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvDeviceName: TextView
    private lateinit var ivQr: ImageView
    private lateinit var tvQrFallback: TextView
    private lateinit var tvCapability: TextView
    private lateinit var tvLocalIp: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvPinHint: TextView
    private lateinit var tvFilePush: TextView
    private lateinit var pinViews: List<TextView>

    /** AirPlay 配对 PIN（非空时四格显示它，并暂停自有 PIN 轮换）。 */
    private var airPlayPin: String? = null
    private var airPlaySenderName: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val nsdHelper = NsdHelper()
    private var signalingServer: SignalingServer? = null
    private var rtpReceiver: RtpReceiver? = null
    private var filePushServer: FilePushServer? = null

    private var pin: String = "0000"
    private var deviceName: String = ""
    private var localIp: String = ""

    // ─── 自有协议 PIN 输错锁定（v0.3.0） ──────────────────────────────────────
    private val pinLockout: PinLockout by lazy {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        PinLockout(object : PinLockout.Store {
            override fun getInt(key: String, def: Int): Int = prefs.getInt(key, def)
            override fun putInt(key: String, value: Int) {
                prefs.edit().putInt(key, value).apply()
            }
            override fun getLong(key: String, def: Long): Long = prefs.getLong(key, def)
            override fun putLong(key: String, value: Long) {
                prefs.edit().putLong(key, value).apply()
            }
            override fun remove(key: String) {
                prefs.edit().remove(key).apply()
            }
        })
    }

    /**
     * 自有协议 PIN 校验器：锁定期内直接拒绝；3 次输错后锁定 60 秒。
     * 运行在信令 I/O 线程，TV 端提示经主线程刷新。
     */
    private val pinVerifier = object : PinVerifier {
        override fun verify(inputPin: String): PinVerifier.Result {
            if (pinLockout.isLocked()) {
                val sec = pinLockout.remainingLockSeconds()
                postPinHint(getString(R.string.pin_locked_hint, sec))
                scheduleLockHintRefresh(sec)
                return PinVerifier.Result.Locked(sec)
            }
            return if (inputPin == pin) {
                pinLockout.verifyOk()
                runOnUiThread { refreshPinHint() }
                PinVerifier.Result.Ok
            } else {
                if (pinLockout.recordFailure()) {
                    Log.w(TAG, "native PIN locked for ${PinLockout.LOCK_SECONDS}s after 3 failures")
                    postPinHint(getString(R.string.pin_locked_hint, PinLockout.LOCK_SECONDS))
                    scheduleLockHintRefresh(PinLockout.LOCK_SECONDS)
                    PinVerifier.Result.Locked(PinLockout.LOCK_SECONDS)
                } else {
                    val left = PinLockout.MAX_ATTEMPTS - pinLockout.failedAttempts()
                    Log.w(TAG, "native PIN wrong ($left attempts left)")
                    PinVerifier.Result.Wrong(left)
                }
            }
        }
    }

    private fun postPinHint(text: String) {
        runOnUiThread { tvPinHint.text = text }
    }

    /** 锁定提示在到期后自动恢复为常规提示（若仍锁定则保持）。 */
    private val lockHintRefresh = Runnable { runOnUiThread { refreshPinHint() } }

    private fun scheduleLockHintRefresh(sec: Long) {
        handler.removeCallbacks(lockHintRefresh)
        handler.postDelayed(lockHintRefresh, sec * 1000L + 500L)
    }

    /** 按当前状态（AirPlay PIN 显示中 / 自有锁定中 / 常规）刷新 PIN 提示行。 */
    private fun refreshPinHint() {
        tvPinHint.text = when {
            airPlayPin != null -> getString(R.string.airplay_pin_hint)
            pinLockout.isLocked() ->
                getString(R.string.pin_locked_hint, pinLockout.remainingLockSeconds())
            else -> getString(R.string.standby_pin_hint)
        }
    }

    // ─── 30 秒无操作降亮防烧屏（v0.3.0） ─────────────────────────────────────
    /** 任一投屏链路激活后保持常亮，不再降亮。 */
    private var castActive = false

    private val dimRunnable = Runnable { dimScreen() }

    private fun dimScreen() {
        if (castActive) return
        val lp = window.attributes
        lp.screenBrightness = DIM_BRIGHTNESS
        window.attributes = lp
        Log.i(TAG, "standby dimmed to $DIM_BRIGHTNESS after ${DIM_DELAY_MS}ms idle")
    }

    private fun restoreBrightness() {
        val lp = window.attributes
        if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        }
    }

    private fun scheduleDim() {
        handler.removeCallbacks(dimRunnable)
        if (!castActive) handler.postDelayed(dimRunnable, DIM_DELAY_MS)
    }

    /** 任一接收链路激活（RTP NAL 到达 / AirPlay CONNECTED）：恢复常亮并停止降亮计时。 */
    // v0.3.10：AirPlay 镜像视频层
    private lateinit var airplaySurfaceView: SurfaceView
    @Volatile private var airplaySurfaceReady = false

    // v0.3.10：原生协议投屏——首个 NAL 拉起全屏播放页，后续 NAL 经桥接转发解码。
    // 距上次 NAL 超过 10 秒视为新流，允许重新拉起。
    @Volatile private var nativePlayerLaunched = false
    @Volatile private var lastNativeNalTime = 0L

    private fun onNativeNal(nal: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNativeNalTime > NATIVE_STREAM_GAP_MS) {
            nativePlayerLaunched = false
        }
        lastNativeNalTime = now
        NativeStreamBridge.emitNal(nal)
        if (!nativePlayerLaunched) {
            nativePlayerLaunched = true
            Log.i(TAG, "native stream started, launching player")
            runOnUiThread {
                startActivity(Intent(this, StreamPlayerActivity::class.java))
            }
        }
    }

    private fun onCastActive() {
        runOnUiThread {
            if (!castActive) {
                castActive = true
                Log.i(TAG, "cast active — brightness restored, dimming disabled")
            }
            handler.removeCallbacks(dimRunnable)
            restoreBrightness()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 任意触摸：恢复亮度并重新计时
        restoreBrightness()
        scheduleDim()
        return super.dispatchTouchEvent(ev)
    }

    // ─── 文件投放（v0.3.0） ──────────────────────────────────────────────────
    private fun onFilePushed(file: File) {
        runOnUiThread {
            tvFilePush.text = getString(R.string.file_received_hint, file.name)
            tvFilePush.setOnClickListener {
                // v0.3.5：按类型打开——媒体播放、APK 安装确认、其他调系统查看器
                if (FileAction.isMedia(file)) {
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_PATH, file.absolutePath)
                    )
                } else {
                    FileAction.openExternal(this, file)
                }
            }
            Log.i(TAG, "file push received: ${file.name} (${file.length()}B)")
        }
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 60_000L)
        }
    }

    private val pinRunnable = object : Runnable {
        override fun run() {
            pin = generatePin()
            updatePinViews()
            updateQr()
            handler.postDelayed(this, PIN_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_standby)

        // 全屏 + 常亮（v0.3.0：30 秒无操作降亮至 0.05 防烧屏，而非常亮到底）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN

        tvWifiName = findViewById(R.id.tv_standby_wifi)
        tvClock = findViewById(R.id.tv_standby_clock)
        tvDeviceName = findViewById(R.id.tv_standby_device_name)
        ivQr = findViewById(R.id.iv_standby_qr)
        tvQrFallback = findViewById(R.id.tv_qr_fallback)
        tvCapability = findViewById(R.id.tv_standby_capability)
        tvLocalIp = findViewById(R.id.tv_standby_ip)
        tvFilePush = findViewById(R.id.tv_file_push)
        pinViews = listOf(
            findViewById(R.id.tv_pin_1),
            findViewById(R.id.tv_pin_2),
            findViewById(R.id.tv_pin_3),
            findViewById(R.id.tv_pin_4)
        )
        tvStatus = findViewById(R.id.tv_standby_status)
        tvPinHint = findViewById(R.id.tv_pin_hint)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        deviceName = prefs.getString(KEY_DEVICE_NAME, getString(R.string.default_device_name))
            ?: getString(R.string.default_device_name)
        localIp = NetUtil.getLocalIp()

        tvDeviceName.text = deviceName
        tvCapability.text = getString(R.string.standby_capability)
        tvLocalIp.text = if (localIp.isEmpty()) getString(R.string.unknown_value) else localIp
        val ssid = NetUtil.getWifiSsid(this)
        tvWifiName.text = if (ssid.isEmpty()) getString(R.string.unknown_value) else ssid

        pin = generatePin()
        updatePinViews()
        updateQr()

        updateClock()
        handler.postDelayed(clockRunnable, 60_000L)
        handler.postDelayed(pinRunnable, PIN_REFRESH_MS)

        // 本机作为接收端：mDNS 注册 + 信令服务（自有协议 PIN 校验走 pinVerifier，
        // 含 3 次错 → 锁定 60 秒）+ RTP 接收 + 文件投放服务
        nsdHelper.registerAll(this, deviceName, pin)
        signalingServer = SignalingServer(SIGNALING_PORT, { pin }, pinVerifier).also { it.start() }
        rtpReceiver = RtpReceiver(RTP_PORT) { nal ->
            onCastActive()   // 任一接收链路激活 → 恢复常亮
            onNativeNal(nal)
        }.also { it.start() }
        filePushServer = FilePushServer(
            FilePushServer.DEFAULT_PORT,
            FilePushServer.dirFor(cacheDir),
            onFileReceived = { onFilePushed(it) },
            log = { Log.i(TAG, it) },
            // 落地页显示当前四格 PIN（AirPlay 配对时显示 iPhone 侧 PIN）
            pinProvider = { airPlayPin ?: pin },
        ).also { it.start() }

        // 文件投放提示：http://<ip>:8756（点击打开播放器列表）
        tvFilePush.text = if (localIp.isEmpty()) "" else getString(R.string.file_push_hint, localIp)
        tvFilePush.setOnClickListener { startActivity(Intent(this, PlayerActivity::class.java)) }

        // 降亮计时启动
        scheduleDim()

        // AirPlay 配对 PIN / 状态 / 发送端名（AirPlayService → AirPlayUiBridge，主线程回调）
        AirPlayUiBridge.pinListener = { p -> onAirPlayPin(p) }
        AirPlayUiBridge.stateListener = { state -> onAirPlayState(state) }
        AirPlayUiBridge.senderNameListener = { name -> airPlaySenderName = name }

        // v0.3.10：待机页直接提供 AirPlay 镜像 surface——sv_airplay_video 平时
        // invisible（surface 保留），有视频流时切 visible 全屏显示。
        airplaySurfaceView = findViewById(R.id.sv_airplay_video)
        airplaySurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { airplaySurfaceReady = true }
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { airplaySurfaceReady = false }
        })
        AirPlayUiBridge.surfaceProvider = {
            if (airplaySurfaceReady) airplaySurfaceView.holder.surface else null
        }
    }

    /** AirPlay 配对 PIN 显示/隐藏：显示时暂停自有 PIN 轮换，隐藏后恢复。 */
    private fun onAirPlayPin(pin: String?) {
        runOnUiThread {
            airPlayPin = pin
            if (pin != null) {
                handler.removeCallbacks(pinRunnable)
                updatePinViews(pin)
            } else {
                this.pin = generatePin()
                updatePinViews()
                updateQr()
                handler.removeCallbacks(pinRunnable)
                handler.postDelayed(pinRunnable, PIN_REFRESH_MS)
            }
            refreshPinHint()   // AirPlay PIN 显隐切换后按当前状态（可能含自有协议锁定）刷新提示
        }
    }

    /** ProtocolState → 待机页状态栏。发送端名在 CONNECTED 前由 senderNameListener 先送达。 */
    private fun onAirPlayState(state: ProtocolState) {
        if (state == ProtocolState.CONNECTED) onCastActive()   // AirPlay 链路激活 → 恢复常亮
        runOnUiThread {
            // v0.3.10：AirPlay 镜像连上 → 视频层全屏；断开 → 藏回待机页
            airplaySurfaceView.visibility =
                if (state == ProtocolState.CONNECTED) View.VISIBLE else View.INVISIBLE
            tvStatus.text = when (state) {
                ProtocolState.CONNECTED -> getString(
                    R.string.airplay_connected,
                    airPlaySenderName.ifEmpty { getString(R.string.unknown_value) }
                )
                ProtocolState.ERROR -> getString(R.string.airplay_error)
                else -> getString(R.string.standby_waiting)
            }
        }
    }

    override fun onDestroy() {
        AirPlayUiBridge.pinListener = null
        AirPlayUiBridge.stateListener = null
        AirPlayUiBridge.senderNameListener = null
        AirPlayUiBridge.surfaceProvider = null
        nativePlayerLaunched = false
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(pinRunnable)
        handler.removeCallbacks(dimRunnable)
        handler.removeCallbacks(lockHintRefresh)
        rtpReceiver?.stop()
        rtpReceiver = null
        signalingServer?.stop()
        signalingServer = null
        filePushServer?.stop()
        filePushServer = null
        nsdHelper.unregisterAll()
        super.onDestroy()
    }

    private fun generatePin(): String = (1000..9999).random().toString()

    private fun updatePinViews() {
        updatePinViews(pin)
    }

    private fun updatePinViews(code: String) {
        pinViews.forEachIndexed { index, tv ->
            tv.text = code.getOrNull(index)?.toString() ?: getString(R.string.unknown_value)
        }
    }

    private fun updateQr() {
        // v0.3.3：二维码改为文件投放落地页 http URL——iOS 相机可识别并用 Safari 打开；
        // 之前 castlink:// 自定义 scheme 在 iPhone 上报"未找到可用数据"。
        val content = if (localIp.isNotEmpty()) "http://$localIp:${FilePushServer.DEFAULT_PORT}/" else null
        val bitmap = content?.let { QrUtil.encode(it, 512) }
        if (bitmap != null) {
            ivQr.setImageBitmap(bitmap)
            ivQr.visibility = View.VISIBLE
            tvQrFallback.visibility = View.GONE
        } else {
            ivQr.visibility = View.GONE
            tvQrFallback.visibility = View.VISIBLE
        }
    }

    private fun updateClock() {
        tvClock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    companion object {
        private const val TAG = "StandbyActivity"
        private const val PREFS = "castlink_prefs"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val SIGNALING_PORT = 8755
        private const val RTP_PORT = 8760
        private const val PIN_REFRESH_MS = 5 * 60_000L
        // 待机防烧屏：无操作降亮延迟 + 降亮目标（0.05，不用 0 以免用户误以为关机）
        private const val DIM_DELAY_MS = 30_000L
        private const val DIM_BRIGHTNESS = 0.05f
        // v0.3.10：NAL 间隔超过此值视为新流
        private const val NATIVE_STREAM_GAP_MS = 10_000L
    }
}
