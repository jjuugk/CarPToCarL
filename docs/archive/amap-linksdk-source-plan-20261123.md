# 高德 linksdk（非官方 AIDL）作为导航数据源：方案提案（未实施）

> **状态：📐 方案 / 调研，未实现、未引入任何新依赖。本文只作取证与决策依据，不是现行行为。**
> 现行导航数据源仍是 CarLife+ 引擎的 `0x10030`。
> 与同期的 [`amap-navi-sdk-source-plan-20261123.md`](amap-navi-sdk-source-plan-20261123.md)
> 对照：那份走官方 SDK（需 AAR/key 与技术服务许可），本文走 GitHub 开源实现的
> 高德地图 App 内部 linksdk 绑定（无 AAR、无 key，但有绑定权限与版本兼容风险）。
>
> 调研时间：2026-11-23。方式：GitHub 代码搜索 API（按用户给出的 AIDL descriptor 精确检索）+ 源码通读。

## 1. 一句话结论

linksdk 回调里的 `datas[].data`（`eventType` / `curManeuverID` / `segmentRemainDist` /
`nextRouteName` 等）**能一一映射**到本项目的 `CarLifeNaviNextTurn`，输出侧可完全复用
仪表 `0x5201/0x5202` 与 NowPlaying 两条现成链路；但**绑定能否成功取决于厂商白名单**，
不是纯代码问题，因此定位为**默认关的第二数据源 / 兜底**，不替换 CarLife `0x10030`。

## 2. GitHub 上的开源实现（按参考价值排序）

| 仓库 | 关键文件 | 价值 |
|---|---|---|
| `fw867/hypercar` | `app/src/main/java/io/github/hypercar/navigation/AmapLinkProbe.kt`、`LinkPayload.kt`、`AmapLinkNavigation.kt`、`docs/NAVIGATION-2026-11-08.md` | **最完整**：AIDL 反射调用、加密信封、分块拼包、导航状态机、转向码映射、实机绑定失败记录 |
| `SangLuoCN/OneStep4` | `app/src/main/java/com/sangluo/onestep/AmapNavigationClient.java` | Java 版绑定与字段解析，`parseNavigationState()` 字段表最清晰 |
| `LAOBILAXI233/onestep_on_hyper` | `app/src/main/java/com/hyper/onestep/AmapNavigationClient.java` | OneStep4 的 HyperOS 分支 |
| `caogenfunan123/seagull-desktop` | `app/src/com/seagull/carlauncher/AmapNavClient.java` | 另一套车机侧客户端 |
| `Heriyadi235/SyncPlay` | `app/src/main/java/com/xigaigai/syncglance/nav/AmapLinkClient.java` | 独立实现，可交叉验证协议 |
| `liligit1815/LS_Augment` | `android/app/src/main/java/ls/augment/com/AtomicWorkbenchAmapLinkClient.java` | 含 hook 与目标检查脚本 |

**命名差异**：用户给出的 `CarLifeNavAmap.kt` / `AmapLinkEnvelope.kt` 在 GitHub 公开代码里检索为 0 结果；
`AmapLinkPayload` 只有 2 个无关结果。等价物是 hypercar 的 `AmapLinkProbe`（绑定/轮询）、
`LinkEnvelope.decode`（解密）、`LinkPayload`（分块拼包）。本方案按这些等价物描述，不假设用户类名存在。

## 3. 建链协议（实证，来自 hypercar 源码 + 用户给出的要点）

```
bindService(
  action = "com.autonavi.minimap.service.NAVIGATION_SERVICE",
  component = ComponentName("com.autonavi.minimap",
              "com.amap.bundle.wearable.connect.glass.AmapLinkService"))
→ ILinkSdkControl (descriptor "com.amap.linksdk.aidldefine.ILinkSdkControl")
    transact 1 = authenticate()      → JSON {status:"success", data:{token:...}}
    transact 3 = registerListener()  → boolean accepted（写入 ILinkSdkCallback binder）
    transact 4 = unregisterListener()
→ execute(cmd=7 / cmd=5) 带 token + sessionId + requestId，约 1.25s 轮询请求结构化导航
```

回调（`ILinkSdkCallback`，descriptor `com.amap.linksdk.aidldefine.ILinkSdkCallback`）：

- `onTransact` code `1..3` → `data.readString()`，高德侧 calling uid 必须等于
  `com.autonavi.minimap` 的 uid（`Binder.getCallingUid()` 校验，否则拒绝）；
- 大包走 `onAmapCallbackWithFd(String metadata, ParcelFileDescriptor fd)`，
  metadata 是含 `dataLength` 的 JSON，读取 FD 流并按 `dataLength` 校验；
- 回调频次实证（hypercar 高德 17.02.0.2010 / Xiaomi M154F / HyperOS 3）：
  小包约 **3.01 秒/次**（256 → 512 → 1024 逐步升级），日志形态 `callback=256` 等。

## 4. 加密信封与分块

```
{"encrypted":true,"content":"<base64>"}
→ base64 解码
→ idSize + id(AAD) + keySize + key + 12B nonce + AES/GCM/NoPadding 密文
→ 明文 JSON：{"bizType":113, "datas":"[...]"}
```

- OneStep4 的 `decrypt()` 用 `Cipher.getInstance("AES/GCM/NoPadding")`，
  `SecretKeySpec(key,"AES")` + `GCMParameterSpec`，密钥长度非法直接抛 `Invalid AES key length`。
- hypercar 的 `LinkEnvelope.decode()` 对失败一律返回 null 并记 `callback envelope invalid bytes=N`，
  不打断链路；本项目应沿用「解析失败只丢这一帧」的容错策略。
- 大包 `begin_transfer` / `chunk_data` / `end_transfer`：hypercar `LinkPayload` 上限 **4 MiB**，
  `count in 1..128`，每块 base64 解码后按序号拼回；`raw.length <= MAX_BYTES*2` 防御。
- 只处理 `bizType:113`（导航业务），其余类型（GPS / auth / zoom 等）丢弃，避免刷屏与误判。

## 5. 数据字段 → 本项目映射

linksdk 明文 `datas[].data` 字段（OneStep4 `parseNavigationState` 实证）：

| linksdk 字段 | 含义 | → `CarLifeNaviNextTurn` | 输出 |
|---|---|---|---|
| `eventType` | 导航事件（开始/更新/结束） | `action` | 驱动开始/结束状态 |
| `curManeuverID` | 转向 ID（**高德 ManeuverIconID**） | `nextTurn` | → `Iap2RouteGuidance.publish()` → 仪表 0x5201/0x5202 |
| `segmentRemainDist` | 到当前路段终点距离（米） | `remainDistance` | 同上（0x5201 `0x0a`） |
| `routeRemainDist` / `routeRemainTime` | 全程剩余距离 / 时间 | `totalDistance`（**受 #46 约束**） | 不要直接改 0x07，见 §7 |
| `curRouteName` / `nextRouteName` | 当前 / 下条路名 | `roadName` | 同上 + NowPlaying 文案 |
| `speed` | 车速 | 暂无对应 | 忽略 |

第二条输出链路不用新建：`CarLifeHuClient` 已有 `navigationDisplay.receive(turn)` → `NavigationIcon`
的 NowPlaying 转向文字/箭头（开关 `navigation_now_playing`，默认关）。只要把同一个 `turn` 喂给它。

## 6. 转向码：第三套编码，必须独立映射

hypercar `AmapLinkNavigation.kt` 源码注释明确：

> Maneuver IDs follow AMap's ManeuverIconID, not CarLife TurnKind or CarPlay maneuver enums.

因此：

- 不能复用 `CarLifeNaviGuide.rawToIcon()`（那是 CarLife raw → 高德 ICON）；
- hypercar 内部有独立 `maneuvers` map（ManeuverIconID → CarPlay maneuver type），
  实施时从它的源码提取完整值表并逐项单测；
- 未知 ID 一律返回 `0`（NONE），沿用「宁可不画箭头也不猜方向」的既有策略
  （历史上 raw 7 被误当右后方，车机显示与画面相反）。

## 7. 三个必须守住的坑

1. **`0x07` 语义**：#46（未合）已指出 CarLife field 4 是「当前路段总距离」而非全程剩余，
   活跃更新帧不该填 `0x07`；`routeRemainDist` 虽接近全程剩余，但在 #46 落地前不改写入，
   避免与现行行为分叉。
2. **结束帧立即扇出**：导航结束 / 服务断开 → 立刻 `Iap2RouteGuidance.endRoute()` 补 `state=0`，
   不能只停心跳等车机 30s 陈旧兜底（会留 HUD 残影）。
3. **数据源互斥**：linksdk 与 CarLife `0x10030` 不能同时往 `Iap2RouteGuidance` 写快照；
   同一时刻只允许一个活跃源，CarLife 有数据时优先，linksdk 仅在 CarLife 无诱导时接管。

## 8. 绑定权限：真正的硬门槛

hypercar `docs/NAVIGATION-2026-11-08.md` 的三条路径实测结论：

| 路径 | 结果 |
|---|---|
| A. App 自己 `context.bindService(...)` | 被 MIUI `WakePathChecker` 拦截（`caller=null callee=com.autonavi.minimap`，包名不在目标应用白名单则拒绝） |
| B. shell 里用 Context 绑定 | `resolveService` 成功，但 `bindService` 返回 false（在 `ApplicationThread` 被拒） |
| C. IActivityManager 跳过 | 仍未打通（`am-bind=ERR no-transaction-code`，Android 17 接口名变化） |

成功条件（HyperOS 3 实测）：开启「目标应用白名单」+ shell
`cmd appops set io.github.hypercar 10022 allow`（iOPower / 关联启动类），且高德未运行时绑定失败
需按 3/10/30s 退避重绑。**本项目未 root**，因此：

- vivo V2415A（OriginOS）、OPPO PLP110（ColorOS）是否有同类拦截**完全未验证**；
- 方案必须把绑定做成可插拔、失败静默退回，不能成为连接主链路的前置条件；
- 首次验证应在真机上跑一次「只绑定 + 打印回调形状」的诊断模式，不做任何发送。

## 9. 接入点与开关

| 位置 | 改动 |
|---|---|
| 新 `navigation/AmapLinkClient.kt` | bind / auth / register / execute 轮询 + 回调接收，失败退避 |
| 新 `navigation/AmapLinkEnvelope.kt` | AES-GCM 信封解密（base64 → id(AAD)+key+nonce+密文） |
| 新 `navigation/AmapLinkPayload.kt` | 分块拼包，上限 4 MiB / 128 块 |
| 新 `navigation/AmapNaviSnapshot.kt` | 纯数据类，隔离高德 JSON，便于单测 |
| 新 `navigation/AmapNaviInfoMapper.kt` | snapshot → `CarLifeNaviNextTurn`，ManeuverIconID 映射表 |
| 新 `navigation/NaviSource.kt` | 可插拔数据源接口；与 CarLife `0x10030` 互斥 |
| `SessionController.kt` | 数据源选择 + 默认关开关 `amap_link_navi_source` |
| `Iap2RouteGuidance` / `navigationDisplay` | **不改**，继续作为唯一输出口 |

开关默认关；绑定失败、解密失败连续超阈值、模拟导航 → 静默退回 CarLife 源并限流记日志。
不引入车型 profile，门控只按「数据源可用性」。

## 10. 合规与风险边界

- linksdk 是高德 App 内部 AIDL，非公开 SDK：接口、加密格式、字段含义随版本变化
  （参考实现验证于高德 17.02.0.2010），不能当作稳定契约；
- 绑定依赖厂商白名单 / 关联启动权限，跨机型不可靠，且可能需要 shell 权限——
  与项目「未 root」前提冲突，实施前必须先确认目标机型能否纯 App 绑定；
- 与官方 SDK 一样存在未授权使用风险（虽无 key / AAR），对外发布前需评估；
- 因此本方案阶段只做纯数据解析与映射（零绑定、零网络、零 SDK），
  真机绑定验证作为独立的、用户显式开启的实验开关。

## 11. 验证清单（实施时）

- 单测：信封解密（正例 / 密文损坏 / key 长度非法）、分块拼包（乱序 / 缺块 / 超限）、
  ManeuverIconID 全值表与未知值、字段映射到 `CarLifeNaviNextTurn`、结束帧 state=0、
  两数据源互斥切换。
- 构建：不引入新依赖时 `:app:testDebugUnitTest` 与 `assembleDebug` 通过，lint new=0，合规守卫通过。
- 真机：先诊断模式（只绑定并打印回调形状，不发送）；确认绑定成功后才开启注入到车机，
  再验证箭头方向、距离递减、结束清屏、NowPlaying 与 CarLife 视频不冲突。
