package org.nekopur.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@VanillaFeature
@NullMarked
public class NekoWorldgenBootstrapTest {
    @Test
    void nativeBindingMakesValuesAvailableWithoutFreezingRegistry() {
        ResourceKey<Registry<String>> registryKey = ResourceKey.createRegistryKey(Identifier.parse("nekopur:test_native_binding"));
        MappedRegistry<String> registry = new MappedRegistry<>(registryKey, com.mojang.serialization.Lifecycle.stable());
        ResourceKey<String> first = ResourceKey.create(registryKey, Identifier.parse("nekopur:first"));
        Holder.Reference<String> reference = registry.register(first, "first", RegistrationInfo.BUILT_IN);
        assertThrows(IllegalStateException.class, reference::value);
        registry.bindValuesForNativeBootstrap();
        assertEquals("first", reference.value());
        assertDoesNotThrow(() -> registry.register(ResourceKey.create(registryKey, Identifier.parse("nekopur:second")), "second", RegistrationInfo.BUILT_IN));
        registry.bindValuesForNativeBootstrap();
        assertEquals("second", registry.getOrThrow(ResourceKey.create(registryKey, Identifier.parse("nekopur:second"))).value());
    }

    @Test
    void startupLoadsNativePresetWithoutExternalWorldgenPack() {
        Registry<WorldPreset> presets = RegistryHelper.registryAccess().lookupOrThrow(Registries.WORLD_PRESET);
        WorldPreset neko = presets.getOrThrow(NekoWorldgenBootstrap.PRESET).value();
        WorldPreset normal = presets.getOrThrow(WorldPresets.NORMAL).value();
        NoiseBasedChunkGenerator generator = assertInstanceOf(NoiseBasedChunkGenerator.class, neko.overworld().orElseThrow().generator());
        assertTrue(generator.generatorSettings().is(NekoWorldgenBootstrap.NOISE_SETTINGS));
        assertTrue(assertInstanceOf(NoiseBasedChunkGenerator.class, normal.overworld().orElseThrow().generator())
            .generatorSettings().is(NoiseGeneratorSettings.OVERWORLD));
        assertSame(normal.createWorldDimensions().get(LevelStem.NETHER).orElseThrow(), neko.createWorldDimensions().get(LevelStem.NETHER).orElseThrow());
        assertSame(normal.createWorldDimensions().get(LevelStem.END).orElseThrow(), neko.createWorldDimensions().get(LevelStem.END).orElseThrow());
    }

    @Test
    void serverPropertiesSelectBothAliasAndNamespacedPreset() {
        for (String name : List.of("Neko", "neko", "nekopur:neko")) {
            NoiseBasedChunkGenerator generator = assertInstanceOf(NoiseBasedChunkGenerator.class,
                new DedicatedServerProperties.WorldDimensionData(new JsonObject(), name).create(RegistryHelper.registryAccess()).overworld());
            assertTrue(generator.generatorSettings().is(NekoWorldgenBootstrap.NOISE_SETTINGS), name);
        }
        assertEquals("minecraft:normal", NekoWorldgenBootstrap.normalizeLevelType("minecraft:normal"));
    }

    @Test
    void savedGeneratorCodecRetainsNativeSettingsAndDimensions() {
        RegistryOps<com.google.gson.JsonElement> ops = RegistryHelper.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        WorldPreset preset = RegistryHelper.registryAccess().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(NekoWorldgenBootstrap.PRESET).value();
        com.google.gson.JsonElement encoded = WorldPreset.DIRECT_CODEC.encodeStart(ops, preset).getOrThrow();
        assertTrue(encoded.toString().contains("nekopur:neko"));
        WorldPreset decoded = WorldPreset.DIRECT_CODEC.parse(ops, encoded).getOrThrow();
        NoiseBasedChunkGenerator generator = assertInstanceOf(NoiseBasedChunkGenerator.class, decoded.overworld().orElseThrow().generator());
        assertTrue(generator.generatorSettings().is(NekoWorldgenBootstrap.NOISE_SETTINGS));
        assertTrue(decoded.createWorldDimensions().get(LevelStem.NETHER).isPresent());
        assertTrue(decoded.createWorldDimensions().get(LevelStem.END).isPresent());
        // Exercise the complete native graph, not only its named settings holder.
        com.google.gson.JsonElement settings = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, generator.generatorSettings().value()).getOrThrow();
        NoiseGeneratorSettings decodedSettings = NoiseGeneratorSettings.DIRECT_CODEC.parse(ops, settings).getOrThrow();
        assertEquals(generator.generatorSettings().value().noiseSettings(), decodedSettings.noiseSettings());
        assertEquals(generator.generatorSettings().value().seaLevel(), decodedSettings.seaLevel());
    }

    @Test
    void unrelatedRegistryLoadsDoNotInstallWorldGeneration() {
        RegistryOps.RegistryInfoLookup unused = new RegistryOps.RegistryInfoLookup() {
            @Override
            public <T> Optional<HolderGetter<T>> lookup(ResourceKey<? extends Registry<? extends T>> registryKey) {
                throw new AssertionError("An unrelated registry load must not request worldgen dependencies");
            }

            @Override
            public HolderLookup.Provider lookupForValueCopyViaBuilders() {
                throw new AssertionError("An unrelated registry load must not request builders");
            }
        };
        assertDoesNotThrow(() -> NekoWorldgenBootstrap.register(unused, List.of()));
    }
}
