package io.github.shaonialife.carptocar.amap

/**
 * 手机高德地图 linksdk `type=4`（导航状态）帧的纯数据快照。
 *
 * 只保留一期真正用到的字段。`docs/archive/amap-linksdk-integration-plan-20261124.md`
 * 记着完整字段清单（红绿灯数、车道、隧道、室内导航等），要用时再往上加，
 * **不要在这个类里留"以后可能用"的字段**——它会进单测，字段越少越好证。
 *
 * 单位约定（实测，不要想当然）：
 * - [segmentRemainMeters] 是**到当前动作/段末**的距离（81m 那种量级）；
 * - [routeRemainMeters] 是**全程剩余里程**（19km 那种量级），两者不可互换；
 * - 停车时 `speed=-1/0` 是正常值，不是错误。
 */
internal data class AmapNaviSnapshot(
    /** 高德 `ManeuverIconID`。**不是** CarLife raw 码，必须过 [AmapNaviMapper.toCarLifeIcon]。 */
    val maneuverId: Int,
    val currentRoad: String,
    val nextRoad: String,
    val segmentRemainMeters: Int,
    val routeRemainMeters: Int,
    val routeRemainSeconds: Int,
)
