package dev.adventurers.forge.worldgen;

import dev.adventurers.core.world.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Main-thread queue of asynchronous FULL chunk requests. No entity simulation or permanent forced chunks. */
public final class PlanetPregenerator implements AutoCloseable {
    private final ServerLevel level;
    private final TerrainSettings settings;
    private final LinkedHashSet<PregenerationPlan.Chunk> planned = new LinkedHashSet<>();
    private final ArrayDeque<PregenerationPlan.Chunk> waiting = new ArrayDeque<>();
    private final Map<PregenerationPlan.Chunk,CompletableFuture<?>> active = new LinkedHashMap<>();
    private int completed;
    private String failure;
    public PlanetPregenerator(ServerLevel level,TerrainSettings settings) { this.level=level;this.settings=settings; }
    public void enqueue(int x,int z,int radius) {
        for(var chunk:PregenerationPlan.around(x,z,radius,settings))if(planned.add(chunk))waiting.add(chunk);
    }
    public int total() { return planned.size(); }
    public int completed() { return completed; }
    public int active() { return active.size(); }
    public boolean ready() { return failure==null && waiting.isEmpty() && active.isEmpty(); }
    public Optional<String> failure() { return Optional.ofNullable(failure); }
    public void tick(int budget) {
        if(budget<1||budget>8)throw new IllegalArgumentException("Chunk generation budget outside 1..8");
        if(failure!=null)return;
        var iterator=active.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next();if(!entry.getValue().isDone())continue;
            var chunk=entry.getKey();var pos=new ChunkPos(chunk.x(),chunk.z());
            try {
                // join only after isDone; the server thread must keep pumping Minecraft's chunk tasks.
                entry.getValue().join();
                if(level.getChunkSource().getChunkNow(chunk.x(),chunk.z())==null)
                    throw new IllegalStateException("Chunk did not reach FULL: "+pos);
                completed++;
            }catch(RuntimeException error) { failure="区块预生成失败 "+pos+": "+error.getMessage(); }
            finally { level.getChunkSource().removeTicketWithRadius(ModWorldgen.PREGEN_TICKET.get(),pos,0);iterator.remove(); }
        }
        if(failure!=null){releaseActive();return;}
        for(int started=0;started<budget && active.size()<budget && !waiting.isEmpty();started++) {
            var chunk=waiting.removeFirst();var pos=new ChunkPos(chunk.x(),chunk.z());
            try { active.put(chunk,level.getChunkSource().addTicketAndLoadWithRadius(ModWorldgen.PREGEN_TICKET.get(),pos,0)); }
            catch(RuntimeException error) {
                failure="区块预生成启动失败 "+pos+": "+error.getMessage();
                level.getChunkSource().removeTicketWithRadius(ModWorldgen.PREGEN_TICKET.get(),pos,0);releaseActive();return;
            }
        }
    }
    private void releaseActive() {
        for(var chunk:active.keySet())level.getChunkSource().removeTicketWithRadius(ModWorldgen.PREGEN_TICKET.get(),new ChunkPos(chunk.x(),chunk.z()),0);
        active.clear();
    }
    @Override public void close() { releaseActive();waiting.clear(); }
}
