# docs/ —— 项目文档目录

> **只有两类文档**：[`current/`](current/) 是**现行规范 / 架构（唯一真源）**，
> [`archive/`](archive/README.md) 是**历史排障归档（只作取证）**。
> 判断当前代码行为一律以 `current/` 与源码为准；归档里的旧结论**不得**直接照抄到新修改里。
>
> 阅读顺序：本页 → [`current/PROJECT-GUIDE.md`](current/PROJECT-GUIDE.md)（导航与冲突红线）
> → [`current/PROJECT-STATUS.md`](current/PROJECT-STATUS.md)（体检报告）。

## 分区速览

| 目录 | 内容 | 怎么用 |
| --- | --- | --- |
| [`current/`](current/) | 14 篇现行规范 / 架构 / 体检文档 | **改代码、定方案前必读**；与源码冲突时以源码为准并回报文档缺陷 |
| [`archive/`](archive/README.md) | 40 篇历史排障、调研对照、已失效与外部存档（+ 本索引） | 只在查旧日志、旧链接、结论来源时取证；**不是现状** |
| 本页 | 目录地图 | 按角色找路径 |

## 元文档（先读这三个）

| 文档 | 用途 | 什么时候读 |
|---|---|---|
| [`current/PROJECT-GUIDE.md`](current/PROJECT-GUIDE.md) | **导航**：现行真源索引 + 排障决策表 + 冲突红线 + 构建发版速查 | 想知道「某症状先看哪篇」「这条旧结论还成不成立」 |
| [`current/PROJECT-STATUS.md`](current/PROJECT-STATUS.md) | **体检报告**：版本 / 代码 / 能力 / 风险 / 未验证清单 / 分支状态 | 想知道「项目现在什么状态、哪些还是推断」 |
| [`archive/README.md`](archive/README.md) | **归档说明**：40 篇按组列出原因与仍有效的部分 | 看到旧链接或过时结论时 |

## 现行真源（`docs/current/`，14 篇 md）

| 文档 | 用途 |
|---|---|
| [`PROJECT-GUIDE.md`](current/PROJECT-GUIDE.md) | 导航、状态标签、排障决策、冲突红线、发版速查 |
| [`PROJECT-STATUS.md`](current/PROJECT-STATUS.md) | 项目体检报告 |
| [`PROTOCOL-NOTES.md`](current/PROTOCOL-NOTES.md) | 发送端协议规范全集（以 §9c-1/2/3 最新修正为准） |
| [`PROTOCOL-MODULES.md`](current/PROTOCOL-MODULES.md) | 协议聚合层架构：能力路由、数据面契约、接第三种协议的五步 |
| [`CARPLAY-WIRELESS-HEADUNIT-SPEC.md`](current/CARPLAY-WIRELESS-HEADUNIT-SPEC.md) | 接收端（车机）视角的无线 CarPlay 规范与差异点对照 |
| [`协议核完全逆向-20261122.md`](current/协议核完全逆向-20261122.md) · [`协议核解包分析-20261122.md`](current/协议核解包分析-20261122.md) | v2 协议核链路与静态 JNI 绑定的现行结论 |
| [`carplay-connection-layers-20261109.md`](current/carplay-connection-layers-20261109.md) | L1–L5 全局架构与「症状 → 层」判据 |
| [`audio-channel-watchdog.md`](current/audio-channel-watchdog.md) | 音频通道看门狗的现行三重设限与重连边界 |
| [`videoconfig-canvas-contract.md`](current/videoconfig-canvas-contract.md) | VideoConfig 声明与车机画布的契约（视口只是头部元数据） |
| [`touch-multitouch.md`](current/touch-multitouch.md) | 触摸 / 双指手势的命令通道与坐标约定 |
| [`carlife-hijack-integration.md`](current/carlife-hijack-integration.md) | CarLife 劫持架构、开关与回退 |
| [`WIRED-CARPLAY-FEASIBILITY.md`](current/WIRED-CARPLAY-FEASIBILITY.md) | 有线路线否决结论（现行约束：只走无线） |
| [`LEGAL-NOTES.md`](current/LEGAL-NOTES.md) | 合规边界、收费风险与商业化三件套 |

> 未跟踪的架构图 `协议模块拆分_分层架构图.html` 与根目录的 `CarPlay链接方式对比-20261122.md`、
> `新项目方案-CarLife替代cardeck自渲染-20261122.md` 是补充材料，**不是**唯一真源；正文结论冲突时以
> `current/` + 源码为准。

## 历史归档（`docs/archive/`，40 篇 + 本索引）

分组与逐篇原因见 [`archive/README.md`](archive/README.md)。大致三类：

1. **历史排障 / 复盘**（带日期的诊断、含自我更正）：如 `carplay-black-screen-20261106.md`、
   `carlife-voice-siri-20261104.md`、`audio-call-issues.md`、`connect-recovery.md` 等。
2. **调研 / 可行性 / 外部对照**：如 `CARLIFE-GATEWAY-FEASIBILITY.md`、`wireless-diplay-comparison.md`、
   `cadillac-ct5-carplay-apk-analysis.md`、`archive/connect-protocol-and-models.md` 等。
3. **已失效功能**：MIB3 EA / VIWI / 车控三连篇、画面停摆分级干预等。

## 按角色的阅读路径

**新人接手**
1. 根 [`README.md`](../README.md)（简介 / 快速上手 / 兼容性 / 文档索引）
2. [`current/PROJECT-STATUS.md`](current/PROJECT-STATUS.md) —— 现在处于什么状态、哪些不能信
3. [`current/carplay-connection-layers-20261109.md`](current/carplay-connection-layers-20261109.md) —— L1–L5 架构
4. [`current/PROTOCOL-NOTES.md`](current/PROTOCOL-NOTES.md) §9c-1/2/3 —— 协议最新修正

**排障（先定层，再看日志）** —— 现行判据在 [`current/PROJECT-GUIDE.md`](current/PROJECT-GUIDE.md) §4；
归档文档只用于还原「当时为什么这么判」，旧结论须先在 §7 冲突红线里核一遍。

| 症状 | 现行判据 / 取证 |
|---|---|
| 一直连不上 / 等不到 `0x4301` | [`current/carplay-connection-layers-20261109.md`](current/carplay-connection-layers-20261109.md)、[`archive/connect-recovery.md`](archive/connect-recovery.md) |
| 连上没画面 / 黑屏 | [`current/videoconfig-canvas-contract.md`](current/videoconfig-canvas-contract.md)、[`archive/carplay-black-screen-20261106.md`](archive/carplay-black-screen-20261106.md) |
| 有画面没声音 / 一顿一顿 | [`current/audio-channel-watchdog.md`](current/audio-channel-watchdog.md)、[`archive/audio-call-issues.md`](archive/audio-call-issues.md) |
| 触摸失灵 | [`current/touch-multitouch.md`](current/touch-multitouch.md) |
| 自己断开 / 反复重连 / 冻结 | [`current/PROJECT-GUIDE.md`](current/PROJECT-GUIDE.md) §4、[`archive/process-freeze-recovery.md`](archive/process-freeze-recovery.md)、[`archive/wifi-link-watchdog.md`](archive/wifi-link-watchdog.md) |
| 语音键没反应 | [`archive/carlife-voice-siri-20261104.md`](archive/carlife-voice-siri-20261104.md)（以其中更正章节为准） |

**改代码前**：[`current/PROTOCOL-MODULES.md`](current/PROTOCOL-MODULES.md)、
[`current/PROJECT-STATUS.md`](current/PROJECT-STATUS.md) §5（重构边界）、§8（未验证清单）。

**发版 / 对外**：[`current/LEGAL-NOTES.md`](current/LEGAL-NOTES.md)、
[`current/PROJECT-GUIDE.md`](current/PROJECT-GUIDE.md) §8、根 [`CHANGELOG.md`](../CHANGELOG.md)。

## 命名与写作约定

- 诊断 / 复盘类：`主题-YYYYMMDD.md`，写完即进 [`archive/`](archive/README.md)；
  只有定义了**现行规范 / 架构**的文档才进 [`current/`](current/)。
- 文首必须写**一句话结论 + 状态**（实证 / 假设 / 已废弃）。
- 结论被推翻时**在原文追加「更正」章节并注明日期**，不要删历史；同时更新
  [`current/PROJECT-GUIDE.md`](current/PROJECT-GUIDE.md) 的状态标签与 §7 冲突红线。
- 参数与门限写清数值和来源（实测 / 固件 / 推断），不要只写「较大」「约」。
- 不复制大段日志，链接到具体文件与行号。

## 状态图例

✅ 实车验证 · 🧩 已实现但未逐项实车 · 📐 规范 / 调研 · 🗄 外部存档（勿当现状） · ⛔ 已废弃（仅历史） · ⚠️ 含自我更正或待验证

## 归档规则

满足任一条即 `git mv` 到 [`archive/`](archive/README.md)：功能已被整体移除、结论已被后续实车推翻且无复用价值、
来自其它项目不能代表本仓库现状、**阶段性排障记录已完成使命**。归档时同步改三处：
[`current/PROJECT-GUIDE.md`](current/PROJECT-GUIDE.md) 索引与状态、根 [`README.md`](../README.md) 链接、
[`archive/README.md`](archive/README.md) 原因表。
