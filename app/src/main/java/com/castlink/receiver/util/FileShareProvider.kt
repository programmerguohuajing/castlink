package com.castlink.receiver.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.castlink.receiver.protocol.FilePushServer
import java.io.File

/**
 * v0.3.5：极简文件共享 ContentProvider（不引入 AndroidX FileProvider）。
 *
 * - URI 形如 `content://<authority>/f/<文件名>`，映射到 [FilePushServer.dirFor](cacheDir)/filepush；
 * - 供"文件投放"后打开任意文件：APK 安装（application/vnd.android.package-archive）、
 *   系统查看器（ACTION_VIEW）。读权限由调用方 Intent 的 FLAG_GRANT_READ_URI_PERMISSION 授予。
 * - 文件名仅允许 filepush 目录下的直接子文件，拒绝 ".." 与路径分隔符。
 */
class FileShareProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File? {
        if (uri.pathSegments.size != 2) return null
        val name = uri.pathSegments[1]
        if (name.isBlank() || name.contains("..") || name.contains("/")) return null
        val dir = when (uri.pathSegments[0]) {
            "f" -> FilePushServer.dirFor(context!!.cacheDir)
            // v0.3.12：在线更新下载的 APK（externalCacheDir/update/）
            "u" -> File(context!!.externalCacheDir ?: context!!.cacheDir, "update").apply { mkdirs() }
            else -> return null
        }
        val f = File(dir, name)
        return if (f.isFile) f else null
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? {
        val f = fileFor(uri) ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), 1).apply {
            addRow(arrayOf(f.name, f.length()))
        }
    }

    override fun getType(uri: Uri): String? {
        val f = fileFor(uri) ?: return null
        val ext = f.extension.lowercase()
        if (ext == "apk") return "application/vnd.android.package-archive"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.castlink.receiver.fileprovider"

        fun uriFor(file: File): Uri =
            Uri.parse("content://$AUTHORITY/f/${Uri.encode(file.name)}")

        /** v0.3.12：在线更新 APK 的 content URI */
        fun uriForUpdate(file: File): Uri =
            Uri.parse("content://$AUTHORITY/u/${Uri.encode(file.name)}")
    }
}
