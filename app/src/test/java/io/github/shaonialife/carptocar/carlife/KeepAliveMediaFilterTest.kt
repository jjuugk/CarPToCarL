package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保活假媒体信息的过滤判据。
 *
 * 铁证来自 2026-11-20 1.2.72 日志：
 * `14:12:07.128 CARLIFE: 媒体信息 song=车机音频直通 artist=CarPToCarL art=3377B`
 * → 下一行就被补推成车机歌名。这条路径绕过 MediaSession 列表的包名过滤，
 * 只能在 onMediaInfo 最前端挡。
 */
class KeepAliveMediaFilterTest {
    private fun info(
        song: String,
        artist: String,
    ) = CarLifeMediaInfo(
        source = "carlife",
        song = song,
        artist = artist,
        album = "未知",
        albumArt = null,
        duration = 0,
        playlistNum = 0,
        songId = "",
        mode = 0,
    )

    @Test
    fun `实车日志里的假歌名被挡`() {
        assertTrue(KeepAliveMediaFilter.isKeepAliveInfo(info("车机音频直通", "CarPToCarL")))
    }

    @Test
    fun `大小写与首尾空格不影响判定`() {
        assertTrue(KeepAliveMediaFilter.isKeepAliveInfo(info("  车机音频直通  ", "  carptocarl  ")))
    }

    @Test
    fun `真实歌名同名但歌手不同不挡`() {
        assertFalse(KeepAliveMediaFilter.isKeepAliveInfo(info("车机音频直通", "周杰伦")))
    }

    @Test
    fun `真实歌手同名但歌名不同不挡`() {
        assertFalse(KeepAliveMediaFilter.isKeepAliveInfo(info("晴天", "CarPToCarL")))
    }

    @Test
    fun `普通歌曲不挡`() {
        assertFalse(KeepAliveMediaFilter.isKeepAliveInfo(info("惜別的海岸", "江蕙")))
    }

    @Test
    fun `歌词行不会被误判`() {
        assertFalse(KeepAliveMediaFilter.isKeepAliveInfo(info("车机音频直通-其他", "其他")))
    }
}
