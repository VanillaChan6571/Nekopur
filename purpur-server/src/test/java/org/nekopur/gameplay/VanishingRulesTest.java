package org.nekopur.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.bukkit.support.environment.VanillaFeature;
import org.bukkit.support.RegistryHelper;
import org.bukkit.configuration.file.YamlConfiguration;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@VanillaFeature
@NullMarked
class VanishingRulesTest {
    @Test
    void automaticActivationRespectsEffectCancellationAndWritesCooldownOnlyOnSuccess() {
        GameplayConfig.load(new YamlConfiguration());
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        org.bukkit.craftbukkit.entity.CraftPlayer bukkit = mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
        org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer data = mock(org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer.class);
        when(player.isAlive()).thenReturn(true);
        when(player.level()).thenReturn(level);
        when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        when(player.getBukkitEntity()).thenReturn(bukkit);
        when(bukkit.getPersistentDataContainer()).thenReturn(data);
        when(data.getOrDefault(any(), eq(PersistentDataType.LONG), eq(0L))).thenReturn(0L);
        ItemStack item = new ItemStack(Items.DIAMOND_SWORD);
        item.enchant(RegistryHelper.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.VANISHING_CURSE), 2);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            when(player.getItemBySlot(slot)).thenReturn(slot == EquipmentSlot.MAINHAND ? item : ItemStack.EMPTY);
        }
        when(level.getMaxLocalRawBrightness(BlockPos.ZERO)).thenReturn(2);
        VanishingRules.tick(player);
        verify(data, never()).set(any(), eq(PersistentDataType.LONG), anyLong());
        when(player.addEffect(any(MobEffectInstance.class), eq(EntityPotionEffectEvent.Cause.UNKNOWN))).thenReturn(true);
        long started = System.currentTimeMillis();
        VanishingRules.tick(player);
        org.mockito.ArgumentCaptor<Long> cooldown = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(data).set(any(), eq(PersistentDataType.LONG), cooldown.capture());
        assertTrue(cooldown.getValue() >= started + 60000L);
        org.mockito.ArgumentCaptor<MobEffectInstance> effect = org.mockito.ArgumentCaptor.forClass(MobEffectInstance.class);
        verify(player, times(2)).addEffect(effect.capture(), eq(EntityPotionEffectEvent.Cause.UNKNOWN));
        assertEquals(400, effect.getValue().getDuration());
        assertEquals(MobEffects.INVISIBILITY, effect.getValue().getEffect());
    }

    @Test
    void crouchingStillRequiresDarknessAndUsesTheWorldSkyDarkening() {
        GameplayConfig.load(new YamlConfiguration());
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        org.bukkit.craftbukkit.entity.CraftPlayer bukkit = mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
        org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer data = mock(org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer.class);
        when(player.isAlive()).thenReturn(true);
        when(player.isShiftKeyDown()).thenReturn(true);
        when(player.level()).thenReturn(level);
        when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        when(player.getBukkitEntity()).thenReturn(bukkit);
        when(bukkit.getPersistentDataContainer()).thenReturn(data);
        when(data.getOrDefault(any(), eq(PersistentDataType.LONG), eq(0L))).thenReturn(0L);
        ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);
        helmet.enchant(RegistryHelper.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.VANISHING_CURSE), 3);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            when(player.getItemBySlot(slot)).thenReturn(slot == EquipmentSlot.HEAD ? helmet : ItemStack.EMPTY);
        }
        doCallRealMethod().when(level).getMaxLocalRawBrightness(BlockPos.ZERO);
        doCallRealMethod().when(level).getMaxLocalRawBrightness(eq(BlockPos.ZERO), anyInt());
        when(level.getRawBrightness(eq(BlockPos.ZERO), anyInt())).thenAnswer(invocation -> 15 - invocation.<Integer>getArgument(1));

        // Daylight remains ineligible even while crouching.
        when(level.getSkyDarken()).thenReturn(0);
        VanishingRules.tick(player);
        verify(player, never()).addEffect(any(MobEffectInstance.class), any(EntityPotionEffectEvent.Cause.class));

        // The real LevelReader method subtracts nighttime sky darkening.
        when(level.getSkyDarken()).thenReturn(11);
        when(player.addEffect(any(MobEffectInstance.class), eq(EntityPotionEffectEvent.Cause.UNKNOWN))).thenReturn(true);
        VanishingRules.tick(player);
        org.mockito.ArgumentCaptor<MobEffectInstance> effect = org.mockito.ArgumentCaptor.forClass(MobEffectInstance.class);
        verify(player).addEffect(effect.capture(), eq(EntityPotionEffectEvent.Cause.UNKNOWN));
        assertEquals(600, effect.getValue().getDuration());

        // A persisted cooldown prevents another activation in the same darkness.
        when(data.getOrDefault(any(), eq(PersistentDataType.LONG), eq(0L))).thenReturn(Long.MAX_VALUE);
        VanishingRules.tick(player);
        verify(player, times(1)).addEffect(any(MobEffectInstance.class), eq(EntityPotionEffectEvent.Cause.UNKNOWN));
    }

    @AfterEach
    void resetRules() {
        GameplayConfig.load(new YamlConfiguration());
    }

    @Test
    void deathLossIsSuppressedOnlyWhenReworkIsEnabled() {
        GameplayConfig.load(new YamlConfiguration());
        ItemStack item = new ItemStack(Items.DIAMOND_SWORD);
        item.enchant(RegistryHelper.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.VANISHING_CURSE), 3);
        assertFalse(EnchantmentHelper.has(item, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP));
        GameplayConfig.vanishingEnabled = false;
        assertTrue(EnchantmentHelper.has(item, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP));
        GameplayConfig.vanishingEnabled = true;
        GameplayConfig.enabled = false;
        assertTrue(EnchantmentHelper.has(item, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP));
    }

    @Test
    void darknessThresholdsAreStrictAndRankSpecific() {
        assertTrue(VanishingRules.canActivate(1, 0, 1000, 1000));
        assertFalse(VanishingRules.canActivate(1, 1, 1000, 1000));
        assertTrue(VanishingRules.canActivate(2, 2, 1000, 1000));
        assertFalse(VanishingRules.canActivate(2, 3, 1000, 1000));
        assertTrue(VanishingRules.canActivate(3, 4, 1000, 1000));
        assertFalse(VanishingRules.canActivate(3, 5, 1000, 1000));
        assertFalse(VanishingRules.canActivate(0, 0, 1000, 1000));
    }

    @Test
    void cooldownAndDurationDoNotStackAcrossEquipmentOrUnsafeRanks() {
        assertFalse(VanishingRules.canActivate(3, 0, 999, 1000));
        assertEquals(10, VanishingRules.durationSeconds(1));
        assertEquals(20, VanishingRules.durationSeconds(2));
        assertEquals(30, VanishingRules.durationSeconds(3));
        assertEquals(30, VanishingRules.durationSeconds(255));
    }
}
