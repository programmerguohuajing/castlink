package com.castlink.receiver.ui

import android.app.Activity
import android.content.Context
import com.castlink.receiver.util.LocaleHelper

/**
 * 所有 Activity 的基类：在 attachBaseContext 阶段按用户语言设置包装 Context，
 * 保证设置页切换语言后 recreate() 即时生效且不重启进程。
 * （仅靠 Application.attachBaseContext 在部分机型上不会作用到 Activity 资源。）
 */
open class BaseActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }
}
