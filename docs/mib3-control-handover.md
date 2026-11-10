# 交接档 · MIB3 车机控制（下次从这里接着干）

> 写给「下一个我」：这份文档只回答四件事——**现在到哪一步了、代码在哪、下次上车先试什么、
> 假设错了往哪走**。设计依据与已确认的固件事实见
> [`mib3-viwi-control.md`](mib3-viwi-control.md)，本文不重复。

---

## 0. 状态快照（2026-11-03 14:20）

| 项 | 值 |
| --- | --- |
| 分支 | `main` |
| 提交 | `1b08b25` feat(mib3): 首页新增「MIB3 车机控制」面板… |
| 版本 | **1.2.15 / 10215**（`appVersionName` 是唯一来源） |
| 包 | `app/build/outputs/apk/release/app-release.apk`，**13,502,909 B** |
| 单测 | 345 例全绿（新增 13 例） |
| 装机 | 手机 PTP-AN10 已装 1.2.15（13:23:02） |
| 推送 / 发版 | **都没有**（按约定等明确指令） |
| 代码状态 | 功能完整可跑；**协议层有 5 项推断未经实车验证** → 见第 3 节 |

**一句话**：UI、状态机、安全门槛、日志、单测全部就位；剩下的是**上车把 5 个猜测变成事实**，
然后按实测值改两个文件。

---

## 1. 方案是什么（30 秒版）

iAP2 里没有控制车辆的消息，所以走**车机自己开的门**：

```
手机 ──蓝牙 iAP2──> 车机  ──内桥──>  viwi 总线 127.0.0.1:14751
        0xEA00 建 EA 会话            （明文、免 token、ACL 对 ext_devices 全开）
        0xEA03 会话状态
        0xEA0? 隧道数据  ← 就是这里在猜
```

手机侧只做一件事：在 EA 会话里收发**行式 JSON**（`{"id":1,"method":"GET","path":"/hvac"}\n`），
车机那头把字节直接倒进 viwi。

三条已确认的固件事实（`CL37_MIB3H_AU_AS_G4x_2302700PROD`，非推断）：

1. EA 协议串 `com.audi.cpp.eadata0` / `com.audi.cpp.eadata1` / `de.audi.mmiconnectdatatransfer`；
   `entitlements.enabled=false`（车机不做白名单过滤）。
2. `token_enforcement.enabled=false`（两处副本一致）→ **不需要先取 token**。
3. ACL 档位 `ext_devices` 对 `/hvac` `/door` `/window` `/lighting` `/seating` `/chassis` 等
   27 个端点开放 `GET PUT POST DELETE`。

---

## 2. 代码地图（改哪里 = 改什么）

```
bt/BtSession.kt                     attach/detach 控制链路的时机（bring-up 期间就挂）
iap2/Iap2MessageIds.kt              EA_SESSION_DATA = 0xEA04      ← 假设 1
iap2/Iap2SenderSession.kt           EA 状态/数据分派、sendEaData()、字节计数
mib3/Mib3Viwi.kt                    封帧/切行/字段提取           ← 假设 3、4
mib3/Mib3Endpoints.kt               8 条只读 + 12 个动作的路径与 body  ← 假设 4
mib3/Mib3ControlLink.kt             单例状态机、请求 id 配对、记账
mib3/Mib3ControlPanel.kt            弹窗（链路/只读/控制/原始报文/免责）
MainActivity.bindMib3Card()         首页卡 → 只显示状态，按钮开面板
```

**关键定位**：

| 想改的东西 | 去哪 |
| --- | --- |
| 数据消息 ID / 载荷形态 | `Iap2SenderSession.sendEaData()`（第 ~710 行）+ `handleEaData()` |
| 封帧与切行 | `Mib3Viwi.Request.encode()` 与 `Mib3Viwi.splitLines()` |
| 只读端点、控制动作、body | `Mib3Endpoints.READ_ONLY` / `Mib3Endpoints.ACTIONS` |
| 字段名 / 解析 | `Mib3Endpoints.DISPLAY_KEYS` + `Mib3Viwi.extractFields()` |
| 状态机门槛（什么时候允许写） | `Mib3ControlLink.phase` 与 `perform()` 的前置判断 |
| 面板排版 | `Mib3ControlPanel.buildContent()` |

---

## 3. 五项推断 + 判别信号（**下次的核心工作**）

| # | 推断 | 现在的实现 | 错了的表现 | 唯一改点 |
| --- | --- | --- | --- | --- |
| 1 | 数据消息 ID = `0xEA04` | `EA_SESSION_DATA = 0xEA04` | 车机**完全沉默**：日志只有我方 `EA: tx N B`，没有一条 `iap2 rx=0xea04` | `Iap2MessageIds.kt` |
| 2 | 载荷 = 整段裸字节塞 body（不加 CSM 参数包装） | `send(EA_SESSION_DATA, payload)` | 同上（车机能收到帧但解不开，通常不回应） | `Iap2SenderSession.sendEaData()` |
| 3 | 封帧 = 每行一条 JSON + `\n` | `Request.encode()` 返回 `toJsonLine() + "\n"` | 车机**有回执但不是 JSON**，或回 `{"status":4xx}` 类错误 | `Mib3Viwi.kt` |
| 4 | 字段名 `locked`/`position`/`active`/`targetTemperature`/`heatingLevel` | `DISPLAY_KEYS` | **有回执**、面板「已回 N 字符」，只是「取值」一列空着 | `Mib3Endpoints.kt` |
| 5 | 无线（蓝牙 + 车机热点）下同样可用 | 按可用处理 | 只在 USB 下通 → 需要换接法或改写方案 | 无（方案级） |

**判别技巧**：`1`/`2` 错 → 死寂（无任何 rx）；`3`/`4` 错 → **有 rx 但内容不对**。
这两类现象完全不同，别混着猜。面板「链路」区的 `收/发` 字节计数就是为此设计的。

---

## 4. 下次上车：实验流程（按顺序做，别跳）

### 步骤 0 · 准备

```bash
# 在仓库根目录运行
.tools/android-sdk/platform-tools/adb.exe devices            # 确认 PTP-AN10 在线
.tools/android-sdk/platform-tools/adb.exe install -r app/build/outputs/apk/release/app-release.apk
```
- 手机需**屏幕解锁**（有密码锁时 adb 滑动解不开，必须手动解）。
- 车机侧按正常流程连（蓝牙配对 → 车机热点 → 开始连接）。
- 首页滚到「MIB3 车机控制」卡，记下那行状态文案，再点「打开车机控制面板」。

### 步骤 1 · EA 会话有没有建起来（决定后面一切）

看日志（见第 6 节日志三法），按顺序找这四行：

```
iap2 0x1d01 EA 组件[10] protocol=? name=com.audi.cpp.eadata0
EA: 数据通道协议 = com.audi.cpp.eadata0（0xEA04 承载）
iap2 tx=0xea00 startEASession protocol=? name=com.audi.cpp.eadata0
iap2 rx=0xea03 EASessionStatus bytes=N [..]
```

| 现象 | 结论 | 动作 |
| --- | --- | --- |
| 没有 `EA 组件` 行 | 车机这次没声明 EA（或走了 WiFi 隧道那条会话） | 先确认连的是蓝牙控制会话；换 USB 再试一次 |
| 有协议、有 tx、**无 rx=0xea03** | 0xEA00 的构造不对 | 试「只发协议号」或「只发协议名」两种 param0/param1 组合（上游 PR #17 的遗留分歧） |
| `rx=0xea03` 有，面板显示「EA 隧道已建立」 | ✅ 会话通了，直接进步骤 2 | — |

**这一步不通就别往下走**——后面所有现象都没有判别意义。

### 步骤 2 · 数据消息号（假设 1/2）

面板点「拉取只读数据」，观察 30 秒：

| 现象 | 结论 | 动作 |
| --- | --- | --- |
| 面板「已回」全是 0 字符，日志只有 `EA: tx 27B` 之类 | 车机不认 `0xEA04`，或载荷不成形 | 按**第 5 节的梯子**换 ID / 换载荷 |
| 日志出现 `iap2 rx=0xea04 EA 数据 N B（无消费者，丢弃）` | ID 对了、只是面板没挂上消费者 | 检查 `Mib3ControlLink.attach` 里 `onEaData` 有没有被更晚的 attach 覆盖 |
| 有 rx、面板「已回 N 字符」但内容是乱码/非 JSON | 消息号对了，**封帧或分帧**错 | 进步骤 3 |

> 注意 `0xEA02` 在本仓库已被 `REQUEST_APP_LAUNCH` 占用（3874 分析确认），别再拿它当候选。

### 步骤 3 · 封帧（假设 3）

先只发**一条最简 GET**（面板「拉取只读数据」会发 8 条，先用日志里第一条的 id 对）：

```
→ {"id":1,"method":"GET","path":"/vehicleinformation"}\n
← 期望：{"id":1,"status":0,"data":{...}}
```

- 回执里能读到 `id` → 封帧基本对了（`Mib3Viwi.parseLine` 只认 `id`/`status`）。
- 回执是 `{"status":400}` 之类 → 路径或方法名不对，改 `Mib3Endpoints`。
- 回执完全不是 JSON（二进制/混淆） → 试着去掉 `\n`、或改成 `\r\n`、或整段不换行（三条都试一遍，
  `Mib3Viwi.Request.encode()` 一处改）。

### 步骤 4 · 字段名（假设 4）

一次 `GET /door` 的原文里就能看到真实字段名。**直接抄进 `DISPLAY_KEYS`，不要继续猜**。
同理 `/hvac`、`/window`。

### 步骤 5 · 写动作（**车停稳、最好拆车件/台架**）

顺序建议：`/lighting`（车内灯，可逆、无害）→ `/hvac`（22 ℃，能听到/看到反应）→
`/window`（降一点再升回去）→ `/door`（**最后，且确认车外有人在旁边**）。

每步都从面板走完整流程：拨开「允许写入」→ 点按钮 → 确认框里核对 JSON → 执行 → 看回执。

---

## 5. 假设不成立时的排查梯子（按代价从低到高）

1. **换消息号**（改一行、重装，5 分钟一轮）：`0xEA04` → `0xEA05` → `0xEA02`（已占用，仅作最后
   兜底，需同时改 `nameOf` 与 `REQUEST_APP_LAUNCH` 的映射）。
2. **换载荷形态**（`sendEaData()` 内）：裸字节 → 包一层 CSM 参数
   （`u8(0, protocolId) + bytes(1, payload)`）→ 去掉长度前缀。
   判断依据：iAP2 大多数「会话数据」消息都会带 `param0 = 会话/协议号`。
3. **换封帧**（`Mib3Viwi.Request.encode()`）：`\n` → `\r\n` → 无分隔符（整条一个请求）。
4. **换会话**：`eaDataProtocolId` 从 `eadata0` 改成 `eadata1`，或改 `de.audi.mmiconnectdatatransfer`
   （后两条协议可能才是 viwi 桥；目前只按名字猜优先 `eadata0`）。
5. **确认「EA 隧道是不是唯一入口」**：若 1~4 全试过仍死寂，回到分析文档核对是否有
   `/dev/`、`adb forward` 之外的第三条路（如车机内部 `viwi` 的本地 socket 通过 CarPlay
   的事件通道 `0x4301`/`0x4302` 转发）。这一步要重新读固件逆向材料，不要凭记忆。

**每轮只改一个变量**，并把「改了什么 → 现象」记到本文末尾的试验记录表。

---

## 6. 环境与命令（照抄）

### 构建

```bash
# 在仓库根目录运行
./gradlew :app:clean              # 改版本号或切分支后**必须**先 clean，否则会报假失败
./gradlew :app:testReleaseUnitTest
./gradlew :app:assembleRelease
```

- **Gradle 一律前台跑**。`run_in_background` 启动的 Gradle 必挂
  `BindException: Address already in use: bind`（后台 shell 的 UDP 上下文不同）。
- 单条命令超时上限设 600s。
- 假失败症状：`NoSuchMethodError` / 「找不到符号」成片出现 → `clean` 后即恢复（增量状态被污染）。

### 装机

```bash
ADB=.tools/android-sdk/platform-tools/adb.exe
$ADB install -r app/build/outputs/apk/release/app-release.apk
$ADB shell dumpsys package io.github.shaonialife.carptocar | grep -E "versionCode|versionName"
```

- **降级装不了**：`adb install -r -d` 的 `-d` 只对 debuggable 包生效，release 会报
  `INSTALL_FAILED_VERSION_DOWNGRADE`；`pm uninstall -k` 也不行（PMS 记住旧 versionCode）。
  唯一出路是彻底卸载（丢数据），或者给目标分支**升** versionCode。

### 日志（三条路，release 包没有 `run-as`）

1. **app 内实时**：首页日志卡 / 控制面板不显示日志，但日志卡里能看到 `EA:` 开头的行。
2. **导出**（推荐）。点「导出日志」后，以弹窗显示的路径为准。当前 ICCOA 版导出至
   `下载/CarPToCar+ICCOA`，可用 `adb pull '/sdcard/Download/CarPToCar+ICCOA/' .tmp/logs/` 取回。
3. **debug 包**（临时排查用）：`$ADB shell "run-as io.github.shaonialife.carptocar tail -n 400 files/main_debug.log"`

**关键 grep 词**：`EA:`、`ea00`、`ea03`、`ea04`、`0x1d01 EA 组件`、`数据通道协议`。

---

## 7. 已知坑（这次踩过的）

| 坑 | 表现 | 处理 |
| --- | --- | --- |
| 后台 Gradle | `BindException: Address already in use` | 前台跑，别用后台任务 |
| 版本号改了没 clean | 成片「找不到符号」 | `./gradlew :app:clean` |
| 手机密码锁 | adb `input swipe` 解不开，`isKeyguardShowing=true` | 让用户手动解锁 |
| `KEYCODE_BACK` 连按 | 从面板直接退到桌面/锁屏 | 关面板用「关闭」按钮，别用返回键 |
| release 不可 debug | `run-as` 报 not debuggable | 走日志导出 |
| `releases/latest` 被抢 | 下载直链 404 | README 用固定版本 URL |
| github.com 不通 | push 挂（代理 502 / reset） | 走 Git Data API 推送脚本（`.tmp/push_chain_via_api.py`） |

---

## 8. 安全红线（写动作之前必读）

- **只能在车停稳时试写**；行驶中哪怕 ACL 放行，也不要试 `/door` `/window`。
- 优先**台架/拆车件**。这接口的权限模型把「物理接触」当授权——能插线的人本来就有钥匙；
  它不是远程开锁，但也**不是可以随便玩的接口**。
- 面板的三道闸不要为了调试方便拆掉：`允许写入` 开关、逐次确认框（显示真实 JSON）、
  `只有收到 0xEA03 才允许写`。调试「裸发」请另开一个显式的「实验通道」入口，
  不要放宽正式按钮的门槛。
- 业务前置条件（车速/点火/门状态）在服务实现里，静态分析追不到。
  「ACL 允许 POST /door」≠「行驶中能开门」。

---

## 9. 建议的下一步（优先级排序）

1. **面板加「手输 JSON 直发」实验入口**（最高价值）：一个输入框 + 发送按钮，把文本直接丢进
   `sendEaData()`。这样每次试新封帧/新路径都不用改代码重装，一轮实验从 5 分钟降到 10 秒。
   放在「危险」分隔线以下、同样要求「允许写入」+ 二次确认。
2. **超时与重试**：现在发出去没有超时（收不到就一直是「已请求」）。加 `id → 时间戳`，
   3 秒未回标「超时」，面板才不会把「没回」看成「还在等」。
3. **解析响应 schema**：等第 4 节步骤 4 拿到真实字段后再做，别提前把猜测写成读数。
4. **长轮询 / 订阅**：viwi 支持事件推送（车门状态变化），隧道通了之后再考虑。
5. **写入联锁**：能拿到可信的行驶状态（`/drivingstate`）后再做，之前只能靠用户自觉。
6. **`switch-pool` 分支同步**：功能稳定后 cherry-pick 一条过去（**单条提交，不要 merge**）。

---

## 10. 决策记录（为什么这么写，避免下次推翻重来）

| 决定 | 理由 |
| --- | --- |
| 控制按钮不放首页，全塞弹窗 | `/door` `/window` 是真能动的；首页平铺迟早误触 |
| 单例而不是注入 | 一条 BT 会话同一时刻只有一个；面板随时开关，不该持有会话 |
| 只有 0xEA03 之后才允许写 | 没有回执就发数据，会把「会话没建起来」误诊成「报文格式不对」 |
| 不解析响应 schema | 字段名是猜的，过早解释等于把猜测当读数；先原文照抄进面板 |
| `send`/`sendFireAndForget` 加 `@Synchronized` | 面板是第二条写线程，不加锁会把 CSM 流写花（总线是共享的） |
| 0xEA03/0xEA04 分派排在重复限流之前 | viwi 长轮询会连发；跟着限流走等于每 100 条只放行 1 条 |
| 推断集中在两个文件 | 让「实车校正」是一次外科手术，不是全仓搜索 |

---

## 附 · 试验记录表（每次上车后追加一行）

| 日期 | 包版本 | 改动 | 现象（含日志关键行） | 结论 |
| --- | --- | --- | --- | --- |
| 2026-11-03 | 1.2.15 | 首版，未上车 | 真机仅验证面板弹出与禁用态（无车机） | 待实车 |
