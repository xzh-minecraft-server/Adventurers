package dev.adventurers.core.world;

import java.util.*;

/** Bounded settlement footprints, ordered from the landing area outwards, with spherical seam normalization. */
public final class PregenerationPlan {
    public record Chunk(int x, int z) {}
    private PregenerationPlan() {}
    public static List<Chunk> around(int x, int z, int radius, TerrainSettings settings) {
        if (radius < 0 || radius > 4) throw new IllegalArgumentException("Pregeneration radius must be 0..4 chunks");
        var chunks = new LinkedHashSet<Chunk>();
        for (int ring = 0; ring <= radius; ring++) for (int dz = -ring; dz <= ring; dz++) for (int dx = -ring; dx <= ring; dx++) {
            if (Math.max(Math.abs(dx),Math.abs(dz)) != ring) continue;
            var point = PlanetCoordinates.normalize(((x >> 4)+dx)*16+8,((z >> 4)+dz)*16+8,settings);
            chunks.add(new Chunk(Math.floorDiv((int)Math.floor(point.x()),16),Math.floorDiv((int)Math.floor(point.z()),16)));
        }
        return List.copyOf(chunks);
    }
}
