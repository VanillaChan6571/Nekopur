package org.nekopur.gameplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@VanillaFeature
@NullMarked
public class EnchantmentMechanicsTest {
    @BeforeEach
    @AfterEach
    void resetRules() {
        GameplayConfig.load(new YamlConfiguration());
    }

    @Test
    void spawnerChanceHasExactBoundaries() {
        assertFalse(EnchantmentMechanics.dropsSpawner(1, 0.0F));
        assertTrue(EnchantmentMechanics.dropsSpawner(2, 0.49999F));
        assertFalse(EnchantmentMechanics.dropsSpawner(2, 0.5F));
        assertTrue(EnchantmentMechanics.dropsSpawner(3, 0.99999F));
    }

    @Test
    void aquaRanksNormalizeVanillaAttributeScalingAndRetainOtherModifiers() {
        assertEquals(0.2F, EnchantmentMechanics.submergedMiningSpeed(0.2F, 0), 0.00001F);
        assertEquals(1.0F / 3.5F, EnchantmentMechanics.submergedMiningSpeed(1.0F, 1), 0.00001F);
        assertEquals(0.5F, EnchantmentMechanics.submergedMiningSpeed(1.8F, 2), 0.00001F);
        assertEquals(1.0F, EnchantmentMechanics.submergedMiningSpeed(2.6F, 3), 0.00001F);
        assertEquals(2.0F, EnchantmentMechanics.submergedMiningSpeed(5.2F, 3), 0.00001F);
    }

    @Test
    void synchronizedAquaCorrectionPreservesOtherModifiersAndCanBeRemoved() {
        net.minecraft.world.entity.ai.attributes.AttributeInstance attribute =
            new net.minecraft.world.entity.ai.attributes.AttributeInstance(
                net.minecraft.world.entity.ai.attributes.Attributes.SUBMERGED_MINING_SPEED, ignored -> { });
        attribute.setBaseValue(0.2D);
        net.minecraft.resources.Identifier vanilla = net.minecraft.resources.Identifier.withDefaultNamespace("enchantment.aqua_affinity");
        net.minecraft.resources.Identifier other = net.minecraft.resources.Identifier.fromNamespaceAndPath("test", "other");
        attribute.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(other, 1.0D,
            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        for (int rank = 1; rank <= 3; rank++) {
            attribute.removeModifier(vanilla);
            attribute.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(vanilla, 4.0D * rank,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            EnchantmentMechanics.updateAquaAttribute(attribute, rank);
            assertEquals(rank == 1 ? 2.0D / 3.5D : rank == 2 ? 1.0D : 2.0D, attribute.getValue(), 0.00001D);
            int modifierCount = attribute.getModifiers().size();
            EnchantmentMechanics.updateAquaAttribute(attribute, rank);
            assertEquals(modifierCount, attribute.getModifiers().size());
        }
        EnchantmentMechanics.updateAquaAttribute(attribute, 0);
        assertEquals(5.2D, attribute.getValue(), 0.00001D);
    }
    @Test
    void protectionAllowsOnlyOneSecondaryAndDisabledModeRestoresConflicts() {
        net.minecraft.core.HolderLookup.RegistryLookup<Enchantment> registry = RegistryHelper.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> protection = registry.getOrThrow(Enchantments.PROTECTION);
        Holder<Enchantment> blast = registry.getOrThrow(Enchantments.BLAST_PROTECTION);
        Holder<Enchantment> fire = registry.getOrThrow(Enchantments.FIRE_PROTECTION);
        Holder<Enchantment> projectile = registry.getOrThrow(Enchantments.PROJECTILE_PROTECTION);
        assertTrue(Enchantment.areCompatible(protection, blast));
        assertTrue(Enchantment.areCompatible(fire, protection));
        assertTrue(Enchantment.areCompatible(protection, projectile));
        assertFalse(Enchantment.areCompatible(blast, fire));
        assertFalse(Enchantment.areCompatible(blast, projectile));
        assertFalse(Enchantment.areCompatible(fire, projectile));
        assertFalse(Enchantment.areCompatible(protection, protection));
        YamlConfiguration config = new YamlConfiguration();
        config.set(GameplayConfig.ROOT + ".enabled", false);
        GameplayConfig.load(config);
        assertFalse(Enchantment.areCompatible(protection, blast));
    }
}
