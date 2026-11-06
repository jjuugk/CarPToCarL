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
     * **A/B 测试开关（1.2.25-test-1）**：true = 强制「不写 40/44 视口」，
     * 即回到 AuDiCar 1.1.1（实车能出画面）的 VideoConfig 头形态：
     * 只写 `params[1]`（车机画布）+ `Encrypted(0x04)`，32..48 全 0。
     *
     * 依据：catplay `ScreenFrame::config` 只写 params[1]+flags，其 `config_decode`
     * 里视口字段是**注释掉**的；`docs/videoconfig-canvas-contract.md` 也承认 40/44
     * 只有 AMCar 补丁一条旁证、固件里找不到车机读它的证据。而 1.2.25 的
     * CL39/CL3D/CL35 三档强制 `STREAM`（必写 40/44），用户偏好切不掉——所以
     * 单开一个优先级最高的开关，让现场能一键 A/B，而不改动任何档位常量。
     *
     * 关掉 = 完全回到 1.2.25 既有行为（档位/偏好照旧），用于对照。
     * 测试包默认开；正式包若合并此改动应把 [TEST_NO_VIEW_AREA_DEFAULT] 设为 false。
     */
    private const val KEY_TEST_NO_VIEW_AREA = "test_no_view_area"

    @Volatile
    var testNoViewArea: Boolean = true
        private set

    /**
     * true = 声明码流尺寸（等于 SPS）；false = 声明车机画布。
     *
     * 进程启动时从偏好加载一次；会话建立时读取，不随会话中途变化。
     */
    @Volatile
    var declareStream: Boolean = false
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        declareStream = parse(prefs.getString(KEY, null) ?: DEFAULT_MODE)
        testNoViewArea = prefs.getBoolean(KEY_TEST_NO_VIEW_AREA, TEST_NO_VIEW_AREA_DEFAULT)
    }

    fun saveTestNoViewArea(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TEST_NO_VIEW_AREA, enabled)
            .apply()
        testNoViewArea = enabled
    }

    fun save(context: Context, mode: String) {
        val normalized = if (parse(mode)) MODE_STREAM else MODE_CAR
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, normalized)
            .apply()
        declareStream = parse(normalized)
    }

    /** 给运行日志用的一句话。传实际生效值，避免档位覆盖后日志与行为不符。 */
    fun describe(actualDeclareStream: Boolean): String =
        if (actualDeclareStream) {
            "分离声明（解码=码流尺寸 / 视口=车机画布，40-44 字段由车机缩放）"
        } else {
            "不写视口（解码尺寸=车机画布，32..48 全 0，AuDiCar 1.1.1 形态）"
        }

    /** 给运行日志用的一句话（读取当前偏好值）。 */
    fun describe(): String = describe(declareStream)

    /** 解析偏好值；无法识别时回退到默认（车机画布）。 */
    internal fun parse(raw: String?): Boolean =
        when (raw?.trim()?.lowercase()) {
            MODE_STREAM -> true
            MODE_CAR -> false
            else -> DEFAULT_MODE == MODE_STREAM
        }

    internal const val MODE_STREAM = "stream"
    internal const val MODE_CAR = "car"

    /** 测试包默认开启 A/B（关掉即回到 1.2.25 行为）；正式包应保持 false。 */
    internal const val TEST_NO_VIEW_AREA_DEFAULT = true

    /**
     * 本构建的默认值。
     *
     * 2026-11-04 实验：引擎已证实不肯按 1412×654 编码（65544 回显≠SPS），
     * 「画布 1412×654 + 码流 720p」必然对不上，所以这一版默认改成声明码流尺寸，
     * 让车机自己去缩放——用一次实车连接就能判断这条路通不通。
     */
    internal const val DEFAULT_MODE = MODE_STREAM
}
