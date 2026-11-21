package io.github.shaonialife.carptocar.input

/**
 * 触摸「上报域 → 视图域」自适应映射（纯计算，可单测）。
 *
 * 背景：绝大多数车机 HID 触摸上报的坐标域等于 AirPlay SETUP 通告的显示域
 * （`displayWidth/Height`），所以 `CarLifeHijack.handleTouch` 一直是**原样透传 + 钳制**——
 * 引擎自己会按容器尺寸换算，我们再缩一次就是二次缩放（v0.2.15 引入、v0.2.17 撤销）。
 *
 * 但有些车机不是这样：2026-11-21 的 BUICK_6369（通用 Cinemo，车机画布 1972×520）
 * HID 实际报到 X=2705、Y=555，`coerceIn(0, viewW-1)` 把这些点全钳到最右缘/最下缘，
 * 表现为「分辨率设置对了但触摸漂移」——点右侧永远落到右边缘。
 *
 * 这里给出一个**默认关闭**的兜底：用观测到的上报最大值当作上报域，按比例缩到视图域。
 *
 * 三条设计约束（都是踩过的坑）：
 *
 * 1. **只用观测上限，不猜物理尺寸**。域上限取自 `observedMax`（`handleTouch` 每笔都记录
 *    原始值，不受钳制影响）。这意味着域是「用户点过的最远点」，用户没点过右边缘时上限
 *    偏小——所以它只能当兜底开关，不能默认开。
 * 2. **域要按手势锁定**。观测上限会随手指移动增长，若逐帧重算，一笔拖拽中途比例就会跳变，
 *    落点抖动。调用方在 DOWN 时算一次、整笔复用。
 * 3. **放大才映射，缩小的不映射**。上报域小于视图域属于正常（用户只点了左半边），
 *    绝不能把它放大到全屏。
 */
internal object TouchDomainMapping {
    /**
     * 判定「上报域确实更大」的余量（像素）。
     *
     * 上报域 = 视图域 + 几个像素是常见的（有的车机把 [0, w] 当闭区间，最大值正好等于 w），
     * 这种不该触发映射——否则会把最后几个像素压缩掉，反而制造新的偏移。
     */
    const val EXPAND_MARGIN = 8

    /**
     * 上报域的上限倍数（相对视图域）。
     *
     * 超过这个倍数基本只能是坏帧/解析错位（例如把两个轴的字节读反了），
     * 按它映射会把整块屏幕压成一条缝，比不映射更糟。宁可放弃映射也不做这种缩放。
     */
    const val MAX_EXPAND_RATIO = 4

    /**
     * 决定本次手势「是否映射、按多大的域映射」。
     *
     * @param viewSize 视图域尺寸（车机 display 通告值，即 `viewW`/`viewH`）
     * @param observedMax 观测到的上报坐标最大值（原始值，未被钳制）
     * @param enabled 开关是否打开（默认关；关时永远返回视图域 = 不映射）
     * @return 映射用的上报域；返回 [viewSize] 表示「不映射，原样透传」
     */
    fun domainFor(
        viewSize: Int,
        observedMax: Int,
        enabled: Boolean,
    ): Int {
        if (!enabled) return viewSize
        if (viewSize <= 0 || observedMax <= 0) return viewSize
        if (observedMax <= viewSize + EXPAND_MARGIN) return viewSize
        if (observedMax > viewSize * MAX_EXPAND_RATIO) return viewSize
        return observedMax
    }

    /**
     * 把单个轴的上报坐标换成视图坐标。
     *
     * @param value 上报坐标（未钳制）
     * @param viewSize 视图域尺寸
     * @param domain 上报域（来自 [domainFor]）；等于 [viewSize] 或 <=0 时原样返回
     *
     * 用 `viewSize - 1` 做上界，让「观测到的最大值」正好落在最后一个像素上，
     * 避免调用方再钳一次（钳在边界上会把边缘点击堆成一条线）。
     */
    fun map(
        value: Int,
        viewSize: Int,
        domain: Int,
    ): Int {
        if (viewSize <= 0 || domain <= 0 || domain == viewSize) return value
        val mapped = value.toLong() * (viewSize - 1) / domain
        return mapped.coerceIn(0L, (viewSize - 1).toLong()).toInt()
    }
}
