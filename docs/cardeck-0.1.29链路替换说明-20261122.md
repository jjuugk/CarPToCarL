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

## 验证

- 编译：`assembleDebug` + `compileDebugKotlin` 通过。
- 单测：**946 例 0 失败**（v2 为 968；差额来自归档 3 个只测自研 iAP2 的失效测试文件）。
- 调试包：`app/build/outputs/apk/debug/app-debug.apk`，18,637,133 B，
  sha256 `6f37d1a336b64fea5a4ecd2ca12df69f3b33db15c049495709e452e1851f01d8`
  （含下述 DETECT 探针修正；修正前为 `85db839f…8a1f8f23a`）。
- 补修遗留项：`ProtocolCoreProbe` 原来给 `nativeIap2IsDetectMarker` 传的是
  `FF 55 00 00 00 00`，与逆向确认的魔数 `FF 55 02 00 EE 10` 不符，导致日志
  `ABI=异常｜detectMarker=0`。已改为正确魔数，预期日志变为 `ABI=ok｜detectMarker=1`
  （本机 adb 不可用，待真机确认）。
- **未做真机/真车验证**：iAP2 全流程改由 Rust 驱动是根本性变更，需在实车上确认
  `iap2 flow endpoint` 事件能否到站、0x5703/0x4301 是否被 Rust 正确消化。

## 归档的失效测试

被测实现已被 Rust 取代，以下 3 个测试文件移到 `.tmp/deprecated-tests/`（不参与编译，可随时取回）：
`CinemoStackDetectionTest.kt`、`Iap2SenderSessionTest.kt`、`ParseWirelessApTest.kt`。

## 已知落差

- 歌名/封面/歌词、通话/麦克风音频、导航 ducking 等能力本分支未补齐（与 v2 一致）。
- cardeck 0.1.29 的重放测试（BtLinkReplay / TunnelReplay 等）依赖 Shizuku fixture 与 Python 工具，**未移植**。
