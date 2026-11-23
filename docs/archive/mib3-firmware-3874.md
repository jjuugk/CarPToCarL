# MIB3H（SW 3874）固件分析 → 本项目对照

> 来源：`MIB3_CarPlay协议分析_3874.html`（2026-11-03，静态逆向：8W0906961BT-B9PA-3874 /
> `CL37_MIB3H_AU_AS_G4x_2302700PROD`，MMX3 平台，ivi_root + opt_ivi）。
> 该报告分析的是**车机（配件角色）**的配置与实现；本项目是**手机（发送端）**，
> 所以下面只保留「会改变我们行为」的条目，其余标记为「与本项目无关」。

## 0. 一句话结论

报告的 18 条事实里，**绝大多数与现有实现一致**——本项目就是按这台车的实车抓包
（2026-10-09 起，含 Frida 挂越狱 iPhone 的 mediaserverd）打磨出来的。
真正的差异只有三处：**视频编码 profile 没有显式声明**、**链路重传次数只给自己 4 次**
（车机声明 30）、**OOBBTPairing 模块完全没实现**。前两条已在本轮改掉，第三条只补了识别。

## 1. 逐条对照

| # | 固件事实（车机侧） | 本项目现状 | 处置 |
|---|---|---|---|
| 1 | `global.wirelessCarPlay = true`、`cp: 3`（USB + WiFi 都支持） | 只做无线；有线在 `WIRED-CARPLAY-FEASIBILITY.md` 里已论证不做 | 一致 |
| 2 | `CoWSupported: false` —— 车机**不在 EIR 里广告 CarPlay** | 靠 SDP 查配件 iAP2 服务（`…decacaff`）+ 系统 ACL 事件做接近检测，不读 EIR | 一致（这条正好解释了为什么不能靠广播找车） |
| 3 | 配对流：BT 连上 → `0x5702` 索要 → `0x5703` 回配置 → `0x00B0~0x00B4` 交换 link key → WiFi 上重建 AirPlay | 0x5702/0x5703 已实现且加了「清单不声明也发」的兜底；**OOB 段未实现** | 本轮只补识别与日志 |
| 4 | BT 链路参数：窗口 5 / maxRecv 2048 / RTO 3000ms / cumAck 700ms / 重传 30 次 / cumAck 数 3 | 声明 4 / 0xFFFF / 4000 / 500 / **4** / 3；RTO 会采纳对端值（→3000） | **本轮改：重传上限 4 → 10，并真正采纳对端声明** |
| 5 | `iap2.connectionTimeoutMs = 6000` —— 车机侧 iAP2 建链超时 6 秒 | 我方链路协商上限 60 秒（`Iap2SenderSession.run` 默认值） | 未改（见 §3） |
| 6 | `enableCoprocessorResetOnError = true` —— 出错会复位协处理器 | 失败退避 3s / 10s / 30s，给车机留恢复时间 | 一致 |
| 7 | `enableCertificateCaching = true` / `provideEngineTypeInIdentification = false` | 证书缓存是车机侧行为；我们不依赖引擎类型 | 与本项目无关 |
| 8 | 控制会话 12 模块：auth / appLaunch / communications / deviceNotifications / EA / location / nowPlaying / OOB / power / wifiInfo / (mediaLibrary+vehicleStatus 关闭) | communications、nowPlaying、location、EA、appLaunch、auth 均已覆盖；**OOB 无**；power 是「有线专用」故不订阅 | 见 #3 |
| 9 | `authentication` 只有 `0xAA06`（**仅控制会话版本 2**） | MIB3 事件通道路径（`VirtualIap2Link`）已处理 `0xAA06 → 0xAA00` | 一致 |
| 10 | **没有 RouteGuidance 模块** → 导航转向信息不会下发 | 本项目也不做导航数据（CarLife 画面走视频通道） | 一致 |
| 11 | 视频：`VIDEO_H264_BP`、800×480 / 1280×720 / 1920×1080、30/60fps | 分辨率取 `/info` 的 `displays[]`（对的）；**profile 未声明，吃编码器默认** | **本轮改：显式 Baseline + 按分辨率选 level** |
| 12 | 解码：`omxBuffers 5`、忽略 NAL 时间戳、`cache 30` 帧、`latency 70ms`、`badFramesThreshold 4`、`directDecoding` | GOP 1 秒（60fps 时 60 帧 > 车机 30 帧缓存）；未设码率模式 | 未改（见 §3） |
| 13 | 音频：PCM / AAC-LC / AAC-LC-ADTS、8k~48k、`audioLatency in 95ms / out 80ms` | 从 `/info` 的 `audioLatencies` 动态取，不写死 | 一致（报告值只作兜底参考） |
| 14 | 屏幕落点：`Driver_Display` / displayable 93，`cropping`、`scaling` 全关 | 推流分辨率必须与 `/info` 声明值逐像素相等，否则车机不缩放直接显示错位 | 一致 |
| 15 | AirPlay 接收端 `320.17.7`；RTSP 方法表 OPTIONS/ANNOUNCE/SETUP/RECORD/PAUSE/FLUSH/TEARDOWN | `AirPlayClient` 覆盖同一套方法；`srcvers=320.17.7` 已对上 | 一致 |
| 16 | HID：`fingers: 1`（只报单指）、Knob（select/acHome/acBack）、Remote Playback、`countryCode 9` | 按键映射在 `input/ConsumerKeys.kt`；单指限制是**车机侧**的，真车双指按不出来不是我们的 bug | 一致（`touch-multitouch.md` 的双指结论只适用测试台） |
| 17 | 鉴权走 I²C 上的 Apple 协处理器（`/dev/i2c-0`，0x11/0x10） | 我们是发送端，只做「挑战 → 收签名 → 自报 0xAA05」，不校验 | 与本项目无关 |
| 18 | `MGDA` 在 MIB3 上命中 0 次；高德投屏到仪表不可能 | 本项目的 CarLife 劫持走自己的视频通道，不依赖这条 | 与本项目无关 |

## 2. 本轮落地

1. **`video/VideoSender.kt`**：新增 `openEncoder()`，先请求
   `AVCProfileBaseline` + 按分辨率选 level（≤480p→3.0、≤720p→3.1、1080p→4.0、1080p60→4.2）；
   编码器不接受就**退回不声明的老路径**（与改动前完全一致）。日志新增
   `VIDEO: 编码器已配置 WxH@FPS（显式 Baseline / Level N）`，
   拒绝时打 `VIDEO: Baseline 声明未被接受（…）——退回默认 profile`。
2. **`iap2/Iap2LinkEngine.kt`**：重传次数默认 4 → 10，并新增
   `effectiveMaxRetransmissions()` —— 本地与对端声明**取较小值**（以前对端声明 30 也只用 4）。
3. **`iap2/Iap2MessageIds.kt`**：补齐车机启用但我们没命名的消息族——
   OOB（0x00B0~0x00B4）、`0xEA02`、`0xAA06`、location（0xFFF0/FFFA/FFFB/FFFC）、
   communications 全套（0x4155~0x4172）、power（0xAE00~0xAE03）、
   vehicleStatus（0xA100/0xA101）、`0x5002`。
   以前这些帧在日志里只显示 `0x00B2`，看不出属于哪一族。

## 3. 明确没做的，以及为什么

- **没把链路协商上限从 60s 收到 6s**（对应 #5）。车机的 6 秒是「它等 iAP2 建链」的超时，
  我方短于它并不能让失败更快被车机接受，反而会在车机启动慢时误判；现有「阶段预算 +
  半握手定性」（`connect-hardening.md`）已经能尽早止损。要改必须有实车日志证明
  「6 秒后车机确实已放弃」。
- **没把 GOP 从 1s 改成 0.5s**（对应 #12）。车机缓存 30 帧（60fps 下 0.5 秒），
  理论上 1 秒 GOP 在丢包后要多等半秒才有 IDR；但 IDR 每帧成本高，砍半会让码率明显上升。
  真车目前没有反映出「恢复慢」的问题，先不动。
- **没实现 OOBBTPairing 的应答**。交换 link key 需要读取本机 BT 配对密钥（应用层无权限，
  非 root 拿不到）。收到就记日志、链路层照常 ACK，不伪造应答——**乱回可能让车机
  认为密钥已同步**，比不回更糟。
- **没订阅 power 模块**。固件明确写了 `PowerSourceUpdate` 是有线专用，无线会话订阅它
  只会白等。

## 4. 下一轮实车验证就看这几行

```
VIDEO: 编码器已配置 1280x720@60（显式 Baseline / Level 31）
```
- 出现这行 = Baseline 生效；出现 `Baseline 声明未被接受` = 该机型编码器不认，已自动退回。
- 画面若有变化（黑屏/花屏），**优先怀疑 level 选高了**，把 `avcLevelFor` 的阈值往下调一档。

```
iap2 rx=0x00B0 (OOBBTPairing(0xB0))
```
- 出现 = 车机确实主动发起了带外配对，可以据此判断「第二次连接是否需要重新配对」。
- 不出现 = 这台车/这次固件没有走 OOB，不必再为它设计。

```
iap2 link dead: packet N not acknowledged
```
- 若重传次数明显变多（>4 次才恢复），说明旧值 4 确实偏紧，本轮改动有实际收益；
- 若极少触发，说明蓝牙链路本来就稳，改动只是保险。
