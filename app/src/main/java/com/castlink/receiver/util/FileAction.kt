package com.castlink.receiver.util

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.castlink.receiver.R
import java.io.File

/**
 * v0.3.5：投放文件的按类型打开路由。
 *
 * - 音视频 → 由调用方用 PlayerActivity 播放（[isMedia] 供调用方判断）；
 * - APK → 弹确认框后调系统安装器（需 REQUEST_INSTALL_PACKAGES，首次会引导用户允许"未知应用安装"）；
 * - 其他 → ACTION_VIEW 丢给系统（文档/图片/压缩包等），无应用可处理时 Toast 提示。
 */
object FileAction {

    private val MEDIA_EXTS = setOf(
        "mp4", "m4v", "3gp", "3g2", "webm", "mkv", "mov", "ts", "avi", "flv",
        "mp3", "aac", "flac", "wav", "ogg", "oga", "m4a", "opus", "mid", "midi",
    )

    fun isMedia(file: File): Boolean = MEDIA_EXTS.contains(file.extension.lowercase())

    fun isApk(file: File): Boolean = file.extension.lowercase() == "apk"

    /**
     * 打开非媒体文件：APK 走安装确认，其他走系统查看器。
     * 媒体文件请调用方自行用 PlayerActivity 处理。
     */
    fun openExternal(context: Context, file: File) {
        if (isApk(file)) {
            AlertDialog.Builder(context)
                .setTitle(R.string.install_apk_title)
                .setMessage(context.getString(R.string.install_apk_msg, file.name))
                .setPositiveButton(android.R.string.ok) { _, _ -> viewWith(context, file) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        viewWith(context, file)
    }

    private fun viewWith(context: Context, file: File) {
        val mime = if (isApk(file)) {
            "application/vnd.android.package-archive"
        } else {
            MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(FileShareProvider.uriFor(file), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, file.name))
        } catch (e: Exception) {
            Toast.makeText(context, R.string.file_no_viewer, Toast.LENGTH_SHORT).show()
        }
    }
}
