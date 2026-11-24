package io.github.shaonialife.carptocar.audio

import android.content.Context

/**
 * 音频兼容实验开关（默认关闭，**对所有车型生效**）。
 *
 * ## 来源
 *
 * 2026-11-24 奔驰 MBUX 48445 / `MB_D-M2-5` 实车（`CarPToCarL-log-20261124-083655.txt`）：
 * 点音乐后申请主音频 → 两条流 SETUP 全 `200 OK` → `RECORD` 完成 → 约 300ms 后车机
 * `control EOF` 拆整条会话，音频只发 0~2 包。三次断链都紧跟 `modesChanged（申请主音频）`。
 * PCM 源正常（电平 91%、非零 99%），是车机不收，不是手机没出声。
 *
 * 但这两个策略是通用的 XML/RTSP 资源仲裁差异，别车型也可能踩到——所以**不按车型门控**，
 * 由使用者在界面上按车拨档验证。默认 [OFF]，关掉时行为与加开关前逐字节一致。
 *
 * 推断（**未验证**）：车机不接受手机以 USER 优先级永久夺走它持有的主音频，或不接受
 * 媒体+导航两条流同时 SETUP。两个变量分开 A/B，谁有效留谁：
 *
 * - [SKIP_NAV]（A）：不建 `t101/default` 导航独立流，只留媒体流。
 * - [BORROW_AUDIO]（B）：媒体主音频改**临时借用**（`PRIORITY_USER` + `temporary=true`），
 *   永久归属留在车机。借用时 `entity` 仍是手机（见 `AirPlayResourceState.Resource.borrowForPhone`），
 *   音频流照常建；区别只在 `modesChanged` 里永久归属留车机、多一条借用标记。
 * - [BOTH]（A+B）：两条一起开。
 */
enum class AudioCompatExperiment {
    OFF,
    SKIP_NAV,
    BORROW_AUDIO,
    BOTH,
    ;

    /** A 生效：跳过导航独立流。 */
    val skipNav: Boolean
        get() = this == SKIP_NAV || this == BOTH

    /** B 生效：媒体主音频改临时借用。 */
    val borrowAudio: Boolean
        get() = this == BORROW_AUDIO || this == BOTH

    /** 本档是否开着实验（无车型限制）。 */
    val isEnabled: Boolean
        get() = this != OFF

    /** 界面上的短标签。 */
    val label: String
        get() =
            when (this) {
                OFF -> "关"
                SKIP_NAV -> "A 跳过导航流"
                BORROW_AUDIO -> "B 借用主音频"
                BOTH -> "A+B 都开"
            }

    /** 界面上的一句话说明。 */
    val detail: String
        get() =
            when (this) {
                OFF -> "原样：媒体 + 导航两条流，媒体永久申请主音频。"
                SKIP_NAV -> "只建媒体流，不建 t101/default 导航流（导航 TTS 将无声）。下次连接起效。"
                BORROW_AUDIO -> "媒体主音频改临时借用（永久归属留车机），专治点音乐后立刻被拆链。下次连接起效。"
                BOTH -> "同时跳过导航流 + 媒体改临时借用。下次连接起效。"
            }

    /** 点一下切下一档。 */
    fun next(): AudioCompatExperiment = entries[(ordinal + 1) % entries.size]

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY = "audio_compat_experiment_v1"

        /**
         * 进程内当前档位。启动时 [load] 一次；界面拨动时 [save] 立即改字段。
         * 与 `CarGpsOption` / `VideoCanvasMode` 同一套做法。
         */
        @Volatile
        var current: AudioCompatExperiment = OFF
            private set

        fun load(context: Context) {
            current = parse(
                context
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY, null),
            )
        }

        fun save(
            context: Context,
            value: AudioCompatExperiment,
        ) {
            current = value
            context
                .applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, value.name)
                .apply()
        }

        fun parse(raw: String?): AudioCompatExperiment =
            entries.firstOrNull { it.name == raw } ?: OFF

        /** 是否开了实验（无车型限制——拨了就生效）。 */
        val enabled: Boolean
            get() = current.isEnabled
    }
}
