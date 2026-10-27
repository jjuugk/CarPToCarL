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
| **双口板**（Pi 4/5 + 两路 gadget） | 有线 ✅ | 有线 ✅（手机作 host，板作 RNDIS 设备） | 才真正"全有线"，但成本、供电、体积都翻倍 |

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
