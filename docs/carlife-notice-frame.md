# CarLife 未劫持时向车机推送提示帧

## 背景

连接成功后，画面来自本机 CarLife+（经 `CarLifeHijack.attach` 把原生 CarLife+ 的 H.264
直通进车机通道）。若 CarLife+ 没进入「无线连接」、或引擎没在推流（7240 端口无数据），
车机这会儿是**黑屏**——用户完全不知道是「连上了但没画面」还是「根本没连上」。

需求（2026-11-03）：连接已成功、但 CarLife 没劫持到画面时，把提示文字
「未劫持到手机车联画面」推到车机屏幕上，代替黑屏。

## 改动

| 文件 | 作用 |
|---|---|
| `video/NoticeFrameEncoder.kt`（新增） | 把文字渲染成单帧 H.264（SPS/PPS + 一个 IDR），AnnexB 格式 |
| `video/VideoSender.kt` | 新增 `pushNotice(lines)`，编码后 `submitH264` 注入视频通道 |
| `SessionController.kt` | `startFirstFrameGate()` 超时且**车机本会话未收到任何帧**（`CarLifeHijack.framesDelivered` 相对本次会话起点无增长）时推送提示；按 CarLife 是否真在推流分两种文案，并周期重推 |

## 关键设计

- **复用现有视频通道**：CarLife 直通模式下 `VideoSender` 不持有编码器、直接吃外部 H.264。
  提示帧同样是「一段 AnnexB H.264」，走 `submitH264` 与 CarLife 帧**完全同一条路**
  （同样的加密、同样的 128B 头），所以车机把它当普通关键帧解码——不需要另开一条链路。
- **编码即用即弃**：`NoticeFrameEncoder` 每次 `pushNotice` 临时建一个 MediaCodec Surface
  编码器，画一帧黑底白字、EOS 后取出 codec-config（avcC：SPS/PPS）与 IDR 帧，转成 AnnexB
  后立即 `release()`。**不长期占用编码器**（CarLife 模式本就不该常驻编码器）。
- **MediaCodec 输出是长度前缀格式**：H.264 编码器输出每个 NAL 前带 `lenSize`（来自 avcC
  字节 4 低 2 位 +1，通常 4）字节大端长度，不是起始码。统一转成 `00 00 00 01` 起始码后
  再喂 `submitH264`（其内部 `splitAnnexB` 依赖起始码拆分）。
- **声明宽高对齐车机画布**：`sendConfigFromNals` 仍以 `liveSize` / VideoSender 配置宽高为
  VideoConfig 的画布声明；提示帧按 VideoSender 的 `width`/`height`（即 /info 显示尺寸）编码，
  声明宽高自动一致，不会因提示帧尺寸错配导致黑屏。
- **保活 + 接管**：提示帧被记成 `lastIdrNals`，空闲重发机制持续把它维持在车机屏上；
  之后 CarLife 一旦把帧**真正送进车机通道**（`framesDelivered` 增长），其 IDR 自然覆盖提示
  （检测 SPS 变化重发 config）。注意：判定「车机已收到画面」看的是 `framesDelivered`（车机通道侧
  送达计数），**不是** `CarLifeHijack.isStreaming`——后者只是 CarLife 单方面推流，不代表帧进了车机。
- **防丢帧重推**：首推后每 5s 重推一次（上限 60s），应对车机丢掉首帧；CarLife 接管或会话
  结束即停。
- **失败兜底**：编码器不可用 / 编码异常时 `pushNotice` 返回 false，上层保留原有日志提示，
  绝不因这条新逻辑而丢信息。

## 验证

- `assembleRelease` 通过；`compileReleaseKotlin` / `testReleaseUnitTest` 全绿。
- 该路径依赖真机 MediaCodec（Surface 编码器），**无 JVM 单测**；上车后看两点：
  1. CarLife+ 未进「无线连接」时，车机应显示「未劫持到手机车联画面」而非黑屏；
  2. 在 CarLife+ 里进入「无线连接」后，提示应在 1 秒内被真实画面覆盖。

## 修正记录（2026-11-03 · 1.2.20）

首版（1.2.19）首帧闸门用 `CarLifeHijack.isStreaming` 判断「车机收到首帧」，是错误判据。
用户实测：CarPlay 已连、App 显示「已劫持」（isStreaming=true，CarLife 确实在推流），
但车机一直等视频——根因是 `submitH264` 因 SPS/VideoConfig 卡住或重连等不到新 IDR 返回 false，
帧没进车机通道，`framesDelivered` 始终为 0。旧闸门一看 isStreaming=true 就提前 return，
既不等待真实首帧、也不弹提示。

修法：闸门改以 `CarLifeHijack.framesDelivered` 相对**本次会话起点**的差值作为「车机收到首帧」判据
（`framesDelivered` 跨会话累加不归零，必须用 baseline 差值，与 `CarLifeHijack.startFirstFrameWatchdog`
同一思路）。超时仍无送达时按 CarLife 是否真在推流分两种文案：

- 没推流：沿用「未劫持到手机车联画面 / 请在手机 CarLife+ 进入「无线连接」」；
- 在推流但车机未收到：改为「已连接但车机未收到画面 / 请在车机重连或重启 CarLife+ 无线连接」。

超时日志额外带 `CarLife推流=` 与 `累计送达=` 两项，便于一次区分「源没画面」还是「链路/配置卡住」。
