# 高德导航数据源（手机高德 map linksdk · 已实施）

> **状态**：✅ **P0/P1/P2 已实施**（2026-11-24）。数据源实测跑通，已接入仪表 `0x5201/0x5202`，
> 并带开关「使用手机高德地图获取导航数据」**替换** CarLife `0x10030`。P3（NowPlaying 文字/箭头）未做。
> **一句话**：手机高德地图的 `AmapLinkService` 能在不改任何系统设置的前提下返回真实导航诱导数据
> （1Hz，字段完整），映射成 `CarLifeNaviNextTurn` 后复用车机仪表链路，
> 不需要 API Key、不需要 CarLife 引擎、不需要 root。
>
> **实测证据**：2026-11-24 独立探针在荣耀 PTP-AN10 + 高德 17.02.0.2010 上通过
> （`.tmp/amap-link-probe/`，APK `dist/AmapLinkProbe-1.0-debug.apk`）。
> 全链路 `bindService` → `authenticate success` → `registerListener=true` → `execute cmd=7/5 success`
> → 每秒一帧 `type=4`，`segmentRemainDist` 随行驶从 81m 走到 62m。
>
> **关联文档**：archive 里两份调研（[`amap-linksdk-source-plan-20261123.md`](../archive/amap-linksdk-source-plan-20261123.md)、
> [`amap-navi-sdk-source-plan-20261123.md`](../archive/amap-navi-sdk-source-plan-20261123.md)）
> 只作取证；本文结论取代其「需自实现分块/AES/轮询」三条判断。

## 零、代码位置（改之前先看这里）

| 文件 | 职责 | 是否可单测 |
|---|---|---|
| `amap/AmapLinkClient.kt` | 绑定·认证·注册·回调分发·FD 读取·信封兜底解密·JSON 解析 | 否（Android/Binder） |
| `amap/AmapNaviSnapshot.kt` | `type=4` 的纯数据快照 | — |
| `amap/AmapNaviMapper.kt` | maneuver → CarLife ICON、字段映射、路名清洗 | **是** |
| `amap/AmapNaviStateMachine.kt` | 三态、2s 迟到帧、15s STALE | **是** |
| `amap/AmapNaviSource.kt` | 开关镜像、生命周期、节拍、自检文案 | 否（有 Handler） |
| `app/src/test/.../amap/AmapNaviMapperTest.kt` | 20 例覆盖映射表与状态机 | — |

接线点（都在 `SessionController`）：
- `init` 里 `AmapNaviSource.onEmission = ::onAmapNaviEmission`（进程级常驻）；
- `ensure()` 里 `AmapNaviSource.configure(...)` + `setEnabled(prefs)`；
- CarLife 的 `addNaviNextTurnListener` 开头拦截：`if (AmapNaviSource.isReplacingCarLife()) return`。

UI：设置面板「使用手机高德地图获取导航数据」开关（`switchAmapNaviSource`）+「查看高德数据源自检 ›」。
偏好键 `amap_navi_source`（`DisplayPrefs.amapNaviSource`）。

## 零-1、开关语义：替换，不是兜底

开关打开 → 仪表只认高德，CarLife `0x10030` 不再编码成 `0x5201/0x5202`；关掉 → 完全回到原行为。
**不做自动切换**：两路同时推会让 HUD 箭头互相顶，且"这次为什么是 A"无法排查。
`0x13` 来源名写「高德地图」而不是「CarLife」，车机和日志都能分清这帧是谁给的。

---

## 一、实测结果：四条原假设被推翻

调研阶段基于参考实现推断的形态，实测有四处不同，直接影响工作量（都是**变简单**）：

| 原假设 | 实测 | 影响 |
|---|---|---|
| 回调是 `{"encrypted":true,"content":base64}` 加密信封 | **明文直出** `{"datas":"[...]","bizType":113}` | AES-GCM 只作兜底，主路径不需要解密 |
| 大包走 `begin_transfer`/`chunk_data`/`end_transfer` 分块 | FD 直读到 EOF 即可（实测单帧 3.0–3.8KB） | **不必实现分块协议**，只留 4MiB 上限保护 |
| 需要 `execute(cmd=7/5)` 1.25s 轮询 | 注册后高德**主动每秒推一帧**，`execute` 各调一次即可 | 不需要轮询定时器 |
| MIUI `WakePathChecker` 类拦截可能阻止绑定 | 荣耀（hihonor）**不拦截**，`bindService=true` 一次成功 | 打通概率高于预期，但仍需机型自检 |

**结论**：原方案里最重的三块（分块拼包、AES 解密、轮询调度）都可以降级为「兜底/不实现」，
真正的核心工作量只剩 **绑定与认证、`type` 分发、字段映射** 三件事。

## 二、协议形态（实测确定的部分）

### 2.1 绑定与握手

```
包名      com.autonavi.minimap
组件      com.amap.bundle.wearable.connect.glass.AmapLinkService
action    com.autonavi.minimap.service.NAVIGATION_SERVICE
控制接口  com.amap.linksdk.aidldefine.ILinkSdkControl
回调接口  com.amap.linksdk.aidldefine.ILinkSdkCallback
```

事务号（实测有效）：`1=authenticate` · `2=execute` · `3=registerListener` · `4=unregister`。

握手顺序（实测一次通过，无重试）：
`bindService` → `authenticate`（应答 `status=success`，token 在 `data.token`，**不落盘不打日志**）
→ `registerListener(callback)`（应答 `true`）→ `execute(cmd=7)` / `execute(cmd=5)`（均 `status=success errorCode=0`）。

> 注册成功 ≠ 有诱导数据。用户必须**真的在高德里开始导航**，否则只有空间/心跳类帧。

### 2.2 回调两条通道

- `code=1`：`onAmapCallback(String)`，纯文本。
- `code=2`：`onAmapCallbackWithFd`，先 `String metadata`、再 `int` 空标记、再 `ParcelFileDescriptor`。

**必须 `data.enforceInterface(CALLBACK)` 后才读第一个字段**。漏这句会让第一个 int 读到 Binder
描述符长度，`readString()` 返回 null 并把游标推到末尾（诊断特征：`avail=312 flag=-1 meta=null`），
整包读不出。这是本次实测踩到的坑，写死在实现里。

另外：注册瞬间会有 2 次 `code=1079135572` 的探测调用（不是我们的接口），
`enforceInterface` 抛 `SecurityException` 后直接 return false，不要当异常刷屏。

### 2.3 `type` 矩阵（决定状态机）

`bizType` 恒为 `113`，要点在 `datas[].type`：

| type | 含义 | 是否参与引导 |
|---|---|---|
| **4** | 导航诱导主帧（转向/距离/路名） | **是，核心** |
| 5 | 新转向提前通知（`maneuverID`） | 是（仅 state=1 时用） |
| 14 | 车道信息（`laneCount`/`backLane`/`frontLane`） | 可选，一期不做 |
| **100** | **导航暂停/停止** | **是，清帧依据** |
| **200** | **到达（`isArrived=true`）或手动退出** | **是，清帧依据** |
| 2 / 7 / 13 / 16 / 18 / 20 / 21 / 22 / 23 / 27 | 路线、模式、定位、缩放、限速等旁路帧 | 否，一期忽略 |

**关键认知**：`type=4` 里的 `eventType` **不是** start/update/end 三态，
真正的中止信号是 `type=100` / `type=200`。不要用 `eventType` 做结束判定。

### 2.4 单位与语义（容易搞错的三个）

- `segmentRemainDist` = **到当前段末/下一个动作的距离**（喂 `remainDistance`）。
- `routeRemainDist` = **全程剩余里程**（≠ 上一个，约 19km 量级）。
- `speed` 停车时为 `-1` 或 `0`，不是错误值。
- 坐标 `matchPosLon/Lat` 是 **GCJ-02 火星坐标**，不得当 WGS-84 用。

## 三、`type=4` 字段清单（实测样本）

```json
{
  "eventType": 1, "pathID": 2525873134,
  "routeTotalDist": 18965, "routeRemainDist": 18910, "drivenDist": 55,
  "driveTime": 106, "routeRemainTime": 1449,
  "curRouteName": "银新东一路", "nextRouteName": "上海路",
  "segmentRemainDist": 81, "speed": -1,
  "curManeuverID": 2, "curLinkRoadClass": 8, "curLinkFormway": 15,
  "routeRemainLightCount": 8, "routeRemainIntersectionCount": 10,
  "matchPosLon": 109.160556, "matchPosLat": 21.425315,
  "viaInfo": {...}, "endInfo": {...}, "laneMatchResult": {...},
  "tunnelNaviInfo": {...}, "indoorNaviInfo": {...}
}
```

一期只用 5 个：`curManeuverID` / `nextRouteName` / `segmentRemainDist` /
`routeRemainDist` / `curRouteName`。其余字段留档备后用（红绿灯数、隧道、车道）。

## 四、转向码映射：值域重合，但只到 12

项目里 `CarLifeNaviNextTurn.nextTurn` 用的就是「高德 ICON 转向码」
（`CarLifeNaviGuide.kt:49` 注释明确写了 "= b8/a.smali 枚举实例的 .a 字段值"）。
linksdk 的 `curManeuverID` 是 **AMap ManeuverIconID**。两者前 12 项**数值重合**：

| `curManeuverID` | 含义 | → `CarLifeNaviGuide` 常量 | 是否恒等 |
|---|---|---|---|
| 2 | 左转 | `LEFT_TURN` (2) | ✅ 直接 |
| 3 | 右转 | `RIGHT_TURN` (3) | ✅ 直接 |
| 4 | 向左前方 | `LEFT_FRONT` (4) | ✅ 直接 |
| 5 | 向右前方 | `RIGHT_FRONT` (5) | ✅ 直接 |
| 6 | 向左后方 | `LEFT_BACK` (6) | ✅ 直接 |
| 7 | 向右后方 | `RIGHT_BACK` (7) | ✅ 直接 |
| 8 | 掉头 | `U_TURN_LEFT` (8) | ✅ 直接 |
| 9 | 直行 | `STRAIGHT` (9) | ✅ 直接 |
| 11 | 进入环岛 | `ROUNDABOUT_ENTER` (11) | ✅ 直接 |
| 12 | 驶出环岛 | `ROUNDABOUT_EXIT` (12) | ✅ 直接 |
| 19 | 掉头（另一取值） | `U_TURN_LEFT` (8) | ⚠️ 需归并 |
| 20 | 顺行 | `STRAIGHT` (9) | ⚠️ 需归并 |
| 1 | 未确证（疑自车/起点） | 待定 | ❓ 实测确认 |
| 其他 | — | `0`（NONE） | 未知不猜 |

**但"值域重合"是推断，不是实证**：重合来自两边都自称"高德 ICON 码"，
本次探针只验证了 `curManeuverID=2` 出现在"银新东一路 → 上海路、前方 81m 左转"这一帧，
**没有在车机上确证箭头方向**。落实现时映射必须是独立函数
`AmapManeuver.toCarLifeIcon(id)`，附单测覆盖全表 + 未知返回 0，
**禁止直接写 `nextTurn = curManeuverID`**（一旦哪天高德改号，直通写法会静默画错箭头）。
首批实车必须逐一比对：左转/右转/直行/掉头/环岛进出 五种，各截图存档。

## 五、架构：三层 + 复用两条现成链路

```
amap/AmapLinkClient         绑定·认证·注册·回调分发·FD 读取·信封兜底解密
        │  JSON (type=4)
        ▼
amap/AmapNaviMapper         type 分发 + 字段清洗 + maneuver 映射（零 Android 依赖，可单测）
        │  CarLifeNaviNextTurn
        ├──────────► Iap2RouteGuidance.publish(info)      ← 链路 A：仪表 0x5201/0x5202
        └──────────► NowPlaying 转向文字/箭头              ← 链路 B：需小改造（见 5.2）
```

### 5.1 链路 A：仪表（零改动）

`Iap2RouteGuidance.publish(info: CarLifeNaviNextTurn, sourceName)`（`Iap2RouteGuidance.kt:111`）
直接吃这个数据结构，`toSnapshot()` 内部用 `appleManeuverType(nextTurn)` 转 CarPlay 类型、
用 `label(nextTurn) + road` 拼文字。**不需要新协议、不需要改 iAP2 编码**。

调用方要复制的四步（照 `SessionController.kt:683-710` 的样板）：

```kotlin
if (!isSessionRunning()) return
if (Iap2RouteGuidance.blockedByDeclaration()) return        // 车机可收清单无 0x52xx
Iap2RouteGuidance.publish(info, sourceName = "高德地图")
pushRouteGuidanceAll()                                       // 扇出 WiFi/BT 三条链路
refreshRouteGuidanceHeartbeat()   // 或结束分支：endRoute() + stopRouteGuidanceHeartbeat()
```

三个必须有、容易漏的点：
- **`blockedByDeclaration()` 守门**：车机声明不收 0x52xx 时一个字节都不发（连心跳都不启）。
- **结束帧必须当场扇出**：`endRoute()` 之后立刻 `pushRouteGuidanceAll()`，不能只停心跳等 30s 兜底。
- **停止心跳**：结束走 `stopRouteGuidanceHeartbeat()`，否则 10s 空转。

### 5.2 链路 B：NowPlaying 转向文字/箭头（需小改造）

现状：`SessionController.kt:6348`
`created.nowPlayingOverride = { navigation.displayOverride(CarLifeHijack.navigationDisplay()) }`
—— 提供者是 `CarLifeHijack.navigationDisplay()`（`CarLifeHijack.kt:170`），
它要求 `CarLifeHuClient` 实例存在且 `isRunning`，**纯 iAP2 会话拿不到**。

改造方案（不破坏 CarLife 路径）：
1. 把"导航显示提供者"抽成函数注册表，CarLife 与 amap 各注册一个，**CarLife 优先**。
2. `NavigationDisplay` 从 `CarLifeHuClient` 成员提升（或新增一个 amap 专用实例），
   供 `HudTurn` 复用：`HudTurn(action, turn, road, distance, icon)`（`media/NavigationDisplay.kt:5`）——
   `turn` 是 **CarLife raw 码**，amap 侧要给 `rawToIcon` 的**反向值**或直接复用已映射的 ICON，
   实现时需新增一个 `iconToHudRaw()`，不要硬塞。
3. 开关仍复用 `DisplayPrefs.KEY_NAVIGATION_NOW_PLAYING`（`DisplayPrefs.kt:55`），
   不额外加第二个用户可见开关。

**取舍（已按此执行）**：链路 B 依赖 CarLife 会话存在，且要改 `NavigationDisplay` 的既有语义，
所以本次**只做链路 A**。仪表能拿到转向/路名/距离，已经覆盖这个开关的核心诉求；
NowPlaying 的转向文字留给 P3，动之前要单独评估它和 `navigation_now_playing` 开关的叠加行为。

## 六、状态机与清帧

```
注册成功 ──► IDLE ──type=4──► GUIDING ──type=4 连续──► GUIDING
              │                 │
              │                 ├── type=100 ──► STOPPED（清帧）
              │                 ├── type=200 +isArrived ──► ARRIVED（清帧，可显示"已到达"）
              │                 └── 15s 无 type=4 ──► STALE（清帧）
              └── 绑定失败/断开 ──► IDLE（重试退避）
```

四条铁律：
1. **结束事件后 2s 内的迟到 `type=4` 必须丢弃**（高德会补发最后一帧，会把清除帧顶掉）。
2. **15s 无新帧即清箭头**，不等车机 30s 兜底。
3. **`segmentRemainDist` 变小或 maneuver 变化才算"新动作"**，避免每秒刷箭头闪烁。
4. **进程/连接断开也要补清帧**（照 `onCarLifeNavSourceDisconnected()` 的判据，
   `SessionController.kt:723`），但只在"本轮确实发过 amap 诱导"时清，防止清掉别人的 HUD。

## 七、与 CarLife `0x10030` 的关系：开关二选一

**绝不同时发**——同一个 HUD 两个数据源会互相顶掉，箭头跳变，而且"这次为什么是 A"无法排查。

已实现的规则（**开关决定谁上仪表，不是自动择优**）：

```
开关「使用手机高德地图获取导航数据」打开
    → CarLife 的 addNaviNextTurnListener 第一行就 return，0x10030 不上仪表
    → 只有 AmapNaviSource 在 publish，sourceName = "高德地图"
开关关闭
    → AmapNaviSource 不发送（连绑定都不做），CarLife 一手包办，与改动前完全一致
```

推翻原先"CarLife 优先、amap 兜底"的设计：那种写法在用户只用高德导航时永远轮不到 amap
（CarLife 引擎只要还连着就会推诱导），而用户打开这个开关的意图就是"我就要高德的数据"。
显式开关 + 严格二选一，行为才是确定的。

代价要知道：**开关打开且高德没在导航时，仪表不会显示任何转向**（CarLife 那份被拦了）。
这是有意为之——自检面板会明确写"已注册但未收到数据：请在高德地图里开始导航"。

## 八、开关、自检与回退

- 开关：`amap_navi_source_enabled`，**默认关**，位置与 `navigation_now_playing` 同级（`DisplayPrefs`）。
- 门控：高德未安装 / 版本不在白名单（首版白名单只放 `17.02.0.2010`）→ 直接不启用。
  版本号从 `PackageManager.getPackageInfo().versionName` 取，**不要用包名存在与否判断**。
- 绑定失败：3s / 6s / 12s 三次退避重绑，之后每 60s 一次；全程静默，只在日志留一行。
- **自检面板**：设置里加"高德数据源自检"，一键跑
  `绑定→认证→注册→等 10s→报收到几帧/有无 type=4`，输出结论行
  （`可绑定` / `绑定被拒（厂商拦截）` / `已绑定但无诱导（未开始导航）`）。
  这是排障关键——否则用户只会看到"没反应"。
- 回退：任何异常（绑定失败、解密失败、字段缺失、映射未知）都不抛，只丢帧并计数，
  **绝不影响 CarLife 与 iAP2 主链路**。

## 九、实施状态

| 期 | 内容 | 状态 |
|---|---|---|
| **P0** | `AmapLinkClient` + `AmapNaviMapper` + 开关 | ✅ 已完成（含转向上报与节流日志） |
| **P1** | 接仪表 `0x5201/0x5202`，开关替换 CarLife `0x10030` | ✅ 已完成 |
| **P2** | `type=100/200` 清帧、2s 迟到帧丢弃、15s STALE、自检面板 | ✅ 已完成 |
| **P3**（可选） | NowPlaying 转向文字/箭头 | ⬜ 未做（要动 `NavigationDisplay` 语义，押后） |

**唯一还没做的验证：实车比对转向方向。** 代码侧的 20 例单测只能证明"映射函数按表执行"，
证明不了"这张表与高德界面一致"。装车后必须逐一确认：左转 / 右转 / 直行 / 掉头 / 环岛进出，
各看一次仪表箭头。若某个方向画反，改 `AmapNaviMapper.toCarLifeIcon` 的一行即可，
**不要动状态机和发送侧**（那部分与转向无关）。

## 十、测试计划

单测（零 Android 依赖，可直接在 `app/src/test`）：
- `AmapNaviMapper`：全表 maneuver → ICON；未知码 → 0；缺失字段 → 丢帧；`speed=-1` 不报错。
- 状态机：`type=4` 连续帧合并、`type=100/200` 清帧、结束 2s 内迟到帧丢弃、15s STALE。
- 优先级：CarLife 有帧时 amap 被抑制；CarLife 静默 5s 后 amap 接管。
- 回归：现有 `Iap2RouteGuidance` 用例全绿（不改它的语义）。

实车：
1. 高德开始导航 → 日志出现 `type=4` 且 `segmentRemainDist` 递减。
2. 仪表箭头方向与高德界面一致（左/右/直/掉头/环岛各一次）。
3. 高德退出导航 → 2s 内 HUD 清除。
4. CarLife 导航同时开启 → 不打架（CarLife 优先）。
5. 关开关 → 完全无行为，日志无 amap 相关行。

## 十一、风险清单

| 风险 | 影响 | 对策 |
|---|---|---|
| 高德升级改 AIDL / 字段 / type | 静默失效 | 版本白名单 + 自检面板 + 失败静默回退 |
| 厂商拦截 `bindService`（MIUI 已知） | 该机型不可用 | 自检面板明确报"绑定被拒"，不假装能用 |
| `curManeuverID` 与 CarLife ICON 实为两套 | 箭头画反（左转画成右后方） | 独立映射函数 + P0 实车比对，禁止直通赋值 |
| GCJ-02 被当 WGS-84 | 位置类功能偏移数百米 | 一期不用坐标；要用必须先转 |
| 与 CarLife 双源互顶 | HUD 抖动 | 严格互斥优先级，不同时发 |
| 高德私有 Service 非公开接口 | 合规/稳定性 | 只读本地 App 服务、不破解、不分发数据；文档注明非官方接口 |

## 十二、边界（刻意不做的事）

- **默认关**。开关不打开时连高德的包都不查，CarLife 路径一个字节不变——不改变任何已跑通车机的默认行为。
- 不引入任何车型 profile（与"通用协议对齐"方向一致）。
- 不实现分块传输协议（实测 FD 直读即可），只留 4MiB 上限保护。
- 不把 AES-GCM 解密当主路径（实测明文），仅保留兜底函数。
- 不用 `eventType` 做三态判定。
- 不碰 `NavigationDisplay` / NowPlaying（那是 P3，会改变 NowPlaying 的既有语义）。
- 不把 amap 的坐标（GCJ-02 火星坐标）写进任何现有字段。
- 不做"两路自动择优"，只做开关二选一（理由见第七节）。

---

**取证位置**：
- 探针源码 `.tmp/amap-link-probe/`（`ProbeActivity.java`，含实测踩坑注释）
- 实测日志 `/sdcard/Android/data/com.example.amaplinkprobe/files/probe.log`
- 样本 `.tmp/amap-samples.jsonl`（20 条明文，含完整 `type=4`）
- 参考实现 `fw867/hypercar`（`AmapLinkProbe.kt` / `AmapLinkNavigation.kt` / `LinkPayload.kt`）、`SangLuoCN/OneStep4`（`AmapNavigationClient.java`）
