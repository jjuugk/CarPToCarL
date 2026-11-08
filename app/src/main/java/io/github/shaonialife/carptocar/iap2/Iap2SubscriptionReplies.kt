package io.github.shaonialife.carptocar.iap2

/**
 * 车机发起的 `Start…Updates` 订阅 → 手机必须回的那条状态消息。
 *
 * 规格约束（SPEC §2.8）：**每条订阅都要有人回一条对应状态，哪怕是空态**，
 * 否则发起订阅的一方约 10 秒后关掉控制通道。此前这四条应答只存在于 WiFi 隧道的
 * MIB3 形态（`VirtualIap2Link.maybeRespondControlCsmLocked`），蓝牙 bring-up 阶段
 * 收到同样订阅时只会被丢进「非预期帧暂存区」——车机白等。两条链路共用这一份定义，
 * 避免一侧改了参数布局、另一侧还在回旧字节。
 *
 * 报文体来源：`0x4158` 是奥迪 MIB3 Frida 抓包的逐字节常量（勿重新编码字段）；
 * `0x4155` / `0x4171` 与隧道侧既有的动态编码逐参数一致；`0x5201` 目前**只回空体**——
 * 真机没有 maneuver 样本，凭空编导航报文比不回更危险（见 SPEC §7「越权」条）。
 */
object Iap2SubscriptionReplies {
    const val COMMUNICATIONS_UPDATE = 0x4158
    const val CALL_STATE_UPDATE = 0x4155
    const val LIST_UPDATE = 0x4171
    const val ROUTE_GUIDANCE_UPDATE = 0x5201
    const val STOP_ROUTE_GUIDANCE_UPDATES = 0x5203

    /** 0x4155 里承载通话状态的参数号（MIB3 空态实测就是这个号，值 1 字节）。 */
    const val CALL_STATE_PARAM = 0x0002

    /** 订阅 ID → 应答 ID；不是订阅类消息返回 null。 */
    fun replyIdFor(startId: Int): Int? =
        when (startId) {
            Iap2MessageIds.START_COMMUNICATIONS_UPDATES -> COMMUNICATIONS_UPDATE
            Iap2MessageIds.START_CALL_STATE_UPDATES -> CALL_STATE_UPDATE
            Iap2MessageIds.START_LIST_UPDATES -> LIST_UPDATE
            Iap2MessageIds.START_ROUTE_GUIDANCE_UPDATES -> ROUTE_GUIDANCE_UPDATE
            else -> null
        }

    /** 0x4158 CommunicationsUpdate 空态（无 SIM/无信号场景的可用占位），完整 CSM 帧。 */
    internal val COMMUNICATIONS_UPDATE_IDLE: ByteArray =
        hex(
            "404000644158000500000100050001060005000200000e0004e697a053494de58da1" +
                "00000500050100050006010005000700000500080000050009000005000a000005" +
                "000b000005000c010005000d000005000e000005000f0000050010000005001100",
        )

    /**
     * 生成应答报文体。
     *
     * @param callState iAP2 CallState：0=无通话、1=来电、2=拨出、3=通话中；
     *   蓝牙侧由调用方注入真实状态，取不到就按空态 0。
     */
    fun idleBodyFor(
        replyId: Int,
        callState: Int = 0,
    ): ByteArray? =
        when (replyId) {
            COMMUNICATIONS_UPDATE ->
                COMMUNICATIONS_UPDATE_IDLE.copyOfRange(
                    Iap2Csm.HEADER_BYTES,
                    COMMUNICATIONS_UPDATE_IDLE.size,
                )

            CALL_STATE_UPDATE -> Iap2Csm.param(CALL_STATE_PARAM, byteArrayOf(callState.toByte()))
            LIST_UPDATE ->
                Iap2Csm.param(0x0000, byteArrayOf(0)) +
                    Iap2Csm.param(0x0002, byteArrayOf(0, 0)) +
                    Iap2Csm.param(0x0005, byteArrayOf(0)) +
                    Iap2Csm.param(0x0007, byteArrayOf(0, 0))
            // 没有 maneuver 数据源时不编造字段：空体是规范允许的最小「已应答」信号
            ROUTE_GUIDANCE_UPDATE -> ByteArray(0)
            else -> null
        }

    private const val HEX_DIGITS = "0123456789abcdef"

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            val hi = HEX_DIGITS.indexOf(clean[i * 2])
            val lo = HEX_DIGITS.indexOf(clean[i * 2 + 1])
            ((hi shl 4) or lo).toByte()
        }
    }
}
