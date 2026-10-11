package com.castlink.receiver.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.castlink.receiver.util.FileShareProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * v0.3.12：在线更新。
 *
 * 流程：version.json（jsDelivr/ghproxy 查最新版）→ 对比 versionCode →
 * 经国内镜像（ghproxy.net 等）下载 APK → 调系统安装器。
 *
 * 仓库配置见 [UpdatePrefs]（设置页可改）。version.json 模板见仓库根 version.json。
 */
object UpdateManager {

    private const val TAG = "UpdateManager"

    /** 国内镜像源（按优先级，下载失败自动换下一个） */
    val MIRRORS = listOf(
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
        "https://mirror.ghproxy.com/"
    )

    /** v0.3.17：官方更新源内置，不再需要用户手工填写 */
    const val DEFAULT_OWNER = "programmerguohuajing"
    const val DEFAULT_REPO = "castlink"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,      // GitHub release 直链（未加镜像前缀）
        val changelog: String
    )

    interface Callback {
        fun onResult(info: UpdateInfo?)   // null = 已是最新或检查失败
        fun onError(msg: String)
    }

    interface DownloadCallback {
        fun onProgress(percent: Int)
        fun onDone(file: File)
        fun onError(msg: String)
    }

    /** 当前安装的 versionCode */
    fun currentVersionCode(context: Context): Int {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
        } catch (e: Exception) {
            0
        }
    }

    /** 异步检查更新。version.json 经 jsDelivr（国内快），失败回退 ghproxy+raw。 */
    fun check(context: Context, callback: Callback) {
        val prefs = UpdatePrefs(context)
        val owner = prefs.owner
        val repo = prefs.repo
        if (owner.isBlank() || repo.isBlank()) {
            callback.onError("not_configured")
            return
        }
        thread(isDaemon = true, name = "UpdateCheck") {
            try {
                val urls = listOf(
                    "https://cdn.jsdelivr.net/gh/$owner/$repo@main/version.json",
                    "https://ghproxy.net/https://raw.githubusercontent.com/$owner/$repo/main/version.json"
                )
                var json: String? = null
                var lastErr: Exception? = null
                for (u in urls) {
                    try {
                        json = httpGet(u, 8000)
                        break
                    } catch (e: Exception) {
                        lastErr = e
                        Log.w(TAG, "version.json fetch failed: $u", e)
                    }
                }
                val body = json ?: throw lastErr ?: Exception("fetch failed")
                val obj = JSONObject(body)
                val info = UpdateInfo(
                    versionCode = obj.getInt("versionCode"),
                    versionName = obj.optString("versionName", ""),
                    apkUrl = obj.getString("apkUrl"),
                    changelog = obj.optString("changelog", "")
                )
                if (info.versionCode > currentVersionCode(context)) {
                    callback.onResult(info)
                } else {
                    callback.onResult(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "check failed", e)
                callback.onError(e.message.orEmpty())
            }
        }
    }

    /** 经镜像下载 APK，多镜像源自动 failover。 */
    fun download(context: Context, info: UpdateInfo, callback: DownloadCallback) {
        val prefs = UpdatePrefs(context)
        val mirrorIdx = prefs.mirrorIndex.coerceIn(MIRRORS.indices)
        // 用户选定的镜像优先，其后按列表顺序
        val ordered = listOf(MIRRORS[mirrorIdx]) + MIRRORS.filterIndexed { i, _ -> i != mirrorIdx }
        thread(isDaemon = true, name = "UpdateDownload") {
            var lastErr: Exception? = null
            for (mirror in ordered) {
                val url = mirror + info.apkUrl
                try {
                    val file = downloadFile(context, url, callback)
                    callback.onDone(file)
                    return@thread
                } catch (e: Exception) {
                    lastErr = e
                    Log.w(TAG, "download failed via $mirror", e)
                }
            }
            callback.onError(lastErr?.message.orEmpty())
        }
    }

    private fun downloadFile(context: Context, url: String, callback: DownloadCallback): File {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CastLink-Updater")
        }
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw Exception("HTTP ${conn.responseCode}")
        }
        val total = conn.contentLengthLong
        val updateDir = File(context.externalCacheDir ?: context.cacheDir, "update").apply { mkdirs() }
        val outFile = File(updateDir, "castlink-update-${System.currentTimeMillis()}.apk")
        conn.inputStream.use { input ->
            FileOutputStream(outFile).use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastPercent = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt()
                        if (p != lastPercent) {
                            lastPercent = p
                            callback.onProgress(p)
                        }
                    }
                }
            }
        }
        if (outFile.length() == 0L) throw Exception("empty file")
        return outFile
    }

    /** 调起系统安装器（需 REQUEST_INSTALL_PACKAGES，已声明） */
    fun install(context: Context, apkFile: File) {
        val uri: Uri = FileShareProvider.uriForUpdate(apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun httpGet(url: String, timeoutMs: Int): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CastLink-Updater")
        }
        conn.connect()
        if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}
