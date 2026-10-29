package org.nekopur.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NullMarked;

@NullMarked
class GameplayConfigTest {
    @AfterEach
    void restoreDefaults() {
        GameplayConfig.load(new YamlConfiguration());
    }

    @Test
    void allRequestedDefaultCapsAreWritten() {
        YamlConfiguration config = new YamlConfiguration();
        config.options().copyDefaults(true);
        GameplayConfig.load(config);
        org.bukkit.configuration.ConfigurationSection caps = config.getConfigurationSection(GameplayConfig.ROOT + ".enchanting.caps");
        assertNotNull(caps);
        assertEquals(43, caps.getKeys(false).size());
        assertEquals(8, caps.getInt("minecraft:protection"));
        assertEquals(8, caps.getInt("minecraft:sharpness"));
        assertEquals(3, caps.getInt("minecraft:silk_touch"));
        assertEquals(3, caps.getInt("minecraft:mending"));
        assertEquals(3, caps.getInt("minecraft:vanishing_curse"));
        assertEquals(1, caps.getInt("minecraft:infinity"));
        assertEquals(1, caps.getInt("minecraft:binding_curse"));
    }

    @Test
    void defaultsAndOverridesSurviveYamlRoundTrip() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.options().copyDefaults(true);
        config.set(GameplayConfig.ROOT + ".enchanting.caps.minecraft:sharpness", 100);
        config.set(GameplayConfig.ROOT + ".mending.repair-multiplier", 0.25);
        config.set(GameplayConfig.ROOT + ".mending.armor.xp-per-durability", 2);
        GameplayConfig.load(config);
        YamlConfiguration reloaded = new YamlConfiguration();
        reloaded.loadFromString(config.saveToString());
        GameplayConfig.load(reloaded);
        assertEquals(100, GameplayConfig.maxTableLevel);
        assertEquals(50, GameplayConfig.maxShelfPower);
        assertEquals(100, reloaded.getInt(GameplayConfig.ROOT + ".enchanting.caps.minecraft:sharpness"));
        assertEquals(8, reloaded.getInt(GameplayConfig.ROOT + ".enchanting.caps.minecraft:efficiency"));
        assertEquals(0.25, GameplayConfig.mendingRepairMultiplier);
        assertEquals(2, GameplayConfig.armorMendingXpPerDurability);
        assertTrue(GameplayConfig.removeAnvilCostLimit);
    }

    @Test
    void invalidReloadCannotPartiallyChangeActiveRules() {
        GameplayConfig.load(new YamlConfiguration());
        YamlConfiguration config = new YamlConfiguration();
        config.set(GameplayConfig.ROOT + ".enabled", false);
        config.set(GameplayConfig.ROOT + ".enchanting.max-table-level", 50);
        config.set(GameplayConfig.ROOT + ".enchanting.caps.minecraft:sharpness", 256);
        assertThrows(IllegalArgumentException.class, () -> GameplayConfig.load(config));
        assertTrue(GameplayConfig.enabled);
        assertEquals(100, GameplayConfig.maxTableLevel);
    }

    @Test
    void invalidNumbersAreRejectedInsteadOfTruncatedOrPropagated() {
        for (Object value : new Object[]{0, -1, 1001, 1.5, "1"}) {
            YamlConfiguration config = new YamlConfiguration();
            config.set(GameplayConfig.ROOT + ".mending.armor.xp-per-durability", value);
            assertThrows(IllegalArgumentException.class, () -> GameplayConfig.load(config));
        }
        for (Object value : new Object[]{-1, 0, 1001, 30.5, "100"}) {
            YamlConfiguration config = new YamlConfiguration();
            config.set(GameplayConfig.ROOT + ".enchanting.max-table-level", value);
            assertThrows(IllegalArgumentException.class, () -> GameplayConfig.load(config));
        }
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1}) {
            YamlConfiguration config = new YamlConfiguration();
            config.set(GameplayConfig.ROOT + ".mending.repair-multiplier", value);
            assertThrows(IllegalArgumentException.class, () -> GameplayConfig.load(config));
        }
    }
}
