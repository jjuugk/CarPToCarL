package io.github.shaonialife.carptocar.video

import android.content.Context

/**
 * 会话建立瞬间**首帧从哪来**的策略。
 *
 * ## 背景（两轮实车对着看）
 *
 * 2026-11-06 18:36 同源项目（AMCar 1.0.17）在一台奥迪 CL39 上成功的日志：
 *
 * ```
 * 18:36:08.224 AIRPLAY: ✓ RECORD 完成——会话已激活
 * 18:36:08.228 VIDEO: 帧 #1 9394B (key) 已发送      ← RECORD 后 4 毫秒
 * 18:36:08.247 CARLIFE: 已补 39 帧历史画面（实际推送 39）
 * ```
 *
 * 我们同日 16:44（奥迪 3810 / CL3D）：
 *
 * ```
 * 16:44:39.649 AIRPLAY: ✓ RECORD 完成——会话已激活
 * 16:44:44.662 VIDEO: 直通模式启动
 * 16:44:45.181 VIDEO: 帧 #1 178771B (key) 已发送    ← RECORD 后 5.5 秒
 * ```
 *
 * 也就是说 1.2.25 的「清空缓存、等引擎当期 IDR」把首帧推迟了 5 秒多。而 4580 的
 * HMI 在屏幕流 SETUP 之后只等很短一段时间就要拿画面（`docs/mib3-carplay-display-contract.md`
 * 里 `badFramesThreshold=4`、OMX 对非法头帧直接 flush）；会话不会因此被拆，但
 * **让屏窗口可能已经过去**——这正是「连上了、声音有、屏全黑」的一种合理解释。
 *
 * ## 两种策略
 *
 * - [useCachedGop] = true（默认）：attach 时如果缓存 GOP 的首帧是**自带 SPS 的关键帧**，
 *   就直接回放它起播（自洽：SPS 与参考帧来自同一代编码器），RECORD 后立即有画面；
 * - [useCachedGop] = false：回到 1.2.25 行为——丢掉缓存，等引擎当期 IDR（约 1~5s）。
 *
 * 为什么限定「自带 SPS」：不带 SPS 的旧 IDR 配上缓存 csd 才可能出现「旧 SPS + 新参考帧」
 * 的混搭，那正是 1.2.25 想躲的坑；自带 SPS 的帧不存在这个风险。
 *
 * 偏好 `first_frame_policy_v1`：出问题可一键回退，不必重新出包。
 */
object FirstFramePolicy {
    private const val PREFS = "first_frame_policy_v1"
    private const val KEY_CACHED_GOP = "use_cached_gop"

    /** 本构建的默认值：立即起播（对齐 AMCar 的实车行为）。 */
    internal const val DEFAULT_USE_CACHED_GOP = true

    /**
     * true = RECORD 后用缓存关键帧立即起播；false = 等引擎当期 IDR（1.2.25 行为）。
     * 进程启动时载入一次，会话建立时读取。
     */
    @Volatile
    var useCachedGop: Boolean = DEFAULT_USE_CACHED_GOP
        private set

    fun load(context: Context) {
        useCachedGop =
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CACHED_GOP, DEFAULT_USE_CACHED_GOP)
    }

    fun save(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CACHED_GOP, enabled)
            .apply()
        useCachedGop = enabled
    }

    /** 给运行日志用的一句话。 */
    fun describe(): String =
        if (useCachedGop) {
            "缓存关键帧立即起播（RECORD 后立刻有画面；仅当缓存 IDR 自带 SPS）"
        } else {
            "等引擎当期 IDR（1.2.25 行为；RECORD 后可能空等数秒）"
        }

    /**
     * 这一轮该不该用缓存 GOP 起播（纯函数，单测覆盖）。
     *
     * 三个条件缺一不可：策略打开、缓存首帧是关键帧、且它**自带 SPS**。
     * 最后一条是安全阀——不带 SPS 的旧 IDR 只能配缓存 csd，
     * 那正是 1.2.25 想躲的「旧 SPS + 新参考帧」混搭。
     */
    fun shouldReplay(
        policyEnabled: Boolean = useCachedGop,
        firstFrameIsKey: Boolean,
        firstFrameHasSps: Boolean,
    ): Boolean = policyEnabled && firstFrameIsKey && firstFrameHasSps
}
