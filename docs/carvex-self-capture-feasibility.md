# 参考 CarVeX（iamr0s/carvex-carplay-research）能否替代手机侧 CarLife

> 调研日期：2026-11-03。对象：`github.com/iamr0s/carvex-carplay-research`（根仓库，研究文档）
> + `iamr0s/carvex-android`（Android 实现，submodule）。本文所有关于参考项目的判断都标了
> 文件与行号，可直接回查；凡是没读到代码的不写。

## 结论速览

| 问题 | 结论 |
|---|---|
| 能不能直接用它的实现替代手机 CarLife？ | **不能整体照搬。** 它的内容模型是「App 自己的界面」，不是手机镜像；`:protocol-carlife` 是空骨架；车载音频与麦克风根本没实现（`README.md:178`） |
| 有没有值得抄的？ | **有，而且是硬货。** 送帧闸门（含主动请求 IDR）、码率自适应、编码器能力探测、`min(接收端, 宿主建议)` 的参数决策，四件都打在我们的痛点上 |
| 「去掉 CarLife 依赖」可行吗？ | **可行，而且比预想便宜。** 我们本来就在申请 MediaProjection（音频 `AudioPlaybackCapture` 用），只差 VirtualDisplay + 编码器 |
| 代价是什么？ | 失去 CarLife 的车机优化界面（横屏分栏、大按钮）；触摸回注要另建通道；前台服务与 Android 14 授权顺序要重做 |
| 合规红线 | 参考项目是 **GPL-3.0**，我们是 **MIT**。**不能复制任何一行代码**，只能读懂思路重写 |

## 1. 参考项目是什么

| 项 | 值 |
|---|---|
| 定位 | 「按 iPhone 固件逐字段复现」CarPlay 发送端，目标是 Android 手机直连各协议车机 |
| 语言/规模 | Kotlin，350 个 `.kt`、38,038 行；单测 157 个文件（我们：113 个 `.kt`、40,072 行、52 个测试文件） |
| 许可证 | GPL-3.0（我们 MIT，**不可拷贝代码**） |
| 成熟度 | README 自述「研究预览」「本地测试与编译不代表真实车机互通」，认证仍属实验阶段 |
| CarPlay 覆盖 | iAP2 链路、Identification、Wi-Fi handoff、AirPlay control / RTSP SETUP、视频/音频/麦克风管线 |
| CarLife 地位 | `:protocol-carlife` **只有 2 个文件**，README 明确「只是预留骨架，不能当作已实现的 CarLife 连接」 |

关键点：它在 **CarLife 上什么都没做**，反而在 `:host-carlife-mixing` 里借小米 CarWith、OPPO CAR+、
vivo、三星的 CarLife 兼容入口启动自己的会话（`README.md:79`）。所以「参考它来做 CarLife」
这个说法要先纠正：**它的价值不在 CarLife，而在「自己产出视频」这一段。**

## 2. 它的视频链路，逐段对照我们

### 2.1 内容从哪来（差异最大的一处）

```text
CarVeX：Compose 页面 → Presentation（own-content-only 虚拟屏）→ 直接就是编码器输入 Surface
我们：  CarLife 引擎 7240/8240 → 伪装车机端吃 H.264 → 直通给车机
```

- `app/.../LocalVideoDisplay.kt:51` 注释写得很直白：*no MediaProjection token, no overlay permission
  and no Activity, so it also works while the app is in the background*。它把「不用录屏授权」
  当成优点——因为它的画面是**自己画的界面**，不是手机屏幕。
- `virtualDisplay(...VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)` 直接以编码器 Surface 为输出
  （`LocalVideoDisplay.kt:63-70`），中间不经过任何额外拷贝。

**这意味着**：照它的模型做，车机上显示的是我们这个 App 的页面，不是手机的桌面/高德/音乐。
要显示手机屏幕，必须走 MediaProjection 镜像——**而这件事它明确不做**。

顺带说一句我们自己的历史：`app/.../capture/ScreenSource.kt:8-12` 的注释记着，
「桌面（Presentation 自绘）」与「镜像主屏（MediaProjection AUTO_MIRROR）」两种模式
在 2026-10-10 已移除；`HiddenDisplaySource.kt` 现在是死代码（只被 `SessionController` 里
一行 `release()` 引用，从未被实例化）。也就是说 **CarVeX 现在的模型，我们做过并且放弃了**。

### 2.2 编码器选择：它有完整的探测，我们没有

`VideoEncoder.kt:29-77`：

- 枚举所有 AVC Surface 编码器，按 host 声明的 `profile` / `level` / `bitrate-mode` 过滤；
- 硬编优先排序；
- `supportedFps()` 用 `getSupportedFrameRatesFor` + `areSizeAndRateSupported` 双向校验，
  在区间内**从高往低**找第一个真正支持的帧率；
- `supportedBitrate()` 返回设备真实区间，设置页据此画滑块，而不是拍一个上限。

我们只有 `NoticeFrameEncoder` 里的一次性建码（Baseline + `avcLevelFor`），没有能力探测；
1.2.20 那次 csd 是 AnnexB 的坑（`NoticeFrameEncoder.kt` 的 `isAnnexB/splitAnnexB`）就是
「想当然认为厂商输出 avcC」的代价。它的 `core/AvcConfiguration.kt` 是同一件事的正规解法。

### 2.3 参数决策：`min(接收端, 宿主建议)` + 编码器夹取

`SessionController.kt:706-733`：

```text
几何：宿主建议与接收端画布比例一致 → 用宿主建议；不一致 → 用接收端画布
帧率：limit = min(接收端 maxFPS, 宿主 hint)；再由 supportedFps() 夹到真实可编码值
码率：max(宿主 hint 按像素比归一, 按像素×帧率的 nominal)，再被编码器上限夹取
```

对照我们的现状：主线用 `StreamFps`（车机没报按 60），分支用 `DisplayTuning`
（大屏档没报按 30），两套至今没统一——这正是 1.2.16 合并时唯一的真语义冲突。
它的做法把这个问题消掉了：**接收端说了算，宿主建议只是建议，最后让编码器定。**

几何那段同样值得看：我们 1.2.20 才拆开「98311 申请值 / 65544 引擎确认值」修 10 秒拆链，
它用的是「比例不一致就退回接收端」——同一个教训，更省事的解法。

### 2.4 送帧闸门：它比我们多一个「请求 IDR」

`core/VideoDeliveryGate.kt:4-21`：

```text
needsConfig / waitingForKey 两个状态
config 帧 → 发出，重新进入等 IDR
还没有 config → 丢弃
关键帧 → 发出
在等 IDR → 丢弃
帧龄 > 150ms → DROP_REQUEST_KEY（丢弃**并请求**一个新的关键帧）
```

我们的对应物是 1.2.16 刚加的 `VideoSender.beginAwaitingKeyFrame()`：三处置位 + 起始时刻
做超时兜底，超时就放行 P 帧。**差别在最后一步**：我们只能「等引擎下一次 IDR」，
它可以在帧过期时**让编码器立刻吐一个 IDR**（`MediaCodec` 的 `PARAMETER_KEY_REQUEST_SYNC_FRAME`）。

这就是 ID7 黑屏反复断流排查那么费劲的根因之一：**编码器不在我们手上，就没有请求 IDR 的权力。**
一旦视频源改成自己编码，这个权力就回来了。

### 2.5 码率自适应：我们完全没有

`core/VideoRatePolicy.kt`：每秒采样一次「写阻塞时长」与「帧龄」；
忙（帧龄>150ms 或写>80ms）连续 3 秒 → 码率 ×4/5（下限 1Mbps）；
健康（帧龄<60ms 且写<40ms）连续 15 秒 → +250kbps（回到上限为止）。

我们目前码率是固定的，链路一拥塞就只剩「丢帧 / 车机拆链」两条路。这一件与视频源是谁无关，
**现在就能抄思路用上**。

### 2.6 静止画面保活

`VideoEncoder.kt:22-24`：`IDLE_REPEAT_INTERVAL_MS = 1_000`，没有输出时每秒重发一次最后一帧
（代价是一个极小的 P 帧）；另有可选的 `EncoderFrontSurface`（GPU blit 回灌，默认关，
因为它每帧多一次全屏 blit 和 3~4 帧延迟）。

我们的做法是 `lastIdrNals` 空闲重发 + `pushNotice` 每 5 秒重推提示帧——思路一致，
但它是「重发一个 P 帧」，更轻。

### 2.7 音频：我们比它强，且不依赖 CarLife

`README.md:178`：「车载音频与麦克风传输尚未实现」。全仓库没有 `AudioPlaybackCapture`，
也没有 `AudioRecord`（只有一个 `CarPlayMicReceiver` 收车机麦克风）。

我们这边 `audio/AudioSender.kt:278` 走 `AudioPlaybackCaptureConfiguration`，AAC 编码、
9240/9241 双通道、带看门狗，已经实车跑通；`CarLifeAudioSink` 现在只是把引擎 PCM
直通进去（注释里写着早期「本机播放 + 播放捕获」的老路已废弃）。

**结论：去掉 CarLife 之后音频反而更干净**——直接采系统里的媒体音频即可，
不用再依赖引擎宣告的 `CarlifeMusicInit` 采样率。

### 2.8 触摸

CarVeX：自有页面直接 `dispatchTouchEvent` 派进去（`LocalVideoDisplay.kt:110+`）；
镜像模式下没有注入通道（它不做镜像，所以不需要）。

我们：`TouchRouter.kt`（392 行）+ `input/CarInputClient.kt`，走 CarLife 的触摸通道回注。
**换成自采集镜像后，这条通道没了**，要改成 `injectInputEvent` 或 Shizuku/无障碍——
这是替代 CarLife 最容易被低估的一块。

## 3. 如果照搬会发生什么（三种场景推演）

| 场景 | 结果 |
|---|---|
| 照抄「自有页面」模型 | 车机上看到我们 App 的页面。技术上最省事，但用户预期是「手机投屏」，会立刻被判定为功能退化 |
| 照抄 + 自己加 MediaProjection 镜像 | 能显示手机屏幕，但**它没有这段代码**，等于我们自己写；且镜像出来是竖屏手机的原始布局，在车机横屏上排版难看，CarLife 在这方面做了车机优化 UI |
| 只抄编码/送帧管线，保留 CarLife 作源之一 | 收益确定（码率自适应、IDR 请求、参数决策），风险最小，但没解决「依赖百度 App」这个根本问题 |

## 4. 建议的落地路径

### 阶段 1：把视频源抽象出来（不动现状，纯重构）

把 `VideoSender` 的输入侧抽象成 `VideoSource`（`CarLifePassthrough` / `SelfCapture` 两种实现），
复用现有 `submitH264(..., annexB)` 入口。这一步不改行为，只让第二条源成为可能。
`capture/ScreenSource.kt` 已有雏形，但它绑定的是「编码器输入 Surface」，语义要改为「H.264 输出」。

### 阶段 2：自采集源（核心工作量）

- `MediaProjection` 我们已经持有（`service/CarPlayService.kt:169`，音频用的就是它），
  新增 VirtualDisplay + `MediaCodec` Surface 编码器即可，不必再造授权流程；
- 编码器按 CarVeX 的方式做能力探测（profile/level/bitrate-mode 过滤、硬件优先）；
- csd 两种布局都必须处理——这台荣耀机已知输出 AnnexB，`NoticeFrameEncoder` 的
  `isAnnexB/splitAnnexB` 逻辑可以直接抽成公共工具；
- 送帧闸门补上 `帧龄 > 150ms → 请求 IDR` 那条分支（自采集下我们才有这个权力）。

### 阶段 3：触摸回注 + 码率自适应

- 触摸：车机 HID 触点 → 坐标换算（`TouchRouter` 已有）→ `InputManager.injectInputEvent`
  （需要 INJECT_EVENTS 权限，实际要靠 Shizuku / 无障碍 / root 之一）；
- 码率：按 `VideoRatePolicy` 的采样逻辑做，这一项**与视频源无关，可以提前单独上**。

### 阶段 4（可选）：自有车机页面

长期如果想做「车机优化 UI」而不是硬镜像，CarVeX 的 `LocalVideoContent` +
`Presentation(own-content-only)` 模型就是正确形态——但那是产品方向的改变，不是技术替代。

## 5. 风险清单

| 风险 | 说明 |
|---|---|
| **许可证** | 参考项目 GPL-3.0，我们 MIT。**一行都不能拷**。所有落地必须按自己的注释风格重写，并且不看代码只按行为描述实现更稳妥 |
| 实车未验证 | 对方自述「视频、触摸与兼容性仍需实车验收」，它的参数选择不能当作车机兼容性的证据 |
| 第三方 App 天花板 | CarLife/荣耀车联/小米 CarWith 的镜像与注入靠系统权限；第三方 App 拿到不了一样的权限。要么 Shizuku，要么接受「自有页面」 |
| Android 14+ 限制 | MediaProjection 必须前台服务 + `mediaProjection` 类型 + 每次重新授权，后台长期镜像会被打断（我们音频已经在踩这个） |
| 车机侧期待 | 车机把 CarPlay 当「手机镜像」用时，只给 1280×720 的自有页面会显得很空；反过来硬镜像竖屏手机又会缩放变形。这个取舍要产品决定 |
| 断流回归 | 现在的 CarLife 直通虽然依赖百度，但**出帧是引擎保证的**；自采集一旦编码器卡住，就是全黑。需要把 1.2.20 的首帧闸门 + 提示帧机制原样保留 |

## 6. 一句话回答

**CarVeX 不能拿来当 CarLife 的替代品（它压根没实现 CarLife，视频也不是手机镜像），
但它把「自己编码、自己送帧」这一段做得很干净——那恰好是我们唯一缺的环节。**
真正值得动手的是：`VideoDeliveryGate` 的「帧龄过期就请求 IDR」、`VideoRatePolicy` 的码率自适应、
编码器能力探测、以及 `min(接收端, 宿主建议)` 的参数决策。这四件里，码率和参数决策
与视频源无关，**现在就能落地**；「彻底不依赖 CarLife」需要自采集源 + 触摸注入通道，属于中等规模改造。

## 附：证据索引

| 结论 | 出处 |
|---|---|
| 内容源是自有界面，不用 MediaProjection | `carvex-android/app/.../LocalVideoDisplay.kt:50-78` |
| 编码器能力探测 | `carvex-android/bridge/.../VideoEncoder.kt:29-77` |
| 几何/帧率/码率决策 | `carvex-android/bridge/.../SessionController.kt:706-740` |
| 送帧闸门（含请求 IDR） | `carvex-android/protocol-carplay/.../core/VideoDeliveryGate.kt:4-21`；实际请求在 `bridge/.../VideoEncoder.kt:258`（`PARAMETER_KEY_REQUEST_SYNC_FRAME`）与 `:339-340` |
| 码率自适应 | `carvex-android/protocol-carplay/.../core/VideoRatePolicy.kt:5-40` |
| 静止画面保活 | `carvex-android/bridge/.../VideoEncoder.kt:22-24` |
| 车载音频未实现 | `carvex-android/README.md:178` |
| CarLife 只是骨架 | `carvex-android/README.md:211` |
| 我们做过又移除的镜像模式 | `app/src/main/java/.../capture/ScreenSource.kt:8-12` |
| 我们的录屏授权已有 | `app/src/main/java/.../service/CarPlayService.kt:169-170` |
| 我们的音频采集入口 | `app/src/main/java/.../audio/AudioSender.kt:278, 570` |
