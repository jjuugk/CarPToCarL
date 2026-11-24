package io.github.shaonialife.carptocar.media

import io.github.shaonialife.carptocar.carlife.CarLifeNaviGuide

/**
 * 转向码 → 箭头图形。**零 Android 依赖，可单测**（取图在 [ManeuverArrow] 里）。
 *
 * 为什么单独一个文件：`ManeuverArrow` 要碰 `R.raw` 与 `Bitmap`，
 * 一旦和纯逻辑放同一个文件，文件级属性初始化就会把 JVM 单测带崩。
 *
 * ## 只认 [CarLifeNaviGuide] 的 ICON 码
 *
 * 这里的入参是 `CarLifeNaviNextTurn.nextTurn`（ICON 码），**不是** CarLife 投屏的
 * protobuf raw 码。两套编码在 2..9 上数值撞车但语义完全不同（raw 7 = 左转，
 * ICON 7 = 右后方），混用会让箭头静默画反。raw→ICON 的转换在
 * `CarLifeNaviGuide.rawToIcon` 里已经做过，不要在取图这一层再来一次。
 *
 * 映射不出来的（自车、途经点、服务区、隧道、未知码）一律 [NONE]：
 * 宁可让车机清掉封面位只显示文字，也不画一个猜的方向。
 */
internal enum class ArrowShape {
    STRAIGHT,
    LEFT,
    RIGHT,
    LEFT_FRONT,
    RIGHT_FRONT,
    LEFT_BACK,
    RIGHT_BACK,
    U_TURN,
    ROUNDABOUT_IN,
    ROUNDABOUT_OUT,
    ARRIVE_DEST,
    ARRIVE_TOLL,

    /** 没有对应图标（透明图）。 */
    NONE,
}

internal fun arrowShapeFor(turnCode: Int): ArrowShape = when (turnCode) {
    CarLifeNaviGuide.STRAIGHT -> ArrowShape.STRAIGHT
    CarLifeNaviGuide.LEFT_TURN -> ArrowShape.LEFT
    CarLifeNaviGuide.RIGHT_TURN -> ArrowShape.RIGHT
    CarLifeNaviGuide.LEFT_FRONT -> ArrowShape.LEFT_FRONT
    CarLifeNaviGuide.RIGHT_FRONT -> ArrowShape.RIGHT_FRONT
    CarLifeNaviGuide.LEFT_BACK -> ArrowShape.LEFT_BACK
    CarLifeNaviGuide.RIGHT_BACK -> ArrowShape.RIGHT_BACK
    CarLifeNaviGuide.U_TURN_LEFT -> ArrowShape.U_TURN
    CarLifeNaviGuide.ROUNDABOUT_ENTER -> ArrowShape.ROUNDABOUT_IN
    CarLifeNaviGuide.ROUNDABOUT_EXIT -> ArrowShape.ROUNDABOUT_OUT
    CarLifeNaviGuide.ARRIVE_DEST -> ArrowShape.ARRIVE_DEST
    CarLifeNaviGuide.ARRIVE_TOLL -> ArrowShape.ARRIVE_TOLL
    else -> ArrowShape.NONE
}
