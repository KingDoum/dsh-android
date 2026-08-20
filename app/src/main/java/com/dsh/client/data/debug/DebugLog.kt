package com.dsh.client.data.debug

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 轻量调试日志系统。
 *
 * - 内存环形缓冲（默认保留最近 2000 条），供 UI 即时查看
 * - 同步落盘到 filesDir/dsh_logs/debug.log（异步 IO，不阻塞主线程）
 * - 崩溃时通过 [installCrashHandler] 捕获未处理异常写入日志
 * - 每个条目带时间戳 + 级别 + 标签 + 消息
 */
object DebugLog {

    enum class Level(val tag: String) {
        DEBUG("D"), INFO("I"), WARN("W"), ERROR("E")
    }

    data class Entry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: Level,
        val tag: String,
        val message: String
    ) {
        fun formatted(): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
            return "$time ${level.tag}/$tag: $message"
        }
    }

    private const val MAX_IN_MEMORY = 2000
    private val entries = CopyOnWriteArrayList<Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logFile: File? = null

    /** 初始化日志文件目录。在 Application.onCreate 调用。 */
    fun init(context: Context) {
        val dir = File(context.filesDir, "dsh_logs").apply { mkdirs() }
        logFile = File(dir, "debug.log")
    }

    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun e(tag: String, msg: String) = log(Level.ERROR, tag, msg)

    private fun log(level: Level, tag: String, msg: String) {
        val entry = Entry(level = level, tag = tag, message = msg)
        entries.add(entry)
        if (entries.size > MAX_IN_MEMORY) {
            entries.removeAt(0)
        }
        // 异步落盘
        val file = logFile ?: return
        scope.launch {
            try {
                file.appendText(entry.formatted() + "\n")
            } catch (_: Exception) { }
        }
    }

    /** 返回当前全部内存日志（从旧到新）。 */
    fun snapshot(): List<Entry> = entries.toList()

    /** 返回最近 N 条日志的纯文本。 */
    fun recentText(limit: Int = 500): String {
        val all = entries
        val start = (all.size - limit).coerceAtLeast(0)
        return buildString {
            for (i in start until all.size) {
                appendLine(all[i].formatted())
            }
        }
    }

    /** 清空内存日志（不清文件）。 */
    fun clear() = entries.clear()

    /** 安装全局未捕获异常处理器：崩溃写入日志文件。 */
    fun installCrashHandler() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stack = throwable.stackTraceToString()
                logFile?.appendText("[CRASH] ${thread.name} ${throwable.javaClass.name}: ${throwable.message}\n$stack\n")
            } catch (_: Exception) { }
            default?.uncaughtException(thread, throwable)
        }
    }
}
