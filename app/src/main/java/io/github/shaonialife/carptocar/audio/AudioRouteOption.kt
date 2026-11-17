package io.github.shaonialife.carptocar.audio

import android.content.Context

/**
 * 「音频走 PCM 直通」开关（偏好 `audio_route_option_v1`，默认**关**）。
 *
 * ## 它控制什么
 *
 * 只控制音频档位的**排序**（见 `AirPlayClient.audioCandidates`）：
 *
 * - 关（默认）：AAC-LC（t102）优先，失败再退 PCM —— 也就是一直以来的行为。
 * - 开：PCM（t100）优先，**仍然保留 AAC 兜底**。PCM 全失败才会去试 AAC。
 *
 * ## 为什么需要它
 *
 * 2026-11-17 的 1.2.65 日志里，AAC 通路的每一项硬指标都正常：
 *
 * ```
 * 发送        47991 样本/秒（声明 48000，差 -0.02%）
 * 接收端消费  47999.9 样本/秒（/feedback 的 sampleTime 反推，精确 48000）
 * 端到端延迟  290ms，5 次采样偏差 <15ms
 * 锚点        30/30 全部送达
 * 冻结/丢包   0 / 0
 * ```
 *
 * 数据全对，听感却是「一顿一顿」。这种形态只可能出在**码流本身**或**接收端解码**上，
 * 而手机上唯一能绕开它的手段就是不走 AAC —— PCM 直通把编码器整个去掉：没有 1024 样本
 * 的成批吐帧、没有编码延迟、没有"接收端拿裸 AAC 帧自己解"这一环。
 *
 * 所以这个开关的真正用途是**二分**，不是"更好的默认值"：
 *
 * - 打开后不顿 → 问题在 AAC 编码/解码链路，接着查 ct/sr、profile、priming；
 * - 打开后照样顿 → 与编码无关，回去查混音供电与网络（UDP 丢包、PC 侧播放器）。
 *
 * 默认关的理由：真车（奥迪 MIB3 等）已经验证过 AAC 通路能出声，把默认改掉等于拿能用的
 * 场景去赌一个还没验证的假设。而 t100 推 PCM 在部分车机上会「SETUP 200、给 dataPort、
 * 但 controlPort=0、sampleTime 恒 0，约 6.5 秒后整条会话被拆」（见 `audioCandidates`
 * 注释）——那种车机打开这个开关只会更糟，所以必须留在用户手里。
 *
 * 生效时机：**下次建音频流**（含语音键三连击重启音频）才换档，当前会话不切换。
 */
object AudioRouteOption {
    private const val PREFS = "audio_route_option_v1"
    private const val KEY_PREFER_PCM = "prefer_pcm"
    private const val DEFAULT_PREFER_PCM = false

    /**
     * 是否优先 PCM。用 `@Volatile` 而不是每次读盘：建音频流时要立刻拿到，
     * [load]/[save] 是唯一写入口。
     */
    @Volatile
    var preferPcm: Boolean = DEFAULT_PREFER_PCM
        private set

    /** 启动时调用一次（[io.github.shaonialife.carptocar.SenderApp]）。 */
    fun load(context: Context?) {
        val app = context?.applicationContext ?: return
        preferPcm =
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PREFER_PCM, DEFAULT_PREFER_PCM)
    }

    /** 开关切换时调用（UI 层）。下一次建音频流按新值走。 */
    fun save(
        context: Context?,
        value: Boolean,
    ) {
        preferPcm = value
        val app = context?.applicationContext ?: return
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PREFER_PCM, value)
            .apply()
    }
}
