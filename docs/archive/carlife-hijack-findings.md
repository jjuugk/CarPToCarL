# CarLife 劫持可行性验证结论（路线 A 实测）

> 目标：让本机原生 CarLife 投屏流量经 CarDeck 转换，实现「不依赖真车机的原生投屏」。
> 验证方式：PC 扮演 CarLife 车机（HU），对手机 `com.baidu.carlife.honor` 发起真实协议连接。
> 结论：**链路层完全可通，但鉴权阶段被官方防盗版/证书校验拦死，黑盒无法绕过。**

## 一、已确认可通的部分（这些都是实测结论）

| 环节 | 结果 | 证据 |
|---|---|---|
| 引擎可被外部拉起 | ✅ | `am start -n com.baidu.carlife.honor/com.baidu.carlife.CarlifeActivity`，进程 `com.baidu.carlife.honor` + `:remote` 起来 |
| 不需要蓝牙配对即监听 | ✅ | 拉起后 7240/8240/9240/9241/9242/9340/9440 **七端口全部接受连接**；荣耀车联 UI 的"连接车载蓝牙"只是 UI 门槛 |
| 版本协商 | ✅ | 发 `98305`（协议 v4.0）→ 手机回 `65538 matchStatus=1` |
| 统计信息 | ✅ | 发 `98343` → 手机开始推送 `65563`(前台)/`65560`(亮屏)/`65570`(唤醒录音)/`65574`(模块状态) |
| 手机设备信息 | ✅ | 收到 `65540`，解出真实机型：`HONOR` / `PTP-AN10` / `arm64-v8a` |
| 帧格式 | ✅ | 大端；Channel 1/6 = `[u16 len][u16 rsv][u32 type]`（8B），其余通道 = `[u32 len][u32 seq][u32 type]`（12B） |

首包实测 hex：`0004 0000 0001 8001 08 04 1000`（len=4, type=98305, protobuf major=4 minor=0）

## 二、卡死点：鉴权（手机永久静默）

发遍所有 HU 侧合法信令，手机**完全不回应**：

| 我们发了 | 期望手机回 | 实际 |
|---|---|---|
| `98307` HU_INFO（完整 21 字段设备信息） | `65611` 鉴权结果 | 无 |
| `98376` HU_AUTHEN_REQUEST（随机值） | `65609` 加密响应 | 无 |
| `98309` HU_BT_OOB_INFO（蓝牙 OOB） | `65542` | 无 |
| `98344` HU_MODULE_CONTROL（模块全开） | 模块状态更新 | 无 |
| `98311` HU_VIDEO_ENCODER_INIT（提前强推视频） | `65544` 编码参数 | 无 |
| 心跳 `131074` 每秒 1 次 | `131074` 回心跳 | 仅单向，手机从不回 |

现象特征：
- 只在**引擎刚拉起后的短暂窗口**内应答版本协商；窗口外连 `65538` 都不回（需 force-stop 重启引擎）
- 应答后维持连接 35 秒 ~ 3 分钟，随后被手机 RST
- UDP 7999 **从未见广播**（家 Wi-Fi 与手机热点两种网络下均无）

## 三、根因（APK 反编译证据）

反编译 `base.apk`（115MB，7 个 dex）后定位到：

```
com.baidu.carlife.handler.CarlifeConnectHsgHandler     // 连接消息总调度
com.baidu.carlife.authorization.PrivateKeyManager      // sendMDAuthenResult(65611)
com.baidu.carlife.core.connect.encrypt.EncryptSetupManager  // RSA/AES 密钥交换 65642/65644/65646
com.baidu.carlife.core.connect.box.BoxConnectClient    // 无线转换盒握手
com.baidu.carlife.protobuf.CarlifeVehicleCertificateProto  // 车机证书
```

关键字符串常量：

- `MSG_CMD_BOX_HELLO`、`MSG_CMD_HU_CERTIFICATE`、`recv CERTIFICATE: `、`setBoxAuthorized`
- `PIRACY_BOX_AUTHORIZED`、`PIRACY_BOX_NOT_ACTIVATED`、`PIRACY_BOX_OLD`、`COMMON_TRANSBOX_0003`
- `BoxConnectClient$BOX_TYPE_JUNYONG / LIAOYUAN / LIAOYUAN_V2 / SUDING / YUNDIAN`（已识别的盒子厂商白名单）

**即：荣耀版 CarLife 内置了车机证书校验 + 第三方盒子防盗版识别。** 假车机没有合法
`CarlifeVehicleCertificate`，引擎在鉴权前就把会话挂起（静默、不报错、不返回错误码），
这正是我们观察到的现象。这也解释了为什么 pc_tool 类工具能连通部分机型：那些机型的
引擎未启用同等强度的校验。

## 四、产物

| 文件 | 用途 |
|---|---|
| `scripts/carlife_hu_sim.py` | PC 扮演 CarLife 车机：7 端口连接 + 完整握手时序 + H.264 落盘 + 自动重连 |
| `scripts/probe_burst.py` | 消息试探探针：逐条发送候选 HU 信令，观察手机响应差异 |
| `scripts/scan_dex.py` | dex 扫描：按消息 ID 反查处理类 |
| `scripts/decompile_class.py` | 指定类的字节码级反汇编 |
| `scripts/find_msg_ids.py` | 按字段名反查常量值（Box/证书/鉴权） |

模拟器自测：本地假手机闭环 PASS；真机实测完成到「设备信息交换」阶段。

## 五、路线判断

| 方案 | 可行性 | 成本 | 结论 |
|---|---|---|---|
| 伪造车机证书通过鉴权 | 需逆向证书格式 + 私钥，可能调用云端校验 | 极高，且法律风险 | 不建议 |
| 用已授权盒子协议（BOX_HELLO）对接 | 需盒子厂商密钥，且有防盗版名单 | 高 | 不建议 |
| 系统层抓包转发（VpnService/回环） | 手机与 HU 直连、无中间人位置 | — | 结构上不成立 |
| **回落：移除 Shizuku 依赖（原方案）** | 方案已定型（内嵌 shell 服务 + 无线调试配对） | 中 | ✅ 推荐 |
| 备用手机 Tbox 架构 | CarLifeWirelessBox 已验证 | 中（需第二台手机） | 备选 |

**建议：CarLife 劫持路线到此收口，回到 Shizuku 移除方案。** 已获得的技术资产仍然有价值：
七端口协议栈、握手时序、帧格式、引擎拉起方式，都可用于未来与真车机/盒子对接时的调试。

---

## 六、补充：官方车机版 APK 逆向 + 荣耀车联"第一步"门槛（2026-10-23 深夜）

### 6.1 官方 HU 协议纠正（来自 carlife+车机版4.0.apk）

用户提供官方车机版 APK（4MB、单 dex、混淆 `d/b/a/a/**`），常量表在
`Ld/b/a/a/o/h/e/b;<clinit>`，HU 连接管理器 `Ld/b/a/a/p/m/a/c;`（carlife_pc_tool 正是逆向它而来）。

真实时序（逐指令核对）：

```
onConnectionAttached: 发 98305 (major=q0(), minor=0)
收 65538 matchStatus==1 → 发 98343 统计 + 启动 1s 心跳 + 30s 超时
收 65540 (MD_INFO)      → 回 98307 HU_INFO
收 65611 (authenResult) → 回 98380 ACK
收 65617                → 回 98386 HU_FEATURE_CONFIG_LIST
收 65544                → 回 98313 VIDEO_ENCODER_START
收 65551                → 再发 98311 HU_VIDEO_ENCODER_INIT
```

由此纠正了此前 4 个错误：
1. **HU 从不主动发 98376 鉴权请求**（等手机发 65611 即可）
2. **HU 不发 98309 蓝牙 OOB**（方向是手机→车机）
3. 特征配置正确 ID 是 **98386**（98318 是 PAUSE_MEDIA）
4. `CarlifeDeviceInfo` 真实字段序：os, board, bootloader, brand, cpuAbi, cpuAbi2,
   device, display, fingerprint, hardware, host, cid, manufacturer, model, product,
   serial, codename, incremental, release, sdk, (token), btaddress, carlifeversion，
   另有 sdkInt(整型)。**此前把字符串写进整型位会让对端 protobuf 解析异常而静默断链。**

### 6.2 荣耀车联的"第一步"门槛（来自 com.hihonor.auto APK）

- 页面：`com.hihonor.auto/.carlifeplus.connect.wlan.WlanGuideActivity`
- 状态机在 `b3.o`：字段 `c` 是 step（0=第一步蓝牙 / 1=第二步 WLAN），`h()` 读取它
- 第一步的"下一步"按钮由 `HwButton.setEnabled()` 硬禁用，**必须检测到车机蓝牙连接才解锁**
- 该页监听 `com.hihonor.auto.FOUND_DEVICE_FAILED` 等 HonorAuto 设备发现广播
- 手机里已配对过真车：`Audi_MMI_0000`、`吉利星愿`（说明该门槛就是"连上真车机蓝牙"）

### 6.3 实测状态

| 尝试 | 结果 |
|---|---|
| 直连 `am start CarlifeActivity`（盒/车机形态 UI） | 端口可连，偶发应答 65538（22:19/22:23/22:30/22:53 共 4 次） |
| 停在 WlanGuideActivity（无线页）不动 | 端口可连，65538 无应答 |
| 手机开热点给 PC 连 | 端口可连，20 次 98305 重发零应答 |
| App 掉到桌面/后台 | 完全无应答（引擎前台依赖） |

### 6.4 下一步可行路径（按性价比排序）

1. **物理条件满足**：若奥迪车机（`Audi_MMI_0000`）在蓝牙范围内，手机连上它 →
   第一步自动解锁 → 点"下一步"→ 引擎进入真正的无线等待态 → 用
   `scripts/carlife_hu_sim.py` 接手协议。这是最可能一次跑通的路径。
2. **伪装车机蓝牙**：把另一台设备（备用手机/开发板）的蓝牙 MAC 改成
   `Audi_MMI_0000` 的 MAC 并广播 HFP/SPP —— 需要 root 或 MTK 工具改 MAC，成本中高。
3. **收口**：回到 Shizuku 移除方案（已定型，见会话记录）。

---

## 七、突破：PC 冒充车机成功拿到原生投屏画面（2026-10-23 23:32）

**结果：PC 作为假车机，从手机 CarLife 引擎直推回真实车机画面 1280x720 H.264，
无需真车、无需 root、无需 Shizuku、无需蓝牙配对。**

实测码流：2.3MB / 25s，SPS×1 + PPS×1 + IDR×36 + P×671，PyAV 解码出完整车机 UI。

### 7.1 可复现的完整配方（关键顺序）

```
1. 手机：荣耀车联 → CarLife → 无线 WLAN 连接，停在"正在连接"（此状态引擎才应答协议）
2. PC：python scripts/carlife_hu_sim.py <手机IP>
3. 连 7 端口：7240 8240 9240 9241 9242 9340 9440
4. -> 98305 协议版本 3.0（payload 08 03 10 00）
      ← 65538 matchStatus=1
5. -> 98343 统计信息（channel=20029999, cuid=12345678, versionCode=16, versionName=4.0.0）
      ← 65563 前台 / 65540 手机设备信息 / 65574 模块状态(全 0)
6. -> 98307 HU_INFO（只发 1~8 全字符串字段，见下）
7. <- 65574 模块全 0 → 回 98344 MODULE_CONTROL 全部置 1
8. **关键：主动推 98386 特征配置**（CONNECT_TYPE=5 WiFi, I_FRAME_INTERVAL=300）
9. **关键：主动推 98311 视频编码初始化**（1280x720@30）
      ← 65544 手机确认编码参数
10. -> 98313 视频编码启动
      ← 8240 开始推 H.264 帧（首帧 SPS 0x67，之后 0x41 P 帧）
11. 每 1s 发 131074 心跳保活
```

### 7.2 三个决定性发现

1. **协议版本必须发 3.0，不能发 4.0**
   `CarlifeProtocolVersionInfoManager.updateVehicleVersion()`：
   `major==4` 或 `major==3&&minor==2` → `setNewVehicleVersion(true)` → 走加密鉴权
   （RSA/AES 65642/65644/65646），手机永远不发 65611，会话静默挂死。
   而手机支持列表是 {1.0, 2.0, 3.0, 4.0}，发 **3.0** 既能 match=1，又走老版明文路径。
   注意：`isNewVehicleVersion` 是**静态标记且只置 true 不复位**，被 4.0 污染过必须重启
   CarLife 进程（`am force-stop com.baidu.carlife.honor`）才能恢复。

2. **HU_INFO 字段号错位会静默断链**
   真实字段序（官方车机版 APK `CarlifeDeviceInfoProto$CarlifeDeviceInfo`）：
   os, board, bootloader, brand, cpuAbi, cpuAbi2, device, display, fingerprint,
   hardware, host, cid, manufacturer, model, product, serial, codename, incremental,
   release, sdk, token, btaddress, carlifeversion（另有 sdkInt 整型）。
   之前按猜测把字符串写进 `sdkInt` 位置 → 手机 protobuf 解析抛异常 → 静默 RST。

3. **车机必须主动推进，不能等手机**
   官方车机的时序是"回过 HU_INFO 就等手机发 65611"，但荣耀版引擎在本场景下不会主动发，
   必须由车机侧抢先发 `98386` 特征配置和 `98311` 视频初始化，手机才会回 `65544`
   并开始推流。视频协商不需要鉴权通过。

### 7.3 仍未打通 / 待做

| 项 | 状态 |
|---|---|
| 视频（8240 → H.264） | ✅ 已通 |
| 视频帧不含 12B 头？ | 需要再核对：当前按 `[u32 len][u32 seq][u32 type]` 解析成功 |
| 触控回传（9340） | 未接（可发 425985 CarlifeTouchAction） |
| 音频（9240 MEDIA / 9241 TTS） | 未接（CarlifeMusicInit 采样率需协商） |
| 麦克风/语音（9242 VR） | 未接 |
| 车机 UI 落地渲染 | 已用 PyAV 解码验证，需接 CarDeck 播放管线 |

### 7.4 对 CarDeck 的意义

这条链路等价于"软件实现的无线 CarLife 车机"，可以把手机原生 CarLife 投屏
直接接进 CarDeck 现有的 AirPlay/推流管线，不再依赖真车机。
