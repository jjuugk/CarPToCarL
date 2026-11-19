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
 * 但「协议没说」不等于「车机没实现」。部分固件对 `speechRecognition` 档有自己的
 * 增益与路由，值不值得赌只能用单变量实验回答。这个枚举就是那个实验的两个取值，
 * 改完重启连接即生效：
 *
 *  - [MIX]：与媒体混成一条流（**默认**）。当前行为，AirPlay/CarPlay 的标准做法。
 *  - [SPEECH]：独立流，`audioType=speechRecognition`。车机的 Siri 档。
 *  - [DIRECT]：**只推音乐**（1.2.71 新增，实车排查用）。不进混音器，媒体 PCM 到齐
 *    一帧（352 样本）就封包发走；导航播报（9241）照样连接，但收到的 PCM 全部丢弃。
 *    见下面「为什么要有 DIRECT」。
 *
 * ## 为什么要有 DIRECT（2026-11-19）
 *
 * 混音器是为了解决「两路同时推、RTP 时钟 2×」而生的，代价是一整套状态机（定拍、
 * 空载补静音、duck、水位裁剪、暂停 15 秒窗口）。排查「音乐本身就不对」时，
 * 这套机制自己就是变量：到底是引擎的 PCM 有问题，还是泵/混音把它弄坏了，日志分不清。
 *
 * DIRECT 把这层全部摘掉：**一条通道、一个读线程、到齐一帧就发**，剩下的只有重采样
 * 和封包。音乐仍走 9240；导航播报（9241）在这个档位下**连接照建、PCM 全丢**——车机上
 * 听不到导航，这是刻意的取舍，不是缺陷。**不要为了省这一路去少连端口**：CarLife 的
 * 会话是按端口集合认的，缺一个就有被引擎/车联侧判成「不完整」、迟迟不建虚拟屏的风险
 * （2026-11-19 试过不连 9241，结果引擎确认 98311、回「启动推流」，然后一直 0 帧）。
 * 它也不是「更好的默认值」：没有定拍泵就没有空载静音帧，
 * 引擎不推 PCM 时车机侧 jitter buffer 会被排空，恢复播放可能卡一下。
 *
 * ## alert 档已被移除（1.2.58，实车证伪）
 *
 * 2026-11-16 的 AUDI 4260（DELPHI）日志里，选了 `alert` 的车机会话**每一轮都在
 * 建流后整条断掉**：主音频 SETUP 200 之后发 `t100/alert/0x8000 PCM` 的 SETUP →
 * **400**（补 `ct/sr` 重试仍是 400）→ 77ms 后车机 reset 事件通道 → 视频端口
 * ECONNREFUSED → 控制通道关闭 → RECORD 失败 → 蓝牙 iAP2 断开。
 * 没选 alert 的两轮（speechRecognition / 混音）都稳定运行到手动断开。
 * 结论：这台车机把「不合法的 alert SETUP」当成**会话级致命错误**，不是只忽略那条流；
 * 代价是掉线，所以这个选项不能留着让人误点。
 *
 * [SPEECH] 保留是因为它的失败模式是无害的：车机没声明该档、或该档位图不含我们
 * 要的 PCM 格式，就不会建流，只打一行日志，导航声音照旧从混音流出去
 * （见 `SessionController.startNavAudioChannel` 与 `AudioProfilePicker`）。
 */
enum class NavAudioRoute {
    MIX,
    SPEECH,
    DIRECT,
    ;

    /**
     * 这条路由要建的那条独立流用哪个 `audioType`；[MIX] / [DIRECT] 返回 null（不建流）。
     *
     * 取值必须与 `AirPlayClient.AUDIO_TYPE_*` 常量逐字节一致——车机按字符串匹配档位。
     */
    val audioType: String?
        get() =
            when (this) {
                MIX -> null
                SPEECH -> "speechRecognition"
                DIRECT -> null
            }

    /** 界面与日志用的短名。 */
    val label: String
        get() =
            when (this) {
                MIX -> "混音"
                SPEECH -> "speechRecognition 独立流"
                DIRECT -> "只推音乐（直通）"
            }

    /** 直通档：播报 PCM 丢弃、不混音。音频链路上一切「智能」都让位给「把 PCM 原样送出去」。 */
    val isDirect: Boolean get() = this == DIRECT

    /** 界面上的一句话说明（切换后提示用户会付出什么）。 */
    val detail: String
        get() =
            when (this) {
                MIX -> "导航与音乐混成一条流，播报时自动压低音乐，车机只有一个音量（标准做法）"
                SPEECH ->
                    "导航走车机的语音助手档，可能被当成 Siri（抢麦、弹语音界面）；" +
                        "车机没声明该档、或该档不收 PCM 时自动回退混音"
                DIRECT ->
                    "只处理媒体 9240：不混音、不定拍，PCM 到齐一帧就发（**车机上听不到导航播报**）；" +
                        "排查「音乐本身不对」时用它把混音器排除在外"
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

        /**
         * 持久化值 → 枚举：大小写不敏感，认不出就回 [MIX]（绝不因为脏数据建错流）。
         *
         * 老版本存下的 `"ALERT"` 会走到这里并被回落成 [MIX]——正是要的效果：
         * 那个档位会让整条会话断掉，升级后不该还有任何路径能建它。
         */
        fun parse(raw: String?): NavAudioRoute {
            val key = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.name.lowercase() == key } ?: MIX
        }
    }
}
