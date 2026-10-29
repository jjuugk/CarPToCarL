package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.net.AirPlayFeatures

/**
 * **车机画像**：把散落的版本/能力信号收口成一份可解释的判定。
 *
 * 信号来自三处、到达时机不同：0x1D01 身份帧（蓝牙 bring-up 早期）、mDNS/`/info` 的
 * features 与 srcvers（端点解析期）、实车行为（挑战长度/拆链特征，走既有 Memory 学习）。
 * 每次有新信号到达都可重算——[classify] 是纯函数，同一输入恒同一输出。
 *
 * 两条铁律（HID 描述符教训的推广）：
 *  1. **按能力分支，不按版本号/品牌硬编码**——srcvers/features 只做先验提示，
 *     真正分派以能力清单（收/发清单、audioFormats、hidDescriptors）与实测行为为准；
 *  2. **判定必须可解释**——[describe] 把依据写进日志，判错时一眼能看出是哪条信号误导。
 */
object ReceiverProfile {
    /** 三时代（对照 catplay 分类，§9f）。UNKNOWN = 信号缺失，保持旧路径不激进。 */
    enum class Era { PRE_HOMEKIT, OPTIONAL_PAIRING, MODERN, UNKNOWN }

    /** WiFi 凭据交换协议代际（PR #10 的分派依据，收口进画像）。 */
    enum class WifiGen { LEGACY, NEW, UNKNOWN }

    /** 画像输入：全字段可空——信号到多少算多少。 */
    data class Signals(
        val name: String? = null,
        val model: String? = null,
        val manufacturer: String? = null,
        /** AirPlay features 位图（mDNS TXT / /info）；null = 未取到。 */
        val features: Long? = null,
        /** AirPlay 栈版本（mDNS TXT srcvers / 0x4301 sourceVersion）。 */
        val srcvers: String? = null,
        /** 0x1D01 参数 6：车机声明「会发送」的消息号清单；null = 未提供。 */
        val sendIds: Set<Int>? = null,
        /** 0x1D01 参数 7：车机声明「可接收」的消息号清单；null = 未提供。 */
        val receiveIds: Set<Int>? = null,
    )

    data class Profile(
        val era: Era,
        val wifiGen: WifiGen,
        /** 身份键（与 MfiLengthMemory/AudioProfileMemory 同源，后续 Memory 并表用）。 */
        val identityKey: String,
        /** 判定依据（可解释性：每条结论对应的信号原文）。 */
        val reasons: List<String>,
    )

    fun classify(signals: Signals): Profile {
        val reasons = ArrayList<String>()
        val pairing = AirPlayFeatures.supportsPairing(signals.features)
        val era =
            when {
                pairing == null -> Era.UNKNOWN
                !pairing -> {
                    reasons += "features(${signals.features?.let { "0x${it.toString(16)}" } ?: "?"}) 无 bit38=HK_PAIRING_AND_ENCRYPT"
                    Era.PRE_HOMEKIT
                }

                else -> {
                    reasons += "features bit38=HK_PAIRING_AND_ENCRYPT 已声明"
                    Era.MODERN
                }
            }
        if (signals.srcvers != null) reasons += "srcvers=${signals.srcvers}"
        if (era != Era.PRE_HOMEKIT && signals.srcvers != null) {
            // 先验提示（不改变判定）：老版本号 + 未声明配对 → v280 可选配对档
            val v = signals.srcvers.toDoubleOrNull()?.let { (it * 100).toInt() } ?: 0
            if (v in 1..36599 && era == Era.UNKNOWN) reasons += "srcvers 提示 v280 可选配对档（仅先验）"
        }

        val wifiGen =
            when {
                signals.sendIds == null && signals.receiveIds == null -> {
                    reasons += "0x1D01 未提供收/发清单"
                    WifiGen.UNKNOWN
                }

                signals.sendIds?.contains(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION) == true ||
                    signals.receiveIds?.contains(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION) == true -> {
                    reasons += "清单声明 0x5703/0x5702（旧协议在场）"
                    WifiGen.LEGACY
                }

                (signals.sendIds?.size ?: 0) + (signals.receiveIds?.size ?: 0) >= 10 -> {
                    // 清单齐且相当完整仍无 0x5703/0x5702——BMW iD9 形态（两清单均 10+ 项）。
                    // 不能仅凭「未声明」判新：奥迪清单只列 CarPlay 生命周期消息（实测
                    // 发送清单 2 项/可收 5 项，均无 0x57xx），但旧协议工作正常——
                    // 稀疏清单不算数，保守 UNKNOWN 走旧协议（0x5703 有超时兜底）
                    reasons += "清单完整（${(signals.sendIds?.size ?: 0) + (signals.receiveIds?.size ?: 0)} 项）仍无 0x5703/0x5702——AP 信息由 0x4301 提供"
                    WifiGen.NEW
                }

                (signals.sendIds != null || signals.receiveIds != null) -> {
                    // **不要再试图用「声明了 0x4300/0x4301」来判 NEW**（2026-10-28 踩过的坑）：
                    // 奥迪 3315 的可收清单含 0x4300、发送清单含 0x4301，但它走的是旧协议
                    // 0x5702→0x5703（实测毫秒级应答）；大众车机两份清单同样含 0x4300/0x4301
                    // （合计 9 项），却是新版协议、对 0x5702 只回链路 ACK 不再说话。
                    // 两者的清单形态几乎一致，**清单本身分不开**——按声明判 NEW 会让奥迪
                    // 跳过 0x5702 直接发 0x4300，而奥迪对 0x4300 只回 ACK 不出 0x4301，等于自杀。
                    // 因此这里保持 UNKNOWN，改由发送端「两条请求并行发、谁先回用谁」兜底
                    // （Iap2SenderSession 的并行兜底）。
                    reasons += "清单稀疏且未声明 0x5703/0x5702——不构成「新协议」证据，保守按旧协议（超时兜底）"
                    WifiGen.UNKNOWN
                }

                else -> WifiGen.UNKNOWN
            }
        return Profile(era, wifiGen, identityKeyOf(signals), reasons)
    }

    /** 身份原文（name+model+manufacturer，与 0x1D01 逐字一致；空档跳过）。 */
    fun identityKeyOf(signals: Signals): String =
        listOfNotNull(
            signals.name?.takeIf { it.isNotBlank() },
            signals.model?.takeIf { it.isNotBlank() },
            signals.manufacturer?.takeIf { it.isNotBlank() },
        ).joinToString(" ").ifBlank { "unknown" }

    /** 一行可读判定（进文件日志）。 */
    fun describe(profile: Profile): String =
        "PROFILE: era=${profile.era} wifiGen=${profile.wifiGen} key=${MfiLengthMemory.receiverKey(profile.identityKey)}" +
            " 依据[" + profile.reasons.joinToString("；") + "]"
}
