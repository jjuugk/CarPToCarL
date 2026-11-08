package io.github.shaonialife.carptocar.video

import android.content.Context

/**
 * VideoConfig 头的**尺寸声明策略**。
 *
 * 头里有两组尺寸，含义不同，不能混：
 *
 * - `params[1]`（偏移 16）：**解码器**尺寸；
 * - `params[3]`/`params[4]`（偏移 32/40）：**显示视口** x,y / 宽,高。
 *
 * 三种形态：
 *
 * - **[Mode.RAW]（默认，对齐 AMCar）**：只写解码尺寸 = 码流尺寸（引擎上报/SPS），
 *   **视口整段留 0**，画布尺寸完全不参与声明。这是参考实现
 *   `AMCar`（`explicitVideoGeometry=false`）的做法：它只声明一个尺寸、且跟随实际码流，
 *   不猜车机画布、不写 40/44，把缩放与贴图交给车机默认行为。少写一个字段就少一处
 *   和车机固件理解不一致的地方——这是它「保守」的全部含义，性能上没有代价
 *   （视口只是元数据，不改变编码尺寸与帧率）。
 * - **[Mode.SPLIT]**：解码尺寸 = 码流，视口 = 车机画布（2026-11-04 起的旧默认，
 *   依据是 AMCar 4580 补丁「编码尺寸与显示视口分开」那句话）。留着做 A/B。
 * - **[Mode.CAR]**：解码尺寸直接写成车机画布，不写视口。只在「码流真的等于画布」
 *   时成立，降档后必与 SPS 打架（2026-11-04 13:48 实测被判 invalid header）。
 *
 * 偏好 `videoconfig_canvas_mode`：`raw` / `stream` / `car`（默认随构建常量）。
 * 纯实验开关，改完需重连（会话建立时读取一次）。
 */
object VideoCanvasMode {

    private const val PREFS = "video_canvas_mode_v1"
    private const val KEY = "declare"

    /** 尺寸声明形态。见类注释。 */
    enum class Mode {
        RAW,
        SPLIT,
        CAR,
    }

    /**
     * 本构建的默认值。
     *
     * 2026-11-08：改成 [Mode.RAW] —— 参考 AMCar（`explicitVideoGeometry=false`）的
     * 保守形态，只写解码尺寸、不写视口。此前默认 [Mode.SPLIT]（我们自己的推断，
     * 依据仅 AMCar 补丁里一句话），多写了 40/44 两个字段，属于「给车机额外信息」，
     * 并非协议必需；实车若仍黑屏，再用 `videoconfig_canvas_mode=stream` 做 A/B。
     */
    internal val DEFAULT_MODE = Mode.RAW

    /**
     * 当前形态。
     *
     * 进程启动时从偏好加载一次；会话建立时读取，不随会话中途变化。
     */
    @Volatile
    var mode: Mode = DEFAULT_MODE
        private set

    /** 解码尺寸（`params[1]`）是否取车机画布（只有 [Mode.CAR] 是）。 */
    val sizeFromCanvas: Boolean
        get() = sizeFromCanvasFor(mode)

    /** 是否写显示视口（`params[3]`/`params[4]`，只有 [Mode.SPLIT] 写）。 */
    val writeViewport: Boolean
        get() = writeViewportFor(mode)

    /** 纯函数版本，给单测用（不依赖全局 [mode]）。 */
    internal fun sizeFromCanvasFor(value: Mode): Boolean = value == Mode.CAR

    internal fun writeViewportFor(value: Mode): Boolean = value == Mode.SPLIT

    fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        mode = parse(raw)
    }

    fun save(context: Context, value: String) {
        val normalized = parse(value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, nameOf(normalized))
            .apply()
        mode = normalized
    }

    /** 给运行日志用的一句话。 */
    fun describe(): String =
        when (mode) {
            Mode.RAW ->
                "只声明解码尺寸=码流（不写视口，对齐 AMCar 默认；画布不参与声明）"
            Mode.SPLIT ->
                "分离声明（解码=码流尺寸 / 视口=车机画布，40-44 字段由车机缩放）"
            Mode.CAR ->
                "车机画布（解码尺寸直接写成 /info 通告值，不写视口字段）"
        }

    /** 解析偏好值；无法识别时回退到默认。 */
    internal fun parse(raw: String?): Mode =
        when (raw?.trim()?.lowercase()) {
            MODE_RAW -> Mode.RAW
            MODE_STREAM -> Mode.SPLIT
            MODE_CAR -> Mode.CAR
            else -> DEFAULT_MODE
        }

    internal fun nameOf(value: Mode): String =
        when (value) {
            Mode.RAW -> MODE_RAW
            Mode.SPLIT -> MODE_STREAM
            Mode.CAR -> MODE_CAR
        }

    internal const val MODE_RAW = "raw"
    internal const val MODE_STREAM = "stream"
    internal const val MODE_CAR = "car"
}
