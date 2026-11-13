package com.clickhostiles;

import java.util.List;
import java.util.Set;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;
import org.jetbrains.annotations.Nullable;

public class ClickHostiles implements ModInitializer {
    public static final String MOD_ID = "clickhostiles";
    private static final String KEY = "clickhostiles_mob";

    /** Mobs that can never be picked up. */
    private static final Set<EntityType<?>> BLOCKED =
            Set.of(EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.WARDEN);

    @Override
    public void onInitialize() {
        UseEntityCallback.EVENT.register(ClickHostiles::onUseEntity);
        UseBlockCallback.EVENT.register(ClickHostiles::onUseBlock);
        UseItemCallback.EVENT.register(ClickHostiles::onUseItem);
    }

    // ---- Pick up: sneak + right-click a hostile mob with an empty hand ----
    private static ActionResult onUseEntity(PlayerEntity player, World world, Hand hand,
                                            Entity entity, @Nullable EntityHitResult hit) {
        if (hand != Hand.MAIN_HAND || !player.isSneaking() || player.isSpectator()) return ActionResult.PASS;
        if (!player.getMainHandStack().isEmpty()) return ActionResult.PASS;
        if (!(entity instanceof Monster) || BLOCKED.contains(entity.getType())) return ActionResult.PASS;

        SpawnEggItem egg = SpawnEggItem.forEntity(entity.getType());
        if (egg == null) return ActionResult.PASS;
        if (world.isClient) return ActionResult.SUCCESS;

        NbtCompound nbt = new NbtCompound();
        if (!entity.saveNbt(nbt)) {
            player.sendMessage(Text.literal("Can't pick this mob up right now."), true);
            return ActionResult.FAIL;
        }
        nbt.remove("UUID");

        ItemStack stack = new ItemStack(egg);
        NbtCompound root = new NbtCompound();
        root.put(KEY, nbt);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
        stack.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        stack.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal("Captured ").append(entity.getName()).styled(s -> s.withItalic(false)));
        if (entity instanceof LivingEntity living) {
            stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    Text.literal("Health: " + Math.round(living.getHealth()) + " / "
                                    + Math.round(living.getMaxHealth()))
                            .styled(s -> s.withItalic(false).withColor(Formatting.GRAY)))));
        }

        ServerWorld sw = (ServerWorld) world;
        sw.spawnParticles(ParticleTypes.POOF, entity.getX(), entity.getBodyY(0.5), entity.getZ(),
                12, 0.3, 0.3, 0.3, 0.02);
        sw.playSound(null, entity.getBlockPos(), SoundEvents.ENTITY_ITEM_PICKUP,
                SoundCategory.PLAYERS, 0.8f, 1.0f);

        entity.discard();
        if (!player.getInventory().insertStack(stack)) {
            player.dropItem(stack, false);
        }
        return ActionResult.SUCCESS;
    }

    // ---- Place: right-click a block with a captured mob item ----
    private static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        ItemStack stack = player.getStackInHand(hand);
        NbtCompound data = getMobData(stack);
        if (data == null || player.isSpectator()) return ActionResult.PASS;
        if (world.isClient) return ActionResult.SUCCESS;

        BlockPos clicked = hit.getBlockPos();
        BlockPos target = world.getBlockState(clicked).isReplaceable()
                ? clicked : clicked.offset(hit.getSide());
        if (!world.canPlayerModifyAt(player, target)) return ActionResult.FAIL;

        ServerWorld sw = (ServerWorld) world;
        Vec3d at = Vec3d.ofBottomCenter(target);
        float yaw = player.getYaw() + 180f;
        Entity mob = EntityType.loadEntityWithPassengers(data, sw, e -> {
            e.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0f);
            e.setVelocity(Vec3d.ZERO);
            return e;
        });
        if (mob == null || !sw.spawnNewEntityAndPassengers(mob)) {
            player.sendMessage(Text.literal("Couldn't place the mob here."), true);
            return ActionResult.FAIL;
        }

        sw.emitGameEvent(player, GameEvent.ENTITY_PLACE, target);
        sw.playSound(null, target, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.6f, 1.2f);
        if (!player.isCreative()) stack.decrement(1);
        return ActionResult.SUCCESS;
    }

    // ---- Stop the vanilla egg from spawning a plain mob on water/air clicks ----
    private static TypedActionResult<ItemStack> onUseItem(PlayerEntity player, World world, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (getMobData(stack) != null) return TypedActionResult.fail(stack);
        return TypedActionResult.pass(stack);
    }

    @Nullable
    private static NbtCompound getMobData(ItemStack stack) {
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (comp == null) return null;
        NbtCompound nbt = comp.copyNbt();
        return nbt.contains(KEY, NbtElement.COMPOUND_TYPE) ? nbt.getCompound(KEY) : null;
    }
}
