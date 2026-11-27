package io.github.shaonialife.carptocar.carlife

/**
 * CarLife 歌词行的识别。
 *
 * CarLife 引擎**没有专门的歌词通道**：`CarlifeMediaInfo` 的字段只有
 * `source/song/artist/album/albumArt/duration/playlistNum/songId/mode`
 * （见 [CarLifeProtocol.parseMediaInfoList]），逐行歌词是**另起一条媒体信息**
 * 推过来的。2026-11-03 那批日志里的形状是：
 *
 * ```
 * 真实歌曲：song=总有春风为你而来 (女版)  artist=悠禾                       album=总有春风为你而来 (女版)
 * 歌词行：  song=要相信所有缘分上天         artist=总有春风为你而来 (女版)-悠禾  album=总有春风为你而来 (女版)
 * ```
 *
 * 但 2026-11-26 vivo / 宝马 MGU-81 上是另一种（`artist` 是「歌名-歌手」）：
 *
 * ```
 * 真实歌曲：song=你快乐 所以我快乐  artist=王菲            album=情·菲·得意
 * 歌词行：  song=词：林夕           artist=你快乐 所以我快乐-王菲  album=情·菲·得意
 * ```
 *
 * 所以判据有两条：老形状 `artist == "<album>-<真实艺术家>"` 自识别；新形状必须传入
 * [base]（最近一条非歌词行），按 `artist == "<base.song>-<base.artist>"` 且 album 一致判定。
 * 没有 base 时只认老形状——宁可漏判，也不能把正常歌曲误当歌词丢掉。
 */
object LyricLines {
    /**
     * 这条媒体信息是不是「歌词行」。
     *
     * [base] = 最近一条**非歌词行**媒体信息（真实歌曲），用于识别新形状；
     * 不传时只识别老形状。判据只看 album/artist 的字符串形状，与歌词文本无关。
     */
    fun isLyricLine(
        info: CarLifeMediaInfo,
        base: CarLifeMediaInfo? = null,
    ): Boolean {
        val album = info.album.trim()
        val artist = info.artist.trim()
        if (album.isEmpty() || artist.isEmpty()) return false

        // 老形状：artist = "<album>-<artist>"
        if (artist.startsWith("$album-")) return true

        // 新形状：artist = "<base.song>-<base.artist>"，album 仍与真实歌曲一致
        if (base == null) return false
        val baseSong = base.song.trim()
        val baseAlbum = base.album.trim()
        val baseArtist = base.artist.trim()
        if (baseSong.isEmpty() || baseSong == album) return false
        if (baseAlbum.isNotEmpty() && album != baseAlbum) return false
        val prefix = "$baseSong-"
        if (!artist.startsWith(prefix)) return false
        val tail = artist.removePrefix(prefix).trim()
        return tail.isNotEmpty() && (baseArtist.isEmpty() || tail == baseArtist)
    }

    /**
     * 确认这条**已识别为歌词行**的信息确实属于 [base] 那首歌。
     *
     * [isLyricLine] 只判「形状像不像歌词」，不看归属：切歌瞬间旧歌的歌词行与新曲的
     * 真实曲目混在同一批里时，形状照样成立。这里补两道校验：
     *
     * 1. 专辑必须一致，且 `artist` 的前缀确实是 `"<歌名>-<歌手>"` 或 `"<专辑>-<歌手>"`；
     * 2. 两边时长都有值时**必须相等**——同名不同版本（Live / remix / 翻唱）靠它挡住。
     *
     * 第 2 条之所以对两种形状都生效：专辑名与歌名相同的歌太多了（`总有春风为你而来`
     * 就是），此时两种形状的字符串完全一样，时长是唯一能分辨的凭据。
     */
    fun matchesSong(
        info: CarLifeMediaInfo,
        base: CarLifeMediaInfo,
    ): Boolean {
        val album = info.album.trim()
        val title = base.song.trim()
        val artist = base.artist.trim()
        if (title.isEmpty() || album != base.album.trim()) return false
        val lineArtist = info.artist.trim()
        if (lineArtist != "$title-$artist" && lineArtist != "$album-$artist") return false
        if (info.duration > 0 && base.duration > 0 && info.duration != base.duration) return false
        return true
    }

    /**
     * 歌词行里真正的画家名：去掉 `[album]-` 或 `[base.song]-` 前缀。
     * 取不到（不是歌词行 / 前缀对不上）时原样返回入参。
     */
    fun trueArtist(
        info: CarLifeMediaInfo,
        base: CarLifeMediaInfo? = null,
    ): String {
        val artist = info.artist.trim()
        val album = info.album.trim()
        if (album.isNotEmpty() && artist.startsWith("$album-")) {
            return artist.removePrefix("$album-").trim()
        }
        if (base != null) {
            val prefix = "${base.song.trim()}-"
            if (prefix.length > 1 && artist.startsWith(prefix)) {
                return artist.removePrefix(prefix).trim()
            }
        }
        return info.artist
    }
}
