# 4580（MIB3 / C3）CarPlay 不出画面的条件清单

> 对象：Audi MIB3 `CL3D_MIB3H_AU_AS_G4x_2502501PROD`（2025-06-26 镜像）
> 手段：从 `opt_ivi_C3.img.swup` 里解出 195 个 ELF 模块，逐函数反汇编 + 提取随镜像发布的 JSON 配置。
> 主要模块：`smartphone_integrator`（SI，偏移 243,536,000）、`dio_manager`（DIO/Apple 侧，247,488,640）、
> `libairplay.so`（233,750,656）、`gal`（237,195,392）、`libesoiap2.so`（1,815,556,224）。

## 0. 一句话结论

“有画面”要求 **五道门全开**：编码授权 → USB 识别/端口能力 → CarPlay 测试与激活 →
CarPlay 会话（StartSession）→ 屏幕接管 + 视频流解码。任何一道关门的表现都是“黑的”，
但停在哪一道，日志里的句子完全不同。**其中第 5 道门最容易被忽略：CarPlay 会话起来了、声音也有，
但屏幕没被 HMI 交出来，画面就是不出现。**

## 1. 门 1：编码（Coding）与功能使能码（FEC）

SI 的状态机在启动时读 coding/FEC，读失败或不允许会直接产生事件：

| 事件 | 含义 |
| --- | --- |
| `SI_EVENT_CODING_CARPLAY_DENIED` | 该车没编码 CarPlay |
| `SI_EVENT_FEC_CARPLAY_DENIED` | FEC（功能使能码）没买/没激活 CarPlay |
| `SI_EVENT_CODING_USB_DENIED` | USB 数据功能未编码 |
| `SI_EVENT_CODING_GAL_DENIED` / `SI_EVENT_FEC_GAL_DENIED` | Android Auto 同类 |

判定函数 `CSmartphoneIntegratorConfiguration::isGrantedByCoding(technology, connectionType, isAppleDevice)`
（SI `0x14ec30`）：

```
取值 [cfg+0x14]  = CarPlay 技术位（0 未编码 / 1 USB / 2 WiFi / 3 USB+WiFi）
isAppleDevice != 0 → 授权 = (技术位 == 3)
isAppleDevice == 0 → 授权 = (技术位 >= 2)
再按 technology(1=CarPlay,2=GAL) × connectionType(1=USB,2=WLAN) 取 4 个标志位：
  [cfg+0x1f] CarPlay/USB   [cfg+0x20] CarPlay/WLAN
  [cfg+0x1d] GAL/USB       [cfg+0x1e] GAL/WLAN
```

日志原文（可直接对号入座）：

```
[CSmartphoneIntegratorConfiguration] isGrantedByCoding : technology=%s,connectionType=%s,isAppleDevice=%i -> isGranted=%i
 -> CarPlay not allowed by coding!
[CSIStateRunning] Preferred connectionType %s is not granted by Coding and/or FEC
```

> **实操含义**：很多 4580 出厂只编码了 CarLife/AndroidAuto。这种车插上 iPhone 不是“识别失败”，
> 而是“压根不做 CarPlay 测试”。这条是最常见、也最容易被误判成“协议不对”的原因。

## 2. 门 2：USB 枚举与端口能力

配置里的黑名单（`smartphone_integrator` 的 `usb` 段，随镜像发布，不是推断）：

```jsonc
"ignoredDeviceList": [ "0x2001:0x3c05", "0x2001:0x1a02", "0x2001:0x4a00", "0x2721:0x0003",
  "0x2721:0xec00", "0x0424:0x4040", "0x0424:0x2530", "0x0424:0x4940", "0x0424:0x9500",
  "0x1e2d:0x0055", "0x1e2d:0x0060", "0x1c9e:0x9b05", "0x0403:0x6001", "0x1145:0x0008",
  "0x0403:0x6011", "0x1c9e:0x7605", "0x0b95:0x1780", "0x0b95:0x1790", "0x0b95:0x7720",
  "0x0b95:0x772b", "0x0525:0xa4a2", "0x05c6:0x908c", "0x12d1:0x1573", "0x0951:0x168e",
  "0x0451:0x9261", "0x20be:*", "0x1e2d:0x0061", "0x29f8:0x0200", "0x2996:0x0105", "0x2996:0x0120" ],
"ignoredDeviceClasses": [ "0x09:*" ],        // USB Hub 类设备直接忽略
"carplayUnsupportedProductIDs": [],          // 空：本车没有额外的 CarPlay 产品 ID 黑名单
```

“忽略”是彻底不枚举，连日志都不打，表现为“插上毫无反应”。

进入测试前的三句“跳过”：

```
[CCarPlayTestController] Ignored. Port reset expected.
\nSkipping CarPlay test since test was already executed for deviceID=%i (%s).
\nSkipping CarPlay test since no USB smartphone features supported for deviceID=%i (%s).
\nSkipping CarPlay test since Wired CarPlay not possible at connected USB port for deviceID=%i (%s).
```

- `Port reset expected`：SI 刚刚主动复位过该口，这次重插事件不算数。
- `test was already executed`：同一 deviceID 只测一次，结果被记住。
- `no USB smartphone features supported`：该设备的配置里没有任何 USB 智能手机功能（AA/CarPlay/媒体）匹配。
- `Wired CarPlay not possible at connected USB port`：**端口本身**（只充电/仅数据盘/被 coding 禁 USB）不支持有线 CarPlay。

## 3. 门 3：CarPlay 测试与激活

测试就是“开一个 iAP2 会话，看对方是不是 Apple 设备并给出身份信息”。判定在
`CDeviceDetection::activateCarPlayUSBIfSupported`（SI `0x1a2f50`），顺序是：

```
1) 黑名单判定 0x1b93b0           → " -> Blacklisted as CarPlay device!"
2) 设备测试结果 [dev+0x30]==3     → " -> CarPlay not activated because device test already failed!"
3) coding 判定 0x14ec30           → " -> CarPlay not allowed by coding!"
4) (通过)                         → " -> CarPlay activated!"
```

真正的“必需要素”在 SI `0x1a4a80`：

```
hasInfo(devId, 0x10) || hasInfo(devId, 0x200)     // 是 OR，不是 AND
```

- `0x10` 由 `CSmartphoneIntegratorController`（SI `0x151828`）/ `CSIStateFailed`（`0x1e087c`）置位；
- `0x200` 由 `CMediaConnectorHandler`（SI `0x1b39c8`）置位。

也就是说：**车机要在这个 iAP2 会话里“攒到”它所认为的必备信息（集成条目或媒体连接条目），
一个都没有就不进 CarPlay。** 等待期间日志是：

```
[CCarPlayTestController] IAP2 session already running. Waiting for required IAP2 information.
[CDeviceDetection]       Waiting for (dis)connectDevice() to decide whether the device shall be used for media playback or CarPlay.
```

等待上限来自配置 `iap2CarplayAvailabilityWaitTimeout = 60000`（60 s），超时后：

```
[CIAP2CarPlayAvailabilityHandler] Timer expired for connectionType=%s usbDeviceNum=%u. btMacAddress=0x%lx
```

同层还有几处“直接把设备丢掉”的静默分支（`CSmartphoneIntegratorDeviceInfoUpdater` /
`CSmartphoneIntegratorDeviceList`），对自制设备尤其容易踩：

```
Ignored. Invalid macAddress.
Ignored. Invalid serialNumber.
Ignored. Device not present (%s)
[CSmartphoneIntegratorDeviceList] Ignored. Invalid mac address
 -> Ignored -> Device found neither in PresentDeviceList and there is no ActiveApp !
```

即：iAP2 身份里的 MAC 必须非 0、序列号必须非空、设备必须在“在场列表”里，
否则身份更新被丢弃 → required 信息永远攒不齐 → 测试超时 → 本次插入作废。

一旦这一轮判定为失败，本次插入就**不会重试**——之后再怎么等都不会自己恢复，必须重新插拔（甚至重新枚举）。

## 4. 门 4：CarPlay 会话（StartSession）

DIO 侧 `CDIOManager::trySendCarPlayStartSession`（dio `0x2c1db0`）逐条拦：

```
<DIO> Skipped, Service already started
<DIO> Skipped, CarPlayStartSession command already sent %d times
<DIO> Skipped, accessPointInfo is missing
<DIO> Skipped, AirPlay server is not yet started
<DIO> Skipped, CarPlay Connection is not supported
```

服务建立阶段失败（这些会直接导致“会话都没起来”）：

```
[CDIOManager] Cannot setup service, deviceInfo or connectivityInfo is null
[CDIOManager] Cannot setup service, localBTMacAddress is 0 (invalid)
[CDIOManager] Cannot setup service, coding information is incomplete
[CDIOManager] Cannot setup service, already executed
[CDIOManager] <DIO> CIAP2ServiceJobProxy::carPlayStartSession failed
[CIAP2ServiceEso] carPlayStartSession failed
```

时限（配置实值）：`setupServiceRequestTimeout` 10 s、`startServiceRequestTimeout` 10 s、
`startServiceTimeout` 15 s、`startServiceRetryTimeout` 1 s × `startServiceRetryMaxRetries` 4 次、
`teardownTimeout` 2 s、`carplayHTTPConnectionTimeoutMs` 800。

无线特有的关闭条件：

```
Wireless CarPlay not supported for ...              // 国家/5GHz/编码不允许
Disabled Wireless CarPlay / Disabled Wired CarPlay, ...
[CIAP2Connection] wirelessCarPlaySupported=%d, wiredCarPlaySupported=%d, wirelessCarPlaySessionActive=%d
Keeping IAP2 session open since Wireless CarPlay is running for same device.
```

另外，`[CSIStateRunning] USB device stack not enumerated` 表示“USB 设备栈还没枚举完就来了事件”，
这一轮也会被丢弃。

## 5. 门 5：屏幕接管 + 视频流（“有声音没画面”就死在这里）

CarPlay 会话跑起来后，手机发 `RequestUI`，车机必须把屏幕“交出来”：

```
[CDSICarplayImpl] onJob_requestUI : <DSI> url='%s'
[CDSICarplayImpl] <ERROR> Failed to overtake the screen: "%s"     ← 关键失败点
[CDSICarplayImpl] <ERROR> HMI failed to synchronize with the current mode: %s
```

屏幕对象在配置里是写死的（`dio_manager` 的 `screen.esogfx` 段）：

```jsonc
"display":       "Driver_Display",
"displayable":   "Displayable_External_Smarthphone",
"displayableID": 93,
"layer":         "Layer_Media_Base",
"omxBuffers":    5,
"flushOnError":  true,       // 解码器报错时冲刷输入并请求关键帧
"timestamps":    false,      // 忽略 AirPlay 时间戳，换性能
"directDecoding": true
```

屏幕状态机（`CDIOManager`）：

```
SCREENSTATE_IDLE → SCREENSTATE_SETUP → SCREENSTATE_VIDEOSTREAMING_ACTIVE
                            ↓                     ↓
                     SCREENSTATE_ERROR      SCREENSTATE_STOPPED
日志： [CDIOManager] <SCREEN> newState: %s
       [CDSICarplayImpl] <SCREEN> State changed: %s --> %s
       [CDSICarplayImpl] <DSI><SCREEN> Video state='%s'
```

只有进到 `VIDEOSTREAMING_ACTIVE` 才有画面。之前/之后都是黑的（但音频通路独立，仍会响）。

视频流本身的硬指标（`screen` 段）：`maxFPS 60`、`latency 70`、`cache 30`、
**`badFramesThreshold 4`（连续 4 帧坏帧就上报 screen session error，会话被终止）**。

AirPlay 侧建流失败的样子（`libairplay`）：

```
### ScreenSetup / _ScreenStart
### screen receiver setup failed
### screen start failed
### Unsupported stream type: %d
### Alt Screen supported: %s
```

能力开关（`dio_manager.enabledFeatures`，本车实值）：

```jsonc
"altScreen": false,        // 不支持副屏
"viewAreas": true,         // 支持 viewArea（视口/裁切协商）
"cornerMasks": false,
"focusTransfer": false,
"uiContext": false,
"enhancedSiri": false
```

> `viewAreas=true` 这一点和之前几轮改的 VideoConfig 视口字段是同一件事：
> 车机声明支持 viewArea，手机就会按“解码尺寸 + 视口”分离的方式送流；
> 只要车机侧 HMI 报告的画布和实际显示层对不上，画面就是黑的或错位，而不会报错。

## 6. 配置实值速查（本车）

| 项 | 值 |
| --- | --- |
| CarPlay 运行体 | `dio_manager`（`/opt/smartphone_integration/bin`），另有 `gal`、`iap2connectionmanager` |
| 启动超时 / 看门狗 | dio 10 s / 60 s；gal 18 s / 60 s |
| iAP2 连接超时 | 6000 ms |
| CarPlay 可用性等待 | 60000 ms |
| 屏幕（测试用 HMI 值） | screen 1540×720，window 1376×650，物理 235×110 mm，`displayName "Audi MMI"` |
| 会话时限 | setup 10 s、start 10 s、startService 15 s、重试 4 次 |
| 身份 | model `MIB3`、manufacturer `DELPHI`、`name "Audi MMI"`、`productPlanUUID aa04fa75cdba4388` |

## 7. 排查顺序建议

1. **先确认 coding**：这车有没有 CarPlay 编码 + FEC。没有的话后面全部无意义（门 1）。
2. **确认没被 USB 黑名单吞掉**：VID 是否命中 `ignoredDeviceList`、是否挂在 Hub 后面（门 2）。
3. **看是否进入“测试”**：有 `Starting CarPlay test for deviceID=` 说明门 2 过了。
4. **看 required 信息是否攒齐**：卡在 `Waiting for required IAP2 information` 就是门 3 的
   身份/集成条目问题（这也是前面几轮反复查的点）。
5. **看 StartSession 是否发出**：`<DIO> Skipped, ...` 五连里中哪一条，直接对应门 4 的缺口。
6. **看屏幕接管**：`Failed to overtake the screen: "<reason>"` 里的 reason 是 HMI 给的原文，
   比任何猜测都准（门 5）。

## 8. 证据索引（VA 以各模块自身为基准）

| 证据 | 模块 | 地址 |
| --- | --- | --- |
| `activateCarPlayUSBIfSupported` | SI | `0x1a2f50` |
| required 信息 `hasInfo 0x10 / 0x200` | SI | `0x1a4a80` |
| `isGrantedByCoding` | SI | `0x14ec30` |
| `processSuccessfulCarPlayTest` | SI | `0x1ac190` |
| “跳过测试”三句 | SI | `0x1ac5e0` / `0x1aca70` / `0x1abf6c` |
| `trySendCarPlayStartSession` | dio | `0x2c1db0` |
| `onJob_requestUI` / overtake | dio | `0x312a50` |
| 屏幕状态机 | dio | `0x315360` |
| 配置全文 | — | 镜像内 `smartphone_integrator` / `dio_manager` / `gal` composition |
