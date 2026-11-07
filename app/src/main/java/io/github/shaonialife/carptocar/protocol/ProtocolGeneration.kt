package io.github.shaonialife.carptocar.protocol

import android.content.Context
import io.github.shaonialife.carptocar.iap2.MfiLengthMemory
import io.github.shaonialife.carptocar.iap2.ReceiverProfile

/**
 * **每台车机的协议代际快照**：记住上一轮看到的「AirPlay 时代 + WiFi 凭据代际」，
 * 下一轮若变了就说明车机侧换了协议（最常见：固件升级后从旧代 0x5702/0x5703
 * 切到新代 0x4300/0x4301，或反过来回退）。
 *
 * 为什么值得单独记：本项目有多份**按车机持久化**的试错记忆
 * （[MfiLengthMemory] 的挑战长度档、`AvailabilityBodyMemory` 的 0x4300 报文体），
 * 它们是「上一代协议下的最优解」。协议换代后这些结论可能整体失效——旧档继续生效
 * 会把新一轮死死按在老参数上。因此换代时主动 [MfiLengthMemory.forget] 作废旧档，
 * 并把「为什么作废」写进日志与审计清单，而不是让用户面对「换固件后突然连不上」。
 *
 * 判定规则（与 [ReceiverProfile] 的「按能力不按品牌」一脉相承）：
 *  - **UNKNOWN 不参与比较**：信号补齐（null/UNKNOWN → 已知）不是变更，是这一轮信息更全；
 *  - 只有「已知 → 已知且不同」才算变更，并且**两个字段各自独立**判（era 变、wifiGen 不变
 *    的情况真实存在：同一台车只改了 AirPlay 特性位）。
 *
 * 没有 Context（单测/纯 JVM）时退化成进程内表，行为与真机一致，只是不跨进程存活。
 */
object ProtocolGeneration {
    /** 一次观察到的代际组合。字段用字符串存，便于直接落 SharedPreferences。 */
    data class Snapshot(
        val era: String,
        val wifiGen: String,
        val srcvers: String? = null,
        val featuresHex: String? = null,
    ) {
        val known: Boolean get() = isKnown(era) || isKnown(wifiGen)
    }

    /** 一次变更（老快照 → 新快照）。字段级标志在 [observe] 里算好，避免「信号补齐」被当成变更。 */
    data class Change(
        val from: Snapshot,
        val to: Snapshot,
        val eraChanged: Boolean,
        val wifiChanged: Boolean,
    )

    private const val FILE = "protocol_generation"
    private const val KEY = "last_"
    private const val SEP = '|'

    /** 进程内退化表（无 Context 时使用）。 */
    private val memory = HashMap<String, Snapshot>()

    /**
     * 观察一次代际信号。
     *
     * @return 本轮发生变更时返回 [Change]；首次观察、无变化、信号仍是 UNKNOWN 时返回 null
     *
     * 副作用：把合并后的快照写回存储（UNKNOWN 字段用旧值补齐，避免下一轮把
     * 「mDNS 先到、0x1D01 后到」的到达顺序误判成换代）。
     */
    fun observe(
        context: Context?,
        identityKey: String,
        profile: ReceiverProfile.Profile,
        srcvers: String? = null,
        features: Long? = null,
    ): Change? {
        if (identityKey.isBlank() || identityKey == "unknown") return null
        val key = MfiLengthMemory.receiverKey(identityKey)
        val incoming =
            Snapshot(
                era = profile.era.name,
                wifiGen = profile.wifiGen.name,
                srcvers = srcvers,
                featuresHex = features?.let { "0x" + it.toString(16) },
            )
        val stored = read(context, key)
        val merged = merge(stored, incoming)
        if (merged != stored) store(context, key, merged)
        if (stored == null || !incoming.known) return null
        // 只有「已知 → 已知且不同」才算换代。UNKNOWN → 已知是**信号补齐**（0x1D01 与
        // mDNS 到达顺序不同，先到的那一侧必然带着 UNKNOWN），把它当变更会让每台车
        // 第一次连上都误报「协议代际变更」并白白清掉 MFi 记忆。
        val eraChanged = isKnown(stored.era) && isKnown(incoming.era) && stored.era != incoming.era
        val wifiChanged = isKnown(stored.wifiGen) && isKnown(incoming.wifiGen) && stored.wifiGen != incoming.wifiGen
        if (!eraChanged && !wifiChanged) return null
        return Change(
            from = stored,
            to = merged,
            eraChanged = eraChanged,
            wifiChanged = wifiChanged,
        )
    }

    /** 一行可读描述（进日志）。只列**真的变了**的字段，避免把没变的旧值也读成结论。 */
    fun describe(change: Change): String {
        val parts =
            buildList {
                if (change.eraChanged) add("AirPlay 时代 ${change.from.era} → ${change.to.era}")
                if (change.wifiChanged) add("WiFi 凭据 ${change.from.wifiGen} → ${change.to.wifiGen}")
            }
        val fromSrc =
            change.from.srcvers
                ?.let { "（上轮 srcvers=$it）" }
                .orEmpty()
        val toSrc =
            change.to.srcvers
                ?.let { "（本轮 srcvers=$it）" }
                .orEmpty()
        return "协议代际变更：${parts.joinToString("；")}$fromSrc$toSrc" +
            "——按新一代协议重新分派，并作废该车机的旧 MFi 档位记忆"
    }

    /** 清空（仅单测用；真机记忆要靠换代自然覆盖）。 */
    fun reset() {
        synchronized(memory) { memory.clear() }
    }

    // ---- 内部 ----

    /** UNKNOWN 不覆盖已知：信号补齐不是换代，丢弃信息才是。 */
    private fun merge(
        stored: Snapshot?,
        incoming: Snapshot,
    ): Snapshot {
        if (stored == null) return incoming
        return Snapshot(
            era = pick(stored.era, incoming.era),
            wifiGen = pick(stored.wifiGen, incoming.wifiGen),
            srcvers = incoming.srcvers ?: stored.srcvers,
            featuresHex = incoming.featuresHex ?: stored.featuresHex,
        )
    }

    private fun pick(
        stored: String,
        incoming: String,
    ): String = if (isKnown(incoming)) incoming else stored

    private fun isKnown(value: String): Boolean = value.isNotEmpty() && value != "UNKNOWN"

    private fun read(
        context: Context?,
        key: String,
    ): Snapshot? {
        val raw =
            if (context == null) {
                synchronized(memory) { memory[key] }
                    ?.let { it.era + SEP + it.wifiGen + SEP + (it.srcvers ?: "") + SEP + (it.featuresHex ?: "") }
            } else {
                prefs(context).getString(KEY + key, null)
            }
        val parts = raw?.split(SEP) ?: return null
        if (parts.size < 2) return null
        return Snapshot(
            era = parts[0].ifBlank { "UNKNOWN" },
            wifiGen = parts[1].ifBlank { "UNKNOWN" },
            srcvers = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
            featuresHex = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
        )
    }

    private fun store(
        context: Context?,
        key: String,
        snapshot: Snapshot,
    ) {
        if (context == null) {
            synchronized(memory) { memory[key] = snapshot }
            return
        }
        prefs(context)
            .edit()
            .putString(
                KEY + key,
                snapshot.era + SEP + snapshot.wifiGen + SEP + (snapshot.srcvers ?: "") + SEP + (snapshot.featuresHex ?: ""),
            ).apply()
    }

    private fun prefs(context: Context): android.content.SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
