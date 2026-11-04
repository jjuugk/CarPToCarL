package io.github.shaonialife.carptocar.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import io.github.shaonialife.carptocar.airplay.BinaryPlist
import io.github.shaonialife.carptocar.airplay.StreamFrameCipher
import io.github.shaonialife.carptocar.net.TcpLiveness
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay 主屏视频发送端（Phase 2-C2）。
 *
 * 线格式（对照 DiPlay ScreenStream.kt）：
 *  - 每条消息 = 128 字节头 + body；头部 = [0..3] u32LE body 长度、[4] opcode、
 *    [8..15] NTP 32.32 LE 发送时间；整头作帧加密 AAD，其余字节为零
 *  - opcode 1 = VideoConfig：明文 avcC 记录（looksLikeAvcC 判定）
 *  - opcode 0 = VideoFrame：ChaCha20-Poly1305 密封的「4 字节 BE 长度前缀 NAL 序列」，
 *    nonce = nonce64(帧计数)，计数按帧顺序从 0 递增
 *
 * 视频源：MediaCodec Surface 输入（H.264 硬编码），内容由画面源渲染
 * （隐形屏模式的 DualDisplayComposer 输入 Surface）。
 */
class VideoSender(
    private val host: String,
    private val dataPort: Int,
    /**
     * 帧加密方式（catplay `ScreenFrameCodec` 三种形态）：
     *  - ChaCha：已配对（pair-verify 派生流密钥）
     *  - AesCtr：未配对会话（`et=NONE`，AES-128-CTR + 全零会话密钥）
     */
    private val frameCipher: StreamFrameCipher,
    /** H.264 4:2:0 要求偶数尺寸——奇数高度会让编码器在首帧输入时进错误态（125% 缩放实测踩坑）。 */
    width: Int,
    height: Int,
    /** 目标帧率（30/60）：编码器元数据 + 60fps 提码率；实际节奏由合成器驱动。 */
    private val targetFps: Int = 30,
    private val log: (String) -> Unit,
    /** 推流自然结束（对端断开等）时回调——CarPlay 会话真实结束的信号。 */
    private val onEnded: (() -> Unit)? = null,
    /**
     * 屏幕流内 keepAlive 帧是否携带统计体（车机 /info 的 `keepAliveSendStatsAsBody`）。
     * 奥迪 MMI 0000 声明 true → opcode=5 + bplist 统计体；否则 opcode=2 空帧。
     * 真 iPhone/catplay 在视频持续推流时**仍然每秒发一次**——它不是视频流量的备份，
     * 而是接收端屏幕上独立的存活信号（2026-10-19 奥迪 3315 两轮实测：缺它时
     * RECORD 后 10.2s 整轮被 reset，视频/feedback/事件通道全都健康）。
     */
    private val keepAliveWithStats: Boolean = false,
    /**
     * **车机画布**（`/info displays[0].widthPixels×heightPixels`），VideoConfig 头声明的契约值。
     *
     * 真车（Audi/DELPHI、BMW/Cinemo 同款行为）按这个声明的尺寸建解码器与 surface，
     * 与车机实际画布不符就解不出画面——会话、触摸、心跳全正常，日志一个错都没有。
     * 所以它优先于任何「码流侧」的尺寸（引擎码流、SPS）。
     *
     * 不能省：直通模式下码流尺寸由 CarLife 引擎决定，引擎白名单会降档
     * （申请 1412×654 实编 1280×720，2026-11-04 奥迪 3810 实测），
     * 此时若照码流声明，车机拿到的是 720p 画布（≠ 1412×654）→ 整屏黑。
     */
    private val canvasSize: (() -> Pair<Int, Int>)? = null,
    /**
     * 直通模式下**实时**取「引擎实际编码分辨率」的回调（返回 98311 上报的宽高）。
     *
     * 只在**没有画布信息**时兜底（`canvasSize` 为空/0）。含义是「码流实际多少」，
     * 用来跟随引擎中途重建编码器；它不是画布契约——把它当声明值用，就会在
     * 「画布 ≠ 码流」的车机上黑屏（2026-11-04 奥迪 3810 实测）。
     * 每次发 config 都现取，就永远跟得上。
     */
    private val liveSize: (() -> Pair<Int, Int>)? = null,
) {
    private val width: Int = (width / 2) * 2
    private val height: Int = (height / 2) * 2
    @Volatile
    private var socket: Socket? = null

    /** 正在重连数据口：保活线程据此跳过写入，避免抢在 socket 置空窗口期抛错自杀。 */
    @Volatile
    private var reconnecting = false

    /** 「画布 ≠ 码流」的提示每轮会话只打一次（config 会随每个 IDR 重发，否则刷屏）。 */
    @Volatile
    private var canvasMismatchLogged = false

    /**
     * 视频数据口的**唯一写锁**。
     *
     * 直通模式下有两条线程会往同一个 socket 写字节：CarLife 帧线程（[submitH264]）
     * 和屏保活线程（[passthroughKeepAliveLoop]：keepAlive + 空闲重发 IDR）。
     * 不加锁时两边的 `write(header)` / `write(body)` 会交错，接收端拿到的就是一帧
     * 半 A 半 B 的乱码 —— ChaCha20-Poly1305 的 tag 校验必然失败。
     *
     * WinPlay 1.2.4 实测（2026-10-25）：`视频帧认证失败 → 视频通道正在重新连接 →
     * 8s 后 视频通道恢复超时 → 整轮会话关闭`，几乎每次都紧跟在空闲重发之后
     * （如 18:24:58.887 重发 IDR → 18:24:58.912 Connection reset）。
     *
     * 锁的范围必须包含「构造头 + 加密 + 写字节 + frameCounter 自增」：
     * 只锁写字节仍会让加密时的计数器与落地顺序错位，接收端按到达顺序解密同样失败。
     */
    private val writeLock = Any()
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val running = AtomicBoolean(false)

    /**
     * [onEnded] 是否已触发过（只触发一次）。
     *
     * 直通/编码两条收尾路径都可能到达：保活线程写失败、帧线程收尾。重复触发会把
     * 上层收尾做两遍（蓝牙门闸复位、暗幕解除、横屏复原都是幂等的，但
     * `attributeAudioTeardown` 会重复记账，日志也会重复成两条）。
     */
    private val ended = AtomicBoolean(false)
    private var frameCounter = 0L

    /**
     * VideoConfig 是否已发出。
     *
     * 写它的是**帧线程 / 重连线程**（`sendConfigFromNals`、`reconnectDataPort`），
     * 读它的却是**保活线程**（[maybeResendIdleFrame] → [sendFrameFromNals]）——
     * 普通字段的写对别的线程不保证可见：保活线程可能一直看到旧的 false 而把
     * 空闲重发全丢掉（表现是「画面静止 30 秒后车机拆链」），也可能在重连后仍看到
     * true 而漏发新 config（表现是「重连后一帧都过不去」）。
     */
    @Volatile
    private var configSent = false

    @Volatile
    private var passthrough = false
    private var droppedBeforeConfig = 0L

    /** 诊断：最多打印 3 条直通丢帧原因（定位零帧死锁）。 */
    private var diagRejects = 0

    /** 直通模式：最近一个 IDR 的 NAL（空闲时重发，防止车机超时拆链）。 */
    @Volatile
    private var lastIdrNals: List<ByteArray>? = null

    /**
     * 最近一次成功发出的 csd（SPS/PPS NAL，含 NAL 头、不含起始码）。
     *
     * CarLife 换一轮会话后，新编码器**未必**在首个 IDR 里带 SPS/PPS；此时若手上没有
     * 缓存就发不出 VideoConfig，`configSent` 永远 false → 每一帧都被丢弃。
     * 缓存它就能在源重启后立刻用旧参数重发 config 兜底（新 SPS 到达时再覆盖）。
     */
    @Volatile
    private var cachedCsd: List<ByteArray>? = null

    /** 已发出的 SPS 字节（用于识别「源换了分辨率/档位」→ 必须重发 VideoConfig）。 */
    @Volatile
    private var sentSps: ByteArray? = null

    /**
     * 源重启后先等一个 IDR 再恢复送帧。
     *
     * 新一源码流的参考帧与上一轮毫无关系，此时把 P 帧塞给车机解码器只会拿旧参考帧
     * 去叠，画面花屏或干脆黑住——直到下一个 IDR 才自愈。这里直接挡掉。
     */
    @Volatile
    private var awaitingKeyFrameAfterRestart = false

    /**
     * 开始「等 IDR」的时刻（uptimeMillis），配合 [WAIT_KEY_FRAME_TIMEOUT_MS] 做超时兜底。
     *
     * 三处置位（新会话 / 视频通道重连 / 源重启）都要一起更新，否则超时判断会用到上一轮的
     * 旧时刻，表现为「刚置位就判定超时、P 帧直接放行」——比不等更糟（等了个寂寞）。
     */
    @Volatile
    private var awaitingKeyFrameSince = 0L

    /** 源重启后被挡掉的非 IDR 帧数（诊断）。 */
    private var droppedBeforeRestartKey = 0L

    /** 最近一次真正写出帧的时刻（uptimeMillis；0 = 还没出过帧）。 */
    @Volatile
    private var lastFrameAtMs = 0L

    /**
     * 是否有**真实视频流**正在写进车机通道（最近 5s 内写过帧）。
     * 判定只认「帧真正发出去了」（[lastFrameAtMs] 只在 sendFrameFromNals 成功后更新），
     * 不认 CarLife 侧的握手/进程状态——UI 与提示逻辑都用它，避免「显示已劫持却没画面」。
     */
    val isVideoFlowing: Boolean
        get() = running.get() && lastFrameAtMs > 0 &&
            android.os.SystemClock.uptimeMillis() - lastFrameAtMs < 5_000

    /**
     * 送帧闸门：帧龄（排队延迟）+ 写入耗时。
     *
     * 以前这条链路只有「帧 #N 已发送」，看不出「发得出去、但已经是 1.5 秒前的画面」。
     * 判据与动作见 [VideoDeliveryGate]：抖动只记账，只有结构性堆积才跳下一个 IDR。
     */
    private val deliveryGate = VideoDeliveryGate()

    /** 上一次空闲重发的时刻。 */
    private var lastIdleResendAt = 0L

    /** 空闲重发次数（日志限频用）。 */
    private var idleResendCount = 0L

    /** 空闲重发时不打印逐帧日志（否则每秒两行刷屏）。 */
    @Volatile
    private var suppressFrameLog = false

    /** AES-CTR 密钥流实例：状态必须跨帧连续（每帧新建会导致对端解出乱码）。 */
    private val aesStream: AirPlayCrypto.Aes128CtrStream? =
        (frameCipher as? StreamFrameCipher.AesCtr)?.let { AirPlayCrypto.Aes128CtrStream(it.key, it.iv) }

    /** 上一次屏幕流 keepAlive 的时刻（uptimeMillis；0 = 尚未发过）。 */
    private var lastKeepAliveAt = 0L

    /** 两次 keepAlive 之间的帧数/字节数（统计体用，catplay ScreenSenderStats 同义）。 */
    private var framesSinceKeepAlive = 0
    private var bytesSinceKeepAlive = 0L
    private var keepAliveCount = 0L

    @Volatile
    private var framesSent = 0L

    /** 推流是否活跃（静默降噪守卫判断用：start 失败时保持 false）。 */
    val isRunning: Boolean get() = running.get()

    /** 已发送帧总数（英雄卡实时帧率 = 1s 轮询取差值）。 */
    val framesSentTotal: Long get() = framesSent

    /**
     * 提前建立屏幕数据端口 TCP 连接（幂等）。
     *
     * 车机看门狗（2026-10-17 奥迪实测）：屏幕流 SETUP 之后 ~2s 内数据端口必须连上，
     * 否则直接 reset 控制通道拆掉整轮会话。音频 SETUP 本身要 ~2.1s，若排在它后面，
     * 端口连接必然迟到——所以协商音频前先调本方法。catplay 亦然（视频 SETUP 后立即
     * finish_tx 连流，再协商音频）。
     */
    fun openDataPort(): Boolean {
        val existing = socket
        if (existing != null && !existing.isClosed) return true
        return try {
            val client = Socket()
            client.connect(InetSocketAddress(host, dataPort), 5_000)
            client.tcpNoDelay = true
            // 视频口是单向长写：对端静默消失时写窗口会一直「写成功」直到内核缓冲填满，
            // keepalive 让断链在 ~19s 内可见（进而触发 [reconnectDataPort] 的 8s 窗口补救）
            TcpLiveness.configure(client, "视频数据口", log)
            socket = client
            log("VIDEO: 数据端口已连通 $host:$dataPort（等 RECORD 后启动编码器）")
            true
        } catch (error: Exception) {
            log("VIDEO: 数据端口连接失败 - ${error.message}")
            false
        }
    }

    /**
     * 视频通道被接收端重置后重新拉起数据端口。
     *
     * 接收端的处理流程是「检测到解密失败 → 丢掉旧通道 → 重新监听同一端口 →
     * 等手机连回来新通道（8s 内）→ 超时就关掉整轮会话」
     * （WinPlay 1.2.4 日志：`视频帧认证失败 → 视频通道正在重新连接 → 视频通道恢复超时`）。
     * 旧实现收到 reset 直接 [stop]，等于放弃这 8 秒窗口，于是每次解密失败都升级成
     * 「整轮会话断开、要重新点启动连接」。
     *
     * 新通道视作新流：接收端会重建解密状态，计数器从 0 起算（AirPlay 视频帧的 AEAD
     * nonce 不随包传输，双方只能各自按「本流第几帧」推算），所以这里必须把
     * [frameCounter] 归零并重发 VideoConfig，否则重连后每一帧都会校验失败。
     */
    private fun reconnectDataPort(): Boolean {
        reconnecting = true
        try {
            runCatching { socket?.close() }
            socket = null
            synchronized(writeLock) {
                configSent = false
                frameCounter = 0L
                lastIdrNals = null
                // 新通道 = 接收端重建解密与解码状态：同样要等一个 IDR 再喂帧。
                beginAwaitingKeyFrame()
            }
            var attempt = 0
            while (running.get() && attempt < DATA_RECONNECT_ATTEMPTS) {
                attempt++
                try {
                    val client = Socket()
                    client.connect(InetSocketAddress(host, dataPort), 2_000)
                    client.tcpNoDelay = true
                    TcpLiveness.configure(client, "视频数据口(重连)", log)
                    socket = client
                    log("VIDEO: 视频通道已重连（第 $attempt 次）——重发 VideoConfig，等下一个 IDR")
                    return true
                } catch (error: Exception) {
                    log("VIDEO: 视频通道重连失败（第 $attempt 次）- ${error.message}")
                    try {
                        Thread.sleep(DATA_RECONNECT_GAP_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
            }
            return false
        } finally {
            reconnecting = false
        }
    }

    /**
     * 启动编码与推流。编码器输入 Surface 通过 [onSurfaceReady] 交给调用方渲染
     * （隐形屏模式下由 DualDisplayComposer 的合成画布填充）。
     */
    fun start(onSurfaceReady: (Surface) -> Unit): Boolean {
        if (running.get()) return true
        return try {
            if (!openDataPort()) {
                stop()
                return false
            }
            val bitrate =
                (
                    ((width * height * 6L).toInt()).coerceIn(2_000_000, 10_000_000) *
                        if (targetFps >= 60) 1.5f else 1f
                ).toInt().coerceAtMost(15_000_000)
            log("VIDEO: 启动编码器 ${width}x$height@$targetFps ${bitrate / 1_000_000}Mbps（端口已连通）")

            val codec =
                openEncoder(bitrate, requestBaseline = true)
                    ?: openEncoder(bitrate, requestBaseline = false)
                    ?: throw IllegalStateException("没有可用的 H.264 编码器")
            val surface = codec.createInputSurface()
            codec.start()
            encoder = codec
            inputSurface = surface
            ended.set(false)
            running.set(true)
            lastKeepAliveAt = android.os.SystemClock.uptimeMillis()

            onSurfaceReady(surface)

            Thread({ drainEncoder() }, "video-drain").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            log("VIDEO: 启动失败 - ${error.message}")
            stop()
            false
        }
    }

    /**
     * 打开并配置 H.264 编码器；[requestBaseline] 为真时**显式声明 Baseline profile**。
     *
     * 为什么要声明：车机侧只解 Baseline。MIB3H（SW 3874 / `CL37_MIB3H_AU_AS_G4x_2302700PROD`）
     * 的 `carplay.json → screen.esogfx` 把解码格式写死为 `VIDEO_H264_BP`，且
     * `cropping/scaling` 全关、只有 5 个 OMX 输入缓冲、`badFramesThreshold: 4`——
     * 编出一帧它不认的 profile 就是直接黑屏，没有降级路径。
     * 我们过去不声明、吃设备默认值：荣耀/高通多数机型默认就是 Baseline，能跑；
     * 但同一份 APK 换到默认 Main/High 的机型上就会「连上了没画面」，日志里还看不出来。
     *
     * 声明失败（编码器不接受 Baseline / 该 level）就返回 null，由调用方退回不声明的老路径——
     * 这部分行为与改动前完全一致，不会因为一次声明把原本能用的机型打死。
     */
    private fun openEncoder(bitrate: Int, requestBaseline: Boolean): MediaCodec? =
        runCatching {
            val format =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    if (requestBaseline) {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                        setInteger(MediaFormat.KEY_LEVEL, avcLevelFor(width, height, targetFps))
                    }
                }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (error: Exception) {
                runCatching { codec.release() }
                throw error
            }
            val detail =
                if (requestBaseline) {
                    "（显式 Baseline / Level ${avcLevelName(avcLevelFor(width, height, targetFps))}）"
                } else {
                    "（未声明 profile，吃设备默认）"
                }
            log("VIDEO: 编码器已配置 ${width}x$height@$targetFps $detail")
            codec
        }.getOrElse { error ->
            if (requestBaseline) {
                log("VIDEO: Baseline 声明未被接受（${error.message}）——退回默认 profile")
            }
            null
        }

    /** 按分辨率/帧率选 H.264 level：低了编码器会拒，高了车机解码器可能不认。 */
    private fun avcLevelFor(width: Int, height: Int, fps: Int): Int {
        val pixels = width.toLong() * height
        return when {
            pixels > 1280L * 720 ->
                if (fps > 30) {
                    MediaCodecInfo.CodecProfileLevel.AVCLevel42
                } else {
                    MediaCodecInfo.CodecProfileLevel.AVCLevel4
                }
            pixels > 800L * 480 -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel3
        }
    }

    /** level 常量的可读写法（日志里 `0x200` 看不出是 3.1）。 */
    private fun avcLevelName(level: Int): String =
        when (level) {
            MediaCodecInfo.CodecProfileLevel.AVCLevel3 -> "3.0"
            MediaCodecInfo.CodecProfileLevel.AVCLevel31 -> "3.1"
            MediaCodecInfo.CodecProfileLevel.AVCLevel4 -> "4.0"
            MediaCodecInfo.CodecProfileLevel.AVCLevel42 -> "4.2"
            else -> "0x%X".format(level)
        }

    fun stop() {
        running.set(false)
        passthrough = false
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { socket?.close() }
        socket = null
    }

    /**
     * 立即编码一个关键帧（车机 `/command` 的 `forceKeyFrame` 请求）。
     *
     * 车机解不出画面时会反复要 I 帧；我们本身每秒一个，但**要帧请求必须立即响应**——
     * 否则车机可能一直卡在「等下一个 I 帧」的状态，表现就是「连上了但没画面」
     * （宝马 iD9 2026-10-20 实测：会话刚起时收到一次 forceKeyFrame）。
     */
    // ---- 外部码流直通（CarLife 劫持分支）----

    /**
     * 直通模式启动：不建 MediaCodec，直接把外部 H.264 帧经 [submitH264] 送进同一条
     * 屏幕流通道（同样加密、同样 128B 头）。用于「本地劫持原生 CarLife 再转发给车机」。
     */
    fun startPassthrough(): Boolean {
        if (running.get()) return true
        if (!openDataPort()) return false
        passthrough = true
        ended.set(false)
        running.set(true)
        lastKeepAliveAt = android.os.SystemClock.uptimeMillis()
        // 新一轮会话：上一轮的「最后一帧」不能拿来重发（车机解码器已重建，
        // 旧 IDR 的 SPS/PPS 与本轮 avcC 可能不一致）。
        lastIdrNals = null
        lastFrameAtMs = 0L
        lastIdleResendAt = 0L
        idleResendCount = 0L
        // 新会话：上一轮的 csd/config 指纹一律作废。
        cachedCsd = null
        sentSps = null
        configSent = false
        // **必须等 IDR**（2026-11-03 ID7 实测）：此前这里是 false，配合下面 attach() 后
        // `seedCsd()` 预置的 cachedCsd，submitH264 会走「用缓存 csd 重发 VideoConfig」
        // 分支把**当前这一帧 P 帧当成新流的首帧**发出去。车机解码器刚重建、一帧参考都没有，
        // 收到 P 帧直接拆整轮会话：15 次断流里有 8 次是「首帧 NAL=1 → 0.1~0.2 秒被 reset」，
        // 而 7 次「首帧 NAL=7,8,5」全部活过 7 秒，无一例外。
        // 预置 csd 仍然有用：它让首个 IDR 一到就能立刻发 config，只是不许放行 P 帧。
        beginAwaitingKeyFrame()
        log("VIDEO: 直通模式启动（外部 H.264，跳过重编码）${width}x$height")
        Thread({ passthroughKeepAliveLoop() }, "video-passthrough-ka").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun passthroughKeepAliveLoop() {
        try {
            while (running.get()) {
                if (reconnecting) {
                    // 重连窗口期 socket 正在被替换：让出，别抢写。
                    Thread.sleep(100)
                    continue
                }
                val output = socket?.getOutputStream()
                if (output == null) {
                    // 不能 `?: return`——那会让保活线程在 socket 短暂为空时永久退出。
                    Thread.sleep(200)
                    continue
                }
                maybeSendKeepAlive(output)
                maybeResendIdleFrame(output)
                Thread.sleep(200)
            }
        } catch (error: Exception) {
            log("VIDEO: 直通 keepAlive 中止 - ${error.message}")
            // 车机侧的会话已经没了（EOF/Broken pipe）：必须复位 running，否则
            // `isRunning` 会一直为 true，接收端重发 0x4301 时被「会话推流中」挡掉，
            // 表现为「车机重连了但画面永远不再出现」（2026-10-24 日志实测）。
            stop()
            // 这条路径同样意味着「会话真的结束了」，必须通知上层——否则
            // `BtSessionGate.sessionActive` 永远停在 true（蓝牙探测被门闸挡住，车机
            // 再邀请也不会重连）、暗幕 45s 后把手机屏变黑、横屏覆盖不复原。
            // 旧实现只在 `drainEncoder` 的 finally 里回调，而当前产品只走直通，
            // 那个方法根本不会执行（2026-11-02 自查）。
            endOnce()
            log("VIDEO: 直通通道已失效——等待车机重新建立会话")
        }
    }

    /**
     * 空闲保活：CarLife 不再出帧时，周期性重发**最后一个 IDR**。
     *
     * 车机侧只要视频流静默太久就判定「源断了」并拆整轮会话（2026-10-25 日志实测：
     * 音频一直在推、视频 0 帧超过 ~30s，车机发 EOF 断开）。CarLife 不产帧的常见
     * 原因是它自己的虚拟屏上没内容（用户还没在 CarLife+ 里点「无线连接」），
     * 但此时**音频和触摸其实都还活着**——被拆掉纯属浪费。
     *
     * 重发 IDR 而不是最后一帧 P 帧：IDR 自包含，重复解码只会得到同一幅画面；
     * P 帧重复会让车机按错误参考帧叠加，出现花屏。
     */
    private fun maybeResendIdleFrame(output: java.io.OutputStream) {
        val nals = lastIdrNals ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (lastFrameAtMs == 0L || now - lastFrameAtMs < IDLE_RESEND_AFTER_MS) return
        if (now - lastIdleResendAt < IDLE_RESEND_INTERVAL_MS) return
        lastIdleResendAt = now
        suppressFrameLog = true
        try {
            sendFrameFromNals(nals, true, output)
            idleResendCount++
            if (idleResendCount == 1L || idleResendCount % 30L == 0L) {
                log(
                    "VIDEO: 已 ${(now - lastFrameAtMs) / 1000}s 没有新画面——重发最后一个 IDR " +
                        "维持车机连接（第 $idleResendCount 次）",
                )
            }
        } catch (error: Exception) {
            log("VIDEO: 空闲保活重发失败 - ${error.message}")
        } finally {
            suppressFrameLog = false
        }
    }

    /**
     * 提交一帧外部 H.264（AnnexB，含 00 00 00 01 起始码）。
     * 首次遇到含 SPS/PPS 的帧时自动发 VideoConfig；返回 false 表示被丢弃。
     */
    /**
     * **CarLife 源重启**：引擎断开重连 / 用户重开一次「无线连接」时由劫持层调用。
     *
     * 场景（2026-10-25 用户实测）：CarPlay 会话一直保持，手机端 CarLife 掉线重连后，
     * 车机再也收不到画面。原因是 `VideoSender` 的会话级状态没跟着源一起重置：
     *
     * - `configSent` 仍是 true → 新码流的 VideoConfig 永远不会再发。新车机解码器 /
     *   已重建的解码器手里是上一轮的 avcC，新 SPS 一比就废（改过分辨率时更必然）。
     * - 旧 `lastIdrNals` 还在 → 空闲保活会把**上一轮的 IDR** 反复送给车机。
     * - 旧源的参考帧与新的完全无关 → 新源开头那几个 P 帧解出来就是花屏。
     *
     * 所以这里清配置态、丢掉旧 IDR、并在新 IDR 到来前拒收 P 帧。
     *
     * ⚠️ **`frameCounter` 绝不能归零**：仍是同一条 AirPlay 视频流、同一把 key，
     * 计数器归零 = ChaCha/AES 的 nonce 复用，车机侧 AEAD 校验必然失败，
     * 表现就是「重连后一帧都过不去」。只有换整轮会话（新 SETUP）才允许归零。
     */
    fun onSourceRestart(reason: String) {
        if (!running.get()) return
        configSent = false
        beginAwaitingKeyFrame()
        // 不在这里清 lastIdrNals：源重启到新源出首帧之间可能有几秒空窗，
        // 旧 IDR 还能继续喂保活，避免车机因「视频静默」拆掉整轮会话。
        // 一旦真的发出新 config，sendConfigFromNals 会立刻把它作废。
        lastFrameAtMs = android.os.SystemClock.uptimeMillis()
        log("VIDEO: 检测到 CarLife 源重启（$reason）——重置配置、等待新 IDR（帧计数保持，nonce 不复用）")
    }

    /**
     * 进入「等下一个 IDR」状态：置位 + 记起始时刻 + 清零丢弃计数。
     *
     * 三处调用点（新会话 [startPassthrough] / 视频通道重连 [reconnectDataPort] /
     * 源重启 [onSourceRestart]）共用，**不要各自赋值**——漏记时间戳会让超时兜底
     * 拿上一轮的旧时刻比较，`submitH264` 会在刚置位的瞬间就判定「已超时」把 P 帧放行。
     */
    private fun beginAwaitingKeyFrame() {
        awaitingKeyFrameSince = android.os.SystemClock.uptimeMillis()
        awaitingKeyFrameAfterRestart = true
        droppedBeforeRestartKey = 0
        // 等 IDR = 换了一路码流的时间基准，排队延迟的基线必须跟着重来。
        // 顺着这里做而不是各调用点各写一遍，理由同上面那两个时间戳。
        deliveryGate.reset()
    }

    /**
     * 用上层缓存的 SPS/PPS（AnnexB，含起始码）预置 csd。
     *
     * 用于「车机重新接上通道、手上暂时没有带 SPS 的关键帧」：此前只能干等引擎下一次
     * 编码器重建（4s 看门狗）才发得出 VideoConfig，这段时间车机就是黑屏。
     * 预置后，**首个 IDR 一到**就能立刻把 VideoConfig 发出去（IDR 常常不带 SPS/PPS），
     * 不用再等下一个带 SPS 的关键帧。
     *
     * ⚠️ 预置 csd **不等于**可以先送 P 帧：新会话必须等 IDR（见 [beginAwaitingKeyFrame] 的
     * 调用点）。2026-11-03 ID7 的 8 次秒断就是这个误解造成的——预置了 csd、
     * 于是 P 帧把 config 带出去、自己也被当首帧发走，车机解码器直接 reset。
     *
     * 已有 csd 时不覆盖：真实码流里的 SPS 永远比缓存更可信。
     */
    fun seedCsd(annexB: ByteArray) {
        if (cachedCsd != null) return
        val nals = splitAnnexB(annexB)
        val sps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 } ?: return
        val pps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 } ?: return
        cachedCsd = listOf(sps, pps)
        log("VIDEO: 已用缓存 SPS/PPS 预置 csd（${sps.size}B/${pps.size}B）")
    }

    /**
     * 直通模式送一帧 H.264（来自 CarLife 引擎）。
     *
     * @param sourceTimestampMs CarLife 帧头 `[4..7]` 的毫秒时间戳（0/负 = 取不到）。
     *   只用来算「排队延迟」——基准未知也没关系，闸门内部取历史最小值当基线。
     *   缓存回放（[CarLifeHijack.attach] 补历史 GOP）**不要**传：那批帧本来就旧，
     *   传进来会被误判成「已经在排队」。
     */
    fun submitH264(
        data: ByteArray,
        keyFrame: Boolean,
        sourceTimestampMs: Long? = null,
    ): Boolean {
        if (!running.get()) return false
        val output = try {
            socket?.getOutputStream()
        } catch (error: Exception) {
            null
        } ?: return false
        return try {
            val nalUnits = splitAnnexB(data)
            if (nalUnits.isEmpty()) {
                if (diagRejects < 3) {
                    diagRejects++
                    log("VIDEO: 直通丢帧——NAL 拆分结果为空 size=${data.size}")
                }
                return false
            }
            val hasSps = nalUnits.any { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
            val isIdr = nalUnits.any { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 5 }
            if (awaitingKeyFrameAfterRestart) {
                val waitedMs = android.os.SystemClock.uptimeMillis() - awaitingKeyFrameSince
                if (!isIdr && waitedMs < WAIT_KEY_FRAME_TIMEOUT_MS) {
                    droppedBeforeRestartKey++
                    if (droppedBeforeRestartKey == 1L || droppedBeforeRestartKey % 30 == 0L) {
                        log(
                            "VIDEO: 等新 IDR——已跳过 $droppedBeforeRestartKey 个非关键帧" +
                                "（已等 ${waitedMs}ms，上限 ${WAIT_KEY_FRAME_TIMEOUT_MS}ms）",
                        )
                    }
                    return false
                }
                awaitingKeyFrameAfterRestart = false
                if (isIdr) {
                    log("VIDEO: 收到新 IDR——恢复送帧（重发 VideoConfig，跳过 ${droppedBeforeRestartKey} 帧）")
                } else {
                    log(
                        "VIDEO: ⚠ 等 IDR 超时（${waitedMs}ms，已跳过 $droppedBeforeRestartKey 帧）" +
                            "——放行当前 P 帧，避免源不出 IDR 时永久黑屏",
                    )
                }
            }
            // 会话中途 SPS 变了（用户在车联里改分辨率 / 引擎重建编码器）：
            // 车机手里的 avcC 已经不对，必须重发一份新的。
            if (configSent && hasSps && !spsMatchesSent(nalUnits)) {
                log("VIDEO: 检测到 SPS 变化——重发 VideoConfig（分辨率/档位已变）")
                configSent = false
            }
            if (!configSent && diagRejects < 3) {
                diagRejects++
                log("VIDEO: 等 SPS 中——本帧 NAL 类型=" +
                    nalUnits.joinToString(",") { "${it[0].toInt() and 0x1F}" } + " hasSps=$hasSps")
            }
            if (!configSent) {
                if (hasSps) {
                    sendConfigFromNals(nalUnits, output)
                } else {
                    // 新源的首个 IDR 没带 SPS/PPS：用上一轮缓存的 csd 兜底重发。
                    // 没这层兜底就是「configSent 永远 false → 每帧丢弃 → 永久黑屏」。
                    val csd = cachedCsd
                    if (csd != null) {
                        log("VIDEO: 新 IDR 未携带 SPS/PPS——用缓存 csd 重发 VideoConfig")
                        sendConfigFromNals(csd, output)
                    } else {
                        droppedBeforeConfig++
                        return false
                    }
                }
            }
            val writeStartMs = android.os.SystemClock.uptimeMillis()
            sendFrameFromNals(nalUnits, keyFrame, output)
            val writtenAtMs = android.os.SystemClock.uptimeMillis()
            // 写入耗时本身就是背压指标：socket 写不动时会直接阻塞在这里，
            // 而这条调用链跑在 CarLife 的读线程上，一卡就是一整条视频通路在卡。
            val verdict = deliveryGate.onFrame(
                nowMs = writtenAtMs,
                sourceTsMs = sourceTimestampMs,
                writeMs = writtenAtMs - writeStartMs,
                keyFrame = keyFrame || isIdr,
            )
            if (verdict.resync) {
                log("VIDEO: ⚠ ${verdict.reason}")
                // 跳到下一个 IDR：丢弃此后的 P 帧直到新 IDR 到达（≤1 个 GOP，引擎配的是 1s）。
                // 不复位 frameCounter——nonce 复用会让车机整轮 AEAD 校验失败。
                beginAwaitingKeyFrame()
            }
            deliveryGate.summaryIfDue(writtenAtMs)?.let { log(it.describe()) }
            lastFrameAtMs = writtenAtMs
            if (keyFrame || nalUnits.any { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 5 }) {
                lastIdrNals = nalUnits
            }
            true
        } catch (error: Exception) {
            log("VIDEO: 直通送帧失败 - ${error.message}")
            // 先试着在接收端的 8s 恢复窗口内重连视频通道；连不回来才认输复位，
            // 让下一轮 0x4301 重新起会话。
            if (reconnectDataPort()) return false
            stop()
            false
        }
    }

    /** 编码器不存在时（直通模式）也允许请求关键帧：直接返回 true，由上游自行发 IDR。 */
    fun requestKeyFrame(): Boolean {
        if (passthrough) return true
        val codec = encoder ?: return false
        return try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
            true
        } catch (error: Exception) {
            log("VIDEO: 请求关键帧失败 - ${error.message}")
            false
        }
    }

    /**
     * 把一行提示文字渲染成单帧 H.264 推到车机——用于「CarLife 没劫持到画面」时
     * 在车机屏幕上显示文字，代替黑屏。
     *
     * 只在直通模式下有意义（此时 VideoSender 不持有编码器，画面来自外部 H.264）。
     * 编码失败一律返回 false，调用方保留「仅日志」兜底即可。
     */
    fun pushNotice(lines: List<String>): Boolean {
        if (!running.get() || !passthrough) return false
        val frame = runCatching { NoticeFrameEncoder.encodeNotice(width, height, lines) }
            .getOrElse { error ->
                // 带异常类型与调用栈：MediaCodec 的厂商异常 message 经常语焉不详（如"10 > -2145395697"），
                // 只有类型+堆栈才能一次定位。
                val top = error.stackTrace.take(5).joinToString(" ← ") {
                    "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
                }
                log("VIDEO: 通知帧编码异常 - ${error.javaClass.simpleName}: ${error.message} @ $top")
                return false
            } ?: run {
                log("VIDEO: 通知帧编码失败（编码器返回空）——保留日志兜底")
                return false
            }
        // SPS/PPS + IDR 拼成一段 AnnexB 一次提交：submitH264 会先发 VideoConfig 再发帧，
        // 并把这一帧记成 lastIdrNals，空闲保活会持续重发维持显示，直到 CarLife 接管。
        val sent = submitH264(frame.csd + frame.idr, true)
        if (sent) {
            log("VIDEO: 已向车机推送提示帧（${lines.firstOrNull() ?: ""}）")
        } else {
            log("VIDEO: 通知帧送出失败（通道可能正在重连）")
        }
        return sent
    }

    // ---- 编码输出 → 协议封装发送 ----

    private fun drainEncoder() {
        val codec = encoder ?: return
        val output = socket?.getOutputStream() ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val index =
                    try {
                        codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    } catch (error: Exception) {
                        // 编码器进错误态（如非法尺寸首帧）：必须留痕——静默 break 曾让
                        // 「零帧推流 4 秒后断开」无从排查（接收端只见 peer EOF）
                        log("VIDEO: 编码器取帧异常，停止推流 - ${error.message}")
                        break
                    }
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        maybeSendKeepAlive(output)
                        continue
                    }

                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index) ?: continue
                        val flags = bufferInfo.flags
                        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            sendVideoConfig(buffer, bufferInfo.size, output)
                        } else if (bufferInfo.size > 0) {
                            sendVideoFrame(buffer, bufferInfo.size, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, output)
                        }
                        codec.releaseOutputBuffer(index, false)
                        maybeSendKeepAlive(output)
                    }
                }
            }
        } catch (error: Exception) {
            // 视频数据口断开（如接收端屏幕关闭收摊会话）：只终止推流，绝不冒泡——
            // 后台线程未捕获异常会杀死整个进程（曾导致锁屏后 app 消失）
            log("VIDEO: 推流中止 - ${error.message}（等待接收端恢复后重连）")
        } finally {
            stop()
            // 会话真实结束信号（静默降噪解除 / 状态收尾）
            endOnce()
        }
    }

    /**
     * 会话结束回调（只触发一次）。
     *
     * 两条收尾路径都可能到达：直通保活线程写失败、编码帧线程退出。
     */
    private fun endOnce() {
        if (!ended.compareAndSet(false, true)) return
        runCatching { onEnded?.invoke() }
    }

    /**
     * 屏幕流 keepAlive 帧（catplay `ScreenTransmitSession::create_keep_alive`，1s 间隔）。
     *
     * 与 UDP keepAlive / RTSP feedback 是三条相互独立的存活信号；接收端（奥迪 3315）
     * 只看视频帧不够——缺这条时 RECORD 后约 10s 会 reset 整轮会话（2026-10-19 两轮
     * 实测）。帧是**明文**的（catplay 仅对 opcode=VideoFrame 加密），因此不消耗 ChaCha
     * 帧计数；带统计体时 opcode=5，并把 body 长度写进头部 params[14] 的后 4 字节
     * （f32 LE），与 catplay 逐字节一致。
     */
    private fun maybeSendKeepAlive(output: java.io.OutputStream) {
        if (!running.get()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastKeepAliveAt < KEEPALIVE_INTERVAL_MS) return
        val elapsedNanos = (now - lastKeepAliveAt).coerceAtLeast(1L) * 1_000_000.0
        val frames = framesSinceKeepAlive
        val bytes = bytesSinceKeepAlive
        val framesPerSecond = (frames * 1_000_000_000.0 / elapsedNanos).toInt()
        val bytesPerSecond = bytes * 1_000_000_000.0 / elapsedNanos
        framesSinceKeepAlive = 0
        bytesSinceKeepAlive = 0
        lastKeepAliveAt = now
        keepAliveCount++
        val frame = buildKeepAliveFrame(keepAliveWithStats, frames, bytesPerSecond, framesPerSecond)
        synchronized(writeLock) {
            output.write(frame)
            output.flush()
        }
        if (keepAliveCount <= 3 || keepAliveCount % 30 == 0L) {
            log(
                "VIDEO: keepAlive #$keepAliveCount " +
                    "opcode=${if (keepAliveWithStats) OP_KEEP_ALIVE_WITH_BODY else OP_KEEP_ALIVE} " +
                    "body=${frame.size - HEADER_LEN}B fps=$framesPerSecond",
            )
        }
    }

    private fun sendVideoConfig(
        buffer: java.nio.ByteBuffer,
        size: Int,
        output: java.io.OutputStream,
    ) {
        val annexB = ByteArray(size)
        buffer.get(annexB)
        sendConfigFromNals(splitAnnexB(annexB), output)
    }

    /** 本帧携带的 SPS 是否与已发出的那份一致（true = 无需重发 config）。 */
    private fun spsMatchesSent(nalUnits: List<ByteArray>): Boolean {
        val sps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 } ?: return true
        val sent = sentSps ?: return false
        return sent.contentEquals(sps)
    }

    /** 从一组 NAL 里取 SPS/PPS 组装 avcC 并发出 VideoConfig。 */
    private fun sendConfigFromNals(
        nalUnits: List<ByteArray>,
        output: java.io.OutputStream,
    ) {
        val sps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
        val pps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
        if (sps == null || pps == null) {
            log("VIDEO: csd 缺少 SPS/PPS（${nalUnits.size} 个 NAL），跳过 config")
            return
        }
        // 记住这份 csd：源重启后新 IDR 若不带 SPS/PPS，靠它兜底重发 config。
        cachedCsd = listOf(sps, pps)
        sentSps = sps.copyOf()
        // 换参数后旧 IDR 与新车机 avcC 不再匹配，不能再用它做空闲重发。
        lastIdrNals = null
        // 关键：VideoConfig 头部声明的宽高**必须与车机解码器画布一致**，否则真车
        // （Audi/DELPHI）按声明画布建解码器后解不出画面（概率性黑屏，2026-10-19 实测）。
        //
        // 取值优先级：
        //  **车机画布（[canvasSize]）> 引擎码流（[liveSize]）> 构造值 > SPS**，SPS 仅作诊断。
        //
        //  2026-10-25 曾改成「SPS 优先」（理由：声明应等于码流真实尺寸，否则拉伸），
        //  真机复测出问题已回滚。原因：车机是**按 1412×654 建解码器与 surface** 的，
        //  声明改成 SPS 的 1920×1080 后，车机侧画布/纹理对不上 → 画面异常。
        //  结论：这条链路里「车机画布」才是契约，不能让码流尺寸反过来改写声明。
        //
        //  2026-11-04 奥迪 3810（1412×654 画布）黑屏复盘：直通模式把优先级做成
        //  「引擎码流优先」，引擎白名单把 1412×654@60 降档成 1280×720@20，于是
        //  声明变成 720p ≠ 画布 → 车机建了 720p 解码器 → 整屏黑；而会话、送帧、
        //  写入耗时全部正常，测试台（WinPlay/DiPlay 不校验这几个头字段）照样有画面。
        val canvas = canvasSize?.invoke()
        val live = liveSize?.invoke()
        val parsed = parseSpsDimensions(sps)
        val spsSane = parsed != null &&
            parsed.first in 320..4096 && parsed.second in 180..2304
        val (cfgWidth, cfgHeight) = resolveConfigSize(canvas, live, width, height, parsed)
        if (parsed != null && (parsed.first != cfgWidth || parsed.second != cfgHeight)) {
            log(
                "VIDEO: SPS 解析 ${parsed.first}x${parsed.second}" +
                    "（${if (spsSane) "宏块对齐值，仅诊断" else "不合理已忽略"}），" +
                    "VideoConfig 声明 ${cfgWidth}x$cfgHeight" +
                    "\n  ↳ SPS ${sps.size}B=" + sps.joinToString(" ") { "%02x".format(it) },
            )
        }
        val streamSize = live?.takeIf { it.first > 0 && it.second > 0 } ?: (width to height)
        if (!canvasMismatchLogged && (streamSize.first != cfgWidth || streamSize.second != cfgHeight)) {
            canvasMismatchLogged = true
            log(
                "VIDEO: ⚠ 声明的画布 ${cfgWidth}x$cfgHeight 与码流 ${streamSize.first}x${streamSize.second} 不一致" +
                    "——车机按画布建解码器，画面由车机侧缩放；若仍黑屏，说明这台车要求码流尺寸本身等于画布" +
                    "（需让 CarLife 引擎按 ${cfgWidth}x$cfgHeight 编码）",
            )
        }
        val avcc = ByteArrayOutputStream(8 + sps.size + pps.size)
        avcc.write(1)
        avcc.write(sps[1].toInt()) // profile
        avcc.write(sps[2].toInt()) // compat
        avcc.write(sps[3].toInt()) // level
        avcc.write(0xFF) // 6 位保留 + 2 位长度尺寸-1（4 字节）
        avcc.write(0xE1) // 3 位保留 + 5 位 SPS 数
        avcc.write((sps.size ushr 8) and 0xFF)
        avcc.write(sps.size and 0xFF)
        avcc.write(sps)
        avcc.write(1)
        avcc.write((pps.size ushr 8) and 0xFF)
        avcc.write(pps.size and 0xFF)
        avcc.write(pps)
        val body = avcc.toByteArray()
        // 头部必须与 catplay `ScreenFrame::config` 一致，否则部分车机（Audi/DELPHI 实测：
        // 连接与触摸都正常、车机屏全黑）会把后续加密帧按明文 NAL 解析 → 解码失败：
        //  - small_param[1] |= ScreenFlag::Encrypted(0x04)：声明「视频帧是加密的」
        //  - params[1] = (width, height) 两个 f32 LE：车机据此建立解码器画布
        //  - params[0] 归零（catplay 的 config 不带 PTS；只有 VideoFrame 才写 params[0]）
        // 旧实现三项都没写——DiPlay/WinPlay 只读 body、不校验头，所以测试台一直正常，
        // 真车（Audi 4851）却黑屏（2026-10-19 用户日志）。
        val header = buildVideoConfigHeader(body.size, cfgWidth, cfgHeight)
        synchronized(writeLock) {
            // config 帧是明文，但同样不能插在别的帧中间（否则接收端按半帧解析）。
            output.write(header)
            output.write(body)
            output.flush()
            configSent = true
        }
        log(
            "VIDEO: ✓ VideoConfig 已发送 avcC=${body.size}B（SPS ${sps.size}B/PPS ${pps.size}B，" +
                "profile=${sps[1].toInt() and 0xFF} level=${sps[3].toInt() and 0xFF} " +
                "flags=0x%02x ${cfgWidth}x$cfgHeight）".format(header[6].toInt() and 0xFF) +
                "\n  ↳ 头部前 24 字节=" + header.take(24).joinToString(" ") { "%02x".format(it) },
        )
    }

    /**
     * 从 H.264 SPS NAL 解析画面宽高（去掉 0x67 起始码后的 RBSP）。
     *
     * 按 ITU-T H.264 语法：跳过 profile/constraints/level（3 字节）→ 读 exp-Golomb
     * 得到 seq_parameter_set_id → 若 profile 是 100/110/122/244/44/83/86/118/128/138/139/134
     * 则先读 chroma_format_idc + 可能的扩展 → log2_max_frame_num_minus4 →
     * pic_order_cnt_type 相关 → max_num_ref_frames → gaps_in_frame_num_value_allowed_flag →
     * pic_width_in_mbs_minus1 → pic_height_in_map_units_minus1 → frame_mbs_only_flag 等。
     * 返回 (宽, 高)，解析失败返回 null。
     */
    private fun parseSpsDimensions(sps: ByteArray): Pair<Int, Int>? {
        if (sps.size < 8) return null
        return runCatching {
            // 1) 重建 RBSP：跳过 NAL 头，并剔除 emulation prevention bytes（00 00 03 → 00 00）。
            //    这步是必须的——SPS 里普遍存在 `… 00 00 03 00 01 …` 的转义字节，不去掉会让
            //    bit 流整体错位（2026-10-25 实测：漏这步解出 272x112 之类的垃圾值）。
            val rbsp = ArrayList<Byte>(sps.size)
            var zeroRun = 0
            for (k in 1 until sps.size) {
                val b = sps[k].toInt() and 0xFF
                if (zeroRun >= 2 && b == 0x03) {
                    zeroRun = 0
                    continue
                }
                rbsp.add(b.toByte())
                zeroRun = if (b == 0) zeroRun + 1 else 0
            }
            val d = rbsp.toByteArray()
            val totalBits = d.size * 8
            var bit = 0

            fun readBit(): Int {
                require(bit < totalBits) { "SPS 越界" }
                val v = (d[bit / 8].toInt() ushr (7 - (bit % 8))) and 1
                bit++
                return v
            }

            fun readBits(n: Int): Int {
                var v = 0
                repeat(n) { v = (v shl 1) or readBit() }
                return v
            }

            /** exp-Golomb 无符号（ue(v)）：`[zeros 个 0] 1 [zeros 位 info]`。 */
            fun readUe(): Int {
                var zeros = 0
                while (readBit() == 0) zeros++
                var info = 0
                repeat(zeros) { info = (info shl 1) or readBit() }
                return (1 shl zeros) - 1 + info
            }

            /** exp-Golomb 有符号（se(v)）：偶数映射负、奇数映射正。 */
            fun readSe(): Int {
                val ue = readUe()
                val mag = (ue + 1) / 2
                return if (ue % 2 == 0) -mag else mag
            }

            val profileIdc = readBits(8)
            readBits(8) // constraint_set0..5 + reserved
            readBits(8) // level_idc
            readUe() // seq_parameter_set_id

            // High 系列 profile 才有 chroma_format_idc / scaling matrix 扩展
            if (profileIdc in intArrayOf(
                    100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135,
                )
            ) {
                val chromaFormatIdc = readUe()
                if (chromaFormatIdc == 3) readBit() // separate_colour_plane_flag
                readUe() // bit_depth_luma_minus8
                readUe() // bit_depth_chroma_minus8
                readBit() // qpprime_y_zero_transform_bypass_flag
                if (readBit() == 1) { // seq_scaling_matrix_present_flag
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    repeat(count) { idx ->
                        if (readBit() == 1) { // seq_scaling_list_present_flag
                            val size = if (idx < 6) 16 else 64
                            var lastScale = 8
                            var nextScale = 8
                            repeat(size) {
                                if (nextScale != 0) {
                                    nextScale = (lastScale + readSe() + 256) % 256
                                }
                                lastScale = if (nextScale == 0) lastScale else nextScale
                            }
                        }
                    }
                }
            }

            readUe() // log2_max_frame_num_minus4
            when (readUe()) { // pic_order_cnt_type
                0 -> readUe() // log2_max_pic_order_cnt_lsb_minus4
                1 -> {
                    readBit() // delta_pic_order_always_zero_flag
                    readSe() // offset_for_non_ref_pic
                    readSe() // offset_for_top_to_bottom_field
                    val numRefFrames = readUe()
                    require(numRefFrames in 0..255) { "num_ref_frames 异常 $numRefFrames" }
                    repeat(numRefFrames) { readSe() }
                }
            }
            readUe() // max_num_ref_frames
            readBit() // gaps_in_frame_num_value_allowed_flag
            val widthMbs = readUe() + 1
            val heightMapUnits = readUe() + 1
            val frameMbsOnly = readBit()
            if (frameMbsOnly == 0) readBit() // mb_adaptive_frame_field_flag
            readBit() // direct_8x8_inference_flag

            var width = widthMbs * 16
            var height = heightMapUnits * 16 * (if (frameMbsOnly == 1) 1 else 2)
            // frame_cropping：编码器常把画面 padding 到宏块边界，再用裁剪字段声明真实尺寸。
            // 例如 1412x654 会被编码成 1424x656，再裁掉 12x2——不处理就会得到对齐值。
            if (readBit() == 1) {
                val cropLeft = readUe()
                val cropRight = readUe()
                val cropTop = readUe()
                val cropBottom = readUe()
                width -= (cropLeft + cropRight) * 2
                height -= (cropTop + cropBottom) * 2 * (if (frameMbsOnly == 1) 1 else 2)
            }
            width to height
        }.getOrNull()
    }

    private fun sendVideoFrame(
        buffer: java.nio.ByteBuffer,
        size: Int,
        keyFrame: Boolean,
        output: java.io.OutputStream,
    ) {
        val annexB = ByteArray(size)
        buffer.get(annexB)
        sendFrameFromNals(splitAnnexB(annexB), keyFrame, output)
    }

    /** 把一组 NAL 打包成 4B 长度前缀流，加密后作为视频帧发出。 */
    private fun sendFrameFromNals(
        nalUnits: List<ByteArray>,
        keyFrame: Boolean,
        output: java.io.OutputStream,
    ) {
        if (!configSent) {
            log("VIDEO: 丢弃一帧（config 尚未发送）")
            return
        }
        if (nalUnits.isEmpty()) return
        val size = nalUnits.sumOf { it.size + 4 }

        val packed = ByteArrayOutputStream(size)
        for (nal in nalUnits) {
            packed.write((nal.size ushr 24) and 0xFF)
            packed.write((nal.size ushr 16) and 0xFF)
            packed.write((nal.size ushr 8) and 0xFF)
            packed.write(nal.size and 0xFF)
            packed.write(nal)
        }
        val plain = packed.toByteArray()

        // 加密方式按会话的 et 选择（catplay ScreenFrameCodec）：
        //  - ChaCha：header 作 AAD，body 长度含 16B tag；
        //  - AES-128-CTR：无 tag、不改长度，密钥流跨帧连续。
        // keepAlive/config 帧在两种模式下都不加密。头只能构造一次——ChaCha 的 AAD
        // 必须是最终发出的那 128 字节（重复构造会带不同时间戳，对端解密必然失败）。
        // 构造头 + 加密 + 写字节 + 计数器自增必须整体串行（见 [writeLock]）。
        synchronized(writeLock) {
            val header =
                buildHeader(
                    OP_FRAME,
                    if (frameCipher is StreamFrameCipher.ChaCha) plain.size + TAG_SIZE else plain.size,
                )
            val sealed =
                when (val cipher = frameCipher) {
                    is StreamFrameCipher.ChaCha ->
                        AirPlayCrypto.chachaSeal(cipher.key, AirPlayCrypto.nonce64(frameCounter), plain, header)
                    is StreamFrameCipher.AesCtr -> {
                        val encrypted = plain.copyOf()
                        aesStream?.applyKeystream(encrypted)
                        encrypted
                    }
                }
            frameCounter++
            output.write(header)
            output.write(sealed)
            output.flush()
        }
        framesSent++
        framesSinceKeepAlive++
        bytesSinceKeepAlive += plain.size.toLong()
        if (!suppressFrameLog && (keyFrame || framesSent <= 3 || framesSent % 150 == 0L)) {
            log("VIDEO: 帧 #$framesSent ${plain.size}B${if (keyFrame) " (key)" else ""} 已发送")
        }
    }

    /** 128 字节消息头：u32LE body 长度、opcode、NTP 32.32 LE 时间戳，其余为零。 */
    private fun buildHeader(
        opcode: Int,
        bodyLength: Int,
    ): ByteArray {
        val header = ByteArray(HEADER_LEN)
        header[0] = (bodyLength and 0xFF).toByte()
        header[1] = ((bodyLength ushr 8) and 0xFF).toByte()
        header[2] = ((bodyLength ushr 16) and 0xFF).toByte()
        header[3] = ((bodyLength ushr 24) and 0xFF).toByte()
        header[4] = opcode.toByte()
        val nanos = System.nanoTime()
        val seconds = nanos / 1_000_000_000L
        val fraction = ((nanos % 1_000_000_000L) shl 32) / 1_000_000_000L
        val ntp = (seconds shl 32) or fraction
        for (index in 0 until 8) {
            header[8 + index] = ((ntp ushr (8 * index)) and 0xFF).toByte()
        }
        return header
    }

    private fun writeMessage(
        opcode: Int,
        body: ByteArray,
        declaredLength: Int,
        output: java.io.OutputStream,
    ) {
        synchronized(writeLock) {
            output.write(buildHeader(opcode, declaredLength))
            output.write(body)
            output.flush()
        }
    }

    /** 按 00 00 00 01 / 00 00 01 起始码切分 AnnexB 码流为 NAL 列表。 */
    private fun splitAnnexB(bytes: ByteArray): List<ByteArray> {
        val units = ArrayList<ByteArray>()
        val starts = ArrayList<Int>()
        var index = 0
        while (index + 3 < bytes.size) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                if (bytes[index + 2] == 1.toByte()) {
                    starts += index
                    index += 3
                    continue
                }
                if (index + 3 < bytes.size && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte()) {
                    starts += index
                    index += 4
                    continue
                }
            }
            index++
        }
        if (starts.isEmpty()) {
            if (bytes.isNotEmpty()) units += bytes
            return units
        }
        for (unit in 0 until starts.size) {
            val start = starts[unit]
            val prefix = if (bytes[start + 2] == 1.toByte()) 3 else 4
            val bodyStart = start + prefix
            val bodyEnd = if (unit + 1 < starts.size) starts[unit + 1] else bytes.size
            if (bodyEnd > bodyStart) units += bytes.copyOfRange(bodyStart, bodyEnd)
        }
        return units
    }

    companion object {
        private const val HEADER_LEN = 128
        private const val OP_FRAME = 0
        private const val OP_CONFIG = 1
        private const val OP_KEEP_ALIVE = 2
        private const val OP_KEEP_ALIVE_WITH_BODY = 5
        private const val TAG_SIZE = 16

        /** 视频通道被重置后的重连尝试次数（接收端只等 8s，2s 超时 × 3 次刚好留余量）。 */
        private const val DATA_RECONNECT_ATTEMPTS = 3

        /**
         * 「等首个 IDR」的最长等待（ms）。
         *
         * 等 IDR 是为了不让车机拿新流的 P 帧去参考旧参考帧（那是 0.1 秒被拆的直接原因，
         * 见 `docs/id7-blackscreen-diagnosis.md`）。但**不能无限等**——源若迟迟不出 IDR
         * （引擎卡住、用户刚进 CarLife 还没开始编码），死等就是永久黑屏。CarLife 的
         * GOP 实测约 2 秒一帧 I，3 秒还没等到说明源有问题，此时放行比死等好：
         * 画面可能花一下，但至少链路还在，下一个 IDR 会自动修正。
         */
        private const val WAIT_KEY_FRAME_TIMEOUT_MS = 3_000L

        /** 两次重连之间的间隔。 */
        private const val DATA_RECONNECT_GAP_MS = 500L

        /**
         * 头部位标志（catplay `ScreenFlag`）：写进 `small_param[1]`（头部偏移 6）。
         * `Encrypted = 1<<2`：视频帧是 ChaCha 密封的；VideoConfig 里必须声明，
         * 否则车机按明文 NAL 解析 → 解码失败黑屏（会话与触摸仍正常）。
         */
        private const val SCREEN_FLAG_ENCRYPTED = 0x04

        /** VideoConfig 头部 `params[1]` 的偏移：8 + 1×8，两个 f32 LE = 宽、高。 */
        private const val CONFIG_WIDTH_HEIGHT_OFFSET = 16

        /** 屏幕流 keepAlive 间隔（catplay `DEFAULT_KEEP_ALIVE` = 1s）。 */
        private const val KEEPALIVE_INTERVAL_MS = 1_000L

        /** 直通空闲多久后开始重发 IDR（毫秒）。 */
        private const val IDLE_RESEND_AFTER_MS = 1_500L

        /** 空闲重发间隔（毫秒）：2s 一次足够撑住车机的存活判定，也不会白烧带宽。 */
        private const val IDLE_RESEND_INTERVAL_MS = 2_000L

        /**
         * 构造屏幕流 keepAlive 帧（明文头 + 可选 bplist 统计体）。
         *
         * 拆成纯函数是为了单测：帧格式一旦写错（opcode/body 长度字段/统计体键名），
         * 接收端会静默忽略而不报错——现象仍是 10 秒后被拆链，排查成本极高。
         */
        internal fun buildKeepAliveFrame(
            withStats: Boolean,
            frames: Int,
            bytesPerSecond: Double,
            framesPerSecond: Int,
        ): ByteArray {
            val body =
                if (withStats) {
                    // 字段名对照 catplay ScreenSenderStats（serde camelCase）
                    BinaryPlist.encode(
                        linkedMapOf(
                            "txUsageAvg" to bytesPerSecond,
                            "encoderCurrentFPS" to framesPerSecond.toLong(),
                            "sentFramesAvg" to frames.toLong(),
                            "queuedFramesAvg" to 0L,
                            "lossAvg" to 0.0,
                        ),
                    )
                } else {
                    ByteArray(0)
                }
            val header = ByteArray(HEADER_LEN)
            // 头部布局：u32LE body 长度、u8 opcode、3 字节小参数、15 × 8 字节参数
            header[0] = (body.size and 0xFF).toByte()
            header[1] = ((body.size ushr 8) and 0xFF).toByte()
            header[2] = ((body.size ushr 16) and 0xFF).toByte()
            header[3] = ((body.size ushr 24) and 0xFF).toByte()
            header[4] = (if (body.isEmpty()) OP_KEEP_ALIVE else OP_KEEP_ALIVE_WITH_BODY).toByte()
            if (body.isNotEmpty()) {
                // params[14]（偏移 8+14*8=120）：前 4 字节 0.0f，后 4 字节 body 长度 f32 LE
                writeLittleEndianFloat(header, 120, 0f)
                writeLittleEndianFloat(header, 124, body.size.toFloat())
            }
            return if (body.isEmpty()) header else header + body
        }

        /**
         * 决定 VideoConfig 头里声明的画布尺寸（纯函数，便于单测）。
         *
         * 优先级：车机画布 → 引擎码流 → 构造值 → SPS 解析值。
         *
         * 前两级的先后顺序是这个函数的全部意义：真车按声明建解码器，画布是契约；
         * 引擎码流只是「实际编出来多少」，它比画布小的时候（白名单降档）如果拿它
         * 去声明，车机就会拿着一个比屏幕小的画布去建解码器 → 黑屏。
         */
        internal fun resolveConfigSize(
            canvas: Pair<Int, Int>?,
            live: Pair<Int, Int>?,
            fallbackWidth: Int,
            fallbackHeight: Int,
            sps: Pair<Int, Int>?,
        ): Pair<Int, Int> {
            canvas?.let { if (it.first > 0 && it.second > 0) return it }
            live?.let { if (it.first > 0 && it.second > 0) return it }
            if (fallbackWidth > 0 && fallbackHeight > 0) return fallbackWidth to fallbackHeight
            sps?.let { if (it.first > 0 && it.second > 0) return it }
            return fallbackWidth to fallbackHeight
        }

        /**
         * VideoConfig 帧头（catplay `ScreenFrame::config` 逐字节对齐）：
         *  - `small_param[1]`（偏移 6）置 Encrypted(0x04) —— 声明后续视频帧是密封的
         *  - `params[1]`（偏移 16）= 宽、高两个 f32 LE —— 车机据此建解码器画布
         *  - `params[0]`（偏移 8..16）保持 0 —— config 不带 PTS
         *
         * 拆成纯函数是为了单测：真车（Audi 4851）缺这两项时**会话不断、触摸正常、屏全黑**，
         * 日志里看不出任何错误，极难排查。
         */
        internal fun buildVideoConfigHeader(
            bodySize: Int,
            width: Int,
            height: Int,
        ): ByteArray {
            val header = ByteArray(HEADER_LEN)
            header[0] = (bodySize and 0xFF).toByte()
            header[1] = ((bodySize ushr 8) and 0xFF).toByte()
            header[2] = ((bodySize ushr 16) and 0xFF).toByte()
            header[3] = ((bodySize ushr 24) and 0xFF).toByte()
            header[4] = OP_CONFIG.toByte()
            header[6] = SCREEN_FLAG_ENCRYPTED.toByte()
            for (index in 0 until 4) {
                val widthBits = width.toFloat().toRawBits()
                header[CONFIG_WIDTH_HEIGHT_OFFSET + index] = ((widthBits ushr (8 * index)) and 0xFF).toByte()
                val heightBits = height.toFloat().toRawBits()
                header[CONFIG_WIDTH_HEIGHT_OFFSET + 4 + index] = ((heightBits ushr (8 * index)) and 0xFF).toByte()
            }
            return header
        }

        private fun writeLittleEndianFloat(
            target: ByteArray,
            offset: Int,
            value: Float,
        ) {
            val bits = value.toRawBits()
            for (index in 0 until 4) {
                target[offset + index] = ((bits ushr (8 * index)) and 0xFF).toByte()
            }
        }
    }
}
