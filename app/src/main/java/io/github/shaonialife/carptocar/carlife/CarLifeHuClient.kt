package io.github.shaonialife.carptocar.carlife

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import io.github.shaonialife.carptocar.util.LogThrottle

/**
 * 本机 CarLife **车机端（HU）** 客户端。
 *
 * 手机上的 CarLife 引擎（`com.baidu.carlife.honor` 等）在「无线连接」态会监听 7 个
 * TCP 端口。本类从 **127.0.0.1** 连上去扮演车机，完成握手后即可收到手机推送的
 * H.264 码流——这就是「本地劫持原生 CarLife」的数据面。
 *
 * 握手顺序（2026-10-23 实机验证，缺一步都不出画面）：
 * ```
 * -> 98305 (协议 3.0)   <- 65538 match=1
 * -> 98343 (统计信息)   <- 65563/65540/65574
 * -> 98307 (HU_INFO)    <- 65574 模块状态 -> 回 98344 全部置 1
 * -> 98386 (特征配置)   (主动推，不等手机)
 * -> 98311 (视频初始化) <- 65544 手机确认编码参数
 * -> 98313 (启动推流)   <- 8240 开始推 H.264
 * 每 1s -> 131074 心跳
 * ```
 *
 * 音频走 9240（媒体）/ 9241（TTS）两条**裸字节流**：每包 12B 包头
 * `[len][timestamp][type]` + 负载，type=0x30001 是格式宣告（CarlifeMusicInit），
 * 0x30006 是裸 PCM。音频不需要车机主动请求——手机开始播放媒体就会推。
 */
class CarLifeHuClient(
    private val host: String = "127.0.0.1",
    private val log: (String) -> Unit,
    /**
     * 一节视频帧。
     *
     * @param sourceTimestampMs 引擎帧头 `[4..7]` 的毫秒时间戳，原样带过去（不做基准换算）。
     *   下游 [io.github.shaonialife.carptocar.video.VideoDeliveryGate] 用它算排队延迟。
     */
    private val onVideoFrame: (data: ByteArray, keyFrame: Boolean, sourceTimestampMs: Long) -> Unit,
    private val onVideoParams: (width: Int, height: Int, fps: Int) -> Unit = { _, _, _ -> },
    /**
     * **码流真实分辨率**（SPS 解析值）确认/变更时回调。
     *
     * 与 [onVideoParams] 的区别是硬性的：那是「跟引擎谈定的档位」，这是「引擎真正编了
     * 多大」。两者经常不等——申请 1412×654 时引擎按白名单降档实编 1280×720
     * （2026-10-29 日志实证），界面若拿协商值当码流尺寸就会显示「码流同尺寸」，
     * 与车机上看到的画面自相矛盾。这个回调只在 SPS 解析成功后才触发。
     */
    private val onStreamSize: (width: Int, height: Int) -> Unit = { _, _ -> },
    /** 手机宣告音频格式（采样率, 声道数）。 */
    private val onAudioFormat: (sampleRate: Int, channels: Int) -> Unit = { _, _ -> },
    /** 收到一包裸 PCM（16bit 交织，size 为有效字节数）。 */
    private val onAudioPcm: (pcm: ByteArray, size: Int) -> Unit = { _, _ -> },
    /** TTS 通道（9241）宣告音频格式。导航播报通常是独立的采样率/声道，与媒体分开记。 */
    private val onTtsFormat: (sampleRate: Int, channels: Int) -> Unit = { _, _ -> },
    /**
     * TTS 通道（9241）的裸 PCM —— **导航播报就走这里**。
     *
     * 2026-10-27 真机日志定谳：0x40003 带 1280B 负载 = 导航播报 PCM；旧实现把它归到
     * 「暂不处理」直接丢掉，于是「放音乐有声、开导航没声」。TTS 与媒体是两条独立流，
     * 采样率也可能不同（TTS 常见 16k 单声道），所以必须各自带格式送进发送端重采样。
     */
    private val onTtsPcm: (pcm: ByteArray, size: Int) -> Unit = { _, _ -> },
    /**
     * 引擎音频**开停信号**（0x30002 开始 / 0x30003 停止，长度 0 的裸类型包）。
     *
     * 真机实证（2026-10-26，奥迪 MIB3）：这两个包出现在「引擎音频真正开始/停止推 PCM」
     * 的同一瞬间——来电期间只有 0x30003、挂断恢复播放时才有 0x30002。上层据此在恢复时
     * 补发同步包重新对齐车机时钟（[io.github.shaonialife.carptocar.audio.AudioSender.resyncAnchor]）。
     * 只当「信号」用，不作为是否转发 PCM 的裁决——停止后万一还有 PCM 仍然照常转发。
     */
    private val onAudioGate: (active: Boolean) -> Unit = {},
    /**
     * 外部「此刻**应该**有声音」的信号（上层按音乐会话是否在播判断）。
     *
     * 只用于音频沉默看门狗 [audioSupervisor]：9240 活着但长时间零 PCM 时，必须先确认
     * 「用户确实在放东西」才值得重连——否则人只是停在桌面没开音乐，也会被当成故障反复
     * 重连，白白打断引擎的音频管道。默认 false = 永不因沉默重连（只有断链才重连）。
     */
    private val audioExpected: () -> Boolean = { false },
    /** 收到手机推送的媒体信息（歌名/歌手/封面，0x10035）。 */
    private val onMediaInfo: (info: CarLifeMediaInfo) -> Unit = {},
    private val connectTimeoutMs: Int = 3_000,
    /**
     * 向 CarLife 引擎申请的编码帧率（98311 的 fps 字段 + 98316 帧率变更）。
     *
     * 0.2.9 方案 A：引擎的编码器按此值配（申请 30 就只给约 20~30 帧），想跟手就拉到 60。
     * 引擎确认时会回它自己实际跑的值（见 MD_VIDEO_ENC_INIT）。
     *
     * 0.2.10 实验：字节码实证引擎 `Recorder.changeFrameRate` 上限是 60（非旧结论 30），
     * 所以 [requestHigherFrameRate] 会**直接发 98316 带 fps=60**（不再跳过），
     * 验证 `setOutputTime50(1000/fps)` 能否把输出节拍从 50ms 拉到 16ms。
     */
    private val requestedFps: Int = 20,
    /**
     * 98386 特征配置里的 CONNECT_TYPE。默认 5 = Wi-Fi 局域网。
     *
     * 引擎会按连接类型选编码档位（实测 5 恒定 20fps），改这个值可以试出更高档位。
     */
    private val connectType: Int = 5,
    /**
     * 帧率变更命令的探测 id 列表。
     *
     * 手机端 `VideoMsgHandler.handleMessage` 里确实有 `VIDEO_ENCODER_FRAME_RATE_CHANGE`
     * 分支（解析 `CarlifeVideoFrameRate{1:fps}`，判定 `3 <= fps <= 30`，成功后回
     * `sendChangeDone`），但**协议命令号没有硬编码在 dex 里**，只能实测反推。
     * 这里做成可配置，靠偏好 `fps_probe_ids` 改候选，不必反复重编译。
     */
    private val probeIds: IntArray = CarLifeProtocol.FRAME_RATE_PROBE_IDS,
    /**
     * 向 CarLife 引擎申请的**初始**编码分辨率（98311 的 width/height 字段）。
     *
     * 这是车机（HU）告诉手机引擎「我的屏幕多大」的唯一通道，而且引擎**只在创建编码器
     * 之前才认**：编码器起来后再改申请值，引擎只回一个确认值、实际编码尺寸不动
     * （2026-11-04 奥迪 3810：申请 1412×654@60，回 1280×720@20，重发多少次都是 720p）。
     *
     * 默认 1280×720 是引擎白名单内的通用档（非标尺寸会 `reset2Default` 降回 720p，
     * 逆向 `VideoResolution.getResolution` 实证）。真实车机画布由
     * [io.github.shaonialife.carptocar.carlife.CarLifeHijack] 从「上次连过的车机」缓存里
     * 取来传进来——因为 CarLife 端口先开、AirPlay `/info` 后到，等知道了画布再申请已经晚了。
     */
    private val initialWidth: Int = 1280,
    private val initialHeight: Int = 720,
    /**
     * 98311 的 `needAdapterScreen`（字段 9）：请引擎在「车机画面比例 ≠ 编码比例」时做
     * 等比适配而不是拉伸。默认 true——车机屏几乎都不是 16:9（奥迪 MIB3 是 21:9）。
     */
    private val needAdapterScreen: Boolean = true,
    /**
     * 98311 的 `saveArea`（字段 4~7）：车机安全区 [宽, 高, x, y]，来自 AirPlay /info 的
     * `viewAreas[].safeArea`。厂商适配层据此算最终编码分辨率。
     */
    private val saveArea: IntArray? = null,
    /** 98311 的 `drawUIOutsideSafeArea`（字段 8）。 */
    private val drawUIOutsideSafeArea: Boolean = false,
) {

    private val running = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap<Int, Socket>()
    private var cmdOut: OutputStream? = null
    private var touchOut: OutputStream? = null

    /**
     * 低频诊断日志的限流。
     *
     * 「媒体进度」每秒一条、「媒体信息解析为空」每次媒体变化一条——前者在进程被系统
     * 冻结再解冻时会把积压的几百条一次吐出（2026-10-30 日志里 0.5 秒刷了 100+ 行），
     * 把真正要看的那几行挤没。这两类信息对定位问题价值很低，限流后每周转一圈即可。
     */
    private val progressLog = LogThrottle(10_000L)
    private val emptyMediaLog = LogThrottle(5_000L)

    /** 命令帧写锁：心跳线程、握手线程与视频看门狗会并发下发，必须串行写同一条 socket。 */
    private val writeLock = Any()

    @Volatile
    private var versionMatched = false

    /**
     * 视频通道（9242）是否已失效。
     *
     * 实测（2026-10-24 12:38:48）CarLife 引擎冷启动时 7 个端口**全部 accept**，但
     * 视频/媒体/TTS 三条会在 accept 后立刻被引擎 reset——TCP 连接是通的，socket
     * 却已经死了，于是握手成功、`fps` 永远是 0。控制通道不会因它关闭，所以必须
     * 由看门狗单独把视频通道重连起来（否则整轮空转）。
     */
    @Volatile
    private var videoDead = false

    /** 最近一次收到视频帧的时刻（elapsedRealtime）。 */
    @Volatile
    private var lastFrameAt = 0L

    /**
     * 音频通道（9240 媒体 / 9241 TTS）的运行状态。
     *
     * 为什么需要（2026-11-01 日志 090105 定谳）：WiFi 入网会把荣耀 CarLife 引擎重启，
     * 视频/媒体/TTS 三条数据 socket 同时 `Connection reset`。视频有视频看门狗会自动重连，
     * 音频链路**一条都没有**——于是出现「画面早就有了，音乐要等 88 秒才出声」：
     * 那 88 秒里 9240 其实是死的，一直干等到引擎自己重新推流才恢复。
     */
    private class AudioChannel(
        val port: Int,
        /** 日志里的通道名（「媒体」/「TTS」）。 */
        val label: String,
        val isTts: Boolean,
    ) {
        /** 读线程已退出（socket 不可用），需要重连。 */
        @Volatile
        var dead = false

        /**
         * 连接代次：每次（重新）挂读线程 +1。
         *
         * 旧读线程被 `close()` 后一定走 finally，若它无条件把 [dead] 置 true，会把刚接上的
         * 新通道判死，形成「重连 → 秒判死 → 再重连」的死循环。读线程只在自己这一代仍是最新
         * 时才允许置死。
         */
        @Volatile
        var generation = 0

        /** 最近一次收到 PCM 的时刻（elapsedRealtime）。 */
        @Volatile
        var lastPcmAt = 0L

        /** 本次连接建立的时刻（沉默判定从这里的宽限期起算）。 */
        @Volatile
        var openedAt = 0L

        /**
         * 引擎已宣告过音频格式。
         *
         * 重连后**必须保留**：新读线程若以为没宣告过，会给上层报一个 48kHz 立体声兜底值，
         * 把真实的 44.1kHz 覆盖掉，声音会变调。
         */
        @Volatile
        var announced = false

        /** TTS 通道已宣告的采样率 / 声道（0 = 未宣告）。 */
        @Volatile
        var rate = 0

        @Volatile
        var channels = 1

        /** 因「沉默」触发的重连次数（收到 PCM 后清零）。 */
        @Volatile
        var silentReopens = 0

        /** 下一次允许因沉默重连的时刻（退避）。 */
        @Volatile
        var nextSilentAt = 0L

        /** 因断链触发的重连次数（诊断用）。 */
        @Volatile
        var linkReopens = 0

        /** 已记录过的开停信号类型（重连后不重复刷屏）。 */
        val controlLogged = HashSet<Int>()
    }

    private val mediaChan = AudioChannel(CarLifeProtocol.PORT_MEDIA, "媒体", isTts = false)
    private val ttsChan = AudioChannel(CarLifeProtocol.PORT_TTS, "TTS", isTts = true)

    /**
     * 引擎音频是否处于「活跃」态（0x30002 开 / 0x30003 停）。
     *
     * 沉默看门狗的判据之一：引擎刚说过「开始推音频」却一直没有 PCM，是明确的异常。
     * 只当参考信号，不用来裁决是否转发 PCM（见 [onAudioGate] 注释）。
     */
    @Volatile
    private var engineAudioActive = false

    /** 已收到的视频帧总数（诊断用）。 */
    @Volatile
    var videoFrameCount: Long = 0
        private set

    /** 视频已连上但迟迟不出帧时，重新下发编码初始化的次数（防止无限重试）。 */
    @Volatile
    private var reinitCount = 0

    /** 最近一次**真正发出**的 98311 尺寸签名（`宽x高@帧率`），配合 [sendEncoderInit] 去重。 */
    private var lastEncInitSig: String? = null

    /** 最近一次发出 98311 的时刻（[android.os.SystemClock.uptimeMillis]）。 */
    private var lastEncInitAt = 0L

    /**
     * 我们向引擎**申请**的编码分辨率（98311 的 width/height 字段）。
     *
     * 与 [videoWidth]/[videoHeight]（引擎 65544 回显的确认值）分开存：申请值是「想要多少」，
     * 回显值是「引擎说它给多少」，两者在降档时不等（申请 1972×520 实编 1280×720）。
     * 分开存还有个诊断价值：日志里 `申请 … / 确认 …` 一眼看出引擎是否降档，从而判断
     * 车机黑屏是「画布不对」还是「码流不对」。
     */
    @Volatile
    private var requestedWidth = initialWidth.coerceAtLeast(320)

    @Volatile
    private var requestedHeight = initialHeight.coerceAtLeast(180)

    /** 引擎 65544 回显的**确认编码值**（初始 = 申请值，之后只由回显覆盖）。 */
    @Volatile
    private var videoWidth = initialWidth.coerceAtLeast(320)

    @Volatile
    private var videoHeight = initialHeight.coerceAtLeast(180)

    @Volatile
    private var videoFps = requestedFps.coerceIn(15, 60)

    /** 已记录过的未知音频包类型（避免刷屏）。 */
    private val audioUnknownTypes = HashSet<Int>()

    /** 已记录过的未处理控制消息 id（避免刷屏）。 */
    private val unknownLogged = HashSet<Int>()

    /**
     * 从 SPS 解析出的**码流真实分辨率**（0 = 还没解析到）。
     *
     * 触摸坐标必须以它为基准：`98311` 申请值、`65544` 回包值都只是「谈好的档位」，
     * 引擎可能按白名单降档编码（申请 1412×654 实际仍编 1280×720），拿谈好的值做缩放
     * 会让车机点哪儿都偏。
     */
    @Volatile
    private var streamWidth = 0

    @Volatile
    private var streamHeight = 0

    val isRunning: Boolean get() = running.get()

    /**
     * 引擎 **65544 回显的确认编码档位**。
     *
     * 语义是「引擎说它会给多少」，但**不等于码流真实尺寸**：引擎会把非白名单申请降档
     * （申请 1972×520 回 1280×720），也可能沿用上一轮编码器而回一个已过时的值
     * （2026-11-04 奥迪 3810：回 1280×720，SPS 却是 1412×654）。所以它只作诊断与
     * 兜底用——VideoConfig 声明的画布以**车机画布**为准（`VideoSender.canvasSize`），
     * 码流真实尺寸以 SPS 为准（[videoSize]）。
     */
    val negotiatedSize: Pair<Int, Int> get() = videoWidth to videoHeight

    /**
     * 触摸坐标映射基准：优先 SPS 真实值，其次协商档位。
     *
     * **只给触摸用**。视频声明绝不能换成 SPS 值——车机是按自己画布建解码器/surface 的，
     * 声明跟码流走会让画布对不上（2026-10-25 真机复测已证明会出画面异常，已回滚）。
     */
    val videoSize: Pair<Int, Int>
        get() {
            val sw = streamWidth
            val sh = streamHeight
            return if (sw > 0 && sh > 0) sw to sh else videoWidth to videoHeight
        }

    /** 当前视频分辨率是否已由 SPS 确认（诊断用）。 */
    val videoSizeConfirmed: Boolean get() = streamWidth > 0 && streamHeight > 0

    /**
     * 用**会话缓存下来的 SPS** 预置触摸基准。
     *
     * 引擎只在那一个 IDR 里带 SPS，若本客户端是在它之后才接上的（重连/晚启动），
     * 光靠后续码流可能很久都解析不到 SPS，触摸就会一直用协商档位当基准。
     * 这时用上层缓存的 SPS 立刻校准，触摸从第一下就是准的。
     */
    fun seedSps(annexB: ByteArray) {
        if (streamWidth > 0) return
        val sps = H264Sps.parseAnnexB(annexB) ?: return
        if (!isPlausibleSize(sps.first, sps.second)) return
        streamWidth = sps.first
        streamHeight = sps.second
        log("CARLIFE: 触摸基准取自缓存 SPS ${sps.first}x${sps.second}")
        onStreamSize(sps.first, sps.second)
    }

    /** 已收到的音频包计数（诊断用）。 */
    @Volatile
    var audioPacketCount: Long = 0
        private set

    /** 连接并开始握手。阻塞不超过 [connectTimeoutMs] × 端口数，需在后台线程调用。 */
    fun start(): Boolean {
        if (running.get()) return true
        log("CARLIFE: 连接本机车机端口 $host …")
        for (port in PORTS) {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                sockets[port] = socket
            } catch (error: Exception) {
                log("CARLIFE: 端口 $port 连接失败 - ${error.message}")
                runCatching { socket.close() }
            }
        }
        val cmd = sockets[CarLifeProtocol.PORT_CMD]
        if (cmd == null) {
            log("CARLIFE: 控制端口未连通，CarLife 可能不在「无线连接」态")
            closeAll()
            return false
        }
        cmdOut = cmd.getOutputStream()
        touchOut = sockets[CarLifeProtocol.PORT_TOUCH]?.getOutputStream()
        running.set(true)
        lastFrameAt = android.os.SystemClock.uptimeMillis()
        if (touchOut != null) startTouchWorker()

        startReader("carlife-cmd") { cmdReader(cmd.getInputStream()) }
        sockets[CarLifeProtocol.PORT_VIDEO]?.let { video ->
            startReader("carlife-video") { videoReader(video.getInputStream(), video) }
        }
        // 音频：媒体（9240）/ TTS（9241）各挂一条读线程，挂上即为沉默判定的宽限起点
        sockets[CarLifeProtocol.PORT_MEDIA]?.let { openAudio(mediaChan, it) }
        sockets[CarLifeProtocol.PORT_TTS]?.let { openAudio(ttsChan, it) }
        startReader("carlife-heartbeat") { heartbeatLoop() }
        // 视频通道看门狗：冷启动期被引擎 reset、或中途断链时自动重连并重发编码初始化。
        startReader("carlife-video-dog") { videoSupervisor() }
        // 音频通道看门狗：补上「断链后没人管」的窟窿（画面早就有、声音却要等 88 秒的真因）
        startReader("carlife-audio-dog") { audioSupervisor() }

        return sendVersionNegotiation()
    }

    fun stop() {
        running.set(false)
        touchLock.lock()
        try {
            touchQueue.clear()
            touchReady.signalAll()
        } finally {
            touchLock.unlock()
        }
        closeAll()
    }

    // ---- 触控回传（车机 → 手机）----

    fun touchDown(x: Int, y: Int) = enqueueTouch(CarLifeProtocol.TOUCH_DOWN, x, y)

    fun touchMove(x: Int, y: Int) = enqueueTouch(CarLifeProtocol.TOUCH_MOVE, x, y)

    fun touchUp(x: Int, y: Int) = enqueueTouch(CarLifeProtocol.TOUCH_UP, x, y)

    /**
     * 复合动作（0x68001）：`action` 为原始 MotionEvent 动作码，指针索引在 action 的高字节。
     * 双指手势用它——单点消息的负载只有 x/y，无法表达「哪根手指」。
     */
    fun touchAction(
        action: Int,
        x: Int,
        y: Int,
    ) = enqueueTouch(CarLifeProtocol.TOUCH_ACTION, x, y, action)

    /**
     * 触摸下发队列。
     *
     * 为什么不在调用线程上直接写 socket：CarLife 引擎处理触摸是**串行**的，
     * 慢的时候一条事件要几百毫秒才消费完，`write` 随之阻塞。如果直接写，
     * 车机端每 16ms 的触摸回调就会被这条慢 socket 拖住，事件在本地越堆越多，
     * 表现为「拖一下松手，画面半天才动；再点就没反应了」。
     *
     * 策略：
     * - 单 worker 线程按序写，DOWN/UP 绝不丢（否则会留下按住不放的幽灵触点）；
     * - MOVE 只保留最新一条（手指位置天然是「最后的值才有效」），
     *   引擎消费不过来时自动合并，不做无意义的排队；
     * - 队列总长有上限，超了从最老的 MOVE 开始丢，仍保留 DOWN/UP。
     */
    private val touchQueue = ArrayDeque<IntArray>()

    private val touchLock = java.util.concurrent.locks.ReentrantLock()

    private val touchReady = touchLock.newCondition()

    @Volatile
    private var touchWorker: Thread? = null

    private fun enqueueTouch(
        serviceType: Int,
        x: Int,
        y: Int,
        action: Int = -1,
    ) {
        if (touchOut == null) return
        // 只做安全范围约束，**不按视频分辨率（SPS/协商档位）钳位**：
        // 触摸坐标的域是「车机 display 坐标」，引擎自己乘 getTouchXOffset() 换算到手机屏。
        // 以前按 videoSize 钳位，码流 1280×720 而车机画布 1412×654 时，右侧 132 像素
        // 的点击会被压到 x=1279（整块区域变成同一个点），拖到右侧也拖不动。
        val px = x.coerceIn(0, TOUCH_COORD_MAX)
        val py = y.coerceIn(0, TOUCH_COORD_MAX)
        touchLock.lock()
        try {
            if (!running.get()) return
            if (isMoveEvent(serviceType, action)) {
                // 队尾已是 MOVE：直接覆盖坐标，等价于「只发最新位置」。
                val last = touchQueue.lastOrNull()
                if (last != null && isMoveEvent(last[0], last[3])) {
                    last[1] = px
                    last[2] = py
                    return
                }
            }
            if (touchQueue.size >= TOUCH_QUEUE_MAX) {
                val removable = touchQueue.indexOfFirst { isMoveEvent(it[0], it[3]) }
                if (removable >= 0) touchQueue.removeAt(removable) else return
            }
            touchQueue.addLast(intArrayOf(serviceType, px, py, action))
            touchReady.signalAll()
        } finally {
            touchLock.unlock()
        }
    }

    /** 是不是「可合并的移动事件」：单点 MOVE，或复合动作里的 ACTION_MOVE。 */
    private fun isMoveEvent(
        serviceType: Int,
        action: Int,
    ): Boolean =
        serviceType == CarLifeProtocol.TOUCH_MOVE ||
            (serviceType == CarLifeProtocol.TOUCH_ACTION && action >= 0 && (action and 0xFF) == CarLifeProtocol.TouchAction.MOVE)

    private fun startTouchWorker() {
        if (touchWorker?.isAlive == true) return
        val worker = Thread({
            while (running.get()) {
                var event: IntArray? = null
                touchLock.lock()
                try {
                    if (touchQueue.isEmpty()) touchReady.await(400, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (touchQueue.isNotEmpty()) event = touchQueue.removeFirst()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } finally {
                    touchLock.unlock()
                }
                if (!running.get()) break
                if (event != null) sendTouch(event[0], event[1], event[2], event[3])
            }
        }, "carlife-touch").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
        }
        touchWorker = worker
        worker.start()
    }

    /**
     * 触控走 9340，但用的是**控制帧格式**（8B 头）而不是视频那种 12B 流格式——
     * 手机端把这条 socket 当 ConnectSocket 读，按 CarlifeCmdMessage 解。
     */
    private fun sendTouch(
        serviceType: Int,
        x: Int,
        y: Int,
        action: Int,
    ) {
        val out = touchOut ?: return
        val px = x.coerceIn(0, TOUCH_COORD_MAX)
        val py = y.coerceIn(0, TOUCH_COORD_MAX)
        // 复合动作（0x68001）的负载是 CarlifeTouchAction{action,x,y}，
        // 单点消息（0x68002/3/4）的负载是 CarlifeTouchSinglePoint{x,y}——两者不能混用。
        val payload =
            if (serviceType == CarLifeProtocol.TOUCH_ACTION) {
                CarLifeProtocol.touchAction(action, px, py)
            } else {
                CarLifeProtocol.touchPoint(px, py)
            }
        val frame = CarLifeProtocol.packCommand(serviceType, payload)
        sentTouchCount++
        if (!runCatching { out.write(frame); out.flush() }.isSuccess) {
            touchWriteFailures++
            if (touchWriteFailures == 1 || touchWriteFailures % 50 == 0) {
                log("CARLIFE: 触摸写入失败 ${touchWriteFailures} 次（通道可能已断）")
            }
        }
    }

    /** 已下发的触摸报文条数（诊断：确认车机事件真的写进了通道）。 */
    @Volatile
    var sentTouchCount = 0
        private set

    @Volatile
    private var touchWriteFailures = 0

    // ---- 握手 ----

    private fun sendVersionNegotiation(): Boolean {
        val deadline = System.currentTimeMillis() + 15_000
        while (running.get() && !versionMatched && System.currentTimeMillis() < deadline) {
            send(CarLifeProtocol.HU_PROTOCOL_VERSION, CarLifeProtocol.protocolVersion())
            var waited = 0
            while (waited < 1500 && !versionMatched && running.get()) {
                Thread.sleep(100)
                waited += 100
            }
        }
        if (!versionMatched) {
            log("CARLIFE: 15s 内未收到版本匹配，放弃本次连接")
            stop()
            return false
        }
        log("CARLIFE: 版本匹配成功（协议 3.0）")
        return true
    }

    private fun cmdReader(input: InputStream) {
        val data = DataInputStream(input)
        try {
            while (running.get()) {
                val header = ByteArray(8)
                data.readFully(header)
                val length = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
                val serviceType = readU32(header, 4)
                val payload = ByteArray(length)
                if (length > 0) data.readFully(payload)
                handleMessage(serviceType, payload)
            }
        } catch (error: Exception) {
            if (running.get()) log("CARLIFE: 控制通道断开 - ${error.message}")
        } finally {
            stop()
        }
    }

    private fun handleMessage(serviceType: Int, payload: ByteArray) {
        when (serviceType) {
            CarLifeProtocol.MD_VERSION_MATCH -> {
                versionMatched = true
                send(CarLifeProtocol.HU_STATISTICS, CarLifeProtocol.statisticsInfo())
            }

            CarLifeProtocol.MD_INFO -> {
                log("CARLIFE: 收到手机设备信息（${payload.size}B）")
                send(CarLifeProtocol.HU_INFO, CarLifeProtocol.deviceInfo("${videoWidth}x$videoHeight"))
                // 关键：车机必须主动推进，不能等手机下发鉴权
                Thread {
                    Thread.sleep(1200)
                    log(
                        "CARLIFE: 主动推送 98386 特征配置（CONNECT_TYPE=$connectType，" +
                            "I 帧间隔=${CarLifeProtocol.I_FRAME_INTERVAL_DEFAULT} 帧，" +
                            "DISABLE_RESOLUTION_OPTION=1 分辨率直通，MEDIA_LYRIC_SUPPORT=1 歌词）",
                    )
                    send(CarLifeProtocol.HU_FEATURE_CONFIG, CarLifeProtocol.featureConfig(connectType))
                    Thread.sleep(1200)
                    log("CARLIFE: 主动推送 98311 视频编码初始化 ${requestedWidth}x$requestedHeight@$videoFps")
                    sendEncoderInit()
                }.apply { isDaemon = true }.start()
            }

            CarLifeProtocol.MD_MODULE_STATUS -> {
                val modules = CarLifeProtocol.parseModuleStatus(payload)
                if (modules.isEmpty()) return
                if (modules.any { it.second == 0 }) {
                    send(
                        CarLifeProtocol.HU_MODULE_CONTROL,
                        CarLifeProtocol.moduleControl(modules.map { it.first }),
                    )
                }
            }

            CarLifeProtocol.MD_VIDEO_ENC_INIT -> {
                // CarlifeVideoEncoderInfo: field1 width, field2 height, field3 fps
                val info = readEncoderInfo(payload)
                if (info != null) {
                    videoWidth = info.first.coerceAtLeast(1)
                    videoHeight = info.second.coerceAtLeast(1)
                    // fps 字段缺失/为 0 时保留我们申请的值，别被清零成 1fps。
                    if (info.third > 0) videoFps = info.third
                    onVideoParams(videoWidth, videoHeight, videoFps)
                }
                log(
                    "CARLIFE: 手机确认编码参数 ${videoWidth}x$videoHeight@$videoFps" +
                        "（申请 ${requestedWidth}x$requestedHeight@$requestedFps），启动推流",
                )
                send(CarLifeProtocol.VIDEO_ENC_START, ByteArray(0))
                requestHigherFrameRate()
            }

            CarLifeProtocol.MD_VIDEO_FRAME_RATE_DONE -> {
                log("CARLIFE: 引擎已接受帧率变更（回包 ${payload.size}B）")
            }

            CarLifeProtocol.MD_FEATURE_CONFIG_DONE -> {
                log("CARLIFE: 引擎已接受特征配置（CONNECT_TYPE=$connectType）")
            }

            CarLifeProtocol.MD_MEDIA_INFO -> {
                // 手机 CarLife 内置播放器开始播放时主动推送的媒体信息（歌名/歌手/封面）
                val infos = CarLifeProtocol.parseMediaInfoList(payload)
                if (infos.isEmpty()) {
                    // 每次媒体变化都会来一包，解析不出内容时对排查无帮助——限流 5 秒
                    if (emptyMediaLog.allow("media-info-empty")) {
                        log("CARLIFE: 收到媒体信息（${payload.size}B）但解析为空（同类提示 5 秒最多一行）")
                    }
                    return
                }
                infos.forEach { info ->
                    log(
                        "CARLIFE: 媒体信息 song=${info.song} artist=${info.artist} " +
                            "album=${info.album} dur=${info.duration}ms mode=${info.mode} " +
                            "art=${info.albumArt?.size ?: 0}B",
                    )
                    onMediaInfo(info)
                }
            }

            CarLifeProtocol.MD_MEDIA_PROGRESS -> {
                // 媒体进度条（u32 progressBar，单位 ms）。只做低频诊断：限流 10 秒，
                // 否则冻结解冻一次就能把几百行灌进日志（见 progressLog 注释）。
                val progress = if (payload.size >= 4) {
                    ((payload[0].toInt() and 0xFF) shl 24) or
                        ((payload[1].toInt() and 0xFF) shl 16) or
                        ((payload[2].toInt() and 0xFF) shl 8) or
                        (payload[3].toInt() and 0xFF)
                } else {
                    0
                }
                if (progressLog.allow("media-progress")) {
                    log("CARLIFE: 媒体进度 ${progress}ms（同类提示 10 秒最多一行）")
                }
            }

            in CarLifeProtocol.MD_IGNORED_IDS -> {
                // 0 字节状态广播，无需回应也不必记录
            }

            CarLifeProtocol.VIDEO_ENC_DONE -> {
                sendEncoderInit()
            }

            else -> {
                if (unknownLogged.add(serviceType)) {
                    log("CARLIFE: 未处理的消息 id=$serviceType (0x%x) ${payload.size}B".format(serviceType))
                }
            }
        }
    }

    /**
     * 用 `VIDEO_ENCODER_FRAME_RATE_CHANGE`（98316）把引擎编码帧率拉到目标值。
     *
     * 98311 里的 fps 字段引擎只回显不生效；真正生效的是这条。
     *
     * 2026-10-24 第四轮逆向**纠正**：旧结论「3..30 硬判定」是错的——字节码铁证
     * `Recorder.changeFrameRate` 的 clamp 是 `3..60`，且 `mDestFrameInterval = 1000/fps`、
     * `setOutputTime50(1000/fps)` 直接拿这个值做输出节拍。所以 fps=60 时
     * `mDestFrameInterval = 16ms`，理论上能把输出节拍拉到 60fps。
     *
     * 本轮实验：直接发 98316 带 fps=60（不再像 0.2.9 方案 A 那样跳过），
     * 观察 5s 窗口实测帧率是否真能冲过 20。
     */
    private fun requestHigherFrameRate() {
        val target = requestedFps.coerceIn(3, CarLifeProtocol.FRAME_RATE_MAX)
        if (target <= 20) return
        Thread {
            try {
                Thread.sleep(800)
                if (!running.get()) return@Thread
                for (id in probeIds) {
                    log("CARLIFE: → 帧率变更 id=$id fps=$target（上限 ${CarLifeProtocol.FRAME_RATE_MAX}）")
                    send(id, CarLifeProtocol.videoFrameRate(target))
                    Thread.sleep(1_200)
                    if (!running.get()) return@Thread
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true }.start()
    }

    // ---- 视频接收 ----

    /**
     * 读 9242。[owner] 是这条线程负责的 socket：退出时只有它**仍是当前 socket** 才允许
     * 置 `videoDead`。
     *
     * 为什么需要这个判断：`reopenVideo()` 先关旧 socket、再连新的、再把 `videoDead`
     * 清零；若旧读线程此刻还卡在 `onVideoFrame`（socket 写入）里，它的 `finally` 会在
     * 清零**之后**把标志又置回 true —— 看门狗下一轮就会把刚建好的健康 socket 关掉、
     * 重连并 `restartEncoding()`，表现为画面周期性黑屏 1~3 秒。音频侧早就用
     * generation 做了同样的事（见 `openAudio`），视频侧一直漏着。
     */
    private fun videoReader(
        input: InputStream,
        owner: Socket? = null,
    ) {
        val data = DataInputStream(input)
        // 实测帧率窗口：每 5 秒统计一次，回答「98311 声明 60 到底有没有让引擎真的吐 60 帧」。
        var windowStart = android.os.SystemClock.uptimeMillis()
        var windowFrames = 0L
        try {
            while (running.get()) {
                val header = ByteArray(12)
                data.readFully(header)
                val length = readU32(header, 0)
                if (length < 0 || length > MAX_VIDEO_FRAME) {
                    log("CARLIFE: 视频帧长度异常（$length），放弃该通道")
                    break
                }
                // 帧头 [4..7] 是引擎给的毫秒时间戳（[8..11] 是包类型），此前一直被丢掉。
                // 按无符号读：若是 currentTimeMillis 量级，当 Int 读会变成负数。
                val sourceTimestampMs = readU32(header, 4).toLong() and 0xFFFF_FFFFL
                val payload = ByteArray(length)
                if (length > 0) data.readFully(payload)
                if (length <= 0) continue
                videoFrameCount++
                lastFrameAt = android.os.SystemClock.uptimeMillis()
                reinitCount = 0
                windowFrames++
                // 关键帧里带 SPS：解析出码流真实分辨率，触摸坐标以它为准。
                // 只在 I 帧上做（20fps 下很稀疏），开销可忽略。
                if (length > 32 && (isSpsCandidate(payload) || (streamWidth == 0 && videoFrameCount < 300))) {
                    val sps = H264Sps.parseAnnexB(payload)
                    if (sps != null && isPlausibleSize(sps.first, sps.second) &&
                        (sps.first != streamWidth || sps.second != streamHeight)
                    ) {
                        val previous = "$streamWidth x $streamHeight"
                        streamWidth = sps.first
                        streamHeight = sps.second
                        log(
                            "CARLIFE: 码流真实分辨率 ${sps.first}x${sps.second}" +
                                "（原 $previous，谈好档位 ${videoWidth}x$videoHeight）",
                        )
                        onStreamSize(sps.first, sps.second)
                    }
                }
                val now = android.os.SystemClock.uptimeMillis()
                if (now - windowStart >= 5_000) {
                    val fps = windowFrames * 1000.0 / (now - windowStart)
                    log(
                        "CARLIFE: 实测帧率 %.1ffps（5s 内 %d 帧，声明 $requestedFps）"
                            .format(fps, windowFrames),
                    )
                    windowStart = now
                    windowFrames = 0
                }
                onVideoFrame(payload, isKeyFrame(payload), sourceTimestampMs)
            }
        } catch (error: Exception) {
            if (running.get()) log("CARLIFE: 视频通道断开 - ${error.message}")
        } finally {
            // 无论 EOF 还是 reset，都交给看门狗重连：这条 socket 已经不可用了。
            // 但只有「仍是当前 socket」才允许判死——旧线程晚到的 finally 不能把新一轮
            // 刚建好的通道一起判死（否则看门狗立刻关掉它，周期性黑屏）。
            if (owner == null || sockets[CarLifeProtocol.PORT_VIDEO] === owner) {
                videoDead = true
            }
        }
    }

    // ---- 视频通道看门狗 ----

    /**
     * 视频通道看门狗。
     *
     * 两条互斥的处理路径：
     *  - **socket 真的死了**（被引擎 reset / 关掉）→ 重连 9242 并重走 98386+98311；
     *  - **socket 还活着只是不出帧** → **只重发 98311，绝不换连接**。
     *
     * 第二条是实测教训：早先「重发 2 次无效就换连接」，结果是 App 退后台再回来、
     * 或引擎编码器暂时卡住时，控制握手被整条丢掉，画面从「卡一下」变成「黑屏 +
     * 重新走一遍 15s 版本协商」。98311 的代价只有一次编码器重建，而 98386+98313
     * 的握手是全局状态，能不碰就不碰。
     */
    private fun videoSupervisor() {
        var reconnects = 0
        while (running.get()) {
            Thread.sleep(1_000)
            if (!running.get()) return
            val socket = sockets[CarLifeProtocol.PORT_VIDEO]
            if (socket == null || socket.isClosed || videoDead) {
                if (reopenVideo()) {
                    reconnects++
                    // 引擎冷启动期可能连续 reset：只在第 1 次和每 10 次打日志，避免刷屏。
                    if (reconnects == 1 || reconnects % 10 == 0) {
                        log("CARLIFE: 视频通道已重连（第 $reconnects 次）——重新下发编码初始化")
                    }
                    restartEncoding()
                    // 连上又立刻被 reset 时放慢节奏，别把引擎打满。
                    if (reconnects > 3) Thread.sleep(2_000)
                }
                continue
            }
            if (!versionMatched) continue
            val idle = android.os.SystemClock.uptimeMillis() - lastFrameAt
            // 重发次数越多间隔越长（10s → 20s → 30s … 封顶 60s）：引擎卡住时
            // 高频重发只会自己也变成负担，而间隔内一旦来帧 reinitCount 会被清零。
            val need = (NO_FRAME_REINIT_MS * (1 + reinitCount / 3)).coerceAtMost(MAX_REINIT_INTERVAL_MS)
            if (idle < need) continue
            reinitCount = (reinitCount + 1).coerceAtMost(1_000)
            log(
                "CARLIFE: ${idle / 1000}s 无视频帧——第 $reinitCount 次重发 98311" +
                    "（socket 存活，保持连接不重建）",
            )
            lastFrameAt = android.os.SystemClock.uptimeMillis()
            resendEncInit()
        }
    }

    /** 重连 9242。成功返回 true（已挂上新的读线程）。 */
    private fun reopenVideo(): Boolean {
        runCatching { sockets.remove(CarLifeProtocol.PORT_VIDEO)?.close() }
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.connect(
                InetSocketAddress(host, CarLifeProtocol.PORT_VIDEO),
                connectTimeoutMs,
            )
            if (!running.get()) {
                runCatching { socket.close() }
                return false
            }
            sockets[CarLifeProtocol.PORT_VIDEO] = socket
            videoDead = false
            lastFrameAt = android.os.SystemClock.uptimeMillis()
            reinitCount = 0
            startReader("carlife-video") { videoReader(socket.getInputStream(), socket) }
            true
        } catch (error: Exception) {
            runCatching { socket.close() }
            false
        }
    }

    /**
     * 请求引擎重建编码器（重发 98386 + 98311）。
     *
     * 重建后引擎必然补一个「SPS + PPS + IDR」，车机侧就能拿到 VideoConfig 配置解码器。
     * 用于「会话接上但一帧都送不出去」的死锁场景。
     */
    fun requestVideoReinit() {
        if (!running.get()) return
        log("CARLIFE: 请求引擎重建编码器（补 SPS/PPS 关键帧）")
        restartEncoding()
    }

    /**
     * 车机连上后更新**申请**分辨率，并重新下发 98311 让引擎按新车机画布编码。
     *
     * 时机：AirPlay SETUP 拿到车机真实分辨率（如奥迪 MIB3 的 1412×654）后调用。
     * 引擎内部有白名单/默认档位决策，非标准分辨率可能仍降回 1280×720——这是引擎
     * 侧限制，不是本侧能强制的；但申请车机真实值至少能让引擎「尝试」按车机匹配。
     *
     * **只改申请值，不改确认值**：视频声明（[negotiatedSize]）必须跟引擎真实编码走，
     * 不能被尚未经引擎确认的车机画布值污染（见 [requestedWidth] 字段注释）。
     *
     * 幂等：宽高未变时跳过（避免车机 1s 轮询重复触发重发 98311 打断推流）。
     */
    fun setRequestedResolution(width: Int, height: Int) {
        val w = width.coerceAtLeast(320)
        val h = height.coerceAtLeast(180)
        if (w == requestedWidth && h == requestedHeight) return
        log("CARLIFE: 车机分辨率申请更新 ${requestedWidth}x$requestedHeight → ${w}x$h，重发 98311")
        requestedWidth = w
        requestedHeight = h
        if (running.get()) resendEncInit()
    }

    /**
     * 只重发 98311 编码初始化（**不碰** 98386）。
     *
     * 引擎收到 98311 会重建编码器并回 65544，我们的 65544 处理里会顺带补 98313
     * 启动推流，所以单发这一条就能把码流重新推起来，且不会动到连接特征。
     * 用于「socket 活着但不出帧」——这条路径绝不能升级成换连接。
     */
    private fun resendEncInit() {
        Thread {
            try {
                Thread.sleep(300)
                if (!running.get()) return@Thread
                sendEncoderInit()
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true }.start()
    }

    /** 重发 98386 特征配置 + 98311 编码初始化，让引擎重建编码器（异步，避免阻塞看门狗）。 */
    private fun restartEncoding() {
        Thread {
            try {
                Thread.sleep(300)
                if (!running.get()) return@Thread
                send(CarLifeProtocol.HU_FEATURE_CONFIG, CarLifeProtocol.featureConfig(connectType))
                Thread.sleep(600)
                if (!running.get()) return@Thread
                sendEncoderInit()
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 发送 98311 视频编码初始化，**同一组 (宽,高,帧率) 在 [ENCODER_INIT_DEDUPE_MS] 内只发一次**。
     *
     * 为什么必须去重：引擎收到**尺寸未变的重复 98311** 时，会把 65544 的确认值降成
     * 1280×720@20（2026-11-04 实测 4/4：08:26、11:20、13:09 三次会话都是「首条回
     * 60fps、紧随其后的第二条回 20fps」）。而握手期天然有两条路径各发一次——
     * AirPlay SETUP 的 [setRequestedResolution]（此刻引擎还热着，几十毫秒内就发出）
     * 与 MD_INFO 的延迟推送（握手后 2.4s）。两条都发，第二条必然把帧率打下来。
     *
     * 用时间窗而不是纯签名判重，是为了不拦住真正需要重建的两处：无帧看门狗
     * （间隔 ≥ [NO_FRAME_REINIT_MS] = 10s）和视频口重连后的 [restartEncoding]。
     */
    @Synchronized
    private fun sendEncoderInit() {
        val sig = "${requestedWidth}x${requestedHeight}@$videoFps"
        val now = android.os.SystemClock.uptimeMillis()
        if (sig == lastEncInitSig && now - lastEncInitAt < ENCODER_INIT_DEDUPE_MS) {
            log("CARLIFE: 98311 跳过重复下发（$sig 刚发过）——重复会让引擎确认值降到 20fps")
            return
        }
        lastEncInitSig = sig
        lastEncInitAt = now
        send(CarLifeProtocol.HU_VIDEO_ENC_INIT, buildEncoderInfo())
    }

    /**
     * 构造 98311 载荷（统一的 9 字段版本）。
     *
     * 集中在一处，保证首次申请、重建、看门狗重发三条路径的字段完全一致——
     * 之前四处各自拼 `videoEncoderInfo(w,h,fps)`，新增字段时极易漏改其中一两条。
     */
    private fun buildEncoderInfo(): ByteArray =
        CarLifeProtocol.videoEncoderInfo(
            requestedWidth,
            requestedHeight,
            videoFps,
            needAdapterScreen = needAdapterScreen,
            saveArea = saveArea,
            drawUIOutsideSafeArea = drawUIOutsideSafeArea,
        )

    /** AnnexB 首个 NAL：7=SPS / 5=IDR 视为关键帧。 */
    private fun isKeyFrame(data: ByteArray): Boolean {
        var index = 0
        while (index + 4 < data.size) {
            if (data[index] == 0.toByte() && data[index + 1] == 0.toByte() &&
                data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte()
            ) {
                val type = data[index + 4].toInt() and 0x1F
                return type == 5 || type == 7
            }
            index++
        }
        // 没有起始码时按裸 NAL 处理
        val type = data[0].toInt() and 0x1F
        return type == 5 || type == 7
    }

    /** 尺寸合理性校验：拦掉解析出错产生的荒谬值（会让车机建错解码器，直接黑屏）。 */
    private fun isPlausibleSize(w: Int, h: Int): Boolean {
        if (w < 160 || h < 120 || w > 4096 || h > 4096) return false
        val ar = w.toFloat() / h
        return ar in 0.3f..4.0f
    }

    /** 帧内是否含 SPS（NAL type 7），命中才值得跑一次完整解析。 */
    private fun isSpsCandidate(data: ByteArray): Boolean {
        var index = 0
        while (index + 4 < data.size) {
            if (data[index] == 0.toByte() && data[index + 1] == 0.toByte()) {
                if (data[index + 2] == 1.toByte()) {
                    if ((data[index + 3].toInt() and 0x1F) == 7) return true
                    index += 3
                    continue
                }
                if (data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte()) {
                    if (index + 4 < data.size && (data[index + 4].toInt() and 0x1F) == 7) return true
                    index += 4
                    continue
                }
            }
            index++
        }
        return false
    }

    // ---- 音频接收 ----

    /**
     * 音频通道（裸字节流，**不是** 12B 的 [len][seq][type] 结构）：
     * 每包 `[u32 len][u32 timestamp][u32 type]` + `len` 字节负载。
     */
    private fun audioReader(
        channel: AudioChannel,
        generation: Int,
        input: InputStream,
    ) {
        val label = channel.label
        val isTts = channel.isTts
        val data = DataInputStream(input)
        // 格式宣告状态挂在 channel 上（不是局部变量）：重连后必须沿用已宣告过的值，
        // 否则新读线程会以为引擎没宣告过，给上层报 48kHz 兜底值把真实的 44.1kHz 覆盖掉。
        var ttsPackets = 0L
        try {
            while (running.get()) {
                val head = ByteArray(CarLifeProtocol.AUDIO_HEAD_LEN)
                data.readFully(head)
                val length = readU32(head, 0)
                val type = readU32(head, 8)
                if (length < 0 || length > MAX_AUDIO_PACKET) {
                    log("CARLIFE: $label 音频包长度异常（$length），放弃该通道")
                    return
                }
                val payload = ByteArray(length)
                if (length > 0) data.readFully(payload)
                if (isTts) {
                    // TTS 通道（9241）：包类型是 0x4xxxx，但编号与媒体的 INIT/START/STOP
                    // **不对应**（媒体数据在 0x30006，TTS 数据却在 0x40003）。只认两条铁律：
                    // 有负载 = 裸 PCM（导航播报），长度 0 = 开停信号（仅记录）。
                    if (type == CarLifeProtocol.TTS_PKG_INIT) {
                        val format = parseAudioInit(payload)
                        if (format != null) {
                            val (rate, channelConfig, _) = format
                            channel.rate = rate
                            channel.channels = channelsOf(channelConfig)
                            log("CARLIFE: TTS 音频格式宣告 ${channel.rate}Hz ${channel.channels}ch（导航播报通道）")
                            onTtsFormat(channel.rate, channel.channels)
                        }
                    } else if (length > 0) {
                        if (channel.rate <= 0) {
                            channel.rate = CarLifeProtocol.TTS_DEFAULT_RATE
                            channel.channels = 1
                            log("CARLIFE: TTS 未宣告格式——按 ${channel.rate}Hz 单声道处理（发送端会重采样）")
                            onTtsFormat(channel.rate, channel.channels)
                        }
                        ttsPackets++
                        if (ttsPackets == 1L) {
                            log(
                                "CARLIFE: ✓ 收到导航播报音频（TTS 首包 ${length}B，" +
                                    "${channel.rate}Hz ${channel.channels}ch）",
                            )
                        }
                        noteAudioPcm(channel)
                        onTtsPcm(payload, length)
                    } else if (channel.controlLogged.add(type)) {
                        log("CARLIFE: TTS 开停信号 0x${type.toString(16)}（长度 0，仅记录）")
                    }
                    continue
                }
                when (type) {
                    CarLifeProtocol.MUSIC_PKG_INIT -> {
                        val format = parseAudioInit(payload)
                        if (format != null) {
                            val (rate, channelConfig, sampleFormat) = format
                            val channels = channelsOf(channelConfig)
                            log(
                                "CARLIFE: $label 音频格式宣告 ${rate}Hz " +
                                    "${channels}ch ${sampleFormat}bit（channelConfig=$channelConfig）",
                            )
                            onAudioFormat(rate, channels)
                            channel.announced = true
                        }
                    }

                    CarLifeProtocol.MUSIC_PKG_NORMAL -> {
                        audioPacketCount++
                        if (!channel.announced) {
                            // 手机没宣告格式就直接推 PCM：按 48k 立体声先顶着
                            channel.announced = true
                            log("CARLIFE: $label 未收到格式宣告，按 48kHz 立体声处理")
                            onAudioFormat(DEFAULT_AUDIO_RATE, 2)
                        }
                        if (length > 0) {
                            noteAudioPcm(channel)
                            onAudioPcm(payload, length)
                        }
                    }

                    // 引擎音频开停信号（长度 0）：来电/路由切换时引擎会先发 STOP，
                    // 挂断恢复播放再发 START。只上报，不拦截音频。
                    CarLifeProtocol.MUSIC_PKG_START -> {
                        log("CARLIFE: $label 引擎音频开始（0x30002）")
                        engineAudioActive = true
                        onAudioGate(true)
                    }

                    CarLifeProtocol.MUSIC_PKG_STOP -> {
                        log("CARLIFE: $label 引擎音频停止（0x30003）——来电或音频路由被切走")
                        engineAudioActive = false
                        onAudioGate(false)
                    }

                    else -> {
                        if (audioUnknownTypes.add(type)) {
                            log("CARLIFE: ${carLifeAudioTypeName(type)} 音频包（长度 $length，暂不处理）")
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) log("CARLIFE: $label 音频通道断开 - ${error.message}（看门狗会自动重连）")
        } finally {
            // 只有「我这一代读线程仍是最新」才允许判死：旧线程被 close() 时也会走到这里，
            // 若无条件置死就会把刚接上的新通道立刻判死，形成重连死循环。
            if (channel.generation == generation) channel.dead = true
        }
    }

    // ---- 音频通道看门狗 ----

    /**
     * 音频沉默看门狗（9240 媒体 / 9241 TTS）。
     *
     * 视频链路早就有看门狗（[videoSupervisor]），音频链路此前**一条都没有**——2026-11-01
     * 日志 090105 的「等一会才连上车机，播放音乐也要等一段时间才出音频」就是这么来的：
     *
     * ```
     * 08:57:38  CARLIFE: 媒体 音频通道断开 - Connection reset   ← 音频 socket 死了，没人管
     * 08:58:00  CARLIFE: TTS  音频通道断开 - Connection reset   ← 同上
     * 08:58:0x  视频看门狗重连 8240 → 画面恢复
     * 09:00:22  CARLIFE: 媒体 音频格式宣告 …                    ← 88 秒后才出声，全靠引擎自己复位
     * ```
     *
     * 两条互斥路径，和视频看门狗同构：
     *  - **socket 真的死了**（引擎重启 / 被 reset）→ 立刻重连，这是 88 秒空窗的根因；
     *  - **socket 活着但 20 秒零 PCM**，且此刻确实该有声音 → 重连一次（退避 20/40/60 秒，最多 3 次）。
     *
     * 第二条必须三重设限（视频在出帧 / 引擎说过音频开始或有音乐在播 / 未超次数），
     * 否则「人只是停在桌面没放音乐」也会被当成故障，反复重连把引擎的音频管道打断。
     */
    private fun audioSupervisor() {
        while (running.get()) {
            Thread.sleep(AUDIO_DOG_POLL_MS)
            if (!running.get()) return
            superviseAudio(mediaChan)
            superviseAudio(ttsChan)
        }
    }

    private fun superviseAudio(channel: AudioChannel) {
        val now = android.os.SystemClock.uptimeMillis()
        val socket = sockets[channel.port]
        if (socket == null || socket.isClosed || channel.dead) {
            // 断链重连：不设次数上限（引擎冷启动期可能连续 reset，重连成功才停）
            if (!reopenAudio(channel)) return
            channel.linkReopens++
            if (channel.linkReopens == 1 || channel.linkReopens % 10 == 0) {
                log("CARLIFE: ${channel.label} 音频通道已重连（第 ${channel.linkReopens} 次断链重连）")
            }
            // 连上又被立刻 reset 时放慢节奏，别把引擎打满（同视频看门狗）。
            if (channel.linkReopens > 3) Thread.sleep(2_000)
            return
        }
        // TTS 只做断链重连：没开导航时它本来就该长时间静默，按沉默判会天天误重连。
        if (channel.isTts) return
        val silentFor = now - channel.lastPcmAt
        if (silentFor < SILENT_RECONNECT_MS) return
        // 刚（重新）接上的宽限期：给引擎一点时间把流推起来。
        if (now - channel.openedAt < SILENT_GRACE_MS) return
        // 画面还在出帧 = 引擎活着，问题只在这条音频流上。视频也停了说明整条链路有事，
        // 那是会话冻结/断链恢复的活，这里跟着重连只会把水搅浑。
        if (now - lastFrameAt > VIDEO_ALIVE_MS) return
        // 「确实该有声音」：引擎刚发过 0x30002，或上层确认音乐会话正在播放。
        if (!engineAudioActive && !audioExpected()) return
        if (now < channel.nextSilentAt) return
        if (channel.silentReopens >= SILENT_MAX_ATTEMPTS) {
            if (channel.nextSilentAt != Long.MAX_VALUE) {
                channel.nextSilentAt = Long.MAX_VALUE
                log(
                    "CARLIFE: ${channel.label} 音频通道连续 ${channel.silentReopens} 次重连仍无 PCM" +
                        "——停止重试（等引擎自己恢复；仍无声可重开 CarLife+ 的「无线连接」）",
                )
            }
            return
        }
        channel.silentReopens++
        channel.nextSilentAt = now + SILENT_BACKOFF_MS * channel.silentReopens
        log(
            "CARLIFE: ${channel.label} 音频通道 ${silentFor / 1000} 秒零 PCM（画面正在正常出帧）" +
                "——重连 ${channel.port}（第 ${channel.silentReopens}/$SILENT_MAX_ATTEMPTS 次，" +
                "${SILENT_BACKOFF_MS * channel.silentReopens / 1000} 秒后才会再试）",
        )
        if (!reopenAudio(channel)) {
            log("CARLIFE: ${channel.label} 音频通道重连失败（引擎该端口未监听）——按退避再试")
        }
    }

    /** 收到 PCM 的打卡点：刷新沉默计时，顺带报告「恢复」。 */
    private fun noteAudioPcm(channel: AudioChannel) {
        val now = android.os.SystemClock.uptimeMillis()
        val previous = channel.lastPcmAt
        channel.lastPcmAt = now
        val reopens = channel.silentReopens
        if (reopens > 0 || channel.nextSilentAt != 0L) {
            channel.silentReopens = 0
            channel.nextSilentAt = 0L
            val gap = if (previous > 0) (now - previous) / 1000 else 0
            val tail = if (reopens > 0) "，重连 $reopens 次后恢复）" else "）"
            log("CARLIFE: ${channel.label} 音频通道恢复（断流 ${gap} 秒$tail")
        }
    }

    /** 把一条音频 socket 挂上读线程（媒体 / TTS 共用）。 */
    private fun openAudio(
        channel: AudioChannel,
        socket: Socket,
    ) {
        val generation = ++channel.generation
        val input =
            try {
                socket.getInputStream()
            } catch (error: Exception) {
                log("CARLIFE: ${channel.label} 音频通道不可用 - ${error.message}")
                channel.dead = true
                return
            }
        val now = android.os.SystemClock.uptimeMillis()
        channel.dead = false
        channel.openedAt = now
        channel.lastPcmAt = now
        startReader(if (channel.isTts) "carlife-tts" else "carlife-media") {
            audioReader(channel, generation, input)
        }
    }

    /** 重连音频通道（9240 / 9241）。成功返回 true（已挂上新的读线程）。 */
    private fun reopenAudio(channel: AudioChannel): Boolean {
        runCatching { sockets.remove(channel.port)?.close() }
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, channel.port), connectTimeoutMs)
            if (!running.get()) {
                runCatching { socket.close() }
                return false
            }
            sockets[channel.port] = socket
            openAudio(channel, socket)
            true
        } catch (error: Exception) {
            runCatching { socket.close() }
            false
        }
    }

    /** 解析 CarlifeMusicInit：field1 sampleRate / field2 channelConfig / field3 sampleFormat。 */
    private fun parseAudioInit(payload: ByteArray): Triple<Int, Int, Int>? {
        var sampleRate = 0
        var channelConfig = 0
        var sampleFormat = 0
        var index = 0
        while (index < payload.size) {
            val tag = payload[index].toInt() and 0xFF
            index++
            if (tag and 0x07 != 0) return null
            var value = 0
            var shift = 0
            while (index < payload.size) {
                val byte = payload[index].toInt() and 0xFF
                index++
                value = value or ((byte and 0x7F) shl shift)
                shift += 7
                if (byte and 0x80 == 0) break
            }
            when (tag ushr 3) {
                1 -> sampleRate = value
                2 -> channelConfig = value
                3 -> sampleFormat = value
            }
        }
        // 字段号若与预期不符（不同版本 proto），按数值特征兜底识别：
        // 采样率必然 > 4000，位深只会是 8/16，剩下的就是声道配置。
        if (sampleRate <= 4000) {
            val candidates = listOf(sampleRate, channelConfig, sampleFormat)
            sampleRate = candidates.firstOrNull { it > 4000 } ?: DEFAULT_AUDIO_RATE
        }
        if (sampleFormat != 8 && sampleFormat != 16) {
            val candidates = listOf(sampleRate, channelConfig, sampleFormat)
            sampleFormat = candidates.firstOrNull { it == 8 || it == 16 } ?: 16
        }
        if (channelConfig <= 0 || channelConfig == sampleRate || channelConfig == sampleFormat) {
            channelConfig = 2
        }
        return Triple(sampleRate, channelConfig, sampleFormat)
    }

    /** CarLife 的 channelConfig → 声道数（1=单声道，2/3/4/12 均按立体声处理）。 */
    private fun channelsOf(channelConfig: Int): Int = if (channelConfig == 1) 1 else 2

    // ---- 心跳 ----

    private fun heartbeatLoop() {
        try {
            while (running.get()) {
                Thread.sleep(1000)
                if (!running.get()) return
                send(CarLifeProtocol.HEARTBEAT, ByteArray(0))
            }
        } catch (error: Exception) {
            if (running.get()) log("CARLIFE: 心跳中止 - ${error.message}")
        }
    }

    // ---- 底层写入 ----

    private fun send(serviceType: Int, payload: ByteArray): Boolean {
        val out = cmdOut ?: return false
        return try {
            synchronized(writeLock) {
                out.write(CarLifeProtocol.packCommand(serviceType, payload))
                out.flush()
            }
            true
        } catch (error: Exception) {
            log("CARLIFE: 发送 0x%X 失败 - %s".format(serviceType, error.message))
            false
        }
    }

    private fun readEncoderInfo(payload: ByteArray): Triple<Int, Int, Int>? {
        var width = 0
        var height = 0
        var fps = 0
        var index = 0
        while (index < payload.size) {
            val tag = payload[index].toInt() and 0xFF
            index++
            if (tag and 0x07 != 0) return null
            var value = 0
            var shift = 0
            while (index < payload.size) {
                val byte = payload[index].toInt() and 0xFF
                index++
                value = value or ((byte and 0x7F) shl shift)
                shift += 7
                if (byte and 0x80 == 0) break
            }
            when (tag ushr 3) {
                1 -> width = value
                2 -> height = value
                3 -> fps = value
            }
        }
        if (width <= 0 || height <= 0) return null
        return Triple(width, height, if (fps > 0) fps else 30)
    }

    private fun closeAll() {
        for ((_, socket) in sockets) runCatching { socket.close() }
        sockets.clear()
        cmdOut = null
        touchOut = null
    }

    private fun startReader(name: String, body: () -> Unit) {
        Thread({
            try {
                body()
            } catch (error: Exception) {
                log("CARLIFE: $name 异常 - ${error.message}")
            }
        }, name).apply {
            isDaemon = true
            start()
        }
    }

    private fun readU32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    companion object {
        /** 音频包上限：192kHz 立体声 16bit 一包 100ms ≈ 76KB，留足余量。 */
        private const val MAX_AUDIO_PACKET = 256 * 1024

        /**
         * 触摸队列上限。正常情况队列长度是 1~2（worker 写得比车机发得快）；
         * 只有引擎消费卡住时才会涨，此时丢掉最老的 MOVE 比继续排队更有意义。
         */
        private const val TOUCH_QUEUE_MAX = 8

        /**
         * 触摸坐标安全上限：**不是**视频分辨率钳位。
         *
         * 坐标系是车机 display 坐标，由引擎自己乘 `getTouchXOffset()` 换算到手机屏。
         * 这里只挡住明显非法的超大值（protobuf varint 无长度限制，但超大坐标无意义）。
         */
        private const val TOUCH_COORD_MAX = 8191

        /** 单个视频帧上限（1080p 高码率 I 帧实测 < 400KB，留足余量）。 */
        private const val MAX_VIDEO_FRAME = 4 * 1024 * 1024

        private const val DEFAULT_AUDIO_RATE = 48_000

        /** 视频通道连上但多久不出帧就重发编码初始化。 */
        private const val NO_FRAME_REINIT_MS = 10_000L

        /**
         * 98311 去重时间窗（毫秒）。
         *
         * 取 5s：要盖住握手期「SETUP 抢先发一条 + MD_INFO 延迟 2.4s 再发一条」这段
         * 天然重复；同时必须小于 [NO_FRAME_REINIT_MS]（10s），否则无帧看门狗会被
         * 自己的去重拦住、引擎永远不重建编码器。
         */
        private const val ENCODER_INIT_DEDUPE_MS = 5_000L

        /**
         * 连续重发时的最大间隔（毫秒）。
         *
         * socket 存活时只重发 98311、次数不设上限——只有 socket 真的死了才走重连。
         */
        private const val MAX_REINIT_INTERVAL_MS = 60_000L

        /** 音频看门狗轮询间隔（毫秒）。 */
        private const val AUDIO_DOG_POLL_MS = 1_000L

        /** 9240 连续多久没 PCM 才怀疑「通道活着但没声音」（毫秒）。 */
        private const val SILENT_RECONNECT_MS = 20_000L

        /** 音频通道（重新）建立后的宽限期：给引擎把流推起来的时间，别刚连上就判沉默。 */
        private const val SILENT_GRACE_MS = 20_000L

        /** 沉默重连的退避步长：第 n 次重连后等 n×20 秒才允许下一次（20 / 40 / 60）。 */
        private const val SILENT_BACKOFF_MS = 20_000L

        /** 沉默重连最多试几次（收到 PCM 即清零）。 */
        private const val SILENT_MAX_ATTEMPTS = 3

        /** 「视频仍在正常出帧」的窗口（毫秒）；超过它说明整条链路都有事，不该只怪音频。 */
        private const val VIDEO_ALIVE_MS = 3_000L

        private val PORTS = intArrayOf(
            CarLifeProtocol.PORT_CMD,
            CarLifeProtocol.PORT_VIDEO,
            CarLifeProtocol.PORT_MEDIA,
            CarLifeProtocol.PORT_TTS,
            CarLifeProtocol.PORT_VR,
            CarLifeProtocol.PORT_TOUCH,
            CarLifeProtocol.PORT_DATA,
        )
    }
}
