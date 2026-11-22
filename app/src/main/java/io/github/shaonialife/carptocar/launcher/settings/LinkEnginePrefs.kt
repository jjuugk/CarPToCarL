package io.github.shaonialife.carptocar.launcher.settings

import android.content.Context

/**
 * CarPlay 链路实现的选择：自研（[io.github.shaonialife.carptocar.airplay.AirPlayClient]）
 * 还是协议核（[io.github.shaonialife.carptocar.protocolcore.AirPlayLink]）。
 *
 * 默认**自研**。原因不是自研更好，而是协议核链路**从未在真车上跑过**——它里面的
 * 协议决策虽然来自固件转译，但"能不能连上这台车"仍需实测。开关默认关，做的就是把
 * 一次实测变成改配置重启，而不是改代码重新打包。
 *
 * 切换代价（写在这里，免得切完才发现）：
 *  - 两套实现的配对库格式不同 → 切过去要重新配对一次（车机可能要重新确认）；
 *  - 协议核链路暂无单流 TEARDOWN / 通话音频档 / NowPlaying 推送 / 导航闪避，
 *    依赖这些的功能会降级，日志里会打 `LINK: 协议核链路不支持 …`；
 *  - 切回来不需要重配对（自研那套的配对库还在）。
 */
object LinkEnginePrefs {
    private const val PREFS_NAME = "settings"
    private const val KEY_ENGINE = "carplay_link_engine"

    /** 自研链路（默认）。 */
    const val ENGINE_SELF = "self"

    /** 协议核链路（Rust `fw::airplay`）。 */
    const val ENGINE_PROTOCOL_CORE = "protocol_core"

    fun engine(context: Context): String =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENGINE, ENGINE_SELF)
            ?: ENGINE_SELF

    fun setEngine(
        context: Context,
        engine: String,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENGINE, engine)
            .apply()
    }

    fun useProtocolCore(context: Context): Boolean = engine(context) == ENGINE_PROTOCOL_CORE
}
