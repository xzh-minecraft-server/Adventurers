package dev.adventurers.forge;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

@Mod(AdventurersMod.ID)
public final class AdventurersMod {
    public static final String ID = "adventurers";
    public AdventurersMod(FMLJavaModLoadingContext context) {
        ModItems.ITEMS.register(context.getModBusGroup());
        dev.adventurers.forge.worldgen.ModWorldgen.GENERATORS.register(context.getModBusGroup());
        dev.adventurers.forge.worldgen.ModWorldgen.BIOMES.register(context.getModBusGroup());
        dev.adventurers.forge.worldgen.ModWorldgen.TICKETS.register(context.getModBusGroup());
        ModGameTests.FUNCTIONS.register(context.getModBusGroup());
        context.registerConfig(net.minecraftforge.fml.config.ModConfig.Type.SERVER,ModConfig.SPEC);
        RegisterCommandsEvent.BUS.addListener(event->AdventCommands.register(event.getDispatcher()));
        ServerStartedEvent.BUS.addListener(event->ServerRuntime.start(event.getServer()));
        ServerStoppingEvent.BUS.addListener(event->ServerRuntime.stop(event.getServer()));
        TickEvent.ServerTickEvent.Post.BUS.addListener(event->ServerRuntime.get(event.server()).tick());
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(event->{
            if(event.getEntity() instanceof ServerPlayer player)ServerRuntime.get(player.level().getServer()).greet(player);
        });
        PlayerEvent.PlayerRespawnEvent.BUS.addListener(event->{
            if(!event.isEndConquered()&&event.getEntity() instanceof ServerPlayer player){
                var runtime=ServerRuntime.get(player.level().getServer());
                if(runtime.world().player(player.getUUID()).isPresent())try{
                    var reborn=runtime.simulation().reincarnate(player.getUUID());
                    runtime.projection().placePlayer(player,runtime.world().city(reborn.city()).orElseThrow());
                    player.sendSystemMessage(Component.literal("[冒险人] 旧身份已结束，你以新身份降生。"));
                }catch(IllegalStateException error){player.sendSystemMessage(Component.literal(error.getMessage()));}
            }
        });
        LivingDeathEvent.BUS.addListener((event,canceled)->{
            if(canceled)return;
            long id=WorldProjection.citizenId(event.getEntity());
            if(id>0 && event.getEntity().level() instanceof net.minecraft.server.level.ServerLevel level){
                var runtime=ServerRuntime.get(level.getServer());
                for(var city:runtime.world().cities())for(var person:city.citizens())if(person.id()==id){
                    person.injure(1);
                    runtime.world().record(new dev.adventurers.core.api.WorldEvent(runtime.world().tick(),"person.killed",Long.toString(city.id()),"居民 "+id+"遇害",.9));
                }
            }
        });
        PlayerInteractEvent.EntityInteractSpecific.BUS.addListener(event->{
            if(event.getHand()!=InteractionHand.MAIN_HAND||!(event.getEntity() instanceof ServerPlayer player))return false;
            long citizen=WorldProjection.citizenId(event.getTarget());
            if(citizen<1)return false;
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            var runtime=ServerRuntime.get(player.level().getServer());
            for(var city:runtime.world().cities())for(var person:city.citizens())if(person.id()==citizen){
                player.sendSystemMessage(Component.literal("[居民 "+citizen+"] 我在"+city.name()+"生活，最近在做"+person.activity()+"。粮食还够"+Math.round(city.foodDays())+"天。/advent requests 可询问当前委托。"));
                return true;
            }
            return true;
        });
    }
}
