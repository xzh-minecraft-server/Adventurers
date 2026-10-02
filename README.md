# 冒险人 · Adventurers

面向 **Minecraft 26.3 / Forge 66.0.9 / Java 25** 的文明演化模组。当前为 `0.3.0` 开发版本：新增 16 阶段地质演化、地貌先变/生态滞后响应、演化地图与文明附近预生成，保留文明、任务与符文玩法。**两份设计文档中的远期功能仍在开发中**。

原始设计保存在 [CONTEXT.md](docs/design/CONTEXT.md) 和 [SYSTEMS.md](docs/design/SYSTEMS.md)。已实现、简化和未实现的部分逐项记录在 [实现范围](docs/IMPLEMENTATION.md)，架构见 [ARCHITECTURE.md](docs/ARCHITECTURE.md)。

验证覆盖 32 项核心检查，以及普通预设、星球预设中各 10 项真实 Forge 服务器测试，详见 [验证记录](docs/VALIDATION.md)。

## 进入游戏

安装 Java 25、Minecraft 26.3 和 Forge **66.0.9**。从 [GitHub Releases](https://github.com/xzh-minecraft-server/Adventurers/releases) 下载 `adventurers-0.3.0.jar`，放入客户端和服务器的 `mods` 目录；自行构建的文件在 `forge/build/libs/`。模组不依赖额外动画库或在线 AI 服务。

1. 新建世界，在“世界类型”中选择**冒险人星球（小 / 中 / 大）**。进入后用 `/advent atlas` 领取演化图，`/advent genesis` 查看地质/区块进度。等待“文明已涌现”提示后选择身份；生成阶段在服务器中分帧推进。原版类型和旧世界仍可使用原有文明玩法。
2. 输入 `/advent civilizations`，查看可以选择的文明。
3. 输入 `/advent join <文明编号> born`。也可选 `summoned`（被召唤）或 `transmigrated`（穿越）。身份只能领取一次，落点由模组选择。
4. 右键获得的**冒险人手记**查看指引，右键城邦居民交谈。手记也可用一本书和一个指南针无序合成。
5. `/advent city` 查看当地状况；`/advent requests` 询问真实需求；`/advent accept <编号>` 接取，携带物资后 `/advent deliver <编号>` 交付。物资会真正扣除并进入城邦，NPC 也可能先解决需求。
6. 在城邦里 `/advent meditate` 恢复魔力；靠近水源 `/advent observe water` 学习水元素，再尝试：

```text
/advent design tide heal 30 water:moon:magic:0
/advent cast tide
```

法术支持 `release`（视线内攻击）、`shield`、`heal`、`purify`。符文格式为 `元素:倾向:种类:层`，多个以逗号连接。例如 `fire:sun:magic:0`。编译器校验知识、同层冲突、能耗/载能与结构；非法法术不能记忆。日、月、混沌倾向分别为 `sun`、`moon`、`chaos`。失效施法不扣除魔力，正常施法有冷却。

常用指令：`/advent me`、`spells`、`history`、`banner true|false`。管理员另有 `status`、`pause true|false`、`simulate 1..24`、`save`、`selftest`；快进使用工作队列。普通玩家不能使用管理员命令。

配置文件由 Forge 在世界的 `serverconfig/adventurers-server.toml` 中生成。默认最多 4 个城邦、4096 个抽象 NPC，每个热区城邦显示至多 16 个村民外壳。建筑在已加载的城邦内按阶段呈现，仅填充空气/可替换植物；不会覆盖已有实心方块。

## 星球世界

| 世界类型 | 东西周长 | 两极间距 | 专用服务器 `level-type` |
|---|---:|---:|---|
| 小 | 2,304 格 | 1,152 格 | `adventurers:planet_small` |
| 中 | 4,608 格 | 2,304 格 | `adventurers:planet_medium` |
| 大 | 9,216 格 | 4,608 格 | `adventurers:planet_large` |

专用服务器首次建档前，在 `server.properties` 中设置 `level-type`，用 `level-seed` 指定种子。星球预设只改变主世界；下界、末地继续使用原版生成器。已有存档不会因修改 `level-type` 自动转换，体验新地形请新建世界。

- 新星球使用地形算法 2：球面板块沿各自旋转轴移动，搬运上一阶段地壳，碰撞抬升与河流侵蚀/沉积累积成地形。16 个阶段各对应 12 个模拟日；风向、地形雨影和海拔影响气候。地下洞穴、矿脉及原版装饰继续保留。
- 每轮地貌先改变，次日生态开始响应，第 6 日进行特质筛选。192 日地质演化结束后才允许文明选址，避开水面/陡坡并考虑水源、矿化和魔力。文明达到投放条件后，等待附近预生成完成再开放身份投放。
- `/advent atlas` 显示当前演化阶段的大陆、山地、河网及生态，下方金条显示地质阶段；`/advent genesis` 报告阶段、预生成区块数和文明条件。地图采用离散阶段更新，尚无独立创世界面或连续动画。
- 全局地质序列在首次区块生成前确定；地图及区域生物模拟依次经历这些阶段，实际 Minecraft 方块统一使用末期地貌。出生地及各存续文明默认提前生成 5×5 区块，每次最多 2 个异步请求；荒野按探索生成。重启重建待办，已有区块直接加载，玩家方块不会重建。
- `/advent geography` 查看当地的地貌与基准气候。向东或向西跨界会到达另一侧；越过极点会改变经度并反转南北方向。跨界保留乘坐关系和货物。

目前采用**边缘坐标传送**，未实现跨边界的无缝视野。原版装饰、结构和玩家建设没有镜像同步到边界外侧；投放后连续板块漂移、动态重塑已生成区块、局部天气、反向重力和多位面穿透仍未实现。具体范围见 [实现对照](docs/IMPLEMENTATION.md) 和 [世界生成说明](docs/WORLDGEN.md)。

## 构建与验证

需要 **JDK 25**。Forge 的辅助工具还需要 **JDK 8**，它不是游戏运行时。Gradle Wrapper 固定为 9.7.1，并验证官方 SHA-256。

```bash
export JAVA_HOME=/path/to/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"
# 两套 JDK 都安装后登记给 Gradle 与其 Mavenizer 子进程：
export JAVA_TOOL_OPTIONS="-Dorg.gradle.java.installations.paths=/path/to/jdk-8,/path/to/jdk-25"
./gradlew -Porg.gradle.java.installations.paths=/path/to/jdk-8,/path/to/jdk-25 :core:check :forge:build
./gradlew -Porg.gradle.java.installations.paths=/path/to/jdk-8,/path/to/jdk-25 :forge:runGameTestServer
./gradlew -Porg.gradle.java.installations.paths=/path/to/jdk-8,/path/to/jdk-25 -PplanetTest :forge:runGameTestServer
./gradlew -Porg.gradle.java.installations.paths=/path/to/jdk-8,/path/to/jdk-25 :forge:runClient
```

GameTest 分别使用独立的 `forge/run-gametest` 和 `forge/run-planet-gametest` 测试目录。`-PplanetTest` 加载仅供测试的星球预设数据包，验证服务器真正采用新生成器；该数据包不进入发行 JAR。普通开发服务器使用 `:forge:runServer`；Minecraft EULA 由运行服务器的用户自行阅读与接受，安装脚本不会代为接受。

不下载 Minecraft 也能运行核心验证：

```bash
./scripts/test-core.sh
"$JAVA_HOME/bin/java" -cp core/build/offline dev.adventurers.core.cli.SimulationCli 42 720
"$JAVA_HOME/bin/java" -cp core/build/offline dev.adventurers.core.cli.PopulationBenchmark
# 或用 Gradle：
./gradlew -PcoreOnly :core:check :core:run --args='42 720'
```

Codex 云环境可执行 `scripts/setup-cloud.sh` 安装经过校验的工具链，再用 `python3 scripts/cloud-build.py :core:check :forge:build`。该脚本使用现有平台代理和系统 CA，不需要 GitHub Token。

## 协作与发布

开发改动通过独立分支与 Pull Request 交接，交付约定见 [AGENTS.md](AGENTS.md)。GitHub Actions 在推送与 PR 上执行构建和测试；推送 `v*` 版本标签时，只有同次构建和测试成功，才会上传 JAR、`SHA256SUMS` 并发布 GitHub 预发布版本。发布前须同步项目版本与 `docs/releases/<版本>.md`。已发布版本不会被流程覆盖。

## 存档

模拟数据位于 `<世界>/data/adventurers/world.bin`，每分钟和正常停服时保存，保留上一份 `.bak`。格式带版本、长度边界和 CRC；写入采用临时文件加原子替换。发现损坏会停止读写，不会用空世界覆盖存档。恢复备份前应先复制保留故障文件。

建筑呈现进度单独保存在同目录的 `projection.properties`；应与整个 Minecraft 世界一起备份。服务器进程、实体句柄和热区状态不会写入核心存档，重启后根据玩家位置重建。

`0.3.0` 继续读取格式 1 / 2。`0.1.0` 世界保存时升级为格式 2；`0.2.1` 星球继续使用地形算法 1，地形和城邦不变。阶段演化需要新建算法 2 的星球。`0.2.1` 不能读取新算法星球；回退请恢复升级前的整个世界备份。星球大小、种子和地形算法版本随存档固定，不能在同一个世界中更换。星球图还使用 Minecraft 自带的地图存档，备份时不要只复制 `world.bin`。

许可证：仓库原有的 [GPL-3.0](LICENSE)。
