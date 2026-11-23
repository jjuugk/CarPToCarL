# MIB3（C8-4580，CL3D 平台）固件分析 → 本项目对照

> 来源：`奥迪_MIB3_(C8-4580)_车机_CarPlay_协议逆向分析报告.html`（2026-11-03，
> 静态逆向：CL3D 平台工程包 C8-4580，`MIB3_build_k8s_CL3D_int`）。
> 该报告分析的是**车机（配件角色）**固件；本项目是**手机（发送端）**，
> 下面只保留「会改变我们行为」的条目，其余标记为「与本项目无关」。
> 姊妹篇：[mib3-firmware-3874.md](mib3-firmware-3874.md)（MMX3 平台，同族）。

## 0. 一句话结论

这份报告分析的 CL3D（C8-4580）与之前分析过的 MMX3（3874）是**同一家族的两个平台**：
同为 ESO iAP2 协议栈（`libesoiap2`）+ Apple 官方 AirPlay 接收端（`libairplay`，
`com.apple.airplay.receiver`）+ dio_manager 业务编排。报告的结论**绝大部分是对既有
实现的印证**——本项目就是拿 3801/3874 实车打磨出来的。

真正的新证据只有一条，但它值钱：**§5.7 证实 EA 会话（0xEA00/0xEA03）经
`CIAP2EAPProtocolViwi` 桥接进车机内部 VIWI TCP，是 AirPlay 信令与车控数据的承载
管道**。这与 2026-11-04 上午主线（1.2.18）用 Audi_MMI_8349 实车日志修出的问题
完全互相印证：EA 协议清单用「参数号 10/11/12 重复出现」声明，旧解析只留最后一个，
0xEA00 只发一个协议，车机一个 0xEA03 都不回。这批修复**原产自主线 1.2.18**，
switch-pool 1.2.22 是照搬移植（三协议解析 + 0xEA00 八秒催问）。

## 1. 逐条对照

| # | 固件事实（车机侧，报告章节） | 本项目现状 | 处置 |
|---|---|---|---|
| 1 | 三套协议栈：`libairplay`（Apple 官方接收端）+ `libesoiap2`（ESO iAP2）+ `iap2connectionmanager`（§1） | 与 3801/3874 实车行为一致，`ReceiverProfile.variantOf` 按 `audi` / (`delphi`+`mib`) 自动判定 | 一致 |
| 2 | **AirPlay 信令可走 iAP2 EA 隧道**（`CIAP2EAPProtocolViwi` → VIWI TCP），也可直连 HTTP，由车机决定（§5.7） | 旧解析 `associateBy { it.id }` 只留最后一个 EA 协议，0xEA00 只发 eadata1 | **本轮移植主线修复**：`Iap2Ea.parseProtocols`（参数号可重复、按序去重）+ 0xEA00 八秒催问（`EA_RETRY_DELAY_MILLIS`） |
| 3 | EA 会话有 `eapIdentifier` + `eapSessionIdentifier` 两级标识，桥接层带 `SO_KEEPALIVE` 与重连（§5.1/5.7） | 隧道数据面在 `VirtualIap2Link` | 一致 |
| 4 | 识别（0x1D10/0x1D01）字段：modelIdentifier / manufacturer / serialNumber / firmwareVersion / hardwareVersion / 语言 / `accessoryMacAddress`；**后续 Control Session 只允许已声明组内的消息**（§5.5） | 我们「清单只能证明有、不能证明没有」的判据（1.0.28 定谳）已兼容；识别应答 0x1D02 有前置限流 | 一致 |
| 5 | **CarPlay 可用性有超时兜底**：`iap2CarplayAvailabilityWaitTimeout` 超时按「不支持」处理（§5.6） | 我们主动发 0x4300（CarPlayAvailability），奥迪画像下 0x5703 交接 3s×5 轮重试兜底 | 一致（双向都有超时，不会互相死等） |
| 6 | 「Let_Phone_decide」：车机可能放弃媒体路径等手机先开口（§2.4） | 我们从不等车机先开口，直接发 0x4300 | 一致 |
| 7 | 无线链路：蓝牙 OOB 配对（`CIAP2OOBBTPairing`，有并发会话上限）+ WiFi Information Sharing（SSID/密码/信道）经 iAP2 下发（§4.2/4.3） | 0x5702→0x5703 即 WiFi Information Sharing 的载体；OOB 段（0x00B0~0x00B4）未实现，与 3874 报告同结论 | 一致（OOB 维持不做） |
| 8 | **连接类型迁移时 iAP2 会话保持打开**：「同一设备的 iAP2 会话在无线 CarPlay 运行期间保持打开」（§4.4） | `pumpIncomingForever` 保活泵整程记录控制消息 | 一致 |
| 9 | AirPlay 方法白名单 OPTIONS/ANNOUNCE/SETUP/RECORD/PAUSE/FLUSH/TEARDOWN + POST/GET/PUT/GET_PARAMETER，HTTP/RTSP 混用（§六） | `AirPlayClient` 覆盖同一套方法 | 一致 |
| 10 | `/info` 有 `sourceVersion` 门槛：`_requestReportIfIncompatibleSender` 版本过低直接回不兼容（§6.3） | `srcvers=320.17.7` 与真机对齐 | 一致 |
| 11 | SETUP 关键键：`latencyMin/Max`、`audioLatencyMs`、`isScreenMirroring`、`supportsRTPPacketRedundancy`、`inputLatencyMicros/outputLatencyMicros`、`forceKeyFrame`；**重复 SETUP 格式必须一致**（`### Setup format (%llx) does not match`，§6.4） | SETUP 体按 catplay 构造；音频 SETUP 400 后补 `ct/sr` 重试（1.2.16 起）只**追加**字段不改已有字段，与「格式一致」约束相容 | 一致 |
| 12 | **pair-setup 防爆破**：`Pair-setup server throttling for %d second(s)`，多次失败后直接禁用（§6.6） | 配对失败退避 3s/10s/30s，不硬刷 | 一致——**不要**加重试密度 |
| 13 | FairPlay：`ekey/eiv`、`AirPlayEncryptionType`、N 秒密钥轮换（§6.6） | 加密路径在 `AirPlayClient.cipher`（pair-verify 派生） | 一致（授权体系部分与本报告无关） |
| 14 | 反馈通道：接收端每 5 秒 `AirPlayReceiverStats`，`R-FPS/Latency/Ahead/A Buff/A Lost`（§6.7） | 我们每 1 秒 POST /feedback 心跳（比它的统计更密），收应答解析 bplist | 一致 |
| 15 | 屏幕流带控制操作码：`kAirPlayScreenOpCode_ForceKeyFrame`（车机可要关键帧）、`SetCornerMask` 圆角遮罩（§7.1） | `/command type=forceKeyFrame` 已响应（`VideoSender.requestKeyFrame()`，1.2.17 起）；屏幕流内 opcode 与 `SetCornerMask` 不解 | 部分一致：`/command` 路径够用；仅流内 opcode 那条路留观 |
| 16 | 音频：PCM/AAC-LC/AAC-ELD/Opus，`AUDIO_AUDIOLATENCY_*` 按场景分常量；报告**未给具体值**（§7.2） | 从 `/info audioLatencies` 动态取，不写死 | 一致 |
| 17 | **SETUP→RECORD 之间车机要做解码器初始化，超时应放宽到秒级**（§7.3） | RTSP 事务超时秒级； Timing/事件通道独立端口 | 一致 |
| 18 | iAP2 Link 层帧格式（SOF FF5A + 长度 + 控制 + 序号 + CRC-16-CCITT）与滑动窗口参数（§5.2） | `Iap2LinkEngine` 同构；RTO 采纳对端 SYN 声明 | 一致 |
| 19 | USB 有线：NCM + libusb 直通 + USB-serial 三条路径；Power 模块（MCC 充电协商）（§三/3.4） | 只做无线（WIRED-CARPLAY-FEASIBILITY.md 已论证） | 与本项目无关 |
| 20 | `EXTENDEDFEATURES_*` / `ENABLEDFEATURES_*`（ALTSCREEN/VIEWAREAS/CORNERMASKS）按车型裁剪（§6.3） | 接收端能力位决定布局，我们是跟随方 | 与本项目无关（接收端配置） |

## 2. 本轮落地（switch-pool）

1. **`iap2/Iap2Csm.kt`**：新增 `object Iap2Ea.parseProtocols(body)` —— 0x1D01 的
   EA 协议清单按「参数号 10/11/12 **可重复出现**」解析，保留声明顺序、去重；
   子参数 0=协议号、1=协议名，忽略附加参数（id=2）。与主线 `eb834c4`（1.2.18）
   同一份实现，两边语义一致。
2. **`iap2/Iap2SenderSession.kt`**：
   - `parseEaProtocols` 改为委托 `Iap2Ea`（旧 map 语义留档在测试里）；
   - 新增 `scheduleEaSessionRetry`：0xEA00 发出 8 秒（`EA_RETRY_DELAY_MILLIS`）
     仍未收到 0xEA03 就补催一轮，每轮会话至多一次——车机可能要到 AirPlay 会话
     SETUP 之后才认 EA 会话（报告 §5.7 的 VIWI 桥要 CarPlay 栈起来才通）；
   - `recvNewFrame` 里挂 0xEA03 记账（`noteEaStatus`）：必须在帧进 expect/暂存区
     之前记账，否则它会以「非目标帧」身份躺在 pendingFrames 里，催问线程看不到。
3. **`ConnectionProfile.AUDI_MIB3`**：档位文案改为覆盖全家族
   （3801 / 3874 / C8-4580），**行为标志一个没动**（`forcesAudiMib3`、
   `allowsB233=false` 原样）。不新增档位：C8-4580 与既有奥迪专版同族，
   自动判定 + 这一个强制档已经覆盖，再造一个重复条目只会让下拉框更难选。
4. **测试**：`Iap2EaTest` 四例（重复参数号保留全部协议、旧 map 语义留档、
   空体、重复声明去重）。

## 3. 明确没做的，以及为什么

- **没新增连接档位**（见上）。「增加链接配置」的正确粒度是把这个家族**已有的**
  档位标注清楚 + 补齐它真正缺的 EA 修复，而不是加一个行为完全相同的新条目。
- **没实现屏幕流内的控制操作码**（`kAirPlayScreenOpCode_*`）：车机通过
  `/command type=forceKeyFrame` 要关键帧时**已经会响应**（`SessionController` →
  `VideoSender.requestKeyFrame()`）；漏的是「只在屏幕流里塞 opcode、不走 /command」
  这条路径，以及 `SetCornerMask` 圆角遮罩。遮罩对 CarLife 固定矩形整屏没有意义，
  操作码则要等实车日志证明车机确实只走流内路径再补。
- **没实现 OOB BT 配对**（`CIAP2OOBBTPairing`，0x00B0~0x00B4）：与 3874 报告同
  结论——无线链路不依赖它也建得起来（3801/3874/4260 实车都通了）。
- **没动 USB 有线 / Power 模块 / mDNS 浏览**：我们只做无线，发现阶段走蓝牙 SDP +
  0x4301 端点，不走 mDNS。

## 4. 对默认连接的影响（用户问的「顺便看看」）

**默认档（自动识别）与其它车机行为不变**，三层保证：

1. `Iap2Ea.parseProtocols` 只在车机**声明了 EA 协议**时才有输出——别克/宝马/凯迪拉克
   的 0x1D01 里 EA 槽为空或只有一个协议（参数号不重复），解析结果与旧实现**逐字节
   一致**；`Iap2EaTest` 把两种语义都钉死了。
2. `scheduleEaSessionRetry` 在 `protocols.isEmpty()` 时直接 return——不声明 EA 的
   车机连催问线程都不会创建。
3. `ConnectionProfileTest` 守「默认档不许改变其它车机」的底线（AUTO/STANDARD 判定、
   别克档互斥、奥迪档唯一开专版），本轮一个断言没改就全绿。

唯一的行为变化落在奥迪 MIB3 族上：0xEA00 会把车机声明的**全部** EA 协议各发一条
（此前只发最后一个），8 秒无回执再催一轮——这正是主线 1.2.18 在 Audi_MMI_8349
实车上验证过的修复。

## 5. 上车后看这几行

| 日志 | 含义 |
|---|---|
| `iap2 0x1d01 EA 组件[10] protocol=1 name=de.audi.mmiconnectdatatransfer`（×3） | 三协议全部解析到位（旧版只有最后一行） |
| `iap2 tx=0xea00 startEASession protocol=… name=…`（×3） | 0xEA00 每个协议各发一条 |
| `iap2 rx=0xea03 EASessionStatus bytes=…` | 车机认了 EA 会话（报告 §5.7 的 VIWI 桥已通） |
| `EA: 8 秒内没等到 0xEA03——再催一轮 0xEA00（3 个协议）` | 车机还没准备好，催问已发（只此一次） |
| `iap2 车机专版：奥迪 MIB3 专版…` | 身份自动判定命中（C8-4580 的 0x1D01 应含 audi/delphi） |

若最后一行显示「通用参数」但车确实是奥迪，把 0x1D01 身份原文补进
`ReceiverProfile.variantOf` 的判据，或下拉框选「奥迪 MIB3（强制专版）」。

## 6. 对主线（main）的协议覆盖评估（2026-11-04）

问题：这份报告里对端（车机）声明/要求的能力，主线还缺哪些？

**先厘清方向**：报告里的 EA 修复本来就产自主线 1.2.18（实车 `Audi_MMI_8349` 日志），
switch-pool 1.2.22 是从主线**移植过去**的。所以「主线缺不缺」要按报告逐条看，
而不是看两个分支谁多了几行。

| 报告条目 | 主线现状 | 结论 |
|---|---|---|
| §5.3 HID 组（触摸屏/电话/按键/旋钮/方向键） | 触摸 `input/TouchReports`（描述符驱动，单指 5B / 双指 12B）；媒体键 `input/ConsumerKeys`（按 Consumer 页 0x0C 判定）。**旋钮（`KNOB_UUID_HEX` 已定义但没映射）、DPad、电话键只落到「其他 HID 报告 … hex=」日志**，不产生输入 | **唯一有代码缺口的条目**。C8（双触摸屏 MMI touch）不带旋钮，暂不臆造映射；同族老 MIB3（带 MMI 旋钮）若用户反馈「旋钮转不动」再按实车报文补 |
| §5.7 「EA 隧道承载 AirPlay 信令」的**另一条拓扑** | 只做直连：0x4301 拿端点 → 直连车机 AirPlay HTTP。EA 隧道目前仅用于 viwi 车控（`VirtualIap2Link`） | 3801/3874/4260 实车全部直连成功，暂不需要。若遇到「只给隧道不给 HTTP 端口」的车机，这是唯一能走通的路 |
| §7.1 `forceKeyFrame` | 已实现：`/command type=forceKeyFrame` → `VideoSender.requestKeyFrame()`（VideoSender:719） | **比旧文档写的更完整**，此前「不做」的记述已过时（见 §3 更正） |
| §7.1 `SetCornerMask` / `ViewArea` | 不消费屏幕流下行操作码 | 不适用：CarLife 画面是固定矩形整屏，没有圆角/多窗口概念 |
| §7.2 Aux In（车机麦克风上行） | 未实现 AirPlay 上行音频 | 不适用：CarLife 语音走车机自己的蓝牙/引擎链路，不经我们的 AirPlay 上行 |
| §7.2 RTP 冗余与重传 | SETUP 明写 `supportsRTPPacketRedundancy=false`，也不发冗余包 | 一致：诉求与实际能力对齐，车机不会期待冗余包 |
| §5.3 Power（0xAE00~0xAE03） | 只定义了消息号，不发起 | 不适用：供电协商是 USB 有线场景，本项目只做无线 |
| §5.5 「后续消息只允许出在已声明组内」 | `Iap2CapabilityReport` 把 0x1D01 参 6/参 7 解析成三层日志，但**出站不做硬门禁** | 故意不加：硬裁剪会打断已跑通的车机。保持「先观察日志、确认某台车机确实拒收再裁剪」 |
| §5.6 `iap2WirelessCarPlayCountryAdaption`（部分市场禁 5GHz/无线 CarPlay） | 未处理 | 中国区无线 CarPlay 可用，3801/3874/4260 实车已验证，不涉及 |
| §6.6 pair-setup 防爆破 | 失败退避 3s/10s/30s，不硬刷 | 一致，**不要**加密重试 |
| §8.2 输入延迟协商 | SETUP 带 `inputLatencyMicros/outputLatencyMicros` | 一致 |

**结论**：对端协议在主线上没有「必须马上补」的缺口。唯一实质缺项是
「旋钮/方向键/电话键 HID 输入映射」，它属于**输入映射**而非协议解析——报文已经能
收到、能打日志，缺的是「转成什么 Android 按键」。这件事在拿到实车报文样本前动手
只会瞎猜（旋钮是相对量还是绝对量、DPad 用不用 Hat Switch 语义都不确定），
所以留到有样本再做。
