package com.castlink.receiver

import android.app.Application
import android.content.Context
import com.castlink.receiver.util.CrashReporter
import com.castlink.receiver.util.LocaleHelper

class CastLinkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // v0.3.6：最早安装崩溃捕获（写 filesDir/crashes），免 adb 拿堆栈
        CrashReporter.install(this)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }
}
