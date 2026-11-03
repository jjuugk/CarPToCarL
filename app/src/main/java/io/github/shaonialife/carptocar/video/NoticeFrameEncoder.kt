package io.github.shaonialife.carptocar.video

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * 把一段文字渲染成单帧 H.264（AnnexB，SPS/PPS + 一个 IDR），用于在
 * 「CarLife 没劫持到画面」时把提示文字推到车机屏幕上，代替黑屏。
 *
 * 实现：MediaCodec Surface 编码器，画一帧黑底白字，EOS 后取出 codec-config（avcC，
 * 内含 SPS/PPS）与 IDR 帧。MediaCodec 的 H.264 输出是「长度前缀」格式（每个 NAL 前
 * `lenSize` 字节大端长度），这里统一转成 AnnexB 起始码（00 00 00 01）后交给
 * [VideoSender.submitH264]。
 *
 * 纯编码、无副作用：失败一律返回 null，让调用方退回「仅日志」兜底。
 */
object NoticeFrameEncoder {

    data class NoticeFrame(val csd: ByteArray, val idr: ByteArray)

    fun encodeNotice(width: Int, height: Int, lines: List<String>): NoticeFrame? {
        val w = (width / 2) * 2
        val h = (height / 2) * 2
        if (w < 16 || h < 16 || lines.isEmpty()) return null

        val codec = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }
            .getOrNull() ?: return null
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
            // 1fps 会被部分厂商编码器拒收（本机实测抛异常）——反正是单帧，给个常规值保险。
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, avcLevelFor(w, h))
        }
        // configure 显式 profile/level 被拒时，去掉这两项重试一次（与 VideoSender 主编码路径同策略）。
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (first: Exception) {
            format.removeKey(MediaFormat.KEY_PROFILE)
            format.removeKey(MediaFormat.KEY_LEVEL)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (second: Exception) {
                runCatching { codec.release() }
                throw IllegalStateException(
                    "configure 失败（带档位=${first.javaClass.simpleName}:${first.message}；" +
                        "去档位=${second.javaClass.simpleName}:${second.message}）",
                    second,
                )
            }
        }
        val surface = try {
            codec.createInputSurface()
        } catch (error: Exception) {
            runCatching { codec.release() }
            throw IllegalStateException(
                "createInputSurface 失败: ${error.javaClass.simpleName}: ${error.message}", error,
            )
        }
        try {
            codec.start()
        } catch (error: Exception) {
            runCatching { surface.release() }
            runCatching { codec.release() }
            throw IllegalStateException(
                "codec.start 失败: ${error.javaClass.simpleName}: ${error.message}", error,
            )
        }
        try {
            drawNotice(surface, w, h, lines)
            codec.signalEndOfInputStream()
            val csd: ByteArray?
            val idr: ByteArray?
            try {
                val result = drain(codec)
                csd = result.first
                idr = result.second
            } catch (error: Exception) {
                return null
            }
            if (csd == null || idr == null) return null
            // csd-0 有两种布局：avcC（多数编码器）或 AnnexB（个别厂商编码器，本机实测是这种）。
            // 按 avcC 硬解析 AnnexB 会读出巨值长度直接炸（2026-11-03 实测），所以先探测格式。
            val csdAnnexB: ByteArray
            val idrAnnexB: ByteArray
            if (isAnnexB(csd)) {
                val nals = splitAnnexB(csd)
                val sps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
                    ?: return null
                val pps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
                    ?: return null
                csdAnnexB = annexB(sps) + annexB(pps)
                idrAnnexB = when {
                    isAnnexB(idr) -> normalizeAnnexB(idr)
                    else -> toAnnexB(idr, 4)
                }
            } else {
                val parsed = parseAvcC(csd) ?: return null
                val lenSize = (csd[4].toInt() and 0x03) + 1
                csdAnnexB = annexB(parsed.first) + annexB(parsed.second)
                idrAnnexB = toAnnexB(idr, lenSize)
            }
            if (idrAnnexB.isEmpty()) return null
            return NoticeFrame(csdAnnexB, idrAnnexB)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
        }
    }

    /** 取 codec-config（avcC）与首个 IDR 帧；超 200 次出队就放弃（防编码器异常挂死）。 */
    private fun drain(codec: MediaCodec, maxIter: Int = 200): Pair<ByteArray?, ByteArray?> {
        val info = MediaCodec.BufferInfo()
        var csd: ByteArray? = null
        var idr: ByteArray? = null
        var iter = 0
        while (iter++ < maxIter) {
            val idx = try {
                codec.dequeueOutputBuffer(info, 5_000_000L)
            } catch (error: Exception) {
                return csd to idr
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                idx < 0 -> return csd to idr
            }
            val buf = codec.getOutputBuffer(idx)
            if (buf == null) {
                codec.releaseOutputBuffer(idx, false)
                continue
            }
            val flags = info.flags
            val data = if (info.size > 0) {
                val b = ByteArray(info.size)
                buf.get(b)
                b
            } else {
                ByteArray(0)
            }
            codec.releaseOutputBuffer(idx, false)
            if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                csd = data
                continue
            }
            if ((flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                if (data.isNotEmpty() && (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) idr = data
                return csd to idr
            }
            if (data.isNotEmpty() && (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                idr = data
            }
        }
        return csd to idr
    }

    /** 画一帧黑底白字（自动按短边缩放字号，多行垂直居中对齐）。 */
    private fun drawNotice(surface: Surface, w: Int, h: Int, lines: List<String>) {
        // lockCanvas 返回 null 不能静默 return——finally 里的 unlockCanvasAndPost(null) 会再抛一个
        // 更难懂的异常把真实原因盖掉。这里显式抛出，让上层日志能看到确切卡点。
        val canvas = surface.lockCanvas(null)
            ?: throw IllegalStateException("Surface.lockCanvas 返回 null——编码器输入面不支持 CPU Canvas 绘制")
        try {
            canvas.drawColor(Color.BLACK)
            val textSize = (h * 0.055f).coerceAtMost(w * 0.085f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                this.textSize = textSize
            }
            val lineH = textSize * 1.35f
            // 字号自适应：若某行宽度超画布 92%，整体缩到放得下。
            val longest = lines.maxByOrNull { measure(paint, it) } ?: return
            if (measure(paint, longest) > w * 0.92f) {
                paint.textSize = textSize * (w * 0.92f) / measure(paint, longest)
            }
            val startY = h / 2f - lineH * (lines.size - 1) / 2f + textSize / 3f
            lines.forEachIndexed { i, line ->
                @Suppress("DEPRECATION")
                canvas.drawText(line, w / 2f, startY + i * lineH, paint)
            }
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun measure(paint: Paint, text: String): Float = paint.measureText(text)

    /** 从 avcC 中取出 SPS 与 PPS（各自含 NAL 头、不含长度前缀）。 */
    private fun parseAvcC(avcC: ByteArray): Pair<ByteArray, ByteArray>? {
        if (avcC.size < 8) return null
        val lenSize = (avcC[4].toInt() and 0x03) + 1
        if (avcC[5].toInt() and 0x1F < 1) return null
        var off = 6
        val sps = readNal(avcC, off, lenSize) ?: return null
        off += lenSize + sps.size
        if (off >= avcC.size) return null
        if (avcC[off].toInt() and 0x1F < 1) return null
        off += 1
        val pps = readNal(avcC, off, lenSize) ?: return null
        return sps to pps
    }

    private fun readNal(buf: ByteArray, off: Int, lenSize: Int): ByteArray? {
        if (off + lenSize > buf.size) return null
        // 用 Long 累积：4 字节长度顶到符号位时 Int 会变负，越界判断随之失效
        // （2026-11-03 实测 copyOfRange(10, -2145396213) 异常就是它）。
        var len = 0L
        for (i in 0 until lenSize) len = (len shl 8) or (buf[off + i].toLong() and 0xFF)
        val start = off + lenSize
        if (len <= 0 || len > buf.size - start) return null
        return buf.copyOfRange(start, start + len.toInt())
    }

    /** csd/帧是否为 AnnexB（00 00 01 或 00 00 00 01 起始码开头）。 */
    private fun isAnnexB(buf: ByteArray): Boolean =
        buf.size >= 4 && buf[0] == 0.toByte() && buf[1] == 0.toByte() &&
            (buf[2] == 1.toByte() || (buf[2] == 0.toByte() && buf[3] == 1.toByte()))

    /** 按 AnnexB 起始码拆 NAL（返回的每个 NAL 不含起始码）。 */
    private fun splitAnnexB(buf: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        var i = 0
        var start = -1
        while (i < buf.size - 2) {
            val sc3 = buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() && buf[i + 2] == 1.toByte()
            val sc4 = i < buf.size - 3 &&
                buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() &&
                buf[i + 2] == 0.toByte() && buf[i + 3] == 1.toByte()
            if (sc3 || sc4) {
                val scLen = if (sc4) 4 else 3
                if (start >= 0 && i > start) nals += buf.copyOfRange(start, i)
                start = i + scLen
                i += scLen
            } else {
                i++
            }
        }
        if (start in 0 until buf.size) nals += buf.copyOfRange(start, buf.size)
        return nals
    }

    /** 把 AnnexB 里的 3 字节起始码统一成 4 字节（车机通道侧按 4 字节约定拆帧）。 */
    private fun normalizeAnnexB(buf: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        splitAnnexB(buf).filter { it.isNotEmpty() }.forEach { out.write(annexB(it)) }
        return out.toByteArray()
    }

    /** 长度前缀流 → AnnexB（每个 NAL 前加 00 00 00 01）。 */
    private fun toAnnexB(buf: ByteArray, lenSize: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var off = 0
        while (off + lenSize <= buf.size) {
            var len = 0
            for (i in 0 until lenSize) len = (len shl 8) or (buf[off + i].toInt() and 0xFF)
            off += lenSize
            if (off + len > buf.size || len <= 0) break
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(buf, off, len)
            off += len
        }
        return out.toByteArray()
    }

    private fun annexB(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(4 + nal.size)
        out.write(byteArrayOf(0, 0, 0, 1))
        out.write(nal)
        return out.toByteArray()
    }

    /** 按分辨率选 H.264 level（与 VideoSender.avcLevelFor 同口径，避免编码器拒绝）。 */
    private fun avcLevelFor(width: Int, height: Int): Int {
        val pixels = width.toLong() * height
        return when {
            pixels > 1280L * 720 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
            pixels > 800L * 480 -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel3
        }
    }
}
