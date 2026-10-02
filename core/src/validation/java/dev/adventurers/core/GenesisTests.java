package dev.adventurers.core;

import dev.adventurers.core.api.WorldTime;
import dev.adventurers.core.civilization.City;
import dev.adventurers.core.persistence.WorldCodec;
import dev.adventurers.core.world.*;
import java.util.*;
import java.util.concurrent.*;

final class GenesisTests {
    static List<SimulationTests.Case> cases() {
        return List.of(
                new SimulationTests.Case("plate history moves coastlines and drainage deterministically",GenesisTests::history),
                new SimulationTests.Case("geological change precedes biological response",GenesisTests::lag),
                new SimulationTests.Case("mid-genesis save and budgeted continuation preserve epochs",GenesisTests::continuation),
                new SimulationTests.Case("evolved terrain stays fixed across parallel exploration and insertion",GenesisTests::insertion),
                new SimulationTests.Case("evolved geography closes longitude and pole boundaries",GenesisTests::boundaries),
                new SimulationTests.Case("pregeneration footprints are bounded, ordered and normalized",GenesisTests::footprints),
                new SimulationTests.Case("released v2 saves preserve legacy planetary geography",GenesisTests::legacy)
        );
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private static TerrainAtlas terrain(long seed) { return new TerrainAtlas(seed,new TerrainSettings(96,2)); }
    private static WorldModel world(TerrainAtlas terrain) {
        var world=new WorldModel(terrain.seed(),terrain.initialPlanet(),Laws.overworld(),4,4096);world.attachTerrain(terrain);return world;
    }
    private static void history() {
        var a=terrain(42);var b=terrain(42);var first=a.frame(0);int shores=0,heights=0;
        check(a.epochs()==16,"missing geological epochs");
        for(int epoch=0;epoch<=a.epochs();epoch++) {
            var frame=a.frame(epoch);check(frame.statistics().equals(b.frame(epoch).statistics()),"history nondeterministic");
            check(frame.statistics().landCells()>0&&frame.statistics().oceanCells()>0,"lost all land or ocean");
            for(int z=-500;z<=500;z+=37)for(int x=-1100;x<=1100;x+=37)
                check(frame.column(x,z).equals(b.frame(epoch).column(x,z)),"frame sampling nondeterministic");
        }
        for(int z=-500;z<=500;z+=24)for(int x=-1100;x<=1100;x+=24) {
            var old=first.column(x,z);var now=a.column(x,z);
            if(old.submerged()!=now.submerged())shores++;
            if(Math.abs(old.ground()-now.ground())>8)heights++;
            check(now.equals(a.frame(a.epochs()).column(x,z)),"chunks do not use final geography");
        }
        check(shores>40&&heights>100,"history only recolors an unchanged surface");
        check(!first.rivers().equals(a.rivers()),"drainage never responds to moving crust");
    }
    private static void lag() {
        var sim=new Simulation(world(terrain(42)));sim.advanceDays(11);
        var before=sim.world().planet().regions().stream().map(Region::view).toList();sim.advanceDays(1);
        int changed=0;
        for(var region:sim.world().planet().regions()) {
            var old=before.get(region.view().id());var now=region.view();
            if(old.elevation()!=now.elevation())changed++;
            check(old.biomass()==now.biomass()&&old.genome().equals(now.genome()),"organisms changed in the geological step");
        }
        check(changed>20&&sim.world().geologicalEpoch()==1,"geology did not advance");
        check(sim.world().cities().isEmpty()&&!sim.insertionReady(City.Era.STONE),"civilization selected unsettled terrain");
        sim.advanceDays(1);
        check(sim.world().planet().regions().stream().anyMatch(r->r.view().biomass()!=before.get(r.view().id()).biomass()),"ecology never responded");
    }
    private static void continuation() throws Exception {
        var a=new Simulation(world(terrain(77)));a.advanceDays(23);
        byte[] snapshot=WorldCodec.encode(a.world());var b=new Simulation(WorldCodec.decode(snapshot));
        check(b.world().geologicalEpoch()==1,"resume restarted geological history");
        long target=41*WorldTime.TICKS_PER_DAY;
        a.engine().advanceFully(target);while(!b.engine().advanceTo(target,1)) { /* finish complete timestamps */ }
        check(Arrays.equals(WorldCodec.encode(a.world()),WorldCodec.encode(b.world())),"budget or restart changed evolution");
        check(a.world().geologicalEpoch()==3,"wrong geological cadence");
    }
    private static void insertion() throws Exception {
        for(long seed:new long[]{42,77,2026}) {
            var atlas=terrain(seed);var sim=new Simulation(world(atlas));var expected=new ArrayList<TerrainAtlas.Column>();
            for(int i=0;i<200;i++)expected.add(atlas.column(i*23-1800,i*11-900));
            sim.generate(City.Era.STONE,2048);
            check(sim.world().geographyReady()&&sim.world().population()>0,"no civilization after geological genesis for "+seed);
            for(var city:sim.world().cities())check(!atlas.column(city.x(),city.z()).submerged(),"settlement in water");
            try(var pool=Executors.newFixedThreadPool(3)) {
                var work=new ArrayList<Callable<TerrainAtlas.Column>>();
                for(int i=199;i>=0;i--){int n=i;work.add(()->atlas.column(n*23-1800,n*11-900));}
                var results=pool.invokeAll(work);
                for(int i=0;i<200;i++)check(results.get(i).get().equals(expected.get(199-i)),"insertion/exploration changed final terrain");
            }
            var loaded=new Simulation(WorldCodec.decode(WorldCodec.encode(sim.world())));
            sim.advanceDays(24);loaded.advanceDays(24);
            check(Arrays.equals(WorldCodec.encode(sim.world()),WorldCodec.encode(loaded.world())),"post-genesis continuation diverged");
        }
    }
    private static void boundaries() {
        for(int size:new int[]{96,192,384}) {
            var atlas=new TerrainAtlas(9,new TerrainSettings(size,2));var settings=atlas.settings();
            for(int epoch:new int[]{0,8,16})for(int x=-1000;x<=1000;x+=77) {
                var frame=atlas.frame(epoch);
                check(frame.column(x,17).equals(frame.column(x+settings.circumference(),17)),"longitude seam in history");
                check(frame.column(x,settings.poleDistance()/2.0+7).equals(frame.column(x+settings.circumference()/2.0,settings.poleDistance()/2.0-7)),"pole seam in history");
            }
            var spawn=atlas.spawnSite();check(!atlas.column(spawn.x(),spawn.z()).submerged(),"evolved spawn in water");
        }
    }
    private static void footprints() {
        var settings=new TerrainSettings(96,2);
        for(int[] location:new int[][]{{0,0},{1151,575},{-1152,-576},{1170,590}}) {
            var chunks=PregenerationPlan.around(location[0],location[1],2,settings);
            check(chunks.size()==25&&new HashSet<>(chunks).size()==25,"unbounded/duplicate footprint");
            for(var chunk:chunks)check(chunk.x()>=-72&&chunk.x()<72&&chunk.z()>=-36&&chunk.z()<36,"pregeneration escaped the planet");
        }
        check(PregenerationPlan.around(10,20,2,settings).getFirst().equals(new PregenerationPlan.Chunk(0,1)),"landing chunk not first");
    }
    private static void legacy() throws Exception {
        byte[] released;try(var stream=GenesisTests.class.getResourceAsStream("/world-v2.bin")){check(stream!=null,"released fixture missing");released=stream.readAllBytes();}
        var loaded=WorldCodec.decode(released);var atlas=loaded.terrain().orElseThrow();
        check(atlas.settings().version()==1&&atlas.epochs()==0,"legacy planet silently upgraded");
        check(loaded.city(2).orElseThrow().x()==-1131&&loaded.city(2).orElseThrow().z()==-231,"old settlement moved");
        var c=atlas.column(17,33);
        check(c.ground()==106&&c.biome()==TerrainAtlas.Biome.FOREST&&c.moisture()==0.6177235001222396,"released geography changed");
        check(Arrays.equals(released,WorldCodec.encode(loaded)),"legacy save did not round-trip identically");
    }
}
