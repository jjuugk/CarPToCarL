package io.github.shaonialife.carptocar.video

import io.github.shaonialife.carptocar.launcher.settings.DisplayPrefs

/**
 * 本会话的目标帧率：**取「车机声明」与「本机出帧能力」的较小值**。
 *
 * ## 为什么不再固定 60
 *
 * 1.2.7 之前帧率恒定 [DisplayPrefs.FPS]（60），理由是「奥迪那类车机的实测最优值」。
 * 但车机在 `/info displays[0].maxFPS` 里本来就会声明自己的解码上限：大屏车机常见 30，
 * 发 60 的结果是车机侧丢帧、解码队列堆积，严重时按超时拆链。车机既然报了，就没有
 * 理由跟它对着干。
 *
 * ## 为什么「车机报得再高也不跟」
 *
 * 参考 CarVeX（`iamr0s/carvex-carplay-research`）的参数决策：目标帧率取
 * `min(接收端 maxFPS, 宿主 hint)`。绝对帧率不是一个参数能决定的——**声明**高于
 * 编码器实际能出的帧，车机的呈现管线会按更高的节拍排帧、等不到帧就抖动；
 * 而本机出帧能力就是这个常量 [DEFAULT_FPS]（编码器与合成器都按它建）。
 *
 * 所以车机报 120、240 都按 60 声明：不是不尊重接收端，是我们根本供不上。
 * 反过来说，车机报 30 就老老实实 30——这一侧是硬约束，必须让。
 *
 * ## 判定规则
 *
 * ```
 * carMaxFps <= 0              → 60（车机没声明）
 * 0 < carMaxFps < 24          → 24（荒谬低值兜底）
 * 24 <= carMaxFps <= 60       → carMaxFps（跟随车机）
 * carMaxFps > 60              → 60（本机出帧上限）
 * ```
 *
 * **下限 24**：车机偶尔报 10、15 这类荒谬值（字段解析错位或半成品实现），
 * 照发会卡成 PPT；夹到 24 至少能看。
 *
 * ## 「未声明」必须是 0，不能兜成 30
 *
 * 判定全压在「0 = 没报」这一个约定上。`AirPlayClient` 早先把未声明兜底成 30
 * （猜测值），那样这条规则永远走不到 [DEFAULT_FPS] 分支——「车机要 30」与
 * 「车机什么都没说」在数据上再也分不开。所以那一处也一并改成 0。
 */
object StreamFps {
    /** 本机出帧能力，也是车机没声明时的目标帧率（编码器与合成器都按它建）。 */
    const val DEFAULT_FPS = DisplayPrefs.FPS

    /** 下限：车机报出荒谬低值时的兜底，避免卡成 PPT。 */
    const val MIN_TARGET_FPS = 24

    /**
     * @param carMaxFps 车机 `/info displays[0].maxFPS`；`<=0` 视为未声明
     */
    fun resolve(carMaxFps: Int): Int =
        if (carMaxFps <= 0) {
            DEFAULT_FPS
        } else {
            minOf(DEFAULT_FPS, carMaxFps.coerceAtLeast(MIN_TARGET_FPS))
        }

    /** 日志用的一句话说明，避免日志里只剩一个数字看不出依据。 */
    fun describe(carMaxFps: Int): String =
        when {
            carMaxFps <= 0 -> "车机未声明 maxFPS，按 $DEFAULT_FPS 声明"
            carMaxFps < MIN_TARGET_FPS -> "车机报 ${carMaxFps}fps（低于下限），按 $MIN_TARGET_FPS 声明"
            carMaxFps > DEFAULT_FPS ->
                "车机报 ${carMaxFps}fps（高于本机 $DEFAULT_FPS 上限），按 $DEFAULT_FPS 声明"
            else -> "跟随车机 maxFPS=$carMaxFps"
        }
}
