package org.nekopur.compat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Runtime overrides for Purpur's fuel API after 26.3 replaced the global fuel table. */
@NullMarked
public final class FuelOverrides {
    private static final Map<Item, Integer> BURN_TIMES = new ConcurrentHashMap<>();

    private FuelOverrides() {
    }

    public static void set(Item item, int ticks) {
        if (ticks < 0) throw new IllegalArgumentException("Fuel time must not be negative");
        BURN_TIMES.put(item, ticks);
    }

    public static @Nullable Integer burnTime(ItemStack stack) {
        return BURN_TIMES.get(stack.getItem());
    }

    static void reset(Item item) {
        BURN_TIMES.remove(item);
    }

    public static boolean isFuel(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Integer ticks = burnTime(stack);
        return ticks == null ? stack.has(DataComponents.COOKING_FUEL) : ticks > 0;
    }
}
