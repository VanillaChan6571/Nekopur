package org.nekopur.gameplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@VanillaFeature
@NullMarked
class AnvilRulesTest {
    @Test
    void shiftClickResetsTheActualTransferredArmorInsteadOfDetachedTakeCopy() {
        boolean previousEnabled = GameplayConfig.enabled;
        try {
            GameplayConfig.enabled = true;
            Player player = mock(Player.class);
            player.experienceLevel = 10;
            Inventory inventory = new Inventory(player, new EntityEquipment());
            AccessibleAnvil menu = new AccessibleAnvil(inventory);
            ItemStack original = new ItemStack(Items.NETHERITE_CHESTPLATE);
            original.setDamageValue(400);
            net.minecraft.nbt.CompoundTag state = ArmorMendingRules.state(original);
            state.putLong("repaired", 1480);
            ArmorMendingRules.save(original, state);
            ItemStack result = original.copy();
            result.setDamageValue(250);
            menu.getSlot(0).set(original);
            menu.getSlot(1).set(new ItemStack(Items.NETHERITE_INGOT));
            menu.getSlot(2).set(result);
            menu.repairItemCountCost = 1;
            menu.cost.set(1);
            assertTrue(menu.canTake(player));
            // Put the exhausted plugin-edited result back after the permission check:
            // quickMoveStack itself must reset the stack before the real transfer.
            ArmorMendingRules.save(result, state);
            menu.quickMoveStack(player, AnvilMenu.RESULT_SLOT);
            ItemStack received = ItemStack.EMPTY;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack item = inventory.getItem(slot);
                if (item.is(Items.NETHERITE_CHESTPLATE)) {
                    received = item;
                    break;
                }
            }
            assertFalse(received.isEmpty());
            assertEquals(250, received.getDamageValue());
            assertEquals(0, ArmorMendingRules.state(received).getLongOr("repaired", -1));
            assertEquals(state.getStringOr("uuid", ""), ArmorMendingRules.state(received).getStringOr("uuid", ""));
            assertTrue(menu.getSlot(0).getItem().isEmpty());
            assertTrue(menu.getSlot(1).getItem().isEmpty());
        } finally {
            GameplayConfig.enabled = previousEnabled;
        }
    }

    @Test
    void expensiveResultRequiresActualExperienceDespiteClientDisplay() {
        boolean previousEnabled = GameplayConfig.enabled;
        boolean previousLimit = GameplayConfig.removeAnvilCostLimit;
        try {
            GameplayConfig.enabled = true;
            GameplayConfig.removeAnvilCostLimit = true;
            Player player = mock(Player.class);
            AccessibleAnvil menu = new AccessibleAnvil(new Inventory(player, new EntityEquipment()));
            menu.cost.set(100);
            assertEquals(100, menu.getCost());
            assertEquals(39, menu.dataSlots.getFirst().get());
            player.experienceLevel = 39;
            assertFalse(menu.canTake(player));
            player.experienceLevel = 100;
            assertTrue(menu.canTake(player));
            menu.dataSlots.getFirst().set(120);
            assertEquals(120, menu.getCost());
            assertFalse(menu.canTake(player));
            player.experienceLevel = 120;
            assertTrue(menu.canTake(player));
            menu.take(player);
            verify(player).giveExperienceLevels(-120);
            assertEquals(AnvilMenu.DEFAULT_DENIED_COST, menu.getCost());
        } finally {
            GameplayConfig.enabled = previousEnabled;
            GameplayConfig.removeAnvilCostLimit = previousLimit;
        }
    }

    @Test
    void disabledModePreservesVanillaDisplay() {
        boolean previousEnabled = GameplayConfig.enabled;
        try {
            GameplayConfig.enabled = false;
            AccessibleAnvil menu = new AccessibleAnvil(new Inventory(mock(Player.class), new EntityEquipment()));
            menu.cost.set(100);
            assertEquals(100, menu.dataSlots.getFirst().get());
        } finally {
            GameplayConfig.enabled = previousEnabled;
        }
    }

    private static final class AccessibleAnvil extends AnvilMenu {
        AccessibleAnvil(Inventory inventory) {
            super(1, inventory);
        }

        boolean canTake(Player player) {
            return this.mayPickup(player, true);
        }

        void take(Player player) {
            this.onTake(player, ItemStack.EMPTY);
        }

        @Override
        public void slotsChanged(Container container) {
            // This test supplies a prepared transaction and verifies pickup/payment only.
        }
    }
}
