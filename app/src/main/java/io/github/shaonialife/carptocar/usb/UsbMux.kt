package io.github.shaonialife.carptocar.usb

/**
 * USBMUX v2 线格式（发送端 = 设备侧）。
 *
 * 依据（2026-10-27 反编译对照 shihabal3amri/DiPlay，GPL-3.0，
 * `shared/.../transport/Iap2UsbMuxHost.kt`，车机侧 USB 主机实现）：
 *
 * - 帧头固定 16 字节：`proto:u32 | length:u32 | magic:u32 | seq:u16 | ack:u16`
 *   （该文件 `sendFrame()`：offset 0/4/8 为 u32，12/14 为 u16；`magic` 只在校验接收方向，
 *   iPhone 回包不带 0xFEEDFACE，所以**解析时不能强制校验 magic**，见 `takeFrame()`）。
 * - `proto=0` 版本帧：**总长 20**（16 头 + u32 version=2）。DiPlay 注释明确：
 *   「The phone replies with the same proto=0, length=20, version=2 packet」——
 *   我们作为设备侧只需**原样回一帧**，不要去等 proto=1。
 * - `proto=2` setup 帧：头 + 1 字节 `0x07`（`SETUP_VALUE`）。
 * - `proto=6` TCP 帧：头 + 20 字节 TCP 头 + 载荷。TCP 头字段顺序
 *   `srcPort:u16 | dstPort:u16 | seq:u32 | ack:u32 | dataOffset/flags:u8x2 | window:u16`，
 *   dataOffset 字节 = `20/4 << 4 = 0x50`，window 固定 512（`sendTcp()` 实测值）。
 *
 * 为什么单独实现而不是复用 `Iap2LinkChannel`：那条链路是 iAP2 CSM 帧，这里是内核之外的
 * 「USB 多路复用 + 内部 TCP」层，两者是串联关系（iAP2 走 mux 里的某个 TCP 端口）。
 */
object UsbMux {
    const val PROTOCOL_VERSION = 0
    const val PROTOCOL_SETUP = 2
    const val PROTOCOL_TCP = 6

    /** iPhone 回包不带这个字，仅发送方向携带；解析时不校验。 */
    const val MAGIC = 0xFEEDFACE.toInt()

    const val VERSION = 2

    const val HEADER_BYTES = 16
    const val VERSION_MESSAGE_BYTES = 20
    const val TCP_HEADER_BYTES = 20

    const val SETUP_VALUE = 0x07

    /** iAP2/lockdown 服务端口（车机会先连这里，再连 iAP2 端口）。 */
    const val LOCKDOWN_PORT = 62078

    const val TCP_WINDOW = 512

    const val FLAG_FIN = 0x01
    const val FLAG_SYN = 0x02
    const val FLAG_RST = 0x04
    const val FLAG_PSH = 0x08
    const val FLAG_ACK = 0x10

    /** 单帧上限：超过即判定为流错位，避免无限等待把内存吃穿。 */
    const val MAX_FRAME_BYTES = 1 shl 20

    data class Frame(
        val protocol: Int,
        val length: Int,
        val magic: Int,
        val sequence: Int,
        val acknowledgement: Int,
        val payload: ByteArray,
    )

    data class TcpSegment(
        val sourcePort: Int,
        val destinationPort: Int,
        val sequence: Long,
        val acknowledgement: Long,
        val flags: Int,
        val payload: ByteArray,
    )

    /** proto=0 版本应答帧（设备侧收到握手后原样回）。 */
    fun versionFrame(sequence: Int = 0, acknowledgement: Int = 0): ByteArray {
        val out = ByteArray(VERSION_MESSAGE_BYTES)
        putU32(out, 0, PROTOCOL_VERSION)
        putU32(out, 4, VERSION_MESSAGE_BYTES)
        putU32(out, 8, MAGIC)
        putU16(out, 12, sequence)
        putU16(out, 14, acknowledgement)
        putU32(out, 16, VERSION)
        return out
    }

    /** proto=2 setup 帧。 */
    fun setupFrame(sequence: Int = 0, acknowledgement: Int = 0): ByteArray =
        frame(PROTOCOL_SETUP, byteArrayOf(SETUP_VALUE.toByte()), sequence, acknowledgement)

    /**
     * proto=6 TCP 帧。
     *
     * @param sequence 我方序号；@param acknowledgement 已确认的对端序号。
     */
    fun tcpFrame(
        sourcePort: Int,
        destinationPort: Int,
        sequence: Long,
        acknowledgement: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        muxSequence: Int = 0,
        muxAcknowledgement: Int = 0,
    ): ByteArray {
        require(sourcePort in 1..0xFFFF) { "sourcePort 越界：$sourcePort" }
        require(destinationPort in 1..0xFFFF) { "destinationPort 越界：$destinationPort" }
        val tcp = ByteArray(TCP_HEADER_BYTES + payload.size)
        putU16(tcp, 0, sourcePort)
        putU16(tcp, 2, destinationPort)
        putU32Long(tcp, 4, sequence)
        putU32Long(tcp, 8, acknowledgement)
        tcp[12] = ((TCP_HEADER_BYTES / 4) shl 4).toByte()
        tcp[13] = flags.toByte()
        putU16(tcp, 14, TCP_WINDOW)
        payload.copyInto(tcp, TCP_HEADER_BYTES)
        return frame(PROTOCOL_TCP, tcp, muxSequence, muxAcknowledgement)
    }

    fun frame(
        protocol: Int,
        payload: ByteArray,
        sequence: Int = 0,
        acknowledgement: Int = 0,
    ): ByteArray {
        val out = ByteArray(HEADER_BYTES + payload.size)
        putU32(out, 0, protocol)
        putU32(out, 4, out.size)
        putU32(out, 8, MAGIC)
        putU16(out, 12, sequence)
        putU16(out, 14, acknowledgement)
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    /** 解析 proto=6 的 TCP 段；结构非法时抛 [IllegalArgumentException]。 */
    fun parseTcp(payload: ByteArray): TcpSegment {
        require(payload.size >= TCP_HEADER_BYTES) { "TCP 载荷短于 20 字节头：${payload.size}" }
        val dataOffset = ((payload[12].toInt() ushr 4) and 0x0F) * 4
        require(dataOffset in TCP_HEADER_BYTES..payload.size) { "TCP dataOffset 非法：$dataOffset" }
        return TcpSegment(
            sourcePort = readU16(payload, 0),
            destinationPort = readU16(payload, 2),
            sequence = readU32Long(payload, 4),
            acknowledgement = readU32Long(payload, 8),
            flags = payload[13].toInt() and 0xFF,
            payload = payload.copyOfRange(dataOffset, payload.size),
        )
    }

    fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    fun readU32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    fun readU32Long(bytes: ByteArray, offset: Int): Long = readU32(bytes, offset).toLong() and 0xFFFFFFFFL

    fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }

    fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }

    fun putU32Long(bytes: ByteArray, offset: Int, value: Long) =
        putU32(bytes, offset, (value and 0xFFFFFFFFL).toInt())

    /**
     * 增量拆帧器：USB bulk 一次读到的可能是半帧，也可能是多帧粘连（DiPlay 明确踩过
     * 「上一实例残留的 TCP 帧被当成握手应答」的坑，见 `MAX_STALE_HANDSHAKE_FRAMES`）。
     */
    class Framer(initialCapacity: Int = 8 * 1024) {
        private var buffer = ByteArray(initialCapacity.coerceAtLeast(HEADER_BYTES * 4))
        private var start = 0
        private var end = 0

        val bufferedBytes: Int get() = end - start

        fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
            if (length <= 0) return
            ensure(end + length)
            bytes.copyInto(buffer, end, offset, offset + length)
            end += length
        }

        /** 取出一整帧；数据不足返回 null（不是错误）。 */
        fun take(): Frame? {
            if (bufferedBytes < HEADER_BYTES) return null
            val length = readU32(buffer, start + 4)
            require(length in HEADER_BYTES..MAX_FRAME_BYTES) {
                "USBMUX 帧长度非法：$length（流可能已错位）"
            }
            if (bufferedBytes < length) return null
            val frame =
                Frame(
                    protocol = readU32(buffer, start),
                    length = length,
                    magic = readU32(buffer, start + 8),
                    sequence = readU16(buffer, start + 12),
                    acknowledgement = readU16(buffer, start + 14),
                    payload = buffer.copyOfRange(start + HEADER_BYTES, start + length),
                )
            start += length
            compactIfNeeded()
            return frame
        }

        private fun ensure(required: Int) {
            if (required <= buffer.size) return
            var next = buffer.size
            while (next < required) next = next shl 1
            buffer = buffer.copyOf(next)
        }

        private fun compactIfNeeded() {
            if (start == end) {
                start = 0
                end = 0
                return
            }
            if (start < buffer.size / 2) return
            val remaining = end - start
            buffer.copyInto(buffer, 0, start, end)
            start = 0
            end = remaining
        }
    }
}
