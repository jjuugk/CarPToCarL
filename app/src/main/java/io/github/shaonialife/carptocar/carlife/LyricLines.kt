package io.github.shaonialife.carptocar.carlife

/**
 * CarLife 歌词行的识别。
 *
 * CarLife 引擎**没有专门的歌词通道**：`CarlifeMediaInfo` 的字段只有
 * `source/song/artist/album/albumArt/duration/playlistNum/songId/mode`
 * （见 [CarLifeProtocol.parseMediaInfoList]），逐行歌词是**另起一条媒体信息**
 * 推过来的，形状是：
 *
 * ```
 * 真实歌曲：song=总有春风为你而来 (女版)  artist=悠禾                       album=总有春风为你而来 (女版)
 * 歌词行：  song=要相信所有缘分上天         artist=总有春风为你而来 (女版)-悠禾  album=总有春风为你而来 (女版)
 * ```
 *
 * 签名很干净：**`artist == "<album>-<真实艺术家>"`**。真实歌曲的 artist 不会以
 * 「专辑名-」开头，所以这条判据对正常歌曲零误伤（2026-11-03 实车日志逐条比对，
 * 同一轮 53 条媒体信息里 47 条是歌词行）。
 *
 * 只做判定，不决定要不要展示——展示策略在
 * [io.github.shaonialife.carptocar.iap2.NowPlayingSourcePolicy]。
 */
object LyricLines {
    /**
     * 这条媒体信息是不是「歌词行」。
     *
     * 判据只看 album/artist 的字符串形状，与歌名内容无关：某些歌词行本身就是
     * 空串或纯标点（纯音乐段落），若拿 song 参与判定会漏判。
     */
    fun isLyricLine(info: CarLifeMediaInfo): Boolean {
        val album = info.album.trim()
        val artist = info.artist.trim()
        if (album.isEmpty() || artist.isEmpty()) return false
        return artist.startsWith("$album-")
    }

    /**
     * 歌词行里真正的画家名：`"<album>-<artist>"` 去掉前缀。
     *
     * 取不到（不是歌词行 / 前缀对不上）时原样返回入参。
     */
    fun trueArtist(info: CarLifeMediaInfo): String {
        val artist = info.artist.trim()
        if (!isLyricLine(info)) return info.artist
        return artist.removePrefix("${info.album.trim()}-").trim()
    }
}
