package io.github.shaonialife.carptocar.carlife

import android.content.Context
import io.github.shaonialife.carptocar.media.MediaSessionsState
import io.github.shaonialife.carptocar.video.VideoSender
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「本地劫持原生 CarLife」总控 —— **可选**的增强画面来源。
 *
 * 2026-10-24 产品方向变更：默认画面来源回归 **CarPToCarL 自绘桌面**（CarPToCarL 自己扮演
 * 「CarLife+ 发送端」的内容角色，协议仍是 CarPlay/AirPlay，见 SessionController
 * 的自绘分支）。本模块降级为 opt-in：只有用户在设置里打开「本地 CarLife 劫持」
 * 才会守候 7240 并接管原生 CarLife+ 码流。
 *
 * 开启后的行为不变（劫持**先于**车机会话启动）：
 *
 * ```
 * 打开 App → prewarm()：只起一个「守候线程」，**不拉起 CarLife+**
 * 你手动打开 CarLife+ 点「无线连接」→ 7240 开始监听
 *          → 守候线程发现端口 → 冒充车机连上去 → 收到 H.264 并缓存最近一个 GOP
 * 点「连接车机」→ 会话 RECORD → startPassthrough() → attach()：把缓存 GOP 灌进去
 *          → 之后每帧实时直通（不重编码）
 * ```
 */
object CarLifeHijack {

    private const val PREFS = "settings"
    private const val KEY_ENABLED = "carlife_hijack_enabled"

    /** 向 CarLife 引擎申请的编码帧率（60 = 跟手，30 = 省电）。 */
    private const val KEY_FPS = "carlife_fps"
    /**
     * 目标编码帧率。
     *
     * 实测结论（0.2.7）：CarLife 引擎把视频源钉在 20fps（50ms 定时器），98316 改到 30 反而
     * 掉到 15.4fps——33ms 轮询去取 50ms 才来一帧的源，拍频成 66ms。
     *
     * 0.2.9 方案 A 实测：把 98311 声明的 fps 直接拉到 60，看手机引擎回显的
     * `MD_VIDEO_ENC_INIT` 是否跟着变、以及实际推流帧率有没有提升。若引擎仍回 20，
     * 说明 98311 的 fps 确实只是回显、采集线程钉死 20，此路不通。
     *
     * 2026-10-25 结论：20fps 的根在**荣耀虚拟屏 `renderFrameRate`**，而它可由用户在
     * 「荣耀车联 → 设置 → 投屏显示设置 → 帧率」里选 60（默认档才是 20）。
     * 也就是说申请 60 不会被引擎/虚拟屏卡住，用户改完设置后实测就到 ~60fps。
     * 98316 那条「30 反而掉到 15.4fps」的老结论只对 20fps 源成立，源变 60 后不再适用，
     * 所以不要去动 98316，保持申请 60 即可。
     */
    private const val DEFAULT_FPS = 60

    /** 98386 连接类型（5=WiFi；1~4 为 USB 等其它档位，用于试更高编码档）。 */
    private const val KEY_CONNECT_TYPE = "carlife_connect_type"

    /** 逗号分隔的帧率变更命令候选 id，用于实测反推协议命令号。 */
    private const val KEY_PROBE_IDS = "fps_probe_ids"
    private const val DEFAULT_CONNECT_TYPE = 5

    /**
     * 一次性迁移标记 v1（历史）：曾把劫持对老安装强制开启一次。
     *
     * 2026-10-24 方向变更后被 v2 取代：v2 把被 v1 强开的劫持一次性关回去，
     * 默认画面来源回归 CarPToCarL 自绘桌面。
     */
    private const val KEY_MIGRATED_ON = "carlife_hijack_default_on_v1"

    /** 一次性迁移标记 v2：劫持回归 opt-in，默认关闭（只执行一次）。 */
    private const val KEY_MIGRATED_OFF_V2 = "carlife_hijack_default_off_v2"

    /**
     * 一次性迁移标记 v3：劫持回归默认开启（只执行一次）。
     *
     * 2026-10-24 起画面唯一来源就是「本地劫持原生 CarLife」（自绘桌面已移除），
     * 关着劫持 = 永远黑屏，所以默认改回开启。老安装若被 v2 关掉过，这里一次性打开。
     */
    private const val KEY_MIGRATED_ON_V3 = "carlife_hijack_default_on_v3"

    /** 未接入会话时缓存的最大 GOP 字节数 / 帧数（约 2 秒 720p 的量级）。 */
    private const val MAX_GOP_BYTES = 6 * 1024 * 1024
    private const val MAX_GOP_FRAMES = 240

    /** 画面停摆检查间隔 / 判定阈值（毫秒）。 */
    private const val STALL_POLL_MILLIS = 2_000L
    private const val STALL_LOG_MILLIS = 8_000L

    private val running = AtomicBoolean(false)

    /**
     * 向 CarLife 引擎申请的编码帧率（98311 的 fps 字段 + 98316 帧率变更）。
     *
     * 初值取 [DEFAULT_FPS]（60）；[start] 时用偏好 `carlife_fps` 覆盖并 clamp 到 3..60。
     * 这只是「申请值」：实际出多少帧由荣耀虚拟屏的 `renderFrameRate` 决定。该值默认 20，
     * 但**用户可在手机端「荣耀车联 → 设置 → 投屏显示设置 → 帧率」改成 30 / 60 帧**
     * （2026-10-25 实测：选 60 后引擎确实吐 ~60fps）。所以这里申请 60 是对的，
     * 别为了「和虚拟屏一致」把它改成 20。
     */
    @Volatile
    private var requestedFps = DEFAULT_FPS

    /** 98386 里的 CONNECT_TYPE（默认 5=WiFi，可在偏好 carlife_connect_type 里改）。 */
    @Volatile
    private var connectType = 5

    /** 帧率变更命令的候选 id（可由偏好覆盖）。 */
    private var probeIds: IntArray = CarLifeProtocol.FRAME_RATE_PROBE_IDS

    @Volatile
    private var client: CarLifeHuClient? = null

    /** 当前会话的视频通道；为 null 时收到的帧只进 GOP 缓存。 */
    @Volatile
    private var sender: VideoSender? = null

    private var audioSink: CarLifeAudioSink? = null

    /**
     * TTS（导航播报）专用 sink。
     *
     * 为什么不复用 [audioSink]：TTS 是**独立音频流**（9241 端口），采样率常是 16k 单声道，
     * 而且不能套用「音乐暂停 → 丢残留 PCM」的联动——导航恰恰多在音乐暂停时播报。
     * 两者各自 configure，共用同一个 [io.github.shaonialife.carptocar.audio.AudioSender]
     * （feedPcm 逐包带采样率，由发送端重采样到 48k 立体声）。
     */
    private var ttsSink: CarLifeAudioSink? = null

    /** 最近一条 CarLife 媒体信息（0x10035，歌名/歌手/封面）。供 iAP2 0x5001 转发。 */
    @Volatile
    var latestMediaInfo: CarLifeMediaInfo? = null
        private set

    /** 媒体信息变化监听（歌名/歌手变化时触发）。 */
    private val mediaInfoListeners = java.util.concurrent.CopyOnWriteArrayList<(CarLifeMediaInfo) -> Unit>()

    fun addMediaInfoListener(listener: (CarLifeMediaInfo) -> Unit) {
        mediaInfoListeners.add(listener)
    }

    fun removeMediaInfoListener(listener: (CarLifeMediaInfo) -> Unit) {
        mediaInfoListeners.remove(listener)
    }

    private var worker: Thread? = null

    /**
     * 音乐会话状态监听：车机按暂停 → 手机音乐 App PAUSED → [MediaSessionsState] 触发这里
     * → 联动 [CarLifeAudioSink.setPlaybackPaused] 截断/放行音频。prewarm 时注册一次，stop 时移除。
     */
    private val mediaPlaybackListener: () -> Unit = {
        val sink = audioSink
        if (sink != null) {
            // 明确 PAUSED 才截断；读不到会话（null）不截断——避免误丢引擎 PCM（本地模拟无声的坑）
            val paused = MediaSessionsState.nowPlaying?.playing == false
            sink.setPlaybackPaused(paused)
        }
    }

    /** 会话日志出口（prewarm 时注入）。 */
    @Volatile
    private var logSink: ((String) -> Unit)? = null

    /** 车机通告的视图尺寸（触摸报文坐标系），由会话建立时写入。 */
    @Volatile
    private var viewW = 0

    @Volatile
    private var viewH = 0

    /**
     * 车机真实分辨率（编码分辨率申请）。
     *
     * 缓存原因见 [setRequestedResolution]：AirPlay SETUP 早于 CarLife 握手完成，
     * 若只转发给当时还不存在的 client 会丢失。0 表示未收到过车机通告。
     */
    @Volatile
    private var requestedW = 0

    @Volatile
    private var requestedH = 0

    /** 触摸按下状态（HID 只给 down 布尔，需要自己合成 DOWN/MOVE/UP）。 */
    @Volatile
    private var touchPressed = false

    // ---- 双指（HID 槽 1）----
    //
    // 单点消息 0x68002/3/4 的负载是 CarlifeTouchSinglePoint{x,y}，没有指针概念，
    // 第二根手指无从表达。旧实现直接 `if (slot != 0) return true` 把它吞掉，
    // 所以双指缩放完全没反应。双指手势改走复合动作 0x68001
    // （负载 CarlifeTouchAction{action,x,y}，action 是原始 MotionEvent 动作码，
    // 指针索引在高字节），单指仍走已验证的单点通道，互不影响。

    /** 槽 1 是否按住。 */
    private var slot1Down = false

    /** 槽 1 最近坐标（视频域）。 */
    private var slot1X = 0
    private var slot1Y = 0

    /** 本次手势是否已切换到复合动作通道（从第二根手指按下的那一刻起，直到整笔结束）。 */
    @Volatile
    private var multiActive = false

    /** 本次双指手势内已发出的 MOVE 条数（日志节流 + 摘要用）。 */
    private var multiMoves = 0

    private var lastTouchAt = 0L
    private var touchCount = 0

    /** 按下点（视频坐标），用于短促点击的坐标回吸。 */
    private var downX = 0
    private var downY = 0

    /** 本次按下的起始时刻（点击判定用）。 */
    private var tapStartedAt = 0L

    /** 最近一次真正发出的坐标（补发 UP / 超时清理用）。 */
    private var lastMoveX = 0
    private var lastMoveY = 0

    /** 本次手势内已发送的 MOVE 条数（手势日志用）。 */
    private var gestureMoves = 0

    /** 本次手势起始时刻（手势日志用）。 */
    private var gestureStartAt = 0L

    /** 观测到的上报坐标最大值，用于发现「上报域 ≠ 视图域」。 */
    private var observedMaxX = 0
    private var observedMaxY = 0

    /** 未开启音频通道时被丢弃的 PCM 累计字节（限频提示）。 */
    private var audioDroppedBytes = 0L
    private var lastAudioDropLog = 0L

    /** 最近一个 GOP 缓存（从关键帧起），供会话建立瞬间「补历史帧」。 */
    private val gop = ArrayDeque<Pair<ByteArray, Boolean>>()
    private var gopBytes = 0
    private val gopLock = Any()

    /**
     * 最近一次见到的 SPS+PPS（含起始码）。
     *
     * CarLife 引擎**只在编码器刚初始化时**的那一个 IDR 里带 SPS/PPS，之后的周期性 IDR
     * （码率自适应会频繁插入）只有裸 IDR 片。若会话恰好在「带 SPS 的那帧」已经滚出缓存
     * 之后才建立，`VideoSender` 的 configSent 会永远为 false → **每一帧都被丢弃**，
     * 表现为「车机连上了但永久黑屏」（2026-10-24 实机复现：已补 8 帧 / 实际推送 0）。
     * 所以这里把 SPS/PPS 单独留存，遇到裸 IDR 时临时补回去。
     */
    @Volatile
    private var spsPps: ByteArray? = null

    /** 已收到过至少一帧（用于 UI 判断「可以连车机了」）。 */
    @Volatile
    var hasVideo: Boolean = false
        private set

    /** 已劫持并收到流（车机连接前就该为 true）。 */
    val isStreaming: Boolean get() = running.get() && client?.isRunning == true && hasVideo

    /** 已成功写进车机通道的帧数（区分「握手成功」与「车机真在收画面」）。 */
    @Volatile
    var framesDelivered: Long = 0
        private set

    private var firstDeliverLogged = false
    private var deliverWindowStart = 0L
    private var deliverWindowFrames = 0
    private var deliverWindowBytes = 0
    private var recvWindowFrames = 0
    private var recvWindowKeys = 0
    private var recvWindowBytes = 0

    /** 最近一次收到 CarLife 视频帧的时刻（uptimeMillis）；0 = 本轮还没收到过。 */
    @Volatile
    private var lastSourceFrameAt = 0L

    /** 本次停摆的起点（0 = 当前没在停摆）。用于「只记一次 + 恢复时报时长」。 */
    @Volatile
    private var stallSince = 0L

    /** 收到的帧序号（看门狗用差分量算帧率，不依赖 wall clock 对齐）。 */
    @Volatile
    private var frameSeq = 0L

    /** 车机通道是否已接上（画面正在直通）。 */
    val isAttached: Boolean get() = sender != null

    /** 劫持是否活跃（兼容旧调用点：触摸路由据此切换目标）。 */
    val isActive: Boolean get() = running.get() && client?.isRunning == true

    /**
     * 当前 CarLife **协商档位**分辨率（prewarm 阶段即可用于计算推流画布）。
     *
     * 这是给**视频声明**用的基准：车机按自己画布建解码器，声明必须跟车机/协商值走。
     * 触摸映射请用 [touchVideoSize]。
     */
    val videoSize: Pair<Int, Int>
        get() = client?.negotiatedSize ?: (1280 to 720)

    /**
     * 码流真实分辨率（SPS），**仅用于诊断与画面比例判断，不参与触摸映射**。
     *
     * v0.2.15 曾用它当触摸基准，是错的：引擎 `getTouchXOffset()` 的输入域是车机容器坐标，
     * 换算在引擎侧完成，我们再按码流缩一次就是二次缩放。v0.2.17 起触摸坐标原样透传。
     */
    val touchVideoSize: Pair<Int, Int>
        get() = client?.videoSize ?: (1280 to 720)

    /**
     * 码流**实际**分辨率（SPS 解析值）；还没解析到 SPS 时返回 null。
     *
     * 与 [videoSize] 的区别：那是「跟引擎谈定的档位」，引擎可能按白名单降档编码
     * （申请 1412×654 实编 1280×720，2026-10-29 日志实证）。界面要显示「真实在推
     * 多少」，必须用这个；没确认就返回 null，绝不能拿协商值冒充实际值。
     */
    val actualStreamSize: Pair<Int, Int>?
        get() = client?.let { if (it.videoSizeConfirmed) it.videoSize else null }

    /** SPS 确认/变更时回调（上层刷界面、更新落盘快照）。 */
    @Volatile
    var onActualStreamSize: ((Int, Int) -> Unit)? = null

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // v3 迁移：画面唯一来源就是 CarLife 劫持，默认改回开启。老安装若被 v2 关掉过，
        // 这里一次性打开；之后完全尊重用户在设置里的选择。
        if (!prefs.getBoolean(KEY_MIGRATED_ON_V3, false)) {
            prefs.edit()
                .putBoolean(KEY_ENABLED, true)
                .putBoolean(KEY_MIGRATED_ON_V3, true)
                .apply()
        }
        return prefs.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * 开始守候本机 CarLife+（**不拉起任何应用**，也不需要视频会话）。
     *
     * 只在后台轮询 7240：用户自己进 CarLife+ 点「无线连接」后端口一开就被接管。
     * 可在 App 启动时调用；重复调用安全（已在跑直接返回 true）。
     * 成功只代表「守候线程已启动」，真正连上后 [isStreaming] 才变 true。
     */
    fun prewarm(
        context: Context,
        log: (String) -> Unit,
    ): Boolean {
        if (running.get()) return true
        running.set(true)
        logSink = log
        touchPressed = false
        touchCount = 0
        gestureMoves = 0
        slot1Down = false
        multiActive = false
        multiMoves = 0
        observedMaxX = 0
        observedMaxY = 0
        hasVideo = false
        lastSourceFrameAt = 0L
        stallSince = 0L
        requestedFps = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, DEFAULT_FPS)
            .coerceIn(3, 60)
        connectType = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CONNECT_TYPE, DEFAULT_CONNECT_TYPE)
            .coerceIn(1, 8)
        probeIds = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROBE_IDS, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toIntArray()
            ?.takeIf { it.isNotEmpty() }
            ?: CarLifeProtocol.FRAME_RATE_PROBE_IDS

        worker = Thread({ workerLoop() }, "carlife-hijack").apply {
            isDaemon = true
            start()
        }
        startStallWatchdog()
        MediaSessionsState.addListener(mediaPlaybackListener)
        return true
    }

    /**
     * 会话建立后把车机通道接上：把缓存的 GOP 立刻灌进去，之后每帧实时直通。
     *
     * 调用前 [VideoSender.startPassthrough] 必须已成功（数据端口就绪）。
     */
    fun attach(videoSender: VideoSender, log: (String) -> Unit) {
        logSink = log
        val pending = synchronized(gopLock) {
            val snapshot = gop.toList()
            gop.clear()
            gopBytes = 0
            snapshot
        }
        sender = videoSender
        // 缓存里的 SPS/PPS 先交给视频层：即使下面一帧都补不出来（缓存为空 / 新会话首帧
        // 是裸 P 帧），第一个到达的帧也能立刻发出 VideoConfig，不必干等引擎重建编码器。
        spsPps?.let { videoSender.seedCsd(it) }
        startFirstFrameWatchdog(videoSender)
        if (pending.isEmpty()) {
            log("CARLIFE: 尚无可补的历史帧（缓存为空），等待 CarLife 推流…")
            return
        }
        var pushed = 0
        for ((data, key) in pending) {
            if (videoSender.submitH264(data, key)) {
                pushed++
                noteDelivered(data.size)
            }
        }
        log("CARLIFE: 已补 ${pending.size} 帧历史画面（实际推送 $pushed）")
        // 一帧都没送出去 = 缓存里没有 SPS/PPS（VideoConfig 发不出去就会全速丢帧）。
        // 让引擎重建编码器，它随后会补一个带 SPS 的 IDR，画面即可恢复。
        if (pushed == 0) {
            log("CARLIFE: 缓存里没有 SPS/PPS——请求引擎重建编码器（约 1 秒后出首帧）")
            client?.requestVideoReinit()
        }
    }

    /**
     * 接上通道后 3 秒仍一帧都送不出去，说明 VideoConfig 卡住了（SPS 缺失）。
     * 再补一次编码器重建；重建后引擎必然发「SPS+PPS+IDR」，链路自愈。
     */
    private fun startFirstFrameWatchdog(videoSender: VideoSender) {
        val base = framesDelivered
        Thread {
            try {
                Thread.sleep(3_000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (sender !== videoSender || framesDelivered > base) return@Thread
            logSink?.invoke("CARLIFE: 接上通道 3 秒零帧——再请求一次编码器重建")
            client?.requestVideoReinit()
        }.apply { isDaemon = true }.start()
    }

    /** 会话结束：断开视频通道，回到「只缓存」状态（CarLife 连接保持不断）。 */
    fun detach() {
        sender = null
        firstDeliverLogged = false
        deliverWindowFrames = 0
    }

    /** 完全停止（退出 App / 关闭开关）。 */
    fun stop() {
        running.set(false)
        MediaSessionsState.removeListener(mediaPlaybackListener)
        sender = null
        client?.stop()
        client = null
        audioSink?.stop()
        audioSink = null
        ttsSink?.stop()
        ttsSink = null
        touchPressed = false
        gestureMoves = 0
        slot1Down = false
        multiActive = false
        multiMoves = 0
        worker?.interrupt()
        worker = null
        logSink = null
        hasVideo = false
        lastSourceFrameAt = 0L
        stallSince = 0L
        synchronized(gopLock) {
            gop.clear()
            gopBytes = 0
        }
    }

    // ---- 后台主循环 ----

    private fun workerLoop() {
        var announcedWait = false
        while (running.get()) {
            // 2026-10-25 回滚：**不再自动拉起** CarLife 引擎。
            // 自动拉起（bindService / startForegroundService / CarlifeActivity）实测能让
            // 引擎起来并监听 7240，但拿不到荣耀的画面源——引擎只会把「荣耀车联 Powered by
            // MagicOS」欢迎页反复编码送过来（恒定 ~10KB 静止帧），车机侧表现为
            // 「连接成功但长时间没画面」。真实画面必须由荣耀车联自己创建虚拟屏后才有，
            // 所以改回：用户手动打开 CarLife+ 点「无线连接」，本线程只负责守候并接管。
            if (!portListening(CarLifeProtocol.PORT_CMD)) {
                hasVideo = false
                if (!announcedWait) {
                    announcedWait = true
                    logSink?.invoke(
                        "CARLIFE: 7240 未监听，等待本机 CarLife 引擎启动" +
                            "（请在手机端打开 CarLife+ 并点「无线连接」，端口一开立即接管）",
                    )
                }
                sleep(1500)
                continue
            }
            announcedWait = false
            val sink = CarLifeAudioSink(logSink ?: {})
            audioSink = sink
            // 导航播报走 TTS 通道（9241）：独立 sink、不参与暂停丢弃（见字段注释）
            val naviSink = CarLifeAudioSink(logSink ?: {}, dropWhenPaused = false)
            ttsSink = naviSink
            // 创建即应用当前播放状态：若此刻音乐已是 PAUSED，立刻截断（否则 listener 只在
            // 状态「变化」时触发，稳态 PAUSED 下新 sink 会漏掉这次联动）。
            // 注意：nowPlaying == null（读不到媒体会话：未授权通知使用权 / 本地模拟无会话）
            // 时**不能**当作「暂停」来截断——那会误丢引擎仍在推的 PCM，导致本地模拟无声。
            // 读不到状态 = 不确定，宁可不截断（放行），交由引擎侧 STOP/DATA 自行决定。
            sink.setPlaybackPaused(MediaSessionsState.nowPlaying?.playing == false)
            val hu = CarLifeHuClient(
                host = "127.0.0.1",
                log = { line -> logSink?.invoke(line) },
                onVideoFrame = { data, key -> onFrame(data, key) },
                onVideoParams = { w, h, f -> logSink?.invoke("CARLIFE: 手机编码参数 ${w}x$h@$f") },
                // SPS 是码流的真相：确认后立刻告诉上层（界面显示、落盘快照都跟着它）。
                onStreamSize = { w, h -> onActualStreamSize?.invoke(w, h) },
                onAudioFormat = { rate, channels -> sink.configure(rate, channels) },
                onAudioPcm = { pcm, size -> routeAudio(sink, pcm, size) },
                // 导航播报：TTS 通道独立格式、独立 sink，喂给同一个音频发送端
                onTtsFormat = { rate, channels -> naviSink.configure(rate, channels) },
                onTtsPcm = { pcm, size -> routeAudio(naviSink, pcm, size) },
                onAudioGate = { active -> onEngineAudioGate(active) },
                onMediaInfo = { info -> onMediaInfo(info) },
                requestedFps = requestedFps,
                connectType = connectType,
                probeIds = probeIds,
                // 回放 SETUP 阶段缓存的车机真实分辨率：首次 98311 就报对，不必二次重发。
                // 未收到通告时退回 1280×720（引擎默认档位）。
                initialWidth = if (requestedW > 0) requestedW else 1280,
                initialHeight = if (requestedH > 0) requestedH else 720,
            )
            if (hu.start()) {
                client = hu
                // 会话建立前缓存过 SPS：立刻用它校准触摸基准，别等下一帧关键帧。
                spsPps?.let { hu.seedSps(it) }
                // CarPlay 会话仍在、CarLife 换了一轮（引擎掉线重连 / 用户重开「无线连接」）：
                // 车机手里的 avcC 与参考帧都是上一轮的，必须让视频层重发 VideoConfig、
                // 并等到新 IDR 再送帧，否则「CarPlay 不掉、CarLife 重连后永久没画面」
                // （2026-10-25 用户实测）。首次劫持时 sender 还是 null，这里是空操作。
                if (sender != null) {
                    sender?.onSourceRestart("CarLife 会话重建（引擎重连）")
                    startSourceRestartWatchdog(hu)
                }
                logSink?.invoke("CARLIFE: 劫持成功，已接管本机 CarLife 码流（等待连接车机）")
            } else {
                logSink?.invoke("CARLIFE: 握手失败，2 秒后重试（确认 CarLife 停在「正在连接」）")
            }
            // 保持连接直到断开或停止
            while (running.get() && hu.isRunning) {
                sleep(500)
            }
            if (client === hu) client = null
            sink.stop()
            if (audioSink === sink) audioSink = null
            naviSink.stop()
            if (ttsSink === naviSink) ttsSink = null
            hasVideo = false
            if (running.get()) sleep(1500)
        }
    }

    /**
     * 源重启后 4 秒仍一帧都没送进车机 → 请求引擎重建编码器。
     *
     * 覆盖的是「新会话的首个 IDR 不带 SPS/PPS」这种边角：`VideoSender` 没有可用 csd
     * 就发不出 VideoConfig，帧被逐帧丢弃，看起来又是「重连后一直黑屏」。引擎重建后
     * 必然发「SPS+PPS+IDR」，链路自愈。与 [startFirstFrameWatchdog] 的区别：
     * 那个只在「车机刚接上通道」时跑一次，这里的触发点每次 CarLife 会话重建都会重新计。
     */
    private fun startSourceRestartWatchdog(hu: CarLifeHuClient) {
        val base = framesDelivered
        Thread {
            try {
                Thread.sleep(4_000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!running.get() || client !== hu || framesDelivered > base) return@Thread
            logSink?.invoke("CARLIFE: 源重启后 4 秒零帧——请求引擎重建编码器（约 1 秒后出 SPS+IDR）")
            hu.requestVideoReinit()
        }.apply { isDaemon = true }.start()
    }

    /**
     * 引擎音频开停信号（0x30002 开始 / 0x30003 停止）。
     *
     * 真机时间线（2026-10-26）：来电时引擎发 0x30003，之后音频整段归零；挂断并恢复播放
     * 才发 0x30002。停过一段再恢复时，车机手里的 RTP↔NTP 锚点已经超时，恢复瞬间开头
     * 几百毫秒容易被丢——所以恢复时补发一轮同步包重新对齐。这里**不拦 PCM**：
     * 万一信号语义判断有误，音频照常转发，不会因为猜错把声音弄没。
     */
    private fun onEngineAudioGate(active: Boolean) {
        if (!active) {
            logSink?.invoke("CARLIFE: 引擎音频已停（来电 / 音频路由被切走）——车机侧这一段会静音")
            return
        }
        val audio = io.github.shaonialife.carptocar.SessionController.audioSender
        if (audio != null && audio.isRunning) {
            audio.resyncAnchor("引擎音频恢复")
        } else {
            logSink?.invoke("CARLIFE: 引擎音频恢复，但音频通道未在运行——跳过重新对齐")
        }
    }

    /**
     * 画面源停摆看门狗（只观测，不干预）。
     *
     * 来电时手机侧画面被通话界面占用（系统安全窗口不可录），CarLife 引擎几乎不出帧，
     * 车机表现为**黑屏**。这里 8 秒没有新帧就记一条带时长的日志，恢复时再记一条，
     * 用来区分「引擎真没画面」与「链路/车机侧解码问题」——两者处置完全不同。
     * 不主动重建编码器：通话期间根本没有画面可编，硬重建只会把水搅浑。
     */
    private fun startStallWatchdog() {
        Thread {
            var previous = frameSeq
            var lowRateSince = 0L
            while (running.get()) {
                try {
                    Thread.sleep(STALL_POLL_MILLIS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!running.get()) return@Thread
                val now = android.os.SystemClock.uptimeMillis()
                val current = frameSeq
                val delta = current - previous
                previous = current
                if (sender == null) {
                    lowRateSince = 0L
                    continue
                }
                // 注意不能按「一帧都没有」判：来电期间引擎仍会发 0.8fps 的心跳帧，
                // 只是没有新 IDR。这里按窗口帧数判（2 秒内少于 4 帧 ≈ 低于 2fps）。
                if (delta >= 4) {
                    lowRateSince = 0L
                    continue
                }
                if (lowRateSince == 0L) {
                    lowRateSince = now - STALL_POLL_MILLIS
                    continue
                }
                if (stallSince > 0 || now - lowRateSince < STALL_LOG_MILLIS) continue
                stallSince = lowRateSince
                logSink?.invoke(
                    "CARLIFE: 画面源停摆 ${(now - lowRateSince) / 1000} 秒（帧率 <2fps，零个新 IDR）" +
                        "——引擎这段时间几乎不出帧（来电/锁屏时手机侧画面被占用，车机会黑屏；" +
                        "恢复后会补关键帧）",
                )
            }
        }.apply {
            isDaemon = true
            name = "carlife-stall-watch"
            start()
        }
    }

    /** 一节视频帧：有会话就直通，没有就缓存（从关键帧开始）。 */
    private fun onFrame(
        data: ByteArray,
        keyFrame: Boolean,
    ) {
        recvWindowFrames++
        recvWindowBytes += data.size
        if (keyFrame) recvWindowKeys++
        // 停摆检测的打卡点（来电时引擎会整段不出帧，只能靠这里判断「是源没画面」还是链路断）
        val now = android.os.SystemClock.uptimeMillis()
        lastSourceFrameAt = now
        frameSeq++
        val stalledSince = stallSince
        if (stalledSince > 0) {
            stallSince = 0
            logSink?.invoke("CARLIFE: 画面源恢复（停摆 ${(now - stalledSince) / 1000} 秒）")
        }
        if (!hasVideo && data.isNotEmpty()) {
            hasVideo = true
            // 独立于一会话：还没连车机时也要能看出「CarLife 到底在不在推流」。
            // 排查编码器被配置搞挂时，这是唯一的直接证据。
            logSink?.invoke("CARLIFE: ✓ 收到 CarLife 首帧（${data.size}B，keyFrame=$keyFrame）")
        }
        rememberSpsPps(data)
        val frame = ensureSpsPps(data)
        val target = sender
        if (target != null) {
            if (target.submitH264(frame, keyFrame)) noteDelivered(frame.size)
            return
        }
        synchronized(gopLock) {
            if (keyFrame) {
                gop.clear()
                gopBytes = 0
            } else if (gop.isEmpty()) {
                return // 没有关键帧打头，非关键帧单独送过去也解不出画面
            }
            gop.addLast(frame to keyFrame)
            gopBytes += frame.size
            while (gop.size > MAX_GOP_FRAMES || gopBytes > MAX_GOP_BYTES) {
                val removed = gop.removeFirstOrNull() ?: break
                gopBytes -= removed.first.size
            }
        }
    }

    /** AnnexB 起始码长度（3 或 4），非起始码返回 0。 */
    private fun startCodeLen(
        data: ByteArray,
        index: Int,
    ): Int =
        when {
            index + 4 <= data.size && data[index] == 0.toByte() && data[index + 1] == 0.toByte() &&
                data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte() -> 4
            index + 3 <= data.size && data[index] == 0.toByte() && data[index + 1] == 0.toByte() &&
                data[index + 2] == 1.toByte() -> 3
            else -> 0
        }

    /**
     * 从帧里摘出 SPS(7)+PPS(8) 留存。CarLife 只在编码器初始化后的第一个 IDR 里带它们，
     * 之后周期性 IDR 是裸片——必须自己记住。
     */
    private fun rememberSpsPps(data: ByteArray) {
        val headPrefix = startCodeLen(data, 0)
        if (headPrefix == 0) return
        val headType = data[headPrefix].toInt() and 0x1F
        // 快路径：普通 P 帧（类型 1）不可能带 SPS，直接跳过整帧扫描。
        val couldCarrySps = headType == 7 || headType == 9 || (spsPps == null && headType == 5)
        if (!couldCarrySps) return
        var spsAt = -1
        var ppsEnd = -1
        var index = 0
        while (index < data.size) {
            val prefix = startCodeLen(data, index)
            if (prefix == 0) {
                index++
                continue
            }
            val nalStart = index
            val bodyStart = index + prefix
            if (bodyStart >= data.size) break
            val type = data[bodyStart].toInt() and 0x1F
            var next = bodyStart + 1
            while (next < data.size && startCodeLen(data, next) == 0) next++
            if (type == 7 && spsAt < 0) spsAt = nalStart
            if (type == 8) ppsEnd = next
            index = next
        }
        if (spsAt < 0 || ppsEnd <= spsAt) return
        val snapshot = data.copyOfRange(spsAt, ppsEnd)
        if (snapshot.size > 4) spsPps = snapshot
    }

    /**
     * 关键帧若是裸 IDR（不含 SPS），把留存的 SPS/PPS 补到前面，保证 `VideoSender`
     * 能发出 VideoConfig；否则 configSent 永远为 false，每一帧都会被静默丢弃。
     */
    private fun ensureSpsPps(data: ByteArray): ByteArray {
        if (data.size < 5) return data
        val prefix = startCodeLen(data, 0)
        if (prefix == 0) return data
        val type = data[prefix].toInt() and 0x1F
        if (type == 7) return data // 自带 SPS
        if (type != 5) return data // 只有 IDR 才需要补
        val csd = spsPps ?: return data
        val merged = ByteArray(csd.size + data.size)
        System.arraycopy(csd, 0, merged, 0, csd.size)
        System.arraycopy(data, 0, merged, csd.size, data.size)
        return merged
    }

    /**
     * 送帧统计：首帧一行 + 之后每 10s 一行。
     *
     * 除了 fps 还必须报**实测码率**——帧率被引擎钉死在 20 之后，画质是唯一还能
     * 调节的维度，而画质好不好只能靠码率判断（1080p@20 低于 4Mbps 就能看到压缩块）。
     * 有这个数字才能验证 `/sdcard/BaiduCarlife/video_config.json` 到底生效没有。
     */
    private fun noteDelivered(bytes: Int) {
        framesDelivered++
        deliverWindowFrames++
        deliverWindowBytes += bytes
        val now = android.os.SystemClock.uptimeMillis()
        if (!firstDeliverLogged) {
            firstDeliverLogged = true
            deliverWindowStart = now
            deliverWindowFrames = 0
            deliverWindowBytes = 0
            logSink?.invoke("CARLIFE: ✓ 已开始向车机送帧（首帧 ${bytes}B）")
            return
        }
        if (now - deliverWindowStart < 10_000) return
        val elapsed = now - deliverWindowStart
        val fps = deliverWindowFrames * 1000f / elapsed
        val bps = deliverWindowBytes * 8_000L / elapsed
        // 同时报收帧侧的 I 帧频率：这是验证 98386 的 I_FRAME_INTERVAL 有没有被
        // 引擎采纳的硬指标——间隔 300 时 15 秒才一个 I 帧，间隔 20 时每秒一个。
        logSink?.invoke(
            "CARLIFE: 车机送帧中 %.1f fps · %.2f Mbps · 均帧 %d KB".format(
                fps,
                bps / 1_000_000f,
                deliverWindowBytes / deliverWindowFrames.coerceAtLeast(1) / 1024,
            ) + " ｜ 收到 %d 帧 / I 帧 %d 个 · %.2f Mbps".format(
                recvWindowFrames,
                recvWindowKeys,
                recvWindowBytes * 8_000L / elapsed / 1_000_000f,
            ),
        )
        deliverWindowStart = now
        deliverWindowFrames = 0
        deliverWindowBytes = 0
        recvWindowFrames = 0
        recvWindowKeys = 0
        recvWindowBytes = 0
    }

    /** 会话建立时由 SessionController 写入车机视图尺寸（触摸报文坐标系）。 */
    fun setViewSize(
        width: Int,
        height: Int,
    ) {
        viewW = width
        viewH = height
    }

    /**
     * 车机连上后把真实屏幕分辨率转给 CarLife 引擎，让它按车机画布编码。
     *
     * 与 [setViewSize] 的区别：这里写的是**编码分辨率申请**（98311 width/height），
     * 而 setViewSize 写的是**触摸坐标映射**用的车机视图尺寸。两者通常同源
     * （都来自 AirPlay SETUP 的 displayWidth/Height），但语义不同，分开命名避免混淆。
     *
     * **必须缓存**：AirPlay SETUP（此时调用本方法）通常早于 CarLife 握手完成（workerLoop
     * 里 client 才创建），此前的实现 `client?.setRequestedResolution(...)` 在 client 为 null
     * 时把分辨率直接丢了，导致 98311 永远报默认 1280×720、画面被拉伸（2026-10-25 实测）。
     * 现在缓存下来，client 创建时作为 initialWidth/Height 传入。
     */
    fun setRequestedResolution(
        width: Int,
        height: Int,
    ) {
        requestedW = width
        requestedH = height
        client?.setRequestedResolution(width, height)
    }

    /**
     * PCM 的去向判定：**只有音频通道真的在跑时才本机播放**。
     *
     * 本次连接若没勾「开启声音」，AirPlay 音频流不存在，本地播放只会从手机喇叭
     * 外放（用户不想要、也白耗电），所以直接丢弃并限频提示。
     */
    private fun routeAudio(
        sink: CarLifeAudioSink,
        pcm: ByteArray,
        size: Int,
    ) {
        val routed = io.github.shaonialife.carptocar.SessionController.audioSender?.isRunning == true
        if (routed) {
            sink.write(0, 0, pcm, size)
            return
        }
        audioDroppedBytes += size
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAudioDropLog >= AUDIO_DROP_LOG_MS) {
            lastAudioDropLog = now
            logSink?.invoke(
                "CARLIFE: 收到 CarLife 音频 ${audioDroppedBytes / 1024}KB，但本次连接未开启声音" +
                    "（「启动连接」弹窗里勾选后即可出声）；已丢弃",
            )
            audioDroppedBytes = 0
        }
    }

    /** 收到 CarLife 媒体信息（歌名/歌手/封面）：记录并广播，供 iAP2 0x5001 转发。 */
    private fun onMediaInfo(info: CarLifeMediaInfo) {
        latestMediaInfo = info
        mediaInfoListeners.forEach { runCatching { it(info) } }
    }

    // ---- 触控回传（车机 → 手机）----

    /**
     * 处理一次车机触摸报文。返回 true = 已被劫持链路消费（调用方不要再走虚拟屏注入）。
     *
     * **坐标一律原样透传**（车机 display 坐标，即 viewW×viewH 域）。
     *
     * 引擎侧 `CarlifeTouchManager.dispatchTouchEvent` 拿到的 x/y 会自己乘
     * `getTouchXOffset()`：mix 连接下是 `CastConfig.width / mContainerWidth`，
     * 普通投屏下是 `手机屏宽 / mContainerWidth`——也就是说**引擎的输入域就是车机容器坐标，
     * 换算由它完成**。我们若先按码流 SPS 分辨率再缩一次，等于二次缩放：
     * 码流 1280×720、车机画布 1412×654 时横竖向各缩 10%，拖拽位移同比变小、
     * 松手落点偏移（v0.2.15 引入的问题，v0.2.17 撤销）。
     */
    fun handleTouch(
        slot: Int,
        x: Int,
        y: Int,
        down: Boolean,
    ): Boolean {
        if (!isActive) return false
        // HID 触摸报文最多两槽（TouchReports.SLOTS）；再多的槽位直接吞掉，避免漏到注入路径
        if (slot < 0 || slot > 1) return true
        val hu = client ?: return true
        if (viewW <= 0 || viewH <= 0) return true

        val now = android.os.SystemClock.uptimeMillis()
        val tx = x.coerceIn(0, viewW - 1)
        val ty = y.coerceIn(0, viewH - 1)
        if (x > observedMaxX) observedMaxX = x
        if (y > observedMaxY) observedMaxY = y

        // 槽 1 单独处理：它只会让整笔手势切换到复合动作通道，不参与单点状态机。
        if (slot == 1) {
            onSecondPointer(hu, tx, ty, down, x, y)
            return true
        }

        // ---- 手势收尾：车机漏发 UP 会让引擎永久卡在「按住」状态 ----
        //
        // 引擎 onActionDownEvent 里有这段（已反汇编确认）：
        //   if (isErrorTouchModel()) { if (eventIng) { 记录 errorDown; return } }  // DOWN 被丢弃
        //   eventIng = true
        // 一旦某一笔的 UP 没送到，eventIng 永远是 true → 之后每一次 DOWN 都被丢弃，
        // 只剩 MOVE，而 MOVE 又被 isErrorEvent 判成「更靠近上次的错误按下点」而 return。
        // 表现就是：点一下（或拖一下）之后，整块屏幕再也点不动、拖不动。
        //
        // 所以不能等 3 秒兜底：只要与上一笔间隔超过 NEW_GESTURE_GAP_MS 又收到按下，
        // 就认定上一笔缺抬起，先补一个 UP 再开新笔。
        if (touchPressed) {
            val gap = now - lastTouchAt
            if (gap > NEW_GESTURE_GAP_MS && down) {
                finishGesture(hu, lastMoveX, lastMoveY, now, gap, "上一笔缺抬起 ${gap}ms")
            } else if (gap > STALE_TOUCH_MS) {
                finishGesture(hu, lastMoveX, lastMoveY, now, gap, "触摸超时 ${gap}ms")
            }
        }

        when {
            down && !touchPressed -> {
                touchPressed = true
                gestureStartAt = now
                gestureMoves = 0
                downX = tx
                downY = ty
                tapStartedAt = now
                lastTouchAt = now
                lastMoveX = tx
                lastMoveY = ty
                hu.touchDown(tx, ty)
                logSink?.invoke(
                    "CARLIFE: 触控 ↓ ($x,$y)→($tx,$ty)（视图 ${viewW}x$viewH）",
                )
            }

            down -> {
                // 不做本地节流：引擎 dispatchTouchEvent 自带 10ms 限频，
                // 本地再丢一次只会让拖拽变成「跳跃采样」。发送队列已按
                // 「MOVE 只留最新」合并，不会积压。
                lastTouchAt = now
                lastMoveX = tx
                lastMoveY = ty
                gestureMoves++
                if (multiActive) {
                    // 双指手势已切换到复合动作通道，第一根手指的移动也得走同一条通道，
                    // 否则引擎会在两条通道上各维护一套指针状态（实测会互相覆盖）。
                    multiMoves++
                    hu.touchAction(CarLifeProtocol.TouchAction.MOVE, tx, ty)
                } else {
                    hu.touchMove(tx, ty)
                }
            }

            touchPressed -> {
                // 短促且几乎没有位移 = 点击：用按下点收尾，避免车机端把轻微抖动
                // 当拖动、或把抬起坐标算到相邻控件上。双指手势不算点击。
                val isTap = !multiActive &&
                    now - tapStartedAt <= TAP_MAX_MS &&
                    kotlin.math.abs(tx - downX) <= TAP_MAX_DRIFT_PX &&
                    kotlin.math.abs(ty - downY) <= TAP_MAX_DRIFT_PX
                finishGesture(
                    hu,
                    if (isTap) downX else tx,
                    if (isTap) downY else ty,
                    now,
                    now - lastTouchAt,
                    null,
                )
            }

            else -> return true
        }
        touchCount++
        if (touchCount % 200 == 0) {
            logSink?.invoke(
                "CARLIFE: 触控累计 $touchCount 条（已写入通道 ${hu.sentTouchCount} 条，" +
                    "坐标观测上限 ${observedMaxX}x${observedMaxY}，视图 ${viewW}x$viewH）",
            )
        }
        return true
    }

    /**
     * 第二根手指（HID 槽 1）。
     *
     * 按下 → 整笔手势切到复合动作通道，先发 `ACTION_POINTER_DOWN | (1 shl 8)`；
     * 移动 → 发 `ACTION_MOVE`（带该手指坐标）；抬起 → `ACTION_POINTER_UP | (1 shl 8)`。
     * 之后第一根手指仍由 [handleTouch] 的槽 0 分支继续走复合通道，直到整笔结束。
     */
    private fun onSecondPointer(
        hu: CarLifeHuClient,
        tx: Int,
        ty: Int,
        down: Boolean,
        rawX: Int,
        rawY: Int,
    ) {
        val act = CarLifeProtocol.TouchAction
        if (!down) {
            if (!slot1Down) return
            slot1Down = false
            if (multiActive && touchPressed) {
                hu.touchAction(act.withPointer(act.POINTER_UP, 1), slot1X, slot1Y)
                logSink?.invoke("CARLIFE: 多指 ↑2 ($slot1X,$slot1Y) → POINTER_UP（手指 1）")
            }
            return
        }
        // 第一根手指还没按下就来了槽 1：多半是车机把占位槽报成按下，忽略
        if (!touchPressed) return
        if (!slot1Down) {
            slot1Down = true
            slot1X = tx
            slot1Y = ty
            if (!multiActive) {
                multiActive = true
                multiMoves = 0
                gestureMoves = 0
                hu.touchAction(act.withPointer(act.POINTER_DOWN, 1), tx, ty)
                logSink?.invoke(
                    "CARLIFE: 多指 ↓2 ($rawX,$rawY)→($tx,$ty) 进入双指模式" +
                        "（复合动作 0x68001，手指 1 按下）",
                )
            }
            return
        }
        // 已按住：坐标变化即该手指移动
        if (tx == slot1X && ty == slot1Y) return
        slot1X = tx
        slot1Y = ty
        if (!multiActive) return
        multiMoves++
        hu.touchAction(act.MOVE, tx, ty)
        if (multiMoves <= 5 || multiMoves % 20 == 0) {
            logSink?.invoke("CARLIFE: 多指 移动2 ($tx,$ty) MOVE#${multiMoves}")
        }
    }

    /** 结束一笔手势：发 UP，并按需打印拖动摘要（点击不刷屏）。 */
    private fun finishGesture(
        hu: CarLifeHuClient,
        ux: Int,
        uy: Int,
        now: Long,
        gap: Long,
        abnormal: String?,
    ) {
        touchPressed = false
        lastTouchAt = now
        lastMoveX = ux
        lastMoveY = uy
        val act = CarLifeProtocol.TouchAction
        if (multiActive) {
            // 双指手势：先把还按着的手指 1 抬起，再用 ACTION_UP 收尾。
            // 顺序反了会让引擎留下一个永不放开的幽灵触点。
            if (slot1Down) {
                hu.touchAction(act.withPointer(act.POINTER_UP, 1), slot1X, slot1Y)
                slot1Down = false
            }
            hu.touchAction(act.UP, ux, uy)
            logSink?.invoke(
                "CARLIFE: 双指手势结束（手指 2 移动 ${multiMoves} 次）→ 复合通道 UP" +
                    (if (abnormal != null) " · $abnormal" else ""),
            )
            multiActive = false
            multiMoves = 0
        } else {
            slot1Down = false
            hu.touchUp(ux, uy)
        }
        val duration = now - gestureStartAt
        val span = kotlin.math.abs(ux - downX) + kotlin.math.abs(uy - downY)
        if (abnormal != null) {
            logSink?.invoke("CARLIFE: $abnormal → 补发 UP ($ux,$uy)")
        } else if (!multiActive && (gestureMoves >= 3 || duration >= 300L)) {
            logSink?.invoke(
                "CARLIFE: 手势结束 ${gestureMoves}次移动 时长${duration}ms " +
                    "位移${span}px ↓($downX,$downY) → UP($ux,$uy)",
            )
        }
        gestureMoves = 0
    }

    /** 新手势间隔：超过这个时长又收到按下，就认定上一笔的 UP 丢了（毫秒）。 */
    private const val NEW_GESTURE_GAP_MS = 220L

    /** 点击判定：按住时长上限（毫秒）。 */
    private const val TAP_MAX_MS = 350L

    /** 点击判定：允许的手指抖动（视频像素）。 */
    private const val TAP_MAX_DRIFT_PX = 8

    /** 按下后多久没有任何事件就认定 UP 丢失（毫秒）。 */
    private const val STALE_TOUCH_MS = 3_000L

    /** 「音频被丢弃」提示的限频（毫秒）。 */
    private const val AUDIO_DROP_LOG_MS = 10_000L

    private fun portListening(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 800)
            true
        }
    } catch (error: Exception) {
        false
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
