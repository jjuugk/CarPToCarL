package io.github.shaonialife.carptocar.carlife

/**
 * 保活 MediaSession 的假媒体信息过滤。
 *
 * ## 为什么要挡
 *
 * 前台服务为防国产 ROM 冻结，维持一条 `MediaSession(this, "CarPToCarL")`，元数据写死
 * 歌名「车机音频直通」、歌手「CarPToCarL」（见 `CarPlayService.syncKeepAliveSession`）。
 * 这条会话会被 CarLife 引擎当成当前媒体源读回来，经 `onMediaInfo` 进入我们的媒体信息管线：
 *
 * ```
 * 14:12:07.128  CARLIFE: 媒体信息 song=车机音频直通 artist=CarPToCarL art=3377B
 * 14:12:07.129  EVENT: CarLife 歌名「车机音频直通」→ 补推 iAP2 0x5001 NowPlayingUpdate
 * ```
 *
 * 后果：车机歌名/歌词基线被假歌名顶掉（媒体快照中的真实曲目一度是它，
 * `NowPlayingSourcePolicy` 再也判不出「MediaSession 与 CarLife 是否同一首歌」），
 * 时间戳也被污染成「引擎还在推送」的假象。
 *
 * ## 为什么这里挡
 *
 * `MediaListenerService` / `MediaSessionFallback` 已经按包名过滤了 MediaSession 列表，
 * 但**拦不住引擎回调这条路**——引擎读回来的是普通 `CarLifeMediaInfo`，没有包名。
 * 所以在 `CarLifeHijack.onMediaInfo` 最前端挡一次，下游全部受益。
 *
 * ## 判据为什么取「歌名 + 歌手」双条件
 *
 * 只判歌名怕误伤真实歌曲（用户完全可能播一首叫《车机音频直通》的歌）；
 * 只判歌手也太宽。两个字段同时命中才挡，且大小写不敏感、去首尾空格。
 */
object KeepAliveMediaFilter {
    private const val FAKE_SONG = "车机音频直通"
    private const val FAKE_ARTIST = "CarPToCarL"

    fun isKeepAliveInfo(info: CarLifeMediaInfo): Boolean =
        info.song.trim().equals(FAKE_SONG, ignoreCase = true) &&
            info.artist.trim().equals(FAKE_ARTIST, ignoreCase = true)
}
