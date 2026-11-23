# CarPToCarL 开发与排障指南

> **定位**：开发与排障总导航（L1–L5 分层模型、症状→专题路由表、现行协议不变量、专题全索引、构建发版速查）。
> 用户上手与功能总览见 [`../README.md`](../README.md)；逐版历史见 [`../CHANGELOG.md`](../CHANGELOG.md)；未验证清单与活跃风险见 [`PROJECT-STATUS.md`](PROJECT-STATUS.md)。
> 文档存在历史更正时，**以文中最后一次带日期的更正为准**。

---

## 1. 运行时分层与核心事实（先定层，再看日志）

包名：`io.github.shaonialife.carptocar` · Android 12+（`minSdk 31` / `targetSdk 34`）。

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
| L5 应用编排 | `SessionController`、会话状态机、CarLife 劫持 | 前台/权限/重启恢复 |

**四条数据面通道**：视频 H.264（CarLife `8240` → AirPlay 屏幕流，数据口 6000 非阻塞 `DatagramChannel`）、媒体音频（`9240` / `0x3000x`）、TTS 导航（`9241` / `0x4xxxx`）、HID 触控回传（`9340`）。同步口 `6011` 保持阻塞式以保留 ICMP 端口存活信号。

**四条基础技术事实**：
1. **认证单向**：手机验证车机 MFi 证书、车机信任手机自报结果，发送端无需 Apple 签名材料。
2. **必须手动进入 CarLife+「正在连接」**：App 自动拉起引擎只能拿到欢迎页；荣耀虚拟屏分辨率/帧率由手机侧「荣耀车联 → 投屏显示设置」决定，厂商 mix 层可能覆盖编码尺寸。
3. **收到 `0x5703` 立刻入网并广播 `_carplay-ctrl._tcp`**：`0x4301` 实测可晚达 2 分 35 秒，等待 `0x4301` 再广播会与车机互等死锁。
4. **蓝牙 MAC 必须真实**：`0x4300` / `0x4E0E` / SETUP / ctrl TXT 均校验它；荣耀机 `logcat` 加密且 `run-as` 不可用，靠 App 导出 `files/main_debug.log`。

---

## 2. 排障决策表（按症状分枝路由）

| 症状 | 第一刀核查项 | 深入专题 |
|---|---|---|
| 一直连不上 / 等不到 `0x4301` | 按 L1 SDP → L2 热点/mDNS → L3 `0x4300`/`0x5703` → L4 SETUP 定层；区分「链路 ACK、业务静默」 | `carplay-connection-layers-20261109.md`、`connect-recovery.md`、`gm-cinemo-4301-deadlock.md` |
| 连上没画面 / 黑屏 | `7240` 是否监听、CarLife 是否停在「正在连接」、**首帧 NAL 是否 IDR**、`VideoConfig` 是否对齐画布、屏幕接管门与 `rfps` | `carplay-black-screen-20261106.md`、`carplay-no-projection-conditions.md`、`videoconfig-canvas-contract.md` |
| 有画面没声音 | 音频 `type` 100/102 与 `audioType` 选档、`9240`/`9241` 通道、44.1k/48k 采样率对齐、PCM 门禁 | `audio-channel-watchdog.md`、`audio-call-issues.md`、`../CHANGELOG.md`（1.2.70 / 1.2.83） |
| 播放一段时间一顿一顿 | 看「最长停拍」归因（park / 混音 / 发包）；确认非阻塞发包与采样率对齐状态 | `../CHANGELOG.md`（1.2.70） |
| 只有 20fps / 画面拉伸 | 手机端车联设置帧率 60、分辨率保持「默认」，改后断开重连 | `../README.md` 第三节 |
| 触摸失灵 / 拖不动 / 卡死 | `0x68001`–`0x68008` 命令 ID、以 SPS 真实分辨率为基准、缺 UP 补发、双指复合动作 | `../README.md` 第五节、`touch-multitouch.md` |
| 连上一会自己断 / 反复重连 | 空闲 IDR 保活与单锁写 socket；核对对端字节数排查双重封包（历史：`0x5001`） | `../README.md` 第五节、`connect-hardening.md` |
| 车机没歌名歌词 | 通知使用权是否已 rebound、`98386 MEDIA_LYRIC_SUPPORT=1` | `../README.md` 第五节、`iap2-lyric-lines.md` |
| 方向盘语音键没反应 | 走 AirPlay 事件通道 `requestSiri`（`siriAction` 1/2/3）；检查引擎 `65574` 状态 | `carlife-voice-siri-20261104.md` |
| 后台冻结 / 掉网 | 心跳间隔 ≥4000ms 触发冻结重建；断链附近无 `WIFI:` 行即可排除 wlan0 | `process-freeze-recovery.md`、`wifi-link-watchdog.md` |

---

## 3. 现行协议与工程不变量（基于实车证伪收敛）

1. **出画验收以屏幕接管门（12s）与 `rfps > 0` 为准**：`VideoConfig` 声明对齐车机画布，SPS 仅作诊断；三元素相等只是前置条件，不能替代出画确认（见 `carplay-black-screen-20261106.md`）。
2. **业务发送以车机声明清单为准，链路 ACK 只代表传输送达**：新协议车机按 `0x1D01` 可收清单严格裁剪（如 `0x4E0D`、`0x52xx`）；通用系三档 `0x4300` 全 ACK 无回应说明根因在其它环节，见 `gm-4300-dual-identifier-20261107.md` §6。
3. **麦克风上行端口只挂到本档位声明了非零 `audioInputFormats` 的流**：`media` 档若声明 `audioInputFormats=0x0` 严禁塞入本地端口（宝马 G38 实测会导致关通道/整机重启）。
4. **方向盘语音键走事件通道 `requestSiri`**：HID 侧无对应事件，触发与引擎侧响应见 `carlife-voice-siri-20261104.md`。
5. **停摆看门狗只做观测与记录**：分级重建干预已于 1.2.34 移除（见 `archive/video-stall-recovery-20261108.md`）。
6. **已移除与已回滚功能口径**：
   - 有线 USB、Shizuku、MIB3 车控（EA/VIWI/控制交接）、自动拉起 CarLife 均已移除或排除。
   - `1.2.64`/`1.2.65` 实验补丁已在 `1.2.66`–`1.2.68` 整批回滚，仅「非阻塞发包」与「采样率对齐」于 `1.2.70` 重新落地（查阅 `../CHANGELOG.md` 时按此口径）。
   - `archive/connect-protocol-and-models.md` 为外部存档，仅作对照参考。
7. **合规边界**：源码 MIT（不含第三方协议/商标授权），仅接受赞助或技术支持；仓库严禁反编译产物、厂商 SDK、抓包二进制、私钥与真实车机名，提交前运行 `scripts/check-no-vendor-artifacts.sh`。

---

## 4. 专题文档全索引

> 状态图例：✅ 实车验证 · 🧩 已实现 · 📐 规范/调研 · 🗄 外部存档 · ⛔ 已废弃 · ⚠️ 含更正或待验证。

### 4.1 架构与协议总纲

| 文档 | 状态 | 关键判据 / 主题 |
|---|---|---|
| `carplay-connection-layers-20261109.md` | 🧩 | L1–L5 全局架构 + 症状→层判据；`0x4301` 可晚 2′35″ |
| `PROTOCOL-MODULES.md` | 🧩 | 协议聚合层（`ProtocolRegistry` / 双门面 / `VideoSink` & `AudioSink` 契约 / 接第三种协议五步） |
| `PROTOCOL-NOTES.md` | ✅ | 发送端协议全集；以 §9c-1/2/3 最新修正为准（modes 状态机、`0x4300` 等确认、严格声明模式） |
| `CARPLAY-WIRELESS-HEADUNIT-SPEC.md` | 📐 | 接收端规范（✅逐字节确证 / ⚠️有支持 / ❓待抓包） |
| `carplay-manual-crosscheck-20261107.md` | ⚠️ | 手册对照：采纳初始序号随机，不改 CRC-16 |
| `wireless-diplay-comparison.md` | 📐 | DiPlay 对照（缺 Wi-Fi Direct；`0x5200` 行为） |
| `ocarplay-crosscheck-20261108.md` | 📐 | 接收端对照：`FreshAuthPolicy`、coded/visible/declared 三值诊断、SPS 66/77/88 |
| `cadillac-ct5-carplay-apk-analysis.md` | 📐 | CT5 反编译：三档报文体、双向+方向回退、按声明裁剪 |
| `cardeck-0.1.28-port.md` | ⚠️ | 四项移植与 11-10 更正（`0x4E0D` 未声明不发） |
| `协议核完全逆向-20261122.md` | 📐 | 协议核逆向分析与 main 交叉佐证（含 MFi 证书 OID 识别） |
| `协议核解包分析-20261122.md` | 📐 | 协议核解包结构记录 |
| `WIRED-CARPLAY-FEASIBILITY.md` | 📐 | 有线路线否决（四道 Android 门，纯软件无解） |
| `changes-20261104.md` | 🧩 | 1.2.18→1.2.20 改动总账 |
| [`archive/connect-protocol-and-models.md`](archive/connect-protocol-and-models.md) | 🗄 | 外部存档（另一项目 1.0.51，9 台奥迪机型，非现状） |

### 4.2 CarLife 劫持 / 输入 / 媒体

| 文档 | 状态 | 关键判据 / 主题 |
|---|---|---|
| `CARLIFE-GATEWAY-FEASIBILITY.md` | 📐 | 网关可行性；7 端口、8 阶段握手 |
| `carlife-wireless-verification.md` | 📐 | 单机回环实验记录 |
| `carlife-hijack-findings.md` | ✅ | 以第七节为准：协议版本必须发 **3.0** |
| `carlife-hijack-integration.md` | 🧩 | 劫持架构与开关；`7240` 探测与接入契约 |
| `carlife-notice-frame.md` | 🧩 | 未劫持提示帧；首帧判据用 `framesDelivered` 差值 |
| `carlife-voice-gps-20261104.md` | 🧩 | 语音键与 GPS `0x18010`（25 字段、900ms 节流、tag≥16） |
| `carlife-voice-siri-20261104.md` | ⚠️ | 11-09 更正：走事件通道 `requestSiri` 与引擎 `65574` 判据 |
| `iap2-lyric-lines.md` | ⚠️ | 歌词逐行顶替 title 方案与实车送达核查 |
| `touch-multitouch.md` | 🧩 | 双指 `0x68001` 复合动作与 `POINTER_UP→ACTION_UP` 顺序 |

### 4.3 连接与容错

| 文档 | 状态 | 关键判据 / 主题 |
|---|---|---|
| `connect-recovery.md` | 🧩 | 入网互斥、3s/10s/30s 退避、ctrl 即时响应、半握手定性 |
| `connect-hardening.md` | 🧩 | 失败码 `0x02`/`04`/`05` 终止、身份只看 pk 签名、阶段预算 |
| `car-approach-autostart.md` | ✅ | 靠近自动启/收摊（需前台拉起、90s 抑制 + 10min 窗） |
| `process-freeze-recovery.md` | 🧩 | 心跳 ≥4000ms 判冻结；1.5s 复核 + 3/6/12/24s 重建 |
| `wifi-link-watchdog.md` | 🧩 | 无 `WIFI:` 行即排除 wlan0；同步包 `ECONNREFUSED` 非断链 |
| `bmw-fork-port-20261108.md` | ✅ | SDP 常驻、1.5s 音频守卫、首帧不补 RECORD |
| `gm-cinemo-4301-deadlock.md` | ⚠️ | 互等死锁：空端点保活 + 晚到 `0x4301` 热更新 |
| `gm-4300-dual-identifier-20261107.md` | ⚠️ | §6 推翻 §2：三档 `0x4300` 全 ACK 无回应 |
| `buick-e2lb-availability-silence.md` | ✅ | `0x4300` 后零 ACK、催问重传拆链根因、20fps 封顶 |
| `citroen-r4-sdp-no-service.md` | ✅ | SDP 无 iAP2 → 180ms 失败与闸门修复 |

### 4.4 音频 / 视频 / 画面契约

| 文档 | 状态 | 关键判据 / 主题 |
|---|---|---|
| `carplay-black-screen-20261106.md` | ✅ | 屏幕接管门 12s / `rfps` 探针 |
| `videoconfig-canvas-contract.md` | ⚠️ | 默认 `raw` 声明；视口仅头部元数据 |
| `carplay-no-projection-conditions.md` | 📐 | 4580 固件五道门静态逆向 |
| `audio-call-issues.md` | ✅ | 同步包重试、通话后 2.5s 重建音频流、TTS `9241` 排查 |
| `audio-channel-watchdog.md` | 🧩 | 路径 A 真死重连；路径 B 静音 20s + 3 次退避；TTS 只走 A |
| `wechat-notify-audio-stall-20261109.md` | ✅ | 提示音 `0x30003→0x30002` 空档、静音阈值 0ms |
| `stream-order-audio-first-20261109.md` | ✅ | RECORD 优先 vs 音频优先按车型记忆 |
| `carplay-mic-uplink-20261108.md` | ⚠️ | 按本档 `audioInputFormats` 门控麦克风端口 |
| [`archive/video-stall-recovery-20261108.md`](archive/video-stall-recovery-20261108.md) | ⛔ | 分级干预 1.2.34 全删，仅保留「最后一帧距今」观测判据 |

### 4.5 车厂固件 / 已废弃线 / 合规

| 文档 | 状态 | 关键判据 / 主题 |
|---|---|---|
| `id7-blackscreen-diagnosis.md` | ⚠️ | 首帧 NAL 类型决定存活（纯 P 全断） |
| `mib3-carplay-display-contract.md` | 📐 | 4580 窗口 `1412×654@(128,66)`、`badFramesThreshold=4` |
| `mib3-firmware-3874.md` | 📐 | 重传窗口取双方较小值、AVC Baseline + level |
| `mib3-firmware-c8-4580.md` | 📐 | 4580 固件机制与 pair-setup 防爆破（EA 描述按历史处理） |
| [`archive/audi-mib3-ea-tunnel-20261104.md`](archive/audi-mib3-ea-tunnel-20261104.md) | ⛔ | 1.2.29 后 EA 会话整体移除 |
| [`archive/mib3-viwi-control.md`](archive/mib3-viwi-control.md) | ⛔ | 同上 |
| [`archive/mib3-control-handover.md`](archive/mib3-control-handover.md) | ⛔ | 同上 |
| `LEGAL-NOTES.md` | 📐 | 商业化三件套、开源/赞助边界与合规守卫 |

---

## 5. 构建与发版速查

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug     # 日常验证（固定 JDK 17）
./gradlew :app:assembleRelease :app:testReleaseUnitTest # 发版前必跑（不要与 clean 同命令）
bash scripts/check-no-vendor-artifacts.sh               # 提交前脱敏与合规守卫
adb install -r <apk>                                    # 同签名覆盖装，保留本地配置与记忆
```

- **格式化**：只保持本次修改文件符合 `.editorconfig` / ktlint，不要执行全局 `spotlessApply`。
- **发版同步**：更新 `app/build.gradle.kts` `appVersionName`（禁连续 `44`）→ `CHANGELOG.md` 顶部追加完整版本条目 → `README.md` 更新下载直链（固定 tag，不用 `releases/latest`）、当前版本行与第十一节最新摘要。
