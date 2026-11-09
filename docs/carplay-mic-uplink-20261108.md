# 车机麦克风 → CarLife 语音：可行性与第一步（2026-11-08）

目标：**车机麦克风经 CarPlay 上行到手机，直接喂给手机上跑的 CarLife+ 引擎做语音识别**，
这样手机放在包里/扶手箱里也能用方向盘语音键说话。

现状（本轮之前）：方向盘语音键能触发 CarLife 语音（`0x68008` `VR_START/VR_STOP`），
但 CarLife+ 收到 `0x21` 后**用手机自己的麦克风**录音——11-04 实测结论，见
`docs/carlife-voice-gps-20261104.md` §一。

整件事要过两道关，缺一不可：

| 关卡 | 谁 → 谁 | 现状 |
| --- | --- | --- |
| ① AirPlay 麦克风上行 | 车机 → 手机 | 车机**声明**了输入位图，但我们从未实建输入流 → **未知** |
| ② CarLife 接受注入的语音 | 我们 → 引擎（9242） | 端口已连上但从未发过数据 → **未知** |

---

## 一、① 车机侧：声明 ≠ 会推

`/info` 的 `audioFormats` 里，四路档位的 `audioInputFormats` 都有值：

| audioType | 输入位图 | 说明 |
| --- | --- | --- |
| `compatibility` | `0x54` | bit6 / bit4 / bit2 |
| `default` | `0x30000050` | 含 PCM48k 位（bit30 / bit4 / bit6） |
| `telephony` | `0x30000050` | 同上 |
| `speechRecognition` | `0x20000040` | bit6 + bit29（Siri 档） |

**位图只说明它愿意/有能力**，不代表收到 `input=true` 的 SETUP 就真的开始往我们端口推音频。
旧代码 `microphoneCandidates()` 只把这些位图打进日志（`AIRPLAY: 麦克风上行候选=…`），
从没真开过流——`docs/audio-call-issues.md` §四记着这条「有意没做」。

## 二、为什么要单独做探针

输入流的**端口方向与下行相反**：

- 下行（媒体/TTS）：SETUP 后车机回 `dataPort`/`controlPort`，我们主动连它；
- 上行（麦克风）：我们要在 SETUP 里声明**自己的** `dataPort` / `controlPort`，车机照这个端口**往回发**。

方向搞错 / 参数写错都不会报错，只会「一条数据也收不到」。而 `audioType` 与 `audioFormat`
的取值组合又只能试（车机的位图是「或」关系，看不出哪一档真在用）。

所以第一步只做三件事：**发 SETUP 开流 → 数数据报 → 数完就关**。不解密、不解码、不认音。

代码：`AirPlayClient.probeMicrophoneInput()`（本文档同批提交），由
`SessionController.runMicProbe()` 在音频流就绪 12 秒后调用一次，最多试 4 组参数：

```
speechRecognition / 0x40   / 24kHz
telephony         / 0x8000 / 48kHz
default           / 0x8000 / 48kHz
compatibility     / 0x40   / 24kHz
```

第一组命中即停（车机资源仲裁很贵，不必反复抢）。

## 三、怎么读日志

| 日志行 | 含义 | 下一步 |
| --- | --- | --- |
| `MIC: ✓ … 收到 N 个数据报 / M B——车机在推麦克风` | ① 通了 | 做解密 + PCM |
| `MIC: … 200Xms 内没有数据报——这一组车机没推` | 这组参数不对 | 看下一组 |
| `MIC: 车机拒绝输入流——…` | SETUP 非 200 | ① 基本无望，停 |
| `MIC: 结论——本轮所有组合都没数据报` | 全灭 | 停；只剩「语音激活时才推」这一种可能（需要用 Siri 触发再试） |
| `MIC: 首个数据报 16B head=…` | 收到了但要看格式 | 按 head 判断是不是 RTP / 是否加密 |

## 四、② 手机侧：引擎认不认 9242

CarLife 协议里 `9242` 是 VR 通道（`CarLifeProtocol.PORT_VR`），对应官方车机「车机录音 → 手机识别」。
我们只连不发——因为 11-04 实测「引擎收到 VR_START 后用自己的麦克风」。

**这一关可以在没有车机的情况下单独验**：

1. 拼一包 VR 音频：`[12B 头][PCM]`，包类型按媒体/TTS 的规律先试 `0x50001/0x50002/0x50006`
   （媒体是 `0x3xxxx`、TTS 是 `0x4xxxx`，VR 大概率是 `0x5xxxx`，需现场确认）；
2. 把一段**已知内容**的 16k 单声道 PCM（例如「导航到天安门」）按 20ms 一包推进去；
3. 发 `0x68008 VR_START`；
4. 看 CarLife 识别出的是**我们注入的内容**，还是手机麦克风听到的环境声。

识别出注入内容 → ② 通，两条链路接起来即可。
识别不出来 → 说明引擎只认自己的 `AudioRecord`，那就得回到「怎么让系统麦克风指向车机」
（无 root 基本无解），这条路到此为止——**先验②，能省掉①的解密工作量**。

## 五、顺序建议

严格一次只验一件事，避免「两处都是错的，日志看不出是哪一处」：

1. **①探针**（本轮已实现，装 `1.2.31 #micprobe` 跑一次真车）；
2. 若①有数据 → 先解密 + 落盘成 wav 听一遍（确认不是噪声/不是加密乱码），再谈灌进 CarLife；
3. ②的注入实验独立做（**不需要车机**），成功后再把两条接起来；
4. 接线时注意与 `VoiceKeyRouter` 的时序对齐：VR_START 之后才开流，VR_STOP 之后停止送包，
   且 `siriAction` 的「长按 ≥1.5s 才补 STOP」规则不能破。

## 六、已知风险

- **ALAC/AAC 输入**：若车机只给 ALAC（Apple 无损），Android 没有系统解码器，得自己实现或砍掉这条路。
  探针的 4 组都挑了 PCM 位的组合，正是为了绕开这个。
- **资源仲裁**：多开一条输入流可能让车机把通话/媒体档踢掉。探针限制「一次一组、6 秒、只跑一轮」，
  且失败即退出，就是为了把影响面压到最小。
- **探针本身不该进正式版**：`MIC_PROBE_ENABLED` 是诊断开关，验证完要连同日志一起收掉。

---

## 附：ESP32-P4 接收端项目（songplay / xcertplay 移植）能不能帮上忙

对照过 `github.com/W2215889498/songplay` 的 `esp32p4/STATUS.md`（2026-11-08）。结论分两半：

**对麦克风这条需求：没有帮助。**

- 他们明确把**音频与 HID 排除在范围外**（「明确不做的范围：有线 CarPlay、音频、HID」），
  麦克风/音频上行标为 ❌ 未实现；
- 而且他们的音频接收是**坏的**：iPhone 从不下发音频流（SETUP 里没有 type 100/101/102），
  他们自己也不知道原因（STATUS §9.8），连播放链路都没跑通，自然更没碰上行。

**对发射端其它问题：有 2 条可用的交叉线索。**

1. **资源仲裁（最有价值）**：他们的关键观察是「`modesChanged` 里 audio 资源（resourceID 2）
   始终不被手机接管，屏幕资源 1 会被接管」——即**谁 take/borrow 了 audio 资源，谁才拿到声音**。
   我们是手机侧，对应的是「我们有没有 take 车机的 audio 资源」。这条正好对着我们一个老问题：
   `docs/audio-call-issues.md` 里「通话档在车机上永远没声音」。下一步若再查通话档，
   **先看 iAP2 的 modes/take/borrow 是否有 audio resource**，不要只盯音频流参数。
   他们的候选清单可以整条当检查表：`/feedback` 的 stream clock、`0xA100` VehicleStatus、
   `0x4E0B` device time、HID descriptor、modes take/borrow。
2. **bplist 的三个硬要求**：插入顺序键、最小偏移表宽度、**恰好 32 字节 trailer**、根对象在偏移 8。
   他们为此卡了最久。我们的 [BinaryPlist] 早已四项齐备（`makeRootFirst` 就是对齐 catplay 的那次修复），
   可以当作反向印证——**这里不需要改**。

其余内容对本项目是**方向相反或已覆盖**：控制通道 ChaCha20-Poly1305 帧格式与密钥方向
（我们用 `AirPlayCrypto.chachaSeal` + `ControlCipher` 已实现）、视频 128 字节头与 nonce 计数器
（我们是发送端）、ARP 陈旧与随机 MAC（他们是 AP 侧）、iAP2 PSN 99/100 与会话号（我们已跑通）。

---

## 九、xcertplay 给出的正确做法（2026-11-08 下午，重要修订）

参考 `github.com/shilapi/xcertplay`（Android 车机端接收器，**GPLv3**；本项目 MIT，
因此**只取协议事实，不搬代码**）。它已实现「车机麦克风上行」，说明前面第二～四节的
探针模型**判断错了方向**，真正的机制是：

> **不是**另开一路 `input=true` 的流，而是**在 type=100 的主音频 SETUP 里带上我们自己的
> `dataPort`**——车机看到这个端口，就开始把麦克风音频推过来。它的原话：
> *"The input port, rather than the downlink category, signals that the phone requests a mic."*
> 判据只有两条：`type == 100` 且 `dataPort` 在 1..65535。

### 9.1 完整参数（可直接实现）

| 项 | 值 |
| --- | --- |
| 触发 | 主音频 SETUP 的 stream dict 里带 `dataPort`（我们的 UDP 端口）；建流后**持续推**，不必等 Siri 激活 |
| 密钥 | `HKDF-SHA512(sharedSecret, "DataStream-Salt<streamConnectionID>", "DataStream-Input-Encryption-Key", 32)` —— 与我们已有的 Output 密钥同一个函数，只是 label 换成 `Input` |
| 包格式 | `[12B RTP 头][ChaCha20-Poly1305 密文][16B tag][8B LE nonce]` |
| RTP 头 | `80 <PT=type> <seq BE16> <ts BE32> <SSRC 4B>`；PT = 100 |
| AAD | RTP 头的 `[4..12)`（timestamp + SSRC） |
| nonce | 4 个零字节 + 8 字节 **LE** 计数器（同一流内递增） |
| 负载 | **LPCM 16bit 大端**（单声道按声道数复制）/ Opus 48k mono / AAC-ELD mono；**AAC-LC 不支持** |
| 采样率 | 按协商 audioFormat 位图；对端采集 16k 再重采样 |

### 9.2 对我们两个已有实现的修正

1. **探针（`probeMicrophoneInput`）**：独立开一路 `input=true` 的 SETUP 并非真 iPhone 的做法，
   车机很可能不认。正确做法是把 `dataPort` 加进**现有主音频 stream**，然后用
   `DataStream-Input-Encryption-Key` 解密。
2. **9242 注入**（第二节 ②）依然是独立问题，且**不需要车机**就能验，仍建议先做。

### 9.3 连接方式本身：没有新东西

它是接收端，我们是发送端，流程是镜像的（它 mDNS 广播等电话来连，我们主动连车机）。
`/info → SETUP → RECORD → /feedback`、`eventPort` 由电话在 SETUP 应答后主动连、
`keepAlivePort` 仅在请求带 `keepAliveLowPower` 时给——这几条我们**已按同样顺序实现**，
可当作交叉验证。它的 `invokeSiri()` 连发 `requestSiri {siriAction:2}` 与 `{3}` 两帧，
也印证了我们 `VoiceKeyRouter` 对 2=ButtonDown / 3=ButtonUp 的解析。

---

## 十、按正确机制实现（2026-11-08 晚，`#micuplink` 测试包）

第九节确认了机制，这一节是把它写进代码。**探针模型已经作废**（`MIC_PROBE_ENABLED=false`，
代码先留着），新的路径是「主音频 SETUP 带 dataPort」。

### 10.1 链路

```
SessionController.startAudioChannel()
  ├─ MicUplink.open()                 // 绑一个本地 UDP 端口，不发任何报文
  ├─ client.runAudioStream(..., micPort = uplink.localPort)
  │     └─ SETUP 的 stream dict 多一项 dataPort=<我们的端口>
  ├─ 建流成功 → uplink.start(shared, audio.streamConnectionId, audio.sampleRate)
  │     └─ HKDF(..., "DataStream-Input-Encryption-Key") → 起收包线程
  └─ teardown → uplink.stop()         // 落 wav + 打结论
```

音频流被**重建**（通话结束 / 看门狗重连 / 媒体↔通话换档）时，新 SETUP 继续带同一个
端口（UDP socket 一直绑着，重复声明合法），成功后只调 `updateKey()` 换密钥——
盐是 `streamConnectionID`，换流就换。

### 10.2 关键参数（写死在代码里的）

| 项 | 值 | 位置 |
| --- | --- | --- |
| 端口方向 | 我们声明 `dataPort`，车机往这里推 | `AirPlayClient.runAudioStream(micPort=…)` |
| 密钥 | `HKDF-SHA512(shared, "DataStream-Salt<connId>", "DataStream-Input-Encryption-Key", 32)` | `MicUplink.start()` |
| 包 | `[RTP 头][ChaCha20-Poly1305 密文+16B tag][8B 尾部 nonce]` | `MicUplink.decodePacket()` |
| AAD | RTP 头 `[4..12)`（ts+ssrc） | 同上 |
| 负载 | LPCM 16bit **大端** → 换序成小端再落盘 | `MicUplink.swapS16()` |

### 10.3 三个「先试了再说」的地方

手里的几份实现对这三处并不一致，而每次验证都要等上车，所以首包按顺序试解、
Poly1305 tag 校验命中即锁定（128 位，误判概率可忽略），日志会打 `MIC: 包格式=…`：

1. 尾部 8 字节是 nonce 还是密文（`nonce=尾部` / `nonce=序号计数器`）；
2. 尾部 nonce 是小端还是大端存放；
3. AAD 算不算（`+AAD(头4..12)` / `+无AAD`）。

RTP 头长度也不再假设 12 字节——按 `CC` 与扩展位动态算，否则真机带扩展时
「每包都解不开」，而这种错在日志里看不出原因。

### 10.4 日志判读（连上车后搜 `MIC:`）

| 日志 | 含义 | 下一步 |
| --- | --- | --- |
| `MIC: 上行监听已启动 dataPort=… connId=…` | 端口已声明，等车机推 | — |
| `MIC: 首个数据报 … ts=… head=…` | **车机在推**（第一道关通了） | 看下一行包格式 |
| `MIC: 包格式=nonce=尾部小端 + AAD(头4..12)` | 与下行同族，符合预期 | 听 wav |
| `MIC: 第 N 包解不开` | 密钥或格式不对 | 看 `connId` 是否与 SETUP 日志一致 |
| `MIC: 结论——✓ 收到 12 包、解开 12 包…峰值 8134/32767` | 成功，wav 已落盘 | 拉 wav 听 |
| `MIC: 结论——…端口一包没收到` | 车机没推（可能只在语音激活时推） | 按方向盘语音键再试一轮 |
| `MIC: 结论——收到 N 包但一包都解不开` | 端口对了、密钥/格式错 | 把 head 前 24 字节发我 |

峰值（`峰值 x/32767`）能一眼区分三种情况：接近 0 = 车机推的是静音流；
几百到几千 = 有声音（人声/环境声）；恒在 32767 = 解错了（噪声满幅）。

### 10.5 wav

落盘位置：`下载/CarPToCarL/CarPToCarL-mic-<时间>.wav`（MediaStore，无需权限），
`adb pull /sdcard/Download/CarPToCarL/` 也能拿到。采样率取建流时协商到的那一档
（48k/44.1k），声道按单声道写——**采样率写错只会让播放速度不对，不影响「是不是人声」的判断**。
只保留前 20 秒（够听清，且不占内存）。

### 10.6 风险与回退

- `dataPort` 是**可选字段**，只在 `MIC_UPLINK_ENABLED=true` 时出现；关掉即回到
  1.2.31 的纯下行行为，零残留。
- 若加了 `dataPort` 后下行音频出问题（车机把这条流当成通话上行，不再给下行通路），
  第一件事是把这个开关改 `false` 再验一轮——这就是「A/B 对照」。
- 音频流重建后端口沿用旧值；若车机要求新端口，表现是「重建后一包不收」，日志可见。

### 10.7 还没做的一步

即使 wav 里是清晰人声，也只是第一道关。**第二道关（CarLife 认不认注入）仍未验证**：
引擎收到 `0x68008 VR_START` 后用的是手机自己的麦克风（11-04 实测），9242 VR 通道
我们只连不发。验法见第二节 ②，不需要车机。

## 十一、实车反证：往 media 档塞 dataPort 会把车机打死（2026-11-09）

10.7 那条「第二道关还没验」的第一步就被否掉了——**这台车机根本不接受我们往媒体流里塞输入端口。**

### 11.1 现场

`#micuplink` / 1.2.32（`log-20261109-152317`，宝马 G38 NBT Evo）：

```
34.84  音频 SETUP 200  type=102 media  dataPort=6000  + 我们附带的 micPort=44701
34.94  补发 RECORD 完成
37.04  WiFi onLost / 服务消失 / 事件通道 abort        ← 2.1 秒后整机掉
```

`/info` 里同一档自己写得很清楚：

```
audioFormat[2] type=102 audioType=media output=0x400000 input=0x0   ← 输入位是 0
audioFormat[0] type=100 audioType=default output/input=0xc000000    ← 输入只在这一条
```

**我们在一条车机声明「没有输入能力」的流上，声明了自己的输入端口。**

### 11.2 最硬的对照实验：同一台车、同一档、旧构建活了 12 分钟

`log-20261107-200015` 是用户自己改的构建（没有麦克风功能），19:49 那一轮：

| 项目 | 19:49 旧构建（活 12 分钟） | 17:12 我们（17ms 后死） |
|---|---|---|
| 音频流类型/编码 | `t102/media/AAC-LC 44.1k` | 同 |
| 音频 SETUP 字段 | `type / streamConnectionID / dataPort / controlPort` | **多一个我们自己的输入端口** |
| 音频流加入后补发 RECORD | 发 | 发 |
| 整轮 RECORD 次数 | **4 次** | 2 次 |
| MIB3 突发 | 发（含 0xA100 / 0xFFF0） | 发（已裁掉那两条） |
| 空态回包 0x5001/0x4155/0x4158 | 发 | 发 |
| 0xFFFA 定位订阅 | 发 | 发 |

结论两条：

1. **「重复 RECORD 把车机打死」不成立**——旧构建连发 4 次，稳跑 12 分钟；
2. 两轮唯一的差别就是那个输入端口。

### 11.3 代码改动

判据是 `/info` 里**这一档自己的 `audioInputFormats`**，不是 `audioType` 的名字。

第一版（提交 `a3baa2b`）为了最小改动写成了「`audioType == media` 一律不声明」。这在宝马上是
对的，但会把奥迪一起误杀：奥迪 MIB3H（3873/3874）的媒体通路就是 `t100/media`，`audioType`
同样叫 media，而它自己声明了输入位（bit26/27）。同一份代码在两台车上给出相反的正确结论，
说明判据本身选错了——`media` 这个名字既不必要也不充分。

现在的实现：

- `AudioProfile` 增加 `inputFormats: Long?`（`null` = `/info` 没给），并由
  `audioCandidates()` / `callCandidates()` 逐档带出；
- `AudioProfile.micInputDeclared`：`0` → 车机明说没有输入、`null` → 未知、非 0 → 有；
- `SessionController.micPortFor()`：只有 `true` 才声明端口；`false` / `null` 一律不发。
  未知也保守跳过——拿猜的判据去赌一次整机重启不值得。

日志按原因分开（每个会话最多一行）：

```
MIC: 本档 t102/media/0x400000(...) 不声明上行端口——车机对这一档声明的 audioInputFormats 就是 0x0（纯下行），塞端口会让它关通道
MIC: 本档 t102/media/0x400000(...) 不声明上行端口——车机 /info 没给这一档的 audioInputFormats，保守不声明
```

`MIC_UPLINK_ENABLED` 仍然留着：只要所选档自己声明了输入位，上行照旧生效。

> **两条实车结论的分界**：`media` 能不能挂麦，各车不同。宝马 G38 的 `t102/media` 是 `0x0`；
> 奥迪 3873/3874 的 `t100/media` 不是。**别用车系、更别用 audioType 名字判，只读那一行
> `AIRPLAY: audioFormat[i] … input=0x…`**（3874 的实测值本仓库目前没有记录）。


### 11.4 若还要继续做

- 麦克风必须单独走 `type=100 default`（本车 `input=0xc000000`）那条流，不能挂在媒体流上；
- 但那两条的输入格式位是 **bit26/27**，不是 PCM 位——`MicUplink` 现在按「大端 s16 LPCM」解，
  即使车机推过来也解不出人声。**下一步应先确认 bit26/27 的实际编码（AAC-ELD？），
  再谈注入 CarLife 9242**，否则做出来的是一包噪声。
