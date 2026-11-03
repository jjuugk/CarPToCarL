package io.github.shaonialife.carptocar.mib3

/**
 * MIB3 车机控制用到的 viwi 端点清单（只读 + 可写）。
 *
 * 端点与权限来自固件逆向（`ext_devices` 档位，见 `docs/mib3-viwi-control.md`）：
 * `/hvac`、`/door`、`/window`、`/lighting`、`/seating` 等都是 GET+PUT+POST+DELETE 全开。
 *
 * **但字段名与取值范围没有可读定义**（RUDI 资源定义是编译产物），所以下面每个动作的
 * body 都是**按语义猜的最小报文**。做这样的取舍而不是「只给只读」：
 *  - 猜错的结果是车机返回错误码（面板上看得见、日志里有原文），不是误操作；
 *  - 每个动作在执行前都会把**实际要发的 JSON 原样显示**在确认框里，
 *    用户看得到自己按下的是什么，不会出现「点了 lock 其实发了别的」；
 *  - 首次实车跑完按日志把 body 改对即可——改动只在这个文件里。
 */
object Mib3Endpoints {
    /** 一个可写动作。 */
    data class Action(
        val key: String,
        val label: String,
        val method: String,
        val path: String,
        val body: String?,
        /** 误操作后果较重（解锁车门、降下车窗）——确认框用危险样式。 */
        val danger: Boolean,
        /** 确认框里的一句话说明。 */
        val note: String,
    )

    /** 只读区要拉的端点（label to path）。都可 GET。 */
    val READ_ONLY: List<Pair<String, String>> =
        listOf(
            "车辆信息" to "/vehicleinformation",
            "行驶状态" to "/drivingstate",
            "车门" to "/door",
            "车窗" to "/window",
            "空调" to "/hvac",
            "里程续航" to "/distance",
            "保养" to "/maintenance",
            "挡位" to "/gearbox",
        )

    /** 控制区按钮（按显示顺序）。 */
    val ACTIONS: List<Action> =
        listOf(
            Action("door_lock", "车门上锁", "POST", "/door", """{"locked":true}""", false, "锁止全部车门"),
            Action("door_unlock", "车门解锁", "POST", "/door", """{"locked":false}""", true, "解锁全部车门"),
            Action("window_up", "车窗全升", "POST", "/window", """{"position":100}""", false, "四窗升到顶（100%）"),
            Action("window_down", "车窗全降", "POST", "/window", """{"position":0}""", true, "四窗降到底（0%）"),
            Action("hvac_on", "空调开", "POST", "/hvac", """{"active":true}""", false, "打开空调"),
            Action("hvac_off", "空调关", "POST", "/hvac", """{"active":false}""", false, "关闭空调"),
            Action("hvac_22", "22 ℃", "PUT", "/hvac", """{"targetTemperature":22.0}""", false, "设定目标温度 22 ℃"),
            Action("hvac_24", "24 ℃", "PUT", "/hvac", """{"targetTemperature":24.0}""", false, "设定目标温度 24 ℃"),
            Action("light_on", "车内灯开", "POST", "/lighting", """{"active":true}""", false, "打开车内照明"),
            Action("light_off", "车内灯关", "POST", "/lighting", """{"active":false}""", false, "关闭车内照明"),
            Action("seat_off", "座椅加热关", "PUT", "/seating", """{"heatingLevel":0}""", false, "座椅加热归零"),
            Action("seat_2", "座椅加热 2 档", "PUT", "/seating", """{"heatingLevel":2}""", false, "座椅加热调到 2 档"),
        )

    /** 兜底说明：面板底部那行小字。 */
    const val DISCLAIMER =
        "接口与字段据 MIB3H SW3874 固件逆向而来，尚未实车验证；所有请求原文都会写进日志。行驶中请勿操作车门/车窗。"
}
