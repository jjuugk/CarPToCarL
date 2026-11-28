package io.github.shaonialife.carptocar.input

import android.content.Context

/**
 * 「旋钮走 CarLife 原生硬键」实验开关（偏好 `knob_hardkey_v1`，**默认关**）。
 *
 * 反编译 CarLife+ 8.8.5（`com.baidu.carlife.honor`）实证的旋钮通路：
 * ```
 * 0x68008 / CarlifeCarHardKeyCode{keycode}
 *   → CarlifeTouchManager.onHardKeyCodeEvent(keycode)
 *   → keycode 6  = onKeyKnobClockwise()    → Instrumentation.sendKeyDownUpSync(300)
 *     keycode 7  = onKeyKnobAntiClockwise() → Instrumentation.sendKeyDownUpSync(301)
 *     keycode 20 = onKeyOkEvent()           → 旋钮按下
 *     keycode 14 = onKeyBackEvent()         → 返回
 * ```
 * 但开关打开的旋钮分支前有一道门：`MultiScreenManager.isRemoteAlive()`
 * （内部状态 `getRemoteAliveState() >= 1`）。这道门是否随普通连接置位，静态分析
 * 无法断定——**这正是本 debug 开关要实车验证的唯一问题**。
 *
 * ## 开源实证的门控模型（2026-11-28）
 *
 * 百度 Apollo 开源车机端 `CarLife-Android-Vehicle` 佐证了「硬键受焦点门控」：
 * - 车机侧 `HardKeyReceiverChangAn.onReceive` 首行 `if (!isHoldingAudioFocus) return`；
 * - 焦点状态由手机下发的 `0x3002 GAIN` / `0x3003~3005 LOSS` 命令驱动，默认 false；
 * - 转发用 `0x68008 + CarlifeCarHardKeyCode{keycode}`，与我们发的报文逐字节同款。
 *
 * 由此得到本 debug 的判据（已接进 `CarLifeHuClient.sendHardKeyCode` 的日志）：
 * 1. 先让 CarLife+ 进入播放态，确认日志出现「引擎焦点: 音乐=1」（65574 的 MUSIC 模块）；
 * 2. 此刻转旋钮，6/7 送达后若焦点仍不动，才判定 `isRemoteAlive()` 门未置位；
 * 3. 连按 20（旋钮按下，CarLife+ 侧无这道门）作为对照——它若能动而 6/7 不动，
 *    门控结论即坐实；两者都不动则要先查焦点/播放态。
 *
 * 注意开源那套「音频焦点门」在车机侧，我们的车机是自己投屏、没有那层 Receiver；
 * 真正的疑点始终在手机侧 `isRemoteAlive()`，别把两道门当成同一个。
 *
 * 关闭（默认）时逐字节回到改动前：旋钮仍走 [KnobRouter] 的「转动→滑动 / 按下→点击」
 * 触摸翻译。打开后旋钮改发上面的硬键，失败会在日志里留痕，但**不回退触摸**
 * （否则两种动作叠加，无法判断硬键到底生没生效）。
 *
 * 开关下次连接生效；会话中途切换不影响已建立的会话。
 */
object KnobHardKeyOption {
    private const val PREFS = "knob_hardkey_v1"
    private const val KEY_ENABLED = "enabled"
    private const val DEFAULT_ENABLED = false

    @Volatile
    var enabled: Boolean = DEFAULT_ENABLED
        private set

    fun load(context: Context?) {
        val app = context?.applicationContext ?: return
        enabled =
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    }

    fun save(
        context: Context?,
        value: Boolean,
    ) {
        enabled = value
        val app = context?.applicationContext ?: return
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, value)
            .apply()
    }
}
