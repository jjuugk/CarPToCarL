package io.github.shaonialife.carptocar.net

/**
 * Wi-Fi Direct 组名（SSID）的纯函数判定，供 P2P 连接器与单元测试共用。
 *
 * P2P GO 的 SSID 固定形如 `DIRECT-xy-<设备名>`：`DIRECT-` 前缀 + 两个字符的随机后缀。
 * 这一类 SSID **不能**用 `WifiNetworkSpecifier`（站模式）加入——它由 `WifiP2pManager`
 * 独占管理，站模式关联上去也拿不到 p2p 组的加密参数与 IP。
 */
internal object WifiP2pGroupNames {
    const val PREFIX = "DIRECT-"

    /** 是否是 Wi-Fi Direct 组（大小写不敏感，大小写在不同 ROM 上确实不一致）。 */
    fun isP2pGroupName(ssid: String): Boolean = ssid.startsWith(PREFIX, ignoreCase = true)

    /**
     * 从组名推出 GO 的设备名，用于在扫描结果里挑对等体。
     *
     * `DIRECT-xy-AudiPhone` → `AudiPhone`；格式不符时返回 null（调用方退回「只有一个对等体就用它」）。
     */
    fun deviceNameOf(ssid: String): String? {
        if (!isP2pGroupName(ssid)) return null
        val rest = ssid.substring(PREFIX.length)
        // 期望 "xy-名字"；没有分隔符时把整个剩余部分当名字（有的实现就是 DIRECT-名字）
        val dash = rest.indexOf('-')
        val name = if (dash >= 0 && dash <= 2) rest.substring(dash + 1) else rest
        return name.takeIf { it.isNotBlank() }
    }
}
