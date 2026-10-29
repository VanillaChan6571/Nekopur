package org.nekopur.gameplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import org.jspecify.annotations.NullMarked;

/** Crouching armor regeneration with item-local, persistent exhaustion accounting. */
@NullMarked
public final class ArmorMendingRules {
    static final String STATE_KEY = "nekopur:armor_mending";

    private ArmorMendingRules() {
    }

    public static boolean isArmor(ItemStack item) {
        net.minecraft.world.item.equipment.Equippable equippable = item.get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.slot().isArmor();
    }

    /** Keep Purpur's most-damaged preference while excluding armor even when held. */
    public static Optional<EnchantedItemInUse> selectOrbItem(ServerPlayer player, boolean mostDamaged) {
        if (!mostDamaged) {
            return EnchantmentHelper.getRandomItemWith(EnchantmentEffectComponents.REPAIR_WITH_XP, player,
                item -> item.isDamaged() && !isArmor(item));
        }
        List<EnchantedItemInUse> eligible = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack item = player.getItemBySlot(slot);
            if (!item.isDamaged() || isArmor(item)) {
                continue;
            }
            boolean repairsWithXp = EnchantmentHelper.getEnchantmentsForCrafting(item).entrySet().stream()
                .anyMatch(entry -> entry.getKey().value().matchingSlot(slot)
                    && entry.getKey().value().effects().has(EnchantmentEffectComponents.REPAIR_WITH_XP));
            if (repairsWithXp) {
                eligible.add(new EnchantedItemInUse(item, slot, player));
            }
        }
        return eligible.stream().max(Comparator.comparingDouble(item -> item.itemStack().getDamagePercent()));
    }

    /** Called once each second of online gameplay; unloaded items accumulate no repair or age. */
    public static void tick(ServerPlayer player) {
        if (!GameplayConfig.enabled || !GameplayConfig.armorMendingEnabled || !player.isAlive() || player.isSpectator()) {
            return;
        }
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
                continue;
            }
            ItemStack item = player.getItemBySlot(slot);
            if (!item.isDamageableItem()) {
                continue;
            }
            int rank = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.MENDING, item);
            if (rank <= 0) {
                continue;
            }
            CompoundTag state = state(item);
            if (!item.isDamaged()) {
                if (state.getLongOr("damaged_seconds", 0) != 0 || state.getDoubleOr("credit", 0) != 0) {
                    state.putLong("damaged_seconds", 0);
                    state.putDouble("credit", 0);
                    save(item, state);
                }
                continue;
            }
            CompoundTag previousState = state.copy();
            long ageCap = (long) GameplayConfig.armorMendingDelaySeconds + Math.max(1, GameplayConfig.armorMendingRampSeconds);
            long age = Math.min(ageCap, Math.min(ageCap, Math.max(0, state.getLongOr("damaged_seconds", 0))) + 1);
            state.putLong("damaged_seconds", age);
            if (player.isShiftKeyDown()) {
                double credit = Math.max(0, state.getDoubleOr("credit", 0));
                if (!Double.isFinite(credit)) {
                    credit = 0;
                }
                credit += repairRate(age, GameplayConfig.armorMendingDelaySeconds,
                    GameplayConfig.armorMendingRampSeconds, GameplayConfig.armorMendingDurabilityPerSecond);
                long repaired = Math.max(0, state.getLongOr("repaired", 0));
                int repair = repairBudget(item.getMaxDamage(), item.getDamageValue(), rank, repaired,
                    GameplayConfig.armorMendingLifetimeMultiplier, credit);
                // XpTotal is historical and can disagree with the current bar after
                // enchanting or commands; use Paper's level/progress calculation.
                int xpCost = Math.max(1, GameplayConfig.armorMendingXpPerDurability);
                if (repair > 0) {
                    int available = Math.max(0, player.getBukkitEntity().calculateTotalExperiencePoints());
                    repair = Math.min(repair, available / xpCost);
                }
                if (repair > 0) {
                    player.giveExperiencePoints(-repair * xpCost);
                    item.setDamageValue(item.getDamageValue() - repair);
                    state.putLong("repaired", repaired + repair);
                }
                // Never bank repairs beyond the next fractional point while capped or exhausted.
                state.putDouble("credit", Math.max(0, credit - Math.floor(credit)));
            }
            if (!state.equals(previousState)) {
                save(item, state);
            }
        }
    }

    static double repairRate(long damagedSeconds, int delaySeconds, int rampSeconds, double fullRate) {
        if (damagedSeconds < delaySeconds || fullRate <= 0 || !Double.isFinite(fullRate)) {
            return 0;
        }
        double progress = rampSeconds <= 0 ? 1 : Math.min(1, Math.max(0, damagedSeconds - delaySeconds) / (double) rampSeconds);
        return fullRate * (0.1 + 0.9 * progress);
    }

    static int repairBudget(int maxDurability, int damage, int rank, long repaired, double lifetimeMultiplier, double credit) {
        if (maxDurability <= 0 || damage <= 0 || rank <= 0 || !Double.isFinite(credit) || credit < 1
            || !Double.isFinite(lifetimeMultiplier) || lifetimeMultiplier <= 0) {
            return 0;
        }
        int remaining = Math.max(0, maxDurability - damage);
        int ceiling = (int) Math.floor(maxDurability * (0.2 + 0.1 * Math.min(3, rank)) + 1.0e-9);
        long allowance = Math.max(0, (long) Math.floor(maxDurability * lifetimeMultiplier) - Math.max(0, repaired));
        return (int) Math.max(0, Math.min(Math.min(ceiling - remaining, allowance), Math.floor(credit)));
    }

    public static void resetAfterAnvilRepair(ItemStack original, ItemStack addition, ItemStack output) {
        if (!GameplayConfig.enabled || !isArmor(original) || addition.isEmpty() || output.isEmpty()
            || !ItemStack.isSameItem(original, output) || !output.isDamageableItem()
            || output.getDamageValue() >= original.getDamageValue()
            || !(original.isValidRepairItem(addition) || ItemStack.isSameItem(original, addition))) {
            return;
        }
        CompoundTag state = state(output);
        state.putLong("repaired", 0);
        state.putLong("damaged_seconds", 0);
        state.putDouble("credit", 0);
        save(output, state);
    }

    static CompoundTag state(ItemStack item) {
        CompoundTag state = item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompoundOrEmpty(STATE_KEY);
        if (state.getStringOr("uuid", "").isEmpty()) {
            state.putString("uuid", UUID.randomUUID().toString());
        }
        return state;
    }

    static void save(ItemStack item, CompoundTag state) {
        CustomData.update(DataComponents.CUSTOM_DATA, item, tag -> tag.put(STATE_KEY, state));
    }
}
