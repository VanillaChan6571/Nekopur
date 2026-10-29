package org.nekopur.gameplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.level.Level;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@VanillaFeature
@NullMarked
class MendingOrbTest {
    @Test
    void normalOrbRepairsHalfVanillaDurabilityAndReportsConsumedExperience() throws ReflectiveOperationException {
        checkRepair(20, event -> {}, 13, 0, 7, 7);
    }

    @Test
    void partialRepairReturnsUnusedExperienceAfterEquipmentIsFullyRepaired() throws ReflectiveOperationException {
        checkRepair(3, event -> {}, 0, 4, 3, 3);
    }

    @Test
    void cancelledEventReturnsWholeOrbAndPreservesDamage() throws ReflectiveOperationException {
        checkRepair(20, event -> event.setCancelled(true), 20, 7, 7, 7);
    }

    @Test
    void pluginZeroRepairReturnsWholeOrbAndPreservesDamage() throws ReflectiveOperationException {
        checkRepair(20, event -> event.setRepairAmount(0), 20, 7, 7, 7);
    }

    private void checkRepair(int initialDamage, Consumer<PlayerItemMendEvent> editEvent, int expectedDamage,
                             int expectedRemaining, int expectedEventRepair, int expectedEventCost) throws ReflectiveOperationException {
        boolean previousEnabled = GameplayConfig.enabled;
        double previousMultiplier = GameplayConfig.mendingRepairMultiplier;
        try {
            GameplayConfig.enabled = true;
            GameplayConfig.mendingRepairMultiplier = 0.5;
            ServerLevel level = mock(ServerLevel.class);
            java.lang.reflect.Field config = Level.class.getDeclaredField("purpurConfig");
            config.setAccessible(true);
            config.set(level, mock(org.purpurmc.purpur.PurpurWorldConfig.class));
            ExperienceOrb orb = mock(ExperienceOrb.class, CALLS_REAL_METHODS);
            doReturn(level).when(orb).level();
            ServerPlayer player = mock(ServerPlayer.class);
            doReturn(level).when(player).level();
            ItemStack stack = new ItemStack(Items.DIAMOND_PICKAXE);
            stack.setDamageValue(initialDamage);
            EnchantedItemInUse selected = new EnchantedItemInUse(stack, EquipmentSlot.MAINHAND, player);
            int[] eventValues = {-1, -1};
            try (org.mockito.MockedStatic<EnchantmentHelper> enchantments = mockStatic(EnchantmentHelper.class);
                 org.mockito.MockedStatic<CraftEventFactory> events = mockStatic(CraftEventFactory.class)) {
                enchantments.when(() -> EnchantmentHelper.getRandomItemWith(eq(EnchantmentEffectComponents.REPAIR_WITH_XP), eq(player), any()))
                    .thenAnswer(invocation -> stack.isDamaged() ? Optional.of(selected) : Optional.empty());
                enchantments.when(() -> EnchantmentHelper.modifyDurabilityToRepairFromXp(eq(level), eq(stack), anyInt()))
                    .thenAnswer(invocation -> (int) invocation.getArgument(2) * 2);
                events.when(() -> CraftEventFactory.callPlayerItemMendEvent(eq(player), eq(orb), eq(stack), eq(EquipmentSlot.MAINHAND), anyInt(), anyInt()))
                    .thenAnswer(invocation -> {
                        eventValues[0] = invocation.getArgument(4);
                        eventValues[1] = invocation.getArgument(5);
                        PlayerItemMendEvent event = new PlayerItemMendEvent(mock(org.bukkit.entity.Player.class),
                            new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND_PICKAXE), org.bukkit.inventory.EquipmentSlot.HAND,
                            mock(org.bukkit.entity.ExperienceOrb.class), eventValues[0], eventValues[1]);
                        editEvent.accept(event);
                        return event;
                    });
                java.lang.reflect.Method repair = ExperienceOrb.class.getDeclaredMethod("repairPlayerItems", ServerPlayer.class, int.class);
                repair.setAccessible(true);
                assertEquals(expectedRemaining, repair.invoke(orb, player, 7));
                assertEquals(expectedDamage, stack.getDamageValue());
                assertEquals(expectedEventRepair, eventValues[0]);
                assertEquals(expectedEventCost, eventValues[1]);
            }
        } finally {
            GameplayConfig.enabled = previousEnabled;
            GameplayConfig.mendingRepairMultiplier = previousMultiplier;
        }
    }

    @Test
    void disabledRepairReturnsWholeOrbToPlayerWithoutSelectingEquipment() throws ReflectiveOperationException {
        boolean previousEnabled = GameplayConfig.enabled;
        double previousMultiplier = GameplayConfig.mendingRepairMultiplier;
        try {
            GameplayConfig.enabled = true;
            GameplayConfig.mendingRepairMultiplier = 0;
            ExperienceOrb orb = mock(ExperienceOrb.class, CALLS_REAL_METHODS);
            ServerPlayer player = mock(ServerPlayer.class);
            java.lang.reflect.Method repair = ExperienceOrb.class.getDeclaredMethod("repairPlayerItems", ServerPlayer.class, int.class);
            repair.setAccessible(true);
            assertEquals(7, repair.invoke(orb, player, 7));
            verifyNoInteractions(player);
        } finally {
            GameplayConfig.enabled = previousEnabled;
            GameplayConfig.mendingRepairMultiplier = previousMultiplier;
        }
    }
}
