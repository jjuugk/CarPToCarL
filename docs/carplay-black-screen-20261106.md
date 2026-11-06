# 2026-11-06 实车：A/B 开关「无效」的真相 + 两条自伤 bug

> 样本：`CarPToCarL-log-20261106-164713.txt`（1.2.25-test-3，switch-pool-auto 分支）
> 与 `CarPToCarL-log-20261106-164457.txt`（同一进程 16:44:57 的快照）。
> 车机：`Audi_MMI_5983` / `AUDI 3810` / DELPHI / `CL3D_MIB3H_AU_AS_G4x_2502501PROD`；
> 手机 vivo V2405A。现象（用户口述）：今天测的「流更改开关」无效，车机还是不显示画面。

## 一、开关确实生效了，而且是今天唯一的 A/B

同一进程（16:40:56 启动）中途拨的开关，两轮各留了判据：

| 轮次 | 日志判据 | 实际发出的 VideoConfig 头 |
| --- | --- | --- |
| 16:44（关） | `VideoConfig 画布声明 = 分离声明（解码=码流尺寸 / 视口=车机画布…）（AUTO … → stream）` | `解码=1412x654 视口=(1412, 654)`，偏移 40/44 = `00 80 b0 44 / 00 80 23 44` |
| 16:46（开） | `不写视口（…32..48 全 0…）（TEST 开关=开 → 不写 40/44…）` | `解码=1412x654 视口=未声明`，偏移 32..47 全 0 |

所以「开关没拨上 / 没生效」这条不成立。

## 二、但在今天这个码流下，它**不可能**改变结果

今天 CarLife 引擎真的按 1412×654 编码（`SPS=1412x654`、`profile=66 level=32`），
车机画布也是 1412×654。于是两种模式：

- 关：`params[1] = 码流 = 1412×654` + `40/44 = 1412×654`
- 开：`params[1] = 画布 = 1412×654` + `40/44 = 0`

**解码尺寸完全相同，头里只差最后 8 个字节。** 这个开关只可能影响「贴到哪块矩形」，
碰不到任何能造成黑屏的环节。换句话说：`AuDiCar 1.1.1 形态` 只有在引擎降档成 1280×720
时才与 `stream` 模式有本质区别（那种情况下开 = 声明 1412×654 与 SPS 打架，反而更糟）。

把今天四次尝试拉平看，头部几何这条线已经到头了：

| 时间 | 版本 | 头形态 | 解码尺寸 | 结果 |
| --- | --- | --- | --- | --- |
| 12:43 | 1.2.25 | 分离声明 | 先 1280×720（+视口），后重建 1412×654 | 黑 |
| 14:00 | 1.2.25-test-1 | 不写视口（开） | 1412×654 | 黑 |
| 16:44 | 1.2.25-test-3 | 分离声明（关） | 1412×654，20fps 连推 37 秒 | 黑 |
| 16:46 | 1.2.25-test-3 | 不写视口（开） | 1412×654，只推了 3.8 秒 | 会话被车机拆掉 |

16:44 那轮是全绿却全黑：配对成功 → 加密 → `modesChanged 200` → 主屏流 SETUP（uuid 来自
`/info`）→ `RECORD` → MFi 隧道完成 → 车机开始订阅 `0x4154/0x4157/0x4170/0x5000/0x5200`，
帧稳定 20fps / 1.8Mbps。**「声明 = SPS = 车机窗口 = 1412×654」今天就达成了，屏还是黑的。**
`docs/videoconfig-canvas-contract.md` 里「让三者全等就能出画」的推断，到这次为止被证伪。

## 三、16:46 那轮（开关=开）不是有效测试：被自己的 bug 打死

```
16:46:38.084  TCP 已连通 10.174.189.1:7000
16:46:38.124  /pair-setup req=6B -> 200 OK（M2 已回）
16:46:38.164  iAP2 0x4301 晚到
16:46:38.170  端点更新（0x4301 晚到）…→ fe80::c427:…:7000   ← updateEndpoint() 无条件 close()
16:46:38.170  控制通道关闭——唤醒 1 个待响应请求
16:46:38.171  pair-setup 失败（控制通道已关闭（CSeq=2 POST /pair-setup））  ← M3 被自己掐断
16:46:38.693  pair-setup Error=3（车机要求退避）
16:46:38.724  pair-verify Error=2（认证失败）
16:46:38.725  车机确定性拒绝配对 → 本轮停止重试
16:46:38.726  退回 et=NONE 明文 + AES-CTR 屏流
16:46:38.734  15 分钟内不再重复尝试
16:46:49.096  控制通道读线程结束 / 视频口 Connection reset（车机拆链，仅推了 3.8 秒）
16:47:02~12   连 5 次 7000 端口全部 ECONNREFUSED
```

根因是 `AirPlayClient.updateEndpoint()` 无条件 `close()`，而日志文案写的是「配对循环
下一轮使用新地址」。16:44 那轮同样的晚到没出事，纯粹因为当时没有在飞的请求
（`failAllPending` 空表直接返回）；16:46 撞在 M1→M3 之间，M3 直接写死。

随后两步把「自己打断」升级成「车机不认这台手机」：

1. 退避码（`Error=3`）被当成「pair-setup 失败」，代码顺着往下做 pair-verify——
   车机状态机还没收干净，必然回 `Error=2`；
2. `Error=2` 在 `PairingPolicy` 里是**确定性失败** → 写进 15 分钟记忆 + 降级明文会话。
   明文会话没有共享密钥、建不起 iAP2 隧道，车机 3.8 秒就拆链，之后 7000 端口关了 20 秒以上。

## 四、这一轮改了什么

| # | 改动 | 位置 |
| --- | --- | --- |
| 1 | 端点热更新在「配对进行中 / 有请求在飞」时**只改字段，不关通道**（配对循环每轮起步前自己 close） | `AirPlayClient.updateEndpoint()` + `AirPlaySessionPolicy.deferEndpointClose()` |
| 2 | `close()` 记录「这次关闭是否打断了在飞请求」；配对循环据此把中断当**本机打断**，不计入车机拒绝、不写 15 分钟记忆 | `AirPlayClient.close()` / `interruptedExchangeAtUptime` |
| 3 | pair-setup 回 `Error=3/7/6`（退避/忙/暂不可用）→ **等 5s 重试同一阶段**，不再抢先 pair-verify（只多给一次机会） | `pairSetupNext()` → `PairSetupNext.RETRY_SAME_STAGE` |
| 4 | 新增「屏幕接管门」：首帧送达后 12s 内车机若一条事件命令都没有，直接写结论进日志 | `SessionController.startScreenHandoverGate()` |

单测：`AirPlaySessionPolicyTest`（9 例，覆盖上面 1、3 两条的分派规则）。

## 五、下次上车怎么读日志

```
STAGE: ✓ 首帧已到达，开始推流
STAGE: 车机接管屏幕有回落信号（首帧后 12s 内 N 条事件命令：forceKeyFrame、changeModes…）
```
有这行 = 车机在解、在仲裁，问题在上层（HMI 交屏/贴图）；
```
STAGE: ⚠ 车机对画面无任何回落信号（首帧后 12s 内事件通道 0 条命令，已送达 N 帧 …）
```
有这行 = 车机没把这条流当屏流解码。**不要再调分辨率 / VideoConfig**，
要抓的是车机侧 `CDSICarplayImpl` 的四行：

```
<SCREEN> State changed: %s --> %s
<DSI><SCREEN> Video state='active|inactive'
<ERROR> Failed to overtake the screen: "<reason>"
<DSI> resourcesRequest="…", applicationsRequest="…", reason="…"
```

另外两条要守住的前提：

- 每轮只看「`pair-verify ✓` + 加密=ChaCha20 + 主屏流协商成功」；出现 `et=NONE` /
  `AES-128-CTR` 的轮次直接作废（车机 4 秒内会拆链，测不出任何东西）；
- 重连前把车机上的 CarPlay 关掉再打开：它清 projection 后 7000 端口会关 20 秒以上。

## 六、对照证据：出画面的会话长什么样

`CarPToCarL-log-20261103-202923.txt`（1.2.18-switchpool，奥迪 Q4 `AUDI 3162` / DELPHI，
**出画面**）里，同一条事件通道上有：

| 车机事件命令 | 次数 |
| --- | --- |
| `changeModes`（资源仲裁 → 回 `modesChanged` 接管主屏/主音频） | 5 |
| `requestUI`（params={url=}） | 2 |
| `requestSiri`（siriAction=1） | 1 |

而 11-06 那轮「37 秒全绿却全黑」里是 **0 条**。所以「零事件」不是噪声，而是
「车机没把这条流当屏流」的硬信号——这就是把屏幕接管门写进日志的依据。

## 七、新增探针：车机侧收帧统计（`statsDictionary.rfps`）

来源：`AMCar`（同源项目）在 SETUP 里发 `statsCollectionEnabled=true`，奥迪 4580 的
`libairplay` 就会在 `/feedback` 里多回一个 `statsDictionary`，键是流号、值带
`stty`（流类型，110/111=屏幕）/`rfps`（接收端**实收帧率**）/`ltnc`/`ahea`。

我们原本硬编码 `false`，所以「车机到底收没收到视频帧」在手机侧一直是黑箱——
只能看 `VIDEO: 帧 #N 已发送`（写 socket 成功 ≠ 车机收到）。现在：

- `AirPlayClient.buildSessionSetupBody` 固定发 `statsCollectionEnabled=true`；
- `/feedback` 每秒解析一次（`AirPlayReceiverStats`），日志按「首条 + 帧率变化 ≥3 +
  每 10s」节流：

```
AIRPLAY: 车机收帧统计 stream=1 type=110 rfps=19.8 ltnc=0 ahea=0（pre-decode：证明帧进了接收端前级，不能证明解码/交屏）
AIRPLAY: /feedback 没有 statsDictionary——该固件不回报车机侧收帧统计（…）
AIRPLAY: statsDictionary 存在但不是字典（…）——收帧统计解不动，按「没有统计」处理
AIRPLAY: statsDictionary 里没有屏幕流条目（实有类型=[102]，屏幕应为 110/111）——说明接收端还没把这条流登记成屏幕流
```

- 屏幕接管门的结论里也带上它，三种情况分开报：

| 门禁里的结论 | 含义 | 下一步 |
| --- | --- | --- |
| 车机未回报收帧统计 | 固件不支持/没开探针 | 只能靠事件通道 + 车机侧日志 |
| 车机侧实收帧率 ≈ 0 | 帧没进车机前级（数据口写入成功是假象） | 查加密/数据口/端口，别再动 VideoConfig |
| 车机确实在收帧却不回落命令 | 帧到了但没被当屏流解码/交屏 | 抓车机侧 `Video state=` / `Failed to overtake the screen` |

⚠️ 语义边界（别过度解读）：`rfps` 是**认证之后、解码器之前**的计数，`rfps>0` 只证明
帧进了接收端前级；解码成功与 HMI 交屏仍只能看车机侧日志。

## 八、探针首次实车验证（2026-11-06 18:31，1.2.25-test-5）

那轮**一帧视频都没送出去**（CarLife 没在出画面），但探针正好把这件事证死：

```
18:30:55.017 AIRPLAY: ✓ 主屏流协商成功 dataPort=6030 connId=7926980582282045449 加密=ChaCha20
18:30:55.119 AIRPLAY: 车机收帧统计 stream=7926980582282045449 type=110 rfps=0.0 ltnc=25 ahea=0
             全字段={statsDictionary={showTime=203.956…, 7926980582282045449={ahea=0, ltnc=25, stty=110, rfps=0}}}
18:31:00.145 CARLIFE: 已跳过 0 帧缓存… / 18:31:03.146 接上通道 3 秒零帧
18:31:06.270 CARLIFE: 画面源停摆 8 秒（帧率 <2fps，零个新 IDR）
18:31:20.265 STAGE: ⚠ 等首帧超时（…CarLife推流=false，累计送达=0）
```

四条对得上：`stty=110` + 键就是我们的 `connId` ⇒ 车机把这条流登记成屏幕流了；
`ltnc=25` 正是 SETUP 里的 `latencyMs=25`；`showTime` 每秒在走 ⇒ 接收端活着在数；
`rfps=0` ⇒ 一帧没收到（因为一帧没发）。**下一轮只要 `rfps≈20`，就证明「帧到车机」这关过了。**

### 同轮抓到的 bug：进程冻结把拆链栽给音频档

```
18:31:16.733 PROC: 进程被系统冻结 3 秒（心跳中断 4s）
18:31:16.740 SESSION 保活结束（链路断开）        ← 7 毫秒后
18:31:16.789 AUDIO: ⚠ 档位 t102/media/0x800000(48000Hz,AAC_LC) 起流 11s 后整条会话被拆
             ——判定为本车机不兼容档位（已记入名单）
```

冻结期间不回 keepAlive，车机按超时撤会话，与音频档无关。修法：

| # | 改动 | 位置 |
| --- | --- | --- |
| 1 | 冻结监视把**任意一次**（含 <10s、不触发重建的短冻结）记录到全局 `ProcFreeze` | `service/ProcFreeze.kt` + `CarPlayService` |
| 2 | 拆链归因时，30s 内冻结过就**不归因、不写名单、不弹提示**，只落一行「不归因到音频档」 | `SessionController.attributeAudioTeardown` |
| 3 | 已被污染的记忆无法事后辨真假 → 换记忆文件名（`audio_profile_memory_v3`）让其一次性作废，每台车下次重试一轮档位 | `audio/AudioProfileMemory.kt` |

单测：`ProcFreezeTest`（窗口内/外、从未冻结、时钟回退）。



