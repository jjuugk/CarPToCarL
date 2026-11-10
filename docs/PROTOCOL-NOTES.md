# 无线 CarPlay 发送端协议笔记

> 本项目目标：Android 手机扮演 iPhone（CarPlay 发送端），连接 CarPlay 车机（接收端）。
> 本笔记综合四个本地参考仓库（`reference/`：DiPlay、xcertplay、catplay、
> carplay-wifi-extractor，已 gitignore）与公开逆向资料整理。
> **测试台接收端实际使用 DiPlay**（xcertplay 的衍生版，`reference/DiPlay/`）；
> 代码精读路径多为 xcertplay（`reference/xcertplay/`），两份源码同源、协议逻辑一致。
> 标注 ✅ 的为已在参考代码中逐行确证；标注 ❓ 的为待验证项。

## 1. 角色与认证模型

| | 接收端（车机角色） | 发送端（iPhone 角色，本项目） |
|---|---|---|
| 承担者 | 奥迪车机 / DiPlay（测试台） | 本 app |
| 需要苹果签名身份 | ✅ 是（MFi 证书/芯片，被手机验证） | ❌ 否 |
| 验证对方身份 | ❌ 否（单向认证，Oligo DEF CON 2025 证实） | 可选（测试台阶段直接跳过） |

**关键结论**：iAP2 认证是单向的——手机验证车机的 MFi 凭据（0xAA00 段挑战应答），
车机完全信任手机自报的验证结果。因此发送端**不需要**任何 Apple 签名材料，
DiPlay 从 Carlinkit 固件提取的证书对发送端无用（那是接收端身份）。

## 2. 分层结构（自底向上）

```
┌────────────────────────────────────────────────────────┐
│ AirPlay 主会话: RTSP 风格控制 + H.264/H.265 流 + HID     │  Phase 2
│   承载: WiFi TCP（车机 AirPlay 端口, 来自 0x4301）        │
├────────────────────────────────────────────────────────┤
│ mDNS/Bonjour: 车机广播 _airplay._tcp；手机广播           │
│   _carplay-ctrl._tcp（车机反连此端口）                    │
├────────────────────────────────────────────────────────┤
│ iAP2 控制会话 (CSM): 0x4040 分帧 + TLV 参数体            │  Phase 1
├────────────────────────────────────────────────────────┤
│ iAP2 链路层: FF55 SYN / FF5A 数据帧 + 校验和             │  Phase 1
│   承载: 蓝牙 RFCOMM（无线）/ USB bulk（有线,已排除）      │
└────────────────────────────────────────────────────────┘
```

## 3. 链路层（✅ 已按 xcertplay Iap2LinkEngine.kt 精读修正，2026-10-08）

数据帧头（大端，9 字节 + 载荷 + 载荷校验和）：

```
FF 5A | u16 总长 | u8 控制 | u8 seq | u8 ack | u8 会话ID | u8 头校验和 | 载荷 | u8 载荷校验和
```

**Phase 1 实测修正（与早期笔记的差异）**：
- 握手不是「FF55 控制帧」：双方互发 marker `FF 55 02 00 EE 10`，然后发同步包
  （FF5A 帧，control=0x80，载荷 = 参数协商结构 `>BBHHHBB` + 会话描述符×3）
- **无线模式下手机是同步发起方**：配件（openWireless, initiateNegotiation=false）
  只发 marker 等手机先发同步包；双方收到同步包即回 ACK，协商态收到 ACK 进 NORMAL
- **会话 ID 是 10（控制）/11（EA）/12（文件传输）**，不是 Oligo 笔记里的 0/1/2
  （那可能是蓝牙承载变体；以能互通 DiPlay 的 10/11/12 为准）
- 序号 u8 回绕，初始 99；数据帧 control=0x40（捎带 ACK）
- 控制位：SYNC=0x80, ACK=0x40, EAK=0x20, RST=0x10
- 校验和 = 字节和的补码（含校验和在内 mod 256 == 0），头/载荷各一个
- 无线参数参考（DiPlay/xcertplay WIRELESS_LINK_CONFIG）：maxOutgoing=4,
  controlSessionVersion=2, 保留 ACK（有线用 zero-ack 模式）
- CSM 大帧按对端协商的 maxLength 分片，接收端经 CSM 解析器重组

**本项目代码**: `app/src/main/java/.../iap2/Iap2LinkEngine.kt`（完整状态机，已回环测试）

## 4. CSM 分帧与 TLV（✅ xcertplay `iap2/wire/Iap2CsmFramer.kt`）

```
帧:   0x4040 | u16 总长(含头6字节) | u16 消息ID | 体       （大端, 无校验和）
参数: u16 参数总长(含4字节头) | u16 参数ID | 值             （体 = 参数序列）
字符串: NUL 结尾 UTF-8；嵌套参数 = 参数值为子参数序列
```

**本项目代码**: `app/src/main/java/.../iap2/Iap2Csm.kt`（帧编解码 + 增量解析器 + TLV 构建器已实现）

## 5. 无线控制时序（✅ xcertplay `Iap2WirelessControlClient.kt` + extractor README）

蓝牙 RFCOMM 建链（两设备需先系统级配对）后：

```
配件 → 手机: FF55 SYN                          （链路层握手由谁发起 ❓ Phase 1 验证）
手机 → 配件: StartIdentification      (0x1D00)
配件 → 手机: IdentificationInformation (0x1D01)   车机身份 + 收发消息能力清单
手机 → 配件: IdentificationAccepted    (0x1D02)
配件 → 手机: MFi 认证序列              (0xAA00 段)  ← 手机侧可跳过校验 ❓细节待读
手机 → 配件: RequestAccessoryWiFiConfiguration (0x5702)
配件 → 手机: AccessoryWiFiConfiguration (0x5703)   明文 TLV: SSID/密码/信道/安全类型
   （安全类型: 0=None 1=WEP 2=WPA/WPA2 3=WPA3过渡 4=WPA3）
手机加入车机热点 → mDNS 解析 _airplay._tcp
手机 → 配件: CarPlayAvailability      (0x4300)
配件 → 手机: CarPlayStartSession      (0x4301)   无线参数 + 车机IP + AirPlay端口
                                                    + deviceIdentifier + 公钥 + sourceVersion
手机 → 配件: WirelessCarPlayUpdate    (0x4E0D)   通知无线 CarPlay 可用
手机 → 配件: DeviceTransportIdentifier(0x4E0E)   蓝牙/USB 标识，收到后配件重发 0x5703
配件 → 手机: StartHID                 (0x6800)   Phase 2: 触摸回传通道
手机 → 配件: DeviceHIDReport          (0x6801)   应答
```

0x1D01 身份参数布局（发送端需要解析；字段 id → 含义）：
0=name, 1=modelIdentifier, 2=manufacturer, 3=serialNumber, 4=firmwareVersion,
5=hardwareVersion, 6=发出的消息ID列表(u16[]), 7=接收的消息ID列表(u16[]),
8=电源类型, 9=最大电流(mA), 10=EA协议组, 12=语言, 13=语言列表,
16=USB传输组件(有线), 17=蓝牙传输组件(无线), 18=HID组件, 24=无线CarPlay组件(含SSID)。

## 6. mDNS/Bonjour（✅ xcertplay `network/CarPlayBonjour.kt`）

**车机侧**广播 `_airplay._tcp`，TXT 记录：

```
deviceid=AA:BB:CC:DD:EE:FF   features=0x44540380,0x61   flags=0x4
model=...  srcvers=...  protovers=1.1
pi=<配对ID>  pk=<ed25519公钥hex>
```

**手机侧**广播 `_carplay-ctrl._tcp`（TXT: `id=<蓝牙MAC>`），车机 resolve 后**反连**该端口
作为控制通道（即 iAP2 控制会话从蓝牙迁移到 WiFi TCP 的载体）。
测试台（DiPlay，沿袭 xcertplay）偏好 link-local IPv6，回落 IPv4——实测 ctrl 探测
源地址为 `fe80::…%wlan0`。

## 7. 蓝牙 RFCOMM（✅ xcertplay `CarPlayController.kt:1591,1963`）

- iPhone（发送端）监听 SDP 服务：UUID `00000000-deca-fade-deca-deafdecacafe`
- 配件端 UUID：`00000000-deca-fade-deca-deafdecacaff`
- 测试台（DiPlay）的连接策略：扫描**已配对**的 `PHONE_SMART` 设备 → 逐个连接上述 UUID
- 测试台因此只需：两台手机系统级配对 → 本 app 监听 → 备用机 DiPlay 回连

## 8. AirPlay 主会话（Phase 2，仅索引）

- 参考实现：catplay `carplay/catplay_carplay/src/carplay_tx/`（发送端 1959 行）+
  `screen/tx/`（视频发送会话）+ `rtp/record/`（发送侧 RTP）+ `pairing/pairing_helper_tx.rs`
- 加密：ed25519 PairSetup/PairVerify（HomeKit 风格）+ RTP AES/ChaCha
  （接收端参考实现在 xcertplay/DiPlay `airplay/PairSetup.kt`/`PairVerify.kt`/`Srp6a.kt`）
- 视频：H.264/H.265，AVCC/AnnexB 封装转换（catplay `video/`）
- 时钟：NTP 风格 timing 端口 + RTCP（catplay `clock/`）
- ❓ FairPlay/流加密细节无公开规格，靠 catplay 源码 + ludwig-v 解密固件对照

## 8b. 音频通道（✅ 已实现：手机 → 车机主音频流）

**协商**：在已加密的 RTSP 会话上 `SETUP`，body = `streams:[{type:100(主音频),
streamConnectionID, audioFormat:<位图>, audioType:"media"}]`；接收端应答
`{type:100, dataPort:<UDP>, controlPort:<UDP>, streamConnectionID}`。
- 音频格式位图（catplay `msg/streams.rs` AudioFormat）：PCM 48k 立体声 = `1<<15`、
  AAC-LC 48k 立体声 = `1<<23`；当前实现用 PCM 48k 立体声（协议基线）
- 流密钥与视频同规则：`HKDF(shared, "DataStream-Salt<音频 connId>", "DataStream-Output-Encryption-Key")`
- 音频流 ID 独立（用自己的 connId），与视频互不影响

**线格式**（RTP over UDP；接收端 DiPlay `AudioStream.kt` 解密路径证实）：
```
[RTP 头 12B][ChaCha20-Poly1305 密文 + 16B tag][8B 小端 nonce]
头: v=2 | M=0 PT=<stream type=100> | seq u16BE | timestamp u32BE(累计样本帧) | ssrc u32BE
AAD = 头字节 4..12（timestamp+ssrc 的 BE 表示，catplay "modern AAD"）
nonce(12B) = 4 字节零 + 尾 8 字节小端计数（逐包递增）
```
- 每包 512 样本帧（48000Hz 立体声 16bit = 2048B 负载，整包 ~2084B；
  接收端 UDP 收包缓冲 4096B，安全）
- 采集：`AudioPlaybackCapture`（Android 10+，需要 MediaProjection 授权 + RECORD_AUDIO）
- 当前为单向（媒体/导航下车机）；麦克风上行（通话）未实现

**本项目代码**：`audio/AudioSender.kt`（采集+封包+UDP）、
`airplay/AirPlayClient.runAudioStream()`（协商）、单测 `AudioSenderTest`（解密回环）

## 9. 真车（奥迪 MIB3）Phase 3 风险清单

1. catplay 兼容表记录：Audi/Porsche HARMAN MIB2「角色切换后对 dongle 断电重启」——
   但那是 USB 有线方向；无线方向未知
2. 用户车机为 MIB3（2020 款后），无公开兼容记录，全新未知
3. 真车首次配对：需要先有「系统级蓝牙配对」，奥迪车机通常要求先插 USB 才开放无线
   配对（❓ 关键未知项：能否纯蓝牙触发。DiPlay 测试台无此限制）
4. WifiNetworkSpecifier 不支持程序化传入密码（系统弹窗输入）——0x5703 拿到密码后
   无法静默入网，需要用户每次确认（❓ Phase 3 实测影响）

## 9b. 奥迪 MIB3 实测记录（2026-10-09，Audi_MMI_0000 / MHI3_AS_AU_P3801）

车机识别为 `AUDI 0000 / DELPHI / CL37_MIB3H_AU_AS_G4x_2200910PROD`。
蓝牙 SDP 广播配件端 iAP2 服务 `…decacaff`（系统级蓝牙配对后即可连，无需 USB 触发）。

**实测通过（逐字节对照修正后）**：

| 环节 | 结果 | 关键点 |
|---|---|---|
| RFCOMM 双向 | ✅ | 车机也主动回连（约 60s 一轮）；双向都工作 |
| 链路协商 | ✅ | 车机发 SYN 声明会话表 `{1:control, 2:EA}` → 手机必须**ACK + 原样回显该表**；之后 CSM 走**1 号会话**（发 10 号被静默丢弃，链路层照常 ACK——极具迷惑性） |
| 识别 | ✅ | 0x1D01 收到 |
| MFi | ✅ | **20 字节挑战**（32B 静默但不打断链）；0xAA03 签名 132B |
| WiFi 凭据 | ✅ | 0x5702 → 0x5703 ssid=Audi_MMI_0000_5GHz ch=149 psk 明文 |
| ctrl 邀请 | ✅ | 车机在 WiFi 上发现手机 `_carplay-ctrl._tcp` 后 `GET /ctrl-int/1/connect`（死锁关键：手机必须**在 0x5703 到达时立即**绑 WiFi + 广播带**真实蓝牙 MAC** 的 ctrl） |
| mDNS 端点 | ✅ | 车机 `_airplay._tcp` 上线于 10.174.189.1:7000，TXT: `features=0x4040280,0x61 srcvers=320.17.7 flags=0x4 model=AUDI 0000 deviceid=AA:BB:CC:00:00:03`（**无 pk 字段**） |
| AirPlay /info | ✅ | RTSP 200/455（在线） |
| pair-setup | ❌ 403 | 车机不接受匿名 SRP 配对（iAP2 已完成 MFi 信任） |
| pair-verify 快路径 | ⏳ | 403 后转「已配对设备」pair-verify，待实测 |

**2026-10-11 复测补充（新增两条经证实的坑）**：

1. **奥迪对 32B MFi 挑战的行为不稳定**——10-09 是「静默不断链」，10-11 实测
   车机在 32B 挑战后 ~0.4s **直接拆链**（此前 0xAA01 证书 912B 已正常收到，
   说明识别阶段全对）。若每次会话都从 32B 撞起会反复断链。
   修复：`Iap2SenderSession` 记「被拒档位」，本进程内后续会话直接跳过该长度。
2. **客户端线程泄漏会让车机错乱**（10-11 排查发现 3 个 `iap2-bt-client` 并发运行）：
   `BluetoothSocket.connect()` 是阻塞调用，旧代码成功后才登记 socket，
   `stop()` 关不到正在 connect 的 socket → 线程滞留；用户再次「启动连接」后
   旧线程因共享 `running=true` 复活 → 多个 RFCOMM 会话并发连车机，
   车机表现为随机拆链/时序错乱。修复：世代号（start/stop 递增，过期线程自杀）+
   connect 前登记 socket（保证 stop() 能 close 打断阻塞）。

**奥迪与测试台（DiPlay）的差异清单**：
1. 0x4301 因**车机型号/固件而异，不能按「奥迪」一概而论**：
   - **Audi MMI 0000（2026-10-09/11 全通那台）**：不发 0x4301——识别帧声明可收
     `0x4e0c 0x4e09 0x4e0d 0x4e0e 0x5702 0xea00 0xea01`、可发 `0x5703 0xea03`；
     AirPlay 端点只能走 WiFi 的 mDNS `_airplay._tcp`（10.174.189.1:7000）
   - **Audi MMI 0000（AUDI 0000 / DELPHI，2026-10-18 用户实测）**：识别帧
     **明确声明会发 0x4301**（`车机发送清单(2)=0x4301 0xea03`），但实际在 Phase 1
     结束后 **2 分 35 秒**才发（车机侧 CarPlay 窗口刚开）；此时我们的 3s 等待窗口、
     4s ctrl 邀请窗口、120s 配对窗口**全部已过期**。若把它当心跳丢弃，车机等 15s
     无人连入即拆 RFCOMM → 用户看到「连上后自动断开」。
     另：该车热点网关是 `10.174.189.254`（不是 .1），网关兜底端口 7000 仍 Connection
     refused——是否端口不同未定谳（该轮 mDNS 全程未发现 `_airplay._tcp`），
     下一轮日志的 `iap2 0x4301 原始体` 可确认它是否携带 IPv4/端口。
   - 结论：**0x4301 必须作为生命周期事件处理**（迟到也要解析并触发配对），
     超时日志不得写「该车机不发」。
2. 车机**主动发 SYN**（测试台等手机发）；会话表 {1,2} 且必须回显
3. **MFi 挑战长度双兼容（重要坑，2026-10-10 踩过）**：
   - DiPlay（Carlinkit AuthV3 EC 身份）：**只接受精确 32B**，收到 20B **直接断链**
     （实测 70~350ms 断；表现为「连不上 DiPlay + 挑战期间链路断开」）；应答 ~0.2s
   - Audi MIB3：对 32B 静默（不断链）或拆链（10-11 实测两种都出现过），对 20B 秒答
   - 结论：顺序必须 **32B(3s) → 20B(6s) → 16B(3s)** 回退。反过来 20B 优先会打死 DiPlay；
     对奥迪的 32B 拆链波动由「被拒档位」记忆自愈（见下方 2026-10-11 补充）
4. `pair-setup` 被 403（测试台接受 3939 SRP）
5. AirPlay 端口窗口式开放（ctrl 邀请接受后短暂开放；未受邀时 Connection refused）
6. **不要断 BT 控制会话**（测试台 hack 的前提是 DiPlay 重建栈；奥迪会在断链后拆掉
   整轮 AirPlay 窗口）

**设备身份（0x4300/0x4E0E/ctrl TXT id 用）**：真实蓝牙 MAC 必须动态解析
（Android 11+ 应用层只能拿到占位 `02:00:00:00:00:00`）。
Shizuku shell 侧读法：`content query --uri content://settings/secure/bluetooth_address`。

## 9c. 奥迪 MIB3 全通记录（2026-10-11，Phase 3-C 验收）

iAP2 → AirPlay 配对 → 视频推流 → 触摸回传 → 虚拟屏注入，全链路可交互。
本日在 §9b 基础上攻克的最后一批协议差异：

| 环节 | 根因 | 修复 |
|---|---|---|
| pair-setup/pair-verify 403 | 配对请求缺 Apple 标识头 | `Content-Type: application/pairing+tlv8` + `X-Apple-HKP: 2`（pair-verify 再加 `X-Apple-PD: 1`）；M1 加 Method=None(0)+State(1) TLV |
| 屏幕流 SETUP 400 | ① timingPort=0 ② features 只提 videoPlayback（接收端算交集得空） ③ 缺资源声明 ④ 身份自报用车机版本 | TimingServer（32B RTCP NTP，pt=0xD2 请求/0xD3 应答，回显 t3+本地单调钟）；CONTROLLER_FEATURES 完整清单（uiContext/viewAreas/…共 14 项）；SETUP 前发 modesChanged（resourceID 1/2 entity=1）；UA/sourceVersion=iPhone 自报 566.25.21 + osName/osBuild 等全字段 |
| 会话 ~10s 被拆 | 缺双心跳 | keepAlive UDP 4B ping →车机 keepAlivePort（10s）；POST /feedback 空体（1s）。两者独立看门狗 |
| 触摸无报文（一） | 车机命令路径错 | 命令必须发**事件通道 socket** 上的 POST /command（Events 密钥、独立 CSeq、fire-and-forget）；RTSP 控制连接上的 200 是假确认 |
| 触摸无报文（二） | 未武装 HID | SETUP 后对 /info hidDevices[].uuid 逐个发 hidSetInputMode {hidInputMode:0, uuid}；**奥迪 uuid 是字符串 "0"/"1"**（DiPlay 是整数） |
| 触摸无报文（三） | 事件通道是双向 RTSP | 每条来向请求立即回 RTSP/1.0 200 OK（加密）；不应答→车机塞 3 条就断通道（「能滑一下」） |
| 触摸坐标 | 奥迪 HID 描述符 | uuid=1 eso_touchScreen：5 字节 `[tip 1bit][X u16LE][Y u16LE]`，X≤1412/Y≤654=屏幕分辨率绝对坐标；uuid=0 是 Consumer 媒体键（非触摸） |
| 虚拟 iAP2 链路 | iAPSendMessage 语义 | 事件通道 iAPSendMessage.data = 链路层字节流；marker=DETECT，车机（客户端）每 200ms 重发、等服务器回一次 DETECT 后发 SYN；服务器重发 DETECT 会把客户端打回 Detecting |
| 0x4E0E 杀 BT 链路 | 车机接受但不 ACK | sendControl(awaitAck=false) 发后不管；0x4300 同改 |
| mDNS 抓错目标 | 手机开热点共享 | 按「本机 IP /24 前缀」过滤 _airplay._tcp 结果；mDNS 失败时网关兜底（网关=车机，端口 7000） |

**调试流程坑**：`adb install -r` 不杀旧进程（旧 dex 继续跑，修复「无效」）；
NsdManager unregisterService 异步（复用 listener 报 already in use，每次注册新建）；
ctrl mDNS 注册必须发生在 bindProcessToNetwork **之后**（组播跟随进程绑定网络）。

### 9c-1. 资源归属（`modes`）必须是状态机，不是常量（2026-11-10 修正）

上表那句「SETUP 前发 modesChanged（resourceID 1/2 entity=1）」只解决了「有没有发」，
**取值一直是写死的常量**：主屏与主音频都声明 `entity=1, permanentEntity=1`（手机持有、
永久持有）。车机发 `changeModes` 时也无条件照发同一段——等于对「我要接管」「我要归还」
「我借一下」全部回答「屏永远是我的」。

真车会发这两条，且 `/info.modes` 里就写着初始归属。奥迪 4260（`AUDI 4260`/DELPHI）
2026-11-09 09:38/09:42/09:43 的日志里：

```
/info modes={appStates=[{appStateID=2,state=false},{speechMode=-1,appStateID=1},{appStateID=3,state=false}],
             resources=[{transferPriority=100, takeConstraint=100, resourceID=1, transferType=1, ...}]}
EVENT: 车机请求 changeModes → 回 modesChanged 接管资源      ×7
EVENT: 命令 type=requestUI params={url=}                    ×1（旧实现当作未识别命令）
```

`transferType=1` = **车机已接管主屏**，`takeConstraint=100` = 允许在约束内要回来。

现在的语义（`airplay/AirPlayResourceState.kt`）：

| 来源 | 处理 |
|---|---|
| `/info.modes.resources[]` | 初始归属，**车机说了算**；没给就保持「手机持有」（与旧常量一致） |
| `changeModes.transferType=1` | 车机接管，记 `takeConstraint` |
| `transferType=2` | 归还给手机 |
| `transferType=3` / `4` | 开始 / 结束借用（只看 `borrowID`，永久归属不变） |
| 未知 `transferType` | **什么都不改**（猜错方向比不动更糟） |
| `requestUI` | 把主屏交回手机（同 `requestPhoneScreen()`） |

发布顺序照真机：**先按车机给的归属复述 → 再申请主屏 → 状态有变才重发**。
车机若不接受第一次复述，退回旧行为（声明手机永久持有主屏+主音频），保证不因本条退化。

成功判定同时改严：**RTSP 200 且报文体 `status == 0`**（无 status 字段或解析失败仍算成功）。
旧实现只看状态行，车机回 `200 OK` + `{status:-1}` 时被当成功，现场是「都成功了，过一会儿断」。

## 9d. WinPlay（Windows 接收端）实测记录（2026-10-19）

WinPlay 是开源 Windows 无线 CarPlay 接收端（`reference/WinPlay`，GPL-3.0，Node + TS，
接收端角色）。用户用荣耀做发送端连它（日志 `logs/cardeck/xiaomi-winplay.log`）：
iAP2 → WiFi → mDNS → 配对全通，卡在会话级 SETUP。

**根因：会话级 SETUP 的 `deviceID` 必须等于接收端选中的那台 iPhone 的蓝牙 MAC。**

- WinPlay 启动时用 `cfg.targetBluetooth`（用户在 Windows 上选定的已配对 iPhone）
  设置过滤器：`stack.deviceFilter = id => normalize(id) === normalize(cfg.targetBluetooth)`
  （`reference/WinPlay/src/engine/run.ts:64`）。
- 会话级 SETUP 里 `dict.deviceID` 对不上（或缺字段）直接回 **403**
  （`src/engine/protocol/cpStack.ts:696-699`）；`RECORD` 在设备验收前同样 403。
- 我们此前发的是 catplay 的写死假值 `ff:ee:dd:cc:bb:aa`
  （旧注释「车机不校验」只在 Audi/DiPlay 成立）。
- 状态行 `RTSP/1.0 403 OK` 是 WinPlay 的指纹：它的 `STATUS_TEXT` 表没有 403 条目，
  `buildResponse` 回退成 `OK`（`src/engine/protocol/rtspMessage.ts`）。看到「403 OK」
  优先怀疑设备过滤，而不是通用 RTSP 实现差异。
- 失败后的表象是「连上后约 45 秒自动断开」：发送端不再发请求，接收端等视频超时
  （`armVideoDeadline` 45s）后拆链路。发送端应在 SETUP 非 200 时主动 close，别静默等待。

对照 Audi/DiPlay：两者都不校验 SETUP 的 deviceID，所以这个字段长期没有被发现。
本项目已改为复用 `DeviceIdentity` 解析出的**真实本机蓝牙 MAC**（与 0x4300/0x4E0E
一致），占位值 `02:00:00:00:00:00` 只在 Shizuku 未就绪时兜底，并在 403 日志里
明确提示「接收端请选择该地址」。

**WinPlay 的其他特性（对本项目有用的）**：

- 端口固定 TCP 17000；`_airplay._tcp` TXT 与 iPhone 同构
  （`srcvers=950.7.1 features=0x44540380,0x61 flags=0x4`）。
- 会话级 SETUP 只在 `keepAliveLowPower` 为真时才开 keepAlive 端口。
- 事件通道/流 SETUP/音频协商路径与 DiPlay 基本一致（`_setupScreen`/`_setupAudio`），
  同样按 `DataStream-Salt<id>` 派生流密钥，可作为接收端行为的第三份交叉验证。

**顺带修掉的 bplist 编码 bug**：`BinaryPlist.encodeInt` 原先按**无符号**范围选宽度，
但 bplist 整数是**有符号**的——35449（0x8A79）被写成 2 字节，Apple 侧解析成 int16
-30087。临时端口（timingPort/eventPort/dataPort 常落在 32768..65535）都会踩到；
WinPlay 用 BigInt 无符号读取所以不受影响，Audi 是否受影响未知（视频曾正常，但不能
排除它忽略了错误的 timingPort）。现已改为按 `Byte/Short/Int` 有符号范围选宽度，
回归测试覆盖 0/127/128/255/256/32767/32768/35449/65535/65536。

## 9e. 奥迪 MMI 0000 会话 10 秒被拆：三条心跳缺一不可（2026-10-19）

用户实测日志 `logs/cardeck/CarDeck-log-20261019-081700.txt`：iAP2 → MFi → 热点 →
配对 → SETUP → RECORD → 视频 30fps 稳定推流，**RECORD 后 10.17~10.23s 整轮被 reset**，
两轮时序逐毫秒重合（不是随机网络问题）。

**根因一：UDP keepAlive 间隔与车机看门狗等长。** 我们每 10s 发一次（catplay
`KeepAliveClient::PING_INTERVAL`），而车机看门狗也是 10s。日志里 ping #2 距 #1
恰好 10.001s，随后 213~247ms 内被拆——`Thread.sleep` 的毫秒抖动让心跳永远晚到
deadline 1ms。catplay 的 10s 只适用于它自家接收端；对真车必须留余量。
改为 **5s**（`AirPlayClient.KEEPALIVE_INTERVAL_MS`）。

**根因二：缺屏幕流内 keepAlive 帧。** catplay 在**视频持续推流时仍然每秒**向
屏幕数据口写一帧 keepAlive（`ScreenTransmitSession::create_keep_alive`，
`DEFAULT_KEEP_ALIVE = 1s`）——它不是视频流量的备份，而是接收端屏幕上独立的存活
信号。我们从未发过。帧格式（`reference/catplay/.../screen/screen_frame.rs`）：

```
128B 明文头: u32LE body 长度 | u8 opcode | 3B 小参数 | 15 × 8B 参数
opcode 2 = KeepAlive（空体）
opcode 5 = KeepAliveWithBody（/info.keepAliveSendStatsAsBody=true 时；
           body = bplist{txUsageAvg, encoderCurrentFPS, sentFramesAvg,
                        queuedFramesAvg, lossAvg}，
           params[14] 后 4 字节 = body 长度 f32 LE）
```

**keepAlive 帧是明文**——catplay 的 `ScreenFrameCodec::encode_composite` 只对
`opcode == VideoFrame` 加密，所以 keepAlive 不消耗 ChaCha 帧 nonce。我们按此实现，
并在 `VideoSender` 的编码器出帧循环里按 1s 节流发送。

**三条心跳相互独立**：UDP keepAlive（5s，keepAlivePort） + RTSP /feedback（1s，
控制通道） + 屏幕流 keepAlive 帧（1s，视频数据口）。此前只实现了前两条，
`docs` §9c 里记的「补双心跳」只在 MMI 0000 上够用。

**音频采集全零（未解决，与拆链无关）**：`AUDIO: 采集样本 非零0% 峰值=0` 连续两轮。
候选原因按概率：当时无媒体在播（会前只有一条旧记录）、音频被路由到车机蓝牙
A2DP（PlaybackCapture 抓不到蓝牙输出）、或目标应用禁止播放捕获。

**MFi 挑战长度的车机差异（本机实测补充）**：MMI 0000 对 20B **静默 6.0 秒**
（15.605 发出 → 21.604 判定无应答），32B 在 0.64 秒回签名（22.247 收到 0xAA03）。
这 6 秒属于每次连接都要付的固定成本，除非按车记住偏好。现已实现
`MfiLengthMemory`：按 0x1D01 身份（name+model+manufacturer）持久化
「成功 / 静默 / 被拒」三种结局，命中成功档位直接首选；静默档位重试窗口压到 1.5s。
注意这与 §9b/§9c 的记录并不矛盾——**不同年款/固件的车机偏好相反**，MMI 0000
认 20B 且对 32B 会拆链，所以基线顺序仍按接收端类型选择，记忆只做覆盖。

## 9f. 老车机（CarPlay 210.81 / 奥迪 4xxx 固件）连不上：按 catplay 对齐的六处差异（2026-10-19）

用户反馈「奥迪软件版本 4 开头的（如 4851）连不上」。该代车机对应 catplay 分类中的
**pre-HomeKit 接收端**（README 兼容表：Audi/Porsche ~2020 HARMAN MIB2/MHI2 即
CarPlay 210.81）。对照 catplay 上游（本地引用停在 9-30，本次拉到 v0.17.0、39 个提交）
逐项核对后，本项目有六处与之不一致，全部按 catplay 修正：

**① bplist 根对象必须位于字节 8（catplay 9ad3a7e）**。CarPlay 210.81 不读 trailer 的
topObject，**直接按偏移 8 取根**；我们的编码器先编码子对象、最后才追加根容器，根默认在
末尾。现象是车机收到合法 bplist 却解析不出字典（`SETUP plist decode failed` / 400）。
修复：`BinaryPlist.encode` 末尾统一 `makeRootFirst` 重排并按偏移表修正（catplay 同算法），
单测 `StreamFrameCipherTest.encodedPlistRootObjectStartsAtByteEight` 用 >255 对象的
多字节偏移表锁住（小 plist 是单字节表、测不出该 bug）。

**② 初始 SETUP 的请求 URI 用相对路径 `/setup`**（catplay `initial_setup` 同款）。
旧实现发绝对 `rtsp://host:port/`。老固件对绝对 URI 敏感。

**③ 未配对会话必须带 `et=1`（NONE），屏幕流走 AES-128-CTR 零密钥**。
catplay `ScreenFrameCodec::new` 注释：「There was never support for unencrypted screen
sessions. ET = None maps to AES session where session_key before derivation is all
zeroes.」即 `et=NONE` ≠ 明文视频，而是 **AES-128-CTR + 16 字节全零会话密钥**，
密钥/IV = `SHA512("AirPlayStreamKey"/"AirPlayStreamIV" + connId(decimal) + sessionKey)[0..16]`。
事件通道在未配对会话里也是**明文**（catplay `EventsServer::unencrypted`）。
`et=NONE` 时音频没有共享密钥可派生 DataStream 输出密钥，直接跳过。

**④ 屏幕数据帧的两种加密形态**。已配对（pair-verify）→ ChaCha20-Poly1305；
未配对 → AES-128-CTR（无 tag、长度不变、密钥流**跨帧连续**，每帧新建实例会让第二帧起
全部乱码）。只加密 `opcode=VideoFrame`，keepAlive/config 始终明文。

**⑤ 控制通道密文帧的 MTU 是 1440**（catplay `AIRPLAY_CIPHER_MTU_MAX`）：
单帧明文上限 = 1440 - 2B AAD - 16B tag = **1422**。旧实现 0x4000 会被严格车机
判为协议违规（catplay 解码侧 `len > 1422` 直接 `PayloadTooBig`）。

**⑥ 只发 IPv4 地址的判据过窄**。奥迪 4xxx 的 `_airplay._tcp` / 0x4301 常只给
**IPv6 链路本地**（`fe80::…%wlan0`）。旧代码到处用 `contains('.')` 判「有没有地址」，
这类端点被判成不完整 → 白等 ctrl 邀请 → 退回「网关:7000」兜底（4xxx 的网关未必是
`.1`、端口也未必 7000）→ 永远连不上。现统一走 `net/EndpointHosts`：
IPv4 优先 → 全局 IPv6 → 链路本地 IPv6（补 scope），并且**视频/音频/事件通道复用
配对真正连通的地址**（`AirPlayClient.activeHost`），不再各自回头挑 IPv4。

**配套：用 TXT features 识别车机年代**。`_airplay._tcp` 的 `features=低32,高32`，
bit38 = `HK_PAIRING_AND_ENCRYPT`。老车机不声明该位 → 直接 `skipPairing` 走明文会话，
不再空跑 120s 配对窗口（这正是「一直连不上」的直接观感）。TXT 缺失/解析失败一律
视为「未知」并保持旧路径，绝不误降级。若明文 SETUP 仍被拒（判断失误），再补做一次
配对（catplay `handle_setup_result` 的 crypto 升级路径，窗口压到 30s）。

**顺带对齐**：`_carplay-ctrl._tcp` 的 TXT `srcvers` 改为发送端版本号（566.25.21，
此前误写车机侧 950.7.1）、服务名 iPhone；同时广播 `_device-info._tcp`（model=D84AP，
catplay `DeviceInfoBonjourEntry`）；`GET /info` 带空 bplist 字典体 + plist Content-Type。

## 9g. 奥迪 4851 实车「连上了但车机没反应」：VideoConfig 帧头缺两个字段（2026-10-19）

车友日志 `logs/cardeck/CarDeck-log-20261019-101353-all.txt`（小米 24129PN74C /
Android 16，应用 **0.1.4**；车机 `Audi_MMI_0000` / `AUDI 0000` / DELPHI，
`srcvers=450.14.2`，CarPlay `name=CarPlay`）。分析结果分两部分：

**A. 这次日志里确实每次都 10.2 秒被 reset**（六轮全部复现，与 §9e 同源）：
事件通道建立 → 恰好 +10.2s `EVENT: 读取结束 - Connection reset`。该版本缺
屏幕流内 keepAlive 帧、UDP keepAlive 还是 10s（与看门狗等长）。这两点已在
**0.1.6** 修掉（§9e），所以本轮不必再改——但车友必须升级到 0.1.6 才会有完整会话。

**B. 「app 显示已连接、车机没反应」的直接原因：VideoConfig 帧头缺字段。**
0.1.6 之前的实现在发 VideoConfig 时只写 `body_size` + `opcode`，而 catplay
`ScreenFrame::config` 还会写两项，Audi 这类严格解码器缺了就**整屏黑**：

| 字段 | 头部位置 | 值 | 作用 |
|---|---|---|---|
| `small_param[1]` | 偏移 6 | `ScreenFlag::Encrypted (0x04)` | 声明后续 VideoFrame 是加密的；不声明就按明文 NAL 解 → 解码失败 |
| `params[1]` | 偏移 16 | `(width, height)` 两个 **f32 LE** | 车机据此建立解码器画布尺寸 |
| `params[0]` | 偏移 8..16 | 全 0 | config 不带 PTS（只有 VideoFrame 才写） |

**为什么测试台一直没暴露**：DiPlay `ScreenStream.onMessage` 只读 `body`（opcode 0/1）
并从 avcC 里取分辨率，WinPlay `screenStream.ts` 同样只看 opcode 与 body——两者都不校验
这几个头字段。真车（Audi DELPHI）按头字段建解码器，缺了就黑屏，且**会话、触摸、
心跳全部正常**，日志里没有任何错误行——这是最难排查的一类。

**A 与 B 的关系**：A 让会话活不过 10 秒（用户看到「闪一下就断」）；B 让会话活着时
屏幕也是黑的（用户看到「显示已连接但车机没反应」）。两者独立，必须都修。

**诊断日志（本次新增）**：`VIDEO: ✓ VideoConfig 已发送 … flags=0x07 1412x654` 后面
跟一行头部前 24 字节 hex——下次反馈可直接核对这些字段是否写对。

## 9h. BMW 00000 卡在 iAP2 链路协商：SYN-ACK 序号重复（2026-10-19）

日志 `logs/cardeck/CarDeck-log-20261019-103436.txt`（OnePlus PKR110 / Android 16，
应用 0.1.6，车机 `BMW 00000` / MAC `AA:BB:CC:00:00:04`）。**14 轮会话全部卡在链路层**：
`iap2 link ready` 0 次、`0x1D00` 发送 0 次、`0x1D01` 收到 0 次。

**帧统计（决定性证据）**：

| 方向 | 帧类型 | 次数 |
|---|---|---|
| BMW → 手机 | `0x80` SYN | **133** |
| BMW → 手机 | `0x40` ACK / 数据 | **0** |
| 手机 → BMW | `0x40` 裸 ACK | 350 |
| 手机 → BMW | `0xC0` SYN\|ACK | 14 |

**根因一：SYN-ACK 复用 SYN 的序号。** 原始字节：

```
我方 SYN    : ff 5a 00 1d 80 63 ...      seq = 0x63 (99)
我方 SYN|ACK: ff 5a 00 1a c0 63 a1 ...   seq = 0x63 (99)   ← 撞号
```

`sentSequence` 只在 `drainSendWindow()` 发数据帧时自增，协商阶段从不递增。对端按
`SYN.seq + 1` 校验回包（catplay `verify_neg_seq_ack`：`packet.seq == peer_seq + 1`），
同序号被判重复帧丢弃。catplay 的 `NegotiationTx::SynAck` 用 `take_own_seq()` 自增。
修复：`sendSynchronizationEcho` 消耗一个新序号；**同一个 SYN 的字节级重发保持幂等**
（重放上一帧，不推进序号——DiPlay 隧道模式每 500ms 重发同一 SYN）。

**根因二：只有第一条 SYN 得到 SYN|ACK。** 旧实现 `if (firstAdoption) sendSynchronizationEcho(...)`，
其后每条 SYN 只回裸 ACK。而 BMW **每轮重试都用新序号重开协商**（161→191→40…，
日志里单轮最多收到 36 条 SYN），永远等不到它要的回显。catplay `on_recv_neg_host`
对每条 SYN 都发 SYN+ACK。修复：**每条纯 SYN（不带 ACK 位）都回一帧 SYN|ACK**。

**踩坑：裸 ACK 会与 SYN|ACK 撞号。** 裸 ACK 用当前 `sentSequence`，而 SYN|ACK 会自增，
下一次裸 ACK 就复用上一帧 SYN|ACK 的序号。故主机路径改为**只回 SYN|ACK**（它自带
ACK 位与 ack 字段，裸 ACK 本就是冗余的；catplay 主机路径也只发 SYN+ACK）。
代价：Audi 实测的「裸 ACK + SYN|ACK」帧序变为单帧 SYN|ACK——仍符合协议，
但**需在 Audi 上复测确认**（最终 ACK 会回 100 而非 99，序号偏移一位是正常的）。

**踩坑：不能对所有带 SYN 位的帧都回 SYN|ACK。** 首版实现没区分 SYN 与 SYN|ACK，
两个引擎互喂时形成无限乒乓（单测 `windowBacklogFlushesAfterAcknowledgement` 直接
挂死并空转 CPU 55 秒，`jstack` 才看出）。判据必须是 `control and ACK == 0`。

**次要问题（本轮未改，建议后续处理）**：
- 出站客户端与入站会话并行：16 次「BMW 处于蓝牙已连接状态（HFP/A2DP）——已断开」，
  3 秒重试线程反复拆 HFP/A2DP，真车侧看是蓝牙被持续扰动；
- 未指定目标时依次去连蓝牙耳机（`HUAWEI FreeBuds 4` / `FreeClip 2`），候选应只保留
  声明了 CarPlay/iAP2 服务的设备；
- 裸 ACK 500ms 一次（350 条），BMW 的 LSP 声明 `ackTimeout=3000ms`，按对端值发即可。

## 9i. 0.1.7 在奥迪 MMI 0000 上的回归：ctrl 广播身份字段被改（2026-10-19）

日志 `logs/cardeck/CarDeck-log-20261019-141711.txt`（Xiaomi 15 / Android 16，0.1.7，
同车同手机在 0.1.6 下可正常连接）。表象：**iAP2 全通、车机却不开 AirPlay 窗口**。

**已走通**：RFCOMM → `iap2 link ready` → 0x1D01 → MFi（32B，按记忆首选 0.58s）
→ 0x5703（`Audi_MMI_0000_5GHz` ch149）→ 加入应用专属网络。车机 iAP2 层是活的
（后续还发来 0xAE00）。

**卡点（四项证据全为零）**：

| 检查项 | 0.1.7（本次） | 0.1.6（同车可用） |
|---|---|---|
| 0x4301 到达 | **0 次** | 0x4300 后 **0.65s** |
| 车机 `GET /ctrl-int/1/connect` | **0 次** | 有 |
| mDNS `_airplay._tcp` | **0 次** | `CarPlay -> 10.174.189.1:7000` |
| 网关 `:7000` | 54s 全部 `ECONNREFUSED` | 立即可连 |

**根因：ctrl 广播的 TXT `srcvers` 与实例名被我按 catplay 改了。** 0.1.6 是
`name=sender` + `srcvers=950.7.1`（车机认）；0.1.7 改成 `name=iPhone` +
`srcvers=566.25.21`（catplay 的发送端 SDK 版本），车机**连探测都不再发**。
这两项是车机在 WiFi 上唯一能看到的手机身份，改动等价于换了一台设备。
→ 已回退到 0.1.6 的值，并加测试 `CarPlayCtrlAdvertiserTest` 钉死（将来不要再
"照 catplay 抄"）。

**同时移除 `_device-info._tcp` 广播**：Android 的 `NsdServiceInfo` 不接受 port=0，
真机每次都报 `The port is missing`（日志 4 次）。它是未验证收益的附加广播，
失败只会制造噪音——移除而不是去修，因为没有任何证据表明这台车需要它。

**另两个顺带修掉的问题**：

1. **0x4300/0x4E0E 用的是占位 MAC**：本次日志全篇
   `设备状态问候 … mac=02:00:00:00:00:00`。`DeviceIdentity` 对解析失败有 10 秒静默
   窗口，而 BT 会话常在 Shizuku 刚就绪的同一毫秒构造——首次失败后 10 秒内全返回
   占位值（0.1.6 的日志里第 2、3 轮就是真实 MAC `AA:BB:CC:00:00:05`）。奥迪会拿
   0x4300 的传输标识与 RFCOMM 对端核对，占位值导致静默丢弃。
   → `BtSession` 改为会话启动时 `forceRetry` 解析并打日志。
2. **ctrl 广播的 TXT 从未进日志**：导致上面这次无法直接归因（只能靠版本对比）。
   → 每次注册打印 `name= / id= / srcvers=`，占位 MAC 额外标 ⚠。
3. **兜底期不再公告也不再查 mDNS**：4s 邀请窗口超时后只对着网关空转 54s。
   → `AirPlayClient` 增加 `retryTick`（每 10 次重试 ≈ 20s）：重新 force_announce
   ctrl，并把「车机邀请次数 + mDNS 是否发现」写进日志。

**方法论教训**：把「参考实现的值」直接搬进线上，代价可能是一整轮真车不可用。
凡是影响**车机如何识别我们**的字段（TXT、服务名、sourceVersion、deviceID），
都必须先在真车上 A/B 验证，且改动要能一眼从日志归因。

## 10. 参考文件速查

> 表中路径均指 `reference/` 下**代码精读来源**（xcertplay 与 DiPlay 同源，
> 接收端逻辑一致，读哪份都行）；**实际测试台运行的是 DiPlay**（`reference/DiPlay/`）。

| 内容 | 位置 |
|---|---|
| 链路层字节格式/校验和 | `reference/carplay-wifi-extractor/example/iap2/link_layer.py` |
| RFCOMM 传输/UUID | 同上 `transport/bluetooth.py`；xcertplay/DiPlay `CarPlayController.kt` |
| CSM 分帧/TLV | `reference/xcertplay/.../iap2/wire/Iap2CsmFramer.kt`、`Iap2WireCodec.kt` |
| 无线控制时序 | `reference/xcertplay/.../transport/Iap2WirelessControlClient.kt` |
| 0x1D01 身份字段 | `reference/xcertplay/.../transport/Iap2IdentificationClient.kt` |
| 消息端点目录 | `reference/xcertplay/.../iap2/catalog/Iap2Endpoint.kt`（931行,全部参数布局） |
| Bonjour 细节 | `reference/xcertplay/.../network/CarPlayBonjour.kt` |
| 接收端 AirPlay 会话 | `reference/xcertplay/.../airplay/AirPlaySession.kt` + `PairVerify.kt` |
| 测试台（实际运行版） | `reference/DiPlay/`（含真车诊断/音频等增强；与 xcertplay 同源） |
| 发送端全套参考 | `reference/catplay/carplay/catplay_carplay/src/carplay_tx/` 等 |
| Windows 接收端（WinPlay） | `reference/WinPlay/`（TS 接收端；设备过滤见 §9d） |
| MFi 认证（接收端向） | `reference/xcertplay/.../mfi/`；Oligo 博客（单向性证据） |
| 解密固件(仅查阅) | ludwig-v/wireless-carplay-dongle-reverse-engineering（未克隆） |

## 12. MIB3 WiFi 隧道（AirPlay 会话之上的第二条 iAP2 链路，2026-10-19）

> 事实源：`reference/AADisplay`（分支 AADisplay-Split-Lite-Carplay，**GPL-3.0 只读参考**）。
> 他们用 Frida 挂越狱 iPhone 的 mediaserverd 抓的真机握手；本仓库据此实现
> `airplay/VirtualIap2Link.kt`（协议事实照抄，代码独立编写）。

**为什么需要**：MIB3 在 AirPlay 会话建立后还要求手机在**事件通道**上开第二条 iAP2
链路并重跑控制会话——只回链路 ACK（空回显）时 MIB3 约 10~12 秒整链拆除（本仓库
2026-10-19 两轮实车日志 10.2s 复现；此前误判为心跳缺失，§9e 的三条心跳修复并不能
救这台车）。AADisplay 源码注释原文："Empty ACK alone makes MIB3 tear AirPlay control
down in ~12s with no picture."

**传输不对称**（关键）：
- 车机→手机：链路字节走**事件通道**（TCP 5001）上的 `POST /command`，bplist
  `{"type":"iAPSendMessage","params":{"data":<链路帧>}}`；
- 手机→车机：走**RTSP 控制通道**（7000）上的 `POST /command`（同样的 iAPSendMessage
  信封，ChaCha 封帧）——写在事件通道上 MIB3 **不读**（DiPlay 宽容，奥迪不宽容）。

**时序**（Frida 真机）：
1. 屏幕流 SETUP 200（拿到 dataPort）后，手机主动发**一次** DETECT marker（经控制
   通道）——早发会在 SETUP 交换期间被冲掉；此后车机的 marker 都是探测重发，忽略；
2. 车机回 SYN（事件通道，纯 SYN 带 LSP）→ 手机回 SYN|ACK（控制通道）。LSP 要声明
   **自己的**参数：maxOutgoing=0x7F（原样回显对端表=把自己限死在对端小窗口里，
   突发消息会积压）、maxLength 保持对端值；会话表沿用对端（后续版本可注入
   file-transfer 0x0C，仪表盘封面要用）；
3. 链路 NORMAL 后手机发 0x1D00 → 车机 0x1D01 → 手机 0x1D02 + 0xAA00 →
   （车机 0xAA06 auth serial → 手机再发 0xAA00）→ 车机 0xAA01 证书 → 手机 0xAA02
   挑战 → 车机 0xAA03 → 手机 0xAA05（不验签直接接受）。**挑战长度无版本号可查**
   （由车机 MFi 认证芯片代次决定，不在任何身份/版本字段里）：Frida 抓到的隧道
   真机是 20B，DiPlay CARLINKIT 只认 32B，同一台 MMI 0000 蓝牙认 32B——所以隧道
   路径接 `MfiLengthMemory`（记忆键=身份串+" tunnel"，与蓝牙链路独立），冷启动
   基线 20B 优先，静默 6s/3s 换档重试、被拒（0xAA04）即换、全静默照发 0xAA05；
4. 0xAA05 后手机**突发**（先于车机任何订阅）：0x4E0C 无线会话标识(随机大写 UUID)、
   0xA100(param 6 空)、0x4E0A 语言 "zh"、0x4E0B 设备时间(u64 秒+s16 时区分+u8 夏令时)、
   0x4E09 设备名 "iPhone"、0xFFFA StartLocationInformation、0xFFF0 GPRMC 状态——
   后两条为逐字节 Frida 常量（`VirtualIap2Link` companion）；
5. 车机订阅应答：0x5000→0x5001 NowPlaying 空态（**仪表盘歌词的数据通路**，后续接
   MediaSessionsState）、0x4157→0x4158 通讯空态、0x4154→0x4155 无通话、
   0x4170→0x4171 空列表；0xEA02(requestAppLaunch)/0xA101(VehicleStatus)/
   **0xFFFB(PASCD 定位样本 ~1Hz) 只链路 ACK——漏 ACK 车机同样 ~10s 后关控制通道**。

**引擎配套改动**（`iap2/Iap2LinkEngine.kt`，均有单测）：
- 发起方收到 SYN|ACK（非纯 SYN）也采纳会话表并推进有序水位（只采纳不回显）；
- 响应方回显 SYN|ACK 即视为协商完成（真机不等 ACK 就发 0x1D00）；
- 回显用 ownSyncOf（maxOutgoing=0x7F），**发送窗口按 `min(本方 0x7F, 对端声明)` 计**
  （2026-11-10 改，无开关、对所有车机一致，与 link_plus 同源实现相同）：超窗的帧留在
  backlog，等车机 ACK 再放。别克 BUICK_3138 声明 5，旧逻辑一次连发 7 帧（seq 97~103）
  后车机一条 ACK 都不回；奥迪 MMI 4260 同样声明 5（它 7 帧连发是能连上的，改后会被
  切成 5+2，奥迪侧尚未实车验证——「奥迪忽然连不上」先查这里）。
  唯一例外是零重传对端（`maxRetransmissions=0`，隧道侧隐式确认）：它不回 ACK，
  套小窗口会让 backlog 永久卡死，仍按本方值。

**AADisplay 参考索引**：`carplay/protocol/WifiIap2Link.kt`（时序+常量）、
`CarPlayRtspSession.kt:642`（控制通道 POST /command 写法）、`docs/AUDI_CARPLAY_CONNECT_ANALYSIS.md`。

## §13 真机 iPhone sysdiagnose 对照（2026-10-21/17，两车三份抓包）

来源：iOS 24A446（18.x）sysdiagnose 的 unified log（airplayd/mDNSResponder），验证手段
`/usr/bin/log show --archive system_logs.logarchive --predicate ...`（zsh 内建 `log` 会顶掉，必须全路径）。

### 13a. 奥迪 MMI 0000_5GHz ——「首连必拒、重邀即成」

两份抓包（同车，14:24/14:32）一致的时间线：

1. BT 供应链自动入网（airplayd 记 `CarPlay UUID` 有值）→ 约 **21s 后**车机首次 ctrl 邀请
   （`HandleControlServerEvent command 'connect'`）；
2. 连 `[fe80::…%en0]:7000`（**IPv6 link-local**，1022ms）→ pair-verify M1-M4 **32ms 成功**；
3. **MFi 认证段被 403 拒**（`MFi failed: 200403 Forbidden → Stage 1 failed → StartupFailed`）
   ——**真 iPhone 首次连接同样被拒**，属车机固有状态而非客户端问题；
4. **车机 18~20s 后自行重发邀请**，第二次 verify 16ms、MFi（challenge 64B / 证书 607B）
   通过，Stage 1/2 → 授权 → 开流。全程**不断蓝牙、不换网络**；
5. 成功会话里 **`iAPChannel` FeatureKey 协商结果 = 0**（WiFi iAP2 隧道未启用）——
   与我们「MIB3 必须建隧道」的结论相悖，改隧道策略前必须真车 A/B；
6. 杂项：SETUP 应答可不带 streamID（iPhone 自动补 0）；RECORD 往返 17ms；流级心跳
   Standard 2017ms；会话级 keepAlive 10s（LowPower）。

落地（2026-10-22）：`AirPlayClient.pairingForbidden`（任意段 403 标记）→ 主控不再走
「断 BT 第二阶段」、不再降级 et=NONE；配对循环 403 后退避 5s（车机就绪窗 ~20s，
高频重试可能一直重置其状态机）。

### 13b. 一汽大众探歌 23 款（VW WLAN）——「手动入网永远无效」

- 2.4G `VW WLAN` 手动加入 ×5 轮：`CarPlay UUID: N/A`、**`_airplay._tcp` 全程零 mDNS
  应答**、无邀请，iPhone 等 67s 退网，循环；
- BT 供应链就绪后自动加入 `VW WLAN_5GHz`（带 UUID）→ **77ms 后**车机邀请 →
  `fe80::…:7000`（1831ms）→ verify 21ms → MFi 成功 → 正常推流 3 分钟（无 403）；
- 车机 SETUP 应答带 viewAreas/initialViewArea 结构但未声明 feature（iPhone 仅告警容忍）。

结论：该车型只对「iAP2 配供应链带出的那次入网」激活 CarPlay 栈——系统里保存过/手动
连过车机热点都会堵死连接（我们的「同 SSID 已保存弹窗清理」对此车是关键步骤），
且必须严格用 0x5703 下发的 SSID（双频 SSID，成功路径是 `_5GHz`）。

## §14 车机「只认苹果设备」：发现层声明与兼容探针（2026-11-07）

现象：宝马 iD6（F49-NBTEvo-0716 / iDrive 6 / QNX 6.5.1）在**蓝牙设备列表**里直接给
本机打 `设备不支持 Apple CarPlay`，下面只按普通蓝牙电话提示。这句文案出现在
**iAP2 之前**——车机还没和我们跑任何协议就下了结论，所以调 `0x4300`、改识别顺序、
改问候时机在这条路径上全都无效。

同一台车 11-06 曾一路走到 `0xAA05`（MFi 认证成功），随后被整批 MIB3 突发打崩
（AirPlay 服务进程重启，mDNS `pi` 变化）。所以「不支持」更可能是车机侧的设备注册表
随服务重启后把本机降级成普通电话，而不是每个安卓机都会被永久拒绝。

它看的是**发现层**，只有两处与苹果身份有关：

| 层 | 内容 | 应用层可改 |
|---|---|---|
| EIR（inquiry 响应） | iPhone 广播 `2d8d2466-e14d-451c-88bc-7301abea291a` | ✗ 需 hook `WriteEIR` / 改 system/bt |
| SDP 记录 | `Wireless iAP v2` = `02030302-1d19-415f-86f2-22a2106a0a77` | ✓ `listenUsingRfcommWithServiceRecord` |
| 配对本身（SSP） | 无身份字段，只要求 authenticated+encrypted | 无需改 |
| iAP2 识别（0x1D00/0x1D02） | 空报文体，没有「我是 iPhone」这一项 | 无需改 |
| MFi | 方向是**手机验证车机**（0xAA01→0xAA05） | 无需改 |

据此做了个默认关闭的开关（`BtCompatPrefs` → 设置页「兼容「只认苹果设备」的车机」）：

1. 监听 iAP2 时**额外注册两条占位 SDP 记录**（`BtIdentityProbe`：`CarPlay` 用上面的
   EIR UUID、`Wireless iAP v2` 用 catplay 那条 SDP UUID）。记录不做任何协议，
   连进来只记一行日志并立刻关闭——那行日志本身就是结论：**车机确实在看这个 UUID**。
2. **监听常开**：App 一启动就发布服务，不等「启动连接」。按「先配对、后开 App」的
   顺序操作时，车机那一瞥看不到任何服务，很容易把设备记成「不支持」。

日志关键词：`BT-PROBE:`（注册/连入/撤下）、`BT: 兼容模式`（常开状态）。
判定顺序建议：先删设备重配对（缓存判死）→ 借一台真 iPhone 看同一菜单（区分是否
EIR 判定）→ 再开本开关试。奥迪 MIB3 固件 `CoWSupported: false`，根本不看 EIR，
开这个开关对它没有任何收益（这也是默认关的原因之一）。

顺带修的隐患：`Iap2BluetoothServer.acceptLoop` 在 IOException 退出时没把 `running`
改回 false，`isRunning` 会一直骗人说「在监听」，而 `start()` 又因 `running==true`
直接返回——监听永久假死。常开监听依赖这个修复。

## §15 车机声明「要手机发什么 / 支持什么」——按声明分派（2026-11-08）

车机的声明分四层，此前项目只用了其中两层（`/info` 与 mDNS TXT 驱动行为，
`0x1D01` 双清单只进日志）。这次把 iAP2 那两层补上：

| 层 | 字段 | 本次处理 |
| --- | --- | --- |
| `0x1D01` 参 6/7 | 会发 / 可收清单 | 汇入跨链路单例 `AccessoryDeclaration`；蓝牙与隧道两条出口共用同一把尺子 |
| `0x1D01` 参 12/13/24/30 | 当前语言、支持语言、无线 CarPlay 组件、导航显示组件 | 解析并进 `ReceiverProfile` 的「依据」；语言按 ISO 639-1 规范化后发 `0x4E0A` |
| 运行时 `Start…Updates` | 车机动态点名要手机推状态 | 蓝牙侧补 `0x4157→0x4158`、`0x4154→0x4155`、`0x4170→0x4171`、`0x5200→0x5201`；与隧道侧共用 `Iap2SubscriptionReplies` 的空态字节 |
| mDNS TXT + `/info` | features / 屏 / 音频格式 / HID | 本就完整驱动配对策略、选屏、音频换档，本次未动 |

**判据（别推广）**：清单只能证明「有」，不能证明「没有」——奥迪 3315 清单 5 项不含
`0x5702` 却完整支持旧协议。但**「不在清单却逐条 ACK」不能当作这条判据的证据**：
链路层 ACK 只表示帧的 CRC 通过并被收下，与业务层认不认这条消息无关（2026-11-10 更正，
曾据此误判 `0x4E0D`，见下）。因此**绝大多数越权消息仍照发，只记 `UNSOLICITED_MESSAGE` WARN**。

硬裁的有三处：

- `0xA100`/`0xFFF0`：宝马 NBT Evo 与 Cinemo 都没声明可收，整批发过去 42~116ms
  内事件通道 EOF / AirPlay 服务重启。**只裁这两条，不关整条隧道**——宝马会连发
  SYN marker 等我们的会话表，关掉换来的是一直黑屏。
- `0x4E0D`（2026-11-10 加，仅 `wifiGen=NEW`）：奥迪 MMI 3232（`AUDI 4930`/DELPHI，
  唯一完整连通过的那台）的蓝牙清单 7 项 `0x4e0c 0x4e09 0x4e0d 0x4e0e 0x5702 0xea00 0xea01`
  里**有**它 → 走 LEGACY 非裁分支，与 1.0.7 逐字节相同；而 GM/Cinemo 三台
  （别克 E2LB / BUICK_3138、凯迪拉克 CT5）9 项清单都没有，三台也全部止于
  「ACK 完我们自己发的最后一帧，随后只剩 `ff550200ee10` 心跳」。
  `0x4E0C`（设备 UUID）**不裁**：奥迪（7 项）、别克（9 项）、CT5（9 项）三台真车
  清单里都声明了它，裁不裁无差别。`0x4E0E` 同样不动（唯一例外是 Audi 实测它
  「接受但从不 ACK」，见 §7 发后不管清单）。

### §15.1 严格模式：新协议车机的可收清单升格为硬门禁（2026-11-10）

`wifiGen=NEW`（两清单合计 ≥10 项仍无 `0x57xx`：GM/Cinemo、宝马 iD9）时，
**清单之外一律不发**（`AccessoryDeclaration.setStrictDeclaredOnly(true)`，由
`Iap2SenderSession` 在 `0x1D01` 出画像后设置一次，跨链路生效）。老协议与清单未到
仍是宽松档，行为逐字节不变。

由此在别克 BUICK_3138（9 项清单）上，`0x4E0D` 与 `0x4E0E` 两条都不再发；
`0x4E09/0x4E0A/0x4E0B/0x4E0C` 因为清单里本来就有，照发。

**协议骨架永不裁**：`0x1D00/0x1D01/0x1D02/0xAA00/0xAA02/0xAA04/0xAA05/0x4300`。
判据不是「重要」，而是「所有真车清单里都没有」——BUICK_3138 的 9 项、奥迪的 7 项
都不含识别与 MFi 帧，裁掉等于永不连接，实验拿不到任何信息。`0x4300` 同理：
它是驱动 `0x4301` 的请求本身（cardeck 0.1.28 记录里也写着「0x4300 从来不在裁剪范围」）。

日志上要能一眼分开两档：严格模式裁剪写「新协议严格模式：按可收清单裁掉」，
宽松档仍是「仍照常发送」，骨架例外写「属协议骨架，不裁」。

清单未到（纯 WiFi）时全部按未知放行，不误裁。

`ReceiverCapabilities`（测试线 `switch-pool-auto`）的做法是「整条隧道不布防」，
**主线没有照搬**，理由是上面这条。主线 `startConnection()` 每轮
`AccessoryDeclaration.reset()`，避免拿上一台车的清单裁这一台。

**导航引导的边界**：车机可收清单出现 `0x52xx` 时，收到 `0x5200` 会回 `0x5201`
**空体**保住控制通道；可收清单明确没有 `0x52xx` 时不回（不塞越权消息）。
真机没有 maneuver 样本，项目也没有导航数据源，**不编造导航报文体**——投仪表/HUD
仍需先拿到 `0x5201` 的字段布局或接入数据源。清单未到时按未知照回空态。

**明确不做**：参数 10（EA 协议组）不解析、不恢复 EA 会话（`d318dd1` 已移除该模块）；
组件声明暂不参与 `era`/`wifiGen` 硬判定（部分车机走私有编号，当分支会把已跑通
车型判进未知路径）；无语言声明时保留原兜底（新代 `zh`、旧代 `zh-CN`，奥迪已跑通）。

日志关键词：`车机声明：可收 N 项` · `组件声明：currentLanguage=…` ·
`0x4E0A 语言=…` · `车机可收清单未声明 0x… （bt/tunnel）… 裁掉/仍照常发送` ·
`订阅应答 0x4157 → 0x4158` · `0x5200 → 0x5201` · `PROTO! WARN [UNSOLICITED_MESSAGE]`。

