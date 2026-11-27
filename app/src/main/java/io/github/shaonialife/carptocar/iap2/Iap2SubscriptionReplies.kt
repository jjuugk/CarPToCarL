package io.github.shaonialife.carptocar.iap2

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

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
    const val POWER_UPDATE = 0xAE01
    const val STOP_POWER_UPDATES = 0xAE02

    /** 0x4155 里承载通话状态的参数号（MIB3 空态实测就是这个号，值 1 字节）。 */
    const val CALL_STATE_PARAM = 0x0002

    /** `0x4170 StartListUpdates` 中与通话记录（Recents）对应的请求参数号（Properties/Max/Combine）。 */
    private val LIST_REQ_RECENTS_SELECTORS = setOf(0x0001, 0x0003, 0x0004)

    /** `0x4170 StartListUpdates` 中与收藏联系人（Favorites）对应的请求参数号（Properties/Max）。 */
    private val LIST_REQ_FAVORITES_SELECTORS = setOf(0x0006, 0x0008)
    /** 订阅 ID → 应答 ID；不是订阅类消息返回 null。 */
    fun replyIdFor(startId: Int): Int? =
        when (startId) {
            Iap2MessageIds.START_COMMUNICATIONS_UPDATES -> COMMUNICATIONS_UPDATE
            Iap2MessageIds.START_CALL_STATE_UPDATES -> CALL_STATE_UPDATE
            Iap2MessageIds.START_LIST_UPDATES -> LIST_UPDATE
            Iap2MessageIds.START_ROUTE_GUIDANCE_UPDATES -> ROUTE_GUIDANCE_UPDATE
            else -> null
        }

    /** 0x4158 CommunicationsUpdate 完整空态模板（未声明订阅子集时的兜底占位），完整 CSM 帧。 */
    internal val COMMUNICATIONS_UPDATE_IDLE: ByteArray =
        hex(
            "404000644158000500000100050001060005000200000e0004e697a053494de58da1" +
                "00000500050100050006010005000700000500080000050009000005000a000005" +
                "000b000005000c010005000d000005000e000005000f0000050010000005001100",
        )

    /** 预切分后的 `0x4158` 参数列表（paramId -> 完整 TLV 字节），按订阅子集组装时零重编码。 */
    private val communicationsIdleParams: List<Pair<Int, ByteArray>> =
        parseRawParams(
            COMMUNICATIONS_UPDATE_IDLE,
            startOffset = Iap2Csm.HEADER_BYTES,
        )

    /**
     * 手机电源状态快照（用于编码 iAP2 `0xAE01 PowerUpdate`）：
     * - [externalPowerMode]：param 4 ExternalAccessoryPowerMode（0=未外接供电、1=标准供电、2=高电流）
     * - [chargingState]：param 5 BatteryChargingState（0=未充电、1=充电中、2=已充满）
     * - [chargeLevel]：param 6 BatteryChargeLevel（0..100）
     */
    data class BatterySnapshot(
        val externalPowerMode: Int = 1,
        val chargingState: Int = 1,
        val chargeLevel: Int = 100,
    )

    /** 从系统粘性电池广播读取当前电量与充放电状态；无上下文（单测）或读取失败时回退默认快照。 */
    fun readBatterySnapshot(context: Context?): BatterySnapshot {
        val intent =
            context?.let {
                runCatching { it.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            } ?: return BatterySnapshot()
        return batterySnapshotFrom(
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
        )
    }

    internal fun batterySnapshotFrom(
        level: Int,
        scale: Int,
        status: Int,
        plugged: Int,
    ): BatterySnapshot {
        val chargeLevel =
            if (level >= 0 && scale > 0) {
                ((level * 100) / scale).coerceIn(0, 100)
            } else {
                100
            }
        val chargingState =
            when (status) {
                BatteryManager.BATTERY_STATUS_FULL -> 2
                BatteryManager.BATTERY_STATUS_CHARGING -> 1
                BatteryManager.BATTERY_STATUS_DISCHARGING,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                -> 0
                else -> if (plugged != 0) 1 else 0
            }
        val externalPowerMode = if (plugged != 0) 1 else 0
        return BatterySnapshot(
            externalPowerMode = externalPowerMode,
            chargingState = chargingState,
            chargeLevel = chargeLevel,
        )
    }

    /**
     * `0xAE00 StartPowerUpdates` 的标准应答（单帧 `0xAE01 PowerUpdate`，16B）：
     * param 4 = ExternalAccessoryPowerMode、param 5 = BatteryChargingState、
     * param 6 = BatteryChargeLevel。单帧合并三个参数，只占对端链路接收窗口 1 槽。
     */
    fun powerUpdateBody(battery: BatterySnapshot = BatterySnapshot()): ByteArray =
        Iap2Csm.u8(4, battery.externalPowerMode.coerceIn(0, 2)) +
            Iap2Csm.u8(5, battery.chargingState.coerceIn(0, 2)) +
            Iap2Csm.u16(6, battery.chargeLevel.coerceIn(0, 100))
    /**
     * 按 `0x4157 StartCommunicationsUpdates` 请求的参数子集构造 `0x4158 CommunicationsUpdate` 报文体。
     *
     * - `requestBody` 为空：返回完整空态模板（兼容不带参数清单的接收端）；
     * - `requestBody` 非空：严格只回被请求的已知参数；若载荷畸形或不含任何已知参数号，返回空体，
     *   绝不附带未订阅字段，避免严格校验字段集的接收端因越权参数断开控制通道。
     */
    fun communicationsUpdateBody(requestBody: ByteArray = ByteArray(0)): ByteArray {
        if (requestBody.isEmpty()) {
            return COMMUNICATIONS_UPDATE_IDLE.copyOfRange(
                Iap2Csm.HEADER_BYTES,
                COMMUNICATIONS_UPDATE_IDLE.size,
            )
        }
        val requestedIds = Iap2Csm.parseParams(requestBody).mapTo(HashSet()) { it.id }
        if (requestedIds.isEmpty()) return ByteArray(0)
        var totalBytes = 0
        for ((id, rawTlv) in communicationsIdleParams) {
            if (id in requestedIds) totalBytes += rawTlv.size
        }
        if (totalBytes == 0) return ByteArray(0)
        val out = ByteArray(totalBytes)
        var offset = 0
        for ((id, rawTlv) in communicationsIdleParams) {
            if (id in requestedIds) {
                System.arraycopy(rawTlv, 0, out, offset, rawTlv.size)
                offset += rawTlv.size
            }
        }
        return out
    }

    /**
     * 按 `0x4170 StartListUpdates` 订阅的列表类型构造精简 `0x4171 ListUpdate` 空态：
     * - `requestBody` 为空：返回 Recents 最小空态（param 0 = 0，5B）；
     * - 包含 Recents 选择参数（1/3/4）→ 附带 `param 0 = 0`；
     * - 包含 Favorites 选择参数（6/8）→ 附带 `param 5 = 0`；
     * - 非空请求不含上述任一列表选择参数 → 返回空体，不凭空编造未订阅列表。
     */
    fun listUpdateBody(requestBody: ByteArray = ByteArray(0)): ByteArray {
        if (requestBody.isEmpty()) {
            return Iap2Csm.param(0x0000, byteArrayOf(0))
        }
        val requestedIds = Iap2Csm.parseParams(requestBody).mapTo(HashSet()) { it.id }
        val wantsRecents = requestedIds.any { it in LIST_REQ_RECENTS_SELECTORS }
        val wantsFavorites = requestedIds.any { it in LIST_REQ_FAVORITES_SELECTORS }
        return when {
            wantsRecents && wantsFavorites ->
                Iap2Csm.param(0x0000, byteArrayOf(0)) + Iap2Csm.param(0x0005, byteArrayOf(0))
            wantsRecents ->
                Iap2Csm.param(0x0000, byteArrayOf(0))
            wantsFavorites ->
                Iap2Csm.param(0x0005, byteArrayOf(0))
            else -> ByteArray(0)
        }
    }

    /**
     * 生成应答报文体。
     *
     * @param callState iAP2 CallState：0=无通话、1=来电、2=拨出、3=通话中；
     *   蓝牙侧由调用方注入真实状态，取不到就按空态 0。
     * @param requestBody 车机发来的 `Start…Updates` 原始请求体；用于按订阅字段裁剪应答。
     */
    fun idleBodyFor(
        replyId: Int,
        callState: Int = 0,
        requestBody: ByteArray = ByteArray(0),
    ): ByteArray? =
        when (replyId) {
            COMMUNICATIONS_UPDATE -> communicationsUpdateBody(requestBody)
            CALL_STATE_UPDATE -> Iap2Csm.param(CALL_STATE_PARAM, byteArrayOf(callState.toByte()))
            LIST_UPDATE -> listUpdateBody(requestBody)
            // 没有 maneuver 数据源时不编造字段：空体是规范允许的最小「已应答」信号
            ROUTE_GUIDANCE_UPDATE -> ByteArray(0)
            else -> null
        }

    private fun parseRawParams(
        frame: ByteArray,
        startOffset: Int,
    ): List<Pair<Int, ByteArray>> {
        val result = ArrayList<Pair<Int, ByteArray>>()
        var offset = startOffset
        while (offset + 4 <= frame.size) {
            val length = ((frame[offset].toInt() and 0xFF) shl 8) or (frame[offset + 1].toInt() and 0xFF)
            if (length < 4 || offset + length > frame.size) break
            val id = ((frame[offset + 2].toInt() and 0xFF) shl 8) or (frame[offset + 3].toInt() and 0xFF)
            result.add(id to frame.copyOfRange(offset, offset + length))
            offset += length
        }
        return result
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
