package io.github.shaonialife.carptocar.net

/**
 * 接收端地址候选的排序与判定（纯函数，单测覆盖）。
 *
 * 背景：安卓端很容易把「有地址」误判成「有 IPv4 地址」——奥迪 MIB3 的
 * `_airplay._tcp` 只发布链路本地 IPv6（`fe80::…%wlan0`）时，旧代码会把它整条
 * 丢掉，然后退到 `网关:7000` 兜底；而 4xxx 固件车机的网关/端口未必是
 * `10.174.189.1:7000`，于是永远连不上（2026-10-19 用户反馈的 4851 即此类）。
 *
 * 排序策略（真 iPhone / catplay 也是直接使用 Bonjour 给的地址）：
 *  1. IPv4 —— 安卓上最稳；
 *  2. 全局 IPv6；
 *  3. 链路本地 IPv6（必须带 scope 才能用，保留原样由调用方补 `%iface`）。
 * 同一个地址的带/不带 scope 形式视为重复，只保留先出现的。
 */
object EndpointHosts {
    fun isIpv4(host: String): Boolean = host.contains('.') && !host.contains(':')

    fun isIpv6(host: String): Boolean = host.contains(':')

    fun isLinkLocalIpv6(host: String): Boolean {
        val normalized = host.substringBefore('%').lowercase()
        return normalized.startsWith("fe80:") ||
            normalized == "fe80" ||
            normalized.startsWith("fe8") ||
            normalized.startsWith("fe9") ||
            normalized.startsWith("fea") ||
            normalized.startsWith("feb")
    }

    /** 去掉 `%wlan0` / `%3` 这类 scope 后缀。 */
    fun stripScope(host: String): String = host.substringBefore('%')

    /**
     * 接收端是否给出了可尝试的地址（IPv4 或 IPv6 都算）。
     * 供「端点是否自足」「是否需要 mDNS 补全」判断，勿再写 `contains('.')`。
     */
    fun hasUsable(addresses: List<String>): Boolean = candidates(addresses).isNotEmpty()

    /**
     * 候选地址的一行描述（进日志用）：`192.168.43.1（IPv4）、fe80::1%wlan0（链路本地 IPv6）`。
     *
     * 为什么要单独能打出来：mDNS 解析到的地址与 0x4301 自报的地址**可能既不同族也不同值**
     * （现场一台 DiPlay：0x4301 给 IPv6 link-local，mDNS 通告 IPv4 192.168.43.1）。
     * 两者都只是「连接候选」，**身份判定靠 pair-verify 的 pk 签名，与地址无关**——
     * 但排查时必须能一眼看到「这次按什么顺序试了哪些地址」，否则连错屏/连不上都只能猜。
     */
    fun describeCandidates(addresses: List<String>): String {
        val ordered = candidates(addresses)
        if (ordered.isEmpty()) return "无可用地址"
        return ordered.joinToString("、") { host ->
            val base = stripScope(host)
            val family =
                when {
                    isIpv4(base) -> "IPv4"
                    isLinkLocalIpv6(base) -> "链路本地 IPv6"
                    else -> "全局 IPv6"
                }
            "$host（$family）"
        }
    }


    /**
     * 占位地址 / 回环地址：`0.0.0.0`、`::`、`::ffff:0.0.0.0`、`127.x`、`::1`。
     *
     * **必须在「候选」这一步就剔掉**。2026-11-11 宝马 iD8 实测：车机热点 DHCP 把默认
     * 网关填成占位值 `0.0.0.0`，`gatewayAddresses()` 放行后端点里只剩它，于是每次连接都
     * 打到 `127.0.0.1:7000`——日志里四十多条
     * `failed to connect to localhost/127.0.0.1 (port 7000) ECONNREFUSED`，
     * 而真正在发 ctrl 邀请的车机 172.16.222.97 一次都没被试过。更坏的是主控把
     * 「连自己失败」读成「车机 7000 连续拒绝」，反复去「叫醒」一个本来就活着的车机。
     *
     * 注意别顺手把链路本地 IPv6（`fe80::`）算成占位——奥迪 MIB3 只发这个，它是有效地址。
     */
    fun isPlaceholder(host: String): Boolean {
        val base = stripScope(host).trim().lowercase()
        if (base.isEmpty()) return true
        if (base == "0.0.0.0" || base == "::" || base == "0:0:0:0:0:0:0:0") return true
        if (base == "::1" || base == "0:0:0:0:0:0:0:1") return true
        if (base == "::ffff:0.0.0.0" || base == "0000:0000:0000:0000:0000:ffff:0000:0000") return true
        if (base.startsWith("127.")) return true
        return false
    }

    /**
     * 排序 + 去重。空串 / 纯 scope / 占位地址（见 [isPlaceholder]）一律剔除。
     *
     * 占位值收口在这里，而不是只堵网关一处：0x4301、mDNS、网关三条来源最后都汇进本函数，
     * `0.0.0.0` 从任何一条进来都连不通，只会白烧一次 2s 超时再污染「连续拒绝」计数。
     */
    fun candidates(addresses: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(addresses.size)

        fun add(host: String) {
            val trimmed = host.trim()
            if (trimmed.isEmpty()) return
            val base = stripScope(trimmed)
            if (base.isEmpty()) return
            if (!isIpv4(base) && !isIpv6(base)) return
            if (isPlaceholder(trimmed)) return
            val key = base.lowercase()
            if (seen.add(key)) out += trimmed
        }
        addresses.filter(::isIpv4).forEach(::add)
        addresses.filter { isIpv6(it) && !isLinkLocalIpv6(it) }.forEach(::add)
        addresses.filter(::isLinkLocalIpv6).forEach(::add)
        return out
    }

    /**
     * 地址是否属于「本机所在的车机网段」。
     *
     * - IPv4：与本地 IPv4 的 /24 前缀一致（沿用旧过滤，防误连同一 WiFi 上的 Mac）；
     * - IPv6 链路本地：天然只在本链路，直接接受；
     * - IPv6 全局：与本地某个 IPv6 地址的前 64 位一致才接受（拿不到本地 IPv6 时拒绝，
     *   宁可退回网关兜底，也不把公网/家庭网络的 AirPlay 设备当车机）。
     */
    fun isSameNetwork(
        host: String,
        localIpv4: String?,
        localIpv6: List<String> = emptyList(),
    ): Boolean {
        if (isIpv4(host)) {
            val prefix = localIpv4?.substringBeforeLast('.')?.plus(".")
            return prefix == null || host.startsWith(prefix)
        }
        if (!isIpv6(host)) return false
        if (isLinkLocalIpv6(host)) return true
        val candidate = ipv6Prefix64(host) ?: return false
        return localIpv6.any { ipv6Prefix64(it) == candidate }
    }

    /** IPv6 的前 64 位（前 4 组 hextet，展开后比较；解析失败返回 null）。 */
    fun ipv6Prefix64(host: String): String? {
        val groups = expandIpv6(stripScope(host)) ?: return null
        return groups.take(4).joinToString(":")
    }

    /** 把 IPv6 文本展开为 8 组（处理 `::` 压缩），返回小写 hextet 列表。 */
    private fun expandIpv6(host: String): List<String>? {
        if (!host.contains(':')) return null
        val doubleColon = host.indexOf("::")
        val head: List<String>
        val tail: List<String>
        if (doubleColon >= 0) {
            head = host.substring(0, doubleColon).split(':').filter { it.isNotEmpty() }
            tail = host.substring(doubleColon + 2).split(':').filter { it.isNotEmpty() }
        } else {
            head = host.split(':')
            tail = emptyList()
        }
        if (head.size + tail.size > 8) return null
        val fill = 8 - head.size - tail.size
        val groups = ArrayList<String>(8)
        groups += head
        repeat(fill) { groups += "0" }
        groups += tail
        if (groups.size != 8) return null
        if (groups.any { it.length > 4 || it.any { c -> c.digitToIntOrNull(16) == null } }) return null
        return groups.map { it.lowercase().padStart(4, '0') }
    }
}
