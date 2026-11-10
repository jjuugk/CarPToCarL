# CarPToCar+ICCOA

在同一台一加手机上接收系统 Car+（ICCOA）的车联画面和声音，再通过无线 CarPlay 转发到车机。车机上的触摸会回传给系统 Car+。手机锁屏后，已建立的会话仍可显示车联界面、播放声音和接收触摸。

> 这是个人实验项目，与 Apple、ICCOA 智慧车联开放联盟及汽车制造商无关联，未获得 MFi 认证。请在停车状态下完成配对、配置和测试。详见 [免责声明](DISCLAIMER.md)。

## 开发状态

当前开发版本为 **1.2.48**。项目仍在测试和修复问题，暂无公开安装包。[GitHub Releases](https://github.com/As-Care/CarPToCarICCOA/releases) 暂为空。

| 项目 | 说明 |
| --- | --- |
| 应用包名 | `io.github.shaonialife.carptocar.carplus` |
| 手机 | 内置系统 Car+ 且可向本应用授予 **Root** 的一加手机 |
| Android 版本 | 应用最低要求 Android 12。单机 Car+ 已在 ColorOS 17 的一加手机上测试，其他系统和机型的兼容性待验证。 |
| 接收端 | 支持无线 CarPlay 的车机，已在奥迪 MIB3 实车和 Mac 版 AllPlay 测试台验证链路。 |

## 使用说明

### 首次配对

1. 打开 CarPToCar+ICCOA，同意免责声明，按提示授予附近设备权限及 **Root** 权限，并开启手机的蓝牙和 Wi-Fi。
2. 在手机系统的蓝牙设置中与车机配对。回到本应用，在「连接准备 → 选择设备」选中车机。如果应用提示无法获取本机蓝牙地址，在 ColorOS 的「设置 → 关于本机 → 状态信息」查看地址，再填入本应用并保存。
3. 等待应用首页显示 ICCOA 配对码。点「打开系统 Car+」，在系统 Car+ 中选择本应用提供的接收端并输入配对码。这是手机内部的 ICCOA 配对，与上一步的车机蓝牙配对分别完成。
4. 回到本应用，等待车机连接。如已关闭「启动后自动连接」，点「启动连接」。系统或应用提示加入车机热点时，核对目标后确认。

### 日常连接

「启动后自动连接」默认开启。打开应用后，它会发起无线 CarPlay 连接。车机视频通道就绪时，如果已有 ICCOA 配对记录且系统 Car+ 尚未连接，应用会在接收端就绪后立即请求后台重连，无需每次打开系统 Car+ 页面。关闭此开关后，可以用「启动连接」手动发起。

首页显示「已连接」表示车机视频通道已建立。「等待 ICCOA 画面」表示还在等系统 Car+ 出帧。此时可点「重新连接 Car+」，它会尝试重新建立本机 ICCOA 会话，保留现有的车机连接。点「断开连接」会结束整次投屏会话。

### 流分辨率

默认读取车机上报的画布尺寸并请求系统 Car+ 按该尺寸出图。没有可用的车机尺寸时，ICCOA 接收端默认请求 1280×720。奥迪 MIB3 的 1412×654 是实测示例。

如果知道车机画布尺寸，可以在「显示 → 流分辨率」填写宽、高并保存。宽度支持 320–4096 像素，高度支持 180–2304 像素，两项都须为偶数。清空两项再保存即可恢复自动读取。设置在下次连接时生效。连接后，「显示」卡会分别列出车机上报尺寸和实际码流尺寸。

「历史连接」会按蓝牙设备保存车机上报的分辨率。点「一键输入」，或重新选择已有记录的设备，会把宽、高填入输入框。点「保存」后，下次连接生效。

ICCOA 版的目标帧率为 **30 fps**，实际帧率随画面更新变化。

### 权限与功能

| 权限 | 用途 |
| --- | --- |
| Root | 建立一加手机上系统 Car+ 与本应用接收端之间的本机连接 |
| 附近设备（蓝牙和 Wi-Fi） | 发现接收端、与车机建立蓝牙连接及加入车机热点。Android 12 / 12L 入网时还需位置权限和位置服务。 |
| 通知权限 | 显示前台服务的连接状态通知 |
| 通知使用权（可选） | 读取音乐应用提供的媒体会话信息，用于同步歌名、歌手和播放状态 |

音乐和导航播报来自 ICCOA 音频通道，无需录屏授权。触控目前支持单指点击和拖动。导航定位由手机提供。

## 工作原理

车机通过蓝牙 iAP2 与手机建立控制链路。应用通过 Wi-Fi 加入车机热点，并通过 AirPlay 将本机 ICCOA 接收端的画面和声音发给车机。车机的 HID 触摸事件经应用换算坐标后回传给系统 Car+。

| 通道 | 转发方式 |
| --- | --- |
| 视频 | 优先转发系统 Car+ 的 H.264 码流。源流尺寸、比例或编码规格不适合直通时，回退到 SDK 解码到 Surface，再由应用编码为 H.264。回退时会等比适配，比例不同时可能出现黑边。 |
| 音频 | 优先尝试 48 kHz 立体声 PCM 直传，按车机协商结果可回退到 AAC-LC。 |

## 故障排查

| 现象 | 检查步骤 |
| --- | --- |
| 车机一直显示「正在连接」 | 核对「连接准备」中的蓝牙目标和本机蓝牙地址，确认车机支持无线 CarPlay。首次加入热点时留意确认弹窗。 |
| 车机已连接，首页一直显示「等待 ICCOA 画面」 | 先点「重新连接 Car+」。如果首页显示 ICCOA 配对码，打开系统 Car+ 完成首次配对。后台重连超时时也可通过「打开系统 Car+」检查系统侧连接状态。 |
| 画面有黑边、裁切或触摸落点偏移 | 先将流分辨率的宽、高清空并保存，再断开重连。若仍出现问题，记下车机上报尺寸、实际码流尺寸和是否出现「切回 Surface」日志。 |
| 有画面但没有声音 | 确认车机选择了 CarPlay 音源且未静音。记录是音乐、导航播报还是两者都无声，以及是否发生在相机、通话或音源切换之后。 |
| 车机没有歌曲信息 | 确认本应用的「通知使用权」已开启，音乐应用正在播放且提供媒体会话。 |

复现后，展开首页底部的「运行日志」，确认「监听日志」已开启。点导出按钮会保存本次 App 启动以来的日志，长按可导出保留的历史日志。文件位于「下载 → CarPToCar+ICCOA」。反馈时可附上车机型号、应用版本、复现步骤和大致时间。

## 本地构建

ICCOA 真机测试包需要 JDK 17、Gradle 8.10.2、Android SDK 36、Build-Tools 34.0.0、Python 3，以及本机准备的 ICCOA SDK 和转发组件。依赖准备和测试方式见 [ICCOA 构建与 Mac 验证](docs/carplus-mac-validation.md)。

仓库的 Gradle Wrapper 和 JDK 默认配置指向原开发机的 Windows 路径。在 Mac 或其他环境上，用 `local.properties` 配置 `sdk.dir`，将 `GRADLE` 指向本机 Gradle 8.10.2 的可执行文件，`JAVA_HOME` 指向 JDK 17，`RUNTIME` 和 `RELAY_ASSETS` 指向已准备的运行时和转发组件目录。从仓库根目录运行：

```sh
"$GRADLE" -Dorg.gradle.java.home="$JAVA_HOME" -PcarPlus=true \
  -PcarPlusSdkStubs="$RUNTIME/../icar-sdk-stubs.jar" \
  -PcarPlusSdkDex="$RUNTIME/icar-sdk.dex" \
  -PcarPlusSdkAssets="$RUNTIME/assets" \
  -PcarPlusSdkJni="$RUNTIME/jni" \
  -PcarPlusRelayAssets="$RELAY_ASSETS" \
  :app:packageCarPlusRuntime
```

本地测试包位于 `app/build/carplus/outputs/apk/carplus-runtime/CarPToCar+ICCOA.apk`。安装和打开应用：

```sh
adb install -r 'app/build/carplus/outputs/apk/carplus-runtime/CarPToCar+ICCOA.apk'
adb shell am start -n io.github.shaonialife.carptocar.carplus/io.github.shaonialife.carptocar.MainActivity
```

运行时 APK 使用调试签名，默认关闭 Android 调试标记。需要用 `adb shell run-as io.github.shaonialife.carptocar.carplus` 读取私有日志时，构建命令需加 `-PcarPlusDebuggable=true`。仅用 SDK 接口存根可以编译和跑单测，真机投屏需要完整运行时。

## 工程结构

| 路径 | 职责 |
| --- | --- |
| [app/src/carPlus/](app/src/carPlus/) | ICCOA 接收端、配对和重连，SDK 在独立的 `:iccoa` 进程运行 |
| [app/src/main/](app/src/main/) | 界面、会话管理、蓝牙 iAP2、AirPlay 和音视频传输 |
| [app/src/carPlusTest/](app/src/carPlusTest/) | ICCOA Android 回环和真机测试 |
| [app/src/test/](app/src/test/) | 协议、编解码与其他 JVM 单元测试 |
| [scripts/carplus-relay/](scripts/carplus-relay/) | 构建单机 ICCOA 转发组件 |
| [docs/](docs/) | 构建、验证和协议笔记 |

## ICCOA 版本记录

**1.2.48（开发中）**。新增按蓝牙设备记录分辨率和一键填入。针对奥迪测试中出现的 UDP 发包错误缩小了 PCM 包，并加入车机接管或归还主音频时的暂停、续播处理。这两项行为待奥迪实车复测。

## 许可与致谢

源码以 [MIT 许可证](LICENSE) 发布。iAP2、AirPlay、蓝牙链路及工程骨架参考了独立项目 [CarDeck](https://github.com/shaonialife/cardeck)，版权与第三方声明见 [NOTICE](NOTICE)。

CarPlay、AirPlay、ICCOA、ColorOS 等名称归各自权利人所有，本文引用这些名称是为了说明兼容目标。使用条件和测试范围见 [免责声明](DISCLAIMER.md)。
