package io.github.shaonialife.carptocar.net

import android.content.Context
import io.github.shaonialife.carptocar.iap2.Iap2SenderSession

/**
 * 「最近一次成功建链时车机下发的网络信息」的持久化记忆。
 *
 * 解决的问题：车机声明会发 `0x4301`（携带热点 SSID / 密码 / 端点），但**它并不总是发**——
 * 2026-11-21 别克 BUICK_6369（Saic GM / B233）实测：07:34、07:39 两轮正常下发并建链成功，
 * 08:17 用户断开再连时整轮 `0x4301` 零下发（三档 0x4300 报文体都换不来），App 只能干等
 * 90 秒后拆链。而同一台车机四次 0x4301 的无线组参数**逐字节相同**（SSID=BUICK_6369、
 * 16 字符固定密码、信道 149、`fe80::c800:8cff:fe9a:da9b:7000`）——也就是说这份网络信息
 * 本来就可以复用。
 *
 * 为什么此前复用不了：`SessionController.lastGoodEndpoint` 那份快照**只在内存**
 * （注释写明「进程被系统冻结后车机不会再补发 0x4301，解冻恢复只能靠这份快照」），
 * 用户杀掉 App 或系统回收进程后就归零了。本对象补的就是「落盘」这一环。
 *
 * 三条自我纠正机制，保证陈旧凭据不会把本来能连的车机拖坏：
 *  1. 有效期 [TTL_MS]，过期自动不再作为候选（车机固件更新/恢复出厂换密码后即自然失效）；
 *  2. 复用后**入网失败**时调用方会 [forget] 掉该条并回退到「等车机下发 0x4301」的原有流程，
 *     不会反复拿坏凭据撞墙；
 *  3. 只有**真正出过首帧**的端点才会被 [record]（见 `SessionController.startFirstFrameGate`），
 *     配对成功但没出画面的一轮不会污染记忆。
 *
 * 存储：SharedPreferences 单键多行，每行一条，最多 [MAX_ENTRIES] 条（最近成功在前）。
 * 密码按原样落盘（用户 2026-11-21 选定方案 A：明文，不做 Keystore 加密）。
 */
object NetworkMemory {
    /** 30 天：覆盖「一个月才开一次车」，同时短于固件更新周期。 */
    const val TTL_MS = 30L * 24 * 60 * 60 * 1000

    /** 最多记几台车机：自驾游换车、家里两辆车都够用。 */
    const val MAX_ENTRIES = 5

    private const val PREFS_NAME = "carplay_network"
    private const val KEY = "carplay_network_v1"

    /** 字段分隔符：Wi-Fi SSID / 密码里不会出现 NUL，用它对 8 个字段做定长切分。 */
    private const val FIELD_SEP = '\u0000'

    /** 条目分隔符：同理解析用。 */
    private const val ENTRY_SEP = '\u0001'

    /**
     * 一条网络记忆。
     *
     * [savedAtMillis] 用 `System.currentTimeMillis()`（要跨进程重启，不能用 elapsedRealtime），
     * 与 [io.github.shaonialife.carptocar.airplay.PairingMemory] 同一口径。
     */
    data class Entry(
        val endpoint: Iap2SenderSession.CarPlayEndpoint,
        val savedAtMillis: Long,
    ) {
        fun expired(
            nowMillis: Long,
            ttlMillis: Long = TTL_MS,
        ): Boolean = nowMillis - savedAtMillis >= ttlMillis

        /** 单行存储：`ssid␀passphrase␀channel␀securityType␀airPlayPort␀addresses␀publicKey␀savedAt`。 */
        fun encode(): String =
            listOf(
                endpoint.ssid,
                endpoint.passphrase,
                endpoint.channel.toString(),
                endpoint.securityType.toString(),
                endpoint.airPlayPort.toString(),
                endpoint.ipAddresses.joinToString(","),
                endpoint.publicKey.orEmpty(),
                savedAtMillis.toString(),
            ).joinToString(FIELD_SEP.toString())

        companion object {
            fun decode(raw: String?): Entry? {
                if (raw.isNullOrEmpty()) return null
                val parts = raw.split(FIELD_SEP)
                if (parts.size != 8) return null
                val ssid = parts[0]
                if (ssid.isEmpty()) return null
                val channel = parts[2].toIntOrNull() ?: return null
                val securityType = parts[3].toIntOrNull() ?: return null
                val port = parts[4].toIntOrNull() ?: return null
                val savedAt = parts[7].toLongOrNull() ?: return null
                val addresses =
                    parts[5]
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                val endpoint =
                    Iap2SenderSession.CarPlayEndpoint(
                        ssid = ssid,
                        passphrase = parts[1],
                        channel = channel,
                        securityType = securityType,
                        airPlayPort = port,
                        deviceIdentifier = null,
                        publicKey = parts[6].takeIf { it.isNotEmpty() },
                        sourceVersion = null,
                        ipAddresses = addresses,
                    )
                return Entry(endpoint, savedAt)
            }
        }
    }

    /** 解码整块内容（纯函数，可单测）。坏行静默跳过，不影响其它条目。 */
    fun decodeAll(raw: String?): List<Entry> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw
            .split(ENTRY_SEP)
            .mapNotNull { Entry.decode(it) }
    }

    fun encodeAll(entries: List<Entry>): String = entries.joinToString(ENTRY_SEP.toString()) { it.encode() }

    /**
     * 把 [entry] 并入已有列表（纯函数，可单测）：同 SSID 就地替换、置顶、截断到 [max]。
     *
     * 同 SSID 视为同一台车机：热点名就是它的身份，地址/密码变了也要覆盖而不是并排存两条。
     */
    fun merge(
        existing: List<Entry>,
        entry: Entry,
        max: Int = MAX_ENTRIES,
    ): List<Entry> =
        (listOf(entry) + existing.filter { it.endpoint.ssid != entry.endpoint.ssid })
            .take(max.coerceAtLeast(1))

    /** 过滤过期条目并保持原顺序（纯函数）。 */
    fun fresh(
        entries: List<Entry>,
        nowMillis: Long,
        ttlMillis: Long = TTL_MS,
    ): List<Entry> = entries.filterNot { it.expired(nowMillis, ttlMillis) }

    /**
     * 选一条最可能命中的记忆（纯函数）。
     *
     * 手机当前就在某个热点里时优先用同名的——避免「多台车都有记忆，拿错那台的密码去入网」。
     */
    fun pick(
        entries: List<Entry>,
        currentSsid: String?,
    ): Entry? {
        if (entries.isEmpty()) return null
        if (!currentSsid.isNullOrBlank()) {
            entries.firstOrNull { it.endpoint.ssid == currentSsid }?.let { return it }
        }
        return entries.first()
    }

    /** 人类可读的「多久之前成功过」（纯函数，日志用）。 */
    fun describeAge(
        savedAtMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val minutes = (nowMillis - savedAtMillis).coerceAtLeast(0) / 60_000
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            minutes < 24 * 60 -> "${minutes / 60} 小时前"
            else -> "${minutes / (24 * 60)} 天前"
        }
    }

    // ---- 存储（要 Context 的部分） ----

    /** 记录一次成功建链的端点。空 SSID / 无密码 / 合成端点标签一律不存。 */
    fun record(
        context: Context,
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (!isCacheable(endpoint)) return
        val store = store(context) ?: return
        val merged =
            merge(
                existing = decodeAll(store.getString(KEY, null)),
                entry = Entry(endpoint, nowMillis),
            )
        store.edit().putString(KEY, encodeAll(merged)).apply()
    }

    /** 候选列表（未过期，最近成功在前）。 */
    fun candidates(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<Entry> = fresh(decodeAll(store(context)?.getString(KEY, null)), nowMillis)

    /** 选一条可复用的记忆：[currentSsid] 命中优先，否则取最新的一条。 */
    fun pick(
        context: Context,
        currentSsid: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): Entry? = pick(candidates(context, nowMillis), currentSsid)

    /** 移除某个 SSID 的记忆（复用失败时调用，避免反复拿坏凭据撞墙）。 */
    fun forget(
        context: Context,
        ssid: String,
    ) {
        val store = store(context) ?: return
        val remaining = decodeAll(store.getString(KEY, null)).filterNot { it.endpoint.ssid == ssid }
        if (remaining.isEmpty()) {
            store.edit().remove(KEY).apply()
        } else {
            store.edit().putString(KEY, encodeAll(remaining)).apply()
        }
    }

    fun clear(context: Context) {
        store(context)?.edit()?.remove(KEY)?.apply()
    }

    /**
     * 能不能存。
     *
     * 合成的「当前 WiFi」端点（ssid 是 [CURRENT_WIFI_LABEL] 这类标签）必须排除——
     * 拿标签去入网必然失败；没密码的端点存下来也复用不了。
     */
    fun isCacheable(endpoint: Iap2SenderSession.CarPlayEndpoint): Boolean =
        endpoint.ssid.isNotEmpty() &&
            endpoint.passphrase.isNotEmpty() &&
            endpoint.ssid != CURRENT_WIFI_LABEL

    private fun store(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 合成端点用的假 SSID 标签。**唯一来源**，`SessionController` 直接引用这里。
     *
     * 真实 SSID 不可能以半角 `(` 开头（车机热点名都是字母数字），所以拿它当哨兵安全。
     */
    const val CURRENT_WIFI_LABEL = "(当前WiFi)"
}
