# VideoConfig 声明的是「车机画布」，不是码流尺寸

> 2026-11-04 · 奥迪 MIB3（`Audi_MMI_5983` / `AUDI 3810` / DELPHI）实车「连上了、帧一直在送、车机全黑」

## 现象

1.2.21-switchpool 日志（用户导出 `2026-11-04 11:08:32`）里，除了画面，**一切都正常**：

```
11:08:04.983  /info displays[0] = 1412×654 @60fps
11:08:05.087  车机分辨率申请更新 1280x720 → 1412x654，重发 98311
11:08:05.391  手机编码参数 1280x720@20（申请 1412x654@60）   ← 引擎白名单降档
11:08:05.141  VideoConfig 已发送 avcC=33B … flags=0x04 1280x720
11:08:05.170  帧 #1 177033B (key) 已发送 …
11:08:31.458  帧 #719 176768B (key) 已发送
11:08:20.212  送帧统计 5.0s/122帧 … 写入 p95=2 max=14ms；慢写 0 次
```

会话、RECORD、加密数据口、送帧节奏、写入耗时全部健康——**只有屏是黑的**。

## 根因

VideoConfig 头 `params[1]`（偏移 16、20）有两个 f32 LE：车机**按它们建解码器与 surface**。
这次发出去的是 `1280.0 / 720.0`（头部前 24 字节尾部 `00 00 a0 44 00 00 34 44`），
而车机画布是 **1412×654** —— 车机拿 720p 画布去解 1412×654 的屏，解不出画面。

为什么声明会变成 720p：直通模式下码流尺寸由 CarLife 引擎决定，代码把
「引擎 ack 的尺寸」当成声明值。这台车的引擎把 `1412×654@60` 降档成 `1280×720@20`
（白名单只认标准档），声明就跟着掉到 720p。

## 为什么以前没暴露

- **测试台不暴露**：DiPlay / WinPlay 只读 `opcode` 与 `body`（avcC 里取分辨率），
  不校验头部 `params[1]` 与 `small_param[1]`（见 `PROTOCOL-NOTES.md` §9g）。
  所以在 1280×720 的测试台上、乃至把测试台画布改成 1412×654，都照样有画面。
- **老车机不暴露**：引擎多数时候老老实实把申请值回显成 ack（`AUDI 4260` / ID7 的
  `2240×976` 都是这样），声明恰好等于画布，问题被掩盖。一旦引擎降档就现形。

## 规则（写死在代码里）

`VideoConfig.params[1]` = **车机 `/info displays[0]` 的画布尺寸**，优先级：

```
车机画布（canvasSize） > 引擎码流（liveSize） > 构造值 > SPS 解析值
```

SPS / 引擎尺寸只作诊断。码流比画布小时，由车机侧放大；码流比画布大时，
2026-10-25 已经验过一次「画面异常」，同样不能反着来。

## 验证点（下次上车看这两行）

```
VIDEO: ✓ VideoConfig 已发送 … flags=0x04 1412x654          ← 声明必须是 1412x654
VIDEO: ⚠ 声明的画布 1412x654 与码流 1280x720 不一致 …      ← 新增提示，说明降档发生
```

若声明已经正确、屏还是黑，就说明这台车要求**码流尺寸本身等于画布**，
下一步只能让 CarLife 引擎按 1412×654 编码（重发 98311 / 换帧率组合试探），
而不是继续改声明。

## 2026-11-04 追加：4580 固件给出的旁证

`docs/mib3-carplay-display-contract.md` 里从车机固件抽出的事实支持这个判断：

- 车机 HMI 把 CarPlay 固定在 `carPlayDeviceG35Layout` = **1412×654**（偏移 128,66，
  物理屏 1540×720），画面落在 esogfx `Displayable_External_Smarthphone`(93)，走 OMX 解码。
- 4260 出画那次码流正好是 1412×654；3810 全黑这次是 1280×720。两车在**同一固件包**里，
  接收端同一份代码——差异只可能来自我们送进去的流。

⚠️ 因此「只把声明改成 1412×654、码流仍是 1280×720」并不安全：声明与 SPS 打架时，
接收端会按声明建解码器/校验头部（固件里对「invalid header frame」有专门的 flush+重发
workaround）。**正确目标是声明 = SPS = 车机窗口 = 1412×654**，光改声明不算修好。


## 2026-11-04 15:00 修正：`params[1]` 是**解码**尺寸，视口在 40/44

上面那套「声明车机画布」只对了一半。4580 的头里有**两组**尺寸，含义不同：

| 偏移 | 字段 | 含义 | 应填 |
|---|---|---|---|
| 16 | `params[1]` | 解码器尺寸 | **码流尺寸**（= SPS，本例 1280×720） |
| 32/36 | `params[3]` | 视口原点 x/y | 0 / 0 |
| 40/44 | `params[4]` | 视口宽高 | **车机画布**（1412×654） |

依据**只有一条直接证据**：另一实现（AMCar 1.0.11）的 4580 补丁原文

```
4580's parser reads x/y at 32/36 and width/height at 40/44.
Encoded dimensions and display view dimensions are separate.
```

它采用「视频尺寸跟随 SPS、视口填 `/info` 画布」。本机固件**没有**提供第二条独立证据，
见下一节复核。

所以 11:08 那次黑屏的**直接原因不是「声明了 720p」**，而是「此前一直只写 16、
32..48 全是 0」——按 AMCar 的说法，车机没有视口可用。把 16 改成 1412×654 只会让它与 SPS 打架
（13:48 实测：65544 原样回 1412×656，SPS 仍是 1280×720，首个 IDR 被判 invalid header）。

实现：`VideoSender.viewAreaSize` → `buildVideoConfigHeader(..., viewArea)` 写 32/36/40/44。
开关 `videoconfig_canvas_mode`：

- `stream`（默认）：16 = 码流尺寸，40/44 = 车机画布 —— 分离声明；
- `car`：16 = 车机画布，不写视口 —— 旧行为，留作对照。

## 2026-11-04 18:20 复核：这个改动到底可不可行

把 `opt_ivi_C3.img.swup` 里 195 个 ELF 全解出来之后，结论是：
**可以做、风险低、值得 A/B，但它不是固件推出来的结论，也不能靠它单独点亮屏幕。**

### 固件能证明什么、不能证明什么

| 命题 | 固件里的东西 | 强度 |
| --- | --- | --- |
| 车机有独立显示矩形 | `carPlayDeviceG35Layout` = 1412×654 @(128,66)，物理屏 1540×720（刚好填满）；esogfx `displayable 93` / `Layer_Media_Base` | **强**，HMI 配置原文 |
| 渲染层有「源矩形 → 目标矩形」能力 | media 模块 `[autoscale=%i,winWidth=%d,…,sourceX=…,destWidth=…]`、`invalid video format -> adjust to window: keep input aspect ratio!` | 中：DVD/Player 通用管线，**非 CarLife 专用** |
| 车机会读 VideoConfig 的 40/44 当视口 | **找不到**。195 个 ELF 里没有 CarLife 协议栈；`de/esolutions/fw/dsi/carlife/*.class` 是 Java DSI 接口，native 侧只有 `CarLife_Video_Width/Height/Framerate` 元数据与 `isCarLife*USBEnabled` 配置解析 | **无** |
| 「视口」是不是 CarLife 的概念 | 轻度反证：CarLife 元数据表只有 W/H/Framerate，没有 viewArea/insets；带 `AndroidAuto_Video_Margin_*` / `_Current_Insets_*` 的是 AA 侧 | 弱反证 |

⚠️ **撤回上一版的一处误用**：CarPlay `/info` 里的 `viewAreas=true` 是 **AirPlay 能力位**（手机据此送
viewArea 内容描述），不是 CarLife VideoConfig 的头字段；`CDeviceDetection` /
`CCarPlayTestController` 是**激活判定链**，跟视频矩形没有关系。把这两条当成「独立证据」是错的。
40/44 这个偏移目前**只有 AMCar 补丁一条旁证**。

### 改动本身的风险

- **低**。默认 `stream`，`car` 一键回退；`VideoConfigSizeTest` 覆盖有/无视口两种头部；
  不传视口时 32..48 保持全 0，等于旧行为。
- **最坏情况不比旧行为差**：车机若不读 40/44，就退化成「只声明解码尺寸 = 码流尺寸」，
  这本来就是协议正确形态。
- **但比例对不上**：1280×720 (16:9) → 1412×654 (2.16:1) 是**非等比**缩放。media 层对未知比例
  的处理是 `keep input aspect ratio`（加黑边/裁剪），不是拉伸；再加 `badFramesThreshold=4`，
  即使视口被采纳也不保证出画。

### 主次关系：固件真正指向的是 SPS

- 4260 出画那次 SPS 就是 1412×654；3810 黑屏那次 SPS 是 1280×720 —— 同一固件包、同一接收端。
- 13:18 唯一成功的那轮，SPS 真为 1412×654、20fps 正常出帧。
- 视口分离只解决「贴到哪块矩形」，解决不了「解出来的比例不对」。所以它是**配套**，不是主修。

### 下一步顺序（别在 VideoConfig 上打转）

1. 上车先看两行：`VIDEO: ✓ VideoConfig 已发送 … 解码=1280x720 视口=(1412, 654)`、
   以及车机侧 `Failed to overtake the screen` / `SCREENSTATE_*`。
2. **A/B**：切 `videoconfig_canvas_mode=car` 重连一次。两种都黑 ⇒ 问题不在头部字段。
3. 主攻引擎定档：让 98311 在编码器创建前生效（`carlife_ordered_init`）、把 1412×654 注册成
   可用档、确认 `DISABLE_RESOLUTION_OPTION=1` 早于编码器创建。**以 SPS 解析值为唯一判据**
   （65544 只回显申请值，会撒谎）。
4. 只有车机侧进了 `VIDEOSTREAMING_ACTIVE` 却仍黑，才轮到怀疑解码/贴图，那时视口改动才有机会生效。

