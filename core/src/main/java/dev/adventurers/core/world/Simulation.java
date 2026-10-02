package dev.adventurers.core.world;

import dev.adventurers.core.api.*;
import dev.adventurers.core.civilization.City;
import dev.adventurers.core.engine.*;
import dev.adventurers.core.life.Memory;
import dev.adventurers.core.magic.*;
import dev.adventurers.core.player.*;
import dev.adventurers.core.systems.*;
import java.util.*;

/** Public use cases shared by the command adapter and headless tests. */
public final class Simulation {
    private final WorldModel world;
    private final SimulationEngine engine;
    private final SpellCompiler compiler = new SpellCompiler();
    public Simulation(WorldModel world) {
        this.world = world;
        var events = new EventBus(100_000);
        events.subscribe("*", world::record);
        events.subscribe("city.needs", event -> world.city(Long.parseLong(event.subject())).ifPresent(city -> {
            // Long-cycle decisions notify the interaction shell through a bounded memory, no cross-layer polling.
            for (var person : city.citizens()) if (person.alive() && world.tier(city) != LoadingTier.COLD)
                person.memory().remember(new Memory.Entry(event.tick(), event.detail(), .15), event.tick());
        }));
        engine = new SimulationEngine(world, events);
        EnvironmentSystems.create().forEach(engine::register);
        SocietySystems.create().forEach(engine::register);
    }
    public WorldModel world() { return world; }
    public SimulationEngine engine() { return engine; }
    public void advanceDays(int days) {
        if (days < 0 || days > 4096) throw new IllegalArgumentException("Days must be 0..4096");
        engine.advanceFully(Math.addExact(world.tick(), Math.multiplyExact((long) days, WorldTime.TICKS_PER_DAY)));
    }
    public boolean insertionReady(City.Era minimum) {
        return world.geographyReady() && world.cities().stream().anyMatch(c -> c.population() > 0 && c.era().ordinal() >= minimum.ordinal());
    }
    public void generate(City.Era minimum, int maxDays) {
        for (int day = 0; day < maxDays && !insertionReady(minimum); day++) advanceDays(1);
        if (!insertionReady(minimum)) throw new IllegalStateException("投放条件未达到；世界仍可继续演化");
    }
    public PlayerProfile join(UUID id, long cityId, PlayerProfile.Origin origin) {
        if (!world.geographyReady()) throw new IllegalStateException("地质演化尚未完成");
        if (world.player(id).isPresent()) throw new IllegalStateException("已拥有身份，不能重复领取出身");
        var city = world.city(cityId).filter(c -> c.population() > 0).orElseThrow(() -> new IllegalArgumentException("城邦不存在或已灭绝"));
        var player = new PlayerProfile(id, city.id(), origin, 0);
        if (origin == PlayerProfile.Origin.SUMMONED) player.learn(Element.FIRE);
        if (origin == PlayerProfile.Origin.BORN) player.reward(2);
        world.putPlayer(player); world.phase(WorldModel.Phase.PLAYING);
        return player;
    }
    public PlayerProfile reincarnate(UUID id) {
        var previous = requirePlayer(id);
        world.quests().stream().filter(q -> q.active() && id.equals(q.claimant())).forEach(q -> q.close(Quest.Status.CANCELLED));
        var available = world.cities().stream().filter(c -> c.population() > 0).toList();
        if (available.isEmpty()) throw new IllegalStateException("暂无存续文明，需等待新文明涌现");
        var city = available.get(Math.floorMod(Numbers.mix(world.seed() ^ id.getLeastSignificantBits() ^ previous.incarnation()), available.size()));
        var reborn = new PlayerProfile(id, city.id(), PlayerProfile.Origin.BORN, previous.incarnation() + 1);
        world.putPlayer(reborn); return reborn;
    }
    public PlayerProfile requirePlayer(UUID id) { return world.player(id).orElseThrow(() -> new IllegalStateException("先使用 /advent join 选择出身")); }
    public SpellCompiler.Result design(UUID id, Spell spell) {
        var player = requirePlayer(id);
        var result = compiler.compile(spell, player.knowledge(), world.laws());
        if (result.valid()) player.remember(spell);
        return result;
    }
    public SpellCompiler.Result cast(UUID id, String name) {
        var player = requirePlayer(id);
        var spell = player.spells().get(name);
        if (spell == null) throw new IllegalArgumentException("未记忆该法术");
        var result = compiler.compile(spell, player.knowledge(), world.laws());
        if (!result.valid()) throw new IllegalStateException(String.join("；", result.errors()));
        player.cast(world.tick(), result.cost());
        return result;
    }
    public Quest claim(UUID id, long questId, long localCity) {
        requirePlayer(id);
        var quest = world.quest(questId).orElseThrow(() -> new IllegalArgumentException("委托不存在"));
        if (quest.city() != localCity) throw new IllegalArgumentException("需要前往委托所在城邦");
        quest.claim(id, world.tick()); return quest;
    }
    public void deliver(UUID id, long questId, long localCity, int available) {
        var player = requirePlayer(id);
        var quest = world.quest(questId).orElseThrow(() -> new IllegalArgumentException("委托不存在"));
        if (quest.city() != localCity || available < quest.amount()) throw new IllegalArgumentException("地点或物资不足");
        var city = world.city(localCity).filter(c -> c.population() > 0).orElseThrow(() -> new IllegalArgumentException("委托人已消失"));
        quest.complete(id, world.tick());
        city.stocks().add(quest.resource(), quest.amount());
        player.reward(3);
    }
}
