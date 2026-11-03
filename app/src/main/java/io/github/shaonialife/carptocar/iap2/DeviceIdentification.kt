package io.github.shaonialife.carptocar.iap2

/**
 * 手机侧（iAP2 **device**）的 `0x1D01 IdentificationInformation` 构造。
 *
 * ## 为什么需要它
 *
 * 我们的 bring-up 一直是「发 0x1D00 → 收车机的 0x1D01 → 回 0x1D02」，
 * **从不发自己的识别帧**。奥迪（Delphi 代工）、别克（SAIC-GM/Cinemo）、
 * 一汽大众探歌（老 MQB）都容忍这一点，所以一直没暴露问题。
 *
 * 但大众 MEB **ICAS3** 用的是**苹果原厂栈**（`AppleCarPlay_CommunicationPlugin 320.17.7`
 * + `libiAP2Service-3.0.so`），它对识别阶段的要求是双向的：车机要收到手机的
 * `0x1D01`（含**自己可收/可发的消息位图**）才会往下走，否则不发 `0x4301`。
 * 见 `docs/vw-id-icas3.md` 第 3 节（报告 15.2 节第 4 条）。
 *
 * 所以这个构造只给「大众 ID」档用：**默认档位不发**，既有车机的流程一个字节都没动。
 *
 * ## 参数表（与解析侧完全同源）
 *
 * 参数 ID 取自本仓库解析 `0x1D01` 的那段代码（`Iap2SenderSession.logIdentification`
 * / `parseEaProtocols`），保证「能发出去的」与「能读回来的」是同一套定义：
 *
 * | paramID | 含义 | 类型 |
 * | --- | --- | --- |
 * | 0 | 名称 | NUL 结尾字符串 |
 * | 1 | 型号 | NUL 结尾字符串 |
 * | 2 | 制造商 | NUL 结尾字符串 |
 * | 6 | 本方**会发送**的消息 ID | u16[] |
 * | 7 | 本方**可接收**的消息 ID | u16[] |
 * | 10/11/12 | ExternalAccessory 协议组件 | group(u8 序号 + string 名 + u8 0) |
 *
 * ## 已知缺口（**别在没证据时补**）
 *
 * 报告说识别帧里要带「CarPlay 外部配件协议名」，但两份材料都没给出**字面量**
 * （ICAS3 固件里只有车机侧的 `com.audi.*`/`de.audi.*` 那三条奥迪串）。凭空写一个协议名
 * 比不写更糟：车机会判定「声称支持但握手对不上」。因此 [Spec.eapProtocols] 默认为空，
 * 位置留在参数表里——等一次真 iPhone 的 `0x1D01` 抓包（或 sysdiagnose）填进去即可。
 */
internal object DeviceIdentification {
    // ---- 参数 ID（与解析侧一致）----
    const val P_NAME = 0
    const val P_MODEL = 1
    const val P_MANUFACTURER = 2
    const val P_MESSAGES_SENT = 6
    const val P_MESSAGES_RECEIVED = 7

    /** EAP 组件占用的参数组（车机侧用 10/11/12 声明三条协议，我方同构）。 */
    val EAP_GROUPS = intArrayOf(10, 11, 12)

    /** 我方自称的型号。车机只用它决定 HMI 图标/名字，不参与任何协议判定。 */
    const val MODEL = "iPhone14,5"

    /** 我方自称的制造商——ICAS3 的识别页按这个字段认「是 iPhone」。 */
    const val MANUFACTURER = "Apple Inc."

    /**
     * 识别帧内容。
     *
     * @param name 设备名（取本机蓝牙名，真 iPhone 就是「xxx 的 iPhone」）
     * @param messagesSent 本方会**主动发**的消息 ID
     * @param messagesReceived 本方**接受**的消息 ID
     * @param eapProtocols `序号 to 协议名`；空 = 不声明（见类注释的「已知缺口」）
     */
    data class Spec(
        val name: String,
        val model: String = MODEL,
        val manufacturer: String = MANUFACTURER,
        val messagesSent: IntArray,
        val messagesReceived: IntArray,
        val eapProtocols: List<Pair<Int, String>> = emptyList(),
    ) {
        // data class 里有 IntArray：equals/hashCode 默认按引用比，单测里会被坑，显式重写
        override fun equals(other: Any?): Boolean =
            other is Spec &&
                other.name == name &&
                other.model == model &&
                other.manufacturer == manufacturer &&
                other.messagesSent.contentEquals(messagesSent) &&
                other.messagesReceived.contentEquals(messagesReceived) &&
                other.eapProtocols == eapProtocols

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + model.hashCode()
            result = 31 * result + manufacturer.hashCode()
            result = 31 * result + messagesSent.contentHashCode()
            result = 31 * result + messagesReceived.contentHashCode()
            result = 31 * result + eapProtocols.hashCode()
            return result
        }
    }

    /**
     * 拼出 `0x1D01` 的**参数体**（不含消息头，消息头由 `Iap2Csm.encode` 加）。
     *
     * 参数顺序固定为 0 → 1 → 2 → 6 → 7 → (10..12)：TLV 无顺序要求，但固定顺序让
     * 日志里同一台车的帧可以逐字节对比。两张消息位图在写出前**升序排列**——
     * 调用方怎么给都行，线上永远是同一串字节（否则每次改动清单都会产生无意义 diff）。
     */
    fun body(spec: Spec): ByteArray {
        val parts = ArrayList<ByteArray>(8)
        parts += Iap2Csm.string(P_NAME, spec.name)
        parts += Iap2Csm.string(P_MODEL, spec.model)
        parts += Iap2Csm.string(P_MANUFACTURER, spec.manufacturer)
        if (spec.messagesSent.isNotEmpty()) {
            parts += Iap2Csm.u16List(P_MESSAGES_SENT, spec.messagesSent.sortedArray())
        }
        if (spec.messagesReceived.isNotEmpty()) {
            parts += Iap2Csm.u16List(P_MESSAGES_RECEIVED, spec.messagesReceived.sortedArray())
        }
        spec.eapProtocols.take(EAP_GROUPS.size).forEachIndexed { index, (protocolId, protocolName) ->
            parts +=
                Iap2Csm.group(
                    EAP_GROUPS[index],
                    Iap2Csm.u8(0, protocolId),
                    Iap2Csm.string(1, protocolName),
                    Iap2Csm.u8(2, 0),
                )
        }
        return parts.reduce { acc, bytes -> acc + bytes }
    }

    /**
     * 我方真实能力的默认清单。
     *
     * **只列本仓库真的会收/发的消息**——识别帧是「自我声明」，把没实现的能力写进去，
     * 车机会按声明派活（例如声明可收 0x5200 却不回数据，仪表上的导航卡片会空转）。
     * 与 `Iap2SenderSession` 里的实际调用点一一对应，改动其一必须同步这里。
     */
    fun defaultSpec(name: String): Spec =
        Spec(
            name = name,
            messagesSent =
                intArrayOf(
                    // 识别与链路
                    Iap2MessageIds.START_IDENTIFICATION,
                    Iap2MessageIds.IDENTIFICATION_INFORMATION,
                    Iap2MessageIds.IDENTIFICATION_ACCEPTED,
                    // CarPlay 可用性 + 无线握手
                    Iap2MessageIds.CARPLAY_AVAILABILITY,
                    Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION,
                    Iap2MessageIds.WIRELESS_CARPLAY_UPDATE,
                    Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER,
                    // 设备问候组
                    Iap2MessageIds.DEVICE_INFORMATION_UPDATE,
                    Iap2MessageIds.DEVICE_LANGUAGE_UPDATE,
                    Iap2MessageIds.DEVICE_TIME_UPDATE,
                    Iap2MessageIds.DEVICE_UUID_UPDATE,
                    // 媒体与 MFi
                    Iap2MessageIds.NOW_PLAYING_UPDATE,
                    Iap2MessageIds.MFI_REQUEST_CERTIFICATE,
                    Iap2MessageIds.MFI_REQUEST_CHALLENGE,
                    Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED,
                    // EA 会话（MIB3 车机控制那条隧道；ICAS3 上也声明，失败无副作用）
                    Iap2MessageIds.EA_OPEN_SESSION,
                    Iap2MessageIds.EA_CLOSE_SESSION,
                ),
            messagesReceived =
                intArrayOf(
                    // 识别结果
                    Iap2MessageIds.IDENTIFICATION_INFORMATION,
                    Iap2MessageIds.IDENTIFICATION_ACCEPTED,
                    Iap2MessageIds.IDENTIFICATION_REJECTED,
                    Iap2MessageIds.IDENTIFICATION_UPDATE,
                    // CarPlay 端点
                    Iap2MessageIds.CARPLAY_START_SESSION,
                    Iap2MessageIds.CARPLAY_START_SESSION_STATUS,
                    // 凭据与订阅
                    Iap2MessageIds.REQUEST_WIFI_INFORMATION,
                    Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION,
                    Iap2MessageIds.START_NOW_PLAYING_UPDATES,
                    Iap2MessageIds.STOP_NOW_PLAYING_UPDATES,
                    Iap2MessageIds.START_HID,
                    Iap2MessageIds.ACCESSORY_HID_REPORT,
                    Iap2MessageIds.STOP_HID,
                    // MFi
                    Iap2MessageIds.MFI_CERTIFICATE,
                    Iap2MessageIds.MFI_RESPONSE,
                    Iap2MessageIds.MFI_AUTH_SERIAL_NUMBER,
                    // 车机侧订阅（我们只记录，不应答也安全）
                    Iap2MessageIds.START_CALL_STATE_UPDATES,
                    Iap2MessageIds.STOP_CALL_STATE_UPDATES,
                    Iap2MessageIds.START_COMMUNICATIONS_UPDATES,
                    Iap2MessageIds.START_LIST_UPDATES,
                    Iap2MessageIds.STOP_LIST_UPDATES,
                    Iap2MessageIds.START_ROUTE_GUIDANCE_UPDATES,
                    Iap2MessageIds.START_POWER_UPDATES,
                    Iap2MessageIds.REQUEST_APP_LAUNCH,
                    // EA 会话状态（MIB3 车机控制）
                    Iap2MessageIds.EA_SESSION_STATUS,
                ),
        )

    /** 一行可读摘要（进文件日志；判错时能直接看出自己声明了什么）。 */
    fun describe(spec: Spec): String =
        "0x1D01 name=${spec.name} model=${spec.model} manufacturer=${spec.manufacturer}" +
            " 会发=${spec.messagesSent.size} 条 可收=${spec.messagesReceived.size} 条" +
            " EAP=${spec.eapProtocols.size} 条"

    /**
     * 从**已拼好的 body** 回读参数（单测用它验证「发出去的和读回来的一致」）。
     *
     * 返回 null 表示长度/结构不合法（帧本身没拼对），调用方据此判断而不是硬解析。
     */
    fun readBack(body: ByteArray): List<Iap2Csm.Param>? {
        val params =
            runCatching { Iap2Csm.parseParams(body) }
                .getOrElse { return null }
        return params.ifEmpty { null }
    }
}
