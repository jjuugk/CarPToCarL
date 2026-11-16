package io.github.shaonialife.carptocar.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HID 描述符画像（日志用）。
 *
 * 描述符全部取自真车日志原文，逐字节可比对：
 *  - 宝马 MGU-81（2026-11-16）：62B 触摸 + 78B iDrive 旋钮/面板键，**没有媒体键设备**
 *    —— 这就是「方向盘切歌没反应」在协议层的全部答案；
 *  - 宝马 G38 NBT Evo（2026-11-16）：48B 旋钮、31B Consumer 位图媒体键、29B 电话键；
 *  - 奥迪 MIB3：46B Consumer 数组媒体键（字节是 Usage 数组下标）。
 */
class HidDescriptorSummaryTest {
    private fun hex(text: String): ByteArray =
        text
            .split(" ")
            .filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    /** MGU-81 hidDevices[0]（TouchInputDescriptor，62B）。 */
    private val mguTouch =
        hex(
            "05 0d 09 04 a1 01 05 0d 09 22 a1 02 05 0d 09 33 15 00 25 01 75 01 95 01 81 02 " +
                "75 07 95 01 81 01 05 01 09 30 15 00 26 66 0a 75 10 95 01 81 02 09 31 15 00 " +
                "26 2c 03 75 10 95 01 81 02 c0 c0",
        )

    /** MGU-81 hidDevices[1]（HardKeyInputDescriptor，78B）：按钮 + 返回键 + X/Y + Wheel。 */
    private val mguHardKey =
        hex(
            "05 01 09 08 a1 01 05 09 09 01 15 00 25 01 75 01 95 01 81 02 05 0c 75 01 95 01 " +
                "81 01 0a 24 02 15 00 25 01 75 01 95 01 81 02 75 05 95 01 81 01 05 01 09 30 " +
                "09 31 15 81 25 7f 75 08 95 02 81 02 05 01 09 38 15 81 25 7f 75 08 95 01 81 06 c0",
        )

    /** G38 hidDevices[2]（ButtonInput，31B）：0xB5/0xB6/0xB0 各占 1 bit（位图）。 */
    private val g38MediaKeys =
        hex(
            "05 0c 09 01 a1 01 05 0c 09 b5 09 b6 09 b0 15 00 25 01 75 01 95 03 81 02 " +
                "75 05 95 01 81 01 c0",
        )

    /** G38 hidDevices[1]（HardKey，48B）：旋钮，Consumer 页只有返回键。 */
    private val g38HardKey =
        hex(
            "05 01 09 08 a1 01 05 09 09 01 15 00 25 01 75 01 95 01 81 02 05 0c 0a 24 02 95 01 " +
                "81 02 95 06 81 01 05 01 09 38 15 81 25 7f 75 08 95 01 81 06 c0",
        )

    /** 奥迪 hidDevices[0]（eso_playback，46B）：Consumer 数组（字节=usages 下标）。 */
    private val audiConsumerArray =
        hex(
            "05 0c 09 01 a1 01 15 00 25 08 05 0c 0a 00 00 00 0a b0 00 0a b1 00 0a b3 00 " +
                "0a b4 00 0a b5 00 0a b6 00 0a b9 00 0a bc 00 75 08 95 01 81 00 c0",
        )

    @Test
    fun mguTouchIsTouch() {
        val verdict = HidDescriptorSummary.describe(mguTouch)
        assertEquals(HidDescriptorSummary.Kind.TOUCH, verdict.kind)
        assertFalse("触摸设备不带媒体键", verdict.hasMediaKeys)
    }

    @Test
    fun mguHardKeyIsKnobWithoutMediaKeys() {
        val verdict = HidDescriptorSummary.describe(mguHardKey)
        // 关键结论：Generic Desktop 带 Wheel → 旋钮；Consumer 页只有 0x0224（返回键），
        // 没有任何能映射成 Android 媒体键的 Usage。
        assertEquals(HidDescriptorSummary.Kind.KNOB, verdict.kind)
        assertFalse(verdict.hasMediaKeys)
        assertEquals(listOf(0x0224), verdict.consumerUsages)
        assertTrue("日志要说清它只是返回键", verdict.text().contains("返回键"))
    }

    @Test
    fun mguHasNoMediaKeyDeviceAtAll() {
        val verdicts = listOf(mguTouch, mguHardKey).map(HidDescriptorSummary::describe)
        assertFalse(
            "MGU-81 只声明了触摸与旋钮——方向盘媒体键无承载通路",
            HidDescriptorSummary.hasMediaKeyDevice(verdicts),
        )
    }

    @Test
    fun g38MediaKeysAreBitmapWithThreeUsages() {
        val verdict = HidDescriptorSummary.describe(g38MediaKeys)
        assertEquals(HidDescriptorSummary.Kind.MEDIA_KEYS, verdict.kind)
        assertEquals(listOf(0xB5, 0xB6, 0xB0), verdict.mediaUsages)
        assertTrue("位图语义（81 02）", verdict.bitmap)
        assertTrue(HidDescriptorSummary.hasMediaKeyDevice(listOf(verdict)))
        // 一行画像要能直接读出「哪个 usage 是什么键」
        val text = verdict.text()
        assertTrue(text, text.contains("0xb5 下一首"))
        assertTrue(text, text.contains("位图"))
    }

    @Test
    fun audiMediaKeysAreArrayIndexed() {
        val verdict = HidDescriptorSummary.describe(audiConsumerArray)
        assertEquals(HidDescriptorSummary.Kind.MEDIA_KEYS, verdict.kind)
        assertFalse("数组语义（81 00）", verdict.bitmap)
        // 0xBC（循环）没有对应的 Android 键，不算媒体键；其余 7 个要留下
        assertTrue(verdict.mediaUsages.contains(0xB5))
        assertFalse("0xBC 映射不到 Android 键", verdict.mediaUsages.contains(0xBC))
        assertTrue(verdict.text().contains("数组下标"))
    }

    @Test
    fun g38HardKeyStaysKnob() {
        val verdict = HidDescriptorSummary.describe(g38HardKey)
        assertEquals(HidDescriptorSummary.Kind.KNOB, verdict.kind)
        assertFalse(verdict.hasMediaKeys)
    }

    @Test
    fun emptyDescriptorIsNoneNotMediaKey() {
        val verdict = HidDescriptorSummary.describe(ByteArray(0))
        assertEquals(HidDescriptorSummary.Kind.NONE, verdict.kind)
        assertFalse(verdict.hasMediaKeys)
        assertTrue(verdict.text().contains("没有数据项"))
    }

    @Test
    fun truncatedDescriptorDoesNotThrow() {
        // 车机若把描述符截断（或者我们读短了），画像必须能退化而不是抛异常打死读线程
        val verdict = HidDescriptorSummary.describe(mguHardKey.copyOf(21))
        assertEquals(HidDescriptorSummary.Kind.NONE, verdict.kind)
    }
}
