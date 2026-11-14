# 车机上的「歌词」：iAP2 没有歌词字段，只能顶替 title

> 2026-11-14 结论。起因：奥迪 MIB3（`Audi_MMI_3232` / `AUDI 4930` / DELPHI）会话已显示歌名、
> 画家、封面，但用户问「为什么没有歌词」。

## 一、iAP2 侧：不存在歌词通道

`0x5001 NowPlayingUpdate` 的 media item 组共 17 个字段（`catplay` 的
`core/catplay_csm/src/msg/iap2.rs:1950-2003`，与本仓库
`iap2/Iap2NowPlaying.kt:59-71` 的常量一致）：

| 字段 | 含义 | 字段 | 含义 |
|---|---|---|---|
| 0x0000 | mediaItemPersistentIdentifier | 0x000C | **artist** |
| 0x0001 | **title** | 0x0010 | genre（流派） |
| 0x0004 | duration | 0x0012 | composer（作曲） |
| 0x0006 | albumTitle | 0x001A | artworkFileTransferIdentifier |
| 0x0007/0x0008/0x0009/0x000A | 音轨号/碟号 | 0x0015~0x0018 | isLiked/isBanned 等 |

playback 组只有 status / elapsed / shuffle / repeat / appName。**两组都没有 lyric**；
`.tmp/mfi/carplay.txt`（Apple 规范摘录）里 `grep -i lyric` 同样是 0 命中。

所以「打开某个开关让车机显示歌词」在 CarPlay 侧**不存在**：歌词唯一能出场的形状是
**顶替 title**，逐行替换。

> 反面验证：同一台车机的 `0x5000` 订阅点名了 7 个 media item 字段
> （`0x0001/0x0004/0x0006/0x000C/0x0010/0x0012/0x001A`），看着多出来两个，
> 但那两个是**流派与作曲者**，不是歌词。

## 二、CarLife 侧：歌词有，但走 `0x10035`，而且只由内置播放器推

`CarlifeMediaInfo`（`CarLifeProtocol.kt:752-763`）字段为
`source/song/artist/album/albumArt/duration/playlistNum/songId/mode`——同样没有 lyric。
歌词是一整条**额外的媒体信息**：

```
真实歌曲：song=总有春风为你而来 (女版)  artist=悠禾                       album=总有春风为你而来 (女版)
歌词行：  song=要相信所有缘分上天         artist=总有春风为你而来 (女版)-悠禾  album=总有春风为你而来 (女版)
```

判据：`artist == "<album>-<艺术家>"`，即 `LyricLines.isLyricLine()`。真实歌曲不会长成
这个形状，所以对正常歌名零误伤。

**关键限制**：`0x10035` 由 CarLife **内置播放器**的 `sendMusicInfoToVehicle` 发出。
第三方播放器（QQ音乐 / 酷狗 / 网易云）不会触发它——歌名只能从系统媒体会话拿，
而那条源只有「播放器自己把歌词写进 title」时才有歌词（荣耀音乐会这么做，
见 `SessionController` 里 `pushNowPlayingAll()` 的去重注释）。

2026-11-14 21:35 的日志（`CarPlaySender-log-20261114-213545.txt`）里
`grep -c "CARLIFE: 媒体信息"` = **0**：整轮 66 秒只有视频帧和 PCM，
没有一条媒体信息。歌名 `相思遥 - 魏玉慧` / `侧脸 - 于果` 全部来自 MediaSession。
也就是说那一轮**不是我们丢了歌词，是歌词从没送到**。

## 三、我们怎么处理（1.2.43）

1. `LyricLines`（carlife 包）：识别歌词行，`trueArtist()` 剥掉 `"<专辑>-"` 前缀。
2. `CarLifeHijack`：`latestSongMediaInfo`（最近一条**非**歌词行）+ `latestMediaInfoAtMillis`
   （到达时刻）——歌词行不覆盖歌名基线。
3. `NowPlayingSourcePolicy.shouldShowCarLifeLyric()`：三条门全过才让歌词顶掉歌名。
   - 确实是歌词行；
   - `now - latestMediaInfoAtMillis <= 5s`（CarLife 还在推，暂停/切源后 5 秒内让位）；
   - 两边仍是同一首歌（MediaSession 的 title/artist 与 CarLife 的非歌词基线一致）。
4. `SessionController.buildNowPlaying()`：门通过就用 `carLifeNowPlaying()` 构造 0x5001，
   **画家/专辑/时长取真实歌曲那条**（否则车机画家栏会出现 `专辑-画家` 拼接串），
   封面键按「专辑|画家」归一化 → 歌词行共用同一个 id，不会每行重传封面。

## 四、已知代价（产品取舍，不是 bug）

- 歌机 NowPlaying 卡只有一行 title，歌词滚动时**看不到歌名**。参考项目 1.0.6 因此反过来
  把歌词行过滤掉了。要「同时看到歌名与歌词」需要车机自带歌词控件，MIB3 不接受。
- 只有 **CarLife 内置播放器**放歌才有歌词。第三方播放器仍只能看歌名。
- 每行 0x5001 ≈ 100B，1~2 行/秒；同一会话里视频就有 2.0 Mbps，量可忽略。
