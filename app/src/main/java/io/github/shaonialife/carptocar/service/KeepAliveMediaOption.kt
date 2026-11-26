package io.github.shaonialife.carptocar.service

import android.content.Context

/**
 * 保活用的假 MediaSession 开关（**默认开**，保持原有防冻结效果）。
 *
 * 关掉后 [CarPlayService] 不再注册 `MediaSession(this, "CarPToCarL")`，
 * 前台服务也不再声明 `mediaPlayback` 类型——车机就不会把它当成「正在播放的音乐 App」
 * 显示那张「车机音频直通 / CarPToCarL」卡片。代价是国产 ROM 的冻结风险回到开关加入前的水平。
 *
 * 前台服务本身（connectedDevice + 唤醒锁 + WiFi 锁）不受影响，连接不会因此断掉。
 */
object KeepAliveMediaOption {
    private const val PREFS_NAME = "settings"
    private const val KEY = "keepalive_media_session_v1"

    @Volatile
    var enabled: Boolean = true
        private set

    fun load(context: Context) {
        enabled =
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY, true)
    }

    fun save(
        context: Context,
        value: Boolean,
    ) {
        enabled = value
        context
            .applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, value)
            .apply()
    }

    val label: String get() = if (enabled) "开" else "关"

    val note: String
        get() =
            if (enabled) {
                "默认开：保活服务声明 mediaPlayback 并注册一条假 MediaSession，" +
                    "避免国产 ROM 冻结。副作用是车机可能把本 App 识别成音乐软件，" +
                    "显示「车机音频直通」卡片。关掉可验证这一点，下次连接完全生效。"
            } else {
                "已关闭：不再注册假 MediaSession，也不声明 mediaPlayback；" +
                    "车机不应再显示音乐卡片。前台服务与唤醒锁仍在，但国产 ROM 冻结风险会回来。"
            }
}
