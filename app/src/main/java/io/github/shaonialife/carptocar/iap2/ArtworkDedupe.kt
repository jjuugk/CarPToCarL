package io.github.shaonialife.carptocar.iap2

/**
 * 封面去重：**一张图一个编号**，别再跟着歌名/歌词行走。
 *
 * ## 血泪来历（2026-11-15 奥迪 MIB3 实车日志）
 *
 * 网易云把**逐行歌词**写进 MediaSession 的 `title`，于是 `title` 每 5~8 秒变一次。
 * 旧的封面缓存键是 `"${title}|${artist}"`，一行歌词就成了「新歌」：
 *
 * ```
 * 18:04:48  ART: 封面就绪 id=2 25091B   ← 同一张 25KB 的图，重新编码、换了编号
 * 18:04:58  ART: 封面就绪 id=3 25091B
 * ...
 * 18:06:57  ART: 封面就绪 id=19 25091B  ← 2 分钟里 19 次 SETUP，车机 0 次 START
 * ```
 *
 * 每一次换编号都等于告诉车机「还有一张新封面要来」，19 次全是空头支票。
 *
 * ## 判据（两条，任一成立就算同一张图）
 *
 * 1. **源图是同一个对象**（[key]）——播放器不换图时 `MediaSession` 交回来的
 *    `Bitmap` 实例不变，这一条连编码都省了。
 * 2. **压出来的字节完全一致**（[shouldReuseId]）——有些播放器每次都给新实例，
 *    这时用字节比对兜底（25KB 的一次 `contentEquals`，比重新编码+重传便宜得多）。
 *
 * 两条都不成立才是真的换了图，才分配新编号（车机按编号缓存，只有换编号它才重新要图）。
 */
object ArtworkDedupe {
    /**
     * 封面来源键。
     *
     * @param title 当前标题（**可能是一句歌词**，因此只在没有位图时才拿它兜底）
     * @param artist 当前画家
     * @param art 系统媒体会话给的封面位图（可以是任意对象，这里只要身份）
     */
    fun key(
        title: String?,
        artist: String?,
        art: Any?,
    ): String =
        if (art != null) {
            // 位图身份 = 「还是这张图」。歌词行改变不了它，所以整首歌共用一个键。
            "art#${System.identityHashCode(art)}"
        } else {
            // 没有位图：编码结果必然是 null，键怎么变都不会分配编号，沿用旧语义即可。
            "meta#${title.orEmpty()}#${artist.orEmpty()}"
        }

    /**
     * 新压出来的字节和上一张**一模一样**时，沿用上一张的编号（不重传、不换 id）。
     *
     * @param previousBytes 上一张已声明出去的封面字节（null = 还没有过）
     * @param previousId 上一张的编号（0 = 上一轮没有封面）
     * @param fresh 这次刚压出来的字节
     */
    fun shouldReuseId(
        previousBytes: ByteArray?,
        previousId: Int,
        fresh: ByteArray,
    ): Boolean {
        if (previousId !in 1..0xFF) return false
        if (previousBytes == null || previousBytes.isEmpty()) return false
        return previousBytes.contentEquals(fresh)
    }
}
