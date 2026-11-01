package io.github.shaonialife.carptocar.airplay

/**
 * 车机显示屏参数（像素宽×高 + 身份/能力），唯一来源是接收端 AirPlay `/info` 的 `displays[]`。
 *
 * 为什么单独抽成纯函数：这段解析原本直接写在 [AirPlayClient] 里，只认 `as? Long`
 * 一种类型、只认 `widthPixels/heightPixels` 两个键。车机侧写法并不统一（有的把
 * 分辨率写进 2 字节整数、有的写成 Double、个别固件用 `width/height`），任何一处
 * 对不上就会**静默**退到手机分辨率兜底——画面比例当场就错，日志里却看不出来。
 *
 * ## 多屏（大连屏 / 仪表）
 *
 * `/info` 里 `displays` 可能是多块屏。旧实现直接取 `displays[0]`，遇到「先声明仪表、
 * 后声明主屏」的车机就会把画面推到仪表上（比例、触摸坐标全错，日志还看不出选错了）。
 *
 * 现在的规则：[select] **只在车机自己声明了 `type` 时**才排序，否则保持「第一块有效屏」
 * 的旧行为——绝大多数车机不声明 `type`，改动对它们零影响。声明了 `type` 时：
 *   1. `type == 0` 视为主屏；
 *   2. 其余按 `type` 升序；
 *   3. 同 type 比面积（大屏是主屏）；
 *   4. 再比 `maxFPS`（解码能力强的）；
 *   5. 最后按声明顺序，保证结果确定（同样的 /info 永远选出同一块屏）。
 *
 * 这条排序是**假设**，不是实车结论：目前没有一台多屏车机接入过，所以选完必须打一行
 * 「候选 N 块 → 选中 displays[i]」，实车一跑就能验证对不对。
 */
object CarDisplayGeometry {
    /** 解析结果。[source] 用于日志，说明到底命中了哪一组键。 */
    data class Size(
        val width: Int,
        val height: Int,
        val source: String,
    ) {
        val text: String get() = "${width}×${height}"
    }

    /** `displays[]` 里的一块屏（只保留能用的：宽高都解析成功的）。 */
    data class Screen(
        val index: Int,
        val width: Int,
        val height: Int,
        /** 车机声明的屏类型；null = 没声明。0 视为主屏。 */
        val type: Int?,
        /** 显示屏 UUID（真 iPhone 会回填进屏幕流 SETUP，真车会校验）。 */
        val uuid: String?,
        /** 车机声明的解码帧率上限；0 = 没声明。 */
        val maxFps: Int,
        /** 命中的键名，进日志用。 */
        val widthKey: String,
        val heightKey: String,
    ) {
        val area: Long get() = width.toLong() * height.toLong()
        val text: String get() = "$width×$height"

        fun describe(): String =
            "displays[$index] $text" +
                (type?.let { " type=$it" } ?: "") +
                (if (maxFps > 0) " maxFPS=$maxFps" else "") +
                (uuid?.let { " uuid=${it.take(8)}" } ?: "")
    }

    private val WIDTH_KEYS = listOf("widthPixels", "WidthPhysical", "widthPhysical", "width", "pixelWidth")
    private val HEIGHT_KEYS = listOf("heightPixels", "HeightPhysical", "heightPhysical", "height", "pixelHeight")

    /** 合理上限：超过 8K 的「分辨率」基本都是字段解析错位，宁可判无效走兜底。 */
    private const val MAX_SIDE = 8192

    /** 解析全部可用屏幕（按声明顺序）。空列表 = 车机没通告。 */
    fun screens(root: Map<*, *>?): List<Screen> {
        val displays = root?.get("displays") as? List<*> ?: return emptyList()
        val out = ArrayList<Screen>(displays.size)
        for ((index, entry) in displays.withIndex()) {
            val display = entry as? Map<*, *> ?: continue
            val width = firstPositive(display, WIDTH_KEYS) ?: continue
            val height = firstPositive(display, HEIGHT_KEYS) ?: continue
            out +=
                Screen(
                    index = index,
                    width = width.first,
                    height = height.first,
                    // type 用整数解析而不是 positiveInt：0 是**合法主屏**取值，
                    // positiveInt 会把 0 判成非法（那是给宽高/帧率用的约束）。
                    type = intOrNull(display["type"])?.takeIf { it >= 0 },
                    uuid = uuidText(display["uuid"] ?: display["UUID"] ?: display["displayUUID"]),
                    maxFps = positiveInt(display["maxFPS"]) ?: 0,
                    widthKey = width.second,
                    heightKey = height.second,
                )
        }
        return out
    }

    /** 从候选里选主屏；没有任何可用屏返回 null。 */
    fun select(screens: List<Screen>): Screen? {
        if (screens.isEmpty()) return null
        // 一块屏、或车机没声明 type：保持历史行为（声明顺序里的第一块有效屏）。
        if (screens.size == 1 || screens.none { it.type != null }) return screens.first()
        return screens.sortedWith(MAIN_FIRST).first()
    }

    /**
     * 主屏优先的比较器：type 0 最先，其余 type 升序；同 type 比面积、帧率，最后比下标。
     *
     * 没声明 type 的屏排在声明过的之后（它多半是副屏或被填充的占位项），
     * 但仍参与排序而不是被过滤——少一块屏可选的后果比排序不完美更糟。
     */
    private val MAIN_FIRST: Comparator<Screen> =
        compareBy<Screen> { if (it.type == 0) 0 else if (it.type != null) 1 else 2 }
            .thenBy { it.type ?: Int.MAX_VALUE }
            .thenByDescending { it.area }
            .thenByDescending { it.maxFps }
            .thenBy { it.index }

    /** 解析 `/info` 根字典里的主屏尺寸；拿不到（或数值非法）返回 null。 */
    fun fromInfo(root: Map<*, *>?): Size? = select(screens(root))?.toSize()

    /** 把选中的屏转成 [Size]（调用方已自己 `screens()`+`select()` 时用，避免重复解析）。 */
    fun sizeOf(screen: Screen): Size = screen.toSize()

    private fun Screen.toSize(): Size =
        Size(
            width = width,
            height = height,
            source = "/info displays[$index].$widthKey×$heightKey",
        )

    /** 候选屏的一行描述（空列表也要能说出来，否则「为什么用了手机分辨率」无从判断）。 */
    fun describe(screens: List<Screen>): String =
        if (screens.isEmpty()) {
            "/info displays 无可解析项"
        } else {
            screens.joinToString("；") { it.describe() }
        }

    /** 按优先级取第一个正整数，返回 (值, 命中的键名)。 */
    private fun firstPositive(
        map: Map<*, *>,
        keys: List<String>,
    ): Pair<Int, String>? {
        for (key in keys) {
            val value = positiveInt(map[key]) ?: continue
            return value to key
        }
        return null
    }

    /**
     * 数值宽容解析：Long / Int / Double / 字符串都收。
     *
     * bplist 的整数在我们自己的解析器里是 Long，但侧信道 /info 走的是同一套解析，
     * 而个别车机把宽高写成 real（`0x2` 类型 → Double，如 1412.0）——只写 `as? Long`
     * 会全部落空。字符串形态是给「某些固件把数字塞进字符串」留的口子。
     */
    fun positiveInt(raw: Any?): Int? {
        val value =
            when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.trim().toDoubleOrNull()
                else -> null
            } ?: return null
        if (value.isNaN() || value <= 0.0 || value > MAX_SIDE) return null
        return value.toInt()
    }

    /** 整数宽容解析（允许 0）；非整数/负数返回 null。给 `displays[].type` 这类枚举值用。 */
    private fun intOrNull(raw: Any?): Int? =
        when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }

    /**
     * `/info displays[].uuid` 统一字符串化。
     *
     * 三种编码都见过：字符串（奥迪 "0"/"1"、部分固件给 36 位 UUID）、**16 字节 UUID**
     * （bplist 0xB，`toString()` 会变成 `[B@1f2e3d4` 这种垃圾）、CFKeyedArchiver 的 UID（整数）。
     * 屏幕流 SETUP 里的 `uuid` 必须与接收端声明的**完全一致**，否则车机可能收下流却不往
     * 任何屏上绑（表现为「连上了没画面」）。
     */
    private fun uuidText(raw: Any?): String? =
        when (raw) {
            null -> null
            is String -> raw.takeIf { it.isNotBlank() }
            is ByteArray ->
                if (raw.size == 16) {
                    val hex = raw.joinToString("") { "%02x".format(it) }
                    "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                        "${hex.substring(16, 20)}-${hex.substring(20)}"
                } else {
                    raw.joinToString("") { "%02x".format(it) }
                }
            is Number -> raw.toString()
            else -> raw.toString().takeIf { it.isNotBlank() }
        }
}
