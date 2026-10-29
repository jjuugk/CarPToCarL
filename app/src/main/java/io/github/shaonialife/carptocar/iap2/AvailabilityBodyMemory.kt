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
    /** 0x4300 报文体的三种形态（声明顺序即尝试顺序）。 */
    enum class Variant(
        val label: String,
    ) {
        MODERN_NESTED("新版嵌套体 transportId=蓝牙MAC"),
        LEGACY_FLAT("老版平铺体 DeviceIdentifier"),
        NONE("不发 0x4300"),
    }

    /** 尝试顺序：新版 → 老版 → 不发。 */
    val ORDER: List<Variant> = listOf(Variant.MODERN_NESTED, Variant.LEGACY_FLAT, Variant.NONE)

    private const val FILE = "availability_body_memory"
    private const val KEY_OK = "ok_"
    private const val KEY_SILENT = "silent_"

    /** 车机身份 → 存储键（与 MFi 记忆共用同一套归一化规则）。 */
    fun receiverKey(identityText: String): String = MfiLengthMemory.receiverKey(identityText)

    /**
     * 本轮该用哪一种形态。
     *
     * 规则：记住过成功档就直接用；否则取第一个没被判「静默」的档；全被判静默时
     * 停在 [Variant.NONE]（不再打扰车机，只等它自己开口）。
     */
    fun nextVariant(
        context: Context?,
        identityText: String,
    ): Variant {
        val ok = readOk(context, identityText)
        if (ok != null) return ok
        val silent = readSilent(context, identityText)
        return ORDER.firstOrNull { it.name !in silent } ?: Variant.NONE
    }

    /** 已经试过哪些档（日志用）。 */
    fun silentDescription(
        context: Context?,
        identityText: String,
    ): String {
        val silent = readSilent(context, identityText)
        if (silent.isEmpty()) return "无"
        return ORDER.filter { it.name in silent }.joinToString("/") { it.name }
    }

    /**
     * 记录「该形态发出去后对端零反馈」。
     *
     * @return 下一轮将改用的形态（用于日志：让用户/排查直接看到阶梯走到哪了）
     */
    fun recordSilent(
        context: Context?,
        identityText: String,
        variant: Variant,
    ): Variant {
        if (variant == Variant.NONE) return Variant.NONE
        val key = receiverKey(identityText)
        val silent = readSilent(context, identityText) + variant.name
        // 定过档的形态**又**静默了：旧定档必须作废。
        // 否则 nextVariant 优先返回成功档，阶梯永远停在第一档（2026-10-29 别克 E2LB 实测：
        // 日志同时打印「形态=新版嵌套体」与「历史静默档=新版嵌套体」——该车早前成功过一次被
        // recordSuccess 定档，此后每轮静默都换不动档，「老版平铺体」一次都没被试过）。
        val ok = readOk(context, identityText)?.takeIf { it != variant }
        store(context, key, ok, silent)
        return nextVariant(context, identityText)
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
        return ORDER.firstOrNull { it.name == raw }
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
        val ordered = ORDER.filter { it.name in silent }.map { it.name }
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
