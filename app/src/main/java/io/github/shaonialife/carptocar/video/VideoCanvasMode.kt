package io.github.shaonialife.carptocar.video

import android.content.Context

/**
 * VideoConfig 头里 `params[1]` 画布声明的取值模式。
 *
 * 这条链路一直有两种说法，一直没被干净地对比过：
 *
 * - **车机画布（默认）**：声明车机 `/info` 通告的尺寸（奥迪 MIB3 = 1412×654）。
 *   真车按声明建解码器与 surface，声明与它的画布一致才对得上；
 * - **码流尺寸**：声明引擎实际编出来的尺寸（通常 1280×720，和 SPS 一致）。
 *
 * 2026-11-04 的难点正好卡在这里：CarLife 引擎拒绝按 1412×654 编码（65544 会
 * 原样回显申请值，但 SPS 仍是 1280×720），于是「画布 1412×654 + 码流 720p」
 * 这个组合永远对不上。要么让码流服从画布（转码，代价大），要么让声明服从码流
 * （车机自己缩放，代价几乎为零）——后者就是 [declareStream] = true。
 *
 * 偏好 `videoconfig_canvas_mode`：`stream` / `car`（默认随构建常量）。
 * 纯实验开关，改完需重连（会话建立时读取一次）。
 */
object VideoCanvasMode {

    private const val PREFS = "video_canvas_mode_v1"
    private const val KEY = "declare"

    /**
     * true = 声明码流尺寸（等于 SPS）；false = 声明车机画布。
     *
     * 进程启动时从偏好加载一次；会话建立时读取，不随会话中途变化。
     */
    @Volatile
    var declareStream: Boolean = false
        private set

    fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        declareStream = parse(raw ?: DEFAULT_MODE)
    }

    fun save(context: Context, mode: String) {
        val normalized = if (parse(mode)) MODE_STREAM else MODE_CAR
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, normalized)
            .apply()
        declareStream = parse(normalized)
    }

    /** 给运行日志用的一句话。 */
    fun describe(): String =
        if (declareStream) "码流尺寸（与 SPS 一致，由车机缩放）" else "车机画布（/info 通告值）"

    /** 解析偏好值；无法识别时回退到默认（车机画布）。 */
    internal fun parse(raw: String?): Boolean =
        when (raw?.trim()?.lowercase()) {
            MODE_STREAM -> true
            MODE_CAR -> false
            else -> DEFAULT_MODE == MODE_STREAM
        }

    internal const val MODE_STREAM = "stream"
    internal const val MODE_CAR = "car"

    /**
     * 本构建的默认值。
     *
     * 2026-11-04 实验：引擎已证实不肯按 1412×654 编码（65544 回显≠SPS），
     * 「画布 1412×654 + 码流 720p」必然对不上，所以这一版默认改成声明码流尺寸，
     * 让车机自己去缩放——用一次实车连接就能判断这条路通不通。
     */
    internal const val DEFAULT_MODE = MODE_STREAM
}
