package io.github.shaonialife.carptocar.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * 「车机只认苹果设备」兼容探针的记录表（[BtIdentityProbe]）。
 *
 * 这里锁的是**对外声明什么**，不是怎么注册——UUID 写错一个字符，车机看到的就是
 * 一条无关服务，而日志仍然会说「已注册」，现象上和「车机不看 SDP」完全一样，极难排查。
 */
class BtIdentityProbeTest {
    @Test
    fun `关闭时一条记录都不注册`() {
        assertTrue(BtIdentityProbe.recordsFor(false).isEmpty())
    }

    @Test
    fun `开启时注册 CarPlay 与 Wireless iAP v2 两条`() {
        val records = BtIdentityProbe.recordsFor(true)
        assertEquals(2, records.size)
        assertEquals(
            listOf("CarPlay", "Wireless iAP v2"),
            records.map { it.name },
        )
    }

    @Test
    fun `CarPlay 记录用的是 iPhone 在 EIR 广播的那个 UUID`() {
        // 2d8d2466-e14d-451c-88bc-7301abea291a：车机判「这台是不是 CarPlay 设备」
        // 最常引用的那一个（与 catplay 的 CARPLAY_EIR_PHONE 一致）。
        assertEquals(
            UUID.fromString("2d8d2466-e14d-451c-88bc-7301abea291a"),
            BtIdentityProbe.CARPLAY_WIRELESS_UUID,
        )
        assertEquals(
            BtIdentityProbe.CARPLAY_WIRELESS_UUID,
            BtIdentityProbe.recordsFor(true).first().uuid,
        )
    }

    @Test
    fun `Wireless iAP v2 记录与 catplay 的 SDP 记录同 UUID`() {
        assertEquals(
            UUID.fromString("02030302-1d19-415f-86f2-22a2106a0a77"),
            BtIdentityProbe.WIRELESS_IAP2_UUID,
        )
    }

    @Test
    fun `占位记录不得占用真正的 iAP2 服务 UUID`() {
        // 00000000-deca-fade-deca-deafdecacafe 是 iAP2 主链路正在监听的那条，
        // 占位记录若与它撞号，会把车机回连引到一个不做协议的 socket 上。
        val iap2 = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        BtIdentityProbe.ALL.forEach { record ->
            assertTrue(
                "占位记录「${record.name}」与 iAP2 服务 UUID 撞号",
                record.uuid != iap2,
            )
        }
    }

    @Test
    fun `两条记录互不重复且都带说明`() {
        val records = BtIdentityProbe.ALL
        assertEquals(records.size, records.map { it.uuid }.toSet().size)
        records.forEach { record ->
            assertTrue("「${record.name}」的 UUID 必须是小写规范串", record.uuid.toString().isNotBlank())
            assertTrue("「${record.name}」缺 why 说明，日志里就说不清为什么挂它", record.why.isNotBlank())
        }
    }
}
