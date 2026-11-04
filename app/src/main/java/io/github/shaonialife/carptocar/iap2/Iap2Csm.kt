package io.github.shaonialife.carptocar.iap2

import java.io.ByteArrayOutputStream

/**
 * iAP2 控制会话（CSM）分帧与 TLV 参数体编解码。
 *
 * 帧：`0x4040 | u16 总长(含头) | u16 消息ID | 体`，大端，无校验和。
 * 参数：`u16 参数总长(含4字节头) | u16 参数ID | 值`，可嵌套（参数值为子参数序列）。
 * 字符串以 NUL 结尾的 UTF-8 传输。
 *
 * CSM 字节流再由链路层 [Iap2LinkPacket]（会话ID 0）分片承载。
 */
object Iap2Csm {
    const val START = 0x4040
    const val HEADER_BYTES = 6
    const val MAX_FRAME_BYTES = 0xFFFF

    data class Frame(
        val messageId: Int,
        val body: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Frame && other.messageId == messageId && other.body.contentEquals(body)

        override fun hashCode(): Int = messageId * 31 + body.contentHashCode()
    }

    fun encode(
        messageId: Int,
        body: ByteArray = ByteArray(0),
    ): ByteArray {
        require(messageId in 0..0xFFFF) { "messageId must fit u16" }
        require(body.size <= MAX_FRAME_BYTES - HEADER_BYTES) { "body too large" }
        val out = ByteArray(HEADER_BYTES + body.size)
        writeU16(out, 0, START)
        writeU16(out, 2, out.size)
        writeU16(out, 4, messageId)
        body.copyInto(out, HEADER_BYTES)
        return out
    }

    /** 增量解析器：喂入任意分片的 CSM 字节流，弹出完整帧。 */
    class Parser {
        private var buffer = ByteArray(0)

        fun offer(chunk: ByteArray): List<Frame> {
            buffer += chunk
            val frames = ArrayList<Frame>()
            while (true) {
                val start = indexOfStart(buffer)
                if (start < 0) {
                    // 没有帧头：保留最后 1 字节以防跨 chunk 的 0x40
                    buffer = buffer.takeLast(1).toByteArray()
                    break
                }
                if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
                if (buffer.size < HEADER_BYTES) break
                val length = readU16(buffer, 2)
                if (length < HEADER_BYTES || length > MAX_FRAME_BYTES) {
                    buffer = buffer.copyOfRange(1, buffer.size)
                    continue
                }
                if (buffer.size < length) break
                val messageId = readU16(buffer, 4)
                frames += Frame(messageId, buffer.copyOfRange(HEADER_BYTES, length))
                buffer = buffer.copyOfRange(length, buffer.size)
            }
            return frames
        }

        private fun indexOfStart(bytes: ByteArray): Int {
            if (bytes.size < 2) return -1
            for (index in 0..bytes.size - 2) {
                if (bytes[index] == 0x40.toByte() && bytes[index + 1] == 0x40.toByte()) return index
            }
            return -1
        }
    }

    // ---- TLV 参数体 ----

    data class Param(
        val id: Int,
        val value: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Param && other.id == id && other.value.contentEquals(value)

        override fun hashCode(): Int = id * 31 + value.contentHashCode()
    }

    fun param(
        id: Int,
        value: ByteArray,
    ): ByteArray {
        require(id in 0..0xFFFF) { "paramId must fit u16" }
        val out = ByteArray(4 + value.size)
        writeU16(out, 0, out.size)
        writeU16(out, 2, id)
        value.copyInto(out, 4)
        return out
    }

    fun u8(
        id: Int,
        value: Int,
    ): ByteArray = param(id, byteArrayOf(value.toByte()))

    fun u16(
        id: Int,
        value: Int,
    ): ByteArray = param(id, byteArrayOf((value ushr 8).toByte(), value.toByte()))

    fun u32(
        id: Int,
        value: Long,
    ): ByteArray =
        param(
            id,
            byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            ),
        )

    fun string(
        id: Int,
        value: String,
    ): ByteArray {
        val nul: Char = 0.toChar()
        require(nul !in value) { "NUL-terminated string must not contain U+0000" }
        return param(id, value.encodeToByteArray() + byteArrayOf(0))
    }

    fun u16List(
        id: Int,
        values: IntArray,
    ): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { index, value ->
            out[index * 2] = (value ushr 8).toByte()
            out[index * 2 + 1] = value.toByte()
        }
        return param(id, out)
    }

    fun group(
        id: Int,
        vararg children: ByteArray,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        children.forEach(output::write)
        return param(id, output.toByteArray())
    }

    fun parseParams(body: ByteArray): List<Param> {
        val params = ArrayList<Param>()
        var offset = 0
        while (offset + 4 <= body.size) {
            val length = readU16(body, offset)
            if (length < 4 || offset + length > body.size) break
            params += Param(readU16(body, offset + 2), body.copyOfRange(offset + 4, offset + length))
            offset += length
        }
        return params
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

/**
 * 0x1D01（IdentificationInformation）里 EA 协议清单的解析。
 *
 * 单独提出来是为了能被单测直接咬住：车机可以用**同一个参数号（10/11/12）重复出现**
 * 来表达多个 EA 协议，而参数号并不是唯一键。奥迪 MIB3 一次声明三个
 * （de.audi.mmiconnectdatatransfer / com.audi.cpp.eadata0 / com.audi.cpp.eadata1），
 * 旧实现用 `associateBy { it.id }` 只留下最后一个，于是只对 eadata1 发了 0xEA00，
 * 车机一个 0xEA03 都不回、面板永远「等待 0xEA03 会话状态」
 * （2026-11-04 实车日志逐字节核对，见 `docs/audi-mib3-ea-tunnel-20261104.md`）。
 */
object Iap2Ea {
    /** EA 协议参数号：车机把这几个号各当作一个「EA 协议槽」，可以重复出现。 */
    private val GROUP_IDS = 10..12

    /**
     * 解析出 [(参数号, 协议号, 协议名)]，保留车机声明顺序、去重。
     *
     * 空名按 `?` 记，协议号取不到（子参数 0 缺失/为空）则跳过该槽。
     */
    fun parseProtocols(body: ByteArray): List<Triple<Int, Int, String>> {
        val out = ArrayList<Triple<Int, Int, String>>()
        for (param in Iap2Csm.parseParams(body)) {
            if (param.id !in GROUP_IDS) continue
            val sub = Iap2Csm.parseParams(param.value)
            val protocolId =
                sub.firstOrNull { it.id == 0 }
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: continue
            val name =
                sub.firstOrNull { it.id == 1 }?.value?.let { bytes ->
                    bytes.decodeToString().substringBefore('\u0000').ifEmpty { "?" }
                } ?: "?"
            if (out.any { it.second == protocolId && it.third == name }) continue
            out += Triple(param.id, protocolId, name)
        }
        return out
    }
}

fun Iap2Csm.Param.asString(): String? =
    if (value.isNotEmpty() && value.last() == 0.toByte()) {
        value.copyOf(value.size - 1).decodeToString()
    } else {
        null
    }

fun Iap2Csm.Param.asU16List(): List<Int> {
    require(value.size % 2 == 0) { "u16[] payload has odd length" }
    return List(value.size / 2) {
        ((value[it * 2].toInt() and 0xFF) shl 8) or (value[it * 2 + 1].toInt() and 0xFF)
    }
}
