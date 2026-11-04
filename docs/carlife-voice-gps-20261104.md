# CarLife 语音键 + 车机 GPS（1.2.21，2026-11-04）

两条能力都是「**把车机的信号转给 CarLife+**」——我们冒充车机（HU 角色），CarLife+ 是被接管的手机引擎（MD 角色）。

---

## 一、方向盘语音键 → CarLife 0x68008

### 车机侧怎么来

CarPlay 里车机的语音键走 HID（不是 AirPlay 事件命令）：

```
车机 → 0x6802 AccessoryHIDReport → Consumer 页 usage 0xCF（Voice Command）
```

`VirtualIap2Link` 解析描述符得到 usage 数组 → `ConsumerKeys.keyCodeFor(0xCF)`。以前这里返回 0（"无对应 Android 键"），按键被直接丢掉。

### 我们做什么

| 环节 | 位置 | 行为 |
|---|---|---|
| usage → Android 键 | `input/ConsumerKeys.kt` | `0xCF → KeyEvent.KEYCODE_VOICE_ASSIST`（只当**统一标识**用，不注入系统） |
| 拦截 | `SessionController` 的 `onMediaKey` | 见到 `KEYCODE_VOICE_ASSIST` 就不走 `dispatchMediaKey`，改发 CarLife 硬键 |
| 下发 | `CarLifeHuClient.sendHardKeyCode()` | 控制通道 `0x68008`，载荷 `CarlifeCarHardKeyCode{ keycode = 0x21 }` → 字节 `08 21` |

键值来自官方车机 SDK `CommonParams`：

```
KEYCODE_VR_START = 0x21  (33)
KEYCODE_VR_STOP  = 0x22  (34)
```

CarLife+ 收到 `0x21` 后用**手机自己的麦克风**起语音识别 —— 所以 9242（VR 音频）不需要我们回传，这只是一条控制命令。

### 日志

```
EVENT: 车机语音键（iAP2 0x6802 0xCF）→ CarLife 硬键 0x68008 keycode=0x21 已送达（CarLife+ 应起语音）
CARLIFE: 车机硬键 0x21 （语音识别开始）→ CarLife+ 应起小度
```

未接管 CarLife 时第二行会是「命令通道不可用，未送达」。

---

## 二、车机 GPS → CarLife 0x18010

### 数据从哪来

车机每秒推一条 iAP2 `0xFFFB LocationInformation`，报文体是**一条 CSM 参数**：

```
[len u16][id=0 u16][ASCII 句子][NUL 补齐]
```

两种形态（都已实测）：

| 车机 | 载荷 | 有经纬度 |
|---|---|---|
| 奥迪 4580 | `$PASCD,1583.410,C,D,0,1,0.00,0.000*4B`（43B） | **没有** |
| 别的车型（真 iPhone 抓包） | `$GPGGA,125944.64,3014.842036,N,...*46`（201B） | 有 |

`$PASCD` 是 Apple 私有语句，字段含义无公开文档（DiPlay 的 BYD 文档也写明 "what C and 0 stand for is not public"）。所以解析器**只认它是一条样本、fix=0，不猜坐标**。

### 解析与合并

`iap2/NmeaLocation.kt`：

| 句子 | 提供 |
|---|---|
| `$GPGGA` | 经纬度（ddmm.mmmm + N/S/E/W）、海拔、fix quality、星数、HDOP、UTC 时分秒 |
| `$GPRMC` | 经纬度、**速度（节 → km/h ×1.852）**、航向、日期 |
| `$GPGSA` | PDOP/HDOP/VDOP（取**最后三个字段**，容忍车机省略空卫星槽）、fixType |
| `$PASCD` | 只记 source，无坐标 |

规则：
- 一条报文可能有多句 → 按 `$` 切分；
- 校验和存在但不对 → 丢弃；缺失 → 放行（部分车机不发 `*HH`）；
- `NmeaAssembler` 合并同秒的多句；**GGA 报「无定位」时立刻作废旧坐标**，不拿上一秒的位置冒充。

### 下发

`CarLifeProtocol.carGps()` 按官方 `CarlifeCarGpsProto.proto` 写满 25 字段（前 24 个 required，缺一个对端就丢整条）：

| 字段 | 含义 | 单位 |
|---|---|---|
| 1 / 2 | antennaState / signalQuality | 1=正常；dBHz 未提供补 0 |
| 3 / 4 | latitude / longitude | 1/1,000,000 度（南纬/西经为负，int32） |
| 5 | height | 0.1 米 |
| 6 | speed | 0.01 km/h |
| 7 | heading | 0.1 度 |
| 8~13 | 年 月 日 时 分 秒 | UTC |
| 14 | fix | NMEA fix quality |
| 15~17 | hdop / pdop / vdop | 0.1 |
| 18 / 19 | satsUsed / satsVisible | 个 |
| 20 / 21 | horPosError / vertPosError | 未提供补 0 |
| 22~24 | north / east / vert speed | 0.01 m/s（由地速 + 航向分解） |
| 25 | timeStamp | 毫秒 |

`CarLifeHuClient.sendCarGps()`：900ms 节流；**只在 `hasPosition`（有经纬度且 fix>0）时发**。

### 为什么走独立线程

GPS 是每秒一条、可丢弃的数据，而 `send()` 是一笔本地 socket 写。CarLife 引擎卡住时，写会阻塞 **iAP2 收帧线程**——控制通道靠及时回 ACK 续命（漏 ACK 车机 10 秒关通道）。所以 GPS 与语音键都丢进 `carlife-cmd` 单线程（队列 2 条，满了丢最旧）。

---

## 三、开关

| 项 | 值 |
|---|---|
| 偏好键 | `carlife_car_gps` |
| 默认 | **开** |
| UI | 显示卡「使用车机 GPS」`switchCarGps` + 说明 `carGpsNote` |
| 关掉的效果 | `onCarLocation` 直接 return，**一个字节都不发**，CarLife 完全用手机定位 |

---

## 四、顺手修掉的协议 bug

`CarLifeProtocol` 的 protobuf tag 原来是 `byteArrayOf(((field shl 3) or wire).toByte())` —— **字段号 ≥ 16 时（tag ≥ 0x80）会写成单个字节 `0x80`，没有续位标志**，对端会继续读下一个字节当高位，整条消息从该字段起错位。

之前所有消息的字段号都 ≤ 15（98311 用到 9、98386 用到 5），所以一直没暴露；车机 GPS 的 `pdop`(16) 是第一个踩到的。现在 tag 也按 varint 编码（`tagBytes()`）。

单测：`CarLifeCarGpsTest` 用「25 个字段全在 + 单位换算 + 字段号 16 的多字节 tag」把这三点钉住。

---

## 五、上车怎么验

| 想确认 | 搜这行 |
|---|---|
| 语音键有没有到 | `IAPLINK: 车机媒体键 语音键(0xcf)` |
| 有没有转给 CarLife | `EVENT: 车机语音键 … 已送达` / `CARLIFE: 车机硬键 0x21` |
| 车机到底发了什么定位 | `IAPLINK: 车机定位样本 #1` |
| 有没有坐标 | `CARLIFE: 车机 GPS 已下发 #1 30.247367,119.963585 …` |
| 车机只有 PASCD | `GPS: 车机定位样本无经纬度（PASCD，fix=0）` |
| 开关关掉了 | 上面这些一条都不会出现（除 `IAPLINK: 车机定位样本`） |

若语音键按下去**没有任何 `IAPLINK` 行**，说明这台车不把它当 HID Consumer 0xCF 发（可能走 iAP2 别的消息）——把那一刻的完整日志给我，按实际 usage/消息号补映射即可。
