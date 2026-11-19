package io.github.shaonialife.carptocar.input

import io.github.shaonialife.carptocar.carlife.CarLifeProtocol.HardKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旋钮焦点模式：报告 → CarLife 焦点键，以及按下沿检测。
 *
 * 键值钉死为百度开源车机端 `CommonParams` 的取值，防止被误改。
 */
class KnobFocusTest {
    @Test
    fun keyValuesMatchBaiduVehicleCommonParams() {
        assertEquals(0x06, HardKey.SELECTOR_NEXT)
        assertEquals(0x07, HardKey.SELECTOR_PREVIOUS)
        assertEquals(0x0E, HardKey.BACK)
        assertEquals(0x14, HardKey.OK)
    }

    @Test
    fun clockwiseIsNext() {
        assertEquals(listOf(HardKey.SELECTOR_NEXT), KnobFocus.hardKeysFor(1, pressEdge = false, backEdge = false))
    }

    @Test
    fun counterClockwiseIsPrevious() {
        assertEquals(
            listOf(HardKey.SELECTOR_PREVIOUS, HardKey.SELECTOR_PREVIOUS),
            KnobFocus.hardKeysFor(-2, pressEdge = false, backEdge = false),
        )
    }

    @Test
    fun fastTurnIsCapped() {
        val keys = KnobFocus.hardKeysFor(100, pressEdge = false, backEdge = false)
        assertEquals(KnobFocus.MAX_STEPS_PER_REPORT, keys.size)
    }

    @Test
    fun pressAndBackMapToOkAndBack() {
        assertEquals(listOf(HardKey.OK), KnobFocus.hardKeysFor(0, pressEdge = true, backEdge = false))
        assertEquals(listOf(HardKey.BACK), KnobFocus.hardKeysFor(0, pressEdge = false, backEdge = true))
    }

    @Test
    fun idleReportSendsNothing() {
        assertTrue(KnobFocus.hardKeysFor(0, pressEdge = false, backEdge = false).isEmpty())
    }

    @Test
    fun heldButtonFiresOnce() {
        // 按住旋钮期间车机继续发报告（例如同时转动），每份按钮位都是 1：只算一次按下。
        val edges = ButtonEdges()
        assertEquals(true to false, edges.update("0", pressedNow = true, backNow = false))
        assertEquals(false to false, edges.update("0", pressedNow = true, backNow = false))
        assertEquals(false to false, edges.update("0", pressedNow = false, backNow = false))
        assertEquals(true to false, edges.update("0", pressedNow = true, backNow = false))
    }

    @Test
    fun backEdgeIsIndependentOfPress() {
        val edges = ButtonEdges()
        assertEquals(false to true, edges.update("0", pressedNow = false, backNow = true))
        assertEquals(false to false, edges.update("0", pressedNow = false, backNow = true))
    }

    @Test
    fun devicesAreTrackedSeparately() {
        val edges = ButtonEdges()
        assertTrue(edges.update("0", pressedNow = true, backNow = null).first)
        assertTrue(edges.update("1", pressedNow = true, backNow = null).first)
    }

    @Test
    fun missingFieldKeepsState() {
        // 描述符没声明该位（null）时不能把它当成「松开」，否则下一份会误判一次按下沿。
        val edges = ButtonEdges()
        edges.update("0", pressedNow = true, backNow = null)
        edges.update("0", pressedNow = null, backNow = null)
        assertFalse(edges.update("0", pressedNow = true, backNow = null).first)
    }

    @Test
    fun clearResetsState() {
        val edges = ButtonEdges()
        edges.update("0", pressedNow = true, backNow = null)
        edges.clear()
        assertTrue(edges.update("0", pressedNow = true, backNow = null).first)
    }

    @Test
    fun g38KnobReportDecodesBack() {
        // G38 NBT Evo HardKey 描述符（48B，见 HidDescriptorSummaryTest）：byte0 bit0=按下、bit1=返回，byte1=滚轮。
        val g38HardKey =
            (
                "05 01 09 08 a1 01 05 09 09 01 15 00 25 01 75 01 95 01 81 02 05 0c 0a 24 02 95 01 " +
                    "81 02 95 06 81 01 05 01 09 38 15 81 25 7f 75 08 95 01 81 06 c0"
            ).split(' ').map { it.toInt(16).toByte() }.toByteArray()
        val layout = KnobReports.parseLayout(g38HardKey)!!
        val back = KnobReports.parse(byteArrayOf(0x02, 0x00), layout)
        assertEquals(true, back.backPressed)
        assertEquals(false, back.pressed)
        val turn = KnobReports.parse(byteArrayOf(0x00, 0xFF.toByte()), layout)
        assertEquals(-1, turn.steps)
    }
}
