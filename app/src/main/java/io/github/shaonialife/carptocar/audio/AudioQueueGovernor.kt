package io.github.shaonialife.carptocar.audio

/**
 * 音频队列治理状态机（借鉴 AMcar `AudioQueueGovernor`，适配本项目的单流发送端）。
 *
 * ## 来源与取舍
 *
 * AMcar（xi_zhu/AMcar，Release 387153d）把三件事收进一个纯状态机：稳态积压、
 * 欠载加深、静音关流。它依赖「一个 AudioSender + 三路 source 共用队列」的结构；
 * 本项目是**每个 AudioSender 只有一路流**（媒体 / 导航各一个实例），所以这里
 * 去掉 source 参数，改成按实例构造时声明 `isMedia`：
 *
 * - 媒体流：稳态 [BASE_MEDIA_FRAMES] 帧，欠载升到 [UNDERFLOW_MEDIA_FRAMES] 帧，
 *   并启用静音关流（连续 [SILENCE_STOP_MILLIS] 无有效样本暂停入队）；
 * - 导航流：固定 [BASE_NAV_FRAMES] 帧，**不**做静音关流——导航播报本身有大段空白，
 *   按静音断流会把播报切断。
 *
 * ## 默认关闭
 *
 * 本类只描述策略、不含 Android 依赖（时钟可注入，便于 JVM 单测）。是否启用由
 * [AudioQueueOption] 决定；关闭时 [enabled]=false，[capacity] 回落到「只攒一帧即发」
 * 的旧语义，[noteUnderflow] 与 [observe] 全部旁路。
 *
 * ## 起流过滤窗口
 *
 * 停流/起流后 [UNDERFLOW_GAP_MILLIS] 内的欠载反馈不计入——那段时间重采样与编码器
 * 刚起步，车机侧的空窗属于正常现象，据此加深队列只会在每次起流后造成额外延迟。
 */
class AudioQueueGovernor(
    /** 是否媒体流：只有媒体流做欠载加深与静音关流。 */
    private val isMedia: Boolean = true,
    /** 默认时钟用 System.nanoTime：单调、纯 JVM，单测里构造 AudioSender 不会碰到「SystemClock not mocked」。 */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    enum class State { STEADY, UNDERFLOW_COOLDOWN }

    enum class Silence { AUDIBLE, SILENT, STOP }

    companion object {
        /** 媒体稳态积压（帧）：借鉴 AARE `LIVE_BACKLOG = 12`。 */
        const val BASE_MEDIA_FRAMES = 12

        /** 导航稳态积压（帧）：借鉴 AARE `MAX_QUEUED = 50`（约 1s）。 */
        const val BASE_NAV_FRAMES = 50

        /** 欠载后媒体积压（帧）：借鉴 AARE `UNDERFLOW_BACKLOG = 25`。 */
        const val UNDERFLOW_MEDIA_FRAMES = 25

        /** 欠载放大保持时长：AARE 30_000ms。 */
        const val UNDERFLOW_HOLD_MILLIS = 30_000L

        /** 静音判定阈值（16bit 样本绝对值）。 */
        const val SILENCE_THRESHOLD = 8

        /** 连续静音多久后暂停入队：3s。 */
        const val SILENCE_STOP_MILLIS = 3_000L

        /** 起流后的欠载过滤窗口：1s。 */
        const val UNDERFLOW_GAP_MILLIS = 1_000L
    }

    /** 回退开关：false 时容量回落到单帧，三态逻辑全部旁路。 */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            field = value
            if (!value) reset()
        }

    private var underflowUntilMs = 0L
    private var streamStartedAtMs = clock()
    private var silentSinceMs: Long? = null

    fun state(): State = if (enabled && isMedia && clock() < underflowUntilMs) State.UNDERFLOW_COOLDOWN else State.STEADY

    /** 目标积压（帧）：关闭时 1 帧（等价于旧的「攒满一帧即发」）。 */
    fun capacity(): Int {
        if (!enabled) return 1
        if (!isMedia) return BASE_NAV_FRAMES
        return if (state() == State.UNDERFLOW_COOLDOWN) UNDERFLOW_MEDIA_FRAMES else BASE_MEDIA_FRAMES
    }

    /**
     * 记录一次车机欠载反馈，返回是否被采纳。
     *
     * 起流后 1s 内的反馈视为正常空窗，丢弃；采纳后进入 30s 冷却，
     * 冷却期内重复反馈只续期，不叠加。
     */
    fun noteUnderflow(): Boolean {
        if (!enabled || !isMedia) return false
        val now = clock()
        if (now - streamStartedAtMs < UNDERFLOW_GAP_MILLIS) return false
        underflowUntilMs = now + UNDERFLOW_HOLD_MILLIS
        return true
    }

    /** 起流/重建音频流时调用：重置冷却与静音判定，开启 1s 欠载过滤窗口。 */
    fun onStreamStarted() {
        streamStartedAtMs = clock()
        underflowUntilMs = 0L
        silentSinceMs = null
    }

    /** 清空全部动态状态（开关关闭 / stop 时调用）。 */
    fun reset() {
        underflowUntilMs = 0L
        silentSinceMs = null
    }

    /**
     * 扫描一段 PCM（16bit 小端交织）判断有声/静音。
     *
     * 只对媒体流做静音关流：导航流本来就有整段空白，按 3s 关流会把导航播报切断。
     */
    fun observe(
        pcm: ByteArray,
        size: Int,
    ): Silence {
        if (!enabled || !isMedia) return Silence.AUDIBLE
        val validSize = size.coerceIn(0, pcm.size) and -2
        if (validSize == 0) return Silence.SILENT
        val now = clock()
        if (hasAudibleSample(pcm, validSize)) {
            silentSinceMs = null
            return Silence.AUDIBLE
        }
        val started = silentSinceMs ?: now.also { silentSinceMs = it }
        return if (now - started < SILENCE_STOP_MILLIS) Silence.SILENT else Silence.STOP
    }

    private fun hasAudibleSample(
        pcm: ByteArray,
        size: Int,
    ): Boolean {
        var index = 0
        // 顺序检查全部样本，有声立即返回；不按固定偶数步长抽样，以免漏掉右声道或短提示音。
        while (index + 1 < size) {
            val sample = ((pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xFF)).toShort().toInt()
            if (sample > SILENCE_THRESHOLD || sample < -SILENCE_THRESHOLD) return true
            index += 2
        }
        return false
    }
}
