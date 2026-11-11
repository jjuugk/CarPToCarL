package io.github.shaonialife.carptocar.iap2

/**
 * 「`0x4300` 到底被车机读走了没有」的判据。
 *
 * 2026-11-11 21:12 BUICK_3767 现场：首档 `0x4300`（seq=46）被 ACK 之后，车机就不再读
 * 任何帧——后两档全被丢弃，对端 `ack` 一直停在 43，而我们按旧行为返回空端点进了
 * 5 分钟保活，一档一小轮、四档二十分钟。判断依据只能是**链路 ACK 水位**：
 * 水位没追上我们最后发出的那一帧，就说明那一帧根本没被读走，`0x4301` 不会凭空冒出来。
 *
 * 单独抽出来是为了可测：序号是 8 位环形的，且 `ack` 的语义各家不一
 * （「已收到的最后一个」vs「期望的下一个」），两种都得算追上。
 */
internal object AvailabilityAckProgress {
    /**
     * @param acknowledged 对端当前报告的 ack 序号（null = 本轮它一次都没 ACK 过）
     * @param lastSent 我方最后发出的控制帧序号
     * @return true = 最后一帧已被读走（含两种 ack 语义）；false = 还没追上
     */
    fun caughtUp(
        acknowledged: Int?,
        lastSent: Int,
    ): Boolean {
        if (acknowledged == null) return false
        val acked = acknowledged and 0xFF
        val sent = lastSent and 0xFF
        // 已收到的最后一个：差值 0；期望的下一个：差值 -1（环上即 255）。
        val delta = (sent - acked) and 0xFF
        return delta == 0x00 || delta == 0xFF
    }
}
