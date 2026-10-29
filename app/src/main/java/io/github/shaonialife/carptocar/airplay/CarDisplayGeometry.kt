package io.github.shaonialife.carptocar.airplay

/**
 * 车机主显示屏几何（像素宽×高），唯一来源是接收端 AirPlay `/info` 的 `displays[0]`。
 *
 * 为什么单独抽成纯函数：这段解析原本直接写在 [AirPlayClient] 里，只认 `as? Long`
 * 一种类型、只认 `widthPixels/heightPixels` 两个键。车机侧写法并不统一（有的把
 * 分辨率写进 2 字节整数、有的写成 Double、个别固件用 `width/height`），任何一处
 * 对不上就会**静默**退到手机分辨率兜底——画面比例当场就错，日志里却看不出来。
 *
 * 另外 `displays` 可能有多个（主屏 + 副屏 / 仪表），CarPlay 主屏固定取第一个；
 * 后续要做副屏（仪表）时按 index 取即可，别把这条约定改掉。
 */
object CarDisplayGeometry {
    /** 解析结果。[source] 用于日志，说明到底命中了哪一组键。 */
    data class Size(
        val width: Int,
        val height: Int,
        val source: String,
    ) {
        val text: String get() = "${width}×$height"
    }

    /**
     * 键名按优先级排列：Apple 的 `widthPixels/heightPixels` 优先。
     *
     * `WidthPhysical/HeightPhysical` 不是猜的——DiPlay（Android 车机侧接收端）的
     * `/info` 就按这两个键声明（见 BinaryPlistTest 里那条 plutil 生成的真实向量：
     * `WidthPhysical=800 / HeightPhysical=480`）。自家实现的接收端往往照抄这套命名，
     * 只认 Apple 的键会把它们全判成「车机未通告」。
     */
    private val WIDTH_KEYS = listOf("widthPixels", "WidthPhysical", "widthPhysical", "width", "pixelWidth")
    private val HEIGHT_KEYS = listOf("heightPixels", "HeightPhysical", "heightPhysical", "height", "pixelHeight")

    /** 合理上限：超过 8K 的「分辨率」基本都是字段解析错位，宁可判无效走兜底。 */
    private const val MAX_SIDE = 8192

    /** 解析 `/info` 根字典里的主屏尺寸；拿不到（或数值非法）返回 null。 */
    fun fromInfo(root: Map<*, *>?): Size? {
        val displays = root?.get("displays") as? List<*> ?: return null
        for ((index, entry) in displays.withIndex()) {
            val display = entry as? Map<*, *> ?: continue
            val width = firstPositive(display, WIDTH_KEYS) ?: continue
            val height = firstPositive(display, HEIGHT_KEYS) ?: continue
            return Size(
                width = width.first,
                height = height.first,
                source = "/info displays[$index].${width.second}×${height.second}",
            )
        }
        return null
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
}
