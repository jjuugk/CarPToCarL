package io.github.shaonialife.carptocar.carlife

import android.content.Context

/**
 * 方向盘语音键手势（偏好 `voice_key_gesture_v1`）。
 *
 * ## 现在只有一条：三连击 → 重启音频
 *
 * 实车（宝马 G38，2026-11-17）里有几类「画面还在、音乐没了」：
 * 引擎退出后只恢复视频、车机侧流状态坏了、泵线程被 park 到时间轴错位。
 * 这些过去只能靠**停车熄火重连**解决，而重连要把 CarPlay 会话整套重建（十几秒）。
 * 三连击给用户一个秒级的自救手段：一次点击就够，不用看屏幕、不用摸手机。
 *
 * 为什么是语音键：它是方向盘的硬键里**唯一**在每台车上都必然存在、且不参与导航/音量/
 * 接挂电话的那一个（媒体键在 MGU-81 上根本没有，见 1.2.53 的 HID 画像存档）。
 *
 * ## 为什么要有开关
 *
 * 三连击在**关闭**时是三次普通的语音识别请求（原有行为）；打开后第三次会被我们截获，
 * 顺带撤掉刚发出的 `VR_START`。如果某个车型/某个 CarLife+ 版本对连按语音键有自己的
 * 语义（例如部分车机的「连续两次语音键」是切输入法），用户能一键回到原样。
 *
 * 默认 `true`：这是用户明确要的功能，且触发成本低（短按三次，不放音乐时误触也无害）。
 */
object VoiceKeyGesture {
    private const val PREFS = "voice_key_gesture_v1"
    private const val KEY_TRIPLE_TAP_RESTART = "triple_tap_restart_audio"
    private const val DEFAULT_TRIPLE_TAP_RESTART = true

    /**
     * 「语音键短按三次 → 重启音频」是否启用。
     *
     * `@Volatile` 且由 [load]/[save] 维护：iAP2 事件线程每按一次键都要问，读盘没必要。
     */
    @Volatile
    var tripleTapRestartEnabled: Boolean = DEFAULT_TRIPLE_TAP_RESTART
        private set

    /** 启动时调用一次（[io.github.shaonialife.carptocar.SenderApp]）。 */
    fun load(context: Context?) {
        val app = context?.applicationContext ?: return
        tripleTapRestartEnabled =
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_TRIPLE_TAP_RESTART, DEFAULT_TRIPLE_TAP_RESTART)
    }

    /** 开关切换时调用（UI 层），立即生效——下一次按键就按新值判定。 */
    fun saveTripleTapRestart(
        context: Context?,
        value: Boolean,
    ) {
        tripleTapRestartEnabled = value
        val app = context?.applicationContext ?: return
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TRIPLE_TAP_RESTART, value)
            .apply()
    }
}
