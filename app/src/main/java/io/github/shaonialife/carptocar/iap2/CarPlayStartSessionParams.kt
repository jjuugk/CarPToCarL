package io.github.shaonialife.carptocar.iap2

/**
 * `0x4301 CarPlayStartSession` / `0x5703 WiFiSharing` 里**与连接建立相关**的那几个参数的解析。
 *
 * 字段定义参照车机侧实现（DiPlay `Iap2Endpoint.CARPLAY_START_SESSION` /
 * `ACCESSORY_WIFI_CONFIGURATION_INFORMATION`），字段顺序与本项目一致：
 *
 * ```
 * 0x4301: 0 wired(group) | 1 wireless(group) | 2 airPlayPort(U32) | 3 deviceIdentifier |
 *         4 publicKey | 5 sourceVersion | 6 SDKVersion | 7 clusterAsset(group) | 8 mutualAuth(U8)
 * 0x5703: 0 BSSID(bytes,6) | 1 SSID | 2 passphrase | 3 securityType(U8) | 4 channel(U8)
 * ```
 *
 * 为什么要单独抽出来：这几个参数此前**全部没解析**（只读 1~5 与 1~4），
 * 而它们各自对应一次「连接失败但看不出原因」：
 *  - BSSID 缺失 → Wi-Fi Direct 组加入时拿不到 deviceAddress，只能靠发现去猜；
 *  - mutualAuth=1 → 车机要求双向认证，我们只做单向，会停在「端点拿到了、认证过不去」；
 *  - SDKVersion 缺失 → 日志里判断不了对方是哪一代 CarPlay 实现。
 *
 * 纯函数、不碰 Android API，便于单测（见 `CarPlayStartSessionParamsTest`）。
 */
internal object CarPlayStartSessionParams {
    /**
     * param 0 / 0x5703 param 0：BSSID。标准形态是 6 字节裸 MAC。
     *
     * 也接受 7 字节（首字节为长度 6）——部分实现在 BYTES 类型上会多带一个长度前缀，
     * 而这两种形态在真机上无法事先区分，只能按长度判。
     */
    fun bssid(raw: ByteArray?): String? {
        if (raw == null) return null
        val mac =
            when {
                raw.size == 6 -> raw
                raw.size == 7 && (raw[0].toInt() and 0xFF) == 6 -> raw.copyOfRange(1, 7)
                else -> return null
            }
        if (mac.all { it == 0.toByte() }) return null
        return mac.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** param 6：SDKVersion。缺失返回 null（不是错误，老车机没有这一项）。 */
    fun sdkVersion(raw: ByteArray?): String? {
        val text =
            raw?.toString(Charsets.UTF_8)?.trim('\u0000', ' ')?.takeIf { it.isNotEmpty() } ?: return null
        // 只保留可打印字符：有的实现把 STRING 当带长度前缀的裸字节发，直接显示会带控制符
        return text.filter { it.code in 0x20..0x7E }.takeIf { it.isNotEmpty() }
    }

    /**
     * param 8：mutualAuth。U8，非 0 即要求双向认证。
     *
     * 注意语义边界：这里只负责「读出来」。是否真的需要额外认证流程由会话层决定——
     * 不同接收端对这个标志的实现差异很大，硬套一套流程反而会打断现在能连上的车机。
     */
    fun mutualAuth(raw: ByteArray?): Boolean {
        val value = raw?.firstOrNull()?.toInt()?.and(0xFF) ?: return false
        return value != 0
    }
}
