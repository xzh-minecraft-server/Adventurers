# 已发布存档兼容性样本

`world-v1.bin` 使用已发布 `v0.1.0` 的 `WorldCodec` 生成，格式版本为 1；不能用当前编码器重新生成，否则不能验证向后兼容性。它不含真实玩家数据。

- 种子：77；一个城邦、16 名 NPC。
- 玩家：UUID `00000000-0000-0000-0000-00000000004d`，出身 `SUMMONED`。
- 城邦科技/魔法进度：2.4 / 3.5。
- SHA-256：`86d258ac2b23185e45ddc962696e696b161587893aab05ee637327aff4d8f352`。

在 v0.1.0 的核心类路径中运行以下 Java 代码可以复现（`args[0]` 为输出文件）：

```java
var world = dev.adventurers.core.world.WorldModel.create(77);
var city = world.foundCity(world.planet().regions().stream()
        .filter(r -> r.view().elevation() > 0).findFirst().orElseThrow());
new dev.adventurers.core.world.Simulation(world).join(new java.util.UUID(0, 77), city.id(),
        dev.adventurers.core.player.PlayerProfile.Origin.SUMMONED);
city.knowledge(2.4, 3.5);
java.nio.file.Files.write(java.nio.file.Path.of(args[0]),
        dev.adventurers.core.persistence.WorldCodec.encode(world));
```

## 已发布 0.2.1 星球样本

`world-v2.bin` 来自发布标签 `v0.2.1` 的 `core-0.2.1.jar`，使用该版编码器创建，格式为 2、地形算法为 1；升级测试不得用当前算法重建此样本。

- 种子 77，小星球；16 名 NPC；城邦坐标 `(-1131, -231)`。
- 玩家 UUID `00000000-0000-0000-0000-00000000004d`，出身 `SUMMONED`；城邦科技/魔法为 2.4 / 3.5。
- SHA-256：`a0aec79165e29647203e930a7e6ba1f40a979a25b60480dd6f13cd643e128f9d`。

在 0.2.1 核心类路径中运行：

```java
var atlas = new dev.adventurers.core.world.TerrainAtlas(77,
        new dev.adventurers.core.world.TerrainSettings(96));
var world = new dev.adventurers.core.world.WorldModel(77, atlas.simulationPlanet(),
        dev.adventurers.core.world.Laws.overworld(), 4, 4096);
world.attachTerrain(atlas);
var city = world.foundCity(world.planet().regions().stream()
        .filter(world::canSettle).findFirst().orElseThrow());
new dev.adventurers.core.world.Simulation(world).join(new java.util.UUID(0, 77), city.id(),
        dev.adventurers.core.player.PlayerProfile.Origin.SUMMONED);
city.knowledge(2.4, 3.5);
java.nio.file.Files.write(java.nio.file.Path.of(args[0]),
        dev.adventurers.core.persistence.WorldCodec.encode(world));
```
