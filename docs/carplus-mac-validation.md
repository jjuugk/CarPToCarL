# ICCOA Car+ 在 Mac 上的验证

Car+ 实验构建使用独立包名 `io.github.shaonialife.carptocar.carplus` 和调试签名。接收端 SDK 把 ICCOA 会话画面解码到 Android Surface，现有 `VideoSender` 将该 Surface 的内容编码成 H.264，再经 CarPlay 屏幕通道发送。SDK 在 `:iccoa` 进程运行，通过 Binder 接收 Surface，避免 CarPlay 绑定本进程 Wi-Fi 时改变 ICCOA 的默认路由。首版验证范围是视频，画布为 1280×720、30 fps。

## 本地编译与单测

需要 JDK 17、Gradle 8.10.2、Android SDK 36（Car+）和 Android SDK 34（CarLife）。Car+ 清单显式启用 Android 的 16 KB 页面兼容模式，以运行 SDK 附带的原生库。由于仓库 Wrapper 指向 Windows 路径，`GRADLE` 请设为本机 Gradle 8.10.2 的绝对路径，并用 `local.properties` 配置 `sdk.dir`。可通过 `-Dorg.gradle.java.home="$JAVA_HOME"` 覆盖项目的 Windows JDK 路径。

```sh
"$GRADLE" -Dorg.gradle.java.home="$JAVA_HOME" \
  :app:testDebugUnitTest :app:assembleDebug

curl -fL https://raw.githubusercontent.com/PiggyBackBayonet/MiAuto/v0.1.1/app/stubs/icar-sdk-stubs.jar \
  -o /tmp/icar-sdk-stubs.jar
printf '%s  %s\n' 224fc1f5014f50a8f1670eb42f3c80625a2978ba5299fde2480a3c49eee2c242 \
  /tmp/icar-sdk-stubs.jar | shasum -a 256 -c

"$GRADLE" -Dorg.gradle.java.home="$JAVA_HOME" \
  -PcarPlus=true -PcarPlusSdkStubs=/tmp/icar-sdk-stubs.jar \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

接口存根用于编译核对，验证 APK 中不含运行时 SDK。应用会显示 SDK 缺失状态。单测 HTML 报告位于 `app/build/carplus/reports/tests/testDebugUnitTest/index.html`。

## Android 模拟器测试

启动一个 API 34 Android 模拟器，Apple Silicon 选 arm64 系统镜像。下面的 `emulator-5560` 是测试用模拟器序列号，用实际模拟器的序列号替换它。

```sh
adb -s emulator-5560 install -r app/build/carplus/outputs/apk/debug/app-debug.apk
adb -s emulator-5560 install -r app/build/carplus/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5560 shell am instrument -w \
  -e class io.github.shaonialife.carptocar.video.CarPlusVideoTransportTest \
  io.github.shaonialife.carptocar.carplus.test/androidx.test.runner.AndroidJUnitRunner
```

测试用 Android 解码器把样例 H.264 解码到 `VideoSender` 的输入 Surface。本地 TCP 接收端检查 1412×654 的车机画布声明和 AVC 配置，并用会话密钥验证三个发送帧都能解密为带长度前缀的 H.264 NAL。样例是 FFmpeg 生成的六帧 1280×720 蓝底视频。

## 打包单 APK 真机测试版

本地运行时构建使用 [MiAuto 0.1.1 发布 APK](https://github.com/PiggyBackBayonet/MiAuto/releases/tag/v0.1.1) 中的 ICCOA DEX、arm64 JNI 组件和签名配置。`ccd.data` 已与同标签的[公开资产](https://github.com/PiggyBackBayonet/MiAuto/blob/v0.1.1/app/src/main/assets/ccd.data)逐字节核对。运行组件放在工作区的忽略目录，构建任务检查 DEX、追加到 APK、重新对齐并用调试密钥签名。

在当前 Mac 上，从工作树根目录执行。将 `RUNTIME` 指到本机保存 DEX、`assets/ccd.data` 和 `jni/arm64-v8a/` 的目录。该任务需要 Python 3 和 Android Build-Tools 34.0.0。

```sh
"$GRADLE" -Dorg.gradle.java.home="$JAVA_HOME" -PcarPlus=true \
  -PcarPlusSdkStubs="$RUNTIME/../icar-sdk-stubs.jar" \
  -PcarPlusSdkDex="$RUNTIME/icar-sdk.dex" \
  -PcarPlusSdkAssets="$RUNTIME/assets" \
  -PcarPlusSdkJni="$RUNTIME/jni" \
  :app:packageCarPlusRuntime :app:assembleDebugAndroidTest :app:testDebugUnitTest
```

可安装的单 APK 位于 `app/build/carplus/outputs/apk/carplus-runtime/CarPToCar+ICCOA.apk`。运行模拟器的回环测试时，用它替换前一节安装的应用 APK，测试 APK 路径照常。

运行时包默认关闭 Android 调试标记，并继续使用同一包名和签名，可覆盖安装。ColorOS 在 4 KB 页面设备上也会对可调试包显示原生库对齐警告。需要用 `adb shell run-as` 读应用私有日志时，可加 `-PcarPlusDebuggable=true` 生成诊断包。

### ColorOS 单机重连包

用于一加真机的 APK 还需打包本机 ICCOA 转发组件。用 `scripts/carplus-relay/package-lock.json` 执行 `npm ci`，再运行以下命令。注入器与 `COPYING` 取自 Frida 17.26.2，脚本校验 Android arm64 注入器的发行摘要。

```sh
python3 scripts/carplus-relay/prepare.py "$ANDROID_HOME" "$JAVA_HOME/bin/javac" \
  scripts/carplus-relay/node_modules/.bin/frida-compile \
  /path/to/frida-inject-17.26.2-android-arm64.xz /path/to/COPYING /path/to/relay-assets
```

在上面的运行时构建命令中加入 `-PcarPlusRelayAssets=/path/to/relay-assets`。安装后由应用通过已有的 Root 授权启动本机转发，两个进程在电话上完成认证和视频交换。真机验收使用已保存的 ICCOA 配对记录，在 Android 测试中用 `-e colorosHardware true` 执行 `CarPlusStandaloneReconnectTest`，检查至少 75 秒的真实 H.264 帧能通过加密的 CarPlay 数据口。

另可传入与 `UCarAdapter` 接口匹配的 ICCOA 车端 SDK AAR 直接构建。如果 AAR 已包含 `assets/ccd.data`，可省略资产目录参数。

```sh
"$GRADLE" -Dorg.gradle.java.home="$JAVA_HOME" \
  -PcarPlus=true -PcarPlusSdk=/absolute/path/receiver.aar \
  -PcarPlusSdkAssets=/absolute/path/sdk-assets :app:packageCarPlusRuntime
```

## 使用与验收

安装运行时版，打开 **CarPToCar+ICCOA** 并完成首次授权。应用会自动启动 ICCOA 接收端，首页状态行显示配对码并提供重试入口。CarPlay 仍使用原来的「启动连接」入口与车机设置。首次 ICCOA 配对需要在手机原生 Car+ 中发现接收端并输入配对码。

Mac 模拟器可检查 SDK 初始化、BLE 广播返回和配对码生成。手机实测依次确认原生 Car+ 能发现并配对同机接收端、MIB3 收到第一帧、两条会话持续共存。
