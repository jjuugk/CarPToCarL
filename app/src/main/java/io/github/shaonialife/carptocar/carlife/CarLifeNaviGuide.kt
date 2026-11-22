package io.github.shaonialife.carptocar.carlife

/**
 * CarLife+ 引擎推送的导航诱导信息（`0x10030` / `MSG_CMD_NAVI_NEXT_TURN`）。
 *
 * 字段号来自车机端参考 APK 的 protobuf 生成类
 * `com.baidu.carlife.protobuf.CarlifeNaviNextTurnInfoProto$CarlifeNaviNextTurnInfo`
 * 中的 `*_FIELD_NUMBER` 常量（反编译实证，不是猜的）：
 *
 * ```
 * message CarlifeNaviNextTurnInfo {
 *   optional int32  action          = 1;  // 1=导航开始 2=诱导更新 3=导航结束
 *   optional int32  nextTurn        = 2;  // CarLife 转向枚举，见 [CAR_SELF]..[ENTER_TUNNEL]
 *   optional string roadName        = 3;  // 转向后道路名
 *   optional int32  totalDistance   = 4;  // 全程剩余（米）
 *   optional int32  remainDistance  = 5;  // 到下一转向距离（米）
 *   optional bytes  turnIconData    = 6;  // 引擎自带转向图标（本实现不用，车机侧自己画）
 * }
 * ```
 *
 * 参考 APK 里 `b8/g.smali` 把 `remainDistance` 同时塞进高德广播的
 * `SEG_REMAIN_DIS` 和 `ROUTE_REMAIN_DIS`，`roadName` 塞进 `NEXT_ROAD_NAME`，
 * `nextTurn` 经 `b8/a.smali` 的码表转成高德 `ICON`/`NEW_ICON`。这里保留全部原始
 * 字段，转换交给 [CarLifeNaviGuide] 的下游（iAP2 0x5201/0x5202）去做。
 */
data class CarLifeNaviNextTurn(
    val action: Int = 0,
    val nextTurn: Int = 0,
    val roadName: String = "",
    val totalDistance: Int = 0,
    val remainDistance: Int = 0,
    val turnIconData: ByteArray? = null,
)

/** `0x10030` 载荷的解析与转向码转换。 */
object CarLifeNaviGuide {
    const val ACTION_START = 1
    const val ACTION_UPDATE = 2
    const val ACTION_END = 3

    // ---- CarLife nextTurn 枚举（b8/a.smali 静态构造的第三个参数，实证） ----
    const val CAR_SELF = 1
    const val LEFT_TURN = 2
    const val RIGHT_TURN = 3
    const val LEFT_FRONT = 4
    const val RIGHT_FRONT = 5
    const val LEFT_BACK = 6
    const val RIGHT_BACK = 7
    const val U_TURN_LEFT = 8
    const val STRAIGHT = 9
    const val ARRIVE_WAYPOINT = 10
    const val ROUNDABOUT_ENTER = 11
    const val ROUNDABOUT_EXIT = 12
    const val ARRIVE_SERVICE = 13
    const val ARRIVE_TOLL = 14
    const val ARRIVE_DEST = 15
    const val ENTER_TUNNEL = 16

    /**
     * 解析 `0x10030` 裸 protobuf 载荷。
     *
     * 与 [CarLifeProtocol.parseMediaProgress] 同样的铁律：解析函数**必须**自己吞掉
     * 所有 RuntimeException。异常一旦冒到 CarLifeHuClient.cmdReader 的 catch，
     * `finally -> stop()` 会把整条 CarLife 连接拆掉重来——一个畸形诱导包不能
     * 让导航画面和音频一起断。
     */
    fun parse(payload: ByteArray): CarLifeNaviNextTurn? {
        if (payload.isEmpty()) return null
        try {
            var action = 0
            var nextTurn = 0
            var roadName = ""
            var totalDistance = 0
            var remainDistance = 0
            var turnIconData: ByteArray? = null
            var index = 0
            while (index < payload.size) {
                val tag = payload[index].toInt() and 0xFF
                index++
                if (tag == 0) break // protobuf tag 不可能为 0，视作填充/越界
                val field = tag ushr 3
                val wireType = tag and 0x07
                when {
                    wireType == 0 -> {
                        val value = readVarint(payload, index)
                        index += varintLen(payload, index)
                        when (field) {
                            1 -> action = value
                            2 -> nextTurn = value
                            4 -> totalDistance = value
                            5 -> remainDistance = value
                        }
                    }

                    wireType == 2 -> {
                        val length = readVarint(payload, index)
                        index += varintLen(payload, index)
                        if (length < 0 || index > payload.size || length > payload.size - index) break
                        val bytes = payload.copyOfRange(index, index + length)
                        when (field) {
                            3 -> roadName = bytes.toString(Charsets.UTF_8)
                            6 -> turnIconData = bytes
                        }
                        index += length
                    }

                    wireType == 5 -> index += 4
                    wireType == 1 -> index += 8

                    // wireType 3/4 是废弃 group 编码，无法安全跳过
                    else -> break
                }
            }
            // action/nextTurn 至少有一个才认为是真诱导包；全零包在高德广播里
            // 会变成「无转向 + 0 米」，对车机有害无益。
            if (action == 0 && nextTurn == 0 && roadName.isBlank()) return null
            return CarLifeNaviNextTurn(action, nextTurn, roadName, totalDistance, remainDistance, turnIconData)
        } catch (_: RuntimeException) {
            return null
        }
    }

    /**
     * CarLife `nextTurn` → 高德 `NEW_ICON` 码表（`b8/a.smali` 的 put 映射）。
     * 只用于日志对照；实际发给车机的是下面的 Apple iAP2 转向类型。
     */
    fun amapIcon(nextTurn: Int): Int = when (nextTurn) {
        LEFT_TURN -> 2
        RIGHT_TURN -> 3
        LEFT_FRONT -> 4
        RIGHT_FRONT -> 5
        LEFT_BACK -> 6
        RIGHT_BACK -> 7
        U_TURN_LEFT -> 8
        STRAIGHT -> 9
        ARRIVE_WAYPOINT -> 10
        ROUNDABOUT_ENTER -> 11
        ROUNDABOUT_EXIT -> 12
        ARRIVE_SERVICE -> 13
        ARRIVE_TOLL -> 14
        ARRIVE_DEST -> 15
        ENTER_TUNNEL -> 16
        else -> 0
    }

    /**
     * CarLife `nextTurn` → Apple `RouteGuidanceManeuverType`（iAP2 `0x5202` field 3）。
     *
     * Apple 侧取值来自 DiPlay 的 `BydManeuverCodes.gaode()` / `BydClusterFrame.from()`。
     * 挑的是每个类别里的**规范**值：
     * 左转 1、右转 2、直行 11、掉头 4、缓左 13、缓右 14、环岛进 6、环岛出 7、
     * 目的地 12、急左 47、急右 48（实测直行=11、左转=1、到达=12）。中国是右行，
     * `0x5202` 的 drivingSide 实测写 2（不是 0）。
     *
     * 无法可靠对应的枚举（CAR_SELF / ENTER_TUNNEL / 未知）返回 0 = NONE：
     * 车机不会画一个凭空猜的箭头，宁可空白。
     */
    fun appleManeuverType(nextTurn: Int): Int = when (nextTurn) {
        LEFT_TURN -> 1
        RIGHT_TURN -> 2
        // 直行实测 type=11（DiPlay 映射 gaode 直行码 11）。
        STRAIGHT -> 11
        U_TURN_LEFT -> 4
        ROUNDABOUT_ENTER -> 6
        ROUNDABOUT_EXIT -> 7
        ARRIVE_WAYPOINT -> 12
        ARRIVE_DEST, ARRIVE_SERVICE, ARRIVE_TOLL -> 12
        LEFT_FRONT -> 13
        RIGHT_FRONT -> 14
        LEFT_BACK -> 47
        RIGHT_BACK -> 48
        else -> 0
    }

    fun label(nextTurn: Int): String = when (nextTurn) {
        CAR_SELF -> "自车"
        LEFT_TURN -> "左转"
        RIGHT_TURN -> "右转"
        LEFT_FRONT -> "左前方"
        RIGHT_FRONT -> "右前方"
        LEFT_BACK -> "左后方"
        RIGHT_BACK -> "右后方"
        U_TURN_LEFT -> "掉头"
        STRAIGHT -> "直行"
        ARRIVE_WAYPOINT -> "到达途经点"
        ROUNDABOUT_ENTER -> "进入环岛"
        ROUNDABOUT_EXIT -> "驶出环岛"
        ARRIVE_SERVICE -> "到达服务区"
        ARRIVE_TOLL -> "到达收费站"
        ARRIVE_DEST -> "到达目的地"
        ENTER_TUNNEL -> "进入隧道"
        else -> "转向($nextTurn)"
    }

    fun actionLabel(action: Int): String = when (action) {
        ACTION_START -> "导航开始"
        ACTION_UPDATE -> "诱导更新"
        ACTION_END -> "导航结束"
        else -> "动作($action)"
    }

    private fun readVarint(payload: ByteArray, index: Int): Int {
        var result = 0
        var shift = 0
        var i = index
        while (i < payload.size && shift < 35) {
            val b = payload[i].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            i++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    private fun varintLen(payload: ByteArray, index: Int): Int {
        var len = 0
        var i = index
        while (i < payload.size && len < 5) {
            len++
            if (payload[i].toInt() and 0x80 == 0) break
            i++
        }
        return len
    }
}
