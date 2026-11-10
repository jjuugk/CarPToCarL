package io.github.shaonialife.carptocar.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局调试文件日志（files/main_debug.log）。
 *
 * 荣耀机型 logcat 被 HKS 加密，本文件是 adb 侧唯一调试通道（run-as 读取）。
 * MainActivity 与 CarPlayService（投影生命周期诊断）共用同一把锁，避免同文件并发写交叉。
 */
object DebugFileLog {
    private val lock = Any()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * 「本次进程启动」标记行。日志文件跨进程保留（最多 2MB），用户反馈问题时
     * 历史轮次的日志会把真正有用的部分淹没——导出默认只取最后一条标记之后的内容。
     * 标记本身写进文件而不是只记内存偏移：文件可能被 2MB 截断，偏移会失效。
     */
    const val SESSION_MARKER = "===== CarPToCar+ICCOA 进程启动（本次日志起点）====="

    /**
     * 改名前的标记（应用曾叫 CarDeck）。**故意保留旧字符串**：设备上可能存着旧版本写的
     * 日志文件，那时标记是旧名。只认新名会让这些文件退化成「无标记 = 返回全文」，
     * 用户导出历史日志时反而多出大量无关内容。
     */
    internal const val LEGACY_SESSION_MARKER = "===== CarDeck 进程启动（本次日志起点）====="

    /**
     * 写入本次启动标记。由 [io.github.shaonialife.carptocar.SenderApp.onCreate] 调用一次；
     * 重复调用会产生多条标记，导出仍取最后一条，行为安全。
     */
    fun markSessionStart(context: Context) {
        append(context, SESSION_MARKER)
    }

    /**
     * 读取日志正文。[currentSessionOnly] = true 时只返回最后一条 [SESSION_MARKER]
     * 之后的内容（无标记时返回全文——首次安装/旧版本文件都属这种情形）。
     */
    fun read(
        context: Context,
        currentSessionOnly: Boolean,
    ): String? =
        runCatching {
            val text = File(context.filesDir, "main_debug.log").readText(Charsets.UTF_8)
            if (currentSessionOnly) currentSessionSlice(text) else text
        }.getOrNull()

    /**
     * 取最后一条启动标记之后的内容（纯函数，单测覆盖边界）。
     * 无标记 → 全文：旧版本写入的文件里没有标记，此时全量比空更好。
     */
    internal fun currentSessionSlice(text: String): String {
        // 新旧两种标记都认，取最靠后的那个：同一个文件里可能前半段是旧版本写的。
        val best =
            listOf(SESSION_MARKER, LEGACY_SESSION_MARKER)
                .map { marker -> marker to text.lastIndexOf(marker) }
                .filter { it.second >= 0 }
                .maxByOrNull { it.second }
                ?: return text
        return text.substring(best.second + best.first.length)
    }

    fun append(
        context: Context,
        message: String,
    ) {
        try {
            synchronized(lock) {
                val file = File(context.filesDir, "main_debug.log")
                // 超限直接删会连「本次启动」标记一起丢（导出就退化为全量历史）。
                // 删后立即重写一条标记，保证截断后的新文件仍能按会话切分。
                if (file.length() > 2_000_000) {
                    file.delete()
                    FileWriter(
                        file,
                        true,
                    ).use {
                        it
                            .append('[')
                            .append(timestamp.format(Date()))
                            .append("] ")
                            .append(SESSION_MARKER)
                            .append('\n')
                    }
                }
                FileWriter(file, true).use { writer ->
                    writer
                        .append('[')
                        .append(timestamp.format(Date()))
                        .append("] ")
                        .append(message)
                        .append('\n')
                }
            }
        } catch (_: Throwable) {
        }
    }
}
