package io.github.shaonialife.carptocar.input

import android.content.Context
import io.github.shaonialife.carptocar.carlife.CarLifeProtocol

/**
 * 旋钮焦点模式（实验，偏好 `knob_focus_v1`，默认关）。
 *
 * ## 为什么需要它
 *
 * 默认通路（[KnobRouter]）把旋钮翻译成触摸：转动 = 竖向滑动、按下 = 点击。
 * 实车（宝马 NBT Evo）上的表现是「转旋钮 = 页面上下滚」，而不是 iDrive 用户习惯的「移动选中项」。
 *
 * CarLife 协议本身就有旋钮语义。百度官方开源的车机端（ApolloAuto/apollo-DuerOS，
 * `CarLife-Android-Vehicle`）里：
 *  - `CarlifeConfUtil.KEY_BOOL_FOCUS_UI = "FOCUS_UI"`，由 `FeatureConfigManager.customFeature()`
 *    放进 `HU_FEATURE_CONFIG_RESPONSE`（即本项目的 98386）告诉手机「车机要焦点 UI」；
 *  - `CommonParams` 定义了旋钮键：`KEYCODE_SELECTOR_NEXT = 0x06`、`KEYCODE_SELECTOR_PREVIOUS = 0x07`、
 *    `KEYCODE_BACK = 0x0E`、`KEYCODE_OK = 0x14`，与媒体键同走 `MSG_TOUCH_CAR_HARD_KEY_CODE`（0x68008）。
 *
 * 国行原生带 CarLife 的旋钮车就是这么用的。本模式照此声明 `FOCUS_UI = 1`，
 * 并把旋钮事件改发这四个硬键。
 *
 * ## 为什么默认关、为什么「下次连接起效」
 *
 * 本项目此前只验证过 CarLife+ 8.8.5 处理 6 个媒体/语音硬键（见 [KnobRouter] 的 KDoc），
 * 它对 `FOCUS_UI` 与焦点键的支持还要实车确认，所以默认保持原有的触摸翻译。
 * `FOCUS_UI` 只在 98386 里声明，而 98386 每次连接只发一次，所以开关**下次连接起效**；
 * 本轮连接是否按焦点模式走，以建链时的快照（`CarLifeHijack.knobFocusSession`）为准。
 */
object KnobFocus {
    private const val PREFS = "knob_focus_v1"
    private const val KEY_ENABLED = "enabled"

    /** 单份报告最多展开的焦点键数：快速转动时防止一次灌入过多按键。 */
    const val MAX_STEPS_PER_REPORT = 8

    /** 偏好值；`@Volatile` 由 [load]/[save] 维护。 */
    @Volatile
    var enabled: Boolean = false
        private set

    /** 启动时调用一次（[io.github.shaonialife.carptocar.SenderApp]）。 */
    fun load(context: Context?) {
        val app = context?.applicationContext ?: return
        enabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    }

    /** 开关切换时调用（UI 层）。 */
    fun save(
        context: Context?,
        value: Boolean,
    ) {
        enabled = value
        val app = context?.applicationContext ?: return
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /**
     * 一份旋钮报告 → CarLife 硬键序列（纯函数）。
     *
     * 顺时针（steps > 0）= 下一项，逆时针 = 上一项；按下与返回只取**按下沿**（见 [ButtonEdges]）。
     */
    fun hardKeysFor(
        steps: Int,
        pressEdge: Boolean,
        backEdge: Boolean,
    ): List<Int> {
        val keys = ArrayList<Int>()
        val count = minOf(kotlin.math.abs(steps), MAX_STEPS_PER_REPORT)
        val selector = if (steps > 0) CarLifeProtocol.HardKey.SELECTOR_NEXT else CarLifeProtocol.HardKey.SELECTOR_PREVIOUS
        repeat(count) { keys += selector }
        if (pressEdge) keys += CarLifeProtocol.HardKey.OK
        if (backEdge) keys += CarLifeProtocol.HardKey.BACK
        return keys
    }
}

/**
 * 旋钮按钮的按下沿检测，每个 HID 设备（uuid）一份状态。
 *
 * 车机在按住期间仍会发报告（例如按住旋钮同时转动），每份报告的按钮位都是 1。
 * 只看电平会把一次按下变成多次点击，所以只在「上一份为松开、这一份为按下」时算一次。
 * 做法与 CarDeck-BMW `BmwRotaryHid.decode()` 的 `buttons and lastButtons.inv()` 相同。
 */
class ButtonEdges {
    private val pressed = HashMap<String, Boolean>()
    private val back = HashMap<String, Boolean>()

    /** @return Pair(按下沿, 返回键按下沿)。字段缺失（null）视为该位不变。 */
    @Synchronized
    fun update(
        uuid: String,
        pressedNow: Boolean?,
        backNow: Boolean?,
    ): Pair<Boolean, Boolean> {
        val pressEdge = pressedNow == true && pressed[uuid] != true
        val backEdge = backNow == true && back[uuid] != true
        if (pressedNow != null) pressed[uuid] = pressedNow
        if (backNow != null) back[uuid] = backNow
        return pressEdge to backEdge
    }

    /** 新一轮 /info（设备表可能变了）时清空。 */
    @Synchronized
    fun clear() {
        pressed.clear()
        back.clear()
    }
}
