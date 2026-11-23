# CarLife 本地劫持 → 投屏到车机：App 集成说明

> 目标：**开启开关后，自动把手机原生 CarLife 的画面/声音劫持下来，经 CarDeck 现有
> AirPlay 通道投到车机**，车机触摸反向注入 CarLife。
> 协议已在 2026-10-23 用 PC 冒充车机实测跑通（见 `docs/archive/carlife-hijack-findings.md`
> 第七节，含真实车机界面截图与码流验证）。

## 一、架构

```
手机 CarLife 引擎（荣耀/百度版，监听 7240/8240/9240/9241/9340/9440）
        ▲  127.0.0.1 回环，扮演「车机」
        │  CarLifeHuClient：握手 / 收 H.264 / 收 PCM / 回传触控
   CarLifeHijack（总控）
        ├─ submitH264(annexB, keyFrame) → VideoSender 直通（跳过重编码）
        └─ PCM → CarLifeAudioSink（本机 USAGE_MEDIA 播放）
                          ↓ AudioPlaybackCapture（REMOTE_SUBMIX）
                     AudioSender（原有通路，AAC/PCM → RTP/ChaCha20）
        ▼
   车机（原 AirPlay 链路，不变）
```

三条链路的设计取舍：

- **视频**：不重编码。CarLife 推的就是 H.264，直接复用 `VideoSender` 现成的 NAL 切分、
  avcC 组装、加密与发送逻辑，画质无损、CPU 负担最低。
- **音频**：不新增 RTP 封装，而是把 PCM 在本机用 `USAGE_MEDIA` 播出来，让原有
  `AudioPlaybackCapture` 通路顺手带走。代价是几十毫秒的往返，收益是零改动、可复用
  已验证的加密/同步包/档位协商逻辑。
- **触控**：反查 CarLife 的 Touch 通道（9340），把车机触点换算成 CarLife 视频坐标直接
  回传，**不经过虚拟屏与 Shizuku**。

## 二、新增/修改文件

| 文件 | 说明 |
|---|---|
| `carlife/CarLifeProtocol.kt` | 端口、消息 ID、8B/12B 帧格式、手写 protobuf（协议版本 **3.0**）、音频包头、触控 ID |
| `carlife/CarLifeHuClient.kt` | 连七端口、完整握手、收视频、收 PCM、心跳、触控回传 |
| `carlife/CarLifeAudioSink.kt` | PCM 落地：AudioTrack 播放（自适应采样率/声道），限频统计日志 |
| `carlife/CarLifeHijack.kt` | 总控：预检 7240 → 直通模式 → 后台连接 → 帧/音频转发 + 触控映射；失败返回 false |
| `video/VideoSender.kt` | 新增 `startPassthrough()` / `submitH264()` / 直通 keepAlive；`sendVideoConfig`、`sendVideoFrame` 拆出 `*FromNals` 复用 |
| `input/TouchRouter.kt` | `onContact` 最前面加劫持分流：命中即回传 CarLife，不再走虚拟屏注入 |
| `SessionController.kt` | `retireSession()` 收掉劫持；视频装配处按开关分支；写入车机视图尺寸 |
| `launcher/settings/SettingsFragments.kt` + `res/layout/fragment_panel_display.xml` | 设置「显示」面板新增**本地 CarLife 劫持**开关 |
| `AndroidManifest.xml` | `<application android:allowAudioPlaybackCapture="true">`（自己的播放要能被自己抓到） |

## 三、开关

- 位置：设置 → **显示** → 本地 CarLife 劫持
- 偏好键：`settings` / `carlife_hijack_enabled`，**默认 false**（opt-in）
- 为什么默认关：必须先手动把 CarLife 停在「无线 WLAN 连接 / 正在连接」，否则预检
  1.5 秒内连不上 `7240`——开着只会让每次连接都白等这 1.5 秒
- 超时行为（2026-11-23 起）：**放弃劫持，本次会话没有车机画面**。隐形屏已随虚拟屏
  链路整体删除，不再回退到任何自绘画面源（车机侧有约 2s 看门狗，不能为劫持拖死会话）；

## 四、触控闭环（本次实现）

### 4.1 报文实证（反编译 `CarlifeTouchManager.dispatchTouchEvent`）

之前以为动作在负载里（`CarlifeTouchAction{action,x,y}`），实测反编译发现：

- `dispatchTouchEvent` 是 `packed-switch` 到 **0x68001 起的连续消息 ID**
- **动作由消息 ID 决定**，不是负载字段：
  - `425985` (0x68001) = **按下** → 解析 `CarlifeTouchSinglePoint{x, y, pointerx, pointery}`
  - `425986` (0x68002) = **移动**
  - `425987` (0x68003) = **抬起**
  - `425988` (0x68004) = 硬按键（负载是 `CarlifeCarHardKeyCode`，未接）
  - `425997` (0x6800D) = 复合动作（`CarlifeTouchAction`，含 action/pointer，未接）
- 帧格式是**控制帧 8B 头**（`[u16 len][u16 0][u32 serviceType]`），不是视频那种
  12B `[len][seq][type]`——9340 在手机端是按 `ConnectSocket` 读的

### 4.2 坐标映射

车机触摸报文在**车机通告的视图坐标系**（`setup.displayWidth × displayHeight`），
CarLife 视频是 1280×720（16:9），两者比例通常不同，所以按 **等比缩放 + 居中黑边
（contain）** 映射：

```
scale = min(viewW/videoW, viewH/videoH)
offsetX = (viewW - videoW*scale)/2
tx = (x - offsetX)/scale     // 同理 ty
```

比例一致时退化为线性缩放。`CarLifeHijack.setViewSize()` 由 `SessionController`
在 SETUP 阶段写入。

### 4.3 手势合成与节流

HID 报文只给 `down` 布尔，所以由 `handleTouch` 合成状态机：
`down && !pressed → DOWN`、`down && pressed → MOVE`、`!down && pressed → UP`。
MOVE 按 24ms 节流（与 `TouchRouter.moveThrottleMs` 同策略），避免事件堆积。
只处理 slot 0；其余槽直接吞掉，避免漏到虚拟屏注入路径。

## 五、音频（本次实现）

### 5.1 线格式（反编译 `PCMPackageHead` / `PCMMediaManager`）

音频通道是**裸字节流**，每包 = 12B 包头 + 负载：

```
[0..3] 负载长度 BE | [4..7] 时间戳 ms BE | [8..11] 包类型 BE
类型：0x30001 INIT（负载 CarlifeMusicInit{sampleRate,channelConfig,sampleFormat}）
      0x30006 NORMAL（负载裸 PCM，16bit）
```

手机端不需要车机请求——本地一播放媒体就推。车机只需被动接收。

### 5.2 为什么走「本机播放 + 播放捕获」

`AudioSender` 的采集点是 `AudioPlaybackCapture`（REMOTE_SUBMIX，音量应用点之前），
所以把 PCM 用 `USAGE_MEDIA` 播出来，原有通路就会把它推给车机：

- 不用改音频发送端、不重编码、复用已验证的 ChaCha 加密与同步包
- `AudioSender` 把 STREAM_MUSIC 静音到 0，但静音在音量应用点之后，
  **不影响**采集数据（项目内已有实测结论）——所以手机不响、车机有声
- 前提：清单里 `allowAudioPlaybackCapture="true"`（否则抓不到自己的播放）

### 5.3 只在音频通道开启时才播放

`routeAudio()` 会检查 `SessionController.audioSender?.isRunning`。本次连接若没勾
「开启声音」，AudioSender 不存在，本地播放只会从手机喇叭外放（白耗电），
所以直接丢弃并限频提示：
`CARLIFE: 收到 CarLife 音频 xxKB，但本次连接未开启声音…已丢弃`

## 六、验证状态

| 项 | 状态 |
|---|---|
| 编译（`:app:compileDebugKotlin`） | ✅ 通过（Gradle 8.10.2 + JDK 17 + SDK 34） |
| PC 端协议链路（视频 + 手绘 protobuf） | ✅ 实机跑通（2026-10-23） |
| App 内视频直通 | ⏳ 待装机验证 |
| 触控回传 | ⏳ 待装机验证（报文格式来自反编译，**字段号 1/2 为 x/y 的推断**） |
| 音频 | ⏳ 待装机验证（依赖 REMOTE_SUBMIX 能抓到自身播放） |

**装机验证要点**：
1. 荣耀车联 → 无线 WLAN 连接，停在「正在连接」
2. 设置里打开「本地 CarLife 劫持」，勾选「开启声音」启动连接
3. 日志看 `CARLIFE: 劫持成功…` → `触控 (x,y) → (tx,ty)` → `音频 xx 包`
4. 若触点镜像/偏移：多半是 `CarlifeTouchSinglePoint` 的 x/y 字段号或车机是否
   letterbox 的假设有偏差，改 `CarLifeProtocol.touchPoint` 与 `CarLifeHijack.handleTouch`
   的 mapping 即可

## 七、待办

1. **硬按键回传**：`425988`（音量/旋钮）+ `CarlifeCarHardKeyCode`，车机旋钮可复用
2. **多指**：`CarlifeTouchAction`（0x6800D，带 pointerx/pointery）
3. **兼容性**：协议版本固定 3.0；若遇到只支持 4.0 的机型，需要补 RSA/AES 鉴权
   （`65642/65644/65646`）

## 八、构建

本机已备好完整工具链（都在 `.tools/`，不污染系统）：

- JDK 17：`.tools/jdk-17.0.20.1+1`
- Gradle 8.10.2：`.tools/gradle-8.10.2`
- Android SDK：`.tools/android-sdk`（build-tools 34.0.0 + platforms/android-34）
- Maven 镜像：`.tools/init-mirror.gradle`（腾讯云）

```bash
export JAVA_HOME=.tools/jdk-17.0.20.1+1
export ANDROID_HOME=.tools/android-sdk
.tools/gradle-8.10.2/bin/gradle -I .tools/init-mirror.gradle :app:assembleDebug
```
