package dev.adventurers.core;

import dev.adventurers.core.api.*;
import dev.adventurers.core.civilization.*;
import dev.adventurers.core.engine.*;
import dev.adventurers.core.life.*;
import dev.adventurers.core.magic.*;
import dev.adventurers.core.persistence.*;
import dev.adventurers.core.player.*;
import dev.adventurers.core.world.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Dependency-free integration and invariant suite, runnable even without Minecraft artifacts. */
public final class SimulationTests {
    @FunctionalInterface interface Test { void run() throws Exception; }
    record Case(String name, Test test) {}
    record Result(String name, Throwable failure, double seconds) {}
    static List<Case> cases() {
        var tests = new ArrayList<>(List.of(
            new Case("calendar and spherical pole crossings", SimulationTests::calendar),
            new Case("resource consumption is atomic", SimulationTests::resources),
            new Case("six phases, cadence and bounded catch-up", SimulationTests::scheduler),
            new Case("queued event delivery and cascade budget", SimulationTests::events),
            new Case("world laws are additive", SimulationTests::laws),
            new Case("memory remains bounded and retains important events", SimulationTests::memory),
            new Case("modular structures and vital damage", SimulationTests::assembly),
            new Case("construction consumes resources in six stages", SimulationTests::building),
            new Case("spell compiler rejects conflicts and overload", SimulationTests::spells),
            new Case("alchemy preserves source traits and enforces sequence", SimulationTests::alchemy),
            new Case("seeded genesis actually produces civilization", SimulationTests::genesis),
            new Case("deterministic replay and save continuation", SimulationTests::replay),
            new Case("quest ownership, deadlines and exactly-once delivery", SimulationTests::quests),
            new Case("player origins, cooldowns and reincarnation", SimulationTests::players),
            new Case("snapshot integrity, version and atomic backup", SimulationTests::persistence),
            new Case("multiplayer loading uses the highest tier", SimulationTests::loading),
            new Case("long simulation keeps resources finite and bounded", SimulationTests::longRun)
        ));
        tests.addAll(WorldGenerationTests.cases());
        tests.addAll(GenesisTests.cases());
        return tests;
    }
    public static void main(String[] args) throws Exception {
        var results = new ArrayList<Result>();
        for (var entry : cases()) {
            long start = System.nanoTime(); Throwable failure = null;
            try { entry.test().run(); System.out.println("PASS " + entry.name()); }
            catch (Throwable error) { failure = error; System.err.println("FAIL " + entry.name()); error.printStackTrace(); }
            results.add(new Result(entry.name(), failure, (System.nanoTime() - start) / 1e9));
        }
        long failed = results.stream().filter(r -> r.failure() != null).count();
        if (args.length > 0) writeReport(Path.of(args[0]), results, failed);
        System.out.printf("RESULT: %d passed; %d failed; 0 skipped%n", results.size() - failed, failed);
        if (failed > 0) throw new AssertionError(failed + " simulation tests failed");
    }
    private static void writeReport(Path path, List<Result> results, long failed) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (var output = Files.newBufferedWriter(path)) {
            var xml = javax.xml.stream.XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            xml.writeStartDocument(); xml.writeStartElement("testsuite");
            xml.writeAttribute("name", "SimulationContracts"); xml.writeAttribute("tests", Integer.toString(results.size()));
            xml.writeAttribute("failures", Long.toString(failed)); xml.writeAttribute("errors", "0"); xml.writeAttribute("skipped", "0");
            for (var result : results) {
                xml.writeStartElement("testcase"); xml.writeAttribute("name", result.name());
                xml.writeAttribute("classname", SimulationTests.class.getName()); xml.writeAttribute("time", Double.toString(result.seconds()));
                if (result.failure() != null) {
                    xml.writeStartElement("failure"); xml.writeAttribute("message", result.failure().toString());
                    xml.writeCharacters(result.failure().toString()); xml.writeEndElement();
                }
                xml.writeEndElement();
            }
            xml.writeEndElement(); xml.writeEndDocument(); xml.close();
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void close(double a, double b) { check(Math.abs(a-b) < 1e-8, a + " != " + b); }
    private static void fails(Class<? extends Throwable> type, Test action) throws Exception {
        try { action.run(); } catch (Throwable error) { if (type.isInstance(error)) return; throw new AssertionError("Wrong failure", error); }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void calendar() {
        check(WorldTime.year(24 * 24000L) == 1, "24 days/year");
        check(WorldTime.season(6 * 24000L) == 1, "six days/season");
        var p = Planet.genesis(1, 24, 12);
        check(p.index(-1, 0) == 23, "longitude seam");
        check(p.index(0, -1) == 12, "north pole rotates longitude");
        check(p.index(0, 12) == 11 * 24 + 12, "south pole rotates longitude");
    }
    private static void resources() throws Exception {
        var stock = new Stockpile(); stock.add(Resource.WOOD, 10);
        check(!stock.consume(Map.of(Resource.WOOD, 5.0, Resource.STONE, 1.0)), "partial recipe accepted");
        close(stock.get(Resource.WOOD), 10);
        fails(IllegalArgumentException.class, () -> stock.add(Resource.WOOD, Double.NaN));
        fails(IllegalArgumentException.class, () -> stock.take(Resource.WOOD, -1));
        close(stock.take(Resource.WOOD, 20), 10);
        close(stock.get(Resource.WOOD), 0);
    }
    private static LoopSystem<Integer,Integer,Integer> counter(String name, long period, List<String> phases) {
        return new LoopSystem<>() {
            public Definition definition() { return new Definition(name, period, List.of("counter"), List.of(),List.of(),List.of()); }
            public Integer perceive(LoopContext c) { phases.add(name+":perceive:"+c.tick()); return 1; }
            public Integer evaluate(LoopContext c,Integer p) { phases.add("evaluate"); return p; }
            public Integer decide(LoopContext c,Integer p,Integer n) { phases.add("decide"); return n; }
            public void execute(LoopContext c,Integer d) { phases.add("execute"); }
            public void feedback(LoopContext c,Integer d) { phases.add("feedback"); }
            public void evolve(LoopContext c,Integer d) { phases.add("evolve"); }
        };
    }
    private static void scheduler() throws Exception {
        var world = WorldModel.create(1); var phases = new ArrayList<String>();
        var engine = new SimulationEngine(world, new EventBus(32));
        engine.register(counter("a", 10, phases)); engine.register(counter("b", 10, phases));
        fails(IllegalArgumentException.class, () -> engine.register(counter("a", 5, phases)));
        check(!engine.advanceTo(20, 1), "catch-up must report pending work");
        check(world.tick() == 10 && phases.size() == 12, "timestamp must finish atomically");
        check(phases.subList(0,6).equals(List.of("a:perceive:10","evaluate","decide","execute","feedback","evolve")), "phase order");
        check(engine.advanceTo(20, 2), "pending work not completed");
        check(phases.size() == 24, "wrong cadence");
        fails(IllegalArgumentException.class, () -> engine.advanceTo(19, 1));
    }
    private static void events() throws Exception {
        var bus = new EventBus(8); var received = new ArrayList<String>();
        bus.subscribe("a", event -> { received.add("a"); bus.publish(new WorldEvent(0,"b","x","",0)); });
        bus.subscribe("b", event -> received.add("b"));
        bus.publish(new WorldEvent(0,"a","x","",0));
        check(received.isEmpty(), "delivery must be queued"); bus.flush(); check(received.equals(List.of("a","b")), "event propagation");
        var cyclic = new EventBus(3); cyclic.subscribe("a", cyclic::publish); cyclic.publish(new WorldEvent(0,"a","x","",0));
        fails(IllegalStateException.class, cyclic::flush);
    }
    private static void laws() {
        var parent = new Laws(Set.of(Laws.Domain.PHYSICS),1,0);
        var child = parent.planet(Set.of(Laws.Domain.MAGIC),2,1);
        check(child.allows(Laws.Domain.PHYSICS) && child.allows(Laws.Domain.MAGIC), "inheritance disabled parent law");
        var blocked = new SpellCompiler().compile(Spell.simple("fire",Spell.Effect.RELEASE,Element.FIRE),Set.of(Element.FIRE),parent);
        check(!blocked.valid(), "magic law ignored");
    }
    private static void memory() {
        var m = new Memory(4); m.remember(new Memory.Entry(0,"permanent",1),0);
        for (int i=1;i<100;i++) m.remember(new Memory.Entry(i*24000L,"ordinary",.2),i*24000L);
        check(m.entries().size() <= 4 && m.entries().stream().anyMatch(e->e.event().equals("permanent")), "important memory lost");
    }
    private static void assembly() throws Exception {
        var body = new Assembly(List.of(new Assembly.Part("body",null,"flesh",1,true),new Assembly.Part("head","body","flesh",1,true)));
        check(!body.damage("head",1).viable(), "vital head loss");
        fails(IllegalArgumentException.class, () -> new Assembly(List.of(new Assembly.Part("head","missing","flesh",1,true))));
    }
    private static void building() {
        var b = new Building(1,5,5); var stock = new Stockpile();
        b.work(100,stock); check(b.stage()==Building.Stage.FOUNDATION,"construction without resources");
        stock.add(Resource.WOOD,100); stock.add(Resource.STONE,100);
        for(int i=0;i<6;i++) b.work(100,stock);
        check(b.stage()==Building.Stage.COMPLETE && b.capacity()>0,"all six stages required");
        check(stock.get(Resource.WOOD)<100 && stock.get(Resource.STONE)<100,"building costs missing");
        b.decay(1); check(b.stage()==Building.Stage.RUIN,"ruins missing");
    }
    private static void spells() {
        var compiler = new SpellCompiler(); var valid = Spell.simple("warmth",Spell.Effect.RELEASE,Element.FIRE);
        var knowledge = EnumSet.allOf(Element.class);
        check(compiler.compile(valid,knowledge,Laws.overworld()).valid(),"valid spell rejected");
        check(!compiler.compile(valid,Set.of(Element.SPACE),Laws.overworld()).valid(),"unknown rune accepted");
        var conflict = new Spell("conflict", Spell.Effect.RELEASE,List.of(valid.runes().get(0),new Spell.Rune(Element.WATER,Element.Polarity.MOON,Spell.Kind.MAGIC,0)),valid.circle());
        check(!compiler.compile(conflict,knowledge,Laws.overworld()).valid(),"opposed elements accepted");
        var overloaded = new Spell("overload",valid.effect(),valid.runes(),new Spell.Circle(true,true,true,false,1,.9,1,1));
        check(!compiler.compile(overloaded,knowledge,Laws.overworld()).valid(),"overload accepted");
        var noEye = new Spell("blind",valid.effect(),valid.runes(),new Spell.Circle(false,true,true,false,100,.9,1,1));
        check(!compiler.compile(noEye,knowledge,Laws.overworld()).valid(),"missing eye accepted");
        check(Element.values().length==25,"invented missing element");
    }
    private static void alchemy() throws Exception {
        var source = new Alchemy.Trait(Element.FIRE,Alchemy.Layer.SOURCE,2,true);
        var material = new Alchemy.Material("herb",Element.FIRE,List.of(source),List.of(),false,1,1);
        var alchemy = new Alchemy();
        fails(IllegalArgumentException.class,()->alchemy.process(material,Alchemy.Method.DRY,Element.FIRE));
        var prepared = alchemy.process(material,Alchemy.Method.DRY,Element.WATER);
        check(prepared.traits().contains(source),"source trait changed");
        fails(IllegalArgumentException.class,()->alchemy.process(prepared,Alchemy.Method.DRY,Element.WATER));
        var separated = alchemy.process(prepared,Alchemy.Method.SEPARATE,Element.EARTH);
        fails(IllegalArgumentException.class,()->alchemy.process(separated,Alchemy.Method.GRIND,Element.FIRE));
    }
    private static Simulation populated(long seed) {
        var world = WorldModel.create(seed);
        var region = world.planet().regions().stream().filter(r->r.view().elevation()>0).findFirst().orElseThrow();
        world.foundCity(region);
        return new Simulation(world);
    }
    private static void genesis() {
        for (long seed : new long[]{42,77,2026}) {
            var sim = new Simulation(WorldModel.create(seed)); sim.generate(City.Era.STONE,2048);
            check(sim.world().population()>0,"no evolved civilization for seed " + seed);
            var region=sim.world().planet().region(sim.world().cities().iterator().next().region());
            check(region.view().generation()>0 && region.view().genome().cognition()>=.6,"civilization was preseeded");
        }
    }
    private static void replay() throws Exception {
        var a = populated(55); var b = populated(55); a.advanceDays(30); b.advanceDays(30);
        check(Arrays.equals(WorldCodec.encode(a.world()),WorldCodec.encode(b.world())),"same seed diverged");
        var encoded = WorldCodec.encode(a.world());
        var loaded = new Simulation(WorldCodec.decode(encoded));
        check(Arrays.equals(encoded,WorldCodec.encode(loaded.world())),"snapshot round-trip changed state");
        a.advanceDays(60); loaded.advanceDays(60);
        check(Arrays.equals(WorldCodec.encode(a.world()),WorldCodec.encode(loaded.world())),"save continuation diverged");
    }
    private static void quests() throws Exception {
        var sim=populated(1);var world=sim.world();var city=world.cities().iterator().next();var a=UUID.randomUUID();var b=UUID.randomUUID();
        sim.join(a,city.id(),PlayerProfile.Origin.BORN);sim.join(b,city.id(),PlayerProfile.Origin.BORN);
        var quest=new Quest(world.allocateId(),city.id(),Resource.WOOD,8,24000);world.addQuest(quest);
        sim.claim(a,quest.id(),city.id());
        fails(IllegalStateException.class,()->sim.claim(b,quest.id(),city.id()));
        double before=city.stocks().get(Resource.WOOD);
        fails(IllegalArgumentException.class,()->sim.deliver(a,quest.id(),city.id(),7));
        fails(IllegalStateException.class,()->sim.deliver(b,quest.id(),city.id(),8));
        close(before,city.stocks().get(Resource.WOOD));
        sim.deliver(a,quest.id(),city.id(),8);close(before+8,city.stocks().get(Resource.WOOD));
        fails(IllegalStateException.class,()->sim.deliver(a,quest.id(),city.id(),8));
        var expired=new Quest(world.allocateId(),city.id(),Resource.FOOD,4,0);world.addQuest(expired);
        fails(IllegalStateException.class,()->sim.claim(a,expired.id(),city.id()));
    }
    private static void players() throws Exception {
        var sim=populated(1);var city=sim.world().cities().iterator().next();var id=UUID.randomUUID();
        var p=sim.join(id,city.id(),PlayerProfile.Origin.SUMMONED);
        fails(IllegalStateException.class,()->sim.join(id,city.id(),PlayerProfile.Origin.BORN));
        p.meditate(0,1);fails(IllegalStateException.class,()->p.meditate(1,1));
        check(sim.design(id,Spell.simple("spark",Spell.Effect.RELEASE,Element.FIRE)).valid(),"summoned affinity");
        sim.cast(id,"spark");double mana=p.mana();fails(IllegalStateException.class,()->sim.cast(id,"spark"));close(mana,p.mana());
        var reborn=sim.reincarnate(id);check(reborn.incarnation()==1 && reborn.spells().isEmpty(),"identity must reset on death");
    }
    private static void persistence() throws Exception {
        var sim=populated(7);byte[] bytes=WorldCodec.encode(sim.world());
        byte[] corrupt=bytes.clone();corrupt[corrupt.length-1]^=1;
        fails(IOException.class,()->WorldCodec.decode(corrupt));
        fails(IOException.class,()->WorldCodec.decode(Arrays.copyOf(bytes,bytes.length-1)));
        var dir=Files.createTempDirectory("adventurers-tests-");var save=dir.resolve("world.bin");
        try {
            WorldStore.save(save,sim.world());sim.advanceDays(1);WorldStore.save(save,sim.world());
            check(Files.exists(dir.resolve("world.bin.bak")),"backup absent");
            check(WorldStore.load(save).tick()==24000,"save failed");
            Files.write(save,corrupt);fails(IOException.class,()->WorldStore.save(save,sim.world()));
            check(Arrays.equals(Files.readAllBytes(save),corrupt),"corrupt save overwritten");
        } finally { try(var files=Files.list(dir)){for(var f:files.toList())Files.delete(f);}Files.delete(dir); }
    }
    private static void loading() {
        var observers=List.of(new LoadingTier.Observer(0,0),new LoadingTier.Observer(1000,1000));
        check(LoadingTier.at(1001,1001,observers)==LoadingTier.HOT,"second player ignored");
        check(LoadingTier.at(300,0,observers)==LoadingTier.WARM,"warm tier");
        check(LoadingTier.at(600,0,observers)==LoadingTier.COLD,"cold tier");
    }
    private static void longRun() {
        var sim=new Simulation(WorldModel.create(42));sim.advanceDays(2048);
        check(sim.world().cities().size()>0,"no city");
        for(var city:sim.world().cities()) {
            for(var resource:Resource.values())check(Double.isFinite(city.stocks().get(resource)) && city.stocks().get(resource)>=0,"invalid resource");
            check(city.disease()>=0 && city.disease()<=1,"disease out of range");
        }
        check(sim.world().history().size()<=256,"unbounded events");
        check(sim.world().quests().size()<=256,"unbounded quests");
        check(sim.world().population()<=sim.world().populationLimit(),"population budget");
    }
}
