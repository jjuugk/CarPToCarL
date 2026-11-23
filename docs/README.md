# docs/ —— 项目知识库入口

> **先看下表按场景跳转**，不要从文件名猜测结论是否仍有效（本仓库多篇文档在后续实车中被更正，状态标签与文档末尾「更正」节优先于早期段落）。

## 入口地图（单一事实源分工）

| 文档 | 唯一职责 | 何时打开 |
|---|---|---|
| [`../README.md`](../README.md) | 用户快速上手、权限、常见使用排障、构建与合规 | 装包、配置流程、查看对外说明 |
| [`../CHANGELOG.md`](../CHANGELOG.md) | 全部历史版本逐条变更与回滚口径 | 查某版具体改了什么、确认 1.2.64/65 回滚项 |
| [`PROJECT-GUIDE.md`](PROJECT-GUIDE.md) | **排障决策表 + 协议不变量 + 专题索引 + 发版速查** | 查任何症状对应哪篇文档、核对现行结论与红线 |
| [`PROJECT-STATUS.md`](PROJECT-STATUS.md) | 未实车验证清单与当前高优先级风险 | 确认哪些修复仅有日志/单测证据、当前头号阻塞项 |
| [`PROTOCOL-MODULES.md`](PROTOCOL-MODULES.md) | 协议聚合层、数据面契约与接入第三种协议五步 | 改动 `protocol/`、`VideoSink`/`AudioSink` 或接新协议前 |
| [`archive/README.md`](archive/README.md) | 归档文档清单与失效原因 | 遇到旧链接或 MIB3 EA / 画面停摆干预等已移除特性 |

## 按角色的阅读路径

- **新人接手**：[`../README.md`](../README.md) 第一~四节 → [`PROJECT-GUIDE.md`](PROJECT-GUIDE.md) §1/§3 → [`carplay-connection-layers-20261109.md`](carplay-connection-layers-20261109.md) → [`PROTOCOL-NOTES.md`](PROTOCOL-NOTES.md) §9c-1/2/3。
- **现场排障**：直接查 [`PROJECT-GUIDE.md`](PROJECT-GUIDE.md) §2 排障决策表（先定层，再读对应专题）。
- **改代码 / 提 PR**：[`../AGENTS.md`](../AGENTS.md) → [`PROTOCOL-MODULES.md`](PROTOCOL-MODULES.md) → [`PROJECT-STATUS.md`](PROJECT-STATUS.md)。
- **发版 / 合规**：[`LEGAL-NOTES.md`](LEGAL-NOTES.md) → [`PROJECT-GUIDE.md`](PROJECT-GUIDE.md) §5。

## 维护与归档规则

- **命名**：诊断类用 `主题-YYYYMMDD.md`（如 `carplay-black-screen-20261106.md`）；规范/长期类用主题名或全大写。
- **头部与更正**：文首写明一句话结论与状态标签；结论被推翻时在原文追加带日期的「更正」章节（保留历史脉络），并同步更新 [`PROJECT-GUIDE.md`](PROJECT-GUIDE.md) 的状态标签。
- **证据口径**：参数与门限写清数值和来源（实测 / 固件 / 推断）；引用代码与日志路径，不贴大段原始日志。
- **状态图例**：✅ 实车验证 · 🧩 已实现未逐项实车 · 📐 规范 / 调研 · 🗄 外部存档（非现状） · ⛔ 已废弃 · ⚠️ 含更正或待验证。
- **归档**：功能整体移除、结论被推翻且无复用价值、或属外部项目存档时，移入 [`archive/`](archive/) 并同步更新 `PROJECT-GUIDE.md` 索引、`archive/README.md` 及根 `README.md` 链接。
