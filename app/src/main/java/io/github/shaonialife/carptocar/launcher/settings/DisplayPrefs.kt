package io.github.shaonialife.carptocar.launcher.settings

import android.content.Context

/**
 * 「显示」面板偏好：视频流/虚拟屏的分辨率缩放与目标帧率。
 *
 * - 分辨率缩放（30%–160%）：按车机通告分辨率的百分比生成流画布与虚拟屏，
 *   密度同步缩放（逻辑布局不变，只改像素量）。**仅下次连接生效**——
 *   编码器与全部虚拟屏按会话建立时的尺寸创建，运行中改无意义。
 * - 帧率：**没有设置项**。车机 `/info displays[0].maxFPS` 报了就跟随，没报按 [FPS]（60）
 *   ——判定在 `video/StreamFps.kt`，本文件只提供默认值。
 *
 * 「实际生效值」由 SessionController 在会话建立时记录（activeScalePercent /
 * activeTargetFps）；英雄卡只显示运行时分辨率与实测帧率。
 */
object DisplayPrefs {
    const val SCALE_MIN = 30
    const val SCALE_MAX = 160
    const val SCALE_STEP = 5
    const val SCALE_DEFAULT = 100

    /** 车机没声明 maxFPS 时的目标帧率（无设置项，判定见 `video/StreamFps.kt`）。 */
    const val FPS = 60

    /** 触摸 move 注入节流默认值（ms）：0=默认关闭，交互滞后时在「显示」面板调高试验。 */
    const val MOVE_THROTTLE_DEFAULT = 0
    const val MOVE_THROTTLE_MAX = 48

    private const val PREFS_NAME = "settings"
    private const val KEY_SCALE = "display_scale_percent"
    private const val KEY_MOVE_THROTTLE = "move_throttle_ms"
    private const val KEY_LAST_CAR_WIDTH = "last_car_width"
    private const val KEY_LAST_CAR_HEIGHT = "last_car_height"
    private const val KEY_LAST_CAR_FROM_CAR = "last_car_from_car"
    private const val KEY_LAST_STREAM_WIDTH = "last_stream_width"
    private const val KEY_LAST_STREAM_HEIGHT = "last_stream_height"

    /**
     * 上一次会话的分辨率快照（供「没连接时」界面展示）。
     *
     * 车机分辨率只在 AirPlay SETUP 拉 `/info` 之后才知道，没连上时界面本来无从显示。
     * 落盘之后至少能给用户一个「上次这台车是多少×多少」的参照，缩放换算也有据可依。
     */
    data class LastSession(
        val carWidth: Int,
        val carHeight: Int,
        /** 车机是否真的通告了分辨率（false = 当时用的是手机横屏兜底值）。 */
        val carFromCar: Boolean,
        val streamWidth: Int,
        val streamHeight: Int,
    )

    /** 会话建立时记录一份分辨率快照。 */
    fun recordSession(
        context: Context,
        carWidth: Int,
        carHeight: Int,
        carFromCar: Boolean,
        streamWidth: Int,
        streamHeight: Int,
    ) {
        if (carWidth <= 0 || carHeight <= 0) return
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_CAR_WIDTH, carWidth)
            .putInt(KEY_LAST_CAR_HEIGHT, carHeight)
            .putBoolean(KEY_LAST_CAR_FROM_CAR, carFromCar)
            .putInt(KEY_LAST_STREAM_WIDTH, streamWidth)
            .putInt(KEY_LAST_STREAM_HEIGHT, streamHeight)
            .apply()
    }

    /**
     * 用**码流真实尺寸**（SPS 解析值）修正上一次会话快照里的流尺寸。
     *
     * [recordSession] 只能先记「协商档位」——那时还没收到首帧，引擎也没决定实际编码
     * 尺寸。等 SPS 到手才知道引擎可能降档（申请 1412×654 实编 1280×720，2026-10-29
     * 实测），不修正的话断连态会一直显示一个从没真正推过的尺寸。
     */
    fun updateLastStream(
        context: Context,
        streamWidth: Int,
        streamHeight: Int,
    ) {
        if (streamWidth <= 0 || streamHeight <= 0) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_LAST_CAR_WIDTH, 0) <= 0) return
        if (prefs.getInt(KEY_LAST_STREAM_WIDTH, 0) == streamWidth &&
            prefs.getInt(KEY_LAST_STREAM_HEIGHT, 0) == streamHeight
        ) {
            return
        }
        prefs
            .edit()
            .putInt(KEY_LAST_STREAM_WIDTH, streamWidth)
            .putInt(KEY_LAST_STREAM_HEIGHT, streamHeight)
            .apply()
    }

    /** 上一次会话的分辨率快照；从未连过返回 null。 */
    fun lastSession(context: Context): LastSession? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val carWidth = prefs.getInt(KEY_LAST_CAR_WIDTH, 0)
        val carHeight = prefs.getInt(KEY_LAST_CAR_HEIGHT, 0)
        if (carWidth <= 0 || carHeight <= 0) return null
        return LastSession(
            carWidth = carWidth,
            carHeight = carHeight,
            carFromCar = prefs.getBoolean(KEY_LAST_CAR_FROM_CAR, true),
            streamWidth = prefs.getInt(KEY_LAST_STREAM_WIDTH, 0),
            streamHeight = prefs.getInt(KEY_LAST_STREAM_HEIGHT, 0),
        )
    }

    /** 流分辨率缩放百分比（30–160，步进 5；默认 100）。 */
    fun scalePercent(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SCALE, SCALE_DEFAULT)
            .coerceIn(SCALE_MIN, SCALE_MAX)

    fun setScalePercent(
        context: Context,
        percent: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SCALE, percent.coerceIn(SCALE_MIN, SCALE_MAX))
            .apply()
    }

    /** 触摸 move 注入节流（ms；0=关闭）。 */
    fun moveThrottleMs(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MOVE_THROTTLE, MOVE_THROTTLE_DEFAULT)
            .coerceIn(0, MOVE_THROTTLE_MAX)

    fun setMoveThrottleMs(
        context: Context,
        ms: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MOVE_THROTTLE, ms.coerceIn(0, MOVE_THROTTLE_MAX))
            .apply()
    }
}
