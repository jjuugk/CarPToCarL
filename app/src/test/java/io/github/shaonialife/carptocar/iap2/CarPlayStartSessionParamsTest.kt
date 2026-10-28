package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `0x4301` / `0x5703` 里连接相关参数的解析边界。
 *
 * 这三项此前整个没读，所以测试重点不在「正常值能读出来」（那太容易），
 * 而在**畸形/缺失输入不能把连接带偏**：BSSID 长度不对时必须返回 null 而不是拼出假地址
 * （假地址会被 P2P 连接器当 GO 去连，表现成「连了很久最后失败」）。
 */
class CarPlayStartSessionParamsTest {
    private fun mac(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun bssidFormatsSixBytes() {
        assertEquals(
            "aa:bb:cc:dd:ee:ff",
            CarPlayStartSessionParams.bssid(mac(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF)),
        )
    }

    @Test
    fun bssidAcceptsSevenBytesWithLengthPrefix() {
        // 部分实现把 BYTES 当成「长度 + 内容」发，首字节 6
        assertEquals(
            "aa:bb:cc:dd:ee:ff",
            CarPlayStartSessionParams.bssid(mac(6, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF)),
        )
    }

    @Test
    fun bssidRejectsUnexpectedLength() {
        assertNull(CarPlayStartSessionParams.bssid(mac(1, 2, 3)))
        assertNull(CarPlayStartSessionParams.bssid(ByteArray(0)))
        assertNull(CarPlayStartSessionParams.bssid(null))
    }

    @Test
    fun bssidRejectsAllZeroPlaceholder() {
        // 全零是「没给」的常见占位；当成有效地址会让 P2P 连 00:00:00:00:00:00
        assertNull(CarPlayStartSessionParams.bssid(mac(0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun sdkVersionTrimsAndKeepsPrintable() {
        assertEquals("320.14", CarPlayStartSessionParams.sdkVersion("320.14".toByteArray()))
        assertEquals("320.14", CarPlayStartSessionParams.sdkVersion("320.14\u0000".toByteArray()))
    }

    @Test
    fun sdkVersionNullWhenMissingOrEmpty() {
        assertNull(CarPlayStartSessionParams.sdkVersion(null))
        assertNull(CarPlayStartSessionParams.sdkVersion(ByteArray(0)))
        assertNull(CarPlayStartSessionParams.sdkVersion(byteArrayOf(0, 0)))
    }

    @Test
    fun mutualAuthNonZeroIsTrue() {
        assertTrue(CarPlayStartSessionParams.mutualAuth(byteArrayOf(1)))
        // 任何非零都按「要求」处理：把 2 当 false 会静默跳过认证
        assertTrue(CarPlayStartSessionParams.mutualAuth(byteArrayOf(2)))
    }

    @Test
    fun mutualAuthZeroOrMissingIsFalse() {
        assertFalse(CarPlayStartSessionParams.mutualAuth(byteArrayOf(0)))
        assertFalse(CarPlayStartSessionParams.mutualAuth(null))
        assertFalse(CarPlayStartSessionParams.mutualAuth(ByteArray(0)))
    }
}
