package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0xFFFB 定位解析的回归用例。
 *
 * 语料全部来自真机抓包：
 * - `$PASCD,1583.410,C,D,0,1,0.00,0.000*4B` —— 奥迪 4580 实发（43B，**无经纬度**）
 * - `$GPGGA,125944.64,3014.842036,N,11957.815098,E,6,00,100.0,15.0,M,,M,,*46`
 *   —— 真 iPhone 抓包里的车机 NMEA（有经纬度）
 */
class NmeaLocationTest {
    /** 给句子补上正确的校验和（手写用例时省得算）。 */
    private fun withChecksum(body: String): String {
        var x = 0
        for (c in body) x = x xor c.code
        return "\$" + body + "*%02X".format(x)
    }

    private fun csmBody(text: String): ByteArray {
        val ascii = text.toByteArray(Charsets.US_ASCII)
        // 真机报文：[len u16][id=0 u16][ASCII][NUL 补齐]
        val out = ByteArray(4 + ascii.size + 1)
        out[0] = 0
        out[1] = (out.size and 0xFF).toByte()
        out[2] = 0
        out[3] = 0
        System.arraycopy(ascii, 0, out, 4, ascii.size)
        return out
    }

    @Test
    fun ggaGivesPositionAltitudeAndQuality() {
        val s = withChecksum("GPGGA,125944.64,3014.842036,N,11957.815098,E,1,08,0.9,15.0,M,,M,,")
        val fix = NmeaLocation.parseSentence(s)
        assertNotNull(fix)
        fix!!
        assertEquals(30.247367, fix.latitude!!, 1e-6)
        assertEquals(119.963585, fix.longitude!!, 1e-6)
        assertEquals(15.0, fix.altitudeMeters!!, 1e-6)
        assertEquals(1, fix.fixQuality)
        assertEquals(8, fix.satellitesUsed)
        assertEquals(0.9, fix.hdop!!, 1e-6)
        assertEquals(12, fix.utcHour)
        assertEquals(59, fix.utcMinute)
        assertEquals(44, fix.utcSecond)
        assertTrue(fix.hasPosition)
    }

    @Test
    fun southernAndWesternHemisphereAreNegative() {
        val s = withChecksum("GPGGA,000000.00,3352.000000,S,15112.000000,W,1,05,1.0,10.0,M,,M,,")
        val fix = NmeaLocation.parseSentence(s)!!
        assertEquals(-33.866667, fix.latitude!!, 1e-6)
        assertEquals(-151.2, fix.longitude!!, 1e-6)
    }

    @Test
    fun ggaWithoutFixHasNoPosition() {
        val s = withChecksum("GPGGA,,,,,,0,00,,,,,M,,M,,")
        val fix = NmeaLocation.parseSentence(s)
        assertNotNull(fix)
        assertFalse(fix!!.hasPosition)
        assertEquals(0, fix.fixQuality)
    }

    @Test
    fun rmcConvertsKnotsToKmhAndCarriesDate() {
        val s = withChecksum("GPRMC,125944.00,A,3014.842036,N,11957.815098,E,10.0,90.0,041126,,,A")
        val fix = NmeaLocation.parseSentence(s)!!
        assertEquals(18.52, fix.speedKmh!!, 1e-6)
        assertEquals(90.0, fix.headingDeg!!, 1e-6)
        assertEquals(2026, fix.utcYear)
        assertEquals(11, fix.utcMonth)
        assertEquals(4, fix.utcDay)
        assertTrue(fix.hasPosition)
    }

    @Test
    fun rmcVoidStatusHasNoFix() {
        val s = withChecksum("GPRMC,125944.00,V,,,,,,,041126,,,N")
        val fix = NmeaLocation.parseSentence(s)!!
        assertEquals(0, fix.fixQuality)
        assertFalse(fix.hasPosition)
    }

    @Test
    fun gsaGivesDopAndFixType() {
        val s = withChecksum("GPGSA,A,3,01,02,03,04,05,,,,,,,,,1.8,0.9,1.5")
        val fix = NmeaLocation.parseSentence(s)!!
        assertEquals(1, fix.fixQuality) // fixType=3 → 有定位
        assertEquals(5, fix.satellitesUsed)
        assertEquals(1.8, fix.pdop!!, 1e-6)
        assertEquals(0.9, fix.hdop!!, 1e-6)
        assertEquals(1.5, fix.vdop!!, 1e-6)
    }

    @Test
    fun pascdIsRecognisedButCarriesNoCoordinates() {
        val s = withChecksum("PASCD,1583.410,C,D,0,1,0.00,0.000")
        val fix = NmeaLocation.parseSentence(s)
        assertNotNull(fix)
        assertEquals("PASCD", fix!!.source)
        assertNull(fix.latitude)
        assertNull(fix.longitude)
        assertFalse(fix.hasPosition)
    }

    @Test
    fun badChecksumIsRejectedButMissingChecksumAccepted() {
        val body = "GPGGA,125944.64,3014.842036,N,11957.815098,E,1,08,0.9,15.0,M,,M,,"
        assertNull(NmeaLocation.parseSentence("\$" + body + "*00"))
        // 缺校验和：部分车机不发，按「只在校验和存在且不对时丢弃」的约定应放行
        assertNotNull(NmeaLocation.parseSentence("\$" + body))
    }

    @Test
    fun splitSentencesHandlesNulPaddingAndMultipleSentences() {
        val body = csmBody("\$PASCD,1583.410,C,D,0,1,0.00,0.000*4B\u0000")
        val one = NmeaLocation.splitSentences(body)
        assertEquals(1, one.size)
        assertEquals("PASCD", one[0].substring(1).substringBefore(','))

        val two = NmeaLocation.splitSentences(csmBody("\$PASCD,1,C*00\u0000\$PASCD,2,C*00\u0000"))
        assertEquals(2, two.size)
    }

    @Test
    fun assemblerMergesGgaRmcAndGsa() {
        val asm = NmeaAssembler()
        asm.feed(withChecksum("GPGGA,125944.64,3014.842036,N,11957.815098,E,1,08,0.9,15.0,M,,M,,"))
        asm.feed(withChecksum("GPRMC,125944.00,A,3014.842036,N,11957.815098,E,10.0,90.0,041126,,,A"))
        val merged = asm.feed(withChecksum("GPGSA,A,3,01,02,03,04,05,,,,,,,,,1.8,0.9,1.5"))!!
        assertEquals(30.247367, merged.latitude!!, 1e-6)
        assertEquals(18.52, merged.speedKmh!!, 1e-6)
        assertEquals(90.0, merged.headingDeg!!, 1e-6)
        assertEquals(1, merged.fixQuality)
        assertEquals(5, merged.satellitesUsed)
        assertEquals(1.5, merged.vdop!!, 1e-6)
        assertEquals(2026, merged.utcYear)
        assertTrue(merged.hasPosition)
    }

    @Test
    fun assemblerDropsPositionWhenGgaReportsNoFix() {
        val asm = NmeaAssembler()
        asm.feed(withChecksum("GPGGA,125944.64,3014.842036,N,11957.815098,E,1,08,0.9,15.0,M,,M,,"))
        assertTrue(asm.snapshot()!!.hasPosition)
        // 下一秒车机报「无定位」：不能再拿上一秒的坐标冒充当前定位
        asm.feed(withChecksum("GPGGA,,,,,,0,00,,,,,M,,M,,"))
        assertFalse(asm.snapshot()!!.hasPosition)
    }

    @Test
    fun assemblerResetClearsStalePosition() {
        val asm = NmeaAssembler()
        asm.feed(withChecksum("GPGGA,125944.64,3014.842036,N,11957.815098,E,1,08,0.9,15.0,M,,M,,"))
        asm.reset()
        assertNull(asm.snapshot())
    }
}
