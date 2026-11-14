package io.github.shaonialife.carptocar.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旋钮 HID 报文的解析（宝马 iDrive 旋钮：byte0 = 按钮位图、byte1 = 相对滚轮）。
 *
 * 这些描述符是 2026-11-14 宝马 F49 NBT Evo 实车 `/info` 里抓到的原文，不是构造样本：
 * 判错位偏移的后果是「旋钮转动被当成乱按的按钮」，用户看到的就是「旋钮控制不了屏幕」。
 */
class KnobReportsTest {
    /** `/info` hidDevices 里 name=KnobInput 的描述符（48B）。 */
    private val bmwKnob =
        bytes(
            "05 01 09 08 a1 01 05 09 09 01 15 00 25 01 75 01 95 01 81 02 " +
                "05 0c 0a 24 02 95 01 81 02 95 06 81 01 " +
                "05 01 09 38 15 81 25 7f 75 08 95 01 81 06 c0",
        )

    /** name=TouchScreen 的描述符（62B）—— Digitizer 页，绝不能当旋钮。 */
    private val bmwTouch =
        bytes(
            "05 0d 09 04 a1 01 05 0d 09 22 a1 02 05 0d 09 33 15 00 25 01 75 01 95 01 81 02 " +
                "75 07 95 01 81 01 05 01 09 30 15 00 26 a0 05 75 10 95 01 81 02 " +
                "09 31 15 00 26 1c 02 75 10 95 01 81 02 c0 c0",
        )

    /** name=ButtonInput 的描述符（31B）—— Consumer 媒体键，走 ConsumerKeys。 */
    private val bmwButtons =
        bytes("05 0c 09 01 a1 01 05 0c 09 b5 09 b6 09 b0 15 00 25 01 75 01 95 03 81 02 75 05 95 01 81 01 c0")

    @Test
    fun `宝马旋钮描述符-滚轮在第 2 字节且按钮在第 1 位`() {
        val layout = KnobReports.parseLayout(bmwKnob)
        assertNotNull(layout)
        layout!!
        assertEquals(8, layout.wheelBit)
        assertEquals(8, layout.wheelWidth)
        assertTrue("Logical Minimum 0x81 → 有符号", layout.wheelSigned)
        assertEquals(0, layout.buttonBit)
        assertEquals(1, layout.backBit)
        assertEquals(2, layout.minReportBytes)
    }

    @Test
    fun `顺时针一格 00 01`() {
        val event = KnobReports.parse(bytes("00 01"), KnobReports.parseLayout(bmwKnob)!!)
        assertEquals(1, event.steps)
        assertEquals(false, event.pressed)
        assertEquals(false, event.backPressed)
    }

    @Test
    fun `逆时针一格 00 ff`() {
        val event = KnobReports.parse(bytes("00 ff"), KnobReports.parseLayout(bmwKnob)!!)
        assertEquals(-1, event.steps)
    }

    @Test
    fun `旋钮按下 01 00`() {
        val event = KnobReports.parse(bytes("01 00"), KnobReports.parseLayout(bmwKnob)!!)
        assertEquals(0, event.steps)
        assertEquals(true, event.pressed)
    }

    @Test
    fun `返回键是按钮位 1`() {
        val event = KnobReports.parse(bytes("02 00"), KnobReports.parseLayout(bmwKnob)!!)
        assertEquals(true, event.backPressed)
        assertEquals(false, event.pressed)
    }

    @Test
    fun `触摸设备的描述符不会被当成旋钮`() {
        assertNull(KnobReports.parseLayout(bmwTouch))
    }

    @Test
    fun `媒体键设备的描述符不会被当成旋钮`() {
        assertNull(KnobReports.parseLayout(bmwButtons))
    }

    @Test
    fun `带 Report ID 的描述符直接放弃-不猜位偏移`() {
        val withReportId =
            bytes(
                "05 01 09 08 a1 01 85 02 05 01 09 38 15 81 25 7f 75 08 95 01 81 06 c0",
            )
        assertNull(KnobReports.parseLayout(withReportId))
    }

    @Test
    fun `报告长度不足时返回全零而不是越界`() {
        val event = KnobReports.parse(bytes("00"), KnobReports.parseLayout(bmwKnob)!!)
        assertEquals(0, event.steps)
        assertNull(event.pressed)
        assertNull(event.backPressed)
    }

    private fun bytes(hex: String): ByteArray =
        hex
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
