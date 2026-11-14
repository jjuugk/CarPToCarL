package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/** 用 macOS plutil 生成的真实 bplist00 向量验证解析器。 */
class BinaryPlistTest {
    private val vector =
        Base64.getDecoder().decode(
            "YnBsaXN0MDDXAQIDBAUGBwgJFxgZGhtcYXVkaW9MYXRlbmN5WGRpc3BsYXlzWGRldmljZUlEVW1vZGVsV3Jhd1Np" +
                "emVYZmVhdHVyZXNUbmFtZRAAoQrWCwwNDg8QERITFBURWlVwc2lkZURvd25bUmVmcmVzaFJhdGVdV2lkdGhQaHlz" +
                "aWNhbFRVVUlEXkhlaWdodFBoeXNpY2FsWFJvdGF0aW9uCCNATgAAAAAAABEDIF8QJGI3ZTZjNWEwLTExMTEtNDAw" +
                "MC04MDAwLTAwMDAwMDAwMDAwMREB4AhfEBFBQTpCQjpDQzowMDowMDowMlpEaVBsYXkyMCwxRgABAgMEBRJWYcyi" +
                "VkRpUGxheQAIABcAJAAtADYAPABEAE0AUgBUAFYAYwBuAHoAiACNAJwApQCmAK8AsgDZANwA3QDxAPwBAwEIAAAA" +
                "AAAAAgEAAAAAAAAAHAAAAAAAAAAAAAAAAAAAAQ8=",
        )

    @Test
    fun parsesTopLevelDictScalars() {
        val root = BinaryPlist.parse(vector) as Map<*, *>
        assertEquals("DiPlay", root["name"])
        assertEquals("AA:BB:CC:00:00:02", root["deviceID"])
        assertEquals("DiPlay20,1", root["model"])
        assertEquals(1449249954L, root["features"])
        assertEquals(0L, root["audioLatency"])
    }

    @Test
    fun parsesNestedDisplayArray() {
        val root = BinaryPlist.parse(vector) as Map<*, *>
        val displays = root["displays"] as List<*>
        assertEquals(1, displays.size)
        val display = displays[0] as Map<*, *>
        assertEquals("b7e6c5a0-1111-4000-8000-000000000001", display["UUID"])
        assertEquals(800L, display["WidthPhysical"])
        assertEquals(480L, display["HeightPhysical"])
        assertEquals(60.0, display["RefreshRate"])
        assertFalse(display["Rotation"] as Boolean)
        assertFalse(display["UpsideDown"] as Boolean)
    }

    @Test
    fun parsesDataBlob() {
        val root = BinaryPlist.parse(vector) as Map<*, *>
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5), root["rawSize"] as ByteArray)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPlistInput() {
        BinaryPlist.parse("not a plist at all".toByteArray())
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val original =
            linkedMapOf<String, Any?>(
                "deviceID" to "02:00:00:00:00:00",
                "timingPort" to 0L,
                "features" to listOf("videoPlayback", "iAPChannel"),
                "flag" to true,
                "missing" to null,
                "blob" to byteArrayOf(1, 2, 3),
                "ratio" to 3.5,
            )
        val decoded = BinaryPlist.parse(BinaryPlist.encode(original)) as Map<*, *>
        assertEquals("02:00:00:00:00:00", decoded["deviceID"])
        assertEquals(0L, decoded["timingPort"])
        assertEquals(listOf("videoPlayback", "iAPChannel"), decoded["features"])
        assertEquals(true, decoded["flag"])
        assertNull(decoded["missing"])
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded["blob"] as ByteArray)
        assertEquals(3.5, decoded["ratio"])
    }

    /**
     * 整数宽度必须按**有符号**范围选。端口号常落在 32768..65535，按无符号阈值写成
     * 2 字节时 Apple 端按 int16 读成负数（35449 → -30087），接收端会拒绝或绑错端口。
     */
    @Test
    fun encodeDecodeRoundTripsPortRangeIntegers() {
        for (value in listOf(0L, 127L, 128L, 255L, 256L, 32767L, 32768L, 35449L, 65535L, 65536L)) {
            val decoded = BinaryPlist.parse(BinaryPlist.encode(value))
            assertEquals("value=$value", value, decoded)
        }
    }

    @Test
    fun negativeSpeechModeUsesSigned64BitWireInteger() {
        // Apple bplist00 规范：负整数必须按 8 字节有符号补码编码，单字节 0xFF 会被接收端读成 +255。
        val encoded = BinaryPlist.encode(-1L)
        assertArrayEquals(
            byteArrayOf(0x13, -1, -1, -1, -1, -1, -1, -1, -1),
            encoded.copyOfRange(8, 17),
        )
        assertEquals(-1L, BinaryPlist.parse(encoded))
    }

    @Test
    fun encodeLongAndUnicodeStrings() {
        val longAscii = "x".repeat(300)
        assertEquals(longAscii, BinaryPlist.parse(BinaryPlist.encode(longAscii)))
        val unicode = "奥迪车机 CarPlay 123"
        assertEquals(unicode, BinaryPlist.parse(BinaryPlist.encode(unicode)))
    }

    @Test
    fun parsesHandCraftedMinimalPlist() {
        // 最小 bplist：唯一对象 true @8，offset 表 @9（1 字节 = 0x08），尾部 trailer
        val minimal =
            byteArrayOf(
                0x62,
                0x70,
                0x6C,
                0x69,
                0x73,
                0x74,
                0x30,
                0x30, // bplist00
                0x09, // object #0 = true
                0x08, // offset 表：object#0 在偏移 8
                0,
                0,
                0,
                0,
                0,
                0,
                1,
                1, // offsetIntSize=1, objectRefSize=1
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                1, // numObjects=1
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0, // topObject=0
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                9, // offsetTableOffset=9
            )
        assertEquals(java.lang.Boolean.TRUE, BinaryPlist.parse(minimal))
    }
}
