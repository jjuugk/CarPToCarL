# docs/ —— 项目文档目录

> 42 篇现行专题文档 + 5 篇归档 + 3 篇元文档。**先看元文档，再按角色找路径**，不要从文件名猜。

## 元文档（先读这三个）

| 文档 | 用途 | 什么时候读 |
|---|---|---|
| [`PROJECT-GUIDE.md`](PROJECT-GUIDE.md) | **导航**：47 篇文档逐篇状态标签 + 排障决策表 + 冲突红线 | 想知道「某症状先查哪篇」「这篇还能不能信」 |
| [`PROJECT-STATUS.md`](PROJECT-STATUS.md) | **体检报告**：版本/代码/能力/风险/未验证清单/分支状态 | 想知道「项目现在什么状态、哪些还是推断」 |
| [`archive/README.md`](archive/README.md) | **归档说明**：5 篇为什么失效 | 看到旧链接或旧结论时 |

## 按角色的阅读路径

**新人接手（约 40 分钟）**
1. [`../README.md`](../README.md) 第一~四节 —— 项目是什么、怎么工作、能做什么
2. [`PROJECT-STATUS.md`](PROJECT-STATUS.md) —— 现在处于什么状态、哪些不能信
3. [`carplay-connection-layers-20261109.md`](carplay-connection-layers-20261109.md) —— L1–L5 全局架构与「症状→层」判据
4. [`PROTOCOL-NOTES.md`](PROTOCOL-NOTES.md) §9c-1/2/3 —— 发送端协议的最新修正

**排障（先定层，再看日志）**

| 症状 | 先读 |
|---|---|
| 一直连不上 / 等不到 `0x4301` | [`carplay-connection-layers-20261109.md`](carplay-connection-layers-20261109.md)、[`connect-recovery.md`](connect-recovery.md) |
| 连上没画面 / 黑屏 | [`carplay-black-screen-20261106.md`](carplay-black-screen-20261106.md)、[`videoconfig-canvas-contract.md`](videoconfig-canvas-contract.md) |
| 有画面没声音 / 一顿一顿 | [`audio-channel-watchdog.md`](audio-channel-watchdog.md)、[`audio-call-issues.md`](audio-call-issues.md) |
| 触摸失灵 | [`touch-multitouch.md`](touch-multitouch.md) |
| 自己断开 / 车机反复重连 | [`connect-hardening.md`](connect-hardening.md)、[`process-freeze-recovery.md`](process-freeze-recovery.md)、[`wifi-link-watchdog.md`](wifi-link-watchdog.md) |
| 语音键没反应 | [`carlife-voice-siri-20261104.md`](carlife-voice-siri-20261104.md) |

完整决策表见 [`PROJECT-GUIDE.md`](PROJECT-GUIDE.md) §4。

**改代码前**：[`PROJECT-STATUS.md`](PROJECT-STATUS.md) §5（结构与重构边界）、§8（未验证清单）、§9（协作约定）。

**发版 / 对外**：[`LEGAL-NOTES.md`](LEGAL-NOTES.md)、[`PROJECT-GUIDE.md`](PROJECT-GUIDE.md) §8。

## 命名与写作约定

- 诊断类：`主题-YYYYMMDD.md`（如 `carplay-black-screen-20261106.md`）；功能/规范类用全大写或主题名。
- 文首必须写**一句话结论 + 状态**（实证 / 假设 / 已废弃）。
- 结论被推翻时**在原文追加「更正」章节并注明日期**，不要删历史；同时更新 `PROJECT-GUIDE.md` 的状态标签。
- 参数与门限写清数值和来源（实测 / 固件 / 推断），不要只写「较大」「约」。
- 不复制大段日志，链接到具体文件与行号。

## 状态图例

✅ 实车验证 · 🧩 已实现但未逐项实车 · 📐 规范 / 调研 · 🗄 外部存档（勿当现状） · ⛔ 已废弃（仅历史） · ⚠️ 含自我更正或待验证

## 归档规则

满足任一条即移入 [`archive/`](archive/)：功能已被整体移除、结论已被后续实车推翻且无复用价值、来自其它项目不能代表本仓库现状。
归档时同步改三处：`PROJECT-GUIDE.md` 的索引与状态、根 `README.md` 的链接、`archive/README.md` 的原因表。
