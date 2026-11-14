package io.github.shaonialife.carptocar.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import io.github.shaonialife.carptocar.airplay.TimingServer
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay 主音频发送端（手机 → 车机，RTP over UDP）。
 *
 * 线格式（对照 DiPlay `AudioStream.kt` 解密路径与 catplay `rtp_cipher_chacha.rs`）：
 * ```
 * [RTP 头 12B][ChaCha20-Poly1305 密封的 PCM 负载 + 16B tag][8B LE nonce]
 * ```
 *  - 加密：ChaCha20-Poly1305；AAD = 头 4..12 字节（timestamp+ssrc 的 BE 表示，
 *    catplay 的 "modern AAD"；DiPlay 解密即取同一范围）
 *  - nonce（12B）= 4 字节零 + 尾 8 字节（小端计数，从 0 逐包递增）→ [AirPlayCrypto.nonce64]
 *  - RTP 字段：version=2、PT=流类型（100=PCM 通路 / 102=AAC-LC 通路；
 *    对照 catplay `payload_type = stream_type`）、
 *    SSRC=0（catplay 同款）、timestamp = 累计样本帧数、seq 逐包递增
 *  - **首个音频包置 Marker 位**（规范：RECORD/FLUSH 之后的首包要打标记，接收端据此
 *    切分播放边界；catplay 恒 false，但我们的会话是「RECORD 之后才加入音频流」，
 *    首包标记能帮接收端重新对齐）
 *
 * 旁路：
 *  - **同步包**：[AudioSyncPacket] 每 1 秒发往该流的 `controlPort`，建立 RTP↔NTP 锚点。
 *    （缺了它接收端只能「按到达时刻猜」，严格的实现干脆不排播——2026-10-20 实测）
 *
 * 采集：`AudioPlaybackCapture`（Android 10+）捕获手机端其他应用播放的媒体/导航音频
 * （需要 MediaProjection——与镜像模式的同一授权）。每包 [SAMPLES_PER_PACKET] 样本帧
 * （48000Hz 立体声 16bit = 2048B 负载，总包 ~2084B，不超接收端 4096B 收包缓冲）。
 */
class AudioSender(
    private val host: String,
    private val dataPort: Int,
    private val streamKey: ByteArray,
    private val log: (String) -> Unit,
    /** 静音手机媒体音量（声音只从车机出）。null = 不做（测试用）。 */
    private val context: android.content.Context? = null,
    /**
     * 采集采样率（Hz）：必须与 SETUP 协商出的格式一致（48k 主选，车机只支持
     * 44.1k 时由 AirPlayClient 协商下发）。默认 48k。
     */
    private val sampleRate: Int = 48_000,
    /** 负载编码：PCM（type=100 通路）或 AAC-LC（type=102 通路）。 */
    private val codec: AudioCodec = AudioCodec.PCM,
    /** RTP 流类型，仅用于日志（PT 与 SETUP 的 type 保持一致）。 */
    private val streamType: Long = 100L,
    /** AAC 帧是否补 ADTS 头（见 [AudioTuning.ADTS]，排查车机解码链用）。 */
    private val adtsFrames: Boolean = false,
    /**
     * 该音频流的控制端口（车机在 SETUP 应答里下发）。>0 时每 [SYNC_INTERVAL_MILLIS]
     * 发一个 [AudioSyncPacket] 建立 RTP↔NTP 锚点；0 = 车机没给（旧车机），只发音频。
     */
    private val controlPort: Int = 0,
) {
    companion object {
        const val CHANNELS = 2

        /**
         * 每包样本帧数 = AirPlay 标准 SPF 352（catplay `MAX_FRAMES_PER_PACKET_PCM`，
         * 真车验证）。48000/352 ≈ 136 包/秒（7.33ms/包）。
         * 2026-10-17 实测：此前用 512 帧（10.67ms/包），奥迪接收端无声音——
         * 与真 iPhone 的包长不一致时接收端可能按固定 SPF 缓冲而丢弃。
         */
        const val SAMPLES_PER_PACKET = 352

        /** 一包负载字节数（16bit 立体声）。 */
        const val PACKET_BYTES = SAMPLES_PER_PACKET * CHANNELS * 2

        /** 同步包发送间隔（规范：每秒一个）。 */
        const val SYNC_INTERVAL_MILLIS = 1_000L

        /** 「音频链路体检」节拍（30 秒一行；窗口太短会被单帧抖动带偏）。 */
        private const val HEALTH_INTERVAL_MILLIS = 30_000L

        /** 静音保活的检查节拍。 */
        private const val KEEPALIVE_TICK_MILLIS = 100L

        /**
         * 多久没有真实 PCM 就开始补静音帧。
         *
         * 取 300ms 而不是 0：正常音乐播放时 7~23ms 一帧，压根不会触发；而「暂停/切歌/
         * 导航播报间隙」这种短暂停顿也不至于插静音。真正要救的是「连接后长时间没人
         * 放音乐」这种一分钟级的空转。
         */
        private const val IDLE_FILL_AFTER_MILLIS = 300L

        /**
         * 单拍最多补几帧（≈[KEEPALIVE_TICK_MILLIS]/帧长，留一点追赶余量，避免卡顿后猛灌）。
         *
         * 16 帧 ≈ 117ms > 一拍 100ms：48k/352 样本一帧 7.33ms，一拍需要 13.6 帧，
         * 卡在 12 会让补帧永远比实时慢一点，时间戳逐步落后（引擎报停后立即补帧时更明显）。
         */
        private const val MAX_KEEPALIVE_FRAMES_PER_TICK = 16

        /** 一次 `sendSyncOnce` 内最多尝试次数（ICMP 端口不可达时就地补发一次）。 */
        private const val SYNC_SEND_ATTEMPTS = 2

        /**
         * 是否是「车机 controlPort 没监听」引起的 ICMP 端口不可达。
         *
         * Android 抛的是 `SocketException: sendto failed: ECONNREFUSED (Connection refused)`，
         * 部分实现抛 [java.net.PortUnreachableException]，两种都算。这类错误对连接式 UDP
         * **是延后一拍上报的**（本次 send 抛出，说的其实是上一发），所以不能当链路故障。
         */
        internal fun isSyncIcmpRejection(error: Exception): Boolean {
            if (error is java.net.PortUnreachableException) return true
            val message = error.message?.uppercase() ?: return false
            return "ECONNREFUSED" in message || "PORT UNREACHABLE" in message
        }

        private const val RTP_HEADER_LEN = 12
        private const val TAG_SIZE = 16
        private const val NONCE_SIZE = 8

        private const val READ_TIMEOUT_QUIET_MILLIS = 500L

        /** 静音前媒体音量的持久化键（进程被杀也不丢恢复目标）。 */
        private const val KEY_SAVED_VOLUME = "audio_saved_media_volume"

        /** 小端 s16 → 大端 s16（每两字节换序）。CarPlay 线格式要求大端样本。 */
        fun bigEndianS16(source: ByteArray): ByteArray {
            val out = source.copyOf()
            var index = 0
            while (index + 1 < out.size) {
                val tmp = out[index]
                out[index] = out[index + 1]
                out[index + 1] = tmp
                index += 2
            }
            return out
        }
    }

    private val running = AtomicBoolean(false)

    /** stop() 幂等闸门（多条收尾路径都会调 stop）。 */
    private val stopped = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var syncSocket: DatagramSocket? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var syncThread: Thread? = null
    private var healthThread: Thread? = null

    /** 同步包序号（跨重试连续，避免车机把重发当成乱序丢掉）。 */
    private var syncSequence = 0

    /** 同步包连续失败次数（诊断 + 决定何时重开套接字）。 */
    private var syncFailures = 0

    /** 成功发出的同步包数（收尾汇总用）。 */
    @Volatile private var syncSent = 0L

    /** 被车机 ICMP 端口不可达拒掉、已就地补发成功的次数（= 车机没监听 controlPort）。 */
    @Volatile private var syncIcmpDeferrals = 0L

    /** 首个同步包（X 位=1）是否已成功发出。 */
    private var syncFirstSent = false

    /** 上次「链路体检」的时刻与计数快照（算窗口增量，判据必须看增量而不是累计）。 */
    private var healthAtNanos = 0L
    private var healthSyncSent = 0L
    private var healthIcmpSent = 0L
    private var healthPackets = 0L
    private var healthSamples = 0L

    /** AAC-LC 编码器（仅 [AudioCodec.AAC_LC] 时创建）。 */
    private var aacEncoder: AacLcEncoder? = null

    /**
     * RTP 游标（原本是采集循环里的局部变量）。提到字段上是因为**同步包线程**要读
     * 「当前/下一包时间戳」，否则锚点里的时间戳永远对不上真实发出的包。
     */
    @Volatile private var sequence = 0

    @Volatile private var timestamp = 0L

    @Volatile private var nonceCounter = 0L

    /** 首个音频包标记（打完就清）；AAC 自检只跑一次。 */
    private var markerPending = true
    private var selfChecked = false

    /** 每包/每帧对应的 PCM 字节数（PCM 通路=一包的负载；AAC 通路=一帧的输入）。 */
    private val framePcmBytes: Int
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.pcmBytes(CHANNELS) else PACKET_BYTES

    /** 每包推进的样本数（AAC 一帧 1024，PCM 一包 352）。 */
    private val samplesPerPacket: Long
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.FRAME_SAMPLES.toLong() else SAMPLES_PER_PACKET.toLong()

    /**
     * RTP SSRC：固定 0（catplay `prepare_header` 在真车上即用 0；DiPlay 收端不校验）。
     * 2026-10-17 实测：此前用随机值，奥迪接收端无声音——部分车机按 SSRC 关联 SETUP
     * 声明的流（非 0 视为未知流丢弃）。
     */
    private val ssrc = 0

    /** 采集+推流统计（日志限流用）。 */
    private var packetsSent = 0L
    private var bytesSent = 0L
    private var lastLogAt = 0L
    private var readFailures = 0

    val isRunning: Boolean get() = running.get()

    /** 静音前的手机媒体音量 index（-1 = 未静音）。 */
    private var mutedOriginalVolume = -1

    /**
     * 静音手机媒体输出：音频通道激活后声音只从车机出（真 CarPlay 行为）。
     * 依据（实测 2026-10-10）：播放捕获取自 REMOTE_SUBMIX（音量应用点之前），
     * 手机流音量静音**不影响捕获数据**——手机不响、车机照常出声。
     *
     * 恢复值持久化（SharedPreferences）：进程被系统杀死/安装更新时来不及恢复，
     * 下次静音若读到 0 会把它当"原音量"，导致断开后永久静音（2026-10-10 踩过）。
     */
    private fun mutePhoneMedia() {
        val ctx = context ?: return
        try {
            val manager =
                ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager
            if (mutedOriginalVolume >= 0) return
            val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            val saved = prefs.getInt(KEY_SAVED_VOLUME, -1)
            val current = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            // 目标恢复值：优先上次未恢复成功的保存值；否则当前值（>0 才可信）
            val restoreTarget =
                when {
                    saved > 0 -> saved
                    current > 0 -> current
                    else -> -1
                }
            if (restoreTarget > 0) {
                prefs.edit().putInt(KEY_SAVED_VOLUME, restoreTarget).apply()
            }
            mutedOriginalVolume = restoreTarget
            manager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
            // 回读校验：ROM 的音频策略可能拒绝/立刻回弹（实测见过 volume 仍为原值），
            // 此时手机继续外放——必须让日志能直接看出来
            val after = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            log(
                "AUDIO: 已静音手机媒体输出（原音量 ${if (restoreTarget > 0) restoreTarget else "未知"}，" +
                    "断开后恢复）——回读音量=$after" +
                    if (after != 0) "（⚠ 静音未生效，手机仍会外放）" else "",
            )
        } catch (error: Exception) {
            log("AUDIO: 静音手机媒体失败 - ${error.message}")
        }
    }

    private fun restorePhoneMedia() {
        val ctx = context ?: return
        try {
            val manager =
                ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager
            val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            val target =
                when {
                    mutedOriginalVolume > 0 -> mutedOriginalVolume
                    else -> prefs.getInt(KEY_SAVED_VOLUME, -1)
                }
            if (target > 0) {
                manager.setStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    target,
                    0,
                )
                // 回读校验：`setStreamVolume` 被 ROM/勿扰拒绝时**不抛异常**、音量仍是 0。
                // 旧实现在这里无条件写「已恢复」并删掉持久化备份 —— 手机就永久停在静音，
                // 且日志完全看不出失败（下一次静音时 saved 与 current 两个判据双双失效）。
                val after = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                if (after == target) {
                    log("AUDIO: 已恢复手机媒体音量（$target）")
                    prefs.edit().remove(KEY_SAVED_VOLUME).apply()
                } else {
                    // 保留备份：下一轮恢复还会再试，用户手动调音量也能被 saved 兜住。
                    log(
                        "AUDIO: ⚠ 恢复手机媒体音量未生效（目标 $target，回读 $after）——" +
                            "备份已保留，请手动按音量键调整",
                    )
                }
            } else {
                log("AUDIO: 无有效原音量记录，手机媒体保持当前值（请按音量键调整）")
                prefs.edit().remove(KEY_SAVED_VOLUME).apply()
            }
        } catch (error: Exception) {
            log("AUDIO: 恢复手机媒体音量失败 - ${error.message}")
        }
        mutedOriginalVolume = -1
    }

    /**
     * 启动音频发送。需要 MediaProjection（AudioPlaybackCapture 的授权来源）。
     * 返回是否成功进入采集循环。
     */
    fun start(projection: MediaProjection): Boolean {
        if (running.get()) return true
        stopped.set(false)
        return try {
            val client = DatagramSocket()
            client.connect(InetAddress.getByName(host), dataPort)
            socket = client

            // AAC 通路先把编码器立起来：编不出来就没必要采集（也避免「有包无声」的假象）
            if (codec == AudioCodec.AAC_LC) {
                val encoder = AacLcEncoder(sampleRate, CHANNELS, wrapAdts = adtsFrames, log = log)
                if (!encoder.start()) {
                    client.close()
                    socket = null
                    return false
                }
                aacEncoder = encoder
            }

            val captureConfig = buildCaptureConfig(projection)
            val audioFormat =
                AudioFormat
                    .Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
            val recorder =
                AudioRecord
                    .Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(framePcmBytes * 8)
                    .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                log("AUDIO: AudioRecord 初始化失败（state=${recorder.state}）")
                runCatching { recorder.release() }
                aacEncoder?.stop()
                aacEncoder = null
                client.close()
                socket = null
                return false
            }
            record = recorder
            running.set(true)
            mutePhoneMedia()
            log(
                "AUDIO: 已启动（播放捕获 ${sampleRate}Hz 立体声 → $host:$dataPort，" +
                    "通路=t$streamType/${codec.name}，" +
                    if (codec == AudioCodec.AAC_LC) {
                        "每包 1 个 AAC 帧（${AacLcEncoder.FRAME_SAMPLES} 采样" +
                            (if (adtsFrames) "，ADTS 封装））" else "，裸帧））")
                    } else {
                        "每包 ${SAMPLES_PER_PACKET} 帧 PCM）"
                    },
            )

            // 同步包线程先起：锚点越早建立，接收端越早能排播
            startSyncLoop()

            Thread(
                {
                    try {
                        recorder.startRecording()
                    } catch (error: Exception) {
                        log("AUDIO: 启动采集失败 - ${error.message}")
                        stop()
                        return@Thread
                    }
                    runCaptureLoop()
                },
                "audio-capture",
            ).apply {
                isDaemon = true
                start()
                thread = this
            }
            true
        } catch (error: Exception) {
            log("AUDIO: 启动失败 - ${error.message}")
            stop()
            false
        }
    }

    /**
     * 启动**直通模式**音频发送（无需 MediaProjection / 播放捕获授权）。
     *
     * 音频数据由外部通过 [feedPcm] 注入（如 CarLife 引擎的裸 PCM），这里只负责
     * 封包（RTP + ChaCha20 密封）与发送——彻底绕开「本机播放 + 播放捕获」中转，
     * 因此不依赖录屏授权，也少一次系统内混音/重采样往返。
     *
     * 采样率/声道对齐由注入方负责（[feedPcm] 会按 [sampleRate]/[CHANNELS] 重采样），
     * 但 AAC 通路仍走 [AacLcEncoder]（注入的是 PCM，编码照常）。
     */
    fun startPassthrough(): Boolean {
        if (running.get()) return true
        stopped.set(false)
        return try {
            val client = DatagramSocket()
            client.connect(InetAddress.getByName(host), dataPort)
            socket = client

            if (codec == AudioCodec.AAC_LC) {
                val encoder = AacLcEncoder(sampleRate, CHANNELS, wrapAdts = adtsFrames, log = log)
                if (!encoder.start()) {
                    client.close()
                    socket = null
                    return false
                }
                aacEncoder = encoder
            }

            running.set(true)
            // 直通模式不静音手机：音频来源是 CarLife 引擎（它已自行采集并推 PCM），
            // 静音 STREAM_MUSIC 可能破坏引擎的采集源（采集点不确定，不能赌是 REMOTE_SUBMIX）。
            log(
                "AUDIO: 已启动直通模式（外部注入 PCM → $host:$dataPort，" +
                    "通路=t$streamType/${codec.name}，目标 ${sampleRate}Hz 立体声）",
            )
            startSyncLoop()
            startSilenceKeepAlive()
            true
        } catch (error: Exception) {
            log("AUDIO: 直通启动失败 - ${error.message}")
            stop()
            false
        }
    }

    /** 直通模式下的帧对齐累积缓冲（把任意大小的注入 PCM 切成固定帧长）。 */
    private val passthroughBuffer = java.io.ByteArrayOutputStream()

    /** 有状态重采样器（相位跨块延续，保证 44.1k→48k 的长期比值精确）。 */
    private val pcmResampler = PcmResampler()

    /** 重采样器当前认定的输入采样率（变了就清相位，0 = 还没用过）。 */
    private var resampleInRate = 0

    /** 最近一次收到**真实** PCM 的时刻（uptimeMillis）；静音保活据此判断要不要补帧。 */
    @Volatile
    private var lastRealFeedAt = 0L

    /**
     * 「多久没有真实 PCM 就补静音」的当前阈值（默认 [IDLE_FILL_AFTER_MILLIS]）。
     *
     * 引擎报「音频停」时由 [beginIdleFill] 临时置 0——**立即**开始补，目的是让 RTP 时间戳
     * 连续，车机的 jitter buffer 不排空（微信提示音、来电那种 300ms 级中断，就是因为
     * 前 300ms 没有包，恢复瞬间要重新缓冲，听感是「卡一下」）。
     */
    @Volatile
    private var idleFillAfterMillis = IDLE_FILL_AFTER_MILLIS

    private var keepAliveThread: Thread? = null

    /**
     * 静音保活：音频流建立后**不能长时间一个包都不发**。
     *
     * 2026-11-04 奥迪 MIB3 实车（`docs/audi-mib3-ea-tunnel-20261104.md`）：音频流 SETUP
     * 完成后先是 65 秒完全没有 PCM（车机上没人放音乐），第一段音乐（08:27:39，44.1k
     * 立体声、电平 99%）原样编码发出去了，车机一声不响；而同样的音频在重连后的会话里
     * （流一建立 0.4 秒就有包）立刻正常。差别只有「流建立后先静默了多久」。
     *
     * 真 iPhone 的音频流是**连续**的：没声音也一直送静音帧。接收端据此维持解码/播放管线
     * 与 RTP↔NTP 时钟；停一分钟再突然来音频，管线早已被判空闲，包照收不放。
     *
     * 所以这里在「超过 [IDLE_FILL_AFTER_MILLIS] 没有任何真实 PCM」时按实时速率补静音帧：
     * 作用只是维持管线与时钟，用户听不到任何声音（AAC 静音帧十几字节，开销可忽略）。
     * 真实 PCM 一到就立刻停补，避免在音乐中间插入静音帧。
     */
    private fun startSilenceKeepAlive() {
        if (keepAliveThread != null) return
        lastRealFeedAt = android.os.SystemClock.uptimeMillis()
        val frameBytes = framePcmBytes
        val frameMillis = 1000.0 * samplesPerPacket / sampleRate
        val silence = ByteArray(frameBytes)
        val thread =
            Thread(
                {
                    var nextFillAt = 0L
                    var announced = false
                    while (running.get()) {
                        try {
                            Thread.sleep(KEEPALIVE_TICK_MILLIS)
                        } catch (_: InterruptedException) {
                            break
                        }
                        if (!running.get()) break
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastRealFeedAt < idleFillAfterMillis) {
                            nextFillAt = 0L
                            continue
                        }
                        if (nextFillAt == 0L) {
                            nextFillAt = now
                            if (!announced) {
                                announced = true
                                log(
                                    // 阈值可能是 300ms（常态）或 0（引擎报停后的立即补帧）
                                    "AUDIO: 无 PCM 超过 ${idleFillAfterMillis}ms——开始补静音帧保活" +
                                        "（避免车机把音频流判空闲，之后第一段音乐被丢）",
                                )
                            }
                        }
                        var guard = 0
                        while (running.get() &&
                            nextFillAt <= now &&
                            guard < MAX_KEEPALIVE_FRAMES_PER_TICK
                        ) {
                            if (android.os.SystemClock.uptimeMillis() - lastRealFeedAt <
                                idleFillAfterMillis
                            ) {
                                break // 真实 PCM 回来了，本拍不再补
                            }
                            dispatchPcm(silence)
                            nextFillAt += frameMillis.toLong().coerceAtLeast(1L)
                            guard++
                        }
                        // 落后太多就不追了：补的静音没必要和历史对齐，重新从当前时刻起算
                        if (nextFillAt < now) nextFillAt = now
                    }
                },
                "audio-silence-keepalive",
            )
        thread.isDaemon = true
        keepAliveThread = thread
        thread.start()
    }

    /**
     * 引擎报「音频停」时调用：**立即**开始补静音帧（把 300ms 阈值临时压到 0）。
     *
     * 为什么需要（2026-11-09 实车日志）：微信提示音到达时 CarLife 引擎发 `0x30003`
     * 停掉媒体通路，311ms 后才 `0x30002` 恢复。这段时间**一个包都不发**，车机的
     * jitter buffer 被排空，恢复瞬间要重新缓冲——听感就是「音乐卡一下」。
     * 补静音让 RTP 时间戳保持连续、buffer 不排空，恢复时无缝接上；用户听到的是
     * 311ms 静音（协议内必然），但不再有「卡」的那一下。
     *
     * 真实 PCM 一到，补静音会自动让位（[feedPcm] 更新 [lastRealFeedAt]）。
     */
    fun beginIdleFill(reason: String) {
        if (!running.get()) return
        if (idleFillAfterMillis == 0L) return
        idleFillAfterMillis = 0L
        log("AUDIO: $reason → 立即补静音帧保持 RTP 连续（车机缓冲不排空，恢复不再卡一下）")
    }

    /** 引擎音频恢复：补静音回到常态阈值（300ms），避免正常播放间隙插入静音。 */
    fun endIdleFill() {
        if (idleFillAfterMillis == IDLE_FILL_AFTER_MILLIS) return
        idleFillAfterMillis = IDLE_FILL_AFTER_MILLIS
        log("AUDIO: 引擎音频恢复 → 补静音回到 ${IDLE_FILL_AFTER_MILLIS}ms 阈值")
    }

    /**
     * 直通模式下由外部注入一段 PCM（16bit 交织）。
     *
     * 会自动把任意采样率/声道数的输入重采样到 [sampleRate]/[CHANNELS]（立体声），
     * 再按固定帧长（PCM 通路=每包 [SAMPLES_PER_PACKET] 采样、AAC 通路=每帧
     * [AacLcEncoder.FRAME_SAMPLES] 采样）累积切帧后走封包/编码/发送。非直通模式
     * （采集循环在跑）时忽略——两条路径不该同时喂数据。
     *
     * @param srcPcm   输入 PCM（16bit 小端交织）
     * @param size     有效字节数
     * @param srcRate  输入采样率（<=0 时按 [sampleRate] 处理，不重采样）
     * @param srcChannels 输入声道数（1 或 2；其它按 2 处理）
     */
    /**
     * 外部注入 PCM（CarLife 直通）。
     *
     * **必须同步**：媒体（9240）与 TTS（9241）是两条独立读线程，2026-10-27 接入导航播报
     * 后两路会同时进来；[passthroughBuffer] 是非线程安全的 ByteArrayOutputStream，
     * 不加锁会出现半帧交错（爆音/破音）甚至丢数据。
     */
    @Synchronized
    fun feedPcm(
        srcPcm: ByteArray,
        size: Int,
        srcRate: Int = 0,
        srcChannels: Int = 2,
    ) {
        if (!running.get() || record != null) return // 采集模式在跑时不接受注入
        if (size <= 0) return
        // 真实 PCM 到场：静音保活立刻让位（这一行必须在 alignPcm 之前，
        // 否则重采样失败时空转判定会误以为「一直没声音」）
        lastRealFeedAt = android.os.SystemClock.uptimeMillis()
        val resampled = alignPcm(srcPcm, size, srcRate, srcChannels) ?: return
        // 累积到固定帧长，凑满一帧才发（PCM 通路逐包、AAC 通路逐帧）
        val frameBytes = framePcmBytes
        passthroughBuffer.write(resampled, 0, resampled.size)
        while (passthroughBuffer.size() >= frameBytes) {
            val frame = passthroughBuffer.toByteArray().copyOf(frameBytes)
            val rest = passthroughBuffer.toByteArray()
            passthroughBuffer.reset()
            passthroughBuffer.write(rest, frameBytes, rest.size - frameBytes)
            dispatchPcm(frame)
        }
    }

    /**
     * 把一段 PCM 重采样到 [sampleRate] 立体声，返回新数组；无需处理时返回原输入。
     *
     * 44.1k↔48k 这类小比值交给 [PcmResampler]（线性插值 + 相位跨块延续），不引第三方库：
     * CarLife 引擎实际只会输出 44.1k 或 48k。任意其它比值同一套逻辑也成立，不会中断推流。
     */
    private fun alignPcm(
        src: ByteArray,
        size: Int,
        srcRate: Int,
        srcChannels: Int,
    ): ByteArray {
        val inRate = if (srcRate > 0) srcRate else sampleRate
        val inCh = if (srcChannels == 1) 1 else 2

        // 先处理声道：单声道 → 立体声（复制到左右）
        var rateAligned: ByteArray
        var rateChannels = inCh
        if (inCh == 1 && CHANNELS == 2) {
            val monoFrames = size / 2
            val out = ByteArray(monoFrames * 4)
            var i = 0
            var o = 0
            while (i + 1 < size) {
                val lo = src[i]
                val hi = src[i + 1]
                out[o] = lo
                out[o + 1] = hi
                out[o + 2] = lo
                out[o + 3] = hi
                i += 2
                o += 4
            }
            rateAligned = out
            rateChannels = 2
        } else {
            rateAligned = if (size == src.size) src else src.copyOf(size)
        }

        // 采样率一致直接返回（顺带清相位：万一中途换过源采样率，别拿上一段的尾巴去插值）
        if (inRate == sampleRate) {
            pcmResampler.reset()
            return rateAligned
        }

        // 有状态线性重采样：相位与末帧跨块延续（见 [PcmResampler] 的文件头注释——
        // 旧实现每块独立截断，实测长期少 0.05% 样本，25 分钟欠 0.7 秒）。
        if (inRate != resampleInRate) {
            pcmResampler.reset()
            resampleInRate = inRate
        }
        return pcmResampler.convert(rateAligned, rateAligned.size, inRate, sampleRate, rateChannels)
    }

    /** 走与采集循环一致的封包/编码/发送路径（[feedPcm] 与采集循环共用）。 */
    private fun dispatchPcm(pcm: ByteArray) {
        try {
            val payloads: List<ByteArray> =
                if (codec == AudioCodec.AAC_LC) {
                    val encoder = aacEncoder ?: return
                    encoder.encode(pcm, pcm.size)
                } else {
                    listOf(pcm)
                }
            if (payloads.isEmpty()) return
            var sendFailed = false
            for (payload in payloads) {
                if (!sendPayload(payload)) {
                    sendFailed = true
                    break
                }
            }
            if (sendFailed) stop()
        } catch (error: Throwable) {
            log("AUDIO: 直通注入异常 - ${error.message}")
            stop()
        }
    }

    /**
     * 停止音频。
     *
     * 收尾路径不止一条（采集线程 finally、会话结束回调、手动断开），会重复调用；
     * 必须幂等——否则「已恢复手机媒体音量」会打两遍，且在用户于会话期间手动改过
     * 音量时，第二次恢复会用旧值把它覆盖回去。
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        aacEncoder?.stop()
        aacEncoder = null
        runCatching { socket?.close() }
        socket = null
        runCatching { syncSocket?.close() }
        syncSocket = null
        syncThread = null
        healthThread?.interrupt()
        healthThread = null
        keepAliveThread?.interrupt()
        keepAliveThread = null
        thread = null
        passthroughBuffer.reset()
        restorePhoneMedia()
        if (syncSent > 0 && syncIcmpDeferrals > 0) {
            // 「已就地补发」只说明我们这一侧没丢包，不代表车机收到了：ICMP 端口不可达
            // 意味着那个端口上**没有监听者**，锚点实际一个都没落地。两种情形要分开说，
            // 否则日志会把人引向「锚点没问题」（2026-11-13 那样 1476/1482 被拒还写着「锚点未丢」）。
            val verdict =
                if (syncIcmpDeferrals * 10 >= syncSent * 9) {
                    "该端口全程没有监听者——锚点实际没送达，接收端只能按到达时刻排播"
                } else {
                    "其余已送达，锚点有效"
                }
            log(
                "AUDIO: 同步包共发出 $syncSent 个，其中 $syncIcmpDeferrals 次被车机 " +
                    "controlPort $controlPort 的 ICMP 拒绝（我们这侧已就地补发；$verdict）",
            )
        }
        log("AUDIO: 已停止（共发 $packetsSent 包 / ${bytesSent / 1024}KB）")
    }

    /** 捕获配置：媒体/游戏/导航等常见播放用途（逐项添加，个别用途被系统拒绝时跳过）。 */
    private fun buildCaptureConfig(projection: MediaProjection): AudioPlaybackCaptureConfiguration {
        val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
        listOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        ).forEach { usage ->
            runCatching { builder.addMatchingUsage(usage) }
        }
        return builder.build()
    }

    // ---- 音频同步包（RTP 时间戳 ↔ NTP，每秒一个） ----

    /**
     * 打开同步包套接字（连接式 UDP：连上之后才能收到 ICMP 端口不可达）。
     * 失败返回 null（例如车机下发的 controlPort 非法）。
     */
    private fun openSyncSocket(): DatagramSocket? =
        try {
            DatagramSocket().apply { connect(InetAddress.getByName(host), controlPort) }
        } catch (error: Exception) {
            log("AUDIO: 同步包套接字创建失败 - ${error.message}")
            null
        }

    /**
     * 发一个同步包。**失败不终止同步线程，也不放弃锚点**。
     *
     * 2026-10-26 实证（奥迪 MIB3）：音频 SETUP 后 1 秒，第一个同步包引发
     * `ECONNREFUSED`（车机此刻还没在 controlPort 上监听），旧实现 `catch` 后直接
     * 退出线程 —— 整场会话再没有任何同步包，RTP↔NTP 锚点永久缺失。
     *
     * 2026-11-01 实证（小米 → 奥迪 MIB3，10 分钟日志）：现象不是「一次就好」，而是
     * 恒定的「失败 1 次 → 恢复 → 失败 1 次」交替 —— 说明车机 controlPort 全程没监听，
     * 每一包都被回 ICMP 端口不可达；而连接式 UDP 把这个错误**延后一拍**上报（本次 send
     * 抛异常，说的其实是上一发）。后果是锚点密度只有设计值的一半，日志每秒刷两行。
     *
     * 现在的处理：识别出 ICMP 端口不可达后**在同一条套接字上立刻重发一次**——挂起的
     * 错误已被取走，补发必成；只有真正的发送失败（链路断）才计入连续失败。
     */
    @Synchronized
    private fun sendSyncOnce(first: Boolean): Boolean {
        val client = syncSocket ?: return false
        var deferred = false
        for (attempt in 0 until SYNC_SEND_ATTEMPTS) {
            val next = timestamp
            val current = (next - samplesPerPacket).coerceAtLeast(0L)
            try {
                val ntp = TimingServer.ntpFromNanos(System.nanoTime())
                val packet = AudioSyncPacket.build(syncSequence, current, next, ntp, first)
                client.send(DatagramPacket(packet, packet.size))
                syncSequence = (syncSequence + 1) and 0xFFFF
                syncSent++
                if (deferred) {
                    syncIcmpDeferrals++
                    if (syncIcmpDeferrals == 1L) {
                        log(
                            "AUDIO: 车机 controlPort $controlPort 未监听（ICMP 端口不可达）——" +
                                "每包延后一拍报错，已就地在同一套接字补发，锚点仍每秒一包" +
                                "（此提示只打一次；30 秒体检会持续给出窗口内的被拒比例）",
                        )
                    }
                }
                if (syncFailures > 0) {
                    log("AUDIO: 同步包恢复（连续失败 $syncFailures 次后发出）——锚点重建")
                    syncFailures = 0
                }
                // 「这条流的 controlPort 到底通没通」由独立的 30 秒节拍线程报
                // （见 startHealthTicker → logHealthCheck）：不跟同步包计数绑在一起，
                // 车机没下发 controlPort 时也要能照常给出发送速率。
                return true
            } catch (error: Exception) {
                if (attempt + 1 < SYNC_SEND_ATTEMPTS && isSyncIcmpRejection(error)) {
                    deferred = true
                    continue
                }
                syncFailures++
                if (syncFailures <= 3 || syncFailures % 60 == 0) {
                    log(
                        "AUDIO: 同步包发送失败（第 $syncFailures 次）- ${error.message}" +
                            "——继续每秒重试，不放弃锚点",
                    )
                }
                // 会话已收尾时不要重开（否则 socket 泄漏在 stop 之后）
                if (syncFailures % 10 == 0 && running.get()) {
                    runCatching { client.close() }
                    syncSocket = openSyncSocket()
                }
                return false
            }
        }
        return false
    }

    /**
     * 每 30 秒一行「音频链路体检」。
     *
     * 为什么要有（2026-11-13 日志复盘）：判断「有没有声音」要在四个环节里定位——
     * ① 引擎给没给真 PCM（`CARLIFE: 音频直通 … 电平`）② 我们发得多快（本行）
     * ③ 车机收没收到锚点（本行）④ 车机 `/feedback` 的 `sampleTime` 是否按实时推进。
     * ①④ 各自有日志，②③ 原来散在几十行里，且**判据写反过**：
     * 旧实现比的是 `syncIcmpDeferrals >= syncSent - 1`——只要开头有几包成功，
     * 之后 99.6% 被拒也永远判成「车机已在监听 controlPort」，等于把唯一能说明
     * 「锚点没送达」的证据报成了好消息。现在改成**按 30 秒窗口的增量**比。
     *
     * 第二个数字是「实测样本速率」：用 RTP timestamp 的增量除以真实耗时。
     * 它和声明的 [sampleRate] 不等，就说明送进编码器的样本数与时间轴不匹配
     * （44.1k→48k 重采样按块截断就踩过这个坑，见 [PcmResampler]）。
     */
    private fun logHealthCheck() {
        val now = System.nanoTime()
        val windowMs = if (healthAtNanos == 0L) 0L else (now - healthAtNanos) / 1_000_000
        val sentDelta = syncSent - healthSyncSent
        val icmpDelta = syncIcmpDeferrals - healthIcmpSent
        val packetDelta = packetsSent - healthPackets
        val sampleDelta = timestamp - healthSamples
        val seconds = windowMs / 1000.0
        val measured = if (seconds > 0.5) sampleDelta / seconds else 0.0
        val drift = if (sampleRate > 0) (measured - sampleRate) * 100.0 / sampleRate else 0.0
        val anchor =
            when {
                sentDelta <= 0 -> "锚点本窗口没发（同步线程未跑）"
                icmpDelta <= 0 -> "锚点 $sentDelta 个全部送达"
                icmpDelta * 10 >= sentDelta * 9 ->
                    "锚点 $icmpDelta/$sentDelta 被 controlPort $controlPort 拒绝" +
                        "（该端口没在监听：接收端只能按到达时刻排播）"
                else -> "锚点 $icmpDelta/$sentDelta 被拒（其余已送达）"
            }
        val rateText =
            if (seconds > 0.5) {
                "近 " + String.format(java.util.Locale.US, "%.1f", seconds) + "s 送 " +
                    "$packetDelta 包、${measured.toInt()} 样本/秒（声明 $sampleRate，差 " +
                    String.format(java.util.Locale.US, "%.2f", drift) + "%）"
            } else {
                "累计 $packetDelta 包、${measured.toInt()} 样本/秒（声明 $sampleRate）"
            }
        log("AUDIO: 链路体检 t$streamType/${codec.name} —— 累计 $packetsSent 包；$rateText；$anchor")
        healthAtNanos = now
        healthSyncSent = syncSent
        healthIcmpSent = syncIcmpDeferrals
        healthPackets = packetsSent
        healthSamples = timestamp
    }

    /**
     * 立刻补发一轮同步包，让车机重新对齐 RTP↔NTP。
     *
     * 用在「引擎音频停过一段又恢复」（来电结束、音频路由切回）之后：车机长时间收不到
     * 音频会把锚点判超时，恢复瞬间只按到达时刻猜，容易出现开头几百毫秒被丢/爆音。
     */
    @Synchronized
    fun resyncAnchor(reason: String) {
        if (!running.get() || controlPort <= 0 || syncSocket == null) return
        var sent = 0
        repeat(3) { if (sendSyncOnce(false)) sent++ }
        log("AUDIO: $reason → 补发 $sent 个同步包重新对齐车机时钟")
    }

    private fun startSyncLoop() {
        if (controlPort <= 0) {
            log("AUDIO: 车机未下发 controlPort——不发同步包（只能靠音频包自带时间戳）")
            startHealthTicker()
            return
        }
        val client = openSyncSocket() ?: return
        syncSocket = client
        log("AUDIO: 同步包已启用 → $host:$controlPort（每 ${SYNC_INTERVAL_MILLIS}ms 一个，建立 RTP↔NTP 锚点）")
        startHealthTicker()
        val worker =
            Thread(
                {
                    // 注意：**不能**在循环条件里判 `client.isClosed`——连续失败时
                    // sendSyncOnce 会把套接字关掉重开，那个判断会让线程直接退出。
                    while (running.get()) {
                        val sent = sendSyncOnce(first = !syncFirstSent)
                        if (sent && !syncFirstSent) {
                            syncFirstSent = true
                            log(
                                "AUDIO: 首个同步包已发出（X 位=1）——" +
                                    "车机若仍无声，说明它没在等这个锚点",
                            )
                        }
                        try {
                            Thread.sleep(SYNC_INTERVAL_MILLIS)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                },
                "audio-sync",
            )
        worker.isDaemon = true
        worker.start()
        syncThread = worker
    }

    /**
     * 「音频链路体检」节拍（每 30 秒一行，见 [logHealthCheck]）。
     *
     * 独立线程而不是复用同步线程的计数：车机没下发 controlPort 时同步线程根本不跑，
     * 但那时更需要知道「我们到底按多快的速率在发」。
     */
    private fun startHealthTicker() {
        if (healthThread != null) return
        healthAtNanos = System.nanoTime()
        val worker =
            Thread(
                {
                    while (running.get()) {
                        try {
                            Thread.sleep(HEALTH_INTERVAL_MILLIS)
                        } catch (_: InterruptedException) {
                            break
                        }
                        if (!running.get()) break
                        logHealthCheck()
                    }
                },
                "audio-health",
            )
        worker.isDaemon = true
        worker.start()
        healthThread = worker
    }

    // ---- 采集循环 → 封包发送 ----

    private fun runCaptureLoop() {
        val recorder = record ?: return
        val frameBytes = framePcmBytes
        val pcm = ByteArray(frameBytes)
        // 诊断抽样节拍：PCM 约 125 包/s（每 20s 一次）；AAC 约 47 帧/s
        val diagnosticStride = if (codec == AudioCodec.AAC_LC) 600 else 1875
        try {
            while (running.get()) {
                val read =
                    try {
                        recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    } catch (error: Exception) {
                        log("AUDIO: 采集读取异常 - ${error.message}")
                        break
                    }
                if (read < 0) {
                    readFailures++
                    if (readFailures == 1 || readFailures % 100 == 0) {
                        log("AUDIO: 采集错误码 $read（连续 $readFailures 次）")
                    }
                    if (readFailures > 500) break
                    Thread.sleep(READ_TIMEOUT_QUIET_MILLIS)
                    continue
                }
                if (read < frameBytes) continue // 残包（缓冲尾部）：丢弃，等下一个整包
                readFailures = 0

                // 内容诊断（2026-10-17）：播放捕获可能拿到全零（app 禁止捕获 / 音频路由
                // 到蓝牙 / 无音频播放）。全零时车机静音但手机仍在响，用户会报「声音在
                // 手机上」——必须能区分「没采到」与「采到了但车机没播」。
                if (sequence < 3 || sequence % diagnosticStride == 0) {
                    var nonZero = 0
                    var peak = 0
                    var index = 0
                    while (index + 1 < read) {
                        val value = ((pcm[index + 1].toInt() and 0xFF) shl 8) or (pcm[index].toInt() and 0xFF)
                        val magnitude = if (value > 32767) 65536 - value else value
                        if (magnitude > peak) peak = magnitude
                        if (magnitude > 0) nonZero++
                        index += 2
                    }
                    val percent = nonZero * 100 / (read / 2).coerceAtLeast(1)
                    log(
                        "AUDIO: 采集样本 seq=$sequence 非零$percent% 峰值=$peak" +
                            if (peak == 0) "（⚠ 全零：捕获不到音频——app 可能禁止播放捕获或音频走了蓝牙）" else "",
                    )
                }

                // PCM 通路：采一段 = 发一包；AAC 通路：喂一帧 PCM，编码器可能吐出 0~N 帧
                val payloads: List<ByteArray> =
                    if (codec == AudioCodec.AAC_LC) {
                        val encoder = aacEncoder ?: break
                        encoder.encode(pcm, frameBytes)
                    } else {
                        listOf(pcm)
                    }
                if (payloads.isEmpty()) continue

                // 一次性自检：把编码结果自己解一遍，证明比特流有效且非静音。
                // 这是「车机无声」最需要的一条证据——如果自解码峰值是 0，问题在编码器，
                // 如果自解码有声音而车机静音，问题在车机侧（会话/音量/路由）。
                if (!selfChecked) {
                    selfChecked = true
                    verifyEncodedFrame(payloads.first())
                }

                var sendFailed = false
                for (payload in payloads) {
                    if (!sendPayload(payload)) {
                        sendFailed = true
                        break
                    }
                }
                if (sendFailed) break
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastLogAt >= 30_000L) {
                    lastLogAt = now
                    log(
                        "AUDIO: 推流中（累计 $packetsSent 包，${bytesSent / 1024}KB，" +
                            "seq=$sequence ts=$timestamp）",
                    )
                }
            }
        } catch (error: Throwable) {
            // 后台线程未捕获异常会杀死整个进程——音频异常绝不允许冒泡
            log("AUDIO: 采集线程异常退出 - ${error.message}")
        } finally {
            stop()
        }
    }

    /** 封包 + 发送一包（同步更新 seq/ts/nonce 游标）。返回 false 表示链路已断。 */
    @Synchronized
    private fun sendPayload(payload: ByteArray): Boolean {
        val wire = sealPacket(payload, sequence, timestamp, nonceCounter, markerPending)
        markerPending = false
        sequence = (sequence + 1) and 0xFFFF
        timestamp += samplesPerPacket
        nonceCounter++
        val client = socket ?: return false
        return try {
            client.send(DatagramPacket(wire, wire.size))
            packetsSent++
            bytesSent += wire.size
            true
        } catch (error: IOException) {
            log("AUDIO: UDP 发送失败 - ${error.message}")
            false
        }
    }

    /** 用平台解码器把首帧解回 PCM，记录峰值（后台线程，不阻塞推流）。 */
    private fun verifyEncodedFrame(frame: ByteArray) {
        val payload = frame.copyOf()
        Thread(
            {
                try {
                    val result = AacLcEncoder.decodePeak(payload, sampleRate, CHANNELS)
                    if (result == null) {
                        log("AUDIO: 自检 = 本机解码器没解出样本（首帧 ${payload.size}B）——无法证伪，继续推流")
                        return@Thread
                    }
                    val (peak, samples) = result
                    log(
                        "AUDIO: 自检 = 首帧 ${payload.size}B 本机解码 $samples 个样本，峰值 $peak" +
                            if (peak > 0) {
                                "（✓ 比特流有效且非静音：车机若无声，问题不在编码/加密）"
                            } else {
                                "（⚠ 解码为全零：AAC 编码器输出的是静音数据）"
                            },
                    )
                } catch (error: Exception) {
                    log("AUDIO: 自检解码异常 - ${error.message}")
                }
            },
            "audio-selfcheck",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** 组一个 RTP 包：明文 PCM → ChaCha20 密封 → [头][密文+tag][nonce8]。（internal：测试直测） */
    internal fun sealPacket(
        pcm: ByteArray,
        sequence: Int,
        timestamp: Long,
        nonceCounter: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = buildRtpHeader(sequence, timestamp, marker)
        // AAD = header 的 timestamp+ssrc 段（字节 4..12）——catplay "modern AAD" / DiPlay 解密同款
        val aad = header.copyOfRange(4, RTP_HEADER_LEN)
        // 关键：CarPlay 线格式的 LPCM 是**大端** s16 样本——接收端对收到的负载做
        // byteSwapS16 转回小端再喂 AudioTrack（DiPlay AndroidMediaSink.kt:1687）。
        // Android 采集是原生小端，因此这里必须换序；漏掉会得到「沙沙声」（2026-10-10 实测）。
        //
        // AAC 通路不做换序：AAC 比特流是字节流，不是 16bit 样本序列，换序会直接把
        // 比特流搅烂（车机解码失败 → 有包无声）。
        val wirePayload = if (codec == AudioCodec.AAC_LC) pcm else bigEndianS16(pcm)
        val sealed = AirPlayCrypto.chachaSeal(streamKey, AirPlayCrypto.nonce64(nonceCounter), wirePayload, aad)
        val wire = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_SIZE)
        header.copyInto(wire, 0)
        sealed.copyInto(wire, RTP_HEADER_LEN)
        // 尾部 8 字节小端 nonce（与 AirPlayCrypto.nonce64 的低 8 字节一致）
        for (index in 0 until NONCE_SIZE) {
            wire[wire.size - NONCE_SIZE + index] =
                ((nonceCounter ushr (8 * index)) and 0xFF).toByte()
        }
        return wire
    }

    private fun buildRtpHeader(
        sequence: Int,
        timestamp: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte() // version=2
        // PT 跟随流类型（catplay `payload_type = stream_type`）：100=PCM 通路、102=AAC 通路
        header[1] = ((if (marker) 0x80 else 0) or (streamType.toInt() and 0x7F)).toByte()
        header[2] = ((sequence ushr 8) and 0xFF).toByte()
        header[3] = (sequence and 0xFF).toByte()
        val ts = timestamp.toInt()
        header[4] = ((ts ushr 24) and 0xFF).toByte()
        header[5] = ((ts ushr 16) and 0xFF).toByte()
        header[6] = ((ts ushr 8) and 0xFF).toByte()
        header[7] = (ts and 0xFF).toByte()
        header[8] = ((ssrc ushr 24) and 0xFF).toByte()
        header[9] = ((ssrc ushr 16) and 0xFF).toByte()
        header[10] = ((ssrc ushr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header
    }
}
