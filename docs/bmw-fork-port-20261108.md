# 宝马 fork 三项修复并入主分支（2026-11-08）

来源：一份基于 `switch-pool-auto`（v1.2.25）的宝马适配 fork 的 README，
实测车机 **BMW 94841 / G38 NBT EVO / iDrive 7（QNX 6.5.1）**，2026-11-07 19:49
连续推流 670 秒 / 13350 帧 / 19.9 fps。本文只记录**并进 main 的那部分**、
**没并的部分**，以及并进来之后 main 上行为变了什么。

用户选择：**三项默认全局生效**（不做「只对宝马」的车型开关）。

---

## 一、fork 的结论（原文要点）

原版连不上宝马不是协议实现问题，而是「车机不认这台手机是 CarPlay 手机」：

| # | fork 的判断 | 修复 |
| --- | --- | --- |
| 1 | 缺「CarPlay Phone」标记 SDP（`2d8d2466-e14d-451c-88bc-7301abea291a`），车机只当普通蓝牙音频设备，配对后 2~4 秒断链 | 注册该 SDP 记录 |
| 2 | 持续断开 HFP/A2DP：配置文件在线时车机不开放 iAP2 窗口 | `BtProfiles.guardAudioProfiles()` |
| 3 | 首帧时序：补发 assertModes + RECORD 会让宝马关掉视频口 | 首帧后不补发 |

---

## 二、main 原本的状态（并之前核对过）

| 项 | main 并之前 | 说明 |
| --- | --- | --- |
| ① 标记 SDP | **已有**，在 `bt/BtIdentityProbe.kt`（`2d8d2466-…` + `02030302-…` Wireless iAP v2），但受「车机只认苹果设备」开关控制，**默认关** | 机制同 fork，差了「常驻」这一点 |
| ② 音频配置守卫 | **没有**。只有 `BtProfiles.disconnect()` 的一次性断开（连接前诊断、重连时各断一次） | 车机自己拉回来之后没人管 |
| ③ 首帧后补发 | **有且全局开**：`CarLifeHijack.onVideoSourceRecovered` → `assertModes() + record()` | 这是给奥迪 4580「连接成功但黑屏」修的（`81e75a6`），对宝马正好相反 |

---

## 三、这次改了什么

### 1) 标记 SDP 常驻（`BtCompatPrefs` 默认值 false → true）

车机把设备记成「不支持 Apple CarPlay」发生在 **discovery / 配对阶段**，早于任何
iAP2 交互。等用户点「启动连接」再注册 SDP 记录，那一瞥已经过去了。所以现在
`SessionController.ensure()`（Activity onCreate）就 `applyBtCompat(true)`：
占位 SDP 常驻 + iAP2 监听常开 + 看门狗自动拉回。

设置里那个开关还在，关掉即回到旧行为（不注册记录、不常开监听）。

### 2) 音频配置守卫（新增 `BtProfiles.guardAudioProfiles()`）

- 每 1.5 秒查一次目标车机的 HFP/A2DP/LE Audio，在线就断，断一次记一行日志；
- 目标地址来源：`BtSessionGate.activePeerAddress`（真正建链的对端，入站/出站都登记）
  → 退而用 `TargetDevicePrefs` 里指定的目标；
- 退出条件：用户断开（`!sessionWanted`）、iAP2 bring-up 完成（`BtSessionGate.iap2Ready`，
  比 `sessionActive` 更早，正是 fork 说的「iAP2 建立即停手」）、开始推流、或盯满
  5 分钟（`AudioProfileGuardPolicy.MAX_MILLIS`）；对端地址 60 秒都拿不到也收工
  （没指定目标又还没建链时不留白睡的线程）；
- 由 `SessionController.startAudioProfileGuard()` 在每轮 `connect()` 里拉起，单线程幂等。

节奏与上限抽成 `AudioProfileGuardPolicy`（纯逻辑），配 `AudioProfileGuardPolicyTest`。

### 3) 首帧后不再补发 RECORD

`SessionController.installNowPlayingBridge()` 里的 `CarLifeHijack.onVideoSourceRecovered`
赋值整段删掉，原实现留在注释里（`git show 81e75a6`）。

---

## 四、行为差异（main 并之前 → 并之后）

| 场景 | 并之前 | 并之后 |
| --- | --- | --- |
| App 启动 | 不注册任何 CarPlay 标记 SDP，不监听 | 立刻挂 `2d8d2466-…` + `02030302-…` 两条占位记录并常开监听 |
| 建链期 HFP/A2DP | 只断一次，车机拉回来就不管 | 每 1.5 秒复查并重断，最多 5 分钟 |
| 首帧送达后 | 补发 assertModes + RECORD | 什么都不补 |
| 奥迪 4580 黑屏老症 | 由「首帧后重建屏激活」兜住 | **这层兜底没有了**，若复现需按注释接回去 |

---

## 五、已知风险 / 回退

1. **奥迪 4580 / 4910 的「重建屏激活」失效**（用户已知情并接受）。这是本次唯一
   会直接影响已跑通车型的改动。复现症状：连接成功、状态栏说在推流、车机黑屏。
   回退：把 `installNowPlayingBridge()` 里注释掉那段接回去即可。
2. **握手期蓝牙会反复连断 2~4 分钟**。这不是 bug，是守卫在工作（fork 原文也承认）。
   日志里能看到成对的 `BT-GUARD: 车机把 通话(HFP) 拉回来了 → 再次断开（第 N 次）`。
   若某台车机因此连 iAP2 窗口都开不出来，关掉「车机只认苹果设备」开关即可整体退回。
3. **常开监听**：App 一起来就发布 iAP2 服务，车机可能在我们没点「启动连接」时
   主动连入。`idleListening` / `BtSessionGate` 的既有闸门负责不让它和出站连接打架。

---

## 六、没有并进来的部分

| fork 里的改动 | 为什么不并 |
| --- | --- |
| `BtProfiles.connect()`（主动拉起 HFP/A2DP） | main 没有「档位」概念需要它；守卫只负责断开，拉起交给系统/车机 |
| `BleProbe.kt`（dump 车机 SDP 服务列表 + BLE 广播，134 行） | 纯诊断、默认不调用，需要时再看 |
| MainActivity「长按清除并重配」/「先注册服务再配对」的顺序改造 | 「先注册」已由第 1 条（App 启动即注册）覆盖；清除重配是 UI 便利，未做 |
| `REACTIVATE` 只对 AUDI 生效 | 用户选了全局关掉，不留车型开关 |
