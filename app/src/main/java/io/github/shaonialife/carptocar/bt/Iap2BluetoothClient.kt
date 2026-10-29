package io.github.shaonialife.carptocar.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.shaonialife.carptocar.buick.B233DialPolicy
import io.github.shaonialife.carptocar.buick.B233Identity
import io.github.shaonialife.carptocar.buick.B233Selection
import io.github.shaonialife.carptocar.buick.ConnectionProfile
import io.github.shaonialife.carptocar.buick.ConnectionProfilePrefs
import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import io.github.shaonialife.carptocar.transport.Iap2LinkChannel
import io.github.shaonialife.carptocar.transport.StreamDuplexStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机侧（iPhone 角色）**主动连接车机**的无线 iAP2 RFCOMM 客户端 —— 真车阶段新增。
 *
 * 真无线 CarPlay 建链方向（对照 carplay-wifi-extractor client 模式，已在真车验证）：
 * 手机与车机完成系统级蓝牙配对后，由**手机主动连入**车机 SDP 广播的配件端服务
 * （UUID 00000000-deca-fade-deca-deafdecacaff，奥迪 MIB3 SDP 实测存在）；
 * 车机从不主动回连手机（测试台 DiPlay 会回连，那是接收端的测试行为）。
 *
 * 连上后复用与测试台完全相同的发送端 bring-up（[Iap2SenderSession]），
 * 成功时回调 [onEndpoint] 交出热点凭据与 AirPlay 端点。
 */
class Iap2BluetoothClient(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 0x5703 一到即触发（绑 WiFi + 广播 _carplay-ctrl），真车 0x4301 的前置条件。 */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
    /** Phase 1 之后车机补发 0x4301（CarPlay 窗口晚开）时交给主控热更新端点。 */
    private val onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 目标车机（动态读取：选择后无需重启链路，下一轮尝试即生效）。 */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
    /** 「正在播放」信息提供者（歌名/歌手/专辑，见 [Iap2SenderSession]）。 */
    private val nowPlayingProvider: (() -> CarPlayNowPlaying?)? = null,
    /** 保活泵退出（BT 链路断开）时回调：主控据此释放「会话处理中」（僵尸轮次堵门修复）。 */
    private val onLinkClosed: () -> Unit = {},
) {
    companion object {
        /**
         * 配件端（车机）无线 iAP2 服务 UUID。
         * 手机端 UUID 是 …cafe（xcertplay IAP2_IPHONE_UUID），配件端是 …caff。
         */
        val ACCESSORY_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

        private const val SESSION_TIMEOUT_MILLIS = 90_000L

        /** 主动连 …caff 的基础间隔（退避的第 0 档）。 */
        private const val RETRY_DELAY_MILLIS = 3_000L

        /**
         * 连续失败（含「连上即被关」）时的退避阶梯：3s → 10s → 20s，之后固定 20s。
         *
         * 为什么不再固定 3 秒：2026-10-28 大众日志里 52 轮、约 300 次连接尝试，全部
         * 「连入 0.5 秒后车机 socket 关闭」——这台车机只在自己愿意时发起（日志 B 段就是
         * 它主动连进来的）。密集探测既无用、又不断打断车机侧的节奏；本机的 …cafe 监听
         * 一直在跑，车机想连随时能连，所以放长间隔不会错过什么。
         */
        private val RETRY_BACKOFF_MILLIS = longArrayOf(3_000L, 10_000L, 20_000L)

        /** 会话存活不足这个时长就当作「无效轮」（车机连上就关），计入退避。 */
        private const val SESSION_USEFUL_MILLIS = 3_000L

        /** 退避睡眠的分片粒度：20 秒的退避也要能在 stop() 后立刻醒来。 */
        private const val BACKOFF_POLL_MILLIS = 250L

        private const val FAILURE_LOG_THROTTLE_MILLIS = 15_000L

        /** 目标连不上多少轮后放开限制（3s/轮 → 约 6s 后回退，旧行为要等 40s+）。 */
        private const val TARGET_FALLBACK_ROUNDS = 2

        /** 「蓝牙已连接→已断开」日志节流（车机可能反复自动重连，避免刷屏）。 */
        private const val PROFILE_LOG_THROTTLE_MILLIS = 15_000L

        /** SDP 服务探测等待窗（fetchUuidsWithSdp → ACTION_UUID 广播）。 */
        private const val SDP_PROBE_TIMEOUT_MILLIS = 2_500L

        /** SDP 探测重试次数：首次常撞上 cancelDiscovery 收尾，广播会晚于超时才到。 */
        private const val SDP_PROBE_ATTEMPTS = 2

        /**
         * 连续多少轮「主动方向在 SDP 层就失败」后切被动等车机连入。
         * 3 轮 × 3s ≈ 9 秒——够试两三次「先直连、再断配置文件重试」，又不至于让用户干等。
         */
        private const val PASSIVE_FALLBACK_ROUNDS = 3

        /** 被动等待窗口（对照参考实现的 60s/120s 等待窗，取短的一档）。 */
        private const val PASSIVE_WAIT_MILLIS = 60_000L

        /** 被动窗口内的轮询粒度：也决定 stop()/车机连入的响应延迟。 */
        private const val PASSIVE_POLL_MILLIS = 2_000L
    }

    private var thread: Thread? = null
    private val currentSocket = AtomicReference<BluetoothSocket?>(null)

    /**
     * 客户端线程世代号：start()/stop() 都会递增。卡在阻塞 connect() 里的线程被
     * stop() 解除阻塞后（关闭 connecting socket），会因世代不符直接退出——
     * 防止「stop 后线程残活、用户再次启动时新旧两代并发连车机」
     * （2026-10-11 实测：多个僵尸线程并发 RFCOMM 会让车机频繁拆链）。
     */
    private val generation =
        java.util.concurrent.atomic
            .AtomicLong(0)

    @Volatile
    private var activeChannel: Iap2LinkChannel? = null

    /** 当前保活阶段的发送端会话（bring-up 完成后由 runSenderSession 回填）。 */
    @Volatile
    private var senderSession: Iap2SenderSession? = null

    @Volatile
    private var running = false

    /**
     * 本次启动读到的「连接配置」档位。
     *
     * 只在 [start] 时读一次：建链方向与拆链策略在整轮服务生命周期里保持一致，
     * 中途改档位不会把正在进行的会话从被动切成主动（那会让车机看到两路争抢）。
     */
    @Volatile
    private var connectionProfile: ConnectionProfile = ConnectionProfile.AUTO

    /** 别克档是否由手机主动拨号；false = 等车机接入（E5 实测默认方向）。 */
    @Volatile
    private var b233PhoneDialEnabled = false

    /** 歌名变化时主动补推 0x5001（转发给当前保活会话）。 */
    fun pushNowPlaying() {
        senderSession?.pushNowPlaying()
    }

    /** 目标设备不在已配对列表的提示节流（避免每 3 秒刷屏）。 */
    private var lastTargetMissingLogAt = 0L

    /** 「无 iAP2 服务→跳过」日志去重（每地址一次即可，重复轮询不刷屏）。 */
    private val skippedNoService = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 「是否提供 iAP2 服务」结论缓存（地址→结论）：SDP 查询不便宜，勿每 3s 重查。 */
    private val iap2ServiceCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 最近一次 …caff 连接失败的原始原因（IOException 摘要）。
     *
     * 只打「连接失败」时无法区分三种完全不同的情况：
     * `Service discovery failed`（SDP 里就没有该服务 = 车机没开无线 CarPlay 窗口）、
     * `Connection refused`（有服务但被拒）、`read failed / timeout`（车机卡住）。
     * 2026-10-26 雪铁龙日志就卡在这一层——只有「失败」两字，判不出是哪一种。
     */
    @Volatile
    private var lastConnectError: String? = null

    /**
     * 最近一次失败是不是**SDP 层**失败（`createRfcommSocketToServiceRecord` 直接抛，
     * 即车机 SDP 里根本没有 …caff）。
     *
     * 与「连上了但被拒 / 超时」区分开：只有这一类才值得切「被动方向」——
     * 车机既然没广播配件端服务，主动一侧再怎么重试都是空转，而由车机反向连入
     * 本机的 …cafe 监听仍有可能成功（参考实现 CarWith-CarPlay 的
     * `automatic direction fallback to car-initiated transport` 走的就是这一层）。
     */
    @Volatile
    private var lastFailureWasSdpLookup = false

    /**
     * SDP 闸门结论。
     *
     * 2026-10-26 实车日志（凯迪拉克 CADILLAC_7731）暴露的问题：`ACTION_UUID` 广播在部分机型
     * 上根本不来，旧实现「等不到广播 = 没有 iAP2 服务」，于是自动模式把真车机当耳机跳过
     * ——日志里同一台车 13 秒前被判「非 CarPlay 设备」，13 秒后手动指定目标就连上了。
     * 因此把「问不出来」与「明确没有」分开：只有 SDP 真的回了答案且不含 …caff 才算 MISSING。
     */
    private enum class Iap2Service { HAS, MISSING, UNKNOWN }

    /**
     * 设备是否提供配件端 iAP2 服务（…caff）：先读系统 uuids 缓存，缺失再
     * `fetchUuidsWithSdp` 触发 SDP 查询并等 `ACTION_UUID` 广播。
     *
     * **广播一次都没来 → [Iap2Service.UNKNOWN]**（不是「没有」）；只有收到广播且 uuids 里
     * 确实没有 …caff 才是 [Iap2Service.MISSING]。结论缓存到客户端停止——车机固件升级换
     * UUID 的场景由重启 App 覆盖。
     * 权限：start() 已校验 BLUETOOTH_CONNECT；uuids/fetchUuidsWithSdp 的
     * SecurityException 由 runCatching 吸收。
     */
    @SuppressLint("MissingPermission")
    private fun awaitIap2Service(device: BluetoothDevice): Iap2Service {
        iap2ServiceCache[device.address]?.let { return if (it) Iap2Service.HAS else Iap2Service.MISSING }

        fun cachedHas(): Boolean =
            runCatching {
                device.uuids?.any { it == ACCESSORY_IAP2_UUID } == true
            }.getOrDefault(false)

        if (cachedHas()) {
            iap2ServiceCache[device.address] = true
            return Iap2Service.HAS
        }
        // 允许重试：第一次查询常撞上 cancelDiscovery 的收尾，广播会晚于超时才到。
        repeat(SDP_PROBE_ATTEMPTS) {
            val latch = java.util.concurrent.CountDownLatch(1)
            val receiver =
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(
                        receiverContext: Context,
                        intent: android.content.Intent,
                    ) {
                        @Suppress("DEPRECATION")
                        val hit = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (hit?.address == device.address) latch.countDown()
                    }
                }
            var answered = false
            val started =
                runCatching {
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        android.content.IntentFilter(BluetoothDevice.ACTION_UUID),
                        ContextCompat.RECEIVER_EXPORTED,
                    )
                    device.fetchUuidsWithSdp()
                }.getOrDefault(false)
            if (started) {
                try {
                    answered = latch.await(SDP_PROBE_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    runCatching { context.unregisterReceiver(receiver) }
                    return Iap2Service.UNKNOWN
                }
            }
            runCatching { context.unregisterReceiver(receiver) }
            if (answered) {
                val has = cachedHas()
                iap2ServiceCache[device.address] = has
                return if (has) Iap2Service.HAS else Iap2Service.MISSING
            }
        }
        return Iap2Service.UNKNOWN
    }

    /**
     * 像个人音频设备（耳机/音箱/便携音响）——这类**绝不**为了探 iAP2 去断它的音频。
     * 车机常见的是 HANDSFREE / CAR_AUDIO / UNCATEGORIZED，都不会命中。
     */
    @SuppressLint("MissingPermission")
    private fun looksLikePersonalAudio(device: BluetoothDevice): Boolean {
        val deviceClass =
            runCatching { device.bluetoothClass?.deviceClass }.getOrNull() ?: return false
        return deviceClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
            deviceClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
            deviceClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER ||
            deviceClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO ||
            deviceClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO
    }

    /** 「蓝牙已连接→已断开」日志节流时间戳。 */
    private var lastProfileLogAt = 0L

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            log("BT-C: 缺少 BLUETOOTH_CONNECT 权限")
            return false
        }
        val adapter =
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT-C: 蓝牙不可用或未开启")
            return false
        }
        // 每轮启动重置 SDP 结论与跳过日志（新配对/换固件后重新探测）
        iap2ServiceCache.clear()
        skippedNoService.clear()
        BtSessionGate.targetSoftFallback = false
        // 连接配置档位在这里定型：改动在下次 start() 生效（界面里也这么写）
        connectionProfile = ConnectionProfilePrefs.get(context)
        b233PhoneDialEnabled = connectionProfile.b233PhoneInitiates
        running = true
        val myGeneration = generation.incrementAndGet()
        val target = targetProvider()
        log("BT-C: 连接配置=${connectionProfile.label}——${connectionProfile.summary}")
        log(
            "BT-C: 启动——将主动连入车机的无线 iAP2 服务（…caff）" +
                if (target != null) {
                    "，目标：「${target.name}」${target.address}（只连该设备）"
                } else {
                    "（未指定目标：依次尝试全部候选设备，可在「连接准备」中指定）"
                },
        )
        Thread({ connectLoop(myGeneration) }, "iap2-bt-client").apply {
            isDaemon = true
            thread = this
            start()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun connectLoop(myGeneration: Long) {
        val adapter =
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            } ?: return
        var lastFailureLogAt = 0L
        var targetFailRounds = 0
        /** 连续「SDP 层失败」的轮数（见 [PASSIVE_FALLBACK_ROUNDS]）。 */
        var passiveFailRounds = 0
        /** 别克档「手机拨号」的节流基线（见 [B233DialPolicy]）。 */
        var lastB233DialAt = 0L
        val loopStartedAt = android.os.SystemClock.uptimeMillis()
        /** 连续「没有有效会话」的轮数，用于 [RETRY_BACKOFF_MILLIS] 退避（连上即断也算）。 */
        var failStreak = 0
        while (running && generation.get() == myGeneration) {
            // 静默降噪（2026-10-10 用户实测后启用）——会话期间完全静默，不产生任何连接尝试/日志：
            //  - inboundActive：接收端正在连我们（两条 RFCOMM 会互相踩；Audi 实测双会话混乱）
            //  - sessionActive：CarPlay 会话已建立（已拿到端点）——与其它接收端的连接尝试全部暂停
            //    典型场景：连着 Redmi/DiPlay 时不再无脑探测车机 Audi（消除日志噪音与蓝牙churn）；
            //    会话结束（任一端 BT 会话终结 / 手动断开）时自动恢复。
            if (BtSessionGate.inboundActive || BtSessionGate.sessionActive) {
                sleepQuietly(2_000)
                continue
            }
            val candidates = candidateDevices(adapter)
            if (candidates.isEmpty()) {
                log("BT-C: 没有已配对的蓝牙设备——请先在系统设置里与车机完成蓝牙配对")
                sleepQuietly(RETRY_DELAY_MILLIS * 3)
                continue
            }
            var connected: BluetoothSocket? = null
            /** 连上的这台是否按别克 B233 处理（决定会话侧要不要强制 B233 建链）。 */
            var connectedBuick = false
            // 本轮有几台设备是「SDP 层就没连上」（不是被拒、不是超时）。累计到
            // [PASSIVE_FALLBACK_ROUNDS] 轮就切被动方向（车机主动连入）。
            var sdpLookupFailures = 0
            for (device in candidates) {
                if (!running || generation.get() != myGeneration) return
                val name =
                    try {
                        device.name
                    } catch (_: SecurityException) {
                        null
                    } ?: device.address
                // 别克 B233 判定（档位 + 设备线索）：命中后建链方向、SDP 闸门、
                // 音频配置文件策略都不同，必须在任何连接动作之前算出来。
                val knownBuick =
                    B233Selection.matches(
                        connectionProfile,
                        targetProvider()?.address,
                        device.address,
                        name,
                        ReceiverCompatibilityMemory.wasB233Observed(context, device.address),
                    )
                runCatching { adapter.cancelDiscovery() }
                if (knownBuick) {
                    // ① 被动档（E5 实测默认）：车机自己会连本机的 …cafe。
                    // 手机再主动拨一次只会让两边争抢同一段空口，把车机的建链窗口拖死。
                    if (!b233PhoneDialEnabled) {
                        if (skippedNoService.add("b233-passive:" + device.address)) {
                            log(
                                "BT-C: 「$name」按别克 B233 处理——被动等车机连入（不主动拨号）。" +
                                    "若车机始终不连，可在「连接配置」里改选「别克 E5（手机拨号）」",
                            )
                        }
                        continue
                    }
                    // ② 主动档：先给车机留反向接入的优先窗口，且两次拨号至少隔 15 秒
                    // （E5 对密集重试很敏感，连拨会把它的 iAP2 窗口拖死）。
                    val now = android.os.SystemClock.uptimeMillis()
                    val sinceLastAttempt = if (lastB233DialAt == 0L) Long.MAX_VALUE else now - lastB233DialAt
                    if (!B233DialPolicy.mayDial(
                            active = true,
                            sessionBusy = BtSessionGate.sessionActive || BtSessionGate.inboundActive,
                            runningForMs = now - loopStartedAt,
                            sinceLastAttemptMs = sinceLastAttempt,
                            cooldownRemainingMs = 0L,
                        )
                    ) {
                        continue
                    }
                    lastB233DialAt = now
                    log(
                        "BT-C: 别克 B233 主动档——拨号「$name」" +
                            "（已等过入站优先窗口 ${B233DialPolicy.INBOUND_PRIORITY_MS}ms）",
                    )
                }
                // 自动模式（未选目标）先过 SDP 闸门：候选=全部已配对设备，耳机/手表
                // 没有 iAP2 服务，连不上还白白被断 HFP/A2DP（设备级断开兜底更会波及
                // 其它在线设备）——确认提供 …caff 才进入「断开+连接」流程。选定目标
                // 的设备不过闸（用户显式指定，保持原路径与时序）。
                val gate =
                    when {
                        // 别克 B233 不过 SDP 闸门：它的 …caff 服务窗口只在车机自己打开
                        // CarPlay 时才出现，SDP 经常查不出结论；过闸等于把能连的车机挡在门外。
                        knownBuick -> Iap2Service.HAS
                        // 自动模式（未选目标）先过 SDP 闸门：候选=全部已配对设备，耳机/手表
                        // 没有 iAP2 服务，连不上还白白被断 HFP/A2DP（设备级断开兜底更会波及
                        // 其它在线设备）——确认提供 …caff 才进入「断开+连接」流程。选定目标
                        // 的设备不过闸（用户显式指定，保持原路径与时序）。软回退（目标连不上、
                        // 放开限制）也过闸：否则会把已配对的耳机、蓝牙键盘（AB Shutter3）、
                        // 音箱全试一遍，既断别人的音频又拖慢重试节奏（2026-10-26 雪铁龙日志实测）。
                        targetProvider() == null || BtSessionGate.targetSoftFallback -> awaitIap2Service(device)
                        else -> Iap2Service.HAS
                    }
                if (gate == Iap2Service.MISSING) {
                    if (skippedNoService.add(device.address)) {
                        log("BT-C: 跳过「$name」（SDP 明确不含 iAP2 服务——非 CarPlay 设备，保持其音频连接）")
                    }
                    continue
                }
                if (gate == Iap2Service.UNKNOWN) {
                    // SDP 没问出结论：先**不动音频**，直接探一次 …caff。
                    // 探通=它就是车机（自动模式不再误杀，这是 2026-10-26 凯迪拉克日志的病根）；
                    // 探不通再看是不是「蓝牙已连接时不开放服务窗口」的机型（奥迪实测），
                    // 此时才断开配置文件重试一次——耳机类设备永远不会被我们断掉音频。
                    var probe = tryConnect(device)
                    if (probe == null && !looksLikePersonalAudio(device) && !knownBuick) {
                        val dropped = BtProfiles.disconnect(context, device)
                        if (dropped.isNotEmpty()) {
                            log("BT-C: $name SDP 未给结论、直连失败——已断开 ${dropped.joinToString("/")} 再试一次")
                        }
                        probe = tryConnect(device)
                    }
                    if (probe == null) {
                        iap2ServiceCache[device.address] = false
                        if (lastFailureWasSdpLookup && !looksLikePersonalAudio(device)) {
                            sdpLookupFailures++
                        }
                        if (skippedNoService.add(device.address)) {
                            log("BT-C: 跳过「$name」（SDP 未给出结论、直连 …caff 也失败——按非 CarPlay 设备处理）")
                        }
                        continue
                    }
                    log("BT-C: 「$name」SDP 未给出结论，但直连 …caff 成功——按车机处理")
                    connected = probe
                    connectedBuick = knownBuick
                    break
                }
                // 先「保持现有配置文件」直连一次。
                // 2026-10-26 雪铁龙 R4 日志：手机 HFP/A2DP 在线时先被我们主动断开，
                // 紧接着 …caff 连接在 **180ms** 内就失败——这么快不是超时，是 SDP 里
                // 压根查不到该服务。顺序反过来（先试、失败再断）对两种机型都成立：
                // 需要「保持连接」的车机第一次就能成，奥迪那类需要「仅配对」的车机
                // 会在第一次失败后走到下面的断开重试。
                var socket = tryConnect(device)
                if (socket != null && knownBuick) {
                    // 别克 B233 的 iAP2 服务窗口与蓝牙音频**共存**，拆了 HFP/A2DP 反而
                    // 会让车机关掉服务窗口——与奥迪「只配对不连接」正好相反，所以这里
                    // 保留配置文件，失败就等下一轮（车机会在自己的窗口期连进来）。
                    log("BT-C: 「$name」按别克 B233 处理——保留蓝牙免提/媒体连接，不拆配置文件")
                } else if (socket == null) {
                    // 「只配对、不连接」：奥迪 MIB3 实测手机与车机处于蓝牙已连接状态
                    // （HFP/A2DP 在线）时，车机不开放无线 CarPlay 的 iAP2 服务窗口——
                    // 表现为每 3s 连接失败。iAP2 是独立的 SDP 直连服务，与这些配置文件
                    // 无关，断开后不影响 CarPlay 本身（声音走 WiFi 的 AirPlay）。
                    val dropped = BtProfiles.disconnect(context, device)
                    if (dropped.isNotEmpty()) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastProfileLogAt > PROFILE_LOG_THROTTLE_MILLIS) {
                            lastProfileLogAt = now
                            val left = BtProfiles.stillConnected(context, device.address)
                            log(
                                "BT-C: $name 处于蓝牙已连接状态（${dropped.joinToString("/")}）——已断开重试" +
                                    if (left.isEmpty()) "" else "（仍有 ${left.joinToString("/")} 在线）",
                            )
                        }
                    }
                    socket = tryConnect(device)
                }
                if (socket != null) {
                    log("BT-C: ✓ 已连入 $name 的无线 iAP2 服务（RFCOMM 建立）")
                    connected = socket
                    connectedBuick = knownBuick
                    break
                } else {
                    if (lastFailureWasSdpLookup) sdpLookupFailures++
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastFailureLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                        lastFailureLogAt = now
                        val reason = lastConnectError ?: "原因未知"
                        // 「SDP 里查不到 …caff」与「连得上但被拒」是两种完全不同的故障，
                        // 前者意味着车机没开无线 CarPlay（有些车型根本只有有线 CarPlay）。
                        val hint =
                            if (reason.contains("discovery", ignoreCase = true) ||
                                reason.contains("service", ignoreCase = true)
                            ) {
                                "——车机 SDP 里没有无线 CarPlay 的 iAP2 服务（未开无线 CarPlay / 该车型仅支持有线）"
                            } else {
                                "——车机未就绪或不在无线配对窗口，3 秒后重试…"
                            }
                        log("BT-C: 连接 $name 失败（$reason）$hint")
                    }
                }
            }
            if (connected == null) {
                // 目标连续连不上 → 放开目标限制（见 BtSessionGate.targetSoftFallback）。
                // 只在「用户指定了目标」时计数，且只降级一次。
                if (targetProvider() != null && !BtSessionGate.targetSoftFallback) {
                    targetFailRounds++
                    if (targetFailRounds >= TARGET_FALLBACK_ROUNDS) {
                        BtSessionGate.targetSoftFallback = true
                        log(
                            "BT-C: 目标「${targetProvider()?.name}」连续 $targetFailRounds 轮连不上" +
                                "——本轮放开目标限制，任一已配对车机连入都接受（不必等它）",
                        )
                    }
                }
                // 方向兜底：连续多轮「SDP 里根本没有 …caff」时，主动一侧已无意义
                // （雪铁龙 R4 的 180ms 失败即属此类），切被动等车机连入一段时间。
                if (sdpLookupFailures > 0) {
                    passiveFailRounds++
                    if (passiveFailRounds >= PASSIVE_FALLBACK_ROUNDS) {
                        passiveFailRounds = 0
                        awaitCarInitiated(myGeneration)
                        continue
                    }
                } else {
                    passiveFailRounds = 0
                }
                failStreak++
                val backoff = backoffMillis(failStreak)
                if (failStreak >= 3) {
                    log(
                        "BT-C: 已连续 $failStreak 轮没建起有效会话——下一轮间隔放长到 ${backoff / 1000}s" +
                            "（这台车机可能只在自己愿意时发起：被动监听一直在，不必密集探测）",
                    )
                }
                sleepBackoff(backoff)
                continue
            }
            if (generation.get() != myGeneration) {
                // stop() 与 connect() 成功竞争：本线程已过期，不建会话
                currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
                return
            }
            val sessionStartedAt = android.os.SystemClock.uptimeMillis()
            runSession(connected, connectedBuick)
            // 「连上就被关」不算成功：2026-10-28 大众实测 52 轮全是「连入 → 0.5 秒被车机
            // 关闭」（车机不在无线窗口/要自己发起）。这类轮次按失败计，否则退避永远回到 3 秒，
            // 每轮白断一次车机、还可能抢掉它主动连入的时机。
            val aliveMillis = android.os.SystemClock.uptimeMillis() - sessionStartedAt
            if (aliveMillis < SESSION_USEFUL_MILLIS) {
                failStreak++
                sleepBackoff(backoffMillis(failStreak))
            } else {
                failStreak = 0
                sleepBackoff(RETRY_DELAY_MILLIS)
            }
        }
        log("BT-C: 客户端已停止")
    }

    /**
     * 被动方向兜底窗口：主动连 …caff 连续多轮在 SDP 层失败时，**停掉主动探测**，
     * 给车机一个「反过来连我们」的干净窗口（本机的 …cafe 监听由
     * [Iap2BluetoothServer] 一直开着，这里只负责让出通道并给出可读日志）。
     *
     * 依据（2026-10-26 反编译对照，`docs/cadillac-ct5-carplay-apk-analysis.md` §2.1）：
     * 同侧参考实现 CarWith-CarPlay 在主动建连失败（且非认证类失败）时会打印
     * `automatic direction fallback to car-initiated transport`，注册本机
     * `…cafe` 监听 + 固定通道 29 后**等车机连进来**（等待窗 60s/120s）。
     * 我们的监听一直存在，缺的正是「让出通道 + 明确等待」这一步：
     * 旧实现每 3 秒重新 createRfcomm 一次，既不成功也刷日志，还可能与车机的
     * 反向建连抢同一时刻的空口（未验证，保留为假设）。
     *
     * 窗口内一旦车机连入（`BtSessionGate.inboundActive`）或会话已建立就立刻返回，
     * 由主循环接管；超时则回主动方向再来一轮，并给出「该车型可能仅支持有线」的结论性提示。
     */
    private fun awaitCarInitiated(myGeneration: Long) {
        log(
            "BT-C: 主动连 …caff 连续 $PASSIVE_FALLBACK_ROUNDS 轮在 SDP 层就失败（车机没广播配件端服务）" +
                "——切换为等车机主动连入，最长 ${PASSIVE_WAIT_MILLIS / 1000} 秒（本机 …cafe 监听仍在运行）",
        )
        val deadline = android.os.SystemClock.uptimeMillis() + PASSIVE_WAIT_MILLIS
        while (running && generation.get() == myGeneration) {
            if (BtSessionGate.inboundActive || BtSessionGate.sessionActive) {
                log("BT-C: 被动窗口内车机主动连入——不再探测 …caff")
                return
            }
            if (android.os.SystemClock.uptimeMillis() >= deadline) break
            sleepQuietly(PASSIVE_POLL_MILLIS)
        }
        if (!running || generation.get() != myGeneration) return
        log(
            "BT-C: 被动窗口 ${PASSIVE_WAIT_MILLIS / 1000} 秒内车机没有连入——回到主动方向重试；" +
                "若长期如此，请确认车机是否启用无线 CarPlay（仅支持有线的车型不会有任何无线 iAP2 服务）",
        )
    }

    /**
     * 候选设备：优先 SDP 缓存里带配件端 UUID 的已配对设备；
     * 缓存缺失时退化为全部已配对设备（connect 时的 SDP 查询会自行过滤）。
     * 指定了目标车机时**只返回该设备**（用户配对了多台车机时避免误连）。
     */
    @SuppressLint("MissingPermission")
    private fun candidateDevices(adapter: android.bluetooth.BluetoothAdapter): List<BluetoothDevice> {
        val bonded =
            try {
                adapter.bondedDevices?.toList().orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        targetProvider()?.let { target ->
            // 目标策略：地址命中即唯一候选——不再按 SDP 缓存过滤（缓存可能缺失，
            // 而用户显式指定就该尝试）；目标不在已配对列表时给一次提示并空转等待。
            val hit = bonded.filter { TargetDevicePrefs.matches(target, it.address) }
            if (!BtSessionGate.targetSoftFallback) {
                if (hit.isEmpty()) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastTargetMissingLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                        lastTargetMissingLogAt = now
                        log(
                            "BT-C: 目标设备「${target.name}」(${target.address}) 不在已配对列表" +
                                "——请到系统蓝牙设置完成配对，或在「连接准备」改选目标",
                        )
                    }
                    return emptyList()
                }
                return hit
            }
            // 软回退：目标降级为「优先」，其余已配对设备一并作为候选
            // （按 UUID 提示有 iAP2 服务的排前面，减少无谓的连接尝试）。
            val ranked =
                bonded.sortedWith(
                    compareByDescending<BluetoothDevice> { TargetDevicePrefs.matches(target, it.address) }
                        .thenByDescending { d -> d.uuids?.any { it.uuid == ACCESSORY_IAP2_UUID } == true },
                )
            return ranked
        }
        val withService =
            bonded.filter { device ->
                device.uuids?.any { it.uuid == ACCESSORY_IAP2_UUID } == true
            }
        return if (withService.isNotEmpty()) withService else bonded
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(device: BluetoothDevice): BluetoothSocket? {
        val socket =
            try {
                device.createRfcommSocketToServiceRecord(ACCESSORY_IAP2_UUID)
            } catch (error: IOException) {
                // 这一步抛异常 = SDP 里连 …caff 记录都没有（车机没开无线 CarPlay 窗口），
                // 与「连上了但被拒」是两回事，必须分别记下来。
                lastConnectError = "建立 socket 失败：${error.message ?: error.javaClass.simpleName}"
                lastFailureWasSdpLookup = true
                return null
            }
        // 连接前就登记：connect() 是阻塞调用（SDP 查询 + 建链最长可达数十秒），
        // stop() 只有能拿到 socket 引用才能 close() 打断它，否则线程会滞留。
        currentSocket.set(socket)
        return try {
            socket.connect()
            lastConnectError = null
            lastFailureWasSdpLookup = false
            socket
        } catch (error: IOException) {
            lastConnectError = error.message ?: error.javaClass.simpleName
            lastFailureWasSdpLookup = false
            currentSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
            null
        }
    }

    private fun runSession(
        socket: BluetoothSocket,
        knownBuick: Boolean = false,
    ) {
        val stream =
            StreamDuplexStream(socket.inputStream, socket.outputStream) {
                runCatching { socket.close() }
            }
        val channel = Iap2LinkChannel(stream, name = "iap2-bt-car") { line -> log(line) }
        activeChannel = channel
        val peerAddress =
            try {
                socket.remoteDevice.address
            } catch (_: SecurityException) {
                null
            }
        try {
            channel.runSenderSession(
                context,
                SESSION_TIMEOUT_MILLIS,
                log,
                { endpoint ->
                    // 真正走通 0x4301 且判定为 B233，才把该地址记下来：
                    // 下一次这台车连入/被拨号就直接走专用流程，不必再靠蓝牙名猜。
                    if (endpoint.isBuickB233 &&
                        ReceiverCompatibilityMemory.rememberB233(context, peerAddress)
                    ) {
                        log("BT-C: 已记录 $peerAddress 为别克 B233——以后该地址直接走专用建链")
                    }
                    onEndpoint(endpoint)
                },
                onWifiCredentials,
                onEndpointUpdate,
                nowPlayingProvider,
                onSessionReady = { session -> senderSession = session },
                onLinkClosed = onLinkClosed,
                // 只有用户**显式**选了别克档才强制 B233；自动档命中蓝牙名时仍交给
                // 0x1D01 身份文本判定（万一不是 B233，别把别的别克车型带进死路径）。
                forceBuickE5 = connectionProfile.forcesB233 && knownBuick,
            )
        } catch (error: IOException) {
            log("BT-C: SESSION 失败 - ${error.message}")
        } finally {
            activeChannel = null
            senderSession = null
            currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
            runCatching { socket.close() }
            channel.close()
            log("BT-C: 本轮会话结束（客户端仍在运行，将自动重连）")
        }
    }

    /** 主动结束当前 BT 控制会话（不影响重连循环）。供 AirPlay 配对前复现控制通道退役状态。 */
    fun detachCurrentSession() {
        activeChannel?.let { channel ->
            activeChannel = null
            channel.close()
            log("BT-C: 已主动断开当前控制会话")
        }
        currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
    }

    fun stop() {
        running = false
        // 递增世代：即便有线程卡在 connect()，事后也会因世代不符自杀，不会在下次启动时复活
        generation.incrementAndGet()
        currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
        activeChannel?.let { runCatching { it.close() } }
        thread?.interrupt()
        thread = null
        log("BT-C: 已停止")
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
    }

    /** 第 [streak] 次连续失败的退避间隔（0 次 = 基础间隔）。 */
    private fun backoffMillis(streak: Int): Long =
        RETRY_BACKOFF_MILLIS[(streak - 1).coerceIn(0, RETRY_BACKOFF_MILLIS.size - 1)]

    /**
     * 退避睡眠：分片检查 [running]，让 stop() 能立刻打断 20 秒的长退避
     * （整段 `Thread.sleep` 会让关闭会话时线程还挂着睡满一轮）。
     */
    private fun sleepBackoff(millis: Long) {
        val deadline = android.os.SystemClock.uptimeMillis() + millis
        while (running && android.os.SystemClock.uptimeMillis() < deadline) {
            sleepQuietly(BACKOFF_POLL_MILLIS)
        }
    }
}

/**
 * 入站会话门闸：车机主动连入的 iAP2 会话活跃期间，出站客户端暂停连接尝试。
 * 目的：车机通常只有单条无线 iAP2 上下文，双向同时建链会让双方互相干扰。
 */
object BtSessionGate {
    /** 入站（车机主动连入）的 iAP2 会话活跃：出站暂停，避免两条 RFCOMM 互相踩。 */
    @Volatile
    var inboundActive: Boolean = false

    /**
     * CarPlay 会话已建立（任一路径成功拿到端点后置位，由 SessionController 维护）：
     * 暂停一切出站连接尝试（静默降噪）——典型表现为连着 DiPlay 时不再探测 Audi。
     * 会话结束/手动断开时由 SessionController 清除。
     */
    @Volatile
    var sessionActive: Boolean = false

    /**
     * 目标设备软回退：指定了目标车机但连续多轮连不上时置位。
     *
     * 场景：用户上次连的是 A 车（配了目标），这次坐的是 B 车 / 测试台。
     * 旧行为是**死等目标**——出站只试目标、入站把非目标全部掐掉，
     * 用户体感就是「点了启动连接，四十多秒毫无反应」（2026-10-25 日志实测
     * 17:48:47 启动 → 17:49:30 才连上）。置位后目标降级为「优先」：
     * 出站回退到全部已配对设备，入站也不再拒绝。
     *
     * 每次 `connect()` 重新开始都会清零，所以真车场景只要目标在窗口内可达，
     * 行为与旧版完全一致。
     */
    @Volatile
    var targetSoftFallback: Boolean = false
}
