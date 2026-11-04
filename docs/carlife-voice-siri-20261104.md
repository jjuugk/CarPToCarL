# 语音键真实通路与「收尾」判定（2026-11-04，1.2.22）

> 起因：1.2.21 发版装车后，用户要求「看下手机上的日志，然后看语音键要不要补收尾」。
> 结论：**要补，但首先要修的是「语音键根本没走我们接的那条路」**；收尾只在长按时补，
> 不能松手就发 `VR_STOP`。

## 1. 日志证据

从手机 `/sdcard/Download/CarPToCarL/` 拉到的 6 份历史日志 + 本地 4 份，逐条检索：

| 检索项 | 结果 |
| --- | --- |
| `IAPLINK: 车机 0x6802` 报文明细 | **0 条** |
| `usage=0xcf` / `0xCF` 语音键 | **0 条** |
| `EVENT: 车机媒体键 …（uuid=0 usage=0xb5 …）` | 5 条（全是「下一首」，走 `hidSendReport`） |
| `EVENT: 命令 type=requestSiri` | **9 条**（1 次 `1`、4 次 `2`、4 次 `3`） |
| `/info hidDevices` 的 Consumer 描述符 | 46B Array 版 / 35B 位图版，**都没有 0xCF** |

`requestSiri` 原文（`CarPToCarL-log-20261103-085250.txt`）：

```
[08:52:12.092] EVENT: 命令 type=requestSiri params={siriAction=1}
[08:52:12.643] EVENT: 命令 type=requestSiri params={siriAction=2}
[08:52:16.512] EVENT: 命令 type=requestSiri params={siriAction=3}
[08:52:19.076] EVENT: 命令 type=requestSiri params={siriAction=2}
[08:52:22.766] EVENT: 命令 type=requestSiri params={siriAction=3}
[08:52:30.410] EVENT: 命令 type=requestSiri params={siriAction=2}
[08:52:32.564] EVENT: 命令 type=requestSiri params={siriAction=3}
[08:52:41.953] EVENT: 命令 type=requestSiri params={siriAction=2}
[08:52:42.846] EVENT: 命令 type=requestSiri params={siriAction=3}
```

按住时长：3.87s / 3.69s / 2.15s / 0.89s。只有**第一次**按键之前有 `1`，之后每次都是
成对的 `2`→`3`。

`/info` 里另外两条线索也指向同一个结论（AUDI 3810 / 4910）：

```
buttonInfo=[{buttonLocation=0, buttonPressDuration=1, buttonType=0}]
enhancedSiriInfo={enhancedSiriButton=false, enhancedSiriVoice=false}
```

车机自己声明了「有一个 Siri 实体按钮」，且没有启用 Enhanced Siri（零延迟/语音触发），
所以按键就是标准的 `requestSiri` 事件，不会绕到 HID Consumer 的 Voice Command。

## 2. `siriAction` 的权威定义

来自 Apple `AirPlayCommon.h`（`AirPlaySiriAction`），**不是猜的**：

```c
#define kAirPlaySiriAction_NotApplicable   0
#define kAirPlaySiriAction_Prewarm        1  // Pre-warm Siri.
#define kAirPlaySiriAction_ButtonDown     2  // Button down.
#define kAirPlaySiriAction_ButtonUp       3  // Button up.
```

## 3. 1.2.21 的两处缺口

1. **主通路接错**：`SessionController` 只在 `AirPlayClient.onMediaKey`（iAP2 虚拟链路
   `0x6802`）里拦 `KEYCODE_VOICE_ASSIST`，转到 CarLife `0x68008 0x21`。这台车根本不
   发 `0x6802` HID 报告，日志里连一条报文明细都没有 —— 这条链路是死代码。
2. **第二条路漏接**：事件通道 `hidSendReport` 直接调 `dispatchMediaKey(keyCode)`，
   语音键即使到了也只会当成 Android 系统键注入（本机没 Siri = 什么也不会发生）。
   日志里 5 条真实媒体键全是这条路。

`ConsumerKeys.keyCodeFor(0xCF) = KEYCODE_VOICE_ASSIST` 本身没错，别的车型也可能真用
`0xCF` 送 Voice Command，所以**保留兜底**，不删映射。

## 4. 要不要补「收尾」

**要补，但只在长按时补。**

`ButtonUp(3)` / HID 释放报文确实给了明确的「松手」信号，车机 SDK 也确实提供了
`KEYCODE_VR_STOP = 0x22`（`CommonParams.java`，与 `KEYCODE_VR_START = 0x21` 成对）。
但 `ButtonUp` 只是**物理按键状态**，不等于「用户要结束识别」：

- CarPlay 的习惯是**按住方向盘语音键等提示音、松手后才开口说话**（Apple 官方说明也是
  「按住语音指令按钮，然后提问」）。松手立刻发 `VR_STOP` 会把刚起来的识别掐掉。
- 反过来，「按住说话」的 PTT 用法又确实需要松手收尾，否则 CarLife+ 收不到结束信号。

所以按按住时长分流（阈值 1.5s，正好把实测的 0.89s 与 ≥2.15s 分开）：

| 输入 | 发什么 |
| --- | --- |
| `siriAction=1` Prewarm / 未知值 | 不发，只记日志 |
| `siriAction=2` 或 HID 0xCF 按下 | `0x68008 KEYCODE_VR_START=0x21` |
| 松手，按住 < 1.5s | **不发 VR_STOP**，交给 CarLife+ 自己按静音/超时结束 |
| 松手，按住 ≥ 1.5s | 补 `0x68008 KEYCODE_VR_STOP=0x22` |
| 15s 内没等到释放 | 状态作废，不猜、不补发 |

状态机在 `carlife/VoiceKeyRouter.kt`，纯判定、不碰 socket；实际的本地 socket 写仍由
`SessionController` 的 `carlife-cmd` 单线程执行，避免卡住 iAP2 收帧线程的 ACK。

## 5. 代码改动清单

| 文件 | 改动 |
| --- | --- |
| `carlife/VoiceKeyRouter.kt`（新） | 状态机：Prewarm/ButtonDown/ButtonUp、边沿触发、按住时长分流、超时作废 |
| `SessionController.kt` | 事件通道新增 `requestSiri` 分支；`hidSendReport` 按下/释放两条路都收进状态机；`onMediaKey` 的 0xCF 兜底改用状态机；会话开始 `voiceKey.reset()` |
| `airplay/VirtualIap2Link.kt` | `fireHidKeysLocked` 下跳沿新增 `onHidKeysReleased(Set<Int>)` |
| `airplay/AirPlayClient.kt` | 转发 `onHidKeysReleased` |
| `carlife/CarLifeHijack.kt` | `sendVoiceKey(keycode)` 支持 `VR_START` / `VR_STOP` |
| `carlife/VoiceKeyRouterTest.kt`（新） | 12 例，含实测日志序列重放 |

注意：`hidSendReport` 的释放报文只有一个 0x00、不带 usage，所以新增
`lastConsumerUsage` 记住刚才按下的是哪个键 —— 否则松开「下一首」也会误发 VR_STOP。

## 6. 上车验证看什么

| 想看的 | 日志关键字 |
| --- | --- |
| 车机确实发了语音键 | `EVENT: 命令 type=requestSiri` 不再落到「未识别命令」分支 |
| 起识别 | `requestSiri 按下 → CarLife 0x68008 keycode=0x21 VR_START` |
| 短按不补收尾 | `短按 …ms → 不发 VR_STOP` |
| 长按补收尾 | `按住 …ms → CarLife 0x68008 keycode=0x22 VR_STOP（PTT 收尾）` |
| 命令真的送达 | `CARLIFE: 车机语音键 0x21/0x22 → 0x68008 已送达` |

**实车要确认的一件事**：CarLife+ 在长按松手收到 `VR_STOP` 后的表现。若发现它是
「松手后还要继续说」的语义（即 `VR_STOP` 会提前截断），把
`VoiceKeyRouter.PTT_HOLD_MILLIS` 调大即可完全关掉自动收尾（改成一个极大值），
起识别那段不受任何影响。反过来若短按也需要收尾，把阈值调小到 0 即可。
