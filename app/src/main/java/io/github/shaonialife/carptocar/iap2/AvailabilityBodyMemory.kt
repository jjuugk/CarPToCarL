package io.github.shaonialife.carptocar.iap2

import android.content.Context

/**
 * `0x4300`（CarPlay 可用性）报文体的**按车机试错记忆**。
 *
 * 为什么需要（2026-10-26 别克 E5 / E2LB 实测）：同一段字节在凯迪拉克 CADILLAC_7731 上
 * 被正常 ACK、链路继续存活；到了这台 SAIC-GM 8155（Cinemo 同栈）上，**收到 0x4300 后
 * 整个控制会话不再 ACK 任何帧**（只剩 `0xEE10` 链路心跳），23 秒后我方重传看门狗
 * 判定 `packet N not acknowledged` 拆链，于是每 ~40 秒重来一次，永远走不到 0x4301。
 *
 * 参考实现（CarWith-CarPlay，见 docs/cadillac-ct5-carplay-apk-analysis.md）为此准备了
 * 两种报文体，并且「23 款及以前」档干脆**不发** 0x4300——说明确实存在一批车机不吃
 * 某种形态。我们无法靠推理判断是哪一种，只能「一次连接试一种」自动阶梯：
 *
 *  1. [Variant.MODERN_NESTED] 新版嵌套体（transportId = 蓝牙 MAC）——现状，真车主流；
 *  2. [Variant.LEGACY_FLAT] 老版平铺体（`param0={1}` + DeviceIdentifier），原样与小写各一份；
 *  3. [Variant.NONE] 完全不发，只等车机自己发 0x4301。
 *
 * 2026-11-07 增补 [Variant.DUAL_GROUPS]：同源工程 link_plus 给新版车机发的是「两段 group」
 * （有线标识 + 无线标识），而我们只发了其中一段。通用系（Cinemo 栈）先用它，见 [GM_ORDER]。
 *
 * 判据不是「车机没发 0x4301」（这个家族从来不发），而是**它是否还回链路 ACK**：
 * 发出 0x4300 后整个等待窗口内对端零控制帧 = 该形态把这台车的会话打住了 → 记为
 * [recordSilent]，下一轮自动换下一档。换成能正常 ACK 的形态后立刻记住成功档
 * （[recordSuccess]），此后一直用它。
 *
 * 与 [MfiLengthMemory] 一样按「车机身份（name+model+manufacturer）」持久化；没有
 * Context（单测/纯 JVM）时退化为内存表。调用方只在有 Context 时才启用阶梯，
 * 因此单元测试里的协议时序与改动前完全一致。
 */
object AvailabilityBodyMemory {
    /** 0x4300 报文体的四种形态（声明顺序即尝试顺序）。 */
    enum class Variant(
        val label: String,
    ) {
        /**
         * 新版**双标识**体：`group0={0:1, 1:"00008110"+MAC+"0000"}` +
         * `group1={0:1, 1:"mac 小写"}`。
         *
         * 2026-11-07 别克 E2LB 现场对照 link_plus（同源 Buick 工程）才发现：人家给新版车机
         * 发的是**两段 group**，我们一直只发了 group1（= 人家的「旧版」分支形态）。
         * 车机把 group0 当作「这台设备是 CarPlay 设备」的有线标识，缺了它整条 iAP2
         * 会话只回链路 ACK、不发起任何消息（0x6800/0x5000/0x4c00/0x4301 一条不发）。
         */
        DUAL_GROUPS("新版双标识体（有线标识+无线标识两段）"),
        MODERN_NESTED("新版嵌套体 transportId=蓝牙MAC"),
        LEGACY_FLAT("老版平铺体 DeviceIdentifier"),
        NONE("不发 0x4300"),
    }

    /** 默认尝试顺序：新版 → 老版 → 不发。 */
    val ORDER: List<Variant> = listOf(Variant.MODERN_NESTED, Variant.LEGACY_FLAT, Variant.NONE)

    /**
     * 通用系（SAIC-GM / Cinemo 栈）的尝试顺序：**双标识体优先**，其余同默认。
     *
     * 只给这一族开：凯迪拉克 7731 与别克 E2LB 都是「收下 0x4300 却不发起 0x4301」，
     * 而缺 group0 恰恰是这两台车的共同点；奥迪/宝马/测试台（现有能通的）保持默认顺序，
     * 免得为了修一台车把已经跑通的链路改坏。
     */
    val GM_ORDER: List<Variant> =
        listOf(Variant.DUAL_GROUPS, Variant.MODERN_NESTED, Variant.LEGACY_FLAT, Variant.NONE)

    /** 全部形态（存储排序用，必须是所有档的超集）。 */
    private val ALL: List<Variant> = GM_ORDER

    private const val FILE = "availability_body_memory"
    private const val KEY_OK = "ok_"
    private const val KEY_SILENT = "silent_"

    /** 车机身份 → 存储键（与 MFi 记忆共用同一套归一化规则）。 */
    fun receiverKey(identityText: String): String = MfiLengthMemory.receiverKey(identityText)

    /**
     * 本轮该用哪一种形态。
     *
     * 规则：记住过成功档就直接用；否则取第一个没被判「静默」的档；全被判静默时
     * 停在 [Variant.NONE]（不再打扰车机，只等它自己开口）——**除非车机自己声明
     * 能收 0x4300**（[acceptsAvailability]），那种情况下静默记忆不再阻断发送。
     *
     * @param preferDual 通用系（Cinemo 栈）优先试 [Variant.DUAL_GROUPS]——见 [GM_ORDER]
     * @param acceptsAvailability 车机在 0x1D01 的「可收清单」里是否声明了 0x4300。
     *   声明了却因历史静默记忆而一言不发，等于把**唯一的热点交换入口**关掉：
     *   2026-11-10 BUICK_3138（Cinemo）就是这样卡死的——三种形态各被判过一次静默，
     *   此后每轮都不发 0x4300，车机也就永远不发 0x4301，链路每 40 秒被车机关一次。
     *   而同一份日志里「不发 0x4300」那一轮同样零业务帧，说明「零业务帧」是这台车的
     *   常态、根本不能证明报文体有错。所以：它说能收，就照发（回到首选形态）。
     */
    fun nextVariant(
        context: Context?,
        identityText: String,
        preferDual: Boolean = false,
        acceptsAvailability: Boolean = false,
    ): Variant {
        val ok = readOk(context, identityText)
        // 成功档优先，含 NONE——它意味着「不发 0x4300 也拿到了 0x4301」，是有实证的结论。
        if (ok != null) return ok
        val silent = readSilent(context, identityText)
        val order = if (preferDual) GM_ORDER else ORDER
        // 注意：NONE 也在 order 末尾，必须显式排除——否则「全静默」时 firstOrNull 会
        // 直接返回 NONE，下面那条 acceptsAvailability 的兜底永远走不到。
        val candidate = order.firstOrNull { it != Variant.NONE && it.name !in silent }
        return candidate ?: if (acceptsAvailability) order.first() else Variant.NONE
    }

    /** 这台车机是否被判过「某个形态静默」（日志用）。 */
    fun hasSilent(
        context: Context?,
        identityText: String,
    ): Boolean = readSilent(context, identityText).isNotEmpty()

    /** 已经试过哪些档（日志用）。 */
    fun silentDescription(
        context: Context?,
        identityText: String,
    ): String {
        val silent = readSilent(context, identityText)
        if (silent.isEmpty()) return "无"
        return ALL.filter { it.name in silent }.joinToString("/") { it.name }
    }

    /**
     * 记录「该形态发出去后对端零反馈」。
     *
     * @param preferDual 与 [nextVariant] 保持一致，否则日志里「下一轮改用 X」会与实际不符
     * @param acceptsAvailability 同上——声明可收的车机不会退到 [Variant.NONE]，
     *   日志必须照实说「下一轮仍是 X」，否则排查时会以为阶梯卡住了
     * @return 下一轮将改用的形态（用于日志：让用户/排查直接看到阶梯走到哪了）
     */
    fun recordSilent(
        context: Context?,
        identityText: String,
        variant: Variant,
        preferDual: Boolean = false,
        acceptsAvailability: Boolean = false,
    ): Variant {
        if (variant == Variant.NONE) return Variant.NONE
        val key = receiverKey(identityText)
        val silent = readSilent(context, identityText) + variant.name
        store(context, key, readOk(context, identityText), silent)
        return nextVariant(context, identityText, preferDual, acceptsAvailability)
    }

    /** 记录「该形态车机正常应答（拿到了 0x4301 / 继续推进）」——成功即定档，清掉旧结论。 */
    fun recordSuccess(
        context: Context?,
        identityText: String,
        variant: Variant,
    ) {
        val key = receiverKey(identityText)
        store(context, key, variant, emptySet())
    }

    // ---- 内部 ----

    /** 无 Context 时的进程内记忆：key → (成功档, 静默档)。 */
    private val memory = HashMap<String, Pair<String?, Set<String>>>()

    private fun readOk(
        context: Context?,
        identityText: String,
    ): Variant? {
        val key = receiverKey(identityText)
        val raw =
            if (context == null) {
                synchronized(memory) { memory[key]?.first }
            } else {
                prefs(context).getString(KEY_OK + key, null)
            }
        return ALL.firstOrNull { it.name == raw }
    }

    private fun readSilent(
        context: Context?,
        identityText: String,
    ): Set<String> {
        val key = receiverKey(identityText)
        val raw =
            if (context == null) {
                synchronized(memory) { memory[key]?.second }.orEmpty()
            } else {
                prefs(context)
                    .getString(KEY_SILENT + key, null)
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    .orEmpty()
            }
        return raw
    }

    private fun store(
        context: Context?,
        key: String,
        ok: Variant?,
        silent: Set<String>,
    ) {
        val ordered = ALL.filter { it.name in silent }.map { it.name }
        if (context == null) {
            synchronized(memory) { memory[key] = ok?.name to ordered.toSet() }
            return
        }
        prefs(context)
            .edit()
            .apply {
                if (ok != null) putString(KEY_OK + key, ok.name) else remove(KEY_OK + key)
                if (ordered.isEmpty()) remove(KEY_SILENT + key) else putString(KEY_SILENT + key, ordered.joinToString(","))
            }.apply()
    }

    private fun prefs(context: Context): android.content.SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
