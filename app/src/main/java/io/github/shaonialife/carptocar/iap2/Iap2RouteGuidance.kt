package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.carlife.CarLifeNaviGuide
import io.github.shaonialife.carptocar.carlife.CarLifeNaviNextTurn
import java.util.EnumSet

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
 * - 左转的 0x5202 `[3] u8=1`，正是 CarLife `nextTurn=2` 的对应码；
 * - 驾驶侧实测 `u8=2`（不是 0），把 0 发成左行会让箭头左右颠倒；
 * - 0x5201 的 0x0d 是 **4 字节** `00 00 00 02`，DiPlay 按 u16 读前两字节得 0，
 *   指向索引 0 的「从…出发」maneuver；车机据此显示当前诱导。
 *   我们同时把同一条诱导写在索引 0 和索引 2，兼容两种读法。
 * - 结束帧只有 0x01=0（空串形态 `00 05 00 01 00`）与 0x07=0，DiPlay 据此 clear。
 *
 * 分发是**按链路**的：车机可能在蓝牙链路上订阅但没在 WiFi 隧道订阅（或相反），
 * 单一全局「已订阅」标志会丢这个粒度。订阅状态在 0x5203 / 会话重建时清掉。
 */
object Iap2RouteGuidance {
    enum class Link { TUNNEL, BT }

    private data class Bodies(
        val frames: List<Pair<Int, ByteArray>>,
        val update: ByteArray,
    )

    /** 无数据时给订阅的合法空体。 */
    val IDLE_BODY: ByteArray = ByteArray(0)

    private const val APPLE_MANEUVER_DEPART = 0
    private const val APPLE_MANEUVER_ACTIVE = 2
    private const val STATE_ROUTE_READY = 3
    private const val STATE_GUIDING = 6
    private const val STATE_CLEARED = 0
    private const val DRIVING_SIDE_RIGHT = 2
    private const val SOURCE_APP = "高德地图"
    private const val MAX_ROAD_BYTES = 64

    private val lock = Any()
    private val subscribed = EnumSet.noneOf(Link::class.java)
    private val lastUpdateSeq = mutableMapOf<Link, Int>()
    private val lastManeuverSeq = mutableMapOf<Link, Int>()
    private var current: Bodies? = null
    private var sequence = 0

    /**
     * 收到一条 CarLife 0x10030 后调用：编码并置为最新快照。
     * 真正发送由各链路在 [nextFramesFor] 里拉取，保证只发给已订阅的车机，
     * 也避免业务层绕过 iAP2 的发送闸门。
     */
    fun publish(info: CarLifeNaviNextTurn) {
        val bodies = encode(info) ?: return
        synchronized(lock) {
            current = bodies
            sequence++
        }
    }

    /** 车机 0x5200 订阅：记录链路并返回应回的 0x5201 载荷体（无数据时为空体）。 */
    fun onSubscribe(link: Link): ByteArray {
        synchronized(lock) {
            subscribed.add(link)
            val bodies = current ?: return IDLE_BODY
            // 订阅应答本身就是一帧 0x5201，只标记 update 已发；0x5202 详情
            // 留给紧随其后的下一拍补上，否则晚订阅的车机永远拿不到 maneuver。
            lastUpdateSeq[link] = sequence
            return bodies.update
        }
    }

    /** 车机 0x5203 停止订阅：清掉该链路状态。 */
    fun onUnsubscribe(link: Link) {
        synchronized(lock) {
            subscribed.remove(link)
            lastUpdateSeq.remove(link)
            lastManeuverSeq.remove(link)
        }
    }

    fun isSubscribed(link: Link): Boolean = synchronized(lock) { link in subscribed }

    /**
     * 取出该链路尚未发过的帧：0x5201 在前，随后是 0x5202（索引 0 与 2）。
     * update 与 maneuver 分别按序号去重：订阅应答已经发过 0x5201 时，这里
     * 只补 0x5202，不会重复刷同一个状态帧。
     */
    fun nextFramesFor(link: Link): List<Pair<Int, ByteArray>> {
        synchronized(lock) {
            if (link !in subscribed) return emptyList()
            val bodies = current ?: return emptyList()
            val out = ArrayList<Pair<Int, ByteArray>>(3)
            if (lastUpdateSeq[link] != sequence) {
                lastUpdateSeq[link] = sequence
                bodies.frames.firstOrNull()?.let { out += it }
            }
            if (bodies.frames.size > 1 && lastManeuverSeq[link] != sequence) {
                lastManeuverSeq[link] = sequence
                out.addAll(bodies.frames.drop(1))
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
            sequence = 0
        }
    }

    private fun encode(info: CarLifeNaviNextTurn): Bodies? {
        val ended = info.action == CarLifeNaviGuide.ACTION_END
        val state = when (info.action) {
            CarLifeNaviGuide.ACTION_START -> STATE_ROUTE_READY
            CarLifeNaviGuide.ACTION_END -> STATE_CLEARED
            else -> STATE_GUIDING
        }
        val road = sanitizeRoad(info.roadName)

        val update = if (ended) {
            // 与真机结束帧一致：0x00=42、0x01=0、0x07=0（不发送 0x5202）
            Iap2Csm.u16(0x00, 42) +
                Iap2Csm.u8(0x01, STATE_CLEARED) +
                Iap2Csm.u32(0x07, 0L)
        } else {
            Iap2Csm.u16(0x00, 42) +
                Iap2Csm.u8(0x01, state) +
                Iap2Csm.string(0x03, "") +
                (if (road.isEmpty()) ByteArray(0) else Iap2Csm.string(0x04, road)) +
                Iap2Csm.u32(0x07, info.totalDistance.coerceAtLeast(0).toLong()) +
                Iap2Csm.u32(0x0a, info.remainDistance.coerceAtLeast(0).toLong()) +
                // Apple 实测是 4 字节 00 00 00 02；DiPlay 按 u16 读前两字节得 0。
                Iap2Csm.u32(0x0d, APPLE_MANEUVER_ACTIVE.toLong()) +
                Iap2Csm.string(0x13, SOURCE_APP) +
                Iap2Csm.u8(0x14, 1)
        }

        if (ended) return Bodies(listOf(Iap2SubscriptionReplies.ROUTE_GUIDANCE_UPDATE to update), update)

        val type = CarLifeNaviGuide.appleManeuverType(info.nextTurn)
        // 真机 0x5202 的 field2 是 maneuvers 文字；用「<转向><道路>」这个形态，
        // 车机即使不认识 field3 也能从文字里读出意图。
        val description = "${CarLifeNaviGuide.label(info.nextTurn)}$road"
        val maneuverBody = { index: Int ->
            Iap2Csm.u16(0x00, 42) +
                Iap2Csm.u16(0x01, index) +
                Iap2Csm.string(0x02, description) +
                Iap2Csm.u8(0x03, type) +
                (if (road.isEmpty()) ByteArray(0) else Iap2Csm.string(0x04, road)) +
                Iap2Csm.u8(0x07, DRIVING_SIDE_RIGHT) +
                Iap2Csm.string(0x08, "")
        }
        // 索引 0 = Apple 的「从…出发」，索引 2 = 抓包里 0x0d 的字面值。
        // 两条都写同一条诱导，这样车机无论把 0x0d 当 u16 还是 u32 读，箭头都对。
        val frames =
            listOf(
                Iap2SubscriptionReplies.ROUTE_GUIDANCE_UPDATE to update,
                ROUTE_GUIDANCE_MANEUVER_UPDATE to maneuverBody(APPLE_MANEUVER_DEPART),
                ROUTE_GUIDANCE_MANEUVER_UPDATE to maneuverBody(APPLE_MANEUVER_ACTIVE),
            )
        return Bodies(frames, update)
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
