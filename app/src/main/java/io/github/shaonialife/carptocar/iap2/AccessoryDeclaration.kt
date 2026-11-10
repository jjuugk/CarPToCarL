package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.protocol.ProtocolAudit

/**
 * 车机在 `0x1D01` 里的**声明快照**：会发什么、可收什么、支持哪些组件。
 *
 * 为什么是跨链路的进程内单例：`0x1D01` 可能走蓝牙链路（[Iap2SenderSession]），
 * 也可能走 WiFi 隧道（VirtualIap2Link），而「0xAA05 之后那套 MIB3 突发」由隧道侧
 * 发出、判据却来自蓝牙侧那帧——宝马实测蓝牙清单比隧道布防早 5.5 秒到。
 * 两条链路把声明汇到同一处，才能用同一把尺子判断越权。
 *
 * 两条不可动摇的纪律：
 *
 * 1. **清单只能证明「有」，不能证明「没有」**（上游 cardeck 0.1.28 奥迪 3315 定谳：
 *    可收清单只有 5 项、不含 0x5702，但完整支持 0x5702→0x5703；0x4E0D/0x4E0B/
 *    0x4E0E 不在清单里也逐条 ACK）。所以绝大多数消息越权时**只记录、照发**。
 * 2. **但有两条是例外**：`0xA100`（AUX 状态）与 `0xFFF0`（GPRMC 状态）来自奥迪
 *    MIB3 的 Frida 抓包，只有 MIB3 系在可收清单里声明它们。宝马 NBT Evo / iD6
 *    与通用系 Cinemo 都没声明，实车后果是「突发发出后 42~116ms 事件通道 EOF、
 *    AirPlay 服务重启（`pi` 变化）」/「0.95s Connection reset」。这两条按声明硬裁，
 *    **只裁这两条**——不关整条隧道：宝马会主动连发 SYN marker 等我们的会话表，
 *    关掉隧道换来的是一直黑屏（见 docs/bmw-id6-reboot-20261107.md）。
 *
 * 3. **新协议车机（[ReceiverProfile.WifiGen.NEW]）另有「严格模式」**：可收清单之外
 *    一律不发（[setStrictDeclaredOnly]）。GM/Cinemo 一族（别克 E2LB / BUICK_3138、
 *    凯迪拉克 CT5）都是这个形态：清单 9 项，我们把 0x4E0D/0x4E0E 发过去之后，
 *    车机 ACK 完最后一条就只剩 `ff550200ee10` 心跳，再没有任何业务帧。放宽的代价
 *    只是在「从未连通过」的车上多试一种假设，而老协议车机（奥迪 / 清单未到）**行为
 *    逐字节不变**，仍按上面第 1 条「只记录、照发」。
 *
 * 协议骨架（识别三连 + MFi + 0x4300）**永不按声明裁**：没有任何一台车机把
 * 0x1D02/0xAA00/0xAA02/0xAA05 列进可收清单（BUICK_3138 的 9 项就没有），
 * 裁掉等于「不连接」——那不是实验，是自杀。见 [PROTOCOL_SKELETON]。
 *
 * 清单没到（纯 WiFi 场景）时 [declaresReceive] 返回 null，全部按未知放行，
 * 不会把「没收到识别帧」误判成「车机声明不收」。
 *
 * 解析范围按需求**不含参数 10（EA 协议组）**：EA 车机控制模块已在
 * `d318dd1` 移除，不解析、不恢复、不据此开 EA 会话。
 */
object AccessoryDeclaration {
    /** 0xA100：AUX 状态通知（奥迪 MIB3 Frida 常量，非通用）。 */
    const val MIB3_AUX_STATUS = 0xA100

    /** 0xFFF0：GPRMCDataStatusValuesNotification（同上）。 */
    const val MIB3_GPRMC_STATUS = 0xFFF0

    /** 0x1D01 参数号（Apple `IdentificationInformation` 规格档案）。 */
    const val PARAM_CURRENT_LANGUAGE = 12
    const val PARAM_SUPPORTED_LANGUAGES = 13
    const val PARAM_WIRELESS_CARPLAY_COMPONENT = 24
    const val PARAM_NAVIGATION_DISPLAY_COMPONENT = 30

    /** 唯一允许按清单硬裁的两条；往这里加号之前先拿真机重启证据。 */
    private val RESTRICTED_TO_DECLARED = setOf(MIB3_AUX_STATUS, MIB3_GPRMC_STATUS)

    /**
     * **协议骨架**：无论车机清单怎么写都必须发的号。
     *
     * 判据是「真车清单的形态」而不是「消息重不重要」：BUICK_3138 / 凯迪拉克 CT5 的
     * 9 项可收清单里**没有** 0x1D02、0xAA00、0xAA02、0xAA05，奥迪 7 项里也没有——
     * 这类识别/MFi 帧属于 iAP2 会话建立的下限，车机从不把它们当「可选能力」声明。
     * 裁掉它们的后果是「永远连不上」，任何实验都拿不到信息。
     *
     * 0x4300 同在骨架内，理由与 cardeck 0.1.28 记录一致：它是驱动 0x4301 的请求本身，
     * 裁掉等于放弃本轮连接（且它出现在两代车机的清单里，本来也不会被裁）。
     */
    val PROTOCOL_SKELETON: Set<Int> =
        setOf(
            Iap2MessageIds.START_IDENTIFICATION, // 0x1D00
            Iap2MessageIds.IDENTIFICATION_INFORMATION, // 0x1D01（应答，正常不会主动发）
            Iap2MessageIds.IDENTIFICATION_ACCEPTED, // 0x1D02
            Iap2MessageIds.MFI_REQUEST_CERTIFICATE, // 0xAA00
            Iap2MessageIds.MFI_REQUEST_CHALLENGE, // 0xAA02
            Iap2MessageIds.MFI_AUTHENTICATION_FAILED, // 0xAA04
            Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED, // 0xAA05
            Iap2MessageIds.CARPLAY_AVAILABILITY, // 0x4300
        )

    /** 导航引导段：可收清单出现这些号 = 车机接受 CarPlay 结构化导航数据。 */
    private val ROUTE_GUIDANCE_RANGE = 0x5200..0x52FF

    /**
     * 组件声明。参数 24 / 30 是「组」，可重复出现；这里只记存在性与组内名字，
     * 不解析子参数语义（无真机样本，不猜）。
     */
    data class Components(
        val currentLanguage: String? = null,
        val supportedLanguages: List<String> = emptyList(),
        val wirelessCarPlayDeclared: Boolean = false,
        val wirelessCarPlayNames: List<String> = emptyList(),
        val navigationDisplayDeclared: Boolean = false,
        val navigationDisplayNames: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = currentLanguage == null && supportedLanguages.isEmpty() &&
                !wirelessCarPlayDeclared && !navigationDisplayDeclared
    }

    data class Snapshot(
        val sendIds: Set<Int> = emptySet(),
        val receiveIds: Set<Int> = emptySet(),
        val declared: Boolean = false,
        val components: Components = Components(),
        val source: String = "-",
    )

    private val lock = Any()

    @Volatile
    private var snapshot = Snapshot()

    /** 每条「链路 + 消息号」本轮只留一条越权日志（催问会反复触发）。 */
    private val logged = HashSet<String>()

    /**
     * 本轮是否按可收清单**硬裁**（新协议车机专用）。
     *
     * 由 [Iap2SenderSession] 在 `0x1D01` 算出 [ReceiverProfile.wifiGen] 后设置一次，
     * 跨链路生效（隧道侧的越权发送也要受同一把尺子）。老协议 / 清单未到 = false。
     */
    @Volatile
    private var strictDeclaredOnly = false

    /** 新协议车机：清单之外一律不发（[PROTOCOL_SKELETON] 除外）。 */
    fun setStrictDeclaredOnly(enabled: Boolean) {
        strictDeclaredOnly = enabled
    }

    val isStrictDeclaredOnly: Boolean
        get() = strictDeclaredOnly

    /** 新一轮「启动连接」时清空——别拿上一台车的声明判断这一台。 */
    fun reset() {
        synchronized(lock) {
            snapshot = Snapshot()
            logged.clear()
            strictDeclaredOnly = false
        }
    }

    /**
     * 汇入一帧 `0x1D01`。两条链路可能前后都到：清单取并集（稀疏清单是常态，
     * 并集偏保守 = 更倾向放行），组件声明以「有新信息的一方」为准。
     */
    fun onIdentification(
        sendIds: Set<Int>?,
        receiveIds: Set<Int>?,
        components: Components,
        source: String,
    ) {
        synchronized(lock) {
            val prev = snapshot
            snapshot =
                prev.copy(
                    sendIds = if (sendIds.isNullOrEmpty()) prev.sendIds else prev.sendIds + sendIds,
                    receiveIds = if (receiveIds.isNullOrEmpty()) prev.receiveIds else prev.receiveIds + receiveIds,
                    declared = prev.declared || receiveIds != null,
                    components = if (components.isEmpty) prev.components else components,
                    source = source,
                )
        }
    }

    /** 车机是否声明可收；null = 本轮还没拿到清单。 */
    fun declaresReceive(messageId: Int): Boolean? {
        val snap = snapshot
        return if (!snap.declared) null else messageId in snap.receiveIds
    }

    /** 车机是否声明会发；null = 本轮还没拿到清单。 */
    fun declaresSend(messageId: Int): Boolean? {
        val snap = snapshot
        return if (snap.sendIds.isEmpty()) null else messageId in snap.sendIds
    }

    /** 车机是否接受 CarPlay 导航引导（0x52xx）；null = 清单未到。 */
    fun acceptsRouteGuidance(): Boolean? {
        val snap = snapshot
        if (!snap.declared) return null
        return snap.receiveIds.any { it in ROUTE_GUIDANCE_RANGE }
    }

    fun components(): Components = snapshot.components

    fun snapshot(): Snapshot = snapshot

    /** 排障用：一行说明本轮判据。 */
    fun describe(): String {
        val snap = snapshot
        val mode =
            if (strictDeclaredOnly) {
                "严格模式（清单之外不发，骨架除外）"
            } else {
                "宽松模式（越权只记录，A100/FFF0 例外）"
            }
        if (!snap.declared) return "车机声明：清单未到（全部按未知放行）· $mode · 来源=${snap.source}"
        return "车机声明：可收 ${snap.receiveIds.size} 项 / 会发 ${snap.sendIds.size} 项" +
            " · 0xA100=${0xA100 in snap.receiveIds} 0xFFF0=${0xFFF0 in snap.receiveIds}" +
            " · 导航引导=${acceptsRouteGuidance() == true} · $mode · 来源=${snap.source}"
    }

    /**
     * 从 `0x1D01` 报文体里解析组件声明。
     *
     * 参数 12/13 是 NUL 结尾字符串（13 可重复）；24/30 是组，组内子参 1 通常是
     * 组件名。`Iap2Csm.parseParams` 已按大端 u16 长度切好组，取存在性即可。
     * **不读参数 10**：EA 协议组按需求排除。
     */
    fun parseComponents(body: ByteArray): Components {
        val params = runCatching { Iap2Csm.parseParams(body) }.getOrDefault(emptyList())
        fun text(bytes: ByteArray): String = bytes.decodeToString().trimEnd('\u0000').trim()
        fun groupNames(id: Int): List<String> =
            params
                .filter { it.id == id }
                .mapNotNull { group ->
                    runCatching { Iap2Csm.parseParams(group.value) }.getOrDefault(emptyList())
                        .firstOrNull { it.id == 1 }
                        ?.value
                        ?.let(::text)
                        ?.takeIf { it.isNotEmpty() }
                }
        val wireless = params.filter { it.id == PARAM_WIRELESS_CARPLAY_COMPONENT }
        val navigation = params.filter { it.id == PARAM_NAVIGATION_DISPLAY_COMPONENT }
        return Components(
            currentLanguage =
                params.firstOrNull { it.id == PARAM_CURRENT_LANGUAGE }
                    ?.value
                    ?.let(::text)
                    ?.takeIf { it.isNotEmpty() },
            supportedLanguages =
                params.filter { it.id == PARAM_SUPPORTED_LANGUAGES }.map { text(it.value) }
                    .filter { it.isNotEmpty() },
            wirelessCarPlayDeclared = wireless.isNotEmpty(),
            wirelessCarPlayNames = groupNames(PARAM_WIRELESS_CARPLAY_COMPONENT),
            navigationDisplayDeclared = navigation.isNotEmpty(),
            navigationDisplayNames = groupNames(PARAM_NAVIGATION_DISPLAY_COMPONENT),
        )
    }

    /**
     * 车机声明的语言 → iAP2 `0x4E0A` 要发的两字母码。
     *
     * 规格只认 ISO 639-1（`zh`/`en`/`de`），车机常在参 12 里给区域串 `zh-CN`、
     * `en_US`——取语言段并小写。声明缺失或非法时返回调用方的既有兜底，
     * **不改已跑通车型的默认行为**。
     */
    fun deviceLanguage(fallback: String): String {
        val raw = snapshot.components.currentLanguage ?: return fallback
        return normalizeIso639(raw) ?: fallback
    }

    fun normalizeIso639(raw: String): String? {
        val code = raw.trim().substringBefore('-').substringBefore('_').lowercase()
        return code.takeIf { it.length == 2 && it.all { ch -> ch in 'a'..'z' } }
    }

    /**
     * 只查不发：与 [noteSend] 同一把尺子，但不写审计、不去重。
     *
     * 用途仅限「日志文案要说实话」——催问会说「重发 0x4e0e + 0x4300」，
     * 而严格模式下 0x4e0e 可能根本不会发出去，日志必须能对上真实帧序列。
     */
    fun wouldSend(messageId: Int): Boolean {
        val snap = snapshot
        if (!snap.declared || messageId in snap.receiveIds) return true
        if (messageId in PROTOCOL_SKELETON) return true
        return !(strictDeclaredOnly || messageId in RESTRICTED_TO_DECLARED)
    }

    /**
     * 所有 CSM 发送出口的统一守卫：越权取证 + 按车机声明裁剪。
     *
     * 三档裁定（闸门顺序即优先级）：
     *  1. 清单未到 / 车机声明可收 → 放行；
     *  2. [PROTOCOL_SKELETON] 里的协议骨架 → 放行（车机从不声明它们）；
     *  3. 车机没声明可收：
     *     - **严格模式（新协议车机）** → 裁掉，本条不进 backlog；
     *     - 否则只有 [RESTRICTED_TO_DECLARED] 两条（0xA100/0xFFF0）裁掉，其余照发。
     *
     * @param channel `"bt"` / `"tunnel"`，只进日志与去重键
     * @return true = 允许发送；false = 本条已按车机声明裁掉
     */
    fun noteSend(
        messageId: Int,
        channel: String,
        log: (String) -> Unit,
    ): Boolean {
        val snap = snapshot
        if (!snap.declared || messageId in snap.receiveIds) return true
        if (messageId in PROTOCOL_SKELETON) {
            // 骨架帧不裁，但也不静默：清单里真的没有它时留一条（每轮一条），
            // 便于回答「这台车到底把哪些号当可选能力声明」。
            val key = "skeleton|$channel|$messageId"
            if (synchronized(lock) { logged.add(key) }) {
                log(
                    "iap2 车机可收清单未声明 0x${messageId.toString(16).padStart(4, '0')} " +
                        "(${Iap2MessageIds.nameOf(messageId)}) $channel —— " +
                        "属协议骨架，不裁（识别/MFi/0x4300 是会话下限，车机从不把它们列入清单）",
                )
            }
            return true
        }
        val restricted = messageId in RESTRICTED_TO_DECLARED
        val strictCut = strictDeclaredOnly
        val key = "$channel|$messageId"
        val first = synchronized(lock) { logged.add(key) }
        if (first) {
            val hex = "0x${messageId.toString(16).padStart(4, '0')}"
            val action =
                when {
                    strictCut -> "新协议严格模式：按可收清单裁掉，本条不发"
                    restricted -> "按真机重启证据裁掉（0xA100/0xFFF0 仅 MIB3 系声明）"
                    else -> "仍照常发送（清单只能证明「有」不能证明「没有」）"
                }
            log(
                "iap2 车机可收清单未声明 $hex (${Iap2MessageIds.nameOf(messageId)}) " +
                    "$channel reason=accessory-did-not-advertise —— $action",
            )
            // 只有「照常发送」才是越权，才该进审计。被裁掉的那一帧根本没上线——
            // 2026-11-10 BUICK_3138 日志里就出现了「裁掉 0x4e0e」紧跟一条
            // 「发了车机未声明可收的消息 —— 0x4e0e（严格模式裁剪）」的自相矛盾记录，
            // 审计因此不可信（看起来像我们发了越权帧）。
            if (!strictCut && !restricted) {
                ProtocolAudit.record(
                    ProtocolAudit.Code.UNSOLICITED_MESSAGE,
                    detail = "$hex ${Iap2MessageIds.nameOf(messageId)}（$channel，照常发送）",
                    evidence = "车机可收清单(${snap.receiveIds.size} 项)未包含该号；来源=${snap.source}",
                )
            }
        }
        if (strictCut || restricted) return false
        return true
    }
}
