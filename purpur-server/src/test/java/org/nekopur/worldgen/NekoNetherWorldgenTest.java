package org.nekopur.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@VanillaFeature
@NullMarked
public class NekoNetherWorldgenTest {
    private static final Set<ResourceKey<Biome>> CUSTOM = Set.of(NekoNetherBiomes.ASHEN_BARRENS, NekoNetherBiomes.EMBER_GROVE,
        NekoNetherBiomes.SOULWOOD_HOLLOW, NekoNetherBiomes.OBSIDIAN_SHORES);
    private static final Set<ResourceKey<Biome>> VANILLA = Set.of(Biomes.NETHER_WASTES, Biomes.SOUL_SAND_VALLEY,
        Biomes.CRIMSON_FOREST, Biomes.WARPED_FOREST, Biomes.BASALT_DELTAS);

    @Test
    void customPresetsSelectNativeNetherWhileOriginalPresetsStayUnchanged() {
        for (ResourceKey<WorldPreset> originalKey : List.of(NekoWorldgenBootstrap.PRESET, NekoWorldgenBootstrap.TALL_PRESET)) {
            ResourceKey<WorldPreset> customKey = originalKey.equals(NekoWorldgenBootstrap.PRESET)
                ? NekoWorldgenBootstrap.NETHER_PRESET : NekoWorldgenBootstrap.TALL_NETHER_PRESET;
            WorldPreset original = preset(originalKey);
            WorldPreset custom = preset(customKey);
            assertSame(original.overworld().orElseThrow(), custom.overworld().orElseThrow());
            assertSame(original.createWorldDimensions().get(LevelStem.END).orElseThrow(), custom.createWorldDimensions().get(LevelStem.END).orElseThrow());
            LevelStem originalNether = original.createWorldDimensions().get(LevelStem.NETHER).orElseThrow();
            LevelStem nativeNether = custom.createWorldDimensions().get(LevelStem.NETHER).orElseThrow();
            assertSame(originalNether.type(), nativeNether.type());
            assertTrue(assertInstanceOf(NoiseBasedChunkGenerator.class, originalNether.generator()).generatorSettings().is(NoiseGeneratorSettings.NETHER));
            assertTrue(assertInstanceOf(NoiseBasedChunkGenerator.class, nativeNether.generator()).generatorSettings().is(NekoNetherWorldgen.NOISE_SETTINGS));
        }
    }

    @Test
    void serverPropertiesSelectNetherAliasesAndBothOverworldProfiles() {
        for (String name : List.of("neko-nether", "NeKo-NeThEr", "nekopur:neko-nether",
            "neko-mmo-nether-tall", "NeKo-MmO-NeThEr-TaLl", "nekopur:neko-mmo-nether-tall")) {
            net.minecraft.world.level.levelgen.WorldDimensions dimensions = new DedicatedServerProperties.WorldDimensionData(new JsonObject(), name)
                .create(RegistryHelper.registryAccess());
            NoiseBasedChunkGenerator nether = assertInstanceOf(NoiseBasedChunkGenerator.class, dimensions.get(LevelStem.NETHER).orElseThrow().generator());
            assertTrue(nether.generatorSettings().is(NekoNetherWorldgen.NOISE_SETTINGS), name);
            NoiseBasedChunkGenerator overworld = assertInstanceOf(NoiseBasedChunkGenerator.class, dimensions.overworld());
            assertTrue(overworld.generatorSettings().is(name.toLowerCase(Locale.ROOT).contains("tall")
                ? NekoWorldgenBootstrap.TALL_NOISE_SETTINGS : NekoWorldgenBootstrap.NOISE_SETTINGS), name);
        }
    }

    @Test
    void seededClimateProducesDominantCustomRegionsAndRetainsVanillaBiomes() {
        NoiseBasedChunkGenerator generator = generator();
        assertEquals(9, generator.getBiomeSource().possibleBiomes().size());
        for (ResourceKey<Biome> key : CUSTOM) assertTrue(generator.getBiomeSource().possibleBiomes().stream().anyMatch(holder -> holder.is(key)));
        for (ResourceKey<Biome> key : VANILLA) assertTrue(generator.getBiomeSource().possibleBiomes().stream().anyMatch(holder -> holder.is(key)));
        Map<ResourceKey<Biome>, Integer> counts = new HashMap<>();
        for (long seed : new long[]{6449995202838594431L, 6571L, 26L}) {
            Map<ResourceKey<Biome>, Integer> seedCounts = new HashMap<>();
            RandomState random = random(seed, generator.generatorSettings().value());
            BiomeResolver resolver = generator.getBiomeSource().createResolver(random.createClimateSampler(SamplerContext.builder().enableCaches().build()));
            for (int z = -32768; z < 32768; z += 512) {
                for (int x = -32768; x < 32768; x += 512) {
                    ResourceKey<Biome> key = resolver.getNoiseBiome(x >> 2, 64 >> 2, z >> 2).unwrapKey().orElseThrow();
                    seedCounts.merge(key, 1, Integer::sum);
                }
            }
            int seedCustom = seedCounts.entrySet().stream().filter(entry -> CUSTOM.contains(entry.getKey())).mapToInt(Map.Entry::getValue).sum();
            int seedTotal = seedCounts.values().stream().mapToInt(Integer::intValue).sum();
            System.out.printf(Locale.ROOT, "Native Nether seed %d custom=%.2f%% (%d/%d), per-biome=%s%n", seed,
                100.0 * seedCustom / seedTotal, seedCustom, seedTotal,
                seedCounts.entrySet().stream().sorted(java.util.Comparator.comparing(entry -> entry.getKey().identifier().toString()))
                    .map(entry -> entry.getKey().identifier() + "=" + String.format(Locale.ROOT, "%.2f%%", 100.0 * entry.getValue() / seedTotal)).toList());
            assertTrue(seedCustom > seedTotal / 2, "Custom regions must dominate each seed: " + seed + " " + seedCounts);
            seedCounts.forEach((key, count) -> counts.merge(key, count, Integer::sum));
        }
        int custom = counts.entrySet().stream().filter(entry -> CUSTOM.contains(entry.getKey())).mapToInt(Map.Entry::getValue).sum();
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue(custom > total / 2, "Custom regions must dominate the overhaul: " + counts);
        for (ResourceKey<Biome> key : CUSTOM) assertTrue(counts.getOrDefault(key, 0) > 0, key.toString());
        for (ResourceKey<Biome> key : VANILLA) assertTrue(counts.getOrDefault(key, 0) > 0, "Vanilla biome must remain reachable: " + key);
        System.out.println("Native Nether climate sample custom=" + custom + "/" + total + ": " + counts);
    }

    @Test
    void biomeNetworkAndSavedGeneratorCodecsRoundTripUsingVanillaTypes() {
        RegistryOps<JsonElement> ops = RegistryHelper.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        for (ResourceKey<Biome> key : CUSTOM) {
            Biome biome = RegistryHelper.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(key).value();
            JsonElement network = Biome.NETWORK_CODEC.encodeStart(ops, biome).getOrThrow();
            Biome client = Biome.NETWORK_CODEC.parse(ops, network).getOrThrow();
            assertEquals(network, Biome.NETWORK_CODEC.encodeStart(ops, client).getOrThrow());
            assertEquals(biome.getBaseTemperature(), client.getBaseTemperature());
            JsonElement complete = Biome.DIRECT_CODEC.encodeStart(ops, biome).getOrThrow();
            assertEquals(complete, Biome.DIRECT_CODEC.encodeStart(ops, Biome.DIRECT_CODEC.parse(ops, complete).getOrThrow()).getOrThrow());
        }
        WorldPreset original = preset(NekoWorldgenBootstrap.TALL_NETHER_PRESET);
        JsonElement encoded = WorldPreset.DIRECT_CODEC.encodeStart(ops, original).getOrThrow();
        WorldPreset loaded = WorldPreset.DIRECT_CODEC.parse(ops, encoded).getOrThrow();
        NoiseBasedChunkGenerator saved = assertInstanceOf(NoiseBasedChunkGenerator.class, loaded.createWorldDimensions().get(LevelStem.NETHER).orElseThrow().generator());
        assertTrue(saved.generatorSettings().is(NekoNetherWorldgen.NOISE_SETTINGS));
        assertEquals(generator().getBiomeSource().possibleBiomes(), saved.getBiomeSource().possibleBiomes());
        NoiseGeneratorSettings settings = saved.generatorSettings().value();
        JsonElement graph = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, settings).getOrThrow();
        assertEquals(graph, NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, NoiseGeneratorSettings.DIRECT_CODEC.parse(ops, graph).getOrThrow()).getOrThrow());
    }

    @Test
    void nativeTagsKeepStructuresAndNetherBehaviorAvailable() {
        for (ResourceKey<Biome> key : CUSTOM) {
            Holder<Biome> biome = RegistryHelper.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(key);
            assertTrue(biome.is(BiomeTags.IS_NETHER));
            assertTrue(biome.is(BiomeTags.HAS_NETHER_FORTRESS));
            assertTrue(biome.is(BiomeTags.HAS_RUINED_PORTAL_NETHER));
            assertTrue(biome.is(BiomeTags.SPAWNS_WARM_VARIANT_FROGS));
            assertTrue(biome.is(BiomeTags.SPAWNS_WARM_VARIANT_FARM_ANIMALS));
            assertEquals(!key.equals(NekoNetherBiomes.ASHEN_BARRENS), biome.is(BiomeTags.HAS_BASTION_REMNANT));
            assertEquals(key.equals(NekoNetherBiomes.ASHEN_BARRENS) || key.equals(NekoNetherBiomes.SOULWOOD_HOLLOW), biome.is(BiomeTags.HAS_NETHER_FOSSIL));
            assertNull(biome.value().getAttributes().get(EnvironmentAttributes.PIGLINS_ZOMBIFY), "Dimension behavior must remain authoritative");
        }
        for (ResourceKey<Biome> key : VANILLA) {
            Holder<Biome> biome = RegistryHelper.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(key);
            assertTrue(biome.is(BiomeTags.IS_NETHER));
            assertTrue(biome.is(BiomeTags.HAS_NETHER_FORTRESS));
            assertTrue(biome.is(BiomeTags.HAS_RUINED_PORTAL_NETHER));
            assertEquals(!key.equals(Biomes.BASALT_DELTAS), biome.is(BiomeTags.HAS_BASTION_REMNANT));
        }
    }

    @Test
    void featureOrderingAcrossCustomAndVanillaNetherHasNoCycles() {
        assertDoesNotThrow(() -> FeatureSorter.buildFeaturesPerStep(new ArrayList<>(generator().getBiomeSource().possibleBiomes()),
            biome -> biome.value().getGenerationSettings().features(), true));
    }

    @Test
    void netherHeightLavaNoiseAndRawColumnsRemainVanilla() {
        NoiseBasedChunkGenerator nativeGenerator = generator();
        NoiseGeneratorSettings nativeSettings = nativeGenerator.generatorSettings().value();
        NoiseGeneratorSettings vanilla = RegistryHelper.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER).value();
        assertEquals(vanilla.noiseSettings(), nativeSettings.noiseSettings());
        assertEquals(vanilla.defaultBlock(), nativeSettings.defaultBlock());
        assertEquals(vanilla.defaultFluid(), nativeSettings.defaultFluid());
        assertEquals(vanilla.seaLevel(), nativeSettings.seaLevel());
        assertSame(vanilla.noiseRouter(), nativeSettings.noiseRouter());
        assertEquals(vanilla.aquifers(), nativeSettings.aquifers());
        assertEquals(vanilla.useLegacyRandomSource(), nativeSettings.useLegacyRandomSource());
        assertNotSame(vanilla.materialRule(), nativeSettings.materialRule());
        NoiseBasedChunkGenerator vanillaGenerator = assertInstanceOf(NoiseBasedChunkGenerator.class,
            preset(NekoWorldgenBootstrap.PRESET).createWorldDimensions().get(LevelStem.NETHER).orElseThrow().generator());
        LevelHeightAccessor height = LevelHeightAccessor.create(0, 256);
        RandomState oldState = random(6449995202838594431L, vanilla);
        RandomState newState = random(6449995202838594431L, nativeSettings);
        for (int[] point : new int[][]{{0, 0}, {-1024, 832}, {2048, -1152}, {-4096, -4096}}) {
            NoiseColumn expected = vanillaGenerator.getBaseColumn(point[0], point[1], height, oldState);
            NoiseColumn actual = nativeGenerator.getBaseColumn(point[0], point[1], height, newState);
            for (int y = 0; y < 256; y++) assertEquals(expected.getBlock(y), actual.getBlock(y), "Raw column at " + point[0] + "," + y + "," + point[1]);
        }
    }

    private static WorldPreset preset(ResourceKey<WorldPreset> key) {
        return RegistryHelper.registryAccess().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(key).value();
    }

    private static NoiseBasedChunkGenerator generator() {
        return (NoiseBasedChunkGenerator) preset(NekoWorldgenBootstrap.NETHER_PRESET).createWorldDimensions().get(LevelStem.NETHER).orElseThrow().generator();
    }

    private static RandomState random(long seed, NoiseGeneratorSettings settings) {
        return RandomState.create(RegistryHelper.registryAccess().lookupOrThrow(Registries.NOISE), seed, settings);
    }
}
