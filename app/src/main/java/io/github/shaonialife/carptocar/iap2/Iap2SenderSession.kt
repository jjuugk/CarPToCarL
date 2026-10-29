package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.buick.B233Identity
import io.github.shaonialife.carptocar.transport.Iap2LinkChannel
import io.github.shaonialife.carptocar.util.SessionTrace
import java.io.IOException
import java.security.SecureRandom

/**
 * iAP2 发送端（iPhone 角色）控制会话：完整走通无线 CarPlay 的 bring-up 序列，
 * 最终从 0x5703 / 0x4301 解析出接收端热点与 AirPlay 端点 —— Phase 1 的 Go/No-Go 交付。
 *
 * 序列（对照 reference/xcertplay Iap2WirelessControlClient 的对端行为）：
 *   0x1D00 → 0x1D01 → 0x1D02（识别）
 *   0xAA00 → 0xAA01 → 0xAA02 → 0xAA03 → 0xAA05（MFi，跳过证书/签名校验）
 *   [配件发来 5 个订阅帧 + 0x6800 StartHID，回 0x6801]
 *   0x5702 → 0x5703（拿热点 SSID/密码/信道）
 *   0x4300 → 0x4301（拿 AirPlay 端口/公钥/无线参数）
 *   0x4E0D + 0x4E0E（通知无线可用与传输标识）
 */
class Iap2SenderSession(
    private val channel: Iap2LinkChannel,
    private val log: (String) -> Unit,
    /** 本机真实蓝牙 MAC（0x4300/0x4E0E 的传输标识与 _carplay-ctrl 广播的 id；真车会校验）。 */
    private val bluetoothMac: String = "02:00:00:00:00:00",
    /** 本机蓝牙名（0x4E09 设备信息）。 */
    private val deviceName: String = "iPhone",
    /**
     * 收到 0x5703（热点凭据）时回调 —— 主控须立即：绑定车机 WiFi + 广播
     * `_carplay-ctrl._tcp`（带真实蓝牙 MAC）。真车（Audi MIB3）流程：
     * 车机在 WiFi 上发现该服务并 `GET /ctrl-int/1/connect` 邀请手机，
     * **邀请被接受后才发 0x4301**；若等到 0x4301 之后才广播则双方互等死锁。
     */
    private val onWifiCredentials: ((WifiConfiguration) -> Unit)? = null,
    /**
     * 0x4301 在 Phase 1 结束后才到达时的回调（车机打开 CarPlay 窗口的晚信号）。
     *
     * 保活泵此前把所有消息都当心跳只记长度：Audi MMI 0000 实测 0x4301 在
     * Phase 1 结束后 **2 分 35 秒**才到（车机侧窗口刚开），被日志吞掉后我们
     * 没有发起 AirPlay 连接，车机等 15 秒无人连入便拆掉整条 RFCOMM——
     * 用户看到的就是「连上之后自动断开」。收到就必须交给主控热更新端点/重试配对。
     */
    private val onEndpointUpdate: ((CarPlayEndpoint) -> Unit)? = null,
    /**
     * 仅用于 MFi 挑战长度的按车机持久化记忆（[MfiLengthMemory]）。null = 只用进程内
     * 内存表（单测/纯 JVM），不影响协议流程。
     */
    private val context: android.content.Context? = null,
    /**
     * 「正在播放」信息提供者（歌名/歌手/专辑）。保活阶段收到 0x5000（NowPlaying
     * 订阅）时调用：返回非 null 就回 0x5001。部分接收端（WinPlay/DiPlay 测试台）
     * 在蓝牙 bring-up 后即发 0x5000，歌名需在**这条蓝牙链路**上应答。
     */
    private val nowPlayingProvider: (() -> CarPlayNowPlaying?)? = null,
    /**
     * 强制按别克 E5（B233）建链，不看 0x1D01 文本。
     *
     * 由「连接配置」下拉框的别克档位传入：车主已明确指定这台是别克，就不必再等
     * 身份文本才能切分支（某些固件在识别阶段就可能因通用流程抢先发 0x5702 而掉线）。
     * 默认 false —— 奥迪/宝马/凯迪拉克等既有车机的流程完全不变。
     */
    private val forceBuickE5: Boolean = false,
) {
    /** 从 0x5703 + 0x4301 解析出的接收端信息。 */
    data class CarPlayEndpoint(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val securityType: Int,
        val airPlayPort: Int,
        val deviceIdentifier: String?,
        val publicKey: String?,
        val sourceVersion: String?,
        val ipAddresses: List<String>,
        /**
         * `_airplay._tcp` TXT 的 features 位图（高 32 位已并入）。
         *
         * 关键位：bit38 `HK_PAIRING_AND_ENCRYPT` = 支持 HomeKit 配对/加密。
         * null = 未知（只有 0x4301、没发现 mDNS）——按「需要配对」处理，保持旧行为。
         * catplay 用它区分三个时代的接收端：pre-HomeKit（CarPlay 210.81，无该位，
         * 只能 `et=NONE` 明文会话 + AES-CTR 屏幕流）/ 支持但可跳过（280 时代）/
         * 强制配对（现代）。2026-10-19 奥迪 4xxx（4851）连不上与这条分类直接相关。
         */
        val advertisedFeatures: Long? = null,
        /** `0x4301` param 8：车机要求双向认证。见 [CarPlayStartSessionParams.mutualAuth]。 */
        val mutualAuthRequired: Boolean = false,
        /** `0x4301` param 6：车机的 CarPlay SDK 版本串。仅用于日志定位，不参与决策。 */
        val sdkVersion: String? = null,
        /**
         * 本轮是否按别克 E5（B233）建链。
         *
         * 下游要用它的地方：虚拟 iAP2 链路要切 B233 骨架（等车机 DETECT 再 echo SYN、
         * 0x5001/0x4171 不做致命重传）、蓝牙层成功后要把该地址记为 B233。
         */
        val isBuickB233: Boolean = false,
    )

    private val parser = Iap2Csm.Parser()

    /** 刚解析、尚未分类的帧（到达顺序）。 */
    private val receivedFrames = ArrayDeque<Iap2Csm.Frame>()

    /**
     * [expectOrNull] 等待期间取到的「非预期」帧暂存区；[recvFrame] 优先从这里取。
     *
     * **必须与 [receivedFrames] 分开**：两者若混用，expectOrNull 把非目标帧塞回
     * 它自己下一轮优先读取的队列，就会同一帧反复取出—记录—塞回形成活锁
     * （2026-10-18 实测：车机 bring-up 期间发 0x5000 StartNowPlayingUpdates，
     * 日志以 ~1000 行/秒刷屏直到等待超时）。
     */
    private val pendingFrames = ArrayDeque<Iap2Csm.Frame>()

    /** 暂存区上限：车机订阅类消息可能在长等待期间持续到达，无上限会吃内存。 */
    private val maxPendingFrames = 256
    private var pendingOverflowLogged = false

    private val random = SecureRandom()

    /** 0x5703 解析结果（保活期间车机重发 0x5702 时需重新应答）。 */
    @Volatile
    private var wifi: WifiConfiguration? = null

    /** 0x4301 原始体是否已记录（每轮会话一次，避免周期补发刷屏）。 */
    private var startSessionRawLogged = false

    /** 0x1D01 得到的车机身份文本——[AvailabilityBodyMemory] 的键（空 = 还没识别）。 */
    private var receiverIdentity: String = ""

    /**
     * 本轮 0x4300 用的报文体形态。
     *
     * null = 不启用试错阶梯（没有 Context：单测/纯 JVM），一律用新版嵌套体，行为与
     * 改动前一致。真机有 Context 时由 [AvailabilityBodyMemory.nextVariant] 决定。
     */
    private var availabilityVariant: AvailabilityBodyMemory.Variant? = null

    /**
     * 本轮是否走别克 E5（B233）专用分支。
     *
     * 判定来源有两个：构造参数 [forceBuickE5]（用户在下拉框里指定了别克档），
     * 或 0x1D01 身份文本同时含 buick / b233 / saic general motors。两者都不成立时
     * 保持 false，所有既有车机的行为与改动前逐字节一致。
     */
    private var buickB233: Boolean = false

    /** 最近一次晚到 0x4301 的端点指纹（车机周期重发相同内容时不重复打详情）。 */
    private var lastLateStartSessionSignature: Int? = null

    /**
     * 是否处于「已进入保活、正等车机下发 0x4301」的等待态。
     *
     * 为真时保活泵改用短超时轮询，并每隔 [NUDGE_INTERVAL_MILLIS] 重发一次
     * `0x4e0e`（传输标识）+ `0x4300`（CarPlay 可用性）「催问」车机。
     * 依据：凯迪拉克 CADILLAC_7731（Cinemo 栈）声明了 11 条消息——含 0x4301、
     * 0x6800、0x4c00、0x5000——却**一条都没主动发起**，只对我们的包回 ACK。
     * 这种「只应答不发起」的形态很像在等前置消息；而 0x4E0E 此前要等 8 秒窗口
     * 过期后才发，若车机正是等它，顺序就错了。
     */
    @Volatile
    private var awaitingStartSession = false

    /** 已催问次数（用于限次与日志节流）。 */
    @Volatile
    private var nudgeCount = 0

    /** 上次催问时间戳（毫秒）。 */
    @Volatile
    private var lastNudgeAt = 0L

    /**
     * 上次催问（或发 0x4300）时的对端控制帧计数，用来判断车机是否还在处理我们的帧。
     *
     * 2026-10-26 别克 E2LB 日志：车机 ACK 到 0x4e0c 后彻底停摆（只在 RFCOMM 上每秒发
     * 6 字节封装心跳），而我们的催问会**再补一轮 ACK 待确认的问候**——看门狗 23 秒后
     * `packet 111 not acknowledged` 拆链，5 分钟保活被切成 42 秒一轮的死循环。
     * 也就是说：**催问本身就是拆链元凶**。计数没涨 = 车机不处理，就别再喂。
     */
    private var lastNudgePeerFrames = -1

    /** 静默期跳过催问的次数（日志节流）。 */
    private var silentNudgeSkips = 0

    /**
     * 0x1D01 参数 6：车机声明「它可发送」的消息 ID 清单。
     * 用它判断 0x6800 (StartHID) / 0x4301 是否真的会来——声明里没有就不该盲等：
     * 奥迪实测只声明 0x5703/0xea03，旧流程因此在 0x4301 上空等 10s、StartHID 上空等 2s。
     */
    private var accessorySendIds: Set<Int> = emptySet()

    /** 0x1D01 参数 7：车机声明「它可接收」的消息 ID 清单。 */
    private var accessoryReceiveIds: Set<Int> = emptySet()

    /** 识别帧是否真的带了接收清单（空清单与「未提供」必须区分）。 */
    private var receiveListDeclared = false

    /**
     * WiFi 凭据交换的代际判定（[ReceiverProfile.classify] 的结果，0x1D01 到达时算一次）。
     *
     * 分派**只认它**，不再用「可收清单里有没有 0x5702」直接判：奥迪 3315 的清单
     * 只列 5 项、不含 0x5702，但它完整支持 0x5702→0x5703，而且**要靠收到 0x5702
     * 才把 AirPlay 服务拉起来**——按清单硬拦会走进「7000 端口全程 ECONNREFUSED」
     * 的死局（上游 0.1.13 实测，2026-10-22 定谳）。只有 NEW（两清单合计 ≥10 项
     * 仍无 0x57xx，宝马 iD9 形态）才跳过旧协议。
     */
    private var wifiGen: ReceiverProfile.WifiGen = ReceiverProfile.WifiGen.UNKNOWN

    /** 车机是否声明会发某消息；null = 未提供清单（未知，按保守策略等待）。 */
    private fun accessoryDeclaresSend(messageId: Int): Boolean? = if (accessorySendIds.isEmpty()) null else messageId in accessorySendIds

    /** 车机是否声明能收某消息；null = 未提供清单（未知，按保守策略继续）。 */
    private fun accessoryDeclaresReceive(messageId: Int): Boolean? = if (!receiveListDeclared) null else messageId in accessoryReceiveIds

    /** 「车机没声明能收」的日志去重：同一消息 ID 每轮会话只记一次（催问会反复触发）。 */
    private val undeclaredReceiveLogged = HashSet<Int>()

    /**
     * 可选消息「车机没声明能收」的**记录**（只记不发拦截，2026-10-27 修正）。
     *
     * 曾经的实现（1.0.8 起）把 0x1D01 参数 7 当硬门禁：清单里没有 `0x4E0D`/`0x4E0E`
     * 就真的不发。上游 cardeck 0.1.28 的真车证据推翻了这条判据——
     * 奥迪 3315 的可收清单只有 5 项、不含 `0x5702`，但它完整支持 `0x5702→0x5703`；
     * 同一天的日志里 `0x4e0d/0x4e0b/0x4e0e` 三条不在清单里，车机逐条 ACK 到位。
     * 结论：**清单只能证明「有」，不能证明「没有」**。
     *
     * 别克 E2LB 的日志同样证明裁剪没有换来推进（裁掉之后仍是 ACK 后沉默），
     * 所以这里退回「只记录」：帧照发，日志留证，将来对某台车复现时还能看出
     * 它到底声明了什么。
     */
    private fun recordUndeclaredReceive(messageId: Int) {
        if (accessoryDeclaresReceive(messageId) != false) return
        if (undeclaredReceiveLogged.add(messageId)) {
            log(
                "iap2 车机可收清单未声明 0x${messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(messageId)}) reason=accessory-did-not-advertise" +
                    "—— 仍照常发送（清单只能证明「有」不能证明「没有」，见上游 0.1.28 奥迪定谳）",
            )
        }
    }

    private companion object {
        /** 等 StartHID 的窗口：xcertplay 会发（订阅后立即到），DiPlay 不发。 */
        const val HID_WAIT_NANOS = 2_000_000_000L

        /**
         * 等 0x4301 的短窗口（声明会发的测试台毫秒级应答；没声明的车机直接跳过）。
         * 只在「车机没给声明清单」时才会用满窗口——从 10s 收到 3s：真车整个
         * bring-up 只有几十秒预算，且实测应答都在毫秒级。
         */
        const val START_SESSION_WAIT_NANOS = 3_000_000_000L

        /**
         * 新协议车机（没有 0x5703，AP 信息只在 0x4301 里）的等待窗口。
         * 这类车机必须先把热点信息给手机，手机才可能入网——窗口太短等于直接判死；
         * BMW iD9 实测整个 bring-up 在 10 秒量级，给 8 秒。
         */
        const val START_SESSION_WAIT_NEW_PROTOCOL_NANOS = 8_000_000_000L

        /** 0x4301 原始体日志上限（Audi 实测 261B，完整记录；异常大的只截前段）。 */
        const val START_SESSION_RAW_LOG_BYTES = 1_024

        // ---- 别克 E5（B233）专用 ----

        /** B233 的 MFi 挑战长度：**精确 32 字节**，长度不符车机直接断链。 */
        const val BUICK_MFI_CHALLENGE_BYTES = 32

        /** B233 的 MFi 应答窗口（实测应答在亚秒级，给 5 秒容错）。 */
        const val BUICK_MFI_WINDOW_MS = 5_000L

        /** 合法蓝牙 MAC 形态（B233 会用 group1 里的 MAC 与 RFCOMM 对端比对）。 */
        val LOCAL_MAC_PATTERN = Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

        /** 占位 MAC：构造出来必然是假标识，宁可报错也不发。 */
        val PLACEHOLDER_MACS = listOf("02:00:00:00:00:00", "00:00:00:00:00:00")

        /**
         * B233 等 0x4301 的窗口。
         *
         * 比其他新协议车机的 8 秒长：E5 要先吃完 0x4E0A/0x4E09/0x4E0B/0x4E0C 这组问候、
         * 再收下 75 字节 0x4300 才会回 0x4301，实测整段在 10~20 秒量级。
         */
        const val BUICK_START_SESSION_WAIT_NANOS = 30_000_000_000L

        /** B233 等 0x4301 时的重发间隔。 */
        const val BUICK_AVAILABILITY_POLL_MILLIS = 750L

        /**
         * 发 0x4300 **之前**的静默期：问候组（含 0x4E0C）要用自己的 ACK 走完一轮，
         * 抢在 ACK 之前灌 0x4300 会被车机当成乱序，之后整段不再回帧。
         */
        const val BUICK_PRE_AVAILABILITY_SETTLE_MS = 300L

        /**
         * 发 0x4300 **之后**的硬等：E5 实测在这段里专心处理可用性帧，
         * 期间再发任何控制帧都会让它放弃本轮握手。
         */
        const val BUICK_POST_AVAILABILITY_SETTLE_MS = 1_500L

        /** 等待 0x4301 时保活泵的轮询间隔（也是催问的最小检查粒度）。 */
        const val NUDGE_TICK_MILLIS = 5_000L

        /** 催问间隔：太密会像洪水把车机打懵，太疏则复测一轮耗时长。 */
        const val NUDGE_INTERVAL_MILLIS = 15_000L

        /** 催问上限（20 × 15s = 5 分钟，与主控的空端点等待窗口一致）。 */
        const val NUDGE_MAX_TIMES = 20

        /**
         * 已知「静默过」的档位重试窗口。首次试探保留原窗口（可能是慢车机），
         * 但已经失败过一次的档位没必要再等满 6 秒——真 iPhone 应答在 100ms 级。
         */
        const val MFI_SILENT_RETRY_WINDOW_MS = 1_500L

        /** 挑战档位（长度, 首次试探窗口）：顺序与窗口由 DiPlay/Audi 双端实测决定。 */
        private val MFI_ATTEMPTS = listOf(32 to 3_000L, 20 to 6_000L, 16 to 3_000L)

        /** 真车系接收端特征（0x1D01 name/model/manufacturer 子串，小写比对）。 */
        private val LEGACY_RECEIVER_MARKERS = listOf("audi", "delphi", "mib", "harman", "porsche")
    }

    /** 从 0x1D01 提取接收端身份文本（name/model/manufacturer 小写拼接）——MFi 挑战长度选择用。 */
    private fun identityText(frame: Iap2Csm.Frame): String {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        return listOfNotNull(
            params[0]?.value?.asStringOrEmpty(),
            params[1]?.value?.asStringOrEmpty(),
            params[2]?.value?.asStringOrEmpty(),
        ).joinToString(" ").lowercase()
    }

    fun run(timeoutMillis: Long = 60_000L): CarPlayEndpoint {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        if (!channel.awaitReady(remainingMillis(deadline))) {
            throw IOException("iAP2 链路协商超时")
        }
        log("iap2 link ready peerMaxControl=${channel.peerMaxControlPayload()}")

        // ---- 识别 ----
        send(Iap2MessageIds.START_IDENTIFICATION)
        val identification = expect(Iap2MessageIds.IDENTIFICATION_INFORMATION, deadline)
        logIdentification(identification)
        send(Iap2MessageIds.IDENTIFICATION_ACCEPTED)
        log("iap2 tx=0x1d02 identification-accepted")

        // EA 协议解析（启动时机见下方 MFi 完成后——2026-10-11 DiPlay 实测：
        // 认证前发 0xEA00 会让测试台状态机停摆，不再回 0xAA01；奥迪对此不敏感）
        val eaProtocols = parseEaProtocols(identification)

        // ---- MFi（跳过校验：0xAA05 自报成功）----
        val identityText = identityText(identification)
        // 别克 B233 判定：用户在「连接配置」里指定别克档，或身份文本三个标记齐全。
        // 判定必须发生在 MFi 之前——B233 对挑战长度是硬要求（只认 32B，不符立即断链），
        // 一旦先按通用 20B 试过，车机侧就可能已经把会话掐了。
        buickB233 = forceBuickE5 || B233Identity.confirmsB233(identityText)
        if (buickB233) {
            log(
                "iap2 识别为别克 B233（${if (forceBuickE5) "下拉框指定别克档" else identityText}）——" +
                    "走专用建链：MFi 严格 32B、不发 0x5702、0x4300 用 75B 双 group、0x4301 750ms 轮询",
            )
        }
        // 0x4300 报文体形态的按车机试错记忆（见 AvailabilityBodyMemory 文档：
        // 别克 E2LB 收到新版嵌套体后控制会话直接停摆）。无 Context（单测）时固定新版。
        receiverIdentity = identityText
        // 只有真机（有 Context，可持久化）才启用阶梯；单测/纯 JVM 固定新版嵌套体，
        // 协议时序与改动前完全一致（也让用例之间不会通过进程内记忆互相污染）。
        // B233 不走阶梯：它的形态是确定的（双 group），不需要猜。
        availabilityVariant =
            if (buickB233) {
                null
            } else {
                if (context == null) null else AvailabilityBodyMemory.nextVariant(context, identityText)
            }
        availabilityVariant?.let {
            log(
                "iap2 0x4300 报文体形态：${it.label}" +
                    "（该车机历史静默档=${AvailabilityBodyMemory.silentDescription(context, identityText)}）",
            )
        }
        val legacyCar = LEGACY_RECEIVER_MARKERS.any { identityText.contains(it) }
        if (legacyCar) {
            log("iap2 识别为真车系（$identityText）——MFi 首选 20B（真 iPhone 实录长度；32B 对真车处置不稳定）")
        }
        send(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
        val certificate = expect(Iap2MessageIds.MFI_CERTIFICATE, deadline)
        log("iap2 rx=0xaa01 certificate bytes=${certificate.body.size}（跳过校验）")
        // 挑战长度按接收端身份选择（2026-10-11 奥迪字节级实测修正）：
        //  - 真车系（Audi/Delphi MIB 等）：**20B 首试**。真 iPhone 实录（carplay-wifi-extractor
        //    client）与 catplay 发送端都用 20B；奥迪对 20B 秒答。32B 先行会导致车机
        //    ACK 挑战帧后**整段静默**（不回 ACK、不回应答，会话变僵尸）——本日多轮实测，
        //    32B→20B 的会话全部卡死，20B→（记忆命中）的会话全部秒通。
        //  - 测试台 DiPlay（Carlinkit AuthV3 EC 身份）：**只接受精确 32B**，长度不符直接断链。
        // 全部无应答也不中止——我们不校验签名（0xAA05 自报），由 0x5702 是否应答判定放行。
        runMfiChallenge(deadline, prefer20First = legacyCar, receiverKey = identityText, buickStrict = buickB233)
        send(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
        log("iap2 tx=0xaa05 authentication-succeeded（自报）")

        // ---- 设备状态问候：**紧跟 0xAA05**（上游 PR #17 / BMW iD8.5 实测）----
        // 车机据这批消息判定「发送端已就绪」，晚发会被当成还没起来。旧实现把它们
        // 排在 awaitOptionalHid（最多 2s）之后，等于让车机多等 2 秒——凯迪拉克日志里
        // 0xaa05 在 33.248、问候在 35.249，中间空了 2 秒正是那段 HID 等待。
        sendDeviceStateGreetings()

        // ---- EA 会话启动（MFi 认证完成后）----
        // 奥迪 0x1D01 声明 de.audi.mmicconnectdatatransfer / com.audi.cpp.eadata0/1
        // 三个 EA 协议；触摸 HID 相关数据经 EA 会话。认证前发送会噎死 DiPlay（见上）。
        for ((protocolId, name) in eaProtocols) {
            sendFireAndForget(
                Iap2MessageIds.EA_OPEN_SESSION,
                // param 1 = EA 协议名（UTF-8 字符串）。发成协议号时车机不回 0xEA03
                // （上游 PR #17 实测修正）。
                Iap2Csm.u8(0, protocolId) + Iap2Csm.string(1, name),
            )
            log("iap2 tx=0xea00 startEASession protocol=$protocolId name=$name")
        }

        // ---- 订阅与 HID：0x6800 是可选步骤 ----
        // xcertplay 在订阅后发 StartHID；DiPlay 删掉了这一步（订阅完直接等 0x5702）。
        // 因此这里只短暂等待：收到 0x6800 就应答 0x6801，2 秒内没等到就直接继续。
        awaitOptionalHid(deadline)

        // ---- WiFi 配置交换（两代协议）----
        // 旧协议（0x5702 → 0x5703）：手机请求，车机回 AP 明文——Audi MIB3 走这条。
        // 新协议（0x4300 → 0x4301）：**AP 信息由 0x4301 自带**（Addendum 新版的
        // WirelessCarPlayParams），BMW iD9 走这条——它在 0x1D01 里既不声明能收 0x5702，
        // 也不声明会发 0x5703。旧流程对这类车机会在 0x5703 上死等 10 秒后拆链
        // （2026-10-19 BMW92438 实车日志：每轮都是「等待 0x5703 超时」）。
        // 因此按车机画像（[ReceiverProfile.wifiGen]）分派，**不是**按「清单里有没有
        // 0x5702」：清单只能证明「有」，不能证明「没有」（上游 0.1.28 奥迪 3315 定谳，
        // 别克 E2LB 日志同证）。只有 NEW 才跳过旧协议，LEGACY/UNKNOWN 一律发。
        val declaresReceiveWifi =
            accessoryDeclaresReceive(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
        val declaresSendWifi = accessoryDeclaresSend(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION)
        // 别克 B233 无论画像怎么判都不发 0x5702：它的能力清单没声明可收，E5 实测
        // 每轮都在这里沉默、把整个握手拖死。AP 信息只从 0x4301 取。
        val legacyWifiExchange = !buickB233 && wifiGen != ReceiverProfile.WifiGen.NEW
        val wifiFromExchange: WifiConfiguration? =
            if (buickB233) {
                log("iap2 别克 B233 跳过 0x5702（车机未声明可收）——AP 信息只从 0x4301 取")
                null
            } else if (!legacyWifiExchange) {
                log(
                    "iap2 画像判 wifiGen=NEW（两清单完整仍无 0x57xx，收=${declaresReceiveWifi ?: "未知"} " +
                        "发=${declaresSendWifi ?: "未知"}）——跳过旧协议，AP 信息由 0x4301 提供",
                )
                null
            } else {
                send(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                val wifiConfiguration = expectOrNull(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION, deadline)
                if (wifiConfiguration == null) {
                    log("iap2 0x5703 未在窗口内到达——改走 0x4300/0x4301（新版协议自带 AP 信息）")
                    null
                } else {
                    val parsed = parseWifiConfiguration(wifiConfiguration)
                    log(
                        "iap2 rx=0x5703 ssid=${parsed.ssid} channel=${parsed.channel} " +
                            "security=${parsed.securityType} psk=${if (parsed.passphrase.isEmpty()) "(空)" else "***"}",
                    )
                    // 真车死锁解药（catplay CarPlayInviter 实证）：车机在 WiFi 网络上发现手机的
                    // _carplay-ctrl._tcp 后主动发 GET /ctrl-int/1/connect 邀请，**邀请成功才发 0x4301**。
                    // 因此这里必须立即通知主控绑 WiFi + 广播 ctrl（带真实蓝牙 MAC 作 TXT id）。
                    onWifiCredentials?.invoke(parsed)
                    parsed
                }
            }

        // ---- 设备状态问候（已在 0xAA05 之后立即发过，这里幂等空转）----
        // Audi MIB3 实测：缺这批消息时，车机对 0x4300 只回链路 ACK、不出 0x4301。
        sendDeviceStateGreetings()

        // ---- CarPlay 会话启动 ----
        // 两种对端形态：
        //  - 测试台 DiPlay/部分车厂：收 0x4300 后回 0x4301（含 AirPlay 端口/IP/公钥）
        //  - Audi MIB3：识别清单只声明发送 0x5703/0xea03，**不认 0x4300、不发 0x4301**；
        //    AirPlay 端点走 WiFi 上的 mDNS（_airplay._tcp）。因此 0x4301 只给短窗口，
        //    超时返回无端点的 CarPlayEndpoint，由主控 mDNS 合成。
        // 0x4300 同样发后不管：2026-10-11 12:52 轮实测车机曾对它停止 ACK
        // （事件通道 SYN 洪水把它打懵的时段），链路看门狗险些再次自杀。
        // 发之前记一次对端控制帧计数：别克 E2LB 实测它收到这一帧后就**彻底不再 ACK**，
        // 用这个计数把「对端还在不在处理我们的帧」量出来（见下方 noteAvailabilitySilence）。
        val framesBeforeAvailability = channel.peerFrameCount
        // 催问的静默基线也钉在这一刻：第一次催问（+15s）就能判断车机是否还在回帧。
        lastNudgePeerFrames = framesBeforeAvailability
        // 别克 B233：问候组（含 0x4E0C）要先用自己的 ACK 走完一轮，抢在 ACK 之前
        // 灌 0x4300 会被车机当成乱序，之后整段不再回帧。
        if (buickB233) {
            sleepQuietly(BUICK_PRE_AVAILABILITY_SETTLE_MS)
            if (!channel.isAlive) throw IOException("别克 B233：发 0x4300 之前链路已断开")
        }
        sendAvailability()
        // 别克 B233：0x4300 之后车机专心处理可用性帧，这段窗口里再发任何控制帧
        // 都会让它放弃本轮握手——硬等一段再开始轮询。
        if (buickB233) {
            sleepQuietly(BUICK_POST_AVAILABILITY_SETTLE_MS)
            if (!channel.isAlive) {
                throw IOException("别克 B233：0x4300 之后链路被车机断开（不接受该报文形态）")
            }
        }
        // 车机声明里没有 0x4301 就直接跳过等待（Audi 实测每轮白等 10s）：
        // 真 iPhone 对这类车机也不等——AirPlay 端点经 mDNS/网关合成。
        val declaresStartSession = accessoryDeclaresSend(Iap2MessageIds.CARPLAY_START_SESSION)
        // 没拿到 0x5703（新协议车机）时，0x4301 是**唯一**的 AP 信息来源——窗口给足；
        // 真有 0x5703 的旧协议车机保持 3 秒短窗口（应答都在毫秒级）。
        // 别克 B233 用最长窗口：它要吃完问候组再处理 75/69 字节的双 group 报文。
        val startSessionWindow =
            when {
                buickB233 -> BUICK_START_SESSION_WAIT_NANOS
                wifiFromExchange == null -> START_SESSION_WAIT_NEW_PROTOCOL_NANOS
                else -> START_SESSION_WAIT_NANOS
            }
        var startSession: Iap2Csm.Frame? = null
        if (buickB233) {
            // 别克不看「有没有声明会发 0x4301」（E2LB 日志里它压根不声明），
            // 一律按 750ms 轮询重发 0x4300 催到窗口结束。
            var pollCount = 0
            startSession =
                expectOrNull(
                    Iap2MessageIds.CARPLAY_START_SESSION,
                    minOf(deadline, System.nanoTime() + startSessionWindow),
                    pollIntervalMillis = BUICK_AVAILABILITY_POLL_MILLIS,
                    onPoll = {
                        if (channel.isAlive) {
                            pollCount++
                            log(
                                "iap2 别克 B233：0x4301 未到，第 $pollCount 次重发 0x4300" +
                                    "（${BUICK_AVAILABILITY_POLL_MILLIS}ms 轮询）",
                            )
                            runCatching { sendBuickAvailability() }
                        }
                    },
                )
        } else if (declaresStartSession == false) {
            log("iap2 车机未声明会发 0x4301——跳过等待（AirPlay 端点走 mDNS/网关）")
        } else {
            // 车机可能只发 0x4301 而完全不回答 0x5702：发送后先等一轮，
            // 若这段时间里 0x4301 先到了（新协议的正常顺序），直接采用。
            startSession =
                expectOrNull(
                    Iap2MessageIds.CARPLAY_START_SESSION,
                    minOf(deadline, System.nanoTime() + startSessionWindow),
                )
        }
        // 别克 B233 沉默时**绝不合成 Wi-Fi 凭据**：0x4301 缺失就是明确的建链失败。
        // 这与只走 mDNS 的奥迪不同——E5 的端点和热点都只在这条报文里，
        // 猜一个 SSID 出去只会让车主看到「连上了但一直黑屏」。
        if (buickB233 && startSession == null) {
            throw IOException(
                "别克 B233：0x4301 在 ${startSessionWindow / 1_000_000_000}s 内未到，" +
                    "拿不到车机热点与 AirPlay 端口——本轮断开重连（请确认车机上已选择本机）",
            )
        }
        // 静默判定只在这条路上有意义：新协议车机（没有 0x5703）+ 声明会发 0x4301 + 没等到。
        // 老协议车机（Audi 等）对 0x4300 的处置不影响后续流程，不做阶梯，避免回归。
        if (startSession == null && declaresStartSession == true && wifiFromExchange == null) {
            noteAvailabilitySilence(framesBeforeAvailability, startSessionWindow)
        } else if (startSession != null) {
            availabilityVariant?.let { AvailabilityBodyMemory.recordSuccess(context, receiverIdentity, it) }
        }
        val endpoint =
            if (startSession != null) {
                val parsed = parseStartSession(startSession, wifiFromExchange)
                // 下游（虚拟 iAP2 链路切 B233 骨架、蓝牙层记下该地址是 B233）要这个标记
                if (buickB233) parsed.copy(isBuickB233 = true) else parsed
            } else {
                // 不能断言「该车机不发」：Audi MMI 0000 声明会发、也确实发，只是晚到
                // 2 分 35 秒（2026-10-18 日志）。措辞必须保留「稍后可能补发」的预期，
                // 否则排查时会被这句话误导成「这条路对本车不通」。
                log(
                    if (declaresStartSession == true) {
                        "iap2 0x4301 在 ${startSessionWindow / 1_000_000_000}s 窗口内未到" +
                            "（车机声明会发——可能稍后补发，保活期间收到会重新触发配对）——" +
                            "先用 mDNS/网关"
                    } else {
                        "iap2 0x4301 未到（车机未声明会发；AirPlay 端点走 mDNS _airplay._tcp）"
                    },
                )
                if (wifiFromExchange == null) {
                    // 无 0x5703。分两种情况，前一种不拆链：
                    if (declaresStartSession == true) {
                        // 新协议车机（GM/Cinemo、BMW iD9 同族：无 0x5702/0x5703，AP 信息只在
                        // 0x4301 里）声明会发 0x4301，但窗口内未到。凯迪拉克 CT5 实测
                        // （2026-10-21 日志，对应上游 PR #19）：8s 窗口全空，旧逻辑拆链重连，
                        // 6 轮循环车机始终静默、车机屏「无法连接到 CarPlay 请重试」；
                        // 而真 iPhone 忘掉热点后仍能无线连上——说明车机会经蓝牙把凭据推下来，
                        // 只是晚发或等前置条件（奥迪 MMI 9310 先例：Phase 1 结束后 2 分 35 秒才发）。
                        // **不拆链**：返回空端点让主控进入等待态，晚到的 0x4301 由保活泵经
                        // handleLateStartSession → onEndpointUpdate 重新触发配对。
                        log(
                            "iap2 0x4301 窗口内未到但车机声明会发——不拆链，返回空端点进入保活；" +
                                "晚到 0x4301 将重新触发配对（Audi MMI 9310 先例：2 分 35 秒）",
                        )
                        // 进入「催问」模式：保活泵会周期性重发 0x4e0e + 0x4300。
                        // 依据 2026-10-26 凯迪拉克日志——车机声明了 11 条消息（含 0x4301/0x6800/
                        // 0x4c00/0x5000）却**一条都没主动发起**，只回 ACK，像是卡在等某个前置消息。
                        // 0x4E0E（传输标识）此前要等 8s 窗口过期后才发，若车机正是等它，顺序就错了。
                        awaitingStartSession = true
                        CarPlayEndpoint(
                            ssid = "",
                            passphrase = "",
                            channel = 0,
                            securityType = 0,
                            airPlayPort = 0,
                            deviceIdentifier = null,
                            publicKey = null,
                            sourceVersion = null,
                            ipAddresses = emptyList(),
                        )
                    } else {
                        // 一点线索都没有（车机也没承诺会发 0x4301），继续等只会空转。
                        throw IOException(
                            "未拿到车机热点凭据：本轮 0x5702/0x4301 都没给出 AP 信息，" +
                                "且车机没有声明会发 0x4301（请先在车机上重新选择本机）",
                        )
                    }
                } else {
                    CarPlayEndpoint(
                        ssid = wifiFromExchange.ssid,
                        passphrase = wifiFromExchange.passphrase,
                        channel = wifiFromExchange.channel,
                        securityType = wifiFromExchange.securityType,
                        airPlayPort = 0,
                        deviceIdentifier = null,
                        publicKey = null,
                        sourceVersion = null,
                        ipAddresses = emptyList(),
                    )
                }
            }
        if (startSession != null) {
            logStartSessionRaw(startSession)
            log(
                "iap2 rx=0x4301 airPlayPort=${endpoint.airPlayPort} " +
                    "device=${endpoint.deviceIdentifier} publicKey=${endpoint.publicKey?.take(16)}… " +
                    "srcvers=${endpoint.sourceVersion} sdk=${endpoint.sdkVersion ?: "(未给)"} " +
                    "mutualAuth=${endpoint.mutualAuthRequired} ips=${endpoint.ipAddresses}",
            )
        }

        // 真机顺序：0x4301 之后回 0x4E0E（传输标识确认）。
        // Audi 实测：车机对该消息**接受但从不 ACK**（其余包毫秒级 ACK）——必须发后不管，
        // 否则重传看门狗在 +20s 把整条蓝牙链路杀掉（EA 会话/触摸载体随之丢失）。
        // 车机可收清单里没声明也照发（只记录，见 [recordUndeclaredReceive]）。
        recordUndeclaredReceive(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER)
        sendFireAndForget(
            Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER,
            Iap2Csm.string(0, bluetoothMac),
        )
        log("iap2 0x4e0e/0x4300 —— Phase 1 完成：拿到热点凭据与（可能的）AirPlay 端点")

        // 后续（Phase 2）：加入热点 → mDNS 解析 _airplay._tcp → AirPlay 主会话。
        // 0x5703 缺席时 AP 信息来自 0x4301（已并入 endpoint）——保活期车机重发
        // 0x5703/0x4301 都要用它解析，不能留空。
        // 空端点（声明会发 0x4301 但窗口内未到）必须保持 wifi=null：否则保活期
        // parseStartSession 的 `ap = wifi ?: fromSession` 会用空凭据覆盖晚到 0x4301
        // 自带的 AP 信息，把唯一的凭据来源丢掉。
        if (endpoint.ssid.isNotEmpty()) {
            this.wifi =
                wifiFromExchange ?: WifiConfiguration(
                    ssid = endpoint.ssid,
                    passphrase = endpoint.passphrase,
                    channel = endpoint.channel,
                    securityType = endpoint.securityType,
                )
        }
        return endpoint
    }

    /**
     * Phase 2 过渡：bring-up 完成后保持链路打开，持续记录配件发来的控制消息
     * （如 0x4E0E 后重发的 0x5703、位置请求 0xFFFA 等），直到链路断开。
     * 真实无线 CarPlay 中这条控制通道与 AirPlay 会话并存于整个连接生命周期。
     */
    fun pumpIncomingForever() {
        log(
            "SESSION 保活中（链路保持打开，持续记录配件消息）" +
                if (awaitingStartSession) "——车机尚未下发 0x4301，将每 ${NUDGE_INTERVAL_MILLIS / 1000}s 催问一次" else "",
        )
        // 同一消息 ID 连续到达时限流：车机进入媒体播放后会周期性发 NowPlaying 更新，
        // 逐条打日志会把文件日志撑爆（文件超 2MB 直接删，等于丢掉真正有用的上下文）。
        var lastLoggedId = -1
        var repeatCount = 0
        while (true) {
            // 等待态用短超时：`recvFrame` 阻塞期间没法催问，只能靠「空转一次就检查一次」。
            val frame = recvFrame(if (awaitingStartSession) NUDGE_TICK_MILLIS else 30_000L)
            if (frame == null) {
                if (!channel.isAlive) break
                if (awaitingStartSession) nudgeIfDue()
                continue
            }
            // 生命周期消息必须先于日志限流处理：0x4301 可能作为「车机刚打开 CarPlay
            // 窗口」的晚信号在 Phase 1 之后才到。若跟着限流逻辑走，它要么被当成
            // keep-alive 只记长度，要么因与上一条同 ID 被 continue 掉——两种都等于
            // 丢弃（2026-10-18 Audi MMI 0000「连上后 15 秒自动断开」的根因）。
            if (frame.messageId == Iap2MessageIds.CARPLAY_START_SESSION) {
                handleLateStartSession(frame)
            }
            // 0x5000 NowPlaying 订阅：蓝牙链路（测试台）在这里订阅，回一次 0x5001。
            // 真车（MIB3）的 NowPlaying 订阅走 WiFi 隧道（VirtualIap2Link），此处
            // provider 为 null 时静默忽略，不影响链路。
            if (frame.messageId == Iap2MessageIds.START_NOW_PLAYING_UPDATES) {
                answerNowPlaying("订阅", retriesLeft = nowPlayingRetryDelaysMs.size)
            }
            if (frame.messageId == lastLoggedId) {
                repeatCount++
                if (repeatCount % 100 != 0) continue
            } else {
                if (repeatCount >= 99) {
                    log("iap2 上一条 0x${lastLoggedId.toString(16)} 重复 ${repeatCount + 1} 次（同类已限流）")
                }
                lastLoggedId = frame.messageId
                repeatCount = 0
            }
            log(
                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)}) keep-alive bytes=${frame.body.size}",
            )
        }
        log("SESSION 保活结束（链路断开）")
    }

    /** 设备状态问候是否已发过（幂等；[sendDeviceStateGreetings] 的 force 可再发一轮）。 */
    private var greetingsSent = false

    /**
     * 设备状态问候：0x4E0D 无线会话标识 / 0x4E0A 语言 / 0x4E09 设备名 /
     * 0x4E0B 设备时间 / 0x4E0C 设备 UUID。
     *
     * **必须紧跟 0xAA05**——上游 PR #17（BMW iD8.5 实车）确认车机据这批消息判定
     * 发送端已就绪；晚发会被当成还没起来。默认只发一次，[force] = true 时再补一轮
     * （等待 0x4301 期间的催问、0x5703 迟迟不来时的救援都用它）。
     */
    private fun sendDeviceStateGreetings(force: Boolean = false) {
        if (greetingsSent && !force) return
        greetingsSent = true
        val sent = ArrayList<Int>(5)
        // 0x4E0D（无线可用）与 0x4E0C（设备 UUID）：车机可收清单里没声明也照发，
        // 只记一条日志（2026-10-27 起取消裁剪，见 [recordUndeclaredReceive]）。
        recordUndeclaredReceive(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE)
        send(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE, Iap2Csm.u8(0, 1))
        sent += Iap2MessageIds.WIRELESS_CARPLAY_UPDATE
        send(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE, Iap2Csm.string(0, "zh-CN"))
        sent += Iap2MessageIds.DEVICE_LANGUAGE_UPDATE
        send(Iap2MessageIds.DEVICE_INFORMATION_UPDATE, Iap2Csm.string(0, deviceName))
        sent += Iap2MessageIds.DEVICE_INFORMATION_UPDATE
        val epochSeconds = System.currentTimeMillis() / 1000L
        val timezoneMinutes =
            java.util.TimeZone
                .getDefault()
                .getOffset(System.currentTimeMillis()) / 60_000
        send(
            Iap2MessageIds.DEVICE_TIME_UPDATE,
            Iap2Csm.param(0, u64Bytes(epochSeconds)) +
                Iap2Csm.u16(1, timezoneMinutes and 0xFFFF) +
                Iap2Csm.u8(2, 0),
        )
        sent += Iap2MessageIds.DEVICE_TIME_UPDATE
        recordUndeclaredReceive(Iap2MessageIds.DEVICE_UUID_UPDATE)
        send(
            Iap2MessageIds.DEVICE_UUID_UPDATE,
            Iap2Csm.string(0, deviceUuid()),
        )
        sent += Iap2MessageIds.DEVICE_UUID_UPDATE
        val tag = if (force) "（补发）" else ""
        log(
            "iap2 tx=" + sent.joinToString("/") { "0x%04x".format(it) } +
                " 设备状态问候$tag（name=$deviceName mac=$bluetoothMac）",
        )
    }

    /**
     * 等待 0x4301 期间的「催问」：重发 0x4e0e + 0x4300。
     *
     * 这是**假设验证**用的一步，不是已证实的协议要求：凯迪拉克那台车机声明了
     * 0x4301/0x6800/0x4c00/0x5000 却一条都不主动发起，最可能的解释是它在等某条
     * 前置消息。0x4E0E 是「我这条会话挂在蓝牙上」的显式告知，真 iPhone 在
     * 0x4300 前后都会发；我们此前只在 8s 窗口过期后才补一条。
     *
     * 限次（[NUDGE_MAX_TIMES]）而非无限重发：车机若始终不理，继续喂也只会把日志刷满，
     * 5 分钟窗口到点后由主控拆链重连。
     *
     * **车机零回帧时直接跳过催问**（见 [lastNudgePeerFrames]）：催问里的问候是 ACK
     * 待确认的，喂给一个已经不回 ACK 的会话等于自己触发重传看门狗拆链。
     */
    private fun nudgeIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastNudgeAt < NUDGE_INTERVAL_MILLIS) return
        if (nudgeCount >= NUDGE_MAX_TIMES) return
        lastNudgeAt = now
        // 车机自 0x4300 起一帧都没回（连纯 ACK 都没有）→ 停止催问。
        // 这不是「少发几条」的问题：催问里的问候走 send()（ACK 待确认），车机不回 ACK，
        // 重传看门狗 4 次 × ~4s ≈ 23 秒后 `packet N not acknowledged` 拆链——**催问
        // 本身就是拆链元凶**，把「最长 5 分钟保活」切成 42 秒一轮的重连死循环（别克
        // E2LB 2026-10-26 实测 6 轮，12 次 0x4300 全部零回应）。
        // 静默等待反而能接住晚到的 0x4301；一旦对端恢复回帧，下一轮自动恢复催问。
        val peerFrames = channel.peerFrameCount
        val peerSilent = lastNudgePeerFrames >= 0 && peerFrames == lastNudgePeerFrames
        lastNudgePeerFrames = peerFrames
        if (peerSilent) {
            silentNudgeSkips++
            if (silentNudgeSkips == 1 || silentNudgeSkips % 4 == 0) {
                log(
                    "iap2 车机零回帧（累计 $peerFrames 帧），跳过第 $silentNudgeSkips 次催问——" +
                        "不再喂它没反应的帧，链路保持打开等它自己发 0x4301（继续催问会被重传看门狗拆链）",
                )
            }
            return
        }
        nudgeCount++
        // 先补一轮设备状态问候（上游 PR #17 的「0x5703 分段救援」用的是同一招：
        // 车机不推进时重发问候，而不是干等），再告诉它传输方式，最后重新询问可用性。
        sendDeviceStateGreetings(force = true)
        // 0x4E0E：告诉车机「这条 iAP2 会话的传输是蓝牙」，再问一次能不能开始 CarPlay。
        // 车机可收清单里没声明也照发（只记录）——2026-10-27 起取消裁剪：别克 E2LB 与
        // 上游奥迪 3315 的证据都表明「没声明 ≠ 不能收」，裁掉只换来日志更干净。
        // **0x4300 本来就不裁**：它是驱动 0x4301 的请求本身，宁可多发一次也不能不发。
        recordUndeclaredReceive(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER)
        sendFireAndForget(
            Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER,
            Iap2Csm.string(0, bluetoothMac),
        )
        sendAvailability()
        // 第 1 次必记（确认机制真的跑了），之后每 4 次记一条，避免刷屏。
        if (nudgeCount == 1 || nudgeCount % 4 == 0) {
            log("iap2 催问第 $nudgeCount 次：重发问候 + 0x4e0e + 0x4300——车机仍未下发 0x4301，链路正常保活中")
        }
    }

    /**
     * 发 `0x4300`（CarPlay 可用性），按本轮选定的报文体形态。
     *
     * 三种形态来自参考实现（CarWith-CarPlay）的 `useModernAvailabilityBody` 分档：
     *  - [AvailabilityBodyMemory.Variant.MODERN_NESTED]：`param1 = {param0={1}, param1=transportId}`
     *    —— 24 款档，也是我们一直用的、凯迪拉克/DiPlay 都认的形态；
     *  - [AvailabilityBodyMemory.Variant.LEGACY_FLAT]：`param0={1}` + DeviceIdentifier 字符串
     *    （`00008110` + MAC 去冒号大写 + `0000`），参考实现**原样与全小写各发一份**
     *    （老车机对大小写敏感），我们照抄；
     *  - [AvailabilityBodyMemory.Variant.NONE]：23 款档根本不发——这批车机疑似不吃任何形态。
     *
     * 都是发后不管（[sendFireAndForget]）：0x4300 曾被实测「ACK 但有车机从此沉默」，
     * 进重传队列只会让看门狗提前把链路杀掉。
     */
    private fun sendAvailability() {
        if (buickB233) {
            sendBuickAvailability()
            return
        }
        when (availabilityVariant ?: AvailabilityBodyMemory.Variant.MODERN_NESTED) {
            AvailabilityBodyMemory.Variant.MODERN_NESTED ->
                sendFireAndForget(
                    Iap2MessageIds.CARPLAY_AVAILABILITY,
                    Iap2Csm.group(1, Iap2Csm.u8(0, 1), Iap2Csm.string(1, bluetoothMac)),
                )

            AvailabilityBodyMemory.Variant.LEGACY_FLAT -> {
                val identifier = legacyDeviceIdentifier()
                val flat = { value: String -> Iap2Csm.u8(0, 1) + Iap2Csm.string(1, value) }
                sendFireAndForget(Iap2MessageIds.CARPLAY_AVAILABILITY, flat(identifier))
                sendFireAndForget(Iap2MessageIds.CARPLAY_AVAILABILITY, flat(identifier.lowercase()))
            }

            AvailabilityBodyMemory.Variant.NONE ->
                log("iap2 0x4300 本轮不发（该车机对两种报文体都无反应——只等它自己发 0x4301）")
        }
    }

    /**
     * 别克 B233 的 0x4300：**双 group**，与通用线的单 group 嵌套体不同。
     *
     * 形状对照 iPhone 抓包：group(0) 放设备标识，group(1) 放本机真实蓝牙 MAC。
     * 车机会校验 group1 里的 MAC 是否等于 RFCOMM 链路对端，所以这里必须是
     * [bluetoothMac] 的真值——占位 MAC（`02:00:00:00:00:00`）会直接导致车机沉默，
     * 因此宁可抛错也别发一帧假的。
     *
     * 与通用线一样发后不管：B233 对未被 ACK 的帧会在约 20 秒后拆链，
     * 进重传队列只会让看门狗提前自杀。
     */
    private fun sendBuickAvailability() {
        val mac = bluetoothMac.trim()
        if (!LOCAL_MAC_PATTERN.matches(mac) || PLACEHOLDER_MACS.any { it.equals(mac, ignoreCase = true) }) {
            throw IOException("别克 B233：本机蓝牙 MAC 非法（$mac），拒绝构造 0x4300")
        }
        val body =
            Iap2Csm.group(
                0,
                Iap2Csm.u8(0, 1),
                Iap2Csm.string(1, carPlayDeviceIdentifier()),
            ) +
                Iap2Csm.group(
                    1,
                    Iap2Csm.u8(0, 1),
                    Iap2Csm.string(1, mac),
                )
        val frame = Iap2Csm.encode(Iap2MessageIds.CARPLAY_AVAILABILITY, body)
        // 只记 SHA-256 前 8 字节：成功/失败轮次可对比，又不泄露设备标识与蓝牙 MAC。
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(frame)
                .take(8)
                .joinToString("") { "%02x".format(it) }
        log(
            "iap2 别克 B233 0x4300 双 group（group0 设备标识 / group1 真实 MAC）" +
                "frameBytes=${frame.size} sha256=$digest",
        )
        sendFireAndForget(Iap2MessageIds.CARPLAY_AVAILABILITY, body)
    }

    /**
     * 别克 B233 的 CarPlay 设备标识：由本机蓝牙 MAC 派生，**绝不复制真 iPhone 的私有 ID**。
     *
     * 与通用线的 DeviceIdentifier 同样只是「发送端身份」，车机不校验真伪，
     * 但同一台车会按它去重旧会话，所以必须在同一台手机上保持稳定。
     */
    private fun carPlayDeviceIdentifier(): String {
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(("CarPToCarL-CarPlay:" + bluetoothMac.uppercase()).toByteArray())
        return digest.take(12).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    /**
     * 判定「0x4300 是否把车机的控制会话打住了」，并据此推进报文体阶梯。
     *
     * 判据：从发出 0x4300 到整个等待窗口结束，[Iap2LinkChannel.peerFrameCount] 一次都没涨
     * ——连纯 ACK 都没有。2026-10-26 别克 E2LB 日志就是这种形态：ACK 停在 0x4e0c，
     * 之后 23 秒只有 `ff 55 02 00 ee 10` 链路心跳，最后我方看门狗
     * `packet N not acknowledged` 拆链 → 每 40 秒一轮死循环。
     *
     * 注意：这不是「车机不发 0x4301」的证据（这个家族从来不发），只是「它不认这一帧」
     * 的证据。所以判到静默就换下一档，而不是断言此路不通。
     */
    private fun noteAvailabilitySilence(
        framesBefore: Int,
        windowNanos: Long,
    ) {
        val variant = availabilityVariant ?: return
        if (variant == AvailabilityBodyMemory.Variant.NONE) return
        val framesAfter = channel.peerFrameCount
        if (framesAfter != framesBefore) return
        val next = AvailabilityBodyMemory.recordSilent(context, receiverIdentity, variant)
        log(
            "iap2 ⚠ 0x4300（${variant.label}）发出后 ${windowNanos / 1_000_000_000}s 内车机零反馈：" +
                "连链路层 ACK 都没回（同一份字节在凯迪拉克 CADILLAC_7731 上是正常 ACK 的），" +
                "疑似该固件不认这个报文体；已记为静默，下一轮改用「${next.label}」",
        )
    }

    /**
     * 老版可用性报文里的 DeviceIdentifier（参考实现 `Ct5Variants` 的拼法）：
     * `00008110` + 本机蓝牙 MAC（去冒号、大写）+ `0000`；MAC 形态不对时用参考实现的兜底值。
     */
    private fun legacyDeviceIdentifier(): String {
        val mac = bluetoothMac.filter { it.isLetterOrDigit() }.uppercase()
        return if (mac.length == 12) "00008110${mac}0000" else "00008110000105201EDA801E"
    }

    /**
     * 歌名变化时**主动补推**一次 0x5001（不等下一次 0x5000 订阅）。
     *
     * 蓝牙链路的接收端（WinPlay/DiPlay 测试台）通常只在 bring-up 后发一次 0x5000，
     * 之后切歌不会重订阅——歌名若不主动补推，车机会停在订阅时的空态/旧歌名。
     * 线程安全（[sendFireAndForget] 内部经 [Iap2LinkChannel] 同步发送）。
     */
    fun pushNowPlaying() {
        val np = nowPlayingProvider?.invoke() ?: return
        sendNowPlaying(np, "补推")
    }

    /**
     * 应答 0x5000 订阅（含重试）。
     *
     * **为什么必须重试**：接收端（WinPlay/DiPlay 测试台）只在 bring-up 后订阅一次
     * 0x5000，之后切歌不会再订阅；而手机侧媒体信息总有空窗——重装 APK/冷启动后
     * 通知监听器要先被系统重新绑定、MediaSession 先报播放状态再补 metadata，
     * 这一小段里 [nowPlayingProvider] 返回 null。旧实现 null 就静默放弃，于是整程
     * 没有歌名（2026-10-25 实测：18:35 那轮 0x5000 到达 2 次，0x5001 一条都没发，
     * 日志里连失败行都没有——因为压根没进发送分支）。
     *
     * 现在按 [nowPlayingRetryDelaysMs] 退避重试；新订阅会作废上一轮的待重试任务
     * （[npGeneration]），避免多条线程同时推送。
     */
    private fun answerNowPlaying(
        tag: String,
        retriesLeft: Int,
    ) {
        val provider = nowPlayingProvider ?: return
        val generation = npGeneration.incrementAndGet()
        nowPlayingRetry.execute {
            var remaining = retriesLeft
            while (remaining >= 0) {
                if (!channel.isAlive || npGeneration.get() != generation) return@execute
                val np = provider()
                if (np != null) {
                    sendNowPlaying(np, tag)
                    return@execute
                }
                if (remaining == 0) {
                    log("iap2 0x5000（$tag）重试用尽仍读不到媒体信息——本轮放弃（切歌时会再补推）")
                    return@execute
                }
                val index = nowPlayingRetryDelaysMs.size - remaining
                val delay = nowPlayingRetryDelaysMs[index.coerceIn(0, nowPlayingRetryDelaysMs.size - 1)]
                log("iap2 0x5000（$tag）暂无媒体信息——${delay}ms 后重试（剩 $remaining 次）")
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    return@execute
                }
                remaining--
            }
        }
    }

    /** 真正下发一条 0x5001（参数体，帧头由 [sendFireAndForget] 补）。 */
    private fun sendNowPlaying(
        np: CarPlayNowPlaying,
        tag: String,
    ) {
        runCatching {
            sendFireAndForget(
                Iap2MessageIds.NOW_PLAYING_UPDATE,
                // 必须是纯参数体：sendFireAndForget 会自己补帧头，
                // 传 Iap2NowPlaying.encode() 会双重封包（见该函数注释）。
                Iap2NowPlaying.body(np),
            )
            log("iap2 $tag 0x5001（歌名=${np.title ?: "?"} 歌手=${np.artist ?: "?"}）")
        }.onFailure {
            log("iap2 0x5001 发送失败（$tag）- ${it.message}")
        }
    }

    /** 0x5000 应答重试退避（毫秒）：覆盖「通知监听器重绑 + metadata 补齐」的常见空窗。 */
    private val nowPlayingRetryDelaysMs = longArrayOf(1_500, 4_000, 8_000, 15_000, 25_000)

    /** 单调递增的订阅代次：新订阅作废旧的重试任务。 */
    private val npGeneration = java.util.concurrent.atomic.AtomicInteger()

    /** 0x5001 重试专用单线程（daemon）：不占用 iAP2 收包线程，会话结束自动退出进程。 */
    private val nowPlayingRetry =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "iap2-nowplaying").apply { isDaemon = true }
        }

    /**
     * 保活期间收到 0x4301：解析成端点交给主控（配对窗口内热更新，或上一轮失败后重走）。
     * 没有 0x5703 缓存时（新协议车机：BMW iD9 只有 0x4301）AP 信息直接从 0x4301 的
     * 无线组解析——这正是这类车机唯一的凭据来源，不能像旧代码那样「无 0x5703 就忽略」。
     */
    private fun handleLateStartSession(frame: Iap2Csm.Frame) {
        logStartSessionRaw(frame)
        val endpoint = parseStartSession(frame, wifi)
        if (wifi == null && endpoint.ssid.isNotEmpty()) {
            wifi =
                WifiConfiguration(
                    ssid = endpoint.ssid,
                    passphrase = endpoint.passphrase,
                    channel = endpoint.channel,
                    securityType = endpoint.securityType,
                )
            log("iap2 0x4301 自带 AP 信息：ssid=${endpoint.ssid}——已缓存，供后续 0x5703/0x4301 复用")
        }
        if (endpoint.ssid.isEmpty()) {
            log("iap2 rx=0x4301（晚到）——无线组里没解析出 SSID，本条忽略")
            return
        }
        // 凭据到手，不用再催了（催问频率与日志噪音都到此为止）。
        if (awaitingStartSession) {
            awaitingStartSession = false
            log("iap2 催问共 ${nudgeCount} 次后收到可用 0x4301——停止催问")
        }
        // 车机可能周期性重发同一份 0x4301：详情只在端点变化时打，避免刷日志。
        if (endpoint.hashCode() != lastLateStartSessionSignature) {            lastLateStartSessionSignature = endpoint.hashCode()
            log(
                "iap2 rx=0x4301（晚到，Phase 1 之后）airPlayPort=${endpoint.airPlayPort} " +
                    "device=${endpoint.deviceIdentifier} publicKey=${endpoint.publicKey?.take(16)}… " +
                    "srcvers=${endpoint.sourceVersion} ips=${endpoint.ipAddresses}" +
                    "——交给主控热更新端点/重试配对",
            )
        }
        onEndpointUpdate?.invoke(if (buickB233) endpoint.copy(isBuickB233 = true) else endpoint)
    }

    /** 0x4301 原始体只记一次：车机间端点字段差异（有无 IPv4/端口/公钥）只能靠它定谳。 */
    private fun logStartSessionRaw(frame: Iap2Csm.Frame) {
        if (startSessionRawLogged) return
        startSessionRawLogged = true
        val body = frame.body
        val shown = if (body.size <= START_SESSION_RAW_LOG_BYTES) body else body.copyOf(START_SESSION_RAW_LOG_BYTES)
        val truncated = if (body.size > shown.size) "（前 ${shown.size}/${body.size} 字节）" else ""
        log("iap2 0x4301 原始体$truncated=" + shown.joinToString("") { "%02x".format(it) })
    }

    // ---- 帧收发 ----

    /**
     * MFi 挑战应答：按「对端可接受的长度」依次尝试，每次失败（0xAA04 / 静默超时 / 断链）换下一档。
     * 基础顺序与窗口由两端实测决定（2026-10-09/10，缺一不可）：
     *  - **32B 优先**：测试台 DiPlay（Carlinkit AuthV3 EC 身份）**只接受精确 32B**，
     *    长度不对会直接断开链路（实测 70~350ms 内断链）；其应答快（~0.2s），3s 窗口足够。
     *  - **20B 次之**：真车 Audi MIB3 对 20B 秒答（catplay/extractor 真车实录同款）；
     *    对 32B 的处置两种都出现过——10-09 静默不断链、10-11 拆链。
     *  - **16B 兜底**：部分旧配件。
     *
     * 顺序**以按车机持久化的记忆为准**（[MfiLengthMemory]，基线只作冷启动与兜底）：
     * 2026-10-19 奥迪 MMI 0000 实测 20B 静默 6 秒、32B 0.64 秒应答——只靠基线
     * 「真车都 20B 优先」每次连接都白等 6 秒。记忆命中后直接把上次成功的长度排第一。
     * 全部无应答时不抛异常直接返回——我们跳过签名校验，由后续 0x5702 是否获得应答
     * 判定对端是否放行（真车实测无应答后 0xaa05 仍可推进）。
     */
    private fun runMfiChallenge(
        deadline: Long,
        prefer20First: Boolean = false,
        receiverKey: String,
        buickStrict: Boolean = false,
    ) {
        // 别克 B233 是硬要求档：只认精确 32 字节，不符立即断链，**绝不回落** 20B/16B。
        // 也不能让按车机记忆把顺序改掉——旧记忆里若存着 20B 成功，回落一次车机就把会话掐了。
        if (buickStrict) {
            runBuickMfiChallenge(deadline)
            return
        }
        val baselineLengths =
            if (prefer20First) {
                listOf(MFI_ATTEMPTS[1].first, MFI_ATTEMPTS[0].first, MFI_ATTEMPTS[2].first)
            } else {
                MFI_ATTEMPTS.map { it.first }
            }
        val windowOf = MFI_ATTEMPTS.associate { it.first to it.second }
        val memory = MfiLengthMemory.load(context, receiverKey)
        val order = MfiLengthMemory.orderAttempts(baselineLengths, memory)
        if (!memory.isEmpty) {
            log(
                "iap2 MFi 按车机记忆（$receiverKey）：${MfiLengthMemory.describe(memory)}" +
                    " → 本轮顺序 ${order.joinToString("/")}B",
            )
        }
        for ((position, size) in order.withIndex()) {
            // 已知「静默过」的档位缩短窗口：它已经失败过一次，没必要再等满。
            val baseWindow = windowOf[size] ?: MFI_ATTEMPTS.last().second
            val windowMillis =
                if (size in memory.silent && size != memory.succeeded) {
                    minOf(baseWindow, MFI_SILENT_RETRY_WINDOW_MS)
                } else {
                    baseWindow
                }
            val challenge = ByteArray(size).also(random::nextBytes)
            send(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
            log("iap2 tx=0xaa02 challenge=${size}B（第 ${position + 1} 次，窗口 ${windowMillis}ms）")
            val window = minOf(System.nanoTime() + windowMillis * 1_000_000L, deadline)
            var gotResponse = false
            var gotFailure = false
            while (true) {
                val remaining = (window - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) break
                val frame = recvFrame(remaining)
                if (frame == null) {
                    if (!channel.isAlive) {
                        // 挑战期间被对端断开 = **强证据**，走硬拒绝：即使该长度曾被记成
                        // 「成功」也要推翻（迟到应答的归属歧义，见 MfiLengthMemory.recordHardRejected）。
                        log("iap2 MFi 挑战期间链路断开（${size}B，对端不接受该长度）——记入该车机被拒档位（硬拒绝，可推翻旧的成功记忆）")
                        MfiLengthMemory.recordHardRejected(context, receiverKey, size)
                        return
                    }
                    continue
                }
                when (frame.messageId) {
                    Iap2MessageIds.MFI_RESPONSE -> {
                        log("iap2 rx=0xaa03 signature bytes=${frame.body.size}（跳过校验）")
                        gotResponse = true
                    }

                    Iap2MessageIds.MFI_AUTHENTICATION_FAILED -> {
                        log("iap2 rx=0xaa04 挑战 ${size}B 被拒")
                        gotFailure = true
                    }

                    else ->
                        log(
                            "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                                "(${Iap2MessageIds.nameOf(frame.messageId)})",
                        )
                }
                if (gotResponse || gotFailure) break
            }
            if (gotResponse) {
                MfiLengthMemory.recordSuccess(context, receiverKey, size)
                return
            }
            if (gotFailure) {
                MfiLengthMemory.recordRejected(context, receiverKey, size)
            } else {
                MfiLengthMemory.recordSilent(context, receiverKey, size)
            }
            if (position < order.size - 1) {
                log("iap2 MFi ${size}B 无应答，改用 ${order[position + 1]}B 重试")
            } else {
                log("iap2 MFi 挑战全部无应答——跳过校验直接发 0xaa05（由 0x5702 判定车机是否放行）")
            }
        }
    }

    /**
     * 别克 B233 的 MFi 挑战：**只发一次精确 32 字节**，不换档、不查记忆。
     *
     * 依据 E5 实测：20B/16B 会被车机直接断链（不是静默），而重试本身就是把会话
     * 拖死的元凶。所以这里宁可让本轮失败重连，也不做长度试探——失败原因写清楚，
     * 车主在日志里能直接看到「该车机要求精确 32 字节」。
     */
    private fun runBuickMfiChallenge(deadline: Long) {
        val challenge = ByteArray(BUICK_MFI_CHALLENGE_BYTES).also(random::nextBytes)
        send(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
        log(
            "iap2 tx=0xaa02 challenge=${BUICK_MFI_CHALLENGE_BYTES}B" +
                "（别克 B233 严格档：只认 32B，失败即断链，不回落 20B/16B）",
        )
        val window = minOf(System.nanoTime() + BUICK_MFI_WINDOW_MS * 1_000_000L, deadline)
        while (true) {
            val remaining = (window - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) break
            val frame = recvFrame(remaining) ?: continue
            when (frame.messageId) {
                Iap2MessageIds.MFI_RESPONSE -> {
                    log("iap2 rx=0xaa03 signature bytes=${frame.body.size}（跳过校验）")
                    return
                }

                Iap2MessageIds.MFI_AUTHENTICATION_FAILED -> throw IOException(
                    "别克 B233 拒绝了 ${BUICK_MFI_CHALLENGE_BYTES}B MFi 挑战（0xAA04）——" +
                        "该车机要求精确 32 字节挑战，本轮按失败断开",
                )

                else ->
                    log(
                        "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                            "(${Iap2MessageIds.nameOf(frame.messageId)})",
                    )
            }
        }
        if (!channel.isAlive) {
            throw IOException("别克 B233 在 MFi 32B 挑战期间断开了链路（长度不符即为断链信号）")
        }
        // 静默超时：与其他车机一致，不中止——我们不校验签名（0xAA05 自报），
        // 后续由 0x4301 是否到达判定该车机是否放行。
        log("iap2 MFi 32B 挑战在 ${BUICK_MFI_WINDOW_MS}ms 内无应答——继续发 0xaa05（由 0x4301 判定放行）")
    }

    private fun send(
        messageId: Int,
        vararg body: ByteArray,
    ) {
        val joined =
            when (body.size) {
                0 -> ByteArray(0)
                1 -> body[0]
                else -> body.reduce { acc, bytes -> acc + bytes }
            }
        val frame = Iap2Csm.encode(messageId, joined)
        val limit = channel.peerMaxControlPayload() ?: (frame.size - 1).coerceAtLeast(1)
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + limit, frame.size)
            if (!channel.sendControl(frame.copyOfRange(offset, end))) {
                throw IOException("iAP2 链路在发送 0x${messageId.toString(16)} 时关闭")
            }
            offset = end
        }
        log("iap2 tx=0x${messageId.toString(16).padStart(4, '0')}")
        SessionTrace.csm(SessionTrace.Dir.TX, "bt", messageId, joined)
    }

    /** [send] 的发后不管变体：链路不因对端不 ACK 此消息而重传/死亡（见 0x4E0E）。 */
    private fun sendFireAndForget(
        messageId: Int,
        body: ByteArray,
    ) {
        val frame = Iap2Csm.encode(messageId, body)
        val limit = channel.peerMaxControlPayload() ?: (frame.size - 1).coerceAtLeast(1)
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + limit, frame.size)
            if (!channel.sendControl(frame.copyOfRange(offset, end), awaitAck = false)) {
                throw IOException("iAP2 链路在发送 0x${messageId.toString(16)} 时关闭")
            }
            offset = end
        }
        log("iap2 tx=0x${messageId.toString(16).padStart(4, '0')}（发后不管）")
        SessionTrace.csm(SessionTrace.Dir.TX, "bt", messageId, body, "fire-and-forget")
    }

    private fun expect(
        messageId: Int,
        deadline: Long,
    ): Iap2Csm.Frame =
        expectOrNull(messageId, deadline)
            ?: throw IOException("等待 0x${messageId.toString(16)} 超时")

    /**
     * 同 [expect]，但超时返回 null（用于可选消息）。非预期帧进暂存区供后续消费。
     *
     * 只从 [receivedFrames] 读新帧，**不重读自己刚暂存的帧**：旧实现两者共用一个
     * 队列，非预期帧被塞回后立刻又被本函数取出，形成活锁（见 pendingFrames 注释）。
     * 目标帧若已在暂存区（早到），先从这里取，保证不漏。
     */
    /**
     * 等一条指定消息；期间到达的其它帧暂存进 [pendingFrames]。
     *
     * [pollIntervalMillis] > 0 时把等待切成等长小片，每片到点调一次 [onPoll] ——
     * 别克 B233 靠它做「750ms 没等到 0x4301 就再催一帧 0x4300」的轮询，
     * 通用线传 0（不切片），时序与改动前完全一致。
     */
    private fun expectOrNull(
        messageId: Int,
        deadlineNanos: Long,
        pollIntervalMillis: Long = 0L,
        onPoll: (() -> Unit)? = null,
    ): Iap2Csm.Frame? {
        pendingFrames.firstOrNull { it.messageId == messageId }?.let {
            pendingFrames.remove(it)
            return it
        }
        while (true) {
            val now = System.nanoTime()
            if (now >= deadlineNanos) return null
            // 切片：先等到「下一片到点」或「总超时」中更早的那个
            val sliceEnd =
                if (pollIntervalMillis > 0L) {
                    minOf(deadlineNanos, now + pollIntervalMillis * 1_000_000L)
                } else {
                    deadlineNanos
                }
            val remaining = (sliceEnd - now) / 1_000_000L
            if (remaining <= 0) {
                onPoll?.invoke()
                continue
            }
            val frame = recvNewFrame(remaining)
            if (frame == null) {
                if (!channel.isAlive) return null
                // 本片读完仍没目标帧：先让调用方补一帧，再等下一片
                if (pollIntervalMillis > 0L) onPoll?.invoke()
                continue
            }
            if (frame.messageId == messageId) return frame
            log(
                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)})，非预期，暂存待后续消费",
            )
            pendingFrames += frame
            if (pendingFrames.size > maxPendingFrames) {
                pendingFrames.removeFirst()
                if (!pendingOverflowLogged) {
                    pendingOverflowLogged = true
                    log("iap2 非预期帧暂存区超过 $maxPendingFrames 条——丢弃最旧的（车机订阅类消息过多）")
                }
            }
        }
    }

    private fun awaitOptionalHid(deadline: Long) {
        // 声明清单里没有 StartHID 就不等（DiPlay/奥迪实测均不发，旧流程每轮白等 2s）。
        if (accessoryDeclaresSend(Iap2MessageIds.START_HID) == false) {
            log("iap2 车机未声明会发 0x6800 StartHID——跳过 2s 等待")
            return
        }
        val hidWindowNanos = minOf(System.nanoTime() + HID_WAIT_NANOS, deadline)
        while (true) {
            val remaining = (hidWindowNanos - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) {
                // 措辞要看声明：车机在 0x1D01 的发送清单里**有** 0x6800 时（别克 E2LB、
                // 凯迪拉克 Cinemo 都有）它只是这一刻还没发——写成「该接收端不发 StartHID」
                // 会让人以为清单没声明，和上面那行「StartHID=会发」自相矛盾。
                log(
                    "iap2 ${HID_WAIT_NANOS / 1_000_000_000}s 内未收到 0x6800 StartHID" +
                        (if (accessoryDeclaresSend(Iap2MessageIds.START_HID) == true) {
                            "（车机声明会发，可能等 0x4300 之后才开 HID）——继续"
                        } else {
                            "（车机未声明清单，按不发处理）——继续"
                        }),
                )
                return
            }
            val frame = recvFrame(remaining)
            if (frame == null) {
                if (!channel.isAlive) return // 链路已死：交给后续 expect 报出明确错误
                continue
            }
            when (frame.messageId) {
                Iap2MessageIds.START_HID -> {
                    log("iap2 rx=0x6800 start-hid bytes=${frame.body.size}")
                    send(
                        Iap2MessageIds.DEVICE_HID_REPORT,
                        Iap2Csm.u16(0, 0),
                        Iap2Csm.param(1, byteArrayOf(0)),
                    )
                    return
                }

                else ->
                    log(
                        "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                            "(${Iap2MessageIds.nameOf(frame.messageId)})",
                    )
            }
        }
    }

    /**
     * 通用取帧（MFi 挑战 / HID 等待 / 保活泵）：优先消费暂存的非预期帧
     * （保持到达顺序：暂存 > 新解析），再读新帧。
     */
    private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        pendingFrames.firstOrNull()?.let {
            pendingFrames.removeFirst()
            return it
        }
        return recvNewFrame(timeoutMillis)
    }

    /** 只取新帧（先已解析队列、再读 socket）；绝不返回暂存区的帧。 */
    private fun recvNewFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        receivedFrames.firstOrNull()?.let {
            receivedFrames.removeFirst()
            return it
        }
        if (timeoutMillis <= 0) return null
        val bytes = channel.recvControl(timeoutMillis) ?: return null
        val frames = parser.offer(bytes)
        if (frames.isEmpty()) return null
        // 完整会话录制：原始报文（含 0x1D01 能力清单这类大包）进 session_trace.log，
        // 与 main_debug.log 的摘要行按时间戳对齐，用于事后离线分析拆链/超时。
        frames.forEach { SessionTrace.csm(SessionTrace.Dir.RX, "bt", it.messageId, it.body) }
        frames.forEachIndexed { index, frame -> if (index > 0) receivedFrames += frame }
        return frames.first()
    }

    // ---- 解析 ----

    /** 从 0x5703 解析出的热点凭据（收到后应立即绑 WiFi + 广播 _carplay-ctrl）。 */
    data class WifiConfiguration(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val securityType: Int,
        /**
         * `0x5703` param 0 的 BSSID（`aa:bb:cc:dd:ee:ff`）。null = 车机没给。
         *
         * 用途只有一处：Wi-Fi Direct 组加入时把它当 GO 的 deviceAddress 首选候选。
         * P2P GO 的 BSSID 通常就是它的 p2p 设备地址，比「扫描后按名字猜」可靠得多。
         */
        val bssid: String? = null,
    )

    private fun parseWifiConfiguration(frame: Iap2Csm.Frame): WifiConfiguration {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        return WifiConfiguration(
            ssid = params[1]?.value?.asStringOrEmpty() ?: "",
            passphrase = params[2]?.value?.asStringOrEmpty() ?: "",
            securityType =
                params[3]
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: 0,
            channel =
                params[4]
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: 0,
            // 0x5703 param 0 = BSSID。此前整条丢弃：P2P 组加入要靠它定位 GO。
            bssid = CarPlayStartSessionParams.bssid(params[0]?.value),
        )
    }

    /**
     * 解析 0x4301。AP 信息有两种来源：
     *  - 已通过 0x5703 拿到（旧协议车机）→ 直接用；
     *  - 车机只在 0x4301 的无线组里给（新协议车机，如 BMW iD9）→ 从组内解析。
     *
     * 新协议无线组与 CarPlay Addendum 的 AccessoryWiFiConfigurationInformation 同构
     * （0/1=SSID/密码、2=信道、3=IP、4=安全类型），但个别实现从 1 起编号（本项目 0x5703
     * 解析即 1/2/3/4）——两种都兼容：前两个「非 IP」的 NUL 结尾字符串依次当 SSID/密码，
     * 单字节字段按取值范围归类。原始字节每次都打日志，真车实测后可直接校正。
     */
    private fun parseStartSession(
        frame: Iap2Csm.Frame,
        wifi: WifiConfiguration?,
    ): CarPlayEndpoint {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        val wirelessGroup = params[1]?.value
        val wireless = wirelessGroup?.let { Iap2Csm.parseParams(it) }
        val addresses =
            wireless
                ?.filter { it.id == 3 }
                ?.mapNotNull { param -> param.value.asStringOrEmpty()?.takeIf { it.isNotEmpty() } }
                .orEmpty()
        log(
            "iap2 0x4301 顶层参数: " +
                params.entries.joinToString(" ") { (id, param) -> "#$id(${param.value.size}B)" },
        )
        if (wireless != null) {
            log(
                "iap2 0x4301 无线组参数: " +
                    wireless.joinToString(" ") { param ->
                        "#${param.id}=" + param.value.joinToString("") { "%02x".format(it) }
                    },
            )
        }
        val fromSession = wireless?.let { parseWirelessAp(it) }
        val ap = wifi ?: fromSession
        if (wifi == null && fromSession != null) {
            log(
                "iap2 0x4301 自带 AP：ssid=${fromSession.ssid} channel=${fromSession.channel} " +
                    "security=${fromSession.securityType} psk=${if (fromSession.passphrase.isEmpty()) "(空)" else "***"}",
            )
        }
        // param 6 / 8：连接相关但此前整体未读。
        // SDKVersion 只为把「对方是哪一代实现」写进日志；
        // mutualAuth 必须单独一行：它=1 时失败点会落在 AirPlay 认证段，
        // 而表面症状是「端点全对、就是连不上」，不点名会查很久。
        val sdkVersion = CarPlayStartSessionParams.sdkVersion(params[6]?.value)
        val mutualAuth = CarPlayStartSessionParams.mutualAuth(params[8]?.value)
        if (mutualAuth) {
            log(
                "iap2 0x4301 ★ param8 mutualAuth=1——车机要求双向认证。" +
                    "本实现只做单向（0xAA00~0xAA03 由配件证明自己），" +
                    "若随后卡在 AirPlay 认证段，先怀疑这一条而不是报文格式",
            )
        } else if (params[8] != null) {
            log("iap2 0x4301 param8 mutualAuth=0——单向认证即可")
        }
        return CarPlayEndpoint(
            ssid = ap?.ssid ?: "",
            passphrase = ap?.passphrase ?: "",
            channel = ap?.channel ?: 0,
            securityType = ap?.securityType ?: 0,
            airPlayPort = params[2]?.value?.asU32() ?: 0,
            deviceIdentifier = params[3]?.value?.asStringOrEmpty(),
            publicKey = params[4]?.value?.asStringOrEmpty(),
            sourceVersion = params[5]?.value?.asStringOrEmpty(),
            ipAddresses = addresses,
            mutualAuthRequired = mutualAuth,
            sdkVersion = sdkVersion,
        )
    }

    /**
     * 从 0x4301 的无线组里解析 AP 信息（新协议车机的唯一凭据来源）。
     *
     * 只取「不像 IP 地址」的 NUL 结尾字符串：第一个当 SSID、第二个当密码；
     * 单字节字段按取值范围归类（信道 1..196、安全类型 0..4），信道优先 id=2、
     * 其次 id=4/id=3 中不是安全类型的那个。解析不出来返回 null（调用方会报错并留日志）。
     */
    private fun parseWirelessAp(group: List<Iap2Csm.Param>): WifiConfiguration? {
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        val strings =
            group
                .sortedBy { it.id }
                .mapNotNull { param -> param.value.asStringOrEmpty() }
                .filter { it.isNotEmpty() && !ipv4.matches(it) && !it.contains(':') }
        val ssid = strings.getOrNull(0)?.takeIf { it.length <= 32 } ?: return null
        val passphrase = strings.getOrNull(1) ?: ""
        val singles = group.filter { it.value.size == 1 }.associate { it.id to (it.value[0].toInt() and 0xFF) }
        val security =
            singles[4]?.takeIf { it in 0..4 }
                ?: singles[3]?.takeIf { it in 0..4 }
                ?: 0
        val channel =
            singles[2]?.takeIf { it in 1..196 }
                ?: singles[4]?.takeIf { it in 1..196 && it != security }
                ?: singles[3]?.takeIf { it in 1..196 && it != security }
                ?: 0
        return WifiConfiguration(ssid = ssid, passphrase = passphrase, channel = channel, securityType = security)
    }

    /**
     * 从 0x1D01 的 ExternalAccessoryProtocol 组件（参数组 10/11/12）解析 EA 协议：
     * 子参数 0 = 协议号（u8）、1 = 协议名。对照 DiPlay 同款构造（u8(0,index)+string(1,name)+u8(2,0)）。
     */
    private fun parseEaProtocols(frame: Iap2Csm.Frame): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        for (groupId in intArrayOf(10, 11, 12)) {
            val value = params[groupId]?.value ?: continue
            val sub = Iap2Csm.parseParams(value).associateBy { it.id }
            val protocolId =
                sub[0]
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: continue
            val name = sub[1]?.value?.asStringOrEmpty() ?: "?"
            out += protocolId to name
            log("iap2 0x1d01 EA 组件[$groupId] protocol=$protocolId name=$name")
        }
        return out
    }

    private fun logIdentification(frame: Iap2Csm.Frame) {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        val name = params[0]?.value?.asStringOrEmpty()
        val model = params[1]?.value?.asStringOrEmpty()
        val manufacturer = params[2]?.value?.asStringOrEmpty()
        log("iap2 rx=0x1d01 车机身份 name=$name model=$model manufacturer=$manufacturer")
        // 参 6/7 = 车机声明「它会发送/它可接收」的消息 ID 清单。
        // 参 7（它可接收）直接告诉我们它是否认可 0x4300 —— 真车排查的关键证据。
        val sends = params[6]?.value?.asU16List()
        val receives = params[7]?.value?.asU16List()
        if (sends != null) {
            // 后续可选消息（0x6800/0x4301）按此清单判断是否会来，避免固定窗口空等
            accessorySendIds = sends.toSet()
            Iap2CapabilityReport.report(log, "iap2 0x1d01 ", "发送清单", sends, isReceiveList = false)
            log(
                "iap2 0x1d01 可选消息声明：StartHID(0x6800)=" +
                    (accessoryDeclaresSend(Iap2MessageIds.START_HID)?.let { if (it) "会发" else "不发" } ?: "未知") +
                    " CarPlayStartSession(0x4301)=" +
                    (accessoryDeclaresSend(Iap2MessageIds.CARPLAY_START_SESSION)?.let { if (it) "会发" else "不发" } ?: "未知"),
            )
        }
        if (receives != null) {
            // 可收清单只作**参考与日志**：它能证明「车机有」，不能证明「没有」
            // （BMW iD9 的清单里没有 0x5702 → 画像判 NEW；奥迪 3315 的清单里也没有，
            //  但旧协议完好 → 画像判 UNKNOWN，照样发 0x5702）。
            accessoryReceiveIds = receives.toSet()
            receiveListDeclared = true
            // 三层输出（原始/分类/陌生 ID）+ 导航专判：MIB3 4398（CL3B）起若车机接受
            // CarPlay 导航数据，会在这里出现 0x52xx —— 那是投仪表/HUD 的唯一入口
            Iap2CapabilityReport.report(log, "iap2 0x1d01 ", "可收清单", receives, isReceiveList = true)
            // 车机画像（信号收口点）：身份 + 双清单 → era/wifiGen 一行判定，
            // 依据随行——判错时从这里回溯是哪条信号误导（铁律：按能力不按品牌）
            val profile =
                ReceiverProfile.classify(
                    ReceiverProfile.Signals(
                        name = name,
                        model = model,
                        manufacturer = manufacturer,
                        sendIds = sends?.toSet(),
                        receiveIds = receives.toSet(),
                    ),
                )
            log(ReceiverProfile.describe(profile))
            // 画像结论是本轮 WiFi 分派的唯一依据（见 wifiGen 字段注释）
            wifiGen = profile.wifiGen
            val dispatchHint =
                if (profile.wifiGen == ReceiverProfile.WifiGen.NEW) {
                    "不发 0x5702（等 0x4301 给 AP 信息）"
                } else {
                    "发 0x5702（清单不全/未声明也发，超时降级不拆链）"
                }
            log(
                "iap2 0x1d01 协议代际：请求 WiFi 配置(0x5702)=" +
                    (
                        accessoryDeclaresReceive(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)?.let {
                            if (it) "可收" else "不收"
                        } ?: "未知"
                    ) +
                    " 回 WiFi 配置(0x5703)=" +
                    (
                        accessoryDeclaresSend(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION)?.let {
                            if (it) "会发" else "不发"
                        } ?: "未知"
                    ) +
                    " → 本轮$dispatchHint",
            )
        }
        logTransportComponents(params)
        log("iap2 0x1d01 原始体=" + frame.body.joinToString("") { "%02x".format(it) })
    }

    /**
     * 0x1D01 参数 0x11 / 0x12 / 0x18：车机自报的「传输组件」。
     *
     * 实测 Cinemo 栈一族（别克 E2LB、凯迪拉克 CT5，两组字节逐字节相同）：
     *  - 0x11 子参 0=2 → `CinemoBTComponent`（子参 3 还带一个 `4C:92` 标识）
     *  - 0x18 子参 0=3 → `CinemoWIFIComponent`
     *  - 0x12 子参 0=1 → `CinemoHIDRemote`
     *
     * 意义：它把「蓝牙 / Wi-Fi / HID 遥控」三个传输都声明出来了，说明这套车机
     * **硬件与栈层面确实具备无线 CarPlay**，不推进只是应用层没被唤醒（对照它一条
     * 主动消息都不发）。也留了个待验证问题：0x4300 内层那个 `1` 如果是传输组件号，
     * WiFi 应为 `3` —— 现在按参考实现发 1，先记下来当下一轮实验项。
     */
    private fun logTransportComponents(params: Map<Int, Iap2Csm.Param>) {
        val groups = intArrayOf(0x11, 0x12, 0x18)
        val parts = ArrayList<String>(groups.size)
        for (groupId in groups) {
            val value = params[groupId]?.value ?: continue
            val sub = Iap2Csm.parseParams(value).associateBy { it.id }
            val kindBytes = sub[0]?.value
            val kind =
                if (kindBytes == null) {
                    -1
                } else if (kindBytes.size >= 2) {
                    ((kindBytes[0].toInt() and 0xFF) shl 8) or (kindBytes[1].toInt() and 0xFF)
                } else {
                    kindBytes[0].toInt() and 0xFF
                }
            val name = sub[1]?.value?.asStringOrEmpty() ?: "?"
            parts += "${name}(id=$kind)"
        }
        if (parts.isNotEmpty()) log("iap2 0x1d01 传输组件：" + parts.joinToString(" / "))
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L
        return remaining.coerceIn(0, 60_000L)
    }

    /**
     * 定长停顿（别克 B233 的握手节奏用）。
     *
     * 中断时**不**抛出：这里的停顿只是节奏控制，不是协议要求；擅自把中断当异常抛出，
     * 会让「用户取消连接」在日志里伪装成「车机不接受某帧」。
     */
    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun u64Bytes(value: Long): ByteArray =
        ByteArray(8) { index ->
            (value ushr (8 * (7 - index))).toByte()
        }

    /** 设备 UUID：由蓝牙 MAC 确定性派生，跨会话稳定（车机按它标识设备）。 */
    private fun deviceUuid(): String =
        java.util.UUID
            .nameUUIDFromBytes(bluetoothMac.toByteArray())
            .toString()
            .uppercase()

    /**
     * NUL 结尾的 CSM 字符串。**必须去掉全部尾部 NUL，不能只去一个**：
     * Audi MMI 0000 实测把无线组里的地址字段填充到定长（IPv6 后跟 22 个 NUL、
     * IPv4 后跟 4 个），只去一个会得到 `"10.174.189.1\u0000\u0000\u0000\u0000"`。
     * 这种字符串 `contains('.')` 为真、`contains(':')` 为假，能通过所有「是不是
     * IPv4」的判断，但交给 `InetSocketAddress` 就成了
     * `Unable to resolve host "10.174.189.1"`（NUL 在日志里不可见，极难看出）——
     * 2026-10-18 实测：AirPlay 配对靠 mDNS 刷新端点侥幸成功，但视频数据端口与
     * keepAlive 仍用带 NUL 的地址，双双失败，会话 10 秒后被车机拆掉。
     */
    private fun ByteArray.asStringOrEmpty(): String? {
        if (isEmpty() || last() != 0.toByte()) return null
        var end = size
        while (end > 0 && this[end - 1] == 0.toByte()) end--
        return copyOf(end).decodeToString()
    }

    private fun ByteArray.asU32(): Int =
        if (size == 4) {
            ((this[0].toInt() and 0xFF) shl 24) or
                ((this[1].toInt() and 0xFF) shl 16) or
                ((this[2].toInt() and 0xFF) shl 8) or
                (this[3].toInt() and 0xFF)
        } else {
            0
        }

    /** 参 6/7 的消息清单：大端 u16 序列（识别帧里车机声明收/发能力用）。 */
    private fun ByteArray.asU16List(): List<Int>? {
        if (size < 2 || size % 2 != 0) return null
        return (0 until size step 2).map {
            ((this[it].toInt() and 0xFF) shl 8) or (this[it + 1].toInt() and 0xFF)
        }
    }
}
