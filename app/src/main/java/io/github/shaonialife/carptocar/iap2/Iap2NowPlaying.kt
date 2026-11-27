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
 * 正确 ID 是 `0x0006`。0x001A **只在真的要把封面传过去时才写**——它写出去就等于
 * 向车机声明「有个编号为 N 的 artwork 会跟着来」，车机随后会在文件传输会话
 * （session 12）上等这张图。编号给了却不传，车机可能一直等、或者把媒体卡上的
 * 封面位留空（[Iap2ArtworkTransfer] 是配套的传输实现）。
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
    /**
     * 封面传输标识符（1..255；null = 本轮没有封面）。
     *
     * 换一张图就要换一个 id —— 车机按 id 做缓存，同一个 id 不会重新要图，
     * 换了 id 才会重新走 SETUP/START/分块那一套。
     */
    val artworkId: Int? = null,
    /** 本地传输策略标记，iAP2 编码不包含此字段。 */
    internal val navigationArtwork: Boolean = false,
    /**
     * 曲目持久 ID（组 0 字段 0x0000，u64）。来自 CarLife 的 `songId`。
     *
     * 它标识「这是哪一首」：同一首歌的连续更新共用同一个 ID，车机据此判断
     * 是「同一条内容刷新」还是「换曲」。null = 没有可靠值，不发。
     */
    val itemId: Long? = null,
    /** 队列长度（组 1 字段 0x0003，u32）。来自 CarLife 的 `playlistNum`。 */
    val queueCount: Int? = null,
    /** 组 0 字段 0x0007 / 0x0008 / 0x0009：曲目号、曲目总数、碟号。 */
    val trackNumber: Int? = null,
    val trackCount: Int? = null,
    val discNumber: Int? = null,
    /** 组 0 字段 0x0010 / 0x0012：流派、作曲者。 */
    val genre: String? = null,
    val composer: String? = null,
    /** 组 1 字段 0x0010：应用包名（车机据此认播放器）。 */
    val appBundleId: String? = null,
    /** 播放器的可读名称；只在带 [appBundleId] 时才可能替换 [appName]。 */
    val appDisplayName: String? = null,
    /**
     * 「真实曲目 + 已确认属于它的歌词行」。
     *
     * iAP2 没有歌词字段，歌词只能顶替 title；但**新曲刚起时先给歌名/歌手**，
     * 停留一段时间再切歌词，否则首句歌词会抢掉曲目信息（见
     * [io.github.shaonialife.carptocar.airplay.VirtualIap2Link]）。
     */
    internal val lyricTrack: LyricTrack? = null,
    /** 导航覆盖层：不参与歌词分阶段（它的 title 是转向文案，不是歌名）。 */
    internal val navigationOverlay: Boolean = false,
) {
    /** 已校验属于当前曲目的歌词行。 */
    data class LyricTrack(
        val title: String?,
        val artist: String?,
        val line: String?,
    )
}

object Iap2NowPlaying {
    /** 播放状态枚举（与 iap2-rs PlaybackStatus 一致）。 */
    const val STATUS_STOPPED = 0
    const val STATUS_PLAYING = 1
    const val STATUS_PAUSED = 2

    // ---- media item 组字段 ID（勿凭记忆改，见类注释）----
    internal const val MEDIA_ITEM_PERSISTENT_ID = 0x0000
    internal const val MEDIA_TITLE = 0x0001
    internal const val MEDIA_DURATION = 0x0004
    internal const val MEDIA_ALBUM = 0x0006
    internal const val MEDIA_TRACK_NUMBER = 0x0007
    internal const val MEDIA_TRACK_COUNT = 0x0008
    internal const val MEDIA_DISC_NUMBER = 0x0009
    internal const val MEDIA_ARTIST = 0x000C
    internal const val MEDIA_GENRE = 0x0010
    internal const val MEDIA_COMPOSER = 0x0012
    internal const val MEDIA_ARTWORK_ID = 0x001A

    // ---- playback 组字段 ID ----
    internal const val PLAYBACK_STATUS = 0x0000
    internal const val PLAYBACK_ELAPSED = 0x0001
    internal const val PLAYBACK_QUEUE_COUNT = 0x0003
    internal const val PLAYBACK_SHUFFLE = 0x0005
    internal const val PLAYBACK_REPEAT = 0x0006
    internal const val PLAYBACK_APP_NAME = 0x0007
    internal const val PLAYBACK_APP_BUNDLE_ID = 0x0010

    /** 组号：0 = media item，1 = playback。 */
    const val GROUP_MEDIA_ITEM = 0
    const val GROUP_PLAYBACK = 1

    /**
     * 构造 0x5001 NowPlayingUpdate 完整帧（带真实歌名/歌手/专辑/播放状态）。
     * 空字符串字段仍给一个空串 param，保持结构与空态常量同构。
     */
    fun encode(
        np: CarPlayNowPlaying,
        requestedFields: Map<Int, Set<Int>>? = null,
    ): ByteArray = Iap2Csm.encode(Iap2MessageIds.NOW_PLAYING_UPDATE, body(np, requestedFields))

    /**
     * 只返回**参数体**（不含 `0x4040 | len | msgId` 帧头）。
     *
     * 走 `send(...)/sendForget(...)` 这类「按消息 ID 发体」的链路时必须用它：
     * 那些方法会自己补帧头，把 [encode] 的完整帧当体再封一次，对端解析出来就是
     * `0x4040 0x5001 ...` 这种非法参数组。WinPlay 1.2.4 实测：收到双重封包的 0x5001
     * 后其「接收进程异常」直接退出 → 蓝牙桥接被拆 → 车机不停重连（2026-10-25）。
     *
     * @param requestedFields 车机 `0x5000` 订阅里点名的字段（组号 → 字段 ID）。
     *   **null / 空 = 没解析到掩码**：此时一个可选字段都不加，报文字节与旧版完全一致，
     *   已跑通车型（宝马 MGU、DiPlay、WinPlay）不会被改变。只有车机明确订阅了某字段，
     *   我们才补它——「按能力不按型号」。
     */
    fun body(
        np: CarPlayNowPlaying,
        requestedFields: Map<Int, Set<Int>>? = null,
    ): ByteArray {
        fun wants(
            group: Int,
            field: Int,
        ): Boolean = requestedFields?.get(group)?.contains(field) == true

        var mediaItem = byteArrayOf()
        np.itemId?.takeIf { wants(GROUP_MEDIA_ITEM, MEDIA_ITEM_PERSISTENT_ID) }?.let {
            mediaItem += Iap2Csm.u64(MEDIA_ITEM_PERSISTENT_ID, it)
        }
        mediaItem +=
            Iap2Csm.string(MEDIA_TITLE, np.title.orEmpty()) +
                Iap2Csm.u32(MEDIA_DURATION, np.durationMs.toLong()) +
                Iap2Csm.string(MEDIA_ALBUM, np.album.orEmpty())
        np.trackNumber?.takeIf { wants(GROUP_MEDIA_ITEM, MEDIA_TRACK_NUMBER) }?.let {
            mediaItem += Iap2Csm.u16(MEDIA_TRACK_NUMBER, it)
        }
        np.trackCount?.takeIf { wants(GROUP_MEDIA_ITEM, MEDIA_TRACK_COUNT) }?.let {
            mediaItem += Iap2Csm.u16(MEDIA_TRACK_COUNT, it)
        }
        np.discNumber?.takeIf { wants(GROUP_MEDIA_ITEM, MEDIA_DISC_NUMBER) }?.let {
            mediaItem += Iap2Csm.u16(MEDIA_DISC_NUMBER, it)
        }
        mediaItem += Iap2Csm.string(MEDIA_ARTIST, np.artist.orEmpty())
        np.genre?.takeIf { it.isNotBlank() && wants(GROUP_MEDIA_ITEM, MEDIA_GENRE) }?.let {
            mediaItem += Iap2Csm.string(MEDIA_GENRE, it)
        }
        np.composer?.takeIf { it.isNotBlank() && wants(GROUP_MEDIA_ITEM, MEDIA_COMPOSER) }?.let {
            mediaItem += Iap2Csm.string(MEDIA_COMPOSER, it)
        }
        // 只有真要传图时才声明编号：给了编号却不传，车机封面位会一直等一张永不
        // 到达的图。合法性按 u8 校验，越界（0 或 >255）一律当「没有封面」处理。
        np.artworkId?.takeIf { it in 1..0xFF }?.let { mediaItem += Iap2Csm.u8(MEDIA_ARTWORK_ID, it) }
        var playback =
            Iap2Csm.u8(PLAYBACK_STATUS, np.status) +
                Iap2Csm.u32(PLAYBACK_ELAPSED, np.elapsedMs.toLong())
        np.queueCount?.takeIf { it > 0 && wants(GROUP_PLAYBACK, PLAYBACK_QUEUE_COUNT) }?.let {
            playback += Iap2Csm.u32(PLAYBACK_QUEUE_COUNT, it.toLong())
        }
        playback +=
            Iap2Csm.u8(PLAYBACK_SHUFFLE, np.shuffleMode) +
                Iap2Csm.u8(PLAYBACK_REPEAT, np.repeatMode) +
                Iap2Csm.string(PLAYBACK_APP_NAME, np.appName.orEmpty())
        np.appBundleId
            ?.takeIf { it.isNotBlank() && wants(GROUP_PLAYBACK, PLAYBACK_APP_BUNDLE_ID) }
            ?.let { playback += Iap2Csm.string(PLAYBACK_APP_BUNDLE_ID, it) }
        return Iap2Csm.param(GROUP_MEDIA_ITEM, mediaItem) + Iap2Csm.param(GROUP_PLAYBACK, playback)
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
            np.artworkId?.let { add("art#$it") }
        }.joinToString(",")
}
