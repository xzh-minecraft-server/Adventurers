package dev.adventurers.core.world;

import dev.adventurers.core.api.*;
import dev.adventurers.core.civilization.*;
import dev.adventurers.core.life.*;
import dev.adventurers.core.player.*;
import java.util.*;

/** Shared data contract. Domains interact through this state and events, not other domain implementations. */
public final class WorldModel {
    public enum Phase { GENESIS, PLAYING }
    private final long seed;
    private final Planet planet;
    private final Laws laws;
    private final Map<Long, City> cities = new LinkedHashMap<>();
    private final Map<Long, Civilization> civilizations = new LinkedHashMap<>();
    private final Map<Long, Quest> quests = new LinkedHashMap<>();
    private final Map<UUID, PlayerProfile> players = new LinkedHashMap<>();
    private final ArrayDeque<WorldEvent> history = new ArrayDeque<>();
    private List<LoadingTier.Observer> observers = List.of();
    private long tick, nextId = 1;
    private Phase phase = Phase.GENESIS;
    private Fortune worldFortune = Fortune.healthy();
    private double worldWillReserve = 1;
    private final int cityLimit, populationLimit;
    private TerrainAtlas terrain;

    public WorldModel(long seed, Planet planet, Laws laws, int cityLimit, int populationLimit) {
        if (cityLimit < 1 || cityLimit > 64 || populationLimit < 16 || populationLimit > 100_000) throw new IllegalArgumentException();
        this.seed = seed; this.planet = planet; this.laws = laws; this.cityLimit = cityLimit; this.populationLimit = populationLimit;
    }
    public static WorldModel create(long seed) { return new WorldModel(seed, Planet.genesis(seed, 24, 12), Laws.overworld(), 4, 4096); }
    public long seed() { return seed; }
    public long tick() { return tick; }
    public long nextId() { return nextId; }
    public long allocateId() { return nextId++; }
    public int cityLimit() { return cityLimit; }
    public int populationLimit() { return populationLimit; }
    public Planet planet() { return planet; }
    public Optional<TerrainAtlas> terrain() { return Optional.ofNullable(terrain); }
    public int geologicalEpoch() {
        return terrain == null ? 0 : (int)Math.min(terrain.epochs(), WorldTime.day(tick) / GeologicalHistory.DAYS_PER_EPOCH);
    }
    public boolean geographyReady() { return terrain == null || geologicalEpoch() == terrain.epochs(); }
    public Optional<TerrainAtlas> observedTerrain() { return terrain().map(atlas -> atlas.frame(geologicalEpoch())); }
    public boolean geologicalChangeAt(long tick) {
        return terrain != null && terrain.epochs() > 0 && tick > 0
                && tick <= (long)terrain.epochs() * GeologicalHistory.DAYS_PER_EPOCH * WorldTime.TICKS_PER_DAY
                && tick % (GeologicalHistory.DAYS_PER_EPOCH * WorldTime.TICKS_PER_DAY) == 0;
    }
    public void attachTerrain(TerrainAtlas terrain) {
        if (terrain.seed() != seed || planet.columns() != TerrainSettings.COLUMNS || planet.rows() != TerrainSettings.ROWS)
            throw new IllegalArgumentException("Terrain and simulation geography disagree");
        if (this.terrain != null && !this.terrain.settings().equals(terrain.settings()))
            throw new IllegalArgumentException("Cannot change the geography of an existing world");
        this.terrain = terrain;
    }
    public boolean canSettle(Region region) { return geographyReady() && (terrain == null || terrain.settlement(region.view().id()).isPresent()); }
    public Laws laws() { return laws; }
    public Phase phase() { return phase; }
    public Fortune worldFortune() { return worldFortune; }
    public double worldWillReserve() { return worldWillReserve; }
    public void worldWill(Fortune fortune, double reserve) { worldFortune = fortune; worldWillReserve = Numbers.unit(reserve); }
    public void setTick(long tick) {
        if (tick < this.tick) throw new IllegalArgumentException("Clock cannot go backwards");
        this.tick = tick;
    }
    public void phase(Phase phase) { this.phase = Objects.requireNonNull(phase); }
    public void restoreClock(long tick, long nextId, Phase phase) {
        if (tick < 0 || nextId < 1) throw new IllegalArgumentException();
        this.tick = tick; this.nextId = nextId; this.phase = phase;
    }
    public Collection<City> cities() { return Collections.unmodifiableCollection(cities.values()); }
    public Collection<Civilization> civilizations() { return Collections.unmodifiableCollection(civilizations.values()); }
    public Collection<Quest> quests() { return Collections.unmodifiableCollection(quests.values()); }
    public Collection<PlayerProfile> players() { return Collections.unmodifiableCollection(players.values()); }
    public List<WorldEvent> history() { return List.copyOf(history); }
    public Optional<City> city(long id) { return Optional.ofNullable(cities.get(id)); }
    public Optional<Quest> quest(long id) { return Optional.ofNullable(quests.get(id)); }
    public Optional<PlayerProfile> player(UUID id) { return Optional.ofNullable(players.get(id)); }
    public void putCity(City city) { cities.put(city.id(), city); }
    public void putCivilization(Civilization civilization) { civilizations.put(civilization.id(), civilization); }
    public void putPlayer(PlayerProfile player) { players.put(player.id(), player); }
    public void addQuest(Quest quest) {
        quests.put(quest.id(), quest);
        if (quests.size() > 256) {
            var iterator = quests.values().iterator();
            while (quests.size() > 256 && iterator.hasNext()) if (!iterator.next().active()) iterator.remove();
        }
    }
    public void record(WorldEvent event) {
        history.addLast(event);
        while (history.size() > 256) history.removeFirst();
    }
    public void observe(List<LoadingTier.Observer> observers) { this.observers = List.copyOf(observers); }
    public LoadingTier tier(City city) { return LoadingTier.at(city.x(), city.z(), observers); }
    public Optional<City> nearest(double x, double z, double range) {
        return cities.values().stream().filter(c -> c.population() > 0 && Math.hypot(x - c.x(), z - c.z()) <= range)
                .min(Comparator.comparingDouble(c -> Math.hypot(x - c.x(), z - c.z())));
    }
    public int population() { return cities.values().stream().mapToInt(City::population).sum(); }
    public City foundCity(Region region) {
        if (!geographyReady()) throw new IllegalStateException("地质演化尚未完成，不能提前选址");
        if (cities.size() >= cityLimit || population() + 16 > populationLimit) throw new IllegalStateException("World population/city budget reached");
        var v = region.view();
        var site = terrain == null ? null : terrain.settlement(v.id()).orElseThrow(() -> new IllegalStateException("No dry, buildable settlement site in this region"));
        long civId = allocateId(), cityId = allocateId();
        var city = new City(cityId, civId, v.id(), "城邦·" + cityId,
                site == null ? (v.id() % planet.columns() - planet.columns() / 2) * 96 : site.x(),
                site == null ? (v.id() / planet.columns() - planet.rows() / 2) * 96 : site.z());
        city.stocks().add(Resource.FOOD, 24);
        city.stocks().add(Resource.WOOD, 48);
        city.stocks().add(Resource.STONE, 32);
        for (int i = 0; i < 16; i++) city.addCitizen(new Citizen(allocateId(), cityId, v.genome(), (16 + i % 12) * 24));
        city.addBuilding(new Building(allocateId(), 7, 7));
        putCity(city);
        putCivilization(new Civilization(civId, "文明·" + civId, cityId, List.of(cityId), Fortune.healthy()));
        return city;
    }
}
