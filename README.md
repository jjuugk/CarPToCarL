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

> **[`CarPToCarL-1.2.40.apk`](https://github.com/jjuugk/CarPToCarL/releases/download/v1.2.40/CarPToCarL-1.2.40.apk)**
> —— 主线固定直链（当前 1.2.40，13.9 MB）。**刻意不用 `releases/latest`**：那个指针
> 按发布时间算，`-switchpool` 分支包一发布就会把它抢走，主线的 `latest` 直链随之 404

| 项        | 值                                                                              |
| -------- | ------------------------------------------------------------------------------ |
| 全部版本     | [Releases 页面](https://github.com/jjuugk/CarPToCarL/releases)（含更新日志与历史版本）        |
| 本版固定包    | [`CarPToCarL-1.2.40.apk`](https://github.com/jjuugk/CarPToCarL/releases/download/v1.2.40/CarPToCarL-1.2.40.apk) |
| 系统要求     | Android 12 及以上（minSdk 31）                                                      |
| 签名       | 作者个人调试证书（仓库不含发布密钥）→ 可直接**覆盖安装** 1.0.0 ~ 1.2.40，但不要与其它来源的包混装                    |
| 安装       | 系统安装器直接装，或 `adb install -r CarPToCarL-1.2.40.apk`                              |
| 前置       | 手机需装 **CarLife+**（荣耀机型为 `com.baidu.carlife.honor`）                             |

首次启动会弹出免责声明，需手动同意。装好后怎么配、常见故障怎么查，见
[第三节 使用说明](#三使用说明)与[第五节 故障排查](#五故障排查)。

---

## 一、项目简介

| 项    | 值                                                                             |
| ---- | ----------------------------------------------------------------------------- |
| 当前版本 | `1.2.40`（正式版；**体检页加「一键获取缺失权限」，并把音频发送侧的抖动做成可测的**。① **一键获取权限**：通知使用权（`NotificationListenerService`）根本不是运行时权限，授权存在 `Settings.Secure.enabled_notification_listeners` 里，系统永远不会为它弹框——旧实现靠 Shizuku 跑 `cmd notification allow_listener`，Shizuku 移除后这条命令必然静默失败，于是体检那项永远红、界面上却什么都没发生。现在体检弹窗底部有「一键获取（N 项）」，一项一项真的走到系统界面：运行时权限打包申请、蓝牙用 `ACTION_REQUEST_ENABLE`、WLAN/位置服务跳系统页、通知使用权直接带 `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME` 展开本应用那一条、电池优化弹忽略框；做完返回自动进下一项，推进前重新体检跳过已满足的，最后列清仍缺什么。身份/配对这类设置项刻意不进计划。② **音频发送更稳**：与 PC 接收端双端日志对账后确认「偶尔卡一下」是链路上每 7 秒一次、约 4.5 包（≈96ms）的连续丢包（接收端 `late`/`overflow`/`rebuffer`/`decryptErrors` 全 0，我方环形缓冲丢 0KB）；能控的部分都收紧——混音泵线程提到 `URGENT_AUDIO` 优先级，音频数据口 `SO_SNDBUF` 提到 256KB 并打 DSCP EF（WMM 走语音接入类，排队优先于同链路的视频 TCP）。③ **抖动可量化**：`AUDIO: 混音体检` 新增「最长停拍 XXms」——两次产出之间的最大间隔，≥50ms 会直接标「我方泵线程被拖住，不是链路问题」，下一份日志就能把自家抖动与链路丢包分开。单测 716 例 0 失败；版本号唯一来源是 `app/build.gradle.kts` 顶部 `appVersionName`） |
| 下载   | **[`CarPToCarL-1.2.40.apk`](https://github.com/jjuugk/CarPToCarL/releases/download/v1.2.40/CarPToCarL-1.2.40.apk)**（见上方「立即下载」）       |
| 包名   | `io.github.shaonialife.carptocar`                                             |
| 应用名  | **CarPToCarL**（不含任何第三方商标）                                                     |
| 最低系统 | Android 12（minSdk 31，targetSdk 34）                                            |
| 适用车型 | 支持**无线 CarPlay** 的车机（真车已验证：奥迪 MIB3；测试台：DiPlay / xcertplay）                    |
| 画面来源 | 本机 **CarLife+** 引擎（荣耀：`com.baidu.carlife.honor` 8.8.5）                        |
| 音频   | PCM 直通（CarLife 音频通道 → AirPlay 音频通道）                                           |
| 参考项目 | **[CarDeck](https://github.com/shaonialife/cardeck)**（MIT，独立项目，与本项目非同作者）——本项目的协议层与工程骨架参考了它的实现 |

### 与参考项目 CarDeck 的关系

[CarDeck](https://github.com/shaonialife/cardeck) 是一个**独立项目**（MIT 许可，版权归其作者，
与本项目**不是同一作者**），目标同样是「让 Android 手机扮演 CarPlay 发送端」。本项目在开发中
**参考了它的 iAP2 / AirPlay 协议实现、密码学封装、蓝牙链路与工程骨架**；按其 MIT 许可，
版权与许可声明已保留在 [NOTICE](NOTICE) 第 0 节。运行时仍可见的历史命名（日志目录
`下载/CarDeck`、进程启动行 `CarDeck`）沿用自参考对象，不代表依赖该应用。

两者真正的区别在**画面来源**：

| | CarDeck | CarPToCarL（本项目） |
|---|---|---|
| 画面来源 | 自绘隐形屏（受信虚拟屏跑手机应用）+ Shizuku 触摸注入 | **劫持本机 CarLife+ 引擎码流，不重编码** |
| 触摸通路 | Shizuku 直注虚拟屏 | 车机 → HID → CarLife 9340 通道注入 |
| 依赖 | 需要 Shizuku | **不依赖 Shizuku**（2026-10-24 起移除） |
| 目标 | 隐形屏启动器 | 无线 CarPlay 投屏网关 |

两个仓库都未声明或使用任何第三方的 MFi 授权，法律边界见
[docs/LEGAL-NOTES.md](docs/LEGAL-NOTES.md)。

### 方向说明

- 本项目是**发送端**（手机扮演 iPhone），与 DiPlay / xcertplay 这类**接收端**（扮演车机）方向相反。  
  **DiPlay 是 xcertplay 的衍生版，是本项目实际使用的测试台**。
- 发送端**不需要苹果签名身份**：iAP2 认证是单向的（手机验证车机的 MFi 证书，车机信任手机自报结果）。
- **有线 USB 路线已排除**（App 无法控制 USB gadget，测试机 BL 无法解锁），只走无线。
  卡在哪一层、三条可能的绕法（root 手搓 gadget / 树莓派当 USB 翻译器 / 等 dongle）、
  将来若要做该怎么起步，见 [docs/WIRED-CARPLAY-FEASIBILITY.md](docs/WIRED-CARPLAY-FEASIBILITY.md)。

### 背景：为什么需要它

Android 手机连 CarPlay 车机，官方路径不存在。本项目的做法是：既然车机只认 CarPlay，  
那就让手机**冒充 iPhone** 完成 iAP2 + AirPlay 全链路；既然手机侧最完整的车机界面是原生  
CarLife+，那就把它的视频/音频流**截下来直通**给车机。两边各自用最成熟的实现，中间只做搬运。

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
./gradlew testDebugUnitTest         # 单元测试（96 个测试文件）
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

单模块 Android 应用（`app/`），主源码 83 个 Kotlin 文件，按**链路职责**切分。

### 根目录

```
CarPToCarL/
├── app/                          # 唯一的 Android 应用模块
├── docs/                         # 协议知识库与逆向结论
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

### docs/（逆向与协议沉淀）

| 文档                                                                             | 内容                        |
| ------------------------------------------------------------------------------ | ------------------------- |
| [docs/carlife-hijack-findings.md](docs/carlife-hijack-findings.md)             | CarLife 协议逆向发现（端口、消息号、时序） |
| [docs/carlife-hijack-integration.md](docs/carlife-hijack-integration.md)       | 劫持功能的集成实现                 |
| [docs/carlife-wireless-verification.md](docs/carlife-wireless-verification.md) | 无线链路验证记录                  |
| [docs/PROTOCOL-NOTES.md](docs/PROTOCOL-NOTES.md)                               | iAP2 / AirPlay 协议依据       |
| [docs/carplay-connection-layers-20261109.md](docs/carplay-connection-layers-20261109.md) | CarPlay 连接分层导览（L1 蓝牙 → L5 编排，含症状→层判据表） |
| [docs/CARLIFE-GATEWAY-FEASIBILITY.md](docs/CARLIFE-GATEWAY-FEASIBILITY.md)     | 网关方案可行性评估                 |
| [docs/LEGAL-NOTES.md](docs/LEGAL-NOTES.md)                                     | 开源 / 收费的法律风险梳理与商业化清单      |
| [docs/process-freeze-recovery.md](docs/process-freeze-recovery.md)             | 进程被系统冻结的判定、PROC 日志与解冻后自动恢复 |
| [docs/audio-channel-watchdog.md](docs/audio-channel-watchdog.md)               | 音频通道（9240 / 9241）断链与沉默看门狗：为何「有画面没声音」 |
| [docs/connect-hardening.md](docs/connect-hardening.md)                         | 配对失败定性、候选地址、阶段预算、多屏选择：连接健壮性四处加固 |

### 主源码包结构（`app/src/main/java/io/github/shaonialife/carptocar/`）


```
├── MainActivity.kt               # 手机侧操作台 UI（连接 / 声音 / 暗幕 / 日志 / 免责声明）
├── SessionController.kt          # 会话总管（进程级单例）：链路生命周期、配对、WiFi、取消
├── SenderApp.kt                  # Application（未捕获异常兜底、包变化失效缓存）
├── carlife/       (5)            # 本地 CarLife 劫持（HU 客户端 / 协议 / 音频 / 画质配置）
├── iap2/          (8)            # iAP2 链路层 + CSM + 发送端会话 + NowPlaying
├── airplay/      (10)            # AirPlay 配对 / 控制通道 / 流协商 / 加密 / Timing
├── audio/         (6)            # 音频通道（AAC 编码 / 播放捕获 → RTP/ChaCha20）
├── video/         (1)            # H.264 推流（VideoSender，含 SPS 解析与声明对齐）
├── bt/            (5)            # RFCOMM 双向（Server 等回连 / Client 真车主动连）
├── launcher/     (11)            # 隐形屏启动器（保留，非默认画面来源）
│   └── settings/  (2)            #   设置面板（显示缩放、应用方向兼容）
├── render/        (2)            # EGL 多屏合成 → 编码 Surface
├── input/         (4)            # 触摸分流 + 消费键 + CarInput 客户端
├── capture/       (2)            # 画面源
├── net/           (6)            # WiFi 入网 / mDNS 发现与 ctrl 广播
├── media/         (2)            # 通知使用权 → 媒体会话（音乐卡 / 歌词数据源）
├── service/       (1)            # 前台保活服务
├── transport/     (2)            # 传输层封装
├── ui/            (5)            # 暗幕、波形图、壁纸、深色弹窗、面板尺寸
├── legal/         (3)            # 免责声明
└── util/          (5)            # 后台任务池、日志导出、设备标识
```

### 各包职责速查

| 包                       | 定位          | 关键内容                                           |
| ----------------------- | ----------- | ---------------------------------------------- |
| `carlife`               | CarLife 劫持  | 冒充车机连本机 CarLife+，H.264/PCM 直通，98386/98311 参数下发 |
| `iap2`                  | iAP2 协议链路   | Link 引擎、CSM、发送端会话状态机、NowPlaying                |
| `airplay`               | AirPlay 主会话 | PairVerify、控制通道、流协商、FairPlay/ChaCha 加密、Timing  |
| `video`                 | 视频推流        | H.264 封装、GOP 缓存、SPS 解析、`VideoConfig` 声明对齐      |
| `audio`                 | 音频通道        | AAC 编码、播放捕获、RTP 封装                             |
| `bt`                    | 蓝牙链路        | RFCOMM Server/Client、目标车机设备                    |
| `net`                   | 网络入网        | WiFi、mDNS 发现、ctrl 广播                           |
| `launcher`              | 隐形桌面        | Dock、应用网格、地图屏（保留能力，默认不走）                       |
| `render`                | 画面合成        | EGL 多屏合成 → 编码 Surface                          |
| `input`                 | 输入链路        | 触摸分流、消费键、CarInput                              |
| `media`                 | 媒体会话        | 通知使用权、音乐与歌词数据源                                 |
| `service`               | 前台服务        | 连接/投屏保活                                        |
| `ui` / `legal` / `util` | 通用          | 暗幕、免责声明、日志导出等                                  |

### AIDL

```

app/src/main/aidl/io/github/shaonialife/carptocar/input/ICarInput.aidl

```

跨进程传输触摸/输入事件（`input/` 包下客户端通过 Binder 调用）。

### 单元测试

`app/src/test/java/io/github/shaonialife/carptocar/`，共 **96** 个测试文件、716 个用例，重点覆盖协议编解码与密码学：

- `airplay/`：PairVerify、BinaryPlist、控制通道加密、流帧加密、Timing
- `iap2/`：编解码、Link 引擎、发送端会话
- `audio/`：AAC 编码、载荷封装
- `video/`：SPS 解析（含宏块对齐与 frame_cropping 的回归用例）
- `input/` `launcher/` `net/` `ui/` `util/` 等：各自单元逻辑

> 早期版本依赖 **Shizuku**（虚拟屏注入 / 触摸注入），2026-10-24 起已**移除**，  
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

### 1.2.40 —— 2026-11-14（体检页一键补齐缺失权限；音频发送侧把抖动做成可测的，正式版）

**一、通知使用权为什么永远拿不到（`session/PermissionRepair`，新增）**

「通知使用权」不是运行时权限，授权存在 `Settings.Secure.enabled_notification_listeners`，
系统**永远不会**为它弹框。旧代码只有 `NotificationAccess.ensureGranted()` 去补它，走
`SysShell.exec("cmd notification allow_listener …")`——那是 Shizuku 时代的写法，Shizuku
移除后 `SysShell` 已退化为「用应用自身 uid 跑 sh」，这条命令必定失败，而且只写一行日志。
于是体检里那项永远是红的，界面上却什么都没发生。

- 体检弹窗底部新增主按钮 **「一键获取（N 项）」**，不是弹一段说明，而是**一项一项真的走到
  系统界面**：运行时权限打包申请 → 蓝牙 `ACTION_REQUEST_ENABLE` → WLAN 设置页 →
  位置服务设置页 → 通知使用权 → 电池优化忽略框。
- 每一步给一组候选 Intent，国产 ROM 把某个 `Settings` Action 改成空实现时逐个退让，最后
  退到应用详情页。
- 通知使用权用 API 30+ 的 `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME`，直接把本应用那一条
  顶到眼前，不必在几十个应用里翻。
- 做完返回 App 自动进下一项；推进前重新体检，已满足的跳过（在上一个页面顺手开了别的也不
  会白跳）。全部走完弹一次结果，仍缺的项原样列出。
- 「本机蓝牙地址 / 蓝牙名 / 目标车机已配对」**刻意不进计划**——它们不是权限，跳哪个页面都
  补不上。体检里那项的说明也改成明说「这项没有系统弹窗，只能在设置里手动打开」。

**二、音频发送侧：能控的都收紧（`audio/AudioSender`、`audio/AudioMixer`）**

与 PC 接收端（WinPlay 1.2.5）双端日志逐项对账：接收端 `missing` 24→178、
`discontinuity` 9→43（240 秒）＝**平均每 7.06 秒一个断点、每次约 4.5 包 ≈96ms**；
`late` / `overflow` / `rebuffer` / `timestampReset` / `decryptErrors` 全为 0，
`forwarded == packets`。我方同时段：混音体检 47988 样本/秒、环形缓冲丢 0KB、锚点 30/30、
链路 47954~48059（±0.12%）。**结论是丢在链路上，不在这端**——音频包仅 512B、
46.9 包/秒、1.4Mbps，不分片也不拥塞，抢带宽的是同一条 Wi-Fi 上的视频 TCP
（交互时 29.7fps、key frame 50~70KB）会在 802.11 队列里堆成突发。

- `audio-mixer-pump` 线程优先级提到 `URGENT_AUDIO`（拿不到退 `AUDIO`，再退默认），避免
  GC/编码把 21ms 定拍拖成 40ms。
- 音频数据口统一走 `openDataSocket()`：`SO_SNDBUF` 提到 256KB、`trafficClass=0xB8`
  （DSCP EF，WMM 走语音接入类，排队优先于视频）；两项失败都只记一行，不影响发送。

**三、抖动可量化：`AUDIO: 混音体检` 加「最长停拍」**

`AudioMixer` 新增 `windowMaxGapNanos`（两次产出之间的最大间隔，跨窗口不重置），
体检行多打「最长停拍 XXms」，**≥50ms 直接标注「我方泵线程被拖住，不是链路问题」**。
下一份日志就能一眼把自家抖动与链路丢包分开，不必再靠猜。

单测 716 例 0 失败（新增 `PermissionRepairTest` 6 例、最长停拍 1 例）。

### 1.2.39 —— 2026-11-14（导航+音乐同时播放不再卡顿：两路音频改定拍混音，正式版）

上一版把音频四个环节分开验过（引擎产出、我们发送、车机回执、会话时长）都正常，用户听到的
仍然是「导航一播报，过一会儿声音就卡」。真因找到了：**两路音频被首尾相接塞进同一条流**。

**一、定拍混音（`audio/AudioMixer`，新增）**

媒体（9240）与播报（9241）的 PCM 原来写进同一个 `ByteArrayOutputStream`，按 1024 样本
切帧（≈21ms）——一帧里常常是「半帧音乐 + 半帧播报」，听感是持续的碎、糊。更狠的是封包
时间戳跟着**产出量**走：两路同推 = 一秒产出两秒样本，RTP 时钟 2×，车机按 1× 消费 →
缓冲只增不减，几条播报之后溢出、丢包重同步。这才是「过一会儿才卡」。

- 两路各一个 500ms 环形缓冲（写满丢最旧）+ 各自的重采样器；
- 按 `nanoTime` 反算「现在该产第几帧」**定拍输出**，严格 48000 样本/秒，时间戳回到 1×；
- 相加后饱和限幅；播报在场时音乐压到 −12dB（21ms 线性斜坡，停 250ms 后恢复）；
- 落后超过 3 帧不追赶，直接重新对齐（避免时间戳瞬间前跳）；
- 发送侧收成单线程 `audio-mixer-pump`：混音 → AAC 编码 → ChaCha20 密封 → UDP 全在同一条
  线程；`feedPcm` 只做「重采样 + 写环形缓冲」，CarLife 读线程不再被发送反压；删掉旧的
  补静音保活线程（它会和正常数据抢改时间戳）。

**二、44.1k→48k 重采样不再欠样（`audio/PcmResampler`，新增）**

旧实现每 1024 帧独立截断：`floor(1024×48000/44100)=1114`，每块少 0.55 帧 → 每秒少 ~24
样本（0.05%）。短会话无感，25 分钟欠 0.7 秒，把车机抖动缓冲抽干；块起点还把相位重置成 0，
块边界多一次子样本跳变。现在相位与末帧**跨块延续**，块边界线性插值（不再最近邻复制）。
坑：`phase` 必须写 `pos - (inFrames - 1)`，写成 `- inFrames` 每块丢半步，长期速率更慢。

**三、让日志能自证「声音到底有没有在发」**

- 30 秒体检判据原来写反（`deferrals >= sent - 1`）：只要开头几包成功，之后 99.6% 被拒
  也永远打「车机已在监听」，把唯一的坏消息报成好消息。改为按窗口增量比，合并成一行
  `AUDIO: 链路体检`（累计包数 / 实发包数 / 实测样本率与声明采样率之差 / 锚点被拒比例）。
- 新增 `AUDIO: 混音体检`（每 30 秒）：窗口帧数、实测样本/秒、媒体/播报占比、duck 次数、
  环形缓冲丢了多少 KB。
- 文件日志到 2MB 原来**整份删除**，会话开头的档位/自检/音量全丢。改为只保留尾部 1MB，
  新增 `util/CriticalEvents`：协商结果、自检、启停、拆链等结论行单独留 200 条，导出时
  附在末尾「关键事件」段。

**四、补发 RECORD 后 2 秒被拆，不再赖音频档位（`airplay/PostRecordWatch`，新增）**

宝马 MGU-81 一夜 8 轮：5 轮在补发 RECORD 后 1.79~2.19 秒被整链拆掉，3 轮完全相同的步骤
却活下来（其中一轮跑了 8.6 分钟）。同步骤、同档位、同报文，一半死一半活 → 接收端内部
竞态。旧逻辑只看「起流没跑满 20s 就断」，把 `t102/media/0x800000`（AAC-LC 48k，车机自己
声明的唯一媒体档）记了 4 次「不兼容」，再两轮就会把唯一能用的档位拉黑。

现在补发前 `mark()`，3.5 秒内被拆即命中（实测全落在 1.79~2.19s，留一倍余量），归因到
「补发 RECORD 被拒/被拆」，下一轮改音频优先，**不再算到音频档位上**；`take()` 一次性消费
（车机掉线时视频回调与 BT 回调都会走归因，读两次会记两遍）。用户主动「断开」不归因。

**五、界面**

- 手动填 `IP:端口` 的「测试台接收端」默认隐藏：连点标题 `CarPToCarL` 10 次切换显示
  （相邻点击间隔 >1.5 秒重新计数），状态持久化到 `DevModePrefs`。那一行误填会顶掉正常
  车机连接，普通使用完全用不上。
- 自动连接开关只留「连接准备」卡里那一个（曾同时放到标题栏做快捷入口，用户实测评拥挤，
  已撤掉）。

**六、单测**

**709 例 0 失败**（新增 `AudioMixerTest` 11 例、`PcmResamplerTest` 7 例、
`PostRecordWatchTest` 9 例）。

### 1.2.38 —— 2026-11-12（连接前体检 / 断开主动拆链 / 让车机知道「我们在收音」，正式版）

本版不做新功能，只把**连接这条链路上的不确定性**收掉：连不上时能一眼看出是哪一项没满足，
连上之后车机也知道我们在干什么、什么时候结束。四块内容：

**一、连接前体检（`session/ConnectionPreflight`）**

以前「连不上」只有零散日志，用户看到的是转圈、日志里是十几种不同原因。现在每次启动连接
或自动连接，先一次性核对 11 项并给出同一份结论（UI 与日志同源）：

```
蓝牙权限 / 蓝牙开关 / WiFi 权限 / WiFi 开关 / 本机地址 / 定位服务 /
目标设备已配对 / 设备名 / 通知权限 / 通知使用权 / 电池优化
```

`Report.headline()` 给界面一行摘要，`dialogText()` 给「是哪一项拦住了、去哪儿改」，
`log()` 20 秒节流写日志。**故意不含「投屏授权」**：本项目的画面来自 CarLife 引擎的
H.264 直通、声音来自 CarLife 的 PCM，`MediaProjection` 那条路是死代码，列进去只会误导。

**二、热点复用改为「问系统」（`net/CarNetworkReadiness` + `net/NetworkReusePolicy`）**

旧实现信自己的 `boundCarSsid` 账本，于是「系统已经把热点断了」和「系统还连着、只是没拿到
地址」被当成同一件事——前者该重连，后者重连就会**把自己刚建立的网络拆掉**。现在先取系统
快照（只读、不绑网络），分五态再决定动作：

| 观测 | 动作 |
| --- | --- |
| `READY`（已连、有地址、SSID 对） | `Reuse`，只 bind，不发任何请求 |
| `ADOPT`（已连、无地址） | `AdoptNow`，等系统补地址 |
| `NOT_READY`（已连、地址还在路上） | `WaitForAddress`，**绝不重发 specifier** |
| `WRONG_NETWORK` | `Rejoin` |
| `ABSENT` | 走原有静默入网顺序 |

**三、会话生命周期收敛（`session/SessionAttemptGate` + `session/AsyncSessionStop`）**

- 建链闸门：同一条会话只允许一个发起者；编号、来源（手动/自动/重连）、耗时、被拦次数
  全部入日志，需要时 `takeover()` 强行接手也留痕——以前「两个线程同时建链」在日志里
  只能看到两段交错的空行。
- 收尾唯一执行者：同一时刻只拆一次，其余请求合并计数；30 秒还没拆完就强制接手。
  以前重复拆链会连发两条 TEARDOWN，第二条往往落在已被关闭的 socket 上。

**四、协议侧补齐三件「一直没说」的事**

1. **本机 `appStates`**（AIS Table 23-56）：`Speech` 的语义是「某实体正在做语音操作，
   收音期间所有音频输出必须关闭」。我们此前只把车机 `changeModes` 的值抄回来再回显，
   等于全程只报「车机在干什么」，车机永远不知道我们在听它——识别率因此吃亏。现在按
   CarLife 语音模块边沿 + 语音会话期间的 TTS + `AudioManager.mode` 三路信号上报；
   **导航播报不算说话**（否则放音乐时车机会莫名闭嘴）；本机无语音无通话时，报文与改动前
   逐字节一致。
2. **TEARDOWN 逐级拆链**（AIS 23.3.1 步骤 9~11）：先按条 TEARDOWN 流 → 再拆整个会话 →
   最后关 TCP。目的是把「断开后马上重连，车机给凭据却不开 7000 端口」的那 15 秒
   （`mediaDisconnectTimeout`）从「车机自己猜」变成「我们主动告诉它」。
3. **通话流 `vocoderInfo` + 读车机声明清单**：`vocoderInfo` 报这条流真实的采样率
   （不是 8000——我们不是蜂窝声码器，报窄带会让它按窄带优化满带宽音频），且只放在音频
   SETUP 的重试版报文里，已跑通车机的首次报文逐字节不变。同时开始读 `/info` 的
   `extendedFeatures`（Table 23-33）：清单**明确不含** `vocoderInfo` 才不发，
   清单整个缺失（老固件）时维持照发——「没说」不等于「不支持」。

**五、0x4300 重连阶梯细化**

SSID 读不到时也探测当前 WiFi、一轮内试完三档；拿不到 ACK 时 45 秒快速重连换档；
某档成功后又被判静默时不再把它钉死（不再「一次成功就永久锁定」）；ctrl 广播端口固定不漂移。

单测 678 例 0 失败。

### 1.2.37 —— 2026-11-11（恢复补发 RECORD：把声音找回来；不再自己丢掉车机地址，正式版）

本版两件事：一件是**把 1.2.32 之后一直被关着的「补发 RECORD」打开**，一件是**修掉三处
会让 App 自己把车机地址弄丢的缺陷**。

**一、有画面没声音（宝马 MGU-81 实车）**

`20:05:26` 建链后会话稳定活了 5 分钟，画面正常（车机实收 14~30fps），
`音频直通 251 包 / 864KB / 1412kbps · 电平 91%（非零 43%）`——送进编码器的是真实音乐，
但喇叭一点声音都没有。日志里其实早就写着：

```
20:02:59.568  AUDIO: 音频流已加入——本版**不补发 RECORD**（P1 单变量实验…若车机侧无声，属预期）
20:05:32.706  同上
20:05:32.646  ✓ 音频流协商成功 dataPort=6000 controlPort=6011
20:05:33.709  车机 controlPort 未监听（ICMP 端口不可达）
首轮汇总：     同步包共发出 15 个，其中 14 次被车机 controlPort 的 ICMP 拒绝
```

按 AirPlay 语义，音频流是在「会话已被 RECORD 激活」之后才加入的，必须**补发一次
RECORD** 才能把它纳入会话。不补的后果就是接收端对 SETUP 回 200、给出端口号，
却**从不把这条流纳入播放**——所以它也不会去监听 `controlPort`，同步锚点全部落空。
2026-10-17 奥迪上实测过同一形态（音频包在发、采集非零、车机无声）。

这个开关是 2026-11-09 为排查「宝马 RECORD 后 16~36ms 关控制通道、`pi` 每轮都变」
而临时关掉的（P1 单变量实验）。现在那个崩溃已经被 1.2.36「停发 `0xFFFA`」解决
（本次会话 `pi` 始终是 `bd1b07f0…`、没有重启），P1 完成了它的使命，而它的代价
（**所有车机全程无声**）仍在生效，所以恢复：

- `POST_RECORD_REACTIVATE_ENABLED = true`；
- 若某台接收端确实受不了「流后补 RECORD」（回 500 或立刻关服务），走的是已有的
  `StreamOrderMemory`「音频优先」路径（一次 RECORD 同时纳入视频+音频），**不要**再把它关回去；
- `AudioSender` 新增「同步锚点 30s 体检」，每 30 秒一行：

  ```
  AUDIO: 同步锚点 30s 体检——已发 30 个，其中 30 次被 ICMP 拒绝（车机未为这条流开口：检查 RECORD 是否纳入了音频流）
  AUDIO: 同步锚点 30s 体检——已发 30 个，其中 0 次被 ICMP 拒绝（车机已在监听 controlPort）
  ```

  以后一眼就能分开「RECORD 没生效」和「别的原因（音量/加密）没声音」，
  不用再等停链时的汇总。顺带说明：`feedback.stream[].sampleTime` 一路上涨
  **不能**当作「车机在播」的证据——那是流时钟，建链后在按实时走。

**二、不再自己丢掉车机地址（宝马 iD8 实车）**

`19:11` 那轮连上 8.2 秒后全断，但车机一直活着——它两次发 `GET /ctrl-int/1/connect`
（`172.16.222.97`），我们都回了 200。真正的候选地址却是 `0.0.0.0`：

```
19:12:23.075  AIRPLAY: 连接候选 0.0.0.0（IPv4）
19:12:23.085  连接失败 - failed to connect to localhost/127.0.0.1 (port 7000) ... ECONNREFUSED
```

三处叠加：

1. **mDNS 缓存每轮清空 + `start()` 只会说「已在扫描中」**。`NsdManager` 不会为
   已发现的服务补发 `onServiceFound`，所以缓存一清就再也回不来，第 2 轮起永远「未发现」。
   现在 `lastKnown` 跨轮保留，且「已在扫描中」时改为 `restartDiscovery()`——先 stop，
   再按 300ms / 500ms 重试重新 `discoverServices`（最多 5 次）。
2. **占位网关 `0.0.0.0` 被 `contains('.')` 当成合法 IPv4**。现在 `EndpointHosts.isPlaceholder()`
   统一剔除 `0.0.0.0` / `::` / `::ffff:0.0.0.0` / `127.x` / `::1`；**`fe80::` 明确不算占位**
   （奥迪 MIB3 只发这个）。同时把车机 ctrl 邀请的**来源地址**当候选，过三道关
   （非占位、10 分钟内、同网段）——宝马那轮车机亲手从 `172.16.222.97` 连过我们两次，
   这个地址原本一次都没被用过。
3. **「7000 已连续拒绝」把连自己的失败也算进去**，于是主控反复「叫醒」一台活着的车机。
   现在候选为空时只记日志、不计数、不叫醒。

顺手把诊断日志改准：重试时会写「本轮无新结果，沿用上轮 172.16.222.97:7000」，
不再用一句「未发现」把「其实有旧地址可用」这个事实盖掉。

单测 605 例 0 失败（新增一例钉死 `0.0.0.0` / `127.x` 永不成为候选）。

---

### 1.2.36 —— 2026-11-11（撤回内容加密声明、停发崩溃常量、重认证不再自拆链，正式版）

本版是三条**「我们自己加进去、又被实车日志否掉」**的改动的回收。

**一、`CONTENT_ENCRYPTION=1` 撤回（画面闪退 + 放音乐没声音的同一个根因）**

1.2.32 的 `071d6a3` 在 98386 特征清单里加了 `CONTENT_ENCRYPTION = 1`，理由是参考实现
（OcarPlay，一个 CarPlay 接收端）的清单里有这一项。荣耀 PTP-AN10 的两份日志
（18:51 是 1.2.34、18:53 是 1.2.35 #noburst）暴露出代价：

| 建链 | 首条 98311 | 四条通道同时 reset | 存活 |
|---|---|---|---|
| 18:48:36.480 | 18:48:38.540 | 18:48:56.614 | 20.1s |
| 18:49:15.115 | 18:49:17.174 | 18:49:35.235 | 20.1s |
| 18:49:52.357 | 18:49:54.423 | 18:50:12.497 | 20.1s |
| 18:52:58.730 | 18:53:00.790 | 18:53:18.865 | 20.1s |

拆链时画面正以 59fps 正常推流（`帧 #853 已发送` → 0.5 秒后 reset），所以这不是崩溃，
是**引擎按定时器主动拆掉会话**。拆完 2 秒 `7240 未监听`，再过几秒引擎被重新拉起——
用户看到的就是「CarLife 一直在重启」。

每一轮都有这两行紧挨着，间隔 3 毫秒：

```
18:52:58.834  主动推送 98386 特征配置（…CONTENT_ENCRYPTION=1 内容加密…）
18:52:58.837  未处理的消息 id=65642 (0x1006a) 0B
```

`65642` 是 CarLife 的 `EncryptSetup`（RSA/AES 密钥交换第一步）。对照同机 2026-10-26
的日志（`.tmp/call2.txt`）：98386 **不带**该项，65642 一次都没出现，会话连续放音
6 分钟，`媒体 音频格式宣告 44100Hz 2ch` + `音频直通` 全部正常。

声音也是同一个原因。两份日志里 `音乐=1`，但**一个 PCM 都没有**：

```
18:48:47.113  AUDIO: 无 PCM 超过 300ms——开始补静音帧保活
18:50:15.875  AUDIO: 已停止（共发 4225 包 / 2261KB）      ← 全是补的静音帧
```

全程没有一次 `媒体 音频格式宣告`——加密态下引擎根本不打开媒体音频流。

改：`featureConfig(contentEncryption)` 默认值改回 `false`（清单仍 5 项、值为 0，
`条数 = 长度` 不变），两处 98386 日志文案从「CONTENT_ENCRYPTION=1 内容加密」改成
「CONTENT_ENCRYPTION=0 不加密」，并新增 `65642` 分支：每轮第一条打
`⚠ 引擎要求内容加密握手 0x1006a` 带 head hex。单测钉死「默认必须发 0」。

**二、停发 `0xFFFA / 0xA100 / 0xFFF0`（出画面后卡一下闪退的根因）**

11-11 三份别克日志（13:38 / 13:44 / 13:46）与宝马 iD6 成功轮逐行对齐，崩溃前 200ms
的唯一共同项是 `0xAA05` 之后的 MIB3 突发：

| 车 | 突发 | 断链 | 间隔 |
|---|---|---|---|
| 别克 13:38:14.96 | 7 条整批 | 13:38:22.97 `Connection reset` | 8.0s |
| 宝马 16:01:16.575 | 部分 | 16:01:16.613 | 40ms |

`0xA100` 在宝马那轮没发也崩，排除；交集只剩 `0xFFFA`——它是奥迪固件抓包拿到的
**字节常量**，只有 MIB3 系认。现在只保留 `0x4E0C / 0x4E0A / 0x4E0B / 0x4E09`
四条通用问候，另三条常量保留在代码里并注明恢复条件（奥迪异常时按画像单独恢复）。

**三、重认证改软发送 + 车机端 CarPlay 存活判定**

- 原来首轮 `0x4300` 没换来 `0x4301` 时的重认证，`0x1D00` 走的是带确认的常规发送；
  车机沉默 → 重传 10 次 → `packet 210 not acknowledged` → **自己拆链**（13:44:31
  实测）。等于把「试着救一次」变成「主动断开」。现在整段重认证走 `softSending`，
  重传用尽只丢帧、不拆链。
- 新增 `CarPlayUplinkHealth`：曾拿到过 `0x4301` 的车机，连续 2 轮拿不到（或 AirPlay
  事件口直接 `ECONNREFUSED`）→ 判「车机端 CarPlay 已退出」，跳过重认证、重连间隔
  16s → 60s。之前车机 WiFi 组件崩了以后，蓝牙侧仍 ACK 问候但不再给 `0x4301`，
  我们每 16 秒重认证一次、每次自拆链，死循环。

单测 603 例 0 失败。

---

### 1.2.35 —— 2026-11-11（别克/Cinemo 新协议建链逐项对齐：0x4300 解封、走确认与重传、可重认证，正式版）

本版全部围绕一件事：**让「新协议」车机（GM/Cinemo 栈，别克 E2LB / BUICK_3138、凯迪拉克 CT5）
真的迈出建链的第一步**。之前几轮的失败不是「连不上」，而是**我们从没发出过那一帧**。

**一、现场证据**

把两份别克实车日志与 11-09 宝马（NBT Evo，能连上）成功那轮逐行对齐，差距只有一个：

```
宝马 18.293 tx=0x4300（发后不管）
     18.331 rx ack=201                    车机 38ms 内确认
     19.645 NSD 发现 _airplay._tcp → 172.16.224.1:7000   （0x4300 后 17ms）
     20.389 CTRL: 车机 GET /ctrl-int/1/connect 邀请

别克 47.044 tx=0xaa05 + 问候逐条发出（车机逐条 ACK 到 seq=198，链路完全健康）
     47.050 「0x4300 本轮不发」            ← 一条都没发
     47~118 秒  PROBE ×3 全空、零邀请、零 0x4301
     ~61 秒    车机自己关 RFCOMM，重连，再来一遍
```

关键认知：**宝马的 AirPlay 服务常驻，别克的要收到 `0x4300` 才拉起**。所以
「mDNS 上看不到 `_airplay._tcp` 就不当作车机热点」是在拿结果当判据，形成循环。

**二、五处改动**

| # | 旧行为 | 现在 |
|---|---|---|
| 1 | `AvailabilityBodyMemory` 把三种报文体各判过一次「静默」后永久返回 `NONE`，`0x4300` 再也不发 | 车机自己在可收清单里声明能收 `0x4300` 时，静默记忆**不许堵**；全静默回首选形态（通用系 = 双标识体，与 `link_plus` 的 `modernBootstrap` 一致） |
| 2 | `0x4300` 一律发后不管，丢了就丢 | 「新协议 + 声明可收」改走链路确认 + 最多 **2 次重传**（RTO 4s，共 8s），并新增引擎级 `dropWhenExhausted`：补不上只把这一帧摘掉、打一行日志，**不 die、不拆链** |
| 3 | 首轮 `0x4300` 等 8s 无 `0x4301` 就进保活 | 在同一条蓝牙链路上重开一轮识别+认证（`0x1D00 → 0x1D01 → 0x1D02 → 0xAA00 段 → 0xAA05`）再宣告一次 `0x4300`，只做一次；任何一步没回立刻收手交给保活泵 |
| 4 | 发送窗口按本方声明的 `0x7F` 连发 | 改为 `min(本方, 对端)`；**对端声明零重传时不裁剪**（它不会回 ACK，套小窗 = backlog 永久卡死） |
| 5 | 可收清单之外的号照发（`0x4E0D`/`0x4E0E` 等） | 新协议车机进**严格模式**：清单外一律不发；`0x1D02` / MFi / `0x4300` 这些骨架永不裁（裁了永远连不上）。旧协议奥迪行为逐字节不变 |

另外两处一并进版：

- **AirPlay 资源归属改状态机**（`AirPlayResourceState`）：`/info.modes` 的 `transferType` /
  `takeConstraint` / `initialPermanentEntity` 决定主屏与主音频归谁，不再无条件回「永久归手机」
  （奥迪 `/info` 就带着 `transferType=1`，即车机已接管主屏）。`changeModes` / `requestUI` 统一走状态机，
  成功判据新增 `commandAccepted`：**RTSP 200 且 body `status==0`**（旧实现只看状态行，车机回
  `200 OK` + `{status:-1}` 时被当成功，现场表现是「都说成功了，过一会儿断」）。
- **入站蓝牙 socket 的物理关闭走有界池**：`Semaphore(6)` 记「在用/在关」的套接字，关闭交给 2 线程
  有界队列，额度只在 `close()` 返回后归还；额度用尽退让 500ms 再 accept。旧实现把阻塞的
  `BluetoothSocket.close()` 堆在 accept 循环里，车机反复回连时进程级 RFCOMM 配额被吃满，
  现象是「越连越差，重启 App 就好」。

**三、验证**

单测 **596 例 0 失败**（较 1.2.34 的 569 例新增 27 例：严格模式、发送窗口三条边界含「零重传对端不裁剪」、
`dropWhenExhausted` 双向边界、资源归属 8 例、重认证闭环、socket 关闭池额度）。`BUILD_TAG` 清空。

**四、上车观察点**

1. `iap2 tx=0x4300（等确认，最多重传 2 次；补不上只丢这一帧，不拆链）` —— 这一帧真的发出去了；
2. `iap2 重认证：首轮 0x4300 未换来 0x4301 …` —— 走到了重认证那一轮；
3. `CTRL: … GET /ctrl-int/1/connect` —— 车机的 ctrl 邀请，出现它才会有 `0x4301`。

若三步都走完仍无 `0x4301`，下一个怀疑对象是报文体本身（换 `MODERN_NESTED`），
而不是认证轮次或链路层。

### 1.2.34 —— 2026-11-10（撤掉画面停摆自动干预，看门狗退回「只观测」，正式版）

本版是**减法**：把 1.2.32 加进去的「画面停摆分级干预」整套拿掉。

**一、为什么撤**

1.2.32 的干预逻辑是：画面冻住 8 秒 → `requestVideoReinit()`；每 8 秒再推一次；
30 秒仍未恢复 → `client.stop()` 强制重建 CarLife 会话（阈值随次数翻倍，30→60→120 秒）。

2026-11-10 16:34 的实车日志（小米 Android 17 + Audi MMI 3232，1.2.33 包）把这三个动作全都跑了一遍，
**一次都没救到画面**，反而每一处都在误导判断：

- **空转还刷日志**：引擎退出（`7240 未监听`）之后，`requestVideoReinit()` 往一个没人监听的端口发
  98386+98311，发出去没人接，日志却仍按 8 秒节奏输出「停摆 N 秒——请求引擎重建编码器」。
  在日志里，「引擎不在」和「正在被我们抢救」长得一模一样。
- **阈值翻倍让它永远迟到**：上一轮已把阈值顶到 60 秒，第 5 轮停摆到 36 秒时「强制重建会话」
  还没到点，用户自己手动断了。救火动作比人还慢，就没有存在意义。
- **它自己会 `client.stop()`**：排障时最忌讳「观察者改变被观察对象」——一旦它动手，
  「四条通道同时 reset」到底是引擎自己重建，还是我们拆的，就分不清了。

**二、撤掉的范围**

| 位置 | 内容 |
|---|---|
| `video/StallJudge.kt` | 8s 重建 / 30s 拆会话 / 阈值翻倍的纯策略，整文件删除 |
| `video/StallRecoveryPrefs.kt` | `settings/video_stall_recovery`、`video_stall_by_frame_age` 两个偏好键，整文件删除 |
| `video/StallJudgeTest.kt` | 7 个用例，随策略一起删除 |
| `carlife/CarLifeHijack.kt` | `stallInterventions` / `lastStallReinitAt` / `stallReconnectDelay`、`isPhoneInCall()`、看门狗里整段 `StallJudge` 分支 |
| `res/layout/activity_main.xml` | 设置页「画面停摆自动恢复」整块 |
| `MainActivity.kt` | 该开关的绑定与提示 |

关键一点：**看门狗再也不会调 `client.stop()`**。这条路径现在不存在。

**三、保留的（纯诊断，无副作用）**

- 「画面源停摆 N 秒（最后一帧 Xs 前，本窗口 N 帧，零个新 IDR）……屏幕状态=点亮/锁屏」与
  「画面源恢复（停摆 N 秒）」；
- 判据仍是**最后一帧距今**，不是旧的「2 秒窗口帧数」——通话/语音接管期引擎降到 0.8fps 心跳帧时，
  旧判据按定义必然误报，新判据（心跳帧间隔 ~1.25 秒）不会；
- `VideoSender.noteWriteStall()`：帧写进锁前打点、`finally` 清零，>3 秒未返回记一行（同一笔 5 秒限流），
  只记日志、**不关 socket**。

另外把 `BUILD_TAG` 清空——1.2.33 的正式包版本行尾还挂着 `#p1`（上一轮 P1 实验的残留标记），
本版起正式包不再带尾巴。

**四、验证**

单测 **569 例 0 失败**（较 1.2.33 少 7 例，即删掉的 `StallJudgeTest`）。
`docs/video-stall-recovery-20261108.md` 顶部已标注「只作历史记录」，当前实现以代码为准。

### 1.2.33 —— 2026-11-10（链路层认得出「车机拒绝」：`FF 55` 握手族不再被静默丢弃，正式版）

只改一件事，但改的是排查体验：**把「车机明确说不兼容」从「日志里根本不存在」变成 1 秒内一行原因。**

**一、症状：等 60 秒，然后什么都不知道**

在某些车机上连上去后没有画面、没有报错，日志从头到尾只有「协商无进展」，然后等上层 60 秒预算耗尽、
拆链、重连、再来一遍。原因不在我们的超时，而在**我们把车机的回答整个丢掉了**：
读帧头只认 `FF 5A`，`FF 55` 那一族被帧头扫描逐字节跳过，一个字节都没进过状态机。

**二、`FF 55` 和 `FF 5A` 是两族报文，不是同一族的变体**

`FF 5A` 是链路数据帧；`FF 55` 是握手/探测族，其中：

| 报文 | 形态 | 含义 |
| --- | --- | --- |
| DETECT marker | `FF 55 02 00 EE 10`（6 字节定长） | 唤醒/探测，车机可能每秒重发上百次，正常，忽略 |
| 握手应答 | `FF 55` + u16LE 载荷长度 + 载荷 | 对端对握手的回答——**「我不支持你」就在这里** |

车机侧路径（MIB3 SW3874，`iap2::CIAP2LinkHandshake`，状态机 `UNITIALIZED / HANDSHAKING /
NEGOTIATING_PARAMETER / INITIALIZED / UNSUPPORTED`）是 `sendHandshake → evaluateHandshakeAnswer →
不兼容时 sendLackOfBackwardCompatibility`，那句「不兼容」正是以**非 marker 的 `FF55`** 发出。
另一条确定性证据是 LSP 首字节 `LinkVersion`（7 个链路参数里的第一项）。

**三、这版怎么处置**

1. 认出 marker → 丢弃、**不回调**。它每秒重发，回调只会刷屏；
2. 非 marker 且长度字段可读 → 计一次握手应答，`onPeerHandshake` 把原始字节打进日志；
   **累计 2 次、且一条 LSP 都还没采纳**（说明参数协商根本没开始）→ 判 `peer rejected link` 立即终止；
3. 长度读不出合理值（截断的 `FF5A`、别的端序）→ 只记诊断、**不计入拒绝**，丢 2 字节继续扫描；
4. 对端 LSP 的 `LinkVersion != 1` → **立即**终止，不等第二次（这是确定性证据）。

**四、为什么不「见一个怪包就拆链」**

链路层最怕误杀：正常链路里也有截断、粘连、别的端序的字节序列。所以判据是「**两次 + 参数协商尚未开始**」
两个条件同时成立，而不是见到 `FF 55` 就断。真要退回旧行为，`Iap2LinkEngine.failFastOnHandshakeRejection
= false` 即可——只记日志、不终止。

**五、验证**

- 新增 6 个单测：一次拒绝不算、两次拒绝要断、`LinkVersion=2` 立即断、marker 连发 5 个不杀、
  已采纳 LSP 之后怪包不杀、单字节拆包也能认出；全量 **576 例 0 失败**。
- `Iap2LinkChannel`（真实蓝牙链路）与 `VirtualIap2Link`（测试台）两条链路都接了诊断日志，
  两个入口行为一致。
- 规格与证据记入 `docs/CARPLAY-WIRELESS-HEADUNIT-SPEC.md` §2.3.1。

### 1.2.32 —— 2026-11-09（车机麦克风上行 + 语音键判据可读 + 画面停摆分级干预，正式版）

本版四条线：**把车机麦克风接进来**、**让语音键的断点可判读**、**让被提示音打断的音频/停住的画面自己回来**，
外加社区 PR #20 的构建可移植与 issue #14 的流顺序修复。

**一、车机麦克风上行：方向之前是错的，这版改对**

1.2.31 里的探针假设「另开一路 `input=true` 的流」，这个方向错了。xcertplay 的接收端实现与注释
确认了真 iPhone 的做法：把**自己的 UDP 端口**写进 `type=100` 主音频流的 `dataPort`，车机看到端口
就往这里推（"input port, rather than the downlink category, signals that the phone requests a mic"）。

- `runAudioStream` 新增 `micPort`：>0 时才在 stream dict 里写 `dataPort`；默认 0 = 纯下行，
  与 1.2.31 行为**完全一致**，是可选字段，随时可回退；
- 新增 `audio/MicUplink`：绑本地 UDP 端口 → 派生
  `HKDF-SHA512(shared, "DataStream-Salt<connId>", "DataStream-Input-Encryption-Key")` → 收包 →
  解 `[RTP 头][ChaCha20-Poly1305 + 16B tag][8B 尾部 nonce]`（AAD = 头 `[4..12)`）→
  大端 s16 换序（LPCM 是大端）→ 缓冲（上限 20 秒）；
- 首包按 **5 种格式假设**依次试解（尾部 nonce 小端 / 大端、无尾部用计数器、AAD 有无），
  Poly1305 tag 命中即锁定并打日志——各家实现这三处不一致，验一次要等上车；
  RTP 头长度按 CC / 扩展位动态算，不硬切 12；
- PCM 落 `下载/CarPToCarL/CarPToCarL-mic-<时间>.wav`（MediaStore，无需权限）。车机到底推不推、
  解出来是不是人声，下车听一遍就知道——不用再猜；
- 流重建 / 换档时新 SETUP 继续带同一端口并 `updateKey()`（盐是 streamConnectionID，换流必换密钥）；
- 旧的独立 `input=true` 探针停用：它会多建一条同类型流去抢车机资源。

> **边界说清**：本版只完成「收 + 解 + 落盘」。把 PCM 注入 CarLife+ 引擎（9242 通道）留给下一版，
> 真车也还没验过车机是否真的往这个端口推流。

**二、语音键：从「靠猜」到「有现成证据」**

上一轮我判错了：说「这台车机完全没发语音键」，而用户贴的日志里 08:52:56 的 `requestSiri` 明明按了
7108ms、松手也发了 `0x22`——我依据的两份导出是「仅本次启动」范围（09:26–09:32 / 09:36–09:37），
08:52 那次会话根本不在窗口内。**教训：先看导出范围，再下结论。**

真正的断点在 CarLife+ 引擎侧：`0x68008` 送达后引擎没起语音。字节码实证其判定链是
`CarlifeCarHardKeyCode.parseFrom → ReverseHardKeyChannelManager.transferKeyCodeIfNeed →
CarlifeTouchManager.onHardKeyCodeEvent`，`checkCarLifeChannelStatus()` 不过就直接丢键；另有按车机
渠道号禁用语音硬键的白名单（20082120 / 20742105）。

- `CarLifeProtocol` 新增 `Module`（1 电话 / 2 导航 / 3 音乐 / **4 语音** / 6 麦克风 / 8 巡航 / 9 跟车），
  取自 `ModuleStatusManage.<init>` 的 `ModuleStatusModel(id, 0)`；
- `65574` 以前只回 `98344`、零日志；现在**变化时**打一行模块状态，并对语音模块单独点名
  （`=1` 引擎已进语音态 / `=0` 命令被判无效）——这是「`0x21` 有没有被接住」的唯一现成证据；
- `docs/carlife-voice-siri-20261104.md` 第 7 节整段重写，附两个零成本对照实验
  （点 CarLife+ 自己的语音按钮，看模块状态变不变）。

**三、微信提示音把音乐卡一下：311ms 的空档没补包**

车机日志里只有一次完整中断（09:29:45）：

```
45.458  媒体 引擎音频停止（0x30003）——来电或音频路由被切走
        …311ms、一个音频包都没有…
45.768  媒体 音频格式宣告 44100Hz 2ch 16bit
45.769  媒体 引擎音频开始（0x30002）
```

两条原因叠在一起：CarLife+ 为切换音频路由**自己停掉媒体 PCM**（改不了），而我们在这 311ms 里
**一个包都不发**——`AudioSender` 的静音保活阈值是 300ms（`IDLE_FILL_AFTER_MILLIS`），几乎刚好
没赶上。车机 jitter buffer 被排空，恢复瞬间要重新缓冲，听感就是「卡一下」而不是「静音 0.3 秒」。
现在引擎停时把阈值压到 0（`beginIdleFill`）、补帧上限提到 16 帧 / tick，恢复时不再需要重新对齐
（`onEngineAudioGate` 只有停 ≥2s 才 `resyncAnchor`）。详见 `docs/wechat-notify-audio-stall-20261109.md`。

**四、画面停住之后，不再只是「等它自己好」**

参考同源项目 AMCar 1.0.48 的 `startStallWatchdog()`，差异只在「冻住之后怎么办」：

- **判据**：从「2 秒窗口帧数 < 4」改成「最后一帧距今」。来电 / 语音接管期引擎降到 0.8fps 心跳帧，
  24 秒窗口里只有约 1.6 帧，旧判据必然误判；心跳帧间距约 1.25 秒也不该算停摆。真冻住仍是 8 秒触发，
  「本轮一帧未收到」（-1）继续不判，避免引擎刚起来就被拆。旧判据留在
  `StallRecoveryPrefs.judgeByFrameAge=false` 后可回退；
- **行为**：从只观测改成分级干预——8s 请求重建编码器、每 8s 轻推、30s 翻倍封顶 120s 强制重建会话。
  依据是 AMCar 1.0.15 的真机结论：不干预时停摆最长 128 秒，且每次自然恢复都伴随新会话。
  通话中（`MODE_IN_CALL` / `IN_COMMUNICATION` / `RINGTONE`）不干预；同一次停摆只拆一次；
- **写卡死可观测**：`noteWriteStall()` 在进锁前记时间、`finally` 清零，超过 3s 未返回记一行。
  永久阻塞的写不会进任何「已完成」统计，日志上与「引擎没出帧」完全同形，而两者处置相反；
- 停摆日志带手机屏幕状态（`isInteractive` / `isKeyguardLocked`，无需权限），用来区分「手机侧画面被
  占用」与「链路/引擎问题」；
- 设置页新增「画面停摆自动恢复」开关（默认开），关掉即回到只记日志。

**五、其它**

- **流顺序按接收端学（修 issue #14）**：JYBOX-80 / 沃尔沃 Sensus 上「屏幕 RECORD 成功、音频 SETUP
  也成功，但为把音频纳入会话而补发的第二次 RECORD 被 500」，接收端随即关掉自己的 AirPlay 服务。
  两条顺序各有各的死法（RECORD 优先会被 MIB3 的 2s 看门狗拆链，音频优先则是这个新型号唯一能走的），
  所以不取全局默认：型号快路径（magic-car-link / jybox）+ 默认档那一轮学出来的结论
  （补发 RECORD 非 200 就记住该接收端用音频优先，连第一次 RECORD 都非 200 则反向纠正），
  三行日志可判读（`STREAM: 顺序 = …`）；
- **尺寸声明对齐 AMCar**：默认只写解码尺寸、视口 32..48 整段留 0（原来「解码 + 视口分离声明」只是
  对方一个非常规档）。`VideoCanvasMode` 由 boolean 改三态（`raw` 新默认 / `stream` 原默认留 A/B / `car`），
  偏好值向后兼容。视口只是头部元数据，三种形态送出去的 H.264 帧完全一样；
- **认证：网络可复用、认证不继承**：新增 `FreshAuthPolicy`，同一接收端连续 2 轮未建立会话 → 下一轮
  清掉 `PairingMemory` 的结论、强制走完整 pair-setup（成功一轮归零，换接收端归零，用户取消不计）；
  每次发 `VideoConfig` 打一行 `coded / visible / declared` 三值诊断，判据改成 declared vs visible；
- **构建可移植**（社区 PR #20）：`distributionUrl` 回到官方源、去掉写死的本机路径，fork 者与 CI
  无需作者环境也能构建；
- 单测 **566 例 0 失败**（新增 MicUplink 解包 8 例、StallJudge 7 例、StreamOrderMemory 与尺寸声明
  三态若干），`assembleRelease` 通过。

### 1.2.31 —— 2026-11-08（WiFi 入网改「静默自动连」+ 超时宽限 + 权限一次申请，正式版）

一条主线贯穿本版：**车上没人点屏，入网却每一步都在等人点**。这版把整条链路改成能自己走完，
并顺带修掉两个**会绑错网 / 会丢断线回调**的实质 bug。

**一、1.0.7 是怎么「不用点」就连上的**

1.0.7 的入网只有一条路：直接发 `WifiNetworkSpecifier`，没有任何确认弹窗，也不需要建议授权。
用户实测**确实不用点任何东西就能连上**——说明系统对该热点已有授权/关联记录时，
`requestNetwork` 会被**静默满足**、不弹框。真正要人点的，是后来我们自己在失败分支上
加的那些对话框（「重试 / 打开 WLAN 设置」「清除保存 / 保留」）。

**二、入网顺序回到 1.0.7，建议通道只做兜底**

| 顺位 | 做法 | 要不要人 |
| --- | --- | --- |
| 1 复用 | 系统已连在该热点（已保存网络 / 上轮残留）→ 只 `bindProcessToNetwork` | 零交互 |
| 2 specifier | 直接发 `WifiNetworkSpecifier`——**与 1.0.7 完全同一条路**，有既有授权时静默满足 | 零交互 |
| 3 建议兜底 | 只有 specifier 真进不来（`Unavailable`）才登记 `WifiNetworkSuggestion`，等系统自己关联 | 零交互 |

> 上一版曾把「建议」排在 specifier 之前，依据是 2026-10-29 宝马 iD9 日志里
> 「specifier 每次都弹框」的结论。但那条结论与本机实测不符：建议层排在前面会**每轮先白等
> 12 秒**，反而把 1.0.7 那条快路挤掉。现在按实测纠正——specifier 先上，建议只在失败后兜底。

**三、超时宽限：原先等于每轮都比系统早 0.7 秒放弃**

同一台手机（HONOR WKL-AN20）对同一台车（BMW94841 / NBT EVO）的参考日志里，入网实测耗时
**15.729 秒**（`13:05:23.407 尝试静默加入` → `13:05:39.136 ✓ 已绑定网络`），而旧窗口是
`SPECIFIER_JOIN_TIMEOUT_MS = 15_000L`，且**对所有车机通用**。超时分支第一件事是
`release()`（注销 `NetworkCallback` + `bindProcessToNetwork(null)`）——系统刚建好的网络连带
回调一起被扔掉，下一轮从零再来，界面永远是「正在加入车机热点…」。

改动：`joinCarPlayNetwork` 新增 `graceMillis`（默认 0，其它调用方行为不变），正式窗口到点后
**先不解绑**、继续等 20 秒宽限；`onUnavailable`（系统明确判定不可用）不享受宽限，立即返回，
失败提示不会因此变慢。抽出 `awaitJoin()` 统一「成功/超时/取消」三态，避免两份几乎一样的
循环在其中一份漏掉取消判断。

**四、顺手修掉的两个真 bug**

| Bug | 后果 | 修法 |
| --- | --- | --- |
| 「绑定当前 WiFi」按 `TRANSPORT_WIFI` 泛匹配 | 手机同时挂着家里 WiFi 时绑到**错的那条**：AirPlay 必然连不上，且其它应用瞬间断网 | 删除该函数，统一 `adoptAssociated` **按 SSID 精确**找网络再绑；探针 `probeAirplayOnCurrentWifi` 一并换掉 |
| 静默绑定不走 `requestNetwork` | 丢掉 `onLost` → **被动断开的自动重绑整条链路失效**（热点关闭时一行日志都没有） | 新增 `watchLoss()`，用 `registerNetworkCallback` 只监听断线 |

另外 `adoptAssociated` 要求 `linkReady()`（网络已拿到地址）：刚关联、DHCP 未回时绑上去建
socket 必然失败。

**五、删掉的手动路径**

`promptSpecifierFailed`（重试 / 打开 WLAN 设置）→ `retryJoinSpecifierSilently` 自动重试 2 次；
`promptForgetSavedNetworkIfAny`（清除保存 / 保留）→ 只记日志、**一律保留**（保留反而能让系统
进范围自动连，正好走第 1 层）；`legacyJoinCarWifi` 的确认框；`manualJoinCarWifi` 与
`copyHotspotPsk`（复制密码 + 跳 WLAN 设置 = 纯手动）；`waitForManualWifiThenContinue` 里的跳
设置页；保活期「检测到已连接 X，按车机热点继续？」弹窗。现在**只剩「WiFi 未开启」还会弹框**，
且该分支因没有 shell 权限实际走不到。

`SilentJoinPolicy`（新增，纯逻辑 + 5 例单测）：建议未授权时静默每轮必白等 12 秒，而车机只等
约 27 秒就拆链——所以静默**连续失败 2 次**才降级到确认框路径，成功一次即归零。

**六、系统权限框 4 次 → 1 次**

装包后仍看到「要与 CarPToCarL 配合的设备」。查证：这句话**不在本仓库**任何代码或资源里
（`grep 配合` 只命中注释），它是 Android「附近的设备」权限组的**系统**授权框——标题里带应用名，
由 `BLUETOOTH_CONNECT` / `NEARBY_WIFI_DEVICES` 触发。1.0.7 的权限清单与现在完全一致，
所以不是这版引入的；**真正的问题是弹得太多**：同一权限组被拆成多次请求，系统只能一个个弹。

| 位置 | 申请什么 | 现场感受 |
| --- | --- | --- |
| `onCreate` | `BLUETOOTH_CONNECT` | 每打开一次 App 弹一次 |
| `ensureBluetoothReady` | `BLUETOOTH_CONNECT` | 与上一条同组，又一个框 |
| `ensureWifiLocationReady` | `NEARBY_WIFI_DEVICES` | 同组，第三个框 |
| `startConnectionNow` | `POST_NOTIFICATIONS` | 直到授予为止，每次点连接都问 |

改法：

- 新增 `connectionPermissionLauncher`（`RequestMultiplePermissions`）与 `requiredConnectionPermissions()`：
  13+ 一次请求 `BLUETOOTH_CONNECT` + `NEARBY_WIFI_DEVICES`，12/12L 请求 `BLUETOOTH_CONNECT` +
  `ACCESS_FINE_LOCATION`。**同组权限放进同一次请求，系统只显示一个框**。
- `ensureBluetoothReady` 变成「一次申请 → 位置服务（仅 12/12L）→ 蓝牙开关 → 放行」；
  删掉 `ensureWifiLocationReady` 及其 `locationPermissionLauncher` / `nearbyWifiPermissionLauncher` /
  `pendingStartAfterLocationPermission` / `pendingStartAfterBtPermission`（收进 `pendingStartAfterPermission`）。
- 删掉 `onCreate` 的「启动即申请蓝牙权限」：改成真正要用（点启动连接 / 自动连接）时才问。
- 被拒后**不再重复弹系统框**（系统从第三次起自己就不显示了，调用只会立刻返回未授权，
  白白让人觉得程序在反复索要）：`connectionPermissionDenied` 记住本进程已拒，
  改为一次自家提示（说明缺哪一项 + 「去设置」直达应用详情页），`permissionHintShown` 保证只弹一次。
- `POST_NOTIFICATIONS` 改成每进程只问一次，拒绝后不再纠缠（它不影响连接）。
- 「蓝牙兼容模式」开关改用分组申请，且不置 pending（不会误触发连接）。

绕不过的那一次：这是 Android 系统级授权，只能用户点一次「允许」。真要彻底去掉，
得改用 `WifiNetworkSuggestion` 全量兜底（照样有首次授权）或让用户手工入网，代价都比一次点击大。

**验证**：`:app:assembleRelease` + `:app:testReleaseUnitTest` 通过，单测 **530 例 0 失败**。
真车待验（测试包阶段用 `BUILD_TAG` 做了区分，正式版已清空）。

> **本版为同日重发**：版本号仍是 `1.2.31`（`versionCode` 10231 不变，可直接覆盖安装同日早先的
> 1.2.31），Release 资产已整体替换为新包（13,668,873 B，sha256 `42f2640d…`）。
> 之所以不升号：这一版还没真正上车验收，先把手上这个包修干净再往上加号。

### 1.2.30 —— 2026-11-08（按车机 `0x1D01` 声明分派 + 宝马适配 + 移除 EA 支线，正式版）

1.2.29 之后连续三轮改动，主线从「只把车机声明打进日志」推进到「按声明分派」，同时并入
一份宝马适配 fork 的实测结论、删掉一条用不上的支线。**奥迪 / 通用系既有主链路未动**，
唯一会直接影响已跑通车型的是「首帧后不再补发 RECORD」（见下）。

**一、车机声明终于参与发送决策**

车机在 `0x1D01` 里声明「会发什么（参 6）/ 可收什么（参 7）/ 支持什么组件（参 12/13/24/30）」，
运行时还会用 `Start…Updates` 动态点名要手机推状态；此前项目只有 `/info` 与 mDNS 两层驱动行为。

| 改动 | 说明 |
| --- | --- |
| `AccessoryDeclaration` | 跨蓝牙 / WiFi 隧道的声明快照单例——蓝牙的 `0x1D01` 比隧道布防早约 5.5 秒，裁突发必须用它；`startConnection()` 每轮 reset，不拿上一台车的清单裁这一台 |
| 越权取证下沉 | `noteSend()` 装在四个发送出口（蓝牙 `send`/`sendFireAndForget`、隧道 `sendCsmLocked`/`sendRawCsmLocked`），隧道侧此前连 WARN 都不打 |
| **唯一硬裁** | 只裁 `0xA100` / `0xFFF0`：宝马 NBT Evo / iD6、通用系 Cinemo 都没声明可收，整批发过去 42~116ms 内事件通道 EOF、AirPlay 服务重启（mDNS `pi` 变化）。**只裁这两条，不关整条隧道**——宝马会连发 SYN marker 等会话表，关掉换来的是一直黑屏 |
| 判据不变 | 清单只能证明「有」不能证明「没有」（奥迪 3315 只列 5 项却完整支持 `0x5702`）→ 其余越权消息照发，只记 `UNSOLICITED_MESSAGE` WARN；清单未到时全部按未知放行 |
| 组件声明 | 解析语言 / 无线 CarPlay 组件 / 导航显示组件进画像「依据」，暂不参与 `era`/`wifiGen` 硬判定（部分车机走私有编号）；`0x4E0A` 语言按声明规范化为 ISO 639-1 两字母码，无声明时保留原兜底 |
| 订阅应答 | 新增 `Iap2SubscriptionReplies`，蓝牙侧补 `0x4157→0x4158`、`0x4154→0x4155`、`0x4170→0x4171`、`0x5200→0x5201`，与隧道侧共用同一份空态字节；导航只回 `0x5201` **空体**保住控制通道，没有 maneuver 数据源就不编造报文 |

按用户要求**排除参数 10 的 EA 协议组**：不解析、不恢复 EA 会话。文档同步 `docs/PROTOCOL-NOTES.md` §15、
规格文档 §2.7 / §2.8 / 附录 E。

**二、并入宝马 fork 三项修复**（`docs/bmw-fork-port-20261108.md`，来源实测 BMW 94841 / G38 NBT EVO
连续推流 670 秒；用户选择三项全局生效、不做车型开关）

1. **CarPlay 标记 SDP 常驻**：`BtCompatPrefs` 默认值 `false → true`，App 启动即注册
   `2d8d2466-…` + `02030302-…` 两条占位记录并常开 iAP2 监听。车机把设备判成
   「不支持 Apple CarPlay」发生在 discovery/配对阶段，等点「启动连接」再注册已经晚了。
   设置里开关仍在，关掉即回旧行为。
2. **音频配置守卫**：`BtProfiles.guardAudioProfiles()` 每 1.5 秒复查目标车机的 HFP/A2DP/LE Audio，
   在线就断开（车机自己会拉回来，一次性 disconnect 不够）；退出条件含 iAP2 bring-up 完成、
   开始推流、盯满 5 分钟。新增 `AudioProfileGuardPolicy` 纯逻辑与 4 例单测。
3. **首帧后不再补发 assertModes + RECORD**：这段本是给奥迪 4580「连接成功但黑屏」加的，
   但宝马 NBT EVO 会在首帧后重发 RECORD 时直接关掉视频通道。原实现留在注释里。

> ⚠️ 已知取舍：奥迪 4580/4910 那层「重建屏激活」兜底随之失效（用户已知情并接受），
> 若复现「状态栏说在推流、车机黑屏」，按 `installNowPlayingBridge()` 里的注释接回即可。

**三、整体移除 MIB3 EA 车机控制支线**

按用户要求连协议层 EA 会话一起删：删掉整个 `mib3/` 包（控制面板 / viwi 编解码 / EA 隧道）、
`Iap2SenderSession` 的 EA 解析与 `0xEA00~0xEA04` 收发、相关常量与回调。
**刻意保留**链路层对车机声明的 `{1:control, 2:EA}` 会话表 ACK 与 2 号会话数据帧的 ACK——
前者是 iAP2 链路协商硬要求，后者不确认会让对端不停重传拖慢控制会话。
三份 EA 文档加废弃横幅留作历史取证。

**验证**：`:app:assembleRelease` 通过，单测 **525 例 0 失败**（1.2.29 的 518 例 − 删除 EA/viwi 17 例
+ 音频守卫 4 例 + 声明分派 20 例）。宝马 iD6、奥迪 MIB3 需真机复测：
现在 `0xAA05` 后的突发会自动少发 `0xA100`/`0xFFF0`，其余 5 条照发。

### 1.2.29 —— 2026-11-07（身份对齐：`0x4E09` 固定报 iPhone、链路初始序号随机，正式版）

1.2.28 发版后接着跑 myCadillac 与别克 E2LB，两处都指向同一个问题：**车机眼里的「我们是谁」**
对不上。本版只改这两点 + 补一份手册对照，主链路其余部分一处未动。

**一、`0x4E09` 自报身份固定 `iPhone`，与蓝牙名彻底解耦**

| 项 | 内容 |
|---|---|
| 起因 | 车机蓝牙列表把本机显示成「未知设备」，1.2.28 当天把 `0x4E09` 的 device name 改成本机蓝牙名（`BKQ-AN10`）——展示是修好了，但换掉了「我们是谁」这个最不该动的东西 |
| 证据 | 真机 Frida 抓包（MIB3 隧道 `VirtualIap2Link` 同款常量）与市售盒子在这一层发的都是 `iPhone`；`0x4E09` 是身份声明，不是展示名 |
| 修法 | 新增常量 `Iap2SenderSession.IAP2_DEVICE_NAME = "iPhone"`，构造参数 `deviceName` **直接删掉**（少一个能传错的口子）；`DeviceIdentity.ensureUsableBluetoothName()` 只负责蓝牙名，并过滤占位名（未知设备/未命名/unknown/`null`/`N/A`/`android`） |
| 防回归 | 单测 `deviceInformationUpdateAlwaysReportsIPhone`：`MockAccessory` 记录 `0x4E09` 报文体，断言全文都是 `iPhone` |

蓝牙名与身份分开之后，「未知设备」仍由 `ensureUsableBluetoothName()` 兜底改名解决，两条路不再互相牵制。

**二、iAP2 链路初始序号改为随机**

myCadillac 13 轮重连里，486 个 SYN 字节**完全一致**（`seq=99`），车机连链路层 SYN-ACK 都不给。
此前初始序号是常量 `Iap2LinkEngine.INITIAL_SEQUENCE = 99`，所有链路、所有重连都一样。

| 项 | 内容 |
|---|---|
| 依据 | 手册 §4.3：初始序号 0–255 随机取；真机与 catplay `take_own_seq` 亦然 |
| 修法 | `sentSequence = SecureRandom().nextInt(0x100)`，每条链路新建时取一次；对端本来就是用 `ack` 回显「期望的下一个」，与初值无关，改这个不动任何账法 |
| 单测 | `Iap2LinkEngineTest` 改为「以本方 SYN 的 seq 为基准 +1（模 256）」断言，不再硬编码 `99/100` |

**三、附《CarPlay 车机开发手册》对照核查（`docs/carplay-manual-crosscheck-20261107.md`）**

把外部手册针对发送端主链路（蓝牙 iAP2 → Wi-Fi 凭证 → AirPlay 会话）的建议逐条核了一遍：
**10 处候选改动，采纳 1 处，明确不采纳 9 处**，不采纳的每一条都写了本项目实车证据
（负载 CRC-16、SYN 发起方、认证先于识别、`0x5702` 方向、EIR 字节序、会话 ID、SYN 3 次判死等）。
同时指出手册自身两处硬错误：EIR 两列写反、`0x5702` 角色写反。

### 1.2.28 —— 2026-11-07（通用系现场四项修复：SSID 兜底 / MFi 序列号握手 / mDNS 探测 / 只认苹果车机开关，正式版）

1.2.27 发布后连续跑了六轮别克 E2LB（`BUICK_8948`，Cinemo 栈）实车日志，本版全部改动都由
日志里可复现的现象驱动，**奥迪 / 宝马 / DiPlay 测试台已跑通的路径一处未动**。

**一、等 `0x4301` 期间读不到 SSID：唯一的兜底路被代码堵死**

等待期日志每 30 秒都在写 `Wi-Fi=已开但未连接任何网络`，可同一份日志里
`WIFI: wifi=[wlan0/10.154.116.159] default=wlan0/…`——手机其实挂在车机热点上。

| 项 | 内容 |
|---|---|
| 根因 | `shellWifiStatus()` 只走 `cmd wifi status`；Shizuku 移除后普通应用身份跑不了这条命令，`ok=false` 直接返回 SSID=null |
| 后果 | 「检测到已连接 XXX，按车机热点继续」弹窗**要求 SSID 非空**才触发 → 手动入网 + mDNS 邀请这条完全不依赖 `0x4301` 的路，整份日志里一次都没跑到 |
| 修法 | 新增 `frameworkWifiStatus()` 兜底：`ConnectivityManager` → `NetworkCapabilities.transportInfo` → `WifiInfo.ssid`，只需定位/附近设备权限；读不到**仍返回 null**，不猜（绑错网比不绑更糟） |
| 日志 | 区分「未连网络」与「读不到 SSID」，等待期提示去车机「连接 → 车辆热点」抄 SSID/密码 |

**二、通用系新协议代对齐参考实现（`link_plus` 的 `modernBootstrap`）**

这两台车的现场形态一致：识别、MFi、问候全部通过，车机最后一次 ACK 停在问候的最后一帧
（`ack=110`），其后的 `0x4300` 连链路层 ACK 都拿不到，`0x4301/0x6800/0x5000` 全程零条。
逐行对照同源工程后发现三处差异，全部补齐：

| 改动 | 旧行为 | 新行为 |
|---|---|---|
| MFi 先要序列号 | 直接 `0xAA00` 要证书 | 车机 `0x1D01` 的「会发」清单里列了 `0xAA06` 时，先发**带空 param0** 的 `0xAA00`，收到 `0xAA06` 认证序列号后再要完整证书（车机「发序列号」分支此前 6 轮 0 次被触发） |
| `0x4300` 时机 | `awaitOptionalHid` 在 `0x6800` 上空等 2 秒，`0x4300` 被压到 `0x1D02` 之后约 2.7 秒 | 新协议代问候完**立刻**发；晚到的 `0x6800` 由接收循环就地应答（`respondToStartHid`），HID 通道不会丢 |
| 问候顺序与语言 | 名称 → 语言(`zh-CN`) → … | 新代：UUID → 时间 → 语言(`zh`) → 名称；旧代（奥迪等）保持原顺序与 `zh-CN` |

> 报文体形态这条线到此为止：无线单标识（`MODERN_NESTED`）、有线标识（`LEGACY_FLAT`）、
> 两段 group（`DUAL_GROUPS`）**三种都试过**，全部被车机 ACK、全部无回应。
> 详见 `docs/gm-4300-dual-identifier-20261107.md` §6——不要再试第四种。

**三、保活期在 WiFi 侧找一次车机 AirPlay**

手机挂在车机热点上 6 分钟，我们一个 WiFi 侧的包都没发过，「车机到底起没起 AirPlay」
在日志里查不到，这是排查时最大的盲区。新增：等 `0x4301` 期间每 **30 秒**在当前网络里
探一次 `_airplay._tcp`——找到就直接走 AirPlay（不依赖 `0x4301`），找不到也留下
`PROBE #n: 没有 _airplay._tcp` 这条硬证据。

**四、新开关：兼容「只认苹果设备」的车机（默认关）**

宝马 iD6 在蓝牙列表里直接给本机打「设备不支持 Apple CarPlay」，这句判定发生在 iAP2 之前
——改 `0x4300` / 识别顺序在这条路径上全无作用，它看的是发现层（EIR 里的 iPhone CarPlay
UUID、SDP 里的 Wireless iAP v2）。Android 应用层写不了 EIR，能做的只有 SDP 占位与
「别让车机看不到服务」：

- 开关打开时额外注册两条占位记录（CarPlay `2d8d2466-…`、Wireless iAP v2 `02030302-…`），
  不做任何协议，连进来只记日志并立即关闭——那行 `BT-PROBE` 就是「车机确实在看这个 UUID」的铁证；
- 监听常开：`Iap2BluetoothServer.start()` 幂等对齐占位记录，`SessionController` 在 App 启动时即挂
  监听，30s 看门狗自愈——按「先配对、后开 App」的顺序操作时车机那一瞥不再什么都看不到；
- 区分「挂着等」与「有会话」（`idleListening`）：`isSessionRunning()` 不再把挂机监听算作会话，
  否则自动重连会被自己挡掉、通知栏误报「已连上」；手动断开置 `compatSuppressed`，看门狗不得自动挂回；
- 顺手修 `Iap2BluetoothServer.acceptLoop`：`IOException` 退出时没把 `running` 置回 false，
  导致 `isRunning` 说谎、`start()` 永久短路（监听假死）——常开监听依赖此修复。

**五、构建标记与测试**

同一 `appVersionName` 下当天出过 5 个测试包，11:41 那轮就因日志头只写 `1.2.27` 而误判
「新包已生效」，白跑一轮实车。新增 `AppVersion.BUILD_TAG`，日志头显示 `1.2.28 #标记`
（正式版为空），以后同版本号的测试包可直接分辨。单测 **517 例 0 失败**（新增 7 例：
`BtIdentityProbeTest` 6、`Iap2SenderSessionTest` 的 MFi 序列号握手 1）。

**仍未解决**：别克 E2LB 在三种 `0x4300` 报文体、`0xAA06` 序列号握手、提前 `0x4300` 全部到位后
依然只有 `0x1d01/0xaa01/0xaa03` 三条主动消息；若下一轮 `PROBE` 显示车机热点上**没有**
`_airplay._tcp`，即可判定是车机侧 CarPlay 应用未被唤醒，与我们的 iAP2 帧无关。

### 1.2.27 —— 2026-11-07（新旧协议自动切换 + 协议一致性审计，正式版）

依据新增的 `docs/CARPLAY-WIRELESS-HEADUNIT-SPEC.md`（车机端/接收端视角的无线 CarPlay
规范），把「新旧协议差异」全部收口成**一处判定**，并把每一处「规范要求」与「车机实际
回的」不一致落成可导出的问题清单。协议报文内容与顺序不变。
（1.2.26 为 `switch-pool-auto` 测试线专用号，未进主线，故主线顺延至 1.2.27。）

**一、判据收口：画像成为唯一开关**

改动前新旧判定散在三处：`ReceiverProfile` 算出 era 只写日志、`SessionController` 自己
读 TXT bit38、`AirPlayFeatures.supportsPairing` 又被单独调用一次。三者一旦不一致，就会
出现「画像判定老车机、连接逻辑却去跑 pair-setup 空转 120s」这类事故。现在
`ReceiverProfile` 新增 `PairingPolicy`（era → 策略的**唯一映射**）与 `dispatchSummary`，
`SessionController` 改为查画像结论，不再自己解析能力位。

**二、换固件自动换代（`protocol/ProtocolGeneration`）**

按车机身份（name+model+manufacturer）持久化 `era|wifiGen|srcvers|features` 快照。
关键约束：**只有「已知 → 已知且不同」才算换代**。`0x1D01`（蓝牙）与 mDNS 到达顺序不
固定，先到的那一方必然有一侧是 UNKNOWN，把 UNKNOWN→已知 也算换代会导致每台车第一次
连上都误报。真换代时：按新一代协议重新分派 + 作废该车机旧 MFi 档位记忆（`MfiLengthMemory.forget`），
避免「升级固件后这台车突然连不上、只能清数据重来」。

**三、协议审计（`protocol/ProtocolAudit`）**

9 个审计码，每个都绑定规范章节号，实时输出、可 grep、可导出：

| 码 | 含义 | 依据 |
|---|---|---|
| `WIFI_GEN_MISMATCH` | 画像判的 WiFi 凭据代际与车机实际回的消息号不符 | SPEC §2.10 |
| `LEGACY_WIFI_SILENT` | 判为旧代际但 `0x5703` 在窗口内完全没来 | SPEC §2.10 |
| `PAIRING_POLICY_MISMATCH` | 实际走的配对/明文路径与画像结论不一致 | SPEC §3.3 |
| `MFI_LENGTH_REJECTED` | MFi 挑战长度被车机确定性拒绝 | SPEC §2.9 |
| `FEATURES_TXT_VS_INFO` | mDNS TXT 的 features 与 `/info` 的 features 不一致 | SPEC §3.2 |
| `DEVICE_ID_MISMATCH` | TXT deviceID 与 `/info` deviceID 不一致 | SPEC §3.2 |
| `UNSOLICITED_MESSAGE` | 发了对端 `0x1D01` 未声明可收的消息 | SPEC §2.8 / §7 |
| `NO_ENDPOINT_ADDRESS` | 端点没有任何可用地址（仅 IPv6 / 地址为空） | SPEC §3.3 |
| `ERA_SWITCHED` | 该车机协议代际发生翻转（换固件） | SPEC §6 |

日志固定格式，同 `(码, 细节)` 一轮只记一次（`0x5702` 重发、端点热更新不会刷屏）：

```
PROTO! WARN [WIFI_GEN_MISMATCH] 新旧 WiFi 凭据代际判错 —— 画像判 wifiGen=NEW（等 0x4301），
车机却回了 0x5703（依据 SPEC §2.10 / §7；证据 ssid=AUDI_MMI_5G 信道=149）

PROTO 审计（会话退役）：共 2 项（1 警告 / 1 提示）→ WIFI_GEN_MISMATCH ERA_SWITCHED
```

干净的一轮会打「PROTO 一致性审计通过」，用户问「这次有没有协议问题」不必再翻几百行日志。

**四、文档与测试**

`docs/CARPLAY-WIRELESS-HEADUNIT-SPEC.md` 新增**附录 D**：差异点 → 代码切换点 → 审计码
对照表 + 输出格式 + grep 方法。单测 **494 例 0 失败**（新增 17 例：`ProtocolAuditTest` 7、
`ProtocolGenerationTest` 7、`ReceiverProfileTest` 3）。

**五、通用系（Cinemo）：补上 0x4300 的另一半 + 设备名不再发「未知设备」**

2026-11-07 别克 E2LB（`BUICK_8948`）现场，详见 `docs/gm-4300-dual-identifier-20261107.md`：

| 现象 | 根因 | 改动 |
|---|---|---|
| 车机只回链路 ACK（`ff 55 … ee …`），`0x6800/0x5000/0x4c00/0x4301` 一条都不发起，一直卡在「获取 WiFi」 | 我们抄的「新版嵌套体」其实是同源工程 `link_plus` 的**旧版分支**形态——新版车机要的是**两段 group**：`group0={0:1, 1:"00008110"+MAC+"0000"}`（有线标识）+ `group1={0:1, 1:MAC 小写}`（无线标识） | 新增 `AvailabilityBodyMemory.Variant.DUAL_GROUPS` 与 `GM_ORDER`；通用系（manufacturer 含 `General Motors` 或 `0x1D01` 带 `Cinemo` 组件）优先试它，静默后仍按 `MODERN_NESTED → LEGACY_FLAT → NONE` 回退 |
| 车机蓝牙列表把这台手机显示成「未知设备 / 已连接」 | MagicOS 本地蓝牙名未设置时 `BluetoothAdapter.getName()` 返回本地化占位串，旧实现只判 `isNotBlank()`，于是 `0x4E09` 把「未知设备」发给车机 | `DeviceIdentity.displayName()` 过滤占位串；取名顺序改为 适配器名 → `Settings.Secure.bluetooth_name` → 营销名 → `Build.MODEL`；`ensureUsableBluetoothName()` 在占位时 `setName()` 改成可用名（车机列表与 `0x4E09` 一起修） |

奥迪 / 宝马 / DiPlay 测试台的 `0x4300` 顺序**不动**（它们现在都是通的），双标识体只给
通用系开——修一台车不能把已经跑通的链路改坏。单测 **500 例 0 失败**（新增 6 例：
`AvailabilityBodyMemoryTest` 4 例双标识阶梯、`DeviceIdentityTest` 2 例占位名过滤）。

**六、车机支持报文对不上：按 Apple 规格档案补 144 条目录**

依据 Apple `iap2messages-internal.i2mspecarchive`（同源工程 catplay 的注册表）新建
`iap2/Iap2MessageCatalog.kt`。改动前只认 40 条，于是规范里有、本项目没实现的消息
（`0x4E09~0x4E0C` 车机状态、`0xAA00~0xAA05`、`0xAD00` …）全被报成「陌生 ID」，
`0x1D01` 能力清单比对时也会把车机**声明可收**的条目误判为未知。

| 改动 | 说明 |
|---|---|
| `Iap2MessageIds.nameOf()` 兜底走目录 | 同时删掉两条**泛化区间**（`0xAE00..0xAE03`、`0x415B..0x415C`）——它们把 `0xAE02`（StopPowerUpdates）、`0x415C`（EndCall）盖成了错名字 |
| `0xEA04` 标「推断未确认」 | 规范 EA 段只到 `0xEA03`，故不进目录；单测锁定，以后别顺手加进去 |
| `Iap2CapabilityReport.hasName` | 判据从「本项目实现过没」改为「规范里有没有」——这才是「车机支持报文」该问的问题 |
| `groupOf` 补齐分组 | 新增 `0x0Bxx / 0x4Cxx / 0x0Dxx / 0x10DA / 0x4172 / 0x4302 / 0x5700 / 0x4E01~05 / 0xB1xx / 0xFFF0~FFFC`，并新增 `ACCESSIBILITY` 组 |

目录按 IDENTIFICATION / WIRELESS / MEDIA_NAV / VEHICLE 四段拆 private map 再合并
（单个 map 里 ktlint 会删掉段间空行，分组就看不出来了）。

**七、全链路自查：让日志直接回答「卡在哪一步」**

- 「已发未声明可收消息」的埋点从 4 个手工点**下沉到 `send()` / `sendFireAndForget()` 唯一出口**，
  覆盖 20+ 种发送；`0x1D01` 还没到时返回 `null`，不误报；
- **失败轮次也有收尾结论**：新增 `ConnectBudget.end(cause, keywords)`，接入三条最常见的半路退出
  （等 `0x4301` 超时、热点没绑上、配对线程结束但没推流），日志固定打
  `STAGE: ✗ 本轮未连上，卡在「<阶段>」：<原因>｜关键词：<grep 词>`，再补一行小结
  ——以前这些轮次只有「本轮开始」没有小结，看起来像卡死；
- `expect()` 超时文案带消息名（`等待 0x1d01（IdentificationInformation）超时`），不再只有裸 ID；
- 文档修正三处：`0x5703` 参数表（1=SSID / 2=密码 / 3=安全类型 / 4=信道，**没有 IP 字段**）、
  EA 协议槽（参数 10 是 zero_or_more，不是 10~12）、`0x1D01` 组件参数表；
- 文档新增**附录 E**：18 项逐阶段结论、四条有意偏离（伪 USB 传输标识、`zh-CN` 语言码、
  不实现 `/fp-setup` 与 `/auth-setup`）、17 行日志诊断索引（按现象查关键词）；
- 单测 **510 例 0 失败**（新增 `Iap2MessageCatalogTest` 6 例，含反射断言：`Iap2MessageIds`
  的全部 `const Int` 除 `EA_SESSION_DATA` 外必须在 144 条目录里；`ConnectBudgetTest` 4 例
  覆盖三条半路退出与关键词输出）。设备侧未解决项见文档附录 E 末尾（宝马 `0x4E0C` 载荷异常、
  通用系 `0x4300` 双标识体形态待现场验证）。

### 1.2.25 —— 2026-11-06（对照 catplay 复核连接链路：bit37 能力位、邀请自愈、0x4301 布局判定，正式版）

**参考项目**：`catplay-labs/catplay`（Rust 净室 CarPlay 协议实现，双角色）、
`ludwig-v/wireless-carplay-dongle-reverse-engineering`（Carlinkit 固件逆向）。
三项都是纯增量改动，**协议报文内容与顺序不变**。

**一、bit37 `CARPLAY_CONTROL` 接入判定链**

`AirPlayFeatures.CARPLAY_CONTROL(1<<37)` 此前定义了却全仓零使用，等邀请窗口全靠
按固件串猜车机代际。catplay 的 `AirPlayFeature::CARPLAY_CONTROL` 语义是「车机声明
由它主动发起连接」。现在等邀请窗口读到 `_airplay._tcp` 的 TXT `features` 后明确判定
并写进日志：未声明 = legacy 车机（不等邀请直接连）、已声明 / 未知 = 保持既有放行。
不改变任何已成功的连接路径，只让「这台车会不会发邀请」一眼可见。

**二、邀请先到、端点未就绪的 cache_miss 自愈**

catplay `CarPlayCtrlServer::process_invite` 在 Bonjour 缓存里找不到车机条目时**不丢
邀请**，置 `cache_miss` 等条目晚到自动 unglitch。我们的等价问题：`onCtrlInvite` 在
`lastGoodEndpoint == null` 时直接 return，把邀请信号丢了，此后 0x4301/mDNS 晚到也
不会再把它算作触发源。现挂起待决邀请（30s TTL），`awaitCtrlInvite` 消费后立即放行，
`onCarPlayEndpoint` 端点就绪时清标记，避免跨轮误触发。

**三、`0x4301` 无线组只认两套真实布局**

catplay `CarPlayStartSessionWirelessAttributes` 逐字段印证 1.2.24 的主路径是对的：
`0=SSID, 1=密码, 2=信道, 3=IP(可多个), 4=安全类型`。而 `0x5703` 是**另一条消息**
（`AccessoryWiFiConfigurationInformation`：1=SSID, 2=密码, 3=安全类型, 4=信道），
并不存在「0x4301 整套 ID +1」这种变体——1.2.24 里那条 `id3=信道/id5=安全类型`
的兜底是虚构的，遇到 0x5703 式布局反而会把安全类型读成信道。现删掉它和「ID 小的
是信道」的猜测（0x5703 布局里恰恰相反），改为按**字段形态**二选一：A 的 id2 是单字节
信道 / B 的 id2 是密码串、id3 是单字节安全类型。单测补 B 布局、B+信道落在 1..4
重叠区、A 缺信道不得把安全类型当信道共 3 个回归（共 7 例）。

**四、音频档位优先 48K AAC（修 Q4 3873 重采样卡顿）**

`audioCandidates` 的 `aacOptions` 原先把 44.1K AAC 排在 48K 前面。而 CarLife 恒输出
48K PCM（`MAX_RATE=48000`），于是被迫做一次 48K→44.1K 重采样；重采样出的流锁在手机
48K 晶振上、并非车机自己的 44.1K DAC 时钟，双晶振时钟域漂移在播放 3~5 分钟后累计成
卡顿（Audi Q4 3873 / DELPHI 实车稳定复现）。改为 48K AAC 在前后，同时声明两种 AAC
的车机直接走零重采样通路，整套时钟问题消失。

### 1.2.24 —— 2026-11-05（修 0x4301 无线凭据解析：信道/安全类型按 ID 定位，兼容 1 基车机；补 /info 输入能力解析，正式版）

**一、问题：某些 WPA2 车机确定性连不上，日志里 SSID/密码都对**

0x4301 的无线组子参数，规范是 0 基（`0=SSID, 1=密码, 2=信道, 3=IP, 4=安全类型`），
但个别车机把整套 ID +1（`3=信道, 5=安全类型`）。旧实现用「取值区间盲猜」区分：
信道 1..196、安全类型 0..4——二者在 **1..4 区间重叠**。于是 1 基车机 + 信道 1~4 时，
`singles[4]` 里其实是信道值（1~4）被当成安全类型，真实安全类型（在 id5）被漏掉，
结果 `channel=0`、`securityType=信道值`——按开放网络去连 WPA2 热点，必然失败。

**二、修法：按 ID 定位，不猜取值**

- 0 基：`id2=信道、id4=安全类型`（Apple 规范；WinPlay 车机模拟器构造 0x4301 也是这套，
  `build/engine/iap.js` 里 `param(2, channel)` / `param(4, [2])` 实测）；
- 1 基兜底：`id3=信道、id5=安全类型`；
- 都读不到：按 ID 顺序兜底——低 ID 是信道、高 ID 是安全类型（规范里信道 ID 永远小于安全类型 ID）。

解析逻辑移入伴生对象并新增 `ParseWirelessApTest`（0 基 / 1 基 / 信道落 1~4 重叠区 /
缺失 SSID / 4 字节 IP 排除，共 6 例）。现有 `Iap2SenderSessionTest` 的 0 基回环
（channel=36、security=2）保持不变。

**三、顺带：/info 输入能力解析进日志**

`CarDisplayGeometry.Screen` 新增 `features`（位图：0x1 KNOBS / 0x2 LOWFI_TOUCH /
0x4 HIGHFI_TOUCH / 0x8 TOUCHPAD）与 `primaryInputDevice`（1 触屏 / 2 触摸板 / 3 旋钮），
并解码进「/info 共 N 块屏候选」那行日志。此前只有注释里人肉写着「奥迪 features=0x08」，
面板不解析，旋钮车机盲发触摸时无从诊断。**纯增量**：不改屏幕选择与触摸路由，
只把证据摆到日志里。

### 1.2.23 —— 2026-11-05（隧道发送改为一帧一个传输单元，修 QNX 车机随机卡死，正式版）

**一、问题：投屏跑到一半随机卡死，且不是每次都死**

这类故障最难归因——没有固定复现步骤、日志里也看不到异常，因为**报文本身都没错**，
错的是「报文怎么装进传输单元」。2026-11-05 的《CarPlay 手机侧分析报告》§9 把这条
写成了硬性契约：**同一传输只放一个 iAP2 包，接收侧按流式处理即可，发送时不要合并；
违反后果是 QNX 车机随机卡死。**

我们确实在合并。`AirPlayClient.IapTunnelClient.writeIapBytes` 把
`engine.takeOutput()` 的**全部**字节封成一个 `APTransportPackage` 写出去，而链路引擎
的输出缓冲里经常连着「同步标记 + 一个应答 + 一个数据帧」；MIB3（HARMAN，正是 QNX 系）
在 `0xAA05` 之后的认证突发更是一次连发 8 条消息。接收端按「一个传输单元 = 一个包」
解析时，第二个帧的包头就落到了第一个帧的载荷位置上，链路直接错位。

**二、改法：按帧边界切分，但一切以「不丢帧」为前提**

新增 `Iap2LinkPacket.splitFrames`，两种包头分别处理：

| 包头 | 长度字段含义 | 切分依据 |
| --- | --- | --- |
| `FF 5A`（数据帧） | 头后的 u16 是**整帧**长度（9B 头 + 载荷 + 1B 校验） | 直接按该值切 |
| `FF 55`（同步标记） | 头后的 u16 **不是**长度（`FF 55 02 00 EE 10` 里 `02 00` 大端读 512、小端读 2，两个都不对） | 按固定 6 字节形态 `FF 55 02 00 EE 10` 识别 |

保守兜底：首字节不认识 / 声明长度越界 / 末尾有残帧，**一律整段原样返回**，调用方按
旧行为「一坨」写出。丢一帧等于控制链路死锁，宁可保持现状也不要为了切开而丢数据。

首轮出现多帧时打一行日志（`输出缓冲含 N 帧 → 已按帧拆成 N 个传输单元`），下次上车
就能确认这台车机是否真的踩到了合并形态；只打一次，不刷屏。

**三、影响范围**

只影响走 iAP 隧道（type-130）的车机；走事件通道 `iAPSendMessage` 的老车机行为完全不变。
`flushOutputLocked` 的 `mib3Armed` / `eventLinkArmed` 分支仍把多帧塞进一条
`iAPSendMessage` 命令——那条路奥迪 MIB3 真车验证过可用，报告也没说 HTTP 命令要拆，
本次不动；若将来出现「突发只生效第一条」，先查这里。

**四、验证**

- 新增 `Iap2LinkFrameSplitTest` 9 例：单帧 / 两帧 / 仅同步标记 / 三帧 / 未知包头 /
  前导杂字节 / 残帧 / 脏长度 / 空输入。
- 单测 **431 例 0 失败**（1.2.22 的 422 + 新增 9），`assembleRelease` 通过。
- **没有实车回归**：QNX 车机上的实际效果要等下一次上车验证。

**五、本版顺带移除 `7a2243a`（原「fix(net): 复用缓存 Network 前校验系统仍承认，修 ENONET 死循环」）**

该提交会导致**车机取消链接后再次链接时重复卡在「加入」**：重连时复用的缓存
`Network` 仍被系统承认，但车机侧的会话已失效，于是反复发起加入又失败。1.2.23 的
重新发版把它从提交历史里摘除（rebase 删除 + 强推），本版构建不再包含该改动。若之后
又遇到「断开再连卡加入」，优先排查 `net/` 里网络缓存复用的逻辑，不要再沿用 7a2243a
的那条修法。

### 1.2.22 —— 2026-11-04（语音键改走 requestSiri + 长按收尾，正式版）

**一、1.2.21 的语音键在这些车上一次都没触发**

把手机上的日志翻出来对了一遍：`CarPToCarL-log-20261103-085250.txt` 08:52:12~42
连着 9 条 `EVENT: 命令 type=requestSiri params={siriAction=…}`，而 **没有任何一条
`0x6802` 报文明细、没有任何 `0xCF`**；`/info` 里下发的 Consumer HID 描述符
（46B 的 Array 版 / 35B 的位图版）列出的 usage 只有
`0xB0/0xB1/0xB3/0xB4/0xB5/0xB6/0xB9/0xBC/0xCD`，**没有 Voice Command**。

也就是说：这台车的方向盘语音键走的是 **AirPlay 事件通道 `requestSiri`**，不是 iAP2
HID。1.2.21 里那条 `0x6802 0xCF → 0x68008 0x21` 是死代码（别的车型可能真的用 0xCF，
故保留作兜底）。另外事件通道 `hidSendReport` 那条路直接调 `dispatchMediaKey`，
即使真来了 0xCF 也只会注入系统键而不是转给 CarLife —— 这处也一并补上。

**二、`siriAction` 的取值不是猜的**

Apple `AirPlayCommon.h` 的 `AirPlaySiriAction`：
`0=NotApplicable / 1=Prewarm / 2=ButtonDown / 3=ButtonUp`。日志形态正好对上：
只有第一次按键前有 `1`，之后每次按下 `2`、松开 `3`；实测按住时长
0.89s / 2.15s / 3.69s / 3.87s。

**三、收尾（VR_STOP）怎么补**

新增 `carlife/VoiceKeyRouter.kt`，把 `requestSiri` 与两条 HID 通路收进同一个状态机：

| 事件 | 动作 |
| --- | --- |
| `siriAction=1` Prewarm | 只记日志，不触发 CarLife |
| `siriAction=2` / HID 0xCF 按下 | `0x68008 KEYCODE_VR_START=0x21`（边沿触发，按住连推不重发） |
| `siriAction=3` / HID 释放，按住 < 1.5s | **不发 VR_STOP**，交给 CarLife+ 自己按静音/超时结束 |
| `siriAction=3` / HID 释放，按住 ≥ 1.5s | 补 `0x68008 KEYCODE_VR_STOP=0x22`（PTT 收尾） |
| 15s 内没等到释放 | 状态作废，不猜、不补发 |

为什么不是「松手就 STOP」：`ButtonUp` 只是物理按键状态，CarPlay 里用户常按住语音键
等提示音、松手后才开口说话；这时立刻发 `VR_STOP` 会把刚起来的识别掐掉。阈值 1.5s
把「等提示音就松手」（实测 0.89s）和「按住说一句话」（实测 2.15~3.87s）分开。
详见 [docs/carlife-voice-siri-20261104.md](docs/carlife-voice-siri-20261104.md)。

**四、线程与校验**

- 起/停命令仍走 `carlife-cmd` 单线程，不占 iAP2 收帧线程（ACK 不能被本地 socket 写卡住）。
- `VirtualIap2Link` 在 HID 状态式报告的**下跳沿**新增 `onHidKeysReleased`，
  只对「刚才确实有按键」的那一次回调，空闲时车机持续推 0x00 不会刷屏。
- 单测 **422 例 0 失败**（新增 12 例 `VoiceKeyRouterTest`，含把实测日志的
  1/2/3/2/3/2/3/2/3 序列重放一遍，断言 4 次 START + 3 次 STOP）。

### 1.2.21 —— 2026-11-04（CarLife 语音键 + 车机 GPS 转发，正式版）

**一、方向盘语音键接进 CarLife**

车机的语音键在 CarPlay 里走 HID：`0x6802 AccessoryHIDReport` → Consumer 页 `0xCF`
（Voice Command）。以前它被当成「无对应 Android 键」直接忽略——本机没有 Siri，
按下去什么都不会发生。

现在 `ConsumerKeys.keyCodeFor(0xCF)` 返回 `KEYCODE_VOICE_ASSIST` 作为统一标识，
`SessionController` 拦下它，转成 CarLife 车机硬键：

```
0x68008 MSG_TOUCH_CAR_HARD_KEY_CODE  +  CarlifeCarHardKeyCode{ keycode = 0x21 }
```

`0x21 = KEYCODE_VR_START`（官方车机 SDK `CommonParams`），CarLife+ 收到后用**手机自己的
麦克风**起语音识别——所以不需要回传车机麦克风音频，只是一条控制命令。

**二、车机 GPS 转给 CarLife**

1. `VirtualIap2Link` 新增 `0xFFFB LocationInformation` 分支：报文体是
   `[len][id=0][ASCII]`，按 `$` 切分（一条报文可能有多句）、NUL 补齐；
2. `iap2/NmeaLocation.kt` 解析 `$GPGGA`（经纬度/海拔/星数/HDOP/UTC）、`$GPRMC`
   （速度节→km/h、航向、日期）、`$GPGSA`（PDOP/VDOP，按**倒数三个字段**取，容忍省略的卫星槽）；
   `$PASCD` 是 Apple 私有语句（实测 43B：`$PASCD,1583.410,C,D,0,1,0.00,0.000`），公开资料
   没有字段表，**只识别、不猜坐标**；
3. 合并器 `NmeaAssembler` 把分条到达的句子拼成一份快照；GGA 报「无定位」时立刻作废坐标，
   不拿上一秒的旧位置冒充；
4. `CarLifeProtocol.carGps()` 按官方 `CarlifeCarGpsProto.proto` 写满 25 个字段
   （单位：经纬度 1e-6 度、高度 0.1m、速度 0.01km/h、航向 0.1 度、三轴速度 0.01m/s），
   `CarLifeHuClient.sendCarGps()` 900ms 节流下发。

**只在有经纬度且 fix 有效时发**：车机在地库/隧道里会持续推无坐标样本，发给引擎会让
地图飘到 (0,0)。

**三、「使用车机 GPS」开关**

显示卡里新增开关（偏好 `carlife_car_gps`，**默认开**）。关掉后一个字节都不发，
CarLife 完全用手机定位；打开时也只转车机真给了坐标的样本。

**四、顺手修的 bug**

`CarLifeProtocol.pbVarint/pbVarint64/pbBytes` 的 tag 用的是单字节 `toByte()`，
字段号 ≥ 16 时（tag ≥ 0x80）写出来缺少续位字节，对端会把下一个字节当高位——
整条消息从第 16 个字段起全部错位。之前所有消息字段号都 ≤ 15，所以一直没暴露；
车机 GPS 的 `pdop`(16) 正是第一个踩到的。已改成 tag 也按 varint 编码。

单测 **410 例 0 失败**（新增 18 例：`NmeaLocationTest` 12 例、`CarLifeCarGpsTest` 6 例）。

### 1.2.20 —— 2026-11-04（4580 重连：网关兜底漏 IPv4 + 手动断开后不给车机冷却，正式版）

1.2.19 上车后仍复现「断开就再也连不上」。1.2.19 实车日志（OPPO PLG110 / Android 17 /
奥迪 4910 `CL3D_MIB3H_AU_AS_G4x_2300200PROD`）把链路掐成两段，各自有明确结论：

- **前一段（16:21:12 车机主动连入）**：iAP2、WiFi、AirPlay 全通，10 秒建起会话。1.2.18 的
  EA 修复也生效了——三轮 0xEA00 都没换来 0xEA03，但链路一直活到用户手动断开，
  再没出现 `packet N not acknowledged` 拆链。
- **后一段（16:22:33 我们主动连入）**：车机给了 iAP2 身份、给了 0x5703 热点凭据，
  但 **48 秒里 `_airplay._tcp` 一次都没广播、0x4301 一次都没回**，最后只能拿网关兜底，
  8 次重连全部 `after 2000ms` 超时。

三处改动：

- **`WifiCarPlayConnector.gatewayAddresses()`**：原来 `gatewayAddress()` 只取
  `routes.firstOrNull { isDefaultRoute }`，而这台车机排第一的默认路由是 **IPv6 链路本地**
  `fe80::1086:3aff:fe8d:9239`，于是兜底全程打在这个从来验证不通的地址上；同轮 mDNS 给出的
  `10.174.189.1:7000` 才是通的，被整条丢掉。改为收集**全部**默认网关、IPv4 排最前
  （排序复用 `EndpointHosts`，新增 1 例单测留档）。
- **手动断开后 18 秒冷却**（`BtSessionGate.REACTIVATE_COOLDOWN_MILLIS`）：固件
  `mediaDisconnectTimeout = 15000ms`，设备没被判「已移除」之前 `keepIAP2Session=1`，
  车机只走
  `IAP2 session already running. Waiting for required IAP2 information.`
  这条分支——接受 RFCOMM、走完身份交换、下发热点凭据，**但不启动 CarPlay projection**。
  那一轮正是 `16:22:32.519` 手动断开、`16:22:33.696`（1.2 秒）就重连。现在出站循环在冷却窗口内
  先等够再拨，日志写明还差几秒。
- **`isAssociatedTo()` 补 ConnectivityManager 路径**：Android 12+ 上
  `wifiManager.connectionInfo` 对应用专属网络（`WifiNetworkSpecifier` 建的）常报
  `<unknown ssid>` 或 `netId=-1`，「手机其实还挂在车机热点上」被误判成没连，于是白发一次
  specifier、白等 15 秒确认框（16:22:37 判成没连 → 16:22:52 超时才靠建议网络兜回来）。

单测 **392 例 0 失败**（新增 1 例：网关兜底里 IPv4 必须排在链路本地 IPv6 前面）。**待上车验证**：断开后立刻重连，应看到
`BT-C: 距上次手动断开只有 N 秒——…先等 N 秒再发起`，且随后一轮能连上投影；
若仍失败，看日志里兜底地址是否已经变成 `10.174.189.1`，以及车机侧 7000 端口到底开没开。

### 1.2.19 —— 2026-11-04（4580 重连：EA 会话显式收尾 + 跨过车机的 15 秒移除计时，正式版）

> 现场：`4580`（C8/MIB3H，固件 `CL3D_MIB3H_AU_AS_G4x_2502501PROD`）**只有第一次能连**，
> 断开后一直卡在加入热点或 AirPlay 配对。固件依据见
> [docs/mib3-firmware-c8-4580.md](docs/mib3-firmware-c8-4580.md) 与
> [docs/mib3-carplay-display-contract.md](docs/mib3-carplay-display-contract.md)。

- **断开前先发 0xEA01（StopEASession）**：车机对 EA 会话有泄漏保护（固件原文
  `EAP session for eapSessionIdentifier: %d already exist` /
  `Failed to start EAP session, another session is already running`）——没被显式停掉的
  会话会挡住下一次连接，而它自己的清理要等 15 秒超时。三个「不发」的条件都保留了：
  车机没声明过 EA 协议 / 链路已关 / 对端超过 5 秒没回过帧（停摆的链路写了也没人处理）。
- **重连间隔下限 3s → 16s，退避阶梯 16/20/30s**：车机 `mediaDisconnectTimeout=15000ms`，
  蓝牙断开后要等满 15 秒才把设备判成「已移除」。3 秒就重连会把那个计时器一次次续上，
  车机于是永远停在 `keepIAP2Session=1` + `IAP2 session already running. Waiting for
  required IAP2 information.` 这条分支，既不重建会话也不重新协商 WiFi/CarPlay 状态——
  正是「只有冷启动第一次能连」。被动兜底轮数 3 → 2（否则切一次被动要等 48 秒）。
- **WiFi 先复用、后发 specifier**：断线重连时手机绝大多数时候还在车机热点上，直接
  `bindProcessToNetwork` 即可；再发一次 `WifiNetworkSpecifier` 就弹一次系统确认框，
  车上没人点屏，等到超时整轮失败。复用前用 `WifiManager.connectionInfo.ssid` 校验，
  读不到 SSID（`<unknown ssid>`）时**不猜**，老老实实走原路径。

### 1.2.18 —— 2026-11-04（奥迪 MIB3：EA 隧道不再写死链路 + 音频静音保活，正式版）

> 样本 `CarPToCarL-log-20261104-083034.txt`（1.2.17，`Audi_MMI_8349` / `AUDI 4260` /
> `DELPHI`）。分析与证据链见 [docs/audi-mib3-ea-tunnel-20261104.md](docs/audi-mib3-ea-tunnel-20261104.md)。

- **车机控制读不到数据 → 是它把整条 iAP2 链路写死的**：面板在 0xEA03（EA 会话状态）
  还没到时就发了 8 条 0xEA04（viwi GET）。三轮会话全部在**同一个序号**上出事——
  车机 `ack=114` 之后**再也不 ACK**，33~37 秒后我方重传看门狗判
  `packet 115 not acknowledged` 拆链。现在只读拉取必须先 `Phase.READY`（收到 0xEA03），
  没就绪只记日志并挂待办，0xEA03 一到自动补发那轮。
- **EA 协议清单解析**：车机用**同一个参数号 10 重复三次**声明
  `de.audi.mmiconnectdatatransfer` / `com.audi.cpp.eadata0` / `com.audi.cpp.eadata1`，
  旧代码 `associateBy { it.id }` 只留最后一个，于是 0xEA00 只发给了 `eadata1`
  （设计上数据该走 `eadata0`）。改为按出现顺序全收（`iap2/Iap2Ea.parseProtocols`，可单测）。
- **0xEA00 催问**：它是发后不管，8 秒内没等到 0xEA03 再催一轮（一次为限）——
  车机可能要到自车 CarPlay 栈起来（AirPlay 流 SETUP 之后）才认 EA 会话。
- **控制链路掉线自愈**：CarPlay 在跑时不再无条件静音出站。掉线那一台会被登记，
  出站循环只对它补连（最长 180 秒），连上即清；`detachCurrentSession()` 主动拆的
  不补。修掉「画面声音都在、控制数据永久失联、只能手动断开重连」。
- **音频静音保活**：音频流建立后超过 300ms 没有真实 PCM，就按实时速率补 AAC 静音帧，
  真实 PCM 一到立刻停补。首连时音频流空转 65 秒、之后第一段音乐被车机整段丢弃
  （重连后 0.4 秒就有包则立刻正常），差别只有「空转时长」。空闲开销约 43 包/秒、
  每包十几字节。
- 单测 **359 例全绿**（新增 4 例：EA 参数号可重复解析、旧 map 语义留档、空体、
  重复声明去重）。

### 1.2.17 —— 2026-11-03（送帧闸门：帧龄/背压可见 + 帧率取较小值，正式版）

> 参考 [iamr0s/carvex-carplay-research](https://github.com/iamr0s/carvex-carplay-research)
> 的 `VideoDeliveryGate` / `VideoRatePolicy`。**动作没有照抄**：CarVeX 自己持有编码器，
> 帧龄超 150ms 就丢帧并 `setParameters` 要一个 IDR；我们的编码器在 CarLife 引擎里，
> 丢 P 帧会花屏到下一个 IDR、也没法请求 IDR（98316 帧率变更、码率配置实测都无效），
> 所以只保留「观察」与「极端情况下跳 IDR」。

- **送帧闸门**（`video/VideoDeliveryGate`）：CarLife 帧头 `[4..7]` 本来就是毫秒时间戳，
  此前 `videoReader` 只读 `[0..3]` 的长度就把它丢了。现在用它算**排队延迟**：
  `(now - ts) - 历史最小 offset`，绝对基准被基线消掉，所以时间戳是 `uptimeMillis`、
  `currentTimeMillis` 还是别的毫秒都不影响。写入耗时另算背压：≥80ms 记慢写、
  ≥400ms 记卡顿，每 5 秒打一行 `送帧统计 … 排队 p50/p95/max；写入 p95/max`。
- **时间戳不老实就自动停用**：探测期 20 帧里取值少于 3 种、非单调超 5 次、
  或算出 >30 秒的排队，一律判定「这字段不是时间戳」，退回只测写入耗时，**绝不动画面**。
- **唯一的动作**：排队 ≥1500ms 且持续 ≥2s、当前帧非关键帧、距上次 >10s，
  才 `beginAwaitingKeyFrame()` 跳到下一个 IDR（代价 ≤1 个 GOP）。抖动只记账。
- **帧率取较小值**（`video/StreamFps`，对齐 CarVeX 的 `min(接收端, 宿主 hint)`）：
  车机报 120/240/999 一律按 60 声明——编码器与合成器最高只出 60，声明高了车机按
  更高节拍排帧却等不到帧，反而抖；车机报 30 仍然 30（硬约束必须让）。

### 1.2.16 —— 2026-11-03（从 switch-pool 同步通用修复，正式版）

> 只搬「与车型无关、主线和分支都会踩」的修复，`switch-pool` 的连接档位体系
> （AUTO/通用/别克大屏/奥迪 MIB3/大众 ID MEB）、Wi-Fi Direct P2P 建链、别克 B233
> 强制 32B MFi 挑战**没有**进主线——它们仍在分支上维护。

- **等 IDR 再喂帧**（`VideoSender.beginAwaitingKeyFrame`）：新会话 / 视频通道重连 /
  源重启三处统一置位，并记下起始时刻做超时兜底。此前 `startPassthrough` 里是
  `false`，配合 `seedCsd()` 预置的 csd，会把当前这一帧 **P 帧当新流首帧**发出去；
  车机解码器刚重建、没有任何参考帧，收到 P 帧直接拆整轮会话。ID7 实测 15 次断流里
  8 次是「首帧 NAL=1 → 0.1~0.2 秒被 reset」，而 7 次「首帧 NAL=7,8,5」全部活过 7 秒。
- **推流中收到车机邀请 → 记日志 + 主动重建**（`SessionController.onCtrlInviteWhileStreaming`）：
  以前推流态直接 `return` 丢弃，日志里一个字都没有；ID7 实测车机发邀请的语义是
  「我要重建会话」，不理它就在 18~20 秒后 reset 掉正在推流的会话（黑屏 → 重连 →
  再被邀请 → 再被拆，死循环）。现在落一行日志并按 15 秒防抖主动退役重建。
- **音频 SETUP 400 回退重试**（`AirPlayClient`）：被拒后按真 iPhone 的字段补
  `ct`/`sr` 再发一次。只影响「严格实现」（ID7 三档全 400），已跑通的别克/奥迪/宝马
  第一次就 200，走不到重试，零影响。
- **CarLife 未劫持提示帧**（`video/NoticeFrameEncoder` + `VideoSender.pushNotice`）：
  会话已建立但 CarLife 没推流时，把提示文字渲染成单帧 H.264 推到车机屏幕代替黑屏，
  每 5 秒重推、最多 60 秒，CarLife 一出帧就停。详见
  [`docs/carlife-notice-frame.md`](docs/carlife-notice-frame.md)。
- **csd 格式探测**：这台荣耀机的编码器把 csd-0 输出成 AnnexB 而非 avcC，`parseAvcC`
  硬解析时把起始码高字节读成 SPS 长度 → 负数长度 → `copyOfRange` 抛异常。现在先探测
  格式两条路都支持，`readNal` 长度改 `Long` 并做越界校验，畸形数据返回 null 不抛。
- **首帧闸门按「已送达帧」判定**：改用 `CarLifeHijack.framesDelivered` 相对本会话
  起点的**差值**（该计数跨会话累加、从不归零），只有车机真收到帧才算 ready；
  超时文案按 `CarLifeHijack.sourceActive` 区分「未劫持」与「已连接但车机未收到画面」。
- **CarLife 分辨率申请/确认拆分**：`98311` 发的是申请值，`65544` 回来的是引擎确认值，
  VideoConfig 声明改用确认值，修声明错配导致的 10 秒拆链。
- **单测 345 例全绿**；release 13,520,741 B。

### 1.2.15 —— 2026-11-03（新增 MIB3 车机控制面板，未单独发版，已并入 1.2.16）

> 首页多了一张「MIB3 车机控制」卡，点开是一个**只读 + 控制**两段式面板。链路是
> iAP2 的 EA 隧道（`0xEA00` 建会话、`0xEA03` 状态、`0xEA04` 数据），车机那头接到
> 内部 viwi 总线 `127.0.0.1:14751`。细节与「哪些是猜的」见
> [`docs/mib3-viwi-control.md`](docs/mib3-viwi-control.md)。

- **为什么不是 iAP2 加开关**：iAP2 里没有任何控制车辆的消息，`vehicleStatus`
  （`0xA100`/`0xA101`）在 MIB3 上还是 `enabled:false` 且只有「车 → 手机」一个方向。
  真正的入口是车机把 EAP 会话桥接到 viwi，该端口的 ACL 档位 `ext_devices` 对
  `/hvac`、`/door`、`/window`、`/lighting`、`/seating` 等端点开放全 CRUD。
- **只读区**：`/vehicleinformation`、`/drivingstate`、`/door`、`/window`、`/hvac`、
  `/distance`、`/maintenance`、`/gearbox` 八条 GET。面板同时显示链路状态、车机声明的
  EA 协议、`0xEA03` 原始回执、收发计数，以及最近 8 条原始响应。
- **控制区**：车门锁/解锁、车窗升/降、空调开关、22 ℃ / 24 ℃、车内灯开关、
  座椅加热 0/2 档，共 12 个动作。**默认禁用**——必须拨开「允许写入」，
  且每次执行都弹确认框显示实际要发的 JSON；解锁与降窗标为危险动作。
- **只在收到 `0xEA03` 后才允许写**：没有回执就发数据等于往没建起来的管道里灌字节，
  排障时会被误判成「报文格式不对」。
- **明说没验证**：`0xEA04` 的消息号、裸字节载荷、行式 JSON 封帧、字段名（`locked`
  / `position` / `active` / `targetTemperature` / `heatingLevel`）全部是推断的，
  集中在 `mib3/Mib3Viwi.kt` 与 `mib3/Mib3Endpoints.kt` 两处，实车抓包后改这两处即可。
- **顺带**：`Iap2SenderSession` 的 `send`/`sendFireAndForget` 加 `@Synchronized`——
  现在有第二条线程（面板）会往同一条 CSM 流里写，不加锁会把帧写花。
- 单测 345 例全绿（新增 13 例覆盖封帧、转义、切行、字段提取、端点清单约束）。

### 1.2.14 —— 2026-11-03（正式版 · 按奥迪 MIB3H SW3874 固件对齐协议参数）

> 对照一份针对 MIB3H（SW 3874 / `CL37_MIB3H_AU_AS_G4x_2302700PROD`）的静态逆向报告，
> 把「车机侧已经写明的事实」与本项目逐条核对（18 条，见
> [`docs/mib3-firmware-3874.md`](docs/mib3-firmware-3874.md)）。绝大多数条目本来就一致
> ——这套实现就是照着这台车的实车抓包长出来的。真正的差异三处，前两处已改：

- **视频编码器显式声明 Baseline profile**：车机 `carplay.json → screen.esogfx` 把解码
  格式写死为 `VIDEO_H264_BP`，且 `cropping/scaling` 全关、只有 5 个 OMX 输入缓冲、
  `badFramesThreshold: 4`——编出一帧它不认的 profile 就是黑屏，没有降级路径。我们过去
  不声明、吃设备默认值（多数机型默认就是 Baseline，所以能跑），换到默认 Main/High 的
  机型就会「连上了没画面」。现在先请求 `AVCProfileBaseline` + 按分辨率选 level
  （≤480p→3.0、≤720p→3.1、1080p→4.0、1080p60→4.2），**编码器不接受就退回不声明的
  老路径**，不会把原本能用的机型打死。
- **链路重传次数 4 → 10，并真正采纳对端声明**：固件里车机在 BT 通道声明
  `maxNumberOfRetransmissions: 30`、RTO 3000ms。我们此前只给自己 4 次——蓝牙侧偶发
  丢一帧时 12 秒就自己拆链，而车机愿意等 90 秒。折中取 10 次（30 秒），并让
  `Iap2LinkEngine` 按 LSP 语义**对本地与对端取较小值**（以前对端声明多少完全没用上）。
- **补齐消息目录**：OOB（`0x00B0~0x00B4`，无线 CarPlay 的带外蓝牙配对）、
  `0xEA02` RequestAppLaunch、`0xAA06` MFi 证书序号、location（`0xFFF0/FA/FB/FC`）、
  communications 全套（`0x4155~0x4172`）、power（`0xAE00~0xAE03`）、
  vehicleStatus（`0xA100/0xA101`）。以前这些帧在日志里只显示 `0x00B2`，看不出属于哪一族。

> 没做的三件（以及为什么）写在文档 §3：不改链路协商 60s 上限、不改 GOP、不实现 OOB 应答。

### 1.2.13 —— 2026-11-02（正式版 · 全代码自查修补）

> 这一版不加功能：对 155 个 Kotlin 文件、约 1.7 万行做了一次全量走查（含 lint），
> 逐条核实后修掉 12 处真实缺陷。其中三条会直接表现成「功能看起来没生效」，不是
> 极端时序才能碰到的边角。`switch-pool` 分支对应 1.2.15。

- **视频通道断了，上层永远不知道**：`VideoSender` 的 `onEnded` 只挂在
  `drainEncoder()` 的 `finally` 上，而产品实际只走 `startPassthrough()`——保活写失败
  只 `stop()` 不回调。后果是 `BtSessionGate.sessionActive` 永远停在 true（蓝牙探测被
  门闸挡掉，车机再邀请也不重连，只能手动点「启动连接」）、暗幕 45s 后把手机屏变黑、
  横屏覆盖不复原。现在两条收尾路径统一走 `endOnce()`（`AtomicBoolean` 只触发一次）。
- **蓝牙 iAP2 服务识别恒判「没有」**：`device.uuids` 是 `List<ParcelUuid>`，
  `it == ACCESSORY_IAP2_UUID` 拿 `ParcelUuid` 和 `UUID` 比恒为 false（`ParcelUuid.equals()`
  只认 `ParcelUuid`）。于是 SDP 里明明带 `…caff` 的车机被判 MISSING 整轮跳过
  （自动模式下永远连不上）；缓存快路径也永不命中，每台设备每轮白等两次 SDP（约 5s）。
- **旧视频读线程会把刚重连好的通道判死**：`reopenVideo()` 清零 `videoDead` 后，旧读
  线程若还卡在帧写入里，它的 `finally` 会在清零之后又置回 true → 看门狗下一轮关掉
  健康 socket 并 `restartEncoding()`，画面周期性黑屏 1~3 秒。改为只在「仍是当前
  socket」时判死（音频侧早有 generation 守卫，视频侧一直漏着）。
- **CarLife 手写 protobuf 解析补边界**：`parseModuleStatus` 在 tag 是最后一个字节时
  越界、子消息 `cursor` 未与 `end` 比较、`parseMediaInfo` 的 `copyOfRange` 在 varint
  跨边界时抛 `IllegalArgumentException`。这些异常会冒到 `workerLoop` 的 `finally stop()`
  ——一个畸形包就把 cmd/视频/音频三条通道全拆掉重来。
- **媒体监听器按 token 存回调并真正注销**：原来 `onListenerDisconnected` 只 `clear()`
  集合、不 `unregisterCallback`，重绑后同一批会话重复注册（每次播放变化触发 N 次
  refresh，旧回调还强引用旧 Service 与封面位图）；`onListenerConnected` 还会重复
  post 1Hz 轮询。
- **NSD `discovering` 加 `@Volatile`、`discoverServices` 不再抛异常**：`stop()` 可能
  读到陈旧 false 提前返回导致 discovery 没注销，下一次 `discoverServices` 撞
  `listener already in use` 抛进配对流程。现在失败时强制停一次再试，仍失败走直连
  端点兜底。
- **ctrl 广播异步注册失败时关端口**：`onRegistrationFailed` 只把 `registered` 打回
  false，旧 `ServerSocket` 与 accept 线程无人可关（下次 `start()` 用新 socket 覆盖
  字段，`stop()` 再也够不到旧的）→ 每失败一次漏一个 fd + 一条常驻线程。
- **恢复手机媒体音量加回读校验**：ROM 拒绝写音量时不抛异常，旧实现无条件删备份并
  写「已恢复」，手机可能永久停在静音。现在回读校验，未生效则保留备份并告警。
- **`SysShell` 读取线程与调用方同锁**：原来并发读写同一个 `StringBuilder`，且
  `reader.join(200)` 只是「最多等 200ms」——`dumpsys` 这类大输出被截断成半截字符串
  （解析侧只表现为「没找到」）。改为写入/读取同锁，`join` 放宽到 2s。
- **`MainActivity.logListener` 的 `!isFinishing || !isDestroyed` 写反**（应为 `&&`）：
  finish 后仍在往拆除中的视图追加日志。
- **`CarLifeHijack` 的 `audioSink`/`ttsSink` 补 `@Volatile`**：hijack 线程写、媒体通知
  线程读，车机暂停联动时灵时不灵。
- **`CarLifeHijack.attach` 先补历史 GOP 再开实时通路**：原来先置 `sender` 再补缓存帧，
  补帧期间到达的新帧会先发、更旧的缓存帧后发（解码顺序倒置、画面回跳），两个线程
  还会并发 `noteDelivered`。

顺带修了 `TouchRouter` 横屏地图命中区漏扣 `CARD_MARGIN_PX`（整块左移 16px；该链路
目前是死代码，仅保持一致性）。单测 332 例全绿。

### 1.2.12 —— 2026-11-02（正式版 · 自查修补）

> 这一版不加功能，只补「已经写下的逻辑里的漏洞」。其中有三条会直接表现为
> 「功能看起来没生效」：只发 `0x4301` 的车型自动重绑形同不存在、退避重试可能
> 自己和自己撞车、无事件口固件被误判成车机坏了。

- **只发 `0x4301` 的车型（BMW iD9 / 部分通用车机）自动重绑从没生效**：`lastCarWifi`
  只在 `0x5703` 里赋值，而这类车机根本不发 `0x5703`（AP 信息只走 `0x4301`）→
  断开后永远走「没凭据，只能提示」。现在端点交接处也用 `0x4301` 的
  ssid/passphrase/channel/securityType 记一份。
- **退避重试的令牌改在同一把锁内取**：原来先占排队位、出锁后才读令牌，中间若被
  用户点击或车机邀请 +1，这一次退避会把**新一轮**的令牌当成自己的，醒来判「没过期」
  并再起一轮——两轮同时配对必然互相 `retireSession`。
- **半握手判定要求「接收端确实下发了 `eventPort`」**：`eventPort == 0` 时
  `connectEventChannel` 是「没试过」而不是「连不上」，旧判定会让本来能用的无事件口
  固件被误判成「车机服务异常、请重启车机」而放弃本轮。
- **`WifiJoinGate.finish` 只记录「仍是当前那一发」的结果**：换过 SSID 后旧 ticket
  才结束时，会把**另一条热点**的失败写进去重状态（反向污染）；`await` 被中断时
  补回中断位。
- **`VideoSender` 的 `configSent` / `passthrough` 加 `@Volatile`**：帧线程写、保活
  线程读，无可见性保证时可能一直看到旧值——空闲重发全被丢（画面静止几十秒后车机
  拆链），或重连后漏发 config（一帧都过不去）。
- **AAC 编码器两处**：`put` 被 capacity 截断却按原始 size 记账（个别 ROM 抛
  `IllegalArgumentException`）；`encode`/`stop` 完全没有互斥，收尾并发必抛
  `Invalid to call during stop(); only valid in executing state`。现在统一排队长度、
  加锁互斥、停止后到达的帧静默丢弃并汇总一行。
- **同步包 ICMP 就地补发**：车机 `controlPort` 没监听时每包都被回 ICMP 端口不可达，
  而连接式 UDP 延后一拍上报 → 恒定「失败 1 次、成功 1 次」交替，锚点密度只有设计值
  一半、日志每秒刷两行。现在同一套接字立刻补发一次，锚点仍每秒一包（只提示一次）。
- **WiFi 心跳日志两处**：`eventCount` 每轮启动清零（否则「本轮抖动次数」是历史总和）；
  起点兜底校验 `TRANSPORT_WIFI`（WiFi 掉线切蜂窝后不会再 `rmnet_data0` 写进 `wifi=`
  项，看起来像「WiFi 接口变成了蜂窝」）。
- **`CarPlayCtrlAdvertiser.start` 异常路径关端口**：注册失败时既不关 `ServerSocket`
  也不复位 `registered`，每失败一次漏一个监听端口。
- 单测 **332 例全绿**（新增 2 例：被顶替的 ticket 不污染去重、仍唤醒等待方）。
- 逐条记录见 [docs/connect-recovery.md](docs/connect-recovery.md) 的「七、自查修补」。

### 1.2.11 —— 2026-11-02（正式版 · 连不上时的自救）

> 这一版不改善「正常车机能连多快」，只解决「连不上之后**没有人再试**」。
> 现场日志里最刺眼的一处：18:01 失败之后 11 分钟零动作，而车机在这期间发了 5 次
> 邀请，我们一次都没应。

- **同一 SSID 的入网请求互斥 + 5 秒去重**（新增 `net/WifiJoinGate`）：`0x5703` 与
  `0x4301` 会在同一瞬间各触发一次入网，而 `joinCarPlayNetwork` 第一行就是
  `release()`——后发的把先发的**就地注销**：先发的白等 15s、`onNetworkLost` 回调
  一起消失（上一版刚加的「被动断开自动重绑」于是完全失效）、用户还要面对确认框。
  现在同 SSID 只有一发在跑，其余的等它的结果。日志 2026-11-02 的三处症状同源。
- **建链失败按 3s / 10s / 30s 退避自动重试**：旧实现失败即收工，用户只能自己再点。
  三次用完才停，并明确写「请重启车机侧 CarPlay」——继续怼只会刷屏、还把 CarLife
  引擎反复踢下线。
- **车机 ctrl 邀请即时响应**（`CarPlayCtrlAdvertiser.onInvite`）：空闲态收到邀请
  = 对方正在等我们连，立刻作废退避计时器、马上起一轮。忙（正在连/已推流）时不动，
  交给在跑的那轮。
- **半握手快速定性**：SETUP 回了 200，但 `/info` 没有 displays **且**事件口连不上
  ——不再按手机尺寸兜一个画布硬推（屏上不会出现画面、触摸也没通路，只会把
  「连不上」演成「连上了但黑屏」），直接判定车机侧服务异常并提示重启车机。
  屏幕流 SETUP 失败也不再静默收工，日志会写清是事件口还是 `/info` 不通。
- 单测 **330 例全绿**（新增 10 例：入网互斥/等待方拿到结果/换热点不阻塞/去重窗口/
  force 语义/成功后不去重）。
- 排查与判读见 [docs/connect-recovery.md](docs/connect-recovery.md)。

### 1.2.10 —— 2026-11-02（正式版 · WiFi 断链排查）

> 把「WiFi 到底断没断、什么时候断的」从猜变成能对着时间戳看的日志，同时让被动
> 断开的热点自己回来。

起因：多份现场日志里 CarPlay 会话每 40~80 秒被拆一次，断链前 3 秒却毫无异常；
而「切 WiFi 把链路带走」这个假设无法证伪——旧日志只记录我们主动 `release` 和
`onLost`，**系统侧的被动变化（ROM 踢关联、换信道、热点重启、双 WLAN 争抢）全程静默**。

- **`onLost` 不再只提示**（`SessionController.onCarWifiLost`）：应用专属网络被系统
  断开后，用最近一次 `0x5703` 凭据**原地重绑一次**。旧实现只改提示 + 写日志，
  网络永远不会回来——实测重绑后 8 秒内 CarLife 引擎会报 `7240 未监听`，
  引擎在等网络，而旧代码不会把它拉回来。
- **重绑有 15 秒闸门**：熄火/出范围会连发多次 `onLost`，没闸门就是「重绑→失败→再重绑」
  的风暴，每轮都要 release + requestNetwork，恰好把正在恢复的关联又打断一次。
- **只恢复网络，不自动重建会话**（刻意的取舍）：会话重建要重新配对/建链，会把
  CarLife 引擎再踢一次（它比 AirPlay 脆）。日志明确写「本轮 CarPlay 会话仍需重建」。
- **wlan0 状态心跳**（`net/WifiLinkWatchdog`）：`registerNetworkCallback` 收
  `onAvailable`/`onLost`/`onCapabilitiesChanged`（毫秒级），外加每 5 秒轮询
  「车机网络 / 进程默认网络 / 绑定 SSID」的签名，**只在签名变化时记一行**。
  只监听不请求，避免诊断本身多建一条网络、反过来干扰被测对象。
- 排查口径写进 [docs/wifi-link-watchdog.md](docs/wifi-link-watchdog.md)：
  断链瞬间没有 `WIFI:` 行 → 可以排除 wlan0；有 `onLost` → 才是网络侧。
- 单测 **362 例全绿**（新增 6 例：签名稳定/顺序无关/换热点/丢车机网络/默认网络变化/空态）。

### 1.2.9 —— 2026-11-01（正式版 · 连接健壮性四处加固）

> 修「有画面没声音」背后的真正原因，并把「这次连接多久、慢在哪」写进日志。

- **配对错误码不再被丢掉（最关键）**：`pair-setup` / `pair-verify` 应答里的 TLV
  `Type=Error`（`0x07`）从 1.0 起就被忽略。日志 221747 里车机对两段都回
  `{Error:2, State:4}`——`pair-setup` 报成「M4 缺少 proof」，`pair-verify` 只判
  `State == 4` 干脆**把拒绝读成成功**，派生两把对不上的密钥，加密 SETUP 被静默丢弃
  10 秒，最后降级明文会话（无共享密钥 → 音频与触摸整条跳过）。
  现在每个应答先看 Error 再看 State，抛带错误码的异常，并给出一句可执行的建议。
- **失败分类**：`Error=2`（认证失败）/ `4`（配对数满）/ `5`（次数用尽）属确定性失败，
  本轮停止重试；`3`（退避）/`7`（忙）/`6`（不可用）与未知码仍重试。确定性失败时
  **不再触发「断 BT 重来」**（那只会再失败一次，真车还会拆掉 BT 控制通道），
  但明文会话继续兜底画面，并当场写明「本会话无共享密钥 → 音频与触摸不可用」。
- **短期记忆**：记忆「这台车确定性拒绝配对」15 分钟，下一轮直接跳过注定失败的
  120 秒配对窗口；15 分钟后过期、配对成功即清除、明文 SETUP 被拒仍会补做配对。
- **阶段预算**：每轮连接逐阶段计时并汇总（`STAGE:` 前缀），预算沿用既有常量
  （等端点 5min / 热点 60s / 配对 120s / SETUP 30s / 首帧 20s），超预算会标出来。
  新增**首帧门**：SETUP 成功后 20 秒没收到任何视频帧就明确写一行（只报告不拆链，
  避免与看门狗形成两套超时）。
- **多屏选择**：`/info` 有多块屏时不再盲取第一块——车机声明了 `type` 就按
  `type==0 → 面积 → 帧率 → 声明序` 选主屏，未声明 `type` 时保持旧行为；
  `displayUuid` / `maxFps` 改为取选中那块屏的值。
- **候选地址**：配对开始打印完整候选列表（含地址族），并说明身份判定只看 `pk`
  签名、与地址无关；`pk` 校验失败从「一句 `=false`」改为显式告警（可能连到了同网段
  的另一台 AirPlay 设备），但不中断。
- 详见 [docs/connect-hardening.md](docs/connect-hardening.md)。

### 1.2.8 —— 2026-11-01（正式版 · 音频通道看门狗）

> 修「等一会才连上车机，播放音乐也要等一段时间才出音频」里的后半句。

- **根因是漏配，不是慢**：WiFi 入网把引擎重启后，视频 / 媒体 / TTS 三条 socket 同时
  `Connection reset`。视频有看门狗会重连，音频链路一条都没有——`audioReader()` 读到异常
  只打一行日志就结束，`sockets[9240]` 还留在表里没人重建，声音只能等引擎自己复位推流
  （日志 090105 里等了 88 秒：`08:57:38` 断，`09:00:22` 才见「媒体 音频格式宣告」）。
- **路径 A（主项）**：媒体 `9240` / TTS `9241` 各挂通道状态，看门狗每秒检查，读线程退出即
  判死并重连该端口。用「代次」防止旧读线程把刚接上的新通道秒判死（否则死循环）。
- **路径 B**：socket 活着但 20 秒零 PCM、建立已过 20 秒宽限、视频 3 秒内有帧、且确实该有声
  （引擎发过 `0x30002` 未发 `0x30003`，或音乐会话在播）→ 重连一次，退避 20/40/60 秒、
  最多 3 次。三重设限是必要的：屏幕上没在放声音是常态，只按静默判会反复打断引擎音频管道。
- **格式宣告状态跨重连保留**：它原是读函数的局部变量，重连后新线程会报 48kHz 兜底值，
  把真实的 44.1kHz 覆盖掉（声音变调）。
- 重连只换 socket 与读线程，不动 sink / 发送端，车机侧 RTP 不中断（听感「卡一下」）。
- 详见 [docs/audio-channel-watchdog.md](docs/audio-channel-watchdog.md)。

### 1.1.1 ~ 1.2.7 —— 2026-10-29 ~ 10-31（补记索引）

这一段此前只写在各版 Releases 正文里，没回填到本节。要点按版本索引如下，
细节点版本号看 [Releases](https://github.com/jjuugk/CarPToCarL/releases)：

| 版本     | 日期         | 主题                                          |
| ------ | ---------- | ------------------------------------------- |
| 1.1.1  | 2026-10-29 | 车机分辨率读全 + 界面显示像素                            |
| 1.1.2  | 2026-10-29 | 界面显示真实码流尺寸（1412 申请、实编 1280 不再谎报同尺寸）         |
| 1.2.1  | 2026-10-30 | 靠近车机自动启动 + 通话音频通道                           |
| 1.2.6  | 2026-10-30 | 靠近车机自动拉起并自动连接；划掉最近任务也收摊、抑制加时限               |
| 1.2.7  | 2026-10-31 | 进程冻结监视与解冻后自动恢复（`PROC` 日志）；目标帧率改为跟随车机 `maxFPS` |

> `1.1.0`、`1.2.2` ~ `1.2.5` 只出过本地 dev 包，未单独发 Release；
> `bieke` 分支另有 `1.2.6-bieke` ~ `1.2.9-bieke` 等带分支标记的正式包
> （versionCode 与主线相同，可互相覆盖安装）。

### 1.0.13 —— 2026-10-27（正式版 · 导航播报有声 + 连接稳定性）

> 1.0.8 ~ 1.0.12 是同一轮设备适配的开发迭代（只出过 dev 包，未单独发版），
> 内容一并合入本版。1.0.7 → 1.0.13 的核心是两件事：**把导航播报接进来**、
> **把「连上又断」的一串问题按真车日志逐条堵掉**。

**修一（本版主项）：车机上放音乐有声音，地图导航播报完全没声音。**

- **根因不是「音量太小」，是那条流从来没被接**：CarLife 有**两条独立音频流**——
  媒体 `9240`（包类型 `0x3000x`）与 **TTS `9241`（包类型 `0x4xxxx`，导航播报/语音提示）**。
  `CarLifeHuClient.audioReader()` 的 `when(type)` 只列了媒体那四个常量，
  TTS 包全部落进 `else` →「暂不处理」→ 丢弃。日志里那条 1280 字节的导航 PCM
  就是这样被扔掉的（`0x40003 音频包（长度 1280，暂不处理）`）。
- **第二层原因**：媒体 sink 有「音乐会话暂停 → 丢弃引擎残留 PCM」的联动，
  而导航播报恰好发生在音乐暂停的时候，就算走媒体通道也会被吃掉。
- **改法**：TTS 通道单独解析（`0x40001` 取格式，**任何带负载的包都当 PCM**，
  长度 0 只记开停），TTS 用自己的 sink 且 `dropWhenPaused=false`，两路共用同一个
  发送端做重采样（导航常 16 kHz 单声道，媒体 48 kHz 立体声）；`AudioSender.feedPcm`
  加 `@Synchronized`，否则两路读线程写同一缓冲会半帧交错出爆音。
- 复测看这三行：`TTS 音频格式宣告` / `✓ 收到导航播报音频（TTS 首包 …）` /
  `音频直通 … 电平 xx%（非零 xx%）`。详见 [docs/audio-call-issues.md](docs/audio-call-issues.md)。

**修二：连上之后反复断、或断了一次就再也连不回来（多台车机实测）。**

- **能力清单从「硬门禁」退回「只记录」**：1.0.8 曾按车机 `0x1D01` 的可收清单裁剪，
  清单里没 `0x4E0D/0x4E0E` 就真不发。真车证据推翻了这条判据——奥迪 MIB3 的清单只有
  5 项、不含 `0x5702`，却完整支持无线 CarPlay（硬拦导致 7000 端口 `ECONNREFUSED` ×45）。
  **清单只能证明「有」，不能证明「没有」**。WiFi 凭据分派改认「双清单都 ≥10 项仍无
  `0x57xx`」（宝马 iD9 形态）才跳过旧协议。
- **MFi 长度记忆支持硬拒绝**：挑战期间被对端断开时，即使该长度已记「成功」也要推翻
  ——迟到应答归属歧义会把 32B 的成功记到 20B 头上，之后每轮必死在 `0xAA05`。
- **僵尸轮次释放**：车机拆链后立刻回连，新端点会被「已有会话在处理中」拒掉，
  只剩 keep-alive 干等。现在保活泵退出即释放互斥（≤200 ms 退场），新轮次清掉
  `connectCancelled`。这修的是「每 ~40 秒一轮」的重连死循环。
- **零回帧不再催问**：车机 `0x4300` 后一个字都不回时，催问（重发问候 + `0x4E0E` +
  `0x4300`）本身就是拆链元凶，现改为静默保活。
- **`0x4300` 报文体三档阶梯**：新版嵌套 → 老版平铺 → 不发，按车机身份持久化记忆。
- **新增 `net/TcpLiveness`**：视频数据口启用 keepalive 10s/3s/3 + `TCP_USER_TIMEOUT=20s`
  （内核默认两小时才探测，车机熄火不发 RST 时画面会永久冻结）。
  AirPlay 控制/事件通道**不设**——控制通道上跑着音频 `SETUP`、`volume` 与 `feedback`，
  1.0.12 曾因此误伤音频，已撤回。
- 详见 [docs/cardeck-0.1.28-port.md](docs/cardeck-0.1.28-port.md)、
  [docs/buick-e2lb-availability-silence.md](docs/buick-e2lb-availability-silence.md)、
  [docs/gm-cinemo-4301-deadlock.md](docs/gm-cinemo-4301-deadlock.md)、
  [docs/citroen-r4-sdp-no-service.md](docs/citroen-r4-sdp-no-service.md)。

**其它**：测试增至 208 例（新增 `AvailabilityBodyMemory`、MFi 硬拒绝/矛盾档等用例）。

### 1.0.7 —— 2026-10-26（正式版 · 双指触控 + 蓝牙地址门禁）

本版两件事：**双指触控**（上文主项）与**本机蓝牙地址门禁**（见下）。

**修一：车机上双指缩放完全没反应（单指点击与拖动都正常）。**

- **根因在路由层，不在车机**：车机本来就是按两槽上报（日志里 `#0(...) #1(...)`），
  `TouchRouter` 也把两个触点都往下传了，但 `CarLifeHijack.handleTouch` 第二行写着
  `if (slot != 0) return true`——第二根手指被当成「已处理」直接吞掉。
- **协议层的表达力**：单点消息（`0x68002/3/4`）的负载是 `CarlifeTouchSinglePoint{x,y}`，
  没有指针概念，双指在旧通道上根本表达不出来。所以改走**复合动作通道** `0x68001`
  （`CarlifeTouchAction{action,x,y}`），`action` 用原始 MotionEvent 动作码，
  指针索引放在高字节：第二根手指按下 = `5|(1<<8)` = **261**，抬起 = **262**
  ——这正是官方 HU SDK「新触摸方式」的用法。
- **单指路径一行未改**：只有出现第二根手指才切换到复合通道，直到整笔手势结束；
  收尾固定「先 `POINTER_UP(手指 1)` 再 `ACTION_UP`」，避免留下永不放开的幽灵触点。
- 新增诊断：首次触摸上报会记一行 `EVENT: 触摸格式 多槽（最多 2 指）` /
  `单指绝对坐标（车机只报一个触点）`——后者意味着这台车的 HID 不给第二个触点，App 侧无解。
- 详见 [docs/touch-multitouch.md](docs/touch-multitouch.md)。

**修二：本机蓝牙地址没填就点「启动连接」，只会换来一句笼统的「连接失败」。**

- 没填地址就发起连接，车机侧只会 SETUP 403 / CSM 静默丢弃，用户看不到原因。现在改成当场拦下。
- 门禁统一放在 `proceedToConnect()`：手动点「启动连接」、打开 App 后的自动连接、蓝牙/位置
  权限授权后继续，四个入口都汇到这一处，不存在「手动被拦、自动悄悄放行」。
- 判定用「生效地址」而不是「输入框非空」：机型能从 sysfs 自动读到真实地址就不拦，避免本来
  能用的机器被迫多填一步；自动解析也拿不到（该现象常见于脱敏机型）才拦。
- 未填写时的表现：按钮置灰但**仍可点**（禁用态点了没反馈，用户会以为卡死），点击即弹
  「本机蓝牙地址未填写」；英雄卡副标题同步变红字；MAC 输入框下方显示告警；焦点与滚动定位到输入框。
- 输入框里填了合法地址但忘了点「保存」时自动落盘，不会出现「我明明填了还说我没填」。
- 判断只用缓存值，不触发 shell 解析——该函数在 UI 线程高频调用，不能每次花 4 秒跑
  `content query`。

### 1.0.6 —— 2026-10-26（正式版 · 通话后重建音频流）

**修：微信来电挂断后，车机数据照收、却一直没有声音。**

1.0.5 新增的电平表与通话看门狗把这件事从「推断」变成了「读数」：

| 读数 | 数值 | 排除了什么 |
|---|---|---|
| 挂断后电平 | `电平 100%（非零 99%）` 持续 30 秒以上 | 推的是**真声音**，不是内容静音 |
| 车机 `/feedback` `sampleTime` | 32.16 s 内音频钟走了 32.16 s（**1.000 倍**） | 接收端跨通话仍在正常解码排播，不是掉链 |
| 视频 | 全程 60 fps，帧号连续 | 会话与链路都正常 |
| `GET_PARAMETER volume` | `200 OK` 但 **body 为空** | 车机不支持音量查询，改音量这条路无效 |

也就是说接收端在解码、在按实时排播，**只是它的音频输出设备在通话期间被释放后没重新打开**。

- **新增 `rebuildAudioChannel()`**：通话结束 2.5 秒后重建**一整条音频流**——
  新 `streamConnectionID`、新 dataPort/controlPort、新编码器、新同步锚点，并补发 `RECORD`
  把新流纳入已激活的会话。这是「重连一次就好了」在音频层的等价动作，**不动视频**。
- 顺序是「**先协商新的、成功了才停旧的**」：SETUP 被拒时旧流继续推，
  不会把「本来还有声」搞成「彻底没声」。
- 日志新增 `AUDIO: 音频流重建完成（新 dataPort=… controlPort=…）`；失败则
  `重建协商失败——保持原音频流继续推`。

> 若重建后**仍然**无声，说明卡的是车机音源选择（停在蓝牙/电话没切回 CarPlay），
> 车机上把音源切到「CarPlay/媒体」再切回即可恢复——这一步 App 侧没有可用的协议接口。

### 1.0.5 —— 2026-10-26（诊断版）

- **PCM 电平表**：每 5 秒报一次 `音频直通 … · 电平 X%（非零 Y%）`，
  一眼区分「引擎没声音」与「车机没放出来」。
- **通话状态看门狗**：每秒读 `AudioManager.mode`，记录 `NORMAL→IN_COMMUNICATION→NORMAL`；
  通话结束时重新核对车机音量并补一轮同步包重锚。
- **停摆判定修正**：原来按「完全无帧」判断，但来电时引擎仍发 0.8 fps 心跳帧，
  永远不会触发；改为按帧率低于 2 fps 判定。

### 1.0.4 —— 2026-10-26（正式版 · 音频握手修复）

**修：连上后一段时间内没有声音、来电挂断后一直没声音。**

两份日志定位到四个独立成因，详见 [docs/audio-call-issues.md](docs/audio-call-issues.md)。

- **音频同步包「发一次就死」**（真 bug）：音频 SETUP 后 1 秒，第一个同步包撞上
  车机还没监听的 `controlPort`，`ECONNREFUSED` 被当成致命错误，同步线程直接退出——
  整场会话再没有任何 `RTP↔NTP` 锚点。现在改为**每秒重试、连续失败 10 次重开套接字**，
  并在日志里区分「失败第 N 次」与「恢复」。
- **新增 `resyncAnchor()`**：引擎音频中断后恢复时（来电结束、路由切回）补发 3 个同步包，
  立刻重新对齐车机时钟，缩短恢复瞬间的开头静音。
- **识别引擎音频开停信号** `0x30002` / `0x30003`（长度 0 的音频包，此前只记一次「暂不处理」）：
  来电时引擎发 `0x30003` 停音频、挂断恢复播放发 `0x30002`，现在两者都会落日志。
- **画面源停摆看门狗**：8 秒没有新帧记一条带时长的日志，恢复时记停摆时长——
  用于把「引擎真没画面」与「链路/车机解码问题」分开（两者处置完全不同）。
- **暂停丢弃计数**：按媒体会话状态丢 PCM 的旧逻辑每 30 秒记一次丢弃包数，
  用来排查导航播报是否被误判成「音乐已暂停」而丢掉。
- **暂停截断改为 15 秒窗口**（原来一旦暂停就永久丢）：连上车机时手机里常驻一条暂停的
  音乐会话是常见情况，旧逻辑会把导航播报/提示音一直当「残留」丢掉——正是「开导航没声音」。

> **来电黑屏的定性**：日志显示链路全程正常（keepAlive/timesync/feedback 都有），
> 只是画面源在 37 秒里只给了 4 个心跳帧、零个新 IDR——通话界面是系统安全窗口，
> 录制拿不到内容，这是 Android 的限制，不是本 App 的链路问题。挂断后「无声音」
> 同理是引擎没恢复 PCM（`0x30002` 迟到 2.5 分钟），不是 App 停发。

### 1.0.3 —— 2026-10-25（正式版 · 交互精简）

**[下载](https://github.com/jjuugk/CarPToCarL/releases/tag/v1.0.3)** ——
`CarPToCarL-1.0.3-release.apk` / [`CarPToCarL-latest.apk`](https://github.com/jjuugk/CarPToCarL/releases/latest/download/CarPToCarL-latest.apk)

**上车就不用点按钮了**

- **「启动后自动连接」开关**（连接准备卡顶部，**默认开启**）：打开 App 即自动发起连接，
  走的是和手动点「启动连接」完全相同的链路（权限 → 蓝牙开关 → WLAN → 会话），会话启动本身幂等，不会双开。
  - 只在**冷启动**触发，旋转 / 重建不会重复发起。
  - 前置条件不齐时会等待而不是硬连：免责声明未接受时等接受；启动残留虚拟屏扫描未完成、
    或有残留待确认时等扫描结束（避免「是否清理残留屏」与热点确认弹窗抢焦点）。
  - 想在连之前先改「下次连接起效」的项（分辨率缩放、CarLife 劫持开关）时关掉它即可。
- **首页移除「画质增强」**：实测那条 MediaFormat 通道对码率 / 帧率无效，而写调试配置就会让
  CarLife 进入调试模式，白担开销。`MANAGE_EXTERNAL_STORAGE` 权限也随之不再需要。
- **目标帧率固定 60**：取消 30/60 二选一，会话建立时按 `DisplayPrefs.FPS = 60` 申请；
  英雄卡统计行由「分辨率 · 实测/目标 fps」改为「分辨率 · 实测 fps」。
  ⚠️ 真正决定出帧的仍是**荣耀车联「投屏显示设置 → 帧率」**（默认 20），见第四节已知限制 1。

**真机验证（2026-10-25 晚，荣耀 PTP-AN10）**

- `adb install -r` 从 1.0.1 覆盖安装成功（同签名证书，无冲突），`versionCode` 10003 生效。
- **自动连接整条链在真机上跑通**：冷启动后未触碰界面，日志自行出现
  `CARLIFE: → 帧率变更 id=98316 fps=60（上限 60）` 与连续的 `VIDEO: 帧 #N ... 已发送`，
  即「冷启动 → 700ms → 权限 / 蓝牙 / WLAN 前置 → 会话建立 → 推流」无需手动点按钮。
- 同时确认目标帧率按 60 申请（此前默认档为 30）。
- 验证方式供参考：release 包不可 `run-as`，看会话主线用
  `adb logcat -d | grep CarPlaySender`（需过滤荣耀系统噪音）。

### 1.0.2 —— 2026-10-25（正式版 · 合规补充）

**[下载](https://github.com/jjuugk/CarPToCarL/releases/tag/v1.0.2)** ——
`CarPToCarL-1.0.2-release.apk` / [`CarPToCarL-latest.apk`](https://github.com/jjuugk/CarPToCarL/releases/latest/download/CarPToCarL-latest.apk)

**把「来自哪里」写进说明**

- README 新增「与参考项目 CarDeck 的关系」：本项目参考了独立项目
  [CarDeck](https://github.com/shaonialife/cardeck)（MIT）的 iAP2 / AirPlay 协议实现、
  密码学封装、蓝牙链路与工程骨架；两者非同作者，其版权声明按 MIT 要求保留。
- `NOTICE` 新增第 0 节 `Provenance`（随 APK 打包进 `assets/NOTICE.txt`）；
  `docs/LEGAL-NOTES.md`、`DISCLAIMER.md` 同步标注来源。
- 运行时仍可见的历史命名（日志目录 `下载/CarDeck`、进程启动行 `CarDeck`）注明是渊源而非依赖。
- **功能无改动**：这一版只有文档与随包声明变化。

### 1.0.1 —— 2026-10-25（正式版 · 补丁）

**修：CarPlay 会话保持时，CarLife 断开重连后车机不再出画面。**

- `VideoSender.onSourceRestart()`：CarLife 换一轮会话 → 清 `configSent` 重发 VideoConfig、
  丢弃旧 IDR、新 IDR 到达前拒收 P 帧；`cachedCsd` 兜底（新 IDR 不带 SPS/PPS 也能发 config）。
- 会话中途 SPS 变化（改车联分辨率 / 引擎重建编码器）自动重发 config，不再花屏。
- 源重启后 4 秒零帧 → 自动请求引擎重建编码器（补 SPS+PPS+IDR）。
- `seedCsd()`：车机重新接上通道时先用缓存 SPS/PPS 预置 csd，缩掉「等引擎重建」的黑屏窗口。
- `frameCounter` 明确不归零（同一条流 nonce 不能复用），见第五节排查小节。

**诊断可读性**

- 蓝牙会话收尾按 `BtSessionGate.sessionActive` 区分「iAP2 已迁到 WiFi 隧道（正常）」与
  「端点未拿到就断开（故障）」两种文案，不再把 WinPlay 的正常交接写成「等待接收端重连」。

### 1.0.0 —— 2026-10-25（首个正式版）

从 0.1 的实验性劫持走到「装完能日常用」，这一版把前面踩过的坑都收敛成了规则：

**连接与稳定性**

- 无线 iAP2 + AirPlay 全流程：0x4301 拿热点凭据 → PairVerify → H.264 推流 → HID 触摸回传
- 修 0x5001 **双重封包**：蓝牙链路多出 136B 净荷，对端接收进程当场崩（模拟器「接收进程异常」）
- 视频 socket 加**写锁**（nonce 依赖到达顺序，只锁写字节不够）+ 断链重连窗口（3×2s），  
  重连后重置帧计数 / IDR 缓存 / `configSent`
- 空闲 1.5s 起每 2s 重发**最后一个 IDR**（绝不重发 P 帧，重复 P 帧会花屏）
- 连接目标回退：目标设备连续 2 轮失败即放开全部已配对设备，不再死等 44s

**画面**

- CarLife 码流**直通**（不重编码），`VideoConfig` 声明跟车机画布
- SPS 解析修正（constraint flags 只读 8 位，多读 8 位会导致整段错位 → 一直解析失败）
- 帧率：车联「投屏显示设置 → 帧率 = 60」+ `98316` 申请 60，实测 ~60fps（默认档只有 20）
- 分辨率：车联保持「默认」，与车机实际分辨率对齐（`needAdapterScreen=true`，宁可黑边不拉伸）

**触摸**

- **命令 ID 纠正**：`DOWN=0x68002 / UP=0x68003 / MOVE=0x68004`（旧版全错一格，  
  手指一动就抬笔 → 「能点、点得准，但拖不动」）
- **坐标原样透传**：引擎自己按容器尺寸换算，App 再缩放一次就是「点偏」
- 异步队列：DOWN/UP 保序、MOVE 队尾合并、上限 8，连续滑动不积压
- 缺 UP 自愈：220ms 新手势判定补发 UP + 3s 超时兜底，躲开引擎 `eventIng` 死锁

**音频与媒体**

- PCM 直通，播放 / 暂停联动
- 歌名 / 歌手 / 歌词：98386 `MEDIA_LYRIC_SUPPORT=1` + iAP2 0x5001  
  （订阅按 1.5s/4s/8s/15s/25s 退避重试，不再「订阅那一刻没读到就整程空白」）
- 通知监听器自愈：`requestRebind` + watchdog；MagicOS 上该 API 不生效，需去设置页关开一次

**工程**

- 版本号单一来源：`appVersionName` 推导 `versionCode`（1.0.0 → 10000）
- lint 基线 + 提交前钩子；release 走 `lintVitalRelease`，构建不过直接失败
