package org.nekopur.gameplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.plugin.PluginManager;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.purpurmc.purpur.PurpurWorldConfig;

@VanillaFeature
@NullMarked
public class EnchantmentMenuTest {
    @BeforeEach
    @AfterEach
    void resetRules() {
        GameplayConfig.load(new YamlConfiguration());
    }

    @Test
    void changedConfigurationRejectsTheOldClickWithoutTakingExperienceOrLapis() throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        fixture.insertSword();
        assertEquals(30, fixture.menu.costs[2]);
        YamlConfiguration next = new YamlConfiguration();
        next.set(GameplayConfig.ROOT + ".enchanting.max-table-level", 10);
        GameplayConfig.load(next);
        assertFalse(fixture.menu.clickMenuButton(fixture.player, 2));
        assertEquals(10, fixture.menu.costs[2]);
        fixture.assertNoPaymentOrEnchantments();
    }

    @Test
    void removedBooksRejectTheStaleClickAndBroadcastRefreshesNewOffers() throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        BlockPos offset = EnchantingTableBlock.BOOKSHELF_OFFSETS.iterator().next();
        ChiseledBookShelfBlockEntity shelf = mock(ChiseledBookShelfBlockEntity.class);
        when(shelf.getContainerSize()).thenReturn(6);
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable stored = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        stored.set(RegistryHelper.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 5);
        book.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
        for (int slot = 0; slot < 6; slot++) {
            when(shelf.getItem(slot)).thenReturn(book);
        }
        when(fixture.level.getBlockEntity(BlockPos.ZERO.offset(offset))).thenReturn(shelf);
        fixture.insertSword();
        assertEquals(90, fixture.menu.costs[2]);
        for (int slot = 0; slot < 6; slot++) {
            when(shelf.getItem(slot)).thenReturn(ItemStack.EMPTY);
        }
        assertFalse(fixture.menu.clickMenuButton(fixture.player, 2));
        assertEquals(30, fixture.menu.costs[2]);
        fixture.assertNoPaymentOrEnchantments();
        for (int slot = 0; slot < 6; slot++) {
            when(shelf.getItem(slot)).thenReturn(book);
        }
        fixture.menu.broadcastChanges();
        assertEquals(90, fixture.menu.costs[2]);
        fixture.assertNoPaymentOrEnchantments();
    }

    private static final class Fixture {
        final Level level = mock(Level.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final EnchantmentMenu menu;

        Fixture() throws ReflectiveOperationException {
            // Real menu and registry; only the surrounding server/world/event transport is mocked.
            Field config = Level.class.getField("purpurConfig");
            config.setAccessible(true);
            config.set(this.level, mock(PurpurWorldConfig.class));
            when(this.level.registryAccess()).thenReturn(RegistryHelper.registryAccess());
            CraftServer server = mock(CraftServer.class);
            when(this.level.getCraftServer()).thenReturn(server);
            when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
            when(this.player.getBukkitEntity()).thenReturn(mock(CraftPlayer.class));
            when(this.player.getEnchantmentSeed()).thenReturn(123);
            this.player.experienceLevel = 100;
            for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
                when(this.level.getBlockState(BlockPos.ZERO.offset(offset))).thenReturn(Blocks.BOOKSHELF.defaultBlockState());
                when(this.level.getBlockState(BlockPos.ZERO.offset(offset.getX() / 2, offset.getY(), offset.getZ() / 2)))
                    .thenReturn(Blocks.AIR.defaultBlockState());
            }
            Location location = mock(Location.class);
            when(location.getBlock()).thenReturn(mock(org.bukkit.block.Block.class));
            ContainerLevelAccess access = new ContainerLevelAccess() {
                @Override
                public <T> Optional<T> evaluate(BiFunction<Level, BlockPos, T> action) {
                    return Optional.of(action.apply(Fixture.this.level, BlockPos.ZERO));
                }

                @Override
                public Location getLocation() {
                    return location;
                }
            };
            this.menu = new EnchantmentMenu(1, new Inventory(this.player, new EntityEquipment()), access);
            this.menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 10));
        }

        void insertSword() {
            this.menu.getSlot(0).set(new ItemStack(Items.DIAMOND_SWORD));
        }

        void assertNoPaymentOrEnchantments() {
            assertEquals(100, this.player.experienceLevel);
            assertEquals(10, this.menu.getSlot(1).getItem().getCount());
            assertTrue(this.menu.getSlot(0).getItem().getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty());
            verify(this.player, never()).onEnchantmentPerformed(any(ItemStack.class), anyInt());
        }
    }
}
