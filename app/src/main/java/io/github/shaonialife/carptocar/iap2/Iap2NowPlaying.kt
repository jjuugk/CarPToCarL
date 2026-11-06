package io.github.shaonialife.carptocar.iap2

/**
 * iAP2「正在播放」信息（0x5001 NowPlayingUpdate）的载荷与编码。
 *
 * 字段 ID 来自 iAP2 全量消息目录（catplay
 * `core/catplay_csm/src/msg/iap2.rs` 的 `NowPlayingUpdateMediaItemAttributes` /
 * `NowPlayingUpdatePlaybackAttributes`），并与 AuDiCar（实车仪表能显示媒体信息）
 * 的 `Iap2NowPlaying` 逐字段核对：
 *
 * ```
 * param 0x0000 = media item 组
 *   0x0001 title(string)      0x0004 duration(u32 ms)   0x0006 albumTitle(string)
 *   0x000C artist(string)     ... 0x001A artworkFileTransferIdentifier(u8) ...
 * param 0x0001 = playback 组
 *   0x0000 status(u8)         0x0001 elapsed(u32 ms)    0x0005 shuffle(u8)
 *   0x0006 repeat(u8)         0x0007 appName(string)
 * ```
 *
 * ⚠️ **历史 bug（2026-11-06 修）**：旧实现把专辑名发在 `0x001A`，而 0x001A 是
 * `media_item_artwork_file_transfer_identifier`（**封面传输标识符，单字节 u8**）。
 * 车机按 u8 解析该字段，`VirtualIap2Link.MIB3_NOW_PLAYING_UPDATE_IDLE` 里也是
 * `05001a8000`（1 字节数据）可证。我们把 NUL 结尾字符串写进去：专辑名为空时
 * 只发 1 字节 0x00，长度碰巧对得上、车机能勉强解析；专辑名非空时长度撑爆字段，
 * 后续参数边界全乱 → 整帧 0x5001 被判非法 → **车机仪表概率性不显示歌名/歌词**。
 * 正确 ID 是 `0x0006`。本项目不发封面，因此 0x001A 一律不写（写了等于向车机
 * 声明一个并不存在的 artwork 传输）。
 *
 * 数据源可以是 CarLife 内置播放器经 0x10035 推来的媒体信息，或系统媒体会话
 * （MediaSessionsState）——由各链路的 provider 决定。
 */
data class CarPlayNowPlaying(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Int,
    val status: Int,
    val elapsedMs: Int,
    val shuffleMode: Int = 0,
    val repeatMode: Int = 0,
    val appName: String?,
)

object Iap2NowPlaying {
    /** 播放状态枚举（与 iap2-rs PlaybackStatus 一致）。 */
    const val STATUS_STOPPED = 0
    const val STATUS_PLAYING = 1
    const val STATUS_PAUSED = 2

    // ---- media item 组字段 ID（勿凭记忆改，见类注释）----
    internal const val MEDIA_TITLE = 0x0001
    internal const val MEDIA_DURATION = 0x0004
    internal const val MEDIA_ALBUM = 0x0006
    internal const val MEDIA_ARTIST = 0x000C
    internal const val MEDIA_ARTWORK_ID = 0x001A

    // ---- playback 组字段 ID ----
    internal const val PLAYBACK_STATUS = 0x0000
    internal const val PLAYBACK_ELAPSED = 0x0001
    internal const val PLAYBACK_SHUFFLE = 0x0005
    internal const val PLAYBACK_REPEAT = 0x0006
    internal const val PLAYBACK_APP_NAME = 0x0007

    /**
     * 构造 0x5001 NowPlayingUpdate 完整帧（带真实歌名/歌手/专辑/播放状态）。
     * 空字符串字段仍给一个空串 param，保持结构与空态常量同构。
     */
    fun encode(np: CarPlayNowPlaying): ByteArray = Iap2Csm.encode(Iap2MessageIds.NOW_PLAYING_UPDATE, body(np))

    /**
     * 只返回**参数体**（不含 `0x4040 | len | msgId` 帧头）。
     *
     * 走 `send(...)/sendFireAndForget(...)` 这类「按消息 ID 发体」的链路时必须用它：
     * 那些方法会自己补帧头，把 [encode] 的完整帧当体再封一次，对端解析出来就是
     * `0x4040 0x5001 ...` 这种非法参数组。WinPlay 1.2.4 实测：收到双重封包的 0x5001
     * 后其「接收进程异常」直接退出 → 蓝牙桥接被拆 → 车机不停重连（2026-10-25）。
     */
    fun body(np: CarPlayNowPlaying): ByteArray {
        val mediaItem =
            Iap2Csm.string(MEDIA_TITLE, np.title.orEmpty()) +
                Iap2Csm.u32(MEDIA_DURATION, np.durationMs.toLong()) +
                Iap2Csm.string(MEDIA_ALBUM, np.album.orEmpty()) +
                Iap2Csm.string(MEDIA_ARTIST, np.artist.orEmpty())
        val playback =
            Iap2Csm.u8(PLAYBACK_STATUS, np.status) +
                Iap2Csm.u32(PLAYBACK_ELAPSED, np.elapsedMs.toLong()) +
                Iap2Csm.u8(PLAYBACK_SHUFFLE, np.shuffleMode) +
                Iap2Csm.u8(PLAYBACK_REPEAT, np.repeatMode) +
                Iap2Csm.string(PLAYBACK_APP_NAME, np.appName.orEmpty())
        return Iap2Csm.param(0x0000, mediaItem) + Iap2Csm.param(0x0001, playback)
    }

    /** 诊断用：这一帧实际带了哪些字段（日志里一眼看出专辑有没有发出去）。 */
    fun describe(np: CarPlayNowPlaying): String =
        buildList {
            add("title")
            add("album@0x0006")
            add("artist")
            add("duration")
            add("elapsed")
            add("status")
        }.joinToString(",")
}
