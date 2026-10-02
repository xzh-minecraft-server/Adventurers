package dev.adventurers.forge.worldgen;

import com.mojang.serialization.Codec;
import dev.adventurers.core.api.WorldTime;
import dev.adventurers.core.world.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.*;
import net.minecraft.world.level.saveddata.maps.*;

/** One shared locked vanilla map: it can update from the simulation without being overwritten by vanilla cartography. */
public final class PlanetAtlasMap {
    public static final class Index extends SavedData {
        private int mapId;
        private Index(int mapId) { this.mapId=mapId; }
        public static final SavedDataType<Index> TYPE=new SavedDataType<>(Identifier.fromNamespaceAndPath("adventurers","planet_atlas"),
                ()->new Index(-1),Codec.INT.fieldOf("map_id").xmap(Index::new,index->index.mapId).codec(),null);
    }
    private final ServerLevel level;
    private final WorldModel world;
    private long lastDay=-1;
    private int lastCities=-1;
    public PlanetAtlasMap(ServerLevel level,WorldModel world) { this.level=level;this.world=world; }
    public ItemStack stack() {
        var atlas=world.observedTerrain().orElseThrow(()->new IllegalStateException("当前世界使用原版地形；请新建冒险人星球世界以查看星球图"));
        var index=level.getDataStorage().computeIfAbsent(Index.TYPE);
        MapId id=new MapId(index.mapId);
        if(index.mapId<0||level.getMapData(id)==null) {
            var created=MapItem.create(level,0,0,(byte)4,false,false);id=created.get(DataComponents.MAP_ID);
            level.setMapData(id,MapItem.getSavedData(created,level).locked());index.mapId=id.id();index.setDirty();lastDay=-1;
        }
        render(level.getMapData(id),atlas);
        var stack=new ItemStack(Items.FILLED_MAP);stack.set(DataComponents.MAP_ID,id);
        stack.set(DataComponents.CUSTOM_NAME,Component.literal("冒险人 · 星球演化图"));
        return stack;
    }
    public void tick() {
        if(world.terrain().isEmpty())return;
        var index=level.getDataStorage().get(Index.TYPE);
        if(index!=null&&index.mapId>=0) {
            var data=level.getMapData(new MapId(index.mapId));
            if(data!=null)render(data,world.observedTerrain().orElseThrow());
        }
    }
    public void give(ServerPlayer player) {
        var map=stack();var id=map.get(DataComponents.MAP_ID);
        if(player.getInventory().contains(item->item.is(Items.FILLED_MAP)&&id.equals(item.get(DataComponents.MAP_ID))))return;
        if(!player.addItem(map))throw new IllegalStateException("请先在背包中留出一个空位，再领取演化图");
    }
    private void render(MapItemSavedData map,TerrainAtlas atlas) {
        long day=WorldTime.day(world.tick());
        if(day==lastDay&&lastCities==world.cities().size())return;
        var settings=atlas.settings();
        for(int py=0;py<128;py++)for(int px=0;px<128;px++) {
            byte color=MapColor.COLOR_BLACK.getPackedId(MapColor.Brightness.NORMAL);
            if(py>=32&&py<96) {
                double x=(px+.5-64)*settings.circumference()/128.0,z=(py+.5-64)*settings.poleDistance()/64.0;
                var column=atlas.column(x,z);
                var base=switch(column.biome()) {
                    case OCEAN,RIVER->MapColor.WATER;
                    case FROZEN_OCEAN,FROZEN_RIVER->MapColor.ICE;
                    case BEACH,DESERT->MapColor.SAND;
                    case SNOWY_PLAINS->MapColor.SNOW;
                    case STONY_PEAKS->MapColor.STONE;
                    default->MapColor.GRASS;
                };
                double biomass=world.planet().region(PlanetCoordinates.region(x,z,settings)).view().biomass();
                if(!column.submerged()&&base==MapColor.GRASS&&biomass<.2)base=MapColor.DIRT;
                color=base.getPackedId(column.ground()>100?MapColor.Brightness.HIGH:MapColor.Brightness.NORMAL);
            }
            map.updateColor(px,py,color);
        }
        for(var river:atlas.rivers()) {
            double ax=river.x1()*128/settings.circumference()+64,az=river.z1()*64/settings.poleDistance()+64;
            double bx=river.x2()*128/settings.circumference()+64,bz=river.z2()*64/settings.poleDistance()+64;
            int steps=Math.max(1,(int)Math.ceil(Math.hypot(bx-ax,bz-az)*2));
            for(int i=0;i<=steps;i++) {
                int px=Math.floorMod((int)Math.round(ax+(bx-ax)*i/steps),128),py=(int)Math.round(az+(bz-az)*i/steps);
                if(py>=32&&py<96)map.updateColor(px,py,MapColor.WATER.getPackedId(MapColor.Brightness.HIGH));
            }
        }
        for(var city:world.cities()) {
            int px=(int)(city.x()*128.0/settings.circumference()+64),py=(int)(city.z()*64.0/settings.poleDistance()+64);
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(px+dx>=0&&px+dx<128&&py+dz>=32&&py+dz<96)
                map.updateColor(px+dx,py+dz,(city.population()>0?MapColor.GOLD:MapColor.COLOR_RED).getPackedId(MapColor.Brightness.HIGH));
        }
        int epochs=world.terrain().orElseThrow().epochs();
        if(epochs>0)for(int x=8;x<120;x++)for(int y=104;y<108;y++)
            map.updateColor(x,y,(x-8<112*world.geologicalEpoch()/epochs?MapColor.GOLD:MapColor.STONE).getPackedId(MapColor.Brightness.NORMAL));
        lastDay=day;lastCities=world.cities().size();
    }
}
