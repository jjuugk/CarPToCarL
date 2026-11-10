package io.github.shaonialife.carptocar.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 连接失败时的报文快照（`files/session_trace_fail_*.log`）——用于事后分析「为什么连不上」。
 *
 * **只在失败时落盘**，理由：
 *  - 正常连接时录制没有价值，却要付出磁盘写入与空间代价；
 *  - 失败现场必须保留，而失败发生在最后一刻——所以全程在**内存环形缓冲**里滚，
 *    只有判定失败（未建链就断开/超时/被拆链）才把这一轮写进独立文件。
 *
 * 与 [DebugFileLog] 的分工：
 *  - `main_debug.log` 记「人读的过程」（走到哪一步、为什么放弃），2MB 滚动；
 *  - 本模块记「协议原始字节」（谁在什么时刻发了什么），失败的轮次单独成文件。
 *    两份按时间戳对齐阅读：过程日志说我们放弃了什么，报文说车机最后发了什么。
 *
 * 录制内容（分级，避免被码流淹没）：
 *  - **控制面全量**：iAP2 CSM（蓝牙链路 / 隧道 / AirPlay `comm` 包）、RTSP 请求与应答原文；
 *  - **数据面不记**：视频/音频帧一秒几十条，会把缓冲冲干净，且对「连不上」没有诊断价值。
 *
 * 线程模型：调用方只做字符串拼接并入队到内存（无 IO）；写盘发生在失败那一刻的后台线程。
 */
object SessionTrace {
    /** 报文方向。日志里写作 TX/RX。 */
    enum class Dir(
        val tag: String,
    ) {
        TX("TX"),
        RX("RX"),
    }

    private const val PREFIX = "session_trace_fail_"

    /** 失败快照保留个数（超出删最旧）。 */
    private const val KEEP_FILES = 5

    /** 内存缓冲上限：条数。控制面报文一轮建链约数百条，3000 条够覆盖多轮重试。 */
    private const val MAX_LINES = 3_000

    /** 内存缓冲上限：字符数（约 4MB 内存，hex 后体积翻倍）。 */
    private const val MAX_CHARS = 4_000_000

    /** 单条报文最多记录的 body 字节数；超出保留头尾，中间标注省略。 */
    internal const val MAX_BODY_BYTES = 4_096

    private val lock = Any()

    /** 环形缓冲：只保留最近 [MAX_LINES] 条 / [MAX_CHARS] 个字符。 */
    private val lines = ArrayDeque<String>()
    private var chars = 0

    @Volatile
    private var appDir: File? = null

    private var sessionSeq = 1
    private var sessionNote = ""
    private var sessionStartedAt = 0L

    /** 本轮是否已成功建链。成功则丢弃缓冲——用户要的是「连不上」的现场。 */
    @Volatile
    private var linked = false

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** 绑定应用目录。未绑定 = 所有录制与落盘都是空操作（纯 JVM 单测不碰文件系统）。 */
    fun attach(context: Context) {
        synchronized(lock) {
            if (appDir != null) return
            appDir = context.filesDir
            lines.clear()
            chars = 0
        }
    }

    fun isActive(): Boolean = appDir != null

    /** 会话起点：清空上一轮的缓冲。每次「用户点启动连接」/ 自动重连进入新一轮都调。 */
    fun sessionStart(note: String) {
        if (!isActive()) return
        synchronized(lock) {
            lines.clear()
            chars = 0
            linked = false
            sessionNote = note
            sessionStartedAt = System.currentTimeMillis()
            appendLocked("[${stamp.format(Date())}] ========== 会话开始 #${sessionSeq++} · $note ==========")
        }
    }

    /**
     * 建链成功（视频流真正跑起来）。**成功即丢弃缓冲**——正常连接不需要报文快照。
     * 之后本轮的 [sessionEnd] 也不会落盘。
     */
    fun markLinked() {
        if (!isActive()) return
        synchronized(lock) {
            linked = true
            lines.clear()
            chars = 0
        }
    }

    /**
     * 会话结束。**未成功建链才落盘**（失败现场），成功过就直接丢弃。
     * [reason] 原样进文件首行，尽量写清触发方（车机拆链 / 超时 / 用户取消）。
     */
    fun sessionEnd(reason: String) {
        if (!isActive()) return
        val snapshot: List<String>
        val note: String
        synchronized(lock) {
            if (linked) {
                lines.clear()
                chars = 0
                return
            }
            if (lines.isEmpty()) return
            appendLocked("[${stamp.format(Date())}] ========== 会话结束（未建链）· $reason ==========")
            snapshot = lines.toList()
            note = sessionNote
            lines.clear()
            chars = 0
        }
        val dir = appDir ?: return
        val header =
            buildString {
                appendLine("# CarPToCar+ICCOA 连接失败报文快照")
                appendLine("# 时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                appendLine("# 触发: $note")
                appendLine("# 结束: $reason")
                appendLine("# 时长: ${(System.currentTimeMillis() - sessionStartedAt) / 1000}s，报文 ${snapshot.size} 行")
                appendLine("# 格式: [时刻] 方向 通道 类型 id 长度 报文体hex；TX=手机→车机，RX=车机→手机")
                appendLine("# 说明: 视频/音频帧不记录（只影响体积，对「连不上」无诊断价值）")
                appendLine()
            }
        val body = header + snapshot.joinToString("\n") + "\n"
        // 写盘放到后台：本方法可能在断开链路的关键路径上（甚至主线程），不能因 IO 拖慢收尾
        Thread({
            runCatching { writeSnapshot(dir, body) }
        }, "session-trace-dump").apply { isDaemon = true }.start()
    }

    /** 任意里程碑（WiFi 加入、AirPlay 连接建立……），与报文行同格式便于按时间对齐。 */
    fun mark(text: String) {
        if (!isActive()) return
        synchronized(lock) { appendLocked("[${stamp.format(Date())}] ---- $text") }
    }

    /** iAP2 控制消息。[body] 为 CSM 报文体（不含 messageId 字节）。 */
    fun csm(
        dir: Dir,
        channel: String,
        messageId: Int,
        body: ByteArray,
        note: String = "",
    ) {
        if (!isActive()) return
        val line = formatCsm(stamp.format(Date()), dir, channel, messageId, body, note)
        synchronized(lock) { appendLocked(line) }
    }

    /** RTSP 原文（请求/应答）。多行文本原样保留，行首加缩进便于人读。 */
    fun rtsp(
        dir: Dir,
        channel: String,
        text: String,
    ) {
        if (!isActive()) return
        val line = formatRtsp(stamp.format(Date()), dir, channel, text)
        synchronized(lock) { appendLocked(line) }
    }

    /**
     * 二进制负载的可读摘要：`<binary 1234B> aa bb cc …`（默认只留前 512 字节）。
     * RTSP 的 body 常是 bplist/TLV，全量 hex 会把一行撑到几万字符。
     */
    fun binary(
        body: ByteArray,
        limit: Int = 512,
    ): String {
        if (body.isEmpty()) return ""
        val head = body.copyOfRange(0, minOf(limit, body.size))
        val tail = if (body.size > limit) " …(+${body.size - limit}B)" else ""
        return "\n    <binary ${body.size}B> ${head.toHex()}$tail"
    }

    /** 最近一次失败快照（导出时附在日志后面）。没有则返回 null。 */
    fun latestFailure(context: Context): File? =
        failureFiles(context).firstOrNull()

    /** 全部失败快照，按时间倒序。 */
    fun failureFiles(context: Context): List<File> = filesUnder(context.filesDir)

    /** 读取失败快照正文（导出用）。[limitBytes] 截断保护，默认 2MB。 */
    fun readFile(
        file: File,
        limitBytes: Int = 2_000_000,
    ): String? =
        runCatching {
            val text = file.readText(Charsets.UTF_8)
            if (text.length <= limitBytes) text else "（前部已截断）\n" + text.takeLast(limitBytes)
        }.getOrNull()

    // ---- 内部实现 ----

    private fun appendLocked(line: String) {
        lines.addLast(line)
        chars += line.length + 1
        while (lines.size > MAX_LINES || chars > MAX_CHARS) {
            val removed = lines.removeFirstOrNull() ?: break
            chars -= removed.length + 1
        }
    }

    private fun writeSnapshot(
        dir: File,
        body: String,
    ) {
        val file = File(dir, PREFIX + fileStamp.format(Date()) + ".log")
        file.writeText(body)
        // 只留最近 KEEP_FILES 个：失败快照可能每个几十 KB~数百 KB，攒多了占空间
        filesUnder(dir).drop(KEEP_FILES).forEach { it.delete() }
    }

    private fun filesUnder(root: File): List<File> =
        root
            .listFiles { f -> f.isFile && f.name.startsWith(PREFIX) }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    internal fun formatCsm(
        ts: String,
        dir: Dir,
        channel: String,
        messageId: Int,
        body: ByteArray,
        note: String = "",
    ): String {
        val id = "0x%04x".format(messageId)
        val suffix = if (note.isEmpty()) "" else " ($note)"
        // note 放行尾：夹在 len 与 body 之间会把「长度→报文」的固定结构打断，
        // 按固定列位切日志的脚本（以及人眼睛）都会错位。
        val payload = if (body.isEmpty()) "len=0" else "len=${body.size} body=${body.toHexBody()}"
        return "[$ts] ${dir.tag} $channel csm id=$id $payload$suffix"
    }

    internal fun formatRtsp(
        ts: String,
        dir: Dir,
        channel: String,
        text: String,
    ): String {
        val head = text.lineSequence().firstOrNull()?.trim().orEmpty()
        val body = text.lineSequence().joinToString("\n") { "    | $it" }
        return "[$ts] ${dir.tag} $channel rtsp len=${text.length} $head\n$body"
    }

    /** 小写 hex，空格分隔。 */
    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 3)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F]).append(' ')
        }
        if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
        return sb.toString()
    }

    /**
     * 超长报文体：保留头 [MAX_BODY_BYTES]/2 与尾 [MAX_BODY_BYTES]/2，中间标注省略字节数。
     * 头尾都留的理由——iAP2 的大报文（0x1D01 能力清单、0xEA03 数据）开头是结构、结尾常有
     * 校验/终止项，只留头会看不出结尾是否完整。
     */
    private fun ByteArray.toHexBody(): String {
        if (size <= MAX_BODY_BYTES) return toHex()
        val half = MAX_BODY_BYTES / 2
        val omitted = size - MAX_BODY_BYTES
        return copyOfRange(0, half).toHex() +
            " …省略${omitted}字节… " +
            copyOfRange(size - half, size).toHex()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
