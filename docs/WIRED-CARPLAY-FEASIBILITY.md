# 有线 CarPlay 可行性分析（USB 路线）

> **结论先行**：纯软件、无 root **不可行**；有 root 且内核带 configfs / FunctionFS **理论上可行，
> 但工程量大到不值得**；唯一现实的落地方式是在手机与车机之间加一块 USB gadget 硬件
> （树莓派 Zero 之类）当「USB 侧翻译器」。本项目选无线不是偷懒，是被 Android 的 USB gadget
> 架构卡住。

最后更新：2026-10-25 · 对应版本 1.0.2

---

## 1. 有线与无线只差「承载」这一层

两种 CarPlay 在 CSM（`0x4040`）以上**协议完全一样**，区别只在底下走什么：

| | 无线 | 有线 |
| --- | --- | --- |
| 控制通道 | iAP2 over **Bluetooth RFCOMM** | iAP2 over **USB bulk** |
| 数据通道 | **Wi-Fi**（车机开热点，手机连入，见 `0x5703`） | **CDC-NCM**（USB 里的以太网） |
| 地址分配 | 车机做 AP/DHCP，手机是客户端 | 由手机侧 NCM 端点分配，车机取地址（与无线相反） |
| 服务发现 | 手机在车机网段里广播 `_airplay._tcp` / `_carplay-ctrl._tcp` | 同一套 mDNS，只是跑在 NCM 网段 |
| 触发 | 蓝牙配对 → `0x4300` 邀请 | USB 枚举到「Apple 复合设备」即开始 |

也就是说：**有线的全部难点集中在第一步——让车机在 USB 上看见一台 iPhone。**
一旦枚举通过，iAP2 / MFi / AirPlay 这一整套（本项目已实现）可以直接复用，只是换一条链路。

---

## 2. 车机在 USB 上到底看到了什么

iPhone 插上后，车机（USB host）枚举到的是一个**复合设备**，至少两类接口：

1. **Apple 的 vendor-specific 接口**（iAP2 over **bulk**，不是 HID）
   车机据此认定「这是一台 iOS 设备」，并在 bulk 端点上跑识别 / 认证
   （`0x1d00`、`0xaa00`…`0xaa05`、`0x4301`）。
2. **CDC-NCM 接口**——USB 上的以太网卡。车机为它取 IP，AirPlay 的音视频流全走这条。

具体描述符数值各代 iOS 会变，这里不写死，避免误导。

关键：**这两类接口必须在 USB 描述符里声明，而描述符是「设备侧」在内核里发出的。**
Android 应用层没有「我要声明一个 vendor-specific 接口 + NCM」的入口。

---

## 3. Android 侧的四道门

| # | 门 | 现状 |
| --- | --- | --- |
| ① | USB gadget 归内核 | `/config/usb_gadget` 走 configfs，由 `init` 与 `UsbGadget` HAL 持有，App 摸不到 |
| ② | HAL 只给固定 function | `android.hardware.usb.gadget` 只认 `mtp / ptp / rndis / midi / audio_source`——**没有 NCM，也没有 vendor-specific** |
| ③ | 调 HAL 要系统权限 | `UsbManager.setCurrentFunctions()` 需要 `android.permission.MANAGE_USB`（`signature\|privileged`） |
| ④ | FunctionFS 也不对 App 开放 | `/dev/usb-ffs/*` 受 SELinux 保护，只有特定域能打开 |

补充一点常被误解的：**AOA（Android Open Accessory）是反方向的**。
那是「Android 当配件、PC/车机当主机」，能让我们以 accessory 身份被枚举，
但**换不来 iAP2 bulk + NCM 的复合描述符**，车机依然不会认它是 iPhone。

---

## 4. 三条可能的路

### 路线 A：root + configfs 手搓 gadget（理论可行，实际不建议）

前提：BL 解锁 → root；内核带 `CONFIG_USB_CONFIGFS`、`CONFIG_USB_CONFIGFS_NCM`、`CONFIG_USB_CONFIGFS_F_FS`。

要做的事：

1. 停掉 Android 自带的 gadget 服务，抢 UDC（`echo "" > UDC` 再重新绑）
2. `functions/ncm.usb0` 建 NCM；`functions/ffs.iap2` + 用户态经 `/dev/usb-ffs/iap2`
   **手写自定义描述符**
3. 伪造 Apple 的 VID/PID
4. 起 DHCP 给车机分配地址
5. AirPlay 服务绑到 `ncm0` 的地址上（现有代码基本可复用）
6. SELinux 全链路放行——否则 `init` 会在插拔、充电状态变化时把 function 列表重置回去

**难点不在协议，在「和 Android 抢 USB 控制器」。** `UsbGadget` HAL 与 `init.usb.rc` 会在
插拔、充电状态变化时重设 function，要稳定就得改 ROM（把 gadget 服务禁掉），不是装个 App 的事。

### 路线 B：外接一块 USB gadget 硬件（最现实）

用树莓派 Zero 2 W / Zero W（dwc2 支持 device 模式）当「USB 侧的 iPhone 替身」：

```
手机 ──Wi-Fi / USB──> 树莓派（gadget: vendor-specific + NCM）
                          └──USB 线──> 车机（以为插了台 iPhone）
```

树莓派只做**双向字节桥**（USB bulk ↔ TCP），手机侧把 iAP2 链路层从 RFCOMM 换成 TCP，
CSM / 认证 / AirPlay 全部复用现有代码。

App 侧需要的改动其实很小：

- 抽一个 `Iap2Transport` 接口（现在 `bt/BtSession.kt` 直接绑 RFCOMM）
- 加一个 `TcpTransport` 实现（连树莓派监听的端口）
- `AudioSender` / `VideoSender` 不变

代价：多一块板子、多一根线、多一个供电。收益只是「车机侧真走有线」，
带宽和延迟**不会有数量级差异**（NCM 也就 10/100M）。

### 路线 C：等现成 dongle（不存在）

市面上的 Carlinkit 一类盒子是**反方向**（有线车机 → 无线 iPhone），
没有「有线车机 ← Android 手机」的产品——需求太小，且要伪造 MFi 身份。

---

## 5. 为什么本项目不做

1. 目标机型（荣耀）**BL 锁死**，路线 A 第一步就走不通；零售机型 `adb root` 直接被拒
   （`adbd cannot run as root in production builds`）。
   **2026-10-27 再次实测确认：PTP-AN10 无 root，`/config/usb_gadget` 对 uid 0 以外一律拒绝，
   路线 A 在本机彻底关闭**（详见 `usb-sender-design.md` §7）。
2. 无线已经能跑 **60fps**（车联设置里把帧率调到 60 即可），有线换不来画质 / 延迟收益。
3. 有线的唯一额外好处是「不用连车机热点」，而热点这条路已经跑通（`0x5703` 自动入网）。
4. 车机侧**没有「必须插线才开放无线配对」的限制**——MIB3 实测纯蓝牙即可触发 `0x4300` 邀请，
   所以也没有「为了激活先插一次线」的刚需。

---

## 6. 如果将来要做，按这个顺序验证

1. 找一台**能 BL 解锁**的机器，确认内核支持 NCM：
   `zcat /proc/config.gz | grep -E "USB_CONFIGFS(_NCM|_F_FS)?"`
2. 先只做 NCM（`functions/ncm.usb0`），确认车机能在 USB 上看到网卡并取到地址
3. 再加 `f_fs` 的 vendor-specific 接口，确认车机开始发 iAP2——
   **能收到 `0x1d00` 就成功一半**
4. 最后才接 AirPlay（这一步复用现有代码）

不要反过来：协议层先跑通再回头啃 USB，会在「描述符到底哪一位不对」上无限打转——
USB 侧没有日志，只有「车机不理你」。
