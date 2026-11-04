package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0x1D01 的 EA 协议清单解析。
 *
 * 病根（2026-11-04 奥迪 MIB3 实车，1.2.17 日志 Audi_MMI_8349 / AUDI 4260）：
 * EA 协议是用**同一个参数号重复出现**表达的，旧实现 `associateBy { it.id }`
 * 把它当成唯一键，只留下最后一个 —— 三个协议只剩 eadata1，0xEA00 也只对
 * eadata1 发，车机一个 0xEA03 都不回，EA 隧道建不起来。
 * C8-4580（CL3D）固件报告 §5.7 从车机侧证实：EA 会话经 CIAP2EAPProtocolViwi
 * 桥接进内部 VIWI TCP，是 AirPlay 信令与车控数据的承载管道。
 */
class Iap2EaTest {
    /** 按奥迪 MIB3 实车 0x1D01 的真实结构拼一份体：三个 EA 槽都用参数号 10。 */
    private fun audiBody(): ByteArray {
        val ea = { protocolId: Int, name: String ->
            Iap2Csm.group(
                10,
                Iap2Csm.u8(0, protocolId) +
                    Iap2Csm.string(1, name) +
                    // 实车里每个 EA 槽还带一个 id=2 的附加参数；解析必须忽略它而不是错位
                    Iap2Csm.u8(2, 0),
            )
        }
        return Iap2Csm.group(0, Iap2Csm.string(0, "Audi_MMI_8349")) +
            Iap2Csm.group(1, Iap2Csm.string(0, "AUDI 4260")) +
            ea(1, "de.audi.mmiconnectdatatransfer") +
            ea(2, "com.audi.cpp.eadata0") +
            ea(3, "com.audi.cpp.eadata1")
    }

    @Test
    fun repeatedGroupIdKeepsEveryEaProtocol() {
        val protocols = Iap2Ea.parseProtocols(audiBody())
        assertEquals(3, protocols.size)
        assertEquals(
            listOf(
                Triple(10, 1, "de.audi.mmiconnectdatatransfer"),
                Triple(10, 2, "com.audi.cpp.eadata0"),
                Triple(10, 3, "com.audi.cpp.eadata1"),
            ),
            protocols,
        )
    }

    /**
     * 旧实现的行为留档：`associateBy { it.id }` 只剩最后一个。
     *
     * 这条用例不是在测产品代码，而是把「为什么必须按顺序收集」钉在测试里——
     * 谁把解析改回 map 语义，[repeatedGroupIdKeepsEveryEaProtocol] 会立刻红。
     */
    @Test
    fun mapSemanticsWouldLoseProtocols() {
        val byId = Iap2Csm.parseParams(audiBody()).associateBy { it.id }
        val eaSlots = byId[10]?.let { Iap2Csm.parseParams(it.value) }
        assertTrue(eaSlots != null)
        // 只剩一个槽里的内容：协议 3，而不是三个协议
        assertEquals(3, eaSlots!!.firstOrNull { it.id == 0 }?.value?.first()?.toInt())
    }

    @Test
    fun emptyBodyYieldsNothing() {
        assertEquals(0, Iap2Ea.parseProtocols(ByteArray(0)).size)
    }

    @Test
    fun duplicateDeclarationsAreCollapsed() {
        val ea =
            Iap2Csm.group(10, Iap2Csm.u8(0, 2) + Iap2Csm.string(1, "com.audi.cpp.eadata0"))
        val body = ea + ea
        assertEquals(1, Iap2Ea.parseProtocols(body).size)
    }
}
