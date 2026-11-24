# CarPToCarL 项目文档指南（精简版）

> 定位：**现行结论唯一真源索引**——导航、状态标签、排障决策表、冲突红线。
> 逐版变更与当时的现场证据在根 [`CHANGELOG.md`](../../CHANGELOG.md)；
> 历史排障取证在 [`../archive/`](../archive/README.md)（**只作取证，不是现状**）；
> 本文件只收拢现行结论、标注状态、指出冲突。冲突时以 `docs/current/` 与源码为准，
> **不要照抄早期结论**。
>
> 核对基线：2026-11-23 · 本地 `main` @ `9d4af13`（比 `origin/main` 的 v1.2.83 多 3 个未推送提交：
> 删隐形屏、清残骸、MGU-81 会话重建三修）· 发布版 **v1.2.83**。
> 下文部分数字截止 2026-11-19，涉及代码规模 / 单测数以源码实算为准。
>
> 两个配套入口：[`docs/README.md`](../README.md)（目录地图，按角色给阅读路径）、
> [`PROJECT-STATUS.md`](PROJECT-STATUS.md)（项目体检报告：版本/代码/风险/未验证清单）。

---

## 0. 30 秒看懂项目

| 项 | 值 |
|---|---|
| 做什么 | 未 root 的 Android 手机冒充 **CarPlay 发送端（iPhone 角色）**，把本机 **CarLife+ 引擎的 H.264 / PCM 原样直通**到只认 CarPlay 的车机；不重编码 |
| 包名 / 系统 | `io.github.shaonicalife.carptocar` · Android 12+（minSdk 31 / targetSdk 34） |
| 链路 | 蓝牙 RFCOMM + iAP2（谈凭据）→ 加入车机热点 + mDNS（找端点）→ AirPlay（PairVerify / SETUP / RECORD）→ 视频、音频、HID 四条数据面 |
| 画面源 | 荣耀车联创建的虚拟屏 `HonorAutoDisplay`（1920×1080 默认 20fps），**必须手动打开 CarLife+ 并停在「正在连接」**；App 自动拉起只会拿到欢迎页 |
| 已真车验证 | 奥迪 MIB3（2026-10-11 全链路）；DiPlay / xcertplay / WinPlay 为测试台 |
| 明确不做 | 有线 USB（无 root 无解，见 `WIRED-CARPLAY-FEASIBILITY.md`）；Shizuku（2026-10-24 已移除） |
| 许可边界 | 源码 MIT；**非 MFi 产品**，不授予 Apple / 百度协议与商标权利；开源可继续，**收费只做赞助/技术支持** |

### 三个反复踩的技术事实

1. **认证是单向的**：手机验证车机 MFi 证书、车机信任手机自报结果 → 发送端不需要任何 Apple 签名材料。
2. **分辨率/帧率由荣耀侧决定**：CarLife 协议改不动，只能在手机「荣耀车联 → 投屏显示设置」改；厂商 mix 层还会覆盖编码尺寸。
3. **声明 ≠ SPS ≠ 出画**：`VideoConfig` 声明跟车机画布；SPS 只作诊断。历史上拿 SPS 反改声明导致真车黑屏；2026-11-06 实车又证伪了「三者全等就出画」。

---

## 1. 连接分层（先定层，再看日志）

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
| L3 iAP2 控制面 | 0x4300/0x5703 拿凭据与端点、CSM 消息 | 一直等不到 0x4301；链路 ACK 但业务层静默丢弃 |
| L4 AirPlay 会话 | PairVerify、SETUP（拿画布/音频档）、RECORD | 403、SETUP 超时、`modesChanged` 200 仍黑屏 |
| L5 应用编排 | `SessionController`、会话状态机、CarLife 劫持 | 前台/权限/编码器/重启恢复 |

**数据面四通道**：视频 H.264（AirPlay 屏幕流）、音频媒体（CarLife 9240 / `0x3000x`）、TTS 导航（9241 / `0x4xxxx`）、HID 触控回传（CarLife 9340）。

> 0x5703 到手必须**立刻入网并广播 ctrl**；等 0x4301 再广播会与车机互等死锁。0x4301 实测可晚 2 分 35 秒。

---

## 2. 最短使用路径

```
① 系统蓝牙配对车机
② 打开荣耀车联 / CarLife+ → 点【无线 WLAN 连接】
③ 停在「正在连接」界面（不要退出/返回）
④ 回 CarPToCarL →【启动连接】（自动连接默认开）
⑤ 出画后：车联设置 → 帧率 60；分辨率保持【默认】→ 断开重连一次
```

- **蓝牙 MAC 必须真实**：`0x4300` / `0x4E0E` / SETUP / ctrl TXT 都校验它；读不到时按钮会置灰。解析顺序：手填 → sysfs → Settings.Secure → 反射 → shell；最可靠是**从车机已配对列表或另一台手机扫到后手填**。
- **权限**：附近设备/蓝牙、通知使用权（歌名歌词）、通知权限（前台保活）；「显示在其他应用上层」缺失会让靠近自动启动静默失败。
- **日志**：主界面底部可复制/导出/清空；文件在 `files/main_debug.log`。荣耀机 logcat 被 HKS 加密、`run-as` 不可用，靠 App 导出后 `adb pull`。

---

## 3. 能力 / 限制 / 未验证

| 状态 | 内容 |
|---|---|
| ✅ 已实车 | iAP2 发送端会话至 0x4301、AirPlay 主会话、CarLife 视频/音频（媒体+TTS）直通、HID 触摸、歌名/歌词、自动连接、帧率跟随车机 maxFPS（24–120，缺省 60） |
| ⚠️ 有限制 | 默认 20fps（可改 60）；编码尺寸受厂商 mix 层覆盖，可能回到 1280×720/1080p；依赖 CarLife+ 荣耀 8.8.5 协议行为；必须手动进 CarLife+ |
| 🧪 实验/待验 | 双指缩放（0x68001 复合动作）、车机 GPS 转发、歌词顶替 title、车机麦克风上行（**宝马 G38 实测会把车机打死，现按 `audioInputFormats` 门控**） |
| ⛔ 已移除 | MIB3 车控三篇（EA / VIWI / 控制交接，1.2.29 后整体删除）、画面停摆分级干预（1.2.34 后看门狗只观测）、Shizuku、自动拉起 CarLife |

**尚未实车验证的修复**（看 `CHANGELOG.md` 时别当成已证实）：1.2.47 导航独立流、1.2.50 封面/窗口、1.2.52 静音、**1.2.70 非阻塞发包与采样率对齐**（日志证据充分，但听感未复测）。

> **1.2.64 / 1.2.65 的修复已被整批回滚**（`ab7f7d5`…`c39ff97` 一组 Revert，1.2.66–1.2.68），
> 当前代码里**不存在**：抗冻结四项、语音键三连击、RTP 交付对账 32 帧、bit37 直连、编码解耦一拍一包。
> 只有经日志复核的两项（非阻塞发包、采样率对齐）在 1.2.70 重新落地。读 `CHANGELOG.md` 时按此口径。

---

## 4. 排障决策表

| 症状 | 第一刀 | 深入文档 |
|---|---|---|
| 一直连不上 / 等不到 0x4301 | 先定层：L1 SDP → L2 热点/mDNS → L3 0x4300/0x5703 → L4 SETUP；看是否「链路 ACK、业务静默」 | `carplay-connection-layers-20261109.md`、`connect-recovery.md`、`gm-cinemo-4301-deadlock.md` |
| 连上没画面 / 黑屏 | 7240 是否监听、CarLife 是否停在「正在连接」、**首帧 NAL 是否 IDR**、VideoConfig 声明是否跟画布；rfps>0 只证明认证后收帧 | `carplay-black-screen-20261106.md`、`carplay-no-projection-conditions.md`、`videoconfig-canvas-contract.md` |
| 有画面没声音 | 音频 type 100/102 是否选对；9240/9241 两条通道；采样率 44.1k/48k 是否两端对齐 | `audio-channel-watchdog.md`、`audio-call-issues.md`、README 1.2.70 节 |
| 播放一段时间一顿一顿 | 看「最长停拍」归因：park / 混音 / 发包；发包占主导 → 非阻塞 `DatagramChannel` 已修 | README 1.2.70、1.2.65 节 |
| 只有 20fps / 拖不动 | 车联设置帧率 60（默认档就是 20）；分辨率必须「默认」；改完断开重连 | 根 `README.md` 快速上手 |
| 触摸失灵 / 拖不动 / 点完卡死 | 命令 ID `0x68001`~`0x68008` 错位、坐标不要二次缩放、缺 UP 时补发、MOVE 本地不再节流 | `touch-multitouch.md`（现行） |
| 仪表没转向 / 转向方向不对 | 先看开关「使用手机高德地图获取导航数据」：打开时 CarLife `0x10030` 被拦，高德没开始导航就一条都不发；方向错只改 `AmapNaviMapper.toCarLifeIcon` | `amap-navi-source.md`（现行）、设置里「高德数据源自检」 |
| 连上一会自己断 | 视频 0 帧约 30s 车机拆会话；空闲重发最后 IDR；写 socket 必须同一把锁 | [`../archive/connect-hardening.md`](../archive/connect-hardening.md)（历史取证） |
| 车机反复重连 | 对端字节数是否比正常多一条消息净荷（历史真凶：0x5001 双重封包） | [`../archive/connect-recovery.md`](../archive/connect-recovery.md)（历史取证） |
| 车机没歌名歌词 | 通知使用权是否真的 rebound（荣耀需关开一次）；98386 `MEDIA_LYRIC_SUPPORT=1` | [`../archive/iap2-lyric-lines.md`](../archive/iap2-lyric-lines.md)（历史取证） |
| 方向盘语音键没反应 | 走事件通道 `requestSiri`（`siriAction` 1/2/3），不是 HID 0xCF；看 65574 模块状态是否=1 | `carlife-voice-siri-20261104.md` |
| 冻结 / 掉网 | 心跳间隔 ≥4000ms 判冻结并重建；断链时刻附近有无 `WIFI:` 行——没有就排除 wlan0 | `process-freeze-recovery.md`、`wifi-link-watchdog.md` |

---

## 5. 代码地图

单模块 Android 应用，`main` 主源码 **180** 个 Kotlin 文件，按链路职责切包（协议主干 + 功能分层）：

| 包 | 职责 | 包 | 职责 |
|---|---|---|---|
| `carlife` | 冒充车机劫持本机引擎，98386/98311 下发 | `iap2` | Link/CSM/发送端会话/NowPlaying |
| `airplay` | PairVerify、控制通道、流协商、加密、Timing | `video` | H.264 封装、SPS 解析、VideoConfig 声明 |
| `audio` | AAC 编码、播放捕获、RTP 封装 | `bt` | RFCOMM Server/Client |
| `net` | WiFi 入网、mDNS 发现与 ctrl 广播 | `input` | 触摸分流、消费键、CarInput（AIDL） |
| `media` | 通知使用权 → 媒体/歌词数据源 | `service` | 前台保活 |
| `launcher` | 只剩设置偏好（显示缩放/自适应触摸/转向指引） | `ui` / `legal` / `util` | 波形图、深色弹窗、免责声明、日志导出等 |
| `protocol` | 协议聚合层（门面 + 能力路由 + 代际审计） | `session` / `transport` | 进程级会话编排 / iAP2 链路通道 |

入口：`MainActivity.kt`（操作台）、`SessionController.kt`（进程级会话总管）、`SenderApp.kt`。单测 **130** 个文件 / **979** 例，重点覆盖协议编解码与密码学。

> **协议模块化重构已并入 `main`**（2026-11-19 快进，`main` = `be02fdc`；旧 main 备份在
> `backup/main-pre-protocol-20261119`）。`protocol/` 10 文件：`ProtocolModule` / `ProtocolRegistry` /
> `Capability` / `ProtocolEvent` 抽象，`CarPlayModule` / `CarLifeModule` 两个纯委托门面；
> **媒体键 / 语音键 / 触摸 / 播放意图四条已收口**——装配后走 Registry 的 `FIRST_MATCH`，未装配走
> 本地原路径，二者互斥且共用同一份实现；数据面另抽 `video.VideoSink` / `audio.AudioSink`，
> `carlife` 不再依赖具体发送端。**GPS 刻意不接**（`CarGpsFix` 是 CarLife 单位约定且单消费者）。
> `SessionController` 瘦身留到实车验证后单独评估。完整说明见
> [`PROTOCOL-MODULES.md`](PROTOCOL-MODULES.md)、[`PROJECT-STATUS.md`](PROJECT-STATUS.md) §5.3。

---

## 6. 文档索引（现行真源 15 篇 + 历史归档 40 篇）

> 定现行行为**只读 `current/`**。历史排障按 [`../archive/README.md`](../archive/README.md) 的分组取证，
> 引用前先核对 §7 冲突红线。图例：✅ 实车验证 · 🧩 已实现 · 📐 规范 / 调研 · ⚠️ 含更正 / 待验证。

### 6.1 现行规范 / 架构（`docs/current/`，唯一真源）

| 文档 | 状态 | 一句话 / 关键判据 |
|---|---|---|
| `PROJECT-GUIDE.md` | 🧩 | 本文件：导航、状态标签、排障决策、冲突红线、构建发版速查 |
| `PROJECT-STATUS.md` | 🧩 | 体检报告：版本 / 代码 / 能力 / 风险 / 未验证清单（部分数字截止 11-19） |
| `PROTOCOL-NOTES.md` | ✅ | 发送端协议全集；以 §9c-1/2/3 最新修正为准：modes 状态机、0x4300 等确认 |
| `PROTOCOL-MODULES.md` | 🧩 | 协议聚合层：能力路由、`VideoSink`/`AudioSink` 契约、接第三种协议五步 |
| `CARPLAY-WIRELESS-HEADUNIT-SPEC.md` | 📐 | 接收端视角的无线 CarPlay 规范；附录 E 改正两处表格 |
| `协议核完全逆向-20261122.md` · `协议核解包分析-20261122.md` | 🧩 | v2 协议核链路与静态 JNI 绑定（改包名即全废） |
| `carplay-connection-layers-20261109.md` | 🧩 | L1–L5 全局架构 + 症状→层判据 |
| `audio-channel-watchdog.md` | 🧩 | 看门狗三重设限；路径 A 真死重连、路径 B 静音退避、TTS 只走 A |
| `videoconfig-canvas-contract.md` | ⚠️ | 11-08 定案默认 raw；视口只是头部元数据，不改码流与节奏 |
| `touch-multitouch.md` | 🧩 | 双指走 0x68001 复合动作；收尾顺序 POINTER_UP→ACTION_UP |
| `carlife-hijack-integration.md` | 🧩 | 劫持架构 / 开关 / 回退；7240 不通 1.5s 回退 |
| `amap-navi-source.md` | ✅ | 手机高德 linksdk 数据源：开关**替换** CarLife 0x10030；转向码需独立映射；`type=100/200` 才是清帧依据 |
| `WIRED-CARPLAY-FEASIBILITY.md` | 📐 | 有线路线否决结论（现行：只走无线） |
| `LEGAL-NOTES.md` | 📐 | 收费风险、商业化三件套、脱敏与守卫脚本 |

### 6.2 历史排障归档（`docs/archive/`，40 篇）

按组列在 [`../archive/README.md`](../archive/README.md)：

- **A 已失效功能 / 外部存档（5 篇）**：MIB3 EA / VIWI / 车控三连篇、画面停摆分级干预、外部 AuDiCar 存档。
- **B 历史排障 / 复盘（22 篇）**：带日期的诊断与自我更正，如 `carplay-black-screen-20261106.md`、
  `carlife-voice-siri-20261104.md`、`audio-call-issues.md`、`connect-recovery.md` 等。
- **C 调研 / 可行性 / 对照 / 变更总账（13 篇）**：如 `CARLIFE-GATEWAY-FEASIBILITY.md`、
  `wireless-diplay-comparison.md`、`changes-20261104.md` 等。

**使用规则**：归档文档只用于还原「当时为什么这么判」、查旧链接与旧日志口径；
其中任何结论与 `current/` 或源码冲突时，一律以 `current/` + 源码为准，按 §7 判定。

---

## 7. 冲突与红线（别绕回去）

1. **出画三元素全等 ≠ 出画** —— `carplay-black-screen` 已实车证伪 `videoconfig-canvas-contract` 的早期推断。
2. **0x4300 报文体不是通用系根因** —— `gm-4300-dual-identifier` §6 推翻 §2；三档全 ACK 无回应。
3. **链路层 ACK ≠ 业务层认账** —— 11-10 更正：0x4E0D 改回「未声明不发」，新协议车机整体严格模式。
4. **语音键不是没发** —— 是采样窗口太短误判；真实通路为事件通道 `requestSiri`。
5. **麦克风上行两次推翻** —— 独立 input 探针作废；media 档塞 dataPort 会打死宝马 G38。
6. **MIB3 EA 三连篇已废弃** —— 固件分析篇里 EA 相关描述按历史处理。
7. **停摆分级干预已移除** —— 看门狗只观测，不再重建编码器/会话。
8. **外部存档勿当现状** —— [`archive/connect-protocol-and-models.md`](../archive/connect-protocol-and-models.md) 基于另一项目 1.0.51。
9. **1.2.64 / 1.2.65 的修复已回滚** —— [`CHANGELOG.md`](../../CHANGELOG.md) 里这两版的抗冻结、三连击、交付对账、bit37 直连、编码解耦均不在当前代码，仅 1.2.70 重新落地了非阻塞发包与采样率两项。
10. **`docs/archive/` 只作取证** —— 旧结论不得直接照抄进新修改；判断现状只读 `docs/current/` 与源码，归档里的自我更正章节优先于原文结论。

**合规红线**：不卖 APK/订阅，只收赞助或技术支持；仓库不含反编译产物、厂商 SDK、抓包二进制、私钥和真实车机名；提交前 `scripts/check-no-vendor-artifacts.sh` 守卫。

---

## 8. 构建与发版速查

```bash
./gradlew assembleDebug --offline                       # 固定 JDK 17 + 项目内 .tools/，勿改
./gradlew :app:assembleRelease :app:testReleaseUnitTest # 发版前必跑（不要与 clean 同命令）
./.tools/platform-tools/adb.exe install -r <apk>         # 同签名覆盖装，保留应用数据
```

- `pre-commit` 自动跑 Spotless/ktlint + `lintDebug`；**别随手 `spotlessApply`**（HEAD 本身不满足当前 ktlint，会重排 50+ 文件）。
- 版本号唯一来源：`app/build.gradle.kts` 顶部 `appVersionName`；**禁止连续两个 4**（`1.2.44` 构建立刻失败）。
- 发版同步四处：README 下载行 / 固定包文件名与链接 / 签名覆盖版本 → 根 [`CHANGELOG.md`](../../CHANGELOG.md) 新条目 → `app/build.gradle.kts` 的 `appVersionName` →（`bieke` 另有后缀规则）。固定 tag 直链，**不用 `releases/latest`**；版本流水只写 `CHANGELOG.md`，不再回填 README。
- 协作约定：**改完只本地提交**；用户说「推送/发版」才推、才建 Release；只动指定分支，未说「合并」不并到 `bieke`。PR 常为 draft 且无 CI，合并后必须本地重编译再发版。

---

## 9. 文档维护约定

- **分区**：只有 [`current/`](.)（现行规范 / 架构，唯一真源）与 [`archive/`](../archive/README.md)（历史排障取证）。
  新诊断文档命名 `主题-YYYYMMDD.md`，文首写明**一句话结论 + 状态**，写完直接 `git mv` 进 `archive/`；
  只有定义了现行规范 / 架构的文档才进 `current/`。
- 结论被推翻时**在原文追加更正章节并注明日期**，不要删历史；本指南同步改状态标签与 §7 冲突红线。
- 归档时同步三处：本指南 §6 索引与状态、根 [`README.md`](../../README.md) 链接、[`archive/README.md`](../archive/README.md) 原因表。
- 引用文档时链接到具体文件，不复制大段日志；参数/门限写清数值和来源（实测/固件/推断）。
- 本指南随发版核对一次：版本基线、状态标签、未验证清单；项目级状态变化同步到 [`PROJECT-STATUS.md`](PROJECT-STATUS.md)；逐版变化写入根 [`CHANGELOG.md`](../../CHANGELOG.md)。
