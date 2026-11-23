package io.github.shaonialife.carptocar.media

import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2NowPlaying

/** Wi-Fi iAP2 会话的显示覆盖层。原媒体源仍正常接收播放、歌词和封面变化。 */
internal class NavigationNowPlaying(
    private val music: (includeArtwork: Boolean) -> CarPlayNowPlaying?,
    private val artworkId: (ByteArray) -> Int,
) {
    private var hadGuide = false

    /** null 表示交还给原有媒体 provider。 */
    fun displayOverride(guide: NavigationDisplay.Guide?): CarPlayNowPlaying? {
        if (guide == null) {
            if (!hadGuide) return null
            hadGuide = false
            val restored = music(true) ?: EMPTY
            if (restored.artworkId != null) return restored
            return restored.copy(artworkId = artworkId(NavigationIcon.blank), navigationArtwork = true)
        }

        hadGuide = true
        val song = music(false)
        val duration = song?.durationMs?.coerceAtLeast(0) ?: 0
        return CarPlayNowPlaying(
            title = guide.title,
            artist = guide.road.ifBlank { "   " },
            album = song?.title?.takeIf { it.isNotBlank() } ?: "暂无音乐",
            durationMs = duration,
            status = song?.status ?: Iap2NowPlaying.STATUS_PAUSED,
            elapsedMs = song?.elapsedMs?.coerceIn(0, duration) ?: 0,
            shuffleMode = song?.shuffleMode ?: 0,
            repeatMode = song?.repeatMode ?: 0,
            appName = "导航",
            artworkId = artworkId(guide.artwork),
            navigationArtwork = true,
        )
    }

    private companion object {
        val EMPTY =
            CarPlayNowPlaying(
                title = null,
                artist = null,
                album = null,
                durationMs = 0,
                status = Iap2NowPlaying.STATUS_STOPPED,
                elapsedMs = 0,
                appName = null,
            )
    }
}
