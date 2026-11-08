package io.github.shaonialife.carptocar.audio

import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 麦克风上行解包测试：用**发送端视角**（AirPlayCrypto.chachaSeal）造包，再用接收端路径解开。
 *
 * 覆盖的是 2026-11-08 修订后的真实机制（主音频 SETUP 带 dataPort → 车机往我们端口推麦）：
 *  - 线格式 `[RTP 头][密文+16B tag][8B 尾部 nonce]`、AAD = 头 [4..12)；
 *  - 尾部 nonce 的小端/大端两种存放；
 *  - 不带尾部 nonce、改用序号计数器的变体；
 *  - RTP 头带 CSRC / 扩展时头长度不能算错（按 12 硬切会「每包都解不开」）。
 *
 * 密钥派生（`DataStream-Input-Encryption-Key`）不在这里测——那是 [AirPlayCrypto.hkdfSha512]
 * 的既有行为，本类只关心「拿到密钥之后怎么解」。
 */
class MicUplinkPacketTest {
    private val key = ByteArray(32) { (it * 5 + 3).toByte() }
    private val uplink = MicUplink(log = {})

    private val leTail = MicUplink.WireFormat(nonceFromTail = true, bigEndianNonce = false, useAad = true)
    private val beTail = MicUplink.WireFormat(nonceFromTail = true, bigEndianNonce = true, useAad = true)
    private val counterOnly = MicUplink.WireFormat(nonceFromTail = false, bigEndianNonce = false, useAad = true)

    /** 组一个标准 RTP 头（version=2、PT=100、seq/ts/ssrc 大端）。 */
    private fun rtpHeader(
        sequence: Int = 1,
        timestamp: Long = 0,
        ssrc: Long = 0x11223344,
        csrcCount: Int = 0,
        extension: ByteArray? = null,
    ): ByteArray {
        val base = 12 + 4 * csrcCount
        val head = ByteArray(base + if (extension != null) 4 + extension.size else 0)
        head[0] = (0x80 or csrcCount or (if (extension != null) 0x10 else 0)).toByte()
        head[1] = 100.toByte()
        head[2] = ((sequence ushr 8) and 0xFF).toByte()
        head[3] = (sequence and 0xFF).toByte()
        head[4] = ((timestamp ushr 24) and 0xFF).toByte()
        head[5] = ((timestamp ushr 16) and 0xFF).toByte()
        head[6] = ((timestamp ushr 8) and 0xFF).toByte()
        head[7] = (timestamp and 0xFF).toByte()
        head[8] = ((ssrc ushr 24) and 0xFF).toByte()
        head[9] = ((ssrc ushr 16) and 0xFF).toByte()
        head[10] = ((ssrc ushr 8) and 0xFF).toByte()
        head[11] = (ssrc and 0xFF).toByte()
        if (extension != null) {
            // 扩展头的 length 字段以 32bit 字为单位，位于扩展块的 2..4 字节
            val words = extension.size / 4
            head[base + 2] = ((words ushr 8) and 0xFF).toByte()
            head[base + 3] = (words and 0xFF).toByte()
            extension.copyInto(head, base + 4)
        }
        return head
    }

    private fun tail(
        counter: Long,
        bigEndian: Boolean,
    ): ByteArray =
        ByteArray(8) { index ->
            val shift = if (bigEndian) 8 * (7 - index) else 8 * index
            ((counter ushr shift) and 0xFF).toByte()
        }

    private fun buildPacket(
        plain: ByteArray,
        counter: Long = 0,
        header: ByteArray = rtpHeader(),
        withTail: Boolean = true,
        tailBigEndian: Boolean = false,
        aad: Boolean = true,
    ): ByteArray {
        val aadBytes = if (aad) header.copyOfRange(4, 12) else ByteArray(0)
        val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, aadBytes)
        return if (withTail) {
            header + sealed + tail(counter, tailBigEndian)
        } else {
            header + sealed
        }
    }

    @Test
    fun decodesTailLittleEndianNoncePacket() {
        val plain = ByteArray(960) { (it * 3).toByte() }
        val packet = buildPacket(plain, counter = 7)
        val decoded = uplink.decodeForTest(key, packet, packet.size, 0, leTail)
        assertNotNull(decoded)
        assertArrayEquals(plain, decoded)
    }

    @Test
    fun decodesTailBigEndianNoncePacket() {
        val plain = ByteArray(320) { (it + 1).toByte() }
        val packet = buildPacket(plain, counter = 12, tailBigEndian = true)
        val decoded = uplink.decodeForTest(key, packet, packet.size, 0, beTail)
        assertNotNull(decoded)
        assertArrayEquals(plain, decoded)
    }

    @Test
    fun decodesCounterNoncePacketWithoutTail() {
        val plain = ByteArray(160) { (it * 2).toByte() }
        val packet = buildPacket(plain, counter = 31, withTail = false)
        val decoded = uplink.decodeForTest(key, packet, packet.size, 31, counterOnly)
        assertNotNull(decoded)
        assertArrayEquals(plain, decoded)
    }

    @Test
    fun zeroAadVariantDecodesOnlyWithZeroAadFormat() {
        val plain = ByteArray(64) { it.toByte() }
        val packet = buildPacket(plain, counter = 3, aad = false)
        // 用「带 AAD」的格式解必然失败（tag 校验不过）——这正是首包要探测的原因
        assertNull(uplink.decodeForTest(key, packet, packet.size, 0, leTail))
        val decoded = uplink.decodeForTest(key, packet, packet.size, 0, leTail.copy(useAad = false))
        assertNotNull(decoded)
        assertArrayEquals(plain, decoded)
    }

    @Test
    fun rejectsWrongKeyAndTamperedPacket() {
        val plain = ByteArray(128) { it.toByte() }
        val packet = buildPacket(plain, counter = 5)
        assertNull(uplink.decodeForTest(ByteArray(32) { 9 }, packet, packet.size, 0, leTail))

        val tampered = packet.copyOf()
        tampered[14] = (tampered[14].toInt() xor 0x01).toByte()
        assertNull(uplink.decodeForTest(key, tampered, tampered.size, 0, leTail))
    }

    @Test
    fun rtpHeaderLengthAccountsForCsrcAndExtension() {
        val plain = ByteArray(96) { (it * 7).toByte() }
        // 2 个 CSRC + 一个 8 字节扩展 → 头长 12 + 8 + 4 + 8 = 32
        val header = rtpHeader(csrcCount = 2, extension = ByteArray(8) { 0x5A })
        val packet = buildPacket(plain, counter = 2, header = header)
        assertEquals(32, uplink.headerLenForTest(packet, packet.size))
        val decoded = uplink.decodeForTest(key, packet, packet.size, 0, leTail)
        assertNotNull(decoded)
        assertArrayEquals(plain, decoded)
    }

    @Test
    fun rejectsNonRtpPacket() {
        val notRtp = ByteArray(64) { 0x11 }
        assertNull(uplink.headerLenForTest(notRtp, notRtp.size))
        assertNull(uplink.decodeForTest(key, notRtp, notRtp.size, 0, leTail))
    }

    @Test
    fun swapsBigEndianSamplesToLittleEndian() {
        // 车机推的是大端 s16：0x1234 / 0x8001（负最大幅度附近）
        val wire = byteArrayOf(0x12, 0x34, 0x80.toByte(), 0x01)
        uplink.swapForTest(wire)
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0x01, 0x80.toByte()), wire)
    }
}
