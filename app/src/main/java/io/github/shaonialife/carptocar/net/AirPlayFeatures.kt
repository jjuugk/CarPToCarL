package io.github.shaonialife.carptocar.net

/**
 * `_airplay._tcp` TXT `features` 位图解析（形如 `0x44540380,0x61`）。
 *
 * 低位在前：`低32位,高32位`，合并成 64 位。AirPlay 接收端用 bit38
 * `HK_PAIRING_AND_ENCRYPT` 表示「支持 HomeKit 配对/加密」——老车机
 * （CarPlay 210.81 时代）不声明该位，只接受 `et=NONE` 明文会话。
 *
 * 解析失败一律返回 null（未知），调用方按「保持旧行为」处理，
 * 绝不能把解析失败当成「不支持配对」——那会让正常车机也被降级成明文。
 */
object AirPlayFeatures {
    /** 支持 /pair-setup + /pair-verify（AirPlayFeature::HK_PAIRING_AND_ENCRYPT）。 */
    const val HK_PAIRING_AND_ENCRYPT: Long = 1L shl 38

    /** 支持屏幕镜像。 */
    const val SCREEN: Long = 1L shl 7

    /** 车机控制（CarPlay）。 */
    const val CARPLAY_CONTROL: Long = 1L shl 37

    fun parse(txt: String?): Long? {
        val text = txt?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 2) return null
        val low = parseHex(parts[0]) ?: return null
        val high = if (parts.size == 2) parseHex(parts[1]) ?: return null else 0L
        if (low < 0 || high < 0) return null
        return (high shl 32) or low
    }

    /** 接收端是否声明支持 HomeKit 配对/加密；null = TXT 缺失或不可解析。 */
    fun supportsPairing(features: Long?): Boolean? = features?.let { (it and HK_PAIRING_AND_ENCRYPT) != 0L }

    /**
     * 车机是否声明「由它主动发起连接」（bit37 `CARPLAY_CONTROL`）。
     *
     * catplay `AirPlayFeature::CARPLAY_CONTROL` 的语义是「Car initiates the connection
     * through CarPlayStartSession or CarPlayControl」。声明了就说明车机会发 ctrl 邀请
     * 或 0x4301，没声明的老车机只会等手机来连——此时死等邀请是白等。
     * null = TXT 缺失或不可解析（调用方一律按「保持旧行为」处理，不能当成不支持）。
     */
    fun supportsCarPlayControl(features: Long?): Boolean? = features?.let { (it and CARPLAY_CONTROL) != 0L }

    private fun parseHex(value: String): Long? {
        val cleaned = value.removePrefix("0x").removePrefix("0X")
        if (cleaned.isEmpty()) return null
        return cleaned.toLongOrNull(16)
    }
}
