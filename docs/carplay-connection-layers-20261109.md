# CarPlay 连接的分层结构（2026-11-09，对应 1.2.32）

> 写给「要改连接/排查连不上」的人。线格式（FF5A 帧、CSM 分帧、TLV）见
> [PROTOCOL-NOTES.md](PROTOCOL-NOTES.md)；本文只讲**分层职责、代码入口、失败判据**——
> 出了问题先定层，再进那一层看日志，不要从报头往下通读。
>
> 一句话：本 app 用一台未 root 的 Android 手机**冒充 iPhone（CarPlay 发送端）**，
> 把本机 CarLife+ 引擎的 H.264 码流直通转发给车机，不重编码、不需要 Shizuku、
> 也不需要任何 Apple 签名材料（iAP2 认证是单向的，车机验证手机、手机不回验）。

## 1. 总览：五层

```
┌──────────────────────────────────────────────────────────────┐
│ L5 应用与编排   SessionController / CarPlayService            │  谁持有管线、阶段计时、
│                 日志三路、看门狗、通知栏                        │  什么时候放弃
├──────────────────────────────────────────────────────────────┤
│ L4 AirPlay 会话 /info → /pair-setup → /pair-verify             │  「投屏协议」本体
│                 → SETUP + RECORD（屏幕 110 / 音频 100·102）    │  加密在这里建立
├──────────────────────────────────────────────────────────────┤
│ L3 iAP2 控制面  RFCOMM 首轮：0x1D00…/0xAA00…/0x5703/0x4301    │  拿凭据、拿端点、
│                 事件通道上的虚拟 iAP2 隧道（会话期内维持身份）  │  维持「我还是一台 iPhone」
├──────────────────────────────────────────────────────────────┤
│ L2 网络与发现   车机热点静默入网（复用→specifier→建议）        │  让双方互相看得见
│                 双向 mDNS：_carplay-ctrl（手机）/ _airplay（车机）│
├──────────────────────────────────────────────────────────────┤
│ L1 蓝牙链路     手机主动连车机 RFCOMM（…decafdecacaff）         │  谁先动手
│                 建链期踢掉 HFP/A2DP（车机见到就不开窗口）        │
└──────────────────────────────────────────────────────────────┘
```

| 层 | 只负责一件事 | 主要代码 | 挂了的表现 |
|---|---|---|---|
| L1 蓝牙 | 建立第一条可承载 iAP2 的链路 | `bt/Iap2BluetoothClient`、`bt/Iap2BluetoothServer`、`bt/BtProfiles` | 车机侧根本不出现「已连接」 |
| L2 网络与发现 | 让手机与车机在同一网段并互相发现 | `net/WifiCarPlayConnector`、`net/CarPlayServiceBrowser`、`net/CarPlayCtrlAdvertiser` | 卡在「等车机连接信息」，`7000` 连续 `ECONNREFUSED` |
| L3 iAP2 | 拿热点凭据、拿 AirPlay 端点、维持控制会话 | `iap2/Iap2SenderSession`、`airplay/VirtualIap2Link`、`iap2/Iap2LinkEngine` | 连上后 10~12 秒自动断开 |
| L4 AirPlay | 配对、建流、开加密 | `airplay/AirPlayClient`、`airplay/PairingClients` | 有链路无画面；配对被拒；补发 RECORD 500 |
| L5 编排 | 串起上面四层、计时、重试、恢复 | `SessionController`、`service/CarPlayService` | 各层都对但整体不收敛（重试/取消/预算问题） |

## 2. L1 蓝牙链路：谁先动手

真车（奥迪 MIB3 实车验证）的方向是**手机主动连车机**：

```
手机 ──RFCOMM──▶ 车机 SDP 配件服务 00000000-deca-fade-deca-deafdecacaff
车机 ✗ 从不回连手机（回连是 DiPlay/xcertplay 测试台的测试行为，UUID …cafe）
```

两条入口同时开着：`Iap2BluetoothClient`（真车，主动连）、`Iap2BluetoothServer`
（测试台，等回连）。**测试台能连不代表真车能连**，这是两个方向。

一个反直觉的前置条件：**车机只要看到 HFP/A2DP 还在线就不开放 CarPlay 窗口**，
而用户断开后它自己又会把配置文件拉回来。所以整段建链期有
`SessionController.startAudioProfileGuard()` → `BtProfiles.guardAudioProfiles()`
反复把音频配置文件踢下去，直到 iAP2 会话建立或用户断开。

- 日志：`BT-PROFILE: 连接前「<车型>」的蓝牙配置文件状态=…`（建链前的快照，失败时先看它）
- 相关文档：[car-approach-autostart.md](car-approach-autostart.md)（靠近自动启动）、
  [citroen-r4-sdp-no-service.md](citroen-r4-sdp-no-service.md)（SDP 无服务）

## 3. L2 网络与发现：怎么互相看见

**入网按「打扰程度」分三层，永远从最安静的一层开始**
（2026-11-08 实车纠正过一次，别改回去）：

| 顺位 | 做法 | 要不要人 | 代码 |
|---|---|---|---|
| 1 复用 | 系统已连在该热点（已保存/上轮残留/建议自动关联）→ 只 `bindProcessToNetwork` | 零交互 | `WifiCarPlayConnector.adoptIfAlreadyConnected` |
| 2 specifier | 直接发 `WifiNetworkSpecifier`；系统对该热点已有授权记录时**静默满足** | 零交互 | `joinCarPlayNetwork` |
| 3 建议 | 登记 `WifiNetworkSuggestion` 等系统自己关联 | 零交互 | `joinSilentlyBySuggestion` |

硬约束：**整条链路不得出现任何自家弹窗**；失败只记日志、自动重试 2 次
（`SilentJoinPolicy`：建议连续 2 次不成就不再白等，避免吃掉车机 27 秒的等待窗口）。

发现是**双向**的，缺一半就互等：

```
手机 ──广播 _carplay-ctrl._tcp──▶ 车机
        （TXT srcvers 必须 950.7.1、实例名 sender；改了车机连探测都不发）
车机 ──GET /ctrl-int/1/connect──▶ 手机   探测通过才会下发 0x4301
车机 ──广播 _airplay._tcp──▶ 手机        TXT: deviceid / features / pk / pi / model
```

`pi` 是接收端持久标识——**同一台车机的 `pi` 变了，等于它的 AirPlay 服务进程重启过**，
这是判断「车机被打崩」最硬的一条证据。

- 代码：`net/WifiCarPlayConnector`、`net/CarPlayServiceBrowser`（NSD）、
  `net/CarPlayCtrlAdvertiser`（手机侧服务端，应答车机探测）、`net/WifiLinkWatchdog`
- 日志：`NSD: 发现服务 / 服务消失`、入网耗时、`STREAM/端点` 刷新

## 4. L3 iAP2 控制面：拿凭据、拿端点

RFCOMM 上先跑一轮完整会话（真车验证过的顺序，缺一步都不走）：

```
0x1D00 → 0x1D01 → 0x1D02     识别（0x1D01 里带车机的「可收消息清单」→ AccessoryDeclaration）
0xAA00 → 0xAA01 → 0xAA02     请求证书 / 挑战
0xAA03 → 0xAA05              应答 / 认证通过（跳过证书与签名校验）
[车机 5 个订阅帧 + 0x6800 StartHID]  → 回 0x6801
0x5702 → 0x5703              热点 SSID / 密码 / 信道
0x4300 → 0x4301              AirPlay 端点 / 公钥 / 无线参数
0x4E0D + 0x4E0E              无线可用 + 传输标识（本机真实蓝牙 MAC）
```

关键约束：

- **收到 0x5703 必须立刻入网并广播 ctrl 服务**。等 0x4301 之后再广播 = 双方互等死锁。
- **0x4301 可能晚到**（奥迪 MMI 0000 实测晚 2 分 35 秒）：保活泵收到就要交给编排层热更新
  端点，否则车机等 15 秒无人连入便拆整条 RFCOMM，用户看到的是「连上之后自动断开」。
- **`0x1D01` 的可收清单不能整批套到所有车机**：MIB3 的突发消息里 `0xA100`（AUX 状态）
  和 `0xFFF0`（GPRMC 状态）只有宣称能收的车机才可以发，判据是清单、不是品牌。
- 认证结果可缓存但**不可继承**：`PairingMemory` 记「车机确定性拒绝配对」，`FreshAuthPolicy`
  在同一接收端连续 2 轮未建起会话时把它清掉，强制重做 pair-setup。

屏幕流建立之后，控制会话要**再跑一遍**，但此时已经没有蓝牙——由
`airplay/VirtualIap2Link` 在 AirPlay 事件通道里承载一条虚拟 iAP2 链路：

| 形态 | 出站通道 | 备注 |
|---|---|---|
| DiPlay（测试台） | type-130 隧道 TCP | 链路协商要「回显先行」 |
| Audi MIB3 | 控制通道 POST `/command` 的 `iAPSendMessage` | 屏幕 SETUP 后手机主动 DETECT → 车机 SYN → 手机 SYN\|ACK，随后整段重跑控制会话 |
| 旧形态/明文 | 事件通道 `iAPSendMessage` | 只回空 ACK 时 MIB3 约 10~12 秒拆链 |

**方向不能对调**：出站写到事件通道上，车机不读（Frida 抓包 + Honda HFT 实测）。

## 5. L4 AirPlay 会话：真正的投屏协议

```
TCP 7000（来自 0x4301）
  GET  /info            能力清单（bplist：设备型号、支持的流类型、特性位）
  POST /pair-setup      SRP-6a + ed25519，首次生成控制器长期身份并存盘
  POST /pair-verify     Curve25519 + HKDF → ControlKeys（控制通道 AES、流密钥派生用）
  SETUP  type=110       屏幕流：声明的解码尺寸与视口（本版默认只写解码尺寸）
  RECORD                开流
  SETUP  type=100/102   音频流：100 = PCM、102 = AAC-LC（选错就是有画面没声音）
```

几条踩过的坑，都写在代码注释里了：

- **流顺序按接收端学**（`airplay/StreamOrderMemory`）：默认「屏幕 RECORD 优先」，
  但 JYBOX-80/沃尔沃 Sensus 这类接收端会因为「流建好后补发 RECORD」直接关掉 AirPlay 服务（issue #14）。
  所以型号快路径 + 实测结论记忆 + 反向纠正，日志三行可判读（`STREAM: 顺序 = …`）。
- **`/info` 可能慢应答**（实测 3.3 秒）：别当死链，`ENDPOINT` 阶段预算 5 分钟。
- **加密**：控制通道 `ControlCipher`，流通道 `StreamFrameCipher`（ChaCha20-Poly1305，
  nonce 后 8 字节是小端——第一版在这上面错过一次）。
- **时间基准**：`TimingServer` 提供 NTP 式对时，音频同步包依赖它。

## 6. L5 应用与编排：谁持有这一切

`SessionController` 是**进程级单例**：管线所有权、日志缓冲、阶段计时都在它手里，
Activity 只是「挂屏」。划掉界面再进来，连接不受影响——这是 2026-10-11 三个连带 bug 的修复结果。

阶段预算（`util/ConnectBudget`，`ConnectStage`）：

| 阶段 | 预算 | 说明 |
|---|---|---|
| ENDPOINT 等车机连接信息（0x4301） | 5 分钟 | 车机可能很晚才开窗口 |
| WIFI 加入车机热点 | 60 秒 | 实测同机型 15.73s，另留 20 秒宽限 |
| PAIRING AirPlay 配对 | 120 秒 | 窗口内每 2 秒重试一次 |
| SETUP 会话建立 | 30 秒 | 含屏幕 + 音频两条流 |
| FIRST_FRAME 等首帧 | 20 秒 | 超时推一帧提示帧到车机屏幕 |
| STREAMING 推流中 | — | 交给各看门狗 |

每进一个阶段打一行、结束时打一行汇总（`STAGE: …`），所以「这次为什么慢」不需要人工掐表。

看门狗分四类，都挂在编排层：

- `WifiLinkWatchdog`：WiFi 被动断开要能自动重绑（静默绑定没有 `onLost` 回调，靠 `watchLoss()` 补）
- 音频看门狗：媒体（9240）与 TTS（9241）各自独立，引擎重启后要能自己回来
- `video/StallJudge` + `StallRecoveryPrefs`：画面停摆判据是「最后一帧距今」，分级干预
- `service/FreezeWatch` + `ProcFreeze`：进程被系统冻结后恢复（荣耀 MagicOS 重点）
- `protocol/ProtocolAudit`：按轮收集协议异常，用户导出日志时一并带走

## 7. 数据面：四条常驻通道

建链完成之后，真正一直在跑的是这四条（端口号都是**手机在 SETUP 里自己声明**的）：

```
手机                                                    车机
 │──── 视频 UDP：H.264 直通（CarLife 8240 → 不重编码）──▶│
 │──── 音频 UDP：PCM(100) / AAC(102) + 同步包 ─────────▶│  媒体 9240 与 TTS 9241 在此合流
 │◀─── 麦克风 UDP：车机推往 dataPort（1.2.32 新增）─────│  ChaCha20-Poly1305 解包 → 落 wav
 │◀──▶ 触摸 / 旋钮 / HID 报告（虚拟 iAP2 隧道）────────▶│
```

- **视频**：`video/VideoSender`。只做帧转发 + 节奏控制，「尺寸声明」默认只写解码尺寸
  （`VideoCanvasMode.raw`，对齐 AMCar）；停摆有分级干预（8s 重建编码器 / 30s 封顶 120s 拆会话）。
- **音频**：`audio/AudioSender`。媒体与 TTS 两条读线程，静音保活阈值在引擎停音频时压到 0
  （微信提示音把音乐「卡一下」就是这里 311ms 空档造成的）。
- **麦克风**：`audio/MicUplink`。**本版只到「收 + 解 + 落盘」**，把 PCM 注入 CarLife 引擎（9242）是下一步；
  车机是否真的往 `dataPort` 推流，仍需真车确认，判据是日志里的 `MIC: 结论——…`。
- **输入**：`input/TouchRouter`、`input/CarInputClient`。触摸坐标要按车机画布与手机渲染尺寸换算，
  HID 按键走 `0x6802`/`0x6801`，方向盘语音键另有 AirPlay `requestSiri` 通路
  （见 [carlife-voice-siri-20261104.md](carlife-voice-siri-20261104.md)）。

## 8. 判据表：症状 → 层

| 症状 | 先看哪层 | 该看的那行 |
|---|---|---|
| 车机侧根本不出现「已连接」 | L1 | `BT-PROFILE:`（HFP/A2DP 是否仍在线）、SDP 有没有 `…caff` |
| 长时间停在「等车机连接信息」 | L2 | `_airplay._tcp` 有没有被发现、`7000` 是否连续 `ECONNREFUSED` |
| 连上之后 10~12 秒自己断开 | L3 | 控制会话是否只回了空 ACK（MIB3 会拆链） |
| 有链路、没画面 | L4 | `/pair-verify` 结果、SETUP 返回码、`STREAM: 顺序 = …` |
| 有画面、没声音 | 数据面 | 音频流 `type` 是 100 还是 102、`AUDIO:` 引擎音频开始/停止 |
| 画面停住后一直不回来 | 数据面 | `STALL:` 干预日志、`writeStartedAt` 写卡死告警 |
| 音乐被提示音打断后卡一下 | 数据面 | `CARLIFE: 媒体 暂停（0x30003）` 与 `0x30004` 恢复的间隔、`AUDIO: 混音体检` 的最长停拍 |
| 各层都正常但整体不收敛 | L5 | `STAGE:` 汇总、`RETRY:` 与 `FreshAuthPolicy` 结算 |

三个最容易混淆的：

1. **「连不上」和「连上没画面」不在同一层**——前者查 L1–L3，后者查 L4。
2. **「有画面没声音」几乎永远是音频类型选错**，不是配对失败、也不是音量。
3. **「画面有、声音要等 88 秒」是数据面看门狗缺失**——视频与音频是两条独立的流，
   任何一条都得有「socket 死了谁重连」的答案（见 [audio-channel-watchdog.md](audio-channel-watchdog.md)）。

## 9. 只想读三个文件的话

1. `SessionController.startConnection()`：编排顺序，以及每一步为什么要这么排；
2. `iap2/Iap2SenderSession`：iAP2 消息序列与每个回调的时机；
3. `airplay/AirPlayClient`：RTSP 细节（配对、SETUP、加密、隧道）。

相关文档：[PROTOCOL-NOTES.md](PROTOCOL-NOTES.md)（线格式）、
[CARPLAY-WIRELESS-HEADUNIT-SPEC.md](CARPLAY-WIRELESS-HEADUNIT-SPEC.md)（消息目录附录）、
[connect-recovery.md](connect-recovery.md)（断链恢复）、
[carplay-mic-uplink-20261108.md](carplay-mic-uplink-20261108.md)（麦克风上行）、
[stream-order-audio-first-20261109.md](stream-order-audio-first-20261109.md)（流顺序）。
