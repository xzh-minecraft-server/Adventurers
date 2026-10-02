package dev.adventurers.core.systems;

import dev.adventurers.core.api.*;
import dev.adventurers.core.engine.*;
import dev.adventurers.core.life.Genome;
import dev.adventurers.core.world.*;
import java.util.*;

public final class EnvironmentSystems {
    private EnvironmentSystems() {}
    public static List<LoopSystem<?, ?, ?>> create() { return List.of(new GenesisGeology(), new Climate(), new Ecology(), new Evolution(), new Geology(), new WorldWill()); }

    private static final class GenesisGeology extends DomainSystem<WorldModel> {
        GenesisGeology() { super("genesis_geology", GeologicalHistory.DAYS_PER_EPOCH * WorldTime.TICKS_PER_DAY,
                "plates,crust,drainage", "previous_epoch,wind,runoff", "drift,uplift,erosion", "terrain,rain_shadow,minerals"); }
        public List<WorldModel> perceive(LoopContext c) { return c.world().geologicalChangeAt(c.tick()) ? List.of(c.world()) : List.of(); }
        protected double demand(LoopContext c, WorldModel world) { return 1; }
        protected void act(LoopContext c, WorldModel world, double urgency) {
            var geography = world.observedTerrain().orElseThrow().simulationPlanet();
            for (var region : world.planet().regions()) {
                var old = region.view(); var next = geography.region(old.id()).view();
                // Terrain and water change first. Organisms retain their traits/biomass until the following day.
                region.update(new Region.View(old.id(),old.latitude(),old.longitude(),next.elevation(),next.temperature(),
                        next.moisture(),old.pressure(),next.water(),old.biomass(),next.mana(),next.ore(),old.genome(),old.generation()));
            }
            c.emit("world.geology", Integer.toString(world.geologicalEpoch()),
                    "板块移动与河流冲刷：地质阶段 " + world.geologicalEpoch() + "/" + GeologicalHistory.EPOCHS, .65);
        }
    }

    private static final class Climate extends DomainSystem<Region.View> {
        private List<Region.View> snapshot;
        Climate() { super("climate", WorldTime.TICKS_PER_DAY, "atmosphere,water", "latitude,season,neighbors", "thermal_balance", "rain,temperature,runoff"); }
        public List<Region.View> perceive(LoopContext c) {
            snapshot = c.world().planet().regions().stream().map(Region::view).toList();
            return c.world().laws().allows(Laws.Domain.CLIMATE) ? snapshot : List.of();
        }
        protected double demand(LoopContext c, Region.View r) { return 1; }
        protected void act(LoopContext c, Region.View r, double urgency) {
            var planet = c.world().planet();
            var neighbors = planet.neighbors(r.id()).stream().map(n -> snapshot.get(n.id())).toList();
            double seasonal = Math.sin(WorldTime.day(c.tick()) * 2 * Math.PI / 24) * Math.sin(r.latitude()) * 8;
            double target = 28 - Math.abs(Math.sin(r.latitude())) * 45 - Math.max(0, r.elevation()) * .006 + seasonal;
            double neighborTemperature = neighbors.stream().mapToDouble(Region.View::temperature).average().orElse(r.temperature());
            double temp = r.temperature() + (target - r.temperature()) * .25 + (neighborTemperature - r.temperature()) * .12;
            double incoming = neighbors.stream().mapToDouble(Region.View::moisture).average().orElse(r.moisture());
            double evaporation = Math.min(r.water(), Math.max(0, temp + 10) * .0015);
            double terrainVapor = c.world().terrain().filter(t -> t.settings().version() == 2)
                    .map(t -> c.world().observedTerrain().orElseThrow().column(
                            r.longitude() / (2*Math.PI) * t.settings().circumference(),
                            r.latitude() / Math.PI * t.settings().poleDistance()).moisture()).orElse(r.moisture());
            double vapor = Numbers.unit(r.moisture() + (incoming - r.moisture()) * .15 + evaporation + (terrainVapor-r.moisture())*.2);
            double rain = Math.max(0, vapor - (.5 + Math.max(0, temp) * .005)) * .65;
            double water = r.elevation() < 0 ? 1 : Numbers.unit(r.water() - evaporation + rain + .005);
            planet.region(r.id()).update(new Region.View(r.id(), r.latitude(), r.longitude(), r.elevation(), temp,
                    vapor - rain, 1 - temp * .003 - r.elevation() * .00002, water, r.biomass(), r.mana(), r.ore(), r.genome(), r.generation()));
        }
    }
    private static final class Ecology extends DomainSystem<Region.View> {
        Ecology() { super("ecology", WorldTime.TICKS_PER_DAY, "plants,elements", "water,temperature,mana", "survival,regrowth", "biomass,mana_recovery"); }
        public List<Region.View> perceive(LoopContext c) {
            return c.world().laws().allows(Laws.Domain.LIFE) && !c.world().geologicalChangeAt(c.tick())
                    ? c.world().planet().regions().stream().map(Region::view).toList() : List.of();
        }
        protected double demand(LoopContext c, Region.View r) { return 1; }
        protected void act(LoopContext c, Region.View r, double urgency) {
            double fitness = r.genome().fitness(r.temperature(), r.water(), r.mana());
            double growth = .028 * fitness * (1 - r.biomass()) - .018 * (1 - fitness) * r.biomass();
            double mana = c.world().laws().allows(Laws.Domain.MAGIC)
                    ? r.mana() + (c.world().laws().magicDensity() - r.mana()) * .025 : 0;
            c.world().planet().region(r.id()).update(new Region.View(r.id(), r.latitude(), r.longitude(), r.elevation(),
                    r.temperature(), r.moisture(), r.pressure(), r.water(), r.biomass() + growth, mana, r.ore(), r.genome(), r.generation()));
        }
    }
    private static final class Evolution extends DomainSystem<Region.View> {
        Evolution() { super("evolution", WorldTime.TICKS_PER_DAY * 6, "gene_pool", "environmental_pressure", "adaptation,reproduction", "traits,civilization_emergence"); }
        public List<Region.View> perceive(LoopContext c) {
            return c.world().laws().allows(Laws.Domain.LIFE) && !c.world().geologicalChangeAt(c.tick())
                    ? c.world().planet().regions().stream().map(Region::view).toList() : List.of();
        }
        protected double demand(LoopContext c, Region.View r) { return r.biomass(); }
        protected void act(LoopContext c, Region.View r, double urgency) {
            var random = c.random(r.id());
            Genome winner = r.genome();
            for (int i = 0; i < 4; i++) {
                Genome child = r.genome().offspring(r.genome(), random);
                // Cognition/social cooperation are beneficial when food allows their energy cost.
                if (score(child, r) > score(winner, r)) winner = child;
            }
            c.world().planet().region(r.id()).update(new Region.View(r.id(), r.latitude(), r.longitude(), r.elevation(),
                    r.temperature(), r.moisture(), r.pressure(), r.water(), r.biomass(), r.mana(), r.ore(), winner, r.generation() + 1));
        }
        private double score(Genome genome, Region.View r) {
            return genome.fitness(r.temperature(), r.water(), r.mana()) + r.biomass() * (genome.cognition() * .22 + genome.sociality() * .14);
        }
        protected void adapt(LoopContext c, Region.View old) {
            var region = c.world().planet().region(old.id());
            if (region.habitable() && region.view().genome().cognition() >= .6 && region.view().genome().sociality() >= .45
                    && c.world().cities().size() < c.world().cityLimit() && c.world().population() + 16 <= c.world().populationLimit()
                    && c.world().cities().stream().noneMatch(city -> city.region() == old.id()) && c.world().canSettle(region)) {
                var city = c.world().foundCity(region);
                c.emit("city.founded", Long.toString(city.id()), city.name() + "由社会性物种形成", .95);
            }
        }
    }
    private static final class Geology extends DomainSystem<Region.View> {
        private List<Region.View> snapshot;
        Geology() { super("geology", WorldTime.TICKS_PER_DAY * 24, "plates,terrain", "slope,water", "erosion", "sediment,elevation"); }
        public List<Region.View> perceive(LoopContext c) {
            snapshot = c.world().planet().regions().stream().map(Region::view).toList();
            // Chunk geography was already eroded before generation and must remain independent of exploration order.
            return c.world().terrain().isEmpty() && c.world().phase() == WorldModel.Phase.GENESIS && c.world().laws().allows(Laws.Domain.PHYSICS) ? snapshot : List.of();
        }
        protected double demand(LoopContext c, Region.View r) { return 1; }
        protected void act(LoopContext c, Region.View r, double urgency) {
            double average = c.world().planet().neighbors(r.id()).stream().map(n -> snapshot.get(n.id())).mapToDouble(Region.View::elevation).average().orElse(r.elevation());
            double height = r.elevation() + (average - r.elevation()) * .003 * r.water();
            c.world().planet().region(r.id()).update(new Region.View(r.id(), r.latitude(), r.longitude(), height, r.temperature(),
                    r.moisture(), r.pressure(), r.water(), r.biomass(), r.mana(), r.ore(), r.genome(), r.generation()));
        }
    }
    private static final class WorldWill extends DomainSystem<WorldModel> {
        WorldWill() { super("world_will", WorldTime.TICKS_PER_DAY * 6, "planet,fortune", "ecology,civilization", "self_preservation", "blessings"); }
        public List<WorldModel> perceive(LoopContext c) { return List.of(c.world()); }
        protected double demand(LoopContext c, WorldModel world) { return 1; }
        protected void act(LoopContext c, WorldModel world, double urgency) {
            double biomass = world.planet().regions().stream().mapToDouble(r -> r.view().biomass()).average().orElse(0);
            double stability = world.cities().stream().mapToDouble(city -> city.fortune().stability()).average().orElse(.5);
            world.worldWill(new Fortune(biomass, stability, .5), world.worldWillReserve() + .04);
            if (world.worldWillReserve() >= .2 && world.laws().allows(Laws.Domain.MAGIC)) {
                world.cities().stream().filter(city -> city.population() > 0 && city.foodDays() < 1).findFirst().ifPresent(city -> {
                    city.stocks().add(Resource.FOOD, city.population());
                    world.worldWill(world.worldFortune(), world.worldWillReserve() - .2);
                    c.emit("world.blessing", Long.toString(city.id()), "天道消耗气运缓解饥荒", .8);
                });
            }
        }
    }
}
