package dev.adventurers.forge.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.adventurers.core.world.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.*;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.phys.Vec3;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class WorldGenerationGameTests {
    private WorldGenerationGameTests() {}
    private static PlanetChunkGenerator generator(GameTestHelper helper,int size) {
        var registry=helper.getLevel().registryAccess().lookupOrThrow(Registries.BIOME);
        var biomes=new ArrayList<Holder<Biome>>();
        for(var biome:TerrainAtlas.Biome.values())biomes.add(registry.getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.withDefaultNamespace(biome.name().toLowerCase(Locale.ROOT)))));
        return new PlanetChunkGenerator(new PlanetBiomeSource(size,2,biomes));
    }
    public static void presets(GameTestHelper helper) {
        var ops=RegistryOps.create(JsonOps.INSTANCE,helper.getLevel().registryAccess());
        int[] sizes={96,192,384};String[] names={"small","medium","large"};
        try {
            for(int i=0;i<names.length;i++) {
                var resource=helper.getLevel().getServer().getResourceManager().getResource(Identifier.fromNamespaceAndPath("adventurers","worldgen/world_preset/planet_"+names[i]+".json")).orElseThrow();
                try(var reader=new InputStreamReader(resource.open(),StandardCharsets.UTF_8)) {
                    var preset=WorldPreset.DIRECT_CODEC.parse(ops,JsonParser.parseReader(reader)).getOrThrow();
                    var generator=preset.overworld().orElseThrow().generator();
                    helper.assertTrue(generator instanceof PlanetChunkGenerator,"world preset did not select planet generator");
                    var planet=(PlanetChunkGenerator)generator;
                    helper.assertTrue(planet.settings().regionSize()==sizes[i],"preset size lost");
                    helper.assertTrue(planet.settings().version()==2,"new world preset still uses legacy geology");
                    var encoded=ChunkGenerator.CODEC.encodeStart(ops,generator).getOrThrow();
                    var decoded=(PlanetChunkGenerator)ChunkGenerator.CODEC.parse(ops,encoded).getOrThrow();
                    helper.assertTrue(planet.atlas(42).column(100,100).equals(decoded.atlas(42).column(100,100)),"serialized generator changed terrain");
                    helper.assertTrue(!planet.atlas(42).column(100,100).equals(planet.atlas(77).column(100,100)),"generator ignored world seed");
                }
            }
            helper.succeed();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static ProtoChunk generate(GameTestHelper helper,PlanetChunkGenerator generator,ChunkPos position) {
        var level=helper.getLevel();var random=level.getChunkSource().randomState();
        generator.createState(level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET),random,random.seed());
        var chunk=new ProtoChunk(position,UpgradeData.EMPTY,level,PalettedContainerFactory.create(level.registryAccess()),null);
        generator.createBiomes(random,Blender.empty(),level.structureManager(),chunk).join();
        generator.buildTerrain(chunk,Blender.empty(),random,level.structureManager(),level.getBiomeManager(),null,generator.getBiomeSource().possibleBiomes()).join();
        return chunk;
    }
    public static void terrain(GameTestHelper helper) {
        var generator=generator(helper,96);var level=helper.getLevel();var random=level.getChunkSource().randomState();var atlas=generator.atlas(random.seed());
        var chunks=List.of(generate(helper,generator,new ChunkPos(-1,0)),generate(helper,generator,new ChunkPos(0,0)));
        for(var chunk:chunks)for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
            int wx=chunk.getPos().getMinBlockX()+x,wz=chunk.getPos().getMinBlockZ()+z;
            var c=atlas.column(wx+.5,wz+.5);
            helper.assertTrue(chunk.getBlockState(new BlockPos(wx,-64,wz)).is(Blocks.BEDROCK),"bedrock missing");
            helper.assertTrue(!chunk.getBlockState(new BlockPos(wx,c.ground(),wz)).isAir(),"predicted surface missing in chunk");
            helper.assertTrue(chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG,x,z)==Math.max(c.ground(),c.water()),"heightmap does not match blocks");
            helper.assertTrue(generator.getBaseHeight(wx,wz,Heightmap.Types.WORLD_SURFACE_WG,level,random)==Math.max(c.ground(),c.water())+1,"structure height disagrees with actual terrain");
            if(c.submerged())helper.assertTrue(!chunk.getBlockState(new BlockPos(wx,c.water(),wz)).isAir(),"ocean/river water missing");
        }
        var repeated=generate(helper,generator,new ChunkPos(-1,0));
        for(int y=-64;y<240;y+=3)for(int z=0;z<16;z++)helper.assertTrue(repeated.getBlockState(new BlockPos(-1,y,z)).equals(chunks.getFirst().getBlockState(new BlockPos(-1,y,z))),"regeneration differs");
        if(level.getChunkSource().getGenerator() instanceof PlanetChunkGenerator actual) {
            var spawn=PlayerSpawnFinder.getSpawnPosInChunk(level,actual.getOrigin(random));
            helper.assertTrue(spawn!=null,"vanilla spawn finder failed in the planet's initial chunk");
            helper.assertTrue(level.getFluidState(spawn.below()).isEmpty(),"initial spawn has no dry support");
        }
        helper.succeed();
    }
    public static void boundary(GameTestHelper helper) {
        var level=helper.getLevel();var settings=new TerrainSettings(96);var player=helper.makeMockServerPlayerInLevel();
        var boat=EntityTypes.OAK_CHEST_BOAT.create(level,EntitySpawnReason.COMMAND);
        if(boat==null)throw new IllegalStateException("Cannot create boat");
        boat.snapTo(settings.circumference()/2.0+2,300,0);level.addFreshEntity(boat);
        boat.setItem(0,new ItemStack(Items.IRON_INGOT,7));player.snapTo(boat.getX(),301,0);player.startRiding(boat,true,false);
        boat.setDeltaMovement(new Vec3(.4,.1,.2));
        helper.assertTrue(PlanetBoundary.wrap(boat,level,settings),"east crossing was ignored");
        helper.assertTrue(Math.abs(boat.getX()-(-settings.circumference()/2.0+2))<.01&&player.getVehicle()==boat,"riding group separated");
        helper.assertTrue(boat.getItem(0).getCount()==7&&boat.getDeltaMovement().distanceTo(new Vec3(.4,.1,.2))<.0001,"cargo or momentum lost");
        boat.snapTo(17,300,settings.poleDistance()/2.0+3);boat.setYRot(35);boat.setDeltaMovement(new Vec3(.4,.1,.2));
        helper.assertTrue(PlanetBoundary.wrap(boat,level,settings),"pole crossing was ignored");
        helper.assertTrue(Math.abs(boat.getZ()-(settings.poleDistance()/2.0-3))<.01&&Math.abs(boat.getYRot()-145)<.01,"pole position or heading incorrect");
        helper.assertTrue(boat.getDeltaMovement().distanceTo(new Vec3(.4,.1,-.2))<.0001&&player.getVehicle()==boat,"pole reflection lost riding state");
        player.stopRiding();boat.discard();player.discard();helper.succeed();
    }
    public static void atlas(GameTestHelper helper) {
        var terrain=new TerrainAtlas(42,new TerrainSettings(96));var world=new WorldModel(42,terrain.simulationPlanet(),Laws.overworld(),4,4096);world.attachTerrain(terrain);
        var city=world.foundCity(world.planet().regions().stream().filter(world::canSettle).findFirst().orElseThrow());
        var service=new PlanetAtlasMap(helper.getLevel(),world);var stack=service.stack();var map=MapItem.getSavedData(stack,helper.getLevel());
        helper.assertTrue(map!=null&&map.locked,"atlas not saved or vanilla can overwrite it");
        helper.assertTrue(stack.get(DataComponents.MAP_ID).equals(service.stack().get(DataComponents.MAP_ID)),"atlas request allocated duplicate saved maps");
        int x=(int)(city.x()*128.0/terrain.settings().circumference()+64),z=(int)(city.z()*64.0/terrain.settings().poleDistance()+64);
        byte before=map.colors[x+z*128];city.citizens().forEach(p->p.injure(1));world.setTick(24000);service.tick();
        helper.assertTrue(before!=map.colors[x+z*128],"map failed to reflect civilization extinction");
        var reopened=new PlanetAtlasMap(helper.getLevel(),world);
        helper.assertTrue(stack.get(DataComponents.MAP_ID).equals(reopened.stack().get(DataComponents.MAP_ID)),"atlas index did not survive service reload");
        helper.succeed();
    }
    public static void genesisMap(GameTestHelper helper) {
        var terrain=new TerrainAtlas(42,new TerrainSettings(96,2));
        var world=new WorldModel(42,terrain.initialPlanet(),Laws.overworld(),4,4096);world.attachTerrain(terrain);
        var simulation=new Simulation(world);var service=new PlanetAtlasMap(helper.getLevel(),world);
        var stack=service.stack();var map=MapItem.getSavedData(stack,helper.getLevel());var before=map.colors.clone();
        simulation.advanceDays(GeologicalHistory.DAYS_PER_EPOCH);service.tick();int changed=0;
        for(int y=32;y<96;y++)for(int x=0;x<128;x++)if(before[x+y*128]!=map.colors[x+y*128])changed++;
        helper.assertTrue(changed>50,"geological epoch did not redraw land/water on the map");
        helper.assertTrue(map.colors[8+104*128]!=before[8+104*128],"geological progress bar did not advance");
        helper.assertTrue(world.geologicalEpoch()==1&&!world.geographyReady(),"genesis insertion gate not enforced");
        helper.assertTrue(stack.get(DataComponents.MAP_ID).equals(service.stack().get(DataComponents.MAP_ID)),"epoch allocated another map");
        helper.succeed();
    }
    public static void pregeneration(GameTestHelper helper) {
        var level=helper.getLevel();var settings=new TerrainSettings(96,2);
        var chunks=PregenerationPlan.around(1016,440,1,settings);
        var tickets=level.getDataStorage().computeIfAbsent(net.minecraft.world.level.TicketStorage.TYPE);
        var type=ModWorldgen.PREGEN_TICKET.get();
        helper.assertTrue(!type.persist()&&!type.doesSimulate(),"pregeneration keeps permanent or entity-ticking tickets");
        try(var service=new PlanetPregenerator(level,settings)) {
            service.enqueue(1016,440,1);service.enqueue(1016,440,1);
            helper.assertTrue(service.total()==9&&!service.ready(),"duplicate enqueue or premature readiness");
            service.tick(2);
            helper.assertTrue(service.active()==2&&service.completed()==0&&!service.ready(),"concurrent request budget ignored");
            // Pump actual Minecraft generation before tick assertions, which run faster than real time on GameTest.
            // The production service only polls completed futures and never synchronously loads these chunks.
            for(var chunk:chunks)level.getChunk(chunk.x(),chunk.z());
            for(int i=0;i<10&&!service.ready();i++)service.tick(2);
            helper.assertTrue(service.ready()&&service.completed()==9&&service.failure().isEmpty(),"FULL chunks failed to finish the queue");
            for(var chunk:chunks)helper.assertTrue(tickets.getTickets(ChunkPos.pack(chunk.x(),chunk.z())).stream()
                    .noneMatch(t->t.getType()==type),"completed work leaked a chunk ticket");
        }
        var interrupted=new PlanetPregenerator(level,settings);interrupted.enqueue(1016,440,1);interrupted.tick(1);interrupted.close();
        helper.assertTrue(interrupted.active()==0,"stop retained asynchronous tasks");
        for(var chunk:chunks)helper.assertTrue(tickets.getTickets(ChunkPos.pack(chunk.x(),chunk.z())).stream()
                .noneMatch(t->t.getType()==type),"stop leaked a chunk ticket");
        // Reconstructing the plan after restart must reuse existing chunks safely.
        try(var resumed=new PlanetPregenerator(level,settings)) {
            resumed.enqueue(1016,440,0);resumed.tick(1);resumed.tick(1);
            helper.assertTrue(resumed.ready()&&resumed.completed()==1,"pregeneration resume failed");
        }
        helper.succeed();
    }
}
