# CarPToCar+ICCOA 开发交接

2026-11-09，上海时间

## 最终验收

**一台一加 Ace 7 Pro 上，ColorOS 原生 Car+ 选择 ICCOA，和同机运行的 CarPToCar+ICCOA 完成真实配对。应用接收原生 Car+ 画面，再通过无线 CarPlay 投到 2022 款奥迪 A4L 原厂 MIB3。** Mac 和 ADB 只用于调试。用户已确认验收重点是 ICCOA 输入和 CarPlay 输出。高德路线接续属于后续场景。

**目前已经做到原生 Car+ 弹出「MiAutoCar」连接和输码界面，也在一次实验中让同机两端收到 GATT 已连接回调。完整配对还失败。** 最后一轮的 PIN 已提交，GATT 服务仍未发现，Car+ 超时。还没有原生 Car+ 配对成功、实际画面输入或端到端投屏的证据。

## 工作区与现有 APK

| 项目 | 位置或状态 |
| --- | --- |
| 工作区根目录 | `/Users/ashang/code/githubFile/CarPToCarO` |
| 实际修改目录 | `.tools/worktrees/v1.2.25` |
| 分支及基线 | `codex/carplus-v1.2.25`，基于上游 `v1.2.25`，提交 `7b729fba986492aab0cd492b98ef8d1ad1ec7377` |
| Git 状态 | 改版代码在该 worktree 中，有未提交修改和未跟踪文件。根目录 `main` 是较新的上游版本 |
| 手机上安装的测试包 | [CarPToCar+ICCOA.apk](./dist/CarPToCar+ICCOA.apk) |
| 包名和版本 | `io.github.shaonialife.carptocar.carplus`，`1.2.25-carplus-debug`，versionCode `10225` |
| APK SHA-256 | `65a73be48e094a4ca5814143e380e7240a948879f6a1d84b5aa44784a7c3b110` |
| Android 工具 | `.tools/android-sdk/platform-tools/adb`，SDK 34，Build-Tools 34.0.0 |
| 本地编译环境 | `.tools/temurin17/Contents/Home`，`.tools/gradle-8.10.2/bin/gradle`，`.tools/gradle-user-home` |

从根目录先跑 `git worktree list` 和 `git -C .tools/worktrees/v1.2.25 status --short --branch`。已有改动需要保留。调试工具和运行时素材都在 Git 忽略的 `.tools/` 下，交接应在这台 Mac 的现有目录中继续。

### APK 已有实现

- 使用真实 ICCOA 车端 SDK，组件在 `.tools/references/miauto/runtime/`。包含 `icar-sdk.dex`、`assets/ccd.data`、`jni/arm64-v8a/`，编译存根在同级 `icar-sdk-stubs.jar`。来源是 [MiAuto 0.1.1 公开发行包](https://github.com/PiggyBackBayonet/MiAuto/releases/tag/v0.1.1)，`ccd.data` 与同标签公开资产核对过。
- `CarPlusSdk.kt` 处理 SDK 启动、广播、配对码和连接回调。`CarPlusReceiverService.kt` 在 `:iccoa` 进程运行 SDK。`CarPlusReceiver.kt` 通过 Messenger 跨进程传递状态和 Surface。分进程是为了避免 CarPlay 的 Wi‑Fi 绑定影响 ICCOA 接收。
- `CarPlusVideo.kt` 和现有 `VideoSender.kt` 将 Surface 画面编码成 H.264，沿用原版 CarPlay 输出。Mac 上改版 436 项单测通过，Android 模拟器用样例 H.264 验证了视频回环和 3 帧 CarPlay 数据解密。早期真机日志也出现过 CarPlay 视频数据端口连通和 ICCOA 编码器就绪。
- 用户已用 Cardeck 在这台一加和奥迪上投屏成功。Cardeck 自己创建虚拟显示，当前项目仍以原生 ICCOA 输入为验收。现有无线 CarPlay 输出也应继续保留。
- 已按用户要求完成 APK 命名、首页 ICCOA 文案、GPS 说明、图标和可选 MIB3 控制功能移除。普通 CarPlay 与奥迪 MIB3 的连接逻辑仍在。
- APK 的 BLE 日志已经区分「广播请求已提交」和 Android 实际回调 `onAdvertisingSetStarted status=0`。SDK 启动时还可能出现第一轮 `10003`，这个值是连接失败码。

### 重建 APK

完整说明见 [工作树的 Mac 验证文档](./.tools/worktrees/v1.2.25/docs/carplus-mac-validation.md)。从工作区根目录执行以下命令。

```sh
repo="$PWD"
export JAVA_HOME="$repo/.tools/temurin17/Contents/Home"
export GRADLE_USER_HOME="$repo/.tools/gradle-user-home"
runtime="$repo/.tools/references/miauto/runtime"
cd "$repo/.tools/worktrees/v1.2.25"
"$repo/.tools/gradle-8.10.2/bin/gradle" -Dorg.gradle.java.home="$JAVA_HOME" \
  -PcarPlus=true \
  -PcarPlusSdkStubs="$runtime/../icar-sdk-stubs.jar" \
  -PcarPlusSdkDex="$runtime/icar-sdk.dex" \
  -PcarPlusSdkAssets="$runtime/assets" \
  -PcarPlusSdkJni="$runtime/jni" \
  :app:packageCarPlusRuntime :app:testDebugUnitTest
```

输出是工作树中的 `app/build/carplus/outputs/apk/carplus-runtime/CarPToCar+ICCOA.apk`。确认构建后才更新 `dist/` 中给用户的包。只有接口存根的 APK 能编译验证，但缺少真机运行时。

## 真机实验及证据

一加当前是 Android 17、arm64。系统原生 Car+ 包是 `com.oplus.ocar`，连接逻辑在 `com.oplus.ocar:connect`。手机可通过 USB ADB 读日志，用户可关闭 Mac 的手机投屏窗口。KernelSU 已给我们的 APK 授权。曾用临时 instrumentation 在 APK **真实应用进程**内执行 `su -c id`，拿到 `uid=0(root)`，SELinux 上下文为 `u:r:ksu:s0`。ADB shell 或 `run-as` 显示找不到 `su`，所以 Root 应从真实应用进程调用。

| 阶段 | 已验证的结果 |
| --- | --- |
| 接收端广播 | 一加回调 `onAdvertisingSetStarted status=0`。Mac 的独立扫描确实收到了 `FCFB` 和两段服务数据 `0001`、`0002`。广播里的车端 ID 与我们 App 私有配置一致。 |
| 原生同机扫描 | 原生 Car+ 的两路 `FCFB` 扫描正常注册，约 10 秒后返回 0 个匹配。同一台 Mac 反向仅广播 `FCFB` 时，Car+ 收到 1 个匹配，随后因缺少 ICCOA 广播数据报 `advertising data is invalid`。 |
| Root 转交发现结果 | 用真实广播构建 `ScanResult`，在扫描窗口内传给 ColorOS 的发现逻辑。Root 探针调用 `CarWebBleProvider.notifyCarFound` 返回 0。后来 Frida 直接在原生进程调用发现逻辑也成功。日志出现 `onDeviceMatched`、`INIT -> DISCOVERED`，并启动真实 `com.oplus.ocar/.connect.ui.ConnectActivity`，用户可点「连接」并输入六位码。 |
| 广播新鲜度 | `0001` 第三个字节是广播序号。Car+ 对同序号有限制，复用旧序号可能不弹窗。从接收端实际的 `shared_prefs/serial_num_prefs.xml` 取 `last_serial_num`，每轮刷新真实广播后再构造记录。两段服务数据在本机已捕获。 |
| 真实蓝牙地址 | Frida 在蓝牙服务进程中调用 `AdvertiseManager.getOwnAddress`，按我们 App 活动广播的 advertiser ID 读取。控制器返回 `addressType=1` 的随机 BLE 地址，刷新广播可能改变。它不同于 App 原先手填的本机蓝牙地址。直接用真实随机地址对照，系统 GATT 仍约 25 秒超时。原生 Car+ 传入栈的日志显示 `address type=0`，可能在后续按字符串重建了 BluetoothDevice。这一轮未证实物理同机 GATT 可用。 |
| 手机内部 GATT | Frida 在原生进程拦截 `IBluetoothGatt$Stub$Proxy.clientConnect`，手机内部两进程用 `127.0.0.1` 通信。两端 ping/pong 都通过，也曾在 Car+ 前台检查成功。Mac 负责挂调试和日志。真实发起时 TCP 可建立，但接收进程在后台时消息曾积压。将我们的界面切回前台后，接收线程立即读到了积压消息。系统常规 `isFrozen` 读数曾为 false，ColorOS 的具体后台机制尚未定位。 |
| 已达到的最远位置 | `relay-android-1794193300.log` 对应一轮两端在探针中出现 `sdkGattConnected`、`nativeGattConnected`。原生随后发出 MTU 请求，仍落到系统蓝牙。该轮提交 PIN 后切前台的测试还让原生页关闭了 GATT，因此切换界面不适合作为实际方案。 |
| 最后一轮 | `relay-android-1794193451.log`。已添加 Binder 层 MTU 等拦截。11:04:39 用户提交正确码，原生报 `gatt services undiscovered, ignore send client info`。11:05:03 `Connect timeout`，状态 `START_CONNECT -> FAILED`。超时后把接收端切回前台，接收端才报 `sdkGattConnected`，原生端已经关闭。**未观察到真实 ICCOA 特征数据双向传递、PIN 被验证或 Wi‑Fi/认证会话建立。** |

权限核对已做过。`CarWebBleProvider` 要求 `com.oppo.permission.safe.BLUETOOTH`，是 `signature|privileged`。ADB shell 直接调用被拒绝。`IccoaStarter` 和 `ConnectionEngineService` 没有导出。内部 `START_WIRELESS_CONNECT` 还要求已有的 `device_id`，不能直接给一个 IP 当成新车配对。查询型 `UCarInfoProvider` 能报告会话状态，没有找到可用的公开同机配对入口。

## 调试资料与复现

以下文件是本机实验素材。正式 APK 还没有包含 Root 本机发现和 GATT 转发。

| 用途 | 文件 |
| --- | --- |
| Root 启动探针 | [RelayLauncher.java](./.tools/iccoa-local-relay/launcher/RelayLauncher.java)，已编译的 `launcher/launcher.apk` |
| 读取控制器实际地址 | [controller-address.js](./.tools/iccoa-local-relay/controller-address.js) |
| 拉起原生无线连接页、读取当前 PIN、提交真实发现结果 | [discovery.js](./.tools/iccoa-local-relay/discovery.js) |
| GATT 实验转发 | [gatt-relay.js](./.tools/iccoa-local-relay/gatt-relay.js) 和接收端 [LoopServer.java](./.tools/iccoa-local-relay/worker/LoopServer.java) |
| 测试编排 | [relay-harness.py](./.tools/iccoa-local-relay/relay-harness.py)、[pair-harness.py](./.tools/iccoa-local-relay/pair-harness.py) |
| 最后失败日志 | [relay-android-1794193451.log](./.tools/iccoa-local-relay/relay-android-1794193451.log) |
| 两端 GATT 回调那轮的日志 | [relay-android-1794193300.log](./.tools/iccoa-local-relay/relay-android-1794193300.log) |
| 本地分析产物 | `.tools/ocar-probe.x3hTf9/` 里的系统 Car+ 反编译摘录、`receiver-ble.java`、`receiver-sharelink.java`、`sharelink.java`、`native-wifi-callback.txt` 和测试机广播 `capture.json` |
| 一次性 Root 发现探针 | `.tools/carplus-discovery-probe.KKvJBg/` |

Frida 是 17.26.2，Mac Python venv、Android arm64 服务器、npm 的 `frida-compile` 和 Java bridge 都已经在 `.tools/iccoa-local-relay/`。Android 服务器下载自 [Frida 官方发布页](https://github.com/frida/frida/releases/tag/17.26.2)，与官方 SHA-256 核对过。改 JS 后，在该目录用 `node_modules/.bin/frida-compile gatt-relay.js -o gatt-relay.bundle.js` 重编译。其他 agent 类似。

重新上手机时，在工作区根目录执行下面的准备步骤。会临时安装名为「ICCOA 本机测试」的 instrumentation，用户可能看到系统安装提示。这是调试工具，原应用仍是 `CarPToCar+ICCOA`。

```sh
repo="$PWD"
adb="$repo/.tools/android-sdk/platform-tools/adb"
relay="$repo/.tools/iccoa-local-relay"
umask 077
"$adb" devices
"$adb" push "$relay/frida-server" /data/local/tmp/iccoa-relay-frida
"$adb" shell chmod 700 /data/local/tmp/iccoa-relay-frida
"$adb" install --no-incremental "$relay/launcher/launcher.apk"
"$adb" shell "run-as io.github.shaonialife.carptocar.carplus sh -c 'umask 077; cat > files/iccoa-relay.dex; chmod 400 files/iccoa-relay.dex'" \
  < "$relay/worker/dex/classes.dex"
python3 -c 'import secrets; print(secrets.token_hex(32))' > "$relay/server-token"
"$adb" shell am instrument -w -e mode start -e token "$(cat "$relay/server-token")" \
  io.github.shaonialife.carptocar.carplus.rootprobe/.RelayLauncher
"$adb" forward tcp:0 tcp:27092 > "$relay/forward-port"
cd "$relay"
venv/bin/python -u relay-harness.py --manual --seconds 195
```

`--manual` 会在真正弹出系统配对页后打印 `PAIR_PIN`。用户要求自己输入。应把当轮六位码发给他，含开头的零。保持 Car+ 界面和调试会话活跃。调试窗口结束或 PIN 更新后，应刷新广播再开始。之前一次 ADB 批量输入六位时只被系统输入框接收了两位，之后都以手输为准。

这套调试的监听仅绑定手机 `127.0.0.1`。ADB 转发到 Mac 的 Frida 端口有随机令牌。测试完成后从我们 App 的真实进程执行已保存的 `stop-device.bundle.js` 来停止该服务器，然后核对 `adb shell ps -A -o USER,PID,PPID,NAME` 和监听端口。旧 `RelayLauncher` 的 stop 模式曾只停掉 timeout 包装进程，实际 Frida 仍在，需核对。可以停止探针、删除临时 APK 和 DEX 并移除指定 ADB forward。广播和日志包含本机标识、临时 PIN 或协议数据，留在本机使用。

## 建议接续顺序

1. **先解决接收端在 Car+ 前台时的调度。** 现有 preflight 证明初期本机 socket 可用，最后一次真正 GATT 请求等到接收端重新前台才处理。加带时间戳的握手收发与进程状态记录，测 30–60 秒稳定性。优先考虑在接收端 `:iccoa` 进程本身使用合法的 Android 前台服务或明确的进程保活。若做调试 build，核对 Android 17 的 FGS 类型和权限。把主界面切回来是定位现象的方法，会干扰原生 Car+ 会话。
2. **只用本机通道完成 GATT 服务发现与特征交换。** Android 17 原生进程中的 `BluetoothGatt.connect()` 已无 callback 参数。抓到的实际入口是 `IBluetoothGatt$Stub$Proxy.clientConnect(IBluetoothGattCallback, BluetoothDevice, ...)`。本轮脚本也尝试拦截 `configureMTU`、`discoverServices`、`registerForNotification` 和 `writeCharacteristic`，但最后一轮未进入这些步骤。确认双方的连接、MTU、服务清单、`nativeGattWrite -> sdkGattWrite` 以及 `sdkGattNotification -> nativeGattNotification`。保留原生 SDK 实际的特征数据和配对码校验。接收端 APK 与 ColorOS 原生进程看到的 `BluetoothDevice` 构造签名不同，脚本已经适配本机两种签名。
3. **再跑真正的 ICCOA 认证和媒体通道。** 接收端的 `ShareLinkService` 收到 ClientInfo 后走 Wi‑Fi P2P/AP，随后启动 `UCarAuthService`。原生端的 Wi‑Fi 成功回调会启动 `ClientKeyNegotiator`。同一台手机可能无法建立真实的双端 Wi‑Fi Direct 链路。可验证用本机地址承载现有认证协议的可行性，参考接收端 `startAuthService(address)`、原生 `ShareLinkService$f.L4(...)` 及 `ShareLinkService.s(...)`。这只是从代码确认的候选接入点，还没跑过。需要看到两端的真实认证和会话状态，不能只把 UI 状态改成已连接。
4. **成功后再集成到 APK。** 调试阶段可用 Mac 装载 hooks，最终运行不能依赖 Mac、第二部手机或外置广播设备。先验证原生 Car+ 和接收端的已连接状态、实际第一帧及连续出帧，再验证真实奥迪车机上的 CarPlay 投放和断开重连。用户已经给当前应用 Root，同机方案可以在这个前提下继续验证。
