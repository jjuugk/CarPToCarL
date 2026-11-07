# 《CarPlay 车机开发手册》对照核查（2026-11-07）

来源：`CarPlay车机开发手册.md` / `.html`（v1.0，1847 行，自带 [A]~[X] 五级可信度标签）。
核查对象：本项目**发送端（iPhone 角色）主链路**——蓝牙 iAP2 → Wi-Fi 凭证 → AirPlay 会话。

结论先给：**采纳 1 条（链路初始序号随机），其余 9 处明确不采纳**。
不采纳的每一项都有本项目的实车证据，且手册在这些点上自标 [C]（需查 NDA 原文）或
与公开实现/本项目抓包矛盾。逐项如下。

---

## 1. 采纳：链路初始序号改为随机（手册 §4.3 [B]）

| 项 | 内容 |
|---|---|
| 手册要求 | 每条链路建立时初始序号**随机**取 0–255，SYN 帧携带 |
| 改动前 | `Iap2LinkEngine.INITIAL_SEQUENCE = 99`（常量，所有链路、所有重连都一样） |
| 改动后 | `sentSequence = SecureRandom().nextInt(0x100)`，每条链路新建时取一次 |
| 影响面 | 只影响本端序号账法；对端用 `ack` 回显「期望的下一个」，与初值无关 |

**为什么值得改**：2026-11-07 myCadillac（`10:E8:A7:B5:1B:A7`）13 轮重连，日志里
486 次发送全是 `ctl=0x80 seq=99`，**字节级完全一致**；车机 RFCOMM 接受连接但一个字节
都不回。做了会话缓存/重放判定的车机遇到重复序号会直接按重传帧丢弃。真机与 catplay
发送端（`take_own_seq()`）都是随机的——我们此前是唯一固定值的实现。

单测同步调整（不能再用硬编码 99/100 断言）：
`synAckUsesFreshSequenceNumber` / `everySynGetsASynAckReply` / `retransmittedSynKeepsTheSameEcho`
改为「以本方 SYN 序号为基准 +1（模 256）」；`ackSequenceNeverDuplicatesTheSynAck` 的
`sorted()` 断言在回绕时会假失败，改成逐项 +1 校验。

---

## 2. 不采纳：负载校验和用 CRC-16（手册 §4.4 [C]）

- 手册：头校验 1 字节补码，**负载校验 2 字节 CRC-16/CCITT-FALSE**；并自己承认
  「iAP2 Addendum 正文举例暗示 1 字节，开源实现都是 2 字节」，标 [C]「量产必须查官方原文」。
- 本项目：头、负载各 1 字节，均为「字节和补码」（`-sum and 0xFF`），见
  `Iap2LinkEngine.writePacket`。
- 结论：**不改**。奥迪 MIB3（3873/3874/4580/4910）、宝马 iD6/iD8.5、DiPlay 测试台
  全部按 1 字节互通，累计几百轮实车会话。改成 2 字节等于同时改所有已跑通车型的线格式，
  收益为零、风险极大。若将来遇到「帧被静默丢弃且头校验正常」的新车机，再按手册建议
  做双格式自检（先 1 字节，失败换 2 字节）。

## 3. 不采纳：SYN 由车机（accessory）发起（手册 §4.5、§11.2 第 4 步）

- 手册：`T+600ms 车机→iPhone iAP2 SYN`，SYN 发起方是车机；同时它自己也写了
  「存在对端先发 SYN 的实现（某些车机在 Device 模式下不主动发 SYN），抓到不要惊讶」。
- 本项目：**无线模式下手机先发 SYN**（`Iap2LinkEngine.start()` 先 marker 后 SYN），
  与 xcertplay `openWireless(initiateNegotiation = true)` 一致；同时已实现
  `sendSynchronizationEcho()`，对端先发 SYN 时也能回 `0xC0`（奥迪 MIB3 就是这一支）。
- 结论：**不改**（两个方向都已支持，不需要二选一）。手册这条只描述车机侧视角，
  若照它改成「等车机先发」，DiPlay/宝马这类等手机发起的接收端会直接超时。

## 4. 不采纳：认证必须在识别之前（手册 §5.2、§5.3）

- 手册：「认证必须在识别之前，顺序颠倒会被 `0x1D03` 拒绝」。
- 本项目：`0x1D00 → 0x1D01 → 0x1D02` **在** MFi（`0xAA00…0xAA05`）之前，
  见 `Iap2SenderSession.run()`；这是 xcertplay/DiPlay 的顺序，奥迪/宝马/别克实车均通过。
  全项目至今**没有收到过一条 `0x1D03`**。
- 结论：**不改**。反而有反例：DiPlay 在认证前发 `0xEA00` 会让状态机停摆（本项目
  已因此把 EA 会话推迟到 MFi 之后），说明这些接收端的状态机顺序与手册描述不同。

## 5. 不采纳：`0x5702` 由车机发出、iPhone 回 SSID/PSK（手册 §3.5、§11.2 第 8–9 步）

- 手册：`0x5702` 方向写「车机 → iPhone（请求）；iPhone → 车机（响应）」，响应里由
  **iPhone** 提供 SSID / Password / BSSID / 信道。
- 本项目：`0x5702` **手机 → 配件（请求车机的热点配置）**，`0x5703` 配件 → 手机携带
  SSID/密码/信道。见 `Iap2MessageIds` 与 `parseWirelessAp`。
- 结论：**不改**。无线 CarPlay 的热点是**车机**开的，密码不可能由 iPhone 提供；手册这条
  把「配件（车机）」和「iPhone」的角色写反了。按它改会立刻打断奥迪/宝马的
  `0x5702 → 0x5703` 流程（本项目实车毫秒级应答）。

## 6. 不采纳：EIR 里 iPhone 侧 CarPlay UUID 的字节序（手册 §3.2）

- 手册两列：线上字节 `2D 8D 24 66 E1 4D 45 1C 88 BC 73 01 AB EA 29 1A`，
  反转后字符串 `1A29EAAB-0173-BC88-1C45-4DE166248D2D`。
- 实际：蓝牙 128-bit UUID 在 EIR 中是**小端**，其标准字符串形式就是
  `2d8d2466-e14d-451c-88bc-7301abea291a`（本项目 `BtIdentityProbe.CARPLAY_WIRELESS_UUID`，
  与 catplay、凯迪拉克车机 APK 的 `PHONE_EIR_UUID` 一致）。手册把两列写反了。
- 结论：**不改**（且 Android 应用层写不了 EIR，只能注册占位 SDP，见
  `BtCompatPrefs` / `BtIdentityProbe`）。这条只影响读手册的人，不影响代码。

## 7. 不采纳：会话 ID 用 `0x00` 表示控制会话（手册 §3.4）

- 手册：Control Session = `0x00`，EA 从 `0x01` 起动态分配。
- 本项目：线上 Session Identifier 用 **10（控制）/ 11（EA）/ 12（文件传输）**，
  由 SYN 协商的会话描述符分配；`0x00` 只出现在 SYN/ACK 这类链路控制帧里。
- 结论：**不改**，两者说的是不同层次——手册的 `0x00` 是「会话类型编号（kind）」，
  线上字段是「分配到的会话 ID」。本项目 `SessionDescriptor(SESSION_CONTROL, 0, 2)`
  里 `kind=0` 正是手册说的那个 0。

## 8. 不采纳：SYN 连续 3 次无响应即判链路不可用（手册 §4.5）

- 手册：3 次 × 1 s 无响应 → 判定链路不可用，回退重来。
- 本项目：SYN 每 **500 ms** 重发，直到上层 60 s 预算用尽；车机侧 `0x4301` 有实车
  2 分 35 秒才到的情况（Audi MMI 0000），链路必须一直挂着。
- 结论：**不改**。手册的 3×1s 是「车机侧发现线插坏了」的判据，发送端提前放弃只会
  把「车机窗口开得慢」误判成「链路坏了」。

## 9. 已符合、无需改动（列出以便后续不再重复核对）

| 手册条目 | 本项目实现 |
|---|---|
| §3.4 RFCOMM UUID `…caf e / …caff` | `Iap2BluetoothServer.IPHONE_IAP2_UUID` / `Iap2BluetoothClient` 一致 |
| §4.2 控制位 SYN/ACK/EAK/RST 掩码与互斥 | `Iap2LinkEngine` 常量一致（0x80/0x40/0x20/0x10） |
| §4.2 纯 ACK 不递增序号 | `sendAcknowledgement()` 复用 `sentSequence` |
| §4.3 确认号 = 期望的下一个序号 | `lastReceivedInOrder` 语义一致 |
| §6.5 `/setup` 的 `timingPort` 是 iPhone 监听端口 | `buildSessionSetupBody` 写本机监听端口 |
| §6.7 `/feedback` 1 s + UDP keepAlive 10 s | `/feedback` 1 s；UDP keepAlive **5 s**（比手册更勤，奥迪实测需要） |
| §6.7 `RECORD` 响应须带 `Audio-Latency: 0` | 该项是**车机侧**响应头，发送端不产生 |
| §6.8 `hidSetInputMode` 必须走事件通道 `POST /command` | `AirPlayClient` 已如此实现（主 RTSP 连接上无效） |
| §6.3 `GET /info` 的 `User-Agent: AirPlay/<srcvers>` | `"User-Agent: AirPlay/$SENDER_SOURCE_VERSION"` |
| §6.6 音频 SETUP 的 `ct`/`spf`/`latency` 字段 | 已有（含 48K AAC 优先，1.2.25 起） |

## 10. 待实车验证

初始序号随机这一条，唯一要看的日志是**同一台车连续两轮重连的 `seq=` 不再相同**：

```
LINK tx: ctl=0x80 seq=173 ack=0 ...    ← 第一轮
LINK tx: ctl=0x80 seq=58  ack=0 ...    ← 重连后（此前永远是 99）
```

若某台车出现「随机后再也连不上、改回 99 就能连」，那就是它按固定序号做了白名单/缓存，
届时给 `Iap2LinkEngine` 加一个按车机身份记忆的「固定序号」档（与
`AvailabilityBodyMemory` 同思路），而不是全局回退。
