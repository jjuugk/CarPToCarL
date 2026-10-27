# `usb` 分支结论：无 root 时，有线 CarPlay 走不通

> 日期：2026-10-27 · 分支 `usb` · 状态：**已封存**
>
> 本文是"要不要做有线"这件事的完整判断，不是摘要。协议细节见
> [`usb-sender-design.md`](usb-sender-design.md)，早期的可行性推演见
> [`WIRED-CARPLAY-FEASIBILITY.md`](WIRED-CARPLAY-FEASIBILITY.md)。

---

## 一句话

**没有 root，手机侧的有线 CarPlay 到此为止**——不是"难"，也不是"再想想办法"：
Android 根本没给应用层留入口。

---

## 一、四道门，一道都过不去

| 门 | 实测事实 |
|---|---|
| **描述符归内核** | `/config/usb_gadget` 只有 uid 0 能写；Shizuku 拿到的是 shell（2000），照样 `Permission denied` |
| **HAL 只有固定 function** | `android.hardware.usb.gadget` 只认 `mtp/ptp/rndis/midi/audio_source`，**没有 NCM，更没有 vendor-specific**；调用还要 `MANAGE_USB`（`signature\|privileged`） |
| **FunctionFS 不对 App 开放** | `/dev/usb-ffs/*` 受 SELinux 保护，而且 ffs 实例本身要先由 configfs 建出来——又回到 root |
| **AOA 是反方向** | accessory 模式下 VID 是 Google 的 `0x18D1`，换不来「Apple vendor 接口 + NCM」的复合描述符，车机照样不认 iPhone |

也就是说：**手机要让车机在 USB 上认成一台 iPhone，就得改内核发出的描述符。
这一步没有 root 之外的入口。**

顺带一句：本仓库 `docs/WIRED-CARPLAY-FEASIBILITY.md`（2026-10-25 写）早就给出了同样的结论，
今天只是被这台机器证实。§7 把实测补了进去。

---

## 二、`usb` 分支的代码不是白写

1. **L1~L4 契约是硬事实，换平台照样用**
   VID `0x05AC`、**配置号必须为 4**（车机用 `0xC0/0x52 wIndex=4` 切 CarPlay 配置）、
   `0xC0/0x45` 读 USB 模式、首连端口 `62078`（lockdown）、USBMUX v2 帧头
   `proto|len|magic|seq|ack`、NCM 走 `NTB16`。
   这些是从 DiPlay（车机端接收器）反推出来的，**换任何硬件平台都直接复用**。

2. **`UsbMux` / `UsbMuxServer` 是纯 JVM**
   不碰任何 Android API，可以原样跑在 Linux + JVM 上——将来若走板子方案且选 JVM，
   这两块直接搬过去。

3. **`UsbGadgetProbe` 适合留在 App 里当一键自检**
   无 root 时如实输出"本机不可用"，避免以后再有人（包括我们自己）在 USB 上白转一圈。

---

## 三、无 root 剩下的唯一有线路径，以及它到底值不值

只有一条：**手机外接一块 USB gadget 板，当"iPhone 替身"**（`WIRED-CARPLAY-FEASIBILITY.md` 路线 B）。

| 方案 | 车机侧 | 手机 ↔ 板 | 问题 |
|---|---|---|---|
| **单口 Zero 2 W** | 有线 ✅（Apple VID + NCM） | **只能 Wi-Fi**（唯一 OTG 口给了车机） | 手机到板仍是射频，**现在的掉链/抖动一点不会消失**，只是多一跳 |
| **双口板**（Pi 4/5 + 两路 gadget） | 有线 ✅ | 有线 ✅（手机开「USB 共享网络」当 RNDIS 设备，**无需 root**） | 才真正"全有线"，但成本、供电、体积都翻倍 |

再叠两条既有事实：

- 车机侧 NCM 带宽上限约 **100M**；
- 无线按设置已经能跑 **60fps**（车联设置里调帧率即可）。

**有线换不来画质和延迟的数量级收益。** 唯一值得做板子的场景是：
**车机那个 Wi-Fi 热点本身不稳**——这时用板子当 AP 把它替掉，才有真收益。

---

## 四、建议

**本机不做有线**，把力气放回无线。现在真正掉体验的是：

1. 别克那轮 **42s 拆链循环**（催问重传 → 拆链 → 回连 → 又被"已有会话在处理中"拒绝）；
2. **重连时序**（`sessionStarting` 互斥、`connectCancelled` 残留）；
3. **帧率上限**（实测封顶 20fps，30fps 省电模式无效）；
4. **音频通路**（TTS 9241 已接，导航播报要上车复测）。

这四件都在无线链路上，**不依赖任何硬件**。

---

## 五、如果以后还想继续

| 方向 | 前提 | 工作量 |
|---|---|---|
| **无线继续打磨**（推荐） | 无 | 小，改现有代码即可 |
| **外接 gadget 板** | 买一块 Zero 2 W / Pi 4，接受"多一块板、多一根线、多一个供电" | 中：板子侧 configfs 脚本 + 手机侧抽 `Iap2Transport` 接口把 RFCOMM 换成 TCP；手机侧改动很小，`CSM`/认证/AirPlay 全部复用 |
| **手机本体 USB gadget** | 换一台**能 BL 解锁**的机器（一加/小米旧款/Pixel） | 大：见 `usb-sender-design.md` §5，第一步先跑 `UsbGadgetProbe` |

板子方案的最小验证顺序（不要反过来）：

1. 板子先只做 NCM（`functions/ncm.usb0`），确认车机能在 USB 上看到网卡并取到地址；
2. 再加 `f_fs` 的 vendor-specific 接口，确认车机开始发 iAP2——**能收到 `0x1d00` 就成功一半**；
3. 最后才接 AirPlay（复用现有代码）。

反过来做（协议先跑通再回头啃 USB）会在"描述符到底哪一位不对"上无限打转——
USB 侧没有日志，只有"车机不理你"。

---

## 六、把「没办法」逐条证伪

不想让结论停在"我觉得不行"，所以把所有可能让手机以 Apple 身份出现在 USB 上的机制列了一遍：

| # | 机制 | 结论 | 原因 |
|---|---|---|---|
| 1 | configfs 手搓 gadget（`/config/usb_gadget`） | ✗ | 由 init 以 root 独占；`adb shell`（uid 2000）写入即被拒，而 Shizuku 给的正是这个身份 |
| 2 | FunctionFS（`/dev/usb-ffs/*`） | ✗ | ffs 实例必须先由 configfs 建出来——绕回第 1 条；设备节点还有 SELinux 域限制 |
| 3 | `UsbGadget` HAL / `svc usb setFunctions` | ✗ | 只能在**出厂预置**的 function 间切换（mtp/ptp/rndis/midi/audio_source），既无 NCM 也无 vendor-specific；VID/PID 更不由它决定 |
| 4 | AOA 配件模式（`UsbAccessory`） | ✗ 对 CarPlay / ✓ 对 AA | 进入后 VID 固定是 Google 的 `0x18D1`，只有一类 vendor 接口 + 两对 bulk；但它正是 **Android Auto / CarLife 的 USB 通道**（见第 12 条） |
| 5 | 改 `ro.*` prop / build.prop | ✗ | 与 USB 描述符无关——描述符由 gadget 驱动按 function 拼装 |
| 6 | 加载自定义 gadget 内核模块 | ✗ | 需 root，且零售内核多开了模块签名校验 |
| 7 | 中间串一个"改写描述符"的转接 | ✗ | USB 没这个机制：无源 Hub 只做电气转发；能改描述符的必须是一台**主动设备**（即第 9 条） |
| 8 | 用提权漏洞拿 root | ✗ | 那就是 root，且不建议 |
| 9 | 外接 Linux 板（带 HS OTG）伪装 Apple 设备 | ✓ | 唯一可行：板子侧 configfs 随便写，手机侧只需把 RFCOMM 换成 TCP |
| 10 | 手机「USB 共享网络」给板 | ✓ **关键补丁** | **这一步无 root**：手机能当 RNDIS/CDC 网卡，不需要改任何描述符。于是"手机 ↔ 板"这段也能走线，整条链可做到全有线 |
| 11 | ESP32-S3 之类 MCU 自己当 gadget | △ | 描述符确实能随便写，但只有 USB **全速 12Mbps**；1080p60 的 H.264（10~20Mbps）跑不动，要 HS 的板子（Zero 2 W / Pi 4） |
| 12 | 干脆换协议：Android Auto / CarLife 有线 | ✓ 但非 CarPlay | 两者都基于 AOA，车机支持就零开发、插线即用；中国市场 GM 车机一般只有 CarLife，未必有 AA——得看车机菜单 |

### 唯一还没量过的变量

有一件事没在目标机上亲眼确认：`/config` 的权限位与 SELinux 域。理论上"万一它对 shell 组开放"还剩一条缝，
插上手机 10 秒可判：

```bash
adb shell id
adb shell ls -ld /config /config/usb_gadget 2>&1
adb shell cat /proc/mounts | grep -i configfs
```

- `/config` 是 `root root 0500/0700`（AOSP 默认）→ 连列目录都不行，第 1 条彻底断；
- 若 `usb_gadget` 对某个 shell 所属组可写 → 才有继续谈的必要（还要再过 SELinux）。

**结论措辞修正**：手机本体仍然不行；但"带一块板"这条路比 §三 说的更宽——
**可以做到全有线**（第 9 + 10 条），代价是一块双口板（Pi 4/5 级），而不是"手机侧只能 Wi-Fi"。
