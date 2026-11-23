# docs/archive —— 历史排障归档

> **这里的文件只作历史取证，不要当作现状引用。**
> 判断现行结论请走 [`../current/PROJECT-GUIDE.md`](../current/PROJECT-GUIDE.md)（状态标签 / 冲突红线）与
> [`../current/PROJECT-STATUS.md`](../current/PROJECT-STATUS.md)（体检报告）；
> 目录地图见 [`../README.md`](../README.md)。
>
> 共 39 篇归档文档 + 本索引。2026-11-19 首批 5 篇，2026-11-23 按「现行真源 / 历史归档」分区再移入 33 篇，
> 另新增 1 篇未实施方案提案。
> 移动一律用 `git mv`，历史与 blame 完整保留。

## A. 已失效功能 / 外部存档（5 篇）

| 文档 | 归档原因 | 仍然有效的部分 |
|---|---|---|
| [`audi-mib3-ea-tunnel-20261104.md`](audi-mib3-ea-tunnel-20261104.md) | 1.2.29 后 EA 会话（`0xEA03`/`0xEA04`、mib3 包）**整体移除**，代码已不存在 | 「`0xEA04` 早于 `0xEA03` 写入会致 115 号包不再 ACK」作为协议教训仍成立 |
| [`mib3-viwi-control.md`](mib3-viwi-control.md) | 同上，VIWI 车控端点代码已删；五项推断从未验证 | 无（车控红线见根 `DISCLAIMER.md`） |
| [`mib3-control-handover.md`](mib3-control-handover.md) | 同上，控制交接代码已删 | 「只在车停稳时试写」的安全原则仍适用 |
| [`video-stall-recovery-20261108.md`](video-stall-recovery-20261108.md) | 1.2.34 后**分级干预全删**（看门狗退回只观测） | 「最后一帧距今」判据仍有效，已并入 `PROJECT-GUIDE.md` §4 |
| [`connect-protocol-and-models.md`](connect-protocol-and-models.md) | **外部存档**：来源是另一项目 AuDiCar 1.0.51，9 台奥迪机型；文档自述「不要当作现状引用」 | 车型参数与端口时序**不可**直接套用到本仓库 |

## B. 历史排障 / 复盘（22 篇）

> 这些文档记录的是**当时的现场与推断**，多数含「更正」章节或已被后续版本推翻。
> 引用前先读文首结论与更正，再到 [`PROJECT-GUIDE.md` §7](../current/PROJECT-GUIDE.md) 核冲突红线。

| 文档 | 归档原因 | 仍然有效的部分 |
|---|---|---|
| [`audio-call-issues.md`](audio-call-issues.md) | 多轮自我更正的通话 / 音频排障合集，结论已收拢进 `current/audio-channel-watchdog.md` | 同步包重试、通话后重建音频流的判据 |
| [`bmw-fork-port-20261108.md`](bmw-fork-port-20261108.md) | 一次性 fork 移植记录；后续 fork 又多次改动 | SDP 常驻、首帧不补 RECORD 的历史依据 |
| [`buick-e2lb-availability-silence.md`](buick-e2lb-availability-silence.md) | 别克 E2LB 现场复盘，阶段性结论 | 催问重传是拆链元凶的现场证据 |
| [`car-approach-autostart.md`](car-approach-autostart.md) | 靠近自动启的历史验证记录 | 必须拉到前台、90s 抑制窗口 |
| [`carlife-hijack-findings.md`](carlife-hijack-findings.md) | 劫持立项期的实验记录 | 协议版本必须发 3.0 |
| [`carlife-notice-frame.md`](carlife-notice-frame.md) | 提示帧方案已并入主链路，独立记录完成使命 | 首帧判据改用 `framesDelivered` 差值 |
| [`carlife-voice-gps-20261104.md`](carlife-voice-gps-20261104.md) | 语音键早期通路（0x68008）已被 `requestSiri` 取代 | GPS 转发 25 字段 / 900ms 节流 |
| [`carlife-voice-siri-20261104.md`](carlife-voice-siri-20261104.md) | 语音键排障含多次更正；现行判据见 `PROJECT-GUIDE.md` §4 | `siriAction` 1/2/3、65574 模块状态 |
| [`carplay-black-screen-20261106.md`](carplay-black-screen-20261106.md) | 黑屏根因已收拢，原文保留证伪过程 | 证伪「声明 = SPS = 窗口即出画」 |
| [`carplay-mic-uplink-20261108.md`](carplay-mic-uplink-20261108.md) | 方案两次推翻，不宜当现行设计读 | media 档塞 dataPort 会打死宝马 G38 |
| [`carplay-no-projection-conditions.md`](carplay-no-projection-conditions.md) | 4580 固件静态逆向，不是本仓库行为 | 五道门的现场判读方法 |
| [`citroen-r4-sdp-no-service.md`](citroen-r4-sdp-no-service.md) | 单台车的一次性 SDP 排障 | SDP 无 iAP2 → 快速失败 |
| [`connect-hardening.md`](connect-hardening.md) | 阶段预算等参数后续已多次调整 | 失败码 0x02/04/05 的语义 |
| [`connect-recovery.md`](connect-recovery.md) | 退避 / 入网互斥已有新实现（`ReconnectGate`） | 半握手双条件定性 |
| [`gm-4300-dual-identifier-20261107.md`](gm-4300-dual-identifier-20261107.md) | §6 已推翻 §2，禁止再试第四种报文体 | 三档全 ACK 无回应的现场结论 |
| [`gm-cinemo-4301-deadlock.md`](gm-cinemo-4301-deadlock.md) | 互等死锁的核心假设窗口从未走完 | 空端点保活 + 晚到 0x4301 热更新 |
| [`iap2-lyric-lines.md`](iap2-lyric-lines.md) | 歌词逐行顶替 title 实车从未送到 | iAP2 没有歌词字段这一事实 |
| [`id7-blackscreen-diagnosis.md`](id7-blackscreen-diagnosis.md) | 含两次自我更正的阶段性诊断 | 首帧 NAL 类型决定存活 |
| [`process-freeze-recovery.md`](process-freeze-recovery.md) | 冻结重建退避已被统一收口 | ≥4000ms 心跳判冻结 |
| [`stream-order-audio-first-20261109.md`](stream-order-audio-first-20261109.md) | 按型号记忆的流序方案，后续由 `RecordBudget` / `StreamOrderMemory` 实现 | 奥迪需重复 RECORD、JYBOX 音频优先 |
| [`wechat-notify-audio-stall-20261109.md`](wechat-notify-audio-stall-20261109.md) | 单次提示音卡死的现场复盘 | 静音阈值 300→0ms 的判据 |
| [`wifi-link-watchdog.md`](wifi-link-watchdog.md) | 入网实现已改，旧判据只用于取证 | 断链时刻附近无 `WIFI:` 行即可排除 wlan0 |

## C. 调研 / 可行性 / 对照 / 变更总账（12 篇）

| 文档 | 归档原因 | 仍然有效的部分 |
|---|---|---|
| [`CARLIFE-GATEWAY-FEASIBILITY.md`](CARLIFE-GATEWAY-FEASIBILITY.md) | 网关方案立项调研，未成为现行架构 | 7 端口 / 8 阶段握手清单 |
| [`cadillac-ct5-carplay-apk-analysis.md`](cadillac-ct5-carplay-apk-analysis.md) | 外部 APK 静态分析，不能代表本仓库行为 | 三档报文体与按声明裁剪思路 |
| [`cardeck-0.1.28-port.md`](cardeck-0.1.28-port.md) | 一次性移植记录，11-10 已有更正 | `0x4E0D` 未声明不发、ACK ≠ 业务认账 |
| [`carlife-wireless-verification.md`](carlife-wireless-verification.md) | 单机回环实验，立项判定四项均未勾选 | 回环实验方法 |
| [`carplay-manual-crosscheck-20261107.md`](carplay-manual-crosscheck-20261107.md) | 手册对照只采纳 1 项，其余不采纳 | CRC-16 不改的结论 |
| [`changes-20261104.md`](changes-20261104.md) | 1.2.18→1.2.20 改动总账，逐版详情已进根 `CHANGELOG.md` | 编码尺寸五补丁、4580 重连三改 |
| [`mib3-carplay-display-contract.md`](mib3-carplay-display-contract.md) | 4580 固件静态契约，非本仓库现行行为 | 窗口写死 1412×654@(128,66) |
| [`mib3-firmware-3874.md`](mib3-firmware-3874.md) | 固件版本分析，阶段性 | 重传取双方较小值 |
| [`mib3-firmware-c8-4580.md`](mib3-firmware-c8-4580.md) | 同上 | EA 经 VIWI 桥接的取证 |
| [`ocarplay-crosscheck-20261108.md`](ocarplay-crosscheck-20261108.md) | 接收端对照调研，不是本仓库实现 | coded / visible / declared 三值诊断 |
| [`wireless-diplay-comparison.md`](wireless-diplay-comparison.md) | 测试台对照，非车机实现 | `0x5200` 只 ACK，不发导航箭头 |
| [`amap-navi-sdk-source-plan-20261123.md`](amap-navi-sdk-source-plan-20261123.md) | **未实施方案提案**：官方导航 SDK `NaviInfo` 作为第二数据源的字段映射与接入点；未引入 AAR/key | `IconType` 需独立映射表；公开分发需高德技术服务许可 |

## 归档规则

满足任一条即移入本目录，并同步三处（[`current/PROJECT-GUIDE.md`](../current/PROJECT-GUIDE.md) 索引与状态、
根 [`README.md`](../README.md) 链接、本原因表）：

1. 对应功能已被整体移除；
2. 结论已被后续实车推翻，且没有可复用的判据；
3. 来自其它项目，不能代表本仓库现状；
4. 阶段性排障 / 调研已完成使命，现行结论已收拢到 `docs/current/`。
