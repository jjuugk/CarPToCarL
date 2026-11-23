# OcarPlay 0.8.51 对照：它的 CarPlay 连接与画面

> 2026-11-08 · 分析对象 `io.carplus.rootless.prototype` 0.8.51（`C:\Users\jjuugk\Downloads\OcarPlay.apk`，562 KB）
>
> 方法：`aapt2` 取清单 → 解析 `classes.dex`（566 类 / 6209 字符串）→ `androguard` 读方法级指令流。
> 没有可用的 Java 源码（jadx 1.5.1 对这个 dex 解析卡死，`--single-class` / `simple` 模式都超时），
> 所以下面每条结论都标注了**依据强度**：`[代码]` = 指令流里读到；`[字符串]` = 只有日志常量。

## 一、它是什么

**CarPlay 接收端（车机角色）**，不是发送端——和我们的方向正好相反：

- 广播 `_airplay._tcp`，起 RTSP server（`RTSP/1.0`、`SETUP`/`RECORD`/`TEARDOWN` 全在）；
- 做 accessory 侧配对：`/pair-setup`、`/pair-verify`、`MFiSAP`、`Accessory pair-setup signature invalid`；
- 收 iPhone 的加密 H.264，**再用 CarLife 引擎编码上屏**——`secured/t4->x()` 里发 **98386**，
  payload 键是 `DISABLE_RESOLUTION_OPTION` + `CONTENT_ENCRYPTION` `[代码]`。

所以它同时是「CarPlay 收端」和「CarLife 发端」，画面这一段对我们镜像对称，连接这一段是车机视角。

## 二、连接侧：它比我们多做的事

| 它的日志/机制 | 含义 | 依据 |
|---|---|---|
| `RELEASE_SESSION_BLOCKED no_fresh_integrity_permit` + `Release verification required before starting a new session` | 每次新会话必须有一次**新鲜的完整性校验**，不允许拿上一轮的结果复用 | `[字符串]` |
| `CURRENT_WIFI_REUSE verified_previous_transport=true fresh_authentication_required=true network_switch=false` | 复用已选 WLAN 时**只复用网络**，认证必须重做；同时记下 transport 来源（`bluetooth` / `retained_wifi` / `selected_wifi`） | `[代码]`（onStartCommand 里 putString 那支） |
| `NETWORK_IAP_ROUTE_SELECTED transport=CONTROL_POST trigger=AUTHENTICATED_PEER fresh_link_proof_required=true` | 网络 iAP2 路由只在**对端已认证**之后才选，且要求新鲜链路证明 | `[字符串]` |
| `IAP2_CARPLAY_AVAILABILITY_REANNOUNCE attempt=` | 可用性重播带重试计数（不是盲发一次） | `[字符串]` |
| `MFiSAP accessory leaf fingerprint differs from the normally paired accessory` | 叶子证书指纹变了就单独告警（防中间人/换机） | `[字符串]` |

## 三、画面侧：它的做法

### 3.1 三个尺寸分开，且做一致性判定 `[代码]`

`secured/j->a(sps, w, h)` 拼的诊断串：

```
profile_idc=… constraints=… level_idc=… coded=WxH visible=WxH declared=WxH dimensions_match=true/false sps_metadata=…
```

- `coded` = 宏块对齐后的编码尺寸，`visible` = 去掉 frame_cropping 的真实画面；
- `dimensions_match` 比较的是 **visible vs declared**（不是 coded）；
- 我们 `parseSpsDimensions` 也已经处理 cropping，但只输出一个值、没有和「声明值」并列判定。

### 3.2 不猜画布，等实测 IDR `[代码]`

`secured/i0->u2()` 的顺序：

```
WAITING_ACTUAL_NATIVE_IDR_AFTER_CONNECTION
→ ACTUAL_NATIVE_IDR_ACCEPTED          （真的收到 native IDR 才继续）
→ ACTUAL_NATIVE_AVC_CONFIG_SENT       （用这份 IDR 的 SPS/PPS 组 avcC）
→ CARPLAY_VIDEO_CONFIG width=… height=… encrypted=true
```

它**不在连接后立刻发 config**，也不拿「申请值/占位值」当声明——声明用的是编码器实际输出
（`i0->W1(g, w, h)`，下限 1、上限 4096，超界直接 `encoded video dimensions outside supported bounds`）。

### 3.3 画布策略是 5 档 + 未确认态 `[代码]`

`WirelessCarPlayService->x1()` 里选 `policy=`：

| policy | 触发条件（从分支读） |
|---|---|
| `NATIVE_DISPLAY_MATCH` | 源尺寸与原生显示一致 |
| `ESTABLISHED_SOURCE_POLICY` | 有已建立的源策略，沿用它 |
| `ALIGNMENT_CANVAS_ENCODER_UNCONFIRMED` | 想做对齐但**编码器还没确认** |
| `LEGACY_CANVAS_ENCODER_UNCONFIRMED` | 只能走旧画布，编码器同样未确认 |
| `AUTHENTICATED_RECEIVER` | 对端已认证且宽高与目标一致 |

配套 `fit_mode=GEOMETRY/STRETCH/FIT`。关键不是档位名字，而是**「未确认」是一等状态**：
拿不到实测就标 UNCONFIRMED，等 `VIDEO_INIT_DONE` 再定，而不是一次性赌一个值。

### 3.4 触摸几何随源尺寸失效 `[代码]`

`secured/i0->s2(q6)`：

```
源宽高与上次不同 → TOUCH_GEOMETRY_RETIRED source_dimensions_changed=true → 直接 return（不应用）
几何对象变了     → L1() 清旧的 → TOUCH_GEOMETRY_APPLIED source=… canvas=… viewport=…
```

**源尺寸一变，旧的触摸映射立刻作废**，绝不拿旧比例继续算下一笔触摸。

### 3.5 其他

- `AVC resume parameters must precede incoming video`、`VIDEO_EPOCH_WAITING_IDR`：参数集必须早于码流，
  换 epoch 后等新 IDR；
- `VIDEO_BACKLOG_TRIMMED_AT_IDR`：背压裁剪**只在 IDR 处**做，不会把 GOP 切碎；
- 只接受 SPS profile `66/77/88`（Baseline/Main/Extended），其它直接 `UNSUPPORTED_PROFILE`，
  High(100) 都不收 `[代码]`。

## 四、对照我们

| 事项 | OcarPlay | CarPToCarL | 结论 |
|---|---|---|---|
| 98386 带 `DISABLE_RESOLUTION_OPTION` | 有 | 有（`CarLifeProtocol.featureConfig`） | 持平 |
| 98386 带 `CONTENT_ENCRYPTION` | 有 | **没有** | 可试 |
| 声明尺寸来源 | 编码器实际输出（等真实 IDR） | 引擎上报值（`CarLifeHijack.videoSize`）→ 构造值 → SPS | **可改** |
| coded/visible/declared 三值诊断 | 三值 + match | 只打 SPS 值与声明值的对比 | 小补 |
| 未确认态 | policy 里显式 UNCONFIRMED | 无（策略是静态开关） | 中长期 |
| 触摸几何失效 | 源尺寸变 → retire | 触摸直接透传引擎，映射由引擎算 | 不需要 |
| 背压/停摆 | 裁剪只在 IDR | 已做分级停摆干预 | 持平 |

## 五、建议（按性价比）

1. **声明值优先用 SPS visible**（当解析成功且尺寸合理时），引擎上报值只作兜底。
   理由：`65544` 只回显申请值、会撒谎；SPS 才是车机解码器看到的唯一事实。这也正是它
   `dimensions_match` 检查的对象。风险：若 SPS 与画布差异大，仍会由车机侧缩放（与现在同）。
2. **补 coded/visible/declared 三值日志**，把「声明是否等于 SPS visible」变成一行可判读的结论。
3. **98386 里加 `CONTENT_ENCRYPTION=1`**（与 `DISABLE_RESOLUTION_OPTION` 同批），验证引擎是否会
   改变加密行为——我们现在按 `flags=0x04` 声明加密帧，但没告诉引擎要加密。
4. 中长期：把 `VideoCanvasMode` 的三档静态开关升级成「按实测自动选 + 未确认态」。

## 六、已落地（2026-11-08 第二轮）

按「只复用网络、失败两次后认证必须重做，做 2、3」执行：

### 6.1 认证：复用网络 ≠ 复用认证

- 参考它的 `CURRENT_WIFI_REUSE verified_previous_transport=true
  fresh_authentication_required=true network_switch=false` 与
  `RELEASE_SESSION_BLOCKED no_fresh_integrity_permit`：复用的只是 L2，
  认证一律重来，新会话还要求一次「新鲜」的完整性校验；
- 我们这边可继承的只有 `PairingMemory`（「车机确定性拒绝配对，15 分钟内别再试」）。
  新增 `FreshAuthPolicy`：**同一接收端连续 2 轮未建立会话 → 下一轮清掉该记忆、
  强制走完整 pair-setup**；成功一轮即归零，换接收端（key 变）也归零，用户取消不计入；
- 日志：复用系统热点时打「只复用网络，认证不继承」，重做时打 `AUTH: … 本轮强制重做认证`，
  触发时打 `RETRY: … 下一轮认证重做`。三行合起来才能在日志里证明「没有沿用上一次结论」。

### 6.2 画面：coded / visible / declared 三值诊断

- `parseSpsDimensions` 现在返回 `SpsSize(coded, visible)`：
  `coded` = 宏块对齐（1424×656 那层），`visible` = 去 `frame_cropping` 的真实画面（1412×654）；
- 每次发 VideoConfig 都打一行：
  `VIDEO: 尺寸 coded=1424x656 visible=1412x654 declared=1412x654（宏块对齐裁剪 12x2）· ✓与 visible 一致 · csd来源=…`
  判据按它的口径改成 **declared vs visible**（此前只拿 visible 当「SPS」跟声明比，看不出
  对齐层与画面层的区别）；
- 解析失败 / visible 超范围（320..4096 × 180..2304）/ 不一致各有一种明确措辞，都带 ⚠。

### 6.3 98386 加 `CONTENT_ENCRYPTION=1`

- 与 `DISABLE_RESOLUTION_OPTION`、`MEDIA_LYRIC_SUPPORT`、`I_FRAME_INTERVAL`、`CONNECT_TYPE`
  同批下发（清单从 4 项变 5 项，条数字段同步）；
- 单测钉死「条数 = 清单长度」与「关掉时该项仍在、值为 0」；日志文案同步加
  `CONTENT_ENCRYPTION=1 内容加密`。

### 6.4 没做的

- 建议 1（声明值优先取 SPS visible）**未做**：它改变的是车机建解码器的尺寸契约，
  属于要实车 A/B 的改动，等这一版过去再单独一版。
