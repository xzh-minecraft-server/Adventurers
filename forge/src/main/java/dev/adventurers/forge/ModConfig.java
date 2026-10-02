package dev.adventurers.forge;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ModConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.IntValue CITY_LIMIT = BUILDER.comment("Maximum evolving city-states in this world's regional simulation.").defineInRange("cityLimit", 4, 1, 64);
    public static final ForgeConfigSpec.IntValue POPULATION_LIMIT = BUILDER.defineInRange("populationLimit", 4096, 16, 100000);
    public static final ForgeConfigSpec.IntValue INSERTION_ERA = BUILDER.comment("0=stone, 1=bronze, 2=iron, 3=gunpowder, 4=steam, 5=electric, 6=information. Applies to new worlds.").defineInRange("insertionEra", 0, 0, 6);
    public static final ForgeConfigSpec.IntValue GENESIS_LIMIT = BUILDER.comment("Maximum accelerated genesis days before waiting for operator intervention.").defineInRange("genesisDayLimit", 2048, 24, 4096);
    public static final ForgeConfigSpec.IntValue WORK_BUDGET = BUILDER.comment("Due system operations per server tick; a simultaneous batch always finishes.").defineInRange("workBudget", 32, 1, 256);
    public static final ForgeConfigSpec.IntValue GENESIS_PACE = BUILDER.comment("Server ticks per accelerated simulation day. Increase to watch geological epochs longer.").defineInRange("genesisTicksPerDay",5,1,100);
    public static final ForgeConfigSpec.IntValue PREGEN_RADIUS = BUILDER.comment("Generate this chunk radius around planetary spawn and civilizations before insertion; wilderness stays on demand.").defineInRange("pregenerationRadius",2,0,4);
    public static final ForgeConfigSpec.IntValue PREGEN_BUDGET = BUILDER.comment("Maximum concurrent FULL chunk requests and starts per tick.").defineInRange("pregenerationBudget",2,1,8);
    public static final ForgeConfigSpec.DoubleValue MAGIC_DENSITY = BUILDER.comment("Initial planetary magic density; saved with a new simulation world.").defineInRange("magicDensity",1.0,0.0,8.0);
    public static final ForgeConfigSpec.IntValue VISIBLE_CITIZENS = BUILDER.defineInRange("visibleCitizensPerCity", 16, 0, 64);
    public static final ForgeConfigSpec.BooleanValue BUILD_IN_WORLD = BUILDER.comment("Project simulated building stages into loaded city areas, only replacing air/replaceable plants.").define("buildInWorld", true);
    public static final ForgeConfigSpec SPEC = BUILDER.build();
    private ModConfig() {}
}
