package io.github.shaonialife.carptocar

import android.content.Context
import android.view.KeyEvent
import io.github.shaonialife.carptocar.airplay.AirPlayClient
import io.github.shaonialife.carptocar.airplay.PairVerifyClient
import io.github.shaonialife.carptocar.airplay.VirtualIap2Link
import io.github.shaonialife.carptocar.audio.AudioProfile
import io.github.shaonialife.carptocar.audio.AudioProfileMemory
import io.github.shaonialife.carptocar.audio.AudioSender
import io.github.shaonialife.carptocar.audio.AudioTuning
import io.github.shaonialife.carptocar.bt.BtProfiles
import io.github.shaonialife.carptocar.bt.BtSessionGate
import io.github.shaonialife.carptocar.bt.Iap2BluetoothClient
import io.github.shaonialife.carptocar.bt.Iap2BluetoothServer
import io.github.shaonialife.carptocar.bt.TargetDevicePrefs
import io.github.shaonialife.carptocar.capture.HiddenDisplaySource
import io.github.shaonialife.carptocar.carlife.CarLifeHijack
import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2MessageIds
import io.github.shaonialife.carptocar.iap2.Iap2NowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import io.github.shaonialife.carptocar.iap2.ReceiverProfile
import io.github.shaonialife.carptocar.input.ConsumerKeys
import io.github.shaonialife.carptocar.input.TouchReports
import io.github.shaonialife.carptocar.input.TouchRouter
import io.github.shaonialife.carptocar.launcher.settings.AppOrientationCompat
import io.github.shaonialife.carptocar.launcher.settings.DisplayPrefs
import io.github.shaonialife.carptocar.media.MediaSessionsState
import io.github.shaonialife.carptocar.media.NotificationAccess
import io.github.shaonialife.carptocar.net.AirPlayFeatures
import io.github.shaonialife.carptocar.net.CarPlayCtrlAdvertiser
import io.github.shaonialife.carptocar.net.CarPlayServiceBrowser
import io.github.shaonialife.carptocar.net.EndpointHosts
import io.github.shaonialife.carptocar.net.WifiCarPlayConnector
import io.github.shaonialife.carptocar.net.WifiP2pCarPlayConnector
import io.github.shaonialife.carptocar.net.WifiP2pGroupNames
import io.github.shaonialife.carptocar.net.WifiSavedNetworks
import io.github.shaonialife.carptocar.service.CarPlayService
import io.github.shaonialife.carptocar.ui.ScreenDimmer
import io.github.shaonialife.carptocar.util.Bg
import io.github.shaonialife.carptocar.util.DebugFileLog
import io.github.shaonialife.carptocar.util.SessionTrace
import io.github.shaonialife.carptocar.util.SysShell
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
     * 等应用专属网络（`WifiNetworkSpecifier`）确认框的上限。
     *
     * **必须与车机的等待窗口对齐**：BMW iD9（IDC23）实测在交出 0x4301 后约 27 秒
     * 就拆蓝牙链（2026-10-29，连续 6 轮 25~28s）。旧值 120s 意味着每轮都要白等到
     * 车机先放弃，期间还会叠上下一轮的新请求（旧线程仍在 latch 上转），表现成
     * 「连上就闪退 + 后续越连越连不上」。15s 留出车机窗口内再试一次的余地。
     */
    private const val SPECIFIER_JOIN_TIMEOUT_MS = 15_000L

    /**
     * 建议网络（[registerSuggestion]）生效后，等系统自动关联车机热点的上限。
     * 建议已被用户授权时系统通常 1~3 秒内就连上，比 specifier 快且无弹框。
     */
    private const val SUGGESTION_JOIN_WAIT_MS = 8_000L

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
     * Wi-Fi Direct 加入通道。只有车机用 P2P GO（组名 `DIRECT-` 前缀）时才会用到；
     * 自带热点的车走 [wifiConnector]，这条保持空闲。
     */
    private var p2pConnector: WifiP2pCarPlayConnector? = null

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

    /** DCL 双检锁的可发布字段（无 volatile 的双检锁是坏发布——虽然当前仅主线程调用，别留隐患）。 */
    @Volatile
    private var bluetoothServer: Iap2BluetoothServer? = null
    private var bluetoothClient: Iap2BluetoothClient? = null
    private var ctrlAdvertiser: CarPlayCtrlAdvertiser? = null

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

    /** 本会话目标帧率：2026-10-25 起固定 60（[DisplayPrefs.FPS]），不再有设置项。 */
    @Volatile
    var activeTargetFps: Int = DisplayPrefs.FPS

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
     * 通话结束后多久重建音频流（ms）。
     * 车机从「通话」切回「媒体」自身有 1~3 秒的资源仲裁过渡，立刻重建会和它抢音频通路，
     * 取 2.5 秒让车机先把通话通路收干净。
     */
    private const val AUDIO_REBUILD_DELAY_MS = 2_500L

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
     * 最近一次 0x5703 的热点密码（仅内存，不落盘）。
     * 「车机热点 → 去连接」按钮复制到剪贴板用（手动加入兜底场景）。
     */
    @Volatile
    var lastWifiPsk: String? = null

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
                    // 应用专属网络被系统断开（车机熄火/离开范围）：明确告知用户，
                    // 而不是让英雄卡在「已连接」与失败之间静默摇摆
                    connector.onNetworkLost = { ssid ->
                        boundCarSsid = null
                        phaseHint = "车机热点已断开（$ssid）"
                        uiHintSink?.invoke("车机热点 \"$ssid\" 已断开——请重新启动连接")
                        appendLog(">>> 车机热点 \"$ssid\" 已断开——本次会话需重新连接")
                    }
                }
            p2pConnector = WifiP2pCarPlayConnector(context) { line -> appendLog(line) }
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
            ctrlAdvertiser = CarPlayCtrlAdvertiser(context) { line -> appendLog(line) }
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
            bluetoothServer?.isRunning == true ||
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
    }

    /** 「断开连接」（可在任意 Activity 实例上调用——操作的是真管线对象）。 */
    fun disconnect(context: Context) {
        val hadProjection = CarPlayService.mediaProjection != null
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
        p2pConnector?.release()
        boundCarSsid = null
        if (hadProjection) {
            // Android 14+：MediaProjection 与保活服务（mediaProjection FGS 类型）绑定，
            // 停服务会连带失效授权——下次连接需重新点「申请投屏权限」
            appendLog("已手动断开并停止保活服务（常亮已恢复）；投屏授权已随服务停止失效，下次连接如需声音请重新申请")
        } else {
            appendLog("已手动断开并停止保活服务（常亮已恢复）")
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
            appendLog("0x5703 冗余凭据（会话推流中）——保持现有 WiFi 绑定与 ctrl 广播不变")
            return
        }
        ssidSink?.invoke(wifi.ssid)
        lastWifiPsk = wifi.passphrase
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
     * 校对真实 WiFi 并加入车机热点（Android 10+ NetworkSpecifier，应用专属网络）：
     *  1. `cmd wifi status` 查当前 SSID——已是车机热点直接绑定（快路径）；
     *  2. 否则 `cmd wifi connect-network` 保存并切换（免系统弹窗、免输密码；
     *     网络被系统保存，下次进入热点范围自动重连，契合上车即连）；
     *  3. 轮询 SSID 确认切换完成后再绑定，各环节检查 [connectCancelled]——
     *     用户取消后不得继续跑（否则 phaseHint 被覆盖、ctrl 广播被重新拉起，表现为「取消不了」）。
     * 连接失败时回退「系统 WLAN 手动加入」指引（密码可经「去连接」复制）。
     */
    private fun autoJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        Bg.run("wifi-auto-join") {
            // Shizuku 已移除：没有 shell 特权，直接走「应用专属网络」路径（无需特权）。
            val shellReady = false
            // 1) WLAN 开关。shell 不可用时跳过检查——requestNetwork 会以 Unavailable 反馈。
            if (shellReady && shellWifiDisabled() && !waitForWifiEnabled()) return@run
            if (connectCancelled) return@run
            // 1.5) 登记 WiFi 建议（幂等）：用户授权过一次后，系统会自己把手机连上
            // 车机热点，后续不再出现「连接到此网络？」确认框——这是无人点屏场景的
            // 唯一可行路径（BMW iD9 只给 27s，人来不及点就断链）。
            wifiConnector?.registerSuggestion(wifi.ssid, wifi.passphrase, wifi.securityType)
            // 2) 首选应用专属网络（不影响 4G/5G、不会被系统切回、不需要任何特权）
            //    例外：P2P GO 的组名（DIRECT-*）站模式关联不进去，必须先走 Wi-Fi Direct。
            if (WifiP2pGroupNames.isP2pGroupName(wifi.ssid)) {
                when (val p2p = joinCarWifiViaP2p(wifi)) {
                    is WifiP2pCarPlayConnector.Result.Joined -> return@run
                    else -> appendLog(
                        "WIFI: Wi-Fi Direct 加入未成功（$p2p）——继续尝试应用专属网络",
                    )
                }
            }
            when (val result = joinCarWifiSpecifier(wifi, shellReady)) {
                is WifiCarPlayConnector.JoinResult.Joined -> Unit

                WifiCarPlayConnector.JoinResult.Unsupported -> {
                    appendLog("WIFI: 热点安全类型 ${wifi.securityType} 无法用应用专属网络表达——退回系统级加入")
                    legacyJoinCarWifi(wifi, shellReady)
                }

                WifiCarPlayConnector.JoinResult.Unavailable -> promptSpecifierFailed(wifi, shellReady)
            }
        }
    }

    /**
     * 以 Wi-Fi Direct 加入车机自建的 P2P 组（组名 `DIRECT-*`）。
     *
     * 与站模式（[joinCarWifiSpecifier]）的关键差别在「谁管加密」：P2P 组由
     * `WifiP2pManager` 协商密钥并拉 p2p 接口，站模式连不进去。所以这里不走
     * `WifiNetworkSpecifier`，改由 [WifiP2pCarPlayConnector] 完成扫描/连接/绑定。
     *
     * 成功后与站模式一样要重注册 ctrl 广播——车机靠 `_carplay-ctrl` 找到手机，
     * 绑定完再注册才不会丢第一轮邀请。
     */
    private fun joinCarWifiViaP2p(wifi: Iap2SenderSession.WifiConfiguration): WifiP2pCarPlayConnector.Result {
        val connector = p2pConnector ?: return WifiP2pCarPlayConnector.Result.NotApplicable
        phaseHint = "正在以 Wi-Fi Direct 加入 ${wifi.ssid}…"
        appendLog(
            ">>> \"${wifi.ssid}\" 是 Wi-Fi Direct 组（DIRECT- 前缀）——改用 P2P 加入，" +
                "BSSID=${wifi.bssid ?: "(车机未给)"} channel=${wifi.channel}",
        )
        val result =
            connector.joinGroup(
                ssid = wifi.ssid,
                passphrase = wifi.passphrase,
                bssidHint = wifi.bssid,
                channelHint = wifi.channel,
                isCancelled = { connectCancelled },
            )
        if (result is WifiP2pCarPlayConnector.Result.Joined) {
            boundCarSsid = wifi.ssid
            phaseHint = "已加入车机热点（Wi-Fi Direct）"
            ctrlAdvertiser?.ensureForNetwork(wifi.ssid, force = true, network = result.network)
            appendLog(">>> 已通过 Wi-Fi Direct 加入 \"${wifi.ssid}\"，ctrl 广播已重新注册，等待车机 ctrl 邀请")
        }
        return result
    }

    /**
     * 用应用专属网络（`WifiNetworkSpecifier`）加入车机热点，成功后进程网络已绑定。
     *
     * 先清掉系统里保存的同名热点：旧版本用 `cmd wifi connect-network` 存过它，
     * 只要还在系统配置里，系统就会自动全局连接——无互联网热点于是被当成默认网络，
     * 部分 ROM 连带断掉移动数据，正是本次要解决的场景。
     */
    private fun joinCarWifiSpecifier(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ): WifiCarPlayConnector.JoinResult {
        val connector = wifiConnector ?: return WifiCarPlayConnector.JoinResult.Unavailable
        // 已保存到系统配置里才询问是否清除：不擅自删用户网络（有的场景就是要用
        // 车机流量/互联网，或用户希望系统自动连）。选择保留不影响本轮连接。
        if (shellReady) {
            promptForgetSavedNetworkIfAny(wifi.ssid)
            if (connectCancelled) return WifiCarPlayConnector.JoinResult.Unavailable
        }
        phaseHint = "正在加入车机热点 ${wifi.ssid}…（不影响移动数据）"
        appendLog(
            ">>> 以应用专属网络加入 \"${wifi.ssid}\"（不保存为系统 WiFi，其它应用继续走 4G/5G）",
        )
        val result =
            synchronized(wifiBindLock) {
                val existing = connector.currentNetwork
                if (boundCarSsid == wifi.ssid && existing != null) {
                    appendLog("WIFI: 应用专属网络已在位——跳过重复加入")
                    WifiCarPlayConnector.JoinResult.Joined(existing)
                } else {
                    connector.joinCarPlayNetwork(
                        ssid = wifi.ssid,
                        passphrase = wifi.passphrase,
                        securityType = wifi.securityType,
                        timeoutMillis = SPECIFIER_JOIN_TIMEOUT_MS,
                        isCancelled = { connectCancelled },
                    )
                }
            }
        if (result is WifiCarPlayConnector.JoinResult.Joined) {
            boundCarSsid = wifi.ssid
            phaseHint = "已加入车机热点"
            // 关键时机：bindProcessToNetwork 完成后再注册，车机才能看到 ctrl 广播
            ctrlAdvertiser?.ensureForNetwork(wifi.ssid, force = true, network = connector.currentNetwork)
            appendLog(">>> WiFi 已加入车机热点（应用专属网络），ctrl 广播已重新注册，等待车机 ctrl 邀请")
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
     * 系统里已保存同名热点时，询问用户是否清除（**不擅自删除**）。
     *
     * 为什么要问而不是直接删：保留也有正当用途——用户可能就想让系统自动连车机、
     * 或车机热点确实带互联网（部分车型提供流量）。删除只影响「系统会不会全局
     * 自动连它」，与本轮连接无关；选择「保留」时继续正常连接。
     *
     * 每个 SSID 每个进程只问一次：配对失败重试、端点交接重入都会再次走到这里，
     * 重复弹窗会挡住连接（2026-10-18 实测）。用户的选择在本次进程内记忆。
     *
     * 阻塞直到用户选择（[JOIN_DIALOG_TIMEOUT_S] 超时按「保留」处理——不做破坏性动作）。
     */
    private fun promptForgetSavedNetworkIfAny(ssid: String) {
        if (!forgetPromptHandled.add(ssid)) return
        val ids = savedNetworkIds(ssid)
        if (ids.isEmpty()) return
        appendLog(
            "WIFI: 系统里已保存热点 \"$ssid\"（netId=${ids.joinToString(",")}）" +
                "——询问用户是否清除（保留也能正常连接）",
        )
        val forget =
            askWifiJoinChoice(
                DialogSpec(
                    title = "已保存该车机热点",
                    message =
                        "系统里保存过 \"$ssid\"。保留会让手机在进入范围时自动连接它，" +
                            "若该热点没有互联网，可能导致手机无法使用移动数据。\n\n" +
                            "「清除」只是不再自动连接，不影响本次使用；" +
                            "如果车机热点本身提供网络，选「保留」。",
                    positive = "清除保存",
                    negative = "保留",
                ),
            )
        if (connectCancelled) return
        when (forget) {
            true -> {
                ids.forEach { id ->
                    val result = SysShell.exec("cmd wifi forget-network $id", 5_000L)
                    appendLog(
                        "WIFI: 已按用户选择清除系统级保存的热点 \"$ssid\"（netId=$id ok=${result.ok}）",
                    )
                }
            }

            false -> appendLog("WIFI: 用户选择保留系统保存的 \"$ssid\"——继续以应用专属网络连接")

            // 无人应答（UI 不在前台）：按「保留」处理，绝不在用户未确认时删网络
            null -> appendLog("WIFI: 无人应答是否清除保存的网络——按保留处理（不改用户配置）")
        }
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

    /** 应用专属网络被系统拒绝/超时：让用户重试或转手动连接。 */
    private fun promptSpecifierFailed(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ) {
        if (connectCancelled) return
        val choice =
            askWifiJoinChoice(
                DialogSpec(
                    title = "未能加入车机热点",
                    message =
                        "系统没有允许加入 \"${wifi.ssid}\"（可能点了拒绝，或热点不在范围内）。\n\n" +
                            "「重试」会再次弹出系统确认框。也可以到 WLAN 设置手动连接——" +
                            "手动连接会把热点保存为系统网络，可能影响移动数据。",
                    positive = "重试",
                    negative = "打开 WLAN 设置",
                ),
            )
        if (connectCancelled) return
        when (choice) {
            true -> {
                when (val retry = joinCarWifiSpecifier(wifi, shellReady)) {
                    is WifiCarPlayConnector.JoinResult.Joined -> Unit
                    WifiCarPlayConnector.JoinResult.Unsupported -> legacyJoinCarWifi(wifi, shellReady)
                    WifiCarPlayConnector.JoinResult.Unavailable -> {
                        phaseHint = "未能加入车机热点——请到 WLAN 设置手动连接"
                        appendLog("WIFI: 重试仍未加入 \"${wifi.ssid}\"——请到系统 WLAN 手动连接")
                    }
                }
            }

            false -> manualJoinCarWifi(wifi)

            null -> {
                phaseHint = "未能加入车机热点——请重新连接"
                appendLog("WIFI: 弹窗无人应答——自动加入中止")
            }
        }
    }

    /** 手动兜底：密码进剪贴板 + 跳 WLAN 设置，轮询用户是否连上。 */
    private fun manualJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        copyHotspotPsk(wifi.passphrase)
        openWlanSettings()
        phaseHint = "请在 WLAN 设置中连接 ${wifi.ssid}…"
        appendLog(
            "WIFI: 用户选择手动连接——等待 WLAN 连上 \"${wifi.ssid}\"（最长 120 秒）；" +
                "注意：手动连接会把该热点保存为系统网络，若它没有互联网可能影响移动数据",
        )
        val manualDeadline = android.os.SystemClock.uptimeMillis() + WLAN_WAIT_TIMEOUT_MS
        while (android.os.SystemClock.uptimeMillis() < manualDeadline) {
            if (connectCancelled) return
            Thread.sleep(5_000)
            if (shellWifiStatus().second == wifi.ssid) {
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
        }
        phaseHint = "尚未连接 ${wifi.ssid}——自动加入已中止"
        appendLog("WIFI: 手动连接等待超时——自动加入中止")
    }

    /**
     * 旧路径（WEP 等 specifier 无法表达的安全类型）：`cmd wifi connect-network`
     * 系统级保存并切换。注意这会保存热点为系统网络——无互联网时可能影响移动数据，
     * 只作为兼容兜底。
     */
    private fun legacyJoinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ) {
        if (!shellReady) {
            phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${wifi.ssid}"
            appendLog(
                ">>> 手机不在车机热点 \"${wifi.ssid}\" 且无法自动加入（需要手动在系统 WLAN 里连一次）——" +
                    "请到 系统设置>WLAN 手动加入（密码可点「去连接」复制）",
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
        val choice =
            askWifiJoinChoice(
                DialogSpec(
                    title = "连接车机热点",
                    message =
                        "将自动加入 \"${wifi.ssid}\" 热点。该热点无互联网，系统可能自动切回原 WiFi。" +
                            "\n\n出现断连时，请到 WLAN 设置中主动连接 \"${wifi.ssid}\"（手动连接不会被切回）。",
                    positive = "继续自动加入",
                    negative = "打开 WLAN 设置",
                ),
            )
        if (connectCancelled) return
        if (choice == false) {
            manualJoinCarWifi(wifi)
            return
        }
        if (choice == null) {
            phaseHint = "未能加入车机热点——请重新连接"
            appendLog("WIFI: 弹窗无人应答——自动加入中止")
            return
        }
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

    /** 热点密码复制到剪贴板（手动到 WLAN 页连接时粘贴），并提示用户。 */
    private fun copyHotspotPsk(passphrase: String) {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val cm =
                        ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("carplay-wifi", passphrase),
                    )
                    uiHintSink?.invoke("热点密码已复制（${passphrase.length} 位）——粘贴到密码框即可")
                }
            }
        }
    }

    /**
     * WiFi 开关与当前连接的 SSID（一次 `cmd wifi status` 查询）。
     * 输出形如：`Wifi is enabled` + `Wifi is connected to "Redmi K40"`（未连接无此行）。
     */
    private fun shellWifiStatus(): Pair<Boolean, String?> {
        val result =
            SysShell
                .exec("cmd wifi status", 5_000L)
        if (!result.ok) return true to null
        val enabled = !result.output.contains("Wifi is disabled")
        val ssid = Regex("connected to \"(.*)\"").find(result.output)?.groupValues?.get(1)
        return enabled to ssid
    }

    private fun shellWifiDisabled(): Boolean = !shellWifiStatus().first

    /** 单引号包裹的 POSIX shell 字面量（SSID/密码可能含空格与特殊字符）。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 复现「BT 控制已结束」状态：断开当前任一路径的 iAP2 控制会话（DiPlay 只在此状态应答 RTSP）。 */
    private fun detachActiveBtSessions() {
        bluetoothServer?.detachCurrentSession()
        bluetoothClient?.detachCurrentSession()
    }

    /**
     * 空端点等待期（车机声明会发 0x4301 但迟迟未到）的观察任务。
     *
     * 1. 每 5s 轮询当前 WiFi——用户手动连上车机热点后弹窗确认，确认即以该 SSID 合成
     *    端点重走 [onCarPlayEndpoint]（无端口 → mDNS/网关兜底）；
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
                    "期间请保持手机 Wi-Fi 开启；车机多数要看到手机进入它自己的网络后才会下发凭据",
            )
            var promptedSsid: String? = null
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
                                currentSsid.isNullOrEmpty() -> "已开但未连接任何网络"
                                else -> "\"$currentSsid\""
                            },
                    )
                }
                val ssid = if (wifiEnabled) currentSsid else null
                // 同一 SSID 只问一次（用户选「继续等待」后不再打扰）；换网络再问。
                if (ssid.isNullOrEmpty() || promptedSsid == ssid) continue
                promptedSsid = ssid
                val choice =
                    askWifiJoinChoice(
                        DialogSpec(
                            title = "检测到已连接 $ssid",
                            message =
                                "车机尚未经蓝牙下发连接信息（0x4301）。是否把当前网络 \"$ssid\" 当车机热点，" +
                                    "直接尝试 mDNS/网关连接？（点「继续等待」则保持蓝牙保活等车机下发）",
                            positive = "按车机热点继续",
                            negative = "继续等待",
                        ),
                    )
                if (choice == true && !connectCancelled && !sessionStarting.get()) {
                    appendLog(">>> [$source] 用户确认当前网络 \"$ssid\" 为车机热点——以合成端点重走连接")
                    onCarPlayEndpoint(endpoint.copy(ssid = ssid), "$source/手动入网")
                    return@run
                }
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
            if (endpointBrowser?.awaitEndpoint(0) != null) {
                appendLog(
                    "CTRL: 已发现 _airplay._tcp（未收到邀请，旧机型行为）——直接连接 AirPlay",
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
            for (candidate in ordered) {
                val result = client.runAudioStream(sharedSecret, candidate, tuning)
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
            val profile = chosen
            val audio = streamInfo
            if (profile == null || audio == null) {
                appendLog("AUDIO: 全部候选档位协商失败（共 ${ordered.size} 档）——本次仅推视频")
                return false
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
                client.runAudioStream(secret, profile, AudioTuning.RAW_SPF)
            } catch (error: Exception) {
                appendLog("AUDIO: 重建协商异常 - ${error.message}")
                null
            }
        if (info == null) {
            appendLog("AUDIO: 重建协商失败——保持原音频流继续推（不降级成无声）")
            return false
        }
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
                client.runAudioStream(secret, target, AudioTuning.RAW_SPF)
            } catch (error: Exception) {
                appendLog("AUDIO: 切换协商异常 - ${error.message}")
                null
            }
        if (info == null) {
            if (inCall) rejectedCallProfileKey = target.key
            appendLog("AUDIO: 切到 ${target.label} 失败——保持原音频流继续推（不降级成无声）")
            return false
        }
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
                val gateway = wifiConnector?.gatewayAddress()
                if (gateway != null && EndpointHosts.candidates(listOf(gateway)).isNotEmpty()) {
                    appendLog(">>> [$source] mDNS 未发现，兜底使用网关地址 $gateway:7000（车机热点网关=车机）")
                    return effective.copy(
                        ipAddresses = listOf(gateway) + effective.ipAddresses,
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
        val pairingFlag = AirPlayFeatures.supportsPairing(endpoint.advertisedFeatures)
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
                " HomeKit配对=" +
                when (pairingFlag) {
                    true -> "声明支持"
                    false -> "未声明（按 pre-HomeKit 明文会话处理）"
                    null -> "未知（按需配对）"
                },
        )
        // 车机画像（信号收口点）：mDNS 侧信号到达——era 判定 + 依据一行可解释
        appendLog(
            ReceiverProfile.describe(
                ReceiverProfile.classify(
                    ReceiverProfile.Signals(
                        features = endpoint.advertisedFeatures,
                        srcvers = endpoint.sourceVersion,
                    ),
                ),
            ),
        )
        // 先绑网络再解析端点：mDNS 组播跟随 bindProcessToNetwork 的网络，解析放前面
        // 只会拿到旧网络的结果（此前靠 resolveEndpointHost 内部重试掩盖，白等数秒）。
        // 绑定完成后再并行做三件事：ctrl 广播针对新网络重注册、后台发现 _airplay._tcp、
        // 等车机 ctrl 邀请（车机已发现我们的权威信号）。
        val continueWithNetwork: (Boolean) -> Unit = proceed@{ bound ->
            if (!bound) {
                phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${endpoint.ssid}"
                appendLog(">>> [$source] 手机当前不在该热点——请到 设置>WLAN 加入 \"${endpoint.ssid}\" 后断开重连")
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

                    fun newClient() =
                        AirPlayClient(
                            effectiveEndpoint,
                            log = { line -> appendLog(line) },
                            context = context.applicationContext,
                            endpointRefresher = { current -> enrichEndpointFromBrowser(current, source) },
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
                                dispatchMediaKey(keyCode)
                                appendLog("EVENT: 车机媒体键（iAP2 0x6802）→ keyCode=$keyCode 已注入")
                            }
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
                    var client = newClient()
                    currentClient = client
                    drainPendingEndpoint(client)
                    // pre-HomeKit 老车机（CarPlay 210.81 时代，奥迪 4xxx 固件）在
                    // `_airplay._tcp` 里**不声明** HK_PAIRING_AND_ENCRYPT：它们只接受
                    // 明文 SETUP（et=NONE）+ AES-CTR 屏幕流，走 pair-setup 必然失败。
                    // catplay 的接收端分类 1 即此形态；旧实现会在这里空转 120s 配对窗口，
                    // 用户看到的就是「连不上」。
                    val noPairing =
                        AirPlayFeatures.supportsPairing(client.currentEndpoint.advertisedFeatures) == false
                    var keys: PairVerifyClient.ControlKeys? =
                        if (noPairing) {
                            client.skipPairing("TXT features 未声明 HK_PAIRING_AND_ENCRYPT")
                            client.reconnect()
                            null
                        } else {
                            client.runPairing()
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
                        ).usePlainSession
                    if (plainSession) {
                        phaseHint = "建立明文会话…"
                        appendLog(
                            "AIRPLAY: 配对不可用（${if (client.pairingUnsupported) "接收端不支持" else "配对失败"}）" +
                                "——改用 et=NONE 明文会话 + AES-CTR 屏幕流（catplay 接收端分类 1/2）",
                        )
                        // 配对循环结束时 socket 已关/状态机不可用：SETUP 前必须重连一次。
                        // 失败不直接判死：车机 AirPlay 端口可能还没开，下面会按窗口重试。
                        if (!client.reconnect()) {
                            appendLog("AIRPLAY: 明文会话首次重连未成功——继续按窗口重试 SETUP")
                        }
                    }
                    // keys 是 lambda 内的可变捕获变量：Kotlin 无法智能转换，先固化成不可变值。
                    val pairedKeys = keys
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
                        var setup = runInitialSetup(client, plainSession, noPairing)
                        if (setup == null && plainSession && noPairing) {
                            // catplay 的「加密升级」路径：只有在**我们主动跳过配对**（TXT 未声明
                            // HK_PAIRING_AND_ENCRYPT）且明文 SETUP 仍失败时才补做。
                            // 反过来（配对先试过且失败）再试一次没有意义，只会多等 30s。
                            val upgraded = client.retryWithPairing(PAIRING_UPGRADE_WINDOW_MS)
                            if (upgraded != null) {
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
                            // 记录会话凭据：投影授权晚于连接时用于动态补启音频。
                            // 用 client.currentEndpoint 而非 effectiveEndpoint：配对期间
                            // mDNS 刷新过端点时，真正连上的是 client 里的地址（2026-10-18：
                            // 0x4301 的定长 IP 字段带 NUL，mDNS 给的是干净地址，取旧值
                            // 会让视频端口/keepAlive 用脏地址 → 一律解析失败）。
                            currentSharedSecret = sessionKey
                            currentAudioHost =
                                client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                            client.connectEventChannel(sessionKey, setup) { command ->
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
                                            if (keyCode != 0) {
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
                                        // 喂给链路引擎：握手 → EA 会话 → 触摸 HID。
                                        val params = command["params"] as? Map<*, *>
                                        val data = params?.get("data") as? ByteArray
                                        if (data == null) {
                                            appendLog("EVENT: iAPSendMessage 无 data（params=$params）")
                                        } else {
                                            iapEventMessageCount++
                                            client.onIapEventBytes(data)
                                        }
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
                                // iAP 隧道（type-130）：车机→手机的 iAP2 EA 数据（触摸 HID 载体）。
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
                                    activeScalePercent = scalePct
                                    activeTargetFps = DisplayPrefs.FPS
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
                                    CarLifeHijack.setRequestedResolution(
                                        setup.displayWidth,
                                        setup.displayHeight,
                                    )
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
                                    // 直通模式下码流分辨率由 CarLife 决定（默认 1280×720）：
                                    // VideoConfig 里声明的流尺寸必须与 H.264 SPS 一致，否则车机
                                    // 按 SETUP 画布建解码器后会解不出画面。非直通路径沿用缩放值。
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
                                            " → 流 ${streamOutW}×${streamOutH}（$scalePct%）@${DisplayPrefs.FPS}fps",
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
                                    videoSender =
                                        VideoSender(
                                            host = host,
                                            dataPort = screen.dataPort,
                                            frameCipher = screen.cipher,
                                            width = streamOutW,
                                            height = streamOutH,
                                            targetFps = DisplayPrefs.FPS,
                                            log = { line -> appendLog(line) },
                                            keepAliveWithStats = setup.keepAliveSendStatsAsBody,
                                            // 直通模式：VideoConfig 声明实时跟随 98311 上报值。
                                            // 此刻 CarLife 客户端可能还没握手完成（构造值只是
                                            // 默认 1280×720），等引擎按车机真实分辨率重建编码器后，
                                            // 这里每次发 config 都会取到最新值，声明永远与码流一致。
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
                                client.record()
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
                                }
                                // 音频通道（可选）：SETUP type=100 → UDP RTP 推流。
                                // 会话已激活（RECORD 完成）后再协商；失败不影响视频主链路。
                                val audioStarted = startAudioChannel(context, client, sessionKey, host)
                                if (audioStarted) {
                                    // 流在 RECORD 之后加入：AirPlay 语义要求再发一次 RECORD
                                    // 把新流纳入已激活的会话。缺这一步时车机对 SETUP 回 200
                                    // 并给 dataPort，但不会真正播放（2026-10-17 奥迪实测：
                                    // 音频包持续发送、采集内容非零，车机无声）。
                                    appendLog("AUDIO: 音频流已加入——补发 RECORD 激活（流后加入会话）")
                                    client.record()
                                }
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
                    sessionStarting.set(false)
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
                        }
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
        if (boundCarSsid == endpoint.ssid && wifiConnector?.currentNetwork != null) {
            appendLog(">>> [$source] 已绑定车机热点 ${endpoint.ssid}，跳过重复绑定")
            continueWithNetwork(true)
        } else {
            // 端点先于 0x5703 到达（或上一轮换网失败）：同样走应用专属网络。
            // 不能再用「按 TRANSPORT_WIFI 盲绑当前任意 WiFi」——切网途中会绑到旧网络，
            // AirPlay 必然连不上，且会把无互联网热点留在系统默认网络里。
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
                            // WEP 等无法用 specifier 表达：等用户在系统 WLAN 里连上后绑定
                            waitForManualWifiThenContinue(wifiConfig) { bound ->
                                continueWithNetwork(bound)
                            }
                            return@run
                        }

                        WifiCarPlayConnector.JoinResult.Unavailable -> {
                            // specifier 没进来时，建议网络可能已经在后台把手机连上了
                            // （用户授权过 suggestion，但系统关联比我们的 15s 窗口慢）。
                            // 这里再给建议通道一个短窗口：真连上了就直接绑，省掉弹窗。
                            val bySuggestion =
                                if (connector != null && !connectCancelled) {
                                    connector.bindCurrentWifiBlocking(SUGGESTION_JOIN_WAIT_MS) { connectCancelled }
                                } else {
                                    null
                                }
                            if (bySuggestion != null) {
                                boundCarSsid = wifiConfig.ssid
                                phaseHint = "已加入车机热点"
                                ctrlAdvertiser?.ensureForNetwork(
                                    wifiConfig.ssid,
                                    force = true,
                                    network = connector?.currentNetwork,
                                )
                                appendLog(">>> WiFi 由系统自动连上（热点建议生效，无需确认框），ctrl 广播已重新注册")
                                continueWithNetwork(true)
                                return@run
                            }
                            promptSpecifierFailed(wifiConfig, shellReady)
                            // promptSpecifierFailed 内部可能已重试/转手动；两者都会
                            // 通过 joinCarWifiSpecifier → continueWithNetwork 续上流程，
                            // 这里只在彻底失败时结束本轮
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

    /** specifier 不可用时的手动兜底：轮询系统 WLAN 是否连上目标热点，而后绑定。 */
    private fun waitForManualWifiThenContinue(
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
                phaseHint = "等待手机加入车机热点 ${wifi.ssid}…"
                appendLog("WIFI: 等待用户在系统 WLAN 中加入车机热点（当前 ${current ?: "未连接"}）…")
                openWlanSettings()
            }
            Thread.sleep(1_000)
        }
        if (!connectCancelled) {
            phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${wifi.ssid}"
            appendLog(">>> 等待车机热点超时——请到 设置>WLAN 加入 \"${wifi.ssid}\" 后重新连接")
            uiHintSink?.invoke("未连上车机热点 \"${wifi.ssid}\"——请到 WLAN 设置中主动连接")
        }
        onBound(false)
    }

    /** 触摸/隧道事件低频计数（迁移自 MainActivity 字段，保持日志节流行为一致）。 */
    @Volatile
    private var touchLogCount = 0

    /** 车机媒体键「释放」报文计数（值为 0 的 1 字节报告，静默丢弃，仅计数备查）。 */
    @Volatile
    private var consumerReleaseCount = 0

    @Volatile
    private var iapEventMessageCount = 0
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
): PairingFallback {
    if (keysEstablished) return PairingFallback(useBtRetireHack = false, usePlainSession = false)
    val useBtRetireHack = !noPairing && !pairSetupRejected && !pairingForbidden && attemptedPairing
    val usePlainSession = !pairingForbidden && (pairingUnsupported || attemptedPairing)
    return PairingFallback(useBtRetireHack = useBtRetireHack, usePlainSession = usePlainSession)
}
