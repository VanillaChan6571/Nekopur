package org.nekopur.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@VanillaFeature
@NullMarked
public class NekoTerrainTest {
    private static final ResourceKey<NoiseGeneratorSettings> SETTINGS =
        ResourceKey.create(Registries.NOISE_SETTINGS, Identifier.parse("nekopur:neko"));
    private static final ResourceKey<WorldPreset> PRESET =
        ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("nekopur:neko"));

    private NoiseGeneratorSettings settings() {
        return RegistryHelper.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(SETTINGS).value();
    }

    private RandomState state(long seed) {
        return RandomState.create(RegistryHelper.registryAccess().lookupOrThrow(Registries.NOISE), seed, this.settings());
    }

    @Test
    void terrainIsSeededAndHasBothLandAndOpenOcean() {
        RandomState first = this.state(6571L);
        RandomState same = this.state(6571L);
        RandomState other = this.state(6572L);
        DensitySamplerSet firstSamplers = first.samplersWithContext(SamplerContext.builder().enableCaches().build());
        DensitySamplerSet sameSamplers = same.samplersWithContext(SamplerContext.builder().enableCaches().build());
        DensitySamplerSet otherSamplers = other.samplersWithContext(SamplerContext.builder().enableCaches().build());
        int ocean = 0;
        int land = 0;
        int different = 0;
        for (int z = -32768; z < 32768; z += 1024) {
            for (int x = -32768; x < 32768; x += 1024) {
                float value = firstSamplers.sampleValue(this.settings().noiseRouter().continents(), x, 0, z);
                assertTrue(Float.isFinite(value));
                assertEquals(value, sameSamplers.sampleValue(this.settings().noiseRouter().continents(), x, 0, z));
                if (value < -0.19F) {
                    ocean++;
                } else {
                    land++;
                }
                if (value != otherSamplers.sampleValue(this.settings().noiseRouter().continents(), x, 0, z)) {
                    different++;
                }
            }
        }
        assertTrue(ocean > 0 && land > 0, "The preset must produce separated land and ocean regions");
        assertTrue(different > 0, "Different world seeds must change continental geography");
        System.out.println("Neko seed 6571 continental sample: ocean=" + ocean + ", land=" + land);
    }

    @Test
    void actualNoiseColumnsAreIdenticalAcrossParallelWorkers() throws Exception {
        NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) RegistryHelper.registryAccess()
            .lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(PRESET).value().overworld().orElseThrow().generator();
        RandomState shared = this.state(6571L);
        LevelHeightAccessor height = LevelHeightAccessor.create(-64, 384);
        List<Integer> sequential = new ArrayList<>();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            int x = index * 997 - 11000;
            int z = index * -673 + 7000;
            sequential.add(generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, shared));
            tasks.add(() -> generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, shared));
        }
        try (java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<Integer>> results = workers.invokeAll(tasks);
            for (int index = 0; index < results.size(); index++) {
                assertEquals(sequential.get(index), results.get(index).get());
                assertTrue(sequential.get(index) >= -64 && sequential.get(index) <= 320);
            }
        }
        assertTrue(sequential.stream().distinct().count() > 1, "Terrain height should vary");
    }
}
