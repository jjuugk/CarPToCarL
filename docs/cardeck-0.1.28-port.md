# 上游 cardeck 0.1.28 移植记录（2026-10-27）

对照文档：`cardeck/docs/carplay-connection-compare-0.1.28.md`（本地 `09eebb0` → 远端 `503ed37`，相差 42 笔）。
本次只移植**与「连不上」直接相关、且在别克 E2LB / 凯迪拉克 CT5 上有对照证据**的四项，其余（WFI 入网分级、
0x4301 窗口保活、自动连接等）此前已在 bieke 分支各自实现过，不重复搬。

## 1. 能力清单从「硬门禁」退回「只记录」

**改动**：`Iap2SenderSession`

- `accessoryCanReceive()`（真不发）→ `recordUndeclaredReceive()`（**照发**，只记一条日志）。
  涉及 `0x4E0D`（无线可用）、`0x4E0C`（设备 UUID）、`0x4E0E`（传输标识）三条可选消息。
- WiFi 凭据分派改认 `ReceiverProfile.wifiGen`：只有 `NEW`（两清单合计 ≥10 项仍无 `0x57xx`，
  宝马 iD9 形态）才跳过 `0x5702`；`LEGACY`/`UNKNOWN` 一律发。

**为什么推翻自己（1.0.8 的裁剪）**：上游 0.1.28 的真车证据是「清单只能证明『有』，不能证明『没有』」——

- 奥迪 3315 的可收清单只有 5 项、不含 `0x5702`，但它完整支持 `0x5702→0x5703`，
  而且**要靠收到 0x5702 才把 AirPlay 服务拉起来**（旧实现按清单硬拦 → 7000 端口全程
  ECONNREFUSED ×45 → 全灭）；
- 同一天日志里 `0x4e0d/0x4e0b/0x4e0e` 不在清单内，车机却逐条 ACK 到位；
- 别克 E2LB 侧的对照同样无效：裁掉这三条之后车机仍是「ACK 后沉默」，
  说明它卡的不是「收到了多余帧」，而是应用层没被唤醒（见 `buick-e2lb-availability-silence.md`）。

`0x4300` 从来不在裁剪范围（它是驱动 `0x4301` 的请求本身），本次不变。

### 2026-11-10 更正：0x4E0D 单独改回「按声明裁」

上面三条依据里，**只有 `0x5702` 那条是真车实测**，它支撑的是「WiFi 凭据分派不能按清单硬拦」，
与本条无关。支撑 `0x4E0D` 的那条「不在清单内却逐条 ACK」**在协议上不成立**——
链路层 ACK 只表示帧的 CRC 校验通过并被收下，与业务层认不认这条消息无关。

决定性对照：

| 车机 | 蓝牙可收清单 | 含 `0x4e0d`? | 1.0.7 是否发 | 结果 |
|---|---|---|---|---|
| 奥迪 MMI 3232（AUDI 4930/DELPHI，唯一完整连通过的） | 7 项 | **在** | 发 | 通 |
| WinPlay / Roylyl（测试台） | 16 项 | 在 | 发 | 通 |
| 别克 E2LB | 9 项 | 不在 | 发 | ACK 完最后一帧后沉默 |
| 别克 BUICK_3138 | 9 项 | 不在 | 发 | 同上（2026-11-10 18:34 日志） |
| 凯迪拉克 CT5 | 9 项 | 不在 | 发 | 同上 |

即：**1.0.7 的连通性不能作为「未声明也照发」的证据**——奥迪清单里本来就有它，
发与不发在奥迪上是同一个行为。而按声明裁，对奥迪走 `wifiGen=LEGACY` 的非裁分支，
**逐字节不变**，风险只落在从未通过的 GM/Cinemo 一族的头上。

因此现在：`0x4E0D` 新协议车机不声明就不发；`0x4E0C`（设备 UUID，奥迪清单里也没有
但 1.0.7 一直发着且连通）与 `0x4E0E`（传输标识）**仍照发**，不动。

### 同日追加：新协议车机整体升格为严格模式

上面那条只处理了 `0x4E0D` 一条。用户随后定案：**新协议车机（`wifiGen=NEW`）在
可收清单里没有的，一律不发**，不再逐条论证。实现收口在
`AccessoryDeclaration.setStrictDeclaredOnly()`，由 `Iap2SenderSession` 出画像后设置。

- 放宽的代价只落在「从未连通过」的 GM/Cinemo 一族与宝马 iD9；
  奥迪走 LEGACY 分支，宽松档行为逐字节不变。
- 例外是**协议骨架** `0x1D00/0x1D01/0x1D02/0xAA00/0xAA02/0xAA04/0xAA05/0x4300`：
  真车清单里根本没有它们，裁掉就是「永不连接」，实验也拿不到信息。
- 于是别克 BUICK_3138 上 `0x4E0D` 与 `0x4E0E` 都不再发；`0x4E09/0x4E0A/0x4E0B/0x4E0C`
  在清单里，照发。

## 2. MFi 挑战长度记忆支持「硬拒绝」

**改动**：`MfiLengthMemory.recordHardRejected()` + `orderAttempts()`；`Iap2SenderSession.runMfiChallenge()`

- 挑战期间被对端断开 = 强证据：**即使该长度等于已记「成功」，也清 `succeeded` 并记 `rejected`**。
  原先的 `recordRejected()` 遇到「与成功档相同」会直接放弃更新，于是迟到应答的误记永远洗不掉。
- `orderAttempts()` 把「成功 ∧ 被拒」的矛盾档降级为普通候选（排在被拒档之前，仍会试到）。

**病因**（上游 DiPlay 0.1.24 实测）：32B 的迟到应答被记到 20B 头上 → 之后每轮 20B 都被断链、
记忆永久锁死、每轮死在发 `0xAA05` 之前。

## 3. 僵尸轮次释放（`onLinkClosed`）

**改动**：`BtSession.runSenderSession()` 保活泵退出后回调 `onLinkClosed` →
`Iap2BluetoothClient/Server` 透传 → `SessionController.onSenderLinkClosed()`

- 未推流且配对进行中：立即 `sessionStarting.compareAndSet(true, false)` + 置 `connectCancelled`，
  让卡在 WiFi 加入里的配对线程 ≤200ms 退场；**不停 BT 组件**（车机会立刻回连）。
- `onCarPlayEndpoint()` 抢到互斥后清 `connectCancelled`（属于已退场旧轮次）。

**病因**：车机 15s 看门狗拆链 → 立刻回连并重走 Phase 1 → 新端点被上一轮「已有会话在处理中」拒绝
→ 只剩 keep-alive 干等到用户放弃。别克 E2LB 日志里每 ~40 秒一轮的循环正是这条死锁链。

## 4. `TcpLiveness`（长连接健康检查）

**新增**：`net/TcpLiveness.kt`；接入三处

| 位置 | label |
| --- | --- |
| `AirPlayClient` 控制通道 | 控制通道 |
| `AirPlayClient` 事件通道 | 事件通道 |
| `VideoSender.openDataPort()` / `reconnectDataPort()` | 视频数据口 |

参数 `keepalive idle=10s / interval=3s / count=3` + `TCP_USER_TIMEOUT=20s`（约 19s 判定对端死亡）。
内核默认要 **2 小时**才探测：车机熄火 / 走出热点时链路常不发 RST，读线程会一直挂着——画面冻结、
会话看门狗不触发。19s 刻意大于车机 15s 的 AirPlay 看门狗，避免我们比车机更早判死正常会话。

CarLife 侧（`CarLifeHuClient`）连的是本机端口，不涉及网络闪断，故不设。

## 验证

- `./gradlew testDebugUnitTest`：**208 用例全绿**（新增 `hardRejectOverturnsSucceededMemory`、
  `contradictorySuccessAndRejectIsDemotedToNormal`；Cadillac mock 的
  「禁止消息断言」改为「必须照发断言」`assertUndeclaredFramesStillSent`）。
  2026-11-10 起该断言改名为 `assertUndeclaredFramesCut`（严格模式下两条都必须不发）。
- `./gradlew assembleDebug` → `adb install -r` → 启动无崩溃，界面正常，自动连接已按预期进入
  「蓝牙就绪，等待车机回连…」。
- **待真车验证**：别克 E2LB 上车看三点——① 日志出现 `车机可收清单未声明 0x4e0d/0x4e0e ……仍照常发送`；
  ② 断链后不再出现「已有会话在处理中，忽略本次端点」；③ 熄火后画面冻结能在 ~20s 内被判定断链。

---

## 附：1.0.12「音频隔离」回退（2026-10-27）

**现象**：1.0.11 装机后，车机上地图导航播报无声（画面正常）。

**先查证据：音频代码到底有没有被这轮改动碰过**

```
git log --oneline abd1cf9..HEAD            # main → bieke 共 9 笔
git log --oneline abd1cf9..HEAD -- app/src/main/java/io/github/shaonialife/carptocar/audio \
                                   app/src/main/java/io/github/shaonialife/carptocar/carlife/CarLifeAudioSink.kt
# → 空
```

结论：`audio/` 全目录与 `CarLifeAudioSink` 在这 9 笔里**零改动**，1.0.11 与 1.0.7(main)
的音频代码逐字相同。音频数据面是 `DatagramSocket`（UDP RTP），与 TCP keepalive 无关。

**但有一处物理交集，已撤**

1.0.11 给 AirPlay **控制通道 / 事件通道**加了 `TcpLiveness`（keepalive 10s/3s/3 +
`TCP_USER_TIMEOUT=20s`）。音频包本身是 UDP，但下面这些都跑在控制通道上：

- 音频 `SETUP`（协商 dataPort / controlPort / streamConnectionID）
- `SET_PARAMETER volume`（探测与修正静音位）
- `feedback` 心跳（车机据此排播）

所以「socket 级超时可能影响音频通路建立/维持」是唯一站得住的怀疑链，已在 1.0.12 撤回：
**控制通道与事件通道恢复为 1.0.10 的原始设置**（`soTimeout` 分别为 0 / 30s，无内核级超时）。
视频数据口的 keepalive 保留（纯视频单向写，与音频无交集）。

**版本**：1.0.12（10012），208 用例全绿，`dist/CarPToCarL-1.0.12-audio-safe.apk`。

**若复测仍无声**，按 `docs/audio-call-issues.md` 的三行日志判读，不要再猜代码：

1. `AIRPLAY: ✓ 音频流协商成功 … dataPort=…` —— 没有则音频 SETUP 被拒（换档位问题）；
2. `AUDIO: 首帧` 与 `CARLIFE: 媒体 音频格式宣告` —— 没有则 CarLife 引擎没推 PCM（导航播报不一定能唤醒引擎）；
3. `CARLIFE: 音乐会话暂停——丢弃引擎残留 PCM` 连续出现 —— 导航被 15s 暂停窗口吞掉（那才需要动音频代码）。
