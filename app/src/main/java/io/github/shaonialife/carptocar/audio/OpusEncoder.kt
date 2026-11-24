package io.github.shaonialife.carptocar.audio

import android.media.MediaCodec
import android.media.MediaFormat

/**
 * PCM → Opus 编码器（`type=101 AltAudio / default` 位 `0x40000000` = OPUS 48kHz 单声道用）。
 *
 * 部分车机（如宝马 iD8 / MGU）的 `101/default` 导航独立流仅声明 `0x40000000`
 * （Opus 48kHz 单声道），不声明 PCM 或 AAC-LC。Android 12+（minSdk 31）内置平台
 * `audio/opus` 编码器，跳过 `BUFFER_FLAG_CODEC_CONFIG`（OpusHead）后输出即为标准
 * RFC 6716 裸 Opus 包，每帧固定 20ms = [FRAME_SAMPLES]（960）采样，可直接作为 CarPlay RTP 负载。
 */
internal class OpusEncoder(
    private val sampleRate: Int = 48_000,
    private val channels: Int = 1,
    private val bitRate: Int = BIT_RATE_BPS,
    private val log: (String) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var started = false
    private var inputSamples = 0L

    fun start(): Boolean {
        if (started) return true
        return try {
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            val format =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, pcmBytes(channels) * 2)
                }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            codec = encoder
            started = true
            inputSamples = 0L
            log("AUDIO: Opus 编码器已启动（${sampleRate}Hz ${if (channels == 1) "单声道" else "立体声"} ${bitRate / 1000}kbps）")
            true
        } catch (error: Exception) {
            log("AUDIO: Opus 编码器启动失败 - ${error.message}")
            stop()
            false
        }
    }

    /** 输入一帧 PCM（16bit 小端，[pcmBytes] 字节），返回本次产出的 0..N 个裸 Opus 帧。 */
    fun encode(
        pcm: ByteArray,
        size: Int,
    ): List<ByteArray> {
        val encoder = codec ?: return emptyList()
        if (!started || size <= 0) return emptyList()
        val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            val inputBuffer = encoder.getInputBuffer(inputIndex)
            if (inputBuffer != null) {
                inputBuffer.clear()
                val toCopy = minOf(size, inputBuffer.remaining())
                inputBuffer.put(pcm, 0, toCopy)
                val ptsUs = inputSamples * 1_000_000L / sampleRate.coerceAtLeast(1)
                inputSamples += toCopy / (channels.coerceAtLeast(1) * 2)
                encoder.queueInputBuffer(inputIndex, 0, toCopy, ptsUs, 0)
            }
        }
        val out = ArrayList<ByteArray>(2)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(info, 0)
            if (outputIndex < 0) break
            try {
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (!isConfig && info.size > 0) {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        val packet = ByteArray(info.size)
                        outputBuffer.get(packet)
                        out.add(packet)
                    }
                }
            } finally {
                encoder.releaseOutputBuffer(outputIndex, false)
            }
        }
        return out
    }

    fun stop() {
        started = false
        val encoder = codec
        codec = null
        if (encoder != null) {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    companion object {
        /** 20ms @ 48kHz = 960 采样/帧（CarPlay Opus 48kHz 规范 spf）。 */
        const val FRAME_SAMPLES = 960

        /** 单声道导航语音默认码率 64kbps。 */
        const val BIT_RATE_BPS = 64_000

        private const val TIMEOUT_US = 10_000L

        /** 一帧 PCM 的字节数（16bit）。 */
        fun pcmBytes(channels: Int): Int = FRAME_SAMPLES * channels * 2
    }
}
