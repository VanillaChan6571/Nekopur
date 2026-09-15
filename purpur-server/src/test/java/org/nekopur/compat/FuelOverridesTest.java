package org.nekopur.compat;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.storage.loot.providers.number.floats.ResolvableFloat;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@VanillaFeature
@NullMarked
class FuelOverridesTest {
    @AfterEach
    void restoreComponentBehavior() {
        FuelOverrides.reset(Items.DIAMOND);
    }

    @Test
    void runtimeFuelCanBeAddedRemovedAndReadded() {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        assertFalse(FuelOverrides.isFuel(stack));
        assertNull(FuelOverrides.burnTime(stack));
        FuelOverrides.set(Items.DIAMOND, 400);
        assertTrue(FuelOverrides.isFuel(stack));
        assertEquals(400, FuelOverrides.burnTime(stack));
        FuelOverrides.set(Items.DIAMOND, 0);
        assertFalse(FuelOverrides.isFuel(stack));
        FuelOverrides.set(Items.DIAMOND, 800);
        assertTrue(FuelOverrides.isFuel(stack));
        assertEquals(800, FuelOverrides.burnTime(stack));
    }

    @Test
    void removalOverridesAnItemFuelComponentWithoutChangingTheStack() {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        CookingFuel component = new CookingFuel(new ResolvableInt.Constant(200), new ResolvableFloat.Constant(1.0F));
        stack.set(DataComponents.COOKING_FUEL, component);
        assertTrue(FuelOverrides.isFuel(stack));
        FuelOverrides.set(Items.DIAMOND, 0);
        assertFalse(FuelOverrides.isFuel(stack));
        assertSame(component, stack.get(DataComponents.COOKING_FUEL));
        FuelOverrides.reset(Items.DIAMOND);
        assertTrue(FuelOverrides.isFuel(stack));
    }

    @Test
    void emptyStacksAndNegativeFuelTimesAreRejected() {
        assertFalse(FuelOverrides.isFuel(ItemStack.EMPTY));
        assertThrows(IllegalArgumentException.class, () -> FuelOverrides.set(Items.DIAMOND, -1));
    }
}
