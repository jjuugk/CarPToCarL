package io.github.shaonialife.carptocar.carlife

import android.content.Context

/**
 * 「上次连过的车机画布」记忆（本机落盘，不上传）。
 *
 * ## 为什么需要它
 *
 * `98311`（视频编码初始化）是车机告诉 CarLife 引擎「我的屏幕多大」的**唯一**通道，
 * 而引擎只在**创建编码器之前**认这个值：一旦编码器起来，再改申请值它只回一个
 * 「确认参数」，实际编码尺寸不动（2026-11-04 奥迪 3810 日志：申请 1412×654@60，
 * 引擎回 1280×720@20，之后无论重发几次 98311 都是 720p）。
 *
 * 而时序天生错位：CarLife+ 的端口一开我们就得握手（[CarLifeHijack.prewarm]），
 * 那时**还没连车机**，AirPlay `/info` 里才有车机画布（往往晚 10~20 秒）。
 * 结果首轮 98311 永远报默认 1280×720，等知道了画布已经晚了。
 *
 * 解法只有「记住上次的」：同一台车下次开机，先按上次画布申请，引擎就会按画布建编码器。
 * 首次连一台新车仍会走默认值——这是信息论决定的，只能靠第二次。
 *
 * ## 只记「车机真正通告过的画布」
 *
 * 调用方（[CarLifeHijack.setRequestedResolution]）只会在 AirPlay `/info` 通告了
 * `displayWidth/Height`（`displayFromCar == true`）时写入；「手机横屏兜底」的假画布
 * 不落盘，否则会把手机尺寸当成车机尺寸固化下来，越记越偏。
 */
object CarCanvasMemory {
    private const val PREFS = "car_canvas_memory_v1"
    private const val KEY_WIDTH = "last_display_width"
    private const val KEY_HEIGHT = "last_display_height"

    /** 无缓存时的引擎默认档（引擎白名单内、所有车机都吃得下）。 */
    val DEFAULT: Pair<Int, Int> = 1280 to 720

    /** 合理画布范围，与 SPS 合理性判据（[H264Sps] 调用方的校验）保持一致。 */
    private val WIDTH_RANGE = 320..4096
    private val HEIGHT_RANGE = 180..2304

    /**
     * 校验并归一化一个画布值；不合理返回 null。
     *
     * 范围外的值一律丢弃：车机偶尔会通告 0（未初始化）或离谱值（4K 竖屏误报），
     * 拿它去申请编码器只会让引擎 `reset2Default`，反而丢掉本来正确的缓存。
     */
    fun sanitize(
        width: Int,
        height: Int,
    ): Pair<Int, Int>? {
        if (width !in WIDTH_RANGE || height !in HEIGHT_RANGE) return null
        return width to height
    }

    /** 上次的车机画布；没有/不合法返回 null（调用方回落到 [DEFAULT]）。 */
    fun last(context: Context?): Pair<Int, Int>? {
        val app = context?.applicationContext ?: return null
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val width = prefs.getInt(KEY_WIDTH, 0)
        val height = prefs.getInt(KEY_HEIGHT, 0)
        return sanitize(width, height)
    }

    /**
     * 记下这台车的画布。值未变或非法时返回 false（调用方可据此避免重复写盘/重复打日志）。
     */
    fun remember(
        context: Context?,
        width: Int,
        height: Int,
    ): Boolean {
        val app = context?.applicationContext ?: return false
        val (w, h) = sanitize(width, height) ?: return false
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_WIDTH, 0) == w && prefs.getInt(KEY_HEIGHT, 0) == h) return false
        prefs
            .edit()
            .putInt(KEY_WIDTH, w)
            .putInt(KEY_HEIGHT, h)
            .apply()
        return true
    }
}
