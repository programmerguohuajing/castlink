package com.castlink.receiver.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.castlink.receiver.R
import com.castlink.receiver.airplay.util.Logger

/**
 * v0.1.34: 应用内日志查看（不导出文件）。
 * 显示 Logger 内存缓冲的最近 500 条日志，点“刷新”更新，点“清空”清空。
 * 用户截图发给开发者定位问题（adb 连不上时用）。
 */
class LogViewerActivity : BaseActivity() {
    private lateinit var tvLogs: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)
        tvLogs = findViewById(R.id.tv_logs)
        findViewById<Button>(R.id.btn_log_refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btn_log_clear).setOnClickListener {
            Logger.clearLogs()
            refresh()
        }
        findViewById<Button>(R.id.btn_log_back).setOnClickListener { finish() }
        refresh()
    }

    private fun refresh() {
        val logs = Logger.getLogs()
        tvLogs.text = if (logs.isEmpty()) "暂无日志" else logs.joinToString("\n")
    }
}
