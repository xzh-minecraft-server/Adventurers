package dev.adventurers.forge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.adventurers.core.api.*;
import dev.adventurers.core.civilization.City;
import dev.adventurers.core.magic.*;
import dev.adventurers.core.persistence.WorldCodec;
import dev.adventurers.core.player.*;
import dev.adventurers.core.world.*;
import net.minecraft.commands.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import java.util.*;
import static net.minecraft.commands.Commands.*;

public final class AdventCommands {
    @FunctionalInterface private interface Action { String run(CommandContext<CommandSourceStack> context) throws Exception; }
    private AdventCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root=literal("advent").executes(c->run(c,x->help()));
        root.then(literal("status").executes(c->run(c,AdventCommands::status)));
        root.then(literal("civilizations").executes(c->run(c,AdventCommands::civilizations)));
        root.then(literal("me").executes(c->run(c,AdventCommands::profile)));
        root.then(literal("city").executes(c->run(c,AdventCommands::city)));
        root.then(literal("atlas").executes(c->run(c,cx->{runtime(cx).atlasMap().give(player(cx));return "手持演化图观看：蓝色水域、绿色植被、灰色山地、金色城邦、红色废墟；下方金条显示地质阶段。/advent genesis 查看投放进度。";})));
        root.then(literal("genesis").executes(c->run(c,cx->runtime(cx).genesisStatus())));
        root.then(literal("geography").executes(c->run(c,AdventCommands::geography)));
        root.then(literal("join").then(argument("civilization",LongArgumentType.longArg(1))
                .then(argument("origin",StringArgumentType.word()).suggests((c,b)->SharedSuggestionProvider.suggest(List.of("born","summoned","transmigrated"),b))
                        .executes(c->run(c,AdventCommands::join)))));
        root.then(literal("meditate").executes(c->run(c,AdventCommands::meditate)));
        root.then(literal("observe").then(argument("element",StringArgumentType.word()).suggests((c,b)->SharedSuggestionProvider.suggest(Arrays.stream(Element.values()).filter(e->e.tier()==Element.Tier.BASE).map(e->e.name().toLowerCase(Locale.ROOT)),b))
                .executes(c->run(c,AdventCommands::observe))));
        root.then(literal("design").then(argument("name",StringArgumentType.word()).then(argument("effect",StringArgumentType.word())
                .suggests((c,b)->SharedSuggestionProvider.suggest(List.of("release","shield","heal","purify"),b))
                .then(argument("capacity",DoubleArgumentType.doubleArg(1,300)).then(argument("runes",StringArgumentType.greedyString()).executes(c->run(c,AdventCommands::design)))))));
        root.then(literal("spells").executes(c->run(c,x->runtime(x).simulation().requirePlayer(player(x).getUUID()).spells().keySet().toString())));
        root.then(literal("cast").then(argument("name",StringArgumentType.word()).executes(c->run(c,AdventCommands::cast))));
        root.then(literal("requests").executes(c->run(c,AdventCommands::requests)));
        root.then(literal("accept").then(argument("quest",LongArgumentType.longArg(1)).executes(c->run(c,cx->{
            runtime(cx).simulation().claim(player(cx).getUUID(),LongArgumentType.getLong(cx,"quest"),localCity(cx).id());return "已接受委托；/advent deliver <编号> 交付物资。";
        }))));
        root.then(literal("deliver").then(argument("quest",LongArgumentType.longArg(1)).executes(c->run(c,AdventCommands::deliver))));
        root.then(literal("banner").then(argument("enabled",BoolArgumentType.bool()).executes(c->run(c,cx->{
            runtime(cx).simulation().requirePlayer(player(cx).getUUID()).banner(BoolArgumentType.getBool(cx,"enabled"));return "辖区提示已更新。";
        }))));
        root.then(literal("history").executes(c->run(c,AdventCommands::history)));
        root.then(literal("pause").requires(AdventCommands::operator).then(argument("paused",BoolArgumentType.bool()).executes(c->run(c,cx->{
            runtime(cx).paused(BoolArgumentType.getBool(cx,"paused"));return "模拟暂停="+runtime(cx).paused();
        }))));
        root.then(literal("simulate").requires(AdventCommands::operator).then(argument("days",IntegerArgumentType.integer(1,24)).executes(c->run(c,cx->{
            runtime(cx).requestDays(IntegerArgumentType.getInteger(cx,"days"));return "演化已加入分帧队列；/advent status 查看进度。";
        }))));
        root.then(literal("save").requires(AdventCommands::operator).executes(c->run(c,cx->{runtime(cx).save();return runtime(cx).paused()?"请检查日志确认存档结果。":"模拟存档已保存。";})));
        root.then(literal("selftest").requires(AdventCommands::operator).executes(c->run(c,AdventCommands::selftest)));
        dispatcher.register(root);
    }
    private static boolean operator(CommandSourceStack source) { return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER); }
    private static ServerRuntime runtime(CommandContext<CommandSourceStack> c) { return ServerRuntime.get(c.getSource().getServer()); }
    private static ServerPlayer player(CommandContext<CommandSourceStack> c) throws CommandSyntaxException { return c.getSource().getPlayerOrException(); }
    private static City localCity(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var p=player(c);
        if(p.level().dimension()!=Level.OVERWORLD)throw new IllegalStateException("请返回主世界的城邦");
        return runtime(c).world().nearest(p.getX(),p.getZ(),96).orElseThrow(()->new IllegalStateException("附近没有存续城邦"));
    }
    private static int run(CommandContext<CommandSourceStack> c,Action action) {
        try { String message=action.run(c); c.getSource().sendSuccess(()->Component.literal("[冒险人] "+message),false);return 1; }
        catch(CommandSyntaxException e){c.getSource().sendFailure(Component.literal(e.getRawMessage().getString()));return 0;}
        catch(IllegalStateException|IllegalArgumentException e){c.getSource().sendFailure(Component.literal(e.getMessage()==null?"参数无效":e.getMessage()));return 0;}
        catch(Exception e){com.mojang.logging.LogUtils.getLogger().error("Adventurers command failed",e);c.getSource().sendFailure(Component.literal("操作失败，请查看服务器日志；现有存档已保留。"));return 0;}
    }
    public static String help() {
        return "\n/advent civilizations → /advent join <文明编号> born|summoned|transmigrated\n"
                +"/advent me、city、history：身份、当地需求、历史\n/advent requests → accept <编号> → deliver <编号>\n"
                +"/advent meditate、observe <元素英文名>\n/advent design <名称> release|shield|heal|purify <载能> <符文序列>\n"
                +"示例：/advent design spark release 30 fire:sun:magic:0\n/advent cast spark；/advent spells 查看法术。\n"
                +"星球世界：/advent atlas 领取演化图；/advent geography 查看地势、气候与星球大小。";
    }
    private static String geography(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var p=player(c);var world=runtime(c).world();
        if(p.level().dimension()!=Level.OVERWORLD)throw new IllegalStateException("请返回星球主世界");
        var terrain=world.terrain().orElseThrow(()->new IllegalStateException("当前为原版地形；新建世界时选择冒险人星球（小/中/大）"));
        var column=terrain.column(p.getX(),p.getZ());var settings=terrain.settings();
        return String.format(Locale.ROOT,"星球：东西周长 %d 格，南北极间距 %d 格；区域 %d\n地势 Y=%d，水位 Y=%d，生物群系=%s，温度=%.1f℃，湿度=%.0f%%，矿化=%.0f%%，魔力=%.2f\n东西跨界回到另一侧；过极点反射纬度并转动经度。",settings.circumference(),settings.poleDistance(),PlanetCoordinates.region(p.getX(),p.getZ(),settings),column.ground(),column.water(),column.biome(),column.temperature(),column.moisture()*100,column.ore()*100,column.mana());
    }
    private static String status(CommandContext<CommandSourceStack> c) {
        var runtime=runtime(c);var w=runtime.world();
        return String.format(Locale.ROOT,"阶段=%s，第%d年 第%d日；文明=%d；人口=%d；暂停=%s",w.phase(),WorldTime.year(w.tick())+1,WorldTime.day(w.tick())%24+1,w.civilizations().size(),w.population(),runtime.paused());
    }
    private static String civilizations(CommandContext<CommandSourceStack> c) {
        var result=new StringBuilder("出身文明：\n");
        for(var civ:runtime(c).world().civilizations()) {
            var capital=runtime(c).world().city(civ.capital()).orElseThrow();
            if(capital.population()>0)result.append(civ.id()).append(" · ").append(civ.name()).append(" · ").append(capital.era()).append("\n");
        }
        return result.append("使用 /advent join <文明编号> <出身>；不能选择具体落点。").toString();
    }
    private static String profile(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var p=runtime(c).simulation().requirePlayer(player(c).getUUID());
        return String.format(Locale.ROOT,"出身=%s，第%d世，城邦=%d，声望=%.1f，魔力=%.1f/%.1f，意识海=%.1f；已认识元素=%s",p.origin(),p.incarnation()+1,p.city(),p.reputation(),p.mana(),p.mentalCapacity(),p.consciousness(),p.knowledge().stream().map(Element::label).sorted().toList());
    }
    private static String city(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var city=localCity(c);
        return String.format(Locale.ROOT,"%s：人口=%d，粮食=%.1f天，住房=%d，时代=%s，魔法=%.2f，疫病=%.1f%%，稳定=%.2f\n权力中心：%s",city.name(),city.population(),city.foodDays(),city.housing(),city.era(),city.magic(),city.disease()*100,city.fortune().stability(),city.powers());
    }
    private static String join(CommandContext<CommandSourceStack> c) throws Exception {
        var r=runtime(c);var p=player(c);
        if(r.world().phase()!=WorldModel.Phase.PLAYING)throw new IllegalStateException("世界仍在演化，请稍候");
        if(r.world().player(p.getUUID()).isPresent())throw new IllegalStateException("已有身份，无法重复投放");
        long civilization=LongArgumentType.getLong(c,"civilization");
        var civ=r.world().civilizations().stream().filter(x->x.id()==civilization).findFirst().orElseThrow(()->new IllegalArgumentException("文明不存在"));
        var origin=PlayerProfile.Origin.valueOf(StringArgumentType.getString(c,"origin").toUpperCase(Locale.ROOT));
        var available=civ.cities().stream().map(r.world()::city).flatMap(Optional::stream).filter(x->x.population()>0).toList();
        if(available.isEmpty())throw new IllegalStateException("该文明已灭绝");
        var city=available.get(Math.floorMod(p.getUUID().hashCode(),available.size()));
        r.projection().placePlayer(p,city); // Validate a safe location before issuing identity/items.
        r.simulation().join(p.getUUID(),city.id(),origin);
        p.addItem(new ItemStack(ModItems.CHRONICLE.get()));
        if(origin==PlayerProfile.Origin.BORN)p.addItem(new ItemStack(Items.BREAD,4));
        else if(origin==PlayerProfile.Origin.TRANSMIGRATED)p.addItem(new ItemStack(Items.COMPASS));
        r.save();return "已加入 "+civ.name()+"。右键手记查看指引，右键居民交谈。";
    }
    private static String meditate(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var city=localCity(c);var r=runtime(c);var p=player(c);
        if(p.isSprinting()||p.isInWater())throw new IllegalStateException("请先在干燥处停下来冥想");
        r.simulation().requirePlayer(p.getUUID()).meditate(r.world().tick(),r.world().planet().region(city.region()).view().mana());
        p.level().sendParticles(ParticleTypes.ENCHANT,p.getX(),p.getY()+1,p.getZ(),16,.6,.8,.6,.02);
        return "感知 → 塑想 → 构建 → 捕捉 → 明晰。魔力恢复，精神海得到拓展。";
    }
    private static String observe(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var p=player(c);var element=Element.valueOf(StringArgumentType.getString(c,"element").toUpperCase(Locale.ROOT));
        var profile=runtime(c).simulation().requirePlayer(p.getUUID());
        if(element.tier()!=Element.Tier.BASE && element!=Element.SPACE)throw new IllegalArgumentException("复合与神性元素需要进一步研究，无法直接观察学习");
        boolean found=element==Element.SPACE||element==Element.WIND&&p.level().canSeeSky(p.blockPosition())
                ||element==Element.LIGHT&&p.level().getMaxLocalRawBrightness(p.blockPosition())>=12
                ||element==Element.DARK&&p.level().getMaxLocalRawBrightness(p.blockPosition())<=3
                ||element==Element.ELECTRICITY&&p.level().isThundering();
        if(!found)for(var pos:BlockPos.betweenClosed(p.blockPosition().offset(-4,-2,-4),p.blockPosition().offset(4,2,4))) {
            var block=p.level().getBlockState(pos);
            found=switch(element) {
                case FIRE->block.is(Blocks.FIRE)||block.is(Blocks.LAVA)||block.is(Blocks.CAMPFIRE);
                case WATER->block.is(Blocks.WATER);
                case EARTH->block.is(Blocks.DIRT)||block.is(Blocks.STONE);
                case GRASS->block.is(Blocks.SHORT_GRASS)||block.is(Blocks.TALL_GRASS);
                default->false;
            };
            if(found)break;
        }
        if(!found)throw new IllegalStateException("附近没有可感知的"+element.label()+"元素来源");
        profile.learn(element);return "已认识"+element.label()+"元素符文。";
    }
    private static String design(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var p=player(c);var profile=runtime(c).simulation().requirePlayer(p.getUUID());
        double capacity=DoubleArgumentType.getDouble(c,"capacity");
        if(capacity>profile.mentalCapacity())throw new IllegalArgumentException("载能不能超过精神海容量");
        String sequence=StringArgumentType.getString(c,"runes");
        if(sequence.length()>4096)throw new IllegalArgumentException("符文序列过长");
        var runes=new ArrayList<Spell.Rune>();
        for(String token:sequence.split(",")) {
            String[] parts=token.trim().split(":");
            if(parts.length!=4)throw new IllegalArgumentException("符文格式：element:sun|moon|chaos:magic|empty|reversed:layer；多个用逗号连接");
            runes.add(new Spell.Rune(Element.valueOf(parts[0].toUpperCase(Locale.ROOT)),Element.Polarity.valueOf(parts[1].toUpperCase(Locale.ROOT)),Spell.Kind.valueOf(parts[2].toUpperCase(Locale.ROOT)),Integer.parseInt(parts[3])));
        }
        var spell=new Spell(StringArgumentType.getString(c,"name"),Spell.Effect.valueOf(StringArgumentType.getString(c,"effect").toUpperCase(Locale.ROOT)),runes,new Spell.Circle(true,true,true,false,capacity,.85,3,1));
        var result=runtime(c).simulation().design(p.getUUID(),spell);
        if(!result.valid())throw new IllegalArgumentException(String.join("；",result.errors()));
        return String.format(Locale.ROOT,"法术已记忆：%s，能耗=%.1f，强度=%.1f，稳定=%.2f",spell.name(),result.cost(),result.strength(),result.stability());
    }
    private static String cast(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var p=player(c);var r=runtime(c);String name=StringArgumentType.getString(c,"name");
        var result=r.simulation().cast(p.getUUID(),name);
        var spell=r.simulation().requirePlayer(p.getUUID()).spells().get(name);
        switch(spell.effect()) {
            case HEAL->p.heal((float)Math.min(8,result.strength()));
            case SHIELD->p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE,200,0));
            case PURIFY->{p.removeEffect(MobEffects.POISON);p.removeEffect(MobEffects.WITHER);}
            case RELEASE->{
                Vec3 start=p.getEyePosition(),end=start.add(p.getLookAngle().scale(16));
                var block=p.level().clip(new ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));
                end=block.getLocation();
                var hit=ProjectileUtil.getEntityHitResult(p,start,end,p.getBoundingBox().expandTowards(end.subtract(start)).inflate(1),e->e instanceof LivingEntity&&e.isAlive()&&e.isPickable(),start.distanceToSqr(end));
                if(hit!=null && hit.getEntity() instanceof LivingEntity target && (!(target instanceof ServerPlayer other)||p.canHarmPlayer(other)))
                    target.hurtServer(p.level(),p.damageSources().indirectMagic(p,p),(float)Math.min(12,result.strength()));
                for(int i=1;i<=16;i++){var at=start.lerp(end,i/16.0);p.level().sendParticles(ParticleTypes.ENCHANT,at.x,at.y,at.z,2,.05,.05,.05,0);}
            }
        }
        return "已施放 "+name+"，消耗魔力 "+String.format(Locale.ROOT,"%.1f",result.cost());
    }
    private static String requests(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        long city=localCity(c).id();var out=new StringBuilder("当地居民委托：\n");
        for(var q:runtime(c).world().quests())if(q.city()==city&&q.active())out.append(q.id()).append("：").append(q.amount()).append(" × ").append(item(q.resource()).getName(item(q.resource()).getDefaultInstance()).getString()).append("；").append(q.status()).append("；剩余 ").append(Math.max(0,(q.deadline()-runtime(c).world().tick())/24000.0)).append(" 天\n");
        return out.toString();
    }
    public static Item item(Resource resource) {
        return switch(resource){case FOOD->Items.BREAD;case WOOD->Items.OAK_LOG;case STONE->Items.COBBLESTONE;case METAL->Items.IRON_INGOT;case MEDICINE->Items.DANDELION;case MANA->Items.AIR;};
    }
    private static String deliver(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var p=player(c);var r=runtime(c);long id=LongArgumentType.getLong(c,"quest");
        var q=r.world().quest(id).orElseThrow(()->new IllegalArgumentException("委托不存在"));var expected=item(q.resource());
        int count=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(expected))count+=p.getInventory().getItem(i).getCount();
        r.simulation().deliver(p.getUUID(),id,localCity(c).id(),count);
        int remaining=q.amount();for(int i=0;i<p.getInventory().getContainerSize()&&remaining>0;i++){
            var stack=p.getInventory().getItem(i);if(stack.is(expected)){int take=Math.min(remaining,stack.getCount());stack.shrink(take);remaining-=take;}
        }
        p.getInventory().setChanged();r.save();return "物资已交付城邦，声望 +3。";
    }
    private static String history(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        long city=localCity(c).id();var events=runtime(c).world().history().stream().filter(e->e.subject().equals(Long.toString(city))).toList();
        return String.join("\n",events.stream().skip(Math.max(0,events.size()-8)).map(e->"第"+WorldTime.day(e.tick())+"日："+e.detail()).toList());
    }
    private static String selftest(CommandContext<CommandSourceStack> c) throws Exception {
        byte[] encoded=WorldCodec.encode(runtime(c).world());
        var clone=new Simulation(WorldCodec.decode(encoded));
        clone.advanceDays(1);
        if(clone.world().tick()!=runtime(c).world().tick()+24000)throw new IllegalStateException("模拟时钟验证失败");
        var result=new SpellCompiler().compile(Spell.simple("test",Spell.Effect.RELEASE,Element.FIRE),Set.of(Element.FIRE),Laws.overworld());
        if(!result.valid())throw new IllegalStateException("符文验证失败");
        return "SELFTEST PASS：真实服务器命令、世界模拟、存档校验与符文编译正常。测试未推进实际世界。";
    }
}
