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
