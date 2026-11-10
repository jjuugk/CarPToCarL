# 通用系（Cinemo）卡在「获取 WiFi」：0x4300 少了一半 + 设备名是「未知设备」

> 现场：1.2.26-test-2 · 2026-11-07 10:29~10:36 · `CarPToCarL-log-20261107-103708.txt`
> 车机：`BUICK_8948` / model `E2LB` / manufacturer `Saic General Motors` / `CinemoBT|WIFI|HIDRemote`
> 手机：HONOR BKQ-AN10 / Android 17

## 1. 现场证据

| 时间 | 现象 |
| --- | --- |
| 10:32:14.715 | `BT-C: ✓ 已连入 BUICK_8948 的无线 iAP2 服务（RFCOMM 建立）` |
| 10:32:15.046 | `rx=0x1d01` 车机身份到手；发送清单含 `0x6800/0x5000/0x4c00/0x4c05/0x4301`，可收清单含 `0x4300` |
| 10:32:15.141→15.644 | MFi 全通（`0xaa00/01/02/03/05`） |
| 10:32:15.646 | `0x4e0d/0x4e0a/0x4e09/0x4e0b/0x4e0c` 问候，其中 **`0x4e09 = "未知设备"`** |
| 10:32:17.647 | `0x4300` 发出（只带 group1） |
| 10:32:17→10:35:50 | 车机**每秒一条 `ff 55 02 00 ee 10`**（链路 ACK），此外**一条 CSM 都不发**：没有 `0x6800`、没有 `0x5000`、没有 `0x4c00`、没有 `0x4301` |
| 10:32:25.647 | 代码判「8s 内零反馈」，换老版平铺体，仍然零 CSM |
| 车机屏幕 | 蓝牙列表里这台手机显示 **「未知设备 · 已连接」** |

两条结论：

1. **链路是好的**。`ff 55 ... ee ..` 是链路层 ACK/心跳，说明每一帧都被收下并确认——不是「车机没收到」，
   而是「车机收到了却不推进」，连对任何 iPhone 都会发的 `0x5000 StartNowPlayingUpdates` 都没发。
   这比「拆链重连」更严重：车机的 iAP2 **设备会话压根没开起来**。
2. **`0x4e09` 发的是占位串**。`未知设备` 的 UTF-8 就是日志里那串
   `e6 9c aa e7 9f a5 e8 ae be e5 a4 87`，与车机屏幕显示的完全一致——车机只是把我们给它的名字照抄上屏。

## 2. 根因之一：0x4300 只发了「一段 group」

对照同源工程 `link_plus`（`iap2/Iap2SenderSession.kt` 的 `modernBootstrap` 分支）：

```kotlin
// link_plus：新版车机
val wiredIdentifier    = "00008110${mac.replace(":", "").uppercase()}0000"
val wirelessIdentifier = mac.lowercase()
availability =
    Iap2Csm.group(0, u8(0, 1), string(1, wiredIdentifier)) +      // ← 有线标识
    Iap2Csm.group(1, u8(0, 1), string(1, wirelessIdentifier))     // ← 无线标识
```

我们（`AvailabilityBodyMemory.Variant.MODERN_NESTED`）只发了第二段：

```kotlin
Iap2Csm.group(1, u8(0, 1), string(1, bluetoothMac))              // 只有无线标识
```

也就是说：我们抄的「新版嵌套体」其实是 link_plus 的**旧版分支**形态，少了一个 group。
`group0`（`00008110`+MAC+`0000`）是车机判定「这台设备是 CarPlay 设备」的有线标识；
缺它时这台 Cinemo 车机只回链路 ACK，不发起任何设备会话——正好对上现场。

修法（1.2.28）：

| # | 改动 | 位置 |
| --- | --- | --- |
| 1 | 新增 `Variant.DUAL_GROUPS`：两段 group 一起发（无线标识用 MAC **小写**） | `AvailabilityBodyMemory` / `Iap2SenderSession.sendAvailability` |
| 2 | 新增 `GM_ORDER`：通用系先试双标识，静默后仍回退 `MODERN_NESTED → LEGACY_FLAT → NONE` | 同上 |
| 3 | 通用系判据：身份文本含 `general motors`，或 `0x1D01` 原文含 `Cinemo` | `isCinemoStack()` |

只给通用系开这一档：奥迪（0x5702/0x5703）、宝马（收到 0x4300 立刻回 0x4301）、DiPlay 测试台
现在都是通的，不动它们的顺序。踩过的坑记在这里——修一台车不能把已经跑通的链路改坏。

## 3. 根因之二：设备名是「未知设备」

`DeviceIdentity.deviceName()` 旧实现：

```kotlin
adapter.name?.takeIf { it.isNotBlank() } ?: "iPhone"
```

MagicOS 在「本地蓝牙名未设置」时 `BluetoothAdapter.getName()` 返回的是**本地化占位串**
`未知设备`，`isNotBlank()` 放行，于是：

- `0x4e09` 把 `未知设备` 发给车机 → iAP2 会话内车机就这么称呼我们；
- 车机蓝牙列表用的是**配对时的蓝牙名**，同一个占位串 → 屏幕上「未知设备 / 已连接」。

修法（1.2.28）：

| # | 改动 |
| --- | --- |
| 1 | `DeviceIdentity.displayName()` 过滤占位串（`未知设备 / 未知 / 未命名 / unknown / null / N/A`…） |
| 2 | 依次尝试适配器名、`Settings.Secure.bluetooth_name`、`ro.product.marketname`、`Build.MODEL`，最后用应用名兜底（现为 `CarPToCar+ICCOA`） |
| 3 | `ensureUsableBluetoothName()`：适配器名是占位时 `setName()` 改成可用名（进程内一次），让车机列表也能更新 |

`Android` 不算占位串（AOSP 默认名），不做改动。

## 4. 复测怎么判读

使用当时的测试包 `dist/CarPToCarL-1.2.28-test-1.apk` 连车时，预期日志如下。

```
BT: 本机蓝牙名是占位「未知设备」——已改名为「XXX」          ← 改名生效
iap2 0x4300 报文体形态：新版双标识体（… · 通用系 Cinemo：双标识优先）
iap2 tx=0x4e09 …（name=XXX mac=…）                      ← 不再是「未知设备」
```

随后二选一：

| 日志 | 结论 |
| --- | --- |
| `iap2 rx=0x4301 …` | 双标识体就是缺的那一段 → 直接进热点/配对，问题解决 |
| 仍是只有 `ff 55 02 00 ee 10` | 车机还在等别的东西（见 §5），下一步要抓车机侧或走手动入网 |

## 5. 兜底：手动入网这条路还没试过

整份日志里手机 **从未连上任何 WiFi**（`Wi-Fi=已开但未连接任何网络`），所以代码里
「检测到手机已连热点 → 问你是否按车机热点继续」这条分支一次都没跑到。

车机屏幕「连接 → 车辆热点」页能直接看到 SSID 与密码。手动连上车机热点后再点「启动连接」，
App 会合成端点走 mDNS `_airplay._tcp` / ctrl 邀请，**完全不依赖 0x4301**。
这条路能独立验证「AirPlay 这一侧我们是否已经具备条件」，值得与双标识体一起试。

## 6. 第二轮实测（12:22–12:30，装了 1.2.27 真包）：三种报文体全部被 ACK、全部无回应

日志 `CarPToCarL-log-20261107-123100.txt`（6 轮重连，版本行仍是 `1.2.27`）。这一轮
**双标识体与设备名修复确实生效**：`name=BKQ-AN10`、`… · 通用系 Cinemo：双标识优先）`。
但报文体阶梯已经走到第三步——历史静默档里 `DUAL_GROUPS` 与 `MODERN_NESTED` 都被记过：

```
iap2 0x4300 报文体形态：老版平铺体 DeviceIdentifier（该车机历史静默档=DUAL_GROUPS/MODERN_NESTED）
```

6 轮里车机一共只发过三条消息：`0x1d01`（身份）、`0xaa01`（证书）、`0xaa03`（签名）。
我们发的每一帧它都 ACK（`ack=112` / `121`…全部到位），但 `0xaa06 / 0x6800 / 0x5000 /
0x4c00 / 0x4301` 一条都没有。

**结论：0x4300 报文体不是根因，三种形态都到此为止，不要再试第四种。**

值得留意的细节（可作为后续判据）：

- 无线单标识体（`MODERN_NESTED`）发出后，车机**连链路 ACK 都停**（11:37 那轮 seq=111
  之后 23 秒零 ACK）；有线标识 / 双标识由它照收。所以「0x4300 那一帧有没有被 ACK」
  比「8 秒内有没有 0x4301」更早、更硬地反映它认不认这一帧。
- `0x4300` 被 `awaitOptionalHid` 的 2 秒等待压到 `0x1d02` 之后约 2.7 秒才发，比参考实现
  `link_plus` 晚（它发完问候立刻发）。若后续还要动 iAP2 时序，这是第一嫌疑。

## 7. 这一轮暴露的真 bug：SSID 读不到 → 手动入网这条兜底永远走不到

等待期日志一直写 `Wi-Fi=已开但未连接任何网络`，可手机其实挂在 `192.168.9.15` 上
（`WIFI: wifi=[wlan0/192.168.9.15] default=wlan0/…`）。根因是 `shellWifiStatus()`
只走 `cmd wifi status`：Shizuku 移除后普通应用身份跑不了这条命令，永远返回 SSID=null。

而「检测到已连接热点 → 是否按车机热点继续」这个弹窗**要求 SSID 非空**才触发——
于是唯一没试过的那条路（手动连车机热点 + mDNS 邀请）在代码里被堵死了。

修法：`shellWifiStatus()` 增加框架侧兜底 `frameworkWifiStatus()`（`ConnectivityManager`
的 `NetworkCapabilities.transportInfo` → `WifiInfo.ssid`，只要定位/附近设备权限，不需要
任何特权），并加构建标记 `1.2.27 #wifi-ssid` 让日志能区分同版本号的不同测试包。
