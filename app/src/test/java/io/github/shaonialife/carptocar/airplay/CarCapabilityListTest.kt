package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/info` 声明类字段的三态判定（AIS Table 23-26 / Table 23-33）。
 *
 * 为什么锁这些：`extendedFeatures` 决定「可选键能不能发」——真实车机的两种极端都出现过：
 * 老固件根本不发这个键（此时按原样照发），新固件发了清单但只列了 `"vocoderInfo"`
 * （此时不该再塞 `requestUI` 的 `url`）。把「没说」写成 `false` 会把前者一起关掉，
 * 而这类错误在日志里看起来完全正常（只是少了几个可选键）。
 */
class CarCapabilityListTest {
    @Test
    fun `没有 extendedFeatures 键时返回 null 而不是空清单`() {
        assertNull(CarCapabilityList.extendedFeatures(mapOf<Any?, Any?>("name" to "car")))
        assertNull(CarCapabilityList.extendedFeatures(null))
    }

    @Test
    fun `有键但内容为空是空清单——与键缺失不是一回事`() {
        val info = mapOf<Any?, Any?>("extendedFeatures" to emptyList<Any?>())
        assertEquals(emptyList<String>(), CarCapabilityList.extendedFeatures(info))
    }

    @Test
    fun `清单里声明了 vocoderInfo 才返回 true`() {
        val info = mapOf<Any?, Any?>("extendedFeatures" to listOf("vocoderInfo"))

        assertEquals(true, CarCapabilityList.supportsExtended(info, CarCapabilityList.VOCODER_INFO))
        assertEquals(false, CarCapabilityList.supportsExtended(info, CarCapabilityList.ENHANCED_REQUEST_CAR_UI))
    }

    @Test
    fun `清单缺失时 supportsExtended 返回 null——调用方据此保持原行为`() {
        assertNull(CarCapabilityList.supportsExtended(mapOf<Any?, Any?>(), CarCapabilityList.VOCODER_INFO))
        assertNull(CarCapabilityList.supportsExtended(null, CarCapabilityList.VOCODER_INFO))
    }

    @Test
    fun `特性名大小写不敏感`() {
        val info = mapOf<Any?, Any?>("extendedFeatures" to listOf("VoCoderINFO"))
        assertEquals(true, CarCapabilityList.supportsExtended(info, CarCapabilityList.VOCODER_INFO))
    }

    @Test
    fun `keepAliveLowPower 只在明确 true 时生效`() {
        assertTrue(CarCapabilityList.supportsLowPowerKeepAlive(mapOf<Any?, Any?>("keepAliveLowPower" to true)))
        assertFalse(CarCapabilityList.supportsLowPowerKeepAlive(mapOf<Any?, Any?>("keepAliveLowPower" to false)))
        assertFalse(CarCapabilityList.supportsLowPowerKeepAlive(mapOf<Any?, Any?>()))
        assertFalse(CarCapabilityList.supportsLowPowerKeepAlive(null))
    }

    @Test
    fun `bluetoothIDs 去空白并丢掉空串`() {
        val info = mapOf<Any?, Any?>("bluetoothIDs" to listOf(" 00:11:22:33:44:55 ", "", "  "))
        assertEquals(listOf("00:11:22:33:44:55"), CarCapabilityList.bluetoothIds(info))
        assertEquals(emptyList<String>(), CarCapabilityList.bluetoothIds(null))
    }

    @Test
    fun `summary 把三种状态分开写——事后能分清没说与说了不支持`() {
        val silent = CarCapabilityList.summary(mapOf<Any?, Any?>())
        assertTrue(silent, silent.contains("extendedFeatures=未声明"))
        assertTrue(silent, silent.contains("keepAliveLowPower=未声明"))
        assertTrue(silent, silent.contains("bluetoothIDs=未声明"))

        val declared =
            CarCapabilityList.summary(
                mapOf<Any?, Any?>(
                    "extendedFeatures" to listOf("vocoderInfo", "enhancedRequestCarUI"),
                    "keepAliveLowPower" to true,
                    "bluetoothIDs" to listOf("00:11:22:33:44:55"),
                ),
            )
        assertTrue(declared, declared.contains("extendedFeatures=vocoderInfo,enhancedRequestCarUI"))
        assertTrue(declared, declared.contains("keepAliveLowPower=true"))
        assertTrue(declared, declared.contains("bluetoothIDs=00:11:22:33:44:55"))

        val emptyList = CarCapabilityList.summary(mapOf<Any?, Any?>("extendedFeatures" to emptyList<Any?>()))
        assertTrue(emptyList, emptyList.contains("extendedFeatures=空清单"))
    }
}
