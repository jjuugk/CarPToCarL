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

    /**
     * 触摸域自适应的默认值：**关**。
     *
     * 绝大多数车机上报域 = 显示域，原样透传才对（二次缩放见 v0.2.15/v0.2.17 的教训）。
     * 只有「上报域明显比画面大（点右侧总落到右边缘）」的个别车机才需要打开，
     * 判定与换算见 `input/TouchDomainMapping.kt`。
     */
    const val ADAPTIVE_TOUCH_DEFAULT = false

    /**
     * 车机拿走主屏时是否保留视频流（实验，**默认关**）。
     *
     * 关 = 旧行为：车机一接管就 `TEARDOWN` 屏幕流，归还/`requestUI` 后重新 `SETUP`+`RECORD`。
     * 开 = 只承认「归属不是手机」，**不断流、不停编码器**，归还时补关键帧 + 重整 HID。
     *
     * 依据（2026-11-21）：别克 6369 上「让屏 → 申请 → 100~170ms 又被夺」连了 12 轮，
     * 每轮都白做一次完整重建，一帧留不住；而 link_plus（别克可用的同源项目）从不拆流，
     * 只在界面归还后补关键帧。见 `CarPlayResourceStreams.applyScreen`。
     */
    const val KEEP_SCREEN_STREAM_DEFAULT = false

    /** 连上后主动取一次主音频（实验，**默认关**；不按车机指纹判定，由用户按车型自行开启）。 */
    const val PROACTIVE_AUDIO_DEFAULT = false

    private const val PREFS_NAME = "settings"
    private const val KEY_SCALE = "display_scale_percent"
    private const val KEY_MOVE_THROTTLE = "move_throttle_ms"
    private const val KEY_ADAPTIVE_TOUCH = "adaptive_touch_domain"
    private const val KEY_KEEP_SCREEN_STREAM = "keep_screen_stream_on_take"
    private const val KEY_PROACTIVE_AUDIO = "proactive_audio_on_link"
    private const val KEY_NAVIGATION_NOW_PLAYING = "navigation_now_playing"
    private const val KEY_LAST_CAR_WIDTH = "last_car_width"
    private const val KEY_LAST_CAR_HEIGHT = "last_car_height"
    private const val KEY_LAST_CAR_FROM_CAR = "last_car_from_car"
    private const val KEY_LAST_STREAM_WIDTH = "last_stream_width"
    private const val KEY_LAST_STREAM_HEIGHT = "last_stream_height"

    fun navigationNowPlaying(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_NAVIGATION_NOW_PLAYING, false)

    fun setNavigationNowPlaying(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_NAVIGATION_NOW_PLAYING, enabled)
            .apply()
    }

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

    /**
     * 触摸域自适应（默认关）：上报坐标域大于画面时，按观测到的最大坐标线性缩到画面尺寸。
     *
     * 只影响触摸坐标，不改分辨率、不改码流。换算见 `input/TouchDomainMapping.kt`。
     */
    fun adaptiveTouchDomain(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ADAPTIVE_TOUCH, ADAPTIVE_TOUCH_DEFAULT)

    fun setAdaptiveTouchDomain(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ADAPTIVE_TOUCH, enabled)
            .apply()
    }

    /**
     * 车机拿走主屏时保留视频流（实验，默认关）。
     *
     * 只影响「车机接管主屏」后的处置：打开后不 `TEARDOWN`、不停编码器，车机归还画面时
     * 直接接着推（补关键帧 + 重发 `hidSetInputMode`），不再付一次 SETUP/RECORD 的代价。
     */
    fun keepScreenStreamOnTake(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_SCREEN_STREAM, KEEP_SCREEN_STREAM_DEFAULT)

    fun setKeepScreenStreamOnTake(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KEEP_SCREEN_STREAM, enabled)
            .apply()
    }

    /**
     * 连上后主动取一次主音频（实验，默认关）。
     *
     * 宝马 MGU-50-J：视频在送、车机 ACK 正常，但「没有音频会话就不交屏」——必须等用户
     * 放首歌才进 CarPlay。打开后会话建立 1.5 秒、首帧已出后自动申请一次主音频。
     * **不按车机指纹自动判定**：部分车机确实需要这一步才出画面，由用户按车型自行开启。
     */
    fun proactiveAudioOnLink(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROACTIVE_AUDIO, PROACTIVE_AUDIO_DEFAULT)

    fun setProactiveAudioOnLink(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROACTIVE_AUDIO, enabled)
            .apply()
    }
}
