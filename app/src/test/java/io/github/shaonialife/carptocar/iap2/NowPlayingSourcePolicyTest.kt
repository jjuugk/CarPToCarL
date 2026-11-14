package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.carlife.CarLifeMediaInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0x5001 数据源仲裁：CarLife 歌词行 vs 系统媒体会话。
 *
 * 这里的每一条都对应一个**能看见的**车机现象：
 * - 三条门全过 → 标题逐行滚动（要的就是这个）；
 * - 新鲜期过了还抢 → 暂停后标题永远停在最后一句歌词；
 * - 不校验「同一首歌」→ 手机切到第三方播放器后，车机仍显示上一首的歌词；
 * - 无条件抢 → 荣耀音乐那种把歌词写进 MediaSession title 的播放器被顶掉，标题一秒来回跳。
 */
class NowPlayingSourcePolicyTest {
    private fun info(
        song: String,
        artist: String,
        album: String,
    ) = CarLifeMediaInfo(
        source = "carlife",
        song = song,
        artist = artist,
        album = album,
        albumArt = null,
        duration = 0,
        playlistNum = 0,
        songId = "",
        mode = 0,
    )

    private val base = info("总有春风为你而来 (女版)", "悠禾", "总有春风为你而来 (女版)")
    private val lyric = info("要相信所有缘分上天", "总有春风为你而来 (女版)-悠禾", "总有春风为你而来 (女版)")

    private fun decide(
        now: Long = 10_000L,
        at: Long = 9_500L,
        latest: CarLifeMediaInfo? = lyric,
        baseInfo: CarLifeMediaInfo? = base,
        title: String? = "总有春风为你而来 (女版)",
        artist: String? = "悠禾",
    ) = NowPlayingSourcePolicy.shouldShowCarLifeLyric(
        nowMillis = now,
        lastMediaInfoAtMillis = at,
        latest = latest,
        base = baseInfo,
        sessionTitle = title,
        sessionArtist = artist,
    )

    @Test
    fun `同一首歌且刚推过歌词——让歌词上位`() {
        assertTrue(decide())
    }

    @Test
    fun `不是歌词行时永远不抢`() {
        assertFalse(decide(latest = base))
    }

    @Test
    fun `超出新鲜期就让位回歌名——暂停后标题不许停在最后一句`() {
        assertFalse(decide(now = 9_500L + NowPlayingSourcePolicy.LYRIC_WINDOW_MILLIS + 1))
    }

    @Test
    fun `刚好卡在窗口边界仍算新鲜`() {
        assertTrue(decide(now = 9_500L + NowPlayingSourcePolicy.LYRIC_WINDOW_MILLIS))
    }

    @Test
    fun `从没收到过媒体信息时不抢`() {
        assertFalse(decide(at = 0L))
    }

    @Test
    fun `时间戳倒挂当作过期——宁可显示歌名也不要显示错的歌词`() {
        assertFalse(decide(now = 9_000L, at = 9_500L))
    }

    @Test
    fun `手机已切到别的歌——CarLife 旧歌词不许顶上来`() {
        assertFalse(decide(title = "侧脸", artist = "于果"))
    }

    @Test
    fun `歌名相同但画家不同也不抢——同名翻唱是真实场景`() {
        assertFalse(decide(artist = "某翻唱歌手"))
    }

    @Test
    fun `没有真实歌名基线时不抢——无法证明是同一首歌`() {
        assertFalse(decide(baseInfo = null))
    }

    @Test
    fun `媒体会话没有标题时放行——那条路本来就落到 CarLife 分支`() {
        assertTrue(decide(title = null))
    }

    @Test
    fun `媒体会话没有画家时不因画家缺失而否决`() {
        assertTrue(decide(artist = null))
    }
}
