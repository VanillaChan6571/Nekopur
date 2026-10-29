package org.nekopur.gameplay;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.NullMarked;

/** Darkness-triggered invisibility; the cooldown survives logout and player replacement. */
@NullMarked
public final class VanishingRules {
    private static final NamespacedKey COOLDOWN = new NamespacedKey("nekopur", "vanishing_cooldown_until");

    private VanishingRules() {
    }

    public static int durationSeconds(int rank) {
        return Math.clamp(rank, 0, 3) * 10;
    }

    public static int lightThreshold(int rank) {
        return Math.clamp(rank, 1, 3) * 2 - 1;
    }

    public static boolean canActivate(int rank, int light, long now, long cooldownUntil) {
        return rank > 0 && light < lightThreshold(rank) && now >= cooldownUntil;
    }

    private static int itemRank(ItemStack item) {
        int rank = 0;
        for (it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<Holder<Enchantment>> entry : item.getEnchantments().entrySet()) {
            if (entry.getKey().is(Enchantments.VANISHING_CURSE)) {
                rank = Math.max(rank, entry.getIntValue());
            }
        }
        return rank;
    }

    /** Ignore only Vanishing's death-loss component, preserving other/custom drop-prevention effects. */
    public static boolean ignoreEffect(Holder<Enchantment> enchantment, DataComponentType<?> effect) {
        return GameplayConfig.enabled && GameplayConfig.vanishingEnabled
            && effect == EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP
            && enchantment.is(Enchantments.VANISHING_CURSE);
    }

    public static void tick(ServerPlayer player) {
        if (!GameplayConfig.enabled || !GameplayConfig.vanishingEnabled || !player.isAlive() || player.isSpectator()
            || player.hasEffect(MobEffects.INVISIBILITY)) {
            return;
        }
        int rank = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            rank = Math.max(rank, itemRank(player.getItemBySlot(slot)));
        }
        if (rank == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        org.bukkit.persistence.PersistentDataContainer data = player.getBukkitEntity().getPersistentDataContainer();
        long until = data.getOrDefault(COOLDOWN, PersistentDataType.LONG, 0L);
        if (!canActivate(rank, player.level().getMaxLocalRawBrightness(player.blockPosition()), now, until)) {
            return;
        }
        if (player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, durationSeconds(rank) * 20, 0, false, false),
            org.bukkit.event.entity.EntityPotionEffectEvent.Cause.UNKNOWN)) {
            data.set(COOLDOWN, PersistentDataType.LONG, now + GameplayConfig.vanishingCooldownSeconds * 1000L);
        }
    }
}
