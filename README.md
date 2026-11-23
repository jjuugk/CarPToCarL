# CarPToCarL — 让 Android 手机充当 CarPlay 发送端，把原生 CarLife 投到车机

一台普通 Android 手机（未 root）扮演 **CarPlay 发送端（iPhone 角色）**，  
连到支持 CarPlay 的车机；画面内容取自**手机原生 CarLife+ 引擎的 H.264 码流**，  
经本 App 劫持后**直通转发**给车机（不重编码）。

> **非官方项目**：个人实验性作品，与 Apple Inc.、百度及任何汽车制造商均无关联，  
> 未获授权或认证，**不是 MFi 产品**。CarPlay / AirPlay / CarLife 均为其各自权利人的商标，  
> 文中出现仅用于说明兼容目标。
>
> **驾驶安全**：仅限停车时配置与操作；行驶中依赖本方案可能导致导航中断，  
> 请以原厂导航作为安全冗余。
>
> 完整条款见 [DISCLAIMER.md](DISCLAIMER.md)（与应用内首次启动的免责声明同源）。

---

## 立即下载

**最新正式版**（免编译，直接装）：

> **[`CarPToCarL-1.2.83.apk`](https://github.com/jjuugk/CarPToCarL/releases/download/v1.2.83/CarPToCarL-1.2.83.apk)**
> —— 主线固定直链（当前 1.2.83，14.2 MB）。**刻意不用 `releases/latest`**：那个指针
> 按发布时间算，`-switchpool` 分支包一发布就会把它抢走，主线的 `latest` 直链随之 404

| 项        | 值                                                                              |
| -------- | ------------------------------------------------------------------------------ |
| 全部版本     | [Releases 页面](https://github.com/jjuugk/CarPToCarL/releases)（含更新日志与历史版本）        |
| 本版固定包    | [`CarPToCarL-1.2.83.apk`](https://github.com/jjuugk/CarPToCarL/releases/download/v1.2.83/CarPToCarL-1.2.83.apk) |
| 系统要求     | Android 12 及以上（minSdk 31）                                                      |
| 签名       | 作者个人调试证书（仓库不含发布密钥）→ 可直接**覆盖安装** 1.0.0 ~ 1.2.83，但不要与其它来源的包混装                    |
| 安装       | 系统安装器直接装，或 `adb install -r CarPToCarL-1.2.83.apk`                              |
| 前置       | 手机需装 **CarLife+**（荣耀机型为 `com.baidu.carlife.honor`）                             |

首次启动会弹出免责声明，需手动同意。装好后怎么配、常见故障怎么查，见
[第三节 使用说明](#三使用说明)与[第五节 故障排查](#五故障排查)。

---

## 一、项目简介

| 项    | 值                                                                             |
| ---- | ----------------------------------------------------------------------------- |
| 当前版本 | `1.2.83`（正式版；修 BMW MGU-81 全程无声——PCM 门禁只认引擎信号、暂停窗口 15s→1.5s、「立即重启一次」改先拆后建；并入 PR #37 BMW EntryEVO 链路死锁修复；并入 PR #40「仪表转向指引」开关（默认关，经「正在播放」显示 CarLife 转向文字与箭头）。详见「十一、版本历史」。版本号唯一来源：`app/build.gradle.kts` 的 `appVersionName`） |
| 下载   | **[`CarPToCarL-1.2.83.apk`](https://github.com/jjuugk/CarPToCarL/releases/download/v1.2.83/CarPToCarL-1.2.83.apk)**（见上方「立即下载」）       |
| 包名   | `io.github.shaonialife.carptocar`                                             |
| 应用名  | **CarPToCarL**（不含任何第三方商标）                                                     |
| 最低系统 | Android 12（minSdk 31，targetSdk 34）                                            |
| 适用车型 | 支持**无线 CarPlay** 的车机（真车已验证：奥迪 MIB3；测试台：DiPlay / xcertplay）                    |
| 画面来源 | 本机 **CarLife+** 引擎（荣耀：`com.baidu.carlife.honor` 8.8.5）                        |
| 音频   | PCM 直通（CarLife 音频通道 → AirPlay 音频通道）                                           |
| 参考项目 | **[CarDeck](https://github.com/shaonialife/cardeck)**（MIT，独立项目，与本项目非同作者）——本项目的协议层与工程骨架参考了它的实现 |

### 与参考项目 CarDeck 的关系

[CarDeck](https://github.com/shaonialife/cardeck) 是**独立项目**（MIT，非同一作者），本项目参考了它的
iAP2 / AirPlay 协议实现、密码学封装与工程骨架，声明见 [NOTICE](NOTICE) 第 0 节。日志目录 `下载/CarDeck`
与进程名 `CarDeck` 是历史沿用，不代表运行时依赖。差别只在**画面来源**：

| | CarDeck | 本项目 |
|---|---|---|
| 画面 | 自绘隐形屏 + Shizuku 注入 | **CarLife+ 码流直通，不重编码**；触摸走 HID → CarLife 9340 |
| 依赖 | 需要 Shizuku | **不需要**（2026-10-24 移除） |

两者均未使用第三方 MFi 授权，边界见 [docs/LEGAL-NOTES.md](docs/LEGAL-NOTES.md)。

### 方向说明

- 本项目是**发送端**（手机扮演 iPhone）；DiPlay / xcertplay 是**接收端**（DiPlay 为 xcertplay 衍生版，是本项目测试台）。
- 发送端**不需要苹果签名身份**：iAP2 认证单向（手机验证车机 MFi 证书，车机信任手机自报结果）。
- **有线 USB 已排除**（App 控制不了 USB gadget，测试机 BL 无法解锁），只走无线；绕法见
  [docs/WIRED-CARPLAY-FEASIBILITY.md](docs/WIRED-CARPLAY-FEASIBILITY.md)。

### 背景

Android 手机连 CarPlay 车机没有官方路径。本方案让手机冒充 iPhone 跑通 iAP2 + AirPlay，
再把手机侧最完整的车机界面（原生 CarLife+）的视频与音频**截下来直通**给车机——中间只做搬运。

---

## 二、工作原理

### 整体链路

```
┌──────────────────── 手机（本 App） ────────────────────┐
│                                                        │
│  ① CarLife+ 引擎（com.baidu.carlife.honor）             │
│       │  H.264 码流 + PCM 音频（127.0.0.1:7240 等 7 个端口）
│       ▼                                                │
│  ② CarLifeHijack（冒充车机，接管码流，不重编码）         │
│       │                                                │
│       ▼                                                │
│  ③ VideoSender / AudioSender                           │
│       │  AirPlay 视频/音频流（ChaCha20 加密）            │
│       ▼                                                │
│  ④ 蓝牙 iAP2 控制通道 + WiFi（加入车机热点）             │
│                                                        │
└────────────────────────┬───────────────────────────────┘
                         │  无线 CarPlay
                         ▼
                  ⑤ 车机（CarPlay 接收端）
```

### 关键协议动作

| 阶段   | 协议               | 本 App 做什么                                        |
| ---- | ---------------- | ------------------------------------------------ |
| 识别车机 | 蓝牙 RFCOMM + iAP2 | 与车机配对，走 iAP2 握手，拿到车机热点凭据与 AirPlay 端点             |
| 入网   | WiFi             | 用 iAP2 拿到的凭据自动加入车机热点；mDNS 发现 AirPlay 服务          |
| 建立会话 | AirPlay          | PairVerify → SETUP（拿到车机视图尺寸）→ RECORD             |
| 视频   | AirPlay 视频流      | 把 CarLife 的 H.264 帧原样封装推流；`VideoConfig` 声明车机画布尺寸 |
| 音频   | AirPlay 音频流      | CarLife PCM → RTP 封装                             |
| 触控   | iAP2 HID         | 车机触摸回传到手机，经 CarLife 通道注入                         |
| 媒体信息 | iAP2 NowPlaying  | 歌名 / 歌手 / 歌词回传给车机                                |

### CarLife 劫持的协议参数（98386 / 98311）

本 App 冒充车机连上本机 CarLife 引擎后，主动下发两条关键配置：

**98386 `HU_FEATURE_CONFIG_RESPONSE`**

| 字段                          | 值       | 作用                                                                      |
| --------------------------- | ------- | ----------------------------------------------------------------------- |
| `CONNECT_TYPE`              | 5（WiFi） | 连接类型                                                                    |
| `I_FRAME_INTERVAL`          | 20      | I 帧间隔                                                                   |
| `DISABLE_RESOLUTION_OPTION` | **1**   | 让引擎 `useVehicleResolution=true`，按 98311 上报的车机真实分辨率编码，不再按宽度分档压成 1280×720 |
| `MEDIA_LYRIC_SUPPORT`       | **1**   | 开启歌词支持（车机侧显示歌名歌词）                                                       |

**98311 `CarlifeVideoEncoderInfo`**

| 字段                    | 值              | 说明                   |
| --------------------- | -------------- | -------------------- |
| 1 / 2 / 3             | 车机视图宽 / 高 / 帧率 | 来自 AirPlay SETUP，非写死 |
| 9 `needAdapterScreen` | **true**       | 比例不一致时请引擎等比适配，而不是硬拉伸 |

### 三个必须知道的技术事实（实测结论）

1. **画面源是荣耀车联创建的虚拟屏，不是普通屏幕采集。**  
   走荣耀车联流程时系统会创建 `HonorAutoDisplay`：`1920×1080 @ 20fps`，`type=VIRTUAL`，  
   `FLAG_PRESENTATION | FLAG_OWN_CONTENT_ONLY`。CarLife 引擎只是编码这块屏。  
   → **分辨率和帧率都由荣耀侧决定，CarLife 协议改不动。**  
   → 但**帧率可以在手机端「荣耀车联」里改**：默认档就是虚拟屏的 `renderFrameRate 20`，  
   改成 30 / 60 帧后虚拟屏按新帧率出图，引擎随即吐 30 / 60 帧（见下方「帧率拉到 60」）。
2. **厂商适配层会覆盖编码尺寸。**  
   引擎把 98311 的尺寸交给 `MixActionDispatcher("carlife_encode_format")`（荣耀 mix 模块），  
   厂商层返回非 null 时覆盖 `MediaFormat` 宽高。实测请求 1412×654 时 SPS 仍可能回到 1280×720。  
   → 因此 `VideoConfig` 的声明**以车机画布为准**（`liveSize` 实时读 98311），  
   SPS 解析值只作诊断与兜底，绝不反过来改写声明（历史上这么做过，导致真车黑屏）。
3. **引擎不能由 App 自动拉起。**  
   自动拉起（`bindService` / `startForegroundService` / `startActivity`）确实能让引擎监听 7240  
   并被成功劫持，但**拿不到荣耀的画面源**——引擎只会把「荣耀车联 Powered by MagicOS」欢迎页  
   编码成恒定 ~10KB 的静止帧反复发送，车机表现为「连接成功但长时间没画面」。  
   → 所以 `0.2.14` 起**移除自动拉起**，改回用户手动打开 CarLife+ 点「无线连接」。

---

## 三、使用说明

### 前置条件

- 车机支持**无线 CarPlay**（有线不适用）。
- 手机已安装 **CarLife+**（荣耀机型为系统内置的「荣耀车联」CarLife 通道）。
- 手机蓝牙开启，且**已与车机完成系统级蓝牙配对**（不是 App 内扫描配对，是系统设置里的配对）。
- 首次使用需授予若干权限（见下方「权限清单」）。

### 首次配置（只做一次）

1. 安装并打开 **CarPToCarL**，阅读并同意免责声明。
2. 按提示授予权限：
   - **附近设备 / 蓝牙**：扫描与配对车机
   - **通知使用权**：读取音乐会话（歌名、歌手、歌词、播放状态联动）
   - **通知权限**：前台保活服务的常驻通知
3. 在「连接准备」里选择目标车机（可选）。不指定时会依次尝试全部已配对设备。
4. 如车机蓝牙地址不固定，可在 **MAC** 输入框手填并保存。
5. 「启动后自动连接」**默认开启**：打开 App 即自动发起连接，不用每次点「启动连接」。  
   想在连之前先改「下次连接起效」的项（分辨率缩放、CarLife 劫持开关）时，
   把「连接准备」里这个开关关掉即可。

### 每次使用（重要：顺序）

```
① 手机与车机蓝牙配对（系统蓝牙里完成）
        ↓
② 打开「荣耀车联 / CarLife+」→ 点【无线 WLAN 连接】
        ↓
③ 停在第二步「正在连接」界面（不要退出、不要返回）
        ↓
④ 回到 CarPToCarL → 点【启动连接】
        ↓
⑤ App 自动加入车机热点 → 建立 CarPlay 会话 → 画面出现在车机
```

> **第 ③ 步是关键**：必须让 CarLife+ 真的进入「正在连接」状态。  
> 此时引擎才启动、才创建虚拟屏、才开始监听 7240。App 检测到端口一开立即接管。
>
> 顺序不是绝对强制的——App 一启动就常驻守候，先点「启动连接」再开 CarLife+ 也可以。  
> 但推荐按上面的顺序，因为「正在连接」有超时，先让 App 就位更稳。
>
> 画面出来后再花 10 秒做两件事（都在**同一页**）：帧率选 **60 帧**、分辨率保持 **「默认」**。  
> 只改帧率没风险；**分辨率别动**，动了会画面不一致——详见下一节。

### 帧率拉到 60（连上之后做一次）

默认画面只有 **20fps**，这是荣耀车联虚拟屏的默认档，不是本 App 的节流。  
在**手机端**改一次就好（车机画面已经出来之后再改也能生效）：

```
手机打开「荣耀车联 / CarLife+」 → 设置 → 投屏显示设置 → 帧率 → 选「60 帧」
```

- 同一页还有「默认 / 30 帧 / 60 帧」三档，**默认 = 20fps**（本文档多处提到的 20fps 来源）。
- 页面上那行「可能存在兼容性问题，请谨慎使用」是真的：60 帧对车机解码能力有要求，  
  部分车型会出现**卡顿、花屏、绿屏、拉伸**，出现就退回 30 帧或「默认」。
- 页面提示分辨率类改动「下次连接车机时生效」；帧率改完若没变化，断开重连一次即可。
- 改完在日志里能对账：`CARLIFE: 实测帧率 59.x fps（5s 内 xxx 帧，声明 60）`。

App 侧不需要再改什么：`carlife_fps` 默认就是 **60**，连接建立 800ms 后还会发一次  
`98316 VIDEO_ENCODER_FRAME_RATE_CHANGE`（引擎侧 clamp 3..60）把编码节拍对齐到 16ms。  
也就是说**两边都得到 60 才顺**：车联设置给画面源 60，App 申请值让编码器按 60 输出节拍。  
只改一边仍会被另一边卡在低帧。

> ⚠️ **改完帧率必须回到同一页，核对上面的「分辨率」档有没有跟着变。**
>
> 「帧率」和「分辨率」在同一张「投屏显示设置」页里，改一项时很容易连带把另一项动掉。  
> 分辨率决定荣耀虚拟屏的**尺寸**，也就是本 App 拿到的画面源尺寸；而车机是按**自己真实  
> 分辨率**建解码器和 surface 的（`VideoConfig` 声明也跟随车机画布）。两者比例一旦不一致，  
> 就会**画面不一致**：左右黑边、画面只占中间一块、或被横向拉扁——这和帧率高低无关，  
> 只是看起来像「设置改坏了」。
>
> - 正确状态：分辨率停&#x5728;**「默认（按当前车机的最佳分辨率显示）」**，不要为了「更清晰」手动挑高档。
> - 分辨率档的改动页面自己注明「**下次连接车机时生效**」——所以改完要断开重连一次，  
>   否则会出现「手机设置是 A、车机按旧的 B 在显示」的错觉。
> - 帧率可以随时改，但**改帧率顺手看一眼分辨率**，是这一页最容易踩的坑。

### 界面上的状态提示

主界面状态卡片**上方**有一行 CarLife 状态文字，直接告诉你现在卡在哪：

| 显示                             | 含义           | 该做什么                  |
| ------------------------------ | ------------ | --------------------- |
| 等待你手动打开 CarLife+ 并点「无线连接」      | 没检测到 7240 监听 | 去做上面的第 ②③ 步（点这行可重启守候） |
| 已劫持 CarLife+ 画面 —— 点「启动连接」推到车机 | 码流已接到，还没推给车机 | 点「启动连接」               |
| 车机画面来自 CarLife+（直通中）           | 正在推流         | 无需操作                  |
| CarLife 劫持已在设置里关闭 —— 不会有车机画面   | 开关被关了        | 打开「本地 CarLife 劫持」开关   |

### 断开

- 点【断开连接】：停止推流并关闭保活服务，车机画面立即中断。
- 投屏授权会随服务停止失效，下次若要车机侧出声需重新授权。

### 权限清单

| 权限                                     | 用途                                      | 必需      |
| -------------------------------------- | --------------------------------------- | ------- |
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` | iAP2 控制通道、扫描配对车机                        | ✅       |
| `NEARBY_WIFI_DEVICES`（12L 及以下还需定位）     | 用 `WifiNetworkSpecifier` 加入车机热点         | ✅       |
| `RECORD_AUDIO`                         | `AudioPlaybackCapture` 捕获本机媒体/导航声音      | ✅（要声音时） |
| `POST_NOTIFICATIONS`                   | 前台服务常驻通知                                | ✅       |
| 通知使用权（特殊权限）                            | 读取媒体会话：歌名/歌手/歌词/播放状态                    | 要歌词就必需  |
| `SYSTEM_ALERT_WINDOW`                  | 暗幕（替代息屏）                                | ❌ 可选    |

前四项里 `BLUETOOTH_CONNECT`、`NEARBY_WIFI_DEVICES`（Android 12/12L 的定位）属于**同一个权限组**，
首次连接会合并成**一个**「要与 CarPToCarL 配合的设备」系统框——这是 Android 的系统级授权，
标题里带应用名，只能由你点一次「允许」，普通应用无法跳过或隐藏（1.0.7 也一样）。
点过之后只要不拒绝，之后每次连接都不再弹。

---

## 四、已实现能力

| 能力               | 状态    | 说明                                                                                   |
| ---------------- | ----- | ------------------------------------------------------------------------------------ |
| 无线 iAP2 发送端会话    | ✅     | 走通至 0x4301，拿到热点凭据与 AirPlay 端点                                                        |
| AirPlay 主会话      | ✅     | PairVerify + H.264 推流 + HID 触摸回传                                                     |
| 真车验证（奥迪 MIB3）    | ✅     | 2026-10-11 全通：视频上屏 + 触摸可交互                                                           |
| CarLife 画面直通     | ✅     | 劫持本机引擎码流，不重编码                                                                        |
| CarLife 音频直通     | ✅     | **两条通道都接**：媒体（`9240`，`0x3000x`）+ TTS/导航播报（`9241`，`0x4xxxx`）→ PCM → RTP；媒体通道支持播放/暂停联动，TTS 不参与该联动 |
| 歌名 / 歌手 / 歌词     | ✅     | 98386 `MEDIA_LYRIC_SUPPORT=1` + iAP2 NowPlaying                                      |
| 车机分辨率自适应         | ⚠️    | 98311 上报车机真实视图 + `needAdapterScreen=true`；**但受厂商 mix 层限制，实际码流可能仍为 1280×720 或 1080p** |
| 触摸回传             | ✅     | 车机触摸 → CarLife 通道注入；坐标以 **SPS 真实分辨率**为基准，触摸异步队列不积压                                   |
| 双指触控（缩放）      | 🧪     | 1.0.7 起双指手势改走复合动作通道 `0x68001`（动作码带指针索引），单指仍走单点通道；详见 [docs/touch-multitouch.md](docs/touch-multitouch.md) |
| 启动后自动连接       | ✅     | 打开 App 即自动发起连接，**默认开启**；「连接准备」里可关掉改为手动。2026-10-25 荣耀 PTP-AN10 冷启动实测：无人操作即可自动建会话并推流 |
| 目标帧率跟随车机     | ✅     | 车机 `/info displays[0].maxFPS` 报了就按它出帧（夹在 24–120），没报按 60；判定见 [app/src/main/java/io/github/shaonialife/carptocar/video/StreamFps.kt](app/src/main/java/io/github/shaonialife/carptocar/video/StreamFps.kt) |
| 60fps 投屏         | ✅     | 手机端「荣耀车联 → 设置 → 投屏显示设置 → 帧率 = 60」；默认档为 20fps                                         |
| 自绘隐形桌面（launcher） | 保留    | 早期主路径，现非默认；画面唯一来源已是 CarLife 劫持                                                       |


### 已知限制

1. **默认帧率 20fps（可自行改到 60）**：默认值来自荣耀车联虚拟屏的 `renderFrameRate 20.0`，  
   不是本 App 的节流，CarLife 协议也改不动。但**手机端「荣耀车联 → 设置 → 投屏显示设置 → 帧率」  
   可以选 30 / 60 帧**，实测选 60 后日志实测帧率能到 ~60fps。60 帧可能触发部分车机卡顿/花屏，  
   出问题退回 30 帧或「默认」。  
   → App 侧目标帧率**跟随车机**：`/info displays[0].maxFPS` 有值就用它，没声明按 60。
   车机报 30 就别跟它拗——发 60 只会让车机丢帧、解码队列堆积。日志里能对账：
   `DISPLAY: 车机 1920×720（/info 通告）→ 流 …@30fps（跟随车机 maxFPS=30）`。
2. **分辨率不可完全自主**：厂商 mix 层可能把编码尺寸改回 1280×720 / 1080p。当前策略是  
   「声明跟车机画布一致」，尽量不出现变形；比例差异大的车机（如 21:9）可能仍有黑边或轻微拉伸。  
   → 另外**车联「投屏显示设置 → 分辨率」必须停在「默认」**，手动改档会让虚拟屏尺寸  
   偏离车机画布，直接表现为画面不一致（见第五节「画面拉伸 / 变形 / 比例不一致」）。
3. **必须手动进 CarLife+**：自动拉起拿不到画面源（见第二节事实 3），这是设计取舍，不是 bug。
4. **依赖 CarLife+ 版本行为**：逆向结论基于荣耀 8.8.5（versionCode 274），升级后协议可能变化。

---

## 五、故障排查

### 画面只有 20fps / 滑动发涩不跟手

**先改车联设置，不要怀疑 App**：手机端「荣耀车联 / CarLife+ → 设置 → 投屏显示设置 → 帧率 → 60 帧」。  
默认档是 20fps（虚拟屏 `renderFrameRate 20.0` 决定），改完日志应出现  
`CARLIFE: 实测帧率 59.x fps`。车机跟不上（卡顿/花屏/绿屏）就退 30 帧。

若已选 60 帧仍只有 20 帧，再查这三处：

1. 改完没重连——部分设置项注明「下次连接车机时生效」，断开重连一次。
2. `requestedFps` 被偏好压低了：检查 `carlife_fps`（默认 60），日志里 `MD_VIDEO_ENC_INIT` 的 fps 字段应是 60。
3. 车机侧解码跟不上会主动丢帧，表现为「声明 60、实测 40 上下」，属车机能力上限，退回 30 帧更稳。

**改帧率后如果画面「不一致」（黑边 / 只占一块 / 拉扁），那基本不是帧率造成的**，  
而是同一页的「分辨率」档被动过：回去把它调&#x56DE;**「默认（按车机最佳分辨率显示）」**，  
再断开重连一次（分辨率类改动下次连接才生效）。对账方法见下面「画面拉伸 / 变形」。

### 「连接成功但长时间没画面」

按可能性排序：

1. **CarLife+ 没停在「正在连接」** —— 最常见。引擎没启动或没创建虚拟屏，  
   `netstat -tln | grep 7240` 无监听。重做「每次使用」第 ②③ 步。
2. **引擎被别的东西拉起来了** —— 如果引擎是被 App 或第三方直接拉起（没有荣耀虚拟屏），  
   它只会编码欢迎页。执行 `am force-stop com.baidu.carlife.honor`，再走正式流程。
3. **`VideoConfig` 声明与码流不一致** —— 车机按声明建解码器，尺寸不符就解不出画面。  
   正确状态是日志里 `DISPLAY: CarLife 直通流 WxH` 与 `VIDEO: VideoConfig 声明` 一致。

### 触摸迟钝 / 拖不动 / 点不准

按 2026-10-25 实测日志 + 引擎字节码定位到 6 个原因，均已修：

1. **触摸命令 ID 全错位（v0.2.19 修正，这才是「能点但拖不动」的真因）**。  
   HU 端官方 SDK（`.tools/carlife-ref` 的 `CarLife-Android-Vehicle`）定义：
   ```
   0x68001 MSG_TOUCH_ACTION        复合动作（CarlifeTouchAction）
   0x68002 MSG_TOUCH_ACTION_DOWN   单点
   0x68003 MSG_TOUCH_ACTION_UP     单点
   0x68004 MSG_TOUCH_ACTION_MOVE   单点
   0x68005 SINGLE_CLICK · 0x68006 DOUBLE_CLICK · 0x68007 LONG_PRESS · 0x68008 硬按键
   ```
   旧代码按「0x68001 DOWN / 0x68002 MOVE / 0x68003 UP」连号推断，全错了一格：  
   实际发出的序列是 `0x68001(复合动作，按下) → 0x68002(=抬起！) → …`。  
   手指刚动第一下，引擎就收到「抬起」，手势立刻结束 —— 表现正是  
   **点得准、能点开应用，但拖拽完全不动、列表不跟手**。  
   坐标映射一直是对的，别再动缩放。
2. **坐标被缩放了两次（v0.2.17 修正）**。引擎 `CarlifeTouchManager.dispatchTouchEvent`  
   拿到 x/y 后会自己乘 `getTouchXOffset()`：mix 连接下是 `CastConfig.width / mContainerWidth`，  
   普通投屏下是 `手机屏宽 / mContainerWidth`——**输入域就是车机 display 坐标，换算由引擎做**。  
   v0.2.15 拿码流 SPS 分辨率当基准、v0.2.16 又改成 `x × 视频宽 / 视图宽`，  
   本质都是替引擎做了那次换算：码流 1280×720 配车机 1412×654 时横竖向各缩 10%，  
   拖拽位移同比变小、落点偏移。现在**坐标原样透传**，只做 `0..视图宽高` 的边界钳位。  
   顺带把发送层的 `coerceIn(0, videoSize-1)` 也去掉了——它会把右侧 132 像素压成同一个点。
3. **UP 丢了会让整块屏幕永久卡死（v0.2.16 修一半，v0.2.17 补全）**。  
   引擎 `onActionDownEvent` 的反汇编逻辑是：
   ```java
   if (isErrorTouchModel()) { if (eventIng) { errorDown = (x,y); return; } }  // DOWN 被丢弃
   eventIng = true;
   ```
   只要某一笔的 UP 没送到，`eventIng` 永远是 `true`，之后每一次 DOWN 都被丢弃；  
   而 MOVE 又会被 `isErrorEvent` 判成「更靠近上次的错误按下点」而 return。  
   表现是：点一下（或拖一下）之后，整块屏幕再也点不动、拖不动，像死机。  
   v0.2.16 修了「(0,0) 空槽过滤把 UP 吞掉」，但车机把鼠标拖出控件/窗口时仍可能不发 UP，  
   所以 v0.2.17 加了兜底：**只要与上一笔间隔超过 220ms 又收到按下，先补一个 UP 再开新笔**  
   （日志 `CARLIFE: 上一笔缺抬起 XXXXms → 补发 UP`）。
4. **事件积压（v0.2.15 已修）**。引擎消费触摸是串行的，慢时一条要几百毫秒，  
   直接写 socket 会把车机端回调线程一起拖住。现在是**单线程队列**：  
   DOWN/UP 保序不丢，MOVE 只保留最新坐标。
5. **本地节流去掉了（v0.2.16）**。引擎自身对 MOVE 有 10ms 限频（字节码 `const-wide/16 v4, 10`），  
   本地再叠加 16ms 只会让拖拽变成跳跃采样。
6. **引擎侧响应慢（改不了）**。日志实测点击到画面反应 0.7~3.1s，平均帧只有 4KB，  
   说明手机端 CarLife 引擎本身处理就慢（它同时在跑 1080p 编码）。

自检：拖一次，日志应出现 `CARLIFE: 手势结束 N次移动 时长Xms 位移Ypx ↓(x,y) → UP(x,y)`  
（N ≥ 3、位移和手指移动距离一致），且不应出现「补发 UP」。  
出现「补发 UP」说明车机端漏发抬起，那不是本 App 的问题，但补发能保证不死锁。

### 一直连不上 / 车机侧「正在连接」不结束（读不到本机蓝牙 MAC）

现象：App 日志出现 `BT: ⚠ 未能解析真实蓝牙 MAC（仍是占位值）`，RFCOMM 明明连着，
但 CSM 层再也没有回应，**永远等不到 `0x4301`**；或 WinPlay 类接收端在 SETUP 阶段直接回 `403`。

原因：iAP2 的 `0x4300 bluetoothTransportIdentifier`、`0x4E0E`、AirPlay SETUP 的设备过滤、
以及 `_carplay-ctrl._tcp` 广播的 TXT id，**都要求本机真实蓝牙 MAC**；
而 Android 6+ 起应用层拿不到它。车机校验不一致时**不会报错**——链路层照常 ACK，
CSM 层静默丢弃，看起来就是「一直连不上」。

App 的解析顺序（`util/DeviceIdentity.kt`）：手填 → sysfs → `Settings.Secure` → adapter 反射 → shell。

**未填写 = 不能启动连接。** 手填为空、且自动解析也拿不到真实地址时，「启动连接」会被门禁
直接拦下：按钮置灰、点击弹「本机蓝牙地址未填写」并跳到输入框，输入框下方同时显示红色提示；
「启动后自动连接」走的是同一条链路，同样被拦。理由很直接——没有地址连上去也只会换来
SETUP 403 / CSM 静默丢弃，不如当场把原因说清楚。

| 来源 | 普通 App 能否读到 |
|---|---|
| **手填**（主界面输入框 +「保存」） | ✅ 永远可用，优先级最高 |
| `/sys/class/bluetooth/hci0/address` | 多数机型可读（world-readable）；部分 ROM 被 SELinux 拦 |
| `Settings.Secure` 的 `bluetooth_address` | ❌ 需要 `BLUETOOTH_PRIVILEGED`（只有系统应用有） |
| `BluetoothAdapter.getAddress()` | ❌ Android 6+ 恒返回 `02:00:00:00:00:00` |

**最可靠的办法是从对端取。** 蓝牙经典（BR/EDR）的 inquiry 响应带的是设备真实 BD_ADDR，
不像 BLE 那样用随机地址，也不经过手机 framework——**厂商的脱敏管不到它**：

- **车机**：已配对设备列表里显示的那个地址，就是车机眼里的你（最权威，iAP2 校验的就是它）
- **另一台手机**：装 `nRF Connect` 之类扫描器，让本机停在蓝牙设置页（保持可被发现）扫一下
- **电脑侧 adb**：`adb shell cat /sys/class/bluetooth/hci0/address`
  （`adb shell dumpsys bluetooth_manager | grep -i address` 也能试，但部分 ROM 会打码）

拿到后填进 App 主界面输入框点「保存」，日志会打
`MAC: 本机蓝牙地址已设为 AA:BB:CC:DD:EE:FF（0x4300/SETUP/ctrl 广播都用它）`。

> **能不能把它改成系统应用？** 零售机上基本不行，而且多半白费：
>
> - `pm install` 永远只装到 `/data`；进 `/system/priv-app` 需要 root 或可写 system 分区
>   （即 BL 解锁 + Magisk 模块 / 改 ROM）。零售机 `adb root` 会直接被拒
>   （`adbd cannot run as root in production builds`）。
> - 即使塞进 priv-app，Android 9+ 还要求把 `BLUETOOTH_PRIVILEGED` 写进
>   `/system/etc/permissions/privapp-permissions-*.xml` 白名单，否则开机不授予。
> - 更关键：priv-app **只有用平台签名才会切到 `platform_app` 的 SELinux 域**。用作者自己的签名，
>   域仍是 `untrusted_app`，读 sysfs、开 raw HCI socket 一样被拒。
> - 而且部分机型（含荣耀 MagicOS）的 MAC 在 framework 写入之前就已打码
>   （连 `dumpsys` 都是 `XX:XX:XX:XX:xx:xx`），**权限再高也读不到真值**。
>
> 为读一个地址去刷机不划算——用上面的「从对端取 + 手填」即可。

### 连接很慢（点了「启动连接」半天没反应）

主要卡在**蓝牙 iAP2 出站**这一步：

- 如果「连接准备」里指定了目标车机，旧版会**死等目标**：出站只试它、入站把其它设备全拒掉。  
  坐进另一台车（或换测试台）时表现为四十多秒毫无反应。
- v0.2.16 起目标连续 2 轮（约 6 秒）连不上就自动**降级为优先**：出站回退到全部已配对设备、  
  入站不再拒绝，日志会打印 `BT-C: 目标「X」连续 N 轮连不上——本轮放开目标限制…`。  
  每次点「启动连接」都会重新给目标一个窗口，真车场景行为不变。
- 还有两件事只能等系统：首次用 `WifiNetworkSpecifier` 加入车机热点会弹一次确认框；  
  车机热点本身可能没有互联网，不要手动到系统 WLAN 里连它（会让手机以为断网）。

### 连上后过一会儿自己断

按 2026-10-25 实测：**音频还在推、视频 0 帧超过约 30 秒，车机会判定「源没了」并拆整轮会话**  
（日志 `VIDEO: 直通 keepAlive 中止 - Broken pipe` / `EVENT: 通道关闭（EOF）`）。  
常见原因是 CarLife+ 那边还没进「无线连接」、或在虚拟屏上没内容可编码。

v0.2.16 起直通模式在**空闲 1.5 秒**后每 2 秒重发**最后一个 IDR**，撑住车机的存活判定  
（重发 IDR 而不是最后那帧 P 帧：IDR 自包含，重复解码只是同一幅画面；P 帧重复会花屏）。  
日志标志：`VIDEO: 已 Ns 没有新画面——重发最后一个 IDR 维持车机连接`。

注意这只维持连接，不会凭空产生新画面。真要出画面仍需在手机上走一次  
「打开 CarLife+ → 无线连接 → 停在正在连接」。

**v0.2.18 修掉的两个真断链原因**（WinPlay 1.2.4 双向日志对照确认）：

1. **视频帧被两条线程同时写同一条 socket**。直通模式下 CarLife 帧线程和屏保活线程  
   （keepAlive + 空闲重发 IDR）都往视频数据口写，`write(header)/write(body)` 交错后  
   接收端拿到的是一帧半 A 半 B 的乱码，ChaCha20-Poly1305 校验必然失败，于是  
   `视频帧认证失败 → 视频通道正在重新连接 → 8s 后恢复超时 → 整轮会话关闭`。  
   现在所有写点都在同一把锁内，且「构造头 + 加密 + 写字节 + 帧计数自增」整体串行  
   （只锁写字节仍会让计数器与到达顺序错位）。
2. **视频通道被重置后不重连**。接收端 reset 视频通道后会留着同一端口等手机连回来，  
   8 秒等不到才关整轮会话；旧实现收到 reset 直接 `stop()`，等于每次解密失败都升级成  
   「整轮断开、要重新点启动连接」。现在会重连数据口（3 次 × 2s），并归零帧计数、  
   重发 VideoConfig（新通道 = 新流，AEAD 计数器由双方各自按「本流第几帧」推算）。

### 车机不停重连 / 「一直在连接」循环

2026-10-25 WinPlay 1.2.4 双向日志对照的结论：**0x5001 NowPlayingUpdate 被双重封包**，  
接收端解析到非法参数组后「接收进程异常」退出 → 蓝牙桥接被拆 → 车机重连 → 再崩，形成死循环。

证据链：失败轮次接收端只收到 **717 字节**（成功轮次 581），差值 136 正好等于一条  
0x5001 的链路净荷；对端记录的结束阶段就是 `接收进程异常`，且 `controlConnections=0`  
（连 WiFi 控制通道都没来得及建）。

原因：`Iap2NowPlaying.encode()` 返回的是**完整 CSM 帧**（含 `0x4040 | len | msgId`），  
而 `send()/sendFireAndForget()` 会自己补帧头，把它当体再封一层。走隧道  
（`VirtualIap2Link.sendRawCsmLocked`）时传完整帧是对的，走蓝牙链路必须传纯参数体。  
现在拆成 `encode()`（完整帧）与 `body()`（纯参数体），蓝牙侧两处调用改用后者。

排查提示：如果以后又在某台车机上出现「连上就断、反复重连」，先看对端收到的字节数是否  
比正常轮次多出「某条消息的净荷大小」——多出来的那条就是嫌疑。

### 画面拉伸 / 变形 / 比例不一致

先说结论：**这条链路里有两处「尺寸」必须同时成立，任何一处不齐都会画面不一致。**

```
① 荣耀车联「投屏显示设置 → 分辨率」  →  决定虚拟屏尺寸 = 本 App 拿到的画面源尺寸
② 车机自己上报的画布（AirPlay SETUP）  →  决定车机解码器/surface，也是 VideoConfig 的声明基准
```

按顺序查：

1. **先看车联那一页的分辨率档**（和帧率同一页）。选「默认（按当前车机的最佳分辨率显示）」  
   才是对的；为了「更清晰」手动挑高档，会让虚拟屏尺寸 ≠ 车机画布 → 黑边 / 只占一块 / 拉扁。  
   而且**分辨率类改动下次连接才生效**，改完必须断开重连，否则看到的是上一次的档位。  
   （2026-10-25 用户实测补充：改完帧率常会连带把这一项动掉，务必回看一眼。）
2. **再看 App 日志三方对账**，正常应基本一致：
   ```
   CARLIFE: 主动推送 98311 视频编码初始化 1412x654@60       ← 向引擎申请的编码档
   DISPLAY: CarLife 直通流 1412x654                        ← 实际拿到的码流尺寸
   VIDEO: SPS 解析 1920x1080（宏块对齐值，仅诊断），VideoConfig 声明 1412x654
   CARLIFE: 实测帧率 59.x fps（5s 内 xxx 帧，声明 60）
   ```
   - 只有 `SPS 解析` 与 `VideoConfig 声明` 不同是**正常**的（SPS 是宏块对齐的实际编码尺寸，  
     声明必须跟车机画布，历史上拿 SPS 反改声明导致过真车黑屏）。
   - `DISPLAY` 与 `98311` 不一致、或车机画布比例和它们都差很远 → 就是本节要处理的问题。
3. 车机请求的视图比例（如 1412×654 ≈ 2.16:1）与码流比例（16:9）差很多时会变形；  
   已下发 `needAdapterScreen=true` 请求等比适配（宁可黑边不要拉伸），但厂商 mix 层不一定采纳。
4. 仍不行的缓解手段：车机侧把显示比例调成接近 16:9；或在车联设置的同一页里换一个  
   与车机更接近的分辨率档，然后再核对第 2 步那三行日志。

### 蓝牙连上就断（电脑 / WinPlay 模拟器）

现象：手机和电脑（WinPlay）蓝牙刚连上，一两秒后就断；但车机画面、声音、触摸全都正常。

**这不是故障，是无线 CarPlay 的正常交接。** 时序（2026-10-25 实测日志）：

```
20:07:31.05  BT: 接收端已连入 name=DESKTOP-XXXXXXX，开始 iAP2 发送端会话
20:07:31.27  iap2 rx=0x4301 ... Phase 1 完成（拿到热点凭据 + AirPlay 端点）
20:07:32.42  TUNNEL: ✓ iAP 隧道已建立 192.168.137.1:8827
20:07:32.45  IAPLINK: 链路状态 → NORMAL
20:07:32.52  LINK closed: bt socket closed, read return: -1   ← 对端（电脑）主动 close
20:07:32.52  SESSION 结束——接收端已断开 RFCOMM，iAP2 已迁到 WiFi 隧道
```

iAP2 的识别 / MFi 认证 / CSM 订阅（0x1d00、0xaa00、0x5000、0x4157…）在隧道建立后
**全部改走 WiFi 的 AirPlay 控制通道**（日志前缀 `IAPLINK:`），蓝牙只剩「带路」作用，
接收端于是把 RFCOMM 关掉，避免两条控制通道并存。

判据看两条日志：

| 日志 | 含义 |
|---|---|
| `SESSION 结束——接收端已断开 RFCOMM，iAP2 已迁到 WiFi 隧道` | 正常交接（`BtSessionGate.sessionActive=true`），继续推流 |
| `SESSION 结束，等待接收端重连（BT 监听仍在运行）` + 后面没有 `IAPLINK:` | 端点还没拿到就断了，才是真故障 |

对照：**真车奥迪 MIB3 不会断 BT**（19:44:31 连上后一直保持到用户手动断开），
因为车机还要用 BT 传 0x6800/0x6802 媒体键与电话音频。所以「BT 断不断」取决于接收端策略，
与 App 无关；App 侧 `retireSession()` 明确**不**主动拆 BT（拆了会让 DiPlay 类接收端直接判
bring-up failed）。

### 车机上没有歌名歌词

按这三条依次查（2026-10-25 全部实测踩过）：

1. **`dumpsys notification` 的 `Live notification listeners` 里有没有本应用**。  
   重装 APK 后常见：授权还在 `enabled_notification_listeners` 里，但系统没把监听器  
   绑上 → `MediaSession.metadata` 恒为 null → 车机整程空白。  
   v0.2.19 起应用自己会 `requestRebind` 试着恢复；**荣耀 MagicOS 上该 API 不生效**，  
   日志会打出提示，此时到「设置 → 通知使用权」把本应用**关掉再打开**一次即可。  
   （开发机上等价操作：`adb shell cmd notification disallow_listener <包>/<服务>`  
   然后 `allow_listener` 同一个组件。）
2. **看日志有没有 `iap2 0x5000（订阅）暂无媒体信息——1500ms 后重试`**。  
   接收端只在 bring-up 后订阅一次 0x5000，而媒体信息（尤其冷启动/重装后）要几秒才就绪。  
   v0.2.19 起按 1.5s/4s/8s/15s/25s 退避重试，拿到信息就发 0x5001；  
   旧版 provider 返回 null 时直接静默放弃，一条都不发。
3. 检查 98386 是否包含 `MEDIA_LYRIC_SUPPORT=1`，并确认音乐 App 正在播放且媒体会话可见。

### App 显示「直通中」但车机没画面

- 看日志 `★ 收到 CarLife 首帧` 的字节数：恒定 ~10KB 且全是关键帧 = 引擎在送欢迎页（见上「没画面」）。
- 正常应是几十 KB 的关键帧 + 几百字节到几 KB 的 P 帧交替。

### CarPlay 没断，但 CarLife 重连后车机一直黑屏

典型场景：车机画面还连着（CarPlay 会话保持），手机端 CarLife 掉线 / 你手动重开一次
「无线连接」，之后车机再也收不到画面。

**根因（v1.0.1 修）**：视频通道的会话级状态没跟源一起重置——`configSent` 还是 true，
新码流的 **VideoConfig 永远不再发**；车机手里拿着上一轮的 avcC 与新 SPS 对不上就解不出图。
另外旧源的最后 IDR 会被空闲保活反复重发，新源开头的 P 帧又缺少正确参考帧。

修法（`VideoSender.onSourceRestart`）：
1. 新 CarLife 会话接管时清 `configSent`，下一帧重发 VideoConfig（声明尺寸取当时的协商值）；
2. **先等新 IDR** 再送帧，新源开头的 P 帧一律丢弃，避免花屏；
3. 保留旧的 `cachedCsd` 兜底：新 IDR 万一带不上 SPS/PPS，也能立刻发出一份 config；
4. 会话中途 SPS 变化（改分辨率 / 引擎重建编码器）同样触发重发；
5. 源重启后 4 秒仍零帧 → 自动请求引擎重建编码器（补 SPS+PPS+IDR）。

⚠️ **`frameCounter` 绝不能归零**：还是同一条 AirPlay 视频流、同一把 key，
计数器归零等于 nonce 复用，车机侧 AEAD 校验全失败。只有换整轮会话（新 SETUP）才允许归零。

**验证看这几行日志**：

```
VIDEO: 检测到 CarLife 源重启（CarLife 会话重建（引擎重连））——重置配置、等待新 IDR
VIDEO: 源重启后收到新 IDR——恢复送帧（重发 VideoConfig）
VIDEO: ✓ VideoConfig 已发送 avcC=… （SPS …/PPS …，… flags=0x06 WxH）
VIDEO: 帧 #N … 已发送
```

若只看到「等新 IDR」没有下文，说明引擎没再产 IDR——4 秒后应出现
`CARLIFE: 源重启后 4 秒零帧——请求引擎重建编码器`。

### 日志与诊断

主界面底部「日志」区可**复制 / 导出 / 清空**，还有「残留清理」用于清掉上次的脏状态。  
调试文件日志在应用私有目录 `files/main_debug.log`：

```bash
adb shell "run-as io.github.shaonialife.carptocar cat files/main_debug.log"
```

---

## 六、构建

> 本项目**固定使用 JDK 17**（`.tools/jdk-17.0.20.1+1`，Temurin 17），  
> `gradle.properties` 的 `org.gradle.java.home` 已指向它，**不要改**。  
> 系统默认 `JAVA_HOME` 若指向 JDK 8 会导致编译极慢甚至卡死。

```bash
# 推荐：wrapper 已改为本地发行版（不再访问 services.gradle.org）
./gradlew assembleDebug --offline
```

也可以直接调本地 Gradle：

```bash
./.tools/gradle-8.10.2/bin/gradle assembleDebug -I .tools/init-mirror.gradle
```

其它命令：

```bash
./gradlew :app:assembleRelease      # 发布包（1.0.0 起为正式版；需联网下载 lint-gradle，曾缺失时用 --offline 会失败）
./gradlew testDebugUnitTest         # 单元测试（120 个测试文件 / 926 例）
./gradlew :app:lintDebug            # lint 检查
```

- `.tools/` 是**项目内自包含的工具链**（JDK 17 + Android SDK 34 / build-tools 34.0.0 +
  Gradle 8.10.2 发行版 zip），不依赖任何外部目录，也不入库（`.gitignore`）。
  `gradle.properties` 的 `org.gradle.java.home`、`local.properties` 的 `sdk.dir`、
  `gradle-wrapper.properties` 的 `distributionUrl` 全部指向这个目录。
- `init-mirror.gradle` 把依赖源指向腾讯云镜像（`google()` / `mavenCentral()` 本机易超时）。
- 产物：debug → `app/build/outputs/apk/debug/app-debug.apk`；  
  release → `app/build/outputs/apk/release/app-release.apk`（归档到 `dist/`，`dist/` 不入版本库）。
- **不自己编译也行**：正式包在各版本的
  [Releases](https://github.com/jjuugk/CarPToCarL/releases) 页面直接下载，
  固定直链 <https://github.com/jjuugk/CarPToCarL/releases/latest/download/CarPToCarL-latest.apk> 始终指向最新版。

### 安装与调试

```bash
# adb 不在 PATH 时，项目内自带一份（.tools/platform-tools/）
./.tools/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
./.tools/platform-tools/adb.exe shell am start -n io.github.shaonialife.carptocar/.MainActivity
```

---

## 七、工程结构

单模块 Android 应用（`app/`）：主源码 **180** 个 Kotlin 文件，测试 **120** 个文件 / **926** 个用例。

### 代码是不是按协议分组？——主干是，但不是全部

| 分组 | 包 | 文件 | 依据 |
|---|---|---|---|
| 协议栈（**按协议**） | `carlife` / `iap2` / `airplay` / `protocol` | 67 | 三种协议各占一个独立包，**互不调用**；仅 `iap2` 共享 `carlife` 两个数据类型。`protocol` 是能力聚合层 |
| 承载层（按通道） | `bt` / `net` / `transport` | 30 | 蓝牙、网络、iAP2 隧道各管一条承载 |
| 数据面（按功能） | `audio` / `video` / `input` / `media` / `capture` / `render` | 35 | 跨协议复用，不绑定某一种协议 |
| 应用与基础设施（按功能） | 根入口 / `session` / `service` / `launcher` / `ui` / `util` / `legal` | 48 | 编排、保活、界面、工具 |

三处交叉：`net/` 放 AirPlay 服务发现（`AirPlayFeatures` / `CarPlayCtrlAdvertiser`）；
`transport/Iap2LinkChannel` 属于 iAP2 却独立成包；`protocol/` 不对应任何单一协议——它是
**能力聚合层**，只认 `Capability` 不认具体协议：跨协议交互经 `ProtocolRegistry` 接线
（`BIND` 源汇配对 / `FIRST_MATCH` 控制竞争 / `ALL` 状态广播），`CarPlayModule`、`CarLifeModule`
是纯委托门面（构造参数全是 lambda，不 import 任何协议实现），数据面靠 `video.VideoSink` /
`audio.AudioSink` 两个契约，`carlife` 不再依赖具体发送类。测试目录镜像同一套包名。

> **协议模块化的完整说明在 [docs/PROTOCOL-MODULES.md](docs/PROTOCOL-MODULES.md)**：分层图、包级
> 依赖实测、能力矩阵、媒体键时序、契约前后对比、十个提交演进、接第三种协议五步、已知缺口与
> 实车验证清单。该重构 2026-11-19 已快进并入 `main`（`be02fdc`，无 merge commit），旧 `main`
> 备份在 `backup/main-pre-protocol-20261119`。README 只保留上面这段速览，不重复正文。

文档三入口：[docs/README.md](docs/README.md)（docs 目录地图，按角色给阅读路径）·
[docs/PROJECT-GUIDE.md](docs/PROJECT-GUIDE.md)（43 篇专题文档的状态标注索引 + 排障决策表）·
[docs/PROJECT-STATUS.md](docs/PROJECT-STATUS.md)（项目体检报告：版本/代码/风险/未验证清单）。

### 根目录

```
CarPToCarL/
├── app/                          # 唯一的 Android 应用模块
├── docs/                         # 协议知识库与逆向结论（README 目录地图 / PROJECT-GUIDE 导航 / PROJECT-STATUS 体检 / archive 归档）
├── gradle/wrapper/               # wrapper（指向项目内 .tools/ 的发行版 zip）
├── .tools/                       # 自包含工具链：JDK17 / Android SDK34 / Gradle 8.10.2 / platform-tools
├── build.gradle.kts              # 根构建脚本
├── settings.gradle.kts           # 项目名 + 仓库/镜像源
├── gradle.properties             # JVM 参数 + org.gradle.java.home（固定 JDK 17）
├── keystore.properties.example   # 签名配置模板
├── .editorconfig                 # Kotlin/ktlint 代码风格
├── LICENSE                       # MIT + 授权范围说明（不授予第三方协议/专利）
├── NOTICE                        # 第三方组件、商标与构建期依赖声明
├── DISCLAIMER.md                 # 使用风险与驾驶安全
└── README.md                     # 本文档
```

### 主源码包结构（`app/src/main/java/io/github/shaonialife/carptocar/`）

```
├── MainActivity.kt               # 操作台 UI（连接 / 声音 / 暗幕 / 日志）
├── SessionController.kt          # 会话总管（进程级单例）：链路生命周期、配对、WiFi、取消
├── SenderApp.kt                  # Application（异常兜底、包变化失效缓存）
├── carlife/       (11)           # CarLife 劫持：HU 客户端 / 协议 / 音频 / 画质配置
├── iap2/          (22)           # iAP2 链路层 + CSM + 发送端会话 + NowPlaying
├── airplay/       (24)           # AirPlay 配对 / 控制通道 / 流协商 / 加密 / Timing
├── protocol/      (10)           # 能力聚合层（Registry / 门面 / 事件）+ 代际与一致性审计
├── bt/            (15)           # RFCOMM 双向（Server 等回连 / Client 主动连）
├── net/           (13)           # WiFi 入网 / mDNS 发现 / ctrl 广播
├── transport/      (2)           # iAP2 隧道封装（Iap2LinkChannel）
├── audio/         (12)           # AAC 编码 / 播放捕获 → RTP/ChaCha20（+ AudioSink 契约）
├── video/          (8)           # H.264 推流 / SPS 解析 / VideoConfig（+ VideoSink 契约）
├── input/          (8)           # 触摸分流 / 消费键 / CarInput
├── media/          (3)           # 通知使用权 → 媒体会话（+ 会话轮询兜底）
├── capture/        (2)           # 画面源
├── render/         (2)           # EGL 多屏合成 → 编码 Surface
├── session/        (5)           # 会话编排：预检 / 取消 / 权限修复 / 尝试门
├── service/        (5)           # 前台保活 / 冻结恢复 / 通知动作
├── launcher/      (15)           # 隐形屏启动器（保留，非默认画面来源）
│   └── settings/   (4)           #   设置面板（显示缩放、应用方向兼容）
├── ui/             (5)           # 暗幕、波形图、壁纸、深色弹窗、面板尺寸
├── legal/          (3)           # 免责声明
└── util/          (12)           # 后台任务池、日志导出、设备标识
```

### 各包职责速查

| 包 | 定位 | 关键内容 |
|---|---|---|
| `carlife` | CarLife 劫持 | 冒充车机连本机 CarLife+，H.264/PCM 直通，98386/98311 下发 |
| `iap2` | iAP2 协议链路 | Link 引擎、CSM、发送端会话状态机、NowPlaying |
| `airplay` | AirPlay 主会话 | PairVerify、控制通道、流协商、FairPlay/ChaCha 加密、Timing |
| `protocol` | 跨协议审计 / 聚合 | 能力路由装配点（`Registry` + 两个门面）、协议代际切换与一致性审计码 |
| `bt` / `net` / `transport` | 承载层 | RFCOMM Server/Client、WiFi 入网与 mDNS、iAP2 隧道 |
| `audio` / `video` | 媒体面 | AAC 编码与 RTP 封装、H.264 推流与 VideoConfig 对齐；同时提供 `AudioSink` / `VideoSink` 契约 |
| `input` / `media` / `capture` / `render` | 输入与画面 | 触摸分流、通知使用权与会话轮询兜底、画面源、EGL 合成 |
| `session` / `service` | 编排与保活 | 会话预检与取消、前台服务与冻结恢复 |
| `launcher` / `ui` / `legal` / `util` | 通用 | 隐形桌面（保留）、界面组件、免责声明、日志导出 |

### AIDL

```
app/src/main/aidl/io/github/shaonialife/carptocar/input/ICarInput.aidl
```

跨进程传输触摸/输入事件（`input/` 包下客户端通过 Binder 调用）。

### 单元测试

`app/src/test/java/io/github/shaonialife/carptocar/`，共 **120** 个测试文件、**926** 个用例，
目录与源码包一一对应，重点覆盖协议编解码与密码学：

- `iap2/`（23 文件 / 218 例）：编解码、Link 引擎、发送端会话
- `airplay/`（21 / 178）：PairVerify、BinaryPlist、控制通道加密、流帧加密、Timing
- `protocol/`（4 / 40）：能力路由投递语义（`BIND`/`FIRST_MATCH`/`ALL`、事件不回流产出方、`stopAll` 幂等）
- `carlife/`（10）、`audio/`（12）、`video/`（7）、`net/`（9）、`bt/`（7）：协议与数据面回归
- `input/` `launcher/` `session/` `ui/` `util/` `media/` 等：各自单元逻辑

> 早期版本依赖 **Shizuku**（虚拟屏注入 / 触摸注入），2026-10-24 起已**移除**；
> 代码中 `shizuku/` 目录与 `ShizukuGate` 均已删除，残留文字仅为历史注释。

---

## 八、代码质量（提交前自动拦截）

`git commit` 前 pre-commit 钩子自动执行两步，任一失败即阻止提交：

1. **格式化**：Spotless + ktlint（规则见 `.editorconfig`）
2. **Android Lint**：`lintDebug` 全量检查，error 级使提交失败

存量问题记录在 `app/lint-baseline.xml` 中豁免；新代码再引入同类问题仍会被拦截。

---

## 九、签名与发布

- **版本号**：`app/build.gradle.kts` 顶部 `appVersionName` 是唯一来源，`versionCode` 自动推导  
  （`1.0.3` → `10003`，`1.0.0` → `10000`；旧实验版 `0.2.19` → `219`，升级路径连续）。
- **签名**：release 默认退回 debug 签名；正式分发前配置真实签名：
  1. 复制 `keystore.properties.example` → `keystore.properties`（已 gitignore）
  2. `keytool -genkeypair -v -keystore carptocar-release.jks -alias carptocar -keyalg RSA -keysize 4096 -validity 10000`
  3. 填密码后 `assembleRelease`，用 `apksigner verify --print-certs` 确认证书 CN 非 `Android Debug`

> ⚠️ 私钥泄露 = 任何人可签发能覆盖安装的更新；私钥丢失 = 已装用户再也收不到更新。



---

## 十、边界、商标与合规

- **参考项目**：本项目的协议层与工程骨架参考了 [CarDeck](https://github.com/shaonialife/cardeck)
  （MIT，版权归其作者；**独立项目，与本项目非同作者**）。按其 MIT 许可，版权与许可声明已列在
  [NOTICE](NOTICE) 第 0 节，不附加其它条件；CarDeck 中 `reference/` 下的
  GPL-3.0 参考项目与本项目一样**未入库、未分发**。
- **许可证**：源码以 [MIT](LICENSE) 发布，正文之后附了一段**授权范围说明**：MIT 只授予
  本仓库代码的著作权，**不授予** Apple / 百度的专利、协议规范、MFi 认证或任何商标权利。
- **第三方组件**：AndroidX / Kotlin（Apache-2.0）、BouncyCastle（MIT 系）的版权声明见
  [NOTICE](NOTICE)；同一份文件（外加 LICENSE）已打包进 APK 的 `assets/`，
  随二进制一起分发。
- **风险梳理**：开源与「收费」各自的风险、以及商业化前的三件套（经营主体 / App 备案 /
  隐私政策），见 [docs/LEGAL-NOTES.md](docs/LEGAL-NOTES.md)。
  > 结论先写在这里：**开源可以继续；收费只做「赞助 / 技术支持」，不要把 APK 或订阅当商品卖。**
  > 卖软件会让「被要求下架」升级为「可主张赔偿」，而 MFi 认证与 iAP2 协议这两块，
  > 本项目的 MIT 授权一点都不覆盖。
- 应用名为 **CarPToCarL**，**不含**任何第三方商标；已配对的真车 / 电脑名称一律不写进仓库
  （文档与测试里是 `Audi_MMI_0000` / `AA:BB:CC:DD:EE:FF` 这类占位值）。
- 「CarPlay」「AirPlay」「CarLife」「MMI」「MagicOS」均为其权利人的商标，文中出现仅作
  **描述性引用**，不代表任何关联、授权或认证。
- 本项目**不使用** Apple、百度或任何车厂的图形标识与认证标（含 MFi badge）。
- 仓库内**不含**反编译产物、厂商 SDK、抓包二进制、证书私钥或真实热点密码
  （`.tools/`、`carlife-ref/`、`*.dex`、`*.jks` 均在 `.gitignore`）。
- 真车验证前，建议先用可借到的 iPhone 与车机做一次正常无线配对，留作对照基线。

---

## 十一、版本历史

> **号码规则**：版本号不出现连续两个 `4`（`app/build.gradle.kts` 内 `require` 强制拦截）。

### 最新正式版：`1.2.83`（2026-11-23）

- **BMW MGU-81 无声修复**：PCM 门禁只认引擎信号（不再被播放器轮询抖动误判暂停），暂停残留丢弃窗 `15s → 1.5s`，音频流重建改为先拆后建。
- **并入 PR #37（BMW EntryEVO 建链死锁）**：`Iap2LinkEngine` 在 `NORMAL` 态回 `SYN|ACK`；EntryEVO/Alpine 走 RSA-1024 固定 20B 单档挑战；`MfiLengthMemory` 区分挑战断链硬拒绝。
- **并入 PR #40（仪表转向指引，默认关）**：经「正在播放」与封面通道显示 CarLife 转向文字与箭头，与原生 `0x52xx` 路径并存。

> 完整逐版记录（`1.2.83` 详细日志证据、`1.2.82` 及更早全部版本、含 `1.2.64/1.2.65` 回滚口径）见 **[CHANGELOG.md](CHANGELOG.md)**。
