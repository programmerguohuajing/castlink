/*
 * Ported from PhairPlay (https://github.com/mazer666/PhairPlay, Apache-2.0);
 * package renamed to com.castlink.receiver.airplay.*. See THIRD-PARTY-NOTICES.
 */
package com.castlink.receiver.airplay.util

import android.util.Log

/**
 * Logger — thin wrapper around android.util.Log (ported from PhairPlay; Timber backend removed
 * because CastLink has no Timber dependency).
 *
 * Same call surface as the original: v/d/i/w/e.
 */
object Logger {
    private const val TAG = "CastLink-AirPlay"

    fun v(message: String) = Log.v(TAG, message)
    fun d(message: String) = Log.d(TAG, message)
    fun i(message: String) = Log.i(TAG, message)
    fun w(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
    }
    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
    }
}
