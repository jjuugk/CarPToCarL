package io.github.shaonialife.carptocar.airplay

/**
 * 车机 `/info` 里的**声明类字段**（AIS 23.3.2.2 / Table 23-26、Table 23-33）。
 *
 * 为什么单独拎出来：这些字段的共同点是「车机说它有，我们才该用」，而它们的取值有
 * **三种**（有 / 没有 / 根本没声明），第三种最容易被顺手写成 false。本项目已经因为
 * 「未声明当成不支持」踩过坑（`maxFPS` 被兜成 30、`audioInputFormats` 被当成 0），
 * 所以这里统一用 `Boolean?` 表达三态：`null` 一律解释为「车机没说，别替它决定」。
 *
 * 覆盖范围只到「读取 + 判定」，不含动作：
 *  - `extendedFeatures`：无线 CarPlay 的配件必须声明 `"vocoderInfo"`（Table 23-33），
 *    声明了才代表它认音频 SETUP 里的 `vocoderInfo` 字典；`"enhancedRequestCarUI"`
 *    决定 `requestUI` 能否带 `url`。
 *  - `keepAliveLowPower`：**车机**声明它支持「无活跃媒体流时只发 UDP 保活」
 *    （23.3.2.7）。注意我们 SETUP 请求里同名的键是反向声明（告诉车机我们接受这种模式），
 *    两者不要混为一谈。
 *  - `bluetoothIDs`：车机蓝牙模块的 MAC 清单，是 `disableBluetooth` 的前置（Table 23-26）。
 */
internal object CarCapabilityList {

    /** Table 23-33：配件支持 `requestUI` 的 `url` 键。 */
    const val ENHANCED_REQUEST_CAR_UI = "enhancedRequestCarUI"

    /** Table 23-33：配件上报电话输出流原生采样率；**无线 CarPlay 必填**。 */
    const val VOCODER_INFO = "vocoderInfo"

    /**
     * `/info.extendedFeatures` 原样读出（字符串清单，保持车机给的顺序）。
     *
     * @return `null` = 车机的 `/info` 里**没有这个键**；空列表 = 有键但内容为空。
     *   两者对调用方的含义不同（没给清单 = 不猜，照旧行为）。
     */
    fun extendedFeatures(info: Map<*, *>?): List<String>? {
        val declared = info?.get("extendedFeatures") as? List<*> ?: return null
        return declared.mapNotNull { it?.toString() }
    }

    /**
     * 车机是否声明了某个扩展特性。
     *
     * @return `true` = 明确声明支持；`false` = 有清单但不含它；`null` = **整份清单都没给**
     *   （老固件 / 非无线配件）。调用方遇到 `null` 应保持原有行为，不要据此关闭功能。
     */
    fun supportsExtended(
        info: Map<*, *>?,
        feature: String,
    ): Boolean? = extendedFeatures(info)?.any { it.equals(feature, ignoreCase = true) }

    /** 车机是否声明 `keepAliveLowPower`（缺省 false，与协议里「未声明即不支持」一致）。 */
    fun supportsLowPowerKeepAlive(info: Map<*, *>?): Boolean = (info?.get("keepAliveLowPower") as? Boolean) == true

    /** 车机蓝牙模块 MAC 清单（`bluetoothIDs`，冒号分隔，AIS 要求带分隔符）。 */
    fun bluetoothIds(info: Map<*, *>?): List<String> =
        (info?.get("bluetoothIDs") as? List<*>)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()

    /** 一行日志：把三态如实写出来，避免事后分不清「没说」和「说了不支持」。 */
    fun summary(info: Map<*, *>?): String {
        val features =
            extendedFeatures(info)
                ?.let { list -> "extendedFeatures=${if (list.isEmpty()) "空清单" else list.joinToString(",")}" }
                ?: "extendedFeatures=未声明"
        val lowPower = if (supportsLowPowerKeepAlive(info)) "keepAliveLowPower=true" else "keepAliveLowPower=未声明"
        val bluetooth =
            bluetoothIds(info).let { ids ->
                if (ids.isEmpty()) "bluetoothIDs=未声明" else "bluetoothIDs=${ids.joinToString(",")}"
            }
        return "$features $lowPower $bluetooth"
    }
}
