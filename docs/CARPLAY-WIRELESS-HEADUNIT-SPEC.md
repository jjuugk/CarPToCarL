# 无线 CarPlay 车机端（接收端）实现规范

> **定位**：本文是「用软件实现一台虚拟车机」的协议规格，视角是**接收端（配件 / 车机侧）**，  
> 目标是照着它能把整条链路做出来：蓝牙 iAP2 引导 → WiFi 切换 → AirPlay RTSP 会话 →  
> H.264 屏幕流 + 音频流 → HID 触摸/按键回传。
>
> **与 `docs/PROTOCOL-NOTES.md` 的关系**：那份笔记写的是**发送端（iPhone 角色）**，  
> 是本项目实际在跑的方向；本文是它的镜像，接收端要做的动作正好是那份文档里「车机做的事」。  
> 两份文档合起来才是完整协议。
>
> **标注约定**：✅ = 已在参考源码或实车抓包中逐字节确证；⚠️ = 有源码/日志支持但未逐字段验证；  
> ❓ = 公开资料未覆盖，需自行抓包确认。凡未标注的表格，字段含义来自 Apple 内部规格档案  
> `iap2messages-internal.i2mspecarchive`（catplay `core/catplay_csm/src/msg/iap2.rs` 由它生成）。

---

## 0. 先说清楚能做什么、做不了什么

| 能力                       | 可行性              | 说明                                                                                                  |
| ------------------------ | ---------------- | --------------------------------------------------------------------------------------------------- |
| 协议栈全部软件实现                | ✅ 可行             | 本文 + 第 11 节的开源实现足以覆盖                                                                                |
| 真 iPhone 能连上并出画面         | ⚠️ 取决于 MFi 身份    | iPhone 会**验证车机的 MFi 证书链**（0xAA00 段）。没有 Apple 签发的车机证书，只能连「跳过校验」的实现；商业盒子/开源方案的通行做法是用从量产 dongle 里提取的证书 |
| FairPlay 解密（`/fp-setup`） | ⚠️ 需 FairPlay 密钥 | Apple 专利保护，公开实现只有握手中继/降级路径                                                                          |
| 量产                       | ❌ 需 MFi 授权       | 见 `docs/LEGAL-NOTES.md`；MFi 会员免费，但认证芯片与审核是硬门槛                                                       |

**一句话**：协议本身没有秘密（本文可以写到字节级），**卡点只有 MFi 证书 + FairPlay 密钥**这两把钥匙。

---

## 1. 系统全景

```
┌──────────────────────────── iPhone（发送端 / device）────────────────────────────┐
│  驱动 CarPlay UI，编码 H.264/HEVC，采集音频，把触摸事件送给自己                   │
└───────┬─────────────────────────────────────────────┬───────────────────────────┘
        │ ① 蓝牙 RFCOMM（iAP2，仅引导）                │ ③ WiFi TCP/UDP（AirPlay）
        │ 识别 / MFi / 下发 WiFi 凭据 / 端口与公钥      │ RTSP 控制 + 加密事件通道 + 媒体流
┌───────▼─────────────────────────────────────────────▼───────────────────────────┐
│                        车机（接收端 / accessory）                                │
│  L1 蓝牙: SDP + RFCOMM + iAP2 link(FF55/FF5A) + CSM(0x4040) + MFi                │
│  L2 网络: 热点 or 已入网 + mDNS(_airplay._tcp) + TCP 7000 监听                    │
│  L3 会话: RTSP 服务端（pair-setup/pair-verify/auth-setup/fp-setup/SETUP/RECORD）  │
│  L4 媒体: 解密 → 解封装(AVCC) → 硬解 → 上屏；音频 RTP → 解码 → 功放               │
│  L5 输入: HID 报文 → POST /command（事件通道，加密）                              │
└─────────────────────────────────────────────────────────────────────────────────┘
```

阶段划分与「谁先动」：

| 阶段              | 发起方                                 | 结束标志                |
| --------------- | ----------------------------------- | ------------------- |
| ① 蓝牙配对 + RFCOMM | 系统层（两台设备需先系统级配对）                    | 链路 NORMAL           |
| ② iAP2 识别 + MFi | **手机发 0x1D00**，车机回 0x1D01（✅ 实车逐帧确认） | 手机发 0xAA05          |
| ③ 下发 WiFi 凭据    | 手机发 0x5702 或 0x4300                 | 车机回 0x5703 / 0x4301 |
| ④ 手机入网 + mDNS   | 车机广播 `_airplay._tcp`                | 手机 TCP 连上 7000      |
| ⑤ RTSP 会话       | 手机                                  | RECORD 成功、首帧到达      |
| ⑥ 稳态            | 双向                                  | 三条心跳任一断 → 拆会话       |

> ⚠️ 注意：**iAP2 里「配件」是车机，「设备」是 iPhone**。但 0x1D00（StartIdentification）  
> 是**设备**发出的（✅ 实车日志逐帧确认）。车机的动作是：收到 0x1D00 → 回 0x1D01。

---

## 2. 第一阶段：蓝牙 iAP2

### 2.1 服务发现与 RFCOMM

| 项                 | 值                                                                             |
| ----------------- | ----------------------------------------------------------------------------- |
| 车机（配件）服务 UUID     | `00000000-deca-fade-deca-deafdecacaff` ✅                                      |
| iPhone（设备）服务 UUID | `00000000-deca-fade-deca-deafdecacafe` ✅                                      |
| SDP 服务名           | 任意（常见 `iAP2`）                                                                 |
| RFCOMM 通道         | 由 SDP 动态分配（业界惯例 0x02~0x1E），**不要硬编码**                                          |
| 安全                | 必须 authenticated + encrypted（`listenUsingRfcommWithServiceRecord`，非 insecure） |

前置条件：两台设备必须先完成**系统级蓝牙配对**（iPhone 设置里能看到车机）。  
未配对时 iPhone 不会连这个 RFCOMM 服务。

### 2.2 链路层帧格式 ✅

```
偏移  长度  字段
0     2     起始符 0xFF 0x5A
2     2     总长度 length（大端）＝ 9(头) + 载荷长度 + 1(载荷校验和)
4     1     control
5     1     seq（u8 回绕，初始值 99）
6     1     ack（已按序收到的最大 seq）
7     1     会话 ID（控制会话 10 / EA 11 / 文件传输 12；见 2.4）
8     1     头校验和
9     n     载荷
9+n   1     载荷校验和（无载荷时不存在，此时 length=9）
```

**校验和算法**（两个校验和相同）：把该段所有字节相加取低 8 位，再取补码，  
使「含校验和在内的字节和 mod 256 == 0」。

```text
chk = (0x100 - (sum(bytes) & 0xFF)) & 0xFF
```

校验示例 ✅（catplay 单测常量）：

```
FF 5A 00 1A 80 2B 00 00 E2
```

前 8 字节和为 `0x11E`，低 8 位 `0x1E`，补码 `0xE2` ✓；`control=0x80` 即纯 SYN。

**control 位**：

| 位    | 名   | 含义                         |
| ---- | --- | -------------------------- |
| 0x80 | SYN | 同步（协商）包                    |
| 0x40 | ACK | 确认位；**数据帧必须置位**（捎带 ACK）    |
| 0x20 | EAK | 扩展确认：告诉对端「从 ack 这个序号起我没收到」 |
| 0x10 | RST | 复位 / 拆链                    |

|                              |                                         |
| ---------------------------- | --------------------------------------- |
| 常见组合：`0x80` 纯 SYN、`0xC0` SYN | ACK（回显）、`0x40` 数据/裸 ACK、`0x60` EAK+ACK。 |

### 2.3 握手：marker / LSP

**marker（也叫 DETECT）**：固定 6 字节 ✅

```
FF 55 02 00 EE 10
```

它的作用是「把对端从探测态唤醒」，不属于 FF5A 帧，接收端靠帧同步扫描自然丢弃。

**LSP（Link Synchronization Payload）**：放在 SYN 帧载荷里，10 字节定长头 + 会话表 ✅

| 偏移 | 类型    | 字段                     | 车机建议值                             |
| -- | ----- | ---------------------- | --------------------------------- |
| 0  | u8    | version                | 1                                 |
| 1  | u8    | maxOutgoing            | 4 ~ 127（自己的发送窗口/信用）               |
| 2  | u16BE | maxLength              | 0xFFFF 或 0x1FFF（单帧最大字节，含 9B 头+校验） |
| 4  | u16BE | retransmissionTimeout  | 3000（ms）                          |
| 6  | u16BE | acknowledgementTimeout | 500（ms）                           |
| 8  | u8    | maxRetransmissions     | 10 ~ 30                           |
| 9  | u8    | maxAcknowledgements    | 3                                 |
| 10 | u8×3  | 会话描述符 1                | id, type, version                 |
| 13 | u8×3  | 会话描述符 2                | …                                 |
| 16 | u8×3  | 会话描述符 3                | …                                 |

会话描述符的 `type`：0 = 控制会话，1 = 文件传输，2 = EA（ExternalAccessory）。

**握手时序（车机侧两种合法姿态）**：

```text
A. 车机主动（iPhone 先发 marker 的场合）
   车机 → MARKER            （可每 1s 重发，直到收到 SYN）
   iPhone → FF5A SYN(LSP)
   车机 → FF5A SYN|ACK(LSP)  ← 必须消耗一个**新的** seq
   iPhone → 数据帧
   → NORMAL

B. 车机被动（iPhone 是发起方，常见）
   iPhone → MARKER
   iPhone → FF5A SYN(LSP)
   车机 → FF5A SYN|ACK(LSP)  ← 回显对端会话表 + ACK 位
   车机 → NORMAL（收到 SYN|ACK 后 iPhone 会立刻发 0x1D00，不等 ACK）
```

**三条血泪规则**（本项目实车定谳，见 `docs/PROTOCOL-NOTES.md` §9h）：

|   |   |
| - | - |

|   |   |
| - | - |

1. **SYN|ACK 必须用新 seq**。复用 SYN 的 seq 会被对端当重复帧丢弃 → 协商永不收敛  
   （BMW 14 轮全部卡死，日志里 133 条 SYN / 0 条 ACK）。


   |   |   |
   | - | - |
2. **每一条纯 SYN 都要回一帧 SYN|ACK**（不能只回第一条）。对端每轮重试都换 seq 重开协商。
   |   |   |   |
   | - | - | - |
3. **只对「纯 SYN（不带 ACK 位）」回 SYN|ACK**。对 SYN|ACK 也回会形成无限乒乓  
   （两个实现互喂时协商永远不收敛，实测把 CPU 打满）。

### 2.3.1 握手族（`FF 55`）与「对端拒绝链路」✅

`FF 55` 和 `FF 5A` 是**两族**报文。旧实现只认后者，前者被帧头扫描逐字节丢弃——
于是「车机明确说我不兼容」这件事在日志里根本不存在，只能干等到上层预算耗尽。

| 报文 | 形态 | 含义 |
| --- | --- | --- |
| DETECT marker | `FF 55 02 00 EE 10`（6 字节定长） | 唤醒/探测。车机可能每秒重发上百次（Honda HFT），正常，忽略 |
| 握手应答 | `FF 55` + u16LE 载荷长度 + 载荷 | 对端对握手的回答。**本端没有实现握手应答** |

车机固件（MIB3 SW3874，`iap2::CIAP2LinkHandshake`，状态机 `UNITIALIZED /
HANDSHAKING / NEGOTIATING_PARAMETER / INITIALIZED / UNSUPPORTED`）的路径是
`sendHandshake → evaluateHandshakeAnswer → 不兼容时 sendLackOfBackwardCompatibility`
（见 `MIB3_CarPlay对端协议材料清点.html` §1.5），**「我不支持你」正是以非 marker 的
`FF55` 报文出现**；另一条确定性证据是 LSP 首字节 `LinkVersion`（7 个链路参数里的第一项）。

`Iap2LinkEngine` 的处置（2026-11-10）：

1. 认出 marker → 丢弃，**不回调**（每秒重发，回调会刷屏）；
2. 认出非 marker 且长度字段可读 → 计一次握手应答，回调 `onPeerHandshake` 把原始
   字节打进日志；**累计 2 次、且一条 LSP 都还没采纳**（参数协商根本没开始）→
   判 `peer rejected link` 立即终止；
3. 长度字段读不出合理值（截断的 FF5A / 别的端序）→ 只记诊断、**不计入拒绝**
   （连长度都读不出来，拿它拆链太冒险），丢 2 字节继续扫描；
4. 对端 LSP 的 `LinkVersion != 1` → **立即**终止，不等第二次（确定性证据）。

> 目的只有一个：把「协商无进展、等 60 秒预算耗尽」变成 1 秒内一行可定位的原因。
> `Iap2LinkEngine.failFastOnHandshakeRejection = false` 可退回旧行为（只记日志）。


| 会话 ID | type | version | 用途                     |
| ----- | ---- | ------- | ---------------------- |
| 10    | 0    | 2       | 控制会话（CSM）——**默认**      |
| 11    | 2    | 1       | EA 隧道（车控数据、部分车的触摸 HID） |
| 12    | 1    | 2       | 文件传输（仪表封面图等）           |

⚠️ **会话 ID 不是固定的**：奥迪 MIB3 以发起方姿态声明 `{1: control, 2: EA}`，  
并要求手机**原样回显**；此后 CSM 必须发在 **1 号**会话，发 10 号会被**静默丢弃**  
（链路层照常 ACK，极难排查）。车机实现时的正确做法：

- 自己是发起方 → 用自己的表（10/11/12 或 1/2），并接受对端回显。
- 自己是响应方 → **采纳对端表**，之后所有控制消息用对端声明的 control id 发。

### 2.5 可靠传输

| 机制  | 规则                                                                  |                         |
| --- | ------------------------------------------------------------------- | ----------------------- |
| seq | u8 回绕，初值 99；数据帧、SYN                                                 | ACK 各消耗一个；裸 ACK **不消耗** |
| ack | 每帧都带（即使是纯 SYN 也填「已按序收到的最大 seq」）                                     |                         |
| 窗口  | 自己的未确认包 ≤ 自己声明的 maxOutgoing；对端窗口只用于判断其能否再收                          |                         |
| 重传  | 超时 = 对端 LSP 的 retransmissionTimeout（夹在 500~8000ms），次数 = min(自己, 对端) |                         |
| EAK | 收到 `control & 0x20` 立即重传 ack 之后最靠前的那个未确认包，**不要整窗重发**                |                         |
| 重复帧 | seq 等于水位 → 只补 ACK，不投递                                               |                         |

> 只重传一个包而不是整窗：一次 EAK 触发整窗重发会把链路打成洪水，  
> 而且每个包的重传计数会一次刷满，到顶就自己拆链。

### 2.6 CSM（控制会话消息）分帧与 TLV ✅

```
帧：  0x4040 | u16 总长（含 6 字节头）| u16 消息 ID | 参数体
参数：u16 参数长（含 4 字节头）| u16 参数 ID | 值
```

- 大端；无校验和（完整性靠链路层）。
- 字符串：NUL 结尾 UTF-8。
- 组参数：值为子参数的序列（可递归）。
- 一条 CSM 消息超过对端声明的 `maxLength` 时，**按字节流切片**放进多个链路帧，  
  接收端按长度重组（不是每片一个 CSM 头）。

### 2.7 车机要发的 0x1D01（识别信息）

参数 ID 与含义（2026-11-07 按 catplay 的 Apple 规格档案 `IdentificationInformation` 逐条核对；
下表只列本项目会用到的，完整 36 项见该结构定义）：

| 参数 ID | 内容                            | 类型                          |
| ----- | ----------------------------- | --------------------------- |
| 0     | name                          | NUL 结尾字符串                   |
| 1     | modelIdentifier               | 字符串                         |
| 2     | manufacturer                  | 字符串                         |
| 3     | serialNumber                  | 字符串                         |
| 4     | firmwareVersion               | 字符串                         |
| 5     | hardwareVersion               | 字符串                         |
| 6     | messagesSentByAccessory       | u16 数组（**车机会主动发的消息 ID 清单**） |
| 7     | messagesReceivedFromDevice    | u16 数组（**车机愿意收的消息 ID 清单**）  |
| 8     | powerProvidingCapability      | u8（0=None 1=C37 直通 2=Advanced） |
| 9     | maximumCurrentDrawnFromDevice | u16（mA）                     |
| **10** | **EA 协议组（可重复出现，不是 10/11/12 三个槽）** | 组：0=协议号 u8，1=协议名 字符串 |
| 11    | appMatchTeamID                 | 字符串（可选）                    |
| 12    | currentLanguage                | 字符串                        |
| 13    | supportedLanguage              | 字符串，可多个                    |
| 14    | UART 传输组件                    | 组                           |
| 15    | USB Device 传输组件             | 组                           |
| 16    | USB Host 传输组件               | 组                           |
| 17    | 蓝牙传输组件                       | 组（可多个）                     |
| 18    | iAP2 HID 组件                  | 组（可多个）                     |
| 20    | 车辆信息组件                       | 组                           |
| 21    | 车辆状态组件                       | 组                           |
| 22    | 定位信息组件                       | 组                           |
| 23    | USB Host HID 组件              | 组（可多个）                     |
| 24    | 无线 CarPlay 传输组件              | 组（可多个）                     |
| 26    | BLE 传输组件                     | 组                           |
| 29    | 蓝牙 HID 组件                     | 组                           |
| 30    | 导航引导显示组件                     | 组（可多个）                     |

> **参数 10 是重复出现的组，不是一个号一个协议。** 早期实现按 10/11/12 三个槽解析，
> 遇到「三个协议全填在 10 号」的车机会漏掉两个——本项目 `Iap2Ea.GROUP_IDS = 10..12`
> 是为了同时兼容两种写法（实车两种都见过），**车机侧实现只需按参数 10 重复下发**。

**参数 7 是整条协议里最有用的一条**：iPhone 会据它决定「哪些消息发给这台车机」。  
但它**只能证明「有」，不能证明「没有」**——本项目实测有车机清单只有 5 项却完整支持  
0x5702 流程，也有车机清单没写 0x4E0D 但照样逐条 ACK。所以不要用它做硬门禁。  
**唯一例外**：`0xA100`（AUX 状态）与 `0xFFF0`（GPRMC 状态）来自奥迪 MIB3 抓包，
只有 MIB3 系声明可收；宝马 NBT Evo / iD6 与通用系 Cinemo 都没声明，整批发过去会
让 AirPlay 服务重启（`pi` 变化）或整链 reset。项目按声明**只裁这两条**，其余越权消息
照发并记 `UNSOLICITED_MESSAGE`（实现：`iap2/AccessoryDeclaration.noteSend`）。
不要推广成「凡未声明都不发」，也不要因此关掉整条隧道——宝马会连发 SYN marker
等我们的会话表，关隧道换来的是一直黑屏。

### 2.8 车机必须实现的消息集（最小可用）

方向记号：`A→D` 车机发给手机，`D→A` 手机发给车机。

| ID                   | 名称                                           | 方向  | 车机动作                           | 必须    |
| -------------------- | -------------------------------------------- | --- | ------------------------------ | ----- |
| 0x1D00               | StartIdentification                          | D→A | 收到即回 0x1D01                    | ✅     |
| 0x1D01               | IdentificationInformation                    | A→D | 见 2.7                          | ✅     |
| 0x1D02               | IdentificationAccepted                       | D→A | 收到即进 MFi                       | ✅     |
| 0x1D03               | IdentificationRejected                       | A→D | 拒绝时发                           | 可选    |
| 0xAA00               | RequestAuthenticationCertificate             | D→A | 回 0xAA01                       | ✅     |
| 0xAA01               | AuthenticationCertificate                    | A→D | param0 = 证书链                   | ✅     |
| 0xAA02               | RequestAuthenticationChallengeResponse       | D→A | param0 = 挑战                    | ✅     |
| 0xAA03               | AuthenticationResponse                       | A→D | param0 = RSA 签名                | ✅     |
| 0xAA05               | AuthenticationSucceeded                      | D→A | 收到即可继续                         | ✅     |
| 0xAA06               | AccessoryAuthenticationSerialNumber          | A→D | 仅控制会话 version 2 使用             | ⚠️    |
| 0x5702               | RequestAccessoryWiFiConfigurationInformation | D→A | 回 0x5703                       | 旧代必做  |
| 0x5703               | AccessoryWiFiConfigurationInformation        | A→D | SSID/密码/信道/安全类型                | 旧代必做  |
| 0x4300               | CarPlayAvailability                          | D→A | 回 0x4301                       | 新代必做  |
| 0x4301               | CarPlayStartSession                          | A→D | AirPlay 端口/IP/公钥/无线参数          | 新代必做  |
| 0x4E09               | DeviceInformationUpdate                      | D→A | 设备名（记录即可）                      | —     |
| 0x4E0A               | DeviceLanguageUpdate                         | D→A | 语言                             | —     |
| 0x4E0B               | DeviceTimeUpdate                             | D→A | u64 epoch + s16 时区 + u8 夏令时    | —     |
| 0x4E0C               | DeviceUUIDUpdate                             | D→A | 会话标识（随机 UUID 大写）               | —     |
| 0x4E0D               | WirelessCarPlayUpdate                        | D→A | 无线可用性通知                        | —     |
| 0x4E0E               | DeviceTransportIdentifierNotification        | D→A | 蓝牙 MAC，用于核对身份                  | —     |
| 0x6800               | StartHID                                     | A→D | 声明要发 HID                       | ✅（触摸） |
| 0x6801               | DeviceHIDReport                              | D→A | 手机侧 HID 回传                     | —     |
| 0x6802               | AccessoryHIDReport                           | A→D | 触摸/旋钮事件                        | ✅     |
| 0x6803               | StopHID                                      | A→D | 停                              | —     |
| 0xEA00               | StartExternalAccessoryProtocolSession        | D→A | 开 EA 隧道（param0=协议号，param1=协议名） | 视车机   |
| 0xEA03               | StatusExternalAccessoryProtocolSession       | A→D | 隧道就绪                           | 视车机   |
| 0x5000/0x5001        | StartNowPlayingUpdates / Update              | 双向  | 媒体状态（仪表盘歌名）                    | 可选    |
| 0x5200/0x5201/0x5203 | StartRouteGuidanceUpdates / Update / Stop    | A→D / D→A | 结构化导航引导（投仪表/HUD）              | 可选    |
| 0xA100/0xA101        | StartVehicleStatusUpdates / Update           | 双向  | 车况                             | 可选    |
| 0xFFFA/0xFFFB/0xFFFC | StartLocationInformation / 样本 / Stop         | 双向  | 导航定位                           | 可选    |


> **关键约束**：任何一条 `Start…Updates` 都必须有人回一条对应的状态消息（哪怕是空态），  
> 否则发起订阅的一方会在 ~10s 后关掉控制通道。本项目实测：漏 ACK 与「回了空态但没订阅」  
> 表现相同。**注意两个方向不要混**：
>
> - **车机（配件）发起订阅 → 手机回状态**：`0x5000→0x5001`（NowPlaying）、  
>   `0x4157→0x4158`（通讯）、`0x4154→0x4155`（通话状态）、`0x4170→0x4171`（列表）、  
>   `0x5200→0x5201`（导航引导，车机可收清单需含 `0x52xx`；无 maneuver 数据源时回空体）
> - **手机发起订阅 → 车机回状态**：`0xFFFA→0xFFFB`（定位）、`0xA100→0xA101`（车况）
>
> 蓝牙链路与 WiFi 隧道**都可能**收到这些订阅，两条链路必须回同样的字节
> （实现：`iap2/Iap2SubscriptionReplies`，蓝牙侧 `answerSubscription`、
> 隧道侧 `maybeRespondControlCsmLocked` 共用一份空态定义）。

完整消息表见**附录 A**（约 150 条，来自 Apple 内部规格档案）；项目代码里的机器可读版是  
`iap2/Iap2MessageCatalog.kt`（144 条），两者由同一份档案导出。

### 2.9 MFi 认证（0xAA00 段）

```text
D→A  0xAA00                       请求证书
A→D  0xAA01  param0 = 证书链       典型 908 B（MFi 3.0；实测 607/912 B 都有）
           param? = 序列号         可选
D→A  0xAA02  param0 = 挑战         实测 16 / 20 / 32 / 64 B 都出现过（见下）
A→D  0xAA03  param0 = 签名         128 B（RSA-2048）；实测报文 132 B
A→D  0xAA06                        仅在控制会话 version 2 单向发送
D→A  0xAA04 / 0xAA05               失败 / 成功
```

硬件侧的真实耗时（决定了各方的超时该设多大）：

| 操作                        | 耗时                            |
| ------------------------- | ----------------------------- |
| RSA-2048 签名（MFi 3.0 协处理器） | **390~425 ms**                |
| 读 908 B 证书（I2C 300kHz）    | ~27 ms                        |
| I2C 地址                    | MFi 2.0 = 0x11，MFi 3.0 = 0x10 |

**挑战长度差异**（本项目实车汇总）：同一台车机不同年款偏好相反——  
奥迪 MMI 0000（4xxx 固件）认 20 B 且对 32 B 会拆链；另一台 MMI 0000 认 32 B 而 20 B 静默 6 秒；  
DiPlay（Carlinkit 身份）**只接受精确 32 B**，给 20 B 直接断链。  
→ 车机侧实现建议：**接受任意长度**（16/20/32/64），不要按长度拒绝。

MFi 身份是**单向**的：iPhone 验证车机，车机完全信任 iPhone 自报的 0xAA05。  
所以车机没有证书时，iPhone 会走 0xAA04 → 弹「配件不支持」/ 直接不开 CarPlay。

### 2.10 WiFi 凭据的两代协议

**旧代（0x5702 → 0x5703）**——`AccessoryWiFiConfigurationInformation`，注意**参数号从 1 起，
且没有 IP 字段**（2026-11-07 按 Apple 规格档案逐条核对，此前这张表写错了）：

```text
D→A  0x5702  空体（一个参数都没有）
A→D  0x5703  参数体（明文 TLV，全组可选，但 SSID 必有）
        1 = SSID            （NUL 结尾字符串）
        2 = 密码            （安全类型非 None 时必填）
        3 = 安全类型        u8：0=None 1=WEP 2=WPA2/WPA3 过渡
        4 = 信道            u8（40/80MHz 时填主信道）
```

**新代（0x4300 → 0x4301）**：AP 信息由 0x4301 的**无线组**自带  
（`CarPlayStartSessionWirelessAttributes`），这类车机在 0x1D01 里既不声明能收 0x5702，
也不声明会发 0x5703。

```text
0x4300  CarPlayAvailability     参数 0=有线组 / 1=无线组 / 2=主题资源
          组内：0=available(bool)  1=传输标识字符串（有线=USB，无线=蓝牙 MAC）
0x4301  CarPlayStartSession     参数 0=有线组 / 1=无线组 / 2=port(u32) / …
          无线组（⚠️ 与 0x5703 不是同一套编号）：
            0 = SSID      1 = 密码      2 = 信道(u8)
            3 = IP（可多个，链路本地 IPv6 不带 zone）
            4 = 安全类型  0=保留 1=保留 2=保留 3=WPA3 过渡 4=WPA3 only
```

两处最容易踩的差别（本项目代码 `parseWifiConfiguration` / `parseWirelessAp` 分别按这两套
布局取值，不要合并）：

| | 0x5703 | 0x4301 无线组 |
|---|---|---|
| SSID / 密码 | 1 / 2 | 0 / 1 |
| 信道 | 4 | 2 |
| IP | **无此字段** | 3 |
| 安全类型 | 3（只有 0/1/2 三个值） | 4（3/4 才是 WPA3 两档） |

**车机实现要点**：

- 0x4301 是**生命周期事件**，迟到也要发。实测有车机在识别结束后 **2 分 35 秒**才发  
  （车机侧 CarPlay 窗口刚开），此时客户端早超时了。
- 热点网关不一定是 `.1`（实测有 `10.174.189.254`），端口不一定是 7000。  
  信息要靠 0x4301 或 mDNS 给出，猜不得。
- 老固件（CarPlay 210.81 / 奥迪 4xxx）常只给 **IPv6 链路本地**（`fe80::…%wlan0`）。

---

## 3. 第二阶段：WiFi + Bonjour

### 3.1 AP 参数

| 项    | 建议                                                |
| ---- | ------------------------------------------------- |
| 频段   | 5 GHz 优先（2.4G 在车内干扰重；实测车机双频 SSID 时成功路径总是 `_5GHz`） |
| 信道   | 149 等非 DFS 信道（DFS 会让开机等雷达检测 60s）                  |
| 安全   | WPA2-PSK（WPA3 过渡模式兼容性差）                           |
| 加密   | AES/CCMP                                          |
| DHCP | 车机做 DHCP 服务端，给手机 192.168.x.x / 10.x.x.x           |

⚠️ iPhone 端有个坑（对车机有影响）：手动连过的热点会让系统记成「用户网络」，  
导致 iAP2 供应链带出的那次入网被绕过 → CarPlay 栈不激活。所以**每次下发的 SSID  
应当是本次会话专用的**（加随机后缀），不要用固定 SSID。

### 3.2 `_airplay._tcp` 广播与 TXT 记录 ✅

车机在 TCP 7000（惯例）上监听，Bonjour 广播：

```text
服务类型: _airplay._tcp
端口:     7000
TXT:
  deviceid=AA:BB:CC:DD:EE:FF         车机蓝牙 MAC（必须与 RFCOMM 侧一致）
  features=0x44540380,0x61           低 32 位,高 32 位（十六进制字符串）
  flags=0x4
  model=AUDI 0000                    车机型号
  srcvers=320.17.7                   车机 AirPlay 版本
  protovers=1.1
  pi=<UUID>                          接收端持久标识（见下）
  pk=<32B ed25519 公钥 hex>          配对公钥（老车机可能没有）
  vn=65537 / vs=... / ov=...         可选
```

**`pi` 是诊断金钥匙**：接收端持久标识，正常不该变。它一变就说明**车机的 AirPlay  
服务进程重启了**——这是判断「车机被我们打崩」最硬的证据（本项目靠它定谳宝马 iD6 重启）。

### 3.3 features 位表 ✅

低 32 位：

| 位        | 名                                    | 含义              |
| -------- | ------------------------------------ | --------------- |
| 7        | SCREEN                               | 支持屏幕镜像          |
| 8        | ROTATE                               | 支持旋转            |
| 9        | AUDIO                                | 支持音频            |
| 11       | REDUNDANT_AUDIO                      | 冗余音频            |
| 14       | AUTHENTICATION4                      | 认证 v4           |
| 15/16/17 | AUDIO_METADATA_ARTWORK/PROGRESS/TEXT | 媒体元数据           |
| 18       | AUDIO_PCM                            | PCM             |
| 19       | AUDIO_ALAC                           | ALAC            |
| 20       | AUDIO_AAC_LC                         | AAC-LC          |
| 21       | AUDIO_AAC_ELD                        | AAC-ELD         |
| 22       | AUDIO_UNENCRYPTED                    | 允许明文音频          |
| 26       | AUDIO_AES_128_MFI_SAP_V1             | MFi SAP v1 音频加密 |
| 27       | PAIRING                              | 支持配对            |
| 29       | UNIFIED_METADATA                     | 统一元数据           |
| 30       | UNIFIED_BONJOUR                      | 统一 Bonjour      |

高 32 位：

| 位     | 名                                               | 含义                                |
| ----- | ----------------------------------------------- | --------------------------------- |
| 32    | CAR                                             | CarPlay 设备                        |
| 33    | VIDEO_FAIRPLAY                                  | 视频 FairPlay                       |
| 37    | CARPLAY_CONTROL                                 | 支持 `_carplay-ctrl._tcp` 控制通道      |
| 38    | HK_PAIRING_AND_ENCRYPT                          | **HomeKit 配对 + 加密**（老车机不声明 → 走明文） |
| 40    | BUFFERED_AUDIO                                  | 缓冲音频                              |
| 41    | SUPPORTS_1588_CLOCK                             | PTP                               |
| 42    | SUPPORTS_SCREEN_MULTI_CODEC                     | H.264/HEVC 双编解码                   |
| 43    | SYSTEM_PAIRING                                  | 系统级配对                             |
| 45    | SUPPORTS_HARDWARE_1588_CLOCK                    | 硬件 PTP                            |
| 46/47 | HK_PAIRING_AND_ACCESS_CONTROL / PEER_MANAGEMENT | 配对管理                              |
| 48    | TRANSIENT_PAIRING                               | 临时配对                              |
| 52    | SUPPORTS_SET_PEERS_EXTENDED_MESSAGE             | SETPEERS 扩展                       |

**解码示例**（实车抄下来的两个值）：

```
0x44540380,0x61
  低: 7(SCREEN) 8(ROTATE) 9(AUDIO) 18(PCM) 20(AAC_LC) 22(明文音频) 26(MFI SAP) 30(UNIFIED_BONJOUR)
  高: 32(CAR) 37(CARPLAY_CONTROL) 38(HK_PAIRING_AND_ENCRYPT)   ← iPhone 同构

0x4040280,0x61
  低: 7(SCREEN) 9(AUDIO) 18(PCM) 26(MFI SAP) 30(UNIFIED_BONJOUR)
  高: 32 37 38
  → 没有 8(ROTATE)、没有 20(AAC)、没有 22(明文音频)：老一代车机
```

> 判年代的最快方法：**bit38 不在 → 老车机，跳过配对走明文**（AES-128-CTR 零密钥）。

### 3.4 地址与端口选择

- 手机侧会广播 `_carplay-ctrl._tcp`（TXT: `id=<手机蓝牙 MAC>`），车机 resolve 后  
  **反连**该端口发 `GET /ctrl-int/1/connect` ——这是「催手机开工」的邀请。  
  ⚠️ 这条不是所有车机都有（测试台有、奥迪部分有、宝马走 0x4301）。
- 车机不要假定手机用 IPv4：手机可能只在 `fe80::` 上可连。

---

## 4. 第三阶段：AirPlay 会话（车机是 RTSP 服务端）

### 4.1 端点表（接收端视角）✅

| 方法            | 路径             | 作用                          | 必须  |
| ------------- | -------------- | --------------------------- | --- |
| GET           | `/info`        | 能力查询（见 4.3）                 | ✅   |
| POST          | `/info`        | 同上（带 body）                  | 可选  |
| POST          | `/pair-setup`  | HomeKit SRP 配对（首次）          | ✅   |
| POST          | `/pair-verify` | X25519 + Ed25519 校验（日常）     | ✅   |
| POST          | `/auth-setup`  | MFi/legacy SAP 流密钥          | 视车机 |
| POST          | `/fp-setup`    | FairPlay 密钥交换               | 视车机 |
| SETUP         | `/setup`（相对路径） | 建流 / 初始会话                   | ✅   |
| RECORD        | 任意             | 开始推流                        | ✅   |
| FLUSH         | 任意             | 清缓冲（暂停/seek）                | ✅   |
| TEARDOWN      | 任意             | 拆会话                         | ✅   |
| SET_PARAMETER | 任意             | 音量、进度等                      | 可选  |
| GET_PARAMETER | 任意             | `volume` 等（text/parameters） | 可选  |
| POST          | `/command`     | **命令字典**（HID、模式、Siri）       | ✅   |
| POST          | `/feedback`    | 心跳 + 播放进度反馈                 | ✅   |
| POST          | `/audio-mode`  | 音频模式切换                      | 可选  |
| GET           | `/log`         | 返回 404 即可                   | —   |

⚠️ **老固件要求 SETUP 用相对路径 `/setup`**，发绝对 `rtsp://host:port/` 会被拒。

### 4.2 头部与 Content-Type

| 头              | 值                                  | 何时                       |
| -------------- | ---------------------------------- | ------------------------ |
| `CSeq`         | 递增整数                               | 所有请求                     |
| `Content-Type` | `application/x-apple-binary-plist` | 带 bplist 体的请求            |
| `Content-Type` | `application/pairing+tlv8`         | pair-setup / pair-verify |
| `Content-Type` | `text/parameters`                  | GET/SET_PARAMETER 文本参数   |
| `X-Apple-HKP`  | `2`（或 `3`）                         | **配对请求必带**，缺了 403        |
| `X-Apple-PD`   | `1`                                | pair-verify 必带           |
| `X-Apple-ET`   | `32` 等                             | 声明加密类型                   |
| `User-Agent`   | `AirPlay/xxx`                      | 建议带                      |

### 4.3 `/info` 响应（车机能力清单）

必填字段（bplist 字典）：

| 键                                 | 类型           | 说明                                                |
| --------------------------------- | ------------ | ------------------------------------------------- |
| `deviceID`                        | string       | 车机蓝牙 MAC（与 TXT `deviceid` 一致）                     |
| `features`                        | integer      | 见 3.3，**必须与 TXT 一致**                              |
| `statusFlags`                     | integer      | bit0 PROBLEM / bit1 UNCONFIGURED / bit2 AUDIO     |
| `model` / `manufacturer` / `name` | string       | 型号/厂商/名称                                          |
| `srcvers`                         | string       | AirPlay 版本                                        |
| `protocolVersion`                 | string       | `1.1`                                             |
| `displays`                        | array        | 见下，**至少一个**                                       |
| `audioFormats`                    | array        | `{type, audioOutputFormats, audioInputFormats}`   |
| `audioLatencies`                  | array        | `{type, inputLatencyMicros, outputLatencyMicros}` |
| `keepAliveLowPower`               | bool         | 真 → 开 UDP keepAlive                               |
| `keepAliveSendStatsAsBody`        | bool         | 真 → 用 KeepAliveWithBody                           |
| `hidDevices`                      | array        | HID 设备（触摸必填）                                      |
| `hidLanguages`                    | array        | 例如 `["en-US"]`                                    |
| `rightHandDrive`                  | bool         | 右舵                                                |
| `nightMode`                       | bool         | 夜间模式                                              |
| `limitedUI` / `limitedUIElements` | bool / array | 行车限制 UI                                           |
| `oemIcons`                        | array        | 车标（可选）                                            |
| `hevcInfo`                        | dict         | 支持 HEVC 时给                                        |


`displays[]` 每一项：

| 键                                  | 类型      | 说明                       |
| ---------------------------------- | ------- | ------------------------ |
| `uuid`                             | string  | 显示标识，手机后续 SETUP 会引用      |
| `widthPixels` / `heightPixels`     | integer | 逻辑分辨率                    |
| `widthPhysical` / `heightPhysical` | integer | 物理像素/毫米                  |
| `maxFPS`                           | integer | 最高帧率（60）                 |
| `features`                         | integer | 显示特性位（是否支持 HDR/dark 等）   |
| `viewAreas`                        | array   | 见下，**CarPlay UI 实际画在这里** |
| `initialViewArea`                  | integer | 初始视区索引                   |
| `viewAreaTransitionControl`        | bool    | 是否允许多视区切换                |
| `cornerMasks`                      | bool    | 圆角遮罩                     |
| `primaryInputDevice`               | dict    | 主输入设备（老车机可能没有）           |
| `edid`                             | data    | 可选                       |

`viewAreas[]`：`{originXPixels, originYPixels, widthPixels, heightPixels, safeArea{...}, viewAreaStatusBarEdge}`  
——坐标是**有符号**的（实测出现过负值 safeArea）。`viewAreaStatusBarEdge` 指 Dock 靠哪边  
（1 = 底边，2 = 驾驶侧）。

### 4.4 配对与加密握手

**pair-setup（首次配对，SRP-6a 3072 / SHA-512）** ✅

```text
M1  D→A  state=1, method=0(None) 或 1(Pin), flags
M2  A→D  state=2, salt(16B), publicKey(B)
M3  D→A  state=3, proof(A)
M4  A→D  state=4, proof(M)
M5  D→A  state=5, encrypted{ed25519 私钥, 签名}
M6  A→D  state=6
```

**pair-verify（日常，4 消息）** ✅

```text
M1  D→A  state=1, 手机 X25519 公钥
M2  A→D  state=2, 车机 X25519 公钥 + 车机 Ed25519 签名(公钥||对端公钥)
M3  D→A  state=3, 手机 Ed25519 签名
M4  A→D  state=4
→ 双方得到 shared secret（32B），用于派生控制/事件/流密钥
```

**响应头必须带** `Content-Type: application/pairing+tlv8`，否则 403 ✅（本项目踩过）。  
`X-Apple-HKP: 2` 缺一不可；pair-verify 还要 `X-Apple-PD: 1`。

**auth-setup**：老式 MFi SAP（MFiSAP v1）；**fp-setup**：FairPlay。两者都返回  
独立的 AES 密钥，最终与会话密钥组合成流密钥（见 4.5）。

**未配对路径**：`features` bit38 缺失 → 跳过 pair-setup/verify，  
`SETUP` 带 `et=1`（NONE）。⚠️ `et=NONE` **不是明文**，而是 **AES-128-CTR + 16 字节全零会话密钥**，  
且事件通道此时是明文。

### 4.5 密钥派生与密文帧

**ChaCha20-Poly1305（已配对）** —— HKDF-SHA512：

```text
密钥 = HKDF-SHA512(shared_secret,
                   salt = "<域>-Salt",                       // DataStream 时带 connId
                   info = "<域>-<方向>-Encryption-Key")
```

| 域    | salt                           | info（方向）                                                       |
| ---- | ------------------------------ | -------------------------------------------------------------- |
| 控制通道 | `Control-Salt`                 | `Control-Read-Encryption-Key` / `Control-Write-Encryption-Key` |
| 事件通道 | `Events-Salt`                  | `Events-Read-…` / `Events-Write-…`                             |
| 媒体流  | `DataStream-Salt<connId>`（十进制） | `DataStream-Input-…` / `DataStream-Output-…`                   |

**密文帧封装**（RTSP 与事件通道）：

```text
[2B AAD 长度（明文，= n-2）][n-2 字节密文][16B Poly1305 tag]
```

单帧密文总长上限 **1440** → 明文 ≤ **1422**。超过就被严格车机判协议违规  
（本项目踩过：发 0x4000 直接被 `PayloadTooBig` 拒）。

**AES-128-CTR（未配对 / et=NONE）**：

```text
key = SHA512("AirPlayStreamKey" + connId十进制 + sessionKey)[0..16]
iv  = SHA512("AirPlayStreamIV"  + connId十进制 + sessionKey)[0..16]
→ 无 tag、长度不变、密钥流**跨帧连续**（每帧新建实例会让第二帧起全乱码）
```

### 4.6 SETUP 的流描述

```text
SETUP 体（bplist）：{ streams: [ 流描述, ... ] }
```

**屏幕流（type=110；副屏 111）**：

| 键                    | 说明                             |
| -------------------- | ------------------------------ |
| `type`               | 110 = Screen，111 = AltScreen   |
| `streamConnectionID` | 会话内唯一，密钥派生用                    |
| `latencyMs`          | 建议 50~200                      |
| `uuid`               | 目标显示（对应 /info.displays[].uuid） |

**音频流（type 96/100/101/102）**：

| 键                          | 说明                                                                                              |
| -------------------------- | ----------------------------------------------------------------------------------------------- |
| `type`                     | 96 GeneralAudio / 100 MainAudio / 101 AltAudio / 102 MainHighAudio                              |
| `audioFormat`              | 位图，见 5.6                                                                                        |
| `audioType`                | `"media"` / `"alert"` / `"telephony"` / `"speechRecognition"` / `"default"` / `"compatibility"` |
| `audioLatencyMs`           | 建议 250（小于 250 不稳）                                                                               |
| `streamConnectionID`       | 独立 id                                                                                           |
| `controlPort` / `dataPort` | 手机侧端口（如已预分配）                                                                                    |
| `input`                    | 是否麦克风上行                                                                                         |
| `spf`                      | samples per frame（老格式 352）                                                                      |

**车机应答**：

```text
{ streams: [ { type, dataPort, controlPort?, streamConnectionID?, sampleTime?, timestamp?, timestampRawNs? } ] }
```

老格式还要 `sr`（采样率）、`ct`（压缩类型）。

### 4.7 `/command` 命令字典 ✅

JSON 体：`{ "type": "<命令名>", "params": { ... } }`。

| 命令                          | 方向    | 说明                                   |
| --------------------------- | ----- | ------------------------------------ |
| `hidSendReport`             | 双向    | HID 报文（触摸/按键）                        |
| `hidSetInputMode`           | 车机→手机 | 武装 HID：`{hidInputMode:0, uuid}`      |
| `requestSiri`               | 车机→手机 | `{siriAction: 0/1/2/3}` = 无/预热/按下/松开 |
| `forceKeyFrame`             | 车机→手机 | 请求 IDR                               |
| `changeModes`               | 车机→手机 | 请求切模式（`appStates` / `resources`）     |
| `modesChanged`              | 手机→车机 | 模式切换结果                               |
| `setNightMode`              | 车机→手机 | 昼夜                                   |
| `setLimitedUI`              | 车机→手机 | 行车限制 UI                              |
| `requestUI`                 | 车机→手机 | 请求某个 UI 上下文                          |
| `duckAudio` / `unduckAudio` | 车机→手机 | 压低/恢复音量（导航播报时）                       |
| `disableBluetooth`          | 车机→手机 | 让手机断开蓝牙音频                            |
| `iAPSendMessage`            | 双向    | **WiFi 上的第二条 iAP2 隧道**（见 6.4）        |
| `changeMapZoomLevel`        | 车机→手机 | 地图缩放                                 |

⚠️ **命令必须发在事件通道 socket 的 `POST /command`**（用 Events 密钥加密、独立 CSeq、  
发后不管）；发在 RTSP 控制连接上会收到 200，但**手机不执行**——这是本项目最坑的一个现象  
（触摸看起来「能滑一下」，实际全部丢失）。

### 4.8 心跳

| 心跳              | 载体                             | 间隔                  | 说明                               |
| --------------- | ------------------------------ | ------------------- | -------------------------------- |
| UDP keepAlive   | 发给车机 `/info` 里给的 keepAlivePort | 5s（车机看门狗 10s，必须留余量） | 4 字节 ping                        |
| `/feedback`     | RTSP 控制通道                      | 1s                  | 空体即可                             |
| 屏幕流 keepAlive 帧 | 视频数据口                          | 1s                  | opcode=2，**明文**，不消耗 ChaCha nonce |

三者**互相独立**：缺任一条，车机可能在 10s 左右拆会话（本项目实测 10.17~10.23s reset，  
两轮时序逐毫秒重合）。

---

## 5. 第四阶段：媒体面

### 5.1 屏幕流帧头（固定 128 字节，小端）✅

| 偏移 | 长度  | 字段                                          |
| -- | --- | ------------------------------------------- |
| 0  | 4   | `body_size`（u32 LE，帧体长度）                    |
| 4  | 1   | `opcode`                                    |
| 5  | 3   | `small_param[0..3]`（`small_param[1]` = 标志位） |
| 8  | 120 | `params[0..15]`，每个 8 字节（见附录 B）              |


帧 = 128 字节头 + 帧体 +（ChaCha 模式下的 16 字节 tag）。

### 5.2 opcode ✅

| 值 | 名 | 说明 |
|---|---|---|
| 0 | VideoFrame | 视频帧（唯一会被加密的 opcode） |
| 1 | VideoConfig | 编解码器配置（avcC / hvcC） |
| 2 | KeepAlive | 心跳，空体 |
| 3 | ForceKeyFrame | 请求关键帧 |
| 4 | Ignore | 忽略 |
| 5 | KeepAliveWithBody | 心跳带统计体 |

### 5.3 标志位（`small_param[1]`）✅

| 位 | 名 | 含义 |
|---|---|---|
| 0x01 | ShowHUD | |
| 0x02 | RespectTimestamps | 尊重 PTS（否则按到达时间播） |
| 0x04 | **Encrypted** | 后续 VideoFrame 是密文；**不写就按明文 NAL 解 → 解码失败黑屏** |
| 0x08 | UseFormatDescription | VideoConfig 体是 stsd atom（HEVC 用） |
| 0x20 | Suspended | 挂起 |
| 0x40 | ClearScreen | 清屏 |
| 0x80 | InterestingFrame | |

### 5.4 VideoConfig（opcode=1）

```text
params[1] = (width, height) 两个 f32 LE
small_param[1] |= 0x04 (Encrypted)
small_param[1] |= 0x02 (RespectTimestamps，可选)
body = avcC 配置（或 UseFormatDescription 时的 stsd atom）
```

⚠️ **缺 `params[1]` 或 `small_param[1]` 的真车会整屏黑**，而会话/触摸/心跳全部正常、
日志无任何错误——这是最难排查的一类（DiPlay/WinPlay 都不校验，只在真车上暴露）。

### 5.5 VideoFrame（opcode=0）

- `params[0]` = PTS（u64，映射到会话时钟）
- 帧体 = **AVCC 格式**（每 NAL 前置 `nal_size_len` 字节的长度），**不是 AnnexB**。
  AnnexB（`00 00 00 01`）必须先转 AVCC 再发。
- SPS/PPS 可以中途重发（编号变化时）；除 VideoFrame 外的 opcode **一律明文**。

### 5.6 音频

**格式位图**（`audioFormat`，u128 位图，SETUP 里传）：

| 位 | 格式 |
|---|---|
| 15 | PCM 48000 / 16bit / 立体声 |
| 14 | PCM 48000 / 16bit / 单声道 |
| 11 | PCM 44100 / 16bit / 立体声 |
| 18~21 | ALAC 44100/48000 × 16/24 × 立体声 |
| 22 | AAC-LC 44100 立体声 |
| 23 | AAC-LC 48000 立体声 |
| 24/25 | AAC-ELD 44100/48000 立体声 |
| 26/27 | AAC-ELD 16000/24000 单声道 |
| 28~30 | OPUS 16000/24000/48000 单声道 |
| 38 | PCM 48000 5.1.2 |
| 39/40 | AAC-LC 48000 5.1 / 5.1.2 |
| 44 | PCM 48000 5.1 |
| 45/46/47/48 | PCM 48000 Float32 单/立体声/5.1/5.1.2 |

**RTP 包（UDP）** ✅

```text
[12B RTP 头][ChaCha20-Poly1305 密文 + 16B tag][8B 小端 nonce]
RTP: v=2 | M=0 | PT=<stream type> | seq u16BE | ts u32BE（累计样本帧） | ssrc u32BE
AAD = 头字节 4..12（timestamp + ssrc 的 BE 表示）
nonce(12B) = 4 字节 0 + 尾 8 字节小端逐包递增
```

每包 512 样本帧（48kHz 立体声 16bit = 2048 B 负载）。

### 5.7 时间同步

车机在 `/info` 或 SETUP 应答里给 `timingPort`（UDP）。手机发 RTCP 风格包：

```text
pt = 0xD2  请求（含 t1）
pt = 0xD3  应答（回显 t1，填充 t2/t3），共 32 字节
```

车机要做的是：**把收到时刻 t2 与回发时刻 t3 填进去**，手机据此算
`offset = ((t2-t1) + (t3-t4)) / 2`，后续所有 PTS 都落在这个时钟上。
车机自己的单调钟即可，不必是真实 NTP 时间。

---

## 6. 第五阶段：反向输入

### 6.1 HID 通告

`/info.hidDevices[]` 每项：

| 键 | 说明 |
|---|---|
| `uuid` | 设备标识（**字符串**；本项目实测奥迪是 `"0"`/`"1"`，DiPlay 是整数） |
| `name` | 名称 |
| `hidDescriptor` | HID 报告描述符（原始字节） |
| `hidVendorID` / `hidProductID` | VID/PID |
| `hidCountryCode` | 国家码 |
| `displayUUID` | 关联显示 |

**武装步骤**（不武装收不到任何报文）：

```text
POST /command  {"type":"hidSetInputMode","params":{"hidInputMode":0,"uuid":"1"}}
```


对每个要用的 uuid 各发一次。

### 6.2 触摸报文

```text
POST /command  {"type":"hidSendReport","params":{"uuid":"1","report":"<hex>"}}
```

奥迪 `eso_touchScreen` 的 5 字节报文 ✅：

```text
byte0: bit7 = tip（1 按下 / 0 抬起），其余位保留
byte1-2: X u16 LE（绝对坐标，≤ 屏宽）
byte3-4: Y u16 LE（≤ 屏高）
```

多点触摸：每个触点一个 report（或按描述符定义的 report id 打包），
抬起时同一坐标 + tip=0 再发一次。

### 6.3 旋钮 / 按键 / Siri

| 输入 | 通路 |
|---|---|
| 媒体键（播放/暂停/上下曲） | Consumer HID usage 0xCF 组（HID report） |
| 旋钮/方向 | HID 键盘 usage 或 `changeMapZoomLevel` |
| Home / Back / Select | HID Consumer（0x221 Home 等） |
| 语音键 | **`requestSiri`**：`siriAction` 0=NotApplicable 1=Prewarm 2=ButtonDown 3=ButtonUp。⚠️ 很多车机的方向盘语音键**不走 HID**，只走这条路（本项目实测奥迪 3810/4260/4910/4580 全程 0 条 HID 0xCF） |
| 夜间模式 | `setNightMode` |

### 6.4 事件通道与 iAPSendMessage 隧道

- 事件通道是一条**独立的 TCP**（端口由 SETUP 应答或 `/info` 给出），
  上面跑双向 RTSP：手机发请求、车机也要对**每条来向请求立刻回 `RTSP/1.0 200 OK`**（加密）。
  不应答 → 手机塞 3 条就断通道（现象：触摸「能滑一下」）。

- `iAPSendMessage` 是**在 WiFi 上再跑一条 iAP2 链路**（MIB3 等车机要求）：

```text
手机→车机：POST /command  {"type":"iAPSendMessage","params":{"data":"<链路层字节 hex>"}}
车机→手机：POST /command（控制通道）  同上
```

  链路层内容与第 2 章**完全一致**（FF5A 帧），只是承载从 RFCOMM 换成 TCP。
  车机侧要做的是：把它接进同一个 iAP2 状态机（另开一个实例），
  并在 NORMAL 后重跑一遍 0x1D00 → 0x1D01 → MFi → 订阅。

  ⚠️ 有车机不启用这条隧道（实测某车 `iAPChannel` 协商结果为 0），
  所以它必须是**可选**能力，不能成为出画面的前置条件。

---

## 7. 失败模式与恢复

| 现象 | 根因 | 处理 |
|---|---|---|
| 每轮都卡在协商，日志里大量 SYN / 0 条 ACK | SYN\|ACK 复用了 SYN 的 seq | 回显必须消耗新 seq |
| 协商完成但 0x1D00 收不到 | 会话 ID 用错（发 10 号，对端表是 1 号） | 采纳对端表 |
| 0x1D01 收到但 MFi 断开 | 挑战长度不被接受 | 接受任意长度；或按车记忆档位 |
| 0x5703 一直不来 | 该车用新代 0x4301 | 并行发 0x4300 |
| 0x4301 迟到 2 分钟 | 车机 CarPlay 窗口晚开 | 当生命周期事件处理，别超时丢弃 |
| mDNS 看不到车机 | 手机开了热点共享 / 组播被绑网络挡住 | mDNS 失败时用网关兜底 |
| 配对 403 | 缺 `X-Apple-HKP`/`X-Apple-PD`，或设备已被别的手机配对 | 补头；引导用户删配对记录 |
| SETUP 400 | timingPort=0 / features 与 TXT 不一致 / 缺资源声明 | 三处一致 |
| SETUP 403 | `deviceID` 与车机记录不一致 | 用真实蓝牙 MAC |
| 会话 10s 被 reset | 缺三条心跳之一 | 第 4.8 节 |
| 有会话但黑屏 | VideoConfig 缺 `params[1]` / `Encrypted` 位 | 5.4 节 |
| 触摸无反应 | 命令发在控制通道 / 未 `hidSetInputMode` / 没回 200 | 6.4 节 |
| 车机 AirPlay 服务重启 | 发了它没声明可收的消息（越权） | 按 `0x1D01` 参数 7 **只裁** `0xA100`/`0xFFF0`，不关隧道 |

---

## 8. 车机端实施顺序（checklist）

1. **链路层**：FF5A 帧收发 + 校验 + 帧同步（含 marker 丢弃）→ 单测回环。
|  |  |
| --- | --- |
2. **协商**：LSP 编解码、marker、SYN|ACK（新 seq）、会话表采纳。
3. **CSM**：0x4040 分帧 + TLV + 字符串/组参数 + 分片重组。
4. **识别**：0x1D00 → 0x1D01（至少填 0/1/2/4/6/7）。
5. **MFi**：证书 + 挑战签名（真实硬件或提取的证书）。
6. **WiFi 下发**：0x5702/0x5703（旧）+ 0x4300/0x4301（新），两条都实现。
7. **热点 + mDNS**：`_airplay._tcp` 广播，TXT 与 `/info` 严格一致。
8. **RTSP 骨架**：OPTIONS / GET /info / pair-verify / SETUP / RECORD / TEARDOWN。
9. **加密**：pair-verify → 密钥派生 → 密文帧封装（顺手把 et=NONE 的 AES-CTR 路径也写上）。
10. **屏幕流**：VideoConfig → VideoFrame（AVCC）→ 首帧上屏。
11. **心跳三件套** + 断开检测。
12. **音频**：SETUP 主音频 → RTP 收包 → 解密 → 解码。
13. **输入**：`/info.hidDevices` → `hidSetInputMode` → 触摸 report。
14. **可选**：EA 隧道、iAPSendMessage、副屏、Siri、媒体元数据。

---

## 9. 实车坑表（都是本项目踩过的，按概率排序）

|  |  |
| --- | --- |
1. SYN|ACK 序号复用 → 协商永不收敛。
2. 会话 ID 用错 → 链路层照常 ACK，CSM 全被静默丢弃。
3. `X-Apple-HKP` 缺失 → pair-setup/pair-verify 403。
4. bplist 根对象不在偏移 8 → 老固件解析不出字典（`SETUP plist decode failed`）。
5. bplist 整数按无符号选宽度 → 35449 被解析成 -30087（端口落在 32768+ 必踩）。
6. 控制通道密文帧 >1440 字节 → `PayloadTooBig`。
7. `et=NONE` 当成明文 → 全黑（实际是 AES-128-CTR 零密钥）。
8. AES-CTR 每帧新建 cipher → 第二帧起乱码（密钥流必须跨帧连续）。
9. VideoConfig 缺 `params[1]` / `Encrypted` → 真车黑屏、日志无错。
10. 三条心跳缺一条 → 10s 内拆会话。
11. UDP keepAlive 与看门狗等长（10s vs 10s）→ 抖动必被拆。
12. 命令发在控制通道 → 200 但不执行。
13. 未 `hidSetInputMode` → 收不到任何 HID。
14. 事件通道来向请求不回 200 → 塞 3 条断通道（「能滑一下」）。
15. HID uuid 用整数而车机是字符串 → 武装失败。
16. 只发 IPv4 判据 → 老车机只给 `fe80::`，永远连不上。
17. `srcvers` / 服务名 / deviceID 乱改 → 车机当成另一台设备，连探测都不发。
18. 手动连过固定 SSID → iPhone 绕过 iAP2 供应链，CarPlay 栈不激活。
19. 越权发对端未声明的消息（如 0xA100/0xFFF0）→ 车机 AirPlay 服务重启。
20. `spotlessApply` / 批量格式化 → 与本协议无关，但会制造几百行无关 diff（项目规矩）。

---

## 10. 资料与源码索引

| 内容 | 位置 |
|---|---|
| iAP2 链路层（FF5A / marker / 校验） | `.tmp/catplay/core/catplay_iap2_link/src/{header,packet_coder}.rs` |
| iAP2 完整消息表（150 条 + 参数布局） | `.tmp/catplay/core/catplay_csm/src/msg/iap2.rs`（5550 行，内部规格档案生成） |
| 屏幕帧头 / opcode / 参数 | `.tmp/catplay/carplay/catplay_carplay/src/screen/{screen_frame,screen_frame_ext}.rs` |
| RTSP 端点与接收端实现 | 同上 `carplay_rx/sink/receiver.rs`（端点表在 285~310 行） |
| /info 与 displays 结构 | 同上 `msg/info.rs`、`common/features.rs` |
| SETUP 流描述 | 同上 `msg/{setup,streams,stream_desc}.rs` |
| 密钥派生 | 同上 `cipher/{chacha,aes}.rs` |
| 配对（SRP / X25519） | 同上 `pairing/{pairing_helper_rx,pairing_helper_tx}.rs` + `core/catplay_hap` |
| 命令字典 | 同上 `msg/commands/command_types.rs` |
| 本项目发送端协议笔记（实车证据） | `docs/PROTOCOL-NOTES.md`（626 行） |
| 本项目接收端实现参考 | 外部 `xcertplay` / `DiPlay`（Kotlin，GPL-3.0）、`PlayPort`（JVM） |
| 非官方 AirPlay 规范 | `https://openairplay.github.io/airplay-spec/`（RTSP 16 个端点、RTP、配对） |

---

## 附录 A：iAP2 完整消息表

来自 Apple 内部规格档案 `iap2messages-internal.i2mspecarchive`（catplay 生成）。
代码里的等价物是 `iap2/Iap2MessageCatalog.kt`（144 条，逐条同名），
判定「车机声明的这条消息我们认不认识」一律查它，**不要**用「本项目实现过没有」当判据。

**配对与识别**

```
0x00B0 StartOOBBTPairing              0x00B1 OOBBTPairingAccessoryInformation
0x00B2 OOBBTPairingLinkKeyInformation 0x00B3 OOBBTPairingCompletionInformation
0x00B4 StopOOBBTPairing
0x0B00 StartBluetoothPairing          0x0B01 BluetoothPairingAccessoryInformation
0x0B02 BluetoothPairingStatus         0x0B03 StopBluetoothPairing
0x1D00 StartIdentification            0x1D01 IdentificationInformation
0x1D02 IdentificationAccepted         0x1D03 IdentificationRejected
0x1D05 CancelIdentification            0x1D06 IdentificationInformationUpdate
0xAA00 RequestAuthenticationCertificate    0xAA01 AuthenticationCertificate
0xAA02 RequestAuthenticationChallengeResponse
0xAA03 AuthenticationResponse         0xAA04 AuthenticationFailed
0xAA05 AuthenticationSucceeded        0xAA06 AccessoryAuthenticationSerialNumber
```

**电话 / 通讯 / 列表**

```
0x4154 StartCallStateUpdates          0x4155 CallStateUpdate
0x4156 StopCallStateUpdates           0x4157 StartCommunicationsUpdates
0x4158 CommunicationsUpdate           0x4159 StopCommunicationsUpdates
0x415A InitiateCall                   0x415B AcceptCall
0x415C EndCall                        0x415D SwapCalls
0x415E MergeCalls                     0x415F HoldStatusUpdate
0x4160 MuteStatusUpdate               0x4161 SendDTMF
0x4170 StartListUpdates               0x4171 ListUpdate
0x4172 StopListUpdates
```

**无线 CarPlay / 设备状态**

```
0x4300 CarPlayAvailability            0x4301 CarPlayStartSession
0x4302 AvailableDigitalCarKeys        0x4303 MatchedDigitalCarKeys
0x4E01 BluetoothComponentInformation  0x4E03 StartBluetoothConnectionUpdates
0x4E04 BluetoothConnectionUpdate      0x4E05 StopBluetoothConnectionUpdates
0x4E09 DeviceInformationUpdate        0x4E0A DeviceLanguageUpdate
0x4E0B DeviceTimeUpdate               0x4E0C DeviceUUIDUpdate
0x4E0D WirelessCarPlayUpdate          0x4E0E DeviceTransportIdentifierNotification
0x5700 RequestWiFiInformation         0x5701 WiFiInformation
0x5702 RequestAccessoryWiFiConfigurationInformation
0x5703 AccessoryWiFiConfigurationInformation
```

**媒体库 / NowPlaying / 导航**

```
0x4C00 StartMediaLibraryInformation   0x4C01 MediaLibraryInformation
0x4C02 StopMediaLibraryInformation    0x4C03 StartMediaLibraryUpdates
0x4C04 MediaLibraryUpdate             0x4C05 StopMediaLibraryUpdates
0x4C06 PlayMediaLibraryCurrentSelection  0x4C07 PlayMediaLibraryItems
0x4C08 PlayMediaLibraryCollection     0x4C09 PlayMediaLibrarySpecial
0x5000 StartNowPlayingUpdates         0x5001 NowPlayingUpdate
0x5002 StopNowPlayingUpdates          0x5003 SetNowPlayingInformation
0x5200 StartRouteGuidanceUpdates      0x5201 RouteGuidanceUpdate
0x5202 RouteGuidanceManeuverInformation  0x5203 StopRouteGuidanceUpdates
0x5204 LaneGuidanceInformation
0x10DA StartDestinationInformation    0x10DB DestinationInformation
0x10DC StopDestinationInformation     0x10DD DestinationInformationStatus
0x0D00 StartRoadObjectDetectionUpdates 0x0D01 RoadObjectDetectionUpdate
0x0D02 StopRoadObjectDetectionUpdates
```

**HID / 辅助功能 / 电源 / 车况**

```
0x6800 StartHID                   0x6801 DeviceHIDReport
0x6802 AccessoryHIDReport         0x6803 StopHID
0x6806 StartNativeHID             0x6807 HIDComponentUpdate
0x5400 StartAssistiveTouch        0x5401 StopAssistiveTouch
0x5402 StartAssistiveTouchInformation  0x5403 AssistiveTouchInformation
0x5404 StopAssistiveTouchInformation
0x5601..0x5613 VoiceOver 系列（移动光标/朗读/游标更新）
0xAE00 StartPowerUpdates          0xAE01 PowerUpdate
0xAE02 StopPowerUpdates           0xAE03 PowerSourceUpdate
0xAE05 AccessoryPowerUpdate
0xA100 StartVehicleStatusUpdates  0xA101 VehicleStatusUpdate
0xA102 StopVehicleStatusUpdates
```

**EA / 应用发现 / BLE / USB / 定位**

```
0xEA00 StartExternalAccessoryProtocolSession
0xEA01 StopExternalAccessoryProtocolSession
0xEA02 RequestAppLaunch
0xEA03 StatusExternalAccessoryProtocolSession
0xAD00 StartAppDiscoveryUpdates   0xAD01 AppDiscoveryUpdate
0xAD02 StopAppDiscoveryUpdates    0xAD03 RequestAppDiscoveryAppIcons
0xAD04 AppDiscoveryAppIcon
0xB100 StartBluetoothLowEnergyUpdates   0xB101 DeviceBluetoothLowEnergyUpdate
0xB102 AccessoryBluetoothLowEnergyUpdate
0xB103..0xB106 BLE 配对信息/数据        0xB107 StopBluetoothLowEnergyUpdates
0x7E00 StartUSBHostMode           0x7E01 USBModeNotification
0x7E02 StopUSBHostMode
0xDA00 StartUSBDeviceModeAudio    0xDA01 USBDeviceModeAudioInformation
0xDA02 StopUSBDeviceModeAudio
0xFFF0 GPRMCDataStatusValuesNotification
0xFFFA StartLocationInformation    0xFFFB LocationInformation
0xFFFC StopLocationInformation
```

---

## 附录 B：屏幕帧 `params[]` 索引

| 索引 | 用于 | 含义 |
|---|---|---|
| 0 | VideoFrame | PTS（u64，映射到会话时钟） |
| 1 | VideoConfig | (width, height) 两个 f32 LE |
| 2 | — | 保留 |
| 3 | VideoConfig | (viewOriginX, viewOriginY) f32 ⚠️ |
| 4 | VideoConfig | (viewWidth, viewHeight) f32 ⚠️ |
| 5~13 | — | 保留（官方未公开） |
| 14 | KeepAliveWithBody | (0.0f, bodyLen f32) |

`params[0..1]` 之外的语义来自 catplay 的注释与实测，可靠性次于前两项。

---

## 附录 C：术语

| 缩写 | 全称 / 含义 |
|---|---|
| iAP2 | iPod Accessory Protocol 2，Apple 配件协议（蓝牙/WiFi 承载） |
| CSM | Control Session Message，0x4040 分帧 |
| LSP | Link Synchronization Payload，协商参数包 |
| EAK | Extended Acknowledgement，扩展确认 |
| MFi | Made for iPhone，Apple 配件认证体系 |
| SAP | MFi Secure Audio Protocol |
| EA | External Accessory，自定义协议隧道 |
| HID | Human Interface Device（触摸/按键报文） |
| AVCC | H.264 的「长度前缀」封装（对比 AnnexB 的 `00 00 00 01`） |
| PTS | Presentation Time Stamp |
| `pi` | AirPlay 接收端持久标识（变了 = 服务重启） |



---

## 附录 D：新旧协议的自动切换与一致性审计（本项目发送端实现）

规范描述的是「车机端该怎么做」；本项目跑的是**反方向**（手机端 / 发送端），因此本文里每一处
「新旧差异」在代码里都落成同一个问题：**这一轮按哪一代协议发**。这些判据必须只有一份，
否则就会出现「画像判老车机、连接逻辑另判一次」的分叉（历史事故：TXT 无 bit38 却被拿去
pair-setup，白等 120 秒窗口）。

### D.1 差异点 → 代码切换点 → 审计码

| 规范章节 | 新旧差异 | 代码切换点 | 审计码 |
|---|---|---|---|
| §2.10 | WiFi 凭据：旧 `0x5702→0x5703` / 新 `0x4300→0x4301` | `iap2/Iap2SenderSession`（`wifiGen` 分派 + 0x5702 短窗并行兜底） | `WIFI_GEN_MISMATCH`、`LEGACY_WIFI_SILENT` |
| §3.3 | AirPlay 时代：bit38 `HK_PAIRING_AND_ENCRYPT` 在 → 配对；不在 → `et=NONE` 明文 | `iap2/ReceiverProfile.PairingPolicy`（唯一判定）→ `SessionController` 配对分支 | `PAIRING_POLICY_MISMATCH` |
| §2.9 | MFi 挑战长度：老车机 20B / 测试台 32B，规范要求「接受任意长度」 | `iap2/MfiLengthMemory` 阶梯 + 按车机记忆 | `MFI_LENGTH_REJECTED` |
| §3.2 | TXT 的 `deviceid` / `features` 必须与 `/info` 一致 | `airplay/AirPlayClient.logInfoSummary` | `FEATURES_TXT_VS_INFO`、`DEVICE_ID_MISMATCH` |
| §2.8 / §7 | 不得发对端未声明可收的消息（越权 → 车机重启）；一般越权只记 WARN 照发，两条 MIB3 专用消息按声明裁掉 | `iap2/AccessoryDeclaration.noteSend`（蓝牙 `send`/`sendFireAndForget` 与隧道 `sendCsmLocked`/`sendRawCsmLocked` 四个出口共用） | `UNSOLICITED_MESSAGE` |
| §3.4 | 端点只能来自 `0x4301` / mDNS（新代没有网关兜底） | `AirPlayClient.connect`（无候选地址分支） | `WIFI_NEW_WITHOUT_AP` |
| §3.3 | 同一台车换固件 → 代际翻转 | `protocol/ProtocolGeneration`（代际快照 + 作废旧 MFi 档） | `ERA_SWITCHED` |

**判据铁律**（与 §9 一致）：**按能力与实测行为分派，不按品牌/版本号硬编码**。
`srcvers` / `features` 只作先验提示；清单只能证明「有」，不能证明「没有」——奥迪 3315 的可收
清单不含 `0x5702`，但它完整支持旧协议。所以「代际不确定」时一律**两条请求并行发、谁先回用谁**。

### D.2 审计输出格式

每条问题**当场**进日志（logcat + 文件 + 界面三路同源），格式固定、可 grep：

```text
PROTO! WARN [WIFI_GEN_MISMATCH] 新旧 WiFi 凭据代际判错 —— 画像判 wifiGen=NEW（等 0x4301），
车机却回了 0x5703（依据 SPEC §2.10 / §7；证据 ssid=AUDI_MMI_5G 信道=149）
```

会话退役时补一行**汇总**（条目数变化才打，避免每轮配对起步都刷）：

```text
PROTO 审计（会话退役）：共 2 项（1 警告 / 1 提示）→ WIFI_GEN_MISMATCH ERA_SWITCHED
```

排查入口：`grep "PROTO" CarPToCarL-log-*.txt`。一行干净时是
`PROTO 一致性审计通过：未发现与本规范不一致的现象`。

### D.3 审计码表（`protocol/ProtocolAudit.kt`）

| 码 | 严重度 | 触发条件 | 依据 |
|---|---|---|---|
| `WIFI_GEN_MISMATCH` | 警告 | 画像判 NEW（跳过旧协议）却收到 `0x5703` | §2.10 |
| `LEGACY_WIFI_SILENT` | 警告 | 发了 `0x5702`，窗口内无 `0x5703` | §2.10 / §7 |
| `WIFI_NEW_WITHOUT_AP` | 警告 | 既无 `0x4301` 也无 mDNS，取不到 AirPlay 地址 | §3.2 |
| `PAIRING_POLICY_MISMATCH` | 警告 | 画像说「该配对」，接收端却无 `/pair-setup` | §3.3 |
| `FEATURES_TXT_VS_INFO` | 错误 | TXT `features` ≠ `/info` `features`（SETUP 400 常见根因） | §3.2 / §8 步 7 |
| `DEVICE_ID_MISMATCH` | 错误 | TXT `deviceid` ≠ `/info` `deviceID` | §3.2 / §7 |
| `UNSOLICITED_MESSAGE` | 警告 | 发了车机可收清单里没有的消息（仍照发，仅留证） | §2.8 / §7 |
| `MFI_LENGTH_REJECTED` | 提示 | 挑战长度被 `0xAA04` 明确拒绝，本轮换档 | §2.9 |
| `ERA_SWITCHED` | 提示 | 同一台车机的时代/凭据代际翻转（换固件） | §3.3 |

同一 `(码, 细节)` 一轮只记一次（链路会反复触发同一根因）；上限 64 条，完整过程仍看原始日志。

### D.4 代际快照与旧档作废

`protocol/ProtocolGeneration` 按车机身份（name+model+manufacturer）记一份快照
|  |  |  |  |
| --- | --- | --- | --- |
`era | wifiGen | srcvers | features`，两侧信号（0x1D01 / mDNS）谁先到都能合并：

- **UNKNOWN 不参与比较**：信号补齐不是换代（0x1D01 先到时必然 era=UNKNOWN）；
- 只有「已知 → 已知且不同」才算变更，era 与 wifiGen 各自独立判；
- 变更时 → 打一行 `协议代际变更：…` + 记 `ERA_SWITCHED` + **作废该车机的 MFi 档位记忆**
  （`MfiLengthMemory.forget`）。理由：挑战长度偏好是上一代协议下的最优解，换固件后沿用
  会把新一轮锁死在老参数上，表现为「升级固件后这台车突然连不上」。

### D.5 报文名册自查（「车机支持报文」到底对不对得上）

`0x1D01` 参数 6/7 是车机自报的「我会发 / 我可收」清单，`Iap2CapabilityReport` 把它按
子系统分组打印，并把**规范里查不到的号**单列成「陌生 ID」。这条判据完全依赖消息目录的
覆盖率，所以 2026-11-07 做了一次全面自查，结论与修正如下。

| 自查项 | 修正前 | 修正后 |
|---|---|---|
| 目录覆盖 | `Iap2MessageIds.nameOf` 只认「本项目用过的」约 40 条，其余全 `0xNNNN` | 新增 `iap2/Iap2MessageCatalog.kt`：**144 条规范全表**（catplay 注册表导出），`nameOf` 以它兜底 |
| 「陌生 ID」判据 | `!nameOf(id).startsWith("0x")`，等于「本项目没实现过」 | `Iap2MessageCatalog.contains(id)`，等于「规范里有没有这个号」 |
| 后果 | `0x4E09/0x4E0A/0x4E0B/0x4E0C`（设备名/语言/时间/UUID，**每天发**）、`0xAA00~0xAA05`（MFi 六条，**每次认证都发**）被列进「陌生 ID」，现场误以为车机冒出新东西 | 这些号正常显示规范名；只有真陌生的号（如 `0x4D00`）才进清单 |
| 泛化名掩盖精确名 | `in 0xAE00..0xAE03 -> "PowerUpdates(0xAE02)"`，`0x415B/0x415C -> "CallControl"` | 删掉泛化区间，交目录给精确名（`0xAE02` = `StopPowerUpdates`，`0x415C` = `EndCall`） |
| 分组区间 | `0x4172`（StopListUpdates）、`0x5700/0x5701`（WiFi 信息）、`0x4302/0x4303`（数字车钥匙）、`0x4E01~0x4E05`、`0x4Cxx`（媒体库）、`0x10DA~0x10DD`、`0x0Dxx`、`0xFFF0~0xFFFC`、`0xB1xx`、`0x54xx/0x56xx` 全落到「其他/未知」 | 补齐区间；新增 `ACCESSIBILITY`（辅助功能）组 |
| 越权取证覆盖 | `recordUndeclaredReceive` 手工挂在 4 种消息上，实际发 20+ 种 | 下沉到 `send()` / `sendFireAndForget()` **唯一出口**，全量覆盖（清单未到达时返回 null，不误报）；2026-11-08 起再收口到跨链路的 `AccessoryDeclaration.noteSend`，隧道侧两个出口也纳入，并按声明裁掉 `0xA100`/`0xFFF0` |
| 推断号伪装 | `0xEA04` 在 `nameOf` 里叫 `EASessionData`，日志看不出是猜的 | 改名 `0xEA04(推断未确认)`；规范 EA 段只到 `0xEA03`，目录里**禁止**收录它（单测锁定） |
| 订阅方向表述 | §2.8 写「凡**手机**订阅的消息（含 0x5000/0x4157/0x4154/0x4170）」 | 前四条是**车机订阅手机**、后两条才是手机订阅车机，已按方向拆开 |

单测把这几条钉死（`Iap2MessageCatalogTest`）：目录条数必须 144；`0x4E09/0xAA00` 等
必须叫得出名字；`Iap2MessageIds` 里**每一个** `const Int` 都必须在目录中存在
（唯一例外 `EA_SESSION_DATA`，且它自带「推断未确认」标注）。

以后新增消息号时的纪律：**先确认它在这 144 条里**，否则不许给它起规范样式的名字——
把猜测写成规范名，比报「陌生 ID」危险得多。

---

## 附录 E：全链路协议一致性自查（2026-11-07）

自查问题两条：**① 代码与本文（协议）有没有出入；② 连不上时日志能不能指出卡在哪一步。**
对照源排序：catplay 的 Apple 规格档案（`core/catplay_csm/src/msg/iap2.rs`）＞ 实车字节级
日志 ＞ Apple 公开头文件。**凡没有实车佐证的，一律记为「未验证」，不写成结论。**

### E.1 逐阶段结论

| 阶段 | 对照项 | 结论 |
|---|---|---|
| §2.2 链路帧 | `FF5A` 帧头 9 字节（含 1 字节头校验和）、长度字段含头含校验、校验和 = 两补数累加为 0 | ✅ 与规范一致。用真机日志里 `0x4300` 的原始帧验算：47 字节 = 长度字段，全帧字节和 `& 0xFF == 0` |
| §2.3 握手 | marker `FF 55 02 00 EE 10`；SYN 载荷 version/maxOutgoing/maxLength/RTO/ackTimeout/maxRetrans/maxAck + 3 字节/会话 | ✅ 字段顺序逐条一致（`Iap2LinkEngine.SynchronizationPayload`）；RTO 采纳对端声明值 |
| §2.5 可靠传输 | seq 初值 99、EAK `0x20`、窗口按本方声明值计 | ✅ 一致；重传次数取双方较小值（本地 10，对端 30 → 10） |
| §2.6 CSM | `0x4040` + u16 总长（含 6 字节头）+ u16 消息 ID + 参数体；参数 = u16 长（含 4 字节头）+ u16 ID + 值；**大端** | ✅ 与 catplay `decoder/packets/frame.rs`、`wire/writer.rs` 完全一致（两者都是 `to_be_bytes`）。实测 0x5703 解析结果也能反向印证 |
| §2.7 0x1D01 | 参数 6=车机会发、7=车机可收；身份串 0/1/2 | ✅ 与规格档案一致。⚠️ 本文原先把 EA 协议槽写成「10~12」——**规格里是参数 10 重复出现**，已改（代码兼容 10..12 两种写法） |
| §2.9 MFi | 0xAA00→01→02→03→05；挑战 20B/32B 两档；0xAA04=失败 | ✅ 一致。0xAA06 是**车机**发的（可收清单里常见），我们不需要发 |
| §2.10 0x5703 | 1=SSID、2=密码、3=安全类型、4=信道，**无 IP 字段** | ❌ **本文原来整张表是错的**（写成了 0/1/2/3=IP/4=安全类型，那其实是 0x4301 无线组的布局），已改。代码 `parseWifiConfiguration` 取 1/2/3/4 是对的 |
| §2.10 0x4301 无线组 | 0=SSID、1=密码、2=信道、3=IP（可多个）、4=安全类型（3/4=WPA3） | ✅ 代码 `parseWirelessAp` 按这套布局取值，并保留了对 0x5703 式编号的形态判定 |
| §2.8 设备状态问候 | 0x4E09(名)/0x4E0A(语言)/0x4E0B(u64 epoch + i16 时区 + i8 夏令时)/0x4E0C(UUID 串)/0x4E0D(status 0/1)/0x4E0E(蓝牙传输标识串) | ✅ 六条报文体与规格档案逐条对上；0x4E0D 发 `status=1`（Available）正确 |
| §3.2 TXT / §3.3 features | `_airplay._tcp` 的 `features` 位、`deviceid` 与 `/info` 一致 | ✅ 已按位表判定（bit38 = HomeKit 配对）；一致性由 `ProtocolAudit` 的两个码取证 |
| §4.1 RTSP 端点 | 客户端实际只用到 GET/POST `/info`、`/pair-setup`、`/pair-verify`、SETUP、RECORD、`/feedback`、`/command`（`/command` 同时是事件通道的命令入口） | ✅；`/fp-setup`、`/auth-setup`、`/audio-mode`、`/log`、`/setPEERS` **本项目不实现**，见 E.3 |
| §4.2 头部 | 配对请求必须 `X-Apple-HKP: 2`（pair-verify 另加 `X-Apple-PD: 1`）、`application/pairing+tlv8`；`/info` 用 `application/x-apple-binary-plist` + 空 bplist 体 | ✅ 一致（这两条是 2026-10-11 奥迪 403 的修复点，注释里留了证据） |
| §4.5 密钥派生 | HKDF-SHA512 + 域盐/方向 info；AES-CTR 在 `et=NONE` 时用全零会话密钥、密钥流跨帧连续 | ✅ 一致（`AirPlayCrypto`） |
| §5.1/5.4 屏幕帧 | 128 字节头；`params[1]` = (宽,高) 两个 f32 LE；`small_param[1] |= 0x04` 只在 VideoConfig 上置 | ✅ 一致；Encrypted 位**只标在 config 帧**，与 catplay `ScreenFrame::config` 相同 |
| §5.5 VideoFrame | `params[0]` = PTS（NTP 32.32）；帧体必须 AVCC | ✅ 一致（`buildHeader` 写 NTP、`splitAnnexB` 转 AVCC） |
| §5.6 音频 | 12B RTP 头 + ChaCha 密文 + 16B tag + 8B 小端 nonce；AAD = 头 4..12；PT=流类型；SSRC=0 | ✅ 一致 |
| §6.1/6.2 HID | `/info.hidDevices` → `hidSetInputMode` → 5 字节触摸报文走事件通道 `/command` | ✅ 一致（实测奥迪触摸可用） |

### E.2 本次自查改掉的三处

1. **失败轮次没有收尾结论**（真机日志证据：`CarPToCarL-log-20261107-103708` 三轮连接、三条
   「本轮开始」、**零条小结**）。`ConnectBudget.end(cause, keywords)` 现在会在三条会半路
   退出的路径上打一行
   `STAGE: ✗ 本轮未连上，卡在「<阶段>」：<原因>｜关键词：<grep 词>`，幂等、且随后仍打阶段小结。
2. **`expect()` 超时只说十六进制号**：`等待 0x1d01 超时` 读起来像链路问题，现在写成
   `等待 0x1d01（IdentificationInformation）超时`，一眼看出是哪条消息没回。
3. **两处文档错误**（§2.7 的 EA 槽、§2.10 的整张 0x5703 参数表）已按规格档案改正。

### E.3 已知偏离（有意为之，不是 bug）

| 项 | 偏离内容 | 为什么保留 |
|---|---|---|
| 0x4300「双标识体」 | 参数 0（有线组）里塞 `00008110 + MAC + 0000` 伪 USB 传输标识 | 别克 E2LB / 凯迪拉克 7731 只带无线组时**一条主动消息都不发**；带上有线组才开始回 0x6800/0x5000。属于对车机的迁就，规格里参数 0 的 1 号字段是 `usb_transport_identifier` |
| 设备语言发 `zh-CN` | 规格要求 ISO 639-1/639-2（`zh`） | 实车未见拒收；改与不改都不影响链路，等有车机抱怨语言再动 |
| 不实现 `/fp-setup`、`/auth-setup` | 只走 `et=NONE` 明文会话 + 全零密钥 AES-CTR | 目标准车机（奥迪 4xxx/MIB3、通用系）都接受；FairPlay 受专利限制，没有公开实现 |
| 不发 TEARDOWN | 拆会话靠关闭 TCP + 车机超时 | 实测不影响下一次连接；主动 TEARDOWN 反而有车机回 453 触发额外状态机分支 |

### E.4 日志诊断索引（连不上时按这张表查）

一行 greppable，从下往上找**最后一条**命中的即可定位。

| # | 阶段 | 关键日志（可直接 grep） | 含义 / 下一步 |
|---|---|---|---|
| 1 | 蓝牙 | `BT-C: 连接 … 失败（` | RFCOMM 没建起来。车机不在配对窗口 / 已被别的手机占用；等 16s 自动重试 |
| 2 | iAP2 链路 | `iap2 link ready peerMaxControl=` | 协商成功（有这行就说明不是蓝牙问题） |
| 3 | 识别 | `iap2 rx=0x1d01 车机身份` / `等待 0x1d01（IdentificationInformation）超时` | 后者 = 车机收到 0x1D00 却没回识别信息，问题在车机侧 |
| 4 | 能力清单 | `车机发送清单(` `可收清单(` `陌生 ID(` | 看清车机声明了什么。陌生 ID 若不再是 0x4E09/0xAA00 这类，才值得反推报文体 |
| 5 | MFi | `tx=0xaa02 challenge=` `rx=0xaa03` `tx=0xaa05` | 挑战无应答会换下一档长度；全静默则自报 0xAA05 继续（不阻塞） |
| 6 | WiFi 凭据 | `iap2 rx=0x5703 ssid=` / `iap2 0x4300 报文体形态：` | 前者=旧代成功；后者要接着看有没有 `rx=0x4301` |
| 7 | **0x4300 静默** | `⚠ 0x4300（…）发出后 8s 内车机零反馈` | 车机不认这个报文体（连链路 ACK 都没有），下一轮自动换形态 |
| 8 | 等端点 | `进入保活等待` / `仍在等 0x4301（已等 Ns` | 车机迟迟不下发 0x4301；把手机先手动连上车机热点常能救回 |
| 9 | 本轮结论 | `STAGE: ✗ 本轮未连上，卡在「…」` | **先看这一行**，它直接给出卡点、原因和关键词 |
| 10 | WiFi 入网 | `WiFi 已就位` / `等待车机热点超时` | 后者=密码错或信号弱；日志里有 SSID 与失败原因 |
| 11 | 端点解析 | `端点诊断：地址=` `mDNS 解析到车机地址` | 只有 IPv6 链路本地是正常的；「无地址」说明 0x4301/mDNS 都没给 |
| 12 | TCP | `AIRPLAY: TCP 已连通` / `AIRPLAY: 连接失败 - ` | 后者带每个候选地址的失败原因；常见是手机没连车机热点 |
| 13 | `/info` | `AIRPLAY: /info [200] body=` | 非 200 或 body 很小 = 车机 AirPlay 服务没起 |
| 14 | 配对 | `配对第 N 次尝试失败` `车机确定性拒绝配对` | 后者会 15 分钟内跳过配对，改走明文会话 |
| 15 | SETUP | `AIRPLAY: SETUP 应答 [200]` / `会话建立失败` | 失败时日志会打 `/info` 的 displays 与 deviceID，按提示重启车机 CarPlay |
| 16 | 首帧 | `STAGE: ✓ 首帧已到达` / `VIDEO: 帧 #1` | 没有这行但 SETUP 成功 = 编码器/画布问题，看 `VIDEO:` 与 `CARLIFE:` |
| 17 | 事件通道 | `EVENT: 通道关闭（EOF）` | 车机主动断开命令通道，通常紧跟 HID/模式命令被拒 |

> 经验：**先看第 9 行（本轮结论），再看它给出的关键词对应的行**，不要从日志开头顺着读。
> 那三条 `仍在等 0x4301` 只是心跳，不是原因。

---

_本文与 `docs/PROTOCOL-NOTES.md`（发送端视角）互补；协议事实以实车字节级日志为准，
公开源码与 Apple 规格档案仅作为交叉印证。_
