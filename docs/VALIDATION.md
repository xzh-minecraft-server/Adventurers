# 0.3.0 验证记录

验证日期：2026-10-02 UTC（北京时间 10 月 3 日）。平台：Linux x86_64，Temurin JDK 25.0.4.1+1，辅助 JDK 8u504-b01，Gradle 9.7.1，Minecraft 26.3，Forge 66.0.9。

| 检查 | 结果 |
|---|---|
| `scripts/test-core.sh` | 32 通过、0 失败、0 跳过 |
| `:core:check` / `simulationTest` | 32 通过、0 失败、0 跳过 |
| `:forge:build` | 成功 |
| `:forge:runGameTestServer` | 普通测试世界中 10 项通过 |
| `-PplanetTest :forge:runGameTestServer`，`-XX:ActiveProcessorCount=2` | 实际新星球预设中 10 项通过 |

沿用已验证的云环境工具链，通过 `python3 scripts/cloud-build.py <任务> --no-daemon` 执行。没有代用户接受普通服务器 Minecraft EULA。

## 核心检查

报告：`core/build/test-results/simulation/TEST-simulation.xml`。独立套件使用 `validation` 源集；JUnit 的 `NO-SOURCE` 不是验证证据。

原有 25 项覆盖六阶段调度、确定性模拟、文明涌现、资源与任务事务、符文编译、身份与存档、旧地形的并发采样/跨界/选址/尺寸等。新增 7 项验证：

- 16 阶段地质历史逐次可复现，大陆岸线、高程和河网确实变化；区块地形等于最后阶段。
- 第 12 日地貌改变时生物量与基因保留，次日生物量响应；未完成地质阶段不能选址。
- 第 23 日存档恢复后，以最小调度预算继续至第 41 日，与不间断模拟的完整存档字节一致。
- 种子 42 / 77 / 2026 自然涌现文明；投放前后及多线程反序探索不改变最终地形；继续 24 日的存档一致。
- 小/中/大尺寸在早期、中期、末期的经界和极点采样连续，出生搜索从陆地开始。
- 预生成足迹去重、有界、中心优先，跨经界/极点归一化。
- 真实发布版 0.2.1 创建的格式 2 样本，读取后保留算法 1、原地形采样、城邦坐标与身份，重新编码与原样本逐字节相等。

旧格式 1 / 2 样本来源、生成代码及 SHA-256 见 `core/src/validation/resources/README.md`，没有使用新编码器伪造版本头。

## Forge 服务器检查

报告：`forge/run-gametest/gametest-results.xml`、`forge/run-planet-gametest/gametest-results.xml`。两种服务器均执行全部 10 项，无跳过。

| 测试 | 检查内容 |
|---|---|
| `adventurers:runtime` | 模组注册/生命周期、真正激活的世界类型、实际 selftest 指令 |
| `adventurers:save_replay` | 服务器中的存档继续完整字节一致 |
| `adventurers:player_magic` | 真实玩家治疗、魔力扣除、重复施法冷却 |
| `adventurers:quests_and_projection` | 背包与资源交付、重复拒绝、热区各城邦人口上限、实体就绪、不重复、冷区清理、草丛/头部碰撞 |
| `adventurers:planet_presets` | 三种新预设使用算法 2，生成器编解码保留尺寸/种子/地形 |
| `adventurers:planet_terrain` | 新算法真实 ProtoChunk 高度图/水面/基岩/并排重建；真实星球的陆地出生搜索 |
| `adventurers:planet_boundary` | 载具、乘客、货物、方向在经界和极点传送后保留 |
| `adventurers:planet_atlas` | 原地图锁定、单 ID 复用、城邦灭绝标记、索引恢复 |
| `adventurers:planet_genesis` | 地质推进后实际地图大陆/水域像素变化、金条进度更新、同一地图 ID、未完成投放门槛 |
| `adventurers:planet_pregeneration` | 真实 FULL 区块请求、并发上限、去重、完成及中断释放票据、重新建队后复用区块；票据不持久化/不运行实体模拟 |

GameTest 无实时限速，测试初始化会同步完成外部地形的必要加载，再进行按 tick 的断言，避免后台生成慢于测试时钟。正式预生成服务使用异步请求，只在 future 已完成时读取结果；没有在服务器 tick 上同步等待。居民断言仍等待实体区块和两圈邻区就绪；没有放宽上限或跳过旧回归。

`src/gametest/packs/planet` 覆盖测试预设，只有 `-PplanetTest` 加载；不进入模组 JAR。`runtime` 检查实际生成器和模拟地理类型，避免星球测试包未启用仍被算作通过。

## 发布与验证边界

发布产物为 `adventurers-0.3.0.jar`；准确 SHA-256 随 Release 的 `SHA256SUMS` 交付。标签流水线在全量构建、核心验证、两种服务器测试通过后才发布附件，不覆盖旧标签或旧版产物。

交互客户端画面、长期多人负载、其他操作系统均未执行；未测量大面积新区域预生成的 TPS/内存上限。地图像素测试不等同于渲染客户端验收，区块请求预算不等于严格毫秒预算。运行时的文明周边计划会随实际城邦增加；测试验证核心生成、服务器适配与队列组件，没有代替长时间真人游玩。

本版不宣称实现投放后方块重塑、连续创世动画、无缝跨位面或宇宙生成。详见 [WORLDGEN.md](WORLDGEN.md) 与 [IMPLEMENTATION.md](IMPLEMENTATION.md)。
