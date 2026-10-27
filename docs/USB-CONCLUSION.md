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
| **描述符归内核** | `/config` 的 DAC 是 **0755**（比 AOSP 松），但 SELinux 对 shell 域**连 `search` 都没有**，`cd /config` 直接 `EACCES`；Shizuku 拿的正是 shell（2000） |
| **HAL 只有固定 function** | 可选 function 只有 `mtp/ptp/rndis/midi/audio_source`，**没有 NCM，更没有 vendor-specific**。`MANAGE_USB` 不是障碍——`com.android.shell` 已被预授（详见 §六 末尾实测） |
| **FunctionFS 不对 App 开放** | `/dev/usb-ffs` 属主就是 `shell`（DAC 有 `w`），SELinux 仍判 `{ write }` 拒绝；而且 ffs 实例本身要先由 configfs 建出来——又回到 root |
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

### 实测结果：`/config` 权限位与 SELinux 域（2026-10-27）

上一版这一节写的是"还没在目标机上亲眼确认，插上手机 10 秒可判"，并给了三条命令和一段理论推断。
2026-10-27 手机接上后量完了。**结果比推演更彻底，同时推翻了原文两处措辞。**

**目标机**：荣耀 `PTP-AN10`，Android 17（SDK 37），`ro.build.type=user`、`ro.debuggable=0`、`ro.secure=1`；
无 `su`；`adb root` 返回 `adbd cannot run as root in production builds`。

#### 复现命令

```bash
export MSYS_NO_PATHCONV=1            # Git Bash 下 /config 会被当 Windows 路径转换
ADB=.tools/android-sdk/platform-tools/adb.exe

$ADB shell id                              # uid=2000(shell) ... context=u:r:shell:s0
$ADB shell getenforce                      # Enforcing
$ADB shell stat -c '%A' /config            # drwxr-xr-x
$ADB shell ls -ldZ /config                 # 带 SELinux 标签
$ADB shell 'cd /config && echo OK'         # Permission denied → 触发 AVC
$ADB shell ls -ldZ /dev/usb-ffs            # 属主是 shell，DAC 有 w
$ADB shell mkdir /dev/usb-ffs/probe        # Permission denied → 触发 AVC
$ADB shell 'logcat -c -b all; <触发操作>; sleep 1; \
           logcat -d -b all | grep "avc:"' # 关键：此机 AVC 会进 logcat
$ADB shell grep -E '^Cap(Eff|Bnd)' /proc/self/status
$ADB shell 'ls /sys/module | grep -E "libcomposite|configfs|cdc_ncm"'
$ADB shell 'zcat /proc/config.gz | grep -E "USB_CONFIGFS|USB_F_NCM|USB_GADGET"'
$ADB shell 'readlink /sys/class/udc/*/gadget'
$ADB shell 'dumpsys package com.android.shell | grep -i MANAGE_USB'
```

> 这台机器的 AVC 拒绝**不进 `dmesg`（被拒读）但进 `logcat -b all`**，所以拒绝判词可以自己触发、自己抓回来。
> 这是本次取证能拿到精确 `perm/tclass/tcontext` 的原因。

#### 发现 1：`/config` 的权限位是松的，SELinux 才是墙

| 项 | 实测值 |
|---|---|
| `stat /config` | `drwxr-xr-x root root` = **0755**（**不是** AOSP 默认的 0500/0700） |
| 挂载 | `none on /config type configfs (rw,nosuid,nodev,noexec,relatime)` |
| SELinux 标签 | `u:object_r:configfs:s0` |
| link count | 7（约 5 个子项，gadget 全在里面） |
| `stat /config/usb_gadget` | `Permission denied`（连 stat 都不行） |
| `cd /config` | `Permission denied` |

0755 意味着 shell 作为 other 本有 `r-x`、能列目录。进不去是 SELinux 判的：

```text
avc: denied { search } for name="/" dev="configfs" ino=4006 \
  scontext=u:r:shell:s0 tcontext=u:object_r:configfs:s0 tclass=dir permissive=0
```

只拒了一个 `search` —— **策略里没有 shell → configfs 的任何 allow 规则**，不是"某一位权限没给"。
原文担心"万一它对 shell 组开放还剩一条缝"：**缝不存在**，但原因不是权限位紧，而是规则压根没写。

#### 发现 2：`/dev/usb-ffs` 更反直觉——属主就是 shell，照样写不进去

| 项 | 实测值 |
|---|---|
| `/dev/usb-ffs` | `drwxrwxr-x shell system u:object_r:device:s0` ← **属主 shell，DAC 上 `w` 齐全** |
| 已存在实例 | `adb` / `hdb` / `mtp` / `ptp`，均 `dr-xr-x---`、标签 `u:object_r:functionfs:s0` |
| `ls /dev/usb-ffs/adb` | `Permission denied`（`adb` 实例属主写着 `shell:system`，DAC 给 `r-x`） |
| `mkdir /dev/usb-ffs/probe` | `Permission denied` |

```text
avc: denied { write } for name="usb-ffs" dev="tmpfs" ino=2059 \
  scontext=u:r:shell:s0 tcontext=u:object_r:device:s0 tclass=dir permissive=0
```

这条把结论 2 从"受 SELinux 保护"精确成：**DAC 已经把目录交给 shell 了，SELinux 又把它拿回去。**
而且就算 mkdir 成功也没用——ffs 实例要靠 `mount -t functionfs` 挂上去，那是 `CAP_SYS_ADMIN`。

#### 发现 3：capability 也不够，放开 SELinux 也白搭

```text
CapInh: 0000000000000000
CapPrm: 0000000000000000
CapEff: 0000000000000000   ← effective 一个都没有
CapBnd: 00000000008000c0   ← bounded 只有 SETUID / SETGID / SYS_NICE
```

bounded set 里没有 `CAP_SYS_ADMIN`（mount）、`CAP_DAC_OVERRIDE`、`CAP_SYS_MODULE`（insmod）、`CAP_NET_ADMIN`。
**所以"给 shell 域加条 allow"这种假设性绕过也是死路**：即使策略放开，`mount -t configfs` 仍会 `EPERM`。

#### 发现 4：内核侧全是齐的——缺的不是驱动

| 项 | 实测 |
|---|---|
| `CONFIG_USB_GADGET` | `=y` |
| `CONFIG_USB_CONFIGFS` | `=y` |
| `CONFIG_USB_F_NCM` | `=y` |
| `CONFIG_ANDROID_USB_CONFIGFS_UEVENT` | `=y` |
| 已加载模块 | `libcomposite` / `configfs` / `cdc_ncm` |
| UDC | `a600000.dwc3`（高通 DWC3，`a600000.ssusb`） |
| UDC 的 gadget 链接 | `../../gadget.0` ← **不是 AOSP 的 `g1`** |
| 厂商 gadget 状态 | `sys.usb.config = hisuite,mtp,mass_storage,adb,hdb`，`sys.usb.configfs=9`，`sys.usb.controller=a600000.dwc3` |
| `ls /sys/class/net` | 没有 `usb0`/`ncm0`（NCM 未启用） |

**内核什么都有，缺的只是"谁有资格写 configfs"。**
给后来人的坑：这台机器 gadget 目录叫 **`gadget.0`**，任何写死 `usb_gadget/g1` 的脚本第一步就踩空。

#### 发现 5（措辞纠正）`MANAGE_USB` 其实不是门

原文结论 3 写"调用还要 `MANAGE_USB`（`signature|privileged`）"。实测：

```text
android.permission.MANAGE_USB: granted=true      # com.android.shell
usb_hal_version=20
port_manager: can_change_mode=true, current_mode=ufp, data_role=device
```

**shell 已被预授 `MANAGE_USB`**，HAL 是 2.0，端口支持改 mode。也就是说权限层是通的，卡的是**没有你要的那个 function**：

```bash
adb shell svc usb getFunctions     # 返回空 —— Honor 的 UsbService 不走 AOSP 的 functions 列表
```

结论 3 的正确表述因此改为：**`svc usb` 能调，但能选的 function 里没有 NCM / vendor-specific。**

> **这一步没跑**：`svc usb setFunctions ncm` 没有真正执行。它会重建 gadget、很可能踢掉 adb，
> 需要一台随时能重插的机器再做；且即便成功也只到 NCM，仍拿不到 Apple vendor 接口，对 CarPlay 无益。

#### 发现 6：App 侧（`untrusted_app`）只会更窄

App 进程域实测为 `u:r:untrusted_app_34:s0`（带 MLS category `c24,c257,c512,c768`，从本机 AVC 日志可见）。
`untrusted_app` 不持有 `MANAGE_USB`（`signature|privileged`，只有 shell 被预授），也没有任何 configfs 允许规则；
而 shell 是所有应用域里权限最宽的一个，它对 configfs 连 `search` 都没有。
**App 直接 `open("/config/usb_gadget/...")` 必然 `EACCES`**——不需要再单独验证。

#### 实测后的净结论

| 门 | 推演版 | 实测版 |
|---|---|---|
| 1 描述符归内核 | "只有 uid 0 能写" | **权限位 0755 是松的，但策略里没有 allow，连 `search` 都拒** |
| 2 FunctionFS | "受 SELinux 保护" | **DAC 主动把 `/dev/usb-ffs` 交给 shell，SELinux 又拒 `{ write }`** |
| 3 HAL / `svc usb` | "还要 `MANAGE_USB`" | **`MANAGE_USB` 有、HAL 2.0 有，缺的是 NCM/vendor function** |

三条都从"我认为"变成"实机判词"，且都更硬。**结论不变：手机本体不行。**

**结论措辞修正**：手机本体仍然不行；但"带一块板"这条路比 §三 说的更宽——
**可以做到全有线**（第 9 + 10 条），代价是一块双口板（Pi 4/5 级），而不是"手机侧只能 Wi-Fi"。
