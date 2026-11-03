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
| `SessionController.kt` | `startFirstFrameGate()` 超时且 `!CarLifeHijack.isStreaming` 时推送提示，并周期重推 |

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
  之后 CarLife 一旦出帧，`isStreaming` 变 true，其 IDR 自然覆盖提示（检测 SPS 变化重发 config）。
- **防丢帧重推**：首推后每 5s 重推一次（上限 60s），应对车机丢掉首帧；CarLife 接管或会话
  结束即停。
- **失败兜底**：编码器不可用 / 编码异常时 `pushNotice` 返回 false，上层保留原有日志提示，
  绝不因这条新逻辑而丢信息。

## 验证

- `assembleRelease` 通过；`compileReleaseKotlin` / `testReleaseUnitTest` 全绿。
- 该路径依赖真机 MediaCodec（Surface 编码器），**无 JVM 单测**；上车后看两点：
  1. CarLife+ 未进「无线连接」时，车机应显示「未劫持到手机车联画面」而非黑屏；
  2. 在 CarLife+ 里进入「无线连接」后，提示应在 1 秒内被真实画面覆盖。
