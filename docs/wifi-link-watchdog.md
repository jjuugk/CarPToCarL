# WiFi 断链排查口径（wlan0 到底动没动）

1.2.12-switchpool 起，日志里多了一套 wlan0 状态心跳。这份文档只说一件事：
**拿到现场日志后，怎么用它判断「断链是不是 WiFi 造成的」**。

## 一、先看断的是哪条链

| 日志最后几行 | 断的是 | 结论 |
|---|---|---|
| `SESSION 保活结束（链路断开）` → `LINK closed` → `BT: 已主动断开当前控制会话` | iAP2 / 蓝牙 RFCOMM | **不是 WiFi**，看下一节 |
| `WIFI: 系统事件 onLost` → `>>> 车机热点 … 已断开` | wlan0（应用专属网络） | 是网络侧，先看重绑结果 |
| `VIDEO: 帧 #…` 之后直接没有下文，CarPlay 会话还在 | 帧源（CarLife 引擎） | 画面冻结，不是会话结束 |

2026-11-01 那份 10 分钟日志里 6 次结束**全是第一类**。所以「CarPlay 断链」这个词
在那份日志里指向蓝牙，而不是 WiFi——这两件事被混在一起，是当时判断卡住的主因。

## 二、心跳怎么读

启动时一行（起点）：

```
WIFI: 状态心跳已启动（起点 wifi=[wlan0/192.168.1.5] car=wlan0/192.168.1.5 default=wlan0/192.168.1.5 ssid=GeXu-Audi_5GHz）
```

之后**只有签名变化**才出新行，带 `上次 …` 便于对照：

```
WIFI: 系统事件 onLost → wifi=[] car=- default=rmnet0/10.1.2.3 ssid=GeXu-Audi_5GHz（上次 wifi=[wlan0/192.168.1.5] …）
WIFI: 车机热点 "GeXu-Audi_5GHz" 已断开——走它的 socket 已随网络销毁，本轮会话不会自愈
WIFI: 自动重绑车机热点 "GeXu-Audi_5GHz"——只恢复网络，本轮 CarPlay 会话仍需重建（旧 socket 已失效）
WIFI: 车机热点已重新绑定——网络恢复，本轮会话需重建后才生效
```

四个字段的含义：

| 字段 | 来自 | 变化说明什么 |
|---|---|---|
| `wifi=[…]` | `onAvailable` / `onLost` 维护的集合 | 系统里 WiFi 网络的增删（换热点、断开关） |
| `car=` | 应用专属网络实例 | `-` = 车机网络没了；地址变了 = 换了关联 |
| `default=` | `ConnectivityManager.activeNetwork` | `bindProcessToNetwork` 是否被别的网络顶掉（回调不报，但这里会变） |
| `ssid=` | `boundCarSsid` | 我们认为已绑定的车机 SSID |

**判定规则只有一条**：断链时刻附近有没有 `WIFI:` 行。

- **没有** → wlan0 那一刻没动过，WiFi 可以排除；去查对端为什么拆链
  （`docs/audio-channel-watchdog.md` 里的抓包口子）。
- **有 `onLost`** → 网络真的断了。再看重绑是否成功：
  - 重绑成功 + 几十秒内会话重建 → 是 WiFi 抖动导致的一轮断链，可修（重绑已做，会话自愈还没做）；
  - 重绑失败 / 连续 `onLost` → 热点本身没了（熄火、出范围），属正常终态。

## 三、两个常见误判

1. **`AUDIO: 同步包发送失败 - ECONNREFUSED`**：这不是断链，是车机 `controlPort`（6011）
   没在监听，ICMP 端口不可达。音频照常出声（那台 MIB3 按 RTP 时间戳排播）。
   1.2.11 起同套接字就地补发一次，日志已不再每秒刷两行。
2. **`CARLIFE: 实测帧率 27.5fps`（此前 53fps）**：画面静止时编码器本来就少出帧，
   不能当链路劣化证据。要复现请**别动画面**，静止状态下再看帧率。

## 四、还想进一步确认时

1. `adb logcat -s WifiClientModeImpl WifiStateMachine`，看断链瞬间有没有
   `DISCONNECTING` / `CONNECTING`——系统侧关联状态机比我们的回调更底层。
2. **对照实验**：把 CarLife+ 改成 USB 连接（偏好 `carlife_connect_type` 从 5 改成 1）。
   断链消失 → 单 STA 争抢（CarLife+ 与我们的应用专属网络抢同一条 wlan0）成立；
   照旧 → 与 WiFi 无关。
3. `adb shell dumpsys wifi | grep -i "curState\|mNetworkInfo"`，断链前后各抓一次。

## 五、已知但本版没做的

- **WiFi 抖动后 CarPlay 会话不会自愈**：`onLost` 只重绑网络，不重建会话。
  自动重建要重新配对/建链，会把 CarLife 引擎再踢一次，风险大于收益，留待实车确认
  抖动频率后再决定。
- **`AUDIO: 同步包 … ECONNREFUSED` 与 6011 端口的关系**：车机全程不监听是它的实现选择，
  不是我们发错端口（真 iPhone 同期也不会有回包）。
