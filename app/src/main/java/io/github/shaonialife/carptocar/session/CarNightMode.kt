package io.github.shaonialife.carptocar.session

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CarPlay 车机日夜模式的接收状态与转发出口。
 *
 * 接收来源：AirPlay 事件通道的 `POST /command`，报文体为 binary plist：
 * ```
 * { type = setNightMode; params = { nightMode = <bool>; }; }
 * ```
 * 该格式由 xcertplay（DiPlay 使用的接收端协议栈）`AirPlaySession.sendPendingNightModeLocked`
 * 实证；本项目此前只在 `SessionController` 的 `when` 分支里落到「未识别」日志。
 *
 * 转发目标：**手机侧 CarLife+ 引擎**，经 CarLife 控制通道命令
 * `0x18078`（`MSG_CMD_THEME_CHANGE`），protobuf `CarlifeThemeChange{ themeModes }`，
 * 由引擎在自己的进程里决定导航/UI 配色。字节码证据见
 * `CarLifeProtocol.HU_THEME_CHANGE` 注释（车机端 `La9/r4;->b(I)V` 反编译）。
 *
 * 注意：早期误把状态当作「高德/Amap 车机属性广播」
 *（`AUTONAVI_STANDARD_BROADCAST_SEND` / `KEY_TYPE=0x2740` / `EXTRA_DAY_NIGHT_MODE`）发出。
 * 那是车机本地 App（如车机高德）监听的广播，**手机 CarLife+ 引擎根本不接收**，
 * 故已废弃。真正的出口是 [onChange] 回调里投递的 CarLife 协议命令。
 *
 * 本类只负责状态收口与变化通知，不直接依赖 carlife 包：具体发送动作由
 * [SessionController] 在收到变化后投递到 CarLife 命令线程池（`carLifeCommandSender`），
 * 避免占用 iAP2 收帧线程。[addListener] 供未来把状态接到本地提示帧/UI 等扩展点使用。
 */
object CarNightMode {
    private const val TAG = "CarPlaySender"

    /** 当前生效状态；进程内全局，会话重建后由车机下一次下发覆盖。 */
    @Volatile
    var isNight: Boolean = false
        private set

    @Volatile
    var lastSource: String? = null
        private set

    /**
     * 是否收到过车机下发的权威日夜状态。
     *
     * CarPlay 会话（AirPlay/iAP2）往往先于本机 CarLife+ 引擎建链：车机在会话刚建立时
     * 就下发 `setNightMode`，那一刻引擎还没连上、转发会被丢弃。引擎就绪后据此判断
     * 「确实有状态需要补发」，避免用默认 [isNight]=false 误覆盖引擎当前主题。
     */
    val hasValue: Boolean get() = lastSource != null

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /**
     * 解析车机命令里的 `nightMode` 字段。
     *
     * DiPlay/xcertplay 发 Boolean（plist 0x08/0x09）；部分固件按 CarPlay 字典发 0/1 数值
     * 或字符串。三种形态统一收口，无法识别时返回 null 让调用方保留原始日志。
     */
    fun parse(raw: Any?): Boolean? =
        when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String ->
                when (raw.trim().lowercase()) {
                    "true", "night", "1" -> true
                    "false", "day", "0" -> false
                    else -> null
                }
            else -> null
        }

    /** 注册状态变化监听（由 SessionController 注入 CarLife 发送动作）。 */
    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 应用车机下发的状态并通知转发出口。
     *
     * 同一命令重复到达时仍会通知：车机显式下发即视为权威状态，避免 CarLife 引擎
     * 进程重启后错过唯一一次状态更新。
     *
     * @param source 仅用于日志归因，例如 `AirPlay /command`。
     */
    fun apply(
        night: Boolean,
        source: String,
    ) {
        val changed = isNight != night
        isNight = night
        lastSource = source
        Log.i(
            TAG,
            "NIGHT: 车机日夜模式=${if (night) "夜间" else "日间"}" +
                " source=$source changed=$changed",
        )

        listeners.forEach { listener ->
            runCatching { listener(night) }
        }
    }

    fun describe(): String = "night=${if (isNight) "夜间" else "日间"} source=${lastSource ?: "none"}"
}
