package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面去重：**一张图一个编号**。
 *
 * 判据的实际来源是 2026-11-15 奥迪 MIB3 实车日志——2 分钟里同一张 25KB 的图被
 * 重新编码、重新声明了 19 次（每行歌词一次），车机 19 次一条 START 都没回。
 */
class ArtworkDedupeTest {
    /** 歌词行把 title 改掉、图还是同一张：键必须不变（否则就换编号、重传一遍）。 */
    @Test
    fun `歌词行换标题但图没换时键不变`() {
        val art = Any()
        val first = ArtworkDedupe.key("街道上人来人往 我手心里的空荡", "嗜好（LIVE） - 网易音乐人/颜人中", art)
        val second = ArtworkDedupe.key("以后会有 谁会前来造访", "嗜好（LIVE） - 网易音乐人/颜人中", art)
        assertEquals("同一张图的两句歌词必须共用一个键", first, second)
    }

    /** 换歌换了图（新对象）：键必须变，否则车机会一直用旧封面的缓存。 */
    @Test
    fun `换歌换了图就换键`() {
        val first = ArtworkDedupe.key("歌名", "歌手", Any())
        val second = ArtworkDedupe.key("歌名", "歌手", Any())
        assertFalse("图对象不同即视为换图", first == second)
    }

    /** 没有位图时按 title 兜底——反正编码结果是 null，键怎么变都不会分配编号。 */
    @Test
    fun `没有位图时按标题兜底`() {
        val key = ArtworkDedupe.key("歌名", "歌手", null)
        assertTrue(key.startsWith("meta#"))
        assertTrue(key.contains("歌名"))
    }

    /** 编出来的字节一模一样 → 沿用旧编号（不换 id、不重传）。 */
    @Test
    fun `字节相同沿用旧编号`() {
        val bytes = ByteArray(64) { it.toByte() }
        assertTrue(ArtworkDedupe.shouldReuseId(bytes.copyOf(), previousId = 7, fresh = bytes))
    }

    @Test
    fun `字节不同必须换编号`() {
        val previous = ByteArray(64) { it.toByte() }
        val fresh = ByteArray(64) { (it + 1).toByte() }
        assertFalse(ArtworkDedupe.shouldReuseId(previous, previousId = 7, fresh = fresh))
    }

    /** 上一轮没有封面（id=0）时没有「沿用」可言。 */
    @Test
    fun `没有旧编号时不复用`() {
        val bytes = ByteArray(8)
        assertFalse(ArtworkDedupe.shouldReuseId(bytes, previousId = 0, fresh = bytes))
        assertFalse(ArtworkDedupe.shouldReuseId(null, previousId = 9, fresh = bytes))
        assertFalse(ArtworkDedupe.shouldReuseId(ByteArray(0), previousId = 9, fresh = bytes))
    }
}
