# CarPToCarL 项目文档指南（精简版）

> 定位：**导航 + 现行结论摘要**。原始证据、日志时间线和逐版变更仍在 [README.md](../README.md) 与各专题文档；
> 本文件只做收拢、标注状态、指出冲突。冲突时**以文档中最后一次更新为准**，不要照抄早期结论。
> 核对基线：2026-11-19 · `main` @ `b7506d3` · v1.2.70（900 例单测 0 失败，听感顿挫修复未实车验证）。

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
| L5 应用编排 | `SessionController`、会话状态机、CarLife 劫持 | 前台/权限/虚拟屏/重启恢复 |

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

**尚未实车验证的修复**（看版本历史时别当成已证实）：1.2.47 导航独立流、1.2.50 封面/窗口、1.2.52 静音、1.2.64 四项、1.2.65 两项、**1.2.70 非阻塞发包与采样率对齐**（日志证据充分，但听感未复测）。

---

## 4. 排障决策表

| 症状 | 第一刀 | 深入文档 |
|---|---|---|
| 一直连不上 / 等不到 0x4301 | 先定层：L1 SDP → L2 热点/mDNS → L3 0x4300/0x5703 → L4 SETUP；看是否「链路 ACK、业务静默」 | `carplay-connection-layers-20261109.md`、`connect-recovery.md`、`gm-cinemo-4301-deadlock.md` |
| 连上没画面 / 黑屏 | 7240 是否监听、CarLife 是否停在「正在连接」、**首帧 NAL 是否 IDR**、VideoConfig 声明是否跟画布；rfps>0 只证明认证后收帧 | `carplay-black-screen-20261106.md`、`carplay-no-projection-conditions.md`、`videoconfig-canvas-contract.md` |
| 有画面没声音 | 音频 type 100/102 是否选对；9240/9241 两条通道；采样率 44.1k/48k 是否两端对齐 | `audio-channel-watchdog.md`、`audio-call-issues.md`、README 1.2.70 节 |
| 播放一段时间一顿一顿 | 看「最长停拍」归因：park / 混音 / 发包；发包占主导 → 非阻塞 `DatagramChannel` 已修 | README 1.2.70、1.2.65 节 |
| 只有 20fps / 拖不动 | 车联设置帧率 60（默认档就是 20）；分辨率必须「默认」；改完断开重连 | README 第三节 |
| 触摸失灵 / 拖不动 / 点完卡死 | 命令 ID `0x68001`~`0x68008` 错位、坐标不要二次缩放、缺 UP 时补发、MOVE 本地不再节流 | README 第五节、`touch-multitouch.md` |
| 连上一会自己断 | 视频 0 帧约 30s 车机拆会话；空闲重发最后 IDR；写 socket 必须同一把锁 | README 第五节 |
| 车机反复重连 | 对端字节数是否比正常多一条消息净荷（历史真凶：0x5001 双重封包） | README 第五节 |
| 车机没歌名歌词 | 通知使用权是否真的 rebound（荣耀需关开一次）；98386 `MEDIA_LYRIC_SUPPORT=1` | README 第五节、`iap2-lyric-lines.md` |
| 方向盘语音键没反应 | 走事件通道 `requestSiri`（`siriAction` 1/2/3），不是 HID 0xCF；看 65574 模块状态是否=1 | `carlife-voice-siri-20261104.md` |
| 冻结 / 掉网 | 心跳间隔 ≥4000ms 判冻结并重建；断链时刻附近有无 `WIFI:` 行——没有就排除 wlan0 | `process-freeze-recovery.md`、`wifi-link-watchdog.md` |

---

## 5. 代码地图

单模块 Android 应用，主源码 **169** 个 Kotlin 文件，按链路职责切包（协议主干 + 功能分层）：

| 包 | 职责 | 包 | 职责 |
|---|---|---|---|
| `carlife` | 冒充车机劫持本机引擎，98386/98311 下发 | `iap2` | Link/CSM/发送端会话/NowPlaying |
| `airplay` | PairVerify、控制通道、流协商、加密、Timing | `video` | H.264 封装、SPS 解析、VideoConfig 声明 |
| `audio` | AAC 编码、播放捕获、RTP 封装 | `bt` | RFCOMM Server/Client |
| `net` | WiFi 入网、mDNS 发现与 ctrl 广播 | `input` | 触摸分流、消费键、CarInput（AIDL） |
| `media` | 通知使用权 → 媒体/歌词数据源 | `service` | 前台保活 |
| `launcher` / `render` | 隐形屏（保留非默认）/ EGL 合成 | `ui` / `legal` / `util` | 暗幕、免责声明、日志导出等 |

入口：`MainActivity.kt`（操作台）、`SessionController.kt`（进程级会话总管）、`SenderApp.kt`。单测 **118** 个文件 / **900** 例，重点覆盖协议编解码与密码学。

---

## 6. 文档索引（docs/ 全部 47 篇）

> 6.1 是跨主题精选的阅读顺序；6.2–6.7 按主题完整列出，少量重复项用「见…」交叉引用。

图例：✅ 实车验证 · 🧩 已实现 · 📐 规范/调研 · 🗄 外部存档（勿当现状） · ⛔ 已废弃（仅历史） · ⚠️ 含自我更正/待验证

### 6.1 优先读（先定层、再查细节）

| 文档 | 状态 | 一句话 / 关键判据 |
|---|---|---|
| `carplay-connection-layers-20261109.md` | 🧩 | L1–L5 全局架构 + 症状→层判据表；0x4301 可晚 2′35″ |
| `PROTOCOL-NOTES.md` | ✅ | 发送端协议全集；以 §9c-1/2/3（11-10/11）最新修正为准：modes 状态机、0x4300 等确认、新协议严格模式 |
| `carplay-black-screen-20261106.md` | ✅ | 证伪「声明=SPS=窗口即出画」；屏幕接管门 12s / rfps 探针 |
| `bmw-fork-port-20261108.md` | ✅ | SDP 常驻、1.5s 音频守卫、首帧不补 RECORD（3b 又删控制就绪补发）；影响所有车型 |
| `connect-recovery.md` | 🧩 | 入网互斥、3s/10s/30s 退避、ctrl 即时响应、半握手双条件定性 |
| `carlife-hijack-integration.md` | 🧩 | 劫持架构/开关/回退；7240 不通 1.5s 回退；三项待装机验证 |
| `videoconfig-canvas-contract.md` | ⚠️ | 11-08 定案默认 raw；视口只是头部元数据，不改码流与节奏 |
| `LEGAL-NOTES.md` | 📐 | 收费风险、商业化三件套、脱敏与守卫脚本 |

### 6.2 协议与规范

| 文档 | 状态 | 一句话 / 关键判据 |
|---|---|---|
| `CARPLAY-WIRELESS-HEADUNIT-SPEC.md` | 📐 | 接收端规范（1342 行）；✅逐字节确证/⚠️有支持/❓待抓包；附录 E 改正两处表格 |
| `carplay-manual-crosscheck-20261107.md` | ⚠️ | 手册对照：采纳 1（初始序号随机）、不采纳 9；CRC-16 不改 |
| `wireless-diplay-comparison.md` | 📐 | DiPlay 对照：缺 Wi-Fi Direct；0x5200 只 ACK，不发导航箭头 |
| `ocarplay-crosscheck-20261108.md` | 📐 | 接收端对照：FreshAuthPolicy、coded/visible/declared 三值诊断；只收 SPS 66/77/88 |
| `cadillac-ct5-carplay-apk-analysis.md` | 📐 | CT5 反编译：三档报文体、双向+方向回退、按声明裁剪 |
| `cardeck-0.1.28-port.md` | ⚠️ | 四项移植；11-10 更正：0x4E0D 改回「未声明不发」，ACK≠业务认账 |
| `connect-protocol-and-models.md` | 🗄 | 外部存档（AuDiCar 1.0.51），9 台奥迪机型；文档自述勿当现状引用 |
| `WIRED-CARPLAY-FEASIBILITY.md` | 📐 | 有线路线否决：四道 Android 门，唯一现实解是 USB gadget 硬件 |
| `changes-20261104.md` | 🧩 | 1.2.18→1.2.20 改动总账；编码尺寸五补丁、4580 重连三改 |

### 6.3 CarLife 劫持 / 输入 / 媒体

| 文档 | 状态 | 一句话 / 关键判据 |
|---|---|---|
| `CARLIFE-GATEWAY-FEASIBILITY.md` | 📐 | 网关可行性；7 端口、8 阶段握手；高德走闭源 ICCOA Carlink |
| `carlife-wireless-verification.md` | 📐 | 单机回环三组实验，立项判定四项全部未勾选 |
| `carlife-hijack-findings.md` | ✅ | 以第七节为准：PC 假车机拿到原生画面；协议版本必须发 **3.0** |
| `carlife-notice-frame.md` | 🧩 | 未劫持时推单帧 H.264 提示；首帧判据改用 framesDelivered 差值 |
| `carlife-voice-gps-20261104.md` | 🧩 | 语音键→0x68008；GPS→0x18010（25 字段、900ms 节流）；修 protobuf tag≥16 |
| `carlife-voice-siri-20261104.md` | ⚠️ | 11-09 更正：按键确实到了，断点在引擎；请求走 `requestSiri`，65574 判据 |
| `iap2-lyric-lines.md` | ⚠️ | iAP2 无歌词字段，逐行顶替 title；实车日志显示从没送到 |
| `touch-multitouch.md` | 🧩 | 双指改走 0x68001 复合动作；收尾顺序 POINTER_UP→ACTION_UP |

### 6.4 连接与容错

| 文档 | 状态 | 一句话 / 关键判据 |
|---|---|---|
| `connect-hardening.md` | 🧩 | 失败码 0x02/04/05 终止、身份只看 pk 签名；阶段预算 5min/60s/120s/30s/20s |
| `car-approach-autostart.md` | ✅ | 靠近自动启/收摊；1.2.2 实车：必须拉到前台；90s 抑制 + 10min 窗 |
| `process-freeze-recovery.md` | 🧩 | 心跳 ≥4000ms 判冻结；1.5s 复核 + 3/6/12/24s 四轮重建 |
| `wifi-link-watchdog.md` | 🧩 | 断链时刻附近无 `WIFI:` 行即可排除 wlan0；同步包 ECONNREFUSED 不是断链 |
| `bmw-fork-port-20261108.md` | ✅ | 见优先读（3b 补发已删；奥迪 4580 回退风险已知情接受） |
| `gm-cinemo-4301-deadlock.md` | ⚠️ | 互等死锁：空端点保活 + 晚到 0x4301 热更新；核心假设窗口从未走完 |
| `gm-4300-dual-identifier-20261107.md` | ⚠️ | §6 实测推翻 §2：三档报文体全 ACK 无回应，**别再试第四种**；SSID 兜底另修 |
| `buick-e2lb-availability-silence.md` | ✅ | 0x4300 后零 ACK；催问重传是拆链元凶；视频实测 20fps 封顶 |
| `citroen-r4-sdp-no-service.md` | ✅ | SDP 无 iAP2 → 180ms 失败；1.0.8 修闸门与错误文案 |
| `changes-20261104.md` | 🧩 | 见协议类（改动总账） |

### 6.5 音频 / 视频 / 画面契约

| 文档 | 状态 | 一句话 / 关键判据 |
|---|---|---|
| `audio-call-issues.md` | ✅ | 多轮自我更正：同步包重试、通话后 2.5s 重建音频流、TTS 9241 被整条丢弃 |
| `audio-channel-watchdog.md` | 🧩 | 路径 A 真死即重连；路径 B 静音 20s+3 次退避；TTS 只走 A |
| `wechat-notify-audio-stall-20261109.md` | ✅ | 提示音 0x30003→311ms 0x30002；静音阈值 300→0ms；同步包 298 发 293 拒 |
| `stream-order-audio-first-20261109.md` | ✅ | RECORD 优先（奥迪）/ 音频优先（JYBOX）各有适用范围，按型号记忆 |
| `carplay-mic-uplink-20261108.md` | ⚠️ | 方案两次推翻；最后结论：按本档 `audioInputFormats` 判据，media 档塞端口会打死车机 |
| `carplay-no-projection-conditions.md` | 📐 | 4580 固件五道门（Coding/FEC→USB→激活→StartSession→屏幕接管）；静态逆向 |
| `video-stall-recovery-20261108.md` | ⛔ | 干预部分 1.2.34 全删；仅「最后一帧距今」判据仍有效 |
| `videoconfig-canvas-contract.md` | ⚠️ | 见优先读 |

### 6.6 车厂固件 / 黑屏 / 已废弃线

| 文档 | 状态 | 一句话 / 关键判据 |
|---|---|---|
| `id7-blackscreen-diagnosis.md` | ⚠️ | 首帧 NAL 类型决定存活（纯 P 全断）；两次自我更正 |
| `mib3-carplay-display-contract.md` | 📐 | 4580 固件：窗口写死 1412×654@(128,66)；badFramesThreshold=4；timestamps=false |
| `mib3-firmware-3874.md` | 📐 | 重传 30→取双方较小值；显式 AVC Baseline + level；单指限制是车机侧 |
| `mib3-firmware-c8-4580.md` | 📐 | EA 经 VIWI 桥接；0xEA00 催问一次；pair-setup 有防爆破 |
| `audi-mib3-ea-tunnel-20261104.md` | ⛔ | 1.2.29 后 EA 会话整体移除；0xEA04/0xEA03 顺序错致 115 号包不 ACK |
| `mib3-viwi-control.md` | ⛔ | 同上；车控端点取证，五项推断从未验证 |
| `mib3-control-handover.md` | ⛔ | 同上；安全红线：只在车停稳时试写 |

### 6.7 合规

`LEGAL-NOTES.md`（📐）：见优先读。根 `DISCLAIMER.md` 与应用内首次启动免责声明同源。

---

## 7. 冲突与红线（别绕回去）

1. **出画三元素全等 ≠ 出画** —— `carplay-black-screen` 已实车证伪 `videoconfig-canvas-contract` 的早期推断。
2. **0x4300 报文体不是通用系根因** —— `gm-4300-dual-identifier` §6 推翻 §2；三档全 ACK 无回应。
3. **链路层 ACK ≠ 业务层认账** —— 11-10 更正：0x4E0D 改回「未声明不发」，新协议车机整体严格模式。
4. **语音键不是没发** —— 是采样窗口太短误判；真实通路为事件通道 `requestSiri`。
5. **麦克风上行两次推翻** —— 独立 input 探针作废；media 档塞 dataPort 会打死宝马 G38。
6. **MIB3 EA 三连篇已废弃** —— 固件分析篇里 EA 相关描述按历史处理。
7. **停摆分级干预已移除** —— 看门狗只观测，不再重建编码器/会话。
8. **外部存档勿当现状** —— `connect-protocol-and-models.md` 基于另一项目 1.0.51。

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
- 发版同步 README **六处**：下载行版本/体积 → 本版固定包文件名与链接 → 签名覆盖范围 → 项目简介「当前版本」→ 版本历史新条目 →（`bieke` 另有后缀规则）。固定 tag 直链，**不用 `releases/latest`**。
- 协作约定：**改完只本地提交**；用户说「推送/发版」才推、才建 Release；只动指定分支，未说「合并」不并到 `bieke`。PR 常为 draft 且无 CI，合并后必须本地重编译再发版。

---

## 9. 文档维护约定

- 新诊断文档命名 `主题-YYYYMMDD.md`；文首写明**一句话结论 + 状态（实证/假设/已废弃）**。
- 结论被推翻时**在原文追加更正章节并注明日期**，不要删历史；本指南同步改状态标签。
- 引用文档时链接到具体文件，不复制大段日志；参数/门限写清数值和来源（实测/固件/推断）。
- 本指南随发版核对一次：版本基线、状态标签、未验证清单。
