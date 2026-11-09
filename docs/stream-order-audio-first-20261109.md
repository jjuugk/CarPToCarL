# 音频 SETUP 与 RECORD 的先后：按接收端记忆（issue #14，2026-11-09）

来源：仓库 issue **#14**「无线 CarPlay 接收端（JYBOX-80 / Magic-Car-Link）在「音频流后补
RECORD」时被回 500，整轮被拆」。现场是 vivo X Fold3 + JYBOX-80（`/info`：model=
`Magic-Car-Link-1.00`、manufacturer=`Magic Tec.`、固件 2024.03.21）+ 沃尔沃 Sensus。

## 1. 现象

```
[21:32:34.253] AIRPLAY: ✓ RECORD 完成——会话已激活          ← 第一次 RECORD（仅视频）200
[21:32:34.791] AIRPLAY: ✓ 音频流协商成功 dataPort=34791 …
[21:32:34.919] AUDIO: 音频流已加入——补发 RECORD 激活（流后加入会话）
[21:32:34.927] AIRPLAY: RECORD 应答 [RTSP/1.0 500 Internal Server Error]
→ 盒子关闭 AirPlay：0x4301 port=0，mDNS 消失
```

表现为「连上 → 出过一瞬间画面 → 黑屏 / 回连接页」，且**不是每次都能救回来**：接收端
自己把 AirPlay 服务关了，之后 `0x4301` 的 `port=0`、mDNS 也扫不到，只能等它自己重启服务。

## 2. 两种顺序，各有各的死法

| 顺序 | 步骤 | 谁需要它 | 谁会被它搞死 |
|---|---|---|---|
| **RECORD 优先**（原默认） | 屏幕 SETUP → 连数据口 → RECORD → 起编码器 → 音频 SETUP → **补发 RECORD** | 奥迪 MIB3 系（2026-10-17 定谳） | JYBOX-80 这类：补发 RECORD 直接 500 |
| **音频优先**（本次新增） | 屏幕 SETUP → 连数据口 → 音频 SETUP → **一次 RECORD** 同时激活 | JYBOX-80（实车两轮均 200） | 奥迪 MIB3：音频 SETUP 要 ~2.1s，超 2s 看门狗 → 拆链 |

两条都是实车结论，谁也不能当全局默认：

- 奥迪那条的日志特征是 `音频 SETUP 往返 2091ms` 与 `EVENT 读取结束 - Connection reset`
  落在同一毫秒，5 次实测全部复现；
- issue #14 那条，把音频 SETUP 提到 RECORD 之前，「同一盒子连跑两轮都是 200」，
  且日志里出现 `音频 SETUP 往返 24ms`（不等 RECORD 就不必等车机侧音频通路初始化）。

## 3. 判定：两层，默认不动

新增 `airplay/StreamOrderMemory`（纯逻辑 + SharedPreferences，按接收端身份存一行）。

1. **型号快路径**：`magic-car-link` / `jybox` 子串命中 → 音频优先，省掉第一轮失败。
   名单只放实车证实过的型号——名单外的宁可先按默认走一轮。
2. **学出来的结论**：默认档那一轮里，若「音频 SETUP 之后补发的 RECORD」返回非 200，
   把这台接收端记成音频优先，下一轮自动换序。代价是一轮失败（这台接收端本来也会因 500
   作废），换来之后都不再踩。
3. **反向纠正**：音频优先那一轮若连第一次 RECORD 都非 200，说明不是「补发 RECORD 过敏」，
   记回 RECORD 优先并**压过型号快路径**（`Learned.RECORD_FIRST` 优先级最高），避免被
   白名单永久锁在错误顺序上。

`receiverKey` 与 `AudioProfileMemory.receiverKey` 同口径（manufacturer + model 归一化），
同一份 `/info` 文本只算一个键。

## 4. 落地细节

- 音频 SETUP 的唯一入口收敛成 `SessionController.startAudioChannelAndLog()`：里面先等
  隧道控制会话（MFi）就绪再协商，两条顺序共用同一份等待逻辑（原来那段日志只写在一个分支）；
- 音频优先档**刻意跳过 MIB3 的二次激活**：那段逻辑会在控制会话迟到时 `assertModes()` +
  再发一次 RECORD，正好是这类接收端最怕的动作。这台设备不是 MIB3，跳过不损失什么；
- 日志三行可判读：
  ```
  STREAM: 顺序 = 音频优先：先 SETUP 音频，再一次 RECORD 同时激活 · 依据=已知型号快路径 · 接收端=Magic Tec. Magic-Car-Link-1.00
  AUDIO: 音频已随本次 RECORD 一同激活（未补发 RECORD）
  STREAM: 补发 RECORD 被拒——已记住该接收端要用音频优先，下一轮改为「先 SETUP 音频再一次 RECORD」
  ```

## 5. 没做的

- 轮内换序（补发 RECORD 拿到 500 之后当场改发一次「音频 SETUP + RECORD」）：接收端此时
  已经把 AirPlay 服务关了，轮内做任何事都没有对端，只会把失败拖长；
- 不按 `manufacturer` 字符串硬拦：`Magic Tec.` 也做别的盒子，判据取型号子串 + 学出来的结论。

## 6. 实车验证要点

1. JYBOX-80：第一轮日志应有 `STREAM: 顺序 = 音频优先…依据=已知型号快路径`、紧接着的
   `AUDIO: 音频已随本次 RECORD 一同激活（未补发 RECORD）`，**不再出现**「补发 RECORD」；
2. 奥迪 MIB3：日志仍是 `STREAM: 顺序 = RECORD 优先…`，`补发 RECORD 激活（流后加入会话）`
   照旧出现，画面/声音行为零变化；
3. 陌生接收端若第一次失败，第二轮应自动变成 `依据=本机记忆（上次补发 RECORD 被拒）`。
