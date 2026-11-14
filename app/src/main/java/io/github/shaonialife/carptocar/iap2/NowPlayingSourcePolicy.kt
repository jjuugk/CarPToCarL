package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.carlife.CarLifeMediaInfo
import io.github.shaonialife.carptocar.carlife.LyricLines

/**
 * 0x5001 NowPlayingUpdate 的**数据源仲裁**：MediaSession 与 CarLife 歌词行谁上位。
 *
 * ## 为什么需要仲裁
 *
 * iAP2 的 NowPlayingUpdate **没有歌词字段**（media item 组 17 个字段里没有，
 * playback 组也没有；车机 `0x5000` 订阅里点名的 0x0010/0x0012 是流派与作曲者）。
 * 歌词唯一能出场的形状是**顶替 title**，逐行替换。
 *
 * 而 [io.github.shaonialife.carptocar.SessionController.buildNowPlaying] 原本是
 * 「MediaSession 有 title 就直接 return」，CarLife 那条源永远轮不到。所以要让歌词
 * 行生效就必须让它在**特定条件下**插到 MediaSession 前面——这个「特定条件」就是本对象。
 *
 * ## 三条门（缺一不可）
 *
 * 1. **确实是歌词行**——`artist == "<album>-<artist>"`（[LyricLines]）。
 * 2. **CarLife 还在推**——最后一条媒体信息距今不超过 [LYRIC_WINDOW_MILLIS]。
 *    暂停/换到第三方播放器后 CarLife 就不再推，窗口一过立刻让位回 MediaSession，
 *    不会把上一首的歌词挂在新歌上。
 * 3. **两边还是同一首歌**——MediaSession 的 title 与「CarLife 最近一条**非歌词行**」
 *    的歌名一致（artist 两边都有值时要一致）。用户用第三方播放器切歌时这条会先失败，
 *    哪怕 CarLife 那边还在推旧歌的歌词。
 *
 * MediaSession 压根没有标题时也放行：那种情况 [io.github.shaonialife.carptocar.SessionController]
 * 本来就会落到 CarLife 分支，无需仲裁。
 *
 * 纯函数，时间由调用方传入（可测）。
 */
object NowPlayingSourcePolicy {
    /**
     * CarLife 媒体信息的「新鲜期」。
     *
     * 5 秒 = 歌词行正常间隔（1~2 秒一行）的 2.5 倍以上：偶尔卡一两行不会闪回歌名，
     * 而暂停播放后 5 秒内必然让位。**别调大到 10 秒以上**——那会让「切到第三方
     * 播放器」后的头几秒仍显示上一首的歌词。
     */
    const val LYRIC_WINDOW_MILLIS = 5_000L

    /**
     * 该不该用 CarLife 的歌词行顶掉 MediaSession 的歌名。
     *
     * @param nowMillis 当前 `SystemClock.uptimeMillis()`
     * @param lastMediaInfoAtMillis CarLife 最后一条媒体信息的到达时刻（0 / 负数 = 从没有过）
     * @param latest CarLife 最近一条媒体信息（可能就是歌词行）
     * @param base CarLife 最近一条**非**歌词行媒体信息（真实歌名）
     * @param sessionTitle 系统媒体会话当前的歌名
     * @param sessionArtist 系统媒体会话当前的画家名
     */
    fun shouldShowCarLifeLyric(
        nowMillis: Long,
        lastMediaInfoAtMillis: Long,
        latest: CarLifeMediaInfo?,
        base: CarLifeMediaInfo?,
        sessionTitle: String?,
        sessionArtist: String?,
    ): Boolean {
        if (latest == null || !LyricLines.isLyricLine(latest)) return false
        if (lastMediaInfoAtMillis <= 0L) return false
        val age = nowMillis - lastMediaInfoAtMillis
        if (age < 0L || age > LYRIC_WINDOW_MILLIS) return false
        // 没有媒体会话标题：buildNowPlaying 本来就走 CarLife 分支，无需仲裁。
        if (sessionTitle.isNullOrBlank()) return true
        // 没见过「真实歌名」的基线，无法证明是同一首歌——保守起见不抢。
        if (base == null) return false
        if (sessionTitle.trim() != base.song.trim()) return false
        val baseArtist = base.artist.trim()
        val artist = sessionArtist?.trim()
        if (baseArtist.isNotEmpty() && !artist.isNullOrEmpty() && baseArtist != artist) return false
        return true
    }
}
