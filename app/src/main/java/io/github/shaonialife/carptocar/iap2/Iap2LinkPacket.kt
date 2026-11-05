package io.github.shaonialife.carptocar.iap2

/**
 * iAP2 链路层（Link Layer）包编解码。
 *
 * 无线（蓝牙 RFCOMM）与有线（USB bulk）承载的是同一套链路层字节流：
 *
 *   FF 5A | u16 长度 | u8 控制 | u8 seq | u8 ack | u8 会话ID | u8 头校验和 | 载荷 | u8 载荷校验和
 *
 * SYN 同步包以 FF 55 开头。校验和为「字节和的补码」：把包括校验和在内的所有字节
 * 相加（mod 256）结果必须为 0。
 *
 * 格式出处（公开逆向资料，字节级一致）：
 *  - reference/carplay-wifi-extractor example/iap2/link_layer.py（Struct(">HHBBBB") + gen_checksum）
 *  - Oligo Security《Pwn My Ride》对 iAP2 链路层的描述
 *
 * 会话ID约定：0 = 控制/认证，1 = 数据，2+ = External Accessory 流。
 *
 * Phase 1 待办：SYN/ACK 状态机（收发序号、重传、参数协商 LinkSynchronizationPayload
 * ">BBHHHBB"：version=1, maxOutgoing, maxLen, retransTimeout, ackTimeout, maxRetrans, maxAck）。
 */
object Iap2LinkPacket {
    const val START_DATA = 0xFF5A
    const val START_SYN = 0xFF55

    const val CTRL_SYN: Int = 0x80
    const val CTRL_ACK: Int = 0x40
    const val CTRL_EAK: Int = 0x20
    const val CTRL_RST: Int = 0x10

    /** FF5A + len(2) + ctrl(1) + seq(1) + ack(1) + sessionId(1) + 头校验和(1) */
    const val HEADER_BYTES = 9
    const val CHECKSUM_BYTES = 1

    const val SESSION_CONTROL = 0
    const val SESSION_DATA = 1

    data class LinkPacket(
        val control: Int,
        val seq: Int,
        val ack: Int,
        val sessionId: Int,
        val payload: ByteArray,
    ) {
        val isSyn: Boolean get() = control and CTRL_SYN != 0
        val isAck: Boolean get() = control and CTRL_ACK != 0

        override fun equals(other: Any?): Boolean =
            other is LinkPacket &&
                other.control == control &&
                other.seq == seq &&
                other.ack == ack &&
                other.sessionId == sessionId &&
                other.payload.contentEquals(payload)

        override fun hashCode(): Int = control * 31 + seq * 961 + ack * 31 + sessionId + payload.contentHashCode()
    }

    /** 所有字节（含校验和）相加 mod 256 应为 0；因此校验和 = -(载荷和) & 0xFF。 */
    fun checksum(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ): Int {
        var sum = 0
        for (index in from until to) sum += bytes[index].toInt() and 0xFF
        return (-sum) and 0xFF
    }

    fun verify(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ): Boolean {
        var sum = 0
        for (index in from until to) sum += bytes[index].toInt() and 0xFF
        return sum and 0xFF == 0
    }

    fun encode(packet: LinkPacket): ByteArray {
        val totalLen = HEADER_BYTES + packet.payload.size + CHECKSUM_BYTES
        val out = ByteArray(totalLen)
        writeU16(out, 0, START_DATA)
        writeU16(out, 2, totalLen)
        out[4] = packet.control.toByte()
        out[5] = packet.seq.toByte()
        out[6] = packet.ack.toByte()
        out[7] = packet.sessionId.toByte()
        out[8] = checksum(out, 0, 8).toByte()
        packet.payload.copyInto(out, HEADER_BYTES)
        out[out.size - 1] = checksum(out, HEADER_BYTES, out.size - 1).toByte()
        return out
    }

    /**
     * 把一段链路字节按**帧边界**切开（发送侧用，返回的每一段应单独成一次传输）。
     *
     * 为什么要切：接收端对 comm 隧道包的解析粒度是「一个传输单元 = 一个 iAP2 包」。
     * 把 SYN 标记与后面的数据帧合并在同一个 `APTransportPackage` 里写出去，会让按此
     * 粒度解析的接收端（QNX 系车机、部分原生实现）在第二个帧上错位，症状是投屏跑到
     * 一半**随机卡死**——不是每次都死，所以很难归因。依据：2026-11-05《CarPlay 手机侧
     * 分析报告》§9「同一传输一个 iAP2 包（发送侧策略）：接收侧按流式处理即可，但发送时
     * 不要合并」。
     *
     * 两种包头共用「FF 5A/55 + u16」，但只有数据帧按帧长切：
     *  - `FF 5A` 数据帧：头后 u16 = **整帧长度**（9B 头 + 载荷 + 1B 校验），见 [encode]；
     *  - `FF 55` 同步标记：固定 6 字节 `FF 55 02 00 EE 10`。它头后的 `02 00` **不是**
     *    大端帧长（按大端读是 512，按小端读是 2，两种都和 6 字节的实物对不上），
     *    只能按形态识别——它是本项目链路引擎自己产出的唯一 SYN 形态（`MARKER`）。
     *
     * **保守策略**：任何一处对不上（首字节不是已知包头、长度越界、末尾残帧）就整段
     * 原样返回一个元素，调用方按旧的「一坨」写出——宁可保持现状，也绝不因为切分
     * 算法猜错而丢掉链路字节（丢一帧等于整条控制链路死锁）。
     */
    fun splitFrames(bytes: ByteArray): List<ByteArray> {
        if (bytes.size < 4) return listOf(bytes)
        val frames = ArrayList<ByteArray>(4)
        var offset = 0
        while (offset < bytes.size) {
            if (offset + 4 > bytes.size) return listOf(bytes)
            val start = readU16(bytes, offset)
            val declared = readU16(bytes, offset + 2)
            val frameLength =
                when (start) {
                    // 整帧长度。下限 HEADER_BYTES + CHECKSUM_BYTES，否则是脏数据
                    START_DATA -> declared.takeIf { it >= HEADER_BYTES + CHECKSUM_BYTES } ?: return listOf(bytes)
                    // 同步标记固定 6 字节，尾部必须是 EE 10（见方法说明）
                    START_SYN -> SYN_MARKER_BYTES.takeIf { canTakeSynMarker(bytes, offset) } ?: return listOf(bytes)
                    else -> return listOf(bytes)
                }
            if (offset + frameLength > bytes.size) return listOf(bytes)
            frames += bytes.copyOfRange(offset, offset + frameLength)
            offset += frameLength
        }
        return if (frames.isEmpty()) listOf(bytes) else frames
    }

    /** 同步标记 `FF 55 02 00 EE 10` 的固定长度。 */
    private const val SYN_MARKER_BYTES = 6

    /** 偏移处是否是一个完整的同步标记（够长且尾标为 `EE 10`）。 */
    private fun canTakeSynMarker(
        bytes: ByteArray,
        offset: Int,
    ): Boolean =
        offset + SYN_MARKER_BYTES <= bytes.size &&
            bytes[offset + 4] == 0xEE.toByte() &&
            bytes[offset + 5] == 0x10.toByte()

    /** 单个数据包（FF5A）的定长解析；不定长/流式重组见 [Iap2Csm] 与 Phase 1 的链路状态机。 */
    fun decode(bytes: ByteArray): LinkPacket? {
        if (bytes.size < HEADER_BYTES + CHECKSUM_BYTES) return null
        if (readU16(bytes, 0) != START_DATA) return null
        if (!verify(bytes, 0, HEADER_BYTES)) return null
        val length = readU16(bytes, 2)
        if (length != bytes.size) return null
        if (!verify(bytes, HEADER_BYTES, bytes.size)) return null
        return LinkPacket(
            control = bytes[4].toInt() and 0xFF,
            seq = bytes[5].toInt() and 0xFF,
            ack = bytes[6].toInt() and 0xFF,
            sessionId = bytes[7].toInt() and 0xFF,
            payload = bytes.copyOfRange(HEADER_BYTES, bytes.size - 1),
        )
    }

    private fun readU16(
        bytes: ByteArray,
        offset: Int,
    ): Int = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun writeU16(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
