package io.github.shaonialife.carptocar.bt

import android.content.Context

/**
 * 「靠近车机自动启动」的偏好与运行时状态（2026-10-30）。
 *
 * 存在 `settings` 里与 [io.github.shaonialife.carptocar.launcher.settings.AutoConnectPrefs]
 * 同一份文件——两者是同一件事的两层：本类管「怎么被唤醒」（后台静默检测），
 * AutoConnectPrefs 管「唤醒后要不要连」。
 */
object CarApproachPrefs {
    /**
     * 默认开：只在**已指定目标车机**时才可能触发（见 CarApproachPolicy.NO_TARGET），
     * 因此不会误连测试台或其它已配对设备。
     */
    const val DEFAULT_ENABLED = true

    private const val PREFS_NAME = "settings"
    private const val KEY_ENABLED = "car_approach_autostart"
    private const val KEY_SILENT_UI = "car_approach_silent_ui"
    private const val KEY_LAST_TRIGGER = "car_approach_last_trigger_at"
    private const val KEY_SUPPRESSED = "car_approach_suppressed"
    private const val KEY_BATTERY_PROMPT_AT = "car_approach_battery_prompt_at"
    private const val KEY_OVERLAY_PROMPT_AT = "car_approach_overlay_prompt_at"
    private const val KEY_ACL_CONNECTED = "car_approach_acl_connected"

    /**
     * 「请把本应用加入电池优化白名单」的提醒间隔：7 天。
     * 太长会让用户永远错过（一旦被限流，功能就是静默失效，他自己看不出来）；
     * 太短会变成骚扰。拨动开关时不受此限（force）。
     */
    const val BATTERY_PROMPT_INTERVAL_MILLIS = 7L * 24 * 3_600_000

    fun enabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

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

    /**
     * 「触发后不弹出界面」（默认关）。
     *
     * 关：自动拉起的这一轮把界面留在眼前，用户能看着进度。
     * 开：界面建好就走（等同按 Home），连接与状态只在通知栏那一行里，
     * 点通知可随时打开界面查看日志、或直接点「断开」。
     *
     * 为什么不能干脆「不建界面」：整条管线（CarLife 接管、虚拟屏/编码器、启动自检）
     * 都挂在 Activity 这一路上，绕开界面直接连，握手能过但出流那一段起不来
     * （1.2.2 实车结论）。所以「静默」= 建完界面立刻退到后台，而不是不要界面。
     */
    fun silentUi(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SILENT_UI, false)

    fun setSilentUi(
        context: Context,
        silent: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SILENT_UI, silent)
            .apply()
    }

    /** 上次触发时刻（墙钟毫秒，0 = 从未触发）。 */
    fun lastTriggerAt(context: Context): Long =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_TRIGGER, 0L)

    fun markTriggered(
        context: Context,
        at: Long = System.currentTimeMillis(),
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_TRIGGER, at)
            .apply()
    }

    /**
     * 目标车机的蓝牙链路**此刻是否连着**（由 ACL 广播维护，落盘而不是放内存）。
     *
     * 为什么要落盘：判断这件事的时机（30 秒收摊闹钟到点）可能发生在**新进程**里——
     * 旧进程早就被系统回收了，内存里的标志跟着一起没。落盘后闹钟到点读到的
     * 就是最后一条 ACL 事件留下的真实状态。
     */
    fun aclConnected(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACL_CONNECTED, false)

    fun setAclConnected(
        context: Context,
        connected: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACL_CONNECTED, connected)
            .apply()
    }

    /**
     * 用户手动断开/取消后置位：同一段停车期间不再自动拉起。
     * 车机蓝牙链路断开（ACL_DISCONNECTED，通常意味着人离开了车）时自动清除。
     */
    fun suppressed(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SUPPRESSED, false)

    fun setSuppressed(
        context: Context,
        suppressed: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SUPPRESSED, suppressed)
            .apply()
    }

    /** 距上次提醒电池优化是否已超过 [BATTERY_PROMPT_INTERVAL_MILLIS]（从未提醒过 = true）。 */
    fun batteryPromptDue(
        context: Context,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val last =
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_BATTERY_PROMPT_AT, 0L)
        if (last <= 0L) return true
        val elapsed = now - last
        // 系统时间被往回调时 elapsed 为负：当作刚提醒过，别趁乱再弹
        return elapsed >= BATTERY_PROMPT_INTERVAL_MILLIS
    }

    fun markBatteryPrompt(
        context: Context,
        at: Long = System.currentTimeMillis(),
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_BATTERY_PROMPT_AT, at)
            .apply()
    }

    /**
     * 「显示在其他应用上层」的提醒（同样 7 天节流）。
     *
     * 这条权限在这个项目里有两副面孔：暗幕（ScreenDimmer）用它盖住屏幕，
     * 而 Android 10+ 的**后台启动界面**豁免也认它——没有它，车机靠近时
     * `startActivity` 会被系统**静默拦下**：日志写着「已拉起」，屏幕上什么都没有
     * （2026-10-30 实测：SYSTEM_ALERT_WINDOW 的拒绝时刻与触发时刻完全吻合）。
     */
    fun overlayPromptDue(
        context: Context,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val last =
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_OVERLAY_PROMPT_AT, 0L)
        if (last <= 0L) return true
        return now - last >= BATTERY_PROMPT_INTERVAL_MILLIS
    }

    fun markOverlayPrompt(
        context: Context,
        at: Long = System.currentTimeMillis(),
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_OVERLAY_PROMPT_AT, at)
            .apply()
    }
}
