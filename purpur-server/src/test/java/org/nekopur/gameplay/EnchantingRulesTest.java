package org.nekopur.gameplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@VanillaFeature
@NullMarked
public class EnchantingRulesTest {
    @BeforeEach
    @AfterEach
    void resetRules() {
        GameplayConfig.load(new YamlConfiguration());
    }

    private Holder<Enchantment> sharpness() {
        return RegistryHelper.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
    }

    private ItemStack book(int rank) {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(sharpness(), rank);
        book.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
        return book;
    }

    @Test
    void booksUseStoredRanksAndCapTheirContribution() {
        assertEquals(0.0, EnchantingRules.bookPower(new ItemStack(Items.BOOK)));
        assertEquals(2.0, EnchantingRules.bookPower(book(2)));
        assertEquals(5.0, EnchantingRules.bookPower(book(100)));
    }

    @Test
    void ordinaryShelvesCannotUnlockAdvancedEnchantingAndBlockedBooksAddNothing() {
        Level level = mock(Level.class);
        for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
            when(level.getBlockState(BlockPos.ZERO.offset(offset))).thenReturn(Blocks.BOOKSHELF.defaultBlockState());
            when(level.getBlockState(BlockPos.ZERO.offset(offset.getX() / 2, offset.getY(), offset.getZ() / 2)))
                .thenReturn(Blocks.AIR.defaultBlockState());
        }
        assertEquals(15, EnchantingRules.bookshelfPower(level, BlockPos.ZERO));
        BlockPos offset = EnchantingTableBlock.BOOKSHELF_OFFSETS.iterator().next();
        ChiseledBookShelfBlockEntity shelf = mock(ChiseledBookShelfBlockEntity.class);
        when(shelf.getContainerSize()).thenReturn(6);
        for (int slot = 0; slot < 6; slot++) {
            when(shelf.getItem(slot)).thenReturn(book(5));
        }
        when(level.getBlockEntity(BlockPos.ZERO.offset(offset))).thenReturn(shelf);
        assertEquals(45, EnchantingRules.bookshelfPower(level, BlockPos.ZERO));
        when(level.getBlockState(BlockPos.ZERO.offset(offset.getX() / 2, offset.getY(), offset.getZ() / 2)))
            .thenReturn(Blocks.STONE.defaultBlockState());
        assertEquals(15, EnchantingRules.bookshelfPower(level, BlockPos.ZERO));
    }

    @Test
    void highestOfferReachesOneHundredWhileOrdinaryShelvesStayVanilla() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        for (int seed = 0; seed < 50; seed++) {
            assertEquals(100, EnchantingRules.tableCost(RandomSource.create(seed), 2, 50, sword));
            assertEquals(30, EnchantingRules.tableCost(RandomSource.create(seed), 2, 15, sword));
        }
        assertEquals(0, EnchantingRules.tableCost(RandomSource.create(1), 2, 50, new ItemStack(Items.STONE)));
    }

    @Test
    void experienceSpendIsIndependentOfOfferRequirementAndSupportsDisabling() {
        assertEquals(3, EnchantingRules.levelSpend(2));
        YamlConfiguration config = new YamlConfiguration();
        config.set(GameplayConfig.ROOT + ".enchanting.level-spend-multiplier", 2.5);
        GameplayConfig.load(config);
        assertEquals(8, EnchantingRules.levelSpend(2));
        config.set(GameplayConfig.ROOT + ".enabled", false);
        GameplayConfig.load(config);
        assertEquals(3, EnchantingRules.levelSpend(2));
    }

    @Test
    void highPowerSelectsHigherRanksAndSaturatedLibrariesStillOfferValidResults() {
        List<EnchantmentInstance> available = EnchantingRules.available(100, new ItemStack(Items.DIAMOND_SWORD), Stream.of(sharpness()));
        assertEquals(1, available.size());
        assertEquals(8, available.getFirst().level());
        YamlConfiguration config = new YamlConfiguration();
        config.set(GameplayConfig.ROOT + ".enchanting.caps.minecraft:sharpness", 3);
        GameplayConfig.load(config);
        assertEquals(3, EnchantingRules.available(100, new ItemStack(Items.DIAMOND_SWORD), Stream.of(sharpness())).getFirst().level());
        assertFalse(EnchantingRules.select(RandomSource.create(123), new ItemStack(Items.DIAMOND_SWORD), 100, Stream.of(sharpness())).isEmpty());
        assertTrue(EnchantingRules.available(100, new ItemStack(Items.DIAMOND_PICKAXE), Stream.of(sharpness())).isEmpty());
    }

    @Test
    void sameSeedProducesSamePreviewAndAppliedEnchantmentsAndRetainsConflicts() {
        List<Holder<Enchantment>> source = RegistryHelper.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
            .listElements().map(holder -> (Holder<Enchantment>) holder).toList();
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        for (int seed = 0; seed < 50; seed++) {
            List<EnchantmentInstance> preview = EnchantingRules.select(RandomSource.create(seed), sword, 100, source.stream());
            assertEquals(preview, EnchantingRules.select(RandomSource.create(seed), sword, 100, source.stream()));
            for (int first = 0; first < preview.size(); first++) {
                for (int second = first + 1; second < preview.size(); second++) {
                    assertTrue(Enchantment.areCompatible(preview.get(first).enchantment(), preview.get(second).enchantment()));
                }
            }
        }
    }
}
