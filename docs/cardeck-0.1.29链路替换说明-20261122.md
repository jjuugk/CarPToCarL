# cardeck 0.1.29 链路替换说明（2026-11-22）

## 来源与基线

- 源码：`C:/Users/jjuugk/Desktop/cardeck-0.1.29`（完整 Android 工程，123 个 Kotlin 文件）
- 协议核 so：`libcardeck_protocol.so`，sha256 `4cc643c06aa074b52599e971e8f39b259182bcd72c86362a9a8d7012dbfa5198`，
  与本分支原先携带的 so **逐字节一致**——0.1.29 就是这份 so 的配套 Kotlin 源码。
- 新分支：`feat/cardeck-029-full`，从 `feat/v2-protocol-core` 拉出（继承 so、118 符号 NativeBridge、arm64 配置、共存包名）。
- 用户裁定：**新建分支 + 完整替换 iAP2 与 AirPlay，以 cardeck 源码为主**。

## 核心变化

### iAP2：自研状态机 → Rust FlowStart 驱动

| 层 | 替换前（v2） | 替换后（0.1.29） |
|---|---|---|
| `transport/Iap2LinkChannel.kt` | 355 行，内含自研 `Iap2LinkEngine` | 289 行，只做 RFCOMM 读写 + 节拍 + 事件分流 |
| `iap2/Iap2SenderSession.kt` | 2429 行 CSM 状态机、MFi 试探、0x4300/4301 编排 | 254 行驱动壳：`nativeSessionFlowStart` 一条调用启动全流程 |
| 协议决策 | Kotlin 手工组 TLV | Rust FSM 内部消化，负 id 编排事件（-1 WIFI / -2 ENDPOINT / -3 PHASE1_DONE）回传宿主 |

**关键 JNI 调用序**：`nativeSessionCreate` → `nativeSetTransport` → `nativeSessionSetEventSink` →
`nativeSessionBtAttached` → `nativeSessionFeed` + `nativeSessionTick` → `nativeSessionLinkReady` →
**`nativeSessionFlowStart`**（iAP2 全流程唯一开关）→ `nativeSessionDestroy`。

此前逆向出的 MFi 证书识别（`MfiCertificateLength`）在主分支仍有价值，但本分支的 MFi 已由 Rust
在 `nativeSessionFlowStart` 内部完成，Kotlin 侧不再参与档位选择。

### AirPlay：0.1.29 版 AirPlayLink 接管

- 新增 `airplay/AirPlayLink.kt`（2435 行，cardeck 0.1.29 源码 + 兼容补丁），替换 v2 的 `protocolcore/AirPlayLink.kt`。
- `NativeTunnelIap2Link.kt`、`SdpLocalPorts.kt` 同步换成 0.1.29 版；旧 `protocolcore/` 整包删除。
- 帧密封仍走 `nativeAirplayScreenSealFrame`（128B 头 + 密文整帧，Kotlin 不二次加密）；
  音频走 `nativeAirplayAudioSealPacket`。

### 保留的适配层与平台能力（有意为之）

- **保留 `link/ProtocolCoreSession.kt`**：主控 7700 行依赖 `AirPlayLinkSession` 的 40+ 方法，
  它是 Rust 整会话托管与主控分步调用（`runPairing`→`runSetup`→`record`）之间的桥。内部实现已换成 0.1.29。
- **保留我们迭代的蓝牙平台策略**（`Iap2BluetoothClient/Server`、`BtProfiles`、`BtSessionGate`）：
  cardeck 将这些写死在自家环境（含 Shizuku），直接照搬会丢失断 HFP/A2DP、世代防僵尸线程、
  音频配置守卫等实车迭代成果。
- **保留 `DeviceIdentity`（sysfs 读真 MAC）与 `TimingServer`（ntpFromNanos）**：我们已移除 Shizuku，
  cardeck 原版依赖 `shizuku.Shell`，不适格。
- **OEM 图标发布置空**：屏幕源走 CarLife，不上车机自绘桌面。

### 从 v2 移植并保留的修复

- `SetupPlan` 收尾判据 = **计划请求的流到齐**，不再死等 `recorded`（修第二路音频建不起来、屏流数据口晚于车机看门狗）。
- `ControlKeys` 在适配层补 `phoneReadKey` 空数组（0.1.29 的 ControlKeys 少这一字段）。
- `SetupInfo`/`ScreenStreamInfo`/`AudioStreamInfo` 在适配层做类型转换，屏流标 `CoreSealed`。
- `nativeSessionSendMediaLibrary` 两版都只有声明、零调用；媒体库推送未接线（已知落差）。
- **歌曲信息（歌名/歌手/专辑/播放状态/进度）已补**：`ProtocolCoreSession.pushNowPlaying()`
  改用 `Iap2NowPlaying.encode()` 生成完整 `0x5001` CSM 帧，经 `sendIap()`
  （控制通道 `iAPSendMessage`）发给车机；控制会话就绪时补推一次，之后跟随宿主的
  内容变化 / 每秒进度事件。`artworkId` 强制去掉，避免声明了封面却发不出图。
- **画布/触摸几何字段补回**（触摸偏移排查）：0.1.29 原码的 `SetupInfo` 没有
  `displayFromCar`，无 displays 时兜 1080×2320 竖屏、未声明 maxFPS 兜 30；宿主
  Side 依赖这三项决定 98311 申请书、画布记忆和触摸映射域。已在移植版恢复：
  `displayFromCar = 宽高有效`，兜底回 1280×720，`maxFps` 未声明保持 0，
  并由 `ProtocolCoreSession` 双向转换传给主控。同时在每个 HID 设备的首条报告日志
  追加原始 hex，便于下次从报文直接核对 X/Y 取位。

## 验证

- 编译：`assembleDebug` + `compileDebugKotlin` 通过。
- 单测：**946 例 0 失败**（v2 为 968；差额来自归档 3 个只测自研 iAP2 的失效测试文件）。
- 调试包：`app/build/outputs/apk/debug/app-debug.apk`，18,638,001 B，
  sha256 `89afd48c3cccb1aa7e328385cea212cc0d814f3676f605d1de87ebadf818fcb2`
  （含 DETECT 探针修正、0x5001 歌曲信息修复与画布/触摸几何修复；前一版为 `1b331292…d91ab`）。
- 补修遗留项：`ProtocolCoreProbe` 原来给 `nativeIap2IsDetectMarker` 传的是
  `FF 55 00 00 00 00`，与逆向确认的魔数 `FF 55 02 00 EE 10` 不符，导致日志
  `ABI=异常｜detectMarker=0`。已改为正确魔数，预期日志变为 `ABI=ok｜detectMarker=1`
  （本机 adb 不可用，待真机确认）。
- **未做真机/真车验证**：iAP2 全流程改由 Rust 驱动是根本性变更，需在实车上确认
  `iap2 flow endpoint` 事件能否到站、0x5703/0x4301 是否被 Rust 正确消化。

## 触摸偏移排查（2026-11-22）

- 现象：用户反馈「触摸有点偏移」。本机无 adb，拿不到该反馈对应的日志；能拿到的最新日志是
  17:55 那版（v2 包），因此**不能把那次日志当成这次反馈的现场**。
- 17:55 日志交叉核对：`/info` 与 HID 描述符都是 1280×720（X 逻辑上限 0x500、Y 0x2d0，
  12B = 2 槽 × [tip][contact-id+padding][X u16LE][Y u16LE]），`DISPLAY:` 与
  `CARLIFE: 触控 ↓ (x,y)→(x,y)` 也都在同一域内。就这份日志看，**没有比例错**，
  不能凭它下「坐标缩放错」的结论。
- 代码侧发现的确定缺口：0.1.29 `AirPlayLink.SetupInfo` 丢了宿主依赖的
  `displayFromCar`，且兜底/帧率语义与原链路不同（见上）。这会让「车机没给 displays」
  的车按错域设触摸映射，或把兜底画布当成车机画布写进 98311/画布记忆——足以造成
  按比例偏移。已修复，但**是否就是用户反馈的那一次，仍需新日志确认**。
- 下次上车请抓含以下三行的日志：
  `AIRPLAY: ✓ 连接序完成 … display=W×H`、`DISPLAY: 车机 W×H → 流 …`、
  `EVENT: HID 报告首次来自 uuid=2a2a2a2a … hex=…`。
  判据：若 W×H 与车机实际画布不符 → 几何修复生效范围；若 hex 里 X/Y 字节与
  `CARLIFE: 触控 ↓` 对不上 → 取位问题；若两者都对、落点仍偏 → 车机/CarLife 引擎侧
  的画面容器偏移，再考虑开「触摸域自适应」兜底。

## 归档的失效测试

被测实现已被 Rust 取代，以下 3 个测试文件移到 `.tmp/deprecated-tests/`（不参与编译，可随时取回）：
`CinemoStackDetectionTest.kt`、`Iap2SenderSessionTest.kt`、`ParseWirelessApTest.kt`。

## 已知落差

- **歌名/歌手/专辑/播放状态/进度已补**（走 `0x5001`，同自研链路出口）；**封面**仍未补：
  协议核把 session 12 的 START/SUCCESS 应答吞在 Rust 内，Kotlin 只拿到 id+len 日志，
  无法可靠驱动 `Iap2ArtworkTransfer`。**歌词**协议层无字段，只能沿用「歌词行写进 title」
  的现有策略（CarLife 内置播放器有效，第三方播放器无数据源）。
- 通话/麦克风音频、导航 ducking 等能力本分支未补齐（与 v2 一致）。
- cardeck 0.1.29 的重放测试（BtLinkReplay / TunnelReplay 等）依赖 Shizuku fixture 与 Python 工具，**未移植**。
