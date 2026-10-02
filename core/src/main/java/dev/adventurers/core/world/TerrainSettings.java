package dev.adventurers.core.world;

/** Persisted geography contract. Changing the algorithm requires a new, supported version. */
public record TerrainSettings(int regionSize, int version) {
    public static final int COLUMNS = 24, ROWS = 12;
    public TerrainSettings {
        if (regionSize != 96 && regionSize != 192 && regionSize != 384)
            throw new IllegalArgumentException("Planet region size must be 96, 192 or 384");
        if (version != 1 && version != 2) throw new IllegalArgumentException("Unsupported terrain version; preserve the original world");
    }
    public TerrainSettings(int regionSize) { this(regionSize, 1); }
    public int circumference() { return COLUMNS * regionSize; }
    public int poleDistance() { return ROWS * regionSize; }
}
