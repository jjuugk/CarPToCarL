package io.github.shaonialife.carptocar

import android.content.Context
import android.view.KeyEvent
import io.github.shaonialife.carptocar.airplay.AirPlayClient
import io.github.shaonialife.carptocar.airplay.FreshAuthPolicy
import io.github.shaonialife.carptocar.airplay.PairVerifyClient
import io.github.shaonialife.carptocar.airplay.PairingGate
import io.github.shaonialife.carptocar.airplay.PairingMemory
import io.github.shaonialife.carptocar.airplay.StreamOrderMemory
import io.github.shaonialife.carptocar.airplay.VirtualIap2Link
import io.github.shaonialife.carptocar.audio.AudioProfile
import io.github.shaonialife.carptocar.audio.AudioProfileMemory
import io.github.shaonialife.carptocar.audio.AudioSender
import io.github.shaonialife.carptocar.audio.AudioTuning
import io.github.shaonialife.carptocar.audio.MicUplink
import io.github.shaonialife.carptocar.bt.BtCompatPrefs
import io.github.shaonialife.carptocar.bt.BtProfiles
import io.github.shaonialife.carptocar.bt.BtSessionGate
import io.github.shaonialife.carptocar.bt.Iap2BluetoothClient
import io.github.shaonialife.carptocar.bt.Iap2BluetoothServer
import io.github.shaonialife.carptocar.bt.TargetDevicePrefs
import io.github.shaonialife.carptocar.capture.HiddenDisplaySource
import io.github.shaonialife.carptocar.carlife.CarGpsFix
import io.github.shaonialife.carptocar.carlife.CarLifeHijack
import io.github.shaonialife.carptocar.carlife.CarLifeProtocol
import io.github.shaonialife.carptocar.carlife.CarGpsOption
import io.github.shaonialife.carptocar.carlife.VoiceKeyRouter
import io.github.shaonialife.carptocar.iap2.AccessoryDeclaration
import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2MessageIds
import io.github.shaonialife.carptocar.iap2.Iap2NowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import io.github.shaonialife.carptocar.iap2.NmeaAssembler
import io.github.shaonialife.carptocar.iap2.NmeaLocation
import io.github.shaonialife.carptocar.iap2.ReceiverProfile
import io.github.shaonialife.carptocar.input.ConsumerKeys
import io.github.shaonialife.carptocar.input.TouchReports
import io.github.shaonialife.carptocar.input.TouchRouter
import io.github.shaonialife.carptocar.launcher.VirtualLauncher
import io.github.shaonialife.carptocar.launcher.settings.AppOrientationCompat
import io.github.shaonialife.carptocar.launcher.settings.DisplayPrefs
import io.github.shaonialife.carptocar.media.MediaSessionsState
import io.github.shaonialife.carptocar.media.NotificationAccess
import io.github.shaonialife.carptocar.net.AirPlayFeatures
import io.github.shaonialife.carptocar.net.CarPlayCtrlAdvertiser
import io.github.shaonialife.carptocar.net.CarPlayServiceBrowser
import io.github.shaonialife.carptocar.net.EndpointHosts
import io.github.shaonialife.carptocar.net.SilentJoinPolicy
import io.github.shaonialife.carptocar.net.WifiCarPlayConnector
import io.github.shaonialife.carptocar.net.WifiJoinGate
import io.github.shaonialife.carptocar.net.WifiLinkWatchdog
import io.github.shaonialife.carptocar.net.WifiSavedNetworks
import io.github.shaonialife.carptocar.protocol.ProtocolAudit
import io.github.shaonialife.carptocar.protocol.ProtocolGeneration
import io.github.shaonialife.carptocar.service.CarPlayService
import io.github.shaonialife.carptocar.service.ProcFreeze
import io.github.shaonialife.carptocar.ui.ScreenDimmer
import io.github.shaonialife.carptocar.util.Bg
import io.github.shaonialife.carptocar.util.ConnectBudget
import io.github.shaonialife.carptocar.util.ConnectStage
import io.github.shaonialife.carptocar.util.DebugFileLog
import io.github.shaonialife.carptocar.util.MicDumpWriter
import io.github.shaonialife.carptocar.util.SessionTrace
import io.github.shaonialife.carptocar.util.SysShell
import io.github.shaonialife.carptocar.video.StreamFps
import io.github.shaonialife.carptocar.video.VideoCanvasMode
import io.github.shaonialife.carptocar.video.VideoSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程级会话中枢：**日志缓冲**与**会话管线所有权**的唯一持有者。
 *
 * 背景（2026-10-11 用户实测发现三个连带问题）：投屏管线运行在 MainActivity 派生线程里，
 * 划掉界面（Activity 销毁）后进程被前台服务保活、管线继续跑；但重进应用创建的是**新
 * Activity 实例**——
 *  1. 老线程的日志写进老实例（已销毁）的 UI → 新界面"日志断联"（文件日志实际未断）
 *  2. 「断开连接」操作新实例的新对象 → 老管线无人持有，断不掉
 *  3. 重开连接会建第二套对象 → 双会话风险
 *
 * 本对象把这些状态提升为进程级单例：Activity 只是"挂屏"（订阅日志、转发按钮），
 * 销毁/重建对其无影响。会话逻辑线程通过 [appContext] 运行，不依赖任何 Activity。
 */
object SessionController {
    // ---- 日志缓冲（进程级；Activity 订阅渲染）----

    private val logLock = Any()
    private val logBuilder = StringBuilder()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 当前挂屏 Activity 的渲染回调（收到**本条新增日志行**，含时间戳前缀）；Activity onDestroy 时清空。 */
    @Volatile
    var logListener: ((String) -> Unit)? = null

    /** 「监听日志」开关（由 Activity 从偏好读取后同步；关闭时不采集不渲染）。 */
    @Volatile
    var logEnabled: Boolean = true

    @Volatile
    private var appContext: Context? = null

    /**
     * 本轮连接的阶段计时器：进阶段打一行、结束打汇总。
     *
     * 存在的意义是回答「这次连接为什么慢、慢在哪」——旧实现这个答案散落在七个阶段的
     * 各自日志里，只能人工掐时间（2026-11-01 日志 090105：连接 29.7s，音频等 88s）。
     */
    private val connectBudget = ConnectBudget(sink = { line -> appendLog(line) })

    /** WiFi 等待用户操作的窗口（打开 WLAN 开关 / 手动加入热点的轮询上限）。 */
    private const val WLAN_WAIT_TIMEOUT_MS = 120_000L

    /** 加入确认弹窗的应答等待上限（无人应答按中止处理）。 */
    private const val JOIN_DIALOG_TIMEOUT_S = 90L

    /**
     * 空端点（车机声明会发 0x4301 但窗口内未到）的等待上限。
     *
     * 奥迪 MMI 9310 实测 0x4301 晚到 2 分 35 秒——保活窗口必须显著长于它；
     * 超时拆链让 BT 重连循环接管（与旧「8s 拆链」的区别只在窗口长度）。
     */
    private const val PENDING_START_SESSION_WAIT_MS = 5 * 60_000L

    /**
     * 保活期「当前 WiFi 里有没有车机 AirPlay 服务」的探测节奏。
     *
     * 一次探测 = 绑当前 WiFi + 起 8s mDNS + 停。30s 一次的用意是接住「车机是在被点了
     * 之后才把 AirPlay 起起来」的情况（车机侧启动常常晚于蓝牙握手几十秒）。
     */
    private const val PROBE_INTERVAL_MS = 30_000L

    /** 一次探测等 `_airplay._tcp` 的时长；局域网内 Bonjour 应答通常 <500ms。 */
    private const val PROBE_TIMEOUT_MS = 8_000L

    /** 同一网络里最多探 6 次（≈3 分钟），再多只是刷日志。 */
    private const val PROBE_MAX_TIMES = 6

    /**
     * 等车机 ctrl 邀请的上限（事件驱动，收到即走）。
     * catplay 以邀请为连接触发点；实测邀请在 ctrl 重注册后 ~1.0-1.3s 到达。
     * 车机不发邀请时（旧机型）由「已发现 _airplay._tcp」提前放行，最坏也只等这么久。
     */
    private const val CTRL_INVITE_WAIT_MS = 4_000L

    /** 端点不完整时等 mDNS 缓存命中：绑网时已开始发现，通常几百毫秒内命中。 */
    private const val ENDPOINT_RESOLVE_WAIT_MS = 3_000L

    /**
     * 明文 SETUP 被拒后「加密升级」补配对的上限（catplay handle_setup_result 路径）。
     * 只在实际被拒时才走到这里，正常车机零成本；不支持配对的接收端会在
     * pair-setup 收到 404/405/455 后立刻放弃，不会等满这个窗口。
     */
    private const val PAIRING_UPGRADE_WINDOW_MS = 30_000L

    /**
     * 明文会话初始 SETUP 的重试窗口。pre-HomeKit 车机的 AirPlay 端口常在
     * 「用户点车机上的手机图标」后才开放；端口晚开 1~2 秒不该让整轮失败。
     */
    private const val INITIAL_SETUP_WINDOW_MS = 30_000L

    /**
     * 端点已到但手机还没切到车机热点时的等待上限。
     * 覆盖「自动加入（几秒）」与「用户手动加入（常见 10-30s）」两种路径；
     * 超时后明确提示并复位 sessionStarting，不留「永久正在连接」。
     */
    private const val CAR_WIFI_BIND_WAIT_MS = 45_000L

    /**
     * `requestNetwork(WIFI)` 等 onAvailable 的上限（秒）。
     * 用户正在系统设置里手动加入热点时，回调要等关联+DHCP 完成才来，实测可达 10s+。
     * 旧值 5s 会在成功前 116ms 误判失败并放弃整轮会话（见 bindCarWifiAndRegister）。
     */
    private const val WIFI_BIND_WAIT_S = 15L

    /**
     * 热点被动断开后自动重绑的最小间隔（毫秒）。
     *
     * 车机熄火 / 走出范围 / ROM 踢关联都会连发多次 `onLost`，没有闸门就会形成
     * 「重绑→失败→再来一轮」的风暴，而每轮重绑都要 release + requestNetwork，
     * 恰好把正在恢复的关联又打断一次。15 秒 > 一次应用专属网络的关联+DHCP 时间。
     */
    private const val WIFI_REBIND_MIN_INTERVAL_MS = 15_000L

    /**
     * 等应用专属网络（`WifiNetworkSpecifier`）确认框的上限。
     *
     * **必须与车机的等待窗口对齐**：BMW iD9（IDC23）实测在交出 0x4301 后约 27 秒
     * 就拆蓝牙链（2026-10-29，连续 6 轮 25~28s）。旧值 120s 意味着每轮都要白等到
     * 车机先放弃，期间还会叠上下一轮的新请求（旧线程仍在 latch 上转），表现成
     * 「连上就闪退 + 后续越连越连不上」。15s 留出车机窗口内再试一次的余地。
     */
    private const val SPECIFIER_JOIN_TIMEOUT_MS = 15_000L

    /**
     * 入网正式窗口到点后的**宽限**：只继续等，不解绑、不弹失败。
     *
     * 由来（2026-11-07，同机型 HONOR WKL-AN20 + BMW94841 / NBT EVO）：一份能连上的参考
     * 实现日志里「尝试加入 BMW94841 CarPlay 13:05:23.407 → 已绑定 wlan0 13:05:39.136」
     * = **15.73 秒**；而我们对所有车机只等 15 秒。于是每一轮都恰好差最后半秒：
     * 15.0s 判定超时并 `release()`（注销回调 + 解绑进程网络），系统在 15.7s 建好的那条
     * 网络随即被拆掉，现场观感就是「一直卡在加入车机热点」。
     *
     * 20 秒是按该实测值留足余量（覆盖 DHCP 慢、系统确认框晚弹的情况），
     * 对真正的失败（用户点了拒绝、热点不在范围）只是把失败提示推迟 20 秒——
     * [WifiCarPlayConnector.joinCarPlayNetwork] 收到 `onUnavailable` 时**立即**返回，
     * 不会白等这段宽限。
     */
    private const val SPECIFIER_JOIN_GRACE_MS = 20_000L

    /**
     * **建议兜底**窗口：specifier 没进来之后，登记 WiFi 建议、等系统自己把手机连上热点。
     *
     * 只在 specifier 返回 `Unavailable` 后才走这一段——1.0.7 到 1.2.30 一直是用
     * specifier 直接连的（用户实测**不需要点任何东西**：系统对已有授权/关联记录的热点
     * 会静默满足请求），所以快路必须先发。建议通道是给「系统确实要人确认」的机型兜底的，
     * 排在前面会每轮白等这段窗口、把快路挤掉。
     *
     * 12 秒的取值：覆盖参考实现实测 15.73s 里「系统侧关联」那一段，又不至于把失败场景拖太久。
     */
    private const val SUGGESTION_SILENT_WAIT_MS = 12_000L

    /**
     * 建议兜底连续失败多少次后不再白等。
     *
     * 建议未授权时每轮白等 12 秒，连着几轮就会把「车机只等 27 秒」的窗口吃光。
     * 所以给两次机会：第一次登记后系统可能正在弹授权通知，第二次接住刚授权的那一下；
     * 两次都没成，说明这台机器上这条通道不可用，别再耗时间——但**也不弹任何框**。
     */
    private const val SILENT_JOIN_MAX_MISSES = 2

    /**
     * 车机麦克风上行开关（**本轮验证用**）。
     *
     * 打开后每轮会话：建音频流之前先绑一个本地 UDP 端口，把端口写进主音频 SETUP 的
     * `dataPort`（真 iPhone 要麦就是这个写法，不是另开 `input=true` 的流），
     * 建流成功后由 [MicUplink] 收包、解密、把 PCM 落成 wav 到 `下载/CarPToCarL`。
     *
     * 验证内容只有一件：**车机到底推不推**。日志看 `MIC: …`，wav 用耳朵听。
     * 通了之后再谈「灌进 CarLife 9242」——那一步引擎认不认注入还是未知。
     *
     * 风险控制：端口是**可选字段**，只在开关打开时出现；关掉就退回纯下行，
     * 与 1.2.31 的行为完全一致。看到下行音频出问题就先把这里改 false。
     */
    private const val MIC_UPLINK_ENABLED = true

    /**
     * 「HID 报告映射不到 usage」最多打几条日志。
     *
     * 释放报文每按一次都会来一条（值为 0，已单独静默），但**越界值**只可能出现在
     * 「车机按了我们没声明的键」——正是语音键排查要的证据，限 8 条足够看清是哪个 usage，
     * 又不至于按住不放时刷屏。
     */
    private const val UNKNOWN_CONSUMER_LOG_LIMIT = 8

    /**
     * 旧的「独立 `input=true` 流」探针开关（**已停用**，代码先留着）。
     *
     * 2026-11-08 由 xcertplay 的接收端实现纠正：真 iPhone 不这么要麦，车机很可能不认；
     * 而且它会额外建一条同类型的流，和正常的音频流抢车机资源。新机制见 [MIC_UPLINK_ENABLED]。
     */
    private const val MIC_PROBE_ENABLED = false

    /** 探针启动前的等待：音频流刚建好时车机在做资源仲裁，抢跑会被拒。 */
    private const val MIC_PROBE_DELAY_MS = 12_000L

    /** 每组参数的监听窗口（毫秒）。车机推麦克风是**持续**流的，6 秒足够判断有没有。 */
    private const val MIC_PROBE_LISTEN_MS = 6_000L

    /**
     * 探针参数组合（按 `/info` 输入位图里最像语音的三档排序）。
     *
     * - `speechRecognition` / `0x40`（bit6）/ 24kHz：Siri 档，输入位图 `0x20000040` 里的低位
     * - `telephony` / `0x8000`（PCM48k 立体声）/ 48kHz：通话档，`0x30000050` 含 PCM 位
     * - `default` / `0x8000` / 48kHz：默认档，`0x30000050` 同样含 PCM 位
     * - `compatibility` / `0x40` / 24kHz：兼容档，`0x54`
     */
    private val MIC_PROBE_COMBOS =
        listOf(
            MicProbeCombo("speechRecognition", 1L shl 6, 24_000),
            MicProbeCombo("telephony", 1L shl 15, 48_000),
            MicProbeCombo("default", 1L shl 15, 48_000),
            MicProbeCombo("compatibility", 1L shl 6, 24_000),
        )

    /**
     * 系统确认框路径失败后的自动重试次数与间隔。
     *
     * 旧实现在这里弹「重试 / 打开 WLAN 设置」问用户——车上没人点屏，90 秒后按无人应答
     * 中止。可「点重试」和「自动再试一次」做的事完全一样，唯一区别是要人点。所以改成
     * 自动做，用次数兜住（热点不在范围时不至于无限怼）。
     */
    private const val SPECIFIER_AUTO_RETRY_TIMES = 2
    private const val SPECIFIER_RETRY_GAP_MS = 5_000L

    /**
     * 同 SSID 入网请求的去重窗口（毫秒）。
     *
     * 0x5703 与 0x4301 两条路都会触发入网，且**在同一个瞬间**各发一次（2026-11-02
     * 日志：06.581 / 06.586 两行「以应用专属网络加入」）。`joinCarPlayNetwork` 第一行
     * 就是 `release()`——后发的那次会把先发的请求就地注销，网络回调一起消失，
     * 于是「被动断开自动重绑」失效、本次入网白花 28 秒（15s 超时 + 弹窗重试）。
     * 5 秒只是挡住这种紧挨着的重复，用户手点「重试」走 force 通道不受影响。
     */
    private const val WIFI_JOIN_DEDUPE_MS = 5_000L

    /**
     * 建链失败后的自动重试退避（毫秒）。
     *
     * 车机侧会话残留时立刻重连必然失败（对方还没清干净旧会话），等它一拍才有机会：
     * 3s 覆盖「刚被拆、马上能重进」，10s 覆盖「车机重启 AirPlay 服务」，30s 覆盖
     * 「上一轮被硬断后 TCP 端口彻底关掉再开」。三次用完还在失败就是车机侧真坏了
     * （2026-11-02 MBUX：SETUP 回 200 却 7ms 后 FIN），继续怼只会刷屏。
     */
    private val CONNECT_RETRY_BACKOFF_MS = longArrayOf(3_000L, 10_000L, 30_000L)

    /**
     * 推流中「因车机邀请而主动重建」的防抖窗口（ms）。
     *
     * 重建本身要 3~4 秒（退役 + 重新绑定 + 配对 + SETUP + 等 IDR），而被车机拆掉要等
     * 18~20 秒，所以正常情况下重建永远划算。这个窗口只防一种极端：车机在会话刚起来时
     * 连续发邀请，两侧互相拆——那样会比被拆更糟（永远出不了首帧）。
     * ID7 实测的邀请间隔是 18~22 秒（含我们重连耗时），15 秒窗口刚好放行、又能挡住抖动。
     */
    private val STREAMING_INVITE_REBUILD_GAP_MS = 15_000L

    /**
     * 建链失败自动重试的互斥 + 序号。
     *
     * [retryGeneration] 是「这一轮重试还算不算数」的令牌：车机 ctrl 邀请到达时
     * 会把它 +1 并立刻另起一轮，挂在退避睡眠里的旧线程醒来后发现令牌变了就安静退出，
     * 不会叠出两轮并行交接（两轮同时配对必然互相 retireSession）。
     */
    private val connectRetryLock = Any()
    private var connectRetryScheduled = false
    private var connectRetryAttempt = 0
    private var connectRetryGeneration = 0

    /**
     * 「复用网络 ≠ 复用认证」：同一接收端连续两轮没建立起会话，下一轮强制重做认证。
     *
     * 只影响**认证姿态**（是否还按上一次的配对结论跳过 pair-setup），不影响入网顺序，
     * 也不会因此拆掉已经在推流的会话。判据与 [PairingMemory] 同一把 key（见
     * [FreshAuthPolicy] 注释）：两处若各用一套，记忆跳过认证、失败计数却以为在重做，
     * 正好把「失败两次后必须重做」这个兜底吃掉。
     */
    private val freshAuthPolicy = FreshAuthPolicy(FreshAuthPolicy.DEFAULT_FAILURE_THRESHOLD)

    /** 本轮认证归属的接收端 key——成功/失败结算时用它核算连续失败轮次。 */
    @Volatile
    private var authReceiverKey: String? = null

    /**
     * 推流期间收到的 ctrl 邀请计数与时刻（2026-11-03 ID7 实测）。
     *
     * 以前推流中的邀请直接 `return` 丢掉，日志里一个字都没有——ID7 反复断流时
     * 完全看不出车机来过，排障只能靠猜。现在至少记一行，并按下面两条规则处理。
     */
    private var streamingInviteCount = 0
    private var lastStreamingInviteAt = 0L

    /** 上一次「因推流中邀请而主动重建」的时刻（防抖，避免车机抖动时反复拆会话）。 */
    private var lastInviteRebuildAt = 0L

    /** Activity onCreate 时调用：登记 applicationContext 并创建会话对象（幂等）。 */
    fun ensure(context: Context) {
        appContext = context.applicationContext
        ensureSessionObjects(context.applicationContext)
        // 横屏兼容写入失败的原因（user build 限制 / shell 无权限）只有这里能落盘：
        // 车机侧设置面板的 toast 只给用户看，排查需要日志。
        AppOrientationCompat.diagSink = ::appendLog
        // 主线程调用 shell 的告警（历史上两次真机 ANR 都源于此；HKS 下 logcat 不可读）
        SysShell.violationSink = ::appendLog
        installNowPlayingBridge()
        startMediaWatchdog()
        // 「车机只认苹果设备」兼容模式（默认开，2026-11-08 起）：App 一起来就挂上
        // 占位 SDP 并常开监听——车机是在 discovery/配对阶段就把设备判成「不支持
        // Apple CarPlay」的，等用户点「启动连接」才注册已经晚了。
        startBtCompatWatchdog()
        if (BtCompatPrefs.enabled(context.applicationContext)) applyBtCompat(true)
    }

    /** 媒体看门狗是否已启动（幂等）。 */
    private var mediaWatchdogStarted = false

    /**
     * 媒体信息看门狗：**通知监听器没被系统绑上时，定时用系统 API 请求重绑**。
     *
     * 为什么需要：重装 APK 后授权还在（`enabled_notification_listeners` 里有本组件），
     * 但系统的「Live notification listeners」里已经没有它——`onListenerConnected`
     * 不来，`MediaSession.metadata` 就恒为 null，车机端歌名整程空白
     * （2026-10-25 实测：`dumpsys notification` Live 段无本组件 → 0x5000 到达时
     * provider 返回 null → 一条 0x5001 都发不出去）。
     *
     * 旧的自动恢复走 `cmd notification disallow/allow`，需要 shell/Shizuku；用户移除
     * Shizuku 后就永久坏掉。这里改用系统公开 API `requestRebind()`，无需任何权限。
     * 连上后拉长到 60s 只看不动，避免无意义调用。
     */
    private fun startMediaWatchdog() {
        if (mediaWatchdogStarted) return
        mediaWatchdogStarted = true
        Thread({
            var attempts = 0
            var hinted = false
            while (true) {
                val connected = NotificationAccess.isConnected
                try {
                    Thread.sleep(if (connected) 60_000L else 8_000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (NotificationAccess.isConnected) continue
                val context = appContext ?: continue
                attempts++
                NotificationAccess.requestSystemRebind(context, ::appendLog)
                // requestRebind 在部分 ROM（荣耀 MagicOS 实测）不生效：系统只认
                // 「通知使用权」设置页里的手动关→开。3 次（≈24s）仍没绑上就明说，
                // 免得用户对着没有歌名的车机猜。
                if (!hinted && attempts >= 3) {
                    hinted = true
                    appendLog(
                        "MEDIA: ⚠ 通知使用权已授权但系统始终没绑定监听器——" +
                            "请到「设置 → 通知使用权」把本应用关掉再打开一次（车机歌名依赖它）",
                    )
                    uiHintSink?.invoke("车机歌名不显示：请到「设置 → 通知使用权」把本应用关掉再打开")
                }
            }
        }, "media-watchdog").apply { isDaemon = true }.start()
    }

    /** 注册 CarLife 媒体信息 → iAP2 0x5001 的桥接（幂等，见 [ensure]）。 */
    private var nowPlayingBridgeInstalled = false

    private fun installNowPlayingBridge() {
        if (nowPlayingBridgeInstalled) return
        nowPlayingBridgeInstalled = true
        // 首帧后**不再**补发 assertModes + RECORD（2026-11-08，按宝马 fork 的实车结论）。
        //
        // 这段原本是给奥迪 4580 修的（81e75a6「首帧送达后重建屏激活」）：建屏阶段喂过
        // 旧帧或长时间无画面时，屏幕会话可能已进错误态（固件 badFramesThreshold=4），
        // 需要在合法当期帧到达后重新触发一次让屏仲裁。
        //
        // 但宝马 NBT EVO 上这一步是**反向的**：首帧之后重发 RECORD，车机会直接关掉
        // 视频通道（fork 实测：补发即 Broken pipe / 通道关闭）。而宝马的画面对它自己的
        // 建屏时序是够用的，不需要这一脚。
        //
        // 取舍：全局关掉（用户 2026-11-08 决定）。代价是奥迪 4580 那种「连接成功但黑屏」
        // 的老症若复现，需要把下面这段接回去——原始实现见 git show 81e75a6。
        // CarLifeHijack.onVideoSourceRecovered = { ... assertModes(); record() }
        // 数据源 A：CarLife 内置播放器（0x10035）——兜底，仅其内置播放器会推。
        CarLifeHijack.addMediaInfoListener { info ->
            appendLog(
                "EVENT: CarLife 歌名「${info.song}」→ 补推 iAP2 0x5001 NowPlayingUpdate",
            )
            pushNowPlayingAll()
        }
        // 数据源 B：系统媒体会话（第三方播放器 QQ音乐/网易云等）——主源。
        // 第三方播放器不触发 CarLife 的 0x10035，歌名只能从 MediaSession 拿。
        MediaSessionsState.addListener {
            val np = MediaSessionsState.nowPlaying ?: return@addListener
            appendLog(
                "EVENT: MediaSession 歌名「${np.title ?: "?"}」→ 补推 iAP2 0x5001 NowPlayingUpdate",
            )
            pushNowPlayingAll()
        }
    }

    /**
     * 歌名变化：同时补推 WiFi 隧道（currentClient）与蓝牙链路（server/client）。
     *
     * 去重保护：部分播放器（荣耀音乐等）会把「逐行歌词」写进 MediaSession title，
     * 导致每秒多次内容变化、且同一行会因多触发源（1s 轮询 + 会话回调 + metadata 回调）
     * 重复 publish。这里按 title+artist 去重，只在真正切歌/切歌词行时推一次。
     */
    private fun pushNowPlayingAll() {
        val np = buildNowPlaying() ?: return
        val key = "${np.title}|${np.artist}"
        if (key == lastPushedNowPlayingKey) return
        lastPushedNowPlayingKey = key
        currentClient?.pushNowPlaying()
        bluetoothServer?.pushNowPlaying()
        bluetoothClient?.pushNowPlaying()
    }

    /** 上次推送的 0x5001 内容键（title|artist），用于去重。 */
    private var lastPushedNowPlayingKey: String? = null

    /**
     * 新一轮连接开始：清掉 0x5001 去重键。
     *
     * 去重键是「上次推过什么」，跨会话保留就是 bug：同一首歌在重连后需要重推一次
     * （接收端重连后是全新会话，之前收到的歌名不算数）。旧实现里 18:26 推过
     * 「真爱假说」后，18:35 重连的整轮会话即使媒体事件触发也会被这行去重静默吞掉
     * ——车机端持续空白，日志里连一行「补推」都没有（2026-10-25 实测）。
     */
    fun resetNowPlayingDedupe() {
        lastPushedNowPlayingKey = null
    }

    /**
     * 合并两个歌名数据源，构造 iAP2 0x5001 载荷。
     *
     * 优先系统媒体会话（覆盖第三方播放器 + CarLife 内置播放器的绝大多数场景），
     * 读不到再退回 CarLife 0x10035 推来的媒体信息；两者皆无返回 null（不响应）。
     */
    private fun buildNowPlaying(): CarPlayNowPlaying? {
        val session = MediaSessionsState.nowPlaying
        if (session != null && session.title != null) {
            return CarPlayNowPlaying(
                title = session.title,
                artist = session.artist,
                album = null, // MediaSession 未单独暴露专辑名，这里留空
                durationMs = session.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                status =
                    if (session.playing) {
                        Iap2NowPlaying.STATUS_PLAYING
                    } else {
                        Iap2NowPlaying.STATUS_PAUSED
                    },
                elapsedMs =
                    session
                        .displayPositionAt(android.os.SystemClock.elapsedRealtime())
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt(),
                appName = session.pkg,
            )
        }
        val info = CarLifeHijack.latestMediaInfo ?: return null
        return CarPlayNowPlaying(
            title = info.song.takeIf { it.isNotBlank() },
            artist = info.artist.takeIf { it.isNotBlank() },
            album = info.album.takeIf { it.isNotBlank() },
            durationMs = info.duration,
            status = Iap2NowPlaying.STATUS_PLAYING, // CarLife 推歌名即视为播放中
            elapsedMs = 0,
            appName = info.source.takeIf { it.isNotBlank() },
        )
    }

    /** 追加一行日志：logcat + 文件 + 进程级缓冲 + 挂屏渲染（增量行直送）。任意线程可调。 */
    fun appendLog(message: String) {
        // NUL 会写进日志文件：文本编辑器/文件管理器打不开（Read 工具也会判为二进制），
        // 用户反馈日志时等于白导。0x4301 的定长地址字段曾原样打印出多个 NUL
        // （2026-10-18 实测 216 个），这里替换成可见占位符——顺带让「字符串里有脏字节」
        // 在日志上直接可见，而不是隐形成 InetSocketAddress 的解析失败。
        val clean = if (message.indexOf('\u0000') >= 0) message.replace("\u0000", "␀") else message
        android.util.Log.i("CarPlaySender", clean)
        appContext?.let {
            DebugFileLog
                .append(it, clean)
        }
        if (!logEnabled) return
        val line =
            synchronized(logLock) {
                val stamped = '[' + timestamp.format(Date()) + "] " + clean + '\n'
                logBuilder.append(stamped)
                if (logBuilder.length > 40_000) logBuilder.delete(0, logBuilder.length - 20_000)
                stamped
            }
        // 增量行直送 UI：此前每行 toString 整个缓冲再 setText 全文，
        // 高频日志（触摸/帧）下是 UI 线程与 GC 的双重放大器
        logListener?.invoke(line)
    }

    fun logText(): String = synchronized(logLock) { logBuilder.toString() }

    /**
     * 派发系统媒体键。桌面音乐卡的按钮与车机/方向盘媒体键（Consumer HID）共用这一路径。
     *
     * 优先级：**先直投当前活跃音乐会话的 transportControls**（不依赖焦点路由，精确命中
     * 手机音乐 App，避开 CarPToCarL 自身 AudioTrack 抢占焦点导致的「暂停不生效」），
     * 直投失败再回退到 AudioManager 全局路由。
     */
    fun dispatchMediaKey(keyCode: Int) {
        val action = MediaSessionsState.transportActionFor(keyCode)
        if (action >= 0 && MediaSessionsState.dispatchTransport(action)) {
            return
        }
        // 回退：无活跃会话 / 动作不受支持时走系统焦点路由
        val ctx = appContext ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /** 车机定位样本合并器：GGA/RMC/GSA 分条到达，必须拼成一份完整快照再下发。 */
    private val carNmea = NmeaAssembler()

    /** 无定位样本的日志计数（车机 1Hz 推，别刷屏）。 */
    private var carGpsNoFixFrames = 0

    /**
     * CarLife 控制命令（GPS / 语音键）走独立单线程，队列只留 2 条、满了丢最旧的。
     *
     * 为什么不能在收帧线程直接 `send`：那是一笔**本地 socket 写**，一旦 CarLife 引擎
     * 卡住，写会阻塞 iAP2 收帧线程——而 iAP2 控制通道靠「及时回 ACK」续命（漏 ACK
     * 车机 10 秒就关通道）。GPS 每秒一条、语音键一次，都是可丢弃/可延迟的数据，
     * 绝不能让它有能力卡住链路。
     */
    private val carLifeCommandSender =
        java.util.concurrent.ThreadPoolExecutor(
            1,
            1,
            0L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(2),
            { runnable -> Thread(runnable, "carlife-cmd").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy(),
        )

    /**
     * 车机语音键 → CarLife 硬键（0x68008）的状态机（1.2.22）。
     *
     * 实测这台车的语音键走 AirPlay 事件通道 `requestSiri`（siriAction=2 按下 / 3 松开），
     * 不是 iAP2 `0x6802` 的 HID `0xCF`；两条通路都收进同一个 [VoiceKeyRouter]，
     * 由它做边沿触发和「长按才补 VR_STOP」的收尾判定。这里只负责把动作搬去
     * [carLifeCommandSender]，不占 iAP2 收帧线程。
     */
    private val voiceKey =
        VoiceKeyRouter(
            send = { keycode ->
                carLifeCommandSender.execute {
                    val ok = CarLifeHijack.sendVoiceKey(keycode)
                    appendLog(
                        "CARLIFE: 车机语音键 0x%02X → 0x68008 ".format(keycode) +
                            if (ok) "已送达" else "未送达（CarLife 未接管）",
                    )
                }
            },
            log = ::appendLog,
        )

    /**
     * 车机定位 → CarLife（0x18010 `CarlifeCarGps`）。
     *
     * 开关见 [CarGpsOption]（设置里「使用车机 GPS」，默认开）。关掉后**一个字节都不发**，
     * CarLife 继续用手机自己的定位——这是默认行为，也是开关要能回去的状态。
     *
     * 只有 [NmeaLocation.Fix.hasPosition] 为真才发：车机在地库/隧道里会持续推一条
     * 没有经纬度的样本（奥迪就是 `$PASCD,…,C,D,0,…`，fix=0）。把这种样本发给引擎
     * 会让地图飘到 (0,0)，比不给更糟。
     */
    private fun onCarLocation(sentences: List<String>) {
        if (!CarGpsOption.enabled) return
        for (sentence in sentences) carNmea.feed(sentence)
        val merged = carNmea.snapshot() ?: return
        if (!merged.hasPosition) {
            carGpsNoFixFrames++
            if (carGpsNoFixFrames <= 3 || carGpsNoFixFrames % 60 == 0) {
                appendLog(
                    "GPS: 车机定位样本无经纬度（${merged.source}，fix=${merged.fixQuality}）" +
                        "——本次不发 0x18010，CarLife 继续用手机定位" +
                        if (merged.source == "PASCD") {
                            "（该车机只发 Apple 私有 \$PASCD，实测形态不含坐标）"
                        } else {
                            ""
                        },
                )
            }
            return
        }
        val carFix = toCarGpsFix(merged)
        carLifeCommandSender.execute { CarLifeHijack.sendCarGps(carFix) }
    }

    /** NMEA 快照 → CarLife 的单位约定；UTC 日期缺失时用当前 UTC 补（CarLife 只用来标记时刻）。 */
    private fun toCarGpsFix(fix: NmeaLocation.Fix): CarGpsFix {
        val now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
        return CarGpsFix(
            latitude = fix.latitude ?: 0.0,
            longitude = fix.longitude ?: 0.0,
            altitudeMeters = fix.altitudeMeters,
            speedKmh = fix.speedKmh,
            headingDeg = fix.headingDeg,
            fixQuality = fix.fixQuality,
            satellitesUsed = fix.satellitesUsed,
            satellitesVisible = maxOf(fix.satellitesVisible, fix.satellitesUsed),
            hdop = fix.hdop,
            pdop = fix.pdop,
            vdop = fix.vdop,
            utcYear = if (fix.utcYear > 0) fix.utcYear else now.year,
            utcMonth = if (fix.utcMonth > 0) fix.utcMonth else now.monthValue,
            utcDay = if (fix.utcDay > 0) fix.utcDay else now.dayOfMonth,
            utcHour = if (fix.utcYear > 0 || fix.utcHour > 0) fix.utcHour else now.hour,
            utcMinute = if (fix.utcYear > 0 || fix.utcMinute > 0) fix.utcMinute else now.minute,
            utcSecond = if (fix.utcYear > 0 || fix.utcSecond > 0) fix.utcSecond else now.second,
        )
    }

    /**
     * 清空进程级日志缓冲（UI 刷新是调用方的事：显式 `renderConsole(logText())`）。
     * 历史上这里靠 `logListener?.invoke("")` 通知 UI——那是「每行全量 setText」时代的
     * 语义；改为增量 appendConsole 后空串等于追加空行，UI 原地不动（「清除按钮点了
     * 没反应」根因，2026-10-16）。不要恢复该调用：空串与真实日志行无法区分。
     */
    fun clearLog() {
        synchronized(logLock) { logBuilder.setLength(0) }
    }

    // ---- 会话对象（进程级所有权；Activity 重建不影响）----

    private var wifiConnector: WifiCarPlayConnector? = null

    /**
     * 当前已绑定到哪个车机热点（bindCarWifiAndRegister 成功后写入）。
     * 端点交接时若已绑定到同一 SSID，跳过二次 bindCurrentWifi——它内部先
     * release() 解绑，会打断刚注册好的 ctrl mDNS 广播并白等系统重连。
     */
    @Volatile
    private var boundCarSsid: String? = null

    /** 本次进程内已就「是否清除系统保存热点」询问过的 SSID（避免重试时重复弹窗）。 */
    private val forgetPromptHandled =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    /** WiFi 绑定互斥：auto-join 与端点交接两条路径可能同时绑定同一热点。 */
    private val wifiBindLock = Any()

    /**
     * 「入网请求」互斥与去重（见 [WifiJoinGate] 与 [WIFI_JOIN_DEDUPE_MS]）。
     *
     * 两条路径（0x5703 / 0x4301）会在同一瞬间各发一次入网，而
     * `joinCarPlayNetwork` 第一行就是 `release()`——后发的会把先发的注销掉。
     */
    private val wifiJoinGate = WifiJoinGate(WIFI_JOIN_DEDUPE_MS)

    /**
     * 静默入网（WiFi 建议自动关联）的尝试预算，见 [SilentJoinPolicy]。
     * 建议未授权时每轮会白等，用预算把「白等」限制成最多两次。
     */
    private val silentJoinPolicy = SilentJoinPolicy(SILENT_JOIN_MAX_MISSES)

    /** DCL 双检锁的可发布字段（无 volatile 的双检锁是坏发布——虽然当前仅主线程调用，别留隐患）。 */
    @Volatile
    private var bluetoothServer: Iap2BluetoothServer? = null
    private var bluetoothClient: Iap2BluetoothClient? = null
    private var ctrlAdvertiser: CarPlayCtrlAdvertiser? = null

    /**
     * 蓝牙监听是「兼容模式的常开挂机」而不是「本轮连接拉起来的」。
     *
     * 只影响 [isSessionRunning] 的判定——不能让它冒充「有会话在跑」，
     * 否则 `maybeAutoConnect` 会以为自己已经在连、通知栏会写成「蓝牙已连上车机」。
     * 一旦真有设备连进来（[Iap2BluetoothServer] 的 onPeerConnected）立刻清零。
     */
    @Volatile
    private var idleListening = false

    /**
     * 用户在本轮里**手动断过**（或关掉了开关）：常开监听不再自动复活。
     *
     * 没有这一位的话，「断开」之后 30 秒看门狗又会把监听拉起来——用户明确叫停的东西
     * 自己回来了，车机在旁边时还会重新握手。下一次点「启动连接」或重新打开开关才清。
     */
    @Volatile
    private var compatSuppressed = false
    private var btCompatWatchdogStarted = false

    /**
     * 常驻 `_airplay._tcp` 发现器（对照 catplay 的常驻 Bonjour cache）。
     * 加入热点即开始后台发现，交接端点时地址多半已就绪，不再现查现等。
     */
    private var endpointBrowser: CarPlayServiceBrowser? = null

    @Volatile
    var videoSender: VideoSender? = null

    // ---- 「显示」运行时生效值（会话建立时快照；英雄卡展示运行时值而非偏好值）----

    /** 本会话流分辨率缩放百分比（按车机分辨率的百分比生成；100=原尺寸）。 */
    @Volatile
    var activeScalePercent: Int = 100

    /**
     * 本会话目标帧率：车机 `/info displays[0].maxFPS` 有值就跟随，没声明按 60
     * （判定见 [StreamFps]）。会话建立前是 60，SETUP 之后才是最终值。
     */
    @Volatile
    var activeTargetFps: Int = StreamFps.DEFAULT_FPS

    // ---- 本会话分辨率（英雄卡 / 显示卡片展示，均为会话建立时的快照）----

    /** 车机通告的屏幕宽（`/info displays[0].widthPixels`；0 = 还没连过）。 */
    @Volatile
    var activeCarWidth: Int = 0

    /** 车机通告的屏幕高。 */
    @Volatile
    var activeCarHeight: Int = 0

    /**
     * 车机分辨率是否真的来自车机。
     *
     * false = 车机没回 `/info` displays，[activeCarWidth]/[activeCarHeight] 是手机横屏兜底值，
     * UI 必须照实标注——把兜底值当车机参数展示，会让人误判「车机就是竖屏/小屏」。
     */
    @Volatile
    var activeCarResolutionFromCar: Boolean = false

    /** 本会话真正上线的码流宽（直通模式下取 CarLife 引擎实际编码尺寸）。 */
    @Volatile
    var activeStreamWidth: Int = 0

    /** 本会话真正上线的码流高。 */
    @Volatile
    var activeStreamHeight: Int = 0

    @Volatile
    var audioSender: AudioSender? = null

    /**
     * 车机麦克风上行监听（收包 + 解密 + 落 wav）。
     *
     * 与 [audioSender] 成对存在：端口在音频 SETUP 里声明，所以必须先有 [MicUplink] 才能建流；
     * 流的生命周期与音频流一致（换流/断会话时 stop）。
     */
    @Volatile
    var micUplink: MicUplink? = null

    @Volatile
    var currentClient: AirPlayClient? = null

    /** 通话状态看门狗线程（音频会话期间每秒看一次 AudioManager.mode）。 */
    private var callWatcher: Thread? = null

    @Volatile
    var currentHiddenSource: HiddenDisplaySource? = null

    /** 当前会话的共享密钥与接收端地址：投影授权晚于连接时，动态补启音频用。 */
    @Volatile
    var currentSharedSecret: ByteArray? = null

    @Volatile
    var currentAudioHost: String? = null

    /** 本次会话是否在车机侧播放声音（2026-10-24 起默认恒 true，不再有开关）。 */
    @Volatile
    var audioEnabledForSession: Boolean = true

    /**
     * 「开启声音（兼容档）」：候选里把非 media 档（compatibility 等）排前面。
     * 2026-10-19 Honda HFT 实测——media 档只声明 44.1k，该车机音频通路只吃得下 48k，
     * 走默认档会「上线几秒后整车机拆链」。
     */
    @Volatile
    var audioPreferCompat: Boolean = false

    /** 本轮真正用上的音频档位（拆链归因与记忆回写用；null = 本次没有音频）。 */
    @Volatile
    private var activeAudioProfile: AudioProfile? = null

    /**
     * 当前音频流是否处在**通话场景**（`audioType=telephony` 档）。
     *
     * 通话与媒体在接收端是两条**不同的输出设备**：车机收到 telephony 档的流才会打开
     * 通话增益与麦克风通路。所以进/出通话都要换一次流，只调音量是没用的。
     */
    @Volatile
    private var audioInCallMode: Boolean = false

    /** 本次通话真正切上的通话档（null = 没切过 / 车机未声明 telephony）。 */
    @Volatile
    private var activeCallProfile: AudioProfile? = null

    /**
     * 已被拒过的通话档键：同一次通话里不重复撞同一档。
     * SETUP 往返最长 8 秒，撞两遍就白等 16 秒——而通话场景本来就只有几十秒。
     */
    @Volatile
    private var rejectedCallProfileKey: String? = null

    /** 音频档位记忆的键（车机身份文本，见 [AirPlayClient.receiverIdentity]）。 */
    @Volatile
    private var activeAudioReceiverKey: String = ""

    /** 音频通道真正开始推流的时刻（uptimeMillis）；0 = 未开始。 */
    @Volatile
    private var activeAudioStartedAt: Long = 0L

    /**
     * 音频「存活窗口」（ms）：超过它才认为该档位可用。
     * 依据 2026-10-19 实测：拆链发生在音频起流后 6.5s；取 20s 留足车机音频通路初始化
     * 与首个欠载周期的余量。
     */
    private const val AUDIO_PROFILE_PROBE_MS = 20_000L

    /**
     * RECORD 之后等 iAP2 控制会话就绪的上限（ms）。
     *
     * 4580（AUDI 3810 / DELPHI MIB3）的 HMI 要等 `required IAP2 information` 齐了
     * 才激活 CarPlay；在那之前收到的 RECORD 它不认，表现为「会话全绿、屏幕全黑」。
     * 正常车机走到 RECORD 时链路早已 NORMAL，这条分支根本不会进。
     */
    private const val MIB3_REACTIVATE_WAIT_MS = 2_500L

    /**
     * 音频 SETUP 之前等隧道控制会话（MFi）就绪的上限（ms）。
     *
     * 2026-11-06 实车：抢在 MFi 前发音频，车机 5097ms 才回、期间 MFi 20B/32B 挑战
     * 连续静默；对齐之后（同源项目在 CL39 上）音频 SETUP 往返只有 172ms。
     * 取 6s 覆盖正常的 16B/20B 挑战 + 一次重试；等不到就照发，不能因为音频拖死主链路。
     */
    private const val AUDIO_CONTROL_SESSION_WAIT_MS = 6_000L

    /** 车机没起 AirPlay 服务时「叫醒」的最大次数（每 10s 最多一次，见 AirPlayClient.onPortRefused）。 */
    private const val CARPLAY_WAKE_MAX_TIMES = 3

    /** 两次「叫醒」的最小间隔（ms）：比 AirPlay 端口重试节奏慢，避免把 iAP2 会话喂成噪声。 */
    private const val CARPLAY_WAKE_GAP_MS = 10_000L

    /** 连上后先等这么久再判「没有 CarLife 画面」（给缓存回放/引擎首帧一点时间，正常 <1s）。 */
    private const val NOTICE_FIRST_DELAY_MS = 1_200L

    /** 车机仍然没画面时提示帧的重推间隔（ms）：车机偶尔丢帧，切屏回来也要能看到。 */
    private const val NOTICE_REPEAT_MS = 5_000L

    /** 提示帧最多推这么多次（≈60s），之后交给首帧门与视频看门狗。 */
    private const val NOTICE_MAX_PUSHES = 12

    /** 首帧门判「刚推过提示」的窗口（ms）：窗口内不重复推，避免两套文案互相覆盖。 */
    private const val NOTICE_RECENT_MS = 8_000L

    /**
     * 屏幕接管门等首帧的上限（ms）。
     *
     * 首帧都没送到就交给 first-frame-gate（那里的分类更准），这里不重复报。
     */
    private const val HANDOVER_FIRST_FRAME_WAIT_MS = 20_000L

    /**
     * 首帧送达后给车机的观察窗（ms）：这段时间内车机若一条事件命令都没有，
     * 就基本可以判定它没把这条流当屏流解码。
     *
     * 12 秒是「正常车机早已回落 forceKeyFrame/changeModes」与「不拖太久才出日志」
     * 的折中；车机自己的 `badFramesThreshold=4`、~4 帧坏帧就会开口要 I 帧，20fps 下
     * 不到 1 秒。
     */
    private const val HANDOVER_QUIET_MS = 12_000L

    /**
     * 通话结束后多久重建音频流（ms）。
     * 车机从「通话」切回「媒体」自身有 1~3 秒的资源仲裁过渡，立刻重建会和它抢音频通路，
     * 取 2.5 秒让车机先把通话通路收干净。
     */
    private const val AUDIO_REBUILD_DELAY_MS = 2_500L

    /**
     * 解冻后重建会话的退避序列（ms）。
     *
     * 车机撤会话到重新开放 AirPlay 端口之间有一段空窗，紧贴着重连只会被
     * Connection refused 打回；3/6/12/24 秒四轮覆盖 45 秒，足够它把旧会话收干净。
     */
    private val recoveryBackoffMillis = longArrayOf(3_000L, 6_000L, 12_000L, 24_000L)

    /** 每次重建后给交接流程的观察窗：配对 + SETUP + RECORD 正常 3~6 秒出首帧。 */
    private const val RECOVERY_SETTLE_MILLIS = 8_000L

    /** 兼容模式常开监听的复检间隔：监听/占位记录掉了多久之内必须被发现。 */
    private const val BT_COMPAT_REVIVE_MILLIS = 30_000L

    /** 端口探测超时（本机回环 / 车机同网段，都不该慢）。 */
    private const val PROBE_TIMEOUT_MILLIS = 800L

    /**
     * 「邀请先到、端点未就绪」的有效期（ms）。
     *
     * 车机邀请与 0x4301/mDNS 谁先谁后没有保证；超过这个时间还没等到端点，
     * 就当那次邀请已过期，别让上一轮的旧信号误触发新一轮会话。
     */
    private const val PENDING_INVITE_TTL_MS = 30_000L

    /** 会话建立互斥（端点交接 → 配对 → 推流全程只允许一条）。 */
    val sessionStarting = AtomicBoolean(false)

    /**
     * 交接流程已在跑、但 AirPlayClient 尚未创建时收到的晚到端点（0x4301）。
     * 配对线程起步时取用一次后清空——此刻没有 client 可更新，丢了就白等到下一轮。
     */
    @Volatile
    private var pendingEndpointUpdate: Iap2SenderSession.CarPlayEndpoint? = null

    /** 用户取消连接：配对线程在关键检查点看到后中止（mDNS/配对等待无法中途打断，粒度秒级）。 */
    @Volatile
    var connectCancelled: Boolean = false

    /**
     * 空端点等待观察者的**代际**：每新起一个就自增，旧观察者看到代际变化即退出。
     *
     * 2026-10-26 别克 E2LB 日志：车机每 ~40 秒把链路掐掉一次（收到 0x4300 后停止 ACK，
     * 我方重传看门狗拆链），每次重连都新起一个观察者而旧的只认 connectCancelled——
     * 于是 5 轮之后同一时刻有 5 个观察者在跑，日志里同时出现「已等 30s / 61s / 122s /
     * 153s」，看起来像时间倒流。
     */
    private val pendingWatchGeneration = AtomicInteger(0)

    /**
     * 一次「启动连接」内的等待预算起点（[PENDING_START_SESSION_WAIT_MS] 从这次点击算起）。
     *
     * 不能每轮会话重置：车机反复断链重连时，旧实现每轮重新计 5 分钟，等于**永不超时**，
     * 用户看到的「最多等 5 分钟」是假的，连接会一直原地打转。
     */
    @Volatile
    private var pendingWaitBudgetStartedAt: Long = 0L

    /** 本周期的等待预算已耗尽：后续观察者不再重复拆链/刷日志，等用户重新点「启动连接」。 */
    @Volatile
    private var pendingWaitGaveUp: Boolean = false

    /**
     * 最后一次拿到完整热点信息的端点（SSID / 密码 / 地址）。
     *
     * 解冻恢复要靠它重走整轮交接：`onCarPlayEndpoint` 认的只有这条报文里的东西，
     * 而车机不会因为「手机刚从冻结里出来」再补发一遍 0x4301。
     */
    @Volatile
    private var lastGoodEndpoint: Iap2SenderSession.CarPlayEndpoint? = null

    /**
     * 「邀请先到、端点未就绪」的待决时间戳（uptimeMillis，0 = 无待决）。
     *
     * catplay `CarPlayCtrlServer::process_invite` 在 Bonjour 缓存里还找不到车机
     * `_airplay._tcp` 条目时**不丢弃邀请**，而是置 cache_miss，等条目晚到后自动
     * unglitch。我们的等价场景：车机邀请到达时 `lastGoodEndpoint` 还是 null
     * （当前 WiFi 也合成不出端点），旧实现直接 return 把信号丢了——此后
     * 0x4301/mDNS 晚到时不会再把它算作触发源，表现为「邀请明明来过却没连」。
     *
     * 端点就绪（[onCarPlayEndpoint]）与等邀请窗口（[awaitCtrlInvite]）里消费，
     * 超过 [PENDING_INVITE_TTL_MS] 自动作废。
     */
    @Volatile
    private var pendingInviteAtMs: Long = 0L

    /** 解冻恢复的互斥：一次只允许一条恢复线程，避免冻结抖动叠出好几轮并行重建。 */
    private val freezeRecovery = AtomicBoolean(false)

    /** 用户当前是否希望保持连接（「启动连接」置 true、「断开」置 false）——解冻恢复的前置条件。 */
    @Volatile
    private var sessionWanted: Boolean = false

    /**
     * 当前连接阶段的一句话提示（英雄卡副标题用，详细过程看日志）。
     * 由各阶段显式设置；null 时 UI 显示默认文案。
     */
    @Volatile
    var phaseHint: String? = null

    /** 取消「连接中」：停链路 + 置取消标志，卡在错误网络/等待回连时用户可主动脱身。 */
    fun cancelConnecting(context: Context) {
        connectCancelled = true
        appendLog("SESSION: 用户取消连接——正在停止蓝牙/WiFi 链路")
        disconnect(context)
        // 强制释放互斥：配对流程线程可能阻塞在不可中断的连接/mDNS 等待上（如手机
        // 不在热点网段时连热点 IP），finally 遥遥无期——用户显式取消即强制解锁，
        // 否则英雄卡永久「正在连接」（旧流程产出已被 retireSession 清理，无冲突）
        sessionStarting.set(false)
        phaseHint = "已取消连接"
    }

    /**
     * 发送端 BT 链路断开（保活泵退出）。配对进行中（未推流）时本轮已注定失败——
     * 立即释放「会话处理中」互斥并唤醒卡住的等待。
     *
     * 为什么必须做（上游 0.1.14 奥迪 3315 真机定谳，别克 E2LB 同症）：车机 15s 看门狗
     * 拆链后**立刻回连**并完整重走 Phase 1，新端点却被上一轮还卡在 WiFi 加入里的
     * 僵尸轮次挡住（「已有会话在处理中」），于是只剩 keep-alive 干等到用户放弃。
     *
     * 置 [connectCancelled] 让卡在 WiFi 加入等分片检查的配对线程在 ≤200ms 内退场；
     * **不停 BT 组件**（车机大概率立刻回连，监听必须留着）。新轮次起步处会清掉本标志。
     */
    fun onSenderLinkClosed() {
        if (videoSender?.isRunning == true) return
        if (sessionStarting.compareAndSet(true, false)) {
            connectCancelled = true
            appendLog(">>> 本轮蓝牙链路断开——释放「会话处理中」，等待车机重连端点接手")
            // 未推流就断链 = 这一轮没连上：报文快照落盘（成功过的轮次在 markLinked 时已丢弃）
            SessionTrace.sessionEnd("蓝牙链路断开，未进入推流")
        }
    }

    /**
     * 保活服务的心跳发现进程刚被系统冻结过（[frozenMillis] 毫秒）——自检并自动重建。
     *
     * ## 为什么冻结后必须主动收拾
     *
     * 冻结期间本进程一帧没送、一次 keepAlive 也没回，车机那边的判定只有一个：超时。
     * 实测（2026-10-30 奥迪 MIB3 日志）冻 133 秒后车机先关 AirPlay 控制通道、
     * 再撤 `_airplay._tcp`、最后断蓝牙 iAP2，一条不留。等它自己回来是等不到的——
     * 因为车机认为「这台手机已经走了」。
     *
     * 判据用 [sessionWanted]（用户意图）而不是 [connectCancelled]：链路一断
     * [onSenderLinkClosed] 就会把后者置 true，那正是冻结后的常态，用它等于永不恢复。
     *
     * 冻结不足 [FreezeWatch.RECOVER_MIN_FROZEN_MS] 的只落日志不动手（多半是主线程跑重活，
     * 会话没受伤，重建反而多拆一次链）。
     */
    fun onProcessResumed(frozenMillis: Long) {
        if (!sessionWanted) {
            appendLog("PROC: 解冻后用户已断开——不自动恢复")
            return
        }
        if (!freezeRecovery.compareAndSet(false, true)) {
            appendLog("PROC: 上一次解冻恢复还在进行——本次跳过")
            return
        }
        Bg.run("freeze-recovery") {
            try {
                recoverAfterFreeze(frozenMillis)
            } catch (error: Exception) {
                appendLog("PROC: 解冻恢复异常 - ${error.message ?: error.javaClass.simpleName}")
            } finally {
                freezeRecovery.set(false)
            }
        }
    }

    /**
     * 解冻后的恢复流程：先给积压的帧一点时间自己缓过来，仍无画面才按退避重建。
     *
     * 退避间隔逐轮翻倍（3/6/12/24 秒）：车机撤会话后要先关旧会话、再重新开放 AirPlay
     * 端口，这段时间里猛怼只会被 Connection refused 打回来，还多刷一堆失败日志。
     */
    private fun recoverAfterFreeze(frozenMillis: Long) {
        val seconds = frozenMillis / 1_000
        // 解冻瞬间大量积压回调正在涌出，先让渲染/推流线程把这口气喘完再判定
        sleepQuietly(1_500)
        if (isStreaming()) {
            appendLog("PROC: 解冻 ${seconds}s 后画面仍在推（CarLife 直通没断）——无需重建")
            return
        }
        val endpoint = lastGoodEndpoint
        val carLifeAlive = probeLocalPort(CarLifeProtocol.PORT_CMD)
        val airPlayAlive = endpoint != null && probeEndpoint(endpoint)
        appendLog(
            "PROC: 解冻 ${seconds}s 自检——CarLife:${CarLifeProtocol.PORT_CMD}=" +
                (if (carLifeAlive) "在监听" else "已不在（引擎被一起冻掉/退出了）") +
                "，车机 AirPlay=" +
                when {
                    endpoint == null -> "没有端点记录（本轮还没走到热点这一步）"
                    airPlayAlive -> "端口仍可达"
                    else -> "不可达（多半已被车机撤掉会话）"
                } +
                "，推流=" + (if (isStreaming()) "在" else "已断"),
        )
        if (endpoint == null) {
            // 还没有热点凭据：重连只能靠车机重新下发 0x4301，先把蓝牙监听拉起来等
            appendLog("PROC: 无端点可重走——重新拉起蓝牙链路，等车机回连")
            restartBluetoothForRecovery()
            return
        }
        recoveryBackoffMillis.forEachIndexed { index, delay ->
            if (connectCancelled || isStreaming()) return
            sleepQuietly(delay)
            if (connectCancelled || isStreaming()) return
            appendLog(
                "PROC: 重建会话 第 ${index + 1}/${recoveryBackoffMillis.size} 次" +
                    "（退避 ${delay}ms · 端点 ${endpoint.ssid}）",
            )
            // 上一轮可能卡在「会话处理中」：冻结后它已不可能再推进，强释放让本轮进场。
            // 不清的话 onCarPlayEndpoint 会直接以「已有会话在处理中」把恢复请求丢掉。
            if (sessionStarting.get()) {
                appendLog("PROC: 上一轮仍占着「会话处理中」——强制释放后重走交接")
                sessionStarting.set(false)
            }
            restartBluetoothForRecovery()
            onCarPlayEndpoint(endpoint, "解冻后自动恢复 #${index + 1}")
            // 给这一轮交接起步的时间：配对 + SETUP + RECORD 正常在 3~6 秒内出首帧
            var waited = 0L
            while (waited < RECOVERY_SETTLE_MILLIS) {
                sleepQuietly(500)
                waited += 500
                if (connectCancelled || isStreaming()) break
            }
            if (isStreaming()) {
                appendLog("PROC: ✓ 会话已自动恢复（第 ${index + 1} 次尝试）")
                return
            }
        }
        if (!connectCancelled && !isStreaming()) {
            appendLog(
                "PROC: 自动恢复未成功（${recoveryBackoffMillis.size} 次退避都没起来）——" +
                    "请手动点「启动连接」；若反复如此，把日志发出来",
            )
        }
    }

    /** 恢复前把蓝牙组件拉起来（已运行则空操作）。真车/测试台两条路径都拉，判断交给组件自身。 */
    private fun restartBluetoothForRecovery() {
        if (bluetoothClient?.isRunning == true && bluetoothServer?.isRunning == true) return
        runCatching { bluetoothClient?.start() }
        runCatching { bluetoothServer?.start() }
        appendLog("PROC: 蓝牙链路已重新拉起（等车机重新下发 0x4301）")
    }

    /**
     * 「车机只认苹果设备」兼容模式（[BtCompatPrefs]，默认关）：挂机监听 + 占位 SDP 记录。
     *
     * 场景（2026-11-07 宝马 iD6）：车机在蓝牙设备列表里直接给本机打
     * `设备不支持 Apple CarPlay`。这句判定发生在 iAP2 之前，改 0x4300 之类全都无效——
     * 它看的是发现层（EIR 有没有 iPhone 的 CarPlay UUID、SDP 里有没有对应服务）。
     * Android 应用层写不了 EIR，能做的只有多注册两条占位 SDP 记录，
     * 并**在 App 一启动就把服务发布出去**（按「先配对、后开 App」的顺序操作时，
     * 车机那一瞥看不到任何服务，很容易把设备记成「不支持」）。
     *
     * 关掉只撤占位记录，不动已经在跑的控制链路——这一刀的语义是「别再声明苹果身份」，
     * 不是「断开连接」。
     *
     * [fromUser] 区分「用户刚拨了开关」和「Activity 重建时按偏好补齐」：前者要解除
     * 手动断开留下的抑制，后者不能——否则用户点过「断开」，回到界面又被挂上监听。
     */
    fun applyBtCompat(
        enabled: Boolean,
        fromUser: Boolean = false,
    ) {
        val server = bluetoothServer ?: return
        if (!enabled) {
            idleListening = false
            server.stopProbe()
            return
        }
        if (fromUser) compatSuppressed = false
        if (compatSuppressed) return
        if (!server.hasPermission()) {
            // 首次启动还没授权：不刷屏，等授权后由看门狗在 30 秒内自己挂上
            return
        }
        Bg.run("bt-compat-listen") {
            if (isSessionRunning() || sessionStarting.get()) {
                // 正在连/已在跑：start() 幂等，顺带把占位记录同步到当前开关状态
                server.start()
                return@run
            }
            idleListening = true
            if (server.start()) {
                appendLog("BT: 兼容模式——蓝牙监听常开（iAP2 + CarPlay 占位 SDP）")
            } else {
                idleListening = false
            }
        }
    }

    /** 兼容模式看门狗：常开的监听死了（accept 异常退出、蓝牙被关又打开）就自己拉回来。 */
    private fun startBtCompatWatchdog() {
        if (btCompatWatchdogStarted) return
        btCompatWatchdogStarted = true
        Thread({
            while (true) {
                try {
                    Thread.sleep(BT_COMPAT_REVIVE_MILLIS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val ctx = appContext ?: continue
                if (!BtCompatPrefs.enabled(ctx) || compatSuppressed) continue
                // 连接流程/推流中由它们自己维护监听，这里不插手
                if (sessionStarting.get() || isStreaming()) continue
                val server = bluetoothServer ?: continue
                if (server.isRunning && server.isProbeRunning) continue
                if (!server.hasPermission()) continue
                if (server.isRunning) {
                    // 监听在但占位没了（极少见：注册被系统拒过）：只补挂记录
                    server.start()
                    continue
                }
                idleListening = true
                if (server.start()) {
                    appendLog("BT: 兼容模式——常开监听已自动恢复")
                } else {
                    idleListening = false
                }
            }
        }, "bt-compat-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 车机麦克风上行探针（**诊断**，只在本轮测试包里开）。
     *
     * 目的只有一个：搞清「车机到底会不会把麦克风音频推向手机」。`/info` 里四路音频档都
     * 声明了输入位图，但那只是**声明**，从没实建过 `input=true` 的流（旧
     * `microphoneCandidates()` 只打日志）。这一步不成立，后面「解密 → PCM → 灌进 CarLife
     * 的 9242 VR 通道」全是白工。
     *
     * 组合按「车机声明里最像语音的三档」排，第一组命中就停（不浪费车机的资源仲裁）。
     * 监听窗口 6 秒 × 最多 4 组，全程只跑一次、在后台线程，不影响推流。
     */
    private fun runMicProbe(client: AirPlayClient) {
        Thread(
            {
                runCatching { Thread.sleep(MIC_PROBE_DELAY_MS) }
                appendLog("MIC: 开始上行探针（只数数据报，不解密不识别）")
                var hit = false
                for (combo in MIC_PROBE_COMBOS) {
                    val received =
                        try {
                            client.probeMicrophoneInput(
                                audioType = combo.audioType,
                                formatBit = combo.formatBit,
                                sampleRate = combo.sampleRate,
                                listenMillis = MIC_PROBE_LISTEN_MS,
                            )
                        } catch (error: Exception) {
                            appendLog("MIC: 探针异常（${combo.label}）- ${error.message}")
                            0
                        }
                    when {
                        received > 0 -> {
                            appendLog(
                                "MIC: ✓ ${combo.label} 收到 $received 个数据报" +
                                    "——车机在推麦克风，此路可走",
                            )
                            hit = true
                        }

                        received < 0 -> {
                            appendLog("MIC: ${combo.label} 被车机拒绝（SETUP 非 200）→ 不再试其它组合")
                            break
                        }

                        else -> appendLog("MIC: ${combo.label} ${MIC_PROBE_LISTEN_MS}ms 内无数据报")
                    }
                    if (hit) break
                }
                appendLog(
                    if (hit) {
                        "MIC: 结论——车机愿意把麦克风推给手机；下一步做「解密 + PCM + 9242 注入」"
                    } else {
                        "MIC: 结论——本轮所有组合都没数据报（车机不接受输入流 / 或只在语音激活时才推）"
                    },
                )
            },
            "mic-probe",
        ).apply { isDaemon = true }.start()
    }

    /** 一组麦克风上行探针参数（audioType + 车机声明的输入格式位 + 采样率）。 */
    private data class MicProbeCombo(
        val audioType: String,
        val formatBit: Long,
        val sampleRate: Int,
    ) {
        val label: String
            get() = "$audioType/0x${formatBit.toString(16)}/${sampleRate}Hz"
    }

    /** 音频配置守卫是否在跑（一轮 connect 只拉一条线程）。 */
    private val audioGuardRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 音频配置守卫：会话期间**持续**断开目标车机的 HFP/A2DP（2026-11-08 按宝马 fork 移植）。
     *
     * 实车依据（fork 在 BMW 94841 / G38 NBT EVO 上验证）：车机在蓝牙配置文件
     * 在线时不开放 iAP2 窗口；我们一次性断开后，车机那一侧的蓝牙栈会自己把
     * HFP/A2DP 再拉起来，于是又回到「连得上但车机不理」的状态。表现为握手期
     * 2~4 分钟的连/断循环——守卫在工作，但用户看着像「蓝牙坏了」，所以日志里
     * 每次重断都写一行，便于事后区分「守卫在干活」与「真的在抖」。
     *
     * 退出条件（任一命中）：用户断开、iAP2 会话建立（[BtSessionGate.sessionActive]）、
     * 已经开始推流、或盯满 [AudioProfileGuardPolicy.MAX_MILLIS]。
     */
    private fun startAudioProfileGuard() {
        if (!audioGuardRunning.compareAndSet(false, true)) return
        Thread({
            val ctx = appContext
            try {
                if (ctx == null) return@Thread
                val appCtx = ctx.applicationContext
                val drops =
                    BtProfiles.guardAudioProfiles(
                        appCtx,
                        addressProvider = {
                            BtSessionGate.activePeerAddress
                                ?: TargetDevicePrefs.get(appCtx)?.address
                        },
                        shouldStop = {
                            !sessionWanted ||
                                BtSessionGate.iap2Ready ||
                                BtSessionGate.sessionActive ||
                                isStreaming()
                        },
                        onEvent = { line -> appendLog(line) },
                    )
                appendLog("BT-GUARD: 守卫收工（共断开 $drops 次）")
            } catch (error: Throwable) {
                appendLog("BT-GUARD: 守卫异常 - ${error.message ?: error.javaClass.simpleName}")
            } finally {
                audioGuardRunning.set(false)
            }
        }, "bt-audio-guard").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 一轮建链失败后的自动重试（3s / 10s / 30s 三档）。
     *
     * 旧实现失败即收工：2026-11-02 日志里 18:01:17 失败后到 18:12:47 整整 11 分钟
     * 零动作，而车机在这期间发了 5 次 ctrl 邀请。车机侧「半握手」状态（SETUP 回 200
     * 却立刻 FIN、事件口 ECONNREFUSED）多半只是上一轮被硬断后没清干净——它自己会好，
     * 我们不去试就永远连不上。
     *
     * 三次用完就停：还在失败说明是车机侧真的坏了（需要重启车机侧 CarPlay），
     * 继续怼只会刷屏并把 CarLife 引擎反复踢下线。
     */
    private fun scheduleConnectRetry(reason: String) {
        if (connectCancelled || !sessionWanted || isStreaming()) return
        val endpoint = lastGoodEndpoint ?: return
        var delay = 0L
        var attempt = 0
        var generation = 0
        synchronized(connectRetryLock) {
            if (connectRetryScheduled) {
                appendLog("RETRY: 已有一轮重试在排队——本次失败不再叠加")
                return
            }
            if (connectRetryAttempt >= CONNECT_RETRY_BACKOFF_MS.size) {
                appendLog(
                    "RETRY: 已连续失败 ${connectRetryAttempt} 轮，停止自动重试——" +
                        "请在车机上把 CarPlay 关掉再打开（或熄火重上电）后，手动点「启动连接」",
                )
                return
            }
            attempt = connectRetryAttempt
            delay = CONNECT_RETRY_BACKOFF_MS[attempt]
            connectRetryScheduled = true
            // 令牌必须与「占用排队位」在同一把锁里取：分两步读时，中间若有用户点击/
            // 车机邀请把令牌 +1，这一次退避就会把**新一轮**的令牌当成自己的，
            // 醒来后判定「没过期」并再起一轮 —— 正是要避免的两轮并行交接。
            generation = connectRetryGeneration
        }
        Bg.run("connect-retry") {
            try {
                appendLog(
                    "RETRY: $reason——${delay / 1_000}s 后自动重试" +
                        "（第 ${attempt + 1}/${CONNECT_RETRY_BACKOFF_MS.size} 次）",
                )
                sleepQuietly(delay)
                val stale =
                    synchronized(connectRetryLock) {
                        connectRetryGeneration != generation
                    }
                if (stale) {
                    appendLog("RETRY: 等待期间已有新一轮接手（车机邀请/用户操作）——本次重试作废")
                    return@run
                }
                if (connectCancelled || !sessionWanted || isStreaming() || sessionStarting.get()) {
                    appendLog("RETRY: 条件已变（取消/正在连/已出画面）——跳过本次重试")
                    return@run
                }
                synchronized(connectRetryLock) { connectRetryAttempt = attempt + 1 }
                appendLog("RETRY: 发起第 ${attempt + 1} 次自动重试（端点 ${endpoint.ssid}）")
                onCarPlayEndpoint(endpoint, "建链失败自动重试 #${attempt + 1}")
            } finally {
                synchronized(connectRetryLock) { connectRetryScheduled = false }
            }
        }
    }

    /**
     * 车机 ctrl 邀请到达（车机已发现手机、AirPlay 窗口已开）。
     *
     * 空闲态收到邀请 = 对方正在等我们连，比任何退避计时器都权威：立刻把挂着的重试
     * 作废、马上起一轮。
     *
     * **推流态**以前是直接忽略（"让在跑的那轮自己走完"）。2026-11-03 ID7 日志证明这个假设
     * 不成立：它发邀请的语义是「我要重建会话」，我们不理，它就在 18~20 秒后把正在推流的
     * 那条会话 reset 掉——15 次断流每一次前面都有一条邀请。现在交给
     * [onCtrlInviteWhileStreaming] 判断（见那里的两条规则）。
     */
    private fun onCtrlInvite(receiverId: String?) {
        if (!sessionWanted || connectCancelled) return
        if (isStreaming()) {
            onCtrlInviteWhileStreaming(receiverId)
            return
        }
        val endpoint = lastGoodEndpoint
        if (endpoint == null) {
            pendingInviteAtMs = android.os.SystemClock.uptimeMillis()
            appendLog(
                "CTRL: 车机邀请到达（receiver=${receiverId ?: "?"}）——但还没有热点端点；" +
                    "已挂起待决邀请（catplay cache_miss 自愈），端点晚到时直接接续，不再空等",
            )
            return
        }
        if (sessionStarting.get()) {
            appendLog("CTRL: 车机邀请到达——本轮已有会话在处理中，交给它（不并行再起一轮）")
            return
        }
        val exhausted =
            synchronized(connectRetryLock) {
                if (connectRetryAttempt >= CONNECT_RETRY_BACKOFF_MS.size) {
                    true
                } else {
                    // 邀请打断退避：令牌 +1，挂在睡眠里的重试线程醒来就作废
                    connectRetryGeneration++
                    connectRetryScheduled = false
                    false
                }
            }
        if (exhausted) {
            appendLog(
                "CTRL: 车机仍在邀请（本轮共 ${ctrlAdvertiser?.inviteCount ?: 0} 次），" +
                    "但已连续失败 ${CONNECT_RETRY_BACKOFF_MS.size} 轮——不再自动重连；" +
                    "请重启车机侧 CarPlay 后手动点「启动连接」",
            )
            return
        }
        appendLog("CTRL: 车机邀请到达（receiver=${receiverId ?: "?"}）且当前空闲——立即发起一轮连接")
        Bg.run("ctrl-invite-connect") {
            if (connectCancelled || !sessionWanted || isStreaming()) return@run
            onCarPlayEndpoint(endpoint, "车机 ctrl 邀请触发")
        }
    }

    /**
     * 推流中收到车机 ctrl 邀请：先记下来，再按两条规则决定要不要主动重建。
     *
     * 为什么不能继续静默忽略（2026-11-03 ID7 实测，15 次断流全部有邀请在前）：
     * 车机发邀请的语义是「**我要重建这条会话**」——它认为当前会话不完整（那台车机
     * 是因为音频 SETUP 三档全 400）。我们不配合，它就等 18~20 秒后自己 reset 掉
     * 正在推流的那条会话，表现为黑屏；然后我们重连、推流、再被邀请、再被拆，死循环。
     *
     * 两条规则：
     *  1. **任何一条推流中的邀请都要落日志**（带次数、距上次间隔）。以前完全静默，
     *     排障时无法把"黑屏"与"车机来过邀请"关联起来——这是这次排查最费时的坑。
     *  2. **主动重建**，但加 [STREAMING_INVITE_REBUILD_GAP_MS] 防抖：重建要 3~4 秒
     *     （退役 + 重新配对 + SETUP），比等车机 18 秒后拆会话划算得多；防抖只防
     *     "车机抖动时两侧反复拆"这种极端情况。
     *
     * 不动的情况：没端点（还没走完热点那步）、正在建链（交给那一轮）、防抖窗口内。
     */
    private fun onCtrlInviteWhileStreaming(receiverId: String?) {
        val now = android.os.SystemClock.uptimeMillis()
        val seen = ++streamingInviteCount
        val sincePrev = if (lastStreamingInviteAt == 0L) -1L else now - lastStreamingInviteAt
        lastStreamingInviteAt = now
        appendLog(
            "CTRL: 推流中收到车机邀请（receiver=${receiverId ?: "?"}，本流第 $seen 次" +
                (if (sincePrev >= 0) "，距上次 ${sincePrev}ms" else "，本次推流首次") +
                "）——车机语义是「要重建会话」，不再静默丢弃",
        )
        if (connectCancelled || !sessionWanted) return
        val endpoint = lastGoodEndpoint
        if (endpoint == null) {
            appendLog("CTRL: 推流中邀请——但还没有热点端点可用，只能继续推流并等车机下发 0x4301")
            return
        }
        if (sessionStarting.get()) {
            appendLog("CTRL: 推流中邀请——本轮已有会话在交接，交给它处理")
            return
        }
        val sinceRebuild =
            if (lastInviteRebuildAt == 0L) Long.MAX_VALUE else now - lastInviteRebuildAt
        if (sinceRebuild < STREAMING_INVITE_REBUILD_GAP_MS) {
            appendLog(
                "CTRL: 距上次因邀请重建 ${sinceRebuild}ms（防抖 ${STREAMING_INVITE_REBUILD_GAP_MS}ms）" +
                    "——先不动，避免两侧反复拆会话",
            )
            return
        }
        lastInviteRebuildAt = now
        appendLog("CTRL: 主动退役当前会话并按车机要求重建（预计 3~4 秒无画面，比被它 18 秒后拆掉划算）")
        Bg.run("ctrl-invite-rebuild") {
            if (connectCancelled || !sessionWanted) return@run
            // 先退役：onCarPlayEndpoint 开头有「推流中忽略端点」的守卫，不退役就进不去。
            runCatching { retireSession() }
                .onFailure { appendLog("CTRL: 退役旧会话失败（${it.message}）——仍尝试重连") }
            sessionStarting.set(false)
            connectCancelled = false
            onCarPlayEndpoint(endpoint, "推流中车机邀请要求重建")
        }
    }

    /** 本机端口是否在监听（CarLife 引擎 7240）。600ms 足够，本机回环不会慢。 */
    private fun probeLocalPort(port: Int): Boolean =
        try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MILLIS.toInt())
                true
            }
        } catch (_: Exception) {
            false
        }

    /** 车机 AirPlay 端口是否还能连（默认 7000）。只用来定性，连不上也照样尝试重建。 */
    private fun probeEndpoint(endpoint: Iap2SenderSession.CarPlayEndpoint): Boolean {
        val port = if (endpoint.airPlayPort > 0) endpoint.airPlayPort else 7000
        val host =
            endpoint.ipAddresses.firstOrNull { EndpointHosts.isIpv4(it) }
                ?: endpoint.ipAddresses.firstOrNull()
                ?: return false
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), PROBE_TIMEOUT_MILLIS.toInt())
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 最近一次 0x5703 的热点密码（仅内存，不落盘）。
     * 「车机热点 → 去连接」按钮复制到剪贴板用（手动加入兜底场景）。
     */
    @Volatile
    var lastWifiPsk: String? = null

    /**
     * 最近一次 0x5703 的**完整**热点凭据：应用专属网络被系统被动断开时用它原地重绑。
     *
     * 只存密码不够——重绑要重新走 `WifiNetworkSpecifier`，需要 SSID/安全类型/BSSID。
     * 与 [lastWifiPsk] 一样只在内存里，不落盘。
     */
    @Volatile
    private var lastCarWifi: Iap2SenderSession.WifiConfiguration? = null

    /**
     * 本轮会话中 wlan0 是否丢过（被动断开或我们自己的 release）。
     *
     * 存在的理由：网络一断，走它的 socket 已经随网络销毁，**本轮会话不会自愈**。
     * 重绑只能把网络拉回来（让 CarLife 引擎与下一轮建链能用），不能救当前这一轮；
     * 建链层据此把日志写清楚，而不是让用户对着「已连接」却黑屏的界面猜。
     */
    @Volatile
    var wifiLinkLost: Boolean = false
        private set

    /** 自动重绑闸门：ROM 抖动会连发多次 onLost，不设间隔会形成重绑风暴。 */
    private val wifiRebindLock = Any()
    private var lastWifiRebindAt = 0L

    /** wlan0 状态心跳：断链后靠它回答「那一刻 WiFi 到底动没动」。 */
    private var wifiWatchdog: WifiLinkWatchdog? = null

    /** 挂屏 UI 钩子：SSID 变化时通知当前 Activity 回填输入框（可空）。 */
    @Volatile
    var ssidSink: ((String) -> Unit)? = null

    /** 挂屏 UI 钩子：重要连接提示（WiFi 未开启/热点加入限制等）弹给用户看。 */
    @Volatile
    var uiHintSink: ((String) -> Unit)? = null

    /** 弹窗规格（加入热点确认等两按钮询问）。 */
    data class DialogSpec(
        val title: String,
        val message: String,
        val positive: String,
        val negative: String,
    )

    /**
     * 挂屏 UI 钩子：两按钮确认弹窗。
     * onChoice：true=positive；false=negative；null=无人应答/被关闭（调用方兜底）。
     */
    @Volatile
    var dialogSink: ((DialogSpec, onChoice: (Boolean?) -> Unit) -> Unit)? = null

    private fun ensureSessionObjects(context: Context) {
        if (bluetoothServer != null) return
        synchronized(this) {
            if (bluetoothServer != null) return
            wifiConnector =
                WifiCarPlayConnector(context) { line -> appendLog(line) }.also { connector ->
                    // 应用专属网络被系统断开（车机熄火/离开范围/ROM 踢关联）：
                    // 除了告知用户，还要**原地重绑一次**——网络没了，走它的 socket 已随
                    // 网络销毁，不重绑的话连 CarLife 引擎都会一直断着（实测重绑后 8 秒
                    // 内引擎会掉线一次，重绑回来它才自己恢复）。
                    connector.onNetworkLost = { ssid -> onCarWifiLost(ssid) }
                }
            wifiWatchdog =
                WifiLinkWatchdog(
                    context = context,
                    log = { line -> appendLog(line) },
                    carNetwork = { wifiConnector?.currentNetwork },
                    boundSsid = { boundCarSsid },
                )
            val wifiCredentialHook: (Iap2SenderSession.WifiConfiguration) -> Unit =
                { wifi -> onWifiCredentials(wifi) }
            // 目标车机动态读取：选择后下一轮连接尝试/下一次连入即生效，无需重启链路
            val targetProvider: () -> TargetDevicePrefs.Target? = {
                appContext?.let { TargetDevicePrefs.get(it) }
            }
            // 0x5001 NowPlayingUpdate 数据源（蓝牙链路）：优先系统媒体会话（第三方播放器），
            // 兜底 CarLife 内置播放器 0x10035。部分接收端（WinPlay/DiPlay 测试台）在蓝牙
            // bring-up 后即发 0x5000 订阅，歌名必须在这条蓝牙链路上应答；读不到（null）时
            // 静默不回应。
            val nowPlayingProvider: () -> CarPlayNowPlaying? = { buildNowPlaying() }
            bluetoothServer =
                Iap2BluetoothServer(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "车机回连") },
                    onWifiCredentials = wifiCredentialHook,
                    onEndpointUpdate = { endpoint -> onLateEndpoint(endpoint, "车机回连") },
                    targetProvider = targetProvider,
                    nowPlayingProvider = nowPlayingProvider,
                    onLinkClosed = ::onSenderLinkClosed,
                    onPeerConnected = {
                        // 挂着等 ≠ 有会话：真有车机连进来，立刻恢复成「会话进行中」
                        if (idleListening) {
                            idleListening = false
                            appendLog("BT: 兼容模式的挂机监听已转为正式会话")
                        }
                    },
                )
            bluetoothClient =
                Iap2BluetoothClient(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "主动连车机") },
                    onWifiCredentials = wifiCredentialHook,
                    onEndpointUpdate = { endpoint -> onLateEndpoint(endpoint, "主动连车机") },
                    targetProvider = targetProvider,
                    nowPlayingProvider = nowPlayingProvider,
                    onLinkClosed = ::onSenderLinkClosed,
                )
            ctrlAdvertiser =
                CarPlayCtrlAdvertiser(context) { line -> appendLog(line) }.also { advertiser ->
                    // 空闲态收到邀请 = 车机正在等我们连（会话失败后唯一的恢复入口）。
                    // 回调本身只入队，真正的建链在后台线程做——它会占用应答窗口。
                    advertiser.onInvite = { receiverId -> onCtrlInvite(receiverId) }
                }
            endpointBrowser =
                CarPlayServiceBrowser(
                    context,
                    log = { line -> appendLog(line) },
                    accept = { info ->
                        // 手机双归属（连车机热点 + 自己开热点共享给电脑）时 mDNS 会跨网
                        // 发现电脑的 AirPlay 接收器（macOS 自带）——2026-10-11 实测把
                        // MacBook 当成车机连了 120s。非本网段一律不算车机。
                        // 地址判定统一走 EndpointHosts：IPv4 按 /24 前缀，链路本地 IPv6
                        // 天然只在本链路，全局 IPv6 按 /64 比对。旧实现只认 IPv4，会把
                        // 只广播 fe80:: 的奥迪 4xxx 直接丢掉（2026-10-19）。
                        val ip = info.host?.hostAddress ?: return@CarPlayServiceBrowser false
                        EndpointHosts.isSameNetwork(
                            ip,
                            wifiConnector?.localIpv4Address(),
                            wifiConnector?.localIpv6Addresses().orEmpty(),
                        )
                    },
                )
        }
    }

    /** 状态行文本（Activity 渲染 statusText 用）。 */
    fun statusLine(): String =
        "wifi=${wifiConnector?.currentNetwork != null} " +
            "bt=${bluetoothServer?.isRunning == true} ctrl=${(ctrlAdvertiser?.port ?: -1) > 0}"

    /** 会话是否活跃（推出流进程或 BT 任一在跑）。 */
    fun isSessionRunning(): Boolean =
        videoSender?.isRunning == true ||
            // 「兼容模式」下监听常开：只是挂着等服务 ≠ 有会话。算进去会让自动连接
            // 被自己挡掉、通知栏写成「蓝牙已连上车机」、靠近触发也判成已在跑。
            (bluetoothServer?.isRunning == true && !idleListening) ||
            bluetoothClient?.isRunning == true

    /**
     * 是否**真的在推流**（画面已经在车机上跑）。
     *
     * 与 [isSessionRunning] 的区别很关键：后者在「点过启动连接、蓝牙服务正监听等
     * 车机回连」时也是 true，可能持续几分钟；而这里只认「画面真的在送」。
     * 车机远离后的收摊判定用这个——否则永远判为「会话还在跑」，收摊永不生效。
     */
    fun isStreaming(): Boolean = videoSender?.isRunning == true

    /**
     * 通知栏的一句话状态（[io.github.shaonialife.carptocar.service.CarPlayService] 每 3 秒取一次）。
     *
     * 后台自动连接时用户面前没有界面，通知栏是唯一可见的界面——那里若只写
     * 「运行中」等于什么都没说。这里把当前阶段摊开：已出画面 / 正在连 / 连上了但
     * 还在等车机下发信息 / 空转，用户一眼能判断「要不要点断开」。
     */
    fun notificationSummary(): String {
        val hint = phaseHint
        return when {
            videoSender?.isRunning == true -> "已连上车机，正在镜像手机画面（点「断开」可停止）"
            sessionStarting.get() -> hint ?: "正在连接车机…"
            isSessionRunning() -> hint ?: "蓝牙已连上车机，等待它下发连接信息…"
            else -> hint ?: "空闲——等待车机靠近，或点「打开界面」查看"
        }
    }

    /**
     * 「启动连接」主体（权限检查由 Activity 完成后调用）：前台保活服务 + 双向蓝牙。
     * 幂等：已有会话时 BT 组件的 start() 自行短路，不会双开。
     */
    fun startConnection(context: Context) {
        connectCancelled = false
        // 新一轮 = 新的一致性审计：清空上一轮的问题清单，并把输出口接到本控制器的
        // appendLog（logcat + 文件 + 界面三路都走它，用户导出日志即带走整份清单）。
        ProtocolAudit.reset()
        ProtocolAudit.sink = ::appendLog
        // 车机声明快照同样按轮清空：上一台车的「可收清单」绝不能拿来裁这一台
        // （清单是 MIB3 突发两条专用消息的裁剪判据，串轮次会误裁）。
        AccessoryDeclaration.reset()
        auditSummarizedCount = -1
        // RTSP 全量报文落盘（files/rtsp-YYYYMMDD.log）
        io.github.shaonialife.carptocar.util.RtspDump.init(context.filesDir)
        // 用户明确要连：解冻恢复的前提之一（断开后再解冻不该自己爬起来）
        sessionWanted = true
        // 用户主动开链 = 解除「别再挂机」的抑制；这一轮由连接流程自己拉监听，
        // 不算挂机态（否则 isSessionRunning 会漏判）
        compatSuppressed = false
        idleListening = false
        // 新一次点击 = 新的重试预算（上一轮用完的三次不该算到这一轮头上）；
        // 令牌 +1 让还在退避睡眠里的旧线程作废，避免与手动这一轮并行交接
        synchronized(connectRetryLock) {
            connectRetryAttempt = 0
            connectRetryGeneration++
            connectRetryScheduled = false
        }
        // 新一轮 = 新的链路状态：上一轮丢过的 wlan0 不影响这一轮的判定
        wifiLinkLost = false
        wifiWatchdog?.start()
        // 报文录制分界：一次点击 = 一段独立对话，事后按这行切分
        SessionTrace.sessionStart("用户点击启动连接")
        // 新一次用户点击 = 新的 5 分钟等待预算（断链重连不再重置，见 pendingWaitBudgetStartedAt）
        pendingWaitBudgetStartedAt = 0L
        pendingWaitGaveUp = false
        phaseHint = "蓝牙就绪，等待车机回连…"
        // 新会话 = 新的 NowPlaying 上下文：清去重键，否则同一首歌重连后不会被重推
        resetNowPlayingDedupe()
        CarPlayService
            .start(context)
        // 能力发布先于连接（catplay 的做法：CarPlayCtrlServer 在会话一开始就常驻广播
        // `_carplay-ctrl._tcp`，之后每 1s force_announce，收到车机邀请才建 AirPlay）。
        // 我们此前要等 0x5703 才注册，导致「车机发现手机」这一步总落在热点绑定之后，
        // 每轮多花 1~3 秒；现在启动连接即发布，换网时 ensureForNetwork 会自动重注册。
        ctrlAdvertiser?.resetInviteCount()
        // 后台发布：start() 首次要读真实蓝牙 MAC（可能走 shell），不能在主线程做
        Bg.run("ctrl-publish") { ctrlAdvertiser?.start() }
        // 真车路径：手机主动连车机（SDP …caff 配件服务）
        // 测试台路径：监听手机侧 iPhone UUID，等 DiPlay/xcertplay 回连
        bluetoothClient?.start()
        bluetoothServer?.start()
        // 诊断（奥迪实测：手机与车机「蓝牙已连接」时车机不开放 CarPlay）：
        // 连之前把目标设备的蓝牙配置文件状态打出来，失败时一眼看出是不是这个原因。
        Bg.run("bt-profile-diag") {
            val target = appContext?.let { TargetDevicePrefs.get(it) } ?: return@run
            val connected = BtProfiles.connectedNames(context.applicationContext, target.address)
            appendLog(
                "BT-PROFILE: 连接前「${target.name}」的蓝牙配置文件状态=" +
                    (connected.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "未连接"),
            )
        }
        // 音频配置守卫（2026-11-08，按宝马 fork 移植）：车机只要看到 HFP/A2DP 在线就
        // 不开放 iAP2 窗口，而我们断开之后它自己又会把配置文件拉回来——一次性断不够，
        // 得在整段建链期反复复查。iAP2 会话一并建立（或用户断开）就收工。
        startAudioProfileGuard()
    }

    /** 「断开连接」（可在任意 Activity 实例上调用——操作的是真管线对象）。 */
    fun disconnect(context: Context) {
        val hadProjection = CarPlayService.mediaProjection != null
        // 用户终态：解冻恢复不得再自动把会话拉起来
        sessionWanted = false
        // 兼容模式的常开监听也一并停在这里；记下「用户叫停过」，
        // 否则看门狗 30 秒后又把监听挂回来（用户会以为断开没生效）
        compatSuppressed = true
        idleListening = false
        // 记下这一刀的时刻：车机固件 `mediaDisconnectTimeout=15000ms`，设备没被判
        // 「已移除」之前它只重开旧会话、不启动 AirPlay。出站循环据此强制冷却
        // （见 BtSessionGate.REACTIVATE_COOLDOWN_MILLIS）——否则用户断开后立刻
        // 再点「启动连接」，就会稳稳落在「给热点凭据但不开 7000 端口」的那条路上。
        BtSessionGate.noteManualStop()
        // 用户终态同样作废排队中的自动重试——它醒来第一件事就是检查 sessionWanted，
        // 但先让令牌变一次，日志就能明确写出「用户已断开，本次重试作废」
        synchronized(connectRetryLock) {
            connectRetryGeneration++
            connectRetryScheduled = false
            connectRetryAttempt = 0
        }
        SessionTrace.sessionEnd("用户手动断开")
        pendingEndpointUpdate = null
        retireSession()
        // BT 控制会话只在这里（用户终态）退役，socket close 让保活读循环立刻退出：
        // 否则它阻塞到对端超时（实测 ~24s）才结束，用户取消后英雄卡持续转圈。
        // 配对中绝不能断——DiPlay 在隧道 iAP2 就绪前看到 RFCOMM 断开即判
        // bring-up failed 并拆整轮无线栈（含推流中的 AirPlay 会话，2026-10-16 实测）。
        detachActiveBtSessions()
        phaseHint = null
        CarPlayService
            .stop(context)
        bluetoothServer?.stop()
        bluetoothClient?.stop()
        ctrlAdvertiser?.stop()
        endpointBrowser?.stop()
        wifiConnector?.release()
        wifiWatchdog?.stop()
        boundCarSsid = null
        if (hadProjection) {
            // Android 14+：MediaProjection 与保活服务（mediaProjection FGS 类型）绑定，
            // 停服务会连带失效授权——下次连接需重新点「申请投屏权限」
            appendLog("已手动断开并停止保活服务（常亮已恢复）；投屏授权已随服务停止失效，下次连接如需声音请重新申请")
        } else {
            appendLog("已手动断开并停止保活服务（常亮已恢复）")
        }
    }

    /** 上次打出审计汇总时的条目数（-1 = 本轮还没打过；用于「只在有新问题时补一行」）。 */
    private var auditSummarizedCount = 0

    /**
     * 把「本轮发现的协议不一致」汇总成一行。
     *
     * 实时逐条输出由 [ProtocolAudit.sink] 负责（每条问题当场进日志），这里只补**汇总**——
     * 用户反馈「连不上」时最想一眼看到的是「这一轮到底有几处不对、都是哪几类」，
     * 而不是在几百行日志里翻 `PROTO!`。按条目数去重，因此 [retireSession] 在每轮配对
     * 起步时也会被调用也不会刷屏（那时条目数没变）。
     */
    private fun flushProtocolAudit(reason: String) {
        val total = ProtocolAudit.snapshot().size
        if (total == auditSummarizedCount) return
        auditSummarizedCount = total
        if (total == 0) {
            appendLog(ProtocolAudit.summary())
        } else {
            appendLog("PROTO 审计（$reason）：${ProtocolAudit.summary()}")
        }
    }

    /** 会话结束/新周期开始前彻底退役上一代会话（旧管线泄漏会累积编码器/虚拟显示器，最终 dequeueBuffer -19）。 */
    fun retireSession() {
        // 只断车机视频通道，不拆 CarLife 劫持本身：本方法在每轮配对起步也会执行，
        // 而 CarLife 连接与 GOP 缓存要跨会话保留（用户可以在会话中途才进「无线连接」）。
        CarLifeHijack.detach()
        // 回调属于上一轮会话（捕获了旧的 appendLog 语境），退役时一并摘掉。
        CarLifeHijack.onActualStreamSize = null
        videoSender?.stop()
        videoSender = null
        // 暗幕/常亮随会话收掉（本方法在每轮配对起步也会执行，新一轮推流起来后会重新置位）
        ScreenDimmer.setSessionActive(false)
        audioSender?.stop()
        audioSender = null
        // 麦克风上行跟着音频流一起收：车机那边流一停就不会再推，留着端口空转没意义。
        // stop() 里会落 wav 并打一行 `MIC: 结论——…`，所以必须在会话日志收尾之前调。
        micUplink?.stop()
        micUplink = null
        // 通话场景标记必须随会话清掉：留着的话下一轮会被当成「已经在通话档」，
        // 进入通话时 switchAudioScene 直接返回 true，一条通话流都不会建。
        audioInCallMode = false
        activeCallProfile = null
        rejectedCallProfileKey = null
        currentSharedSecret = null
        currentAudioHost = null
        currentHiddenSource?.release()
        currentHiddenSource = null
        TouchRouter
            .reset()
        currentClient?.cancelled = true
        currentClient?.close()
        currentClient = null
        // 注意：这里绝不能 detach BT 控制会话。本方法在每轮配对起步时也会执行，
        // 而 DiPlay 在 WiFi 隧道就绪前看到 RFCOMM 断开即判 bring-up failed、
        // closeWirelessStack 拆掉整轮无线栈（Bonjour/热点/AirPlay 服务），把已建立
        // 甚至正在推流的 AirPlay 会话一起拆掉——「连上约 3 秒即断」循环的根因
        // （2026-10-16 实测）。BT 退役只在用户终态做，见 disconnect()。
        // 解除会话静默：出站探测恢复（下次会话建立时会再次置位）
        BtSessionGate.sessionActive = false
        // 「强制应用横屏」覆盖随会话结束统一复原（应用回到手机侧正常竖屏形态）。
        // 异步：每应用两次 am compat shell 往返，6 个应用实测 ~1.6s；此前同步跑在
        // 配对前，等于每次连接白等 1.6s（2026-10-18 实测）。执行器串行保证
        // 「先复原上一会话、后写入本会话」的顺序。
        appContext?.let { ctx -> AppOrientationCompat.resetAllAsync(ctx, ::appendLog) }
        // 协议一致性汇总：放在最后一行，拆链原因与问题清单一前一后，反馈日志时最上面、
        // 最下面各有一条结论，不用翻中间过程。
        flushProtocolAudit("会话退役")
    }

    /** 会话已建立且投影就绪、但音频尚未启动时，动态补启（投影授权晚于连接的场景）。 */
    @Synchronized
    fun maybeStartAudioNow(context: Context) {
        val client =
            currentClient ?: run {
                appendLog("MODE: 投屏已就绪（当前无会话——下次连接时自动启用音频）")
                return
            }
        val secret = currentSharedSecret
        if (secret == null) {
            appendLog("MODE: 明文会话（未配对）无共享密钥——不补启音频")
            return
        }
        if (audioSender?.isRunning == true) return
        appendLog("MODE: 会话进行中，动态补启音频通道…")
        startAudioChannel(context, client, secret, currentAudioHost)
    }

    /**
     * 0x5703 到达（拿到车机热点凭据）立即执行——真车 0x4301 的前置条件：
     * 车机在 WiFi 网络上发现手机的 `_carplay-ctrl._tcp`（TXT id=真实蓝牙 MAC）后
     * 主动发 `GET /ctrl-int/1/connect` 邀请，邀请被接受才发 0x4301。
     * 因此这里必须：绑车机 WiFi → 广播 ctrl；晚于 0x4300 则双方互等死锁（Audi 实测）。
     */
    fun onWifiCredentials(wifi: Iap2SenderSession.WifiConfiguration) {
        // 会话推流中收到的 0x5703 是冗余凭据（DiPlay 控制器周期重连会重发一轮
        // Phase 1，2026-10-15 实测「连上后自动断开」循环的推手之一）：此时再走绑定
        // 流程会 release+重绑进程网络（扰动正在推流的 socket）、force 重注册 ctrl
        // mDNS（DiPlay 视为手机重新上线、再拉一轮 RFCOMM）。只刷新密码缓存。
        if (videoSender?.isRunning == true) {
            lastWifiPsk = wifi.passphrase
            lastCarWifi = wifi
            appendLog("0x5703 冗余凭据（会话推流中）——保持现有 WiFi 绑定与 ctrl 广播不变")
            return
        }
        ssidSink?.invoke(wifi.ssid)
        lastWifiPsk = wifi.passphrase
        // 完整凭据留给「网络被动断开后自动重绑」用（见 onCarWifiLost）
        lastCarWifi = wifi
        phaseHint = "拿到车机热点凭据，绑定 WiFi…"
        appendLog(">>> 拿到车机热点凭据（0x5703）——立即绑定 WiFi + 广播 _carplay-ctrl")
        // ctrl 广播已在 startConnection 时发布（catplay：连接前保持发布能力在线）。
        // 这里登记 SSID 并让注册对齐一次（手机若已手动连在车机热点上、自动加入不可用
        // 时这是唯一一次重注册机会）；加入热点后 bindCarWifiAndRegister 还会 force
        // 重注册，把组播落到车机所在网络（真车实测必需）。
        ctrlAdvertiser?.ensureForNetwork(wifi.ssid)
        appendLog("CTRL: 广播已在启动连接时发布，此处对齐到热点 ${wifi.ssid}")
        // 绑定与切换统一走自动加入流程：bindCurrentWifi 是「任意 WiFi 即成功」的盲绑，
        // 手机连着公司 WiFi 时会误判已在车机热点（ctrl 广播落在错误网络 → 双方互等死锁），
        // 必须先核对真实 SSID 再决定绑定或切换
        autoJoinCarWifi(wifi)
    }

    /**
     * 车机热点**被动断开**后的处置：留痕、告知、原地重绑一次。
     *
     * 旧实现只做前三件里的两件（改提示 + 写日志），不重绑。后果在 2026-11-01 日志里
     * 看得很清楚：断链后 8 秒重绑发生、再 8 秒 CarLife 引擎报「7240 未监听」——
     * 引擎在等网络回来，而旧代码从来不会把网络拉回来，只能等下一轮连接流程重建。
     *
     * 重绑的范围刻意收窄：**只把网络拉回来**，不主动重建 CarPlay 会话。会话重建要
     * 重新配对/建链，会把 CarLife 引擎再踢一次（它比 AirPlay 更脆），风险远大于收益。
     * 因此这里明确写进日志：本轮会话仍需重建，重绑只是让下一次能连上。
     */
    private fun onCarWifiLost(ssid: String) {
        val label = ssid.ifBlank { "车机热点" }
        boundCarSsid = null
        wifiLinkLost = true
        phaseHint = "车机热点已断开（$label）"
        appendLog(">>> 车机热点 \"$label\" 已断开——走它的 socket 已随网络销毁，本轮会话不会自愈")
        val target = lastCarWifi
        if (target == null) {
            // 本轮压根没拿到过凭据（0x5703 与 0x4301 都没到 / 用户手动连的）：
            // 没有密码就发不出入网请求，只能提示
            appendLog("WIFI: 本轮没有热点凭据（0x5703/0x4301 都未到）——无法自动重绑")
            uiHintSink?.invoke("车机热点 \"$label\" 已断开——请重新启动连接")
            return
        }
        if (!sessionWanted) {
            appendLog("WIFI: 用户已主动断开——不自动重绑")
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        synchronized(wifiRebindLock) {
            if (now - lastWifiRebindAt < WIFI_REBIND_MIN_INTERVAL_MS) {
                appendLog("WIFI: ${WIFI_REBIND_MIN_INTERVAL_MS / 1_000}s 内已重绑过一次——跳过这次抖动")
                return
            }
            lastWifiRebindAt = now
        }
        uiHintSink?.invoke("车机热点 \"$label\" 已断开——正在自动重绑")
        Bg.run("wifi-rebind") {
            appendLog(
                "WIFI: 自动重绑车机热点 \"${target.ssid}\"——只恢复网络，" +
                    "本轮 CarPlay 会话仍需重建（旧 socket 已失效）",
            )
            autoJoinCarWifi(target)
        }
    }

    /**
     * 校对真实 WiFi 并加入车机热点——**全程不要求用户点任何东西**。
     *
     * 入网分三层（详见 [WifiCarPlayConnector] 类注释），由 [joinCarWifiOnce] 落地：
     *  1. 复用它已连上的那条热点（零交互）；
     *  2. 登记 WiFi 建议后等系统自己关联（零交互，授权过一次后长期有效）；
     *  3. `WifiNetworkSpecifier` 应用专属网络（唯一会弹系统确认框的一层，只作兜底）。
     *
     * 各环节检查 [connectCancelled]——用户取消后不得继续跑（否则 phaseHint 被覆盖、
     * ctrl 广播被重新拉起，表现为「取消不了」）。
     */
    private fun autoJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        Bg.run("wifi-auto-join") {
            // Shizuku 已移除：没有 shell 特权，直接走「应用专属网络」路径（无需特权）。
            val shellReady = false
            // 1) WLAN 开关。shell 不可用时跳过检查——requestNetwork 会以 Unavailable 反馈。
            if (shellReady && shellWifiDisabled() && !waitForWifiEnabled()) return@run
            if (connectCancelled) return@run
            // 1.5) 先登记 WiFi 建议（幂等，不弹框）：登记越早，系统越可能在我们要用时
            // 就已经把手机连上热点。授权过一次后系统进范围自动关联，这是无人点屏的
            // 唯一可行路径（BMW iD9 只给 27s，人来不及点就断链）。
            wifiConnector?.registerSuggestion(wifi.ssid, wifi.passphrase, wifi.securityType)
            // 2) 入网（复用 → 静默 → 确认框，见 joinCarWifiOnce）
            when (val result = joinCarWifiSpecifier(wifi, shellReady)) {
                is WifiCarPlayConnector.JoinResult.Joined -> Unit

                WifiCarPlayConnector.JoinResult.Unsupported -> {
                    appendLog("WIFI: 热点安全类型 ${wifi.securityType} 无法用应用专属网络表达——退回系统级加入")
                    legacyJoinCarWifi(wifi, shellReady)
                }

                WifiCarPlayConnector.JoinResult.Unavailable -> retryJoinSpecifierSilently(wifi, shellReady)
            }
        }
    }

    /**
     * 入网入口（三层策略见 [joinCarWifiOnce]）：成功后进程网络已绑定、ctrl 广播已重注册。
     *
     * 系统里保存过同名热点时**只记日志、不删除、不询问**——保留它对「静默自动连」有利，
     * 详见 [noteSavedNetworkIfAny]。
     */
    private fun joinCarWifiSpecifier(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
        force: Boolean = false,
    ): WifiCarPlayConnector.JoinResult {
        val connector = wifiConnector ?: return WifiCarPlayConnector.JoinResult.Unavailable
        if (shellReady) {
            noteSavedNetworkIfAny(wifi.ssid)
            if (connectCancelled) return WifiCarPlayConnector.JoinResult.Unavailable
        }
        phaseHint = "正在加入车机热点 ${wifi.ssid}…（不影响移动数据）"
        val result = joinCarWifiOnce(connector, wifi, force)
        if (result is WifiCarPlayConnector.JoinResult.Joined) {
            boundCarSsid = wifi.ssid
            if (wifiLinkLost) {
                // 会话已经死过一次，重绑成功也不代表它能自己活过来——只把状态摆正
                wifiLinkLost = false
                appendLog("WIFI: 车机热点已重新绑定——网络恢复，本轮会话需重建后才生效")
            }
            phaseHint = "已加入车机热点"
            // 关键时机：bindProcessToNetwork 完成后再注册，车机才能看到 ctrl 广播
            ctrlAdvertiser?.ensureForNetwork(wifi.ssid, force = true, network = connector.currentNetwork)
            appendLog(">>> WiFi 已就位（车机热点），ctrl 广播已重新注册，等待车机 ctrl 邀请")
        } else {
            // 取消态也必须留痕：旧实现用 `if (!connectCancelled)` 把失败日志挡掉，
            // 于是「车机 27s 超时掐断 BT → 本轮加入被静默取消」在日志里完全消失，
            // 现场只剩「发起加入」一行、后面什么都没有，根本无从判断卡在哪一步。
            appendLog(
                "WIFI: 应用专属网络加入未成功（${result.javaClass.simpleName}）" +
                    if (connectCancelled) "——本轮已被取消（车机链路已断开），下一轮重来" else "",
            )
        }
        return result
    }

    /**
     * 真正发起一次入网请求——**同一 SSID 全程只允许一次在跑**。
     *
     * 为什么必须互斥：`WifiCarPlayConnector.joinCarPlayNetwork` 第一行是 `release()`
     * （注销回调 + 解绑进程网络）。两个线程同时要同一个 SSID 时，后来的那次会把
     * 先发的那次**就地注销**：先发的永远等不到 onAvailable（白等 15s 超时），
     * `onNetworkLost` 也随回调一起消失（于是自动重绑整条链路失效）。
     * 2026-11-02 日志里「06.581 / 06.586 两行加入 + 28 秒才连上 + 断网时没有任何
     * 断开日志」就是它，三处症状是同一个 bug。
     *
     * @param force 用户显式点了「重试」——绕过 [WIFI_JOIN_DEDUPE_MS] 去重窗口
     *   （那次失败是用户面前的，重试是他明确要求的动作）。
     */
    private fun joinCarWifiOnce(
        connector: WifiCarPlayConnector,
        wifi: Iap2SenderSession.WifiConfiguration,
        force: Boolean,
    ): WifiCarPlayConnector.JoinResult {
        val wired = connector.currentNetwork
        if (boundCarSsid == wifi.ssid && wired != null) {
            appendLog("WIFI: 应用专属网络已在位——跳过重复加入（只复用网络，认证照常重做）")
            return WifiCarPlayConnector.JoinResult.Joined(wired)
        }
        return when (val decision = wifiJoinGate.decide(wifi.ssid, android.os.SystemClock.uptimeMillis(), force)) {
            is WifiJoinGate.Decision.Skip -> {
                appendLog(
                    "WIFI: \"${wifi.ssid}\" ${decision.sinceMillis / 1_000}s 前刚加入失败——" +
                        "${WIFI_JOIN_DEDUPE_MS / 1_000}s 去重窗口内不重复请求" +
                        "（再发一次会把可能在跑的那次 release 掉）",
                )
                WifiCarPlayConnector.JoinResult.Unavailable
            }

            is WifiJoinGate.Decision.Wait -> {
                appendLog(
                    "WIFI: \"${wifi.ssid}\" 已有一次入网请求在跑——等它的结果，" +
                        "不重复请求（再发一次会把在跑的那次 release 掉）",
                )
                val ok = decision.ticket.await(SPECIFIER_JOIN_TIMEOUT_MS + SPECIFIER_JOIN_GRACE_MS + 5_000L)
                val network = connector.currentNetwork
                if (ok == true && network != null) {
                    appendLog("WIFI: 等到的结果是「已加入」——直接复用那条网络（$network）")
                    WifiCarPlayConnector.JoinResult.Joined(network)
                } else {
                    WifiCarPlayConnector.JoinResult.Unavailable
                }
            }

            is WifiJoinGate.Decision.Run -> {
                // **顺序与 1.0.7 一致：复用 → specifier → 建议兜底**，全程无弹框。
                //  1) 复用：系统已连在该热点上 → 只 bindProcessToNetwork，零交互；
                //  2) specifier：1.0.7 唯一的一条路。既有授权/关联记录还在时系统会
                //     静默满足、不弹框，实测整车链路 6 秒内完成；
                //  3) 建议：只有 specifier 真进不来才登记，等系统自己关联（授权过一次
                //     后长期有效）。排在最后是因为它会让快路先白等一轮。
                val adopted = connector.adoptIfAlreadyConnected(wifi.ssid)
                if (adopted != null) {
                    // 参考实现 OcarPlay 的 CURRENT_WIFI_REUSE 语义：
                    // `verified_previous_transport=true / fresh_authentication_required=true /
                    //  network_switch=false` —— 复用的只是**网络**（L2 已关联），
                    // 认证/会话状态一律不继承，本轮照常从配对重新做一遍。
                    // 写这一行是为了让「复用」在日志里不再等于「沿用上次结果」。
                    appendLog(
                        "WIFI: 复用系统已连热点 \"${wifi.ssid}\"（$adopted）——只复用网络，" +
                            "认证不继承：本轮仍重新做一遍配对/会话认证",
                    )
                }
                val result =
                    if (adopted != null) {
                        WifiCarPlayConnector.JoinResult.Joined(adopted)
                    } else {
                        joinSpecifierThenSuggestion(connector, wifi)
                    }
                wifiJoinGate.finish(
                    decision.ticket,
                    result is WifiCarPlayConnector.JoinResult.Joined,
                    android.os.SystemClock.uptimeMillis(),
                )
                result
            }
        }
    }

    /**
     * specifier 与建议通道的先后：**先 specifier（1.0.7 原样），失败才退回建议**。
     *
     * 这条顺序是被实车纠正过一次的：1.2.30/1.2.31 一版曾把「建议」放到 specifier 之前，
     * 依据是 2026-10-29 宝马 iD9 日志里「specifier 每次都要人在系统确认框上点连接」。
     * 但用户对 1.0.7 的实测是**纯 specifier 一路静默**（1.0.7 里根本没有建议通道）——
     * 说明系统对该热点的授权/关联记录还在时，`requestNetwork` 会被**静默满足、不弹框**；
     * 而建议层排在前面反而每轮先白等 12 秒，把快路挤掉。
     *
     * 所以现在：specifier 先上（本机不弹框、有既有授权时毫秒级满足），只有它真的
     * 进不来（`Unavailable`）才登记建议、等系统自己关联——两种机型都被覆盖，
     * 且「不需要点」这一点与 1.0.7 完全一致。
     */
    private fun joinSpecifierThenSuggestion(
        connector: WifiCarPlayConnector,
        wifi: Iap2SenderSession.WifiConfiguration,
    ): WifiCarPlayConnector.JoinResult {
        appendLog(
            ">>> 以应用专属网络加入 \"${wifi.ssid}\"（不保存为系统 WiFi，其它应用继续走 4G/5G）",
        )
        val specifier =
            try {
                connector.joinCarPlayNetwork(
                    ssid = wifi.ssid,
                    passphrase = wifi.passphrase,
                    securityType = wifi.securityType,
                    timeoutMillis = SPECIFIER_JOIN_TIMEOUT_MS,
                    graceMillis = SPECIFIER_JOIN_GRACE_MS,
                    isCancelled = { connectCancelled },
                )
            } catch (error: Exception) {
                appendLog("WIFI: 入网请求异常 - ${error.message ?: error.javaClass.simpleName}")
                WifiCarPlayConnector.JoinResult.Unavailable
            }
        if (specifier is WifiCarPlayConnector.JoinResult.Joined) return specifier
        if (specifier is WifiCarPlayConnector.JoinResult.Unsupported) return specifier
        if (connectCancelled) return specifier

        // specifier 没进来：登记建议（后台生效，系统自己关联，全程不出确认框）。
        // 连续失败两次就说明本机这条通道不可用/未授权，不再每轮白等。
        if (!silentJoinPolicy.shouldTrySilently(wifi.ssid)) {
            appendLog(
                "WIFI: \"${wifi.ssid}\" 建议通道已连续 ${SILENT_JOIN_MAX_MISSES} 次未成" +
                    "（本机不可用或未授权）——本轮到此为止，不弹任何框",
            )
            return specifier
        }
        phaseHint = "正在自动加入车机热点 ${wifi.ssid}…（无需操作）"
        appendLog(
            ">>> specifier 未进来，改登记热点建议让系统自己关联" +
                "（不弹确认框，最多 ${SUGGESTION_SILENT_WAIT_MS / 1_000}s）",
        )
        val silent =
            connector.joinSilentlyBySuggestion(
                ssid = wifi.ssid,
                passphrase = wifi.passphrase,
                securityType = wifi.securityType,
                timeoutMillis = SUGGESTION_SILENT_WAIT_MS,
                isCancelled = { connectCancelled },
            )
        silentJoinPolicy.recordResult(wifi.ssid, silent != null)
        if (silent != null) {
            appendLog(">>> 静默入网成功：\"${wifi.ssid}\" 已由系统自动连上并绑定（全程无弹框）")
            return WifiCarPlayConnector.JoinResult.Joined(silent)
        }
        return specifier
    }

    /**
     * 系统里已保存同名热点时**只记一行日志，不再问用户**（2026-11-08 起）。
     *
     * 旧实现弹「清除保存 / 保留」——而「保留」其实对我们更有利：系统保存了它，进入范围
     * 就会自动连上，`adoptIfAlreadyConnected` 直接复用绑定，正是「静默自动连」最省事的
     * 一条路。删除只影响「系统会不会全局自动连」，与本轮连接无关，却要人点一下。
     *
     * 所以现在一律保留、一律不打扰：问也不问，删也不删。每个 SSID 只记一次日志。
     */
    private fun noteSavedNetworkIfAny(ssid: String) {
        if (!forgetPromptHandled.add(ssid)) return
        val ids = savedNetworkIds(ssid)
        if (ids.isEmpty()) return
        appendLog(
            "WIFI: 系统里已保存热点 \"$ssid\"（netId=${ids.joinToString(",")}）" +
                "——保留它（进范围会自动连上，正好走静默复用），不删除、不再询问",
        )
    }

    /** 查系统已保存的、SSID 与目标完全一致的网络 id（`cmd wifi list-networks`）。 */
    private fun savedNetworkIds(ssid: String): List<Int> {
        val listing = SysShell.exec("cmd wifi list-networks", 5_000L)
        if (!listing.ok) {
            appendLog("WIFI: 无法读取系统已保存网络（exit=${listing.exitCode}）——跳过检查")
            return emptyList()
        }
        return WifiSavedNetworks.idsOf(listing.output, ssid)
    }

    /** WiFi 未开启时的引导与等待；true = 已开启可继续。 */
    private fun waitForWifiEnabled(): Boolean {
        val open =
            askWifiJoinChoice(
                DialogSpec(
                    title = "WiFi 未开启",
                    message = "连接车机热点需要打开手机 WLAN。是否打开 WLAN 设置？",
                    positive = "打开 WLAN 设置",
                    negative = "取消",
                ),
            )
        if (connectCancelled) return false
        when (open) {
            true -> {
                openWlanSettings()
                uiHintSink?.invoke("请在 WLAN 设置中打开 WiFi 开关")
            }

            false -> {
                phaseHint = "已取消——连接车机热点需要开启 WLAN"
                appendLog("WIFI: 用户取消——自动加入中止（WiFi 未开启）")
                return false
            }

            null -> {
                phaseHint = "WiFi 未开启——连接车机热点需要开启 WLAN"
                appendLog("WIFI: 弹窗无人应答——自动加入中止（WiFi 未开启）")
                return false
            }
        }
        var waited = 0
        while (shellWifiDisabled() && waited < WLAN_WAIT_TIMEOUT_MS && !connectCancelled) {
            if (waited == 0) {
                phaseHint = "等待打开 WLAN（最长 120 秒，取消连接可中止）"
                appendLog("WIFI: 等待用户打开 WLAN（最长 120 秒，取消连接可中止）")
            }
            Thread.sleep(2_000)
            waited += 2_000
        }
        if (connectCancelled) {
            phaseHint = "已取消连接"
            return false
        }
        if (shellWifiDisabled()) {
            phaseHint = "WiFi 未开启——无法连接车机热点"
            appendLog("WIFI: 等待超时 WiFi 仍未开启——自动加入中止，请打开 WLAN 后重新连接")
            return false
        }
        return true
    }

    /**
     * 应用专属网络被系统拒绝/超时：**不再弹窗问用户**，自己静默再试几轮。
     *
     * 旧实现在这里弹「重试 / 打开 WLAN 设置」：车上没人点屏，90 秒后按无人应答中止，
     * 用户回来只看到「未能加入车机热点」。可「点重试」和「自动再试一次」做的事完全
     * 一样，唯一区别是要人点——所以直接自动做，用次数兜住（热点不在范围时别无限怼）。
     *
     * @return true = 重试中连上了
     */
    private fun retryJoinSpecifierSilently(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ): Boolean {
        for (attempt in 1..SPECIFIER_AUTO_RETRY_TIMES) {
            if (connectCancelled) return false
            phaseHint = "正在重试加入车机热点 ${wifi.ssid}…（无需操作）"
            appendLog(
                "WIFI: 自动重试加入 \"${wifi.ssid}\"（第 $attempt/${SPECIFIER_AUTO_RETRY_TIMES} 次，无需操作）",
            )
            sleepQuietly(SPECIFIER_RETRY_GAP_MS)
            if (connectCancelled) return false
            // force：绕过 5 秒去重窗口——这一轮本来就该重发
            when (val retry = joinCarWifiSpecifier(wifi, shellReady, force = true)) {
                is WifiCarPlayConnector.JoinResult.Joined -> return true
                WifiCarPlayConnector.JoinResult.Unsupported -> {
                    legacyJoinCarWifi(wifi, shellReady)
                    return false
                }
                WifiCarPlayConnector.JoinResult.Unavailable -> Unit
            }
        }
        phaseHint = "未能加入车机热点——下一轮连接会自动再试"
        appendLog(
            "WIFI: 自动重试 ${SPECIFIER_AUTO_RETRY_TIMES} 次仍未加入 \"${wifi.ssid}\"——" +
                "本轮到此为止，不打扰用户（下次连接会重新走「复用 → specifier → 建议」）",
        )
        return false
    }

    /**
     * 旧路径（WEP 等 specifier 无法表达的安全类型）：`cmd wifi connect-network`
     * 系统级保存并切换。注意这会保存热点为系统网络——无互联网时可能影响移动数据，
     * 只作为兼容兜底。
     *
     * 没有 shell 权限时这里**不再跳 WLAN 设置页**（那是纯手动操作，2026-11-08 起取消），
     * 只把「为什么连不上」写进日志与提示，交给下一轮静默路径重试。
     */
    private fun legacyJoinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ) {
        if (!shellReady) {
            phaseHint = "热点安全类型较旧，自动加入不可用——下一轮会再试"
            appendLog(
                ">>> 热点 \"${wifi.ssid}\" 的安全类型无法用应用专属网络表达，且当前无 shell 权限" +
                    "（WEP 等旧加密）——本轮不跳设置页、不要求手动操作，等下一轮静默路径重试",
            )
            return
        }
        val current = shellWifiStatus().second
        if (current == wifi.ssid) {
            appendLog("WIFI: 手机已在车机热点 \"$current\"——直接绑定")
            bindCarWifiAndRegister(wifi.ssid)
            return
        }
        if (connectCancelled) return
        phaseHint = "正在自动加入车机热点 ${wifi.ssid}…"
        appendLog(
            ">>> 手机当前 WiFi: ${current ?: "未连接"}（非车机热点）——" +
                "已自动切换到 \"${wifi.ssid}\"",
        )
        if (connectCancelled) return
        val security =
            if (wifi.passphrase.isEmpty()) "open" else "wpa2 ${shellQuote(wifi.passphrase)}"
        val result =
            SysShell
                .exec("cmd wifi connect-network ${shellQuote(wifi.ssid)} $security", 10_000L)
        if (!result.ok) {
            phaseHint = "自动加入热点失败——请到 WLAN 设置加入 ${wifi.ssid}"
            appendLog(
                "WIFI: connect-network 失败（${result.output.take(80).ifEmpty { "exit=${result.exitCode}" }}）" +
                    "——请到 系统设置>WLAN 手动加入（密码可点「去连接」复制）",
            )
            return
        }
        phaseHint = "正在加入车机热点 ${wifi.ssid}…若被切回原 WiFi，请在 WLAN 中手动连接"
        appendLog("WIFI: connect-network 已发起（自动保存网络，等待切换…）")
        // 系统切换是异步的（断开当前网/扫描/关联/DHCP，2-10 秒）：轮询 SSID 确认。
        repeat(20) {
            if (connectCancelled) {
                appendLog("WIFI: 自动加入已取消（用户断开）")
                return
            }
            val ssid = shellWifiStatus().second
            if (ssid == null) {
                // status 命令不可用（非 AOSP 系统）：退化为直接尝试绑定（无 SSID 校验）
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
            if (ssid == wifi.ssid) {
                appendLog("WIFI: 已切到车机热点 ${wifi.ssid}")
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
            Thread.sleep(800)
        }
        phaseHint = "未能在热点驻留——请到 WLAN 设置手动连接 ${wifi.ssid}"
        uiHintSink?.invoke("未能在热点驻留——请到 WLAN 设置中主动连接 ${wifi.ssid}")
        appendLog(
            ">>> WiFi 自动加入超时（可能被系统切回原 WiFi）——" +
                "请到 系统设置>WLAN 手动连接 \"${wifi.ssid}\"（手动连接不会被自动切回）",
        )
    }

    /**
     * 绑定当前 WiFi（应为车机热点）并重注册 ctrl 广播；绑定超时给出指引。
     *
     * 绑定动作用 [wifiBindLock] 串行化：auto-join 线程与端点交接线程都可能触发，
     * 并发调用 bindCurrentWifi 会互相 release（解绑瞬间 mDNS 广播直接失效）。
     *
     * 等待窗口 [WIFI_BIND_WAIT_S] 必须够长：`requestNetwork(WIFI)` 的 onAvailable
     * 在「手机还没连上任何 WiFi、用户正在系统设置里手动加入热点」时，要等到真正
     * 关联+DCHP 完成才回调。旧值 5s 在奥迪实测中是致命竞态——20:02:09.352 判定
     * 「不在热点」放弃整轮会话，20:02:09.467（**116ms 后**）onAvailable 就到了并
     * 绑定成功；用户被迫多等一轮 BT 重连（+13s）。宁可可中断地多等，也不要假失败。
     *
     * @return 进程网络是否绑定成功
     */
    private fun bindCarWifiAndRegister(ssid: String): Boolean {
        var bound = false
        synchronized(wifiBindLock) {
            // 另一个路径已经绑定同一热点：别再走一遍 release+requestNetwork
            if (boundCarSsid == ssid && wifiConnector?.currentNetwork != null) {
                bound = true
            } else {
                val latch = java.util.concurrent.CountDownLatch(1)
                wifiConnector?.bindCurrentWifi { ok ->
                    bound = ok
                    latch.countDown()
                }
                // 分片等待：期间用户取消要能立刻退出，不必等满窗口
                val deadline = android.os.SystemClock.uptimeMillis() + WIFI_BIND_WAIT_S * 1_000
                while (!connectCancelled &&
                    bound.not() &&
                    android.os.SystemClock.uptimeMillis() < deadline
                ) {
                    if (latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) break
                }
                if (bound) boundCarSsid = ssid
            }
        }
        if (bound && !connectCancelled) {
            phaseHint = "已加入车机热点"
            // 关键时机：bindProcessToNetwork 完成后再注册，车机才能看到 ctrl 广播
            ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)
            appendLog(">>> WiFi 已绑定车机热点，ctrl 广播已重新注册，等待车机 ctrl 邀请（随后才回 0x4301）")
        } else if (!connectCancelled) {
            phaseHint = "手机不在车机热点——请到 WLAN 设置加入 $ssid"
            appendLog(
                ">>> 等待 ${WIFI_BIND_WAIT_S}s 仍未绑定到 \"$ssid\"——" +
                    "请在 系统设置>WLAN 手动加入该热点后重新连接",
            )
        }
        return bound
    }

    /**
     * 弹窗询问用户（阻塞调用线程直到选择/关闭/90 秒超时）：
     * true=positive；false=negative；null=无人应答（调用方决定兜底）。
     */
    private fun askWifiJoinChoice(spec: DialogSpec): Boolean? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var choice: Boolean? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val sink = dialogSink
            if (sink == null) {
                // 无 UI 宿主（Activity 销毁窗口）：宁可中止也不默认替用户决定加入
                latch.countDown()
                return@post
            }
            sink(spec) { result ->
                choice = result
                latch.countDown()
            }
        }
        latch.await(JOIN_DIALOG_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
        return choice
    }

    private fun openWlanSettings() {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    ctx.startActivity(
                        android.content
                            .Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    /** 只记一次「SSID 读不到」，避免 5 秒一次的轮询把日志刷满。 */
    @Volatile
    private var wifiSsidUnreadableLogged = false

    /**
     * WiFi 开关与当前连接的 SSID。
     *
     * 两条来源，**先 shell 后框架**：
     *  1. `cmd wifi status`（要有 shell 身份，Shizuku/root 在时才有），输出形如
     *     `Wifi is enabled` + `Wifi is connected to "Redmi K40"`；
     *  2. [frameworkWifiStatus]：不需要任何特权，只要给了定位 / 附近设备权限。
     *
     * 为什么必须补第 2 条（2026-11-07 别克 E2LB 现场）：Shizuku 移除后 `cmd wifi status`
     * 一律失败，于是这里永远返回「已开、SSID=null」，日志里一直写「Wi-Fi=已开但未连接任何
     * 网络」——而手机其实挂在 192.168.9.15 上。后果不只是日志难看：「手动连车机热点」那条
     * 兜底路径靠 SSID 非空才会弹窗，读不到 SSID 等于这条路根本走不到（空等 5 分钟超时）。
     */
    private fun shellWifiStatus(): Pair<Boolean, String?> {
        val result = SysShell.exec("cmd wifi status", 5_000L)
        if (result.ok) {
            val enabled = !result.output.contains("Wifi is disabled")
            // 老 ROM 写 `connected to "X"`，新 ROM（13+）写 `SSID: "X"`——两种都收。
            val ssid =
                Regex("connected to \"(.*)\"").find(result.output)?.groupValues?.get(1)
                    ?: Regex("SSID: \"(.*)\"").find(result.output)?.groupValues?.get(1)
            if (!ssid.isNullOrEmpty()) return enabled to ssid
            if (!enabled) return false to null
        }
        val (enabled, ssid) = frameworkWifiStatus()
        if (ssid == null && enabled && !wifiSsidUnreadableLogged) {
            wifiSsidUnreadableLogged = true
            appendLog(
                "WIFI: 读不到当前 SSID（无 shell 权限，且定位/附近设备权限不足）——" +
                    "「已连网络是否就是车机热点」这一步需要它；请到系统设置给本应用「位置」或「附近的设备」权限",
            )
        }
        return enabled to ssid
    }

    /**
     * 框架侧读取 WiFi 状态（无特权）。
     *
     * SSID 走 [android.net.NetworkCapabilities.transportInfo]：Android 12 起
     * `wifiManager.connectionInfo` 对应用专属网络常常报 `<unknown ssid>`，而 transportInfo
     * 在所有已连网络上都有。读不到（没权限 / 未连接）返回 null——**不猜**，猜错会把进程
     * 绑到家里的 WiFi 上（AirPlay 必然连不上，其它应用还会瞬间断网）。
     */
    private fun frameworkWifiStatus(): Pair<Boolean, String?> {
        val ctx = appContext ?: return true to null
        val wifiManager =
            runCatching { ctx.getSystemService(android.net.wifi.WifiManager::class.java) }.getOrNull()
        @Suppress("DEPRECATION")
        val enabled = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(true)
        val connectivityManager =
            runCatching {
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            }.getOrNull() ?: return enabled to null
        val ssid =
            runCatching {
                connectivityManager.allNetworks.firstNotNullOfOrNull { network ->
                    val caps = connectivityManager.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
                    if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                        return@firstNotNullOfOrNull null
                    }
                    plainSsid((caps.transportInfo as? android.net.wifi.WifiInfo)?.ssid)
                        ?: runCatching {
                            @Suppress("DEPRECATION")
                            plainSsid(wifiManager?.connectionInfo?.ssid)
                        }.getOrNull()
                }
            }.getOrNull()
        return enabled to ssid
    }

    /** 去掉系统给的双引号，把「读不到」的占位值统一成 null。 */
    private fun plainSsid(raw: String?): String? {
        val plain = raw?.removePrefix("\"")?.removeSuffix("\"") ?: return null
        return if (plain.isEmpty() || plain == UNKNOWN_SSID) null else plain
    }

    /** 没权限读 SSID 时系统给的占位值（与 WifiCarPlayConnector 里的同名常量同义）。 */
    private const val UNKNOWN_SSID = "<unknown ssid>"

    private fun shellWifiDisabled(): Boolean = !shellWifiStatus().first

    /** 单引号包裹的 POSIX shell 字面量（SSID/密码可能含空格与特殊字符）。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 复现「BT 控制已结束」状态：断开当前任一路径的 iAP2 控制会话（DiPlay 只在此状态应答 RTSP）。 */
    private fun detachActiveBtSessions() {
        bluetoothServer?.detachCurrentSession()
        bluetoothClient?.detachCurrentSession()
    }

    /**
     * 保活期在当前 WiFi 里找一次车机的 AirPlay 服务（`_airplay._tcp`）。
     *
     * 为什么值得单独做：车机不下发 `0x4301` 时，我们唯一还能自己动的线索就是 WiFi 侧——
     * 它的 AirPlay 接收端起没起、地址端口是多少。找到了就能直接连（真 iPhone 也是这么定位
     * 接收端的）；找不到则证明「车机没起 AirPlay 服务」，把问题稳稳地留在车机侧，
     * 不必再猜是不是我们的 iAP2 帧发错了。
     *
     * 绑定当前 WiFi 是必须的：mDNS 组播跟随进程默认网络，手机同时挂着蜂窝/另一条 WiFi 时
     * 不绑就发不出去，也收不到应答。
     *
     * @return true = 已发现并放进浏览器的缓存（调用方随后合成端点即可）
     */
    private fun probeAirplayOnCurrentWifi(
        ssid: String,
        attempt: Int,
    ): Boolean {
        val connector = wifiConnector ?: return false
        appendLog("PROBE #$attempt: 在 \"$ssid\" 里找车机 _airplay._tcp（最多等 ${PROBE_TIMEOUT_MS / 1000}s）")
        // 只绑「确实连在目标 SSID 上」的那条网络：以前这里是 bindCurrentWifiBlocking
        // （泛匹配 TRANSPORT_WIFI），手机同时挂着家里 WiFi 时会绑错，mDNS 自然什么也发现不了。
        val bound = connector.awaitAssociation(ssid, 4_000L) { connectCancelled }
        if (bound == null) {
            appendLog("PROBE #$attempt: 绑定当前 WiFi 失败——跳过本轮探测（蓝牙保活不受影响）")
            return false
        }
        // 广播跟着同一条网络走，车机才可能发出 ctrl 邀请。
        ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = bound)
        endpointBrowser?.resetCache()
        endpointBrowser?.start()
        val info = endpointBrowser?.awaitEndpoint(PROBE_TIMEOUT_MS)
        if (info == null) {
            endpointBrowser?.stop()
            appendLog(
                "PROBE #$attempt: \"$ssid\" 里 ${PROBE_TIMEOUT_MS / 1000}s 内没有 _airplay._tcp ——" +
                    "车机的 AirPlay 接收端没起来（不是我们 iAP2 帧的问题）；请确认车机上已打开无线 CarPlay / 点过本机图标",
            )
            return false
        }
        appendLog(
            "PROBE #$attempt: ✓ 发现 _airplay._tcp ${info.host?.hostAddress}:${info.port}" +
                "（${info.serviceName}）——车机 AirPlay 已就绪",
        )
        return true
    }

    /**
     * 空端点等待期（车机声明会发 0x4301 但迟迟未到）的观察任务。
     *
     * 1. 每 5s 轮询当前 WiFi——用户手动连上车机热点后弹窗确认，确认即以该 SSID 合成
     *    端点重走 [onCarPlayEndpoint]（无端口 → mDNS/网关兜底）；同时每 30s 在当前
     *    WiFi 里找一次车机的 `_airplay._tcp`（见 [probeAirplayOnCurrentWifi]）。
     * 2. 晚到 0x4301 触发新一轮交接（sessionStarting 重新置位）时让位退出；
     * 3. 超过 [PENDING_START_SESSION_WAIT_MS] 无进展：记日志并拆掉保活链路，让 BT
     *    重连循环接管（与旧「8s 拆链」的区别只在窗口长度）。
     *
     * 为什么需要第 1 条：GM/Cinemo 这类车机（凯迪拉克 CT5/A2LL 实测）的 0x4301 以
     * 「车机在 Wi-Fi 上看到手机的 _carplay-ctrl 并邀请」为前提，而它又不下发 0x5703
     * 凭据——手机进不了它的网络就永远等不到 0x4301。车主在车机上能看到热点名与密码，
     * 手动连上即可打破死锁；这一步就是把「用户已经连上了」这件事告诉主控。
     */
    private fun watchForPendingStartSession(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        // 代际：新观察者一起，前面所有观察者作废（每次断链重连都会新起一个）。
        val generation = pendingWatchGeneration.incrementAndGet()
        Bg.run("pending-start-session-watch") {
            if (pendingWaitGaveUp) {
                // 本周期的预算已耗尽，不重复刷日志/重复拆链（旧实现会在此无限循环）。
                return@run
            }
            // 英雄卡副标题同步：否则界面一直显示默认的「蓝牙 iAP2 握手 → …」，
            // 用户看到的是「一直在握手」，实际早已连上、在等车机下发凭据。
            phaseHint = "已连上车机，正在等它下发热点信息（最多 ${PENDING_START_SESSION_WAIT_MS / 60_000} 分钟）· 请保持 Wi-Fi 开启"
            appendLog(
                ">>> [$source] 进入保活等待：车机声明会发 0x4301 但窗口内未到。" +
                    "蓝牙链路保持（最多 ${PENDING_START_SESSION_WAIT_MS / 60_000} 分钟，从点「启动连接」算起），" +
                    "期间请保持手机 Wi-Fi 开启；车机多数要看到手机进入它自己的网络后才会下发凭据。" +
                    "若车机屏幕上有「车辆热点 / 热点名称」，抄下 SSID 与密码在系统 WLAN 里手动连上" +
                    "（提示无法上网时选「保持连接」），App 检测到入网会自动接着走 mDNS",
            )
            var promptedSsid: String? = null
            // 保活期每轮 mDNS 探测的状态：换了网络重置；同一网络里最多探 6 次（每 30s 一次），
            // 车机的 AirPlay 服务常常是在「车机屏幕被点了/它自己醒了」之后才起来的。
            var probedSsid: String? = null
            var probeCount = 0
            var lastProbeAt = 0L
            // 预算从**用户点击**算起：车机反复断链重连时不能每轮重新计 5 分钟（否则永不超时）。
            if (pendingWaitBudgetStartedAt == 0L) pendingWaitBudgetStartedAt = android.os.SystemClock.uptimeMillis()
            val watchStartedAt = pendingWaitBudgetStartedAt
            val deadline = watchStartedAt + PENDING_START_SESSION_WAIT_MS
            var tick = 0
            while (!connectCancelled && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(5_000)
                tick++
                if (connectCancelled) return@run
                if (pendingWatchGeneration.get() != generation) return@run
                // 晚到 0x4301 已触发新一轮交接——观察任务让位。
                if (sessionStarting.get()) return@run
                val (wifiEnabled, currentSsid) = shellWifiStatus()
                // 每 30s 落一行：没有它，等待期在日志里只剩车机心跳，用户/排查都看不出在等什么。
                if (tick % 6 == 0) {
                    val waited = (android.os.SystemClock.uptimeMillis() - watchStartedAt) / 1_000
                    appendLog(
                        ">>> [$source] 仍在等 0x4301（已等 ${waited}s · 上限 " +
                            "${PENDING_START_SESSION_WAIT_MS / 60_000} 分钟）· Wi-Fi=" +
                            when {
                                !wifiEnabled -> "已关闭（请打开——车机看不到手机就不会下发凭据）"
                                currentSsid.isNullOrEmpty() ->
                                    "已开，SSID 读不到（未连网络，或缺定位/附近设备权限）"
                                else -> "\"$currentSsid\""
                            },
                    )
                }
                val ssid = if (wifiEnabled) currentSsid else null
                // ---- 保活期的 mDNS 探测 ----
                // 2026-11-07 12:39 那轮：手机已经挂在车机热点（10.154.116.159）上，蓝牙链路
                // 也正常，可我们整轮既没找过 `_airplay._tcp`、也没发过任何 WiFi 侧的包——
                // 「车机到底有没有把 AirPlay 服务起起来」这个问题在日志里完全查不到。
                // 这里每 30s 在当前 WiFi 里找一次：找到就直接走 AirPlay（不依赖 0x4301），
                // 找不到也至少留下一条「它没起服务」的硬证据，不用再靠猜。
                if (!ssid.isNullOrEmpty()) {
                    if (probedSsid != ssid) {
                        probedSsid = ssid
                        probeCount = 0
                        lastProbeAt = 0L
                    }
                    val now = android.os.SystemClock.uptimeMillis()
                    if (probeCount < PROBE_MAX_TIMES && now - lastProbeAt >= PROBE_INTERVAL_MS) {
                        probeCount++
                        lastProbeAt = now
                        if (probeAirplayOnCurrentWifi(ssid, probeCount)) {
                            if (!connectCancelled && !sessionStarting.get()) {
                                appendLog(
                                    ">>> [$source] \"$ssid\" 里已有车机 AirPlay 服务——" +
                                        "以该网络合成端点重走连接（不再等 0x4301）",
                                )
                                onCarPlayEndpoint(endpoint.copy(ssid = ssid), "$source/热点探测")
                            }
                            return@run
                        }
                        // 探测会把进程默认网络绑到 WiFi 上；已经确认车机没有 AirPlay 服务时，
                        // 仍保留蓝牙保活，继续等 0x4301 与用户确认。
                    }
                }
                // 手机上挂了某条网络、但车机始终没下发 0x4301 时，**不再弹「是否把它当车机
                // 热点」问用户**（2026-11-08 起）：上面那轮 mDNS 探测已经在无人工干预的
                // 情况下把「真车机热点」认出来了并直接放行；探测不到就说明它不是车机热点，
                // 问了也只是让人点一下「继续等待」。这里只留一行日志。
                if (ssid.isNullOrEmpty() || promptedSsid == ssid) continue
                promptedSsid = ssid
                appendLog(
                    ">>> [$source] 手机当前挂在 \"$ssid\" 上，但里面没有 _airplay._tcp —— " +
                        "不当作车机热点（不打扰用户），继续蓝牙保活等车机下发 0x4301",
                )
            }
            if (!connectCancelled && !sessionStarting.get() && pendingWatchGeneration.get() == generation) {
                pendingWaitGaveUp = true
                phaseHint = "车机未下发热点信息（0x4301），本轮已停止重试"
                appendLog(
                    ">>> [$source] 空端点等待 ${PENDING_START_SESSION_WAIT_MS / 60_000} 分钟超时——" +
                        "车机始终未下发 0x4301（0x4300 各种报文体都试过了，见上方「0x4300 报文体形态」与" +
                        "「⚠ 零反馈」两行）。先拆掉保活链路停止空转；确认车机开了无线 CarPlay 后再点" +
                        "「启动连接」，并把日志发出来",
                )
                // 等待预算耗尽 = 这一轮确定连不上：先落一条「卡在哪」的结论行（幂等），
                // 否则日志里只有「本轮开始」、没有收尾，用户不知道该看哪一段。
                connectBudget.end(
                    cause = "车机在 ${PENDING_START_SESSION_WAIT_MS / 60_000} 分钟内始终未下发 0x4301",
                    keywords = "0x4300 报文体形态 / ⚠ 零反馈 / 仍在等 0x4301",
                )
                detachActiveBtSessions()
                // 等待预算耗尽 = 这一轮确定连不上：把内存里的报文快照落盘（独立文件）
                SessionTrace.sessionEnd("等待 0x4301 超时（车机未下发热点信息），本轮放弃")
            }
        }
    }

    /**
     * 等车机 ctrl 邀请（`GET /ctrl-int/1/connect`）——catplay 的连接触发点。
     *
     * 邀请意味着「车机已在 WiFi 上发现我们，且已把 AirPlay 端口打开」，此时连
     * AirPlay 才不会被 Connection refused。旧实现用固定 sleep 1.6s 赌邀请已到：
     * 车机快时白等，慢时又不够。这里事件驱动，并且若 mDNS 已发现 `_airplay._tcp`
     * （端口确定可连）也立即放行，避免旧机型不发邀请时白等满窗口。
     *
     * @return true = 收到邀请 / 已发现端点；false = 等待窗口耗尽
     */
    /**
     * 消费「邀请先到、端点未就绪」的待决标记。
     *
     * 返回 true = 车机在更早的时候已经邀请过我们，只是当时拿不到端点；
     * 现在这轮连接不必再空等一次邀请窗口，直接放行。过期或没有待决都返回 false。
     */
    private fun consumePendingInvite(source: String): Boolean {
        val at = pendingInviteAtMs
        if (at == 0L) return false
        pendingInviteAtMs = 0L
        val age = android.os.SystemClock.uptimeMillis() - at
        if (age > PENDING_INVITE_TTL_MS) {
            appendLog("CTRL: [$source] 待决邀请已过期（${age}ms）——不接续，按正常流程走")
            return false
        }
        appendLog("CTRL: [$source] 接续 ${age}ms 前先到的车机邀请——直接放行，不再空等邀请窗口")
        return true
    }

    private fun awaitCtrlInvite(
        ssid: String,
        timeoutMillis: Long,
    ): Boolean {
        val advertiser = ctrlAdvertiser
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        val startedAt = android.os.SystemClock.uptimeMillis()
        var lastLogAt = startedAt
        var reannounced = false
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (connectCancelled) return false
            val invites = advertiser?.inviteCount ?: 0
            if (invites > 0) {
                appendLog(
                    "CTRL: ✓ 收到车机邀请（${android.os.SystemClock.uptimeMillis() - startedAt}ms，第 $invites 次）" +
                        "——立即连接 AirPlay",
                )
                return true
            }
            // 邀请更早到过、只是当时端点未就绪（catplay cache_miss 自愈）：直接接续。
            if (consumePendingInvite("等邀请窗口")) return true
            val browserInfo = endpointBrowser?.awaitEndpoint(0)
            if (browserInfo != null) {
                // bit37 CARPLAY_CONTROL = 车机声明「由它主动发起连接」。catplay 的
                // AirPlayFeature 位域同款；没声明的老车机只会等手机来连，死等邀请是白等。
                // 保持既有「发现 _airplay._tcp 就放行」的行为不变，只是把判定依据写进日志，
                // 让「这台车到底会不会发邀请」在排查时一眼可见，不再靠固件串猜代际。
                val control =
                    AirPlayFeatures.supportsCarPlayControl(
                        AirPlayFeatures.parse(txtValue(browserInfo, "features")),
                    )
                appendLog(
                    when (control) {
                        false ->
                            "CTRL: 车机未声明 CARPLAY_CONTROL(bit37)——按 catplay legacy 行为" +
                                "（车机不主动发起），不等邀请直接连 AirPlay"
                        true ->
                            "CTRL: 已发现 _airplay._tcp（bit37 已声明，车机会主动发起）——直接连接 AirPlay"
                        null ->
                            "CTRL: 已发现 _airplay._tcp（features 未知，按旧机型行为）——直接连接 AirPlay"
                    },
                )
                return true
            }
            val now = android.os.SystemClock.uptimeMillis()
            // catplay 实测：iPhone 的 Bonjour 公告常被车机丢弃——它在每次邀请前都
            // force_announce。这里在等待中途补发一次，覆盖「绑网瞬间公告丢失」的情况。
            if (!reannounced && now - startedAt >= 2_000) {
                reannounced = true
                appendLog("CTRL: 2s 未收到邀请——强制重新公告一次（catplay 同款 force_announce）")
                ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)
            }
            if (now - lastLogAt > 1_200) {
                appendLog("CTRL: 等待车机 ctrl 邀请…（已等 ${now - startedAt}ms）")
                lastLogAt = now
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return false
            }
        }
        appendLog("CTRL: ${timeoutMillis}ms 未收到邀请——按超时继续连接（可能车机走旧发现路径）")
        return false
    }

    // ---- 以下方法体自 MainActivity 原样迁入（会话逻辑不依赖 Activity）----

    /**
     * 音频 SETUP 的**唯一入口**：先等隧道控制会话（MFi）就绪，再真正协商音频。
     *
     * 为什么必须先等（2026-11-06 实车）：抢在 MFi 之前发，车机要 5097ms 才回应，
     * 同期 MFi 20B/32B 挑战连续静默，控制会话晚 6 秒才起来，6011 同步包还被 ICMP 拒。
     * 同源项目在 CL39 上就是「控制会话就绪——允许音频 SETUP」之后才发，往返只有 172ms。
     * 等不到也不拆链：照常发，超时兜底。
     *
     * 「音频优先」与「RECORD 优先」两条路径都从这里进，保证等待逻辑只有一份。
     */
    private fun startAudioChannelAndLog(
        client: AirPlayClient,
        sharedSecret: ByteArray?,
        host: String?,
        context: Context,
    ): Boolean {
        if (sharedSecret != null) {
            val ready = client.awaitControlSession(AUDIO_CONTROL_SESSION_WAIT_MS)
            appendLog(
                if (ready) {
                    "AUDIO: 控制会话已就绪——开始音频 SETUP（不再抢在 MFi 前）"
                } else {
                    "AUDIO: 等控制会话就绪超时（${AUDIO_CONTROL_SESSION_WAIT_MS}ms）" +
                        "——照常音频 SETUP（不拆链）"
                },
            )
        }
        return startAudioChannel(context, client, sharedSecret, host)
    }

    /**
     * 音频通道（手机 → 车机，RTP over UDP）：SETUP type=100 协商 → 直通 PCM → ChaCha 封包推流。
     *
     * 2026-10-24 起画面来源唯一化为 CarLife 本地劫持，音频随之改为**直通**：CarLife 引擎
     * 的裸 PCM 直接喂给 [AudioSender.feedPcm]，不再依赖 MediaProjection 播放捕获授权，
     * 也不需要「本机播放 + 采集」中转。点击「启动连接」默认带声音（不再有开关）。
     *
     * @return 音频通道是否真正启动（调用方据此决定是否补发 RECORD 激活新加入的流）
     */
    fun startAudioChannel(
        context: Context,
        client: AirPlayClient,
        sharedSecret: ByteArray?,
        host: String?,
    ): Boolean {
        if (sharedSecret == null) {
            // 明文会话（et=NONE）没有共享密钥可派生 DataStream 输出密钥：
            // 推出去的 RTP 车机无法解密。宁可不推，也不要制造「有包无声」的假象。
            appendLog("AUDIO: 明文会话（未配对）无共享密钥——跳过音频通道")
            return false
        }
        if (host == null) {
            appendLog("AUDIO: 无接收端可用地址，跳过音频通道")
            return false
        }
        return try {
            // 车机身份 → 只用于「本车机哪些音频档位踩过雷」的持久化记忆。
            val identity = client.receiverIdentity()
            // 音频封装参数已定案（见 AudioTuning），不再提供切换
            val tuning = AudioTuning.RAW_SPF
            val entry = AudioProfileMemory.load(context.applicationContext, identity)
            if (entry.disabled) {
                // 以前「全部档位都导致拆链」会自动退成只推视频，并要用户去弹窗里手点
                // 「清除记忆」。现在音频通路已换成 AAC-LC 且实测能稳定跑满 147s，
                // 旧结论不再可信：用户既然主动选了「开启声音」，就直接清记忆重试。
                AudioProfileMemory.clearAll(context.applicationContext)
                appendLog("AUDIO: 本车机（$identity）有「全部档位拆链」的旧记忆，已自动清除并重试")
            }
            val candidates = client.audioCandidates(preferCompat = audioPreferCompat)
            val ordered = AudioProfileMemory.order(candidates, entry)
            if (ordered.isEmpty()) {
                appendLog("AUDIO: 没有可用音频档位（候选=${candidates.map { it.label }}）——仅推视频")
                return false
            }
            appendLog(
                "AUDIO: 音频档位候选=${ordered.map { it.label }}" +
                    "（本车机记忆：${AudioProfileMemory.describe(entry)}；" +
                    "封装=${tuning.detail}）",
            )
            // SETUP 失败不会扰动会话（车机只是不接这条流的请求），所以本会话内就把候选
            // 试完，不必等下一次连接。这一点对当前场景很关键：102/AAC-LC 与 100/PCM 是
            // 两条完全不同的通路，前者不被接受时应当立刻退到后者，而不是让用户白连一次。
            var chosen: AudioProfile? = null
            var streamInfo: AirPlayClient.AudioStreamInfo? = null
            // 麦克风上行：端口必须**在 SETUP 之前**绑好——车机就是照这个端口往我们这边推麦，
            // 建流后再绑就没有声明的机会了。绑不上（极少）就退回纯下行，不影响现有功能。
            val uplink =
                if (MIC_UPLINK_ENABLED) {
                    MicUplink(
                        channels = 1,
                        log = { line -> appendLog(line) },
                        dumpWav = { data, rate, ch -> MicDumpWriter.writeWav(context.applicationContext, data, rate, ch) },
                    ).takeIf { it.open() }
                } else {
                    null
                }
            try {
                for (candidate in ordered) {
                    val result = client.runAudioStream(sharedSecret, candidate, tuning, micPort = uplink?.localPort ?: 0)
                    if (result != null) {
                        chosen = candidate
                        streamInfo = result
                        break
                    }
                    // SETUP 被拒不等于「车机不兼容」：可能只是资源仲裁/网络抖动，
                    // 不能据此把整台车机的音频判死（置 disabled 只认「拆链」证据）。
                    AudioProfileMemory.recordRejected(
                        context.applicationContext,
                        identity,
                        candidate.key,
                        ordered.map { it.key }.toSet(),
                        markExhausted = false,
                    )
                    appendLog("AUDIO: 档位 ${candidate.label} 协商失败——本会话继续试下一档")
                }
            } catch (error: Exception) {
                uplink?.stop()
                throw error
            }
            val profile = chosen
            val audio = streamInfo
            if (profile == null || audio == null) {
                // 没有音频流就没人往那个端口推麦，收尾掉（会打一行「一包没收到」）
                uplink?.stop()
                appendLog("AUDIO: 全部候选档位协商失败（共 ${ordered.size} 档）——本次仅推视频")
                return false
            }
            if (uplink != null) {
                // 密钥的盐是这条流的 streamConnectionID，必须用建流后返回的那个值
                uplink.start(sharedSecret, audio.streamConnectionId, audio.sampleRate)
                micUplink = uplink
            }
            activeAudioProfile = profile
            activeAudioReceiverKey = identity
            activeAudioStartedAt = android.os.SystemClock.uptimeMillis()
            val sender =
                AudioSender(
                    host = host,
                    dataPort = audio.dataPort,
                    streamKey = audio.streamKey,
                    log = { line -> appendLog(line) },
                    context = context.applicationContext,
                    sampleRate = audio.sampleRate,
                    codec = profile.codec,
                    streamType = profile.streamType,
                    adtsFrames = tuning.adts,
                    // 同步包目标端口：车机在 SETUP 应答里下发。缺了它就没有 RTP↔NTP 锚点，
                    // 接收端只能「按到达时刻猜」，严格的实现干脆不排播（2026-10-20 实测）。
                    controlPort = audio.controlPort,
                )
            audioSender = sender
            if (!sender.startPassthrough()) {
                audioSender = null
                activeAudioProfile = null
                activeAudioStartedAt = 0L
                false
            } else {
                startCallWatcher()
                true
            }
        } catch (error: Exception) {
            appendLog("AUDIO: 通道启动异常 - ${error.message}")
            audioSender = null
            activeAudioProfile = null
            activeAudioStartedAt = 0L
            false
        }
    }

    /**
     * 通话结束后**重建整条音频流**（新的 streamConnectionID / 新端口 / 新编码器）。
     *
     * 2026-10-26 实测（1.0.5 · 奥迪 MIB3 + WinPlay 车机盒）：微信来电挂断后
     * `/feedback` 的 `sampleTime` 仍按 **1.00 倍**实时推进（接收端在正常解码排播），
     * 电平表同时证明推的是**真声音**（100%，非零 99%），车机却一点声都不出。
     * 两个「接收端在消费」的证据合起来只剩一种解释：**它的音频输出设备在通话期间
     * 被释放后没重新打开**——解码照旧、输出通路断开。
     *
     * 这类卡死下改音量（本机 `GET_PARAMETER volume` 返回空 body，车机不支持查询，
     * `SET_PARAMETER` 也无效果）和补同步包都不能救，因为本机侧时钟与内容都是正常的。
     * 唯一在 App 侧能做的、也正是「重连一次就好」在音频层的等价物：
     * 让接收端**新建一条音频流**，逼它重新打开输出通路。
     *
     * 顺序刻意是「**先协商新的、成功了才停旧的**」：SETUP 被拒时旧流仍在推，
     * 绝不能把「本来还有声」搞成「彻底没声」。
     */
    @Synchronized
    fun rebuildAudioChannel(reason: String): Boolean {
        val client = currentClient ?: return false
        val secret = currentSharedSecret ?: return false
        val host = currentAudioHost ?: return false
        val profile = activeAudioProfile ?: return false
        val context = appContext?.applicationContext ?: return false
        val old = audioSender ?: return false
        if (!old.isRunning) return false
        appendLog("AUDIO: $reason → 重建音频流（接收端音频输出在通话期间被释放，需新建流才会重开）")
        val info =
            try {
                // 重建时把上行端口继续声明进去：不然车机以为手机不要麦了，通话里反而没上行
                client.runAudioStream(secret, profile, AudioTuning.RAW_SPF, micPort = micUplink?.localPort ?: 0)
            } catch (error: Exception) {
                appendLog("AUDIO: 重建协商异常 - ${error.message}")
                null
            }
        if (info == null) {
            appendLog("AUDIO: 重建协商失败——保持原音频流继续推（不降级成无声）")
            return false
        }
        // 新流的密钥盐变了，上行监听跟换
        micUplink?.updateKey(secret, info.streamConnectionId)
        // 新流已就绪：停旧发送端。旧流不再有包，接收端会在超时后自行回收。
        old.stop()
        audioSender = null
        // 看门狗线程跟随 audioSender 字段：换流后自动续上，所以这里**不该**置
        // callWatcher = null —— 那只丢引用、旧线程仍在跑，紧接着 startCallWatcher()
        // 再起一个就成双线程（两边同时切音频流）。startCallWatcher 自己有 isAlive 检查。
        val sender =
            AudioSender(
                host = host,
                dataPort = info.dataPort,
                streamKey = info.streamKey,
                log = { line -> appendLog(line) },
                context = context,
                sampleRate = info.sampleRate,
                codec = profile.codec,
                streamType = profile.streamType,
                adtsFrames = AudioTuning.RAW_SPF.adts,
                controlPort = info.controlPort,
            )
        audioSender = sender
        if (!sender.startPassthrough()) {
            audioSender = null
            activeAudioStartedAt = 0L
            appendLog("AUDIO: 音频流重建后启动失败——本次仅推视频（重连可恢复）")
            return false
        }
        activeAudioStartedAt = android.os.SystemClock.uptimeMillis()
        startCallWatcher()
        // 新流是在「会话已激活」之后加入的：按 AirPlay 语义必须补发 RECORD 纳入会话，
        // 否则车机对 SETUP 回 200、给端口，但不排播（见 startAudioChannel 同名注释）。
        client.record()
        appendLog(
            "AUDIO: 音频流重建完成（新 dataPort=${info.dataPort} " +
                "controlPort=${info.controlPort}）——若仍无声，说明卡在车机音源选择而非本机",
        )
        return true
    }

    /**
     * 按场景**换一条音频流**（媒体档 ↔ 通话档），先建新的、成功了才停旧的。
     *
     * 为什么必须换流而不是改参数：`audioType` 只在 SETUP 里出现一次，一条流建成后
     * 无法中途改变语义。车机把 media / telephony 当**两台不同的输出设备**——这也是
     * 通话在车机上没声音的根本原因（媒体流一直占着媒体通路，通话通路从没被打开）。
     *
     * 顺序沿用 [rebuildAudioChannel] 的约定：新流 SETUP 成功后才停旧流，SETUP 被拒时
     * 旧流仍在推，绝不把「本来有声音」搞成「彻底没声」。成功后补一次 RECORD，
     * 否则车机对后加入的流只给端口不排播。
     *
     * 音源**不变**：CarLife 引擎的 PCM 还是喂给 [AudioSender]，只是接收端换了输出通路
     * （[CarLifeAudioSink] 取的是 `SessionController.audioSender`，换字段即生效）。
     *
     * @return 是否真的切过去了（false = 保持原流，调用方据此决定兜底动作）
     */
    @Synchronized
    private fun switchAudioScene(
        inCall: Boolean,
        reason: String,
    ): Boolean {
        if (audioInCallMode == inCall) return true
        val client = currentClient ?: return false
        val secret = currentSharedSecret ?: return false
        val host = currentAudioHost ?: return false
        val context = appContext?.applicationContext ?: return false
        val old = audioSender ?: return false
        if (!old.isRunning) return false
        val target: AudioProfile? =
            if (inCall) {
                val candidates = client.callCandidates()
                if (candidates.isEmpty()) {
                    // 车机没声明 telephony：没有通话通路可切。这不是失败，是这台车就不支持
                    // ——硬用媒体档顶替只会得到怪声（见 AudioProfile 注释）。
                    appendLog("AUDIO: $reason → 车机未声明通话音频档（telephony），保持媒体流（通话声音仍在本机）")
                    return false
                }
                val chosen = candidates.firstOrNull { it.key != rejectedCallProfileKey } ?: candidates.first()
                // 上行能力只做**诊断**（2026-10-29）：真正的麦克风上行流要用我们自己的
                // UDP 监听端口换车机推流，端口/密钥语义与下行不同，没有车机日志无法确认
                // 方向。先把结论落到日志：有候选说明车机声明了 PCM 输入位，下一轮才有依据。
                val mic = client.microphoneCandidates()
                val micText =
                    if (mic.isEmpty()) {
                        "车机 telephony 档未声明 PCM 输入位（无上行流可建）"
                    } else {
                        mic.joinToString(" ") { it.label }
                    }
                appendLog("AUDIO: 麦克风上行能力=$micText；本次下行用 ${chosen.label}")
                chosen
            } else {
                activeAudioProfile
            }
        if (target == null) {
            appendLog("AUDIO: $reason → 没有可用的${if (inCall) "通话" else "媒体"}档，保持当前流")
            return false
        }
        appendLog(
            "AUDIO: $reason → 音频流切换 ${if (inCall) "媒体→通话" else "通话→媒体"}（${target.label}）",
        )
        val info =
            try {
                // 同理：换档（媒体↔通话）也是新流，上行端口继续声明，避免车机当手机不要麦
                client.runAudioStream(secret, target, AudioTuning.RAW_SPF, micPort = micUplink?.localPort ?: 0)
            } catch (error: Exception) {
                appendLog("AUDIO: 切换协商异常 - ${error.message}")
                null
            }
        if (info == null) {
            if (inCall) rejectedCallProfileKey = target.key
            appendLog("AUDIO: 切到 ${target.label} 失败——保持原音频流继续推（不降级成无声）")
            return false
        }
        micUplink?.updateKey(secret, info.streamConnectionId)
        // 新流已就绪：停旧流。旧流不再有包，接收端会在超时后自行回收。
        old.stop()
        audioSender = null
        val sender =
            AudioSender(
                host = host,
                dataPort = info.dataPort,
                streamKey = info.streamKey,
                log = { line -> appendLog(line) },
                context = context,
                sampleRate = info.sampleRate,
                codec = target.codec,
                streamType = target.streamType,
                adtsFrames = AudioTuning.RAW_SPF.adts,
                controlPort = info.controlPort,
            )
        audioSender = sender
        if (!sender.startPassthrough()) {
            audioSender = null
            appendLog("AUDIO: 切换后音频流启动失败——本段仅推视频（重连可恢复）")
            return false
        }
        audioInCallMode = inCall
        activeCallProfile = if (inCall) target else null
        if (!inCall) rejectedCallProfileKey = null
        // 新流是在「会话已激活」之后加入的：按 AirPlay 语义必须补发 RECORD 纳入会话，
        // 否则车机对 SETUP 回 200、给端口，但不排播。
        client.record()
        appendLog(
            "AUDIO: 音频流已切到 ${target.label}（dataPort=${info.dataPort} " +
                "controlPort=${info.controlPort}）——音源不变，只换了接收端输出通路",
        )
        // 通话档建好**之后**才告诉车机「正在通话」：顺序反了它会切到一条还没建的流上。
        if (inCall) runCatching { client.pushCallState() }
        return true
    }

    /**
     * 当前通话状态（iAP2 `CallState`），由 `AudioManager.mode` 反推。
     *
     * 只区分「正在通话」与「无通话」：这两种都不会误报。`RINGTONE` 与 `IN_CALL` 一起
     * 归为通话通路被占用——车机据此把音频路由让给 telephony 档。
     * 面板来电/去电的区分依赖 `READ_PHONE_STATE` + 通话 App，不在本层职责内。
     */
    private fun callStateOf(): Int {
        val manager =
            appContext?.applicationContext?.getSystemService(Context.AUDIO_SERVICE)
                as? android.media.AudioManager ?: return VirtualIap2Link.IAP2_CALL_STATE_NONE
        return when (manager.mode) {
            android.media.AudioManager.MODE_RINGTONE -> VirtualIap2Link.IAP2_CALL_STATE_INCOMING
            android.media.AudioManager.MODE_IN_CALL,
            android.media.AudioManager.MODE_IN_COMMUNICATION,
            -> VirtualIap2Link.IAP2_CALL_STATE_ACTIVE
            else -> VirtualIap2Link.IAP2_CALL_STATE_NONE
        }
    }


    /** 延迟 2.5 秒再重建：让车机先把通话通路的资源收干净，避免两边抢音频设备。 */
    private fun scheduleAudioRebuild() {
        val worker =
            Thread {
                try {
                    Thread.sleep(AUDIO_REBUILD_DELAY_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                runCatching { rebuildAudioChannel("通话结束 ${AUDIO_REBUILD_DELAY_MS / 1000} 秒后") }
                    .onFailure { appendLog("AUDIO: 重建异常 - ${it.message}") }
            }
        worker.isDaemon = true
        worker.name = "audio-rebuild"
        worker.start()
    }

    /**
     * 通话状态看门狗（音频会话期间每秒看一次 `AudioManager.mode`）。
     *
     * 2026-10-26 真机现象：微信来电接听后声音转到手机，挂断后车机**数据照收、`/feedback`
     * 的 sampleTime 还按 1.00 倍实时推进，就是没有声音**——说明接收端在正常消费，
     * 问题只能是「内容静音」或「接收端增益停在静音位」。
     *
     * 真 iPhone 会在会话中反复下发 `SET_PARAMETER volume`，我们只在音频 SETUP 时发过一次；
     * 车机若在通话期间把增益压到静音位，挂断后不会自己回来。所以通话结束
     * （IN_CALL / IN_COMMUNICATION / RINGTONE → NORMAL）时补两件事：
     * ① 重新核对并纠正车机音量；② 补一轮同步包重锚。两者都幂等，失败只记日志。
     */
    private fun startCallWatcher() {
        if (callWatcher?.isAlive == true) return
        val manager =
            appContext?.applicationContext?.getSystemService(Context.AUDIO_SERVICE)
                as? android.media.AudioManager ?: return
        callWatcher =
            Thread {
                var lastMode = manager.mode
                // 目标状态与连续计数：mode 在通话建立/挂断的瞬间会抖几下（IN_CALL → NORMAL
                // → IN_COMMUNICATION），只认「连续 2 秒同向」才动手，避免来回换流。
                var pendingInCall = isCallMode(lastMode)
                var pendingTicks = 0
                var idleTicks = 0
                while (true) {
                    try {
                        Thread.sleep(1_000)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    // 跟随字段而不是构造参数：换场景时 sender 会被整个换掉，
                    // 看门狗得继续盯新的那条流，否则切换后这个线程直接退出。
                    val sender = audioSender
                    if (sender == null) {
                        // 切换间隙（停旧 → 起新）也会短暂为 null：连续 3 次才算会话结束。
                        if (++idleTicks >= 3) return@Thread
                        continue
                    }
                    idleTicks = 0
                    if (!sender.isRunning) return@Thread
                    val mode = manager.mode
                    if (mode != lastMode) {
                        appendLog("AUDIO: 音频模式 ${audioModeName(lastMode)} → ${audioModeName(mode)}")
                        lastMode = mode
                    }
                    val inCall = isCallMode(mode)
                    if (inCall == audioInCallMode) {
                        pendingTicks = 0
                        continue
                    }
                    if (inCall != pendingInCall) {
                        pendingInCall = inCall
                        pendingTicks = 0
                        continue
                    }
                    if (++pendingTicks < 2) continue
                    pendingTicks = 0
                    if (inCall) {
                        appendLog("AUDIO: 进入通话（${audioModeName(mode)}）→ 换到通话音频档")
                        val switched =
                            runCatching { switchAudioScene(true, "进入通话") }.getOrElse {
                                appendLog("AUDIO: 切换通话档异常 - ${it.message}")
                                false
                            }
                        if (!switched) {
                            appendLog("AUDIO: 通话档未切上——车机侧通话通路不会打开，通话声音仍在本机")
                        }
                    } else {
                        appendLog("AUDIO: 通话结束——重新核对车机音量并补同步包（车机常停在静音位）")
                        currentClient?.refreshVolume("通话结束")
                        sender.resyncAnchor("通话结束")
                        if (audioInCallMode) {
                            val switched =
                                runCatching { switchAudioScene(false, "通话结束") }.getOrElse {
                                    appendLog("AUDIO: 切回媒体档异常 - ${it.message}")
                                    false
                                }
                            if (switched) {
                                runCatching { currentClient?.pushCallState() }
                            } else {
                                // 切不回去就按老办法重建媒体流：接收端在为通话释放过
                                // 音频输出设备后，只有新建流才会重新打开。
                                scheduleAudioRebuild()
                            }
                        } else {
                            // 没切过通话档（车机未声明 telephony / 切换失败）：仍按老办法重建。
                            scheduleAudioRebuild()
                        }
                    }
                }
            }.apply {
                isDaemon = true
                name = "audio-call-watch"
                start()
            }
    }

    private fun isCallMode(mode: Int): Boolean =
        mode == android.media.AudioManager.MODE_IN_CALL ||
            mode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
            mode == android.media.AudioManager.MODE_RINGTONE

    private fun audioModeName(mode: Int): String =
        when (mode) {
            android.media.AudioManager.MODE_NORMAL -> "NORMAL"
            android.media.AudioManager.MODE_RINGTONE -> "RINGTONE"
            android.media.AudioManager.MODE_IN_CALL -> "IN_CALL"
            android.media.AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            else -> "mode=$mode"
        }

    /**
     * 会话结束时的**音频拆链归因**（2026-10-19 Honda HFT / Alpine 实测新增）。
     *
     * 现象：选了「视频+音频」后会话能建立、视频也正常，但音频起流约 6s 后车机把整条
     * AirPlay 会话拆掉（`EVENT: 读取结束 - Connection reset`），只推视频则稳定。
     * 车机侧不会告诉我们「哪一档音频不兼容」，只能用存活时间反推：
     *  - 音频起流后**没跑满** [AUDIO_PROFILE_PROBE_MS] 就整链被拆 → 判该档位不兼容，
     *    记入本车机名单（全部档位都被判死则置 disabled，下次直接只推视频）；
     *  - 跑满了 → 记为该档位可用，下次连接直接首选。
     *
     * 注意只在「确实起过音频」时归因；纯视频会话结束不做任何记忆改写。
     */
    private fun attributeAudioTeardown() {
        val profile = activeAudioProfile ?: return
        val startedAt = activeAudioStartedAt
        val identity = activeAudioReceiverKey
        // 通话中拆链不能算到媒体档头上：这段时间上线的是通话档（audioType=telephony），
        // 两条流在接收端是不同设备，把责任记到媒体档会让它下次被跳过。
        if (audioInCallMode) {
            appendLog(
                "AUDIO: 通话档在线期间会话被拆——不归因到媒体档 ${profile.label}" +
                    (activeCallProfile?.let { "（通话档 ${it.label}）" } ?: ""),
            )
            activeAudioProfile = null
            activeAudioStartedAt = 0L
            return
        }
        // 冻结过就别怪音频档（2026-11-06 实车）：冻结期间不送帧、不回 keepAlive，车机按超时
        // 撤会话，和档位毫无关系。旧实现只看「存活 < 20s」就记名单，把刚提到首选的 48K AAC
        // 永久排到队尾，界面还会弹「该档位导致断开」——一次 3 秒冻结就能毁掉后续所有选档。
        val nowUptime = android.os.SystemClock.uptimeMillis()
        if (ProcFreeze.suspect(nowUptime)) {
            appendLog(
                "AUDIO: 会话在进程冻结后 ${ProcFreeze.sinceLast(nowUptime) / 1000}s 被拆——" +
                    "不归因到音频档 ${profile.label}（冻结期间不回 keepAlive，车机按超时撤会话；" +
                    "档位记忆保持不变）",
            )
            activeAudioProfile = null
            activeAudioStartedAt = 0L
            return
        }
        val lived = if (startedAt > 0) android.os.SystemClock.uptimeMillis() - startedAt else -1L
        // 本轮记忆很快失效：下次连接重新按最新记忆选档
        activeAudioProfile = null
        activeAudioStartedAt = 0L
        if (startedAt == 0L || identity.isBlank()) return
        val ctx = appContext?.applicationContext
        if (lived >= AUDIO_PROFILE_PROBE_MS) {
            AudioProfileMemory.recordWorking(ctx, identity, profile.key)
            appendLog("AUDIO: 档位 ${profile.label} 存活 ${lived / 1000}s——记为本车机可用档位")
            return
        }
        val allKeys = audioCandidateKeys(identity)
        AudioProfileMemory.recordRejected(ctx, identity, profile.key, allKeys)
        val after = AudioProfileMemory.load(ctx, identity)
        appendLog(
            "AUDIO: ⚠ 档位 ${profile.label} 起流 ${lived / 1000}s 后整条会话被车机拆掉——" +
                "判定为本车机不兼容档位（已记入名单）。" +
                if (after.disabled) {
                    "全部档位均不兼容：下次连接将自动只推视频（车机侧无声音）。"
                } else {
                    "下次点「启动连接」会自动换用下一档；也可直接选「开启声音（兼容档）」。"
                },
        )
        uiHintSink?.invoke(
            if (after.disabled) {
                "本车机音频不兼容，下次连接将自动只推视频"
            } else {
                "该音频档位导致断开，已记录；重连会自动换档"
            },
        )
    }

    /** 该车机上「所有可能用到的档位键」——用于判断是否已全部试穿。 */
    private fun audioCandidateKeys(identity: String): Set<String> =
        runCatching {
            val client = currentClient ?: return@runCatching emptySet()
            val preferred = client.audioCandidates(preferCompat = audioPreferCompat).map { it.key }
            val alternate = client.audioCandidates(preferCompat = !audioPreferCompat).map { it.key }
            (preferred + alternate).toSet()
        }.getOrDefault(emptySet())

    /**
     * 端点补全：优先吸收 mDNS 解析出的地址，再决定是否等待/兜底。
     *
     * 关键（2026-10-19 奥迪 4851 实车日志）：0x4301 只给了
     * `fe80::3ca8:64ff:fe7c:293e`（IPv6 链路本地），而 `_airplay._tcp` 解析出的是
     * `10.174.189.1:7000`——车机实际用 IPv4 通的。**不能因为 0x4301 带了 IPv6 就不看
     * mDNS**：那样会把唯一验证过的 IPv4 路径丢掉。这里先做一次「零等待」缓存合并，
     * 拿到 IPv4 就按 EndpointHosts 的优先级（IPv4 → IPv6）使用；缓存没命中才按需等。
     */
    private fun resolveEndpointHost(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint {
        var effective = endpoint
        val cached = endpointBrowser?.awaitEndpoint(0)
        if (cached != null) {
            effective = mergeBrowserEndpoint(effective, cached, source, needPort = effective.airPlayPort <= 0)
        }
        val needHost = !EndpointHosts.hasUsable(effective.ipAddresses)
        val needPort = effective.airPlayPort <= 0
        if (!needHost && !needPort) return effective

        phaseHint = "mDNS 解析车机地址…"
        appendLog(">>> [$source] 0x4301 端点不完整（port=${effective.airPlayPort} ips=${effective.ipAddresses}），取 mDNS 车机 _airplay._tcp…")
        // 用常驻发现器的缓存（绑网时已开始后台发现，通常此时已命中）。
        // 此前现起一次发现并硬重试 3×6s：车机 mDNS 稳定时白等，不稳定时又拖满 18s。
        // 网段过滤（防误连同网 macOS 接收器）由 browser 的 accept 统一做。
        val info =
            endpointBrowser?.awaitEndpoint(ENDPOINT_RESOLVE_WAIT_MS) ?: run {
                // 兜底：热点场景车机 IP = 网关（Audi 实测 .1，但取网关更通用）。
                // mDNS 在部分网络/机型上不稳定（2026-10-11 实测车机 mDNS 时有时无）；
                // 网关地址 + 默认端口 7000 直接连，AirPlay 端口是固定约定。
                //
                // **必须收全部默认网关，不能只取一条**：2026-11-04 奥迪 4910 实测
                // 默认路由里排第一的是 IPv6 链路本地 fe80::1086:…，只取首条就等于
                // 把唯一验证过的 IPv4 10.174.189.1 丢掉——那一轮 8 次重连全超时。
                // EndpointHosts 会按 IPv4 → 全局 IPv6 → 链路本地重排。
                val gateways = EndpointHosts.candidates(wifiConnector?.gatewayAddresses().orEmpty())
                if (gateways.isNotEmpty()) {
                    appendLog(
                        ">>> [$source] mDNS 未发现，兜底使用网关地址 ${gateways.joinToString(" / ")}:7000" +
                            "（车机热点网关=车机，${gateways.size} 个候选按 IPv4 优先）",
                    )
                    return effective.copy(
                        ipAddresses = gateways + effective.ipAddresses,
                        airPlayPort = if (needPort) 7000 else effective.airPlayPort,
                    )
                }
                appendLog(">>> [$source] mDNS 未发现车机 AirPlay 服务（车机可能尚未启动无线会话）")
                return effective
            }
        return mergeBrowserEndpoint(effective, info, source, needPort)
    }

    /**
     * 把一次 mDNS 解析结果并入端点：地址**追加到最前**（EndpointHosts 会按
     * IPv4 → 全局 IPv6 → 链路本地 IPv6 重排，顺序只影响同类型内部）。
     * 其余字段只在缺失时补（0x4301 的权威值不覆盖）。
     */
    private fun mergeBrowserEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        info: android.net.nsd.NsdServiceInfo,
        source: String,
        needPort: Boolean,
    ): Iap2SenderSession.CarPlayEndpoint {
        var result = endpoint
        val address = info.host?.hostAddress
        if (address != null &&
            EndpointHosts.candidates(listOf(address)).isNotEmpty() &&
            result.ipAddresses.none { EndpointHosts.stripScope(it) == EndpointHosts.stripScope(address) }
        ) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
            appendLog(">>> [$source] mDNS 解析到车机地址 $address:${info.port}")
        }
        if (needPort && info.port > 0) {
            result = result.copy(airPlayPort = info.port)
        }
        // TXT 公钥兜底（pair-verify 校验用；0x4301 缺失时只能从这里拿）
        if (result.publicKey == null) {
            txtValue(info, "pk")?.let { result = result.copy(publicKey = it) }
        }
        if (result.deviceIdentifier == null) {
            txtValue(info, "deviceid")?.let { result = result.copy(deviceIdentifier = it) }
        }
        if (result.sourceVersion == null) {
            txtValue(info, "srcvers")?.let { result = result.copy(sourceVersion = it) }
        }
        if (result.advertisedFeatures == null) {
            AirPlayFeatures.parse(txtValue(info, "features"))?.let {
                result = result.copy(advertisedFeatures = it)
            }
        }
        return result
    }

    /**
     * Phase 1 之后车机补发 0x4301（CarPlay 窗口晚开）时的热更新入口。
     *
     * 三种归属：
     *  - 正在推流：冗余端点，忽略（与 [onCarPlayEndpoint] 同一防护，避免拆掉健康会话）；
     *  - 交接/配对进行中：热更新当前 AirPlayClient 的端点，配对循环下一轮即用新地址；
     *    若 client 尚未创建（还在绑网/等邀请）先暂存，配对线程起步时并入；
     *  - 上一轮已失败（无会话在跑）：按新端点重走一轮完整交接——Audi MMI 0000
     *    实测 0x4301 在 120s 配对窗口耗尽后 28s 才到，旧实现把它当日志垃圾丢掉，
     *    车机等 15s 无人连入即拆链，表现为「连上后自动断开」。
     */
    private fun onLateEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        if (connectCancelled) {
            appendLog(">>> [$source] 0x4301 晚到（已取消连接）——忽略")
            return
        }
        if (videoSender?.isRunning == true) {
            appendLog(">>> [$source] 0x4301 晚到（推流中）——忽略冗余端点")
            return
        }
        val client = currentClient
        if (sessionStarting.get()) {
            if (client != null && client.pairingInProgress) {
                // 合并而非替换：0x4301 可能不带 IPv4（Audi MMI 0000 实测端点解析依赖
                // mDNS/网关），直接替换会把已解析到的地址抹掉，配对循环反而连不上。
                val merged = preferEndpoint(endpoint, client.currentEndpoint)
                val changed = client.updateEndpoint(merged, "0x4301 晚到")
                appendLog(
                    if (changed) {
                        ">>> [$source] 0x4301 晚到（配对进行中）——端点已热更新，下一轮重试用新地址"
                    } else {
                        ">>> [$source] 0x4301 晚到（配对进行中）——端点无变化，忽略"
                    },
                )
            } else {
                pendingEndpointUpdate = endpoint
                appendLog(">>> [$source] 0x4301 晚到（交接尚未创建配对客户端）——暂存，配对起步时采用")
            }
            return
        }
        // 用户已断开（BT 组件已停）时不得用晚到消息复活会话
        if (bluetoothClient?.isRunning != true && bluetoothServer?.isRunning != true) {
            appendLog(">>> [$source] 0x4301 晚到（蓝牙链路已退役）——忽略")
            return
        }
        appendLog(">>> [$source] 0x4301 晚到（上一轮已结束）——按新端点重走一轮连接")
        onCarPlayEndpoint(endpoint, "$source/0x4301 晚到")
    }

    /** 把交接期间暂存的晚到端点并入新建的客户端（建 client 与收到 0x4301 的竞态窗口）。 */
    private fun drainPendingEndpoint(client: AirPlayClient) {
        val late = pendingEndpointUpdate ?: return
        pendingEndpointUpdate = null
        if (client.updateEndpoint(preferEndpoint(late, client.currentEndpoint), "交接期间晚到 0x4301")) {
            appendLog("AIRPLAY: 交界窗口的 0x4301 端点已并入配对客户端")
        }
    }

    /**
     * 配对循环的端点刷新器（非阻塞）：只读浏览器缓存的 `_airplay._tcp`，
     * 有则并入 IP/端口/公钥等字段。返回 null = 无新信息（配对循环继续用当前端点）。
     */
    private fun enrichEndpointFromBrowser(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint? {
        val info = endpointBrowser?.awaitEndpoint(0) ?: return null
        var result = endpoint
        val address = info.host?.hostAddress
        if (address != null &&
            EndpointHosts.candidates(listOf(address)).isNotEmpty() &&
            result.ipAddresses.none { EndpointHosts.stripScope(it) == EndpointHosts.stripScope(address) }
        ) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
        }
        if (result.airPlayPort <= 0 && info.port > 0) {
            result = result.copy(airPlayPort = info.port)
        }
        if (result.publicKey == null) {
            txtValue(info, "pk")?.let { result = result.copy(publicKey = it) }
        }
        if (result.deviceIdentifier == null) {
            txtValue(info, "deviceid")?.let { result = result.copy(deviceIdentifier = it) }
        }
        if (result.sourceVersion == null) {
            txtValue(info, "srcvers")?.let { result = result.copy(sourceVersion = it) }
        }
        if (result.advertisedFeatures == null) {
            AirPlayFeatures.parse(txtValue(info, "features"))?.let {
                result = result.copy(advertisedFeatures = it)
            }
        }
        if (result == endpoint) return null
        appendLog(
            ">>> [$source] 配对期间 mDNS 刷新端点 → " +
                "${result.ipAddresses.firstOrNull()}:${result.airPlayPort}",
        )
        return result
    }

    private fun txtValue(
        info: android.net.nsd.NsdServiceInfo,
        key: String,
    ): String? =
        info.attributes[key]
            ?.toString(Charsets.UTF_8)
            ?.trimEnd { it.code == 0 }
            ?.takeIf { it.isNotEmpty() }

    /**
     * 晚到端点优先：车机明确告知的端口/公钥优先于 mDNS 合成值，
     * 但 IP 列表取并集（0x4301 可能不带 IPv4，Audi MMI 0000 实测即如此）。
     */
    private fun preferEndpoint(
        late: Iap2SenderSession.CarPlayEndpoint,
        fallback: Iap2SenderSession.CarPlayEndpoint,
    ): Iap2SenderSession.CarPlayEndpoint =
        late.copy(
            airPlayPort = if (late.airPlayPort > 0) late.airPlayPort else fallback.airPlayPort,
            ipAddresses = (late.ipAddresses + fallback.ipAddresses).distinct(),
            publicKey = late.publicKey ?: fallback.publicKey,
            deviceIdentifier = late.deviceIdentifier ?: fallback.deviceIdentifier,
            sourceVersion = late.sourceVersion ?: fallback.sourceVersion,
            advertisedFeatures = late.advertisedFeatures ?: fallback.advertisedFeatures,
        )

    /**
     * 初始 SETUP（带重连重试）。
     *
     * 已配对路径：配对循环刚连通过，SETUP 一次即可（失败就是真失败，交给降级/收尾）。
     * 明文会话路径：车机的 AirPlay 端口可能要到「用户点车机图标」后才开，
     * catplay 同样是「收到邀请才连」；这里在 [INITIAL_SETUP_WINDOW_MS] 内每 2s
     * 重连一次并重试 SETUP，避免端口晚开 1~2 秒就整轮失败。
     */
    private fun runInitialSetup(
        client: AirPlayClient,
        plainSession: Boolean,
        noPairing: Boolean,
    ): AirPlayClient.SetupInfo? {
        val first = client.runSetup()
        if (first != null || !plainSession) return first
        val deadline = android.os.SystemClock.uptimeMillis() + INITIAL_SETUP_WINDOW_MS
        var attempt = 1
        while (android.os.SystemClock.uptimeMillis() < deadline && !connectCancelled) {
            attempt++
            appendLog("AIRPLAY: 明文 SETUP 第 ${attempt - 1} 次未通过——2s 后重连重试（车机 AirPlay 端口可能尚未开放）")
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                return null
            }
            if (connectCancelled) return null
            client.close()
            if (!client.reconnect(2_000)) continue
            val retried = client.runSetup()
            if (retried != null) {
                appendLog("AIRPLAY: ✓ 明文 SETUP 在第 $attempt 次尝试成功")
                return retried
            }
            // 端口开了但 SETUP 仍被拒：TXT 判断可能错了，交给调用方的加密升级路径
            if (!noPairing) return null
        }
        return null
    }

    /**
     * 连上就立刻给反馈：会话已激活、但 CarLife 还没出画面时，**马上**把提示推到车机屏幕。
     *
     * 2026-11-06 用户要求：不要对着黑屏等 20 秒首帧门超时才发现「原来是 CarLife 没在出画面」。
     *
     * 提示帧本身是一帧 H.264（SPS/PPS+IDR，见 [VideoSender.pushNotice]），推上去之后空闲保活
     * 会每秒重发它——所以文字会一直挂在车机上，直到 CarLife 的真实 IDR 到达（真实帧会替换
     * `lastIdrNals`，提示自动消失）。
     *
     * 判据用 [videoSessionFrameBase]（attach **之前**取的累计送达数）：
     *  - 本次会话一帧都没送出去 → 提示；
     *  - 缓存回放/真实帧已经有增长 → 不提示（否则会拿提示帧盖掉刚显示的页面）。
     */
    private fun startCarlifeWaitNotice() {
        Bg.run("carlife-wait-notice") {
            // 先给「缓存回放 / 引擎首帧」一点时间：正常车机 <1s 就有画面，不能一上来就盖提示
            sleepQuietly(NOTICE_FIRST_DELAY_MS)
            if (connectCancelled) return@run
            var pushed = 0
            while (pushed < NOTICE_MAX_PUSHES) {
                if (connectCancelled || videoSender?.isRunning != true) return@run
                if (CarLifeHijack.framesDelivered > videoSessionFrameBase) {
                    if (pushed > 0) {
                        appendLog("CARLIFE: 画面已接上——车机侧提示帧会被真实画面替换")
                    }
                    return@run
                }
                // 两种「没画面」要说清：源在出帧但没送达（链路/配置），还是源压根没出帧
                // （CarLife 没进无线连接）。用 sourceActive（带新鲜度）而不是 isStreaming。
                val lines =
                    if (CarLifeHijack.sourceActive) {
                        listOf("已连接，但车机还没收到画面", "请在车机上重连或重启 CarLife+ 无线连接")
                    } else {
                        listOf("已连接，但没有 CarLife 画面", "请在手机上打开 CarLife+ →「无线连接」")
                    }
                val sent = videoSender?.pushNotice(lines) == true
                pushed++
                carlifeNoticePushedAt = android.os.SystemClock.uptimeMillis()
                if (pushed == 1) {
                    appendLog(
                        "STAGE: 车机已连接但还没有 CarLife 画面——已立即推送提示帧到车机屏幕" +
                            (if (sent) "" else "（送出失败，仅日志兜底）") +
                            "；CarLife 出画面后提示会被真实帧替换",
                    )
                }
                sleepQuietly(NOTICE_REPEAT_MS)
            }
        }
    }

    /**
     * 首帧门：SETUP 成功之后等首帧，超时**只报告不拆链**。
     *
     * 等的是「车机真的收到首帧」（`CarLifeHijack.framesDelivered` 相对本次会话起点有增长），
     * **不是** `CarLifeHijack.isStreaming`（CarLife 单方面在推流）。
     * 后者为 true 只代表 CarLife 把帧扔进了劫持层，不代表帧进了车机通道——一旦 SPS/VideoConfig、
     * 重连等待新 IDR、或 socket 任一处卡住，`submitH264` 会返回 false，车机永远等不到画面，
     * 但 isStreaming 早已为 true。旧逻辑只判断 isStreaming，于是「连接上却一直等视频」时立刻
     * return，把故障当成正常，也不弹任何提示（用户实测：CarPlay 已连、App 显示「已劫持」、
     * 车机却一直等视频、且不提示「未劫持」）。
     *
     * 注意 framesDelivered 是跨会话累加、从不归零的，必须用「本次会话起点」的差值判断，
     * 否则会把上一次成功会话的帧数误当成已送达（CarLifeHijack 侧的 startFirstFrameWatchdog
     * 也是同一套 baseline 思路）。
     *
     * 为什么不拆：拆链由视频看门狗与冻结恢复负责，再叠一套超时就是两套语义互相打架
     * （这台项目历史上反复踩过）。这里要的只是「把没有画面这件事说清楚」——
     * 旧日志在「SETUP 成功」之后就断片了，到底是黑屏、还是 CarLife 没在投，
     * 事后完全无从判断（2026-11-01 日志 090105 就缺这一段）。
     */
    private fun startFirstFrameGate() {
        Bg.run("first-frame-gate") {
            // 基准取 attach **之前**的累计送达数（videoSessionFrameBase）：缓存回放成功时
            // framesDelivered 在 attach 里已经涨过，若在这里重新取基准，会把「已经回放了画面」
            // 误判成「20s 没帧」，然后用提示帧把真实画面盖掉。
            val base = videoSessionFrameBase
            val budget = ConnectStage.FIRST_FRAME.budgetMs
            val deadline = android.os.SystemClock.uptimeMillis() + budget
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                if (CarLifeHijack.framesDelivered > base) {
                    connectBudget.markStreaming()
                    return@run
                }
                // 只在「会话被整体收掉」（retireSession 会把 videoSender 置空）或用户取消时退出；
                // 不用 isRunning 当条件——它在这条通道刚起时可能还是 false，会让门直接失效。
                if (connectCancelled || videoSender == null) return@run
                sleepQuietly(250)
            }
            if (CarLifeHijack.framesDelivered > base) {
                connectBudget.markStreaming()
                return@run
            }
            // 超时仍没有任何帧送进车机通道。按「源侧最近是否真出过帧」区分两种失败
            // （sourceActive 带新鲜度；不用 isStreaming——hasVideo 一旦置位就挂着，
            // 源停摆时会误判。判定一律以真实视频流为准，不认 CarLife 进程状态）：
            //  - 源没出帧：CarLife 没劫持到画面，沿用原提示；
            //  - 源在出帧但帧没送达：连接已好、CarLife 也在投，是链路/配置问题
            //    （典型如 SPS/VideoConfig 卡住或重连后等不到新 IDR，submitH264 返回 false）。
            val carlifePushing = CarLifeHijack.sourceActive
            val noticeLines = if (carlifePushing) {
                listOf("已连接但车机未收到画面", "请在车机重连或重启 CarLife+ 无线连接")
            } else {
                listOf("未劫持到手机车联画面", "请在手机 CarLife+ 进入「无线连接」")
            }
            // 「没有 CarLife 画面」的提示已经由 startCarlifeWaitNotice 在**连上那一刻**推过了
            // （2026-11-06 用户要求：不要对着黑屏等 20 秒）。这里只在确实没提示过时才补推，
            // 避免两套文案互相覆盖、并把提示帧反复刷新。
            val recentlyNoticed =
                android.os.SystemClock.uptimeMillis() - carlifeNoticePushedAt < NOTICE_RECENT_MS
            val pushed =
                !recentlyNoticed && videoSender?.isRunning == true &&
                    (videoSender?.pushNotice(noticeLines) ?: false)
            appendLog(
                "STAGE: ⚠ ${ConnectStage.FIRST_FRAME.label}超时（${budget / 1000}s 内车机未收到本会话任何视频帧，" +
                    "CarLife推流=$carlifePushing，累计送达=${CarLifeHijack.framesDelivered - base} 帧）——" +
                    (if (carlifePushing) {
                        "CarLife 在推流但帧未送达车机（检查 SPS/VideoConfig 或重连车机）"
                    } else {
                        "画面来自本机 CarLife+：确认它处于「无线连接」且引擎在推流（7240）"
                    }) +
                    "；会话本身已建立，不需要重连车机" +
                    when {
                        pushed -> "；已向车机屏幕推送提示"
                        recentlyNoticed -> "；提示帧已在连上时推送（本次不重复）"
                        else -> ""
                    },
            )
            if (pushed) {
                // 车机偶尔会丢掉首帧：每 5s 重推一次，最多 60s；本会话一旦收到帧或会话结束即停。
                var repeats = 0
                while (videoSender?.isRunning == true &&
                    CarLifeHijack.framesDelivered <= base &&
                    !connectCancelled &&
                    repeats < 12
                ) {
                    sleepQuietly(5_000)
                    if (CarLifeHijack.framesDelivered > base || connectCancelled) break
                    val linesNow = if (CarLifeHijack.sourceActive) {
                        listOf("已连接但车机未收到画面", "请在车机重连或重启 CarLife+ 无线连接")
                    } else {
                        listOf("未劫持到手机车联画面", "请在手机 CarLife+ 进入「无线连接」")
                    }
                    videoSender?.pushNotice(linesNow)
                    repeats++
                }
            }
        }
    }

    /**
     * 屏幕接管门：首帧真的送进车机之后，看车机有没有「反应」。
     *
     * 判据来自 4580 固件的显示契约（`docs/mib3-carplay-display-contract.md`、
     * `docs/carplay-no-projection-conditions.md` 门 5）：手机发帧 → 车机解码
     * （`Video state=active`）→ 资源仲裁 → HMI 交屏。车机在解不出画面时会主动回落
     * 事件命令：`forceKeyFrame`（要 I 帧）、`changeModes`（抢/让资源）、`requestUI`。
     *
     * 2026-11-06 奥迪 CL3D（AUDI 3810）那轮实车：帧 20fps 连送 37 秒、
     * SPS = 车机画布 = 1412×654、RECORD 与 MFi 全绿，车机**一条事件命令都没发过**，
     * 屏依旧全黑。也就是说问题不在手机侧的码流/分辨率/VideoConfig 头——那条线今天
     * 四种组合全试过了。把这条结论自动写进日志，下一轮就不用再靠人工比对推。
     *
     * 对照组（为什么「零事件」可以当判据）：2026-11-03 奥迪 Q4（`AUDI 3162` / DELPHI，
     * 1.2.18-switchpool，出画面）那场会话在同一条事件通道上收到过 `changeModes`×5、
     * `requestUI`×2——车机真接管屏幕时是会说话的。
     *
     * 只观察、不改状态：真没有画面时收尾仍由视频看门狗与冻结恢复负责（这里不拆链，
     * 拆链由两套超时管着就够了，历史上叠第三套必打架）。
     *
     * 判据从此有两条腿：**事件通道有没有回落** + **车机侧实收帧率**（`/feedback` 的
     * `statsDictionary.rfps`，见 `AirPlayReceiverStats`）。两条一起看才能分清
     * 「帧根本没到车机」「到了但没解码」「解码了但 HMI 没交屏」。
     */
    private fun startScreenHandoverGate(client: AirPlayClient) {
        Bg.run("screen-handover-gate") {
            val frameBase = CarLifeHijack.framesDelivered
            val waitDeadline = android.os.SystemClock.uptimeMillis() + HANDOVER_FIRST_FRAME_WAIT_MS
            while (android.os.SystemClock.uptimeMillis() < waitDeadline) {
                if (connectCancelled || videoSender == null) return@run
                if (CarLifeHijack.framesDelivered > frameBase) break
                sleepQuietly(250)
            }
            val delivered = CarLifeHijack.framesDelivered - frameBase
            // 首帧都没送到：这是「帧没进车机」而不是「车机不认屏流」，
            // first-frame-gate 的分类比这里准，交给它，不重复刷屏。
            if (delivered <= 0) return@run
            val eventsAtFrame = carEventCount
            sleepQuietly(HANDOVER_QUIET_MS)
            if (connectCancelled || videoSender?.isRunning != true) return@run
            val events = carEventCount - eventsAtFrame
            // 车机侧实收帧率（可能为 null：固件不回报统计）
            val receive = client.screenReceiveSummary()
            val receiveLine = receive ?: "车机未回报收帧统计（该固件没有 statsDictionary）"
            if (events > 0) {
                appendLog(
                    "STAGE: 车机接管屏幕有回落信号（首帧后 ${HANDOVER_QUIET_MS / 1000}s 内 ${events} 条事件命令：" +
                        carEventTypes.joinToString("、") + "；$receiveLine）",
                )
                return@run
            }
            // 零事件时再用收帧率把两种完全不同的故障切开：
            //  - 车机没收到帧（rfps 缺失/0）→ 传输/加密/数据口问题，看我们的 VideoSender；
            //  - 车机收到了却不说话（rfps≈我们送的帧率）→ 解码/HMI 交屏，看车机侧日志。
            val verdict =
                when {
                    receive == null ->
                        "⚠ 车机没回报收帧统计，无法判断帧是否到达；先看 `VIDEO: 送帧统计` 与车机侧日志"
                    client.screenReceiveFps == null || (client.screenReceiveFps ?: 0.0) < 1.0 ->
                        "⚠ 车机侧实收帧率≈0：帧没进车机前级（数据口写入成功≠车机收到）——查加密/数据口/端口"
                    else ->
                        "车机确实在收帧（$receiveLine）却不回落任何命令：帧到了但没被当屏流解码/交屏，" +
                            "**不是手机侧码流/分辨率问题**——别再调 VideoConfig"
                }
            appendLog(
                "STAGE: ⚠ 车机对画面无任何回落信号（首帧后 ${HANDOVER_QUIET_MS / 1000}s 内事件通道 0 条命令，" +
                    "已送达 $delivered 帧 ${activeStreamWidth}×${activeStreamHeight}；$receiveLine）\n" +
                    "  ↳ $verdict。\n" +
                    "  ↳ 对照：2026-11-03 奥迪 Q4（AUDI 3162）出画面那轮，同一条通道上收到过 " +
                    "changeModes×5、requestUI×2；要抓的是车机侧 CDSICarplayImpl 的 " +
                    "`<SCREEN> State changed` / `Video state=active|inactive` / " +
                    "`Failed to overtake the screen: \"<reason>\"`",
            )
        }
    }

    /** BT 会话交接点：拿到 0x5703/0x4301 后两条路径流程一致——绑 WiFi → AirPlay 配对 → SETUP → 推流。 */
    private fun onCarPlayEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        val context = appContext ?: return
        // 冗余端点防护：接收端控制器（DiPlay 实测）在我们配对推流后才完成既有的
        // RFCOMM 重连并重发 0x4301。此时重启配对会 retireSession 掉健康会话
        // （编码器 stop 竞态 = 「连上后自动断开」循环的根因）。推流中的端点一律忽略；
        // 真断连时推流自行终止（isRunning=false），下一个端点自然接续重连。
        if (videoSender?.isRunning == true) {
            appendLog(">>> [$source] 会话推流中——忽略冗余端点（接收端周期重连的重复 0x4301）")
            return
        }
        if (!sessionStarting.compareAndSet(false, true)) {
            appendLog(">>> [$source] 已有会话在处理中，忽略本次端点（等待上一轮结束）")
            return
        }
        // 新轮次起步清取消标志：上一轮可能因「BT 链路断开」置位退出（见
        // onSenderLinkClosed）——能走到这里说明 BT 组件仍在运行（用户取消路径会
        // 停组件、被上方守卫拦截），该标志属于已退场的旧轮次，不清会误伤本轮。
        connectCancelled = false
        // 新一轮交接开始：清掉上一轮（可能中途失败）遗留的晚到端点暂存，
        // 否则它会在本轮的 drain 阶段覆盖掉本会话刚解析出的端点。
        pendingEndpointUpdate = null
        // 端点已经就绪：此前「邀请先到、端点未就绪」挂起的信号由本轮接续，
        // 清掉标记避免它跨轮误触发下一次会话（catplay cache_miss 只在本次邀请生效）。
        if (pendingInviteAtMs != 0L) {
            pendingInviteAtMs = 0L
            appendLog(">>> [$source] 端点晚到——接续此前挂起的车机邀请（邀请先到 cache_miss 已自愈）")
        }
        // 阶段计时清零：从这里开始算「这次连接一共多久」。
        connectBudget.begin(source)
        // 新一轮推流：邀请计数归零（日志里"本流第 N 次"才读得懂）。
        // 注意**不清 [lastInviteRebuildAt]**：防抖必须跨会话生效，否则重建出来的新会话
        // 一收到邀请就再拆一次，会变成"永远在重建、永远出不了画面"。
        streamingInviteCount = 0
        lastStreamingInviteAt = 0L
        // 空端点 = 新协议车机（GM/Cinemo 等）声明会发 0x4301 但窗口内未到。
        // Iap2SenderSession 已不再为此拆链，保活泵会把晚到的 0x4301 经 onLateEndpoint
        // 送回本函数重走完整流程。这里**不占 sessionStarting**（晚到端点要走「上一轮
        // 已结束」分支），改为进入等待态：期间轮询手机 WiFi，用户手动连上车机热点
        // （密码在车机设置里可见，凯迪拉克 CT5 实测）后确认即可用该 SSID 合成端点，
        // 走 mDNS/网关兜底路径；超时再拆链让重连循环接管。
        if (endpoint.ssid.isEmpty()) {
            appendLog(
                ">>> [$source] 端点无 AP 信息（车机未在窗口内下发 0x4301）——蓝牙保持保活等待晚到 0x4301；" +
                    "检测到手机已连 WiFi 时会询问是否按车机热点继续",
            )
            phaseHint = "等待车机下发连接信息（0x4301）…"
            sessionStarting.set(false)
            watchForPendingStartSession(endpoint, source)
            return
        }
        appendLog(
            ">>> [$source] 拿到接收端热点：SSID=${endpoint.ssid}；" +
                "密码=${if (endpoint.passphrase.isEmpty()) "(空)" else "已获取"}",
        )
        // 记下这一轮的完整端点：进程被系统冻结后车机不会再补发 0x4301，
        // 解冻恢复只能靠这份快照重走交接（见 onProcessResumed）。
        lastGoodEndpoint = endpoint
        // 认证归属：先按端点算一次（配对前就失败的轮次——没入网、端口没开——也要能计数，
        // 否则「连不上两次」这类最常见的失败根本进不了认证重做的判据）。
        // 口径与配对阶段一致（[PairingMemory.receiverKey]），那边拿到 mDNS 的 pk 后会覆盖。
        authReceiverKey =
            PairingMemory.receiverKey(endpoint.publicKey, endpoint.deviceIdentifier, endpoint.ssid)
        phaseHint = "蓝牙已连上车机，解析热点地址…"
        ssidSink?.invoke(endpoint.ssid)
        // 0x4301 同样带热点密码（BMW iD9 不发 0x5703，AP 信息只走这条路）。
        // 不缓存的话首页「WiFi」按钮会判成「尚未拿到热点密码」，用户连手动加入
        // 都做不了——2026-10-29 日志里连着出现三次这条误导性提示，根因就是这里。
        if (endpoint.passphrase.isNotEmpty()) lastWifiPsk = endpoint.passphrase
        // 诊断（老车机排查必看）：地址是 IPv4 还是只有 IPv6、TXT features 是否声明配对能力。
        // 奥迪 4xxx 两种「连不上」都源于这里——旧版日志里完全没有这两项。
        val addresses = endpoint.ipAddresses
        val hasIpv4 = addresses.any { EndpointHosts.isIpv4(it) }
        // 车机画像（信号收口点）：mDNS 侧信号到达——era 判定 + wifiGen 沿用 0x1D01 的结论
        // （本处没有收/发清单，classify 会给 UNKNOWN；代际快照的 merge 负责「保留已知」，
        //  所以同一台车「先 0x1D01 后 mDNS」的到达顺序不会被误判成换代）。
        val endpointProfile =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    features = endpoint.advertisedFeatures,
                    srcvers = endpoint.sourceVersion,
                ),
            )
        val pairingPolicy = endpointProfile.pairingPolicy
        appendLog(
            ">>> [$source] 端点诊断：地址=${addresses.ifEmpty { listOf("(无)") }}" +
                "（${if (hasIpv4) {
                    "IPv4"
                } else if (addresses.isEmpty()) {
                    "无地址"
                } else {
                    "仅 IPv6"
                }}）" +
                " features=" + (endpoint.advertisedFeatures?.let { "0x${it.toString(16)}" } ?: "未知") +
                " HomeKit配对=" + pairingPolicy.label,
        )
        appendLog(ReceiverProfile.describe(endpointProfile))
        // 一句话分派结论：0x1D01 与 mDNS 两个信号都到齐了，这时才说得清「本轮按哪一代跑」
        appendLog(ReceiverProfile.dispatchSummary(endpointProfile))
        // 代际快照（mDNS 侧）：AirPlay 特性位翻转（例如换固件后开始/停止声明 bit38）
        // 同样算换代——必须重算并作废旧 MFi 档，否则新一轮会卡在上代参数上。
        ProtocolGeneration
            .observe(
                appContext,
                endpointProfile.identityKey,
                endpointProfile,
                srcvers = endpoint.sourceVersion,
                features = endpoint.advertisedFeatures,
            )?.let { change ->
                appendLog(ProtocolGeneration.describe(change))
                ProtocolAudit.record(
                    ProtocolAudit.Code.ERA_SWITCHED,
                    detail = "${change.from.era}/${change.from.wifiGen}" +
                        " → ${change.to.era}/${change.to.wifiGen}",
                    evidence = "mDNS features=${change.to.featuresHex ?: "未知"} srcvers=${change.to.srcvers ?: "未知"}",
                )
            }
        // 先绑网络再解析端点：mDNS 组播跟随 bindProcessToNetwork 的网络，解析放前面
        // 只会拿到旧网络的结果（此前靠 resolveEndpointHost 内部重试掩盖，白等数秒）。
        // 绑定完成后再并行做三件事：ctrl 广播针对新网络重注册、后台发现 _airplay._tcp、
        // 等车机 ctrl 邀请（车机已发现我们的权威信号）。
        val continueWithNetwork: (Boolean) -> Unit = proceed@{ bound ->
            if (!bound) {
                phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${endpoint.ssid}"
                appendLog(">>> [$source] 手机当前不在该热点——请到 设置>WLAN 加入 \"${endpoint.ssid}\" 后断开重连")
                // 这一轮到此为止（没有配对线程会接手），必须自己收尾，否则只有「本轮开始」。
                connectBudget.end(
                    cause = "手机没加入车机热点 \"${endpoint.ssid}\"",
                    keywords = "手机当前不在该热点 / 入网",
                )
                sessionStarting.set(false)
                return@proceed
            }
            // 网络绑定完成：ctrl 广播重新注册（落到车机所在网络），同时开始后台 mDNS 发现。
            // 两者都发生在等邀请期间，不额外占用时间。
            ctrlAdvertiser?.ensureForNetwork(endpoint.ssid, force = true, network = wifiConnector?.currentNetwork)
            endpointBrowser?.resetCache()
            endpointBrowser?.start()
            // 0x4301 自带完整地址+端口 = 接收端已明确告知「连这里」，无需等邀请
            // （DiPlay 测试台路径：本轮直接配对，省掉固定等待）。
            // IPv6 也算地址：奥迪 4xxx 的 0x4301 常只给 fe80::（旧判据只看 IPv4，
            // 会把这类端点当成「不完整」，白等 ctrl 邀请再退回网关兜底）。
            val endpointSelfContained =
                endpoint.airPlayPort > 0 && EndpointHosts.hasUsable(endpoint.ipAddresses)
            Bg.run("airplay-pairing") {
                try {
                    if (connectCancelled) {
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        sessionStarting.set(false)
                        return@run
                    }
                    if (endpointSelfContained) {
                        appendLog("AIRPLAY: 0x4301 端点完整（${endpoint.ipAddresses.firstOrNull()}:${endpoint.airPlayPort}）——直接配对，不等 ctrl 邀请")
                    } else {
                        // 等 ctrl 邀请（catplay CarPlayCtrlServer：邀请=车机已发现手机且
                        // AirPlay 窗口已开，收到即刻连接）。此前是「固定睡 1.6s 赌它已到」，
                        // 快的时候白等、慢的时候不够；改为事件驱动。
                        awaitCtrlInvite(endpoint.ssid, CTRL_INVITE_WAIT_MS)
                    }
                    // 端点解析放在邀请之后：车机的 `_airplay._tcp` 常在受邀后才上线，
                    // 且这一步最长 3s——必须在后台线程做，不能占住网络回调线程。
                    var effectiveEndpoint = resolveEndpointHost(endpoint, source)
                    // 交接流程起步期间车机补发的 0x4301（比 mDNS 合成更权威）在此并入。
                    pendingEndpointUpdate?.let { late ->
                        pendingEndpointUpdate = null
                        effectiveEndpoint = preferEndpoint(late, effectiveEndpoint)
                        appendLog(
                            "AIRPLAY: 并入交接期间晚到的 0x4301 端点 → " +
                                "${effectiveEndpoint.ipAddresses.firstOrNull()}:${effectiveEndpoint.airPlayPort}",
                        )
                    }
                    if (connectCancelled) {
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        sessionStarting.set(false)
                        return@run
                    }

                    // 车机没起 AirPlay 服务时的「叫醒」：端口连续拒绝 → 重发 0x4e0e + 0x4300
                    // 并重新公告 ctrl。限 3 次、每次间隔 ≥10s。
                    // 2026-11-06 19:31 现场：凭据拿到、入网成功、0x4300 也发了，但 7000 连续
                    // 15 次 ECONNREFUSED、ctrl 邀请 0 次、0x4301 不回——整轮 39 秒除了重试日志
                    // 什么都没做，车机也就一直停在「等 IAP2 信息」里没起投影。
                    var wakeCount = 0
                    var lastWakeAt = 0L
                    val wakeCarPlayWindow: (Int) -> Unit = { refused ->
                        val now = android.os.SystemClock.uptimeMillis()
                        if (wakeCount < CARPLAY_WAKE_MAX_TIMES && now - lastWakeAt >= CARPLAY_WAKE_GAP_MS) {
                            lastWakeAt = now
                            wakeCount++
                            val reason = "7000 已连续拒绝 $refused 次"
                            val nudged = bluetoothServer?.nudgeCarPlayAvailability(reason) == true ||
                                bluetoothClient?.nudgeCarPlayAvailability(reason) == true
                            ctrlAdvertiser?.ensureForNetwork(
                                endpoint.ssid,
                                force = true,
                                network = wifiConnector?.currentNetwork,
                            )
                            appendLog(
                                "AIRPLAY: 7000 已连续拒绝 $refused 次（车机没起 AirPlay 服务）——" +
                                    "第 $wakeCount/$CARPLAY_WAKE_MAX_TIMES 次叫醒：" +
                                    (if (nudged) "重发 0x4e0e+0x4300 + " else "iAP2 会话已不在，只能") +
                                    "重新公告 ctrl；若仍不开，请在车机上点本机图标或重启 CarPlay",
                            )
                        }
                    }

                    fun newClient() =
                        AirPlayClient(
                            effectiveEndpoint,
                            log = { line -> appendLog(line) },
                            context = context.applicationContext,
                            endpointRefresher = { current -> enrichEndpointFromBrowser(current, source) },
                            onPortRefused = wakeCarPlayWindow,
                            retryTick = { attempt ->
                                // 配对重试期间车机仍可能没探测到 ctrl（Bonjour 漏掉首次公告）：
                                // 周期性强制重播一次，并把 mDNS 现状写进日志——否则排查时
                                // 只看得到「端口未开」的重试刷屏，无法判断是车机没邀请还是
                                // 地址不对（2026-10-19 奥迪 0.1.7 日志就缺这两条信息）。
                                ctrlAdvertiser?.ensureForNetwork(
                                    endpoint.ssid,
                                    force = true,
                                    network = wifiConnector?.currentNetwork,
                                )
                                val invites = ctrlAdvertiser?.inviteCount ?: 0
                                val found = endpointBrowser?.awaitEndpoint(0)
                                appendLog(
                                    "AIRPLAY: 重试 #$attempt —— 已重新公告 ctrl；" +
                                        "车机邀请=$invites 次，mDNS _airplay._tcp=" +
                                        (found?.let { "${it.host?.hostAddress}:${it.port}" } ?: "未发现"),
                                )
                            },
                        ).also { created ->
                            // 车机媒体键（iAP2 0x6802 通路）：方向盘/面板的上一曲、下一曲。
                            // 放在这里而不是调用点，保证重连重建 client 时也挂得上。
                            created.onMediaKey = { keyCode ->
                                if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
                                    // 少数车机确实把 Voice Command 放在 HID 0x6802 的
                                    // Consumer 0xCF 上；留作兜底，和 requestSiri 共用一个状态机。
                                    voiceKey.onHidDown("iAP2 0x6802 0xCF")
                                } else {
                                    dispatchMediaKey(keyCode)
                                    appendLog("EVENT: 车机媒体键（iAP2 0x6802）→ keyCode=$keyCode 已注入")
                                }
                            }
                            // 0x6802 是状态式报告：松手时给空集合，语音键长按靠这个补 VR_STOP。
                            created.onHidKeysReleased = { usages ->
                                if (usages.any { ConsumerKeys.keyCodeFor(it) == KeyEvent.KEYCODE_VOICE_ASSIST }) {
                                    voiceKey.onHidUp("iAP2 0x6802 0xCF")
                                }
                            }
                            // 车机定位（0xFFFB）→ CarLife 0x18010。只在开关打开且
                            // 车机真的给了经纬度时才发（见 onCarLocation）。
                            created.onLocationSamples = { sentences -> onCarLocation(sentences) }
                            // 新一轮会话：清掉上一轮的定位，别把停车前的位置当成本次定位；
                            // 语音键的按下状态也别跨会话带过来。
                            carNmea.reset()
                            carGpsNoFixFrames = 0
                            voiceKey.reset()
                            // 0x5001 NowPlayingUpdate 的数据源：优先系统媒体会话，兜底 CarLife
                            // 0x10035。读不到（null）时虚拟链路自动退化为空态。
                            created.nowPlayingSource = { buildNowPlaying() }
                            // 0x4155 CallStateUpdate 的数据源：车机订阅通话状态时按真实值应答，
                            // 否则它认定这台手机永远不打电话，通话时不切音频通路。
                            created.callStateSource = { callStateOf() }
                        }
                    retireSession()
                    // 会话静默降噪（2026-10-10）：从交接点起暂停其它接收端的出站连接探测
                    // （典型：连着 DiPlay 测试台时不再噪音式探测 Audi）。会话结束/断开时解除。
                    BtSessionGate.sessionActive = true
                    // 第一阶段：保持 BT 在位直接配对 + SETUP（协议上正确的形态）
                    phaseHint = "AirPlay 配对中…"
                    connectBudget.enter(ConnectStage.PAIRING)
                    var client = newClient()
                    currentClient = client
                    drainPendingEndpoint(client)
                    // 候选地址全打出来：0x4301 与 mDNS 给的可能既不同族也不同值，
                    // 两者都只是候选，**身份判定靠 pair-verify 的 pk 签名**。连错屏/连不上时
                    // 这一行就是唯一能看出「按什么顺序试了哪些地址」的证据。
                    appendLog(
                        "AIRPLAY: 连接候选 ${EndpointHosts.describeCandidates(client.currentEndpoint.ipAddresses)}" +
                            "（身份判定只看 pk，与地址无关）",
                    )
                    // pre-HomeKit 老车机（CarPlay 210.81 时代，奥迪 4xxx 固件）在
                    // `_airplay._tcp` 里**不声明** HK_PAIRING_AND_ENCRYPT：它们只接受
                    // 明文 SETUP（et=NONE）+ AES-CTR 屏幕流，走 pair-setup 必然失败。
                    // catplay 的接收端分类 1 即此形态；旧实现会在这里空转 120s 配对窗口，
                    // 用户看到的就是「连不上」。
                    //
                    // **判据只认画像**（[ReceiverProfile.PairingPolicy]）：端点可能在配对前
                    // 被 0x4301 热更新过，所以按当前端点重新分类一次；不要再这里直接读 bit38，
                    // 否则「画像说老车机、这里说新」的下一处分叉迟早出现（SPEC §3.3）。
                    val pairingPolicy =
                        ReceiverProfile
                            .classify(
                                ReceiverProfile.Signals(
                                    features = client.currentEndpoint.advertisedFeatures,
                                    srcvers = client.currentEndpoint.sourceVersion,
                                ),
                            ).pairingPolicy
                    val txtNoPairing = pairingPolicy == ReceiverProfile.PairingPolicy.PLAINTEXT_ONLY
                    // 短期记忆：上次被车机确定性拒绝配对（Error=2 这类），本轮直接跳过
                    // pair-setup——它会一直回同一个码，120s 窗口纯属白等（日志 221747）。
                    // 记忆只影响「要不要再试一次重复动作」，不影响明文会话，也不影响
                    // 明文 SETUP 失败后的补配对（见下方 retryWithPairing）。
                    val pairingReceiverKey =
                        PairingMemory.receiverKey(
                            client.currentEndpoint.publicKey,
                            client.currentEndpoint.deviceIdentifier,
                            endpoint.ssid,
                        )
                    authReceiverKey = pairingReceiverKey
                    freshAuthPolicy.onAttempt(pairingReceiverKey)
                    // 连续两轮没建立起会话 → 本轮认证重做：先清掉「上次被车机确定性拒绝」
                    // 的记忆，再强制走完整 pair-setup。复用网络只代表 L2 通了，不代表车机
                    // 还是上次那个状态（它可能已删掉本机配对记录 / 自己的状态机复位过）。
                    val redoAuth = freshAuthPolicy.consumeRedo(pairingReceiverKey)
                    if (redoAuth) {
                        PairingMemory.clear(context)
                        appendLog(
                            "AUTH: 同一车机连续 ${FreshAuthPolicy.DEFAULT_FAILURE_THRESHOLD} 轮未建立会话" +
                                "——本轮强制重做认证（清掉配对记忆，不再跳过 pair-setup）",
                        )
                    }
                    val pairingGate =
                        if (redoAuth) {
                            PairingGate.NORMAL
                        } else {
                            PairingMemory.decide(
                                PairingMemory.read(context),
                                pairingReceiverKey,
                                System.currentTimeMillis(),
                            )
                        }
                    val noPairing = txtNoPairing || pairingGate == PairingGate.SKIP_SETUP
                    appendLog(
                        "PROTO 配对策略=$pairingPolicy" +
                            (if (noPairing) {
                                "（本轮跳过 pair-setup）"
                            } else {
                                "（本轮先配对）"
                            }) +
                            "；依据：端点 features=" +
                            (client.currentEndpoint.advertisedFeatures?.let { "0x${it.toString(16)}" } ?: "未知"),
                    )
                    var keys: PairVerifyClient.ControlKeys? =
                        if (noPairing) {
                            if (pairingGate == PairingGate.SKIP_SETUP) {
                                client.skipPairing(
                                    "上次连接被车机确定性拒绝配对（15 分钟内不重复尝试；" +
                                        "配对成功或过期后自动恢复）",
                                )
                            } else {
                                client.skipPairing("TXT features 未声明 HK_PAIRING_AND_ENCRYPT")
                            }
                            client.reconnect()
                            null
                        } else {
                            client.runPairing()
                        }
                    // 审计：画像说「该配对」，接收端却根本没有 /pair-setup（404/不可用）。
                    // 这是 SPEC §3.3 特征位与实测不符的硬证据（多见于自称新代的老固件），
                    // 记下来才能解释「为什么每轮都白等配对窗口」。
                    if (client.pairingUnsupported && !txtNoPairing) {
                        ProtocolAudit.record(
                            ProtocolAudit.Code.PAIRING_POLICY_MISMATCH,
                            detail = "画像判 $pairingPolicy（应配对），接收端不提供 /pair-setup",
                            evidence = "features=" +
                                (client.currentEndpoint.advertisedFeatures?.let { "0x${it.toString(16)}" } ?: "未知"),
                        )
                    }
                    if (connectCancelled) {
                        retireSession()
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        sessionStarting.set(false)
                        return@run
                    }
                    // 配对失败后的兜底路径（纯函数决策，真机抓包场景由 PairingFallbackTest 钉死）
                    val fallbackAfterFirstRound =
                        pairingFallback(
                            keysEstablished = keys != null,
                            noPairing = noPairing,
                            pairSetupRejected = client.pairSetupRejected,
                            pairingForbidden = client.pairingForbidden,
                            attemptedPairing = client.attemptedPairing,
                            pairingUnsupported = client.pairingUnsupported,
                            pairingTerminal = client.pairingTerminal,
                        )
                    if (fallbackAfterFirstRound.useBtRetireHack) {
                        // 第二阶段（仅测试台 DiPlay）：复现「BT 控制已结束」状态再试一次。
                        // 真车（Audi）绝不能断 BT——真无线 CarPlay 的 BT 控制通道全程保活，
                        // 断开会让车机拆掉整轮 AirPlay 窗口（实车实测）。
                        // pairingForbidden（任意段 403，含 verify/认证段——2026-10-21 真机
                        // sysdiagnose 定谳：首连被 403 是车机固有状态，它 18~20s 后自动
                        // 重邀、第二次即成）时同样禁用：断 BT 会让车机整栈重建、恰好错过
                        // 即将到来的重邀窗口。
                        // 门槛：仅当本轮真的连上过接收端 RTSP（attemptedPairing）才用此 hack；
                        // 端点未解析（无 IP）/端口未开时绝不值得断 BT（2026-10-11 踩过：
                        // mDNS 失败也触发 detach，白白拆掉车机窗口）。
                        detachActiveBtSessions()
                        client = newClient()
                        currentClient = client
                        drainPendingEndpoint(client)
                        keys = client.runPairing()
                        if (connectCancelled) {
                            retireSession()
                            appendLog("AIRPLAY: 用户已取消——中止配对")
                            sessionStarting.set(false)
                            return@run
                        }
                    }
                    // keys == null 时仍有合法路径：**catplay 的 et=NONE 明文会话**。
                    //  - 接收端明确不支持配对（pre-HomeKit / TXT 无 HK_PAIRING_AND_ENCRYPT）；
                    //  - 配对彻底失败但 RTSP 确实连上过（有些车只认明文会话，
                    //    pair-setup/pair-verify 全拒——catplay 正是「先试明文、失败才配对」，
                    //    我们反过来：优先配对（保住 DiPlay/已知车机），失败后降级明文兜底）。
                    //  - **403 证据（pairingForbidden）除外**（真机抓包 2026-10-21 定谳，决策
                    //    见 pairingFallback）：车机在等自身就绪、~20s 后重邀即成——明文必被
                    //    拒且消耗重邀窗口，不降级。
                    // hack 段可能已重跑拿到 keys：决策以重跑后的状态为准。
                    val plainSession =
                        pairingFallback(
                            keysEstablished = keys != null,
                            noPairing = noPairing,
                            pairSetupRejected = client.pairSetupRejected,
                            pairingForbidden = client.pairingForbidden,
                            attemptedPairing = client.attemptedPairing,
                            pairingUnsupported = client.pairingUnsupported,
                            pairingTerminal = client.pairingTerminal,
                        ).usePlainSession
                    if (plainSession) {
                        phaseHint = "建立明文会话…"
                        appendLog(
                            "AIRPLAY: 配对不可用（${if (client.pairingUnsupported) "接收端不支持" else "配对失败"}）" +
                                "——改用 et=NONE 明文会话 + AES-CTR 屏幕流（catplay 接收端分类 1/2）",
                        )
                        if (client.pairingTerminal) {
                            // 把「画面能用、但为什么没声音」写在连接当场，而不是等用户来问。
                            // 旧日志对这类会话的说法是「加密 SETUP 被拒（403）」/「弹窗里没勾声音」，
                            // 两条都是假线索（1.1.2 起 `audioEnabledForSession` 默认就是 true）。
                            appendLog(
                                "AIRPLAY: ⚠ 本会话无共享密钥（车机拒绝配对）：画面正常，**音频与触摸不可用**。" +
                                    "要恢复声音需在车机侧删掉本机的配对记录后重连",
                            )
                        }
                        // 配对循环结束时 socket 已关/状态机不可用：SETUP 前必须重连一次。
                        // 失败不直接判死：车机 AirPlay 端口可能还没开，下面会按窗口重试。
                        if (!client.reconnect()) {
                            appendLog("AIRPLAY: 明文会话首次重连未成功——继续按窗口重试 SETUP")
                        }
                    }
                    // keys 是 lambda 内的可变捕获变量：Kotlin 无法智能转换，先固化成不可变值。
                    val pairedKeys = keys
                    // 把配对结果落成「短期记忆」：确定性拒绝则记住（下轮省掉 120s 白等），
                    // 成功则立刻清除（车机侧删过配对记录后能自己恢复，不用等 TTL）。
                    client.pairingTrouble?.let { trouble ->
                        if (trouble.deterministic) {
                            PairingMemory.record(context, pairingReceiverKey, trouble.stage, trouble.rawCode)
                            uiHintSink?.invoke("配对失败：${trouble.advice}")
                            appendLog(
                                "AIRPLAY: 车机确定性拒绝配对（${trouble.text}）——已记录，15 分钟内不再重复尝试。" +
                                    "建议：${trouble.advice}",
                            )
                        }
                    }
                    if (pairedKeys != null) PairingMemory.clear(context)
                    if (pairedKeys != null || plainSession) {
                        phaseHint = if (pairedKeys != null) "配对完成，建立 CarPlay 会话…" else "建立明文会话…"
                        if (pairedKeys != null) {
                            appendLog(
                                "AIRPLAY: ✓ 配对完成 writeKey=" +
                                    pairedKeys.phoneWriteKey.joinToString("") { "%02x".format(it) }.take(16) + "… " +
                                    "readKey=" + pairedKeys.phoneReadKey.joinToString("") { "%02x".format(it) }.take(16) + "…",
                            )
                        }
                        // 已配对 → 共享密钥；明文会话 → null（流加密降级为 AES-CTR 零密钥）
                        var sessionKey = pairedKeys?.sharedSecret
                        connectBudget.enter(ConnectStage.SETUP)
                        var setup = runInitialSetup(client, plainSession, noPairing)
                        if (setup == null && plainSession && noPairing) {
                            // catplay 的「加密升级」路径：只有在**我们主动跳过配对**（TXT 未声明
                            // HK_PAIRING_AND_ENCRYPT）且明文 SETUP 仍失败时才补做。
                            // 反过来（配对先试过且失败）再试一次没有意义，只会多等 30s。
                            val upgraded = client.retryWithPairing(PAIRING_UPGRADE_WINDOW_MS)
                            if (upgraded != null) {
                                // 补配对真的成了：说明「上次确定性拒绝」的记录已经过时，
                                // 立刻清掉，下一轮不必再走明文。
                                PairingMemory.clear(context)
                                sessionKey = upgraded.sharedSecret
                                setup = client.runSetup()
                            }
                        }
                        if (setup == null && pairedKeys != null) {
                            // 反向兜底（2026-10-24 WinPlay 实测）：接收端能完成
                            // pair-setup/pair-verify，却对带 et 的 SETUP 回 403——
                            // 典型是只支持 AirPlay 1 明文会话的自制/PC 端接收端。
                            // 丢掉配对密钥、重连后按 et=NONE 再试一次（仅一次）。
                            client.retryWithoutEncryption("配对已完成但 SETUP 被 403 拒绝")
                            if (client.reconnect(5_000)) {
                                sessionKey = null
                                setup = client.runSetup()
                                if (setup != null) {
                                    appendLog("AIRPLAY: ✓ 明文会话建立成功——接收端不接受加密会话")
                                }
                            } else {
                                appendLog("AIRPLAY: 明文降级重连失败——接收端可能已关闭该连接")
                            }
                        }
                        if (setup != null) {
                            connectBudget.enter(ConnectStage.FIRST_FRAME)
                            // 记录会话凭据：投影授权晚于连接时用于动态补启音频。
                            // 用 client.currentEndpoint 而非 effectiveEndpoint：配对期间
                            // mDNS 刷新过端点时，真正连上的是 client 里的地址（2026-10-18：
                            // 0x4301 的定长 IP 字段带 NUL，mDNS 给的是干净地址，取旧值
                            // 会让视频端口/keepAlive 用脏地址 → 一律解析失败）。
                            currentSharedSecret = sessionKey
                            currentAudioHost =
                                client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                            val eventChannelOk =
                                client.connectEventChannel(sessionKey, setup) { command ->
                                // 车机侧「有反应」计数：屏幕接管门（startScreenHandoverGate）用它
                                // 区分「车机压根没把这条流当屏流」与「只是画面还没上屏」。
                                command["type"]?.toString()?.let { type ->
                                    carEventCount++
                                    carEventTypes.add(type)
                                    lastCarEventAtUptime = android.os.SystemClock.uptimeMillis()
                                }
                                when (command["type"]) {
                                    "hidSendReport" -> {
                                        val uuid = command["uuid"]?.toString()
                                        val report = command["hidReport"] as? ByteArray
                                        // 车机媒体键（Consumer Control）走 hidSendReport 通路，
                                        // 判定**以设备描述符为准**（不按 uuid、不按车机型号写死）：
                                        // 描述符声明了 Consumer 页(0x0C)的设备才当媒体键，报告字节是
                                        // Usage 数组下标（HID Array 语义）。触摸/旋钮/电话设备的描述符
                                        // 分别是 Digitizer/GenericDesktop/Telephony 页，一律不走这里——
                                        // DiPlay 的 knob(4B) 与 telephony(1B) 报告长度与媒体键重叠，
                                        // 只靠长度会误判（如 telephony usage 0xB3 会误发「快进」）。
                                        val descriptor = uuid?.let { setup.hidDescriptors[it] }
                                        val keyMap =
                                            descriptor?.let(ConsumerKeys::parseDescriptor)
                                        val rawValue =
                                            report
                                                ?.takeIf { it.isNotEmpty() }
                                                ?.let { it[0].toInt() and 0xFF }
                                        val consumerUsage =
                                            when {
                                                // 有描述符：只有 Consumer 设备才解析（非 Consumer → null）
                                                descriptor != null -> keyMap?.let { rawValue?.let(it::usageFor) }
                                                // 描述符缺失（未知车机）：保守回退——仅 1 字节报告，
                                                // 值当 Usage ID 直读
                                                report != null && report.size == 1 -> rawValue?.takeIf { it != 0 }
                                                else -> null
                                            }
                                        // 触摸格式按设备描述符判定（Digitizer 页 + 单指/多槽），
                                        // 描述符不可用时按报文长度回退——不依赖车机型号或 uuid
                                        val touchFormat =
                                            descriptor?.let(TouchReports::formatFromDescriptor)
                                        val contacts =
                                            if (consumerUsage == null && report != null) {
                                                TouchReports.parse(report, touchFormat)
                                            } else {
                                                null
                                            }
                                        if (consumerUsage != null) {
                                            val keyCode = ConsumerKeys.keyCodeFor(consumerUsage)
                                            lastConsumerUsage = consumerUsage
                                            if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
                                                // 1.2.21 漏了这条通路：事件通道 hidSendReport
                                                // 直接调 dispatchMediaKey，语音键会被当成系统键
                                                // 注入而不是转给 CarLife。和 requestSiri 统一收口。
                                                voiceKey.onHidDown("hidSendReport 0xCF")
                                            } else if (keyCode != 0) {
                                                dispatchMediaKey(keyCode)
                                                appendLog(
                                                    "EVENT: 车机媒体键 ${ConsumerKeys.nameOf(consumerUsage)}" +
                                                        "（uuid=$uuid usage=0x%02x → keyCode=$keyCode）"
                                                            .format(consumerUsage),
                                                )
                                            } else {
                                                appendLog(
                                                    "EVENT: 车机媒体键 ${ConsumerKeys.nameOf(consumerUsage)}" +
                                                        "（uuid=$uuid usage=0x%02x）无对应 Android 键，忽略"
                                                            .format(consumerUsage),
                                                )
                                            }
                                        } else if (keyMap != null && report != null) {
                                            // Consumer 设备的「无按键」/释放报文（值为 0 或下标越界）：
                                            // 静默计数，避免释放事件刷屏
                                            consumerReleaseCount++
                                            // 但**非 0 的越界值**必须留痕：车机确实按了键、只是这个
                                            // usage 不在描述符里（语音键 0xCF 常是这种）。以前全静默，
                                            // 表现是「按了语音键日志一个字都没有」，分不清谁的问题。
                                            val unmapped = rawValue ?: 0
                                            if (unmapped != 0 && consumerUnknownCount < UNKNOWN_CONSUMER_LOG_LIMIT) {
                                                consumerUnknownCount += 1
                                                appendLog(
                                                    "EVENT: HID 报告 uuid=$uuid 值=0x%02x 映射不到 usage"
                                                        .format(unmapped) +
                                                        "（描述符声明 ${keyMap.usages.size} 项：" +
                                                        keyMap.usages.joinToString(" ") { "0x%02x".format(it) } +
                                                        "）——车机按了键但我们没声明这项",
                                                )
                                            }
                                            if (lastConsumerUsage?.let(ConsumerKeys::keyCodeFor) ==
                                                KeyEvent.KEYCODE_VOICE_ASSIST
                                            ) {
                                                voiceKey.onHidUp("hidSendReport 0xCF")
                                            }
                                            lastConsumerUsage = null
                                        } else if (contacts != null) {
                                            // 限频只压 MOVE（拖拽时每秒上百条）；
                                            // **抬起永远记录**——排查手势卡死时最需要看的就是
                                            // 「UP 到底有没有从车机发过来」。
                                            // 只看 slot 0：多槽格式里 slot 1 常驻 (0,0)↑ 占位。
                                            if (touchLogCount == 0) {
                                                // 双指能不能用，第一眼看这里：单指绝对坐标的
                                                // 报文里根本没有第二个触点的位置，双指无从谈起。
                                                appendLog(
                                                    "EVENT: 触摸格式 " +
                                                        when (touchFormat) {
                                                            TouchReports.Format.MULTI_SLOT -> "多槽（最多 2 指）"
                                                            TouchReports.Format.SINGLE_ABSOLUTE -> "单指绝对坐标（车机只报一个触点）"
                                                            null -> "未知（按报文长度 ${report?.size} B 回退）"
                                                        },
                                                )
                                            }
                                            val hasRelease = contacts.any { it.slot == 0 && !it.down }
                                            if (hasRelease || touchLogCount <= 30 || touchLogCount % 50 == 0) {
                                                appendLog(
                                                    "EVENT: 触摸 " +
                                                        contacts.joinToString {
                                                            "#${it.slot}(${it.xPixels},${it.yPixels})${if (it.down) "↓" else "↑"}"
                                                        },
                                                )
                                            }
                                            touchLogCount++
                                            contacts.forEach {
                                                TouchRouter.onContact(
                                                    context.applicationContext,
                                                    it,
                                                )
                                            }
                                        } else if (report != null) {
                                            appendLog(
                                                "EVENT: 其他 HID 报告 uuid=$uuid bytes=${report.size}" +
                                                    " hex=${report.joinToString(" ") { "%02x".format(it) }}",
                                            )
                                        }
                                    }

                                    "iAPSendMessage" -> {
                                        // 事件通道上的 iAP2 虚拟链路字节（车机先发 marker 开场）。
                                        // 喂给链路引擎：握手 → 触摸 HID（EA 车机控制已移除）。
                                        val params = command["params"] as? Map<*, *>
                                        val data = params?.get("data") as? ByteArray
                                        if (data == null) {
                                            appendLog("EVENT: iAPSendMessage 无 data（params=$params）")
                                        } else {
                                            iapEventMessageCount++
                                            client.onIapEventBytes(data)
                                        }
                                    }

                                    "requestSiri" -> {
                                        // 方向盘的语音键（2026-11-03 08:52 手机日志实测）：
                                        // 车机在事件通道发 type=requestSiri，params.siriAction =
                                        // 1 Prewarm（预热）/ 2 ButtonDown / 3 ButtonUp
                                        // —— 来自 Apple AirPlayCommon.h 的 AirPlaySiriAction。
                                        // 它**不走** 0x6802 HID 0xCF，所以 1.2.21 那条语音键
                                        // 链路在这台车上是死代码；这里才是真正的入口。
                                        val params = command["params"] as? Map<*, *>
                                        val raw = params?.get("siriAction")
                                        val action =
                                            when (raw) {
                                                is Number -> raw.toInt()
                                                else -> raw?.toString()?.trim()?.toIntOrNull()
                                            }
                                        voiceKey.onSiriAction(action)
                                    }

                                    "forceKeyFrame" -> {
                                        // 车机解不出画面时会要 I 帧。必须立即响应：
                                        // 拖到下一个周期 I 帧（1s）车机可能一直卡在等帧状态。
                                        val forced = videoSender?.requestKeyFrame() == true
                                        appendLog(
                                            "EVENT: 车机请求关键帧（forceKeyFrame）→ " +
                                                if (forced) "已强制编码 I 帧" else "编码器未就绪，等周期 I 帧",
                                        )
                                    }

                                    "changeModes" -> {
                                        // 车机请求资源仲裁（如用户在 MMI 上点了手机）：
                                        // 回 modesChanged 声明手机持有主屏/主音频
                                        appendLog("EVENT: 车机请求 changeModes → 回 modesChanged 接管资源")
                                        client.assertModes()
                                    }

                                    else ->
                                        appendLog(
                                            // 未识别的命令必须带参数：车机的私有命令（如 requestUI）
                                            // 到底要什么，全在 params 里——只打 type 等于没打。
                                            "EVENT: 命令 type=${command["type"]} " +
                                                command.entries
                                                    .filter { it.key?.toString() != "type" }
                                                    .joinToString(" ") { (key, value) ->
                                                        "$key=" +
                                                            when (value) {
                                                                is ByteArray -> value.joinToString("") { "%02x".format(it) }
                                                                else -> value.toString().take(240)
                                                            }
                                                    }.take(600),
                                        )
                                }
                            }
                            // 「半握手」判定（2026-11-02 MBUX 两轮实测）：
                            // SETUP 回了 200，但 /info 一条 displays 都没给、事件通道还连不上——
                            // 车机只是答应了前半段，后面什么都不通。此时按手机屏幕尺寸
                            // 兜一个画布硬推没有任何意义（屏上不会出现画面，触摸也没通路），
                            // 只会把「连不上」演成「连上了但黑屏」。
                            //
                            // 前提是**确实有事件口可连**：接收端压根没下发 eventPort 时
                            // `connectEventChannel` 是「没试过」而不是「连不上」，把它当证据
                            // 会误判一台本来能用的车（这类固件正是画布兜底要照顾的对象）。
                            val eventChannelRejected = setup.eventPort != 0 && !eventChannelOk
                            if (!setup.displayFromCar && eventChannelRejected) {
                                appendLog(
                                    "AIRPLAY: ⚠ 车机侧 CarPlay 服务半握手——SETUP 回了 200，" +
                                        "但 /info 无 displays 且事件口连不上（端口 ${setup.eventPort}）",
                                )
                                appendLog(
                                    "AIRPLAY: 本轮不再按手机尺寸兜底硬推——请重启车机侧 CarPlay" +
                                        "（中控把 CarPlay 关掉再打开，或熄火重上电）后重连",
                                )
                                phaseHint = "车机侧 CarPlay 服务异常，请重启车机"
                                uiHintSink?.invoke(
                                    "车机侧 CarPlay 服务异常（只答应了 SETUP，事件口/信息口都不通）——" +
                                        "请在车机上把 CarPlay 关掉再打开后重试",
                                )
                                runCatching { client.close() }
                                return@run
                            }
                            if (!setup.displayFromCar) {
                                appendLog(
                                    "AIRPLAY: ⚠ /info 没有 displays——画布按本机横屏兜底，" +
                                        "比例与触摸映射都可能不准（事件口" +
                                        when {
                                            eventChannelOk -> "已连上，先按兜底画布继续"
                                            setup.eventPort == 0 -> "接收端未下发，先按兜底画布继续"
                                            else -> "也没连上"
                                        } +
                                        "）",
                                )
                            }
                            if (!eventChannelOk) {
                                appendLog(
                                    "AIRPLAY: ⚠ 事件通道未建立——触摸/车机媒体键/资源仲裁本轮都收不到" +
                                        "（视频与音频仍可继续）",
                                )
                            }
                            // 资源声明（must）：真车对未声明资源的屏幕流 SETUP 直接 400
                            client.assertModes()
                            // HID 输入模式武装：/info hidDevices（奥迪字符串 "0"/"1"，触摸="1"）。
                            // display features=0x08（高保真触摸）已声明，缺这步车机不发 hidSendReport。
                            setup.hidDeviceUuids.forEach { uuid ->
                                client.sendHidSetInputMode(uuid, mode = 0)
                            }
                            val screen = client.runScreenStream(sessionKey, setup.displayUuid)
                            if (screen != null) {
                                // 地址取 client 真正连通的那个（配对期间可能被 mDNS 刷新过）：
                                // 视频数据端口与 keepAlive 都按它建 socket；IPv6 端点也算
                                // 可用地址（EndpointHosts 统一排序：IPv4 → 全局 IPv6 → 链路本地 IPv6）。
                                val host = client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                                // 心跳双保险（缺失任一都会被车机 ~10s 看门狗拆会话，2026-10-11 实测）：
                                //   keepAlive UDP ping（车机 SETUP 返回的 keepAlivePort，10s 周期）
                                //   + RTSP POST /feedback（1s 周期，catplay FEEDBACK_INTERVAL）
                                if (host != null && setup.keepAlivePort != null) {
                                    client.startKeepAlive(host, setup.keepAlivePort!!)
                                }
                                client.startFeedbackLoop()
                                // iAP 隧道（type-130）：车机→手机的 iAP2 数据（触摸 HID 载体）。
                                // 建立失败不阻塞会话（触摸不可用但视频照常）。
                                val tunnel = client.runIapTunnel(sessionKey)
                                tunnel?.onIapMessage = { frame ->
                                    // 触摸证据采集：先全量记录，格式确认后接 TouchRouter
                                    if (frame.messageId != 0x0000) {
                                        val hex = frame.body.joinToString(" ") { "%02x".format(it) }
                                        appendLog(
                                            "TUNNEL: rx 0x${frame.messageId.toString(16)} " +
                                                "(${Iap2MessageIds.nameOf(
                                                    frame.messageId,
                                                )}) ${frame.body.size}B" +
                                                if (frame.body.isNotEmpty()) " hex=${hex.take(120)}" else "",
                                        )
                                    }
                                }
                                // 关键时序（2026-10-17 奥迪定谳）：屏幕 SETUP 后必须尽快连上
                                // dataPort——车机有 ~2s 看门狗。音频 SETUP 实测要 ~2.1s，
                                // 若排在前面，端口连接迟到即触发车机 reset 控制通道
                                // （日志特征：`音频 SETUP 往返 2091ms` 与 `EVENT 读取结束
                                // - Connection reset` 同一毫秒）。故先建 VideoSender 并
                                // openDataPort()，再协商音频，最后 RECORD + 启动编码器。
                                var pendingVideoW = 0
                                var pendingVideoH = 0
                                var pendingVideoDensity = 0
                                if (host != null) {
                                    // 「显示」偏好：流画布/虚拟屏按车机分辨率百分比生成。
                                    // 只在会话建立时生效——编码器与虚拟屏尺寸此时定型；
                                    // 密度同步缩放，逻辑布局（dp）与 100% 完全一致。
                                    val scalePct =
                                        appContext
                                            ?.let {
                                                DisplayPrefs
                                                    .scalePercent(it)
                                            } ?: 100
                                    val scale = scalePct / 100f
                                    // 缩放后向下对齐偶数（H.264 要求）：虚拟屏/合成器/编码器
                                    // 三方必须同一尺寸，故在源头对齐（VideoSender 内另有兜底）
                                    val streamW =
                                        ((setup.displayWidth * scale).toInt().coerceAtLeast(320) / 2) * 2
                                    val streamH =
                                        ((setup.displayHeight * scale).toInt().coerceAtLeast(180) / 2) * 2
                                    // 目标帧率：车机 `/info displays[0].maxFPS` 说了算，
                                    // 车机没声明按 60（判定见 video/StreamFps.kt）。
                                    val sessionTargetFps = StreamFps.resolve(setup.maxFps)
                                    activeScalePercent = scalePct
                                    activeTargetFps = sessionTargetFps
                                    // 合成器出帧节奏跟着走：attach 时按 60 建（那时还没
                                    // SETUP），车机上限要到这一刻才知道。不同步的话
                                    // 多出来的帧会白白送去编码再丢掉。
                                    VirtualLauncher.setStreamFps(sessionTargetFps)
                                    // 记录「车机通告分辨率」原值：英雄卡与「显示」卡片要如实
                                    // 展示它，而不是只显示缩放百分比（用户根本看不出画布多大）。
                                    activeCarWidth = setup.displayWidth
                                    activeCarHeight = setup.displayHeight
                                    activeCarResolutionFromCar = setup.displayFromCar
                                    // 车机触摸在通告视图坐标系（如 1160×540），流已按比例
                                    // 缩放（如 1450×674）——注入前需映射，否则点击漂移
                                    TouchRouter
                                        .setCoordinateScale(
                                            setup.displayWidth,
                                            setup.displayHeight,
                                            streamW,
                                            streamH,
                                        )
                                    // CarLife 劫持链路用自己的坐标映射（车机视图 → 手机视频），
                                    // 这里只需把车机通告的视图尺寸交给它
                                    CarLifeHijack.setViewSize(setup.displayWidth, setup.displayHeight)
                                    // 同时把车机真实分辨率转给 CarLife 引擎（98311 width/height），
                                    // 让引擎按车机画布编码——取代此前写死的 1920×1080 白申请。
                                    // 只转「车机真正通告过」的画布：displayFromCar=false 是手机
                                    // 横屏兜底的假画布，转过去会让引擎按手机尺寸编码，还会被
                                    // CarCanvasMemory 当成车机画布记下来，越记越偏。
                                    if (setup.displayFromCar) {
                                        CarLifeHijack.setRequestedResolution(
                                            setup.displayWidth,
                                            setup.displayHeight,
                                        )
                                    }
                                    pendingVideoW = streamW
                                    pendingVideoH = streamH
                                    // dock 屏密度随画布短边等比（设计基准 1160×540@420dpi，
                                    // 见 design/launcher-proto.html：dock 48dp=126px）：高分辨率
                                    // 车机（测试台 2320×1080=2×）下 dock 文字/图标与奥迪同比例
                                    // 放大——此前固定 420dpi，dock 相对画布减半显小（2026-10-17）。
                                    // 上限 4×（4K 级画布）。
                                    pendingVideoDensity =
                                        (420f * minOf(streamW, streamH) / 540f)
                                            .toInt()
                                            .coerceIn(120, 1680)
                                    // 直通模式下码流分辨率由 CarLife 决定（默认 1280×720，引擎
                                    // 白名单还会把非标尺寸降档）：这里的构造值只是**兜底**，
                                    // VideoConfig 真正声明的是车机画布（见 canvasSize），
                                    // 不能用码流尺寸反过来改写声明——那会让车机按比屏幕小的
                                    // 画布建解码器，整屏黑（2026-11-04 奥迪 3810 实测）。
                                    val hijackMode = context != null && CarLifeHijack.isEnabled(context)
                                    val streamOutW: Int
                                    val streamOutH: Int
                                    if (hijackMode) {
                                        val (carW, carH) = CarLifeHijack.videoSize
                                        streamOutW = ((carW.coerceAtLeast(320)) / 2) * 2
                                        streamOutH = ((carH.coerceAtLeast(180)) / 2) * 2
                                    } else {
                                        streamOutW = streamW
                                        streamOutH = streamH
                                    }
                                    // 真正上线的码流尺寸（直通模式下与申请值可能不同）：
                                    // 英雄卡与「显示」卡片显示的就是它，不是申请值。
                                    activeStreamWidth = streamOutW
                                    activeStreamHeight = streamOutH
                                    appendLog(
                                        "DISPLAY: 车机 ${setup.displayWidth}×${setup.displayHeight}" +
                                            (if (setup.displayFromCar) {
                                                "（/info 通告）"
                                            } else {
                                                "（车机未通告·手机横屏兜底）"
                                            }) +
                                            " → 流 ${streamOutW}×${streamOutH}（$scalePct%）" +
                                            "@${sessionTargetFps}fps（${StreamFps.describe(setup.maxFps)}）",
                                    )
                                    if (streamOutW != streamW || streamOutH != streamH) {
                                        appendLog(
                                            "DISPLAY: 码流尺寸由 CarLife 引擎决定（申请 " +
                                                "${streamW}×$streamH，建链时刻 ${streamOutW}×$streamOutH）" +
                                                "——引擎收到车机分辨率后会重建编码器，实际尺寸以界面显示为准",
                                        )
                                    }
                                    // 落盘：下次还没连上时，界面也能显示「上次车机分辨率」，
                                    // 而不是一片空白（用户问的就是「分辨率能不能从车机端看到」）。
                                    appContext?.let { ctx ->
                                        DisplayPrefs.recordSession(
                                            ctx,
                                            setup.displayWidth,
                                            setup.displayHeight,
                                            setup.displayFromCar,
                                            streamOutW,
                                            streamOutH,
                                        )
                                    }
                                    appendLog("VIDEO: VideoConfig 尺寸声明 = ${VideoCanvasMode.describe()}")
                                    // 车机画布（/info 通告值）：既是「声明画布」模式的 params[1]，
                                    // 也是「分离声明」模式的视口（params[3]/params[4]）。
                                    val carCanvas: Pair<Int, Int>? =
                                        if (setup.displayFromCar &&
                                            setup.displayWidth > 0 &&
                                            setup.displayHeight > 0
                                        ) {
                                            setup.displayWidth to setup.displayHeight
                                        } else {
                                            null
                                        }
                                    videoSender =
                                        VideoSender(
                                            host = host,
                                            dataPort = screen.dataPort,
                                            frameCipher = screen.cipher,
                                            width = streamOutW,
                                            height = streamOutH,
                                            targetFps = sessionTargetFps,
                                            log = { line -> appendLog(line) },
                                            keepAliveWithStats = setup.keepAliveSendStatsAsBody,
                                            // VideoConfig 头声明的画布 = 车机 /info 通告的画布。
                                            // 真车按它建解码器与 surface，与画布不符就黑屏
                                            // （2026-11-04 奥迪 3810：画布 1412×654、直通码流
                                            // 被引擎降档成 1280×720，照码流声明 → 整屏黑）。
                                            canvasSize =
                                                if (VideoCanvasMode.sizeFromCanvas) {
                                                    // 只有 car 档把解码尺寸写成车机画布。
                                                    carCanvas?.let { size -> { size } }
                                                } else {
                                                    // raw / stream 档：params[1] 交给码流尺寸（下方 liveSize）。
                                                    null
                                                },
                                            // 视口（params[3] 原点 / params[4] 宽高）。
                                            // 默认 raw 档**不写**：参考 AMCar（explicitVideoGeometry=false），
                                            // 只声明解码尺寸、视口整段留 0，少一个字段就少一处与车机固件
                                            // 理解不一致的风险。stream 档才写车机画布做 A/B。
                                            viewAreaSize =
                                                if (VideoCanvasMode.writeViewport) {
                                                    carCanvas?.let { size -> { size } }
                                                } else {
                                                    null
                                                },
                                            // 直通模式：码流实际尺寸（98311 上报值），**仅作兜底**——
                                            // 没有画布信息时才用它；有画布时不能用，否则画布会被
                                            // 码流带偏（同理见 canvasSize 注释）。
                                            liveSize =
                                                if (hijackMode) {
                                                    { CarLifeHijack.videoSize }
                                                } else {
                                                    null
                                                },
                                            onEnded = {
                                                // 会话真实结束：解除静默降噪（客户端恢复探测/重连）
                                                appendLog(">>> CarPlay 会话结束（推流终止）——恢复蓝牙探测")
                                                BtSessionGate.sessionActive = false
                                                // 车机掉线类结束（无用户断开操作）：暗幕/常亮必须一起收掉。
                                                // 这条路径不经过 retireSession（VirtualLauncher 仍挂着），
                                                // 漏掉它就会出现「会话早就断了、45s 后手机屏突然变黑」
                                                // （2026-10-19 用户实测）。
                                                ScreenDimmer.setSessionActive(false)
                                                // 同样复原横屏覆盖。这里跑在推流收尾线程，不是连接
                                                // 关键路径，但统一走异步口以保持「串行、不重入」的语义。
                                                appContext?.let { ctx ->
                                                    AppOrientationCompat.resetAllAsync(ctx, ::appendLog)
                                                }
                                                // 音频拆链归因：起过音频却很快被拆 → 记下该档位
                                                // 不兼容，下次连接自动换档（见 attributeAudioTeardown）。
                                                attributeAudioTeardown()
                                            },
                                        )
                                    // 先连端口（车机看门狗），编码器等 RECORD 后再启动
                                    videoSender?.openDataPort()
                                }
                                // 先 RECORD 激活会话并起视频，再协商音频（2026-10-17 奥迪定谳）：
                                // 车机在「屏幕流 SETUP 之后」有约 2s 看门狗，等不到 RECORD/数据流即
                                // 拆掉整轮会话。音频 SETUP 实测本身要 ~2.05-2.16s（车机侧音频通路
                                // 初始化），排在 RECORD 之前必然超时——日志里 reset 与音频 SETUP
                                // 返回同一毫秒、且 5 次实测全部复现。故顺序改为：
                                //   屏幕 SETUP → 连数据端口 → RECORD → 起编码器（出帧）→ 音频 SETUP
                                // 音频 SETUP 失败只丢声音，视频会话保持。
                                //
                                // 但有一类接收端反着来（issue #14 JYBOX-80 / Magic-Car-Link）：
                                // 音频流后补 RECORD 直接 500，并顺手关掉自己的 AirPlay 服务，
                                // 整轮作废。对它们改用「音频优先」——先 SETUP 音频，再一次
                                // RECORD 同时激活视频+音频。顺序按接收端学，默认不动
                                // （见 StreamOrderMemory）。
                                val receiverIdentity = client.receiverIdentity()
                                val orderLearned = StreamOrderMemory.readLearned(appContext, receiverIdentity)
                                val streamOrder =
                                    StreamOrderMemory.resolve(orderLearned, receiverIdentity)
                                appendLog(
                                    "STREAM: 顺序 = " +
                                        StreamOrderMemory.describe(streamOrder, orderLearned, receiverIdentity),
                                )
                                var audioStartedBeforeRecord = false
                                if (streamOrder == StreamOrderMemory.Order.AUDIO_FIRST) {
                                    // 音频 SETUP 要 ~2s，这里先做完再把 RECORD 一次发出去。
                                    // 视频数据端口已在上面 openDataPort() 连上，2s 看门狗
                                    // 不会因为「没连端口」而拆链（这是奥迪那条结论的前提）。
                                    audioStartedBeforeRecord = startAudioChannelAndLog(client, sessionKey, host, context)
                                    if (!audioStartedBeforeRecord) {
                                        appendLog("AUDIO: 音频优先档未建立音频流——本轮仅推视频（RECORD 照发）")
                                    }
                                }
                                val firstRecordOk = client.record()
                                if (!firstRecordOk && streamOrder == StreamOrderMemory.Order.AUDIO_FIRST) {
                                    // 音频优先时连第一次 RECORD 都非 200：不是「补发 RECORD 过敏」，
                                    // 记回 RECORD 优先，别把这台接收端永久锁在错误顺序上
                                    // （已知型号快路径也会被这条压过去）。
                                    StreamOrderMemory.noteRecordFirstForced(appContext, receiverIdentity)
                                    appendLog("STREAM: 音频优先下首次 RECORD 也非 200——记住该接收端用 RECORD 优先，下一轮回退")
                                }
                                // 4580（AUDI 3810 / DELPHI MIB3）：车机 HMI 要等 iAP2 控制会话
                                // （链路 NORMAL + MFi 应答）就绪才激活 CarPlay——固件
                                // `CCarPlayTestController` 在那之前一直是
                                // "Waiting for required IAP2 information"，此时收到的 RECORD 它不认，
                                // 现场就是「会话全绿、码流在送、屏幕全黑」。
                                //
                                // 正常车机走到这里链路早已 NORMAL → 整段跳过，行为零变化。
                                //
                                // 「音频优先」档整段跳过：它要防的接收端恰恰怕**任何**第二次
                                // RECORD（补发即 500）。这台设备不是 MIB3（MIB3 走默认档），
                                // 为它补激活只会把已经激活好的会话再赌一次。
                                if (streamOrder == StreamOrderMemory.Order.AUDIO_FIRST) {
                                    if (!client.controlSessionReady) {
                                        appendLog(
                                            "MIB3: 控制会话未就绪，但本档音频优先——不做补发 RECORD 的二次激活" +
                                                "（该接收端对重复 RECORD 过敏）",
                                        )
                                    }
                                } else if (!client.controlSessionReady) {
                                    appendLog("MIB3: RECORD 时 iAP2 控制会话尚未就绪——等它就绪后补一次激活")
                                    if (client.awaitControlSession(MIB3_REACTIVATE_WAIT_MS)) {
                                        appendLog("MIB3: 控制会话已就绪 → 重新声明主屏资源并补发 RECORD")
                                        client.assertModes()
                                        client.record()
                                        setup.hidDeviceUuids.forEach { uuid ->
                                            client.sendHidSetInputMode(uuid, mode = 0)
                                        }
                                    } else {
                                        appendLog(
                                            "MIB3: ${MIB3_REACTIVATE_WAIT_MS}ms 内控制会话仍未就绪" +
                                                "——保留首次激活，不重复（重复 RECORD 会被车机当噪声）",
                                        )
                                    }
                                }
                                // RECORD 成功 = 会话活着。此刻丢弃报文缓冲：用户要的是
                                // 「连不上」的现场，正常连接不需要快照（省空间也省导出体积）。
                                SessionTrace.markLinked()
                                if (host != null) {
                                    // CarLife 本地劫持是**唯一**画面来源（2026-10-24 用户要求
                                    // 移除 CarPToCarL 自己的隐形屏画面）：手机原生 CarLife+ 的
                                    // H.264 直接送进车机通道，不做 GL 合成、不重编码。
                                    // 码流在 App 启动时（CarLifeHijack.prewarm）就已开始接收并
                                    // 缓存 GOP，这里只需把数据端口接上再把缓存帧灌进去。
                                    val target = videoSender
                                    if (target == null) {
                                        appendLog("MODE: 视频通道未创建，本轮无画面")
                                    } else if (target.startPassthrough()) {
                                        appendLog("MODE: CarLife 直通（车机画面 = 本机 CarLife+）")
                                        // 引擎**实编**尺寸只有 SPS 说得准：98311 申请值/65544
                                        // 回包值都只是「谈定的档位」，引擎会按白名单降档
                                        // （申请 1412×654 实编 1280×720，2026-10-29 实测）。
                                        // 界面与落盘一律跟真值走，否则「码流同尺寸」是假的。
                                        CarLifeHijack.onActualStreamSize = { w, h ->
                                            activeStreamWidth = w
                                            activeStreamHeight = h
                                            appContext?.let { ctx ->
                                                DisplayPrefs.updateLastStream(ctx, w, h)
                                            }
                                            val negotiated = activeCarWidth > 0 &&
                                                (w != activeCarWidth || h != activeCarHeight)
                                            appendLog(
                                                "DISPLAY: 实际码流 ${w}×$h" +
                                                    if (negotiated) {
                                                        "（引擎降档，车机画布 " +
                                                            "${activeCarWidth}×${activeCarHeight}）"
                                                    } else {
                                                        ""
                                                    },
                                            )
                                        }
                                        // attach 之前取累计送达基准：「本次会话有没有送出过帧」以它为准。
                                        // 缓存回放（FirstFramePolicy）会在这里之后立刻 +N 帧，
                                        // 于是「已回放画面」不会被误判成「没有 CarLife 画面」。
                                        videoSessionFrameBase = CarLifeHijack.framesDelivered
                                        CarLifeHijack.attach(target) { line -> appendLog(line) }
                                        ScreenDimmer.setSessionActive(true)
                                    } else {
                                        appendLog("MODE: 数据端口未就绪，直通启动失败")
                                    }
                                    if (!CarLifeHijack.isStreaming) {
                                        appendLog(
                                            "MODE: 尚未收到 CarLife 画面——先在 CarLife+ 里进入" +
                                                "「无线连接」，画面会自动接上（无需重连车机）",
                                        )
                                    }
                                    // 连上就立刻给车机屏幕一个反馈（不等 20s 首帧门超时）
                                    startCarlifeWaitNotice()
                                    startFirstFrameGate()
                                    startScreenHandoverGate(client)
                                }
                                // 音频通道（可选）：SETUP type=100 → UDP RTP 推流。
                                // 失败不影响视频主链路。
                                if (streamOrder == StreamOrderMemory.Order.AUDIO_FIRST) {
                                    // 音频已在本轮 RECORD 之前 SETUP 完成：那次 RECORD 就是
                                    // 同时纳入视频+音频的那一次，**绝不能再补发**——JYBOX-80
                                    // 对「流后补 RECORD」直接 500 并关掉 AirPlay 服务（issue #14）。
                                    if (audioStartedBeforeRecord) {
                                        appendLog("AUDIO: 音频已随本次 RECORD 一同激活（未补发 RECORD）")
                                    }
                                    if (MIC_PROBE_ENABLED && audioStartedBeforeRecord) runMicProbe(client)
                                } else {
                                    val audioStarted =
                                        startAudioChannelAndLog(client, sessionKey, host, context)
                                    if (audioStarted) {
                                        // 流在 RECORD 之后加入：AirPlay 语义要求再发一次 RECORD
                                        // 把新流纳入已激活的会话。缺这一步时车机对 SETUP 回 200
                                        // 并给 dataPort，但不会真正播放（2026-10-17 奥迪实测：
                                        // 音频包持续发送、采集内容非零，车机无声）。
                                        appendLog("AUDIO: 音频流已加入——补发 RECORD 激活（流后加入会话）")
                                        if (!client.record()) {
                                            // 这台接收端受不了「流后补 RECORD」：记住它，下一轮
                                            // 改走音频优先（先 SETUP 音频再一次 RECORD）。本轮
                                            // 已经废了（它多半会顺手关掉 AirPlay 服务），没有
                                            // 在轮内换序的意义。
                                            StreamOrderMemory.notePostRecordRejected(appContext, receiverIdentity)
                                            appendLog(
                                                "STREAM: 补发 RECORD 被拒——已记住该接收端要用音频优先，" +
                                                    "下一轮改为「先 SETUP 音频再一次 RECORD」",
                                            )
                                        }
                                        if (MIC_PROBE_ENABLED) runMicProbe(client)
                                    }
                                }
                            } else {
                                // 屏幕流 SETUP 失败：会话已经死了，但旧实现到这里什么都不说，
                                // 日志只剩一行「本轮小结」——用户看到的却是「连上了又断」。
                                appendLog(
                                    "AIRPLAY: 主屏流 SETUP 失败——本轮不会有画面（事件口" +
                                        (if (eventChannelOk) "已连上" else "不通") +
                                        "、/info " + (if (setup.displayFromCar) "有 displays" else "无 displays") +
                                        "）；若反复如此请在车机上重启 CarPlay",
                                )
                                phaseHint = "车机拒绝了屏幕流（SETUP）"
                                uiHintSink?.invoke("车机拒绝了屏幕流（SETUP）——若反复如此请重启车机侧 CarPlay")
                                runCatching { client.close() }
                            }
                        } else {
                            // 会话级 SETUP 被拒（典型：接收端按设备过滤回 403，见
                            // AirPlayClient.runSetup 的 deviceID 说明）。主动关闭控制通道：
                            // 否则接收端要等约 45s 视频超时才拆 BT 链路，用户只看到
                            // 「连上之后自动断开」，无从判断是会话建立失败。
                            phaseHint = "接收端拒绝了会话建立（SETUP 未通过）"
                            appendLog("AIRPLAY: 会话建立失败——主动关闭控制通道，本轮结束")
                            uiHintSink?.invoke("接收端拒绝了会话建立（SETUP）——请查看日志中的 deviceID 提示")
                            runCatching { client.close() }
                        }
                    }
                } catch (error: Throwable) {
                    // 配对线程任何异常都不能留下「已有会话在处理中」的卡死状态
                    appendLog("AIRPLAY: 配对线程异常 - ${error.message ?: error.javaClass.simpleName}")
                } finally {
                    // 阶段汇总：成功也打——「这次连接一共多久、花在哪」是用户最常问的问题
                    // （2026-11-01 日志 090105 就是靠人工掐时间才拆出 29.7s 的构成）。
                    // 没推起来的一轮改用 end()：多一行「✗ 卡在哪个阶段」的结论，并把计时器
                    // 关掉（避免保活/重试路径上又打一次同一份小结）。推流中则保持计时，
                    // 由后续失败/结束时再收尾。
                    if (videoSender?.isRunning != true) {
                        connectBudget.end(
                            cause = phaseHint ?: "",
                            keywords = "SETUP / /info / 配对 / 事件通道",
                        )
                    } else {
                        appendLog(connectBudget.summary())
                    }
                    sessionStarting.set(false)
                    // 认证轮次结算（「只复用网络、认证重做」的判据来源）。
                    // 用户主动取消 / 被新一轮顶掉不算车机失败——拿它推「认证重做」
                    // 只会在本来能连的车机上白多花一轮 120s 配对窗口。
                    authReceiverKey?.let { key ->
                        val established = videoSender?.isRunning == true
                        when {
                            established -> freshAuthPolicy.onResult(key, true)
                            !connectCancelled ->
                                if (freshAuthPolicy.onResult(key, false)) {
                                    appendLog(
                                        "RETRY: 同一车机已连续 ${FreshAuthPolicy.DEFAULT_FAILURE_THRESHOLD} 轮" +
                                            "未建立会话——下一轮认证重做（不再沿用上一次的配对结论）",
                                    )
                                }
                        }
                    }
                    // 配对未成功建立推流（fail/异常/start 失败提前退出）时解除静默降噪，
                    // 否则 BT-C 会永久停在门闸外、连真车路径失效。
                    // 推流活跃时保持关闭，由 VideoSender.onEnded（推流终止）负责解除。
                    if (videoSender?.isRunning != true) {
                        BtSessionGate.sessionActive = false
                        // 收尾竞态：晚到 0x4301 若恰好在「配对已失败、互斥尚未释放」的
                        // 窗口内到达，会被暂存但没人消费。此处补投一次——车机等 15s
                        // 无人连入就拆链，错过这一投就是整轮失败（2026-10-18 分析）。
                        val pending = pendingEndpointUpdate
                        if (pending != null && !connectCancelled) {
                            pendingEndpointUpdate = null
                            appendLog(">>> 收尾时发现暂存的晚到 0x4301——立即按新端点重走一轮")
                            Bg.run("late-endpoint-retry") {
                                onCarPlayEndpoint(pending, "0x4301 晚到（本轮收尾补投）")
                            }
                        } else {
                            // 既没连上、也没有晚到端点可补投：按退避自动重试
                            // （旧实现到此就收工，等用户自己再点一次——MBUX 那 11 分钟
                            // 零动作就是这么来的）
                            scheduleConnectRetry("本轮未建立会话")
                        }
                    } else {
                        // 起来了：重试计数归零，下次断链重新从 3s 起算
                        synchronized(connectRetryLock) { connectRetryAttempt = 0 }
                    }
                }
            }
        }
        // 已绑定过网络（autoJoinCarWifi 刚绑好并重注册过）就不要再走一遍加入流程：
        // WifiCarPlayConnector 内部先 release() → bindProcessToNetwork(null)，
        // 会把刚注册好的 ctrl mDNS 广播打断，再等系统重建绑定。
        val wifiConfig =
            Iap2SenderSession.WifiConfiguration(
                endpoint.ssid,
                endpoint.passphrase,
                endpoint.channel,
                endpoint.securityType,
            )
        // 被动断开后的自动重绑要的是「完整凭据」，而 0x4301 里就有一份（BMW iD9 /
        // 通用车机根本不发 0x5703，AP 信息只走这条路）。旧实现只在 onWifiCredentials
        // 里记 lastCarWifi —— 这类车型上 onCarWifiLost 永远走「没凭据，只能提示」，
        // 1.2.10 加的自动重绑对它们等于不存在（只有 0x5703 车型才生效）。
        lastCarWifi = wifiConfig
        if (boundCarSsid == endpoint.ssid && wifiConnector?.currentNetwork != null) {
            appendLog(">>> [$source] 已绑定车机热点 ${endpoint.ssid}，跳过重复绑定（只复用网络，认证照常重做）")
            continueWithNetwork(true)
        } else {
            // 端点先于 0x5703 到达（或上一轮换网失败）：同样走应用专属网络。
            // 不能再用「按 TRANSPORT_WIFI 盲绑当前任意 WiFi」——切网途中会绑到旧网络，
            // AirPlay 必然连不上，且会把无互联网热点留在系统默认网络里。
            connectBudget.enter(ConnectStage.WIFI)
            Bg.run("wifi-join-on-endpoint") {
                // 第一步永远是登记「WiFi 建议」——幂等、不等结果、不弹框。
                // 用户授权过一次之后，系统会在进入范围时自动关联车机热点，
                // 届时下面那条 specifier 请求会立刻拿到 onAvailable（网络已存在，
                // 系统不再要求确认），整条链路才能真正做到「上车即连」。
                val connector = wifiConnector
                connector?.registerSuggestion(wifiConfig.ssid, wifiConfig.passphrase, wifiConfig.securityType)
                while (!connectCancelled) {
                    // Shizuku 已移除：没有 shell 特权。
                    val shellReady = false
                    when (val result = joinCarWifiSpecifier(wifiConfig, shellReady)) {
                        is WifiCarPlayConnector.JoinResult.Joined -> {
                            continueWithNetwork(true)
                            return@run
                        }

                        WifiCarPlayConnector.JoinResult.Unsupported -> {
                            // WEP 等无法用 specifier 表达：等系统自己关联上（无人工操作）
                            waitForAssociationThenContinue(wifiConfig) { bound ->
                                continueWithNetwork(bound)
                            }
                            return@run
                        }

                        WifiCarPlayConnector.JoinResult.Unavailable -> {
                            // 三层路径（复用 → 静默建议 → 确认框）都没成：静默再试几轮。
                            // 旧实现在这里弹「重试 / 打开 WLAN 设置」问用户，车上没人点屏
                            // 就按无人应答中止——现在改成自动做，只是限次。
                            retryJoinSpecifierSilently(wifiConfig, shellReady)
                            if (boundCarSsid == wifiConfig.ssid && wifiConnector?.currentNetwork != null) {
                                continueWithNetwork(true)
                            } else {
                                sessionStarting.set(false)
                            }
                            return@run
                        }
                    }
                }
                sessionStarting.set(false)
            }
        }
    }

    /**
     * specifier 表达不了的安全类型（WEP）时的兜底：**等系统自己关联**上目标热点，而后绑定。
     *
     * 2026-11-08 起不再跳 WLAN 设置页、不再提示「请手动加入」——那条路要人点，与「静默
     * 自动连」相反。这里只安静轮询：手机若已在热点上（手动连过 / 系统自动连）就绑上继续，
     * 否则超时后交给下一轮。
     */
    private fun waitForAssociationThenContinue(
        wifi: Iap2SenderSession.WifiConfiguration,
        onBound: (Boolean) -> Unit,
    ) {
        val deadline = android.os.SystemClock.uptimeMillis() + CAR_WIFI_BIND_WAIT_MS
        var lastHintAt = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() < deadline && !connectCancelled) {
            val current = shellWifiStatus().second
            if (current == wifi.ssid) {
                onBound(bindCarWifiAndRegister(wifi.ssid))
                return
            }
            if (android.os.SystemClock.uptimeMillis() - lastHintAt > 5_000) {
                lastHintAt = android.os.SystemClock.uptimeMillis()
                phaseHint = "等待手机加入车机热点 ${wifi.ssid}…（无需操作）"
                appendLog("WIFI: 仍在等手机关联车机热点（当前 ${current ?: "未连接"}）…")
            }
            Thread.sleep(1_000)
        }
        if (!connectCancelled) {
            phaseHint = "手机不在车机热点——下一轮连接会自动再试"
            appendLog(">>> 等待车机热点超时（该热点安全类型较旧，无法主动请求关联）")
        }
        onBound(false)
    }

    /** 触摸/隧道事件低频计数（迁移自 MainActivity 字段，保持日志节流行为一致）。 */
    @Volatile
    private var touchLogCount = 0

    /** 车机媒体键「释放」报文计数（值为 0 的 1 字节报告，静默丢弃，仅计数备查）。 */
    @Volatile
    private var consumerReleaseCount = 0

    /**
     * 「车机发了报告、但我们映射不出 usage」的计数（限流打日志用）。
     *
     * 语音键排查最需要这条：报告值非 0 却映射不到 usage，只有两种原因——描述符里没声明
     * 这一项（下标越界），或该设备的报文不是 Array 语义。以前这里完全静默，症状是
     * 「按了语音键，日志一个字都没有」，既分不清「车机没发」还是「我们没认」。
     */
    @Volatile
    private var consumerUnknownCount = 0

    /**
     * `hidSendReport` 通路最近一次按下的 Consumer usage（1.2.22）。
     *
     * 释放报文只有一个 0x00，不带 usage；语音键的 VR_STOP 收尾要靠它判断
     * 「刚才松开的是不是语音键」，否则松开「下一首」也会把语音识别掐掉。
     */
    @Volatile
    private var lastConsumerUsage: Int? = null

    @Volatile
    private var iapEventMessageCount = 0

    /**
     * 车机事件通道收到的命令条数（任何 `type` 都算）。
     *
     * 用途见 [startScreenHandoverGate]：车机解不出画面时会主动回落 `forceKeyFrame`，
     * 0 条命令就说明它连「解码」这一步都没进——这是判断「黑屏在车机侧还是在码流侧」
     * 最省事的一条证据，2026-11-06 那轮全靠人工比对才看出来。
     */
    @Volatile
    private var carEventCount = 0

    /** 车机发过的事件命令 `type` 集合（去重，仅日志诊断用）。 */
    private val carEventTypes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 最近一次收到车机事件命令的 `uptimeMillis`（0 = 从没收到）。 */
    @Volatile
    private var lastCarEventAtUptime = 0L

    /**
     * 本次视频会话「送出第一帧」的累计基准（attach **之前**取）。
     *
     * `CarLifeHijack.framesDelivered` 跨会话累加、从不归零，所以「本次有没有送出过帧」
     * 只能用「当前值 - 本基准」判断。缓存回放（[io.github.shaonialife.carptocar.video.FirstFramePolicy]）
     * 也会让它增长，因此基准必须在 attach 之前取——否则会拿提示帧盖掉刚回放出来的画面。
     */
    @Volatile
    private var videoSessionFrameBase = 0L

    /** 最近一次向车机推送「没有 CarLife 画面」提示的时刻（`uptimeMillis`；0 = 没推过）。 */
    @Volatile
    private var carlifeNoticePushedAt = 0L
}

/** 配对失败后的兜底路径决策（纯函数，真机抓包场景由 PairingFallbackTest 钉死）。 */
internal data class PairingFallback(
    /** 「断 BT 复现控制结束再试」二段 hack（仅测试台 DiPlay 形态）。 */
    val useBtRetireHack: Boolean,
    /** et=NONE 明文会话降级（pre-HomeKit 老车机 / 配对全拒但 RTSP 连上过的兜底）。 */
    val usePlainSession: Boolean,
)

/**
 * 决策依据（2026-10-21 真机 sysdiagnose，docs/PROTOCOL-NOTES.md §13a）：
 * 任意阶段 403 = 「iAP2 信任已建立的车机在等自身就绪」——首连被拒是车机固有状态，
 * 它 18~20s 后自动重邀、第二次即成。此状态下断 BT（整栈重建 30~40s，恰好错过重邀
 * 窗口）与明文降级（必被拒且空耗重邀窗口）都是反向操作，两条兜底一律禁用；
 * 配对循环以 5s 退避在窗口内原地重试（见 pairingRetryBackoffMs）。
 */
internal fun pairingFallback(
    keysEstablished: Boolean,
    noPairing: Boolean,
    pairSetupRejected: Boolean,
    pairingForbidden: Boolean,
    attemptedPairing: Boolean,
    pairingUnsupported: Boolean,
    /**
     * 车机**确定性**拒绝配对（TLV `{Error:2}` 这类，见 [PairingPolicy]）。
     *
     * 影响只有一条：禁止「断 BT 二段 hack」。那个 hack 的前提是「换个链路层状态可能就好」，
     * 而确定性拒绝是车机侧的判断——断 BT 只会再失败一次，真车还会顺手拆掉 BT 控制通道
     * （即空间接失效）。明文会话照旧兜底，因为画面不需要共享密钥。
     */
    pairingTerminal: Boolean = false,
): PairingFallback {
    if (keysEstablished) return PairingFallback(useBtRetireHack = false, usePlainSession = false)
    val useBtRetireHack =
        !pairingTerminal && !noPairing && !pairSetupRejected && !pairingForbidden && attemptedPairing
    val usePlainSession = !pairingForbidden && (pairingUnsupported || attemptedPairing)
    return PairingFallback(useBtRetireHack = useBtRetireHack, usePlainSession = usePlainSession)
}
