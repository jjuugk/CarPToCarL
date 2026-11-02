package io.github.shaonialife.carptocar.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * PCM → AAC-LC 编码器（type=102 MainHighAudio 通路用）。
 *
 * 为什么需要它（2026-10-20 Honda HFT / Alpine 实测）：该车机 `/info` 里
 * `type=102`（MainHighAudio）的 `media` 档只声明 `0x400000` = **AAC-LC 44.1k 立体声**，
 * 没有任何 PCM 位；而 `type=100 media` 只声明 PCM 44.1k，往那里推 PCM 时车机
 * 收包却不消费（`/feedback` 里 `sampleTime` 恒 0），约 6.5s 后拆会话。
 * 真 iPhone 的媒体音频走的就是 102 + AAC-LC，所以必须自己编 AAC。
 *
 * 线格式（对照 catplay `aac_encoder.rs` + `rtp_recorder.rs`）：编码器输出的是
 * **裸 AAC 帧** —— 没有 ADTS 头、没有 RFC3640 的 AU header，RTP 负载就是该帧本身，
 * 一包一帧。Android `MediaCodec` 的 AAC 编码器同样输出裸帧，可直接使用。
 *
 * 帧长固定 1024 采样（AAC-LC 规格），因此 RTP 时间戳每包步进 1024。
 */
class AacLcEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val bitRate: Int = 192_000,
    /** 是否给每帧补 7 字节 ADTS 头（见 [AudioTuning.ADTS]）。 */
    private val wrapAdts: Boolean = false,
    private val log: (String) -> Unit,
) {
    companion object {
        /** AAC-LC 一帧固定 1024 个采样（每声道）。 */
        const val FRAME_SAMPLES = 1024

        /** 一帧 PCM 的字节数（16bit）。 */
        fun pcmBytes(channels: Int): Int = FRAME_SAMPLES * channels * 2

        /** ADTS 头长度（无 CRC）。 */
        private const val ADTS_HEADER_SIZE = 7

        /** ADTS 里的 audioObjectType：AAC-LC = 2（存的是 AOT-1）。 */
        private const val AAC_PROFILE_LC = 2

        private const val MIME = "audio/mp4a-latm"
        private const val TIMEOUT_US = 10_000L

        /**
         * 组 7 字节 ADTS 头（无 CRC）：MPEG-4 / AAC-LC / 对应采样率与声道数。
         *
         * 线上报文（RTP 负载）按 Apple 的约定是**裸 AAC 帧**，但个别车机的解码链是
         * 按 ADTS 组织的（Android `audio/aac-adts`），裸帧喂进去会被判为非帧。
         * 因此留一条可切换的封装路径（[AudioTuning.ADTS]）。
         */
        fun adtsHeader(
            payloadSize: Int,
            sampleRate: Int,
            channels: Int,
        ): ByteArray {
            val frameLength = payloadSize + ADTS_HEADER_SIZE
            val freqIndex = frequencyIndex(sampleRate)
            val header = ByteArray(ADTS_HEADER_SIZE)
            header[0] = 0xFF.toByte()
            // syncword 剩余 4 位 + MPEG-4(0) + layer(00) + protection_absent(1)
            header[1] = 0xF1.toByte()
            // profile=LC(2) 存的是 AOT-1；采样率索引 <<2；声道配置高 2 位
            header[2] =
                (
                    ((AAC_PROFILE_LC - 1) shl 6) or
                        ((freqIndex and 0x0F) shl 2) or
                        ((channels shr 2) and 0x03)
                ).toByte()
            // 声道配置低 2 位 + 帧长高 2 位
            header[3] = (((channels and 0x03) shl 6) or ((frameLength shr 11) and 0x03)).toByte()
            header[4] = ((frameLength shr 3) and 0xFF).toByte()
            // 帧长低 3 位 + buffer_fullness=0x7FF（VBR 惯例）+ 单帧（低 2 位 0）
            header[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
            header[6] = 0xFC.toByte()
            return header
        }

        /** 采样率 → ADTS 的 samplingFrequencyIndex。 */
        fun frequencyIndex(rate: Int): Int =
            when (rate) {
                96_000 -> 0
                88_200 -> 1
                64_000 -> 2
                48_000 -> 3
                44_100 -> 4
                32_000 -> 5
                24_000 -> 6
                22_050 -> 7
                16_000 -> 8
                12_000 -> 9
                11_025 -> 10
                8_000 -> 11
                7_350 -> 12
                else -> 4
            }

        /**
         * 自检：用平台解码器把一帧裸 AAC 解回 PCM，返回 `(峰值, 样本数)`；解不出返回 null。
         *
         * 为什么需要（2026-10-20 第三次实测）：车机「收包但不发声」时，最容易搞错的是
         * 归因——是编码器吐了坏流/静音，还是车机侧没播。本机解码一遍就能证伪：
         * 峰值 > 0 说明比特流有效且非静音，问题必在车机侧；峰值 = 0 说明编码器在出静音。
         *
         * 注意：这里**绝不能**对帧做任何字节换序（旧实现把所有负载统一按大端 s16 换序，
         * 连 AAC 比特流也换掉了——车机解码器直接判坏帧，这就是「有包无声」的根因）。
         */
        fun decodePeak(
            frame: ByteArray,
            sampleRate: Int,
            channels: Int,
        ): Pair<Int, Int>? {
            var created: MediaCodec? = null
            try {
                val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels)
                val codec = MediaCodec.createDecoderByType(MIME)
                created = codec
                codec.configure(format, null, null, 0)
                codec.start()
                val inputIndex = codec.dequeueInputBuffer(200_000L)
                if (inputIndex < 0) return null
                val input = codec.getInputBuffer(inputIndex) ?: return null
                input.clear()
                input.put(frame)
                codec.queueInputBuffer(inputIndex, 0, frame.size, 0L, 0)
                val info = MediaCodec.BufferInfo()
                val deadline = System.nanoTime() + 600_000_000L
                while (System.nanoTime() < deadline) {
                    val outputIndex = codec.dequeueOutputBuffer(info, 60_000L)
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER ||
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                        outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED
                    ) {
                        continue
                    }
                    if (outputIndex < 0) continue
                    val output = codec.getOutputBuffer(outputIndex)
                    var peak = 0
                    var samples = 0
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        while (output.remaining() >= 2) {
                            val value = output.short.toInt()
                            val magnitude = if (value < 0) -value else value
                            if (magnitude > peak) peak = magnitude
                            samples++
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (samples > 0) return peak to samples
                }
                return null
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { created?.stop() }
                runCatching { created?.release() }
            }
        }
    }

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationUs = 0L

    /**
     * [encode] 与 [stop] 的互斥闸门。
     *
     * 2026-11-01 实测（小米 → 奥迪 MIB3，会话收尾）：采集线程/直通注入线程还在喂帧，
     * 另一条收尾路径已经 `stop()` → 下一次 `dequeueInputBuffer` 抛
     * `Invalid to call during stop(); only valid in executing state`。
     * 只加一个「已停止」标志挡不住**正在执行**的那一次调用（它已经拿到了 codec 引用），
     * 必须让 stop 等它走完；反之 encode 也不该踩到已 release 的 codec。
     */
    private val lock = Any()

    /** stop() 之后仍到达的 encode 次数（竞态被丢弃的帧，收尾时汇总一行，不逐帧刷屏）。 */
    private var droppedAfterStop = 0L

    /** 编码器输入缓冲满 / 拿不到输入缓冲导致丢弃的 PCM 帧数（限频提示）。 */
    private var droppedInputBusy = 0L

    /** ADTS 剥离只提示一次，避免刷屏。 */
    private var adtsStripped = false

    /** ADTS 封装提示只打一次。 */
    private var adtsWrapped = false

    /** 首帧十六进制只打一次。 */
    private var firstFrameLogged = false

    /** 每帧 PCM 输入字节数。 */
    val inputFrameBytes: Int = pcmBytes(channels)

    var started: Boolean = false
        private set

    fun start(): Boolean =
        synchronized(lock) {
            if (started) return true
            try {
                val format =
                    MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
                        setInteger(
                            MediaFormat.KEY_AAC_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                        )
                        setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputFrameBytes)
                    }
                val created = MediaCodec.createEncoderByType(MIME)
                created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                created.start()
                codec = created
                presentationUs = 0L
                started = true
                log(
                    "AUDIO: AAC-LC 编码器就绪（${sampleRate}Hz ${channels}ch " +
                        "${bitRate / 1000}kbps，帧=${FRAME_SAMPLES}采样/${inputFrameBytes}B）",
                )
                true
            } catch (error: Exception) {
                log("AUDIO: AAC-LC 编码器启动失败 - ${error.message}（本档位放弃）")
                started = false
                false
            }
        }

    /**
     * 喂一帧 PCM（[inputFrameBytes] 字节），返回本次编出的全部 AAC 帧。
     *
     * 编码器内部有延迟（首帧通常要喂 1~2 帧才吐第一包），所以返回值可能为空，
     * 调用方按「拿到的帧逐个发包」处理即可。
     *
     * **已停止时返回空表**：收尾阶段（会话断开、重连、手动断开）采集线程与注入线程
     * 可能比 [stop] 晚一拍到达，此时绝不能再去碰已 release 的 codec——2026-11-01
     * 日志里那条 `Invalid to call during stop()` 就是这条路走出来的。
     */
    fun encode(
        pcm: ByteArray,
        size: Int,
    ): List<ByteArray> =
        synchronized(lock) {
            val encoder = codec
            if (encoder == null || !started) {
                droppedAfterStop++
                return emptyList()
            }
            val output = mutableListOf<ByteArray>()
            try {
                val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex < 0) {
                    // 编码器 10ms 内都没空出输入槽（瞬时过载 / 车机侧卡顿）：这一帧
                    // PCM 只能丢。旧实现连计数都没有，日志里表现为「音频好好的突然
                    // 卡了一下」，无从判断是解码器慢还是采集侧丢帧。
                    droppedInputBusy++
                    if (droppedInputBusy == 1L || droppedInputBusy % 200L == 0L) {
                        log("AUDIO: 编码器输入缓冲满——已丢 $droppedInputBusy 帧 PCM（不影响会话）")
                    }
                } else {
                    val buffer = encoder.getInputBuffer(inputIndex)
                    // 送进 codec 的字节数必须与 put 进去的一致：`put` 会被 capacity
                    // 截断，而 queueInputBuffer 若仍按原始 size 记账，codec 会去读
                    // 没写过的区域（个别 ROM 直接抛 IllegalArgumentException）。
                    val length = size.coerceAtMost(buffer?.capacity() ?: 0).coerceAtMost(pcm.size)
                    if (buffer == null || length <= 0) {
                        // 拿不到输入缓冲：不排队空 buffer（那会被当成一帧空音频）
                        encoder.queueInputBuffer(inputIndex, 0, 0, presentationUs, 0)
                        droppedInputBusy++
                    } else {
                        buffer.clear()
                        buffer.put(pcm, 0, length)
                        encoder.queueInputBuffer(inputIndex, 0, length, presentationUs, 0)
                        presentationUs += FRAME_SAMPLES * 1_000_000L / sampleRate
                    }
                }
                drain(encoder, output)
            } catch (error: IllegalStateException) {
                // 兜底：codec 在别处被停/释放（理论上 lock 已排掉，这里防 ROM 的意外行为）。
                // 不是「异常」，是一次可预期的收尾竞态，记一行即可，别继续喂。
                codec = null
                started = false
                log("AUDIO: AAC 编码器已释放，停止编码（${error.message}）——收尾竞态，已忽略")
            } catch (error: Exception) {
                log("AUDIO: AAC 编码异常 - ${error.message}")
            }
            output
        }

    private fun drain(
        encoder: MediaCodec,
        output: MutableList<ByteArray>,
    ) {
        while (true) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index < 0 -> return
                else -> {
                    val buffer = encoder.getOutputBuffer(index)
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (buffer != null && bufferInfo.size > 0 && !isConfig) {
                        val data = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer.get(data)
                        output += finalizeFrame(data)
                    }
                    encoder.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    /**
     * 去掉 ADTS 头（若存在）。
     *
     * 线上格式要求是**裸 AAC 帧**（catplay `rtp_recorder` 直接把编码器输出当负载）。
     * 多数 MediaCodec 实现输出裸帧，但个别硬件编码器会带 7/9 字节 ADTS 头——带上就是
     * 非法负载，车机解码器会整条流丢弃。这里按同步字识别并剥掉，属于防御性处理。
     */
    private fun stripAdts(frame: ByteArray): ByteArray {
        if (frame.size < 7) return frame
        val b0 = frame[0].toInt() and 0xFF
        val b1 = frame[1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) return frame
        // ADTS 的 protection_absent 位在第二个字节最低位：1=无 CRC（7 字节头），0=9 字节
        val headerSize = if ((b1 and 0x01) == 0x01) 7 else 9
        if (frame.size <= headerSize) return frame
        if (!adtsStripped) {
            adtsStripped = true
            log("AUDIO: 编码器输出带 ADTS 头（${headerSize}B）——已剥离，改发裸 AAC 帧")
        }
        return frame.copyOfRange(headerSize, frame.size)
    }

    /**
     * 按当前方案决定线上负载形态：裸帧（默认）或「ADTS 头 + 裸帧」。
     *
     * 先统一剥掉编码器可能自带的 ADTS（避免重复套头），再按方案决定是否重建。
     */
    private fun finalizeFrame(frame: ByteArray): ByteArray {
        val raw = stripAdts(frame)
        if (!adtsWrapped && wrapAdts) {
            adtsWrapped = true
            log("AUDIO: 已改为 ADTS 封装（每帧补 7 字节头，方案2）")
        }
        val out = if (wrapAdts) buildAdts(raw) else raw
        // 首帧留档：出问题时能直接从日志里核对负载是不是合法 AAC（而不是等用户导文件）
        if (!firstFrameLogged) {
            firstFrameLogged = true
            val head = out.take(16).joinToString(" ") { "%02X".format(it) }
            log(
                "AUDIO: 首帧 ${out.size}B（裸帧 ${raw.size}B）head=$head" +
                    (if (wrapAdts) "（ADTS，sync=FFF）" else ""),
            )
        }
        return out
    }

    /** 组 7 字节 ADTS 头（无 CRC）：MPEG-4 / LC / 对应采样率与声道。 */
    private fun buildAdts(raw: ByteArray): ByteArray = adtsHeader(raw.size, sampleRate, channels) + raw

    /** 停止并释放编码器（幂等）。与 [encode] 互斥，停止后到达的帧静默丢弃。 */
    fun stop() {
        synchronized(lock) {
            val encoder = codec
            codec = null
            started = false
            if (encoder != null) {
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
            }
            if (droppedAfterStop > 0) {
                log("AUDIO: 编码器已停止，丢弃停止后到达的 $droppedAfterStop 帧（收尾竞态，已忽略）")
                droppedAfterStop = 0
            }
            if (droppedInputBusy > 0) {
                log("AUDIO: 本轮编码器输入缓冲满共丢 $droppedInputBusy 帧 PCM（过载丢帧，会话未中断）")
                droppedInputBusy = 0
            }
        }
    }
}
