package io.github.shaonialife.carptocar.media

import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2NowPlaying
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationNowPlayingTest {
    @Test
    fun arrivalHintKeepsGuideAndConfirmedEndInvalidatesArtwork() {
        val display = NavigationDisplay()
        val request = checkNotNull(display.receive(HudTurn(1, 7, "Main Road", 300, byteArrayOf(1))))
        assertEquals("300m 左转", display.current()?.title)
        display.receive(HudTurn(3, 24, "", 1, null))
        assertEquals("300m 左转", display.current()?.title)
        display.receive(HudTurn(3, 24, "", 0, null))
        assertNull(display.current())
        display.receive(HudTurn(1, 3, "Next Road", 500, null))
        display.prepared(request, byteArrayOf(55))
        assertArrayEquals(NavigationIcon.blank, display.current()?.artwork)
    }

    @Test
    fun distanceUpdatesReusePreparedArrowAndNewTurnReplacesIt() {
        val display = NavigationDisplay()
        val input = byteArrayOf(1)
        val request = checkNotNull(display.receive(HudTurn(1, 7, "Main Road", 1_500, input)))
        val leftArrow = byteArrayOf(42)
        display.prepared(request, leftArrow)
        assertEquals("1.5km 左转", display.current()?.title)
        assertNull(display.receive(HudTurn(2, 7, "Main Road", 1_400, input.copyOf())))
        assertNull(display.receive(HudTurn(2, 7, "Main Road", 800, null)))
        assertSame(leftArrow, display.current()?.artwork)

        val next = checkNotNull(display.receive(HudTurn(1, 3, "Next Road", 200, byteArrayOf(2))))
        display.prepared(request, leftArrow)
        assertArrayEquals(NavigationIcon.blank, display.current()?.artwork)
        val rightArrow = byteArrayOf(43)
        display.prepared(next, rightArrow)
        assertSame(rightArrow, display.current()?.artwork)
    }

    @Test
    fun overlayKeepsMusicPositionAndRestoresCurrentArtwork() {
        var title = "原曲"
        val artworkRequests = mutableListOf<Boolean>()
        val navigation =
            NavigationNowPlaying(
                music = { includeArtwork ->
                    artworkRequests += includeArtwork
                    CarPlayNowPlaying(
                        title,
                        "歌手",
                        "专辑",
                        100_000,
                        Iap2NowPlaying.STATUS_PLAYING,
                        12_000,
                        appName = "player",
                        artworkId = if (includeArtwork) 7 else null,
                    )
                },
                artworkId = { 8 },
            )
        assertNull(navigation.displayOverride(null))
        assertTrue(artworkRequests.isEmpty())

        val guide = NavigationDisplay.Guide("100m 右转", "Main Road", byteArrayOf(1))
        val active = checkNotNull(navigation.displayOverride(guide))
        assertEquals("100m 右转", active.title)
        assertEquals("Main Road", active.artist)
        assertEquals("原曲", active.album)
        assertEquals(12_000, active.elapsedMs)
        assertEquals(Iap2NowPlaying.STATUS_PLAYING, active.status)
        assertEquals(8, active.artworkId)
        assertTrue(active.navigationArtwork)

        title = "下一首"
        val restored = checkNotNull(navigation.displayOverride(null))
        assertEquals("下一首", restored.title)
        assertEquals(7, restored.artworkId)
        assertEquals(listOf(false, true), artworkRequests)
        assertNull(navigation.displayOverride(null))
    }

    @Test
    fun endingWithoutMusicSendsAnExplicitEmptyStateAndBlankArtwork() {
        val navigation = NavigationNowPlaying(music = { null }, artworkId = { 1 })
        navigation.displayOverride(NavigationDisplay.Guide("100m 左转", "", byteArrayOf(1)))
        val cleared = checkNotNull(navigation.displayOverride(null))
        assertNull(cleared.title)
        assertEquals(Iap2NowPlaying.STATUS_STOPPED, cleared.status)
        assertEquals(1, cleared.artworkId)
        assertTrue(cleared.navigationArtwork)
        assertNull(navigation.displayOverride(null))
    }
}
