package io.github.shaonialife.carptocar.input

/**
 * HID 描述符 → 「这台设备是什么」的一行画像（**只服务于日志与结论，不参与按键派发**）。
 *
 * 为什么需要它：`/info` 的 `hidDevices` 是「方向盘媒体键能不能收到」的唯一依据——
 * 车机只在自己声明过的 HID 设备上发 `hidSendReport`（或 iAP2 `0x6800`/`0x6802`）。
 * 2026-11-16 宝马 MGU-81 那份日志里两个设备分别是「触摸」与「iDrive 旋钮/面板键」，
 * 78 字节的描述符里**一个媒体键 Usage 都没有**，也就是说「按方向盘切歌没反应」在协议层
 * 就没有承载通路；可当时日志只打了描述符十六进制和 `[B@xxx` 对象引用，要靠人肉翻字节
 * 才能得出这个结论。这里把它压成一行：
 *
 * ```
 * AIRPLAY: hidDevices[1] uuid=0 name=HardKeyInputDescriptor → 旋钮/面板键（…，含 Wheel）
 * AIRPLAY: ⚠ HID 无媒体键设备 —— 方向盘媒体键只能靠 iAP2 0x6800/0x6802
 * ```
 *
 * 判据一律取自描述符本身（页 + Usage + Input 的 Array/Variable 位），不按车机型号或
 * uuid 写死——同一个 `HardKeyInputDescriptor` 在宝马 G38 上只是旋钮，在别的车上可能是键盘。
 *
 * 扫描器刻意做成通用的「全局项 / 局部项 / 主项」状态机，而不是复用
 * [ConsumerKeys.parseDescriptor]：那边只认 Consumer 页的第一个 Application 集合
 * （报告语义要用），这边要的是**整份描述符里出现过哪些页、哪些非恒定 Usage**。
 */
object HidDescriptorSummary {
    private const val PAGE_GENERIC_DESKTOP = 0x01
    private const val PAGE_TELEPHONY = 0x0B
    private const val PAGE_CONSUMER = 0x0C
    private const val PAGE_DIGITIZER = 0x0D

    /** Generic Desktop 页的 Wheel（iDrive 旋钮 / 滚轮的转动轴）。 */
    private const val USAGE_WHEEL = 0x38

    /** Consumer 页的 AC Back：宝马 iDrive 控制器上的「返回」键。 */
    private const val USAGE_AC_BACK = 0x0224

    enum class Kind {
        /** Consumer 页上有能映射成 Android 媒体键的 Usage（0xB0/0xB5/0xB6/0xCF…）。 */
        MEDIA_KEYS,

        /** Digitizer 页：触摸屏。 */
        TOUCH,

        /** Generic Desktop 页带 Wheel：iDrive 旋钮 / 滚轮 / 面板控制器。 */
        KNOB,

        /** Telephony 页：车机面板上的接听/挂断键。 */
        TELEPHONY,

        /** 以上都不是（或描述符解析不出可用项）。 */
        NONE,
    }

    /**
     * 一台 HID 设备的画像。
     *
     * @param mediaUsages Consumer 页上**媒体键**的 Usage（顺序同描述符）
     * @param consumerUsages Consumer 页上全部非恒定 Usage（含返回键这类非媒体键）
     * @param bitmap true = 报告是位图（`81 02`，每个 usage 占 1 bit）；false = 数组下标（`81 00`）
     * @param pages 描述符里出现过数据项的页（诊断用）
     */
    class Verdict(
        val kind: Kind,
        val mediaUsages: List<Int>,
        val consumerUsages: List<Int>,
        val bitmap: Boolean,
        val pages: List<Int>,
    ) {
        val hasMediaKeys: Boolean get() = kind == Kind.MEDIA_KEYS

        /** 一行结论（接在 `hidDevices[i] uuid=… name=… → ` 之后）。 */
        fun text(): String =
            when (kind) {
                Kind.MEDIA_KEYS ->
                    "媒体键 " +
                        mediaUsages.joinToString(" / ") {
                            "${usageHex(it)} ${ConsumerKeys.nameOf(it)}"
                        } +
                        if (bitmap) "（位图）" else "（数组下标）"
                Kind.TOUCH -> "触摸（Digitizer 页 0x0D）"
                Kind.KNOB ->
                    "旋钮/面板键（Generic Desktop 页 0x01，含 Wheel）" +
                        if (consumerUsages.isEmpty()) {
                            ""
                        } else {
                            "；Consumer 页只有 " +
                                consumerUsages.joinToString(" ") { usageHex(it) } +
                                (if (consumerUsages.contains(USAGE_AC_BACK)) "（返回键，不是媒体键）" else "（不是媒体键）")
                        }
                Kind.TELEPHONY -> "电话键（Telephony 页 0x0B）"
                Kind.NONE ->
                    "无法识别" +
                        if (pages.isEmpty()) "（描述符里没有数据项）" else "（页 ${pageText(pages)}）"
            }
    }

    /**
     * 解析描述符 → 画像。
     *
     * 支持 HID 短条目编码：bSize 0 → 0 字节、1 → 1、2 → 2、3 → 4；全局项里的
     * Usage Page 决定后续 Usage 归属哪一「页」。Usage Minimum/Maximum 的区间展开
     * 不实现——车机描述符里没有这种写法，真出现时只会让画像偏保守（判成「无法识别」）。
     */
    fun describe(descriptor: ByteArray): Verdict {
        val pageUsages = LinkedHashMap<Int, MutableList<Int>>()
        var page = 0
        var usages = ArrayList<Int>()
        var bitmap = false
        var sawArray = false
        var index = 0

        while (index < descriptor.size) {
            val prefix = descriptor[index].toInt() and 0xFF
            val dataBytes =
                when (prefix and 0x03) {
                    1 -> 1
                    2 -> 2
                    3 -> 4
                    else -> 0
                }
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            if (index + 1 + dataBytes > descriptor.size) break
            var value = 0
            for (offset in 0 until dataBytes) {
                value = value or ((descriptor[index + 1 + offset].toInt() and 0xFF) shl (8 * offset))
            }
            index += 1 + dataBytes

            when {
                // 全局项 Usage Page：之后的局部 Usage 都归属这一页
                type == 1 && tag == 0x0 -> page = value
                // 局部项 Usage：攒到下一个主项（Input/Collection）为止
                type == 2 && tag == 0x0 -> usages += value
                // 局部项 Usage Min/Max、Delimiter 等：不影响画像
                type == 2 -> Unit
                // 主项 Collection：不改页（子集合沿用外层页）
                type == 0 && tag == 0xA -> usages = ArrayList()
                // 主项 EndCollection：清掉未消费的局部项
                type == 0 && tag == 0xC -> usages = ArrayList()
                // 主项 Input：bit0=Constant（补齐位，不算用途）、bit1=Variable（位图）
                type == 0 && tag == 0x8 -> {
                    val constant = (value and 0x01) != 0
                    val variable = (value and 0x02) != 0
                    if (!constant) {
                        if (variable) bitmap = true else sawArray = true
                        val known = pageUsages.getOrPut(page) { ArrayList() }
                        usages.forEach { usage ->
                            if (usage != 0 && usage !in known) known += usage
                        }
                    }
                    usages = ArrayList()
                }
                else -> Unit
            }
        }

        val consumer = pageUsages[PAGE_CONSUMER].orEmpty()
        val media = consumer.filter { ConsumerKeys.keyCodeFor(it) != 0 }
        val kind =
            when {
                media.isNotEmpty() -> Kind.MEDIA_KEYS
                pageUsages.containsKey(PAGE_DIGITIZER) -> Kind.TOUCH
                sawWheel(pageUsages) -> Kind.KNOB
                pageUsages.containsKey(PAGE_TELEPHONY) -> Kind.TELEPHONY
                else -> Kind.NONE
            }
        return Verdict(
            kind = kind,
            mediaUsages = media,
            consumerUsages = consumer,
            // 两种语义同时出现时按「位图」讲：媒体键描述符通常是纯位图，混写只出现在
            // 带指针轴的面板控制器上，而那类设备的按键本来就不走媒体键通路。
            bitmap = bitmap && !sawArray,
            pages = pageUsages.keys.toList(),
        )
    }

    /** 一批描述符里有没有能承载媒体键的设备（没有就说明方向盘切歌不可能收到）。 */
    fun hasMediaKeyDevice(verdicts: Collection<Verdict>): Boolean = verdicts.any { it.hasMediaKeys }

    private fun sawWheel(pageUsages: Map<Int, List<Int>>): Boolean =
        pageUsages[PAGE_GENERIC_DESKTOP]?.contains(USAGE_WHEEL) == true

    private fun pageText(pages: List<Int>): String = pages.joinToString(" ") { "0x%02x".format(it) }

    /** Usage 十进制可读化：≤0xFF 两位、其余四位（Consumer 页的 0x0224 之类）。 */
    private fun usageHex(usage: Int): String =
        if (usage > 0xFF) "0x%04x".format(usage) else "0x%02x".format(usage)
}
