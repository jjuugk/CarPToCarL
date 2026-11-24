package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.carlife.CarLifeNaviGuide
import io.github.shaonialife.carptocar.carlife.CarLifeNaviNextTurn
import java.util.EnumMap

/**
 * CarLife 导航诱导 → Apple iAP2 `0x5201/0x5202` 的编码与分发状态。
 *
 * 报文布局按 Apple Maps 实测格式校准（iPhone → DiPlay，覆盖导航开始/诱导/结束全过程）：
 *
 * ```
 * 0x5201 RouteGuidanceUpdate
 *   field 0x00 u16   固定 42（Apple 每帧都带，DiPlay 忽略但车机可能校验）
 *   field 0x01 u8    状态：3=路线就绪 6=诱导中 0=路线清除
 *   field 0x03 str   空串（Apple 实测为空；真正的当前道路在 0x04）
 *   field 0x04 str   当前道路（NUL 结尾 UTF-8）
 *   field 0x07 u32   全程剩余米数
 *   field 0x0a u32   到下一转向米数
 *   field 0x0d u32   4 字节索引字，实测 00 00 00 02
 *   field 0x13 str   导航来源 App 名
 *   field 0x14 u8    固定 1
 *
 * 0x5202 RouteGuidanceManeuverUpdate
 *   field 0x00 u16   固定 42
 *   field 0x01 u16   索引
 *   field 0x02 str    maneuvers 文字（如「从某路出发」）
 *   field 0x03 u8    Apple 转向类型
 *   field 0x04 str   转向后道路（NUL 结尾 UTF-8）
 *   field 0x07 u8    驾驶侧：2=右行（实测），1=左行
 *   field 0x08 str   空串
 * ```
 *
 * 实测关键证据：
 * - 直行的 0x5202 `[3] u8=11`，DiPlay 映射为 gaode 直行码 11；
 * - 左转的 0x5202 `[3] u8=1`，对应 CarLife 高德 ICON 2（protobuf raw 7），
 *   **不是** protobuf raw 值本身；转换在 [CarLifeNaviGuide.rawToIcon] 完成；
 * - 驾驶侧实测 `u8=2`（不是 0），把 0 发成左行会让箭头左右颠倒；
 * - 0x5201 的 0x0d 是 **4 字节** `00 00 00 02`，DiPlay 按 u16 读前两字节得 0，
 *   指向索引 0 的「从…出发」maneuver；车机据此显示当前诱导。
 *   我们同时把同一条诱导写在索引 0 和索引 2，兼容两种读法。
 *
 * 结束策略（2026-11-22 二次修正）：CarLife 显式 `action=3` 或引擎控制通道意外断开时，
 * 立即把最新快照换成 0x5201 state=0（`0x00=42`、`0x01=0`、`0x07=0`，不附 0x5202）
 * 并同步扇出到所有已订阅链路，车机据此马上清 HUD。不能只停心跳等 DiPlay 的 30s
 * 陈旧兜底——那样会多留一段 HUD 残影。只有「引擎静默但会话仍在」才靠心跳保活，
 * 这两条路径互不重叠。
 *
 * 心跳策略：CarLife 引擎长时间不刷新诱导时，周期性重发最近一帧 0x5201（只发 update，
 * 不重发 0x5202），把 DiPlay 的 `lastRouteUpdateNs` 一直往后推，避免 30s 陈旧超时
 * 误判为「导航结束」。心跳由 SessionController 的 10s 节拍驱动，结束/断链即停。
 *
 * 分发是**按链路**的：车机可能在蓝牙链路上订阅但没在 WiFi 隧道订阅（或相反），
 * 单一全局「已订阅」标志会丢这个粒度。订阅状态在 0x5203 / 会话重建时清掉。
 */
object Iap2RouteGuidance {
    enum class Link { TUNNEL, BT }

    private data class Snapshot(
        val clear: Boolean,
        val state: Int,
        val road: String,
        val remainDistance: Int,
        val maneuverType: Int,
        val description: String,
        val sourceName: String,
    )

    /** 无数据时给订阅的合法空体。 */
    val IDLE_BODY: ByteArray = ByteArray(0)

    /**
     * 车机是否**明确**不接受 iAP2 导航引导（0x1D01 可收清单无 0x52xx）。
     *
     * 这是「不发送」的唯一判据，三条发送路径共用：0x5200 订阅应答、主动推帧、心跳。
     * 清单未到（null）返回 false —— 按未知放行，沿用「清单只能证明有、不能证明没有」；
     * 只有清单真的到过且里面没有 0x52xx，才认定这台车机不支持并彻底停发。
     */
    fun blockedByDeclaration(): Boolean = AccessoryDeclaration.acceptsRouteGuidance() == false

    const val DEFAULT_COMPONENT_ID = 42
    const val DEFAULT_SOURCE_APP = "CarLife"

    private const val APPLE_MANEUVER_DEPART = 0
    private const val APPLE_MANEUVER_ACTIVE = 2
    private const val STATE_ROUTE_READY = 3
    private const val STATE_GUIDING = 6
    private const val STATE_CLEARED = 0
    private const val DRIVING_SIDE_RIGHT = 2
    private const val MAX_ROAD_BYTES = 64
    private const val MAX_COMPONENTS_PER_LINK = 4

    private val lock = Any()
    private val subscribed = EnumMap<Link, LinkedHashSet<Int>>(Link::class.java)
    private val lastUpdateSeq = mutableMapOf<Pair<Link, Int>, Int>()
    private val lastManeuverSeq = mutableMapOf<Pair<Link, Int>, Int>()
    private var current: Snapshot? = null
    private var sequence = 0

    /** 是否还在导航活跃期；只有它为 true 时才允许心跳刷新 DiPlay 的 30s 超时。 */
    private var routeActive = false

    /**
     * 收到一条 CarLife 0x10030 后调用：编码并置为最新快照。
     * 真正发送由各链路在 [nextFramesFor] 里拉取，保证只发给已订阅的车机，
     * 也避免业务层绕过 iAP2 的发送闸门。
     *
     * `ACTION_END` 是显式结束：立刻换成 clear 快照并停活跃态；调用方紧接着
     * 扇出 [nextFramesFor]，车机会同步收到 0x5201 state=0 并清 HUD。
     */
    fun publish(
        info: CarLifeNaviNextTurn,
        sourceName: String = DEFAULT_SOURCE_APP,
    ) {
        val snapshot = toSnapshot(info, sourceName)
        install(snapshot, active = !snapshot.clear)
    }

    /**
     * CarLife+ 引擎断开（非本进程主动停止）时调用：与 [CarLifeNaviGuide.ACTION_END]
     * 同样处理，让车机立即结束 HUD 引导，不等 30s 陈旧兜底。
     */
    fun endRoute() {
        install(clearSnapshot(), active = false)
    }

    private fun install(snapshot: Snapshot, active: Boolean) {
        synchronized(lock) {
            current = snapshot
            routeActive = active
            sequence++
        }
    }

    /**
     * 车机 0x5200 订阅：从 TLV param 0（u16）解析组件 ID（未带时回退 [DEFAULT_COMPONENT_ID]
     * 兼容 DiPlay 等空体订阅），记录链路并返回应回的首组件 0x5201 载荷体（无数据时为空体）。
     */
    fun onSubscribe(
        link: Link,
        body: ByteArray = ByteArray(0),
    ): ByteArray {
        synchronized(lock) {
            val ids = parseComponentIds(body).ifEmpty { listOf(DEFAULT_COMPONENT_ID) }
            val linkComponents = subscribed.getOrPut(link) { linkedSetOf() }
            for (id in ids) {
                if (linkComponents.size < MAX_COMPONENTS_PER_LINK || id in linkComponents) {
                    linkComponents.add(id)
                }
            }
            val snapshot = current ?: return IDLE_BODY
            val primaryId = ids.first()
            // 订阅应答本身就是一帧 0x5201，只标记该组件 update 已发；0x5202 详情
            // 及额外组件留给紧随其后的下一拍补上，否则晚订阅的车机永远拿不到 maneuver。
            lastUpdateSeq[link to primaryId] = sequence
            return encodeUpdate(snapshot, primaryId)
        }
    }

    /** 车机 0x5203 停止订阅：带组件 ID 时仅移除对应组件，空体时清掉整条链路。 */
    fun onUnsubscribe(
        link: Link,
        body: ByteArray = ByteArray(0),
    ) {
        synchronized(lock) {
            val ids = parseComponentIds(body)
            if (ids.isEmpty()) {
                subscribed.remove(link)
                lastUpdateSeq.keys.removeAll { it.first == link }
                lastManeuverSeq.keys.removeAll { it.first == link }
                return
            }
            val linkComponents = subscribed[link] ?: return
            for (id in ids) {
                linkComponents.remove(id)
                lastUpdateSeq.remove(link to id)
                lastManeuverSeq.remove(link to id)
            }
            if (linkComponents.isEmpty()) subscribed.remove(link)
        }
    }

    fun isSubscribed(link: Link): Boolean = synchronized(lock) { !subscribed[link].isNullOrEmpty() }

    /**
     * 取出该链路尚未发过的帧：先发 0x5202（索引 0 与 2）定义转向条目，再发引用它们的 0x5201。
     * update 与 maneuver 分别按 (链路, 组件ID) 序号去重：订阅应答已经发过 0x5201 时，
     * 这里只补 0x5202，不会重复刷同一个状态帧。
     */
    fun nextFramesFor(link: Link): List<Pair<Int, ByteArray>> {
        synchronized(lock) {
            if (blockedByDeclaration()) return emptyList()
            val components = subscribed[link] ?: return emptyList()
            if (components.isEmpty()) return emptyList()
            val snapshot = current ?: return emptyList()
            val out = ArrayList<Pair<Int, ByteArray>>(components.size * 3)
            for (componentId in components) {
                val key = link to componentId
                if (!snapshot.clear && lastManeuverSeq[key] != sequence) {
                    lastManeuverSeq[key] = sequence
                    out += ROUTE_GUIDANCE_MANEUVER_UPDATE to encodeManeuver(snapshot, componentId, APPLE_MANEUVER_DEPART)
                    out += ROUTE_GUIDANCE_MANEUVER_UPDATE to encodeManeuver(snapshot, componentId, APPLE_MANEUVER_ACTIVE)
                }
                if (lastUpdateSeq[key] != sequence) {
                    lastUpdateSeq[key] = sequence
                    out += Iap2SubscriptionReplies.ROUTE_GUIDANCE_UPDATE to encodeUpdate(snapshot, componentId)
                }
            }
            return out
        }
    }

    /** 当前是否处于导航活跃期（心跳节拍据此决定是否继续跑）。 */
    fun hasActiveRoute(): Boolean = synchronized(lock) { routeActive && current != null }

    /**
     * 心跳帧（首组件）：只重发最近一版 0x5201，**不走序号去重**（同一版也要反复刷），
     * 且不带 0x5202——DiPlay 只更新 `lastRouteUpdateNs`，不需要重画 maneuver。
     *
     * 发过之后把该组件的 update 序号标记为已发，避免紧接着的 200ms 常规拍
     * 又重复推一帧完全相同的 0x5201。
     */
    fun nextHeartbeatFor(link: Link): Pair<Int, ByteArray>? {
        synchronized(lock) {
            if (blockedByDeclaration()) return null
            if (!routeActive) return null
            val componentId = subscribed[link]?.firstOrNull() ?: return null
            val snapshot = current ?: return null
            lastUpdateSeq[link to componentId] = sequence
            return Iap2SubscriptionReplies.ROUTE_GUIDANCE_UPDATE to encodeUpdate(snapshot, componentId)
        }
    }

    /** 心跳帧（该链路所有已订阅组件）：逐组件重发最近一版 0x5201。 */
    fun nextHeartbeatsFor(link: Link): List<Pair<Int, ByteArray>> {
        synchronized(lock) {
            if (blockedByDeclaration()) return emptyList()
            if (!routeActive) return emptyList()
            val components = subscribed[link] ?: return emptyList()
            if (components.isEmpty()) return emptyList()
            val snapshot = current ?: return emptyList()
            val out = ArrayList<Pair<Int, ByteArray>>(components.size)
            for (componentId in components) {
                lastUpdateSeq[link to componentId] = sequence
                out += Iap2SubscriptionReplies.ROUTE_GUIDANCE_UPDATE to encodeUpdate(snapshot, componentId)
            }
            return out
        }
    }

    /** 会话重建时清掉「上一轮已订阅/已发送」快照。 */
    fun reset() {
        synchronized(lock) {
            subscribed.clear()
            lastUpdateSeq.clear()
            lastManeuverSeq.clear()
            current = null
            routeActive = false
            sequence = 0
        }
    }

    private fun clearSnapshot(): Snapshot =
        Snapshot(
            clear = true,
            state = STATE_CLEARED,
            road = "",
            remainDistance = 0,
            maneuverType = 0,
            description = "",
            sourceName = DEFAULT_SOURCE_APP,
        )

    private fun toSnapshot(
        info: CarLifeNaviNextTurn,
        sourceName: String,
    ): Snapshot {
        if (info.action == CarLifeNaviGuide.ACTION_END) return clearSnapshot()
        val state =
            if (info.action == CarLifeNaviGuide.ACTION_START) STATE_ROUTE_READY else STATE_GUIDING
        val road = sanitizeRoad(info.roadName)
        val type = CarLifeNaviGuide.appleManeuverType(info.nextTurn)
        // 真机 0x5202 的 field2 是 maneuvers 文字；用「<转向><道路>」这个形态，
        // 车机即使不认识 field3 也能从文字里读出意图。
        val description = "${CarLifeNaviGuide.label(info.nextTurn)}$road"
        val cleanSource = sanitizeRoad(sourceName).ifEmpty { DEFAULT_SOURCE_APP }
        return Snapshot(
            clear = false,
            state = state,
            road = road,
            remainDistance = info.remainDistance.coerceAtLeast(0),
            maneuverType = type,
            description = description,
            sourceName = cleanSource,
        )
    }

    private fun encodeUpdate(
        snapshot: Snapshot,
        componentId: Int,
    ): ByteArray {
        if (snapshot.clear) {
            // 与真机结束帧一致：只带 0x00=componentId、0x01=0、0x07=0，不附 0x5202。
            return Iap2Csm.u16(0x00, componentId) +
                Iap2Csm.u8(0x01, STATE_CLEARED) +
                Iap2Csm.u32(0x07, 0L)
        }
        // CarLife 0x10030 field 4（totalDistance）是路段总距离而非全程剩余里程，
        // 不填入 iAP2 0x07（RouteGuidanceDistanceRemaining，规范可选字段），避免仪表显示错误总剩余里程。
        return Iap2Csm.u16(0x00, componentId) +
            Iap2Csm.u8(0x01, snapshot.state) +
            Iap2Csm.string(0x03, "") +
            (if (snapshot.road.isEmpty()) ByteArray(0) else Iap2Csm.string(0x04, snapshot.road)) +
            Iap2Csm.u32(0x0a, snapshot.remainDistance.toLong()) +
            // Apple 实测是 4 字节 00 00 00 02；DiPlay 按 u16 读前两字节得 0。
            Iap2Csm.u32(0x0d, APPLE_MANEUVER_ACTIVE.toLong()) +
            Iap2Csm.string(0x13, snapshot.sourceName) +
            Iap2Csm.u8(0x14, 1)
    }

    private fun encodeManeuver(
        snapshot: Snapshot,
        componentId: Int,
        index: Int,
    ): ByteArray =
        Iap2Csm.u16(0x00, componentId) +
            Iap2Csm.u16(0x01, index) +
            Iap2Csm.string(0x02, snapshot.description) +
            Iap2Csm.u8(0x03, snapshot.maneuverType) +
            (if (snapshot.road.isEmpty()) ByteArray(0) else Iap2Csm.string(0x04, snapshot.road)) +
            Iap2Csm.u8(0x07, DRIVING_SIDE_RIGHT) +
            Iap2Csm.string(0x08, "")

    private fun parseComponentIds(body: ByteArray): List<Int> {
        if (body.isEmpty()) return emptyList()
        val out = ArrayList<Int>(2)
        for (param in Iap2Csm.parseParams(body)) {
            if (param.id == 0x00 && param.value.size == 2) {
                val id = ((param.value[0].toInt() and 0xFF) shl 8) or (param.value[1].toInt() and 0xFF)
                if (id !in out) out += id
            }
        }
        return out
    }

    /** iAP2 字符串是 NUL 结尾 UTF-8，且路名过长会挤爆 CSM 分片预算。 */
    private fun sanitizeRoad(raw: String): String {
        val cleaned = raw.replace('\u0000', ' ').trim()
        val bytes = cleaned.encodeToByteArray()
        if (bytes.size <= MAX_ROAD_BYTES) return cleaned
        var end = MAX_ROAD_BYTES
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202
}
