package org.nekopur.gameplay;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedRandom;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import org.jspecify.annotations.NullMarked;

/** Survival enchanting rules, isolated from loot and other enchantment providers. */
@NullMarked
public final class EnchantingRules {
    private EnchantingRules() {
    }

    public static int bookshelfPower(Level level, BlockPos table) {
        double power = 0;
        int ordinaryShelves = 0;
        for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
            if (!level.getBlockState(table.offset(offset.getX() / 2, offset.getY(), offset.getZ() / 2))
                .is(BlockTags.ENCHANTMENT_POWER_TRANSMITTER)) {
                continue;
            }
            BlockPos shelfPos = table.offset(offset);
            if (level.getBlockEntity(shelfPos) instanceof ChiseledBookShelfBlockEntity shelf) {
                for (int slot = 0; slot < shelf.getContainerSize(); slot++) {
                    power += bookPower(shelf.getItem(slot));
                }
            } else if (level.getBlockState(shelfPos).is(BlockTags.ENCHANTMENT_POWER_PROVIDER)) {
                ordinaryShelves++;
            }
        }
        return (int) Math.min(GameplayConfig.maxShelfPower, Math.floor(power) + Math.min(15, ordinaryShelves));
    }

    /** A multi-enchantment book contributes its strongest rank, without double counting. */
    public static double bookPower(ItemStack book) {
        if (!book.is(Items.ENCHANTED_BOOK)) {
            return 0;
        }
        int strongest = 0;
        for (it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<Holder<Enchantment>> entry : book.getOrDefault(DataComponents.STORED_ENCHANTMENTS,
            net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY).entrySet()) {
            strongest = Math.max(strongest, entry.getIntValue());
        }
        return Math.min(GameplayConfig.maxBookPower, strongest * GameplayConfig.bookPowerPerLevel);
    }

    public static int tableCost(RandomSource random, int slot, int power, ItemStack item) {
        if (!item.has(DataComponents.ENCHANTABLE)) {
            return 0;
        }
        power = Mth.clamp(power, 0, GameplayConfig.maxShelfPower);
        int selected = random.nextInt(8) + 1 + (power >> 1) + random.nextInt(power + 1);
        int cost = slot == 0 ? Math.max(selected / 3, 1)
            : slot == 1 ? selected * 2 / 3 + 1 : Math.max(selected, power * 2);
        return Math.min(cost, GameplayConfig.maxTableLevel);
    }

    /** Lapis stays at one/two/three while actual XP consumption can be tuned independently. */
    public static int levelSpend(int slot) {
        return GameplayConfig.enabled ? (int) Math.ceil((slot + 1) * GameplayConfig.tableLevelSpendMultiplier) : slot + 1;
    }

    public static List<EnchantmentInstance> select(RandomSource random, ItemStack item, int cost,
        Stream<Holder<Enchantment>> source) {
        List<EnchantmentInstance> results = new ArrayList<>();
        net.minecraft.world.item.enchantment.Enchantable enchantable = item.get(DataComponents.ENCHANTABLE);
        if (enchantable == null) {
            return results;
        }
        cost += 1 + random.nextInt(enchantable.value() / 4 + 1) + random.nextInt(enchantable.value() / 4 + 1);
        float span = (random.nextFloat() + random.nextFloat() - 1.0F) * 0.15F;
        cost = Math.max(1, Math.round(cost + cost * span));
        List<EnchantmentInstance> available = available(cost, item, source);
        if (!available.isEmpty()) {
            WeightedRandom.getRandomItem(random, available, EnchantmentInstance::weight).ifPresent(results::add);
            while (random.nextInt(50) <= cost) {
                EnchantmentHelper.filterCompatibleEnchantments(available, results.getLast());
                if (available.isEmpty()) {
                    break;
                }
                WeightedRandom.getRandomItem(random, available, EnchantmentInstance::weight).ifPresent(results::add);
                cost /= 2;
            }
        }
        return results;
    }

    public static List<EnchantmentInstance> available(int power, ItemStack item, Stream<Holder<Enchantment>> source) {
        List<EnchantmentInstance> results = new ArrayList<>();
        source.filter(holder -> item.is(Items.BOOK) || holder.value().isPrimaryItem(item)).forEach(holder -> {
            Enchantment enchantment = holder.value();
            for (int rank = GameplayConfig.getMaxLevel(holder); rank >= enchantment.getMinLevel(); rank--) {
                // Saturated libraries retain capped enchantments rather than falling outside every cost window.
                if (power >= enchantment.getMinCost(rank)) {
                    results.add(new EnchantmentInstance(holder, rank));
                    break;
                }
            }
        });
        return results;
    }
}
