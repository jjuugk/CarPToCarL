package io.github.shaonialife.carptocar.amap

import io.github.shaonialife.carptocar.carlife.CarLifeNaviGuide
import io.github.shaonialife.carptocar.carlife.CarLifeNaviNextTurn

/**
 * 高德 linksdk 字段 → 项目内部导航结构的映射。
 *
 * **本文件零 Android 依赖，全部可单测。** JSON 解析在 [AmapLinkClient] 里做，
 * 这里只处理已解好的纯数据——因为 Android 的 `org.json` 在 JVM 单测里是空壳，
 * 一旦把 `JSONObject` 引进来，映射表就再也没法被单测钉住。
 *
 * ## 转向码为什么不直通
 *
 * `CarLifeNaviNextTurn.nextTurn` 的语义是「高德 ICON 转向码」
 * （见 [CarLifeNaviGuide] 的常量区，`LEFT_TURN=2` / `RIGHT_TURN=3` / …），
 * 而 linksdk 的 `curManeuverID` 是「AMap ManeuverIconID」。两者**前 12 项数值恰好重合**，
 * 但那是两套体系各自演化的结果，不是同一个契约：
 *
 * - 重合部分：2/3/4/5/6/7/8/9/11/12 共 10 项可以直接对应；
 * - 不重合：高德 19（掉头）、20（顺行）要归并到 8/9；高德 1 的语义尚未确证；
 * - 未知值一律 [ICON_NONE]（0），宁可**不画箭头**也不猜方向。
 *
 * 所以这里必须是显式 `when`，**禁止 `nextTurn = maneuverId` 这种直通写法**：
 * 哪天高德调整编号，直通会让箭头静默画错（历史上 CarLife raw 7 被当成右后就是这么踩的）。
 */
internal object AmapNaviMapper {

    /** 映射不出来时的转向码：`CarLifeNaviGuide` 里没有 0 号常量，下游按「无箭头」处理。 */
    const val ICON_NONE = 0

    /**
     * AMap `ManeuverIconID` → 项目内部的「高德 ICON 转向码」。
     *
     * 对应关系来自同源参考实现（`fw867/hypercar` 的 `AmapLinkNavigationState.maneuvers`，
     * 注释自述 "AMap AutoSDK ManeuverIconID"），并经 2026-11-24 探针实测
     * `curManeuverID=2` 出现在「银新东一路 → 上海路、前方 81m 左转」帧。
     *
     * ⚠️ **"数值重合"仍是推断，不是实车确证**：接入后必须逐一比对
     * 左转/右转/直行/掉头/环岛进出五种与高德界面箭头是否一致。
     */
    fun toCarLifeIcon(maneuverId: Int): Int = when (maneuverId) {
        2 -> CarLifeNaviGuide.LEFT_TURN
        3 -> CarLifeNaviGuide.RIGHT_TURN
        4 -> CarLifeNaviGuide.LEFT_FRONT
        5 -> CarLifeNaviGuide.RIGHT_FRONT
        6 -> CarLifeNaviGuide.LEFT_BACK
        7 -> CarLifeNaviGuide.RIGHT_BACK
        // 高德把掉头拆成 8 与 19 两个取值，20 是「顺行」，都要归并。
        8, 19 -> CarLifeNaviGuide.U_TURN_LEFT
        9, 20 -> CarLifeNaviGuide.STRAIGHT
        11 -> CarLifeNaviGuide.ROUNDABOUT_ENTER
        12 -> CarLifeNaviGuide.ROUNDABOUT_EXIT
        else -> ICON_NONE
    }

    /**
     * 纯数据 → [CarLifeNaviNextTurn]。
     *
     * [action] 用 [CarLifeNaviGuide.ACTION_START] / [CarLifeNaviGuide.ACTION_UPDATE]，
     * **结束不要走这里**（结束是清除帧，由状态机单独产出）。
     *
     * 字段取值的三个约定：
     * - `remainDistance` 取**段剩余**（[AmapNaviSnapshot.segmentRemainMeters]）——
     *   `Iap2RouteGuidance` 把它塞进 `0x5201` 的 `0x0a`，那是「到下一个动作的距离」，
     *   不是全程剩余；
     * - `totalDistance` 取全程剩余（`routeRemainDist`）。注意 `Iap2RouteGuidance.encodeUpdate`
     *   当前**刻意不发** `0x07`（全程剩余），所以这个值目前只进日志；
     * - `roadName` 取**下一条路名**（`nextRouteName`）——仪表要显示的是「转进哪条路」，
     *   不是当前所在的路。
     */
    fun toNextTurn(
        snapshot: AmapNaviSnapshot,
        action: Int,
    ): CarLifeNaviNextTurn {
        val icon = toCarLifeIcon(snapshot.maneuverId)
        return CarLifeNaviNextTurn(
            action = action,
            nextTurn = icon,
            // rawNextTurn 语义是「protobuf 原始码，仅供日志」。这里留高德原始 id，
            // 日志里就能一眼看出「原始 19 被归并成 8」，排查时不用反推。
            rawNextTurn = snapshot.maneuverId,
            roadName = snapshot.nextRoad,
            totalDistance = snapshot.routeRemainMeters,
            remainDistance = snapshot.segmentRemainMeters,
            turnIconData = null,
        )
    }

    /**
     * 路名清洗：高德的 `nextRouteName` 可能带 NUL 填充（参考实现里也做了这个处理），
     * 直接塞进 protobuf 字符串会让车机显示成方块或截断。
     */
    fun sanitizeRoad(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val cleaned = raw.replace('\u0000', ' ').trim()
        return if (cleaned.length <= MAX_ROAD_CHARS) cleaned else cleaned.substring(0, MAX_ROAD_CHARS)
    }

    /** 车机端路名字段容纳有限，超长会挤掉转向文字；40 个字符够任何中文路名。 */
    private const val MAX_ROAD_CHARS = 40
}
