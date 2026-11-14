package io.github.shaonialife.carptocar.launcher.settings

import android.content.Context

/**
 * 开发者入口是否可见（「测试台接收端」那一行）。
 *
 * 默认**关闭**：那个入口是给 PC 上的 mock 接收端做直连排查用的，填错地址会顶掉正常
 * 车机连接，不该摆在普通用户面前。解锁方式是连点顶部标题 10 次（见 MainActivity）。
 *
 * 状态持久化：解锁一次之后每次启动都显示，免得每次调试都要点十下；再连点 10 次隐藏。
 */
object DevModePrefs {
    private const val PREFS_NAME = "settings"
    private const val KEY_ENABLED = "dev_mode_visible"

    fun enabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
