package com.castlink.receiver.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 二维码生成：内部调用 zxing（QRCodeWriter().encode(...) -> Bitmap）。
 * 调用处需做好 try/catch 降级：本函数在任何异常时返回 null，
 * 调用方应显示占位文字（R.string.qr_fallback）。
 */
object QrUtil {

    private const val TAG = "QrUtil"

    fun encode(text: String, size: Int): Bitmap? {
        return try {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val rowOffset = y * w
                for (x in 0 until w) {
                    pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } catch (e: Exception) {
            Log.w(TAG, "QR encode failed", e)
            null
        }
    }
}
