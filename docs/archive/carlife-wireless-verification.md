# CarLife 劫持验证方案（对齐 CARLIFE-GATEWAY-FEASIBILITY.md）

> 前置文档：`docs/archive/CARLIFE-GATEWAY-FEASIBILITY.md`（2026-10-08 完整调研，本文件是其  
> 「回环 Go/No-Go 实验」的可执行细化）。路线修正：**首选单机回环监听，VpnService 仅作备选**。  
> Shizuku 移除工作（任务 #2-#5）保持暂停待命。

## 路线修正说明（2026-10-23）

初版方案假设「手机 CarLife 客户端主动连车机」→ 需要 VpnService 截流。  
对照调研文档 §1 证实该假设不成立：

- **CarLife WiFi 模式是「接收端（车机）主动连手机端 7 个 TCP 端口」**：  
  7240(CMD)` / 8240(VIDEO) / 9240(MEDIA) / 9241(TTS) / 9242(VR) / 9340(TOUCH) / 9440(CTRL)，`  
  纯 TCP、无需 ADB。
- 因此本机 app **直接监听 127.0.0.1 同端口**即可扮演车机端（CarLife 接收端），  
  无需 root、无需 VpnService、无需无线调试——比初版方案简单一个量级。
- 视频是 H.264 Annex B 裸流 → **零转码 remux** 进现有 CarPlay 发送端；  
  音频 PCM（MediaAudio 通道）与现有 AudioSender 的 PCM 48k 立体声格式天然对齐；  
  触摸走 CarLife touch 通道（425985）↔ 现有 CarPlay HID 触点坐标换算，  
  **CarLife 发送端原生注入触摸——本模式不依赖 Shizuku**。

## 单机回环 Go/No-Go 实验（1-2 天）

### 实验一：端口监听态（数据面）

1. 手机装旧版 CarLife app（≤6.7.2，规避 ≥7.2 与第三方车机端黑屏的已知坑），  
   或直接用荣耀车联的 CarLife 兼容模式。
2. 打开 CarLife 的「连接车机」界面（保持前台）。
3. 用任意 TCP 客户端（或临时 demo app）连 `127.0.0.1:7240`：
   - 有监听/有应答 → **数据面 GO**；
   - 无监听 → 检查是否只在「已连上车机热点 / USB 插入」状态机下才启动监听。

### 实验二：状态机触发（风险面，文档 §3 指出的核心未知项）

若实验一失败，逐个尝试解除发送端的状态前置：

- a. 手机加入任意热点（哪怕本机开热点再连回去/另一台手机开热点）后再试 7240；
- b. USB 插入 PC（模拟 AOA 枚举）后拔线再试；
- c. 荣耀车联 vs 独立 CarLife app vs CarWith 兼容入口分别测  
  （文档证实 CarWith 保留 CarLife 连接入口、CarLifeWirelessBox 实测兼容该路径）；
- d. 全部失败 → 回环 NO-GO，转备选架构（见下）。

### 实验三：协议握手对拍（确认可解性）

数据面通了以后，本机监听端按 apollo-DuerOS / CarLifeWirelessBox 的握手表  
（版本协商 98305/65538 → 鉴权 98380/65611 → 心跳 131074，8 阶段）回应，  
确认能进入 8240 收到 H.264 Annex B（`00 00 00 01` 起始码）。  
到这一步即可立项：CarLife 接收端（复用 CarLifeWirelessBox Kotlin 实现，2-3 周）  
→ remux 桥（1 周）→ 对接现有 AirPlay 会话。

## 备选架构（回环跑不通时，按序）

1. **备用手机 Tbox 架构**（文档 §3 fallback）：备用机装 CarLife 接收端 + CarPlay  
   发送端常驻车内 = 自研 Carlinkit 盒子。CarLifeWirelessBox 已验证该拓扑可行。
2. **VpnService 截流**（初版方案，降级保留）：仅当发送端强制「连真实车机热点」  
   且无法在回环满足状态机时使用；实现成本高、收益低于 1/2，一般不必动用。

## 已知限制（立项前须知晓，来自调研文档 §2/§5）

- **Ca<u>rLife 通道不支持高德深度投屏</u>**<u>（只适配百度系；高德走 ICCOA Carlink 通道，  
  闭源+加密，另案）。变通：CarLife+ 8.0「</u>投屏手机应用」全屏镜像。
- 本模式产出 = CarLife UI 单屏（非 CarDeck 自绘桌面/多屏），定位为独立投屏模式。
- 车机端协议有成熟参考：milesking/apollo-DuerOS fork（Apache 2.0）、  
  pitate7777777/CarLifeWirelessBox（MIT，Kotlin，实测 CarWith 无线兼容）、  
  MeshHun/carlife_pc_tool。合规：保留版权头；「CarLife」商标商用需授权。

## 当前状态

- [x] 路线修正（本文档）
- [ ] 实验一：127.0.0.1:7240 监听态探测
- [ ] 实验二：发送端状态机触发
- [ ] 实验三：协议握手对拍
- [ ] 立项判定
