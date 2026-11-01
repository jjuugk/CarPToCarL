package io.github.shaonialife.carptocar.airplay

import java.io.IOException

/**
 * HomeKit 配对协议（`/pair-setup`、`/pair-verify`）的失败语义。
 *
 * 为什么要有这个文件：配对应答里的 `TYPE_ERROR`（TLV 0x07）从 1.0 起就一直被丢掉了。
 * 两处具体表现（2026-10-30 日志 221747 定谳）：
 *
 *  - `pair-setup` M4 回 `{Error:2, State:4}` 时，代码只找 `TYPE_PROOF`，找不到就报
 *    「M4 缺少 proof」——真实原因（车机拒绝认证）在日志里**一次都没出现过**；
 *  - `pair-verify` M4 回同样的 `{Error:2, State:4}` 时更糟：代码只判 `State == 4`
 *    （正常成功标记），Error 被完全忽略 → **把拒绝读成成功**，派生出两把对不上的
 *    密钥，加密 SETUP 必然被车机静默丢弃（10s 零应答），再降级明文会话——此时没有
 *    共享密钥，音频/触摸整条链路被跳过。用户看到的就是「有画面、一直没声音」。
 *
 * 所以约定：**每个应答先看 Error，再看 State**，并且错误码要一路传到调用方能做决策。
 */
enum class PairingStage(
    val path: String,
    val label: String,
) {
    PAIR_SETUP("/pair-setup", "pair-setup"),
    PAIR_VERIFY("/pair-verify", "pair-verify"),
}

/** HomeKit TLV 错误码（规范里的 `kTLVError_*`，只收线上真实出现过的取值）。 */
enum class PairingErrorCode(
    val code: Int,
    val label: String,
) {
    UNKNOWN(0x01, "未知错误"),
    AUTHENTICATION(0x02, "认证失败"),
    BACKOFF(0x03, "对端要求退避"),
    MAX_PEERS(0x04, "配对数已达上限"),
    MAX_TRIES(0x05, "尝试次数已用尽"),
    UNAVAILABLE(0x06, "暂时不可用"),
    BUSY(0x07, "对端忙"),
    ;

    companion object {
        /** 未知取值（规范外的私有码）返回 null，调用方按「可重试」处理。 */
        fun of(raw: Int?): PairingErrorCode? = entries.firstOrNull { it.code == raw }
    }
}

/**
 * 接收端明确回了错误码的配对失败（区别于「没有应答」的网络层失败）。
 *
 * 携带 [state] 是因为真机上 Error 与 State 会同时出现（`{Error:2, State:4}`），
 * 只留错误码会把现场丢掉一半。
 */
class PairingRejectedException(
    val stage: PairingStage,
    val code: PairingErrorCode?,
    val rawCode: Int,
    val state: Int?,
) : IOException(
        "${stage.label} 被接收端拒绝：Error=$rawCode" +
            (code?.let { "（${it.label}）" } ?: "（规范外私有码）") +
            (state?.let { "，State=$it" } ?: ""),
    ) {
    /** 同一个错误再试一百次也是同样结果（见 [PairingPolicy.isDeterministic]）。 */
    val deterministic: Boolean get() = PairingPolicy.isDeterministic(stage, code)
}

/** 一轮配对失败的定性结果，用于日志与界面提示。 */
data class PairingTrouble(
    val stage: PairingStage,
    val code: PairingErrorCode?,
    val rawCode: Int,
    /** 确定性失败：本轮不该再重试同一个阶段。 */
    val deterministic: Boolean,
    /** 给用户看的一句话（做什么才能好）。 */
    val advice: String,
) {
    val text: String get() = "${stage.label} Error=$rawCode" + (code?.let { "（${it.label}）" } ?: "")
}

/**
 * 纯策略：把「哪个阶段 + 哪个错误码」翻译成「接下来该怎么办」。
 *
 * 判定原则只有一条：**这个错误是「这台车此刻的状态」，还是「这次尝试的抖动」**。
 * 前者重试毫无意义（车机会一直回同一个码），后者必须重试（端口晚开、忙、退避）。
 */
object PairingPolicy {
    /**
     * 确定性失败：重试只会白白消耗车机的重邀窗口。
     *
     * - AUTHENTICATION：车机不认这个控制器身份（已配过别的手机 / 删过我们的记录 /
     *   根本不接受匿名 SRP）。实测这类车机**每次**都回 Error=2，退避多久都一样。
     * - MAX_PEERS：车机侧已满，用户得先在车机上删一台设备。
     * - MAX_TRIES：车机把尝试次数用完了，只有它自己的计时器能解除。
     *
     * 注意 pair-setup 的 AUTHENTICATION 有一点特殊：车机可能**已经保存了我们的公钥**
     * （所以不会再走一遍 SRP 注册），此时正确动作不是放弃，而是改走「已配对快路径」
     * 直接 pair-verify——与 403 同义（见 [shouldTryPairedFastPath]）。
     */
    fun isDeterministic(
        stage: PairingStage,
        code: PairingErrorCode?,
    ): Boolean =
        when (code) {
            PairingErrorCode.AUTHENTICATION,
            PairingErrorCode.MAX_PEERS,
            PairingErrorCode.MAX_TRIES,
            -> true
            // BACKOFF 是「等会再来」而不是「别来了」；UNAVAILABLE/BUSY/UNKNOWN
            // 都可能自己恢复（车机重新开放 AirPlay 窗口时端口状态就是抖的）。
            else -> false
        }

    /**
     * pair-setup 被拒后是否值得直接改走 pair-verify。
     *
     * 与 403 的结论一致（2026-10-21 真机 sysdiagnose）：车机拒绝的是「再来注册一次」，
     * 而不是「这台手机不能连」。pair-verify 才是已配对设备的正常路径。
     */
    fun shouldTryPairedFastPath(
        stage: PairingStage,
        code: PairingErrorCode?,
    ): Boolean = stage == PairingStage.PAIR_SETUP && code == PairingErrorCode.AUTHENTICATION

    /** 是否值得「等一会再试同一个阶段」。 */
    fun shouldRetrySameStage(code: PairingErrorCode?): Boolean =
        code == null || code in setOf(PairingErrorCode.BACKOFF, PairingErrorCode.BUSY, PairingErrorCode.UNAVAILABLE)

    /** 给用户的一句话建议（日志与界面共用，避免两处文案漂移）。 */
    fun advice(
        stage: PairingStage,
        code: PairingErrorCode?,
    ): String =
        when {
            code == PairingErrorCode.AUTHENTICATION && stage == PairingStage.PAIR_VERIFY ->
                "车机不认这台手机的配对身份：到车机「已连接设备/手机列表」里删掉本机再重连，" +
                    "或在本机「断开」后重新配对。在此之前只能用明文会话（无音频、无触摸）"
            code == PairingErrorCode.AUTHENTICATION ->
                "车机拒绝了本次配对：先试「已配对设备」快路径；仍失败就去车机侧删掉本机的配对记录"
            code == PairingErrorCode.MAX_PEERS ->
                "车机保存的设备已达上限：到车机上删掉一台旧设备后重连"
            code == PairingErrorCode.MAX_TRIES ->
                "车机的配对尝试次数已用尽：等它自己解除（通常几分钟），期间可继续用明文会话"
            code == PairingErrorCode.BACKOFF || code == PairingErrorCode.BUSY ->
                "车机暂时不接受配对：已按退避重试，无需操作"
            else ->
                "配对失败：车机上重新点一次本机图标；若持续失败请导出日志"
        }
}
