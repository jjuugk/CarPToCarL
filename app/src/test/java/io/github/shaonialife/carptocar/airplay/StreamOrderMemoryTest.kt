package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「音频 SETUP 与 RECORD 的先后」决策（issue #14）。
 *
 * 这套逻辑最怕两件事：
 *  1. 把陌生接收端默认推进非常规顺序 —— 奥迪 MIB3 的 2s 看门狗会因此拆链；
 *  2. 学到的结论压不过型号快路径 —— 那台接收端就被永久锁死在错误顺序上。
 */
class StreamOrderMemoryTest {
    private val jybox = "Magic Tec. Magic-Car-Link-1.00"
    private val audi = "Audi MIB3 3810"
    private val bmwId6 = "BMW F36-NBTEvo-0716"

    /** 非 Evo 的宝马（iD5/NBT）与 MGU（iD7/iD8）都还没被前置，仍走默认 + 学习。 */
    private val bmwOther = "BMW F20-NBT-0716"
    private val bmwMgu = "BMW MGU-81"

    @Test
    fun `陌生接收端默认 RECORD 优先`() {
        // 默认必须是老顺序：奥迪/宝马等靠这条活着，改动等于把已定谳的时序推翻。
        assertEquals(
            StreamOrderMemory.Order.RECORD_FIRST,
            StreamOrderMemory.resolve(StreamOrderMemory.Learned.NONE, audi),
        )
        assertEquals(
            StreamOrderMemory.Order.RECORD_FIRST,
            StreamOrderMemory.resolve(StreamOrderMemory.Learned.NONE, bmwOther),
        )
        assertEquals(
            StreamOrderMemory.Order.RECORD_FIRST,
            StreamOrderMemory.resolve(StreamOrderMemory.Learned.NONE, "unknown"),
        )
    }

    @Test
    fun `宝马 iD6 全系第一次连就音频优先`() {
        // 2026-11-09 G38（NBTevo_Q18491I）：补发 RECORD 回 200 后 17ms 通道关闭，
        // 3 秒后 mDNS 服务消失 —— 车机 AirPlay 服务重启。2026-11-14 F36 同报文集合
        // 走「一次 RECORD」稳定 3 分钟。所以 iD6 不靠学，直接前置。
        assertTrue(StreamOrderMemory.matchesKnown(bmwId6))
        assertEquals(
            StreamOrderMemory.Order.AUDIO_FIRST,
            StreamOrderMemory.resolve(StreamOrderMemory.Learned.NONE, bmwId6),
        )
        assertEquals(
            StreamOrderMemory.Order.AUDIO_FIRST,
            StreamOrderMemory.resolve(StreamOrderMemory.Learned.NONE, "BMW G38-NBTEvo-0716"),
        )
        // 自报口径带连字符（NBT-Evo）与大小写混写都要命中。
        assertEquals(
            StreamOrderMemory.Order.AUDIO_FIRST,
            StreamOrderMemory.resolve(StreamOrderMemory.Learned.NONE, "BMW 5系 NBT-Evo iD6"),
        )
        assertEquals(
            StreamOrderMemory.Order.AUDIO_FIRST,
            StreamOrderMemory.resolve(StreamOrderMemory.Learned.NONE, "bmw f36-nbtevo-0716"),
        )
    }

    @Test
    fun `奥迪与 iD7 的 MGU 不能被型号快路径误伤`() {
        assertFalse(StreamOrderMemory.matchesKnown(audi))
        assertFalse(StreamOrderMemory.matchesKnown("Delphi MIB3 4580"))
        // MGU 是 iD7/iD8，不在本次前置范围：让它继续走「学」的路径。
        assertFalse(StreamOrderMemory.matchesKnown(bmwMgu))
        assertFalse(StreamOrderMemory.matchesKnown(bmwOther))
    }

    @Test
    fun `已知型号走音频优先快路径`() {
        // issue #14 实车：/info model=Magic-Car-Link-1.00、manufacturer=Magic Tec.
        assertTrue(StreamOrderMemory.matchesKnown(jybox))
        assertEquals(
            StreamOrderMemory.Order.AUDIO_FIRST,
            StreamOrderMemory.resolve(StreamOrderMemory.Learned.NONE, jybox),
        )
        // 盒子另一种自报口径（JYBOX-80 型号名）也要命中。
        assertEquals(
            StreamOrderMemory.Order.AUDIO_FIRST,
            StreamOrderMemory.resolve(StreamOrderMemory.Learned.NONE, "Magic Tec. JYBOX-80"),
        )
        // 大小写不敏感。
        assertEquals(
            StreamOrderMemory.Order.AUDIO_FIRST,
            StreamOrderMemory.resolve(StreamOrderMemory.Learned.NONE, "MAGIC TEC. magic-car-link"),
        )
    }

    @Test
    fun `学到的音频优先对陌生型号同样生效`() {
        // 不在快路径名单里的接收端，也可能是「补发 RECORD 过敏」那一类：
        // 只有学出来的结论才救得了它（第一轮付一次失败的代价）。
        assertEquals(
            StreamOrderMemory.Order.AUDIO_FIRST,
            StreamOrderMemory.resolve(StreamOrderMemory.Learned.AUDIO_FIRST, "Some Vendor X1"),
        )
    }

    @Test
    fun `学到的 RECORD 优先压过型号快路径`() {
        // 音频优先那一轮连第一次 RECORD 都非 200 → 说明问题不在顺序，
        // 必须能回退，否则快路径会把一台车永久锁死。
        assertEquals(
            StreamOrderMemory.Order.RECORD_FIRST,
            StreamOrderMemory.resolve(StreamOrderMemory.Learned.RECORD_FIRST, jybox),
        )
        // iD6 也留同一条逃生门：这台车的第一次 RECORD 就非 200，说明它有别的问题，
        // 不能因为「型号在名单里」把它反复推回音频优先。反向纠正优先于型号快路径。
        assertEquals(
            StreamOrderMemory.Order.RECORD_FIRST,
            StreamOrderMemory.resolve(StreamOrderMemory.Learned.RECORD_FIRST, bmwId6),
        )
    }

    @Test
    fun `存储键与音频档位记忆同口径`() {
        // 与 AudioProfileMemory.receiverKey 必须一致：同一份 /info 文本归一化后
        // 得到同一个键，两套记忆才不会各算各的。
        assertEquals(
            StreamOrderMemory.receiverKey(jybox),
            StreamOrderMemory.receiverKey("  magic tec.  MAGIC-CAR-LINK-1.00  "),
        )
        assertEquals("magic_tec_magic_car_link_1_00", StreamOrderMemory.receiverKey(jybox))
        assertEquals("unknown", StreamOrderMemory.receiverKey("   "))
    }

    @Test
    fun `诊断行同时给出顺序与依据`() {
        val learned = StreamOrderMemory.describe(
            StreamOrderMemory.Order.AUDIO_FIRST,
            StreamOrderMemory.Learned.AUDIO_FIRST,
            jybox,
        )
        assertTrue("要写明音频优先：$learned", learned.contains("音频优先"))
        assertTrue("要写明依据是本机记忆：$learned", learned.contains("本机记忆"))
        assertTrue("要带接收端身份：$learned", learned.contains("Magic-Car-Link"))

        val fast = StreamOrderMemory.describe(
            StreamOrderMemory.Order.AUDIO_FIRST,
            StreamOrderMemory.Learned.NONE,
            jybox,
        )
        assertTrue("快路径要可区分：$fast", fast.contains("已知型号"))

        val def = StreamOrderMemory.describe(
            StreamOrderMemory.Order.RECORD_FIRST,
            StreamOrderMemory.Learned.NONE,
            audi,
        )
        assertTrue("默认档要说明是 MIB3 时序：$def", def.contains("MIB3"))
    }
}
