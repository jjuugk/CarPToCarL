package io.github.shaonialife.carptocar.audio

import android.content.Context

/**
 * 媒体音频采样率策略（跟随车机 / 48kHz / 44.1kHz）。
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
 *  - 排序 `audioCandidates`，让 SETUP 优先选同一采样率的档位。
 *
 * 三档：
 *  - [AUTO]：**默认，跟随车机**——不下发 featureConfig，排序按引擎最近一次宣告的
 *    实际采样率（[noteAnnouncedMediaRate]）；还没收到宣告时退回 48k（旧顺序）。
 *    车机只声明 44.1k 就只有 44.1k 档可协商，只声明 48k 就只有 48k 档，天然跟随；
 *    两档都声明时（奥迪 `100/media` = `0x8800`）才由这里的宣告值决定先试哪个。
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
                AUTO -> "跟随车机"
                RATE_48K -> "48 kHz"
                RATE_44K1 -> "44.1 kHz"
            }

    /** 界面上的一句话说明。 */
    val detail: String
        get() =
            when (this) {
                AUTO -> "车机给 44.1k 就用 44.1k，给 48k 就用 48k（两档都支持时按引擎实际输出，未收到宣告则 48k）"
                RATE_48K -> "要求 CarLife 引擎输出 48k PCM，并优先协商 48k 音频档，避免重采样"
                RATE_44K1 -> "要求 CarLife 引擎输出 44.1k PCM，并优先协商 44.1k 音频档，避免重采样"
            }

    /**
     * 98386 featureConfig `MEDIA_SAMPLE_RATE` 的取值。
     *
     * 引擎判定：`getSampleRateSupported() == 1` → 48k，否则 44.1k。
     * null = 不下发这一项（[AUTO]，让引擎按车机协商出来的持久化偏好走）。
     */
    val mediaSampleRateFeatureValue: Int?
        get() =
            when (this) {
                AUTO -> null
                RATE_48K -> 1
                RATE_44K1 -> 0
            }

    /**
     * 候选档排序时优先的采样率（Hz）。
     *
     * [AUTO] = 跟随车机：用引擎最近一次宣告的媒体采样率（也就是车机协商出来的值）。
     * 还没收到宣告（首次连接、音乐没播过）或宣告了非 44.1k/48k 的怪值时退回 48k，
     * 与设置加入前的顺序一致，不会因为拿不到数据就改变行为。
     */
    fun preferredRate(announced: Int? = announcedMediaRate()): Int =
        when (this) {
            AUTO -> announced?.takeIf { it == RATE_44K1_HZ || it == RATE_48K_HZ } ?: RATE_48K_HZ
            RATE_48K -> RATE_48K_HZ
            RATE_44K1 -> RATE_44K1_HZ
        }

    /** 循环到下一个取值（界面点一下切一档）。 */
    fun next(): AudioRatePreference = entries[(ordinal + 1) % entries.size]

    companion object {
        const val RATE_44K1_HZ: Int = 44_100
        const val RATE_48K_HZ: Int = 48_000

        /**
         * 按 [preferRate] 重排候选档：把该采样率的档提到最前，其余保持原顺序。
         *
         * 只重排、不删除——车机若实际只接受另一率，后面的档仍可作兜底，避免无声。
         * 用 `partition` 而不是 `sortedBy`：结果稳定，AAC/PCM 两大组的相对顺序不受影响。
         * 纯函数，供 `AirPlayClient.audioCandidates` 与单测使用。
         */
        fun reorderCandidates(
            candidates: List<AudioProfile>,
            preferRate: Int,
        ): List<AudioProfile> {
            if (candidates.size < 2) return candidates
            val (matching, others) = candidates.partition { it.sampleRate == preferRate }
            return if (matching.isEmpty()) candidates else matching + others
        }

        private const val PREFS_NAME = "settings"
        private const val KEY = "audio_rate_preference"

        /**
         * 引擎最近一次宣告的媒体采样率（CarLife `MUSIC_PKG_INIT` 的 field1）。
         *
         * 0 = 还没收到过（或宣到了 44.1k/48k 以外的值，不采信）。跨会话保留：
         * 引擎的 `is48k` 是持久化偏好，**上一次**协商出来的值就是这一次要跟随的目标——
         * 而建流（SETUP）发生在音乐开播之前，本次宣告还没到，只能靠这个历史值。
         */
        @Volatile
        private var announcedMediaRate: Int = 0

        /** 记录引擎宣告的媒体采样率；只认 44.1k / 48k，其它值忽略。 */
        fun noteAnnouncedMediaRate(rate: Int) {
            if (rate == RATE_44K1_HZ || rate == RATE_48K_HZ) announcedMediaRate = rate
        }

        /** 引擎最近宣告的采样率；未知（从未收到）返回 null。 */
        fun announcedMediaRate(): Int? = announcedMediaRate.takeIf { it > 0 }

        /** 清空宣告值。仅供单测隔离使用。 */
        fun resetAnnouncedMediaRate() {
            announcedMediaRate = 0
        }

        /** 读当前设置；未写过 / 值非法都按 [AUTO]（默认跟随车机）。 */
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
