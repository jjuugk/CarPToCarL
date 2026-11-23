# 从 4580 固件看车机侧的 CarPlay 显示契约

> 2026-11-04 · 数据来源：`B9-4580`（`MHI3_AS_AU_P4580`，PackageVersion 1.0.17417，
> 整包 2025-06-26 / CP 板 2025-02-04）· 实车 `Audi_MMI_5983` / `AUDI 3810` / DELPHI

## 怎么读的

固件不是加密的，只是 VW/Audi 的 SWUP 封装（128 字节 `___ESO_SWUP_HEADER___` 头 + 镜像体）。
镜像体里稀疏零区很多、实体是未压缩的 Linux 文件系统内容，**可以直接 `strings` 抽出来**：

```
Data/MMX3.fs_opt_ivi_CL3D_MIB3H_AU_AS_G4x_2502501PROD/13/opt_ivi_C6.img.swup   2.3 GB
Data/MMX3.fs_root_ivi_CL3D_MIB3H_AU_AS_G4x_2502501PROD/13/ivi_root_C6.img.swup 356 MB
```

`CL3D` = 这台车（C8/B9 的 3810）。9/11/12 是 C3、13/14 是 C6/C7，指同一份软件的不同硬件变体。

## 一、车机侧是 Apple 官方 AirPlay 接收端

不是车厂自研的协议栈，所以它对流的合法性很挑：

```
libairplay.so
AirPlayReceiverSessionReadAudio / AirPlayReceiverSessionWriteAudio
APReceiverStatsCollector* / APSRTPJitterBufferRead
kAirPlayThreadPriority_ScreenReceiver = 17   // Receives screen frames
```

上层是大众自己的 `dio`（`CAirPlayHostThread`、`CIAP2ServiceJob_AirplaySessionStarted`）+
`smartphone_integration`（viwi 服务 `/displayconfiguration`、`ScreenObject.VideoStateEnum`）。
进程/用户模型：`carplay` uid 1122，网络接口 `carplay0`。

## 二、CarPlay 屏幕配置（`"screen":{…}` 原文）

```json
"screen":{
    "maxFPS":60,
    "latency":70,
    "cache":30,
    "badFramesThreshold":4,
    "videoDumping":false,
    "videoDumpingPath":"/var/tmp",
    "hid":{ "fingers":1, "testAppId":false },
    "esogfx":{
        "display":"Driver_Display",
        "displayable":"Displayable_External_Smarthphone",
        "displayableID":93,
        "layer":"Layer_Media_Base",
        "omxBuffers":5,
        "timestamps":false
    }
}
```

注释里两条关键语义（原文）：

- `badFramesThreshold` — “the number of **contiguously corrupted screen frames** before the
  screen session error will be reported to SI”
- OMX workaround — “falsely signaling the invalid header frame … the input buffers will be
  flushed and request for send”

也就是说：**解码走 OpenMAX，帧进的是 esogfx 的 displayable 93，不是标准 DRM/vkms。**

## 三、窗口尺寸是写死的：1412×654 @ (128,66)，物理屏 1540×720

HMI 布局表（原文，JS 对象）：

```js
{name:"carPlayDeviceG35Layout", width:1412, height:654,
 offsetX:128, offsetY:66, rightHandOffsetX:0}

{offsetY:0, offsetX:0, resolutionY:654, resolutionX:1412,
 physicalHeight:99, physicalWidth:214,
 smartphonetype:Carplay, windowMode:true}
```

对照实车 `/info displays[0]`：

| 字段 | 固件布局 | 实车 /info |
|---|---|---|
| widthPixels / heightPixels | 1412 / 654 | 1412 / 654 |
| widthPhysical / heightPhysical (mm) | 214 / 99 | 214 / 99 |

**逐字段一致**——全包里只有 G35 这个布局是 1412×654（另有 `carPlayDeviceG33Layout` = 1152×654，
是别的屏），所以这台车走的就是 G35。

物理屏等于窗口加边距：`1412+128 = 1540`、`654+66 = 720`，正好对上开发用的
`"test":{"hmi":{"screenResolutionWidth":1540,"screenResolutionHeight":720}}`。

**结论：`/info displays[0]` 报的 1412×654 就是 CarPlay 窗口本身，不是物理屏。**

## 四、物理分辨率不是硬编码，由 HMI 运行时给

```json
"video":{"sink":{"display":{
    "screenResolutionX":0, "screenResolutionY":0,
    "windowResolutionX":0, "windowResolutionY":0,
    "windowOffsetX":-1, "windowOffsetY":-1,
```

注释：`0 to use the values provided by the HMI`。所以分辨率没有编译期固定值，
一切来自 HMI 运行时（即 `/info`）——车机侧不会因为我们报了别的尺寸就"改契约"。

## 五、对我们「连上但全黑」的意义

固件能确定的是**契约**，不能直接给出运行期报错（车机日志我们拿不到），
所以下面区分「硬事实」与「推断」。

**硬事实**

1. CarPlay 画面只能落在 1412×654 的窗口里（displayable 93），窗口位置固定。
2. 接收端是 Apple 实现 + OMX 解码，`badFramesThreshold=4`：连续 4 个坏帧才会上报会话错误。
3. 时间戳默认**被忽略**（`timestamps:false`，为性能）——所以我们帧头里的 PTS 不影响显示。
4. 接收端对「头部帧（SPS/PPS）非法」有专门的 flush + 请求重发 workaround，
   说明**它确实会校验码流的头部**。

**推断（与实车日志吻合）**

- 4260 那天能出画，日志里 `SPS 解析 1412x654`——**码流正好等于窗口**。
- 3810 这次全黑，码流是 `1280x720`（引擎把 `1412×654@60` 降档成 `1280×720@20`）——
  **码流不等于窗口**。
- 两台车在**同一个固件包**里（`DUC4H426` / `DUC4H380` 都是这份 P1.0.17417 的变体），
  AirPlay 接收端是同一份代码，唯一的变量就是我们送进去的流。

所以在「仅改 VideoConfig 声明」之外，**更根本的解法是让码流本身就是 1412×654**：
声明、SPS、车机窗口三者全等，就不存在"谁按谁建解码器"的分歧。

## 六、下次上车的验证点

```
CARLIFE: 手机编码参数 1412x654@…（申请 1412x654@60）   ← 引擎这次真的接了
VIDEO:   ✓ VideoConfig 已发送 … 1412x654
VIDEO:   （不再出现）声明的画布 1412x654 与码流 1280x720 不一致
```

只要还出现「申请 1412×654、确认 1280×720」，就说明引擎在建流前没拿到车机尺寸，
要先解决**建流时序**，而不是继续调声明。

## 附：本包还证实的两件事

- **无线 CarPlay 走 `carplay0` 虚拟网口**（nftables 里有 `chain carplay_input`），
  与报告 §5.7 的 EA 隧道是两条不同拓扑。
- OOB BT 配对的开关在固件里是**按车型编码**的（注释：`only registered if Wireless
  CarPlay is coded`），与我们「不实现 OOB 也能连上」的实测结论一致。
