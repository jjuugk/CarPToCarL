# 双指触控：为什么按不出来，以及怎么改

> 面向后续维护。1.0.7 起双指手势改走 CarLife 的**复合动作通道**（`0x68001`），
> 单指仍走已验证的单点通道（`0x68002/3/4`）。

## 症状

车机上单指点击、拖动都正常，**双指缩放完全没反应**——地图缩不动，图片放不大。
不是「缩错了」，是一点反应都没有。

## 三层原因，逐层排除

### 一、数据其实到了（不是车机不报）

日志里车机是按**两槽**上报的：

```
EVENT: 触摸 #0(452,286)↓, #1(0,0)↑
EVENT: 触摸 #0(472,216)↓, #1(0,0)↑
```

`TouchReports.parseMultiSlot` 解析 12 字节 = 2 槽 × 6 字节，每槽 `[槽号][按下][X u16LE][Y u16LE]`。
slot 1 常驻 `(0,0)↑` 只是空闲占位，真正按下时会带坐标。
`SessionController` 又是 `contacts.forEach { TouchRouter.onContact(...) }`——两个触点都会往下传。

### 二、路由层把它吞掉了（真正的原因）

`CarLifeHijack.handleTouch` 的第一行判断：

```kotlin
if (slot != 0) return true // 只支持单指；多指其余槽直接吞掉
```

返回 `true` 的意思是「我处理了」，于是 `TouchRouter` 直接 `return`，第二根手指到此消失。

### 三、协议层的表达力问题（为什么当时只能这么做）

单点消息的负载是 `CarlifeTouchSinglePoint{x=1, y=2}`（proto 里就这两个字段），
**没有指针概念**。用 `0x68002/3/4` 无论怎么发，引擎都只能看到一个触点，
双指手势在协议上表达不出来。当年的「只支持单指」不是偷懒，是真的发不出去。

## 改法：双指走复合动作通道

`0x68001` 的负载是 `CarlifeTouchAction{action=1, x=2, y=3}`，`action` 就是
**原始 MotionEvent 动作码**，指针索引按 Android 的约定放在高字节：

| 事件 | action | 说明 |
| --- | --- | --- |
| 第一根手指按下 | `0` | `ACTION_DOWN`，仍走单点通道 |
| 第二根手指按下 | `5 \| (1<<8)` = **261** | `ACTION_POINTER_DOWN` + 指针索引 1 |
| 任一根移动 | `2` | `ACTION_MOVE` |
| 第二根手指抬起 | `6 \| (1<<8)` = **262** | `ACTION_POINTER_UP` + 指针索引 1 |
| 最后一根抬起 | `1` | `ACTION_UP` |

这正是官方 HU SDK 的「新触摸方式」：

```java
// TouchListenerManager.mNewListener
sendAction(event.getX(), event.getY(), event.getAction());
```

`event.getAction()` 对 `ACTION_POINTER_DOWN` 天然带索引位。我们比官方样例多做一步：
坐标取**该手指自己的**（官方样例取的是 `getX()`，即指针 0 的坐标）。

### 实现要点

- 状态机仍以槽 0 为主线；槽 1 只在 `onSecondPointer` 里处理，
  按下时把整笔手势切到复合通道（`multiActive = true`），从此槽 0 的移动也走复合通道
  ——**两条通道各维护一套指针状态，混用会互相覆盖**。
- 收尾顺序固定为「先 `POINTER_UP(手指 1)`，再 `ACTION_UP`」，
  反了会留下一个永不放开的幽灵触点（引擎的 `eventIng` 卡在 true）。
- 发送队列的「MOVE 只留最新」合并逻辑扩展到复合动作里的 `ACTION_MOVE`。
- 单指路径**一行未改**：`multiActive` 为 false 时仍走 `touchDown/Move/Up`。
  也就是说双指就算仍然不通，也不会影响原本正常的点击与拖动。

## 复测判读

按顺序看这四行日志（导出日志后直接搜 `多指` / `触摸格式`）：

1. `EVENT: 触摸格式 多槽（最多 2 指）`
   —— 如果是「单指绝对坐标」，说明这台车的 HID 根本不报第二个触点，App 侧无解。
2. `CARLIFE: 多指 ↓2 (x,y)→(tx,ty) 进入双指模式`
   —— 没有这行 = 第二根手指没到，或第一根手指没按下（槽位顺序异常）。
3. `CARLIFE: 多指 移动2 (tx,ty) MOVE#1…`
   —— 有这行说明移动事件在往复合通道发。
4. `CARLIFE: 双指手势结束（手指 2 移动 N 次）→ 复合通道 UP`
   —— 收尾正常，没有卡住。

**若 1～4 都正常但地图仍不缩放**：说明引擎的复合动作处理不支持指针索引，
需要换策略（例如整笔手势都从复合通道的 `ACTION_DOWN` 起始，而不是中途切换）。
届时改 `CarLifeHijack.handleTouch` 里第一次按下那一支即可，`multiActive` 的开关点只有一个。
