# CarLife/Carlink → CarPlay 网关可行性调研

> 调研日期：2026-10-08。目标：在现有 app（CarPlay 发送端）中增加一个
> CarLife/ICCOA Carlink **接收端**，让手机上的车联 app / 高德把画面投进我们的 app，
> 再经 CarPlay 转发到奥迪车机——从「自绘桌面」升级为「真实投屏内容网关」。

## 结论速览

| 维度 | 评估 |
|---|---|
| 架构先例 | ✅ Carlinkit ICCOA 盒子/Tbox 就是「互联协议接收端→CarPlay 通道」桥接，市售成熟 |
| CarLife 协议开放度 | ✅ 百度 2018 年官方开源过完整车机端（apollo-DuerOS，Apache 2.0，fork 幸存） |
| 开源参考实现 | ✅ CarLifeWirelessBox（MIT，2026 活跃，实测兼容小米 CarWith 的 CarLife 连接）|
| 视频转发成本 | ✅ CarLife 已是 H.264 Annex B，→ CarPlay 的 TS/RTP 只需 remux，可零转码 |
| **高德完整投屏** | ❌ **CarLife 通道不支持高德**（只适配百度系）；高德深度适配走 Carlink 通道 |
| ICCOA Carlink 开放度 | ❌ 联盟会员制、零开源、含认证加密；只能逆向（工作量大、有合规风险） |
| 单机回环（127.0.0.1） | ⚠️ 数据面无障碍；风险在发送端 app 是否肯在无 USB/无车机时进入监听态，需实测 |

## 1. CarLife 协议：几乎是全公开的

百度 2018 年在 GitHub ApolloAuto/apollo-DuerOS 官方开源过车机端完整实现
（原仓库已删，81 个 fork 幸存，如 milesking/apollo-DuerOS，Apache 2.0 可合法复用）。

- **传输**：7 个 TCP 通道——手机端监听 7240(CMD)/8240(VIDEO)/9240(MEDIA)/
  9241(TTS)/9242(VR)/9340(TOUCH)/9440(CTRL)；WiFi 模式 = 接收端主动连
  「手机IP:端口」纯 TCP 直连（无需 ADB；USB 模式才走 ADB forward/AOA）
- **消息**：Protobuf + 自定义包头；消息 ID 实测已知：版本协商 98305/65538、
  鉴权 98380/65611、心跳 131074、触摸 425985、硬按键 425992；握手 8 阶段
- **媒体**：视频 = 发送端编码的 H.264/AVC Annex B 裸流（720p/30fps 量级）；
  音频 PCM/AAC；控制走 protobuf
- **触摸回传**：独立 touch 通道，坐标按投屏分辨率换算——正好接我们 CarPlay 的 HID 触点

## 2. 关键缺口：高德在哪个通道

- **CarLife+ 默认不支持高德投屏**（只适配百度系 app）。变通：CarLife+ 8.0
  「投屏手机应用」镜像全屏（含高德），或「超级投屏」USB 解锁——镜像模式体验受限
- **高德完整深度投屏在 CarWith/ICCOA Carlink 通道**（车道级/巡航/完整手机版 UI），
  该协议：规范仅联盟会员可下载、零开源实现、含认证加密；社区只能逆向
  CarWith/Jovi 手机端或恩山流通的车机端 APK
- **小米 CarWith 内保留「CarLife 连接」入口**（内置 com.baidu.carlife 组件）——
  即只实现 CarLife 接收端即可先覆盖 CarWith 的兼容模式；荣耀车联同理（CarLife 兼容）
- 新版坑：CarLife app ≥7.2 与第三方车机端黑屏不兼容；可靠发送端 =
  CarWith 的 CarLife 连接、旧版 CarLife app（≤6.7.2）、各厂商车联的兼容模式（需实测）

## 3. 单机回环可行性（本方案的核心创新点与风险点）

- CarLife WiFi 模式数据面 = TCP 直连手机 IP → 127.0.0.1 理论直接可用
  （Android 沙箱允许 app 间 localhost 互通，无需 adb forward——无 root 也做不了自转发）
- **无人公开验证过同机回环**。风险全在发送端状态机：
  发送端 app（车联/CarLife/高德内嵌模块）可能要求「检测到 USB 插入」或
  「连上车机热点」才启动监听服务/接受连接
- 实测方法（Go/No-Go 实验）：手机装旧版 CarLife app → 打开连接界面 →
  本机浏览器/另一 app 连 127.0.0.1:7240 看是否有服务；或先用
  carlife_pc_tool 在 PC 上验证手机发送端，再挪回同机
- **Fallback 架构**（回环跑不通时）：备用手机装完整 app（CarLife 接收端 +
  CarPlay 发送端）常驻车内 = 自研版 Tbox 盒子（CarLifeWirelessBox 已验证此拓扑，
  我们再把 CarPlay 发送端补上即可）——这正是商业产品的形态

## 4. 与现有项目的整合（若实施）

```
┌─────────────────── 同一台荣耀手机 ───────────────────┐
│ 荣耀车联/CarLife app(发送端)                          │
│        ↕ 127.0.0.1:7240… (CarLife WiFi 模式)         │
│ carlife/ 模块：CarLife 接收端（新）                    │
│   - 握手/鉴权/心跳（protobuf）                        │
│   - H.264 Annex B 收流 → remux（零转码）              │
│   - touch 425985 ← CarPlay HID 触点坐标换算           │
│ bridge/：把 remux 后的 TS 流喂给 CarPlay 发送端        │
│        ↕ 蓝牙 RFCOMM + WiFi + AirPlay 主会话          │
└──────────────────────→ 奥迪车机屏 ────────────────────┘
```

实施顺序建议：先做「回环 Go/No-Go 实验」（1-2 天）→ CarLife 接收端
（复用 apollo-DuerOS C++ 库或 CarLifeWirelessBox Kotlin 实现，2-3 周）→
remux 桥（1 周）→ 与 Phase 2 的 CarPlay 发送端对接。

## 5. 与替代路线的对比（重要）

| 路线 | 高德体验 | 工程量 | 风险 | 延迟 |
|---|---|---|---|---|
| A. 高德导航 SDK 嵌入自绘 UI | 导航专业、UI 可控但非「完整高德」 | 小（个人可申请 Key） | 低 | 单级，最低 |
| B. CarLife 网关（本调研） | 百度系 app + 镜像模式高德 | 中 | 中（发送端兼容性） | 双级 ~200-400ms |
| C. Carlink 网关（逆向） | 完整高德深度适配（最优） | 大 | 高（闭源+加密+碎片化） | 双级 |
| D. 买 Tbox 盒子 | 完整（盒子内置 B+C） | 零开发 | 低 | 盒子级 |

**推荐**：A 作为基线立即做（它和 B/C 不冲突，本来就是 Phase 2 自绘 UI 的内容）；
B 作为增强在 Phase 2 完成后启动（先跑回环实验）；C 仅在 B 验证后仍有强需求时评估。

## 6. 参考实现索引

| 项目 | 说明 |
|---|---|
| milesking/apollo-DuerOS（fork） | 百度官方开源车机端，Apache 2.0，C++ 库 + Android app |
| pitate7777777/CarLifeWirelessBox | MIT，Kotlin，HU+MD 双角色，实测兼容 CarWith 无线 CarLife |
| MeshHun/carlife_pc_tool | PC 端车机模拟器，反编译协议常量，2026-09 活跃 |
| aa112901/CarProjection | 手机当 CarLife 车机屏，Android 10+ 免 root |
| eytb2.github.io CarLife 培训笔记 | 官方培训资料：连接方式/通道/protobuf 结构 |
| 恩山 ICCOA Carlink 更新集合 | 车机端 APK 流通源（仅研究参考，勿分发） |

## 7. 合规注意

- apollo-DuerOS（Apache 2.0）与 CarLifeWirelessBox（MIT）可合法复用，保留版权头
- 逆向 CarWith/Carlink APK、恩山流通 APK：仅限个人研究，不分置、不分发
- 「CarLife」为百度商标，商用需授权；个人项目命名注意规避
