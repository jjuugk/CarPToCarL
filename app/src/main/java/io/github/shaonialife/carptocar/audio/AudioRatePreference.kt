package io.github.shaonialife.carptocar.audio

import android.content.Context

/**
 * 媒体音频采样率策略（自动 / 48kHz / 44.1kHz）。
 *
 * ## 为什么需要它
 *
 * CarLife 引擎的媒体 PCM 采样率不是恒定的：反编译 `carlife.apk` 证实
 * `MusicAudioHelper.getSampleRate()` 读 `is48k` 偏好（默认来自车机下发的
 * `MEDIA_SAMPLE_RATE` featureConfig），**48k（0xbb80）与 44.1k（0xac44）都会出现**。
 * 而 AirPlay 音频档的采样率由车机 `/info` 声明、SETUP 协商决定。
 * 两者不一致时 `AudioSender.alignPcm` 被迫重采样——双晶振时钟域漂移在播放
 * 数分钟后累计成「一顿一顿」（2026-11-06 Q4 3873 结论；当时误以为引擎恒出 48k，
 * 把「48K 优先」写死，实际多车实车日志显示引擎出 44.1k，反而引入 44.1→48 重采样）。
 *
 * 本设置同时作用于两端，让它们对齐：
 *  - 下发 98386 featureConfig 的 `MEDIA_SAMPLE_RATE`，指示引擎按指定率输出 PCM；
 *  - 过滤/排序 `audioCandidates`，让 SETUP 优先选同一采样率的档位。
 *
 * 三档：
 *  - [AUTO]：**默认，保持旧行为**——不改 featureConfig、不改候选顺序（完全等同设置加入前）；
 *  - [RATE_48K]：宣告 48k、优先 48k 档；
 *  - [RATE_44K1]：宣告 44.1k、优先 44.1k 档。
 *
 * 注意：引擎的 `is48k` 是持久化偏好，**只在从未写过它时**才用我们下发的
 * `MEDIA_SAMPLE_RATE` 作为默认；若用户在 CarLife 设置里手动选过，则以偏好为准，
 * 本设置只能影响 AirPlay 侧候选（仍会显示实际协商频率供核对）。
 */
enum class AudioRatePreference {
    AUTO,
    RATE_48K,
    RATE_44K1,
    ;

    /** 界面与日志用的短名。 */
    val label: String
        get() =
            when (this) {
                AUTO -> "自动"
                RATE_48K -> "48 kHz"
                RATE_44K1 -> "44.1 kHz"
            }

    /** 界面上的一句话说明。 */
    val detail: String
        get() =
            when (this) {
                AUTO -> "不改引擎采样率，也不改音频档顺序（原有行为）"
                RATE_48K -> "要求 CarLife 引擎输出 48k PCM，并优先协商 48k 音频档，避免重采样"
                RATE_44K1 -> "要求 CarLife 引擎输出 44.1k PCM，并优先协商 44.1k 音频档，避免重采样"
            }

    /**
     * 98386 featureConfig `MEDIA_SAMPLE_RATE` 的取值。
     *
     * 引擎判定：`getSampleRateSupported() == 1` → 48k，否则 44.1k。
     * null = 不下发这一项（[AUTO]，保持旧行为）。
     */
    val mediaSampleRateFeatureValue: Int?
        get() =
            when (this) {
                AUTO -> null
                RATE_48K -> 1
                RATE_44K1 -> 0
            }

    /** 期望的音频档采样率（Hz）；[AUTO] 为 null（不干预候选顺序）。 */
    val targetSampleRate: Int?
        get() =
            when (this) {
                AUTO -> null
                RATE_48K -> 48_000
                RATE_44K1 -> 44_100
            }

    /**
     * 按本设置重排候选档：把目标采样率的档提到最前，其余保持原顺序。
     *
     * 只重排、不删除——车机若实际只接受另一率，后面的档仍可作兜底，避免无声。
     * [AUTO] 原样返回。纯函数，便于单测。
     */
    fun reorderCandidates(candidates: List<AudioProfile>): List<AudioProfile> {
        val rate = targetSampleRate ?: return candidates
        val (matching, others) = candidates.partition { it.sampleRate == rate }
        return matching + others
    }

    /** 循环到下一个取值（界面点一下切一档）。 */
    fun next(): AudioRatePreference = entries[(ordinal + 1) % entries.size]

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY = "audio_rate_preference"

        /** 读当前设置；未写过 / 值非法都按 [AUTO]（保持旧行为）。 */
        fun load(context: Context?): AudioRatePreference {
            if (context == null) return AUTO
            val raw =
                runCatching {
                    context
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(KEY, null)
                }.getOrNull() ?: return AUTO
            return parse(raw)
        }

        fun save(
            context: Context?,
            pref: AudioRatePreference,
        ) {
            if (context == null) return
            runCatching {
                context
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY, pref.name)
                    .apply()
            }
        }

        /** 持久化值 → 枚举：大小写不敏感，认不出就回 [AUTO]。 */
        fun parse(raw: String?): AudioRatePreference {
            val key = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.name.lowercase() == key } ?: AUTO
        }
    }
}
