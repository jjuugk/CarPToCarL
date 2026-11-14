package io.github.shaonialife.carptocar.audio

import android.content.Context

/**
 * 导航播报（CarLife TTS 通道 9241）在车机侧的落点。
 *
 * ## 为什么需要这个开关
 *
 * AirPlay 的 `audioType` 枚举里**没有 navigation**——全集是
 * `media` / `alert` / `telephony` / `speechRecognition` / `default` / `compatibility`
 * （见 `docs/CARPLAY-WIRELESS-HEADUNIT-SPEC.md`）。也就是说「让车机单独给导航一条
 * 音量曲线」在协议层没有正规入口，Apple 自己的做法也是手机侧混音 + ducking。
 *
 * 但「协议没说」不等于「车机没实现」。部分固件对 `alert` / `speechRecognition`
 * 档有自己的增益与路由，值不值得赌只能用单变量实验回答。这个枚举就是那个实验的
 * 三个取值，改完重启连接即生效：
 *
 *  - [MIX]：与媒体混成一条流（**默认**）。当前行为，AirPlay/CarPlay 的标准做法。
 *  - [ALERT]：独立流，`audioType=alert`。车机的提示音/铃声档。
 *  - [SPEECH]：独立流，`audioType=speechRecognition`。车机的 Siri 档。
 *
 * 后两个都是实验性质，失败一律**自动回退** [MIX]（见
 * `SessionController.startNavAudioChannel`）：车机没声明该档、SETUP 被拒、发送端
 * 起不来，都只打一行日志，导航声音照旧从混音流出去——不会变成「导航没声音」。
 */
enum class NavAudioRoute {
    MIX,
    ALERT,
    SPEECH,
    ;

    /**
     * 这条路由要建的那条独立流用哪个 `audioType`；[MIX] 返回 null（不建流）。
     *
     * 取值必须与 `AirPlayClient.AUDIO_TYPE_*` 常量逐字节一致——车机按字符串匹配档位。
     */
    val audioType: String?
        get() =
            when (this) {
                MIX -> null
                ALERT -> "alert"
                SPEECH -> "speechRecognition"
            }

    /** 界面与日志用的短名。 */
    val label: String
        get() =
            when (this) {
                MIX -> "混音"
                ALERT -> "alert 独立流"
                SPEECH -> "speechRecognition 独立流"
            }

    /** 界面上的一句话说明（切换后提示用户会付出什么）。 */
    val detail: String
        get() =
            when (this) {
                MIX -> "导航与音乐混成一条流，播报时自动压低音乐，车机只有一个音量（标准做法）"
                ALERT ->
                    "导航走车机的提示音档，可能单独调音量；音乐不再自动压低，" +
                        "没声明该档的车机自动回退混音"
                SPEECH ->
                    "导航走车机的语音助手档，可能被当成 Siri（抢麦、弹语音界面）；" +
                        "没声明该档自动回退混音"
            }

    /** 循环到下一个取值（界面点一下切一档）。 */
    fun next(): NavAudioRoute = entries[(ordinal + 1) % entries.size]

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY = "nav_audio_route"

        /** 读当前设置；未写过 / 值非法都按 [MIX]（保持旧行为）。 */
        fun load(context: Context?): NavAudioRoute {
            if (context == null) return MIX
            val raw =
                runCatching {
                    context
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(KEY, null)
                }.getOrNull() ?: return MIX
            return parse(raw)
        }

        fun save(
            context: Context?,
            route: NavAudioRoute,
        ) {
            if (context == null) return
            runCatching {
                context
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY, route.name)
                    .apply()
            }
        }

        /** 持久化值 → 枚举：大小写不敏感，认不出就回 [MIX]（绝不因为脏数据建错流）。 */
        fun parse(raw: String?): NavAudioRoute {
            val key = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.name.lowercase() == key } ?: MIX
        }
    }
}
