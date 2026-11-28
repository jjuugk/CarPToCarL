# CarPToCarL — 让 Android 手机充当 CarPlay 发送端，把原生 CarLife 投到车机

一台未 root 的 Android 手机扮演 **CarPlay 发送端（iPhone 角色）**，连接支持无线 CarPlay 的车机；
画面与声音取自手机原生 **CarLife+ 引擎**，本 App 劫持后**直通转发**（H.264 / PCM 不重编码）。

> **非官方实验项目**：与 Apple、百度及任何汽车制造商无关联，未获授权或认证，**不是 MFi 产品**；
> CarPlay / AirPlay / CarLife 均为其各自权利人的商标，文中仅作兼容性描述。
> 仅限停车时配置与操作，行驶中请以原厂导航作为安全冗余。完整条款见 [DISCLAIMER.md](DISCLAIMER.md)。

---

## 一、项目简介

| 项 | 值 |
| --- | --- |
| 当前发布版 | **1.2.91**（正式版）。逐版变更见 [CHANGELOG.md](CHANGELOG.md) |
| 包名 | `io.github.shaonialife.carptocar` |
| 系统要求 | Android 12+（minSdk 31 / targetSdk 34） |
| 画面来源 | 手机 **CarLife+**（荣耀机型为系统内置「荣耀车联」，逆向基线 8.8.5） |
| 音频 | CarLife 媒体 `9240` + 导航播报 `9241` → PCM → AirPlay RTP；两路独立通道 |
| 链路 | 蓝牙 iAP2 取热点凭据 → WiFi + mDNS 找端点 → AirPlay PairVerify / SETUP / RECORD → 视频、音频、HID 数据面 |
| 参考项目 | [CarDeck](https://github.com/shaonialife/cardeck)（MIT，独立项目，非同一作者）——协议层与工程骨架参考其实现，声明见 [NOTICE](NOTICE) 第 0 节 |
| 许可 | 源码 [MIT](LICENSE)；MIT 只授予本仓库代码的著作权，**不授予** Apple / 百度的协议、认证与商标权利 |

**边界**：发送端不需要苹果签名身份（iAP2 认证单向：手机验证车机 MFi 证书，车机信任手机自报结果）。
**有线 USB 不可行**（无 root 无解，见 [docs/current/WIRED-CARPLAY-FEASIBILITY.md](docs/current/WIRED-CARPLAY-FEASIBILITY.md)），
不使用 Shizuku。收费边界与商业化风险见 [docs/current/LEGAL-NOTES.md](docs/current/LEGAL-NOTES.md)。

---

## 二、快速上手

### 下载与安装

> **[`CarPToCarL-1.2.91.apk`](https://github.com/jjuugk/CarPToCarL/releases/download/v1.2.91/CarPToCarL-1.2.91.apk)**
> —— 主线固定直链（13.8 MB）。刻意不用 `releases/latest`：那个指针按发布时间算，
> `-switchpool` 分支包一发布就会把它抢走，主线的 `latest` 直链随之 404。

| 项 | 值 |
| --- | --- |
| 全部版本 | [Releases 页面](https://github.com/jjuugk/CarPToCarL/releases) |
| 安装 | 系统安装器直接装，或 `adb install -r CarPToCarL-1.2.91.apk`（**同签名覆盖装，保留应用数据**） |
| 签名 | 作者个人调试证书（仓库不含发布密钥）→ 可直接覆盖 1.0.0 ~ 1.2.91；不要与其它来源的包混装 |
| 首次启动 | 弹出免责声明，需手动同意 |

### 前置条件

- 车机支持**无线 CarPlay**（有线不适用）。
- 手机已安装 **CarLife+**（荣耀机型为系统内置「荣耀车联」）。
- 手机蓝牙已与车机完成**系统级配对**（不是 App 内扫描配对）。
- 首次使用按提示授权（见下方权限表）。

### 每次使用（顺序）

```
① 系统蓝牙里与车机配对
        ↓
② 打开「荣耀车联 / CarLife+」→ 点【无线 WLAN 连接】
        ↓
③ 停在「正在连接」界面（不要退出、不要返回）
        ↓
④ 回 CarPToCarL → 点【启动连接】
        ↓
⑤ 自动加入车机热点 → 建立 CarPlay 会话 → 画面出现在车机
```

> 第 ③ 步是关键：引擎此时才启动、创建虚拟屏并监听 `7240`，App 检测到端口一开立即接管。
> 「启动后自动连接」默认开启；需要连前改设置时，在「连接准备」里关掉它。

**画面出来后做一次**：手机端「荣耀车联 → 设置 → 投屏显示设置 → 帧率」选 **60 帧**，
同一页的**分辨率保持「默认」**，然后断开重连一次。默认档只有 20fps；改帧率容易顺手动到分辨率，
一旦虚拟屏尺寸偏离车机画布就会黑边 / 拉伸（详见 [docs/README.md](docs/README.md) 的排障入口）。

### 权限

| 权限 | 用途 | 必需 |
| --- | --- | --- |
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` | iAP2 控制通道、扫描配对车机 | ✅ |
| `NEARBY_WIFI_DEVICES`（12L 及以下还需定位） | `WifiNetworkSpecifier` 加入车机热点 | ✅ |
| `RECORD_AUDIO` | `AudioPlaybackCapture` 捕获本机媒体 / 导航声音 | ✅（要声音时） |
| `POST_NOTIFICATIONS` | 前台服务常驻通知 | ✅ |
| 通知使用权（特殊权限） | 媒体会话：歌名 / 歌手 / 歌词 / 播放状态 | 要歌词就必需 |
| `SYSTEM_ALERT_WINDOW` | 靠近自动启动豁免 | ❌ 可选 |

`BLUETOOTH_CONNECT` 与 `NEARBY_WIFI_DEVICES` 同组，首次连接合并成**一个**系统授权框
「要与 CarPToCarL 配合的设备」——这是 Android 系统框，无法由应用隐藏，点一次允许即可。

### 自己构建 / 看日志

```bash
./gradlew assembleDebug --offline                       # 固定 JDK 17 + 项目内 .tools/，勿改
./gradlew :app:assembleRelease :app:testReleaseUnitTest # 发版前必跑
./.tools/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

主界面底部可复制 / 导出 / 清空日志（文件在 `files/main_debug.log`）；
荣耀机 logcat 被 HKS 加密、`run-as` 不可用，靠 App 导出后 `adb pull`。
构建、签名、发版与提交钩子细则见 [docs/current/PROJECT-GUIDE.md](docs/current/PROJECT-GUIDE.md) §8、§9。

---

## 三、兼容性表

| 类别 | 状态 | 说明 |
| --- | --- | --- |
| 手机系统 | ✅ Android 12+ | minSdk 31 / targetSdk 34；厂商后台冻结策略会显著影响稳定性（vivo / 荣耀需加白名单） |
| 车机 | ✅ 无线 CarPlay | 真车全链路验证：**奥迪 MIB3**；**宝马 MGU-81 / EntryEVO / iD6 / iD9** 与**通用 Cinemo 系（别克等）**有现场日志，兼容程度依固件而异 |
| 测试台 | 🧩 | DiPlay / xcertplay / WinPlay（接收端模拟），不等于真车 |
| 画面源 | ✅ 荣耀车联 / CarLife+ 8.8.5 | 其它厂商 CarLife+ 未逐一验证；必须手动停在「正在连接」，App 自动拉起只会拿到欢迎页 |
| 音频档 | ✅ / ⚠️ | 媒体：`t100/media` PCM48k 立体声，`t102/media` AAC-LC（44.1k 或 48k/250ms，按车机声明选）；导航仅 AAC-ELD 时建不起来，**绝不回落媒体流** |
| 帧率 / 分辨率 | ⚠️ | 视频目标帧率跟随车机 `displays[0].maxFPS`（24–120，缺省 60）；画面源默认 20fps，需在车联里手动改 60；编码尺寸受厂商 mix 层覆盖，分辨率必须保持「默认」 |
| 明确不支持 | ⛔ | 有线 USB CarPlay；无 CarLife+ 环境；需要 root / Shizuku 的路线；仅有有线 CarPlay 的车机 |
| 实验 / 未逐项实车 | 🧪 | 双指缩放、车机 GPS 转发、导航独立音频流、车机麦克风上行（media 档塞 dataPort 会打死宝马 G38，按 `audioInputFormats` 门控）、「仪表转向指引」开关 |

能力矩阵与未验证清单见 [docs/current/PROJECT-STATUS.md](docs/current/PROJECT-STATUS.md)。

---

## 四、文档索引

| 入口 | 什么时候读 |
| --- | --- |
| [CHANGELOG.md](CHANGELOG.md) | 想查某版本改了什么、当时的现场证据与结论更正 |
| [docs/README.md](docs/README.md) | **docs 目录地图**：按角色给阅读路径、命名与归档约定 |
| [docs/current/PROJECT-GUIDE.md](docs/current/PROJECT-GUIDE.md) | **现行结论唯一真源索引**：状态标签、排障决策表、冲突红线、构建发版速查 |
| [docs/current/PROJECT-STATUS.md](docs/current/PROJECT-STATUS.md) | 项目体检报告：版本 / 代码 / 能力 / 风险 / 未验证清单 / 分支状态 |
| [docs/current/PROTOCOL-NOTES.md](docs/current/PROTOCOL-NOTES.md) · [docs/current/PROTOCOL-MODULES.md](docs/current/PROTOCOL-MODULES.md) | 协议规范与模块架构：改协议 / 加第三种协议前必读 |
| [docs/archive/README.md](docs/archive/README.md) | 看到旧链接或过时结论时：历史排障与已失效文档的原因表 |

**分区约定**：`docs/current/` 只放现行规范 / 架构（唯一真源）；`docs/archive/` 是历史排障归档，
**只作取证，不作为现状引用**。结论冲突时以 `docs/current/` 与源码为准，
历史条目按 [`docs/current/PROJECT-GUIDE.md`](docs/current/PROJECT-GUIDE.md) 的冲突红线判定。
