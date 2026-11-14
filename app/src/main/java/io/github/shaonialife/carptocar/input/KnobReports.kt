package io.github.shaonialife.carptocar.input

/**
 * 旋钮 HID 报文（Generic Desktop `Wheel` + 按钮位图）的解析。
 *
 * ## 为什么需要它
 *
 * 宝马 iDrive 旋钮在车机的 `/info` 里是**两台独立设备**：
 * - `KnobInput`：`05 01 09 08 a1 01 …05 0c 0a 24 02 …05 01 09 38 …81 06`
 *   → byte0 是按钮位（bit0 = 旋钮按下、bit1 = Consumer AC Back），byte1 是 **相对滚轮**；
 * - `ButtonInput`：Consumer 页的媒体键（上一首/下一首/播放），走
 *   [ConsumerKeys] 那条通路。
 *
 * 实测（2026-11-14 宝马 F49 NBT Evo）旋钮转动会上报 `00 01` / `00 ff`——就是滚轮 ±1，
 * 但旧实现既不认「Generic Desktop/Wheel」这个签名，也解析不出按钮位，整条报文落进
 * 「其他 HID 报告」只打一行日志就丢了，表现正是「iDrive 旋钮控制不了屏幕」。
 *
 * ## 判定口径：看描述符，不看型号
 *
 * 触摸设备的描述符是 Digitizer 页（`09 04`）、媒体键是 Consumer 页、电话键是
 * Telephony 页，**都不声明 Wheel**；只有旋钮/滚轮设备才声明
 * `Usage Page 0x01 + Usage 0x38`。所以「有 Wheel」就是旋钮的充分判据，
 * 不必按 uuid 或车机型号写死（与 [ConsumerKeys] 的解析口径保持一致）。
 *
 * ## 只支持无 Report ID 的描述符
 *
 * 带 `Report ID` 的报告首字节是 ID，位偏移整体后移 8 位；本项目遇到的旋钮设备都是
 * 无 ID 的单报文形态。遇到带 ID 的描述符直接返回 null（宁可退化成「不处理」，
 * 也不要按错的位偏移取值——那会把旋钮当成乱按的按钮）。
 */
object KnobReports {
    private const val USAGE_PAGE_GENERIC_DESKTOP = 0x01
    private const val USAGE_PAGE_BUTTON = 0x09
    private const val USAGE_WHEEL = 0x38
    private const val USAGE_BUTTON_1 = 0x01
    private const val USAGE_AC_BACK = 0x0224

    /** 滚轮与按钮在一份报告里的位置（位偏移，从 0 起算）。 */
    class Layout(
        /** 滚轮字段的起始位。 */
        val wheelBit: Int,
        /** 滚轮字段位宽（实测 8）。 */
        val wheelWidth: Int,
        /** true = 有符号（描述符 Logical Minimum 为负，如 `15 81`）。 */
        val wheelSigned: Boolean,
        /** 旋钮按下（Generic Desktop Button 1）所在位；描述符没声明则 null。 */
        val buttonBit: Int?,
        /** Consumer AC Back 所在位（iDrive 的返回），没有则 null。 */
        val backBit: Int?,
    ) {
        /** 一份报告的字节数下限。 */
        val minReportBytes: Int
            get() = ((maxOf(wheelBit + wheelWidth, (buttonBit ?: 0) + 1, (backBit ?: 0) + 1)) + 7) / 8
    }

    /** 一次旋钮报告解读出的动作。 */
    class Event(
        /** 滚轮相对位移：>0 顺时针、<0 逆时针、0 无转动。 */
        val steps: Int,
        /** 旋钮按下位；本报告不含按钮字段时为 null。 */
        val pressed: Boolean?,
        /** AC Back（返回）是否按下；没有该字段时为 null。 */
        val backPressed: Boolean?,
    )

    /**
     * 从 HID 描述符里找出滚轮与按钮的位置。
     *
     * @return 该设备不是旋钮（没有 Generic Desktop/Wheel）时返回 null
     */
    fun parseLayout(descriptor: ByteArray): Layout? {
        var index = 0
        var usagePage = 0
        var reportSize = 0
        var reportCount = 0
        var logicalMin = 0
        var bitOffset = 0
        var sawReportId = false
        var wheelBit = -1
        var wheelWidth = 0
        var wheelSigned = false
        var buttonBit: Int? = null
        var backBit: Int? = null
        val usages = ArrayList<Int>()

        while (index < descriptor.size) {
            val prefix = descriptor[index].toInt() and 0xFF
            val dataBytes =
                when (prefix and 0x03) {
                    0 -> 0
                    1 -> 1
                    2 -> 2
                    else -> 4
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
                // 全局项
                type == 1 && tag == 0x0 -> usagePage = value
                type == 1 && tag == 0x1 -> logicalMin = signed(value, dataBytes)
                type == 1 && tag == 0x7 -> reportSize = value
                type == 1 && tag == 0x8 -> if (value > 0) sawReportId = true
                type == 1 && tag == 0x9 -> reportCount = value
                // 局部项：Usage
                type == 2 && tag == 0x0 -> usages += value
                // 主项：Input——按 Variable 语义逐个字段推进位偏移
                type == 0 && tag == 0x8 -> {
                    val constant = (value and 0x01) != 0
                    val variable = (value and 0x02) != 0
                    if (!constant && variable) {
                        for (field in 0 until reportCount) {
                            val usage = usages.getOrNull(field) ?: usages.lastOrNull() ?: 0
                            val bit = bitOffset + field * reportSize
                            when {
                                usagePage == USAGE_PAGE_GENERIC_DESKTOP && usage == USAGE_WHEEL -> {
                                    wheelBit = bit
                                    wheelWidth = reportSize
                                    wheelSigned = logicalMin < 0
                                }
                                usagePage == USAGE_PAGE_BUTTON && usage == USAGE_BUTTON_1 -> {
                                    buttonBit = bit
                                }
                                usagePage == 0x0C && usage == USAGE_AC_BACK -> backBit = bit
                            }
                        }
                    }
                    bitOffset += reportSize * reportCount
                    usages.clear()
                }
                // 其他主项（Collection/EndCollection/Output/Feature）不影响输入位偏移，
                // 但局部 Usage 在 Output 之后仍可能遗留，清掉更安全。
                type == 0 && (tag == 0x9 || tag == 0xA || tag == 0xB || tag == 0xC) -> usages.clear()
            }
        }
        if (sawReportId) return null
        if (wheelBit < 0 || wheelWidth <= 0) return null
        return Layout(wheelBit, wheelWidth, wheelSigned, buttonBit, backBit)
    }

    /** 按 [layout] 解读一份报告。报告长度不足时返回全零（不猜）。 */
    fun parse(
        report: ByteArray,
        layout: Layout,
    ): Event {
        if (report.size < layout.minReportBytes) return Event(0, null, null)
        val steps = readField(report, layout.wheelBit, layout.wheelWidth, layout.wheelSigned)
        val pressed = layout.buttonBit?.let { readField(report, it, 1, false) != 0 }
        val back = layout.backBit?.let { readField(report, it, 1, false) != 0 }
        return Event(steps, pressed, back)
    }

    /** 从 [report] 里按位取一段整数（小端位序，HID 规定低位在前）。 */
    private fun readField(
        report: ByteArray,
        bit: Int,
        width: Int,
        signed: Boolean,
    ): Int {
        var value = 0
        for (offset in 0 until width) {
            val index = bit + offset
            val byte = index / 8
            if (byte >= report.size) break
            val bitValue = (report[byte].toInt() shr (index % 8)) and 0x01
            value = value or (bitValue shl offset)
        }
        if (!signed) return value
        val signBit = 1 shl (width - 1)
        return if (value and signBit != 0) value - (signBit shl 1) else value
    }

    /** 逻辑最小值按 HID 规定做符号扩展（`15 81` = -127）。 */
    private fun signed(
        value: Int,
        bytes: Int,
    ): Int {
        if (bytes <= 0) return 0
        val signBit = 1 shl (bytes * 8 - 1)
        return if (value and signBit != 0) value - (signBit shl 1) else value
    }
}
