package org.nekopur.gameplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.Enchantments;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@VanillaFeature
@NullMarked
class ArmorMendingRulesTest {
    @Test
    void repairDrainsRealLevelAndProgressEvenWhenHistoricalXpTotalIsStale() {
        int oldRamp = GameplayConfig.armorMendingRampSeconds;
        try {
            GameplayConfig.armorMendingRampSeconds = 0;
            ServerPlayer player = mock(ServerPlayer.class);
            doReturn(true).when(player).isAlive();
            doReturn(true).when(player).isShiftKeyDown();
            player.experienceLevel = 1;
            player.experienceProgress = 0;
            player.totalExperience = 10000;
            org.mockito.Mockito.doCallRealMethod().when(player).getXpNeededForNextLevel();
            org.mockito.Mockito.doCallRealMethod().when(player).giveExperiencePoints(org.mockito.ArgumentMatchers.anyInt());
            org.mockito.Mockito.doCallRealMethod().when(player).giveExperienceLevels(org.mockito.ArgumentMatchers.anyInt());
            var bukkit = mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
            doReturn(bukkit).when(player).getBukkitEntity();
            doReturn(player).when(bukkit).getHandle();
            org.mockito.Mockito.doCallRealMethod().when(bukkit).getLevel();
            org.mockito.Mockito.doCallRealMethod().when(bukkit).getExp();
            org.mockito.Mockito.doCallRealMethod().when(bukkit).getExperiencePointsNeededForNextLevel();
            org.mockito.Mockito.doCallRealMethod().when(bukkit).calculateTotalExperiencePoints();
            for (EquipmentSlot slot : EquipmentSlot.VALUES) doReturn(ItemStack.EMPTY).when(player).getItemBySlot(slot);
            ItemStack chest = new ItemStack(Items.NETHERITE_CHESTPLATE);
            chest.setDamageValue(500);
            doReturn(chest).when(player).getItemBySlot(EquipmentSlot.CHEST);
            try (org.mockito.MockedStatic<EnchantmentHelper> enchantments = mockStatic(EnchantmentHelper.class)) {
                enchantments.when(() -> EnchantmentHelper.getItemEnchantmentLevel(Enchantments.MENDING, chest)).thenReturn(3);
                assertEquals(7, bukkit.calculateTotalExperiencePoints());
                for (int i = 0; i < 7; i++) ArmorMendingRules.tick(player);
                assertEquals(493, chest.getDamageValue());
                assertEquals(0, player.experienceLevel);
                assertEquals(0, bukkit.calculateTotalExperiencePoints());
                assertTrue(player.totalExperience > 0, "Historical XP is not the spendable balance");
                ArmorMendingRules.tick(player);
                assertEquals(493, chest.getDamageValue(), "Repairs stop once the actual bar is empty");
            }
        } finally {
            GameplayConfig.armorMendingRampSeconds = oldRamp;
        }
    }

    @Test
    void orbSelectionPredicateExcludesHeldArmorAndElytraButKeepsDamagedTools() {
        ServerPlayer player = mock(ServerPlayer.class);
        ItemStack armor = new ItemStack(Items.NETHERITE_CHESTPLATE);
        ItemStack elytra = new ItemStack(Items.ELYTRA);
        ItemStack tool = new ItemStack(Items.NETHERITE_PICKAXE);
        armor.setDamageValue(50);
        elytra.setDamageValue(50);
        tool.setDamageValue(50);
        try (org.mockito.MockedStatic<EnchantmentHelper> enchantments = mockStatic(EnchantmentHelper.class)) {
            enchantments.when(() -> EnchantmentHelper.getRandomItemWith(eq(EnchantmentEffectComponents.REPAIR_WITH_XP), eq(player), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Predicate<ItemStack> predicate = invocation.getArgument(2);
                    assertFalse(predicate.test(armor));
                    assertFalse(predicate.test(elytra));
                    assertTrue(predicate.test(tool));
                    tool.setDamageValue(0);
                    assertFalse(predicate.test(tool));
                    return java.util.Optional.empty();
                });
            assertTrue(ArmorMendingRules.selectOrbItem(player, false).isEmpty());
            enchantments.verify(() -> EnchantmentHelper.getRandomItemWith(eq(EnchantmentEffectComponents.REPAIR_WITH_XP), eq(player), any()));
        }
    }

    @Test
    void standingPausesRepairsAndCrouchingResumesWithPersistentAccounting() {
        boolean oldEnabled = GameplayConfig.enabled;
        boolean oldArmorEnabled = GameplayConfig.armorMendingEnabled;
        int oldDelay = GameplayConfig.armorMendingDelaySeconds;
        int oldRamp = GameplayConfig.armorMendingRampSeconds;
        double oldRate = GameplayConfig.armorMendingDurabilityPerSecond;
        double oldLifetime = GameplayConfig.armorMendingLifetimeMultiplier;
        try {
            GameplayConfig.enabled = true;
            GameplayConfig.armorMendingEnabled = true;
            GameplayConfig.armorMendingDelaySeconds = 0;
            GameplayConfig.armorMendingRampSeconds = 300;
            GameplayConfig.armorMendingDurabilityPerSecond = 1;
            GameplayConfig.armorMendingLifetimeMultiplier = 2.5;
            ServerPlayer player = mock(ServerPlayer.class);
            doReturn(true).when(player).isAlive();
            var bukkit = mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
            doReturn(bukkit).when(player).getBukkitEntity();
            doReturn(10).when(bukkit).calculateTotalExperiencePoints();
            for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                doReturn(ItemStack.EMPTY).when(player).getItemBySlot(slot);
            }
            ItemStack armor = new ItemStack(Items.NETHERITE_CHESTPLATE);
            armor.setDamageValue(500);
            CompoundTag state = ArmorMendingRules.state(armor);
            state.putLong("damaged_seconds", 300);
            ArmorMendingRules.save(armor, state);
            doReturn(armor).when(player).getItemBySlot(EquipmentSlot.CHEST);
            try (org.mockito.MockedStatic<EnchantmentHelper> enchantments = mockStatic(EnchantmentHelper.class)) {
                enchantments.when(() -> EnchantmentHelper.getItemEnchantmentLevel(Enchantments.MENDING, armor)).thenReturn(3);
                ArmorMendingRules.tick(player);
                assertEquals(500, armor.getDamageValue());
                doReturn(true).when(player).isShiftKeyDown();
                ArmorMendingRules.tick(player);
                assertEquals(499, armor.getDamageValue());
                org.mockito.Mockito.verify(player).giveExperiencePoints(-1);
                assertEquals(1, ArmorMendingRules.state(armor).getLongOr("repaired", 0));
                doReturn(false).when(player).isShiftKeyDown();
                CustomData previousData = armor.get(DataComponents.CUSTOM_DATA);
                ArmorMendingRules.tick(player);
                assertEquals(499, armor.getDamageValue());
                assertEquals(300, ArmorMendingRules.state(armor).getLongOr("damaged_seconds", -1));
                assertSame(previousData, armor.get(DataComponents.CUSTOM_DATA));
            }
        } finally {
            GameplayConfig.enabled = oldEnabled;
            GameplayConfig.armorMendingEnabled = oldArmorEnabled;
            GameplayConfig.armorMendingDelaySeconds = oldDelay;
            GameplayConfig.armorMendingRampSeconds = oldRamp;
            GameplayConfig.armorMendingDurabilityPerSecond = oldRate;
            GameplayConfig.armorMendingLifetimeMultiplier = oldLifetime;
        }
    }

    @Test
    void emptyXpBarCannotRepairAndAvailableXpIsSharedAcrossArmor() {
        int oldRamp = GameplayConfig.armorMendingRampSeconds;
        double oldRate = GameplayConfig.armorMendingDurabilityPerSecond;
        int oldCost = GameplayConfig.armorMendingXpPerDurability;
        try {
            GameplayConfig.armorMendingRampSeconds = 0;
            GameplayConfig.armorMendingDurabilityPerSecond = 10;
            GameplayConfig.armorMendingXpPerDurability = 2;
            ServerPlayer player = mock(ServerPlayer.class);
            doReturn(true).when(player).isAlive();
            doReturn(true).when(player).isShiftKeyDown();
            var bukkit = mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
            doReturn(bukkit).when(player).getBukkitEntity();
            int[] xp = {0};
            org.mockito.Mockito.doAnswer(invocation -> xp[0]).when(bukkit).calculateTotalExperiencePoints();
            org.mockito.Mockito.doAnswer(invocation -> {
                xp[0] += (int) invocation.getArgument(0);
                return null;
            }).when(player).giveExperiencePoints(org.mockito.ArgumentMatchers.anyInt());
            for (EquipmentSlot slot : EquipmentSlot.VALUES) doReturn(ItemStack.EMPTY).when(player).getItemBySlot(slot);
            ItemStack chest = new ItemStack(Items.NETHERITE_CHESTPLATE);
            ItemStack boots = new ItemStack(Items.NETHERITE_BOOTS);
            chest.setDamageValue(chest.getMaxDamage() - 1);
            boots.setDamageValue(boots.getMaxDamage() - 1);
            doReturn(chest).when(player).getItemBySlot(EquipmentSlot.CHEST);
            doReturn(boots).when(player).getItemBySlot(EquipmentSlot.FEET);
            try (org.mockito.MockedStatic<EnchantmentHelper> enchantments = mockStatic(EnchantmentHelper.class)) {
                enchantments.when(() -> EnchantmentHelper.getItemEnchantmentLevel(Enchantments.MENDING, chest)).thenReturn(3);
                enchantments.when(() -> EnchantmentHelper.getItemEnchantmentLevel(Enchantments.MENDING, boots)).thenReturn(3);
                ArmorMendingRules.tick(player);
                assertEquals(1, chest.getMaxDamage() - chest.getDamageValue());
                assertEquals(1, boots.getMaxDamage() - boots.getDamageValue());
                org.mockito.Mockito.verify(player, org.mockito.Mockito.never()).giveExperiencePoints(org.mockito.ArgumentMatchers.anyInt());
                xp[0] = 5;
                ArmorMendingRules.tick(player);
                assertEquals(1, xp[0], "Only two whole repairs are affordable at two XP each");
                assertEquals(4, chest.getMaxDamage() - chest.getDamageValue() + boots.getMaxDamage() - boots.getDamageValue());
                long repaired = ArmorMendingRules.state(chest).getLongOr("repaired", 0) + ArmorMendingRules.state(boots).getLongOr("repaired", 0);
                assertEquals(2, repaired);
                ArmorMendingRules.tick(player);
                assertEquals(1, xp[0]);
                assertEquals(4, chest.getMaxDamage() - chest.getDamageValue() + boots.getMaxDamage() - boots.getDamageValue());
            }
        } finally {
            GameplayConfig.armorMendingRampSeconds = oldRamp;
            GameplayConfig.armorMendingDurabilityPerSecond = oldRate;
            GameplayConfig.armorMendingXpPerDurability = oldCost;
        }
    }

    @Test
    void ranksRestoreOnlyThirtyFortyOrFiftyPercentOfRemainingDurability() {
        assertEquals(177, ArmorMendingRules.repairBudget(592, 592, 1, 0, 2.5, 1000));
        assertEquals(236, ArmorMendingRules.repairBudget(592, 592, 2, 0, 2.5, 1000));
        assertEquals(296, ArmorMendingRules.repairBudget(592, 592, 3, 0, 2.5, 1000));
        assertEquals(0, ArmorMendingRules.repairBudget(592, 295, 3, 0, 2.5, 1000));
        assertEquals(1, ArmorMendingRules.repairBudget(592, 297, 3, 0, 2.5, 1000));
    }

    @Test
    void lifetimeBudgetStopsExactlyAtTwoAndHalfTimesDurability() {
        assertEquals(1, ArmorMendingRules.repairBudget(592, 500, 3, 1479, 2.5, 50));
        assertEquals(0, ArmorMendingRules.repairBudget(592, 500, 3, 1480, 2.5, 50));
        assertEquals(0, ArmorMendingRules.repairBudget(592, 500, 3, 9000, 2.5, 50));
        assertEquals(0, ArmorMendingRules.repairBudget(592, 500, 3, 0, 2.5, 0.9));
    }

    @Test
    void repairStartsSlowlyAndRampsWithoutExceedingConfiguredRate() {
        assertEquals(0.1, ArmorMendingRules.repairRate(0, 0, 300, 1), 1.0e-9);
        assertEquals(0.55, ArmorMendingRules.repairRate(150, 0, 300, 1), 1.0e-9);
        assertEquals(1, ArmorMendingRules.repairRate(300, 0, 300, 1), 1.0e-9);
        assertEquals(1, ArmorMendingRules.repairRate(10000, 0, 300, 1), 1.0e-9);
        assertEquals(0, ArmorMendingRules.repairRate(59, 60, 300, 1), 1.0e-9);
    }

    @Test
    void itemCopyPreservesUuidAndExhaustionWithoutTouchingOtherCustomData() {
        ItemStack armor = new ItemStack(Items.NETHERITE_CHESTPLATE);
        CustomData.update(DataComponents.CUSTOM_DATA, armor, data -> data.putString("other_plugin", "preserved"));
        CompoundTag state = ArmorMendingRules.state(armor);
        state.putLong("repaired", 1480);
        ArmorMendingRules.save(armor, state);
        CompoundTag copied = ArmorMendingRules.state(armor.copy());
        assertEquals(state.getStringOr("uuid", ""), copied.getStringOr("uuid", ""));
        assertEquals(1480, copied.getLongOr("repaired", 0));
        assertEquals("preserved", armor.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr("other_plugin", ""));
        assertTrue(ArmorMendingRules.isArmor(armor));
        assertFalse(ArmorMendingRules.isArmor(new ItemStack(Items.NETHERITE_PICKAXE)));
    }

    @Test
    void onlyActualAnvilMaterialRepairResetsLifetimeCounterAndKeepsIdentity() {
        boolean oldEnabled = GameplayConfig.enabled;
        try {
            GameplayConfig.enabled = true;
            ItemStack original = new ItemStack(Items.NETHERITE_CHESTPLATE);
            original.setDamageValue(400);
            CompoundTag state = ArmorMendingRules.state(original);
            state.putLong("repaired", 1480);
            ArmorMendingRules.save(original, state);
            ItemStack output = original.copy();
            ArmorMendingRules.resetAfterAnvilRepair(original, new ItemStack(Items.NETHERITE_INGOT), output);
            assertEquals(1480, ArmorMendingRules.state(output).getLongOr("repaired", -1));
            output.setDamageValue(250);
            ArmorMendingRules.resetAfterAnvilRepair(original, new ItemStack(Items.ENCHANTED_BOOK), output);
            assertEquals(1480, ArmorMendingRules.state(output).getLongOr("repaired", -1));
            ArmorMendingRules.resetAfterAnvilRepair(original, new ItemStack(Items.NETHERITE_INGOT), output);
            assertEquals(0, ArmorMendingRules.state(output).getLongOr("repaired", -1));
            assertEquals(state.getStringOr("uuid", ""), ArmorMendingRules.state(output).getStringOr("uuid", ""));
        } finally {
            GameplayConfig.enabled = oldEnabled;
        }
    }
}
