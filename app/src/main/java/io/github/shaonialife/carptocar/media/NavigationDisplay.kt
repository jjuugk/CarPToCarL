package io.github.shaonialife.carptocar.media

import java.util.Locale

internal data class HudTurn(
    val action: Int,
    val turn: Int,
    val road: String,
    val distance: Int,
    val icon: ByteArray?,
    val ended: Boolean = action == 3 && turn == 24 && distance == 0,
)

/** 每条 CarLife 控制连接拥有自己的文字、箭头以及尚未完成的图片处理。 */
internal class NavigationDisplay {
    data class Guide(
        val title: String,
        val road: String,
        val artwork: ByteArray,
    )

    data class ArtworkRequest(
        val bytes: ByteArray,
    )

    private var turn: HudTurn? = null
    private var startDistance = 0
    private var artwork: ByteArray = NavigationIcon.blank
    private var artworkRequest: ArtworkRequest? = null

    @Synchronized
    fun receive(next: HudTurn): ArtworkRequest? {
        if (next.ended) {
            clear()
            return null
        }
        // OPPO Car+ 的 action=3 / turn=24 / distance=1 是目的地提示。
        if (next.action !in 1..2) return null

        val old = turn
        val newManeuver =
            next.action == 1 ||
                old == null ||
                old.turn != next.turn ||
                (old.distance <= 5 && next.distance > 20) ||
                next.distance - old.distance > maxOf(100, startDistance / 5)
        startDistance = if (newManeuver) next.distance else maxOf(startDistance, next.distance)
        turn = next
        val icon = next.icon
        if (icon == null && !newManeuver) return null
        if (icon == null || icon.isEmpty()) {
            artworkRequest = null
            artwork = NavigationIcon.blank
            return null
        }
        if (artworkRequest?.bytes?.contentEquals(icon) == true) return null
        artwork = NavigationIcon.blank
        return ArtworkRequest(icon).also { artworkRequest = it }
    }

    @Synchronized
    fun prepared(
        request: ArtworkRequest,
        bytes: ByteArray,
    ) {
        if (artworkRequest === request) artwork = bytes
    }

    @Synchronized
    fun clear() {
        turn = null
        startDistance = 0
        artworkRequest = null
        artwork = NavigationIcon.blank
    }

    @Synchronized
    fun current(): Guide? =
        turn?.let {
            Guide("${formatDistance(it.distance)} ${turnName(it.turn)}", it.road, artwork)
        }

    private companion object {
        private fun formatDistance(meters: Int): String =
            if (meters < 1000) {
                "${meters}m"
            } else {
                String.format(Locale.ROOT, "%.1f", meters / 1000.0).removeSuffix(".0") + "km"
            }

        private fun turnName(code: Int): String =
            when (code) {
                1 -> "直行"
                2 -> "向右前方行驶"
                3 -> "右转"
                4 -> "右后方转弯"
                5 -> "掉头"
                6 -> "左后方转弯"
                7 -> "左转"
                8 -> "向左前方行驶"
                9 -> "进入环岛"
                10 -> "出环岛"
                11 -> "靠左行驶"
                12 -> "靠右行驶"
                13 -> "靠左进入主路"
                14 -> "左分叉直行"
                15 -> "靠右进入主路"
                16 -> "右分叉直行"
                17 -> "分叉路走中间"
                18 -> "靠左驶入辅路"
                19 -> "靠右驶入辅路"
                20 -> "走左侧分叉"
                21 -> "走右侧分叉"
                22 -> "走中间分叉"
                23 -> "出发点"
                24 -> "到达目的地"
                29 -> "进入轮渡"
                30 -> "离开轮渡"
                31 -> "经过收费站"
                // Car+ 实测方向文案，未知转向统一显示「途径」。
                32 -> "靠左直行进入辅路"
                33 -> "靠右直行进入辅路"
                34 -> "靠左直行"
                35 -> "靠右直行"
                36 -> "左分叉直行"
                37 -> "中间分叉直行"
                38 -> "右分叉直行"
                39 -> "走左侧分叉进入辅路"
                40 -> "走中间分叉进入辅路"
                41 -> "走右侧分叉进入辅路"
                42 -> "左分叉直行进入辅路"
                43 -> "中间分叉直行进入辅路"
                44 -> "右分叉直行进入辅路"
                69 -> "前方驶出环岛"
                70 -> "左侧驶出环岛"
                71 -> "左后方驶出环岛"
                72 -> "左前方驶出环岛"
                73 -> "右侧驶出环岛"
                74 -> "右后方驶出环岛"
                75 -> "右前方驶出环岛"
                76 -> "环岛内掉头"
                else -> "途径"
            }
    }
}
