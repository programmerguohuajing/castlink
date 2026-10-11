package com.castlink.receiver.util

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.castlink.receiver.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.3.6：应用内崩溃上报（免 adb）。
 *
 * - [install] 在 Application.onCreate 最早安装：捕获未处理异常，写堆栈到 filesDir/crashes，
 *   再交还系统默认 handler（保留"已停止运行"弹窗与进程退出行为）；
 * - [showPending] 在 MainActivity.onCreate 最早调用：若有上次崩溃日志，弹窗展示堆栈
 *   并提供"复制堆栈"，用户直接把文字发回来即可定位。
 */
object CrashReporter {

    private const val DIR = "crashes"
    private const val MAX_KEPT = 5
    private const val DIALOG_TEXT_CAP = 12000

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(app, thread, throwable) }
            // 交还系统：走默认崩溃流程（弹窗 + 杀进程）。若无默认 handler 则主动退出，
            // 避免吞掉崩溃导致假死。
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun writeCrash(app: Application, thread: Thread, t: Throwable) {
        val dir = File(app.filesDir, DIR).apply { mkdirs() }
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_KEPT - 1)
            ?.forEach { runCatching { it.delete() } }

        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.appendLine("time=$time")
        sb.appendLine("thread=${thread.name}")
        sb.appendLine("model=${Build.MODEL} brand=${Build.BRAND} sdk=${Build.VERSION.SDK_INT}")
        var cur: Throwable? = t
        var first = true
        while (cur != null) {
            if (!first) sb.appendLine("Caused by:")
            first = false
            sb.appendLine("${cur.javaClass.name}: ${cur.message}")
            for (el in cur.stackTrace) sb.appendLine("    at $el")
            cur = cur.cause
        }
        File(dir, "crash-${System.currentTimeMillis()}.log").writeText(sb.toString())
    }

    /** MainActivity.onCreate 首行调用：有崩溃日志则弹窗展示。 */
    fun showPending(activity: Activity) {
        val dir = File(activity.filesDir, DIR)
        val latest = dir.listFiles()
            ?.filter { it.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }
            ?: return
        val full = runCatching { latest.readText() }.getOrNull().orEmpty()
        if (full.isBlank()) return
        val shown = if (full.length > DIALOG_TEXT_CAP) {
            full.take(DIALOG_TEXT_CAP) + "\n…(truncated)"
        } else full

        val tv = TextView(activity).apply {
            text = shown
            setTextIsSelectable(true)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(activity).apply { addView(tv) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.crash_dialog_title)
            .setView(scroll)
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", full))
                Toast.makeText(activity, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.crash_dismiss, null)
            .setOnDismissListener {
                // 看过即清，避免每次启动都弹
                runCatching { dir.listFiles()?.forEach { it.delete() } }
            }
            .show()
    }
}
