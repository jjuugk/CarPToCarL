package io.github.shaonialife.carptocar.iap2

/**
 * 车机 `0x1D01` 能力清单（参 6 = 「车机会发」、参 7 = 「车机可收」）的日志增强。
 *
 * 两条链路共用：蓝牙侧 [Iap2SenderSession]、隧道/事件侧 VirtualIap2Link。读的是同一个清单，
 * 而「这台车机新增了什么能力」只能靠这两行日志判断——所以解析和措辞必须一致，别再各写一份。
 *
 * **本次要回答的问题**：奥迪 MIB3 4398（媒体驱动 `CL3B`）新增「CarPlay 导航精简上仪表 + 投 HUD」。
 * HUD 是仪表 ECU 驱动的 TFT/DLP 投影，接图形指令不接 H.264，物理上收不了视频流；
 * 所以车机必然是从 CarPlay 会话里取**结构化导航数据**（转向类型/距离/路名），
 * 那它就必须在可收清单里声明导航引导（`0x52xx`）。
 * **清单里有没有这一项，是「能不能复现」最直接的证据**——比抓包快，也比买固件便宜。
 *
 * 输出分三层，逐层收窄：
 *  1. 原始清单：ID 原样，可 grep，可与历史日志逐项对比；
 *  2. 按子系统分组：每类一行——单行几十个 ID 会被日志长度截断，分组后不会；
 *  3. 无目录名的 ID：iAP2 目录里没有名字的，就是完全陌生的新东西，只能靠报文体反推。
 *
 * 注意：**「有名字」不等于「我们实现了处理」**。0x5200 在目录里有名字，但发送端从未发过
 * 导航更新——这正是导航提示要单独说一句的原因。
 */
object Iap2CapabilityReport {

    /** iAP2 子系统归类。只用于日志分组，不参与任何协议判定。 */
    enum class Group(val label: String) {
        IDENT("识别/MFi"),
        WIRELESS("无线/WiFi"),
        NAVIGATION("导航引导"),
        MEDIA("媒体"),
        TELEPHONY("通话/通讯录"),
        POWER("电源"),
        HID("HID/输入"),
        EA("EA 会话"),
        VEHICLE("车辆状态"),
        UNKNOWN("其他/未知"),
    }

    /** 按 `0xNNNN` 段归堆。区间取各子系统的惯用段，边界值出现时以目录名为准（[hasName]）。 */
    fun groupOf(id: Int): Group =
        when (id) {
            0x1D00, 0x1D01, 0x1D02, 0x1D03 -> Group.IDENT
            in 0xAA00..0xAA0F -> Group.IDENT
            0x4300, 0x4301, 0x5702, 0x5703 -> Group.WIRELESS
            in 0x4E09..0x4E0F -> Group.WIRELESS
            // 0x00B0~0x00B4 = OOBBTPairing（无线 CarPlay 的带外蓝牙配对，交换 link key）
            in Iap2MessageIds.OOB_BT_PAIRING_BASE..0x00B4 -> Group.WIRELESS
            // 0x52xx = Route Guidance（0x5200 StartRouteGuidanceUpdates 是已确认的那个）
            in 0x5200..0x52FF -> Group.NAVIGATION
            in 0x5000..0x50FF -> Group.MEDIA
            in 0x4150..0x415F -> Group.TELEPHONY
            0x4170, 0x4171 -> Group.TELEPHONY
            in 0xAE00..0xAEFF -> Group.POWER
            in 0x6800..0x68FF -> Group.HID
            in 0xEA00..0xEAFF -> Group.EA
            in 0xA100..0xA1FF -> Group.VEHICLE
            else -> Group.UNKNOWN
        }

    /** 目录里有名字（[Iap2MessageIds.nameOf] 对未知 ID 会回退成 `0xNNNN` 字面量）。 */
    fun hasName(id: Int): Boolean = !Iap2MessageIds.nameOf(id).startsWith("0x")

    /** `0x5200(StartRouteGuidanceUpdates)` / 未知则 `0xad00`。 */
    fun describe(id: Int): String =
        if (hasName(id)) "0x%04x(%s)".format(id, Iap2MessageIds.nameOf(id)) else "0x%04x".format(id)

    /**
     * 打一份清单。
     *
     * @param linePrefix 行前缀（`"IAPLINK: "` / `"iap2 0x1d01 "`），保持与原日志同款便于 grep
     * @param kind 清单种类，只用在文案里（`"发送清单"` / `"可收清单"`）
     * @param isReceiveList 是否「车机可收」——决定导航提示的措辞方向
     */
    fun report(
        log: (String) -> Unit,
        linePrefix: String,
        kind: String,
        ids: List<Int>,
        isReceiveList: Boolean,
    ) {
        if (ids.isEmpty()) {
            log("${linePrefix}车机${kind}=空（车机未声明任何 ID）")
            return
        }
        // 第 1 层：原始清单。保持原格式（ID 空格分隔、小写四位），历史日志可直接对比
        log("${linePrefix}车机${kind}(${ids.size})=" + ids.joinToString(" ") { "0x%04x".format(it) })

        // 第 2 层：按子系统分组，每类一行
        val grouped = ids.groupBy(::groupOf)
        Group.values().forEach { group ->
            val members = grouped[group] ?: return@forEach
            log(
                "${linePrefix}${kind}·${group.label}(${members.size})=" +
                    members.joinToString(" ") { describe(it) },
            )
        }

        // 第 3 层：目录里没有的 ID——完全陌生的新东西
        val unnamed = ids.filterNot(::hasName)
        if (unnamed.isNotEmpty()) {
            log(
                "${linePrefix}${kind}中的陌生 ID(${unnamed.size})=" +
                    unnamed.joinToString(" ") { "0x%04x".format(it) } +
                    "（iAP2 目录无此号，需按报文体反推）",
            )
        }

        // 导航专判：这是本次「投仪表/HUD」的唯一入口，值得单独占一行
        val nav = grouped[Group.NAVIGATION].orEmpty()
        when {
            nav.isNotEmpty() && isReceiveList ->
                log(
                    "${linePrefix}★ ${kind}声明导航引导(${nav.size})=" +
                        nav.joinToString(" ") { describe(it) } +
                        " → 车机接受 CarPlay 导航数据（MIB3 4398/CL3B 起）；本程序尚未发送，这就是复现入口",
                )
            nav.isNotEmpty() ->
                log(
                    "${linePrefix}★ ${kind}含导航引导(${nav.size})=" +
                        nav.joinToString(" ") { describe(it) } +
                        " → 车机会主动下发导航相关消息，注意抓它的报文体（订阅请求或 maneuver 样本）",
                )
            isReceiveList ->
                log(
                    "${linePrefix}${kind}无导航引导(0x52xx) → 车机不接受 CarPlay 导航数据，" +
                        "升级后仪表/HUD 也不会出箭头",
                )
        }
    }
}
