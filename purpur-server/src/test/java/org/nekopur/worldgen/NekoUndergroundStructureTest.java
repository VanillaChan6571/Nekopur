package org.nekopur.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.nekopur.worldgen.preview.NekoSeedPreview;

@VanillaFeature
@NullMarked
class NekoUndergroundStructureTest {
    @Test
    void registeredVanillaUndergroundBandsMoveDownWithTallCaves() {
        var registries = RegistryHelper.registryAccess();
        var generator = NekoSeedPreview.generator(registries, "neko-mmo-tall");
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        var heightContext = new WorldGenerationContext(generator, LevelHeightAccessor.create(-128, 640));
        for (var key : java.util.List.of(BuiltinStructures.ANCIENT_CITY, BuiltinStructures.TRIAL_CHAMBERS)) {
            Structure structure = registries.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key).value();
            var encoded = Structure.DIRECT_CODEC.encodeStart(ops, structure).getOrThrow().getAsJsonObject();
            HeightProvider provider = HeightProvider.CODEC.parse(JsonOps.INSTANCE, encoded.get("start_height")).getOrThrow();
            assertFalse(encoded.has("project_start_to_heightmap"), "Underground structures must not project to the surface");
            RandomSource random = RandomSource.create(6571L);
            for (int i = 0; i < 64; i++) {
                int original = provider.sample(random, heightContext);
                int adjusted = NekoUndergroundStructures.adjustStartHeight(generator, key, provider, false, original);
                assertEquals(original - 64, adjusted);
                if (key.equals(BuiltinStructures.ANCIENT_CITY)) assertEquals(-91, adjusted);
                else assertTrue(adjusted >= -104 && adjusted <= -84);
            }
        }
    }

    @Test
    void otherPresetsProjectedStructuresAndCustomProvidersKeepTheirHeights() {
        var registries = RegistryHelper.registryAccess();
        var tall = NekoSeedPreview.generator(registries, "neko-mmo-tall");
        var standard = NekoSeedPreview.generator(registries, "neko");
        var city = ConstantHeight.of(VerticalAnchor.absolute(-27));
        var chambers = UniformHeight.of(VerticalAnchor.absolute(-40), VerticalAnchor.absolute(-20));
        assertEquals(-27, NekoUndergroundStructures.adjustStartHeight(standard, BuiltinStructures.ANCIENT_CITY, city, false, -27));
        assertEquals(-30, NekoUndergroundStructures.adjustStartHeight(standard, BuiltinStructures.TRIAL_CHAMBERS, chambers, false, -30));
        assertEquals(-27, NekoUndergroundStructures.adjustStartHeight(tall, BuiltinStructures.ANCIENT_CITY, city, true, -27));
        assertEquals(-15, NekoUndergroundStructures.adjustStartHeight(tall, BuiltinStructures.TRAIL_RUINS,
            ConstantHeight.of(VerticalAnchor.absolute(-15)), true, -15));
        assertEquals(-50, NekoUndergroundStructures.adjustStartHeight(tall, BuiltinStructures.ANCIENT_CITY,
            ConstantHeight.of(VerticalAnchor.absolute(-50)), false, -50));
        assertEquals(-91, NekoUndergroundStructures.adjustStartHeight(tall, BuiltinStructures.ANCIENT_CITY,
            ConstantHeight.of(VerticalAnchor.aboveBottom(37)), false, -91));
        assertEquals(-31, NekoUndergroundStructures.adjustStartHeight(tall, BuiltinStructures.TRIAL_CHAMBERS,
            UniformHeight.of(VerticalAnchor.absolute(-41), VerticalAnchor.absolute(-20)), false, -31));
        assertEquals(-30, NekoUndergroundStructures.adjustStartHeight(tall, BuiltinStructures.TRIAL_CHAMBERS,
            UniformHeight.of(VerticalAnchor.aboveBottom(88), VerticalAnchor.aboveBottom(108)), false, -30));
        ResourceKey<Structure> custom = ResourceKey.create(Registries.STRUCTURE, Identifier.parse("example:city"));
        assertEquals(-27, NekoUndergroundStructures.adjustStartHeight(tall, custom, city, false, -27));
        assertEquals(-27, NekoUndergroundStructures.adjustStartHeight(tall, null, city, false, -27));
    }
}
