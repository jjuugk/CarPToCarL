# 高德官方导航 SDK 作为导航数据源：方案提案（未实施）

> **状态：📐 方案 / 调研，未实现、未引入任何 SDK 或 key。本文只作取证与决策依据，不是现行行为。**
> 现行导航数据源仍是 CarLife+ 引擎的 `0x10030`（见 [`../current/PROTOCOL-NOTES.md`](../current/PROTOCOL-NOTES.md)
> 与 [`../current/PROJECT-GUIDE.md`](../current/PROJECT-GUIDE.md) §4）。
>
> 依据：高德官方《导航实时数据获取》<https://developer.amap.com/api/android-navi-sdk/guide/navigation-map/navi-info>
> 与 `NaviInfo` 类文档（2026-11-23 读取）。

## 1. 一句话结论

官方 SDK 的 `NaviInfo` 字段能**一一映射**到本项目的 `CarLifeNaviNextTurn`，输出侧可**完全复用**
现有的仪表 `0x5201/0x5202` 与 NowPlaying 两条链路，不需要新建发送通道；
但官方 SDK 的定位是「**在自己 App 里跑导航**」而非读取高德地图 App 的导航数据，
且公开分发需要技术服务许可，因此本方案阶段**只定字段映射与接入点，不集成 AAR / key**。

## 2. 官方 SDK 的数据获取方式（仅记录，未接入）

```java
AMapNavi navi = AMapNavi.getInstance(context);
navi.addAMapNaviListener(new AMapNaviListener() {
    @Override public void onNaviInfoUpdate(NaviInfo naviInfo) { /* 主数据源 */ }
});
```

- `onNaviInfoUpdate(NaviInfo)` 是主回调，导航过程中持续触发；官文称其数据用于自定义导航视图、
  诱导面板与底部面板，定位为「HUD 和智能硬件的解决方案」。
- 转向图标两条路：`getIconBitmap()`（优先，自带路网信息）与 `getIconType()`（枚举值，
  `com.amap.api.navi.enums.IconType`）。本方案只取 `getIconType()`，不处理 bitmap。
- 车道线 `showLaneInfo(AMapLaneInfo)`、路口放大图 `showCross`/`showModeCross`、电子眼
  `updateCameraInfo`、路况 `onTrafficStatusUpdate` 均为**额外能力**，本方案一期不接。
- 隐私合规：8.1.0 起调用任何接口前必须先 `updatePrivacyShow` / `updatePrivacyAgree`，
  否则可能编译不过或运行异常（官文强制要求）。

## 3. NaviInfo → CarLifeNaviNextTurn 字段映射

| NaviInfo 方法 | 含义（官文） | 映射到 | 备注 |
|---|---|---|---|
| `getIconType()` | 转向图标类型枚举值 | `nextTurn` | **第三套编码**，见 §4；不可直接套 `rawToIcon` |
| `getNextRoadName()` | 下条路名 | `roadName` | 与 CarLife protobuf field 3 同义 |
| `getCurrentRoadName()` | 当前路名 | 暂无对应字段 | 可进 NowPlaying 文案，不进 0x5201 的 0x04（该位现行写路名） |
| `getCurStepRetainDistance()` | 到下一路口距离 | `remainDistance` | 对应 0x5201 的 `0x0a` |
| `getPathRetainDistance()` | 路线剩余距离 | `totalDistance` | **不要直接进 0x07**，见 §4 |
| `getPathRetainTime()` | 路线剩余时间 | 暂无对应字段 | iAP2 0x5201 无该字段，忽略 |
| `getNaviType()` | 1=实时 2=模拟 | 数据源过滤 | 只接受实时；模拟导航不得推车机 |
| `onStartNavi` / 结束回调 | 导航开始 / 结束 | `ACTION_START` / `ACTION_END` | 结束时立即补 `state=0` 清除帧 |

> 数据来源：官文示例（`textNextRoadName.setText(naviInfo.getNextRoadName())`、
> `NaviUtil.formatKM(naviInfo.getCurStepRetainDistance())`、`getPathRetainDistance()`）
> 与 `NaviInfo` 类文档（`getCurrentRoadName` / `getNextRoadName` / `getIconType` /
> `getPathRetainDistance` / `getPathRetainTime`）。

## 4. 三个必须守住的坑

1. **`IconType` 是第三套转向编码**。它不是 CarLife protobuf 的 raw 码，也不是 Apple maneuver。
   本项目现有 `CarLifeNaviGuide.rawToIcon()` 只处理 CarLife raw → 高德 ICON；
   高德 `IconType` 需要**独立映射表**。本方案不猜测枚举数值——实施时从 SDK 的
   `IconType` 类或官文枚举页提取完整值表，未知值一律返回 `0`（NONE），
   沿用「宁可不画箭头也不猜方向」的既有策略（历史上 raw 7 被误当右后方的教训）。
2. **`0x07` 不是全程剩余里程**。PR #46（未合）已指出 CarLife field 4 是「当前路段总距离」，
   活跃更新帧不应填 `0x07`；`getPathRetainDistance()` 语义更接近全程剩余，
   但在 #46 落地前，**不要用它改写入 0x07**，否则与现行行为不一致。
3. **结束帧必须立即扇出**。官文回调结束时直接 `Iap2RouteGuidance.endRoute()` 补 `state=0`，
   不能只停心跳等车机 30s 陈旧兜底（会留 HUD 残影）——与现行 CarLife 断链策略一致。

## 5. 接入点（实施时只动这几处）

| 位置 | 改动 |
|---|---|
| 新 `navigation/AmapNaviSnapshot.kt` | 纯数据类，字段对应 §3；不依赖高德 SDK，便于单测 |
| 新 `navigation/AmapNaviInfoMapper.kt` | snapshot → `CarLifeNaviNextTurn`，含 IconType 映射表与未知值保守策略 |
| 新 `navigation/NaviSource.kt` | 可插拔数据源接口；CarLife `0x10030` 与高德 SDK 两实现互斥 |
| `SessionController.kt` | 数据源选择：开关关闭 → 原路径；开启 → 高德源，失败静默退回 |
| `Iap2RouteGuidance.publish()` | **不改**，继续作为唯一 0x5201/0x5202 出口 |
| `CarLifeHuClient.navigationDisplay.receive(turn)` | **不改**，NowPlaying 转向文字/箭头直接复用（PR #40，开关 `navigation_now_playing` 默认关） |

两条输出链路都是现成的，本方案只新增「解析 + 映射 + 数据源选择」三层。

## 6. 开关与门控

- 新偏好键 `amap_navi_source`，**默认关**；与 `navigation_now_playing` 独立。
- 数据源只在「高德源活跃且收到实时 NaviInfo」时接管；CarLife `0x10030` 仍有数据时
  以 CarLife 为优先，避免同一时刻两个数据源抢 `Iap2RouteGuidance` 的快照。
- 绑定 / 初始化失败、模拟导航、未知 IconType 连续超阈值 → 静默退回 CarLife 源并限流记日志。
- 不引入车型 profile；门控只按「数据源可用性」，不按车机型号。

## 7. 合规边界（必须先解决再谈集成）

官文原文要点：

- 「法人或非法人组织使用平台服务应事先购买**技术服务许可**」；
- 未购买时 key 与配额**仅供短期、少量测试**；
- 「向第三方或公众销售 / 提供应用、应用市场上架、收费或发布广告、长期或大量调用」
  构成**未许可使用**，平台有权采取措施。

本项目是 GitHub 公开仓库、免费开源（收费只做赞助 / 技术支持），因此：

- **一期不集成 AAR、不申请 key、不调用 SDK**，只做纯数据映射，不产生任何许可风险；
- 若将来要真集成，必须先确认许可主体与授权范围，并单独评估 APK 体积、隐私合规
  （`updatePrivacyShow` / `updatePrivacyAgree`）与 NOTICE 声明。

## 8. 与 linksdk（非官方 AIDL）方案的对比

| 维度 | 官方导航 SDK（本文） | 高德地图 App linksdk（见同期调研） |
|---|---|---|
| 数据来源 | 自己 App 跑算路 / 定位 | 读高德地图 App 的导航回调 |
| 字段清晰度 | 官文明确、稳定 | 逆向 JSON，版本敏感 |
| 绑定权限 | 无（同进程） | MIUI WakePathChecker / 白名单 / shell，跨机型不可靠 |
| 合规 | 需技术服务许可 | 非官方接口，风险同样存在 |
| 体积 / key | AAR 大、需 key | 无 AAR、无 key |
| 结论 | **字段语义真源**，先做映射 | 备选兜底，暂不实施 |

建议顺序：先按本文完成纯映射与可插拔接口（零风险、可单测），
再根据许可与实车需求决定接哪条数据源。

## 9. 验证清单（实施时）

- 单测：IconType → 高德 ICON 全值表覆盖、未知值返回 0、结束帧 state=0、
  `getNaviType()==2` 被过滤、两个数据源互斥切换。
- 构建：不引入 AAR 时 `:app:testDebugUnitTest` 与 `assembleDebug` 必须通过；
  lint new=0；合规守卫通过。
- 实车：仅在开关显式打开且许可确认后做；确认仪表箭头方向、距离递减、
  结束清屏、NowPlaying 文案与 CarLife 视频不冲突。
