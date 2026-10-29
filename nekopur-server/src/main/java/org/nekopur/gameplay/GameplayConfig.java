package org.nekopur.gameplay;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.enchantment.Enchantment;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NullMarked;

/** Survival progression settings, loaded and saved alongside the existing Purpur configuration. */
@NullMarked
public final class GameplayConfig {
    public static final String ROOT = "settings.nekopur.gameplay";
    public static boolean enabled = true;
    public static int maxTableLevel = 100;
    public static int maxShelfPower = 50;
    public static double bookPowerPerLevel = 1.0;
    public static int maxBookPower = 5;
    public static double tableLevelSpendMultiplier = 1.0;
    public static double mendingRepairMultiplier = 0.5;
    public static boolean removeAnvilCostLimit = true;
    public static boolean armorMendingEnabled = true;
    public static int armorMendingDelaySeconds = 0;
    public static int armorMendingRampSeconds = 300;
    public static double armorMendingDurabilityPerSecond = 1.0;
    public static int armorMendingXpPerDurability = 1;
    public static double armorMendingLifetimeMultiplier = 2.5;
    public static boolean vanishingEnabled = true;
    public static int vanishingCooldownSeconds = 60;
    public static long revision;
    private static Map<String, Integer> enchantmentCaps = defaultCaps();

    private GameplayConfig() {
    }

    public static void load(YamlConfiguration config) {
        config.addDefault(ROOT + ".enabled", true);
        config.addDefault(ROOT + ".enchanting.max-table-level", 100);
        config.addDefault(ROOT + ".enchanting.max-shelf-power", 50);
        config.addDefault(ROOT + ".enchanting.book-power-per-level", 1.0);
        config.addDefault(ROOT + ".enchanting.max-book-power", 5);
        config.addDefault(ROOT + ".enchanting.level-spend-multiplier", 1.0);
        config.addDefault(ROOT + ".mending.repair-multiplier", 0.5);
        config.addDefault(ROOT + ".anvil.remove-cost-limit", true);
        config.addDefault(ROOT + ".mending.armor.enabled", true);
        config.addDefault(ROOT + ".mending.armor.delay-seconds", 0);
        config.addDefault(ROOT + ".mending.armor.ramp-seconds", 300);
        config.addDefault(ROOT + ".mending.armor.durability-per-second", 1.0);
        config.addDefault(ROOT + ".mending.armor.xp-per-durability", 1);
        config.addDefault(ROOT + ".mending.armor.lifetime-multiplier", 2.5);
        config.addDefault(ROOT + ".vanishing.enabled", true);
        config.addDefault(ROOT + ".vanishing.cooldown-seconds", 60);
        defaultCaps().forEach((id, cap) -> config.addDefault(ROOT + ".enchanting.caps." + id, cap));

        boolean nextEnabled = config.getBoolean(ROOT + ".enabled", true);
        int nextTableLevel = integer(config, ".enchanting.max-table-level", 100, 1, 1000);
        int nextShelfPower = integer(config, ".enchanting.max-shelf-power", 50, 1, 500);
        double nextBookPower = decimal(config, ".enchanting.book-power-per-level", 1.0, 0.0, 100.0);
        int nextMaxBookPower = integer(config, ".enchanting.max-book-power", 5, 0, 500);
        double nextLevelSpend = decimal(config, ".enchanting.level-spend-multiplier", 1.0, 0.0, 100.0);
        double nextMending = decimal(config, ".mending.repair-multiplier", 0.5, 0.0, 1.0);
        int nextArmorDelay = integer(config, ".mending.armor.delay-seconds", 0, 0, 86400);
        int nextArmorRamp = integer(config, ".mending.armor.ramp-seconds", 300, 0, 86400);
        double nextArmorRate = decimal(config, ".mending.armor.durability-per-second", 1.0, 0.0, 1000.0);
        int nextArmorXp = integer(config, ".mending.armor.xp-per-durability", 1, 1, 1000);
        double nextArmorLifetime = decimal(config, ".mending.armor.lifetime-multiplier", 2.5, 0.0, 1000.0);
        int nextVanishingCooldown = integer(config, ".vanishing.cooldown-seconds", 60, 0, 86400);
        Map<String, Integer> nextCaps = new HashMap<>(defaultCaps());
        ConfigurationSection section = config.getConfigurationSection(ROOT + ".enchanting.caps");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Identifier id = Identifier.tryParse(key);
                if (id == null) {
                    throw new IllegalArgumentException("Invalid enchantment identifier in " + ROOT + ".enchanting.caps: " + key);
                }
                Object value = section.get(key);
                if (!(value instanceof Number number) || number.doubleValue() != number.intValue()
                    || number.intValue() < 1 || number.intValue() > 255) {
                    throw new IllegalArgumentException("Enchantment cap for " + key + " must be an integer from 1 to 255");
                }
                nextCaps.put(id.toString(), number.intValue());
            }
        }

        // Publish only after all values have passed validation; failed reloads retain the old rules.
        enabled = nextEnabled;
        maxTableLevel = nextTableLevel;
        maxShelfPower = nextShelfPower;
        bookPowerPerLevel = nextBookPower;
        maxBookPower = nextMaxBookPower;
        tableLevelSpendMultiplier = nextLevelSpend;
        mendingRepairMultiplier = nextMending;
        removeAnvilCostLimit = config.getBoolean(ROOT + ".anvil.remove-cost-limit", true);
        armorMendingEnabled = config.getBoolean(ROOT + ".mending.armor.enabled", true);
        armorMendingDelaySeconds = nextArmorDelay;
        armorMendingRampSeconds = nextArmorRamp;
        armorMendingDurabilityPerSecond = nextArmorRate;
        armorMendingXpPerDurability = nextArmorXp;
        armorMendingLifetimeMultiplier = nextArmorLifetime;
        vanishingEnabled = config.getBoolean(ROOT + ".vanishing.enabled", true);
        vanishingCooldownSeconds = nextVanishingCooldown;
        enchantmentCaps = Map.copyOf(nextCaps);
        revision++;
    }

    public static int getMaxLevel(Holder<Enchantment> enchantment) {
        if (!enabled) {
            return enchantment.value().getMaxLevel();
        }
        return enchantment.unwrapKey()
            .map(key -> enchantmentCaps.getOrDefault(key.identifier().toString(), enchantment.value().getMaxLevel()))
            .orElse(enchantment.value().getMaxLevel());
    }

    private static int integer(YamlConfiguration config, String suffix, int fallback, int min, int max) {
        String path = ROOT + suffix;
        Object value = config.get(path, fallback);
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()
            || number.intValue() < min || number.intValue() > max) {
            throw new IllegalArgumentException(path + " must be an integer from " + min + " to " + max);
        }
        return number.intValue();
    }

    private static double decimal(YamlConfiguration config, String suffix, double fallback, double min, double max) {
        String path = ROOT + suffix;
        Object value = config.get(path, fallback);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
            || number.doubleValue() < min || number.doubleValue() > max) {
            throw new IllegalArgumentException(path + " must be a finite number from " + min + " to " + max);
        }
        return number.doubleValue();
    }

    private static Map<String, Integer> defaultCaps() {
        return Map.ofEntries(
            Map.entry("minecraft:protection", 8),
            Map.entry("minecraft:fire_protection", 8),
            Map.entry("minecraft:blast_protection", 8),
            Map.entry("minecraft:projectile_protection", 8),
            Map.entry("minecraft:feather_falling", 8),
            Map.entry("minecraft:respiration", 8),
            Map.entry("minecraft:aqua_affinity", 3),
            Map.entry("minecraft:thorns", 8),
            Map.entry("minecraft:depth_strider", 5),
            Map.entry("minecraft:frost_walker", 5),
            Map.entry("minecraft:soul_speed", 5),
            Map.entry("minecraft:swift_sneak", 5),
            Map.entry("minecraft:binding_curse", 1),
            Map.entry("minecraft:sharpness", 8),
            Map.entry("minecraft:smite", 8),
            Map.entry("minecraft:bane_of_arthropods", 8),
            Map.entry("minecraft:knockback", 5),
            Map.entry("minecraft:fire_aspect", 5),
            Map.entry("minecraft:looting", 8),
            Map.entry("minecraft:sweeping_edge", 8),
            Map.entry("minecraft:efficiency", 8),
            Map.entry("minecraft:silk_touch", 3),
            Map.entry("minecraft:unbreaking", 8),
            Map.entry("minecraft:fortune", 8),
            Map.entry("minecraft:power", 8),
            Map.entry("minecraft:punch", 5),
            Map.entry("minecraft:flame", 3),
            Map.entry("minecraft:infinity", 1),
            Map.entry("minecraft:luck_of_the_sea", 5),
            Map.entry("minecraft:lure", 5),
            Map.entry("minecraft:loyalty", 5),
            Map.entry("minecraft:impaling", 8),
            Map.entry("minecraft:riptide", 5),
            Map.entry("minecraft:channeling", 3),
            Map.entry("minecraft:multishot", 3),
            Map.entry("minecraft:quick_charge", 5),
            Map.entry("minecraft:piercing", 5),
            Map.entry("minecraft:density", 8),
            Map.entry("minecraft:breach", 8),
            Map.entry("minecraft:wind_burst", 5),
            Map.entry("minecraft:lunge", 5),
            Map.entry("minecraft:mending", 3),
            Map.entry("minecraft:vanishing_curse", 3)
        );
    }
}
