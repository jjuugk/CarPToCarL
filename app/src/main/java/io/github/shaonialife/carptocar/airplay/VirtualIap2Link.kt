package io.github.shaonialife.carptocar.airplay

import android.content.Context
import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2CapabilityReport
import io.github.shaonialife.carptocar.iap2.Iap2Csm
import io.github.shaonialife.carptocar.iap2.Iap2LinkEngine
import io.github.shaonialife.carptocar.iap2.Iap2MessageIds
import io.github.shaonialife.carptocar.iap2.Iap2NowPlaying
import io.github.shaonialife.carptocar.iap2.MfiLengthMemory
import io.github.shaonialife.carptocar.input.ConsumerKeys
import io.github.shaonialife.carptocar.util.SessionTrace
import java.security.SecureRandom
import java.util.TimeZone
import java.util.UUID

/**
 * 事件通道上的虚拟 iAP2 链路（AirPlay 会话之上的「WiFi 隧道」），一条实现服务三种形态：
 *
 *  - **DiPlay（type-130 隧道）**：出站写隧道 TCP；链路协商用「回显先行」语义
 *    （对端引擎硬性期望，见 [Iap2LinkEngine.echoFirst]）；
 *  - **Audi MIB3（事件通道 + 控制通道 POST）**：真机时序（AADisplay 以 Frida 挂
 *    越狱 iPhone mediaserverd 抓包确认）——**屏幕流 SETUP 成功后由手机主动发一次
 *    DETECT（经控制通道 POST /command 的 iAPSendMessage）→ 车机回 SYN（事件通道）
 *    → 手机回 SYN|ACK（控制通道）**，随后控制会话整段重跑：0x1D00 → 0x1D01 →
 *    0x1D02+0xAA00 →（0xAA06 → 0xAA00）→ 0xAA02 挑战 20B → 0xAA03 → 0xAA05 →
 *    手机突发消息 → 应答车机订阅。**只回显空 ACK 的话 MIB3 约 10~12 秒整链拆除**
 *    （本仓库 2026-10-19 两轮实车日志 10.2s 复现，此前误判为心跳缺失）；
 *  - **旧事件形态 / 明文会话**：marker 一问一答回显（保持历史行为）。
 *
 * 车机→手机的链路字节统一从事件通道 iAPSendMessage 进来（[onCarBytes]）；
 * 手机→车机按形态分流（隧道 TCP / 控制通道 POST <-- 默认）。**方向不能对调**：
 * 出站写在事件通道上车机不读（Frida 结论；2026-10-20 Honda HFT 实测复现——
 * 出站改到事件通道后，车机 20 分钟内一条 FF5A 同步包都没回，只按 1Hz 重发 marker）。
 */
class VirtualIap2Link(
    private val log: (String) -> Unit,
    /** 事件通道 iAPSendMessage 出站（旧形态兜底）。 */
    private val sendEventIap: (ByteArray) -> Boolean,
    /** 控制通道 iAPSendMessage 出站（MIB3 手机侧帧的唯一正确通道）。 */
    private val sendControlIap: (ByteArray) -> Unit,
    /** DiPlay type-130 隧道写入口（每次出站动态取用，可为 null）。 */
    private val tunnelWriter: () -> AirPlayClient.IapTunnelClient?,
    /** 节拍线程存活条件（宿主控制连接未关）。 */
    private val alive: () -> Boolean,
    /** MFi 长度记忆的持久化上下文；null = 进程内记忆（单测）。 */
    private val mfiContext: Context? = null,
    /** 时钟（单测可注入）。 */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    /**
     * 「正在播放」信息提供者（歌名/歌手/专辑/播放状态）。
     *
     * 0x5000（StartNowPlayingUpdates）订阅到达时调用：返回非 null 就发带真实歌名的
     * 0x5001 NowPlayingUpdate，否则退化为空态（车机存活前置）。由 SessionController
     * 注入（数据源 = CarLife 内置播放器经 0x10035 推来的歌名，见 CarLifeHijack）。
     */
    private val nowPlayingProvider: (() -> CarPlayNowPlaying?)? = null,
    /**
     * 本轮是否按别克 E5（B233）建链。
     *
     * 只影响「推送类消息要不要等 ACK」：B233 对 0x5001（歌名）/0x4171（列表）
     * 常常只收不 ACK，走致命重传会让链路看门狗在约 20 秒后拆掉整条会话。
     */
    private val isBuickB233: Boolean = false,
    /**
     * 当前通话状态提供者（iAP2 `CallState`：0=无通话、1=来电、2=拨出、3=通话中）。
     *
     * 0x4154（StartCallStateUpdates）订阅到达时用它回**真实值**。恒回 0 的后果不是
     * 「少一个功能」而是「车机认定这台手机永远不打电话」——通话时它不会切通话 UI，
     * 也不会把音频通路让给 telephony 档（2026-10-29 通话适配）。
     *
     * 由 SessionController 注入（数据源 = `AudioManager.mode`，见 `callStateOf`）。
     */
    private val callStateProvider: (() -> Int)? = null,
) {
    private val lock = Any()
    private val engine = Iap2LinkEngine()
    private val parser = Iap2Csm.Parser()
    private var frameCount = 0
    private var eaCount = 0
    private var started = false

    // ---- DiPlay 隧道路径状态 ----
    private var tunnelCsmStarted = false
    private var tunnelIdentified = false

    /** 同步回显待投递（隧道门闸：回显真正写进隧道 TCP 后才允许发 0x1D00）。 */
    private var pendingEchoFlush = false
    private var tunnelEchoDelivered = false

    // ---- MIB3 路径状态 ----
    private var mib3Armed = false
    private var mib3SynAckQueued = false
    private var mib3CsmStarted = false
    private var mib3Identified = false
    private var mib3AuthStage = 0
    private var mib3MarkerIgnores = 0
    private var mib3DeferredLogs = 0
    private var mib3WirelessSessionId: String? = null

    /**
     * 事件通道链路模式（2026-10-20 Honda HFT 实测新增）。
     *
     * 车机在**事件通道**上以发起方姿态反复发 SYN marker（`FF 55 02 00 EE 10`，
     * 每秒一次、连续上百次），等的是我们对它回一个 marker 才会继续发同步包。
     * 而 [armMib3] 的 detect 走的是**控制通道**——车机看不到，于是永远重发。
     *
     * 连续第 [EVENT_LINK_MARKER_THRESHOLD] 个 marker 起判定它确实在等回应，
     * 切到本模式：出入站都走事件通道，marker 应答 + 同步包协商即可建链。
     * 链路建立后车机才会发 0x5000（NowPlaying 订阅）、0x6800（StartHID）、
     * 0x6802（HID 报告 = 方向盘/面板媒体键）——切歌按键的载体就在这里。
     */
    private var eventLinkArmed = false
    private var eventLinkMarkers = 0
    private var eventCsmStarted = false

    /**
     * 控制通道协商轮次（2026-10-20 Honda HFT 定案后新增）。
     *
     * 车机的 marker 从**事件通道入站**，但它的 iAP2 栈只从**控制通道**（RTSP
     * POST /command 的 iAPSendMessage）读我们发的字节——项目原有 Frida 结论就写着
     * 「写在事件通道上时车机根本不读」。r5/r6 把出站也挪到事件通道后，车机连一条
     * FF5A 同步包都不回、1Hz 重发 marker 到天亮，正是这一条被踩中。
     *
     * 现在出站回归控制通道：每轮 [armMib3] / [restartControlNegotiation] 发一次
     * 「marker + 本表会话表」，引擎内部按 500ms/1s 重发；[CONTROL_SYN_ROUND_MILLIS]
     * 无进展就进入下一轮，最多 [MAX_CONTROL_SYN_ROUNDS] 轮，之后停在 NEGOTIATING
     * 静默等待（不再打点，避免把车机栈刷懵）。
     */
    private var controlSynRounds = 0
    private var controlSynRoundStartedAtMs = 0L
    private var controlSynGaveUp = false

    /** 未处理的控制会话消息计数（媒体键排查的观测点，见 [maybeRespondControlCsmLocked]）。 */
    private var unknownCsmIds = 0

    /** 上一次记录过的链路状态（只在对日志里出现一次状态迁移）。 */
    private var lastLinkState = Iap2LinkEngine.State.IDLE

    // ---- 车机 HID 媒体键（0x6802）状态 ----
    /** 上一次报文里按下的 Usage 集合（用于判定「新按下」，松开后自动清空）。 */
    private var lastHidUsages: Set<Int> = emptySet()

    /** 当前这批按键的起始时刻（连续同一按键超过 [HID_HOLD_REPEAT_MILLIS] 才允许连发）。 */
    private var hidHoldStartedAtMs = 0L

    /** 已打印的参数明细帧数（只打前几帧，之后按需打动作）。 */
    private var hidReportFrames = 0

    // ---- MIB3 隧道 MFi 挑战（按车机持久化记忆；与蓝牙链路**独立记**——同一台车
    // 两条链路的认证后端可能不同，实测 MMI 0000 蓝牙认 32B 而 Frida 隧道是 20B） ----
    private var mfiIdentity: String? = null
    private var mfiMemory = MfiLengthMemory.Entry()
    private var mfiOrder: List<Int> = emptyList()
    private var mfiPosition = -1
    private var mfiCurrentLength = 0
    private var mfiWindowMillis = 0L
    private var mfiChallengeSentAtMs = 0L
    private var mfiFinished = false

    init {
        engine.onEaData = { bytes -> handleEaData(bytes) }
        engine.onSynchronizationEchoSent = { pendingEchoFlush = true }
        Thread(
            {
                while (alive()) {
                    try {
                        Thread.sleep(200)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    synchronized(lock) {
                        engine.advanceTime(System.nanoTime() / 1_000_000L)
                        drainLocked()
                    }
                }
            },
            "iap-link-pacer",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** 事件形态启动：服务端待命（收到 marker 回 marker，等车机 SYN）。 */
    fun begin() {
        synchronized(lock) {
            if (started) return
            started = true
            engine.enterNegotiating()
        }
    }

    /**
     * MIB3 布防（屏幕流 SETUP 成功后调用，且仅一次）：主动向**控制通道**发一次 DETECT。
     * 必须等 SETUP 完成后再发——早发会在 SETUP 交换期间被冲掉（Frida 结论）；此后
     * 车机的 marker 都是探测重发，真机（MIB3）回一次 SYN 就不再重发。
     *
     * 注意这里**只发裸 marker**：MIB3 的时序是「它回 SYN → 我们回 SYN|ACK」，
     * 提前把会话表拍过去反而会打乱它的状态机（见 [Iap2LinkEngine.startResponder]
     * 的实测记录）。车机若像 Honda HFT 那样反复重发 marker，才由
     * [startControlRoundLocked] 升级成「marker + 本表会话表」并重试。
     */
    fun armMib3() {
        synchronized(lock) {
            begin()
            if (mib3Armed) return
            mib3Armed = true
            sendControlIap(Iap2LinkEngine.MARKER)
            log("IAPLINK: MIB3 detect 已发（控制通道 iAPSendMessage）——等待车机 SYN")
        }
    }

    /**
     * 主动推送一次 0x5001 NowPlayingUpdate（歌名变化时由宿主调用）。
     *
     * 车机只在订阅（0x5000）时收到一次 0x5001；而歌名是用户开始播放音乐后才经
     * CarLife 0x10035 到达的——若订阅先于歌名，就会停在空态。所以歌名就绪后必须
     * 由外部主动补推一次。链路尚未建立（MIB3 未识别）时静默忽略。
     */
    fun pushNowPlaying() {
        synchronized(lock) {
            if (!mib3Identified && !tunnelIdentified) return
            val nowPlaying = nowPlayingProvider?.invoke() ?: return
            sendRawCsmLocked(buildNowPlayingUpdate(nowPlaying))
            log(
                "IAPLINK: 主动补推 0x5001（歌名=${nowPlaying.title ?: "?"}" +
                    " 歌手=${nowPlaying.artist ?: "?"}）",
            )
        }
    }

    /**
     * 通话状态变化时主动补推 0x4155。
     *
     * 调用时机有讲究（[SessionController.switchAudioScene] 负责顺序）：**先把 telephony
     * 音频档 SETUP 好，再推状态**。车机收到「通话中」会立刻把音频路由切到通话通路，
     * 此时若通话流还没建，它切过去只能拿到一条空的媒体流——「车机显示通话中却没有声音」
     * 正是这个顺序错的典型症状。
     */
    fun pushCallState() {
        synchronized(lock) {
            if (!mib3Identified) return
            val state = currentCallState()
            sendCallStateLocked(state)
            log("IAPLINK: 主动补推 0x4155（通话状态=$state）")
        }
    }

    /** 当前通话状态（provider 缺省或越界一律回「无通话」，绝不发未知值给车机）。 */
    private fun currentCallState(): Int =
        (callStateProvider?.invoke() ?: IAP2_CALL_STATE_NONE)
            .coerceIn(IAP2_CALL_STATE_NONE, IAP2_CALL_STATE_ACTIVE)

    /** 发一条 0x4155（订阅应答与主动补推共用，保证两条路径的参数形态完全一致）。 */
    private fun sendCallStateLocked(state: Int) {
        sendCsmLocked(CALL_STATE_UPDATE, Iap2Csm.param(CALL_STATE_PARAM, byteArrayOf(state.toByte())))
    }

    /**
     * 开一轮控制通道协商：marker + 本表同步包（会话 10/11/12），刷新引擎重发节拍。
     */
    private fun startControlRoundLocked(reason: String) {
        val now = nowMillis()
        controlSynRounds++
        controlSynRoundStartedAtMs = now
        engine.initiate(now)
        log(
            "IAPLINK: 控制通道 marker + 本表同步包（第 $controlSynRounds/$MAX_CONTROL_SYN_ROUNDS 轮，$reason）" +
                "——等车机同步包（FF5A）",
        )
        drainLocked()
    }

    /** 车机链路字节入口（事件通道 iAPSendMessage 的 data）。 */
    fun onCarBytes(bytes: ByteArray) {
        synchronized(lock) {
            // 隧道存在时切换「回显先行」协商语义（DiPlay 对端引擎硬性期望）
            engine.echoFirst = tunnelWriter() != null
            val isMarker =
                bytes.size == 6 &&
                    bytes[0] == 0xFF.toByte() &&
                    bytes[1] == 0x55.toByte()
            if (isMarker && tunnelWriter() == null) {
                if (mib3Armed) {
                    mib3MarkerIgnores++
                    val now = nowMillis()
                    // 车机反复发 SYN marker = 它还没拿到我们的会话表。链路字节**必须**走
                    // 控制通道（事件通道它不读），所以这里不是换通道，而是按轮次重开协商。
                    if (!eventLinkArmed && mib3MarkerIgnores >= EVENT_LINK_MARKER_THRESHOLD) {
                        eventLinkArmed = true
                        log(
                            "IAPLINK: 车机连续发 SYN marker ×$mib3MarkerIgnores" +
                                "（它在等我们的会话表）→ 控制通道补发协商轮次",
                        )
                        startControlRoundLocked("车机 marker 重发")
                    } else if (eventLinkArmed) {
                        eventLinkMarkers++
                        if (engine.state != Iap2LinkEngine.State.NORMAL && !controlSynGaveUp) {
                            val stale = now - controlSynRoundStartedAtMs >= CONTROL_SYN_ROUND_MILLIS
                            when {
                                !stale -> Unit
                                controlSynRounds >= MAX_CONTROL_SYN_ROUNDS -> {
                                    controlSynGaveUp = true
                                    engine.stopNegotiationRetries()
                                    log(
                                        "IAPLINK: 控制通道 $MAX_CONTROL_SYN_ROUNDS 轮协商无应答——停止自动重发，" +
                                            "链路停在 ${engine.state}（车机若发 0x1D01 会自动继续）",
                                    )
                                }

                                else -> startControlRoundLocked("上一轮 ${CONTROL_SYN_ROUND_MILLIS}ms 无应答")
                            }
                        }
                        // 引擎节拍线程也会重发，这里把待发字节立刻冲出去
                        drainLocked()
                        if (eventLinkMarkers <= 3 || eventLinkMarkers % 20 == 0) {
                            log(
                                "IAPLINK: 车机 marker（#$eventLinkMarkers）链路状态=${engine.state}" +
                                    "（控制通道第 $controlSynRounds 轮）",
                            )
                        }
                        return
                    } else if (mib3MarkerIgnores <= 3) {
                        log("IAPLINK: 车机 marker（#$mib3MarkerIgnores）——凑够 $EVENT_LINK_MARKER_THRESHOLD 个后补发协商")
                    }
                } else {
                    // 布防前（屏幕 SETUP 未完成）：不回应——真机在 SETUP 前不 detect，
                    // 事件通道上的回显对 MIB3 无意义（它只认控制通道）；旧明文接收端
                    // 不走此链路语义
                    if (mib3DeferredLogs < 2) {
                        mib3DeferredLogs++
                        log("IAPLINK: SETUP 前的车机 marker 暂不回应（布防后主动 detect）")
                    }
                    return
                }
            }
            engine.feed(bytes, System.nanoTime() / 1_000_000L)
            drainLocked()
        }
    }

    /**
     * 链路字节出站：隧道 TCP（DiPlay）→ 控制通道 POST /command（MIB3 / Honda HFT）→
     * 事件通道（旧明文形态兜底）。
     *
     * **分工是固定的**：车机的链路字节从事件通道 `iAPSendMessage` **入站**，我们的
     * 出站字节只能写控制通道——写在事件通道上时车机不读（Frida 结论，2026-10-20
     * Honda HFT 再次实测：出站挪到事件通道后，车机 20 分钟里一条 FF5A 都没回）。
     */
    private fun sendLinkBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val tunnel = tunnelWriter()
        when {
            tunnel != null -> tunnel.writeIapBytes(bytes)
            eventLinkArmed -> sendControlIap(bytes)
            mib3Armed -> sendControlIap(bytes)
            else -> sendEventIap(bytes)
        }
    }

    /**
     * 引擎出站字节落盘：SYN|ACK 真正投递到对应通道后才允许启动 CSM——
     * 对端引擎未见到 SYN|ACK 前收到裸 ACK 会直接 die（DiPlay 实测）。
     */
    private fun flushOutputLocked() {
        val out = engine.takeOutput()
        if (out.isEmpty()) return
        val tunnel = tunnelWriter()
        when {
            tunnel != null -> {
                val ok = tunnel.writeIapBytes(out)
                if (ok && pendingEchoFlush) {
                    pendingEchoFlush = false
                    if (!tunnelEchoDelivered) {
                        tunnelEchoDelivered = true
                        log("IAPLINK: ✓ 同步回显已写入隧道（链路协商完成，发送端 CSM 就绪）")
                    }
                }
            }

            eventLinkArmed -> {
                // 入站事件通道 / 出站控制通道：应答、同步包、CSM 一律写控制通道
                sendControlIap(out)
                if (pendingEchoFlush) {
                    pendingEchoFlush = false
                    log("IAPLINK: ✓ SYN|ACK 已投递控制通道——链路协商完成，启动控制会话")
                }
            }

            mib3Armed -> {
                sendControlIap(out)
                if (pendingEchoFlush) {
                    pendingEchoFlush = false
                    mib3SynAckQueued = true
                    log("IAPLINK: ✓ MIB3 SYN|ACK 已投递控制通道——等待链路确认后启动 CSM")
                }
            }

            else -> {
                sendEventIap(out)
                pendingEchoFlush = false
            }
        }
    }

    private fun drainLocked() {
        while (true) {
            when (val event = engine.pollEvent()) {
                null -> break
                is Iap2LinkEngine.Event.Control ->
                    handleFrameLocked(event.bytes)

                is Iap2LinkEngine.Event.Dead -> {
                    log("IAPLINK: 虚拟链路终止 - ${event.reason ?: "EOF"}")
                    return
                }
            }
        }
        noteLinkStateLocked()
        // 先冲刷（回显必须先行送达对端），再启动 CSM；超时换档可能新排队帧，最后再冲一次
        flushOutputLocked()
        maybeStartControlCsmLocked()
        checkMfiTimeoutLocked()
        flushOutputLocked()
    }

    /** 链路状态迁移只在日志里出现一次（IDLE→NEGOTIATING→NORMAL 是按键通路是否打通的分水岭）。 */
    private fun noteLinkStateLocked() {
        val state = engine.state
        if (state == lastLinkState) return
        lastLinkState = state
        log("IAPLINK: 链路状态 → $state（NORMAL 才可能收到车机 0x6800/0x6802 媒体键）")
    }

    /** MFi 挑战静默超时：记入该车机的记忆并换下一档重试（节拍线程每 200ms 驱动一次）。 */
    private fun checkMfiTimeoutLocked() {
        val identity = mfiIdentity ?: return
        if (mfiFinished || mfiCurrentLength == 0) return
        if (nowMillis() - mfiChallengeSentAtMs < mfiWindowMillis) return
        val failed = mfiCurrentLength
        MfiLengthMemory.recordSilent(mfiContext, identity, failed)
        log("IAPLINK: 隧道 MFi ${failed}B 静默 ${mfiWindowMillis}ms——记入记忆，换下一档重试")
        mfiCurrentLength = 0
        startNextMfiChallengeLocked()
    }

    /**
     * 下一档 MFi 挑战（顺序=按车机记忆重排，冷启动基线 20B 优先——Frida 真机值）。
     * 全部档位无应答时与蓝牙路径同语义：跳过校验直接 0xAA05，由车机后续行为判定。
     */
    private fun startNextMfiChallengeLocked() {
        val identity = mfiIdentity ?: return
        if (mfiOrder.isEmpty()) {
            mfiMemory = MfiLengthMemory.load(mfiContext, identity)
            mfiOrder = MfiLengthMemory.orderAttempts(TUNNEL_MFI_BASELINE, mfiMemory)
            if (!mfiMemory.isEmpty) {
                log(
                    "IAPLINK: 隧道 MFi 按车机记忆：${MfiLengthMemory.describe(mfiMemory)}" +
                        " → 本轮顺序 ${mfiOrder.joinToString("/")}B",
                )
            }
        }
        mfiPosition += 1
        if (mfiPosition >= mfiOrder.size) {
            log("IAPLINK: 隧道 MFi 挑战全部无应答——跳过校验直接 0xaa05（由车机后续行为判定）")
            mfiFinished = true
            mib3AuthStage = 4
            sendCsmLocked(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
            sendMib3BurstLocked()
            return
        }
        mfiCurrentLength = mfiOrder[mfiPosition]
        val base = TUNNEL_MFI_WINDOWS[mfiCurrentLength] ?: TUNNEL_MFI_SILENT_WINDOW_MS
        mfiWindowMillis =
            if (mfiCurrentLength in mfiMemory.silent && mfiCurrentLength != mfiMemory.succeeded) {
                minOf(base, TUNNEL_MFI_SILENT_WINDOW_MS)
            } else {
                base
            }
        mfiChallengeSentAtMs = nowMillis()
        val challenge = ByteArray(mfiCurrentLength).also { SecureRandom().nextBytes(it) }
        sendCsmLocked(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
        mib3AuthStage = 3
        log("IAPLINK: 隧道 tx=0xaa02 challenge=${mfiCurrentLength}B（第 ${mfiPosition + 1} 次，窗口 ${mfiWindowMillis}ms）")
    }

    /**
     * 控制会话启动（隧道/MIB3 两形态）：链路 NORMAL 且 SYN|ACK 已投递后，主动发
     * 0x1D00 StartIdentification。缺此步时对端控制端 5 分钟窗口超时即拆掉整条链路
     * （DiPlay 2026-10-11 实测根因；MIB3 的拆除窗口短得多，~10s）。
     */
    private fun maybeStartControlCsmLocked() {
        if (engine.state != Iap2LinkEngine.State.NORMAL) return
        val onTunnel = tunnelWriter() != null
        if (onTunnel) {
            if (tunnelCsmStarted || !tunnelEchoDelivered) return
            tunnelCsmStarted = true
        } else if (eventLinkArmed) {
            // 车机发起方形态：出站走控制通道，只要已采纳对端会话表即可开工
            if (eventCsmStarted || engine.peerSynchronization == null) return
            eventCsmStarted = true
        } else {
            if (!mib3Armed || mib3CsmStarted || !mib3SynAckQueued) return
            mib3CsmStarted = true
        }
        sendCsmLocked(Iap2MessageIds.START_IDENTIFICATION)
    }

    /** 链路上的 CSM 发送（进入引擎 backlog，由引擎按发送窗口 flush）。 */
    private fun sendCsmLocked(
        messageId: Int,
        body: ByteArray = ByteArray(0),
    ) {
        val frame = Iap2Csm.encode(messageId, body)
        // 别克 B233：推送类消息（0x5001 歌名、0x4171 列表）车机常常只收不 ACK。
        // 让它们不占「必须 ACK」的名额，避免传输看门狗在约 20 秒后判定链路已死、
        // 把整条控制通道拆掉（E2LB 日志里「ACK 停滞 → packet not acknowledged」的成因）。
        val awaitAck =
            !(isBuickB233 && (messageId == Iap2MessageIds.NOW_PLAYING_UPDATE || messageId == LIST_UPDATE))
        val ok = engine.sendControl(frame, System.nanoTime() / 1_000_000L, awaitAck)
        log(
            "IAPLINK: 隧道 tx 0x${messageId.toString(16)} " +
                "(${Iap2MessageIds.nameOf(messageId)}) ${frame.size}B ok=$ok" +
                if (awaitAck) "" else "（别克 B233：推送不占用 ACK 名额）",
        )
        SessionTrace.csm(SessionTrace.Dir.TX, "tunnel", messageId, body, if (ok) "" else "send-failed")
    }

    /** 直接投递完整 CSM 帧（Frida 抓的逐字节常量，勿重新编码）。 */
    private fun sendRawCsmLocked(frame: ByteArray) {
        val ok = engine.sendControl(frame, System.nanoTime() / 1_000_000L)
        // 帧头：0..1 起始字、2..3 总长度、4..5 messageId、6.. body。
        // 旧实现从 2..3 取 ID（那是长度），日志里的「隧道 tx 0x0100」全是假 ID。
        val messageId =
            if (frame.size >= Iap2Csm.HEADER_BYTES) {
                ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
            } else {
                -1
            }
        log(
            "IAPLINK: 隧道 tx 0x${messageId.toString(16)} " +
                "(${Iap2MessageIds.nameOf(messageId)}) ${frame.size}B raw ok=$ok",
        )
        if (messageId >= 0) {
            SessionTrace.csm(
                SessionTrace.Dir.TX,
                "tunnel",
                messageId,
                frame.copyOfRange(Iap2Csm.HEADER_BYTES, frame.size),
                if (ok) "raw" else "raw,send-failed",
            )
        }
    }

    /**
     * 控制会话应答（隧道与 MIB3 共用识别/MFi 骨架，MIB3 另有突发与订阅应答）：
     *   0x1D01 → 0x1D02 + 0xAA00；0xAA06 → 再发 0xAA00；
     *   0xAA01 → 0xAA02 挑战（MIB3 20B / DiPlay 32B）；0xAA03 → 0xAA05（MIB3 随发突发）。
     * 订阅（仅 MIB3）：0x4157→0x4158、0x4154→0x4155、0x4170→0x4171、0x5000→0x5001 空态。
     * 0xEA02（requestAppLaunch）/0xA101（VehicleStatus）/0xFFFB（PASCD 定位样本 ~1Hz）
     * 只链路 ACK（引擎自动）——**0xFFFB 漏 ACK 车机同样 ~10s 后关控制通道**（Frida 结论）。
     */
    private fun maybeRespondControlCsmLocked(frame: Iap2Csm.Frame) {
        val onTunnel = tunnelWriter() != null
        // 车机发起方形态复用 MIB3 那套识别/MFi 骨架（出站同样走控制通道）
        val mib3 = (mib3Armed || eventLinkArmed) && !onTunnel
        if (!onTunnel && !mib3) return
        val ids = Iap2MessageIds
        when (frame.messageId) {
            ids.IDENTIFICATION_INFORMATION -> {
                val identified = if (onTunnel) tunnelIdentified else mib3Identified
                if (identified) return
                if (onTunnel) tunnelIdentified = true else mib3Identified = true
                val params =
                    Iap2Csm
                        .parseParams(frame.body)
                        .associateBy { it.id }
                log(
                    "IAPLINK: 隧道 rx 0x1d01 接收端身份 " +
                        "name=${params[0]?.value?.decodeToString()?.trimEnd('\u0000')} " +
                        "model=${params[1]?.value?.decodeToString()?.trimEnd('\u0000')}",
                )
                // 参 6/7 = 车机声明「它会发 / 它可收」的消息清单。三层输出见 Iap2CapabilityReport
                // （原始清单 / 按子系统分组 / 目录里没有的陌生 ID）：
                //   · 媒体键排查看**发送清单**里有没有 0x6800/0x6802；
                //   · 投仪表/HUD 看**可收清单**里有没有导航引导 0x52xx（MIB3 4398 起）。
                params[6]?.value?.let { raw ->
                    val sends = u16List(raw)
                    Iap2CapabilityReport.report(log, "IAPLINK: ", "发送清单", sends, isReceiveList = false)
                    if (!sends.contains(Iap2MessageIds.START_HID)) {
                        log(
                            "IAPLINK: 发送清单无 0x6800 StartHID → 这台车机不用 iAP2 HID 送按键，" +
                                "不必在这条通路耗（改查蓝牙/AVRCP 侧）",
                        )
                    }
                }
                params[7]?.value?.let { raw ->
                    Iap2CapabilityReport.report(
                        log,
                        "IAPLINK: ",
                        "可收清单",
                        u16List(raw),
                        isReceiveList = true,
                    )
                }
                if (mib3) {
                    // 记忆键 = 车机身份 + " tunnel"：与蓝牙链路的记忆互不污染
                    mfiIdentity = mfiIdentityOf(params) + " tunnel"
                }
                sendCsmLocked(ids.IDENTIFICATION_ACCEPTED)
                sendCsmLocked(ids.MFI_REQUEST_CERTIFICATE)
                mib3AuthStage = 1
            }

            MFI_AUTH_SERIAL -> {
                if (!mib3 || mib3AuthStage != 1) return
                sendCsmLocked(ids.MFI_REQUEST_CERTIFICATE)
                mib3AuthStage = 2
                log("IAPLINK: MIB3 0xaa06（auth serial）→ 再发 0xaa00")
            }

            ids.MFI_AUTHENTICATION_FAILED -> {
                if (!mib3 || mfiFinished) return
                mfiIdentity?.let { MfiLengthMemory.recordRejected(mfiContext, it, mfiCurrentLength) }
                log("IAPLINK: 隧道 MFi ${mfiCurrentLength}B 被拒（0xaa04）——记入记忆，换下一档")
                mfiCurrentLength = 0
                startNextMfiChallengeLocked()
            }

            ids.MFI_CERTIFICATE -> {
                if (mib3) {
                    // 长度按车机记忆（冷启动 20B 优先，Frida 真机值；详见 startNextMfiChallengeLocked）
                    startNextMfiChallengeLocked()
                } else {
                    // DiPlay CARLINKIT 身份只接受精确 32B（长度不符直接断链）
                    val challenge =
                        ByteArray(32).also { SecureRandom().nextBytes(it) }
                    sendCsmLocked(ids.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
                    mib3AuthStage = 3
                }
            }

            ids.MFI_RESPONSE -> {
                if (mib3) {
                    mfiIdentity?.let { MfiLengthMemory.recordSuccess(mfiContext, it, mfiCurrentLength) }
                    log("IAPLINK: 隧道 rx=0xaa03 签名 ${frame.body.size}B（${mfiCurrentLength}B 挑战成功，已记忆）")
                    mfiCurrentLength = 0
                    mfiFinished = true
                }
                sendCsmLocked(ids.MFI_AUTHENTICATION_SUCCEEDED)
                if (mib3) {
                    mib3AuthStage = 4
                    sendMib3BurstLocked()
                } else {
                    log("IAPLINK: 隧道 MFi 完成——等待对端订阅（随后其标记隧道就绪）")
                }
            }

            START_COMMUNICATIONS_UPDATES -> {
                if (!mib3) return
                sendRawCsmLocked(MIB3_COMMUNICATIONS_UPDATE_IDLE)
                log("IAPLINK: MIB3 0x4157 → 0x4158（空态）")
            }

            START_CALL_STATE_UPDATES -> {
                if (!mib3) return
                // 车机会一直把订阅时的答案当「当前状态」，之后靠主动补推更新。
                val state = currentCallState()
                sendCallStateLocked(state)
                log(
                    "IAPLINK: MIB3 0x4154 → 0x4155（通话状态=$state" +
                        (if (state == IAP2_CALL_STATE_NONE) " 无通话" else "") + "）",
                )
            }

            START_LIST_UPDATES -> {
                if (!mib3) return
                sendCsmLocked(
                    LIST_UPDATE,
                    Iap2Csm.param(0x0000, byteArrayOf(0)) +
                        Iap2Csm.param(0x0002, byteArrayOf(0, 0)) +
                        Iap2Csm.param(0x0005, byteArrayOf(0)) +
                        Iap2Csm.param(0x0007, byteArrayOf(0, 0)),
                )
                log("IAPLINK: MIB3 0x4170 → 0x4171（空列表）")
            }

            Iap2MessageIds.START_NOW_PLAYING_UPDATES -> {
                if (!mib3) return
                val nowPlaying = nowPlayingProvider?.invoke()
                if (nowPlaying != null) {
                    sendRawCsmLocked(buildNowPlayingUpdate(nowPlaying))
                    log(
                        "IAPLINK: MIB3 0x5000 → 0x5001（歌名=${nowPlaying.title ?: "?"}" +
                            " 歌手=${nowPlaying.artist ?: "?"} 状态=${nowPlaying.status})",
                    )
                } else {
                    sendRawCsmLocked(MIB3_NOW_PLAYING_UPDATE_IDLE)
                    log("IAPLINK: MIB3 0x5000 → 0x5001（空态，无正在播放）")
                }
            }

            ids.START_HID -> {
                // 车机声明「我要开始发 HID 报告了」，通常带描述符（决定报告字节语义）
                val params = Iap2Csm.parseParams(frame.body)
                val descriptor = params.map { it.value }.firstOrNull { it.size > 8 }
                if (descriptor != null) {
                    hidDescriptor = descriptor
                    log(
                        "IAPLINK: 车机 0x6800 StartHID 描述符 ${descriptor.size}B hex=" +
                            descriptor.joinToString(" ") { "%02x".format(it) }.take(120),
                    )
                } else {
                    log("IAPLINK: 车机 0x6800 StartHID（无描述符）参数=${params.map { it.id }}")
                }
            }

            ids.ACCESSORY_HID_REPORT -> {
                // 方向盘/面板媒体键。
                //
                // 2026-10-20 本田 HFT 实测帧（11B）：
                //   00 06 00 00 00 03 | 00 05 00 01 04
                //   param id=0 值 00 03（报告类型/描述，恒定）← 之前被当成报告值
                //   param id=1 值 04（真正的 HID 报文）
                // 旧代码取「第一个 1~8 字节的 param」→ 永远拿到 00 03，位图里一位都不置，
                // 于是日志一直打 `usage=无 keyCode=0`，按键到了却点不出任何动作。
                val params = Iap2Csm.parseParams(frame.body)
                val report = hidReportFromParams(params)
                val keyMap = hidDescriptor?.let(ConsumerKeys::parseDescriptor)
                val usages =
                    when {
                        report == null -> emptyList()
                        keyMap != null -> keyMap.usagesFor(report)
                        // 无描述符：退化为「单字节即 Usage ID」
                        else -> listOf(report[0].toInt() and 0xFF).filter { it != 0 }
                    }
                if (hidReportFrames < HID_REPORT_VERBOSE_FRAMES) {
                    hidReportFrames++
                    log(
                        "IAPLINK: 车机 0x6802 params=" +
                            params.joinToString(",") { "${it.id}=" + it.value.joinToString("") { b -> "%02x".format(b) } } +
                            " 报告=" + (report?.joinToString(" ") { "%02x".format(it) } ?: "空") +
                            " 位图=" + (keyMap?.isArray?.let { if (it) "Array" else "Variable" } ?: "无描述符"),
                    )
                }
                fireHidKeysLocked(usages)
            }

            else -> {
                // 未处理的消息也留痕：车机若用非常规 ID 下发媒体键/按键，这里能第一时间
                // 在日志里看到对应消息号（切歌按键排查的关键观测点）。
                // 带全量 hex：宝马 iD9 的 0xad00/0xad02/0xad03 家族既不在 Apple 公开表里，
                // 也没有参考实现的命名——只能靠报文体结构反推它是不是输入（触摸/HID）通路。
                unknownCsmIds++
                val navFrame =
                    Iap2CapabilityReport.groupOf(frame.messageId) == Iap2CapabilityReport.Group.NAVIGATION
                when {
                    // 导航引导单独一支：不设条数上限、不截断。车机若主动下发 0x52xx
                    // （MIB3 4398/CL3B 起可能为投仪表而订阅），报文体就是最直接的样本——
                    // 参数编号、字节序、是否带 maneuver 字段，全在这行里。
                    navFrame -> {
                        val navParams = Iap2Csm.parseParams(frame.body)
                        log(
                            "IAPLINK: ★ 导航引导 CSM 0x${frame.messageId.toString(16)} " +
                                "(${Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B " +
                                "params=" + navParams.joinToString(",") {
                                    "#${it.id}=" + it.value.joinToString("") { b -> "%02x".format(b) }
                                } +
                                " hex=" + frame.body.joinToString(" ") { "%02x".format(it) },
                        )
                    }
                    unknownCsmIds <= 20 -> {
                        log(
                            "IAPLINK: 未处理的 CSM 0x${frame.messageId.toString(16)} " +
                                "(${Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B hex=" +
                                frame.body.joinToString(" ") { "%02x".format(it) },
                        )
                    }
                }
            }
        }
    }

    /**
     * 把一批「本次按下的 Usage」转成 Android 媒体键注入。
     *
     * 车机的 HID 报告是**状态式**的：按住会以 ~10Hz 连推同一个位图，松开推 0x00。
     * 因此只在「新出现」时触发一次（边沿触发），否则一次长按会跳掉几十首歌；
     * 只有在同一按键持续按住超过 [HID_HOLD_REPEAT_MILLIS] 时才允许再触发，
     * 保持方向盘长按「连续切歌」的手感。
     */
    private fun fireHidKeysLocked(usages: List<Int>) {
        val now = nowMillis()
        if (usages.isEmpty()) {
            lastHidUsages = emptySet()
            hidHoldStartedAtMs = 0L
            return
        }
        val fresh = usages.filter { it !in lastHidUsages }
        val repeat =
            fresh.isEmpty() &&
                hidHoldStartedAtMs > 0 &&
                now - hidHoldStartedAtMs >= HID_HOLD_REPEAT_MILLIS
        lastHidUsages = usages.toSet()
        if (fresh.isNotEmpty()) hidHoldStartedAtMs = now
        if (fresh.isEmpty() && !repeat) return
        if (repeat) hidHoldStartedAtMs = now
        val firing = if (fresh.isNotEmpty()) fresh else usages
        for (usage in firing) {
            val keyCode = ConsumerKeys.keyCodeFor(usage)
            log(
                "IAPLINK: 车机媒体键 ${ConsumerKeys.nameOf(usage)}(0x%02x) → keyCode=".format(usage) +
                    (if (keyCode != 0) "$keyCode 已注入" else "无对应 Android 键（忽略）"),
            )
            if (keyCode != 0) onMediaKey?.invoke(keyCode)
        }
    }

    /**
     * 0xAA05 后的突发（Frida：真机在认证成功后立即发这一批，先于车机的订阅请求）。
     * 0xFFFA/0xFFF0/0x4158/0x5001 用逐字节常量——字段布局只存在于真机抓包里，
     * 重新编码极易走样（对照 AADisplay WifiIap2Link 的 Frida 常量）。
     */
    private fun sendMib3BurstLocked() {
        val sessionId =
            mib3WirelessSessionId ?: UUID.randomUUID().toString().uppercase().also {
                mib3WirelessSessionId = it
            }
        sendCsmLocked(
            WIRELESS_SESSION_ID,
            Iap2Csm.param(0, sessionId.toByteArray(Charsets.UTF_8)),
        )
        sendCsmLocked(AUX_STATUS_NOTIFICATION, Iap2Csm.param(0x0006, ByteArray(0)))
        sendCsmLocked(
            DEVICE_LANGUAGE,
            Iap2Csm.param(0, "zh".toByteArray(Charsets.UTF_8)),
        )
        sendCsmLocked(DEVICE_TIME, mib3DeviceTimeParams())
        sendCsmLocked(
            DEVICE_INFORMATION_UPDATE,
            Iap2Csm.param(0, "iPhone".toByteArray(Charsets.UTF_8)),
        )
        sendRawCsmLocked(MIB3_START_LOCATION_INFORMATION)
        sendRawCsmLocked(MIB3_GPRMC_DATA_STATUS)
        log("IAPLINK: MIB3 突发已发（会话标识 $sessionId）——等待车机订阅")
    }

    /**
     * 构造 0x5001 NowPlayingUpdate 完整帧（带真实歌名/歌手/专辑/播放状态）。
     * 字段布局见 [Iap2NowPlaying]，与空态常量 [MIB3_NOW_PLAYING_UPDATE_IDLE] 同构。
     */
    private fun buildNowPlayingUpdate(np: CarPlayNowPlaying): ByteArray = Iap2NowPlaying.encode(np)

    /** 0x4E0B：u64 秒 + s16 时区偏移(分) + u8 夏令时。 */
    private fun mib3DeviceTimeParams(): ByteArray {
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val offsetMin = zone.getOffset(now) / 60_000
        val dst = if (zone.inDaylightTime(java.util.Date(now))) 1 else 0
        val seconds = ByteArray(8)
        for (i in 0 until 8) {
            seconds[i] = (now / 1000L ushr ((7 - i) * 8)).toByte()
        }
        val tz =
            byteArrayOf(
                (offsetMin shr 8).toByte(),
                offsetMin.toByte(),
            )
        return Iap2Csm.param(0, seconds) + Iap2Csm.param(1, tz) + Iap2Csm.param(2, byteArrayOf(dst.toByte()))
    }

    /** 大端 u16 数组（识别信息里的消息 ID 清单）。 */
    private fun u16List(bytes: ByteArray): List<Int> =
        (0 until bytes.size / 2).map {
            ((bytes[it * 2].toInt() and 0xFF) shl 8) or (bytes[it * 2 + 1].toInt() and 0xFF)
        }

    /** 0x1D01 身份参数（name/model/manufacturer）→ 记忆键素材（NUL 去净、空档跳过）。 */
    private fun mfiIdentityOf(params: Map<Int, Iap2Csm.Param>): String =
        listOf(0, 1, 2)
            .mapNotNull { id ->
                params[id]
                    ?.value
                    ?.decodeToString()
                    ?.trimEnd('\u0000')
                    ?.takeIf { it.isNotBlank() }
            }.joinToString(" ")

    private fun handleFrameLocked(bytes: ByteArray) {
        frameCount++
        val frames = parser.offer(bytes)
        for (frame in frames) {
            val hex = frame.body.joinToString(" ") { "%02x".format(it) }
            if (frameCount <= 30 || frameCount % 100 == 0) {
                log(
                    "IAPLINK: rx 0x${frame.messageId.toString(16)} " +
                        "(${Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B hex=${hex.take(160)}",
                )
            }
            // 摘要行按条数抽样（上），录制行全量（下）：事后分析拆链时，被抽掉的那几条
            // 往往正是关键（例如某条订阅的应答没发）。
            SessionTrace.csm(SessionTrace.Dir.RX, "tunnel", frame.messageId, frame.body)
            maybeRespondControlCsmLocked(frame)
        }
        if (frames.isEmpty() && frameCount <= 10) {
            log("IAPLINK: rx ${bytes.size}B（非 CSM）hex=" + bytes.joinToString(" ") { "%02x".format(it) }.take(160))
            SessionTrace.mark("隧道 rx ${bytes.size}B 非 CSM: " + bytes.joinToString(" ") { "%02x".format(it) }.take(400))
        }
    }

    /** 引擎回调：EA 会话（2 号）数据帧——触摸 HID 的高概率载体。 */
    var onEaData: ((ByteArray) -> Unit)? = null

    /**
     * 引擎回调：iAP2 控制会话下发的 HID 报告解析出的 Android 媒体键。
     *
     * 车机方向盘/面板的上一曲、下一曲在 CarPlay 里走 `0x6802 AccessoryHIDReport`，
     * 只有 iAP2 链路真正建立后才会来（见 [eventLinkArmed]）。
     */
    var onMediaKey: ((Int) -> Unit)? = null

    /** 车机在 0x6800 StartHID 里给出的 HID 描述符（解析报告用；null = 未提供）。 */
    private var hidDescriptor: ByteArray? = null

    fun handleEaData(bytes: ByteArray) {
        eaCount++
        val hex = bytes.joinToString(" ") { "%02x".format(it) }
        if (eaCount <= 30 || eaCount % 100 == 0) {
            log("IAPLINK: EA 数据 #$eaCount ${bytes.size}B hex=${hex.take(200)}")
        }
        onEaData?.invoke(bytes)
    }

    companion object {
        /** 隧道 MFi 冷启动基线（Frida 真机 20B 优先；顺序会被按车机记忆重排）。 */
        val TUNNEL_MFI_BASELINE = listOf(20, 32, 16)
        val TUNNEL_MFI_WINDOWS = mapOf(20 to 6_000L, 32 to 3_000L, 16 to 3_000L)
        const val TUNNEL_MFI_SILENT_WINDOW_MS = 1_500L

        /**
         * 车机在事件通道上连续发到第几个 SYN marker 才判定「它在等我们回 marker」。
         * 真机（MIB3）在我们主动 detect 后就不再重发；Honda HFT 实测连发上百次，
         * 取 2 既能避开 MIB3 的一次探测重发，又不会让车机白等太久。
         */
        const val EVENT_LINK_MARKER_THRESHOLD = 2

        /**
         * 控制通道协商：每轮多久无进展就重开下一轮，最多几轮。
         * 车机 marker 是 1Hz，4s 一轮 ⇒ 每轮能收到 4 次重发机会；5 轮 ≈ 20s，
         * 之后停手静默等待（它真发 0x1D01 仍会被正常处理）。
         */
        const val CONTROL_SYN_ROUND_MILLIS = 4_000L
        const val MAX_CONTROL_SYN_ROUNDS = 5

        /**
         * 0x6802 AccessoryHIDReport 的报文参数号。
         *
         * 本田 HFT 实测：param 0 = 报告类型/描述（`00 03`，永远不变），
         * param 1 = 真正的 HID 报文（如 `04` = 下一首）。取错参数号等于所有按键都解码成「无」。
         */
        const val HID_REPORT_DATA_PARAM = 1

        /** 参数明细只打前几帧（之后每帧都会触发按键动作，日志已足够）。 */
        const val HID_REPORT_VERBOSE_FRAMES = 8

        /** 同一按键持续按住多久才允许再次触发（模拟长按连发，短按绝不重复）。 */
        const val HID_HOLD_REPEAT_MILLIS = 1_500L

        /**
         * 0x6802 的参数表 → HID 报文。
         *
         * 优先取 param [HID_REPORT_DATA_PARAM]；缺省（个别车机只给一个参数）时退回
         * 「最后一个 1~8 字节的参数」——注意**不能取第一个**：本田 HFT 的 param 0 恒为
         * `00 03`（报告类型），当成报文值会让所有按键都解成「无」。
         */
        fun hidReportFromParams(params: List<Iap2Csm.Param>): ByteArray? =
            params.firstOrNull { it.id == HID_REPORT_DATA_PARAM }?.value?.takeIf { it.isNotEmpty() }
                ?: params.lastOrNull { it.value.isNotEmpty() && it.value.size <= 8 }?.value

        // ---- MIB3 消息号（无正式名/未入 Iap2MessageIds 的小集合） ----
        const val MFI_AUTH_SERIAL = 0xAA06
        const val WIRELESS_SESSION_ID = 0x4E0C
        const val DEVICE_TIME = 0x4E0B
        const val DEVICE_LANGUAGE = 0x4E0A
        const val DEVICE_INFORMATION_UPDATE = 0x4E09
        const val AUX_STATUS_NOTIFICATION = 0xA100
        const val START_COMMUNICATIONS_UPDATES = 0x4157
        const val CALL_STATE_UPDATE = 0x4155
        const val START_CALL_STATE_UPDATES = 0x4154

        /** 0x4155 里承载通话状态的参数号（MIB3 空态实测就是这个号，值 1 字节）。 */
        const val CALL_STATE_PARAM = 0x0002

        /** iAP2 CallState：无通话。 */
        const val IAP2_CALL_STATE_NONE = 0

        /** iAP2 CallState：来电（车机据此显示来电界面）。 */
        const val IAP2_CALL_STATE_INCOMING = 1

        /** iAP2 CallState：拨出。 */
        const val IAP2_CALL_STATE_OUTGOING = 2

        /** iAP2 CallState：通话已建立。 */
        const val IAP2_CALL_STATE_ACTIVE = 3
        const val START_LIST_UPDATES = 0x4170
        const val LIST_UPDATE = 0x4171

        /** ---- 以下为 Frida 抓包的逐字节常量（MIB3 真机 iPhone → Audi MMI）---- */
        private val HEX_DIGITS = "0123456789abcdef"

        /** 0xFFFA StartLocationInformation。 */
        internal val MIB3_START_LOCATION_INFORMATION: ByteArray =
            hex(
                "40400032fffa00080000000000140004000100088001000000fa" +
                    "0004000200088002000000fa000400040008800400000014",
            )

        /** 0xFFF0 GPRMCDataStatusValuesNotification。 */
        internal val MIB3_GPRMC_DATA_STATUS: ByteArray =
            hex("4040000efff00004000000040001")

        /** 0x5001 NowPlayingUpdate 空态（无媒体/无播放）。 */
        internal val MIB3_NOW_PLAYING_UPDATE_IDLE: ByteArray =
            hex(
                "404000555001002a00000005000100000800040000000000050006000005000c0000" +
                    "0500100000050012000005001a8000250001000500000000080001000000000005" +
                    "000500000500060000050007000005001000",
            )

        /** 0x4158 CommunicationsUpdate 空态（无 SIM/无信号场景的可用占位）。 */
        internal val MIB3_COMMUNICATIONS_UPDATE_IDLE: ByteArray =
            hex(
                "404000644158000500000100050001060005000200000e0004e697a053494de58da1" +
                    "00000500050100050006010005000700000500080000050009000005000a000005" +
                    "000b000005000c010005000d000005000e000005000f0000050010000005001100",
            )

        private fun hex(s: String): ByteArray {
            val clean = s.replace(" ", "")
            return ByteArray(clean.length / 2) { i ->
                val hi = HEX_DIGITS.indexOf(clean[i * 2])
                val lo = HEX_DIGITS.indexOf(clean[i * 2 + 1])
                ((hi shl 4) or lo).toByte()
            }
        }
    }
}
