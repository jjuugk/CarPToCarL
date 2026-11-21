package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BMW EntryEVO / ALPINE 平台识别（2026-11-21 实车日志）。
 *
 * 该平台的 0xaa02 挑战 16B 会直接断链，必须冷启动即从基线剔除。
 * 识别用 AND 命中多个标记，避免误伤同样含 "alpine" 的其它车机。
 */
class EntryEvoMfiProfileTest {
    @Test
    fun identifiesBmwEntryEvoFromLogIdentity() {
        // 11-21 日志里的真实身份：BMW Group Infotainment System / Entry Evo / ALPINE Electronics,Inc.
        val real = "bmw group infotainment system entry evo alpine electronics,inc."
        assertTrue(Iap2SenderSession.breaksOn16BChallenge(real))
    }

    @Test
    fun requiresBothMarkers() {
        assertTrue(Iap2SenderSession.breaksOn16BChallenge("entry evo alpine"))
        // 只有 alpine，没有 entry evo → 不命中（避免误伤其它 ALPINE 车机）
        assertFalse(Iap2SenderSession.breaksOn16BChallenge("some other alpine head unit"))
        // 只有 entry evo，没有 alpine → 不命中
        assertFalse(Iap2SenderSession.breaksOn16BChallenge("entry evo bosch"))
    }

    @Test
    fun doesNotMatchAlreadySupportedBmwPlatforms() {
        // 作者已适配的宝马 MGU-81 / NBT Evo 不能被误伤
        assertFalse(Iap2SenderSession.breaksOn16BChallenge("bmw mgu-81 cinemo"))
        assertFalse(Iap2SenderSession.breaksOn16BChallenge("bmw nbt evo"))
    }
}
