# docs/archive —— 已失效与外部存档文档

> **这里的文件只作历史取证，不要当作现状引用。**
> 判断现行结论请走 [`../PROJECT-GUIDE.md`](../PROJECT-GUIDE.md)（状态标签）与
> [`../PROJECT-STATUS.md`](../PROJECT-STATUS.md)（体检报告）。
>
> 归档时间：2026-11-19。移动使用 `git mv`，历史与 blame 完整保留。

| 文档 | 归档原因 | 仍然有效的部分 |
|---|---|---|
| [`audi-mib3-ea-tunnel-20261104.md`](audi-mib3-ea-tunnel-20261104.md) | 1.2.29 后 EA 会话（`0xEA03`/`0xEA04`、mib3 包）**整体移除**，代码已不存在 | 「`0xEA04` 早于 `0xEA03` 写入会致 115 号包不再 ACK」作为协议教训仍成立 |
| [`mib3-viwi-control.md`](mib3-viwi-control.md) | 同上，VIWI 车控端点代码已删；五项推断从未验证 | 无（车控红线见根 `DISCLAIMER.md`） |
| [`mib3-control-handover.md`](mib3-control-handover.md) | 同上，控制交接代码已删 | 「只在车停稳时试写」的安全原则仍适用 |
| [`video-stall-recovery-20261108.md`](video-stall-recovery-20261108.md) | 1.2.34 后**分级干预全删**（看门狗退回只观测） | 「最后一帧距今」判据仍有效，已并入 `PROJECT-GUIDE.md` §4 |
| [`connect-protocol-and-models.md`](connect-protocol-and-models.md) | **外部存档**：来源是另一项目 AuDiCar 1.0.51，9 台奥迪机型；文档自述「不要当作现状引用」 | 车型参数与端口时序**不可**直接套用到本仓库 |

## 归档规则

满足任一条即移入本目录，并同步三处（`PROJECT-GUIDE.md` 索引与状态、根 `README.md` 链接、本表）：

1. 对应功能已被整体移除；
2. 结论已被后续实车推翻，且没有可复用的判据；
3. 来自其它项目，不能代表本仓库现状。
