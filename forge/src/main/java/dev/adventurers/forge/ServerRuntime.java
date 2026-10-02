package dev.adventurers.forge;

import com.mojang.logging.LogUtils;
import dev.adventurers.core.api.*;
import dev.adventurers.core.civilization.City;
import dev.adventurers.core.persistence.*;
import dev.adventurers.core.world.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ServerRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<MinecraftServer, ServerRuntime> INSTANCES = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final Path save;
    private final Simulation simulation;
    private final WorldProjection projection;
    private final dev.adventurers.forge.worldgen.PlanetAtlasMap atlasMap;
    private final dev.adventurers.forge.worldgen.PlanetPregenerator pregenerator;
    private final Map<UUID, Long> lastCity = new HashMap<>();
    private long target;
    private boolean paused;
    private boolean genesisWarning;

    private ServerRuntime(MinecraftServer server) throws IOException {
        this.server = server;
        save = server.getWorldPath(LevelResource.ROOT).resolve("data/adventurers/world.bin");
        boolean existing=Files.exists(save);
        var generator=server.overworld().getChunkSource().getGenerator();
        var terrain=generator instanceof dev.adventurers.forge.worldgen.PlanetChunkGenerator planet?planet.atlas(server.overworld().getSeed()):null;
        WorldModel world = existing ? WorldStore.load(save) : new WorldModel(server.overworld().getSeed(),
                terrain==null?Planet.genesis(server.overworld().getSeed(), 24, 12):terrain.initialPlanet(),
                new Laws(EnumSet.allOf(Laws.Domain.class),1,ModConfig.MAGIC_DENSITY.get()), ModConfig.CITY_LIMIT.get(), ModConfig.POPULATION_LIMIT.get());
        if(existing && (world.terrain().isPresent()!=(terrain!=null)))throw new IOException("World generator and saved geography differ; preserve the original world type");
        if(terrain!=null) {
            if(world.seed()!=terrain.seed()||world.terrain().isPresent()&&!world.terrain().orElseThrow().settings().equals(terrain.settings()))
                throw new IOException("Planet seed/size differs from saved geography");
            world.attachTerrain(terrain);
        }
        simulation = new Simulation(world); target = world.tick();
        projection = new WorldProjection(this, save.resolveSibling("projection.properties"));
        atlasMap = new dev.adventurers.forge.worldgen.PlanetAtlasMap(server.overworld(),world);
        pregenerator = terrain == null ? null : new dev.adventurers.forge.worldgen.PlanetPregenerator(server.overworld(),terrain.settings());
        if(pregenerator!=null) {
            var spawn=terrain.spawnSite();pregenerator.enqueue(spawn.x(),spawn.z(),ModConfig.PREGEN_RADIUS.get());
        }
        LOGGER.info("Adventurers loaded: day={}, cities={}, population={}", WorldTime.day(world.tick()),world.cities().size(),world.population());
    }
    public static void start(MinecraftServer server) {
        try { INSTANCES.put(server, new ServerRuntime(server)); }
        catch (IOException error) { throw new IllegalStateException("Adventurers 存档无法读取，已保留原文件。请检查 world.bin 和 world.bin.bak。",error); }
    }
    public static ServerRuntime get(MinecraftServer server) {
        var runtime = INSTANCES.get(server);
        if (runtime == null) throw new IllegalStateException("世界模拟尚未初始化");
        return runtime;
    }
    public static void stop(MinecraftServer server) {
        var runtime = INSTANCES.remove(server);
        if (runtime != null) { if(runtime.pregenerator!=null)runtime.pregenerator.close();runtime.save(); }
    }
    public MinecraftServer server() { return server; }
    public Simulation simulation() { return simulation; }
    public WorldModel world() { return simulation.world(); }
    public WorldProjection projection() { return projection; }
    public dev.adventurers.forge.worldgen.PlanetAtlasMap atlasMap() { return atlasMap; }
    public String genesisStatus() {
        String geology=world().terrain().map(t->"地质阶段 "+world().geologicalEpoch()+"/"+t.epochs()).orElse("原版地形");
        String chunks=pregenerator==null?"无需星球预生成":"区块预生成 "+pregenerator.completed()+"/"+pregenerator.total();
        return geology+"；"+chunks+"；"+(simulation.insertionReady(City.Era.values()[ModConfig.INSERTION_ERA.get()])?"文明条件已达到":"等待文明条件")
                +(pregenerator==null?"":pregenerator.failure().map(f->"；"+f).orElse(""));
    }
    public boolean paused() { return paused; }
    public void paused(boolean paused) { this.paused = paused; }
    public void requestDays(int days) {
        target = Math.addExact(target, Math.multiplyExact((long) days, WorldTime.TICKS_PER_DAY));
    }
    public void tick() {
        world().terrain().ifPresent(terrain->dev.adventurers.forge.worldgen.PlanetBoundary.tick(server.overworld(),terrain.settings()));
        if (paused) return;
        var world = world();
        if(pregenerator!=null) {
            for(var city:world.cities())if(city.population()>0)pregenerator.enqueue(city.x(),city.z(),ModConfig.PREGEN_RADIUS.get());
            pregenerator.tick(ModConfig.PREGEN_BUDGET.get());
            if(pregenerator.failure().isPresent()) {
                paused=true;LOGGER.error("{}",pregenerator.failure().orElseThrow());
                server.getPlayerList().broadcastSystemMessage(Component.literal("[冒险人] 区块预生成失败，模拟已暂停。/advent genesis 查看原因。"),false);
                return;
            }
        }
        if (world.phase() == WorldModel.Phase.GENESIS) {
            if (simulation.insertionReady(City.Era.values()[ModConfig.INSERTION_ERA.get()])) {
                if(pregenerator==null||pregenerator.ready()) {
                    world.phase(WorldModel.Phase.PLAYING);
                    server.getPlayerList().broadcastSystemMessage(Component.literal("[冒险人] 文明已涌现。使用 /advent civilizations 查看出身选择。"), false);
                    LOGGER.info("Adventurers genesis ready: day={}, cities={}, population={}",WorldTime.day(world.tick()),world.cities().size(),world.population());
                    save();
                }
            } else if (WorldTime.day(world.tick()) < ModConfig.GENESIS_LIMIT.get()) {
                if (target <= world.tick() && server.getTickCount()%ModConfig.GENESIS_PACE.get()==0) target = world.tick() + WorldTime.TICKS_PER_DAY;
            } else if (!genesisWarning) {
                genesisWarning = true;
                LOGGER.warn("Adventurers insertion condition not reached. Inspect /advent status; /advent simulate can continue genesis.");
            }
        } else target++;
        // Explicit offline advances (including tests) can already have brought the core beyond the adapter target.
        target=Math.max(target,world.tick());
        simulation.engine().advanceTo(target, ModConfig.WORK_BUDGET.get());
        if (server.getTickCount() % 20 == 0) {
            var players = server.getPlayerList().getPlayers().stream().filter(p -> p.level().dimension() == Level.OVERWORLD).toList();
            world.observe(players.stream().map(p -> new LoadingTier.Observer(p.getX(),p.getZ())).toList());
            projection.tick();
            if(world.phase()==WorldModel.Phase.GENESIS||server.getTickCount()%100==0)atlasMap.tick();
            for (var player : players) {
                var city = world.nearest(player.getX(), player.getZ(), 96);
                long id = city.map(City::id).orElse(-1L);
                Long previous = lastCity.put(player.getUUID(),id);
                if (!Objects.equals(previous,id) && city.isPresent() && world.player(player.getUUID()).map(p -> p.banner()).orElse(true))
                    player.sendSystemMessage(Component.literal("进入 " + city.get().name()),true);
            }
            lastCity.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        }
        if (server.getTickCount() % 1200 == 0) save();
    }
    public void save() {
        try { WorldStore.save(save,world()); projection.save(); }
        catch (IOException error) {
            paused = true;
            LOGGER.error("Adventurers save failed; simulation paused to preserve state",error);
            server.getPlayerList().broadcastSystemMessage(Component.literal("[冒险人] 存档失败，模拟已暂停；请检查服务器日志。"),false);
        }
    }
    public void greet(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("[冒险人] /advent 打开指引；/advent civilizations 选择文明；右键居民交谈。"));
        if(world().terrain().isPresent())player.sendSystemMessage(Component.literal("[冒险人] /advent atlas 领取星球演化图；/advent geography 查看当地地理。"));
    }
}
