package io.github.shaonialife.carptocar.util

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把车机麦克风上行的 PCM 落成 wav，方便**人耳直接判断**「推过来的到底是人声、静音还是噪声」。
 *
 * 为什么落 `下载/CarPToCarL` 而不是应用私有目录：
 *  - 与 [LogExporter] 同一套约定，文件管理器里直接看得到、能点开播放；
 *  - 荣耀机是 release 包（`run-as` 不可用、`allowBackup=false`），`filesDir` 里拿不出来，
 *    而 `/sdcard/Download` 可以直接 `adb pull`。
 *
 * 写入走 `MediaStore.Downloads`（无需任何存储权限），先标 `IS_PENDING=1` 再写，
 * 写完清标记——避免文件管理器/媒体扫描到半截文件。
 */
object MicDumpWriter {
    private val RELATIVE_DIR = "${Environment.DIRECTORY_DOWNLOADS}/CarPToCarL"

    /** 文件管理器里展示的绝对路径前缀（分区存储下即 /sdcard）。 */
    private const val DISPLAY_ROOT = "/storage/emulated/0"

    /** PCM16 wav 头长度。 */
    private const val WAV_HEADER_BYTES = 44

    /**
     * 写一份 wav。
     *
     * @param pcm 小端 s16 交错采样（[MicUplink] 已把车机的大端换过序）
     * @return 给用户看的路径；失败返回 null（调用方只记日志，不影响链路）
     */
    fun writeWav(
        context: Context,
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
    ): String? {
        if (pcm.isEmpty()) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "CarPToCarL-mic-$stamp.wav"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        return try {
            val bytes = header(pcm.size, sampleRate, channels) + pcm
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("openOutputStream 返回 null")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            "$DISPLAY_ROOT/$RELATIVE_DIR/$fileName"
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** 44 字节标准 PCM wav 头（RIFF/WAVE/fmt/data）。 */
    internal fun header(
        pcmBytes: Int,
        sampleRate: Int,
        channels: Int,
    ): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArrayOutputStream(WAV_HEADER_BYTES)
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun le32(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }
        fun le16(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }
        ascii("RIFF")
        le32(36 + pcmBytes)
        ascii("WAVE")
        ascii("fmt ")
        le32(16) // fmt 块长度
        le16(1) // PCM
        le16(channels)
        le32(sampleRate)
        le32(byteRate)
        le16(blockAlign)
        le16(bitsPerSample)
        ascii("data")
        le32(pcmBytes)
        return out.toByteArray()
    }
}
