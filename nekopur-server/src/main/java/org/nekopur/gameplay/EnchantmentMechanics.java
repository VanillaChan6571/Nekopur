package org.nekopur.gameplay;

import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.jspecify.annotations.NullMarked;

/** Rank-sensitive mechanics shared by enchanting and normal gameplay. */
@NullMarked
public final class EnchantmentMechanics {
    public static final String SPAWNER_MARKER = "Nekopur.SilkSpawner";

    private static final net.minecraft.resources.Identifier AQUA_CORRECTION =
        net.minecraft.resources.Identifier.fromNamespaceAndPath("nekopur", "aqua_affinity_rank");

    private EnchantmentMechanics() {
    }

    public static boolean protectionPair(Holder<Enchantment> first, Holder<Enchantment> second) {
        return (first.is(Enchantments.PROTECTION) && secondaryProtection(second))
            || (second.is(Enchantments.PROTECTION) && secondaryProtection(first));
    }

    private static boolean secondaryProtection(Holder<Enchantment> enchantment) {
        return enchantment.is(Enchantments.FIRE_PROTECTION) || enchantment.is(Enchantments.BLAST_PROTECTION)
            || enchantment.is(Enchantments.PROJECTILE_PROTECTION);
    }

    public static boolean dropsSpawner(int silkRank, float roll) {
        return silkRank >= 3 || silkRank == 2 && roll < 0.5F;
    }

    /** Keep the actual attribute synchronized so vanilla clients predict the same mining speed. */
    public static void tick(net.minecraft.world.entity.player.Player player) {
        int rank = GameplayConfig.enabled
            ? net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(Enchantments.AQUA_AFFINITY,
                player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)) : 0;
        updateAquaAttribute(player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SUBMERGED_MINING_SPEED), rank);
    }

    static void updateAquaAttribute(net.minecraft.world.entity.ai.attributes.@org.jspecify.annotations.Nullable AttributeInstance attribute, int rank) {
        if (attribute == null) {
            return;
        }
        net.minecraft.world.entity.ai.attributes.AttributeModifier current = attribute.getModifier(AQUA_CORRECTION);
        if (rank <= 0) {
            if (current != null) {
                attribute.removeModifier(AQUA_CORRECTION);
            }
            return;
        }
        double correction = submergedMiningSpeed(1.0F, rank) - 1.0D;
        if (current == null || current.amount() != correction) {
            attribute.removeModifier(AQUA_CORRECTION);
            attribute.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(AQUA_CORRECTION,
                correction, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    /** Remove vanilla's rank-scaled Aqua Affinity factor before applying the survival curve. */
    public static float submergedMiningSpeed(float attributeValue, int aquaRank) {
        if (aquaRank <= 0) {
            return attributeValue;
        }
        float slowdown = aquaRank == 1 ? 3.5F : aquaRank == 2 ? 2.0F : 1.0F;
        return attributeValue * 5.0F / (1.0F + 4.0F * aquaRank) / slowdown;
    }
}
