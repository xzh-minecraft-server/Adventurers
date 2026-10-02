package dev.adventurers.forge;

import dev.adventurers.core.magic.*;
import dev.adventurers.core.persistence.WorldCodec;
import dev.adventurers.core.player.PlayerProfile;
import dev.adventurers.core.player.Quest;
import dev.adventurers.core.world.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.DeferredRegister;
import java.util.*;
import java.util.function.Consumer;

/** Real Minecraft server checks; these functions are inert outside the GameTest runner. */
public final class ModGameTests {
    public static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,AdventurersMod.ID);
    static {
        FUNCTIONS.register("runtime",()->ModGameTests::runtime);
        FUNCTIONS.register("save_replay",()->ModGameTests::saveReplay);
        FUNCTIONS.register("player_magic",()->ModGameTests::playerMagic);
        FUNCTIONS.register("quests_and_projection",()->ModGameTests::questsAndProjection);
        FUNCTIONS.register("planet_presets",()->dev.adventurers.forge.worldgen.WorldGenerationGameTests::presets);
        FUNCTIONS.register("planet_terrain",()->dev.adventurers.forge.worldgen.WorldGenerationGameTests::terrain);
        FUNCTIONS.register("planet_boundary",()->dev.adventurers.forge.worldgen.WorldGenerationGameTests::boundary);
        FUNCTIONS.register("planet_atlas",()->dev.adventurers.forge.worldgen.WorldGenerationGameTests::atlas);
        FUNCTIONS.register("planet_genesis",()->dev.adventurers.forge.worldgen.WorldGenerationGameTests::genesisMap);
        FUNCTIONS.register("planet_pregeneration",()->dev.adventurers.forge.worldgen.WorldGenerationGameTests::pregeneration);
    }
    private ModGameTests() {}
    private static void runtime(GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        helper.assertTrue(ServerRuntime.get(server).world()!=null,"server lifecycle did not initialize simulation");
        boolean planet=Boolean.getBoolean("adventurers.test.planet");
        helper.assertTrue((server.overworld().getChunkSource().getGenerator() instanceof dev.adventurers.forge.worldgen.PlanetChunkGenerator)==planet,"test world preset was not activated");
        helper.assertTrue(ServerRuntime.get(server).world().terrain().isPresent()==planet,"simulation and physical terrain are disconnected");
        helper.assertTrue(!new ItemStack(ModItems.CHRONICLE.get()).isEmpty(),"chronicle registry missing");
        try {
            var source=server.createCommandSourceStack();
            int result=server.getCommands().getDispatcher().execute("advent selftest",source);
            helper.assertTrue(result==1,"registered selftest command failed");
            helper.succeed();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static void saveReplay(GameTestHelper helper) {
        try {
            var a=new Simulation(WorldModel.create(2026));a.generate(dev.adventurers.core.civilization.City.Era.STONE,2048);
            byte[] snapshot=WorldCodec.encode(a.world());var b=new Simulation(WorldCodec.decode(snapshot));
            a.advanceDays(6);b.advanceDays(6);
            helper.assertTrue(Arrays.equals(WorldCodec.encode(a.world()),WorldCodec.encode(b.world())),"save continuation diverged in server runtime");
            helper.succeed();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static void playerMagic(GameTestHelper helper) {
        var runtime=ServerRuntime.get(helper.getLevel().getServer());
        var mock=helper.makeMockServerPlayerInLevel();
        var world=runtime.world();
        if(!world.geographyReady())runtime.simulation().advanceDays(GeologicalHistory.EPOCHS*GeologicalHistory.DAYS_PER_EPOCH);
        if(world.cities().isEmpty())world.foundCity(world.planet().regions().stream().filter(r->r.view().elevation()>0&&world.canSettle(r)).findFirst().orElseThrow());
        var city=world.cities().iterator().next();
        var profile=runtime.simulation().join(mock.getUUID(),city.id(),PlayerProfile.Origin.SUMMONED);
        helper.assertTrue(runtime.simulation().design(mock.getUUID(),Spell.simple("healing",Spell.Effect.HEAL,Element.FIRE)).valid(),"spell design failed");
        mock.setHealth(4);
        try {
            int result=helper.getLevel().getServer().getCommands().getDispatcher().execute("advent cast healing",mock.createCommandSourceStack());
            helper.assertTrue(result==1 && mock.getHealth()>4,"spell command did not heal real Minecraft entity");
            helper.assertTrue(profile.mana()<30,"spell did not consume mana");
            double mana=profile.mana();
            int repeated=helper.getLevel().getServer().getCommands().getDispatcher().execute("advent cast healing",mock.createCommandSourceStack());
            helper.assertTrue(repeated==0 && profile.mana()==mana,"cooldown allowed double cast");
            helper.succeed();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static void questsAndProjection(GameTestHelper helper) {
        var server=helper.getLevel().getServer();var runtime=ServerRuntime.get(server);var world=runtime.world();
        if(!world.geographyReady())runtime.simulation().advanceDays(GeologicalHistory.EPOCHS*GeologicalHistory.DAYS_PER_EPOCH);
        if(world.cities().isEmpty())world.foundCity(world.planet().regions().stream().filter(r->r.view().elevation()>0&&world.canSettle(r)).findFirst().orElseThrow());
        var city=world.cities().iterator().next();
        var mock=helper.makeMockServerPlayerInLevel();
        runtime.simulation().join(mock.getUUID(),city.id(),PlayerProfile.Origin.BORN);
        var level=server.overworld();
        // A grass-covered standing space must work, but a solid canopy at head height must be rejected.
        var feet=helper.absolutePos(new BlockPos(1,1,1)).atY(300);
        var original=List.of(level.getBlockState(feet.below()),level.getBlockState(feet),level.getBlockState(feet.above()));
        try {
            level.setBlockAndUpdate(feet.below(),Blocks.GRASS_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(feet,Blocks.SHORT_GRASS.defaultBlockState());
            level.setBlockAndUpdate(feet.above(),Blocks.AIR.defaultBlockState());
            helper.assertTrue(feet.equals(runtime.projection().safeSurface(feet.getX(),feet.getZ())),"grass incorrectly prevents a safe landing");
            level.setBlockAndUpdate(feet.above(),Blocks.OAK_LEAVES.defaultBlockState());
            helper.assertTrue(runtime.projection().safeSurface(feet.getX(),feet.getZ())==null,"landing ignores head collision");
        }finally {
            level.setBlockAndUpdate(feet.below(),original.get(0));
            level.setBlockAndUpdate(feet,original.get(1));
            level.setBlockAndUpdate(feet.above(),original.get(2));
        }
        var forced=new ArrayList<net.minecraft.world.level.ChunkPos>();
        // The embedded mock client sends no movement packets, so snapTo cannot activate player chunk tickets.
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            var chunk=new net.minecraft.world.level.ChunkPos((city.x()>>4)+dx,(city.z()>>4)+dz);
            if(level.setChunkForced(chunk.x(),chunk.z(),true))forced.add(chunk);
            level.getChunk(chunk.x(),chunk.z());
        }
        // ENTITY_TICKING requires a radius-two FULL neighbourhood (ChunkMap.prepareEntityTickingChunk).
        // Load it before starting the tick-based assertion budget; GameTest ticks faster than wall time.
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)level.getChunk((city.x()>>4)+dx,(city.z()>>4)+dz);
        mock.snapTo(city.x()+.5,level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,city.x(),city.z()),city.z()+.5);
        mock.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,8));
        var quest=new Quest(world.allocateId(),city.id(),Resource.WOOD,8,world.tick()+24000);world.addQuest(quest);
        double before=city.stocks().get(Resource.WOOD);
        try {
            var dispatcher=server.getCommands().getDispatcher();var source=mock.createCommandSourceStack();
            helper.assertTrue(dispatcher.execute("advent accept "+quest.id(),source)==1,"quest acceptance failed");
            helper.assertTrue(dispatcher.execute("advent deliver "+quest.id(),source)==1,"quest delivery failed");
            helper.assertTrue(mock.getInventory().getItem(0).isEmpty(),"Minecraft inventory was not debited");
            helper.assertTrue(city.stocks().get(Resource.WOOD)==before+8,"city did not receive items");
            helper.assertTrue(dispatcher.execute("advent deliver "+quest.id(),source)==0,"duplicate delivery allowed");
            // Full terrain chunks and their entity sections do not become visible in the same tick.
            helper.succeedWhen(()->{
                for(var chunk:forced)helper.assertTrue(level.areEntitiesActuallyLoadedAndTicking(chunk),"city entity chunks not active yet: "+chunk);
                world.observe(List.of(new LoadingTier.Observer(city.x(),city.z())));
                int limit=world.cities().stream().filter(c->world.tier(c)==LoadingTier.HOT)
                        .mapToInt(c->Math.min(c.population(),ModConfig.VISIBLE_CITIZENS.get())).sum();
                runtime.projection().tick();int first=visibleCitizens(level);
                helper.assertTrue(first>0 && first<=limit,"hot projection count="+first+", per-city total limit="+limit);
                runtime.projection().tick();helper.assertTrue(first==visibleCitizens(level),"projection duplicated NPCs");
                world.observe(List.of());runtime.projection().tick();helper.assertTrue(visibleCitizens(level)==0,"cold projection retained entities");
                for(var chunk:forced)level.setChunkForced(chunk.x(),chunk.z(),false);
            });
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static int visibleCitizens(net.minecraft.server.level.ServerLevel level) {
        int count=0;for(var entity:level.getAllEntities())if(!entity.isRemoved()&&WorldProjection.citizenId(entity)>0)count++;return count;
    }
}
