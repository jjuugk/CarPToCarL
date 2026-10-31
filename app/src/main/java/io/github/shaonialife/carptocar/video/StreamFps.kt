package io.github.shaonialife.carptocar.video

import io.github.shaonialife.carptocar.launcher.settings.DisplayPrefs

/**
 * 本会话的目标帧率：**车机说了算，车机没说按 60**。
 *
 * ## 为什么不再固定 60
 *
 * 1.2.7 之前帧率恒定 [DisplayPrefs.FPS]（60），理由是「奥迪那类车机的实测最优值」。
 * 但车机在 `/info displays[0].maxFPS` 里本来就会声明自己的解码上限：大屏车机常见 30，
 * 发 60 的结果是车机侧丢帧、解码队列堆积，严重时按超时拆链。车机既然报了，就没有
 * 理由跟它对着干。
 *
 * ## 判定规则
 *
 * ```
 * carMaxFps > 0  → carMaxFps（夹在 24…120）
 * carMaxFps <= 0 → 60（车机没声明，按通用线一直以来的值）
 * ```
 *
 * 两个保护：
 * - **下限 24**：车机偶尔报 10、15 这类荒谬值（字段解析错位或半成品实现），
 *   照发会卡成 PPT；夹到 24 至少能看。
 * - **上限 120**：编码器与合成器都按这个值出帧，车机报 999 不能跟着疯。
 *
 * ## 「未声明」必须是 0，不能兜成 30
 *
 * 判定全压在「0 = 没报」这一个约定上。`AirPlayClient` 早先把未声明兜底成 30
 * （猜测值），那样这条规则永远走不到 [DEFAULT_FPS] 分支——「车机要 30」与
 * 「车机什么都没说」在数据上再也分不开。所以那一处也一并改成 0。
 */
object StreamFps {
    /** 车机没声明时的目标帧率（通用线沿用至今的值）。 */
    const val DEFAULT_FPS = DisplayPrefs.FPS

    /** 下限：车机报出荒谬低值时的兜底，避免卡成 PPT。 */
    const val MIN_TARGET_FPS = 24

    /** 上限：编码器/合成器的出帧上限，车机报再大也不跟。 */
    const val MAX_TARGET_FPS = 120

    /**
     * @param carMaxFps 车机 `/info displays[0].maxFPS`；`<=0` 视为未声明
     */
    fun resolve(carMaxFps: Int): Int =
        if (carMaxFps <= 0) {
            DEFAULT_FPS
        } else {
            carMaxFps.coerceIn(MIN_TARGET_FPS, MAX_TARGET_FPS)
        }

    /** 日志用的一句话说明，避免日志里只剩一个数字看不出依据。 */
    fun describe(carMaxFps: Int): String =
        when {
            carMaxFps <= 0 -> "车机未声明 maxFPS，按 $DEFAULT_FPS 声明"
            carMaxFps < MIN_TARGET_FPS -> "车机报 ${carMaxFps}fps（低于下限），按 $MIN_TARGET_FPS 声明"
            carMaxFps > MAX_TARGET_FPS -> "车机报 ${carMaxFps}fps（高于上限），按 $MAX_TARGET_FPS 声明"
            else -> "跟随车机 maxFPS=$carMaxFps"
        }
}
