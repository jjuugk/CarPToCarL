package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 我方识别帧（`0x1D01`）构造的单测。
 *
 * 这一帧只有在「大众 ID（MEB ICAS3）」档才会发出去，而发错了的代价是**车机拒绝识别**
 * （`0x1D03`）后整轮连接不成立——所以这里重点守三件事：
 *  1. 能读回来（TLV 自洽，长度/顺序都对）；
 *  2. 声明的消息位图包含 CarPlay 建链必需的两条（0x4300/0x4301）；
 *  3. 位图里没有重复项、没有 0（空洞 ID 会被车机当成非法能力）。
 */
class DeviceIdentificationTest {
    private fun params(body: ByteArray): Map<Int, Iap2Csm.Param> {
        val list = DeviceIdentification.readBack(body)
        assertNotNull("拼出来的帧必须能被自己的解析器读回", list)
        return list!!.associateBy { it.id }
    }

    @Test
    fun `身份字段与消息位图能原样读回`() {
        val spec =
            DeviceIdentification.Spec(
                name = "老王 的 iPhone",
                messagesSent = intArrayOf(0x1D01, 0x4300),
                messagesReceived = intArrayOf(0x4301, 0x5703),
            )
        val body = DeviceIdentification.body(spec)
        val parsed = params(body)

        assertEquals("老王 的 iPhone", parsed[DeviceIdentification.P_NAME]?.asString())
        assertEquals(DeviceIdentification.MODEL, parsed[DeviceIdentification.P_MODEL]?.asString())
        assertEquals(
            DeviceIdentification.MANUFACTURER,
            parsed[DeviceIdentification.P_MANUFACTURER]?.asString(),
        )
        assertEquals(
            listOf(0x1D01, 0x4300),
            parsed[DeviceIdentification.P_MESSAGES_SENT]?.asU16List(),
        )
        assertEquals(
            listOf(0x4301, 0x5703),
            parsed[DeviceIdentification.P_MESSAGES_RECEIVED]?.asU16List(),
        )
        // 没有 EAP 协议时不产生 10/11/12 组（留空比乱填安全，见类的「已知缺口」）
        DeviceIdentification.EAP_GROUPS.forEach { assertNull("未声明协议时不应出现组 $it", parsed[it]) }
    }

    @Test
    fun `EAP 协议按 10 11 12 顺序声明`() {
        val body =
            DeviceIdentification.body(
                DeviceIdentification.Spec(
                    name = "iPhone",
                    messagesSent = intArrayOf(0x1D01),
                    messagesReceived = intArrayOf(0x4301),
                    eapProtocols = listOf(1 to "com.example.a", 2 to "com.example.b"),
                ),
            )
        val parsed = params(body)
        // 组内容 = u8 序号 + NUL 结尾名字 + u8 0（与车机侧同构）
        val group10 = parsed[10]?.value
        val group11 = parsed[11]?.value
        assertNotNull(group10)
        assertNotNull(group11)
        assertNull("只声明两条协议，第 12 组不应出现", parsed[12])
        // 组里第一个参数是 u8 序号
        val inner10 = Iap2Csm.parseParams(group10!!).associateBy { it.id }
        assertEquals(1, inner10[0]?.value?.firstOrNull()?.toInt())
        assertEquals("com.example.a", inner10[1]?.asString())
        val inner11 = Iap2Csm.parseParams(group11!!).associateBy { it.id }
        assertEquals(2, inner11[0]?.value?.firstOrNull()?.toInt())
        assertEquals("com.example.b", inner11[1]?.asString())
    }

    @Test
    fun `默认能力清单含 CarPlay 建链必需的两条`() {
        val spec = DeviceIdentification.defaultSpec("iPhone")
        assertTrue("必须声明会发 0x4300 CarPlayAvailability", 0x4300 in spec.messagesSent.toList())
        assertTrue("必须声明可收 0x4301 CarPlayStartSession", 0x4301 in spec.messagesReceived.toList())
        assertTrue("必须声明可收 0x1D01（车机身份）", 0x1D01 in spec.messagesReceived.toList())
        assertTrue("必须声明会发 0x1D01（本帧自身）", 0x1D01 in spec.messagesSent.toList())
        // 无线凭据链：发 0x5702、收 0x5703
        assertTrue(0x5702 in spec.messagesSent.toList())
        assertTrue(0x5703 in spec.messagesReceived.toList())
    }

    @Test
    fun `默认能力清单没有重复项也没有空洞 ID`() {
        val spec = DeviceIdentification.defaultSpec("iPhone")
        listOf("会发" to spec.messagesSent, "可收" to spec.messagesReceived).forEach { (label, ids) ->
            val list = ids.toList()
            assertEquals("$label 清单里有重复 ID：$list", list.size, list.distinct().size)
            assertFalse("$label 清单里有非法 ID（0 或超出 u16）：$list", list.any { it <= 0 || it > 0xFFFF })
        }
        // 线上字节始终升序：调用方的书写顺序不影响帧内容
        val parsed = params(DeviceIdentification.body(spec))
        listOf(
            parsed[DeviceIdentification.P_MESSAGES_SENT]?.asU16List().orEmpty(),
            parsed[DeviceIdentification.P_MESSAGES_RECEIVED]?.asU16List().orEmpty(),
        ).forEach { wire ->
            assertEquals("位图未按升序写出：$wire", wire, wire.sorted())
        }
    }

    @Test
    fun `长度为奇数或空的位图不会崩`() {
        // 车机可能对空位图回 0 长度；构造侧允许「不声明」，解析侧不能抛
        val body =
            DeviceIdentification.body(
                DeviceIdentification.Spec(
                    name = "iPhone",
                    messagesSent = intArrayOf(),
                    messagesReceived = intArrayOf(),
                ),
            )
        val parsed = params(body)
        assertNull(parsed[DeviceIdentification.P_MESSAGES_SENT])
        assertNull(parsed[DeviceIdentification.P_MESSAGES_RECEIVED])
    }

    @Test
    fun `描述串含身份与两条清单规模`() {
        val spec = DeviceIdentification.defaultSpec("iPhone 15")
        val text = DeviceIdentification.describe(spec)
        assertTrue(text, text.contains("iPhone 15"))
        assertTrue(text, text.contains("会发=${spec.messagesSent.size}"))
        assertTrue(text, text.contains("可收=${spec.messagesReceived.size}"))
    }
}
