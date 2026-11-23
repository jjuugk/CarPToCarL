# 反编译 `CarWith-CarPlay-My-Cadillac`（release-1dafe966）对照笔记

对象：`C:\Users\jjuugk\Downloads\CarWith-CarPlay-My-Cadillac-release-1dafe966.apk`
手法：`unzip` 解包 → `dexdump -d`（build-tools 34）反汇编 → 按类切片读逻辑。
反汇编产物在 `C:\Users\jjuugk\Desktop\cardeck\.tools\cd_classes*.txt`，辅助脚本
`dcls.py`（按类名切）、`dmeth.py`（按方法名出反汇编）、`mpseudo.py`（按方法出"常量字符串 + 调用"骨架）。

## 0. 包信息

| 项 | 值 |
|---|---|
| package | `com.baidu.carlife.xiaomi`（CarLife 车机端包名 + 小米定制） |
| versionName | `8.1.1-carplay.B`（BuildConfig 里 build label = `carplayB`） |
| 应用名 | `CarWith CarPlay 组件` |
| main activity | `io.github.iamr0s.carplay.provider.ProviderActivity` |
| minSdk / target | 29 / 37 |
| 原生库 | `libcarplay_eir_hook.so`（蓝牙 EIR/SDP 改写）、`libandroidx.graphics.path.so` |
| 特有前缀 | 大量 `Ct5*` 类（CT5 = 凯迪拉克 CT5 整车项目代号） |

类结构：`airplay/`（AirPlay 发送端：视频/音频/麦克风/触控）、`protocol/`（iAP2 与无线引导）、
`network/`（mDNS + 车机热点）、`bt` 相关（`ConnectionService`、`Ct5RfcommServer`）、
`bridge/`（Hook CarWith 拿画面/按键）、`desktop/`（自建 Compose 桌面）、`hook/`（LSPosed 模块）、
`provider/`（CT5 专用 UI/启动策略）、`video/`（H.264 编码与触摸 HID）。

它**和我们 CarPToCarL 是同一侧**：都演"iPhone/device"角色，蓝牙 iAP2 拿 Wi-Fi 凭据 → 连车机
AirPlay 收屏。所以下面的差异可以直接对照 `Iap2SenderSession` / `Iap2BluetoothClient`。

## 1. 车型/连接配置（"连接车型里面配置了什么"）

### 1.1 UI 上能配的（`provider/Ct5Ui`）

界面标题 `CarWith CarPlay` / 副标题 `凯迪拉克 CT5 · 无线投屏`。
「连接」分组下面三项（`profileRow` / `keepBluetoothRow` / `audioBufferRow`）：

| 配置项 | 取值 | 存放 |
|---|---|---|
| 车型档位 | `23款及以前` / `24款及以后` / `DiPlay模拟车机` | `ct5-vehicle` 偏好 `generation`(int) |
| 保留蓝牙连接 | 开/关 | `ct5-vehicle` 偏好 `keep-bluetooth`(bool) |
| 音频缓冲容量 | int | 另外的偏好 |

「设置」里还有：手机蓝牙地址输入（读不到系统地址时手填，键 `bluetooth-address`）、
`允许 CarWith 启动和自动重连`（`ProviderStore.enabled`）、`关闭电池优化`。
另有按钮：`打开 CarWith`、`选择已配对车机`、`停止本次连接`、`直接连接车机（诊断）`。

进度分 5 段（`STAGE_LABELS`）：`蓝牙 RFCOMM` → `iAP2 认证` → `Wi-Fi 接入` → `AirPlay 会话` → `视频 / 音频`。

### 1.2 真实生效的协商档位（`protocol/Ct5NegotiationProfile`，偏好文件名 `ct5-vehicle`）

偏好键：`profile`、`generation`、`modern-variant`、`keep-bluetooth`。
三档 = `PROFILE_NAMES = ["legacy-base", "modern-base", "diplay-order"]`，
`generation` 直接映射到档位：0→0、1→1、2→2（`profileFor`）。
`selection` = `0/1/2`（`23款及以前` / `24款及以后` / `software-receiver`），
`variant` 只有第 2 档是 `1`（`diplay-order`），其余为 `0`（`base`）。

每个档位实际改的东西：

| 行为 | 23款及以前 `legacy-base` | 24款及以后 `modern-base` | DiPlay `diplay-order` |
|---|---|---|---|
| 发 `0x4300`（CarPlay 可用性） | **不发** | 发 | 发 |
| `0x4300` 报文体 | —（不发） | **新版嵌套体**：`param1 = {param0={0x01}, param1=transportId}` | **老版平铺体**：`{0x01}` + DeviceIdentifier，同一内容**原样 + 全小写各发一份** |
| 可选消息按车机声明过滤 | 否 | **是**（只对 24 款） | 否 |
| 协商顺序 | 认证优先（`0xAA00`→期望 `0xAA01`） | 同左 | **识别优先**（`0x1D00`→期望 `0x1D01`） |
| keyframe 间隔 / 重发前一帧除数 | 5 / 20 | 1 / 30 | 5 / 20 |
| marker echo（链路检测标记回显） | 否 | 是 | 否 |
| 自建桌面 | 允许 | 只按 README 视频策略 | 允许 |
| 保留 bootstrap 蓝牙 | 由 `keep-bluetooth` 决定 | 同左 | 同左 |

`23款` 档**根本不发 0x4300**——说明这套方案自己也认为有一批车机不吃 0x4300。

### 1.3 硬编码的车型常量

| 常量 | 值 | 用途 |
|---|---|---|
| `ACCESSORY_UUID` | `00000000-deca-fade-deca-deafdecacaff` | 主动连车机 iAP2（我们用的同一个） |
| `PHONE_UUID` | `00000000-deca-fade-deca-deafdecacafe` | 注册本机 SDP 服务名 `CarPlay Probe`，让车机连进来 |
| `PHONE_EIR_UUID` | `2d8d2466-e14d-451c-88bc-7301abea291a` | 注册 `CarPlay Identity Probe`（只占 SDP 位，随即关掉，注释：`no protocol handler`） |
| RFCOMM 固定通道 | **29** | `Ct5RfcommServer.listen(adapter, 29)`，反射 `listenUsingRfcommOn(29 / (29,false,false))` |
| 设备名（`0x4E09`） | `CarPlay Android` | 我们发的是 `iPhone` |
| 语言（`0x4E0A`） | `zh` | 我们发 `zh-CN` |
| DeviceIdentifier | `00008110` + 本机 MAC（去冒号大写）+ `0000`；读不到 MAC 时兜底 `00008110000105201EDA801E` | `0x4300` / 老版可用性报文体 |
| HFP 身份（可选开关，默认 observe） | `AT+CGMI→Apple Inc.`、`AT+CGMM→iPhone`、`AT+CGMR→iOS 26.0` | 骗车机"配对的是 iPhone" |
| EIR/SDP 改写（可选开关，默认 observe） | 把 SDP 里 phone 记录改成 `cafe` + `0x1101`(SPP)，**保留原 RFCOMM 通道**；hook `libbluetooth_jni.so` 的 `WriteEIR`(rva 0xb02c80) / `SDP_AddSequence`(rva 0x8ceaa8) | 只对小米 `pudding` + 固定 SHA256 `047c9421…` 生效，lease 180s |
| 消息 id 常量（`Ct5Variants`） | `0x4300` 可用性 / `0x4E09` 名 / `0x4E0A` 语言 / `0x4E0C` UUID / `0x4E0D` 无线更新 / `0x4E0E` 传输标识 / `0x5702` 请求 Wi-Fi / `0x4301` 启动会话（带 AirPlay 端点） | 与我们同名同义 |

`0x4301` 就是"带 Wi-Fi 凭据 + AirPlay 端点"的那条：`WirelessMessages.start()` 里
`require(id == 0x4301)`，随后解出 `ssid / password / security / channel / addresses / port /
receiverId / receiverPublicKey`。

## 2. 与我们本地实现的差别

### 2.1 连接方向：我们是单向主动，它是**双向 + 自动回退**

我们（`Iap2BluetoothClient`）只有一条路：`createRfcommSocketToServiceRecord(…caff)`。
它（`ConnectionService$onStartCommand$4`）两条：

- 主动：`createRfcommSocketToServiceRecord(…caff)`，日志 `Outbound accessory RFCOMM; secure SDP lookup`；
- 被动：注册 `listenUsingRfcommWithServiceRecord("CarPlay Probe", …cafe)` +
  `Ct5RfcommServer.listen(adapter, 29)`，日志 `Inbound iAP2 phone SDP listener registered uuid=…`。

关键在**回退**：主动建连失败时，除非是认证类失败，否则
`RFCOMM outbound establishment failed before iAP2; automatic direction fallback to car-initiated
transport`，直接切被动等车机连进来（等待窗 60s / 120s，`等待车机主动连接（最长 N 秒）`）。
这正是我们"雪铁龙 R4 / 凯迪拉克连不上"那一层的解药：车机不自带无线 CarPlay 也不给 `…caff` 时，
换个方向还有机会。

### 2.2 候选消息按车机声明过滤（我们完全不看）

`WirelessBootstrap.publish()` 先读车机 `0x1D00` 的收发清单，然后：

```
negotiation profile=MODERN skip=0x4E0E reason=accessory-did-not-advertise
negotiation profile=MODERN skip=0x4E0D reason=accessory-did-not-advertise
bootstrap skip optional deviceUUIDUpdate=0x4E0C; accessory did not advertise receiving it
```

只有 `24款及以后` 档才开这层过滤（`ct5FilterOptional`），但逻辑本身很值得抄：
**车机没声明"能收"，就不发。**
我们 `sendDeviceStateGreetings()` 是 `0x4E0D/0x4E0A/0x4E09/0x4E0B/0x4E0C` 全量硬发，
催问又补 `0x4E0E` + `0x4300`。凯迪拉克 CADILLAC_7731 的接收清单是
`0x6801 0x4c01 0x4c04 0x5001 0x4e09 0x4e0a 0x4e0b 0x4e0c 0x4300`——**`0x4E0D`/`0x4E0E` 不在里面**，
我们每轮都在给 Cinemo 栈喂它没说要的 `0x4E0E`。

### 2.3 认证/识别顺序可切

`WirelessBootstrap.begin()`：`identification-first` 时 `request=0x1D00 expected=0x1D01`，
否则 `request=0xAA00 expected=0xAA01`。我们固定 MFi(`0xAA00`) 先行。

### 2.4 拿到网络配置后**拆链重启**

`WirelessBootstrap.resetForLinkRestart()`：`Link restart after network configuration requires
transport teardown`、`link-restart epoch=N`、`Accessory link restart limit reached`，
即 `0x4301` 里带回网络配置后主动拆掉 RFCOMM 再重建（有次数上限）。
我们是拿到 `0x4301` 后继续在蓝牙上保活。

### 2.5 链路层的姿态：等车机先发

`Iap2Probe`：`Hangsheng responder: await accessory detection; reply once; await accessory SYN;
no bare phone SYN fallback`、`Reliable vendor transport: await accessory SYN; no Bluetooth marker`。
它把自己当 responder：等车机检测标记 → 回一次 → 等车机 SYN，**不主动裸发 phone SYN**。
我们这边是先发 SYN/问候的主动方。链路层谁是发起方跟车型有关，可当备用开关做。

### 2.6 身份伪装是"可选开关"，默认关

UI 明确写着「启用 Native Hook / root 辅助（默认关闭）」，日志里也是
`default=observe`、`HFP identity installed revision=hfp-identity-v1; defaults to observe`。
所以它的**主路径并不依赖** LSPosed/root 改 EIR/HFP，靠的是 2.1~2.4。
把"改 EIR 冒充 iPhone"当作它默认在用的手段会误判。

## 3. 可以直接试的三件事（按性价比）

1. **按车机声明裁剪可选消息**：读 `0x1D00` 的接收清单，`0x4E0D`/`0x4E0E`/`0x4E0C`
   没声明就不发（凯迪拉克那台就不声明 `0x4E0D`/`0x4E0E`）。我们现在的催问正是在反复喂这两条。
2. **`0x4300` 报文体加一档实验**：现在的 body 是 transportId（MAC）。它准备了两种：
   老版 `param0={0x01}` + `00008110+MAC+0000`（大小写各一份）、新版嵌套体。老版车机可能只吃其中一种。
3. **被动方向兜底**：主动连 `…caff` 失败（且非认证失败）时，注册 `…cafe`（或固定通道）等车机连入，
   超时再回主动。雪铁龙那条 `SDP 里没有 …caff` 的结论，正好是这条要救的场。

## 4. 已落地（bieke 分支）

### 4.1 按车机声明裁剪可选消息 —— `Iap2SenderSession`

`accessoryCanReceive(id)`：车机在 `0x1D01` 参数 7 里**明确没列**该消息就不发，并打一条
`iap2 跳过 0x4e0e (DeviceTransportIdentifier) reason=accessory-did-not-advertise`。
清单未提供（老车机不声明）时一律放行，行为与旧版完全一致。

裁剪范围刻意收窄到**通知类**三条，`0x4300` 永不裁：

| 消息 | 位置 | 裁剪 |
|---|---|---|
| `0x4E0D` 无线可用 | `sendDeviceStateGreetings()` | 是（凯迪拉克不声明） |
| `0x4E0C` 设备 UUID | `sendDeviceStateGreetings()` | 是 |
| `0x4E0E` 传输标识 | Phase 1 收尾 + `nudgeIfDue()` | 是（凯迪拉克不声明） |
| `0x4E0A/0x4E09/0x4E0B` | 问候 | 否（身份类，必发） |
| `0x4300` 可用性 | Phase 1 + 催问 | **否**——它是驱动 `0x4301` 的请求本身，宁可多发 |

参考实现自己也是这么划界的：`ct5FilterOptional` 只对 24 款档的通知类消息生效。

### 4.2 被动方向兜底 —— `Iap2BluetoothClient`

`tryConnect` 现在区分「SDP 层失败」（`createRfcommSocketToServiceRecord` 直接抛 =
车机根本没广播 `…caff`）与「连上了被拒/超时」。同一轮里只要有设备是前者，累计一轮；
连续 `PASSIVE_FALLBACK_ROUNDS = 3` 轮（≈9s）后进入 `awaitCarInitiated()`：

- 停止一切主动 `createRfcomm` 尝试，**让出通道**；
- 每 2s 检查 `BtSessionGate.inboundActive / sessionActive`（本机 `…cafe` 监听由
  `Iap2BluetoothServer` 一直开着），车机一旦连入立刻回主循环；
- 60s 内无人连入则回主动方向，并打一条结论性提示
  「请确认车机是否启用无线 CarPlay（仅支持有线的车型不会有任何无线 iAP2 服务）」。

不做的部分：参考实现还会反射 `listenUsingRfcommOn(29)` 抢固定通道，并为小米
`pudding` 改写 EIR/SDP 记录——那两招依赖 root/机型白名单，与本项目「不 root」的前提冲突，
故只做方向切换。

对应日志（新）：
```
BT-C: 主动连 …caff 连续 3 轮在 SDP 层就失败（车机没广播配件端服务）——切换为等车机主动连入，最长 60 秒
BT-C: 被动窗口内车机主动连入——不再探测 …caff         // 或
BT-C: 被动窗口 60 秒内车机没有连入——回到主动方向重试；若长期如此，请确认车机是否启用无线 CarPlay…
```

测试：`Iap2SenderSessionTest.newProtocolCarWithoutCredentialsKeepsLinkForLateStartSession`
改为「静默窗口 8.6s + 禁止消息断言」，既保住「不拆链、晚到补发」的原意，又把裁剪行为锁进用例
（202 用例全绿）。
