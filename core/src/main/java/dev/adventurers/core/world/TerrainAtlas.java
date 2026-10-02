package dev.adventurers.core.world;

import dev.adventurers.core.api.Numbers;
import dev.adventurers.core.life.Genome;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable, seed-bound geology shared by chunk workers, biomes, maps and settlement selection.
 * Continental heights come from spherical plates, erosion and drainage, not an independent noise terrain.
 */
public final class TerrainAtlas {
    public static final int SEA_LEVEL = 63, MIN_Y = -64, HEIGHT = 384;
    static final int COLS = 96, ROWS = 48;
    public enum Biome {
        OCEAN, FROZEN_OCEAN, BEACH, DESERT, PLAINS, FOREST, JUNGLE,
        TAIGA, SNOWY_PLAINS, STONY_PEAKS, RIVER, FROZEN_RIVER, SWAMP
    }
    public record Column(int ground, int water, Biome biome, double temperature, double moisture,
                         double ore, double mana, boolean river) {
        public boolean submerged() { return water > ground; }
    }
    public record Site(int x, int z, int y, double score) {}
    public record River(double x1, double z1, double x2, double z2) {}
    public record Statistics(int landCells, int oceanCells, int riverCells, int lakeCells, int lowest, int highest) {}
    private final long seed;
    private final TerrainSettings settings;
    private final double[] height = new double[COLS * ROWS], moisture = new double[COLS * ROWS];
    private final double[] ore = new double[COLS * ROWS], mana = new double[COLS * ROWS];
    private final double[] filled = new double[COLS * ROWS], drainage = new double[COLS * ROWS];
    private final int[] receiver = new int[COLS * ROWS];
    private final Map<Integer, Optional<Site>> sites = new ConcurrentHashMap<>();
    private final double southHeight, northHeight;
    private volatile Site spawnSite;
    private final List<TerrainAtlas> history;

    public TerrainAtlas(long seed, TerrainSettings settings) {
        this.seed = seed; this.settings = Objects.requireNonNull(settings);
        if (settings.version() == 2) {
            history = GeologicalHistory.generate(seed, settings);
            var last = history.getLast();
            copy(last.height, height); copy(last.moisture, moisture); copy(last.ore, ore); copy(last.mana, mana);
            copy(last.filled, filled); copy(last.drainage, drainage);
            System.arraycopy(last.receiver, 0, receiver, 0, receiver.length);
            southHeight = last.southHeight; northHeight = last.northHeight;
            return;
        }
        history = List.of();
        var plates = Planet.genesis(seed, COLS, ROWS);
        for (var region : plates.regions()) {
            var r = region.view(); int i = r.id();
            height[i] = Numbers.clamp(SEA_LEVEL + r.elevation() * .075, MIN_Y + 12, 260);
            ore[i] = r.ore(); mana[i] = r.mana();
            double latitude = Math.abs(Math.sin(r.latitude()));
            moisture[i] = Numbers.unit(.55 + .2 * Math.cos(latitude * 3 * Math.PI) - Math.max(0, height[i] - SEA_LEVEL) * .002);
            if (height[i] < SEA_LEVEL) moisture[i] = .9;
        }
        // Erode hills toward their lower neighbours, retaining continental/plate-scale relief.
        for (int step = 0; step < 32; step++) {
            double[] sediment = new double[height.length];
            for (int i = 0; i < height.length; i++) {
                int lowest = i;
                for (int n : neighbors(i)) if (height[n] < height[lowest]) lowest = n;
                if (lowest != i) {
                    double moved = Math.min(1.2, (height[i] - height[lowest]) * .025 * moisture[i]);
                    sediment[i] -= moved; sediment[lowest] += moved;
                }
            }
            for (int i = 0; i < height.length; i++) height[i] += sediment[i];
        }
        routeWater();
        double south = 0, north = 0;
        for (int col = 0; col < COLS; col++) { south += height[col]; north += height[(ROWS - 1) * COLS + col]; }
        southHeight = south / COLS; northHeight = north / COLS;
    }
    TerrainAtlas(long seed, TerrainSettings settings, double[] heights, double[] humidity, double[] minerals, double[] magic) {
        this.seed = seed; this.settings = settings; history = List.of();
        copy(heights, height); copy(humidity, moisture); copy(minerals, ore); copy(magic, mana);
        routeWater();
        double south = 0, north = 0;
        for (int col = 0; col < COLS; col++) { south += height[col]; north += height[(ROWS - 1) * COLS + col]; }
        southHeight = south / COLS; northHeight = north / COLS;
    }
    private static void copy(double[] source, double[] target) { System.arraycopy(source, 0, target, 0, target.length); }
    double gridHeight(int cell) { return height[cell]; }
    double gridMoisture(int cell) { return moisture[cell]; }
    double gridDrainage(int cell) { return drainage[cell]; }
    int gridReceiver(int cell) { return receiver[cell]; }
    public int epochs() { return history.isEmpty() ? 0 : history.size() - 1; }
    public TerrainAtlas frame(int epoch) {
        if (epoch < 0 || epoch > epochs()) throw new IllegalArgumentException("Geological epoch outside history");
        return history.isEmpty() ? this : history.get(epoch);
    }
    public Planet initialPlanet() { return frame(0).simulationPlanet(); }
    public long seed() { return seed; }
    public TerrainSettings settings() { return settings; }
    private int index(int x, int z) {
        while (z < 0 || z >= ROWS) {
            if (z < 0) { z = -z - 1; x += COLS / 2; }
            if (z >= ROWS) { z = 2 * ROWS - z - 1; x += COLS / 2; }
        }
        return z * COLS + Math.floorMod(x, COLS);
    }
    private int[] neighbors(int i) {
        int x = i % COLS, z = i / COLS;
        return new int[]{index(x - 1,z),index(x + 1,z),index(x,z - 1),index(x,z + 1)};
    }
    private void routeWater() {
        Arrays.fill(receiver, -1); Arrays.fill(filled, Double.POSITIVE_INFINITY);
        record Flood(int cell, double elevation) {}
        var queue = new PriorityQueue<Flood>(Comparator.comparingDouble(Flood::elevation).thenComparingInt(Flood::cell));
        boolean[] visited = new boolean[height.length]; var order = new ArrayList<Integer>();
        for (int i = 0; i < height.length; i++) if (height[i] <= SEA_LEVEL) {
            visited[i] = true; filled[i] = SEA_LEVEL; queue.add(new Flood(i, SEA_LEVEL));
        }
        if (queue.isEmpty()) { // A wholly continental seed still drains into its lowest enclosed basin.
            int low = 0; for (int i = 1; i < height.length; i++) if (height[i] < height[low]) low = i;
            visited[low] = true; filled[low] = height[low]; queue.add(new Flood(low, filled[low]));
        }
        while (!queue.isEmpty()) {
            int current = queue.remove().cell(); order.add(current);
            for (int n : neighbors(current)) if (!visited[n]) {
                visited[n] = true; receiver[n] = current;
                filled[n] = Math.max(height[n], filled[current] + .01);
                queue.add(new Flood(n, filled[n]));
            }
        }
        for (int i = 0; i < height.length; i++) drainage[i] = .3 + moisture[i];
        for (int n = order.size() - 1; n >= 0; n--) {
            int i = order.get(n); if (receiver[i] >= 0) drainage[receiver[i]] += drainage[i];
        }
    }
    private double interpolate(double[] field, double gx, double gz) {
        int x = (int)Math.floor(gx), z = (int)Math.floor(gz);
        double fx = gx - x, fz = gz - z;
        return lerp(lerp(field[index(x,z)], field[index(x + 1,z)], fx),
                lerp(field[index(x,z + 1)], field[index(x + 1,z + 1)], fx), fz);
    }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    public Column column(double x, double z) {
        var point = PlanetCoordinates.normalize(x, z, settings); x = point.x(); z = point.z();
        double cell = settings.regionSize() / 4.0;
        double gx = x / cell + COLS / 2.0 - .5, gz = z / cell + ROWS / 2.0 - .5;
        double land = interpolate(height, gx, gz), humidity = interpolate(moisture, gx, gz);
        double polar = Math.max(0, (Math.abs(z) - settings.poleDistance() / 2.0 + cell / 2) / (cell / 2));
        land = lerp(land, z < 0 ? southHeight : northHeight, polar);
        double water = SEA_LEVEL;
        double lake = interpolate(filled, gx, gz);
        if (lake - land > 1.5 && polar == 0) water = Math.max(water, lake - .5);
        boolean river = false;
        if (polar == 0) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            int cx = (int)Math.floor(gx) + dx, cz = (int)Math.floor(gz) + dz;
            if (cz < 1 || cz >= ROWS - 1) continue;
            int i = index(cx,cz), next = receiver[i];
            if (next < 0 || height[i] <= SEA_LEVEL || drainage[i] < 3.8) continue;
            double ax = (cx + .5 - COLS / 2.0) * cell, az = (cz + .5 - ROWS / 2.0) * cell;
            double bx = ax + PlanetCoordinates.longitudeDistance((next % COLS - i % COLS) * cell, 0, settings.circumference());
            double bz = az + (next / COLS - i / COLS) * cell;
            double vx = bx - ax, vz = bz - az;
            double t = Numbers.clamp(((x - ax) * vx + (z - az) * vz) / (vx * vx + vz * vz), 0, 1);
            double distance = Math.hypot(x - (ax + t * vx), z - (az + t * vz));
            double width = Math.min(5, 1.2 + Math.sqrt(drainage[i]) * .25);
            if (distance <= width + 2) {
                double level = lerp(filled[i], filled[next], t) - .75;
                double carving = Numbers.unit((width + 2 - distance) / 2);
                land = Math.min(land, lerp(land, level - 2.5, carving));
                if (distance < width) { water = Math.max(water, level); river = true; humidity = Math.max(.7, humidity); }
            }
        }
        int ground = (int)Math.round(land), waterY = (int)Math.floor(water);
        double temperature = 29 - 46 * Math.abs(Math.sin(z / settings.poleDistance() * Math.PI)) - Math.max(0, land - SEA_LEVEL) * .12;
        Biome biome;
        if (ground < SEA_LEVEL - 2) biome = temperature < 0 ? Biome.FROZEN_OCEAN : Biome.OCEAN;
        else if (river || waterY > ground) biome = temperature < 0 ? Biome.FROZEN_RIVER : Biome.RIVER;
        else if (ground <= SEA_LEVEL + 2) biome = Biome.BEACH;
        else if (temperature < -6) biome = Biome.SNOWY_PLAINS;
        else if (ground > 155) biome = Biome.STONY_PEAKS;
        else if (temperature < 5) biome = Biome.TAIGA;
        else if (humidity < .35 && temperature > 14) biome = Biome.DESERT;
        else if (humidity > .64 && temperature > 22) biome = Biome.JUNGLE;
        else if (humidity > .68 && ground < SEA_LEVEL + 8) biome = Biome.SWAMP;
        else if (humidity > .48) biome = Biome.FOREST;
        else biome = Biome.PLAINS;
        return new Column(ground,waterY,biome,temperature,humidity,interpolate(ore,gx,gz),interpolate(mana,gx,gz),river);
    }
    /** Fracture/tube cavities below the surface; evaluated in spherical coordinates for matching boundaries. */
    public boolean cave(double x, int y, double z, Column column) {
        if (y <= MIN_Y + 5 || y >= column.ground() - 9 || column.submerged() && y > SEA_LEVEL - 12) return false;
        var p = PlanetCoordinates.normalize(x,z,settings);
        double lat = p.z() / settings.poleDistance() * Math.PI, lon = p.x() / settings.circumference() * 2 * Math.PI;
        double radius = settings.circumference() / (2 * Math.PI);
        double sx = Math.cos(lat) * Math.cos(lon) * radius, sz = Math.cos(lat) * Math.sin(lon) * radius;
        double sy = Math.sin(lat) * radius;
        double phase = (Numbers.mix(seed) & 65535) * .001;
        double tube = Math.sin(sx * .085 + y * .13 + phase) + Math.sin(sz * .07 - y * .11) + Math.sin(sy * .075 + y * .04);
        return Math.abs(tube) < .16 && Math.cos(sx * .033 - sz * .04 + y * .16) > .65;
    }
    public Optional<Site> settlement(int region) {
        if (region < 0 || region >= TerrainSettings.COLUMNS * TerrainSettings.ROWS) throw new IllegalArgumentException("Region outside atlas");
        return sites.computeIfAbsent(region, this::findSettlement);
    }
    /** Give the vanilla spawn search a dry starting chunk, even when the chart origin is an ocean. */
    public Site spawnSite() {
        var result = spawnSite;
        if (result != null) return result;
        synchronized (this) {
            if (spawnSite == null) {
                var regions = new ArrayList<Integer>();
                for (int i = 0; i < TerrainSettings.COLUMNS * TerrainSettings.ROWS; i++) regions.add(i);
                regions.sort(Comparator.comparingDouble(i -> {
                    double x = i % TerrainSettings.COLUMNS + .5 - TerrainSettings.COLUMNS / 2.0;
                    double z = i / TerrainSettings.COLUMNS + .5 - TerrainSettings.ROWS / 2.0;
                    return x * x + z * z;
                }));
                spawnSite = regions.stream().map(this::settlement).flatMap(Optional::stream).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Planet has no dry, buildable spawn site; choose another seed"));
            }
            return spawnSite;
        }
    }
    private Optional<Site> findSettlement(int region) {
        int centerX = (region % TerrainSettings.COLUMNS - TerrainSettings.COLUMNS / 2) * settings.regionSize() + settings.regionSize() / 2;
        int centerZ = (region / TerrainSettings.COLUMNS - TerrainSettings.ROWS / 2) * settings.regionSize() + settings.regionSize() / 2;
        Site best = null; int step = settings.regionSize() / 10;
        for (int dz = -4; dz <= 4; dz++) for (int dx = -4; dx <= 4; dx++) {
            int x = centerX + dx * step, z = centerZ + dz * step; var c = column(x,z);
            if (c.submerged() || c.ground() <= SEA_LEVEL + 1 || c.temperature() < -8 || c.biome() == Biome.STONY_PEAKS) continue;
            int slope = 0; boolean dry = true;
            for (int[] offset : new int[][]{{-8,0},{8,0},{0,-8},{0,8},{-6,-6},{6,6},{-6,6},{6,-6}}) {
                var nearby = column(x + offset[0], z + offset[1]);
                slope = Math.max(slope, Math.abs(nearby.ground() - c.ground())); dry &= !nearby.submerged();
            }
            if (!dry || slope > 3) continue;
            double score = c.moisture() * 2 + c.ore() + c.mana() * .3 - slope * .3 - Math.abs(c.temperature() - 18) * .015;
            for (int[] offset : new int[][]{{-24,0},{24,0},{0,-24},{0,24}}) if (column(x+offset[0],z+offset[1]).submerged()) score += .2;
            if (best == null || score > best.score()) best = new Site(x,z,c.ground()+1,score);
        }
        return Optional.ofNullable(best);
    }
    public Planet simulationPlanet() {
        var regions = new ArrayList<Region>();
        for (int row = 0; row < TerrainSettings.ROWS; row++) for (int col = 0; col < TerrainSettings.COLUMNS; col++) {
            double x = (col + .5 - TerrainSettings.COLUMNS / 2.0) * settings.regionSize();
            double z = (row + .5 - TerrainSettings.ROWS / 2.0) * settings.regionSize();
            var c = column(x,z);
            regions.add(new Region(new Region.View(regions.size(),z / settings.poleDistance() * Math.PI,x / settings.circumference() * 2 * Math.PI,
                    (c.ground() - SEA_LEVEL) / .075,c.temperature(),c.moisture(),1,c.submerged()?1: .2+c.moisture()*.6,
                    0,c.mana(),c.ore(),Genome.primitive(c.temperature()),0)));
        }
        return new Planet(TerrainSettings.COLUMNS,TerrainSettings.ROWS,regions);
    }
    public Statistics statistics() {
        int land=0, oceans=0, rivers=0, lakes=0, low=320, high=-64;
        for (int i=0;i<height.length;i++) {
            if(height[i]>SEA_LEVEL)land++;else oceans++;
            if(height[i]>SEA_LEVEL&&drainage[i]>=3.8&&receiver[i]>=0)rivers++;
            if(height[i]>SEA_LEVEL&&filled[i]-height[i]>1.5)lakes++;
            low=Math.min(low,(int)Math.floor(height[i]));high=Math.max(high,(int)Math.ceil(height[i]));
        }
        return new Statistics(land,oceans,rivers,lakes,low,high);
    }
    public List<River> rivers() {
        var result=new ArrayList<River>();double cell=settings.regionSize()/4.0;
        for(int i=0;i<height.length;i++)if(receiver[i]>=0&&height[i]>SEA_LEVEL&&drainage[i]>=3.8&&i/COLS>0&&i/COLS<ROWS-1) {
            int next=receiver[i];double x=(i%COLS+.5-COLS/2.0)*cell,z=(i/COLS+.5-ROWS/2.0)*cell;
            result.add(new River(x,z,x+PlanetCoordinates.longitudeDistance((next%COLS-i%COLS)*cell,0,settings.circumference()),z+(next/COLS-i/COLS)*cell));
        }
        return List.copyOf(result);
    }
}
