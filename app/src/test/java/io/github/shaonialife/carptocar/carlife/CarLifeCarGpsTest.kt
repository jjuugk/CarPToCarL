package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0x18010 `CarlifeCarGps` 与 0x68008 硬键的编码回归。
 *
 * 这两条消息的字节布局来自官方车机端 SDK（不是猜的）：
 * - `CarlifeCarGpsProto.proto`：25 个字段，前 24 个 required
 * - `CommonParams.MSG_TOUCH_CAR_HARD_KEY_CODE = 0x00068008` +
 *   `CarlifeCarHardKeyCode{keycode=1}`，`KEYCODE_VR_START = 0x21`
 */
class CarLifeCarGpsTest {
    /** 极简 protobuf 解码：只支持 varint 字段（本消息全部是 varint）。 */
    private fun decode(payload: ByteArray): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        var i = 0
        while (i < payload.size) {
            var tag = 0L
            var shift = 0
            while (i < payload.size) {
                val b = payload[i].toInt() and 0xFF
                i++
                tag = tag or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x07).toInt()
            require(wire == 0) { "本消息只有 varint 字段，遇到 wireType=$wire（field=$field）" }
            var value = 0L
            var s = 0
            while (i < payload.size) {
                val b = payload[i].toInt() and 0xFF
                i++
                value = value or ((b and 0x7F).toLong() shl s)
                s += 7
                if (b and 0x80 == 0) break
            }
            out[field] = value
        }
        return out
    }

    private val fix =
        CarGpsFix(
            latitude = 30.247367,
            longitude = -119.963585,
            altitudeMeters = 15.0,
            speedKmh = 36.0,
            headingDeg = 90.0,
            fixQuality = 1,
            satellitesUsed = 8,
            satellitesVisible = 11,
            hdop = 0.9,
            pdop = 1.8,
            vdop = 1.5,
            utcYear = 2026,
            utcMonth = 11,
            utcDay = 4,
            utcHour = 12,
            utcMinute = 59,
            utcSecond = 44,
        )

    @Test
    fun allRequiredFieldsArePresent() {
        val fields = decode(CarLifeProtocol.carGps(fix, 1_762_309_184_000L))
        // 前 24 个 required + 第 25 个时间戳，一个都不能少（缺字段手机端直接丢整条）
        for (id in 1..25) {
            assertTrue("字段 $id 缺失", fields.containsKey(id))
        }
    }

    @Test
    fun unitsFollowSdkComments() {
        val f = decode(CarLifeProtocol.carGps(fix, 1_762_309_184_000L))
        assertEquals(30_247_367L, f[3]!!) // 纬度 1/1,000,000 度
        assertEquals(-119_963_585L, f[4]!!.toInt().toLong()) // 西经为负，int32 符号扩展
        assertEquals(150L, f[5]!!) // 高度 0.1 米
        assertEquals(3_600L, f[6]!!) // 速度 0.01 km/h
        assertEquals(900L, f[7]!!) // 航向 0.1 度
        assertEquals(9L, f[15]!!) // hdop ×10
        assertEquals(18L, f[16]!!)
        assertEquals(15L, f[17]!!)
        assertEquals(8L, f[18]!!)
        assertEquals(11L, f[19]!!)
    }

    @Test
    fun utcTimeAndTimestampAreCarried() {
        val f = decode(CarLifeProtocol.carGps(fix, 1_762_309_184_000L))
        assertEquals(2026L, f[8]!!)
        assertEquals(11L, f[9]!!)
        assertEquals(4L, f[10]!!)
        assertEquals(12L, f[11]!!)
        assertEquals(59L, f[12]!!)
        assertEquals(44L, f[13]!!)
        assertEquals(1L, f[14]!!) // fix quality
        assertEquals(1_762_309_184_000L, f[25]!!)
    }

    @Test
    fun groundSpeedIsDecomposedIntoNorthEast() {
        // 36 km/h = 10 m/s，正东（航向 90°）→ 北向 0、东向 1000（0.01 m/s）
        val f = decode(CarLifeProtocol.carGps(fix, 0L))
        assertEquals(0L, f[22]!!)
        assertEquals(1_000L, f[23]!!)
        assertEquals(0L, f[24]!!)
    }

    @Test
    fun voiceHardKeyIsKeycode33() {
        val payload = CarLifeProtocol.carHardKeyCode(CarLifeProtocol.HardKey.VR_START)
        assertEquals(2, payload.size)
        assertEquals(0x08, payload[0].toInt())
        assertEquals(0x21, payload[1].toInt())
        assertEquals(33, CarLifeProtocol.HardKey.VR_START)
        assertEquals(34, CarLifeProtocol.HardKey.VR_STOP)
    }

    @Test
    fun messageIdsMatchOfficialSdk() {
        assertEquals(0x00018010, CarLifeProtocol.HU_CAR_GPS)
        assertEquals(0x00068008, CarLifeProtocol.TOUCH_CAR_HARD_KEY_CODE)
    }
}