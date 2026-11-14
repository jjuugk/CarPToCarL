package io.github.shaonialife.carptocar.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志导出：把运行日志写成 `下载/CarPToCarL/CarPToCarL-log-<时间>.txt`，普通文件管理器可见。
 *
 * 为什么落 MediaStore.Downloads 而不是应用私有目录：
 *  - `filesDir` / `getExternalFilesDir` 普通用户在文件管理器里找不到（Android 11+
 *    系统文件管理器直接屏蔽 `Android/data`），日志导出等于没导；
 *  - MediaStore.Downloads 无需任何存储权限，落盘即出现在系统「下载」目录，
 *    微信/QQ/邮件发送附件也能直接选到。
 *
 * 内容优先取 `files/main_debug.log`：它记录全部 appendLog（不受 UI 日志开关影响，
 * 最多保留最近 2MB）；文件不可读时才退回 UI 的内存缓冲（最近 20~40KB）。
 */
object LogExporter {
    /** 导出范围：默认只导本次进程启动后的日志（历史轮次对排查是噪音）。 */
    enum class Scope(
        val label: String,
        val fileNameTag: String,
    ) {
        CURRENT_SESSION("仅本次启动", ""),
        ALL("全部历史日志", "-all"),
    }

    data class Result(
        val uri: Uri,
        val fileName: String,
        /** 给用户看的绝对路径（文件管理器里按这个找）。 */
        val displayPath: String,
        val bytes: Long,
        val scope: Scope,
    )

    /** 导出目录（相对外部存储根）：下载/CarPToCarL。 */
    private val RELATIVE_DIR = "${Environment.DIRECTORY_DOWNLOADS}/CarPToCarL"

    /** 文件管理器里展示的绝对路径前缀（主用户外部存储；分区存储下即 /sdcard）。 */
    private const val DISPLAY_ROOT = "/storage/emulated/0"

    /** 导出并返回结果；失败返回 null（调用方提示用户）。任意线程可调（含 IO）。 */
    fun export(
        context: Context,
        fallbackText: String,
        scope: Scope = Scope.CURRENT_SESSION,
    ): Result? {
        val body = buildContent(context, fallbackText, scope)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "CarPToCarL-log-$stamp${scope.fileNameTag}.txt"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                // 先标 pending 再写：避免文件管理器/相册扫描到半截文件
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("openOutputStream 返回 null")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Result(uri, fileName, "$DISPLAY_ROOT/$RELATIVE_DIR/$fileName", bytes.size.toLong(), scope)
        } catch (_: Exception) {
            // 半截文件（IS_PENDING=1）对用户不可见，但会占空间——清掉
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun buildContent(
        context: Context,
        fallbackText: String,
        scope: Scope,
    ): String =
        buildString {
            appendLine("CarPToCarL 运行日志")
            appendLine("导出时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("应用版本: " + AppVersion.nameWithTag(context))
            appendLine("导出范围: " + scope.label)
            appendLine(
                "设备: ${Build.MANUFACTURER} ${Build.MODEL} | " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            )
            appendLine("==================================================")
            val fileLog = DebugFileLog.read(context, currentSessionOnly = scope == Scope.CURRENT_SESSION)
            if (!fileLog.isNullOrBlank()) {
                if (scope == Scope.CURRENT_SESSION) {
                    appendLine("（仅本次进程启动之后的日志；需要历史请点「导出全部」）")
                } else {
                    appendLine("（全部文件日志；文件最多 2MB，超出时系统只保留最近一段）")
                }
                append(fileLog)
                if (!fileLog.endsWith('\n')) appendLine()
            } else {
                // 启动标记刚写、其余日志还没落盘（或文件不可读）时，界面缓冲仍是有效来源
                appendLine("（文件日志不可用——导出界面缓冲，可能不完整）")
                append(fallbackText)
            }
            // 关键事件回放：文件日志到 2MB 会滚动、内存缓冲只有 20k 字符，而
            // 「音频档位 / 编码器自检 / 车机音量 / 协商结果」这些一锤定音的行都在
            // 会话开头——2026-11-13 的日志就是这样丢掉了解释「为什么没声音」的证据。
            val critical = CriticalEvents.snapshot()
            if (critical.isNotEmpty()) {
                appendLine()
                appendLine("============ 关键事件（不随日志滚动丢失，与上文重复属正常）============")
                critical.forEach { append(it) }
                appendLine("================================================================")
            }
            // 连接失败报文快照：只在「未建链就跑完一轮」时由 SessionTrace 落盘，
            // 正常连接的轮次不产生文件（也不占空间）。附在这里，一份日志就能还原现场：
            // 过程日志说我们放弃了什么，报文说车机最后发了什么。
            val failure = SessionTrace.latestFailure(context)
            val trace = failure?.let { SessionTrace.readFile(it) }
            if (!trace.isNullOrBlank()) {
                appendLine()
                appendLine("============ 连接失败报文快照（${failure.name}）============")
                appendLine("格式：[时刻] 方向 通道 类型 id 长度 报文体hex；TX=手机→车机，RX=车机→手机")
                appendLine("（仅未建链的轮次才会生成；视频/音频帧不记录）")
                appendLine("================================================================")
                append(trace)
                if (!trace.endsWith('\n')) appendLine()
            } else {
                appendLine()
                appendLine("（本轮没有连接失败报文快照——没发生未建链的会话，或快照已被清理）")
            }
        }
}
