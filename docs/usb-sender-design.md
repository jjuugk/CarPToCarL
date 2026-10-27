# 发送端 USB 通道：从 DiPlay 反推 + 本仓库落地

> **分支状态：已封存（2026-10-27）**。目标机 PTP-AN10 无 root、BL 不可解锁，
> 手机侧 USB gadget 路径不存在入口，因此本分支**不做合并、不发版**。
> 保留内容 = 可复用的协议契约（§2）+ 纯 JVM 的 `UsbMux`/`UsbMuxServer` + `UsbGadgetProbe` 自检。
> **30 秒版结论见 §7**。

> 日期：2026-10-27 · 分支 `usb` · 参考项目：[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)（GPL-3.0）
>
> **许可证边界**：DiPlay 是 GPL-3.0，本仓库是 MIT。本文与代码**只提取协议事实**（消息号、字段偏移、
> 描述符类别），未复制其实现代码；后续若真要搬代码，必须先把本仓库切到 GPL-3.0 兼容许可。

## 0. 一句话结论

USB 发送端在协议上完全可镜像，**唯一的门槛是安卓设备侧能不能当"自定义 USB 设备"**：
需要 root + 内核 `usb_f_fs`/`libcomposite`/`usb_f_ncm` + 能抢占 UDC。

**2026-10-27 追加（最终）**：目标机没有 root、BL 不可解锁 → 这个门槛在本机**无法满足**，
本分支因此封存。下面 §0 保留立项时的判断，§7 是落地后的最终结论，两处不冲突：
协议侧结论不变，硬件侧结论从"需要 root"变成"拿不到 root"。

本分支已落地**与硬件无关、可单测的那一层**（USBMUX v2 编解码 + 设备侧应答器 + gadget 能力探测
+ configfs 计划书，16 项单测），剩下的三块（FFS 描述符/ep0 应答、lockdown 服务、NCM 承载网）
依赖真机 root 才能验证。

## 1. DiPlay 是什么，为什么它的 USB 侧能反推发送端

DiPlay 是**车机端接收器**（Android 车机上跑）：iPhone 插进来时，车机是 **USB 主机**，
iPhone 是设备。我们要做的是**镜像**：手机当设备、车机当主机。

四条链路在 DiPlay 里有明确实现（车机侧），反向即可得到我们的责任：

| 层 | 车机侧（DiPlay，USB 主机） | 我们（发送端，USB 设备）要做的 |
|---|---|---|
| L1 枚举/配置切换 | 只认 Apple VID；发厂商请求切配置 | 以 **VID `0x05AC`** 现身；提供**第 4 套配置**并应答该请求 |
| L2 USBMUX v2 | 打开 Apple 复用器 bulk 对，做版本握手后开 TCP | 做**服务端**：回版本帧、按端口分发内部 TCP |
| L3 lockdown | 连 `62078` 拿设备信息/触发 CarPlay | 提供**苹果 plist 协议**的最小 lockdown 服务 |
| L4 NCM | 声明 CDC-NCM 接口，搬 NTB16 以太网帧 | 提供 **NCM 设备功能**，承载 AirPlay 的 IP 链路 |

## 2. 已确证的协议事实（每条都带出处）

### L1 描述符与配置切换

`shared/.../transport/IphoneUsbHost.kt`：

```kotlin
// 176-184：切 CarPlay 配置的厂商请求
connection.controlTransfer(USB_VENDOR_DEVICE_IN /*0xC0*/, CARPLAY_CONFIGURATION_REQUEST /*0x52*/,
    0, CARPLAY_CONFIGURATION_INDEX /*0x0004*/, response, 1, 1000)

// 406-414
private const val USB_VENDOR_DEVICE_IN = 0xc0
private const val GET_USB_MODE_REQUEST = 0x45   // usbmuxd 读 4 字节模式
private const val CARPLAY_CONFIGURATION_REQUEST = 0x52
private const val CARPLAY_CONFIGURATION_INDEX = 0x0004
private const val VENDOR_RESPONSE_LENGTH = 1

// 416：usbmuxd 在初始模式读到 3:3:3:0，设置模式后读 5:3:3:0
```

⇒ 我们作为设备必须：VID=`0x05AC`；`bConfigurationValue=4` 的配置里放 CarPlay 功能；
对 `0xC0/0x52/wIndex=4` 回 1 字节；对 `0xC0/0x45` 回 `3:3:3:0` 或 `5:3:3:0`（4 字节 ASCII）。

### L2 USBMUX v2 线格式

`Iap2UsbMuxHost.kt` 的 `sendFrame()` / `sendTcp()` / `takeFrame()` / `dispatchTcp()`：

```
帧头 16B：proto:u32 | totalLength:u32 | magic:u32(0xFEEDFACE) | seq:u16 | ack:u16
proto=0  版本：整帧 20B（头 + u32 version=2）；iPhone 原样回同形包，不要等 proto=1
proto=2  setup：头 + 1B 0x07
proto=6  TCP ：头 + 20B TCP 头 + payload
         TCP 头：srcPort:u16 | dstPort:u16 | seq:u32 | ack:u32 | 0x50 | flags:u8 | window:u16(512)
```

两个坑（DiPlay 源码注释里明写）：

1. **接收方向不能校验 `magic`**——iPhone 的回包不带 `0xFEEDFACE`，只信任 `length`；
2. 上一个实例残留的 TCP 帧会被误当成握手应答 → 握手阶段要容忍最多 32 个陈旧 TCP 帧。

首个 TCP 连接固定是 `62078`（lockdown）。

### L3 / L4

- lockdown：车机打开 `62078` 拿设备身份并触发 CarPlay 会话；这一步必须是 plist 协议的**服务端**。
- NCM（`NcmFunctionDiscovery.kt`）：控制接口 `class=0x02 subclass=0x0d`，数据接口 `class=0x0a`
  取 **alt setting 1**（bulk 对）；另有苹果私有以太网 `0xff/0xfd/0x01` 备选。
- `NcmUsbBridge.kt`：以太网帧按 **NTB16** 块搬，说明 NCM 侧就是标准 CDC-NCM，不是私有格式。

## 3. 本分支已实现（`app/src/main/java/.../usb/`）

| 文件 | 作用 | 可测性 |
|---|---|---|
| `UsbMux.kt` | 帧编解码、增量拆帧、TCP 段解析；字段偏移与 DiPlay 逐字对齐 | 单测（布局/粘连/半帧/坏长度） |
| `UsbMuxServer.kt` | **设备侧** mux 应答器：版本握手原样回、按目标端口挂本地服务、无监听回 RST、双向数据泵、FIN 收尾 | 单测（用内存回环 + 队列 socket 模拟车机） |
| `UsbGadgetProbe.kt` | 能力探测：root / UDC / configfs / `/dev/usb-ffs` / `usb_f_*` 模块，输出一行人话结论 | 单测（注入命令输出） |
| `UsbGadgetPlan.kt` | configfs 计划书：VID `0x05ac`、**4 套配置**（CarPlay 落第 4 套）、`ffs.usbmux`/`ffs.iap2`/`ncm.usb0`、UDC 绑定拆成单独一步 | 单测（路径/内容/顺序） |

设计取舍：

- **为什么不复用 `Iap2LinkChannel`**：那是 iAP2 CSM 帧层，USB 这里是「复用器 + 内部 TCP」，
  两者串联而非替代。等 L3 打通后，把 mux 里的 iAP2 端口的字节流包成 `DuplexStream` 即可直接复用现链路。
- **UDC 绑定单独一步**：FunctionFS 守护进程没写好描述符就 `echo > UDC`，内核直接 `-ENODEV`。
- **4 套配置**：configfs 的 `bConfigurationValue` 由内核按注册顺序从 1 递增，所以必须建 4 个 config，
  CarPlay 功能挂第 4 个，才能对上 `wIndex=0x0004`。

## 4. 硬门槛（务必先确认，否则后面的活都是空转）

```bash
adb shell id -u                       # 0 = 有 root；2000 = 只有 shell
adb shell ls /sys/class/udc           # 空 = 这台机没有设备侧控制器
adb shell ls -d /config/usb_gadget    # 不存在 = 内核没开 configfs
adb shell ls /dev/usb-ffs             # 不存在 = 没有 FunctionFS 目录
adb shell ls /sys/module | grep -E 'usb_f_|libcomposite'
```

满足条件才是"USB 可尝试"：

1. **root**：`/config/usb_gadget` 只有 uid0 可写，Shizuku 的 shell(2000) 不行；
2. **`usb_f_fs`（FunctionFS）+ `libcomposite`**：自定义描述符只能走 FFS；
3. **`usb_f_ncm`**：没有它 mux 也许能通，但 AirPlay 的 IP 承载起不来；
4. **能抢 UDC**：同一 UDC 只能绑一个 gadget，必须先解绑 Android 自带 `g1`（**会断 adb / U 盘 / USB 共享**）。

> 荣耀 PTP-AN10 大概率锁 BL、拿不到 root。真要走这条线，建议一台可 root 的测试机
> （一加/小米旧款/ Pixel）。

## 5. 剩余工作（按依赖顺序）

1. **FFS 描述符 + ep0 setup 应答**：写 `ffs.usbmux` 的配置/接口/端点描述符；处理
   `FUNCTIONFS_SETUP` 事件，应答 `0xC0/0x52`（1B）与 `0xC0/0x45`（`5:3:3:0`）；
   处理 `FUNCTIONFS_ENABLE` 后打开 bulk 端点，包成 `DuplexStream` 交给 `UsbMuxServer`。
2. **lockdown 服务**：`62078` 上的 plist 协议（GetValue / QueryType / 配对记录），
   让车机认为对面是台"可信 iPhone"。这是最容易被车机卡住的一环，需要抓真机对比。
3. **iAP2 over USB**：确认 iAP2 走 mux 内某个 TCP 端口还是独立 vendor 接口，
   然后接到现有 `Iap2SenderSession`（它只认 `DuplexStream`，接上即可复用全部状态机）。
4. **NCM 承载网**：`usb0` 起来后配 IPv6 link-local、监听/浏览 `_airplay._tcp`，
   把 `SessionController` 里"加入车机热点"的分支换成"走 NCM 网卡"。
5. **通路选择**：USB 与现有蓝牙通路并存时的优先级、失败回退（蓝牙仍是默认）。

## 6. 复现命令

```bash
# 单测（16 项）
./gradlew testDebugUnitTest --tests "io.github.shaonialife.carptocar.usb.*"

# 生成 gadget 脚本（root 后手工执行，仅调试用）
#   UsbGadgetPlan.toShell(UsbGadgetPlan.build())  →  su -c sh -s
#   再启动 FFS 守护进程，最后 UsbGadgetPlan.bindCommand("<udc>")
```

---

## 7. 无 root 的最终结论（2026-10-27 实测确认）

目标机 **PTP-AN10（荣耀）没有 root，BL 也不可解锁**。这一条把本分支在**手机侧**的执行路径
彻底关闭，不是"难"，是**没有入口**：

| 门 | 事实 |
|---|---|
| 描述符归内核 | `/config/usb_gadget` 仅 uid 0 可写；Shizuku 拿到的是 shell(2000)，照样 `Permission denied` |
| HAL 无自定义能力 | `android.hardware.usb.gadget` 只有 `mtp/ptp/rndis/midi/audio_source`，**没有 NCM，更没有 vendor-specific**；且调用需 `MANAGE_USB`（signature\|privileged） |
| FunctionFS 不对 App 开放 | `/dev/usb-ffs/*` 受 SELinux 保护，何况 ffs 实例本身要先由 configfs（=root）创建 |
| AOA 是反方向 | accessory 模式下 VID 是 Google 的 `0x18D1`，换不来「Apple vendor 接口 + NCM」的复合描述符，车机仍不认 iPhone |

### 本分支代码的保留价值（不是白写）

1. **L1~L4 契约**（VID `0x05AC`、配置号必须 4、`0xC0/0x52 wIndex=4`、`0xC0/0x45`、首连 `62078`、
   mux 帧头布局、`NTB16`）是从 DiPlay 逆向出的硬事实，**换任何硬件平台都直接复用**。
2. `UsbMux` / `UsbMuxServer` 是**纯 JVM**（不依赖 Android API），可原样跑在 Linux + JVM 上——
   如果将来板子方案选「Pi 上跑 JVM」，这两块直接搬。
3. `UsbGadgetProbe` 适合留在 App 里当**一键自检**：无 root 时如实输出"本机不可用"，
   避免以后再有人（包括我们自己）在 USB 上白转一圈。

### 无 root 剩下的唯一有线路径，以及它到底值不值

只有一条：**手机外接一块 USB gadget 板当"iPhone 替身"**（`WIRED-CARPLAY-FEASIBILITY.md` 路线 B）。
诚实记账：

| 方案 | 车机侧 | 手机↔板 | 问题 |
|---|---|---|---|
| 单口 Zero 2 W | 有线（Apple VID + NCM） | **只能 Wi-Fi**（唯一 OTG 口给了车机） | 手机到板仍是射频，**现在的掉链/抖动不会消失**，只是中间多一跳 |
| 双口板（Pi 4/5 + 两路 gadget） | 有线 | 有线（手机作 host，板作 RNDIS 设备） | 成本、供电、体积、两套 gadget 并存；才真正"全有线" |

再叠加两条既有事实：车机侧 NCM 上限 ~100M，而无线按设置已经能到 60fps——
**有线换不来画质与延迟的数量级收益**，收益只在"车机 Wi-Fi 热点本身不稳"这一种情况下成立。

### 结论

**为本机不做有线。** 精力放回无线链路的稳定性（拆链循环、重连时序、帧率上限、音频通路），
那才是当前真正影响体验的地方。将来若有可解锁设备，按 §5 的顺序验证，第一步先跑 `UsbGadgetProbe`。
