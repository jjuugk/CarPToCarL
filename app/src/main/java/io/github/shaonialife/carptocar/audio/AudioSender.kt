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
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
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
 *    切分播放边界）
 *
 * 旁路：
 *  - **同步包**：[AudioSyncPacket] 每 1 秒发往该流的 `controlPort`，建立 RTP↔NTP 锚点。
 *    （缺了它接收端只能「按到达时刻猜」，严格的实现干脆不排播——2026-10-20 实测）
 *
 * ## 只有一条出帧路径：直通
 *
 * PCM 到齐一帧（[SAMPLES_PER_PACKET] 样本）就封包发走，**没有混音器、没有定拍泵、
 * 没有空载补静音**。1.2.71 之前有两套：混音泵（定拍 + 双路相加 + duck + 水位裁剪 +
 * 补静音）与直通。那层“智能”本身成了变量——音乐听起来不对时，定拍、补帧、duck、
 * 水位任何一环出问题都和引擎给的 PCM 混在一起看不出来。1.2.72 把混音层整体删掉，
 * 只留直通：谁给的 PCM 就是什么，RTP 时间戳逐包推进。
 *
 * 两个入口共用这条路径：
 *  - [startPassthrough]：外部注入（CarLife 引擎的裸 PCM，见 [feedPcm]）；
 *  - [start]：`AudioPlaybackCapture` 采集本机播放（普通投屏，需要 MediaProjection）。
 *    采集是阻塞式 `read`，天然按实时速率来，不需要泵来定拍。
 *
 * 代价（刻意的取舍）：源头不推 PCM 就不发包，车机 jitter buffer 会排空，恢复瞬间
 * 可能“卡一下”。补静音帧正是被删掉的那部分，不再有。
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
    /**
     * 该流的声道数（1 = 单声道，2 = 立体声）。
     *
     * 必须与 SETUP 里声明的格式位一致：`101 AltAudio/default` 上奥迪只给**单声道** PCM
     * （bit14/bit10），发立体声负载过去会被解成噪声或干脆不响。媒体流仍是立体声。
     */
    private val channelCount: Int = CHANNELS,
    /**
     * 是否媒体流。**只有媒体流**启用队列治理的静音关流与欠载加深；导航流有大段空白，
     * 按静音断流会把播报切断（见 [AudioQueueGovernor] 类注释）。默认 true 保持既有构造
     * 点（媒体流）与单测的行为不变；导航流在 SessionController 里显式传 false。
     */
    private val isMedia: Boolean = true,
    /**
     * 队列治理开关（默认关）。**关掉时 [writePcm] 走原来的同步直发，字节与时间轴完全不变**；
     * 打开后才启用单写者泵线程 + 分帧队列 + 三态治理。由 [AudioQueueOption] 在会话起步时读取。
     */
    private val queueGovernorEnabled: Boolean = false,
) : AudioSink {
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

        /** 两次「缓冲满丢包」日志之间的最小间隔（5 秒；拥塞时别刷屏）。 */
        private const val DROP_FULL_LOG_GAP_NANOS = 5_000_000_000L

        /** 一次 `sendSyncOnce` 内最多尝试次数（ICMP 端口不可达时就地补发一次）。 */
        private const val SYNC_SEND_ATTEMPTS = 2

        /**
         * 一包音频的最大尝试次数（首发 + 重开数据口后补发一次）。
         *
         * 2026-11-21 现场（奥迪 MIB3 + 荣耀 Magic7 Pro）：三次 `Message too long`
         * （内核 `EMSGSIZE`）分别落在 17:17:14 / 17:27:14 / 17:37:14，**间隔恰好 600 秒**，
         * 而那一刻 WiFi 心跳一条事件都没有、视频帧照发（写入 p95=2ms）。说明链路没断，
         * 只是**这一刻内核认为这条路放不下这个包**（PCM 包 1444B，加 IP/UDP 头 1472B，
         * 离 1500 MTU 只剩 28 字节）。这类一过性错误重开数据口就能继续，
         * 旧实现却当场 `stop()` 把整条音频通路拆了。
         */
        private const val SEND_ATTEMPTS = 2

        /**
         * 连续多少次「所有尝试都失败」才放弃这条数据口。
         *
         * 单次失败一律按丢包处理（音频是实时流，丢一包无害）；连续 3 包（≈24ms）都发不出去
         * 才是真的没了。放弃后交给 [io.github.shaonialife.carptocar.SessionController]
         * 的音频自愈去重建整条流——那是唯一能恢复的路径。
         */
        private const val SEND_FAILURES_BEFORE_STOP = 3

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

        /** 队列溢出日志节流（2 秒一条，避免欠载时刷屏）。 */
        private const val QUEUE_OVERFLOW_LOG_GAP_NANOS = 2_000_000_000L

        /** 泵线程空闲等待（有 notify 唤醒，超时只是兜底）。 */
        private const val PUMP_IDLE_WAIT_MILLIS = 200L

        /** 软配额之上的硬缓冲余量（帧）：媒体 6 / 导航 10，吸收 GC 造成的瞬时落后。 */
        private const val MEDIA_HARD_EXTRA_FRAMES = 6
        private const val NAV_HARD_EXTRA_FRAMES = 10

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

    /**
     * 数据口 6000 的发送通道。
     *
     * 2026-11-17 从阻塞式 `DatagramSocket.send()` 换成**非阻塞** `DatagramChannel`：
     * 车机暂停/掉网时内核发送缓冲填满，`DatagramSocket.send()` 会一直阻塞在 sendto
     * （11:00:12 日志「发包 1138ms / 本线程 CPU +10ms」——墙钟走了 1.1 秒但线程几乎没耗 CPU，
     * 只可能是阻塞在内核，不是编码）。一个 send 卡住就把调用线程拖住（直通模式下就是
     * 引擎的读线程），后面所有帧挤在一起补，表现为「声音卡顿/choppy」。
     *
     * 非阻塞模式下 send 返回 0（缓冲满）时**丢掉这一包**即可：音频是实时流，晚到的一包
     * 毫无意义，丢掉反而保住后续帧的节拍。车机恢复消费后自然续发，无需重建会话。
     *
     * 同步口 6011 仍用 `DatagramSocket`（每秒仅 1 包，从不会填满缓冲，且它依赖
     * connect + ICMP 端口不可达判断 controlPort 是否监听，见 [openSyncSocket]）。
     */
    private var socket: DatagramChannel? = null
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
    private var healthDroppedFull = 0L

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

    /** 一包负载字节数（按本流声道数：立体声 1408B / 单声道 704B）。 */
    private val packetBytes: Int = SAMPLES_PER_PACKET * channelCount * 2

    /** 每包/每帧对应的 PCM 字节数（PCM 通路=一包的负载；AAC 通路=一帧的输入）。 */
    private val framePcmBytes: Int
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.pcmBytes(channelCount) else packetBytes

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

    /** 有非零样本的 PCM 包数（连接阶段卡片用；AAC 通路不统计，保持 0）。 */
    @Volatile private var signalPackets = 0L

    /** 连接阶段卡片实时读取，只在 UI 线程调用。 */
    val packetsSentTotal: Long get() = packetsSent
    val nonSilentPacketsTotal: Long get() = signalPackets

    /**
     * 非阻塞发送时因内核缓冲满而丢掉的包数（2026-11-17）。
     * 丢包是**预期行为**：车机暂停/拥塞时不消费，晚到的包毫无意义；丢掉保住节拍，
     * 车机恢复后自然续发。这里只计数 + 节流日志，供链路体检区分「我方缓冲满主动丢」
     * 与「链路上丢」——两者对策完全相反。
     */
    @Volatile private var packetsDroppedFull = 0L
    private var lastDropFullLogAt = 0L
    private var lastLogAt = 0L
    private var readFailures = 0

    /**
     * 连续「一整包的所有尝试都失败」的次数（[SEND_ATTEMPTS] 次全败才算 1）。
     * 成功发出一包即清零——它衡量的是链路，不是单包运气。
     */
    private var sendFailures = 0

    override val isRunning: Boolean get() = running.get()

    // ---- 直通：待发 PCM 缓冲（外部注入与采集共用一条出帧路径） ----

    /** 是否已经收到 RECORD（未收到前不发包，见 [onRecorded]）。 */
    private val readyToSend = AtomicBoolean(false)

    /** 待发缓冲的锁：只有注入/采集线程写，[clearPcm] 与 stop() 会并发清空。 */
    private val pcmLock = Any()

    private var pcmBuf = ByteArray(packetBytes * 16)

    private var pcmLen = 0

    /** 复用的一帧缓冲（[dispatchPcm] 内部会再拷一份，不会别名）。 */
    private val pcmFrame = ByteArray(framePcmBytes)

    // ---- 队列治理（默认关；开时才用）----

    /** 三态策略：稳态积压 / 欠载加深 / 静音关流。时钟可注入，纯 JVM。 */
    private val queueGovernor = AudioQueueGovernor(isMedia = isMedia)

    /** 待发帧队列：只有开关打开、且走直通模式时才有人写。 */
    /** 用 Object 而非 Any：synchronized 块里要调 wait/notifyAll。 */
    private val pendingLock = java.lang.Object()
    private val pendingFrames = ArrayDeque<ByteArray>()
    private var pumpThread: Thread? = null
    private var framesDroppedQueue = 0L
    private var lastQueueOverflowLogAtNanos = 0L
    private var lastSilenceLogAtNanos = 0L

    /** 软配额（帧）：关闭时恒 1（= 攒满一帧即发）；开启时按三态算。 */
    private val queueCapacity: Int
        get() = queueGovernor.capacity()

    private val hardCap: Int
        get() = queueCapacity + if (isMedia) MEDIA_HARD_EXTRA_FRAMES else NAV_HARD_EXTRA_FRAMES

    /**
     * 重采样器：CarLife 引擎可能给 44.1k，车机协商的是 48k（或反之）。
     * 只有一路源，所以只需要一份相位（见 [PcmResampler]）。
     */
    private val resampler = PcmResampler()

    /** 上次重采样认定的输入采样率（变了就重置相位，避免拿上一段的尾巴去插值）。 */
    private var resampleInRate = 0

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
     *
     * 采集循环读多少发多少，与直通共用 [dispatchPcm]，没有泵线程。
     */
    fun start(projection: MediaProjection): Boolean {
        if (running.get()) return true
        stopped.set(false)
        return try {
            val client = openDataSocket()
            socket = client

            // AAC 通路先把编码器立起来：编不出来就没必要采集（也避免「有包无声」的假象）
            if (codec == AudioCodec.AAC_LC) {
                val encoder = AacLcEncoder(sampleRate, channelCount, wrapAdts = adtsFrames, log = log)
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
                    .setChannelMask(
                        if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
                    )
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
                "AUDIO: 已启动（播放捕获 ${sampleRate}Hz ${if (channelCount == 1) "单声道" else "立体声"} → $host:$dataPort，" +
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
     * 采样率/声道对齐由 [feedPcm] 负责（按 [sampleRate]/[CHANNELS] 重采样），
     * AAC 通路仍走 [AacLcEncoder]（车机只吃 AAC 时，编码不可避免）。
     */
    fun startPassthrough(): Boolean {
        if (running.get()) return true
        stopped.set(false)
        return try {
            val client = openDataSocket()
            socket = client

            if (codec == AudioCodec.AAC_LC) {
                val encoder = AacLcEncoder(sampleRate, channelCount, wrapAdts = adtsFrames, log = log)
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
            // 队列治理只在开关打开时启用：关掉时下面不建泵线程，writePcm 走原来的同步直发。
            queueGovernor.enabled = queueGovernorEnabled
            if (queueGovernorEnabled) {
                queueGovernor.onStreamStarted()
                startPumpIfNeeded()
            }
            log(
                "AUDIO: 已启动直通模式（外部注入 PCM → $host:$dataPort，" +
                    "通路=t$streamType/${codec.name}，目标 ${sampleRate}Hz ${if (channelCount == 1) "单声道" else "立体声"}；RECORD 后开始发包，" +
                    "队列治理=${if (queueGovernorEnabled) "开（${if (isMedia) "媒体" else "导航"}，软配额 $queueCapacity 帧）" else "关（逐帧直发）"}）",
            )
            true
        } catch (error: Exception) {
            log("AUDIO: 直通启动失败 - ${error.message}")
            stop()
            false
        }
    }

    /**
     * 会话 `RECORD` 成功后才允许发包（幂等，多条 RECORD 成功路径都会调）。
     *
     * 接收端在 RECORD 激活会话前还没挂上这条流的播放时钟：抢跑的 RTP/同步包要么被
     * controlPort 的 ICMP 拒绝，要么提前把 jitter buffer 填偏。所以这里只做两件事：
     * 起同步包线程（RTP↔NTP 锚点）与打开发包闸门。
     */
    @Synchronized
    fun onRecorded() {
        if (!running.get() || record != null) return
        if (!readyToSend.compareAndSet(false, true)) return
        startSyncLoop()
        // RECORD 激活才是真正的起流点：开启 1s 欠载过滤窗口（此时车机刚开始消费，
        // 早期空窗属正常），关闭时 onStreamStarted 不产生任何行为差异。
        queueGovernor.onStreamStarted()
        log(
            "AUDIO: RECORD 已激活——开始发包（直通：PCM 到齐 ${framePcmBytes}B 一帧即发，" +
                "每包 $samplesPerPacket 样本；无混音、无定拍泵、不补静音" +
                (if (queueGovernor.enabled) "；队列治理开（软配额 $queueCapacity 帧）" else "") + "）",
        )
    }

    /**
     * 外部注入 PCM（CarLife 直通）。
     *
     * 只做「重采样对齐 + 攒帧 + 封包发送」，**不阻塞引擎的读线程**：UDP 是非阻塞的
     * （缓冲满就丢这一包），AAC 编码一帧 1024 样本在本机约 1~2ms。
     *
     * @param srcPcm   输入 PCM（16bit 小端交织）
     * @param size     有效字节数
     * @param srcRate  输入采样率（<=0 时按 [sampleRate] 处理，不重采样）
     * @param srcChannels 输入声道数（1 或 2；其它按 2 处理）
     */
    override fun feedPcm(
        srcPcm: ByteArray,
        size: Int,
        srcRate: Int,
        srcChannels: Int,
    ) {
        if (!running.get() || record != null) return // 采集模式在跑时不接受注入
        if (size <= 0) return
        // RECORD 之前不发：抢跑的 RTP 会被车机拒掉，或者提前把它的 jitter buffer 填偏
        if (!readyToSend.get()) return
        val resampled = alignPcm(srcPcm, size, srcRate, srcChannels) ?: return
        writePcm(resampled)
    }

    /**
     * 直通写入：到齐一帧就封包发走。
     *
     * 不做的事（1.2.72 删掉的一整层）：
     *  - **不定拍**：没有泵线程，没有 nanoTime 推到第几帧的计算，到多少发多少；
     *  - **不补静音**：引擎不推 PCM 就不发（车机 jitter buffer 会排空，恢复时可能卡一下）；
     *  - **不相加**：只有一路源，不存在 duck / 水位裁剪 / 双路时钟 2× 的问题。
     *
     * 时间戳按 `samplesPerPacket` 逐包推进（[sendPayload]），而引擎的推流速率本身就是
     * 实时的，所以 RTP 时钟与墙钟长期一致；唯一的偏差是引擎开局的突发（它自己的解码
     * 缓冲一次推几百毫秒）——这里照发，让车机的 jitter buffer 去吸收，不做水位裁剪。
     */
    private fun writePcm(pcm: ByteArray) {
        val frameBytes = pcmFrame.size
        synchronized(pcmLock) {
            while (pcmLen + pcm.size > pcmBuf.size) {
                pcmBuf = pcmBuf.copyOf(pcmBuf.size * 2)
            }
            pcm.copyInto(pcmBuf, pcmLen)
            pcmLen += pcm.size
        }
        // 派发在锁外：dispatchPcm 失败时会调 stop()（要拿实例锁），锁内派发有机会互相等死。
        // 同一时刻只有一条源线程（9240 读线程或采集线程），pcmFrame 不会被第二个人改写。
        while (true) {
            val frameReady =
                synchronized(pcmLock) {
                    if (pcmLen < frameBytes) {
                        false
                    } else {
                        pcmBuf.copyInto(pcmFrame, 0, 0, frameBytes)
                        if (pcmLen > frameBytes) {
                            pcmBuf.copyInto(pcmBuf, 0, frameBytes, pcmLen)
                        }
                        pcmLen -= frameBytes
                        true
                    }
                }
            if (!frameReady) break
            if (queueGovernor.enabled) {
                enqueueFrame(pcmFrame)
            } else {
                dispatchPcm(pcmFrame)
            }
        }
    }

    /**
     * 队列治理开启时，把一帧放进待发队列（[writePcm] 的出口）。
     *
     * 静音关流：只媒体流生效，连续 3s 无有效样本就丢弃待发帧、暂停入队，恢复有声自动续流；
     * 起流 3s 宽限期内照常发送（车机靠静音包维持时钟），只有判定 STOP 才断。
     * 溢出：排队超过「软配额 + 硬余量」才裁回软配额（丢最旧的帧，保留最新音频）。
     */
    private fun enqueueFrame(frame: ByteArray) {
        val silence = queueGovernor.observe(frame, frame.size)
        if (silence == AudioQueueGovernor.Silence.STOP) {
            var dropped = 0
            synchronized(pendingLock) {
                dropped = pendingFrames.size
                pendingFrames.clear()
            }
            framesDroppedQueue += dropped
            logSilenceStopOnce(dropped)
            return
        }
        val overflow =
            synchronized(pendingLock) {
                pendingFrames.addLast(frame.copyOf())
                var dropped = 0
                while (pendingFrames.size > hardCap) {
                    pendingFrames.removeFirst()
                    dropped++
                    framesDroppedQueue++
                }
                pendingLock.notifyAll()
                dropped
            }
        if (overflow > 0) logQueueOverflowOnce(overflow)
    }

    private fun logSilenceStopOnce(dropped: Int) {
        val now = System.nanoTime()
        if (lastSilenceLogAtNanos != 0L && now - lastSilenceLogAtNanos < QUEUE_OVERFLOW_LOG_GAP_NANOS) return
        lastSilenceLogAtNanos = now
        log(
            "AUDIO: 媒体流静音超过 ${AudioQueueGovernor.SILENCE_STOP_MILLIS / 1000}s——" +
                "暂停入队（丢弃 $dropped 帧），恢复有声后自动续流",
        )
    }

    private fun logQueueOverflowOnce(dropped: Int) {
        val now = System.nanoTime()
        if (lastQueueOverflowLogAtNanos != 0L && now - lastQueueOverflowLogAtNanos < QUEUE_OVERFLOW_LOG_GAP_NANOS) return
        lastQueueOverflowLogAtNanos = now
        log(
            "AUDIO: 队列积压丢弃 $dropped 帧（排队 ${synchronized(pendingLock) { pendingFrames.size }}，" +
                "软配额 $queueCapacity，硬上限 $hardCap）——泵长期跟不上，不是抖动",
        )
    }

    /** 车机 `/feedback` 报欠载时由主控调用；返回是否被采纳（起流 1s 内反馈会被过滤）。 */
    fun noteUnderflow(): Boolean {
        val accepted = queueGovernor.noteUnderflow()
        if (accepted) {
            log(
                "AUDIO: 车机报欠载——媒体目标积压升到 ${AudioQueueGovernor.UNDERFLOW_MEDIA_FRAMES} 帧，" +
                    "保持 ${AudioQueueGovernor.UNDERFLOW_HOLD_MILLIS / 1000}s",
            )
            synchronized(pendingLock) { pendingLock.notifyAll() }
        }
        return accepted
    }

    /** 启动单写者泵线程（幂等；只在队列治理开启时调用）。 */
    private fun startPumpIfNeeded() {
        if (pumpThread != null) return
        Thread(
            {
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
                while (running.get()) {
                    val frame =
                        synchronized(pendingLock) {
                            val next = pendingFrames.removeFirstOrNull()
                            if (next == null) runCatching { pendingLock.wait(PUMP_IDLE_WAIT_MILLIS) }
                            next
                        }
                    if (frame == null || !running.get()) continue
                    dispatchPcm(frame)
                }
            },
            "audio-pump",
        ).apply {
            isDaemon = true
            start()
            pumpThread = this
        }
    }

    /** 清空尚未发出的 PCM（车机暂停/静音时立即止声）。RTP 时间轴保持连续。 */
    fun clearPcm() {
        synchronized(pcmLock) { pcmLen = 0 }
        synchronized(pendingLock) { pendingFrames.clear() }
    }

    /**
     * 用户手动「重启音频」（方向盘语音键三连击，见 [io.github.shaonialife.carptocar.carlife.VoiceKeyGesture]）。
     *
     * 只清掉**我方**待发样本与重采样相位，不动 socket、不动 RTP 时间轴：
     *
     * - 缓冲里积压的旧样本本来就是「重启」要丢掉的东西；
     * - 重采样相位归零，避免时间轴错位后拿上一段的尾巴继续插值；
     * - `seq` / `timestamp` **保持连续**：车机按时间戳排播，凭空跳一段会让它判成丢包或重复，
     *   反而更久不出声。
     *
     * 这条路径**不重建 RTSP 流**（那是 [io.github.shaonialife.carptocar.airplay.CarPlayResourceStreams.restartAudio]
     * 干的事）。两者一起用：先重置这里，流真的坏了再拆建。
     *
     * @return 是否真的重置了。**false 表示这条发送端根本不在跑**（被 stop 了）——
     *   此时「重置缓冲」无从谈起，调用方必须升级成「重建整条流」。
     *   2026-11-21 现场：日志只留一行「重启音频被忽略」，用户按了按钮毫无作用，
     *   看上去像按钮坏了，实际是这里放弃了、上层也没接手。
     */
    @Synchronized
    fun restartPipeline(reason: String): Boolean {
        if (!running.get()) {
            log("AUDIO: 发送端没在运行，无法重置缓冲（$reason）——需重建整条音频流")
            return false
        }
        resampler.reset()
        resampleInRate = 0
        sendFailures = 0
        synchronized(pcmLock) { pcmLen = 0 }
        synchronized(pendingLock) { pendingFrames.clear() }
        queueGovernor.onStreamStarted()
        log("AUDIO: 已清空待发 PCM、重采样相位归零；RTP 时间轴保持连续——$reason")
        return true
    }

    /**
     * 把一段 PCM 重采样到 [sampleRate] 立体声，返回新数组；无需处理时返回原输入。
     *
     * 44.1k↔48k 这类小比值交给 [PcmResampler]（四点三次插值 + 相位跨块延续），不引第三方库：
     * CarLife 引擎实际只会输出 44.1k 或 48k。任意其它比值同一套逻辑也成立，不会中断推流。
     */
    private fun alignPcm(
        src: ByteArray,
        size: Int,
        srcRate: Int,
        srcChannels: Int,
    ): ByteArray? {
        if (size <= 0) return null
        val inRate = if (srcRate > 0) srcRate else sampleRate
        val inCh = if (srcChannels == 1) 1 else 2

        // 先对齐声道：
        //  - 单声道 → 立体声：复制到左右；
        //  - 立体声 → 单声道：左右取平均（直接丢掉一边会让只在一侧的音源消失）；
        //  - 相同：原样。
        // 目标声道由 SETUP 的格式位决定（奥迪 101/default 只有单声道位，见 [channelCount]）。
        val rateAligned: ByteArray
        var rateChannels = inCh
        if (inCh == 1 && channelCount == 2) {
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
        } else if (inCh == 2 && channelCount == 1) {
            val frames = size / 4
            val out = ByteArray(frames * 2)
            var i = 0
            var o = 0
            while (i + 3 < size) {
                val left =
                    (((src[i + 1].toInt() and 0xFF) shl 8) or (src[i].toInt() and 0xFF)).toShort().toInt()
                val right =
                    (((src[i + 3].toInt() and 0xFF) shl 8) or (src[i + 2].toInt() and 0xFF)).toShort().toInt()
                val mixed = (left + right) / 2
                out[o] = (mixed and 0xFF).toByte()
                out[o + 1] = ((mixed shr 8) and 0xFF).toByte()
                i += 4
                o += 2
            }
            rateAligned = out
            rateChannels = 1
        } else {
            rateAligned = if (size == src.size) src else src.copyOf(size)
        }

        // 采样率一致直接返回（顺带清相位：万一中途换过源采样率，别拿上一段的尾巴去插值）
        if (inRate == sampleRate) {
            resampler.reset()
            resampleInRate = 0
            return rateAligned
        }

        // 有状态重采样：相位与末帧跨块延续（见 [PcmResampler] 的文件头注释——
        // 旧实现每块独立截断，实测长期少 0.05% 样本，25 分钟欠 0.7 秒）。
        if (inRate != resampleInRate) {
            resampler.reset()
            resampleInRate = inRate
        }
        return resampler.convert(rateAligned, rateAligned.size, inRate, sampleRate, rateChannels)
    }

    /** 走与采集循环一致的封包/编码/发送路径（[writePcm] 与采集循环共用）。 */
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
            // 一次性自检只对**真实音频**做：拿静音帧去自检只会得到「⚠ 解码为全零」，
            // 把「编码器坏了」误报成事实。
            if (!selfChecked && !isAllZero(pcm)) {
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
            if (sendFailed) stop()
        } catch (error: Throwable) {
            log("AUDIO: 音频发送异常 - ${error.message}")
            stop()
        }
    }

    /** 这一帧是不是全零（静音）。自检必须跳过静音，否则会把「没人播」报成「编码器坏」。 */
    private fun isAllZero(pcm: ByteArray): Boolean {
        var i = 0
        while (i < pcm.size) {
            if (pcm[i].toInt() != 0) return false
            i++
        }
        return true
    }

    /**
     * 停止音频。
     *
     * 收尾路径不止一条（采集线程 finally、会话结束回调、手动断开），会重复调用；
     * 必须幂等——否则「已恢复手机媒体音量」会打两遍，且在用户于会话期间手动改过
     * 音量时，第二次恢复会用旧值把它覆盖回去。
     */
    @Synchronized
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
        thread = null
        readyToSend.set(false)
        synchronized(pcmLock) { pcmLen = 0 }
        synchronized(pendingLock) {
            pendingFrames.clear()
            pendingLock.notifyAll()
        }
        pumpThread?.interrupt()
        pumpThread = null
        queueGovernor.reset()
        queueGovernor.enabled = false
        resampler.reset()
        resampleInRate = 0
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
        log("AUDIO: 已停止（共发 $packetsSent 包 / ${bytesSent / 1024}KB" +
            if (packetsDroppedFull > 0) "；缓冲满主动丢 $packetsDroppedFull 包）" else "）")
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
     * 打开音频**数据**口（连接式 UDP）。
     *
     * 两个 socket 选项是为「链路抖动」设的，都失败也不影响发送，只记一行：
     * - `sendBufferSize`：默认约 64KB。视频 key frame（50~70KB）与音频挤同一条 WiFi 时，
     *   内核排队更容易在 burst 里把我们的包丢掉；放大到 256KB 至少不会「我们自己」先丢。
     * - `trafficClass = 0xB8`（DSCP EF）：WMM 把它映射到语音接入类，AP 侧排队优先于
     *   视频（AC_VI）。不支持 WMM 的 AP 会忽略，等于没设。
     *
     * 2026-11-14 的对照：接收端 `missing` 平均每 7.5 秒涨一串（≈5 包 ≈107ms 空洞），
     * 而我们这侧 0 丢（`环形缓冲丢 0KB`、样本率 47988/秒），丢包只能发生在链路上。
     * 这里能做的就是把「我们发得出去」这件事做到最稳。
     */
    private fun openDataSocket(): DatagramChannel {
        // minSdk 31 上 java.nio.channels 必然可用；connect/socket 选项失败沿用
        // start()/startPassthrough() 外层 try-catch 的既有失败路径（关通道、返回 false）。
        val channel = DatagramChannel.open()
        channel.configureBlocking(false)
        channel.connect(InetSocketAddress(InetAddress.getByName(host), dataPort))
        val underlying = channel.socket()
        val wantBuffer = 256 * 1024
        val bufferOk =
            runCatching {
                underlying.sendBufferSize = wantBuffer
                underlying.sendBufferSize
            }.getOrDefault(0)
        val dscpOk =
            runCatching {
                underlying.trafficClass = 0xB8
                true
            }.getOrDefault(false)
        val bufferNote = if (bufferOk >= 32 * 1024) "" else "（⚠ 系统限制，按默认走）"
        val dscpNote = if (dscpOk) "" else "（⚠ 不支持，按默认 QoS）"
        log(
            "AUDIO: 数据口 → $host:$dataPort（非阻塞，缓冲满即丢这一包）；" +
                "发送缓冲 ${bufferOk / 1024}KB$bufferNote；DSCP=EF$dscpNote",
        )
        return channel
    }

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
        val dropFullDelta = packetsDroppedFull - healthDroppedFull
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
        val dropFullText =
            if (dropFullDelta > 0) {
                "；缓冲满主动丢 $dropFullDelta 包（累计 $packetsDroppedFull）"
            } else {
                ""
            }
        log("AUDIO: 链路体检 t$streamType/${codec.name} —— 累计 $packetsSent 包；$rateText；$anchor$dropFullText")
        healthAtNanos = now
        healthSyncSent = syncSent
        healthIcmpSent = syncIcmpDeferrals
        healthPackets = packetsSent
        healthSamples = timestamp
        healthDroppedFull = packetsDroppedFull
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

                dispatchPcm(pcm)
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
        // 游标先推进：重试时重发**同一个 wire**（同 seq / 同 ts），绝不能各算一次——
        // 时间轴重复或跳空都会被车机判成丢包/重复而更久不出声。
        markerPending = false
        sequence = (sequence + 1) and 0xFFFF
        timestamp += samplesPerPacket
        nonceCounter++

        var lastError: Exception? = null
        for (attempt in 1..SEND_ATTEMPTS) {
            val client = socket ?: return false
            try {
                // 非阻塞：缓冲满时 send 返回 0，绝不卡住调用线程。丢这一包（晚到无意义），
                // RTP 序号/时间戳上面已经推进过——保持时间轴单调，车机按 seq gap 识别丢包。
                val written = client.write(ByteBuffer.wrap(wire))
                if (written <= 0) {
                    packetsDroppedFull++
                    val now = System.nanoTime()
                    if (lastDropFullLogAt == 0L || now - lastDropFullLogAt >= DROP_FULL_LOG_GAP_NANOS) {
                        lastDropFullLogAt = now
                        log(
                            "AUDIO: 数据口缓冲满——已主动丢 $packetsDroppedFull 包" +
                                "（车机暂停/拥塞时不消费；非阻塞不卡发送线程，恢复后自动续发）",
                        )
                    }
                    resetSendFailures()
                    return true // 不是链路断，别 stop——丢包是预期行为
                }
                packetsSent++
                if (codec == AudioCodec.PCM && payload.any { it != 0.toByte() }) signalPackets++
                bytesSent += wire.size
                resetSendFailures()
                return true
            } catch (error: IOException) {
                lastError = error
                if (attempt < SEND_ATTEMPTS) {
                    // 一过性错误（EMSGSIZE / ENETUNREACH / 网络切换瞬间）：先把现场打全，
                    // 再重开数据口补发同一包。UDP 无状态，重开是零成本的。
                    logSendFailure(error, wire.size)
                    if (reopenDataSocket()) {
                        log("AUDIO: 已重开数据口并重发同一包（首发失败：${error.message}）")
                    } else {
                        log("AUDIO: 重开数据口失败——这条音频通路已不可用")
                        return false
                    }
                }
            }
        }

        // 所有尝试都失败：单次按丢包处理，连续多次才认链路真的断了。
        sendFailures++
        if (sendFailures < SEND_FAILURES_BEFORE_STOP) {
            log(
                "AUDIO: 第 $sendFailures/${SEND_FAILURES_BEFORE_STOP} 次整包发送失败" +
                    "（${lastError?.message}）——按丢包处理，继续推流",
            )
            return true
        }
        log("AUDIO: 连续 $sendFailures 次整包发送失败，放弃这条数据口（交给上层重建音频流）")
        return false
    }

    /** 成功发出一包（或预期内的缓冲满丢包）即清零连续失败计数。 */
    private fun resetSendFailures() {
        if (sendFailures == 0) return
        log("AUDIO: 发包恢复（连续失败 $sendFailures 次后发出）")
        sendFailures = 0
    }

    /**
     * 重开数据口（同一目标、同一密钥，只换 socket）。
     *
     * 为什么要重开：`EMSGSIZE` / `ENETUNREACH` 这类错误由内核在 send 时按**当时的**路由与
     * MTU 判定，连接式 UDP 还会把上一条 ICMP 的错误延后一拍上报；重开一个 socket 等于
     * 重新解析路由、清掉挂起的错误，通常立刻就通了。RTP 时间轴与 seq 全在字段上，
     * 与 socket 无关，所以换 socket 不会让车机看到断点。
     */
    private fun reopenDataSocket(): Boolean =
        try {
            runCatching { socket?.close() }
            socket = openDataSocket()
            true
        } catch (error: Exception) {
            socket = null
            log("AUDIO: 重开数据口异常 - ${error.message}")
            false
        }

    /**
     * 发包失败的现场诊断（**只在失败时打**，正常零噪声）。
     *
     * 2026-11-21 的两份现场里，`Message too long` 那一刻 WiFi 心跳一条事件都没有、
     * 视频帧照发——「网络断没断」这个问题日志回答不了，只能靠猜 PMTU。这里把当时
     * 能读到的路况一次打全，下次直接看数字：
     *  - 包长与 IP/UDP 头（判断离 MTU 还有多少余量）；
     *  - 各本机接口的 MTU（`wlan0` 被压到 1400 就是答案）；
     *  - 数据口当时的发送缓冲（系统回收过缓冲也会让大包发不出去）。
     */
    private fun logSendFailure(
        error: Exception,
        wireSize: Int,
    ) {
        val interfaces =
            runCatching {
                java.net.NetworkInterface
                    .getNetworkInterfaces()
                    ?.toList()
                    .orEmpty()
                    .filter { it.isUp && !it.isLoopback }
                    .mapNotNull { nic -> nic.mtu.takeIf { it > 0 }?.let { "${nic.name} mtu=$it" } }
            }.getOrDefault(emptyList())
        val socketBuffer = runCatching { socket?.socket()?.sendBufferSize ?: 0 }.getOrDefault(0)
        log(
            "AUDIO: 发包失败现场 —— 目标 $host:$dataPort，包长 ${wireSize}B" +
                "（含 IP/UDP 头 ${wireSize + 28}B），本机接口 ${interfaces.joinToString("、").ifEmpty { "读不到" }}，" +
                "发送缓冲 ${socketBuffer / 1024}KB，错误=${error.javaClass.simpleName}: ${error.message}",
        )
    }

    /** 用平台解码器把首帧解回 PCM，记录峰值（后台线程，不阻塞推流）。 */
    private fun verifyEncodedFrame(frame: ByteArray) {
        val payload = frame.copyOf()
        Thread(
            {
                try {
                    val result = AacLcEncoder.decodePeak(payload, sampleRate, channelCount)
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
