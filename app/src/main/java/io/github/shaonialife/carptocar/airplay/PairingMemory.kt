package io.github.shaonialife.carptocar.airplay

import android.content.Context

/**
 * 一次「车机确定性拒绝配对」的记忆。
 *
 * [atMillis] 用 `System.currentTimeMillis()`（要跨进程重启，不能用 elapsedRealtime）。
 */
data class PairingRejection(
    val receiverKey: String,
    val stage: PairingStage,
    val rawCode: Int,
    val atMillis: Long,
) {
    fun expired(
        nowMillis: Long,
        ttlMillis: Long = PairingMemory.TTL_MS,
    ): Boolean = nowMillis - atMillis >= ttlMillis

    /** 单行存储：`key|STAGE|raw|毫秒`。分隔符不会出现在 key 里（见 [PairingMemory.receiverKey]）。 */
    fun encode(): String = listOf(receiverKey, stage.name, rawCode.toString(), atMillis.toString()).joinToString("|")

    companion object {
        fun decode(raw: String?): PairingRejection? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split('|')
            if (parts.size != 4) return null
            val stage = PairingStage.entries.firstOrNull { it.name == parts[1] } ?: return null
            val code = parts[2].toIntOrNull() ?: return null
            val at = parts[3].toLongOrNull() ?: return null
            return PairingRejection(parts[0], stage, code, at)
        }
    }
}

/** 本轮该不该进配对阶段。 */
enum class PairingGate {
    /** 正常走 pair-setup → pair-verify。 */
    NORMAL,

    /** 短期记忆命中：跳过 pair-setup，直接明文会话（省掉一整轮注定失败的 120s）。 */
    SKIP_SETUP,
}

/**
 * 配对拒绝的短期记忆（按接收端存一条，投递在 SharedPreferences）。
 *
 * 解决的是一类**纯等待**：车机确定性拒绝配对（见 [PairingPolicy.isDeterministic]）时，
 * 我们仍会在 120s 配对窗口里每 2s 重试一次，最后照样降级明文会话。用户感受到的是
 * 「每次上车都要多等两分钟」，而这两分钟里车机每一次都回同一个错误码。
 *
 * 三条自我纠正机制，保证记忆不会把本来能连的车机搞坏：
 *  1. 有效期 [TTL_MS]，过期自动作废（车机侧删了配对记录、或它自己的状态机复位后即可重试）；
 *  2. 只跳过 pair-setup，**不跳过明文 SETUP**；明文 SETUP 若被拒，调用方仍会补做配对
 *     （`retryWithPairing`），所以「其实能配对」的车机最多多花一次明文 SETUP 的时间；
 *  3. 任何一次配对成功都立刻清除记忆。
 */
object PairingMemory {
    /** 15 分钟：够覆盖一次上车到下次上下车，又不至于把状态变化挡太久。 */
    const val TTL_MS = 15 * 60_000L

    private const val KEY = "carplay_pairing_rejection_v1"

    /**
     * 接收端身份键：优先配对公钥（最能区分「哪台车机」），退到 deviceid，再退到 SSID。
     *
     * 不拿 IP 当键：车机热点每轮可能换地址（0x4301 与 mDNS 常给不同族/不同值），
     * 用地址做键会让记忆永远命中不了、或者命中到别的设备上。
     */
    fun receiverKey(
        publicKey: String?,
        deviceIdentifier: String?,
        ssid: String,
    ): String =
        publicKey?.takeIf { it.isNotBlank() }
            ?: deviceIdentifier?.takeIf { it.isNotBlank() }
            ?: ssid

    /** 纯决策（单测覆盖）：记忆是否命中、是否过期。 */
    fun decide(
        rejection: PairingRejection?,
        receiverKey: String,
        nowMillis: Long,
        ttlMillis: Long = TTL_MS,
    ): PairingGate {
        if (rejection == null) return PairingGate.NORMAL
        if (rejection.receiverKey != receiverKey) return PairingGate.NORMAL
        if (rejection.expired(nowMillis, ttlMillis)) return PairingGate.NORMAL
        val code = PairingErrorCode.of(rejection.rawCode)
        if (!PairingPolicy.isDeterministic(rejection.stage, code)) return PairingGate.NORMAL
        return PairingGate.SKIP_SETUP
    }

    fun read(context: Context): PairingRejection? =
        PairingRejection.decode(store(context)?.getString(KEY, null))

    fun record(
        context: Context,
        receiverKey: String,
        stage: PairingStage,
        rawCode: Int,
    ) {
        val rejection = PairingRejection(receiverKey, stage, rawCode, System.currentTimeMillis())
        store(context)?.edit()?.putString(KEY, rejection.encode())?.apply()
    }

    fun clear(context: Context) {
        store(context)?.edit()?.remove(KEY)?.apply()
    }

    private fun store(context: Context) =
        context.applicationContext.getSharedPreferences("carplay_pairing", Context.MODE_PRIVATE)
}
