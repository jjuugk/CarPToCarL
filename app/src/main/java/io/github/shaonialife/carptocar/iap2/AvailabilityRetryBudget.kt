package io.github.shaonialife.carptocar.iap2

/**
 * 「`0x4300` 发出后最多等多久才判定车机不读了」的预算。
 *
 * 判据本身是 [AvailabilityAckProgress]（对端 ACK 水位有没有追上最后一帧），
 * 这里只决定**给它多长时间**。
 *
 * 为什么要有这条快通道：链路级 `ack` 一旦不再推进，就说明车机已经不读这条链路了——
 * 它既不会回 0x4301，也不会回任何业务帧。旧行为是返回空端点进 SessionController 的
 * 5 分钟保活，一档一小轮、四档二十分钟，而实车往往只有一次点击机会。
 *
 * 两个值来源不同：
 *
 * - 标准 45 秒：三档报文体各一个 8 秒的 0x4301 等待窗口（24s）+ 每档 2 次重传的
 *   RTO（≈4s×2）留余量，刚好够把三档试完还多一点（2026-11-11 别克 E2LB / 3767）。
 * - 通用系 90 秒：对齐同源工程 link_plus 的 `SESSION_TIMEOUT_MILLIS = 90_000`。
 *   它对别克实车只设这一条单轮预算，0x4301 首窗口等不到也不拆链、不重跑识别。
 *   别克在 `0x4300` 之后「零业务帧」是常态（BUICK_3138 / 3767），如果它只是要
 *   60~90 秒才醒，45 秒的拆链就永远卡在它醒来之前（2026-11-14 定案）。
 *
 * 为什么只给通用系：奥迪 MMI 9310 的 ACK 一直正常、`0x4301` 晚到 2 分 35 秒，
 * 本来就不会被这条判据误伤——它的水位一直在追，碰不到这个 throw。把它的窗口
 * 也拉长只会让每轮多白等 45 秒，属回归。宝马 iD9 的 8 秒窗口是实测够用的，
 * 45 秒是给「车机重启 / 换档」留的快通道。
 */
internal object AvailabilityRetryBudget {
    /** 标准车机：三档报文体在一轮里试完的总宽限。 */
    const val STANDARD_FAST_RETRY_NANOS = 45_000_000_000L

    /** 通用系（Cinemo 栈）：对齐 link_plus 的单轮会话预算。 */
    const val CINEMO_FAST_RETRY_NANOS = 90_000_000_000L

    /**
     * @param cinemoStack 本轮车机是不是通用系 Cinemo 栈（[Iap2SenderSession] 的 `isCinemoStack`）
     * @return 本轮允许「车机还没读走最后一帧」持续多久，到点仍未追上就拆链换档
     */
    fun fastRetryNanos(cinemoStack: Boolean): Long =
        if (cinemoStack) CINEMO_FAST_RETRY_NANOS else STANDARD_FAST_RETRY_NANOS
}
