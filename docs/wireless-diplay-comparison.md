# 无线 CarPlay：DiPlay（车机侧）与我们（手机侧）的逐层对照

对照对象：`shihabal3amri/DiPlay`（GPL-3.0，Kotlin，Android 车机上的 CarPlay **接收端**）。
我们 `CarPToCarL` 是**发送端**（手机假冒 iPhone）。两端互为对端，所以 DiPlay 的代码就是一份
「车机期望手机做什么」的现成说明书 —— 比任何文档都硬。

结论先说：**控制面时序逐条对上了，而且本项目已经拿 DiPlay 当过测试台**（代码里三处实测痕迹：
DiPlay 只接受 32B MFi 挑战、认证前不能发 `0xEA00`、AirPlay 内 iAP2 隧道照 `IapTunnel` 实现）。
真正缺的是**一层网络接入方式**和**一块功能**，不是协议。

## 1. 逐层对照

| 阶段 | DiPlay（车机侧）怎么做 | 我们（手机侧）怎么做 | 状态 |
|---|---|---|---|
| iAP2 隧道建立 | RFCOMM 上跑 CSM 分帧 | `Iap2LinkChannel` + `Iap2Csm.Parser` | ✓ |
| 识别 | 发 `0x1D00`，等 `0x1D01` 回 `0x1D02` | 收 `0x1D00` 解析清单，回 `0x1D01`/`0x1D02` | ✓ |
| MFi | 发 `0xAA00` → 回 `0xAA01`（BAA 叶子+中间证书）→ 收 `0xAA02` 挑战 → 用 MFi 协处理器签名回 `0xAA03` | 发 `0xAA00` 要证书、发 `0xAA02` 挑战、收 `0xAA01`/`0xAA03`，**不验签**，自报 `0xAA05` | ✓ |
| 订阅 | 连发 5 条：`0x5000` `0x5200` `0x5400` `0x4157` `0x4154` | 逐条 ACK；`0x5000` 回 `0x5001` | ✓（`0x5200` 只 ACK，见 §3.2） |
| 热点凭据 | **收到 `0x5702` 才发 `0x5703`**（param0 BSSID / 1 SSID / 2 密码 / 3 安全 / 4 信道） | 发 `0x5702`；按 **param id** 解析 `0x5703`，忽略 param0 | ✓ |
| 启动会话 | **收到 `0x4300` 才发 `0x4301`**（无线组 ssid/密码/信道/IP/安全 + 端口 + deviceIdentifier + publicKey + sourceVersion） | 发 `0x4300`（`param1 = group{u8(0,1), string(1,mac)}`）；按 id 解析 `0x4301` 的 1~5 | ✓ |
| 无线通知 | 收 `0x4E0D`（u8 0/1）；收 `0x4E0E`（蓝牙 MAC / USB 标识）后**重发一次 `0x5703`** | 发 `0x4E0D` = `u8(0,1)`；`0x4E0E` = `string(0, 蓝牙MAC)` | ✓ |
| 发现（车机找手机） | 浏览 `_carplay-ctrl._tcp`，读 TXT `id`，TCP 连入后发 `GET /ctrl-int/1/connect`（带 `AirPlay-Receiver-Device-ID`），要求 2xx | 广播 `_carplay-ctrl`（TXT `id`=真实蓝牙MAC、`srcvers=950.7.1`、实例名 `sender`），对 `/ctrl-int/1/connect` 回 `200 OK` | ✓ |
| 发现（手机找车机） | 注册 `_airplay._tcp`，TXT：`deviceid/features/flags/model/srcvers/protovers/pi/pk` | 浏览 `_airplay._tcp`，读 `pk`/`deviceid`/`srcvers`/`features`（全部可选，不强求） | ✓ |
| AirPlay 会话 | RTSP 协商、HomeKit 配对、屏幕流、音频流 | `AirPlayClient`：pair-setup/pair-verify、`et=NONE` 兜底、ANNOUNCE/SETUP/RECORD | ✓ |
| iAP2 隧道 | AirPlay `type-130` DataStream，ChaCha 帧；**判定「真连上」的必要条件** | `AirPlayClient.IapTunnelClient`，同为 ChaCha 帧 + APTransportPackage 封装 | ✓ |

需要注意 DiPlay 判定「成功」的口径比我们严：`WirelessConnectionProof` 要求
**隧道已认证 + 已渲染出一帧**才算确认，只建会话不算；隧道起不来时会走
`handoff/fallback` 降级（蓝牙保活继续），历史记录里不记为成功。

## 2. 安全性类型：4 档全部对得上

DiPlay 的 `Iap2WirelessSecurity` 是 `NONE(0) / WEP(1) / WPA_WPA2(2) / WPA3_TRANSITION(3) / WPA3_ONLY(4)`。
我们的 `WifiCarPlayConnector.buildSpecifier` 用同一套编号：0 开放、1 WEP 走旧路径、
2/3 用 `setWpa2Passphrase`、4 用 `setWpa3Passphrase`。**没有隐藏的枚举错位。**

## 3. 真正缺的东西

### 3.1 Wi-Fi Direct 组加入（唯一会让整条链直接断的缺口）

DiPlay 有四种网络后端：

| 后端 | 形态 | 我们能不能加入 |
|---|---|---|
| `MANUAL_HOTSPOT` | 车机 UI 里开的真热点 | ✓ `WifiNetworkSpecifier` |
| `LOCAL_ONLY_HOTSPOT` | Android `LocalOnlyHotspot` | ✓ 同上 |
| `EXISTING_WIFI` | 手机和车机在同一局域网 | ✓ 同上 |
| **`WIFI_P2P`** | **Wi-Fi Direct 组（GO 建组，SSID 形如 `DIRECT-xx-...`）** | **✗ 没有路径** |

我们全项目只有 `WifiNetworkSpecifier`（普通站模式），没有任何 `WifiP2pManager` 调用。
P2P GO 不是普通 AP：Android 把它交给 `WifiP2pManager` 管，普通站模式即使拿着 SSID+密码
也连不进去（`WifiNetworkSpecifier` 匹配不到；`DIRECT-` 前缀的 SSID 属于框架保留域）。
而 `0x5703`/`0x4301` 只给 SSID/密码/BSSID，**给不出 P2P 的 deviceAddress**，所以也没法
退化成 `WifiP2pManager.connect()`。

影响面：自带热点的车（Audi MIB3、别克那类）走 `MANUAL/LOCAL_ONLY`，不受影响；
**接收端选 Wi-Fi Direct 才会踩**。DiPlay 文档里 Wi-Fi Direct 是正式支持的路径
（Android 9 走 legacy group API、Android 10+ 校验实际频率），不是实验功能。

### 3.2 导航更新一条都没发（`0x5200` 之后）

DiPlay 在订阅批次里发 `0x5200 StartRouteGuidanceUpdates`，参数是
`u16(0, 42) + void(1) + void(2)`，并且车机侧**真的实现了渲染**：

- `Iap2LaneGuidance.kt` —— 车道信息解码
- `ClusterTurnCardOverlay.kt` —— 仪表转向卡片
- `ClusterSpeedometerOverlay.kt` —— 仪表速度环
- 资源里一整套 `ic_maneuver_*.xml` / `ic_turn_card_*.xml`（左转、右转、环岛、掉头…）

我们这边：`0x5200` 常量在目录里有，收到只 ACK，**从没发过** `0x5201 RouteGuidanceUpdate` /
`0x5202 RouteGuidanceManeuverUpdate` / `0x5204 LaneGuidanceInfoUpdate`。

这正是之前那条「MIB3 4398 导航指针上仪表」的路。DiPlay 的存在把这件事从推测变成了实证：
**车机收到导航更新就自己在仪表/HUD 上画箭头，数据源是手机的 iAP2，与车机自带导航授权无关。**
上游：手机发 maneuver（转向类型/距离/路名）+ 车道，车机自绘。

### 3.3 `0x4301` 的 param7 / param8 没解析

我们只读 param 1~5。DiPlay 还能发：

- **param7 `clusterAsset`**：`{string(0, assetID), u32(1, assetVersion)}` —— 车机告诉手机
  用哪套仪表图形资源。要投仪表就要用这个版本号对齐。
- **param8 `mutualAuth`**：u8，1 = 要求双向认证。我们目前没有对应处理；真遇到会停在
  「0x4301 拿到了但 AirPlay 认证不过」这种最难查的位置。

### 3.4 时序窗口：`0x4300` 发在 WiFi 加入之前

DiPlay 一收到 `0x4300` 就发 `0x4301`，并启动 `FirstTcpWatchdog`：`FIRST_TCP_MILLIS = 30s`
内必须看到首个 AirPlay TCP 接入，否则判 `FIRST_TCP_TIMEOUT` 并重试（最多 5 次）。

我们发 `0x4300` 是在 bring-up 内部，WiFi 加入 + 广播 `_carplay-ctrl` 在 bring-up 返回之后。
正常几秒内能完成，30 秒够；但如果加入热点时弹出系统确认框（`WifiNetworkSpecifier` 在
部分机型首次连接会要求用户确认），这段就可能吃掉整个窗口。**不是确定会炸，但是个可测的风险点。**

## 4. 看着像缺、其实不缺

| 项 | 为什么不用管 |
|---|---|
| `0x5700/0x5701 WiFiSharing` | 方向是「配件问手机自己的 Wi-Fi」，DiPlay 从不发 `0x5700` |
| `0x4E0E` 的 param1（USB transport identifier） | DiPlay 用 `optionalString(1)` 读，缺了只是日志显示 `none`，不参与判断 |
| `0x5703` 的 param0（BSSID） | DiPlay 发，我们按 param id 取值、直接忽略，不会误解析 |
| `_airplay._tcp` 的 `pi`/`protovers`/`flags` | 我们只读 `pk`/`deviceid`/`srcvers`/`features`，且都是 `?.let` 可选 |

## 5. 若要补，优先级

1. **Wi-Fi Direct**：只在「接收端用 P2P」时才是硬阻塞，但一旦踩到就是 0 或 1，没有中间态。
   要做就得引入 `WifiP2pManager` + 从扫描结果反查 GO 的 P2P deviceAddress（BSSID 通常等于
   GO 接口地址，可试 `bssid ± 1` 或从 `WifiP2pDeviceList` 按 SSID 匹配）。
2. **导航更新**：改动量比想象小 —— 只是「发一条结构化 maneuver」，不碰视频编码。
   前提是先在真车日志里确认可收清单有 `0x52xx`（`0x1D01` 的能力清单日志已经能直接看出来）。
3. **param7/param8**：跟随上面的工作一起做，解析成本很低，主要是决定收到 `mutualAuth=1` 怎么办。

## 6. 证据位置

DiPlay 侧关键文件（对照时下载到 `.workbuddy/diplay/files/`，不入库）：

- `transport/Iap2WirelessControlClient.kt` —— 无线控制循环全时序
- `iap2/message/Iap2Messages.kt` —— `0x4301`/`0x5703`/`0x4E0D`/`0x4E0E` 的字节级构造
- `iap2/catalog/Iap2Endpoint.kt` —— 消息 ID、方向、字段的权威目录
- `iap2/wire/*` —— CSM 分帧
- `network/CarPlayBonjour.kt` —— `_carplay-ctrl` 探测 + `_airplay` 广播 + TXT
- `network/WirelessHotspotManager.kt` —— 四种后端的定义
- `orchestration/WirelessConnectionProof.kt`、`FirstTcpWatchdog.kt` —— 「成功」判定口径
- `docs/SMOOTH_WIRELESS.md`、`docs/WIRELESS_DIAGNOSTICS.md`、`docs/COMPATIBILITY.md` —— 实测与方法
