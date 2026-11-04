package io.github.shaonialife.carptocar.video

import android.content.Context

/**
 * VideoConfig 头的**尺寸声明策略**。
 *
 * 头里有两组尺寸，含义不同，不能混：
 *
 * - `params[1]`（偏移 16）：**解码器**尺寸，必须与码流 SPS 一致；
 * - `params[3]`/`params[4]`（偏移 32/40）：**显示视口** x,y / 宽,高，4580 按它
 *   决定把画面贴到屏幕哪块矩形（缩放由车机做）。
 *
 * 两种策略：
 *
 * - **[declareStream] = true（默认，分离声明）**：解码尺寸报码流实际值
 *   （通常 1280×720，与 SPS 一致），视口报车机画布（1412×654）。CarLife 引擎
 *   拒绝按画布编码时，这条路不需要转码，缩放交给车机；
 * - **[declareStream] = false（旧行为）**：把解码尺寸直接写成车机画布，不写视口。
 *   只在「码流真的等于画布」时才成立；降档后必然与 SPS 打架
 *   （2026-11-04 13:48 实测被判 invalid header）。
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
        if (declareStream) {
            "分离声明（解码=码流尺寸 / 视口=车机画布，40-44 字段由车机缩放）"
        } else {
            "车机画布（解码尺寸直接写成 /info 通告值，不写视口字段）"
        }

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
