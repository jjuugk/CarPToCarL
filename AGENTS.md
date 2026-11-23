# AGENTS.md — CarPToCarL 仓库协作契约

> 仅记录代码与配置本身无法直接说明的仓库不变量与文档入口；目录结构、文件数与测试数以当前代码树和命令输出为准。

## 构建与校验不变量

- **JDK 17**：Gradle 必须运行在 JDK 17（`gradle.properties` 已固定），不要升级到 JDK 21/25。
- **定向格式化**：`HEAD` 存量文件不满足全局 ktlint，**只保持改动文件整洁，不要运行全局 `./gradlew spotlessApply`**（会重排 50+ 无关文件）。
- **版本号唯一来源**：`app/build.gradle.kts` 顶部的 `appVersionName`；版本号**禁止连续两个 `4`**（如 `1.2.44`，构建脚本会直接拦截）。
- **合规守卫**：提交前运行 `bash scripts/check-no-vendor-artifacts.sh`；仓库不得包含反编译产物、厂商 SDK、抓包二进制、私钥或真实车机/手机名（统一使用脱敏占位符）。
- **Lint 基线对比**：若上游基线已存在 `lintDebug` 报错，验证时对比基线确保**零新增（`new=0`）**，不要顺手改动无关文件。

## 多车机兼容红线

- **已通车型逐字节保护**：奥迪 MIB3、通用系（别克/凯迪拉克 Cinemo）、宝马 EntryEVO/MGU、DiPlay 等已跑通路径的首包与默认报文，在缺乏多车实测前保持逐字节不变；新策略优先做成**失败兜底**、**按 `/info` 或 `0x1D01` 能力门控**或**默认关闭的开关**。
- **出画与协议验收以实测信号为准**：`VideoConfig` 声明对齐车机画布，SPS 仅作诊断；链路层 ACK 不代表业务层接受。

## 按场景读取专题文档（Context Pointers）

- **排查连接 / 黑屏 / 音频 / 触控 / 语音 / 冻结故障** → 先查 [`docs/PROJECT-GUIDE.md`](docs/PROJECT-GUIDE.md) §2（按症状分枝定位到具体 L1–L5 文档）。
- **修改协议行为或复核历史翻案结论** → 先查 [`docs/PROJECT-GUIDE.md`](docs/PROJECT-GUIDE.md) §3（已证伪假设与正反边界），再读对应专题文档末尾的「更正」节。
- **修改跨协议路由 / `VideoSink` / `AudioSink` 或接入新协议** → 读 [`docs/PROTOCOL-MODULES.md`](docs/PROTOCOL-MODULES.md)。
- **核对哪些改动尚未实车验证、以及当前 P0/P1 风险** → 读 [`docs/PROJECT-STATUS.md`](docs/PROJECT-STATUS.md)。
- **查询逐版变更明细或 `1.2.64/1.2.65` 回滚清单** → 读 [`CHANGELOG.md`](CHANGELOG.md)。
- **新增或归档 `docs/` 文档** → 按 [`docs/README.md`](docs/README.md) 的命名、状态标签与归档规则执行。
