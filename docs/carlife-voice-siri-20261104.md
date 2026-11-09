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

---

## 7. 2026-11-09 复查：语音键**确实到了**，是 CarLife+ 没接住

> **本节 2026-11-09 13:00 更正。** 早先版本写的是「这台车机完全没发语音键」，
> **那是错的**——判断依据是两份只覆盖 6 分钟的导出。见 7.1。

### 7.1 我第一次判错在哪

用户给的两份导出（`CarPToCarL-log-20261109-093213.txt` / `-094322.txt`，1.2.31 #oneperm，
Audi_MMI_8349）是**「仅本次启动」**范围：

| 文件 | 实际覆盖 | 里面的 `requestSiri` |
| --- | --- | --- |
| `-093213` | 09:26:05 → 09:32:13（约 6 分钟，视频在推） | 0 条 |
| `-094322` | 09:36:57 → 09:37:52（未建链，蓝牙断开） | 0 条 |

而用户 08:52 那次按语音键的会话**不在这两份文件里**（导出范围之外）。把「这 6 分钟没出现
requestSiri」推成「这台车机从不发语音键」，是把**采样窗口**当成了**全局事实**。

### 7.2 实际发生了什么（用户 08:52 截图）

```
[08:52:56.687] EVENT: 车机语音键（requestSiri 按下→requestSiri 松开，按住 7108ms）
                     → CarLife 0x68008 keycode=0x22 VR_STOP（PTT 收尾）
[08:52:56.687] CARLIFE: 车机硬键 0x22（语音识别结束）
[08:52:56.688] CARLIFE: 车机语音键 0x22 → 0x68008 已送达
```

即：`requestSiri` **到了**，按下时（08:52:49.58）已发 `VR_START=0x21`，按住 7.1 秒后松手补
`VR_STOP=0x22`。**车机侧、我们这侧的转发都没问题**，断点在第三步：CarLife+ 收到 `0x21` 后
没有起语音（用户那边看到的就是「按了没反应」）。

### 7.3 引擎侧的判定链（字节码实证，`classes2.dump.txt`）

```
0x68008 → CarlifeCarHardKeyCodeProto.CarlifeCarHardKeyCode.parseFrom → getKeycode
        → ReverseHardKeyChannelManager.transferKeyCodeIfNeed(keycode)
        → CarlifeTouchManager.onHardKeyCodeEvent(keycode, "touchManager")
```

`onHardKeyCodeEvent` 里要被扣下的几道闸，条条都能让「命令送到了但毫无反应」：

1. `correct()` = `!MixConfig.isMix4Vivo()`（vivo 专用位，与我们无关）；
2. 同一 keycode **50ms** 内重复 → 直接丢（日志 `hard key too fast`）——
   所以长按不能连发 `0x21`，现在的边沿判定是对的；
3. `CommonMsgTransmit.checkCarLifeChannelStatus()` 不过 → `checkPhoneStatus not pass, return`，
   **整条硬键被丢掉**；
4. 再经 `handleSpecialHardKey(keycode)` 与 `OnHardKeyCodeEventListener.onHardKeyCodeEvent` 分发。

另外百度按「车机渠道号」开关语音硬键：

- `DisHardKeyVoiceChannelManager` 里硬编码了**禁用**语音硬键的渠道：`20082120`、`20742105`；
- `VoiceVehicleChannelManager.channelDefault = 20022107`、`defaultWakeUpSoundFromPhoneChannel =
  20202100 / 21050007`；
- 渠道号由 `CarlifeUtil.setVehicleChannel` 写入（来自车机下发的特征配置），
  `onHardKeyCodeEvent` 里也确实拿 `getVehicleChannel()` 与 `20522121` 做比较。

也就是说：**如果引擎认为「这台车机不支持语音硬键」，`0x21` 会被静默忽略**，
和我们发的包对不对无关。这条目前只是怀疑，还没有证据——所以下一步要拿到 7.4 的判据。

### 7.4 本轮补的判据：引擎自己的模块状态（65574）

引擎真起语音时会回 `0x10026`（`MSG_CMD_MODULE_STATUS`，我们一直在回 98344，但从没打过日志）。
模块号来自 `ModuleStatusManage.<init>`：**1 电话 / 2 导航 / 3 音乐 / 4 语音 / 6 麦克风 /
8 巡航 / 9 跟车**。

现在这行只在**变化时**输出，并对语音模块单独点名：

```
CARLIFE: 引擎模块状态 电话=1 音乐=0 语音=0
CARLIFE: 语音模块=1——引擎已进入语音态（0x68008 0x21 生效）
CARLIFE: 语音模块=0——引擎当前没有语音会话（发了 0x21 却一直看到这行，说明 0x68008 被判无效）
```

判读方法（下次按键后看三行）：

| 日志 | 含义 | 下一步 |
| --- | --- | --- |
| `已送达` 后紧接着 `语音模块=1` | 引擎接住了硬键 | 问题在音频：引擎用手机麦克风，查 CarLife+ 的录音权限 |
| 只有 `已送达`，始终没有 `语音=1` | 引擎把硬键丢了 | 查 7.3 第 3 条（渠道状态/渠道号） |
| 连 `引擎模块状态` 一行都没有 | 引擎根本没上报模块 | 先确认 `已送达` 是否也缺失（没送出去） |

### 7.5 两个零成本对照实验（不用改代码，优先做）

1. **手机上直接点 CarLife+ 自己的语音按钮**：
   - 车机屏出现语音界面 → 语音链路本身可用，缺的是「硬键入口」被拒 → 走 7.3 第 3/4 条；
   - 车机屏毫无变化 → CarLife+ 的语音在这台手机上本身不可用（语音组件/录音权限/被系统限制），
     与我们的 `0x68008` 无关。
2. **按键时看车机屏**：CarLife 的语音页是它自己渲染、我们只转发它的 H.264，
   所以「车机屏有没有变」等价于「引擎有没有起界面」。

### 7.6 另一处已补的诊断（保留）

以前「车机发了 HID 报告、但那个 usage 不在描述符里」是**完全静默**的——症状就是
「按了键，日志一个字都没有」。现在会打：

```
EVENT: HID 报告 uuid=0 值=0x0c 映射不到 usage（描述符声明 9 项：0x00 0xb0 …）
      ——车机按了键但我们没声明这项
```

限 8 条（`UNKNOWN_CONSUMER_LOG_LIMIT`）。
