package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 证书识别（逆向协议核 @0xf8a58 + 查表 @0x22520）的纯函数验证。
 *
 * 合成最小 DER：
 *  - 32B ← Apple OID 1.2.840.113635.100.6.36  （9 字节）
 *  - 16B ← Apple OID 1.2.840.113635.100.6.71.1（10 字节）
 *  -  -1 ← Apple OID 1.2.840.113635.100.6.59.1（10 字节，协议核返回无效）
 *  - 20B ← 15 字节 INTEGER，[2]/[6]/[8]/[11] 为 0xAA
 */
class MfiCertificateLengthTest {
    private fun tlv(
        tag: Int,
        content: ByteArray,
    ): ByteArray = byteArrayOf(tag.toByte(), content.size.toByte()) + content

    private fun appleOid(suffix: ByteArray): ByteArray =
        tlv(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x63, 0x64, 0x06) + suffix)

    private fun markerInteger(): ByteArray {
        val value = ByteArray(15)
        for (i in intArrayOf(2, 6, 8, 11)) value[i] = 0xAA.toByte()
        return tlv(0x02, value)
    }

    @Test
    fun recognize32FromAppleOid9() {
        assertEquals(32, MfiCertificateLength.recognize(appleOid(byteArrayOf(0x24))))
    }

    @Test
    fun recognize16FromAppleOid10() {
        assertEquals(16, MfiCertificateLength.recognize(appleOid(byteArrayOf(0x47, 0x01))))
    }

    @Test
    fun invalidAppleOid10IsNotRecognized() {
        assertNull(MfiCertificateLength.recognize(appleOid(byteArrayOf(0x3B, 0x01))))
    }

    @Test
    fun recognize20FromExtensionMarker() {
        val cert = byteArrayOf(0x30, 0x1B) + markerInteger()
        assertEquals(20, MfiCertificateLength.recognize(cert))
    }

    @Test
    fun oidTakesPrecedenceOverExtensionMarker() {
        val cert = appleOid(byteArrayOf(0x24)) + markerInteger()
        assertEquals(32, MfiCertificateLength.recognize(cert))
    }

    @Test
    fun garbageAndEmptyReturnNull() {
        assertNull(MfiCertificateLength.recognize(byteArrayOf()))
        assertNull(MfiCertificateLength.recognize(byteArrayOf(0x30, 0x00)))
        assertNull(MfiCertificateLength.recognize(byteArrayOf(0x06, 0x03, 0x2A, 0x03, 0x04)))
    }

    @Test
    fun preferPutsRecognizedFirstButKeepsAllFallbacks() {
        val baseline = listOf(20, 32, 16)
        assertEquals(listOf(32, 20, 16), MfiCertificateLength.prefer(baseline, 32))
        assertEquals(listOf(16, 20, 32), MfiCertificateLength.prefer(baseline, 16))
        assertEquals(baseline, MfiCertificateLength.prefer(baseline, null))
    }

    @Test
    fun preferIgnoresLengthOutsideBaseline() {
        val baseline = listOf(20, 32)
        assertEquals(baseline, MfiCertificateLength.prefer(baseline, 16))
    }
}
