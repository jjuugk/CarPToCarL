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

    /** 文件上限：超过就只保留尾部 [KEEP_BYTES]（不再整份删除）。 */
    private const val MAX_BYTES = 2_000_000L

    /** 截断后保留的尾部长度：够放一次完整会话的关键过程，又不会让导出文件过大。 */
    private const val KEEP_BYTES = 1_000_000L

    /**
     * 「本次进程启动」标记行。日志文件跨进程保留（最多 2MB），用户反馈问题时
     * 历史轮次的日志会把真正有用的部分淹没——导出默认只取最后一条标记之后的内容。
     * 标记本身写进文件而不是只记内存偏移：文件可能被 2MB 截断，偏移会失效。
     */
    const val SESSION_MARKER = "===== CarPToCarL 进程启动（本次日志起点）====="

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
                // 超限不能整份删：删了连「本次启动」之前的内容一起没了，而文件日志是
                // 出问题后唯一能回溯的证据。2026-11-13 的会话就是这样丢掉开头的——
                // 2MB 一到整份清空，音频 SETUP/编码器自检/车机音量查询全没了，
                // 用户导出时只剩「会话跑了 13 分钟之后」的稳态日志，怎么看都"没问题"。
                // 现在只保留尾部 [KEEP_BYTES]，并在开头写明"更早的已丢弃"。
                if (file.length() > MAX_BYTES) {
                    trimTail(file)
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

    /**
     * 只保留文件尾部 [KEEP_BYTES]，前缀一条截断标记 + 本次启动标记。
     *
     * 截断标记是**必须**的：否则导出看起来像"进程刚开始"，会把「日志被滚掉」
     * 误判成「这轮什么都没发生」。标记以 `=====` 开头，与人眼和脚本都好认。
     */
    private fun trimTail(file: File) {
        val tail =
            runCatching {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    val length = raf.length()
                    val start = (length - KEEP_BYTES).coerceAtLeast(0L)
                    raf.seek(start)
                    val buffer = ByteArray((length - start).toInt())
                    raf.readFully(buffer)
                    val text = String(buffer, Charsets.UTF_8)
                    // 从第一个换行之后切：seek 落在多字节字符中间时会先出现一个 U+FFFD
                    val newline = text.indexOf('\n')
                    if (newline >= 0) text.substring(newline + 1) else text
                }
            }.getOrNull() ?: ""
        FileWriter(file, false).use { writer ->
            // 顺序很关键：**先**写启动标记、**再**写截断说明。
            // 导出「仅本次启动」会取最后一条标记之后的内容，说明放在标记之前会被切掉，
            // 于是用户在导出里完全看不到「更早的已丢弃」，只会觉得这轮什么都没发生。
            writer
                .append('[')
                .append(timestamp.format(Date()))
                .append("] ")
                .append(SESSION_MARKER)
                .append('\n')
                .append('[')
                .append(timestamp.format(Date()))
                .append("] ===== 日志已截断：更早的内容已滚出（文件上限 2MB，保留最近 1MB）=====\n")
                .append(tail)
        }
    }
}
