package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0x5001 NowPlayingUpdate 字段布局回归。
 *
 * 2026-11-06 修的「仪表概率不显示歌名/歌词」：专辑名此前发在 0x001A，
 * 而 0x001A 是 `media_item_artwork_file_transfer_identifier`（单字节 u8 封面 id）。
 * 专辑名为空时只发 1 字节 NUL 恰好蒙混过关，非空时字符串撑爆字段、破坏后续
 * 参数边界 → 车机整帧解析失败。正确 ID 是 0x0006；本项目不发封面，0x001A 不写。
 *
 * 字段 ID 依据：catplay `NowPlayingUpdateMediaItemAttributes`、AuDiCar（实车仪表
 * 能显示）的 `Iap2NowPlaying`、以及本项目 `MIB3_NOW_PLAYING_UPDATE_IDLE` 里
 * `05001a8000`（0x001A 只有 1 字节数据）。
 */
class Iap2NowPlayingTest {
    private fun sample(album: String?) =
        CarPlayNowPlaying(
            title = "测试歌名",
            artist = "测试歌手",
            album = album,
            durationMs = 240_000,
            status = Iap2NowPlaying.STATUS_PLAYING,
            elapsedMs = 12_345,
            appName = "CarLife+",
        )

    /** 从 body 里取出 media item 组（顶层 param 0x0000）的子参数，按 id 索引。 */
    private fun mediaParams(album: String?): Map<Int, Iap2Csm.Param> {
        val body = Iap2NowPlaying.body(sample(album))
        val top = Iap2Csm.parseParams(body).associateBy { it.id }
        assertTrue("顶层必须有 media item 组 param 0", top.containsKey(0))
        return Iap2Csm.parseParams(top.getValue(0).value).associateBy { it.id }
    }

    private fun Iap2Csm.Param.text(): String {
        assertTrue("字符串字段必须以 NUL 结尾", value.isNotEmpty() && value.last() == 0.toByte())
        return value.copyOf(value.size - 1).decodeToString()
    }

    @Test
    fun albumUsesMediaItemId6() {
        val media = mediaParams("测试专辑")
        assertTrue("media item 组必须有 0x0006 albumTitle", media.containsKey(Iap2NowPlaying.MEDIA_ALBUM))
        assertEquals(0x0006, Iap2NowPlaying.MEDIA_ALBUM)
        assertEquals("测试专辑", media.getValue(Iap2NowPlaying.MEDIA_ALBUM).text())
    }

    /** 核心回归：任何专辑名（含 null / 空 / 长串）都不能再占用封面传输 id 0x001A。 */
    @Test
    fun albumNeverWritesArtworkTransferId() {
        for (album in listOf(null, "", "专辑", "A Quite Long Album Name With Spaces 2026")) {
            val media = mediaParams(album)
            assertFalse(
                "0x001A 是 artworkFileTransferIdentifier(u8)，专辑名 '$album' 不得写进去",
                media.containsKey(Iap2NowPlaying.MEDIA_ARTWORK_ID),
            )
            assertTrue("专辑名一律走 0x0006", media.containsKey(Iap2NowPlaying.MEDIA_ALBUM))
            val expected = album.orEmpty()
            assertEquals(expected, media.getValue(Iap2NowPlaying.MEDIA_ALBUM).text())
        }
    }

    /** 旧 bug 的形态：0x001A 是单字节字段；我们的帧里根本不该出现它。 */
    @Test
    fun artworkIdFieldIsSingleByteInIdleFrame() {
        val idle = VirtualIap2LinkProbe.idleFrame()
        val top = Iap2Csm.parseParams(idle.copyOfRange(6, idle.size)).associateBy { it.id }
        // 空态帧是完整 CSM 帧：0x4040 | len | 0x5001 | params
        val media = Iap2Csm.parseParams(top.getValue(0).value).associateBy { it.id }
        assertEquals("空态帧里 0x001A 必须是 1 字节 u8", 1, media.getValue(0x001A).value.size)
    }

    /**
     * 有封面时，0x001A 是**单字节编号**，不是字符串、更不是图片本身。
     *
     * 车机拿到这个号之后才在 session 12 上要图（见 [Iap2ArtworkTransfer]）。
     * 写多字节会把它后面的字段全部挤出边界——这正是 2026-11-06 那次
     * 「仪表概率不显示歌名」的成因。
     */
    @Test
    fun artworkIdGoesIntoField0x1aAsSingleByte() {
        val body = Iap2NowPlaying.body(sample("专辑").copy(artworkId = 42))
        val top = Iap2Csm.parseParams(body).associateBy { it.id }
        val media = Iap2Csm.parseParams(top.getValue(0).value).associateBy { it.id }
        val value = media.getValue(Iap2NowPlaying.MEDIA_ARTWORK_ID).value
        assertEquals("0x001A 是 u8，只能 1 字节", 1, value.size)
        assertEquals(42, value[0].toInt() and 0xFF)
    }

    /** 越界编号一律当「没有封面」：写 0 或写 256 都会让车机解析错位或等一张永不来的图。 */
    @Test
    fun artworkIdOutOfRangeIsNotWritten() {
        for (id in listOf(0, 256, -1)) {
            val body = Iap2NowPlaying.body(sample("专辑").copy(artworkId = id))
            val top = Iap2Csm.parseParams(body).associateBy { it.id }
            val media = Iap2Csm.parseParams(top.getValue(0).value).associateBy { it.id }
            assertFalse(
                "越界 id=$id 不得写进 0x001A（车机按 u8 解析，多余字节会打乱后续参数）",
                media.containsKey(Iap2NowPlaying.MEDIA_ARTWORK_ID),
            )
        }
    }

    @Test
    fun otherMediaItemIdsFollowSpec() {
        val media = mediaParams("专辑")
        assertEquals("测试歌名", media.getValue(0x0001).text())
        assertEquals("测试歌手", media.getValue(0x000C).text())
        // duration 是 u32 大端
        val duration = media.getValue(0x0004).value
        assertEquals(4, duration.size)
        assertEquals(240_000L, duration.fold(0L) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF).toLong() })
    }

    @Test
    fun playbackGroupUnaffected() {
        val body = Iap2NowPlaying.body(sample("专辑"))
        val top = Iap2Csm.parseParams(body).associateBy { it.id }
        val playback = Iap2Csm.parseParams(top.getValue(1).value).associateBy { it.id }
        assertEquals(1, playback.getValue(0x0000).value[0].toInt()) // STATUS_PLAYING
        assertEquals("CarLife+", playback.getValue(0x0007).text())
    }

    @Test
    fun encodedFrameHasNowPlayingUpdateId() {
        val frame = Iap2NowPlaying.encode(sample("专辑"))
        // 帧头：4040 | len(u16) | msgId(u16) = 0x5001
        assertEquals(0x40, frame[0].toInt() and 0xFF)
        assertEquals(0x40, frame[1].toInt() and 0xFF)
        val msgId = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
        assertEquals(0x5001, msgId)
    }

    /** 只用来在单测里读空态常量，避免把常量可见性再放大。 */
    private object VirtualIap2LinkProbe {
        fun idleFrame(): ByteArray = io.github.shaonialife.carptocar.airplay.VirtualIap2Link.MIB3_NOW_PLAYING_UPDATE_IDLE
    }
}
