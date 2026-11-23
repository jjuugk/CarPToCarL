# CarPToCarL 项目情况（详细）

> **本文件回答「项目现在是什么样」**：定位、架构、版本、代码、能力、缺口、风险、分支与文档状态。
> 与 [`PROJECT-GUIDE.md`](PROJECT-GUIDE.md) 分工——指南是**导航**（43 篇现行文档怎么读、排障先看哪篇），
> 本文件是**体检报告**（现在处于什么状态、哪些是已证实的、哪些还是推断）。
>
> 数据截止 **2026-11-19 20:35**。全部数字取自仓库实际状态（`git`、`docs/`、构建配置），不是估算。
> 基线：`main` @ `be02fdc`，正式版 **v1.2.70**；协议模块化重构已于 2026-11-19 **快进并入 main**
> （旧 main 备份在 `backup/main-pre-protocol-20261119` = `95f8b6b`）。

---

## 一、关键事实卡

| 项 | 值 |
|---|---|
| 一句话 | 未 root 的 Android 手机冒充 **CarPlay 发送端（iPhone 角色）**，把本机 **CarLife+ 引擎的 H.264 / PCM 直通**给只认 CarPlay 的车机，全程**不重编码** |
| 形态 | 单模块 Android 应用，Kotlin；**发送端**（手机侧），不是接收端 |
| 包名 / 系统 | `io.github.shaonialife.carptocar` · minSdk 31 / target / compileSdk 34（Android 12+） |
| 当前版本 | **1.2.70**（versionCode 10270，APK 14,084,053 B） |
| 代码规模 | 主源码 **176** 个 Kotlin 文件 / **56,371** 行；测试 **130** 文件 / **979** 例 / 17,287 行 |
| 仓库活跃度 | 272 次提交，2026-10-25 至今 25 天，README 记录 **57** 个版本条目（1.0.0 → 1.2.70） |
| 真车验证 | 奥迪 MIB3（2026-10-11 全链路：出画 + 触摸可交互）；测试台 DiPlay / xcertplay / WinPlay |
| 已知不通 | 宝马 300 / iD9（vivo V2547DA / Android 16）：12 次 iAP2 会话、**0 条 0x4301** |
| 许可 | 源码 MIT；**非 MFi 产品**，不授予 Apple / 百度协议与商标权利；可开源，**收费只做赞助 / 技术支持** |
| 头号未解 | 厂商后台冻结（非 Doze）；以及 1.2.70 的听感修复**未实车复测** |

---

## 二、在解决什么问题

Android 手机连 CarPlay 车机没有官方路径——车机只认 iPhone。本项目的做法是**两头骗通**：

1. **冒充发送端**：蓝牙 RFCOMM + iAP2 谈凭据，加入车机热点，mDNS 找 AirPlay 端点，建 AirPlay 会话。
2. **画面借壳**：不自绘界面，而是劫持手机自己的 CarLife+ 引擎码流（CarLife 本来就在投屏），把 H.264 / PCM **原样搬运**到 AirPlay 通道。

关键可行性来自一条协议事实：**iAP2 认证是单向的**——手机验证车机的 MFi 证书，车机信任手机自报的结果。所以发送端**不需要任何 Apple 签名材料**，这是整个方案不需要 MFi 授权也能跑的技术前提（法律边界另见 §11）。

### 与参考项目 CarDeck 的差别

| | CarDeck（MIT，非同作者） | 本项目 |
|---|---|---|
| 画面 | 自绘隐形屏 + Shizuku 注入 | **CarLife+ 码流直通**，不重编码 |
| 依赖 | 需要 Shizuku | 不需要（2026-10-24 移除） |
| 触摸 | 注入系统 | HID 回传 → CarLife `9340` |

协议层、密码学封装与工程骨架参考了 CarDeck，声明见 `NOTICE` 第 0 节；运行时**不依赖**它（日志目录名 `下载/CarDeck`、进程名 `CarDeck` 属历史沿用）。

### 明确不做

- **有线 USB**：App 控制不了 USB gadget，且测试机 BL 无法解锁——四道 Android 门，纯软件无解（`WIRED-CARPLAY-FEASIBILITY.md`）。唯一现实解是外挂 USB gadget 硬件。
- **Shizuku**：2026-10-24 移除。
- **MIB3 车控**（EA / VIWI / 控制交接）：1.2.29 后整体移除，文档已归档。

---

## 三、运行时架构

### 3.1 分层（排障时先定层，再看日志）

```
手机 App
 ├─① CarLife+ 引擎 → 127.0.0.1:7240/8240/9240/9241/9340/9440
 ├─② CarLifeHijack 冒充车机接管码流（不重编码）
 ├─③ VideoSender / AudioSender → AirPlay RTP + ChaCha20-Poly1305
 └─④ 蓝牙 iAP2 控制 + WiFi 车机热点（L1→L4 建链，L5 编排）
                    ↓ 无线 CarPlay
                  车机（AirPlay 接收端）
```

| 层 | 职责 | 卡住时的典型信号 |
|---|---|---|
| L1 蓝牙 | RFCOMM、SDP、配对、iAP2 链路 | SDP 无 `…caff` → 秒失败；握手 `FF 55` 族 |
| L2 网络/发现 | 加入车机热点、mDNS `_airplay._tcp`、ctrl 广播 | 无热点凭据、mDNS 无服务、Wi-Fi 反复 onLost |
| L3 iAP2 控制面 | `0x4300`/`0x5703` 拿凭据与端点、CSM 消息 | 等不到 `0x4301`；链路 ACK 但业务层静默丢弃 |
| L4 AirPlay 会话 | PairVerify、SETUP（画布/音频档）、RECORD | 403、SETUP 超时、`modesChanged` 200 仍黑屏 |
| L5 应用编排 | `SessionController`、会话状态机、CarLife 劫持 | 前台/权限/虚拟屏/重启恢复 |

### 3.2 数据面四通道

| 通道 | 源 | 目标 | 说明 |
|---|---|---|---|
| 视频 | CarLife `8240`（H.264） | AirPlay 屏幕流（数据口 6000） | 首帧必须是 IDR，否则车机不接 |
| 音频-媒体 | CarLife `9240`（`0x3000x`） | AirPlay 音频流 | 支持播放/暂停联动；AAC 或 PCM 直通 |
| 音频-TTS | CarLife `9241`（`0x4xxxx`） | AirPlay 音频流 | 走独立解析，**不参与**暂停丢弃 |
| HID 触控 | 车机 → App | CarLife `9340` | 坐标以 **SPS 真实分辨率**为基准，异步队列不积压 |

补充端口事实：AirPlay **数据口 6000** 为非阻塞 `DatagramChannel`（缓冲满丢包续泵），**同步口 6011** 保持阻塞式（每秒 1 包不会满，且依赖 ICMP 判端口存活）。

### 3.3 三个反复踩的技术事实

1. **认证单向** → 发送端不需要 Apple 签名材料。
2. **分辨率/帧率由荣耀侧决定** → CarLife 协议改不动，只能改「荣耀车联 → 投屏显示设置」，厂商 mix 层还会覆盖编码尺寸。
3. **声明 ≠ SPS ≠ 出画** → `VideoConfig` 声明跟车机画布，SPS 只作诊断；历史上拿 SPS 反改声明导致真车黑屏，2026-11-06 实车又证伪了「三者全等就出画」。

---

## 四、版本与发布状态

### 4.1 当前正式版 1.2.70（2026-11-18）

| 项 | 值 |
|---|---|
| 发布提交 | `b7506d3`（`6724df7` + `81dd2ce` 两处修复） |
| 单测 | 900 例，0 失败 |
| APK | 14,084,053 B（约 14.1 MB），`CarPToCarL-1.2.70.apk` |
| 内容一 | **非阻塞发包**：数据口改 `DatagramChannel.configureBlocking(false)`，缓冲满丢弃该包继续泵。证据是停拍归因 `791ms —— park 0 / 混音 5 / 发包 785ms；GC +0、CPU +17ms`（墙钟走了 791ms 但几乎不耗 CPU ⇒ 阻塞在内核 `sendto()`） |
| 内容二 | **媒体采样率三档**（自动 / 48k / 44.1k）：反编译证实 CarLife 引擎采样率由车机 `MEDIA_SAMPLE_RATE` + 持久化 `is48k` 决定，实车日志 26 条全 44100Hz，故让 `audioCandidates` 优先同率档，两端对齐零重采样 |
| 诚实边界 | 手机侧录音 2 分钟逐 20ms 分析**零空洞**，证明发出连续；但**听感是否彻底消除顿挫未实车复测** |

### 4.2 近 15 版脉络（每条都是真机日志推出的结论）

| 版本 | 日期 | 一句话 |
|---|---|---|
| 1.2.62 | 11-17 | 找回 1.2.50 的三档连接容错：`isCinemoStack` 判据收紧误伤（该开关全部读取点都是连接容错，无 HID 消费者） |
| 1.2.64 | 11-17 | 抗冻结 + 语音键不拆流 + RTP 交付对账 + 三连击重启音频（当晚因回归回滚） |
| 1.2.65 | 11-17 | 宝马 G38-NBTevo bit37 直连；编码与发送解耦（泵一拍一包）——**1.2.68 前后整批回滚** |
| 1.2.69 | 11-18 | 回滚后重新打测试包（覆盖手机上已装的 10268） |
| 1.2.70 | 11-18 | 非阻塞发包 + 采样率三档（即 1.2.63 的修法在日志确证后重新应用） |

> 值得记住的教训：**1.2.62～1.2.65 的一整串修复在 1.2.66～1.2.68 被批量 revert**（`ab7f7d5`…`c39ff97` 一组 Revert 提交），只把经日志复核确认的两项（非阻塞发包、采样率对齐）重新落地。所以版本历史里 1.2.63/1.2.64/1.2.65 的条目**不能当作现行行为**。

### 4.3 发版规则（写死在构建脚本里）

- 版本号唯一来源：`app/build.gradle.kts` 顶部 `appVersionName`；`versionCode` 由它推导（`major*10000 + minor*100 + patch`）。
- **禁止连续两个 4**：`1.2.44` 会命中 `require`，构建立刻失败（去点后找 `44` 子串）。
- tag 与 Release 按 `v1.2.70` 固定直链，**刻意不用 `releases/latest`**（`-switchpool` 分支包会抢指针）。
- ⚠️ **现状缺口**：本地 tag 只到 `v1.2.65`，`v1.2.66`～`v1.2.70` 未在本地打 tag（远端 Release 存在）。建议发版脚本补一条 `git tag` + 推送。

---

## 五、代码规模与结构

### 5.1 口径说明（重构已并入，只有一条线）

| 口径 | 主源码 | 测试文件 | 测试用例 | 说明 |
|---|---|---|---|---|
| `main`（发布线） | **175** | **130** | **979** | 含协议聚合层 10 文件；2026-11-23 两轮删除隐形屏链路（18 文件 / 5,722 行；再删注入门面 `CarInputClient` + `ICarInput.aidl` 与 `MainActivity` 残留屏清理约 140 行），测试 -5 文件 / 34 例 |
| `backup/main-pre-protocol-20261119` | 169 | 118 | 900 | 合并前的 main 快照，仅供对比与回滚 |

`main` 口径 56,150 行主源码 / 17,288 行测试 ≈ **1 : 0.31**，重点覆盖协议编解码与密码学。

### 5.2 包结构（main，175 文件）

| 包 | 文件 | 职责 | 包 | 文件 | 职责 |
|---|---|---|---|---|---|
| `airplay` | 24 | PairVerify、控制通道、流协商、加密、Timing | `iap2` | 24 | Link/CSM/发送端会话/NowPlaying |
| `launcher` | 3 | 只剩设置偏好（显示/连接/开发模式） | `bt` | 17 | RFCOMM Server/Client |
| `net` | 14 | WiFi 入网、mDNS 发现与 ctrl 广播 | `util` | 12 | 日志、导出、时钟等 |
| `carlife` | 16 | 冒充车机劫持本机引擎（98386/98311） | `audio` | 11 | AAC 编码、播放捕获、RTP 封装、`AudioSink` |
| `protocol` | 10 | **重构新增**：抽象层 + 注册表 + 两个协议门面 | `input` | 8 | 触摸分流、消费键、旋钮与媒体键路由 |
| `video` | 8 | H.264 封装、SPS 解析、VideoConfig 声明、`VideoSink` | `ui` / `session` / `service` | 3 / 6 / 5 | 对话框与自绘视图 / 会话总管 / 前台保活 |
| `legal` | 3 | 免责声明 | `transport` / `media` | 2 / 6 | iAP2 链路通道 / 通知歌词与会话轮询兜底 |
| 根入口 | 3 | `MainActivity` / `SenderApp` / … | | | |

结构性质：**协议主干 + 功能分层**的混合结构，不是纯按协议分组。三处交叉点已知：`net/` 里有 AirPlay 服务发现类、`transport/Iap2LinkChannel` 属 iAP2 但独立成包、`protocol/` 是跨协议抽象。

### 5.3 协议模块化重构（2026-11-19 已并入 `main`，`main` = `be02fdc`）

> 完整说明（分层图 / 能力矩阵 / 时序图 / 接入第三种协议的五步 / 缺口清单）见
> [`PROTOCOL-MODULES.md`](PROTOCOL-MODULES.md)。

目标：CarPlay / CarLife 各一个模块，用一个聚合文件注册，便于接第三种协议。

| 阶段 | 提交 | 内容 | 状态 |
|---|---|---|---|
| 1 | `3c74428` | `protocol/` 抽象：`ProtocolId` / `Capability` / `ProtocolEvent` / `ProtocolModule` / `ProtocolRegistry` / 数据契约 + 8 例测试 | ✅ |
| 2 | `9492a57` | `H264Sps` 从 `carlife/` 移到 `video/`，解除 carlife↔video 跨模块环（908 例通过） | ✅ |
| 3 | `28bf7b9` | `CarPlayModule` / `CarLifeModule` 门面，**纯委托注入**（不 import 协议实现，可纯 JVM 单测）+ 8 例 | ✅ |
| 4 | `68b0c00` | **媒体键收口**：`dispatchMediaKey` 走 Registry 的 `FIRST_MATCH`；未装配走本地原路径，二者互斥并共用 `consumeMediaKeyByCar` 一份实现。+ 4 例接线契约测试 | ✅ |
| 5 | `9df34e0` | **语音键 + 触摸收口**：共同点是「输入状态机留在原处，只投判定完的动作」——`VoiceKeyRouter` 交出的 VR_START/VR_STOP 硬键码走 `dispatchVoiceHardKey`；`TouchRouter` 在槽位分流前走 `dispatchTouch`。+ 4 例契约测试 | ✅ |
| 6 | `c583fea` | **数据面抽契约**：新增 `video.VideoSink` / `audio.AudioSink`，两个发送端反向实现；`CarLifeHijack.attach` 与 `CarLifeAudioSink` 改持接口。**carlife 不再 import `VideoSender`/`AudioSender`**，第三种协议接画面只需实现契约 | ✅ |
| 7 | `0268578` | **播放意图收口**：新增 `Capability.PLAYBACK_INTENT` / `ProtocolEvent.PlaybackIntent`，`onSourceAudioPlayback` 走 `FIRST_MATCH`；`CarPlayModule` 首次真实消费事件。+ 2 例（全量 926 例） | ✅ |

并入 main 时还带入一个与本重构**无关**的修复：`be02fdc` 会话轮询兜底——媒体键不再依赖
「通知监听器被系统绑上」，改了 `media/`（新增 `MediaSessionFallback`）与 `SessionController`
看门狗，与协议路由没有耦合，可独立回退。

阶段 4 首轮曾**整体**受阻回退：`dispatchMediaKey`、`voiceKey`（`VoiceKeyRouter` 状态机）、`carNmea`（`NmeaAssembler` 合并器）、`carLifeCommandSender`（独立线程池）四者紧耦合；当时还臆造了并不存在的 `prewarmCarLife()` / `NmeaAssembler.Fix`，已全部 `checkout` 回退，**没有提交错误代码**。

阶段 4 改用**互斥双路径**破解，不需要先给状态机做适配层：把媒体键原来的前两步抽成 `consumeMediaKeyByCar`，`dispatchMediaKey` 按 `ProtocolRegistry.hasConsumer(MEDIA_KEY)` 二选一——装配了走能力路由（门面委托回同一函数），没装配直接调本地函数。语义等价、不会重复消费。

收口的判据：**事件体能装下「已经判定完的动作」**。语音键交出 `0x21`/`0x22` 两个硬键码，触摸交出 `(down, x, y, slot)`，
播放意图交出三个布尔——都能无损装进事件。装不下的就不接，见下。

**刻意不接：GPS。** `CarGpsFix` 是 CarLife 协议的单位约定（度 / 0.1 米 / 0.01 km/h），
且只有 CarLife 一个消费者。做成通用事件只有两条路：把 CarLife 的单位搬进聚合层
（让 `protocol` 认识 0x18010 的编码约定），或砍掉 DOP / 卫星数 / UTC 时分秒
（引擎拿到半份快照）。两条都更差，所以留在 `onCarLocation` 不动。
真出现第二种定位来源时，再按那时的单位差异设计事件体。

**刻意不接：歌词/媒体信息。** 三条来源（CarLife 0x10035、系统 MediaSession、0x10036 进度）
最终都汇到 `pushNowPlayingAll()` 这个**无载荷触发**——数据从全局状态读，走事件只是多一次分配。
等真有第二种协议要推 NowPlaying 再改。

**仍未做：`SessionController` 瘦身。** 它仍是真正的编排中心（31 处跨协议 import），
Registry 只是插在中间。要把实例创建/生命周期也移进门面，等于重构整个会话状态机
（建链、重试、WiFi 绑定、mDNS、权限修复都缠在一起），风险远高于收益，**建议实车验证后再单独评估**。

---

## 六、能力矩阵

### ✅ 已实车验证

无线 iAP2 发送端会话（走通至 `0x4301`）· AirPlay 主会话 · CarLife 视频/音频（媒体 + TTS）直通 · HID 触摸 · 歌名/歌手/歌词 · 启动后自动连接 · 目标帧率跟随车机 `maxFPS`（夹在 24–120，缺省 60）· 60fps 投屏（改荣耀车联设置）· 奥迪 MIB3 全链路。

### ⚠️ 有硬限制

| 限制 | 原因 |
|---|---|
| 默认只有 20fps | 荣耀车联虚拟屏 `renderFrameRate 20.0`，得手动改设置到 60 |
| 分辨率不可完全自主 | 厂商 mix 层会覆盖编码尺寸，可能回到 1280×720 / 1080p；车联「分辨率」必须停「默认」 |
| 必须手动进 CarLife+ 并停在「正在连接」 | 自动拉起只能拿到欢迎页，是设计取舍不是 bug |
| 依赖 CarLife+ 版本行为 | 逆向结论基于荣耀 8.8.5（versionCode 274），升级后协议可能变 |
| 通话双方音频拿不到 | `AudioPlaybackCapture` 不允许 `USAGE_VOICE_COMMUNICATION`，无 root 无解 |

### 🧪 实验 / 待实车

双指缩放（`0x68001` 复合动作）· 车机 GPS 转发（`0x18010`）· 歌词顶替 title（实车日志显示从没送到）· 车机麦克风上行（**宝马 G38 实测会把车机打死**，现按 `audioInputFormats` 门控）· 导航独立音频流（1.2.47，副作用 duck 失效）。

### ⛔ 已移除

MIB3 车控三篇（EA / VIWI / 控制交接，1.2.29 后删）· 画面停摆分级干预（1.2.34 后看门狗只观测）· Shizuku · 自动拉起 CarLife。

---

## 七、风险与已知问题（按优先级）

### P0 —— 影响可用性，且没有确定解

**1. 厂商后台冻结（头号元凶，仍未根治）**

| 设备 | 证据 |
|---|---|
| 荣耀（1.2.62） | 冻结 7 / 19 / 25 秒 |
| 一加（1.2.63） | 冻结 10 秒 |
| iD9（vivo V2547DA / Android 16） | 28 分钟冻 16 次共 1031 秒，最长 **219 秒** |

已有缓解：心跳 ≥4000ms 判冻结 → 1.5s 复核 + 3/6/12/24s 四轮重建；前台服务补 `mediaPlayback` + `MediaSession`；体检页第 12 项「后台存活」。但**「电池优化已忽略」只管 Doze，管不到厂商冻结**——需要引导用户开厂商的「自启动 / 后台白名单」，这是产品层缺口。

**2. 音频顿挫的听感未复测（1.2.70）**

日志侧证据完整（发包均匀、录音零空洞、缓冲满主动丢包计数），但「用户听到的顿」始终是推断。验收只看两行：
`AUDIO: 发包队列 —— …峰值 N 帧…；空拍 0 次`（峰值应恒定、空拍 0）+「最长停拍」回到 21~22ms。

### P1 —— 特定车型/设备的硬缺口

**3. 宝马 300 / iD9 连不上**：12 次 iAP2 会话、**0 条 `0x4301`**；只有 2 轮进入 5 分钟空端点等待并超时；5 次 mDNS 探测**均无 `_airplay._tcp`**；21:32 Wi-Fi 断开切回 4G。1.2.65 的 bit37 兜底依赖 mDNS 端点，**对此车无效**。下一步应查车机热点是否做了 AP 隔离 / mDNS 屏蔽（而不是继续改 iAP2 报文）。

**4. 帧率与画质天花板**：20fps 默认 + mix 层覆盖尺寸，用户侧无感，容易误判为 App bug。

### P2 —— 排障与工程债

**5. 接收端不可观测**：WinPlay 不回报 `statsDictionary`；它每 10 秒一次的 682ms `ptsGapMs` 重锚是接收端行为，本项目未改。意味着「车机侧卡顿」无法直接取证。
**6. 发版 tag 缺口**：`v1.2.66`～`v1.2.70` 本地无 tag。
**7. 重构已收口媒体键/语音键/触摸，GPS 与播放意图仍需专门事件体**：见 §5.3。
**8. 早期文档结论有 11 处过时/自相矛盾**：已在 `PROJECT-GUIDE.md` §7 收敛成 8 条红线，归档 5 篇彻底失效文档（见 §10）。

---

## 八、未实车验证清单（看版本历史时别当成已证实）

| 版本 | 改动 | 证据强度 | 验收判据 |
|---|---|---|---|
| 1.2.47 | 导航播报独立音频流 | 代码 + 单测 | 导航播报时不打断音乐；duck 仍生效 |
| 1.2.50 | 专辑封面 / 窗口保活 | 日志 | 车机媒体卡显示封面、歌词滚到底 |
| 1.2.52 | 引擎 PAUSE 后永久静音的放行闸门 | 日志 | 暂停再播放能恢复声音 |
| 1.2.64 | RTP 交付对账 / 静音保流 / 前台保活 / 语音键三连击 | 单测 | **已回滚，勿引用** |
| 1.2.65 | bit37 直连 / 编码解耦一拍一包 | 单测 | **已回滚，勿引用** |
| 1.2.70 | 非阻塞发包 / 采样率三档对齐 | 真机日志 + 录音分析 | 见 §7-P0-2 两行 |

---

## 九、分支与协作状态

| 项 | 状态 |
|---|---|
| `main` | `be02fdc`，**与 `origin/main` 一致**（已推送）；由 `95f8b6b` 快进并入重构分支，无 merge commit |
| `refactor/protocol-modules` | `be02fdc`，与 `main` 同点，已推送（保留分支便于对照） |
| `backup/main-pre-protocol-20261119` | `95f8b6b`，合并前的 main 快照，已推送远端 |
| 其他本地分支 | 共 19 条：`backup/*` 9 条（含 `backup/audio-before-rollback-1.2.68`、`backup/carlife-invite-1.2.56`）、`switch-pool` / `switch-pool-auto`、`pr25`/`pr26`/`pr27`、`usb`、`carlife-plugin`、`honor-provider`、`main-backup-4580` |
| 未跟踪文件 | `design/`（含唯一有效 UI 稿 `from-scratch.html`） |
| 工作区 | 干净（本轮文档整理已提交） |

**协作约定（用户明确要求，长期有效）**：

1. 改完代码**只本地提交**；等说「推送 / 发版 / 推上去」才推、才建 Release。
2. **只动指定分支**；没说「合并」不并到 `bieke`。
3. 重装测试包用 `adb install -r` 保留数据，**不要 `adb uninstall`**。
4. 推送直连 GitHub 常报 `Empty reply from server`，走 `.tmp/api_push.py`（令牌从 `git credential fill` 读取，不落盘）。
5. `pre-commit` 自动跑 Spotless/ktlint + `lintDebug`；**别随手 `spotlessApply`**（HEAD 本身不满足当前 ktlint，会重排 50+ 文件）。
6. 右侧面板打开 README 会自动重排表格并插入空表头——**看到重排不要提交**。

---

## 十、文档体系现状

### 10.1 本轮整理做了什么

| 动作 | 内容 |
|---|---|
| 新建 `docs/README.md` | 文档目录入口：按角色的阅读路径、命名规范、状态图例、归档规则 |
| 新建 `docs/PROJECT-STATUS.md` | 本文件（项目体检报告） |
| 新建 `docs/archive/` | 归档 **5 篇**已失效或外部存档文档，附归档说明 |
| 更新 `PROJECT-GUIDE.md` | 归档路径、新增入口、基线提交、篇数 |
| 更新 `README.md` | 两处指向已归档文档的链接 |

归档的 5 篇（**不要再当现状引用**）：

| 文档 | 原因 |
|---|---|
| `audi-mib3-ea-tunnel-20261104.md` | 1.2.29 后 EA 会话整体移除，仅历史取证 |
| `mib3-viwi-control.md` | 同上；五项推断从未验证 |
| `mib3-control-handover.md` | 同上；含「只在车停稳时试写」安全红线 |
| `video-stall-recovery-20261108.md` | 1.2.34 后分级干预全删，仅「最后一帧距今」判据仍有效（已并入指南） |
| `connect-protocol-and-models.md` | 外部存档（另一项目 1.0.51，9 台奥迪机型），文档自述勿当现状 |

### 10.2 现状

| 项 | 数量 |
|---|---|
| 整理前 docs 顶层 | 48 篇（47 篇专题 + 1 篇指南） |
| 现行专题文档 | **43 篇** |
| 归档 | 5 篇 |
| 元文档 | `README.md`（docs 入口）、`PROJECT-GUIDE.md`（导航）、`PROJECT-STATUS.md`（本文件）、`archive/README.md` |

篇均信息密度很高（单篇 4–20 KB，多为真机日志推导），但**结论会被后续版本推翻**是本项目的常态，因此状态标签比内容更重要：✅ 实车验证 · 🧩 已实现 · 📐 规范/调研 · 🗄 外部存档 · ⛔ 已废弃 · ⚠️ 含自我更正/待验证。

---

## 十一、合规与法律边界

- **非 MFi 产品**，未获 Apple / 百度授权；CarPlay / AirPlay / CarLife 均为其权利人商标，文中仅用于说明兼容目标。
- 源码 MIT；**收费卖软件会改变性质**（MFi、规避技术措施、百度 CarLife、隐私合规四项风险同步升级），因此只做赞助 / 技术支持。
- 仓库不含反编译产物、厂商 SDK、抓包二进制、私钥、真实车机名；提交前跑 `scripts/check-no-vendor-artifacts.sh` 守卫；历史提交已于 2026-10-25 用 filter-branch 脱敏。
- 应用内首次启动免责声明与根 `DISCLAIMER.md` 同源；驾驶安全条款：仅停车配置，行驶中以原厂导航为安全冗余。

---

## 十二、建议的下一步（按性价比排序）

| # | 事项 | 为什么现在做 | 完成判据 |
|---|---|---|---|
| 1 | **实车复测 1.2.70** | 成本最低、消除最大不确定性：顿挫到底修没修 | 「最长停拍」21~22ms、空拍 0、峰值恒定 |
| 2 | **厂商冻结治理** | P0 且无解；当前只能靠用户手动加白名单 | 体检页给出机型专属白名单指引；iD9 单次会话无 >30s 冻结 |
| 3 | **refactor 阶段 4 设计先行** | 直接改代码已失败一次；缺的是接口适配层 | 先出 `MediaKey` / `VoiceKey` / `NMEA` / `CarLifeCommand` 四个适配接口设计，再动 `SessionController` |
| 4 | **发版脚本补 tag** | 避免 1.2.66～1.2.70 这种情况复发 | 发版一条命令产出 tag + Release + README 六处 |
| 5 | **iD9 专项** | 唯一的「完全连不上」车型 | 先确认车机热点是否屏蔽 mDNS / AP 隔离，再决定改不改代码 |
| 6 | **接入第三种协议的准备** | `protocol/` 抽象已就位，只差文档化接入步骤 | 写一页「新增协议模块 checklist」 |

---

## 附：与其它文档的关系

- 想知道**某篇文档讲了什么**、**某个症状先看哪篇** → [`PROJECT-GUIDE.md`](PROJECT-GUIDE.md)
- 想知道**项目现在什么状态、哪些还没验证** → 本文件
- 想知道**版本逐条改了什么** → [`../README.md`](../README.md) 第十一节
- 想知道**怎么用、怎么排障** → [`../README.md`](../README.md) 第三 / 五节
