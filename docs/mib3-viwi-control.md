# MIB3 车机控制（viwi over iAP2 EA 隧道）

> **⚠️ 已于 1.2.29 之后的未发版改动中整体移除**：`mib3/` 包（车机控制面板 / 控制链路 /
> viwi 端点与编解码）与 iAP2 的 EA 会话发起、`0xEA03`/`0xEA04` 处理全部删除。
> 本项目不再提供、也不再发起 EA 车机控制。本文仅作当时的取证与设计记录保留，
> 文中提到的文件、类、界面入口均已不存在。iAP2 链路层对车机声明的
> `{1:control, 2:EA}` 会话表仍照常 ACK + 回显（这是链路协商，不是 EA 功能）。

> 入口：首页「MIB3 车机控制」卡 → 「打开车机控制面板」
> 依据：`MIB3_CarPlay控制车辆链路分析.html`（MIB3H 3874 / `CL37_MIB3H_AU_AS_G4x_2302700PROD`
> 静态逆向）。本文只记录**本仓库怎么落地**，以及哪些是确认的、哪些是猜的。

## 一、为什么不是「iAP2 里加个开关」

iAP2 全消息表里**没有任何**控制车辆的消息。与车况有关的只有 `vehicleStatus`
（`0xA100` 订阅 / `0xA101` 推送），而 MIB3 的 `carplay.json` 里这个模块是
`enabled:false` + `dataGeneratorEnabled:false`，方向也只有「车 → 手机」。

真正的入口是 **ExternalAccessoryProtocol（EAP）隧道**：车机把它桥接到内部 viwi 总线的
`127.0.0.1:14751`（明文、`token_enforcement.enabled=false`），该端口的 ACL 档位
`ext_devices` 对 `/hvac`、`/door`、`/window`、`/lighting`、`/seating` 等 27 个端点
开放 `GET PUT POST DELETE`。手机侧就是一条 EA 会话。

## 二、代码结构

| 文件 | 职责 |
| --- | --- |
| `iap2/Iap2MessageIds.kt` | `EA_OPEN_SESSION(0xEA00)` / `EA_CLOSE_SESSION(0xEA01)` / `EA_SESSION_STATUS(0xEA03)` / **`EA_SESSION_DATA(0xEA04)`** |
| `iap2/Iap2SenderSession.kt` | bring-up 时按 0x1D01 声明的协议发 0xEA00；保活泵里分派 0xEA03/0xEA04；`sendEaData()` 出口 |
| `bt/BtSession.kt` | 会话就绪即 `Mib3ControlLink.attach()`，链路断开 `detach()`（含 `run()` 抛错的路径） |
| `mib3/Mib3Viwi.kt` | 封帧/切行/字段提取（纯 Kotlin，可单测） |
| `mib3/Mib3Endpoints.kt` | 只读端点清单 + 12 个控制动作（含 body） |
| `mib3/Mib3ControlLink.kt` | 单例状态机：发请求、收响应、记账、通知 UI |
| `mib3/Mib3ControlPanel.kt` | 弹窗：链路状态 / 只读区 / 控制区（允许写入 + 二次确认）/ 原始报文 |

### 状态机

```
未连接车机 ── attach ──> 车机未声明 EA 协议（不可用）
                     └─> 等待 0xEA03（只读可发，写动作禁用）
                     └─> EA 隧道已建立（可读写）
```

**只有收到 0xEA03 才允许写**。0xEA03 是车机对 0xEA00 的唯一回执；没它就发数据，等于
往没建起来的管道里灌字节，排障时会被误判成「报文格式不对」。

## 三、确认的（固件原文）

- EA 协议串 `com.audi.cpp.eadata0` / `com.audi.cpp.eadata1`，`entitlements.enabled=false`
  （车机不做白名单过滤），桥接目标 `127.0.0.1:14751`。
- `ext_devices` 档位的端点与可用方法（`/hvac` 6.6.0、`/door` 2.0.0、`/lighting` 4.0.0…）。
- `token_enforcement.enabled = false`（两处副本一致）→ 不需要先取 token。
- 能力目录 `hvacRead` / `hvacModify` / `hvacControl` / `carStatusRead` 证明设计上做了分级。

## 四、推断的（**首次实车必须核对**）

| 项 | 当前实现 | 错了会怎样 |
| --- | --- | --- |
| 数据消息 ID | `0xEA04` | 车机沉默；日志里只有我方 tx、没有 rx |
| 载荷形态 | 整段裸字节塞进 body（不加 CSM 参数包装） | 同上 |
| 封帧 | 每行一条 JSON + `\n`：`{"id":N,"method":"GET","path":"/hvac"}` | 同上 |
| 字段名 | `locked` / `position` / `active` / `targetTemperature` / `heatingLevel` | 车机回错误码（有回执、能看见） |
| 无线是否同样可用 | 按可用处理 | 只在 USB 下通 |

**这五项都集中在 `Mib3Viwi.kt` 与 `Mib3Endpoints.kt` 两处**，实车抓包后改这两个文件即可，
不用动 UI 与会话层。

## 五、首次实车怎么核对

1. 连上车机，看日志有没有这两行：
   ```
   iap2 0x1d01 EA 组件[10] protocol=… name=com.audi.cpp.eadata0
   EA: 数据通道协议 = com.audi.cpp.eadata0（0xEA04 承载）
   ```
2. 打开控制面板，面板「链路」应为 `等待 0xEA03 会话状态` 或 `EA 隧道已建立`：
   - 停在「等待 0xEA03」→ 车机没回执。先确认 0xEA00 发出去了（`iap2 tx=0xea00 …`），
     再怀疑 `param 0 = 协议号` / `param 1 = 协议名` 的组合（这是上游 PR #17 的结论）。
   - `EA 隧道已建立` → 看「报文」行：发了 N 条、收了 M 条。
3. 只读区任意一行从「已请求」变成「已回 N 字符」→ 封帧猜对了；一行都回不来 →
   封帧或消息 ID 不对，按第四节改。
4. 写动作先在**拆车件/静止**状态试，`/door`、`/window`、`/chassis` 会传到执行层。

## 六、安全边界

- 面板默认**不允许写**：开关关着时 12 个控制按钮全部禁用；每次写仍弹确认框，
  框里显示**实际要发的 JSON 原文**，不是「上锁」这种转述。
- 解锁车门、降下车窗标为危险动作（暗红按钮 + 危险确认样式）。
- ACL 只说明「允许调用」，**业务前置条件在服务实现里**（车速/点火/门状态），
  静态分析追不到。「ACL 允许 POST /door」不等于「行驶中能开门」。
- 这套接口的权限模型把「物理接触」等同于授权——能插线的人本来就有钥匙。它不是远程
  开锁，但也**不是一个可以随便玩的接口**。

## 七、已知未做

- **不解析响应 schema**：只取 `id`/`status` 用于配对与成败判断，其余原文照抄进日志与
  面板「原始报文」。字段名是猜的，过早解释只会把猜测当读数。
- **不做长轮询订阅**：viwi 支持订阅事件（如车门状态变化推送），首版只做「点一次拉一次」。
- **不做写入联锁**：拿不到可信的行驶状态前，联锁只能是「用户自己看车停没停」。
