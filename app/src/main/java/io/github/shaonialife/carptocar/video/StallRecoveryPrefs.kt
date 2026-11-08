package io.github.shaonialife.carptocar.video

import android.content.Context

/**
 * 画面停摆**自动恢复**的偏好（默认开，可一键关回旧行为）。
 *
 * 关掉之后 [io.github.shaonialife.carptocar.carlife.CarLifeHijack] 的停摆看门狗
 * 退回「只观测、不干预」：照旧记「画面源停摆 N 秒」，但不会请求引擎重建编码器、
 * 也不会强制重建 CarLife 会话。排障时用它对照「干预有没有帮助」。
 *
 * 为什么需要这个开关：干预会动引擎的编码器（重建要 1 秒上下、冷启动会话要 30~45 秒
 * 才出首个 IDR）。已跑通的车型万一被误判成停摆，用户必须能自己关掉，而不是等出包。
 */
object StallRecoveryPrefs {
    private const val PREFS_NAME = "settings"

    /** 停摆分级干预总开关。 */
    private const val KEY_RECOVERY = "video_stall_recovery"

    /** 停摆判据：「最后一帧距今」= true（默认）／旧的「2 秒窗口帧数」= false。 */
    private const val KEY_FRAME_AGE = "video_stall_by_frame_age"

    /** 是否按「最后一帧距今」判停摆。 */
    fun judgeByFrameAge(context: Context): Boolean = prefs(context).let { p ->
        runCatching { p.getBoolean(KEY_FRAME_AGE, true) }.getOrDefault(true)
    }

    /** 是否执行自动恢复（请求重建编码器 / 强制重建会话）。 */
    fun recoveryEnabled(context: Context): Boolean = prefs(context).let { p ->
        runCatching { p.getBoolean(KEY_RECOVERY, true) }.getOrDefault(true)
    }

    fun setRecoveryEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_RECOVERY, enabled).apply()
    }

    fun setJudgeByFrameAge(
        context: Context,
        enabled: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_FRAME_AGE, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
