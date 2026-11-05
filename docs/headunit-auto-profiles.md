# 自动识别车机并选档（switch-pool-auto）

目标：不增加任何手动开关，按车机身份自动选择**等待窗口/资源声明**组合。铁律与
AMCar 的 `HuPreset` 一致：**只调参数，不改协议**——不改变报文顺序、收发内容，不跳过握手。
因此识别错只会留下一条日志，不会让本来能连的车机连不上。

## 识别信号

按到达顺序，后到的强信号覆盖先到的弱信号：

| 信号 | 来源 | 强度 | 用途 |
| --- | --- | --- | --- |
| 固件代际 `CLxx` | iAP2 `0x1D01` 参 4（如 `CL37_MIB3H_...`） | 强 | 直接判 CL35/37/39/3D |
| 蓝牙目标名 | 系统配对列表 / `TargetDevicePrefs` | 中 | 在 `0x1D01` 之前先识别品牌/代际 |
| AirPlay `/info` | `model` / `sourceVersion` | 弱 | iAP2 缺失时补品牌，**不允许降级**已识别的固件代际 |

识别结果按「蓝牙目标地址 + 身份串」落盘（`headunit_auto_v1`）。换目标车机立刻回到
通用参数，避免把上一台车的窗口套到新车上。

## 档位表

| 档位 | 入网 | ctrl 邀请 | 等 0x4301 | 绑网 | 重激活 | 无条件重声明 | 画面声明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 通用（未识别） | 15s | 4s | 5min | 15s | 2.5s | 否 | 跟随 `VideoCanvasMode` |
| CL37（3801/3874） | 15s | 4s | 5min | 15s | 2.5s | 否 | 跟随 `VideoCanvasMode` |
| CL39（4420） | 15s | **1s** | **6min** | 15s | 2.5s | **是** | 码流尺寸 |
| CL3D（4580/MIB3P/MIB3H） | **25s** | 4s | **6min** | 15s | **5s** | **是** | 码流尺寸 |
| CL35（3620/4910） | **12s** | 4s | 5min | 15s | 2.5s | **是** | 码流尺寸 |
| BMW / MGU / iDrive | 15s | 4s | 5min | 15s | 2.5s | 否 | 跟随 `VideoCanvasMode` |
| 测试台 / 未知奥迪 | 15s | 4s | 5min | 15s | 2.5s | 否 | 跟随 `VideoCanvasMode` |

- **CL39**：不发 ctrl 邀请、0x4301 可能晚到 2~3 分钟；缩短邀请窗口让连接更快落到 mDNS/旧发现路径。
- **CL3D**：系统确认框常要 5~10 秒；HMI 要等 iAP2 控制会话就绪才认 RECORD，故重激活窗口 5s。
- **CL35**：约 19 秒拆链，入网窗口缩短到 12 秒，让失败在车机放弃前暴露。
- 「无条件重声明」= 即使控制会话已就绪，也重发 `modesChanged` + `RECORD` 一次；只多两条 RTSP 报文。

未识别车机的默认值与主线 1.2.24 逐字段一致，`HeadUnitAutoTest` 已锁定。

## BMW 专用策略（对齐 hypercar `bmw_id85`）

BMW 除了窗口参数，还改变**连接方向与激活判据**（不改任何 iAP2 报文顺序/内容）：

| 行为 | 通用档位 | BMW 档位 |
| --- | --- | --- |
| 主动出站拨号 `…caff` | 是 | **否**（只监听 `…cafe`，等车机连入） |
| 失败后断 HFP/A2DP | 是 | **否**（保留系统音频连接） |
| 激活判据 | 端口/mDNS 可连即可 | **本轮 ctrl 邀请或完整 0x4301** |
| 没有 0x4301 时的端点 | 网关/ mDNS 猜测 | **ctrl 邀请对端 IP + 7000** |
| 档案 A（仅 iPod/BMW Apps） | 抛异常拆链 | **保活不拆链**，等车机切回档案 B |
| 私有消息 `0x4C00..0x4C07` | 只记长度 | **dump 前 512 字节**（可能是凭据载体） |

- `com.bmw.carplay` 只是车机给的 EA 频道；A/B 档案由车机决定，识别到 A 时只记日志、不主动拆链。
- 0xEA00 必须按「协议号 + 协议名」逐条发送；BMW MGU-55 可能声明多个协议号相同的 EA 协议，不能按 id 去重。
- 设备状态问候（`0x4E0D/0x4E0A/0x4E09/0x4E0B/0x4E0C`）与 `0x4E0E` 仍按主线时序发送。
- **首次连接（还没有 `lastGoodEndpoint`）**：邀请本身就是激活信号，用邀请的 TCP 对端 IPv4 +
  7000 合成端点（SSID 取手机当前关联的网络，走 `adoptIfAlreadyConnected` 直接绑定，不用密码、
  不弹确认框）。前提是手机已在该 WiFi 上——否则私网地址没有路由，只记日志继续等 0x4301。
  合成端点带着邀请地址时会**跳过 mDNS 合并**：mDNS 缓存可能残留上一台车机的地址，
  而 `mergeBrowserEndpoint` 会把 mDNS 地址排到最前，等于连错主机。

## 代码位置

- `auto/HeadUnitAuto.kt`：识别、档位映射、进程内当前值、落盘。
- `iap2/Iap2SenderSession.kt`：`0x1D01` → `HeadUnitAuto.observe`；BMW 私有消息 dump、A/B 档案日志、A 档案保活。
- `airplay/AirPlayClient.kt`：`/info` → `observeInfo`；`videoOnlyMode` 控制音频资源声明。
- `net/CarPlayCtrlAdvertiser.kt`：记录 ctrl 邀请对端 IPv4（BMW 无 0x4301 时的端点来源）。
- `net/WifiCarPlayConnector.kt`：`currentSsid()`——邀请合成端点时取手机当前关联的 SSID。
- `bt/Iap2BluetoothClient.kt`：按档位关闭主动拨号、跳过 HFP/A2DP 主动断开。
- `SessionController.kt`：入网/邀请/0x4301/绑网/重激活窗口、画布策略、音频跳过、BMW 激活等待与邀请端点。
- `SenderApp.kt`：启动时恢复上次识别结果。

## 验证

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug --offline
```

现场只需看日志里的 `AUTO:` 两行：本轮使用的档位（含实际窗口）以及 0x1D01 的识别结果。
