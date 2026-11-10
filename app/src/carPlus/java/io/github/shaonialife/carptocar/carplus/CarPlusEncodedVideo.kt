package io.github.shaonialife.carptocar.carplus

import android.os.Handler
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.carlife.H264Sps
import io.github.shaonialife.carptocar.video.VideoSender
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException

/** Reads Annex B access units and cast start markers from the ICCOA process. */
internal class CarPlusEncodedVideo(
    private val input: ParcelFileDescriptor,
    private val sender: VideoSender,
    private val outputSize: CarPlusVideo.Size,
    private val allowOtherProfiles: Boolean,
    private val main: Handler,
    private val onFailure: (CarPlusEncodedVideo, String) -> Unit,
) {
    @Volatile private var cancelled = false
    @Volatile private var waitingForKeyFrame = false
    @Volatile private var timeoutReason: String? = null
    private val timeout = Runnable {
        if (waitingForKeyFrame && !cancelled) {
            timeoutReason = "8 秒内未收到可用的 H.264 关键帧"
            interruptRead()
        }
    }

    fun start() {
        Thread(::read, "iccoa-video-in").apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        cancelled = true
        main.removeCallbacks(timeout)
        interruptRead()
    }

    private fun interruptRead() {
        runCatching { Os.shutdown(input.fileDescriptor, OsConstants.SHUT_RDWR) }
    }

    private fun read() {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var failure = "ICCOA 直通管道已关闭"
        try {
            DataInputStream(BufferedInputStream(ParcelFileDescriptor.AutoCloseInputStream(input))).use { stream ->
                while (!cancelled && sender.isRunning) {
                    val size = stream.readInt()
                    if (size == 0) {
                        sps = null
                        pps = null
                        awaitKeyFrame()
                        continue
                    }
                    if (size !in 1..CarPlusVideoTap.MAX_FRAME_BYTES) throw IOException("ICCOA 帧长度异常")
                    val timestampUs = stream.readLong()
                    val frame = ByteArray(size).also(stream::readFully)
                    if (!waitingForKeyFrame && sps == null) continue
                    val units = sender.splitAnnexB(frame)
                    val newSps = units.firstOrNull { nalType(it) == 7 }
                    val newPps = units.firstOrNull { nalType(it) == 8 }
                    if (newSps != null && !newSps.contentEquals(sps)) {
                        val dimensions = H264Sps.parseNal(newSps)
                        val profile = newSps.getOrNull(1)?.toInt()?.and(0xff)
                        if (dimensions != (outputSize.width to outputSize.height) ||
                            profile == null || !allowOtherProfiles && profile != 66
                        ) {
                            throw IOException("源 H.264 档位不兼容（尺寸 $dimensions，profile $profile）")
                        }
                        sps = newSps
                        awaitKeyFrame()
                        SessionController.appendLog("ICCOA: 源 H.264 SPS $dimensions profile=$profile")
                    }
                    if (newPps != null && !newPps.contentEquals(pps)) {
                        pps = newPps
                        awaitKeyFrame()
                    }
                    val idr = units.any { nalType(it) == 5 }
                    if (waitingForKeyFrame && (!idr || sps == null || pps == null)) continue
                    if (units.none { nalType(it) in 1..5 }) continue
                    val data = if (idr && sps != null && pps != null && (newSps == null || newPps == null)) {
                        PREFIX + sps + PREFIX + pps + frame
                    } else {
                        frame
                    }
                    if (sender.submitH264(data, idr, timestampUs / 1_000) && waitingForKeyFrame) {
                        waitingForKeyFrame = false
                        main.removeCallbacks(timeout)
                        SessionController.appendLog("ICCOA: H.264 直通首个 IDR 已送达 CarPlay 视频通道")
                    }
                }
            }
        } catch (error: IOException) {
            failure = error.message ?: failure
        } finally {
            main.removeCallbacks(timeout)
            if (!cancelled) main.post {
                if (!cancelled) onFailure(this, timeoutReason ?: failure)
            }
        }
    }

    private fun awaitKeyFrame() {
        if (waitingForKeyFrame) return
        waitingForKeyFrame = true
        main.postDelayed(timeout, 8_000)
    }

    private fun nalType(nal: ByteArray) = nal[0].toInt() and 0x1f

    companion object {
        private val PREFIX = byteArrayOf(0, 0, 0, 1)
    }
}
