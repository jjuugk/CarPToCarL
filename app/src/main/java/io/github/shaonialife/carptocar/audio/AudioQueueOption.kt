package io.github.shaonialife.carptocar.audio

import android.content.Context

/**
 * 音频队列治理开关（默认关闭，对所有车型生效）。
 *
 * 借鉴 AMcar 的 `AudioQueueGovernor`：稳态积压 / 车机欠载时加深媒体队列 / 静音关流。
 * 目标是缓解「车机侧偶发欠载导致的卡顿」——**不是**修复 RTP 链路丢包（那是传输层问题，
 * 加缓冲治不好，见项目长期结论）。所以定位为「音频卡顿时可开启尝试」的实验档。
 *
 * 关闭时 [AudioSender] 走原来的同步直发路径（攒满一帧即发），字节与时间轴完全不变；
 * 开启时才启用单写者泵线程 + 分帧队列。下次连接起效。
 */
object AudioQueueOption {
    private const val PREFS_NAME = "settings"
    private const val KEY = "audio_queue_governor_v1"

    /** 进程内当前值。启动时 [load] 一次；界面拨动时 [save] 立即改字段。 */
    @Volatile
    var enabled: Boolean = false
        private set

    fun load(context: Context) {
        enabled =
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY, false)
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

    /** 界面文案。 */
    val label: String
        get() = if (enabled) "开" else "关"

    val note: String
        get() =
            if (enabled) {
                "已开启：媒体流 12 帧积压，车机报欠载时升到 25 帧并保持 30s；连续 3s 静音暂停入队。下次连接起效。"
            } else {
                "默认关，原样逐帧直发。音频卡顿时可开启尝试（加缓冲不能修链路丢包）。下次连接起效。"
            }
}
