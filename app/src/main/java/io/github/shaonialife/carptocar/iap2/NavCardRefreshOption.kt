package io.github.shaonialife.carptocar.iap2

import android.content.Context

/**
 * 媒体卡片（音频通道 `0x5001` NowPlaying）上「导航箭头图 + 转向文案」的刷新策略。
 *
 * ## 这条链路为什么会刷得太密
 *
 * 导航期间媒体卡片由 `pushNowPlayingGuide()` 维护，去重键是 `title|road`；
 * 而 `title` 由 `NavigationText.maneuver(remainDistance, 转向名)` 生成，**带剩余距离**。
 * 于是「300 米右转」变成「299 米右转」就是一次 `0x5001`——CarLife 引擎与高德
 * 各自 1Hz 往上推，车机每秒收一条，原先**没有任何频率上限**。
 *
 * 注意区分两件事：
 * - **报文次数**由这里的 [intervalMillis] 限制（默认 3 秒）。
 * - **箭头图本身**按像素字节复用同一个车机编号，图**不会**重复传输；
 *   车上真正收到新图只在转向形状变化时（12 种形状）。由 [reuseArtwork] 控制。
 *
 * ## 两个开关的默认值
 *
 * - [intervalMillis] 默认 3 秒；另有「不限」一档可退回改动前的逐条发送。
 * - [reuseArtwork] 默认**开**（相同图不换编号，不重复传图）。
 *   关掉后每次刷新都换新编号，车机会重新拉一次图——用于排查「箭头图不跟着变」，
 *   代价是图片会被反复传输。这就是「避免有些人觉得更新慢」的那条退路。
 */
object NavCardRefreshOption {
    private const val PREFS_NAME = "settings"
    private const val KEY_INTERVAL = "nav_card_refresh_ms"
    private const val KEY_REUSE_ART = "nav_card_reuse_artwork"

    /** 默认 3 秒。 */
    const val DEFAULT_MILLIS = 3_000L

    const val MIN_MILLIS = 1_000L
    const val MAX_MILLIS = 15_000L

    /** 「不限」= 改动前行为（内容一变就发）。 */
    const val UNLIMITED = 0L

    /** 界面可选项：1..15 秒，最后是「不限」。 */
    val STEPS: List<Long> = (1..15).map { it * 1_000L } + UNLIMITED

    /** 最小刷新间隔（毫秒）。改设置立即生效，不需要重连。 */
    @Volatile
    var intervalMillis: Long = DEFAULT_MILLIS
        private set

    /** 相同箭头图是否沿用同一车机编号（默认开 = 不重复传图）。 */
    @Volatile
    var reuseArtwork: Boolean = true
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        intervalMillis = normalize(prefs.getLong(KEY_INTERVAL, DEFAULT_MILLIS))
        reuseArtwork = prefs.getBoolean(KEY_REUSE_ART, true)
    }

    fun saveInterval(
        context: Context,
        value: Long,
    ) {
        val next = normalize(value)
        intervalMillis = next
        prefs(context).edit().putLong(KEY_INTERVAL, next).apply()
    }

    fun saveReuseArtwork(
        context: Context,
        value: Boolean,
    ) {
        reuseArtwork = value
        prefs(context).edit().putBoolean(KEY_REUSE_ART, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 落盘/加载时的兜底：越界夹到区间，非「不限」的对齐到整秒。 */
    fun normalize(value: Long): Long =
        when {
            value <= UNLIMITED -> UNLIMITED
            value < MIN_MILLIS -> MIN_MILLIS
            value > MAX_MILLIS -> MAX_MILLIS
            else -> (value / 1_000L) * 1_000L
        }

    fun label(value: Long = intervalMillis): String =
        if (value <= UNLIMITED) "不限" else "${value / 1_000} 秒"

    fun note(value: Long = intervalMillis): String =
        if (value <= UNLIMITED) {
            "不限：内容一变就发（改动前行为）。距离开导航时车机每秒收一条 0x5001。"
        } else {
            "每 ${value / 1_000} 秒最多刷新一次媒体卡片上的导航箭头与文案（默认 3 秒，" +
                "可选 1–15 秒）。箭头图本身只在转向形状变化时换，不会重复传输。"
        }

    fun reuseArtworkNote(reuse: Boolean = reuseArtwork): String =
        if (reuse) {
            "开（默认）：同一张箭头图沿用同一编号，车机走缓存、不重复收图。"
        } else {
            "关：每次刷新都换新编号，车机会重新拉一次箭头图。" +
                "仅在「箭头图不跟着转向变化」时临时关掉排查，代价是图片反复传输。"
        }
}
