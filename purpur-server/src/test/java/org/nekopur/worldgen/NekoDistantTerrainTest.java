package org.nekopur.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.nekopur.worldgen.preview.NekoSeedPreview;

@VanillaFeature
@NullMarked
class NekoDistantTerrainTest {
    private static final int[][] POINTS = {{-12800, -31232}, {-17408, -29184}, {-1, -1}, {16, -17}};

    @Test
    void columnsMatchNativeNoiseIncludingDeepOceanAndTallMountains() {
        for (String preset : List.of("neko", "neko-mmo-tall")) {
            NoiseBasedChunkGenerator generator = NekoSeedPreview.generator(RegistryHelper.registryAccess(), preset);
            RandomState random = RandomState.create(RegistryHelper.registryAccess().lookupOrThrow(Registries.NOISE), 6571L, generator.generatorSettings().value());
            int minY = generator.generatorSettings().value().noiseSettings().minY();
            int height = generator.generatorSettings().value().noiseSettings().height();
            NekoDistantTerrain.Snapshot snapshot = NekoDistantTerrain.snapshot(generator, random, minY, height);
            NekoDistantTerrain.Sampler sampler = snapshot.newSampler();
            assertEquals(minY, snapshot.minY());
            assertEquals(minY + height - 1, snapshot.maxY());
            assertEquals(63, snapshot.seaLevel());
            assertEquals(NekoSeedPreview.normalizePreset(preset), snapshot.preset());
            for (int[] point : POINTS) {
                NekoDistantTerrain.Column sampled = sampler.sampleColumn(point[0], point[1]);
                net.minecraft.world.level.NoiseColumn expected = generator.getBaseColumn(point[0], point[1], LevelHeightAccessor.create(minY, height), random);
                int top = minY - 1;
                int solid = top;
                for (int y = minY; y < minY + height; y++) {
                    var state = expected.getBlock(y);
                    assertEquals(state.asBlockData().getAsString(), sampled.blockState(y), preset + " at Y=" + y);
                    if (!state.isAir()) top = y;
                    if (Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(state)) solid = y;
                }
                assertEquals(top, sampled.topY());
                assertEquals(solid, sampled.highestSolidY());
                var resolver = generator.getBiomeSource().createResolver(random.createClimateSampler(SamplerContext.builder().enableCaches().build()));
                assertEquals(resolver.getNoiseBiome(QuartPos.fromBlock(point[0]), QuartPos.fromBlock(Math.max(solid, 62)), QuartPos.fromBlock(point[1]))
                    .unwrapKey().orElseThrow().identifier().toString(), sampled.surfaceBiome());
                String original = sampled.blockState(minY);
                sampled.blockStates()[0] = "minecraft:diamond_block";
                assertEquals(original, sampled.blockState(minY), "Exported arrays must not mutate cached columns");
                assertEquals("minecraft:air", sampled.blockState(minY - 1));
                assertEquals("minecraft:air", sampled.blockState(minY + height));
                if (preset.equals("neko-mmo-tall") && point == POINTS[0]) {
                    assertTrue(sampled.highestSolidY() < -64, "Tall ocean must retain its deep floor");
                    assertEquals("minecraft:water[level=0]", sampled.blockState(-60));
                }
                if (preset.equals("neko-mmo-tall") && point == POINTS[1]) {
                    assertTrue(sampled.highestSolidY() > 319, "Tall peaks must not be clipped to vanilla height");
                }
            }
            assertThrows(IllegalArgumentException.class, () -> sampler.sampleColumn(Integer.MIN_VALUE, 0));
            assertThrows(IllegalArgumentException.class, () -> NekoDistantTerrain.snapshot(generator, random, minY, height - 16));
        }
    }

    @Test
    void independentWorkerContextsMatchSerialAndRejectSharedSampler() throws Exception {
        NoiseBasedChunkGenerator generator = NekoSeedPreview.generator(RegistryHelper.registryAccess(), "neko-mmo-tall");
        RandomState random = RandomState.create(RegistryHelper.registryAccess().lookupOrThrow(Registries.NOISE), 6571L, generator.generatorSettings().value());
        NekoDistantTerrain.Snapshot snapshot = NekoDistantTerrain.snapshot(generator, random, -128, 640);
        NekoDistantTerrain.Sampler serial = snapshot.newSampler();
        List<NekoDistantTerrain.Column> expected = new ArrayList<>();
        for (int[] point : POINTS) expected.add(serial.sampleColumn(point[0], point[1]));
        try (var workers = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Callable<NekoDistantTerrain.Column>> tasks = new ArrayList<>();
            for (int[] point : POINTS) tasks.add(() -> snapshot.newSampler().sampleColumn(point[0], point[1]));
            var results = workers.invokeAll(tasks);
            for (int i = 0; i < results.size(); i++) {
                NekoDistantTerrain.Column result = results.get(i).get();
                assertArrayEquals(expected.get(i).blockStates(), result.blockStates());
                assertEquals(expected.get(i).surfaceBiome(), result.surfaceBiome());
            }
            workers.submit(() -> assertThrows(IllegalStateException.class, () -> serial.sampleColumn(0, 0))).get();
        }
    }

    @Test
    void batchedColumnsMatchScalarAcrossUnalignedAquiferAndChunkBoundaries() {
        for (String preset : List.of("neko", "neko-mmo-tall")) {
            NoiseBasedChunkGenerator generator = NekoSeedPreview.generator(RegistryHelper.registryAccess(), preset);
            RandomState random = RandomState.create(RegistryHelper.registryAccess().lookupOrThrow(Registries.NOISE), 6571L, generator.generatorSettings().value());
            int minY = generator.generatorSettings().value().noiseSettings().minY();
            int height = generator.generatorSettings().value().noiseSettings().height();
            NekoDistantTerrain.Sampler sampler = NekoDistantTerrain.snapshot(generator, random, minY, height).newSampler();
            for (int[] origin : new int[][] {{-12803, -31235}, {-17411, -29187}, {-17, -17}}) {
                NekoDistantTerrain.Column[] batch = sampler.sampleTile(origin[0], origin[1], 16);
                assertEquals(256, batch.length);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        assertColumnEquals(sampler.sampleColumn(origin[0] + x, origin[1] + z), batch[x * 16 + z],
                            preset + " at " + (origin[0] + x) + "," + (origin[1] + z));
                    }
                }
            }
            assertThrows(IllegalArgumentException.class, () -> sampler.sampleTile(0, 0, 32));
            assertThrows(IllegalArgumentException.class, () -> sampler.sampleTile(29_999_980, 0, 16));
            assertThrows(IllegalArgumentException.class, () -> sampler.sampleTile(Integer.MAX_VALUE, 0, 64));
        }
    }

    @Test
    void fullTileMatchesScalarWithBoundedBatchesAndReportsColdTimings() {
        NoiseBasedChunkGenerator generator = NekoSeedPreview.generator(RegistryHelper.registryAccess(), "neko-mmo-tall");
        RandomState batchRandom = RandomState.create(RegistryHelper.registryAccess().lookupOrThrow(Registries.NOISE), 42L, generator.generatorSettings().value());
        RandomState scalarRandom = RandomState.create(RegistryHelper.registryAccess().lookupOrThrow(Registries.NOISE), 42L, generator.generatorSettings().value());
        NekoDistantTerrain.Sampler batched = NekoDistantTerrain.snapshot(generator, batchRandom, -128, 640).newSampler();
        NekoDistantTerrain.Sampler scalar = NekoDistantTerrain.snapshot(generator, scalarRandom, -128, 640).newSampler();
        long start = System.nanoTime();
        NekoDistantTerrain.Column[] tile = batched.sampleTile(-33, -65, 64);
        double batchSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
        assertEquals(4096, tile.length);
        start = System.nanoTime();
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                assertColumnEquals(scalar.sampleColumn(-33 + x, -65 + z), tile[x * 64 + z], "64-tile " + x + "," + z);
            }
        }
        double scalarSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
        System.out.printf("Distant terrain cold 64x64 tall tile: batched %.3fs, scalar %.3fs (scalar time includes equality checks)%n", batchSeconds, scalarSeconds);
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class, () -> batched.sampleTile(0, 0, 16));
        } finally {
            Thread.interrupted();
        }
    }

    private static void assertColumnEquals(NekoDistantTerrain.Column expected, NekoDistantTerrain.Column actual, String coordinate) {
        assertArrayEquals(expected.blockStates(), actual.blockStates(), coordinate);
        assertEquals(expected.topY(), actual.topY(), coordinate);
        assertEquals(expected.highestSolidY(), actual.highestSolidY(), coordinate);
        assertEquals(expected.surfaceBiome(), actual.surfaceBiome(), coordinate);
    }

    @Test
    void estimatedSurfaceSamplesSavedGraphAtNegativeCellCentersWithoutHeightBias() {
        for (String preset : List.of("neko", "neko-mmo-tall")) {
            NoiseBasedChunkGenerator generator = NekoSeedPreview.generator(RegistryHelper.registryAccess(), preset);
            RandomState random = RandomState.create(RegistryHelper.registryAccess().lookupOrThrow(Registries.NOISE), 6571L, generator.generatorSettings().value());
            int minY = generator.generatorSettings().value().noiseSettings().minY();
            int height = generator.generatorSettings().value().noiseSettings().height();
            NekoDistantTerrain.Snapshot snapshot = NekoDistantTerrain.snapshot(generator, random, minY, height);
            NekoDistantTerrain.Sampler sampler = snapshot.newSampler();
            SamplerContext context = SamplerContext.builder().enableCaches().build();
            var surface = random.samplersWithContext(context).get(generator.generatorSettings().value().aquifers().orElseThrow().surfaceLevel());
            var resolver = generator.getBiomeSource().createResolver(random.createClimateSampler(context));
            for (int stride : new int[] {1, 2, 4, 8, 16}) {
                NekoDistantTerrain.SurfaceColumn[] first = sampler.sampleSurfaceTile(-1031, -2055, stride);
                NekoDistantTerrain.SurfaceColumn[] repeated = sampler.sampleSurfaceTile(-1031, -2055, stride);
                assertArrayEquals(first, repeated, "Surface estimates must be deterministic at stride " + stride);
                assertEquals(4096, first.length);
                for (int x : new int[] {0, 1, 31, 63}) {
                    for (int z : new int[] {0, 1, 31, 63}) {
                        int blockX = -1031 + x * stride + stride / 2;
                        int blockZ = -2055 + z * stride + stride / 2;
                        int expectedSolid = Math.clamp(Math.round(surface.sampleValue(blockX, 0, blockZ)), minY, minY + height - 1);
                        NekoDistantTerrain.SurfaceColumn actual = first[x * 64 + z];
                        assertEquals(expectedSolid, actual.highestSolidY());
                        assertEquals(Math.max(expectedSolid, 62), actual.topY());
                        assertEquals(minY, actual.minY());
                        assertEquals(NekoDistantTerrain.Sampler.estimatedCover(actual.surfaceBiome(), expectedSolid, "minecraft:stone"), actual.surfaceMaterial());
                        assertEquals(expectedSolid < 62 ? "minecraft:water[level=0]" : "minecraft:air", actual.fluidMaterial());
                        assertEquals(resolver.getNoiseBiome(QuartPos.fromBlock(blockX), QuartPos.fromBlock(Math.max(expectedSolid, 62)), QuartPos.fromBlock(blockZ))
                            .unwrapKey().orElseThrow().identifier().toString(), actual.surfaceBiome());
                    }
                }
            }
            assertThrows(IllegalArgumentException.class, () -> sampler.sampleSurfaceTile(0, 0, 3));
            assertThrows(IllegalArgumentException.class, () -> sampler.sampleSurfaceTile(29_999_000, 0, 16));
            assertThrows(IllegalArgumentException.class, () -> sampler.sampleSurfaceTile(Integer.MIN_VALUE, 0, 1));
        }
    }

    @Test
    void estimatedTallSurfaceRetainsDeepOceanAndHighMountainsAndReportsCoarseTiming() throws Exception {
        NoiseBasedChunkGenerator generator = NekoSeedPreview.generator(RegistryHelper.registryAccess(), "neko-mmo-tall");
        RandomState random = RandomState.create(RegistryHelper.registryAccess().lookupOrThrow(Registries.NOISE), 6571L, generator.generatorSettings().value());
        NekoDistantTerrain.Sampler sampler = NekoDistantTerrain.snapshot(generator, random, -128, 640).newSampler();
        long start = System.nanoTime();
        NekoDistantTerrain.SurfaceColumn[] oceans = sampler.sampleSurfaceTile(-12800 - 520, -31232 - 520, 16);
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        NekoDistantTerrain.SurfaceColumn ocean = oceans[32 * 64 + 32];
        assertTrue(ocean.highestSolidY() < -64, "Coarse tall ocean prediction must retain the deeper profile");
        assertEquals(62, ocean.topY());
        assertEquals("minecraft:water[level=0]", ocean.fluidMaterial());
        NekoDistantTerrain.SurfaceColumn[] peaks = sampler.sampleSurfaceTile(-17408 - 520, -29184 - 520, 16);
        assertTrue(peaks[32 * 64 + 32].highestSolidY() > 319, "Coarse tall mountains must not clip to vanilla height");
        for (NekoDistantTerrain.SurfaceColumn column : peaks) {
            assertTrue(column.highestSolidY() >= -128 && column.topY() <= 511);
        }
        System.out.printf("Distant surface 64x64 stride16: %.3fs, covers 1024x1024 blocks / 4096 Minecraft chunks (256 full 64-block tiles), estimates not final columns%n", seconds);
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> assertThrows(IllegalStateException.class, () -> sampler.sampleSurfaceTile(0, 0, 16))).get();
        }
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class, () -> sampler.sampleSurfaceTile(0, 0, 16));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void estimatedCoverColorsSurfaceWhileKeepingUndergroundAndRockyTerrainNative() {
        assertEquals("minecraft:grass_block", NekoDistantTerrain.Sampler.estimatedCover("minecraft:dappled_forest", 90, "minecraft:stone"));
        assertEquals("minecraft:grass_block", NekoDistantTerrain.Sampler.estimatedCover("minecraft:pale_garden", 90, "minecraft:stone"));
        for (String biome : List.of("minecraft:ocean", "minecraft:beach", "minecraft:desert")) {
            assertEquals("minecraft:sand", NekoDistantTerrain.Sampler.estimatedCover(biome, 60, "minecraft:stone"));
        }
        assertEquals("minecraft:red_sand", NekoDistantTerrain.Sampler.estimatedCover("minecraft:badlands", 90, "minecraft:stone"));
        assertEquals("minecraft:snow_block", NekoDistantTerrain.Sampler.estimatedCover("minecraft:snowy_plains", 90, "minecraft:stone"));
        for (String biome : List.of("minecraft:stony_shore", "minecraft:stony_peaks", "minecraft:windswept_hills")) {
            assertEquals("minecraft:stone", NekoDistantTerrain.Sampler.estimatedCover(biome, 90, "minecraft:stone"));
        }
        assertEquals("minecraft:stone", NekoDistantTerrain.Sampler.estimatedCover("minecraft:ocean", -80, "minecraft:stone"));
        assertEquals("minecraft:deepslate", NekoDistantTerrain.Sampler.estimatedCover("minecraft:forest", 90, "minecraft:deepslate"));
    }

    @Test
    void accurateSparseSurfaceMatchesNativeColumnsAtOceanMountainAndNegativeCenters() throws Exception {
        for (String preset : List.of("neko", "neko-mmo-tall")) {
            NoiseBasedChunkGenerator generator = NekoSeedPreview.generator(RegistryHelper.registryAccess(), preset);
            RandomState random = RandomState.create(RegistryHelper.registryAccess().lookupOrThrow(Registries.NOISE), 6571L, generator.generatorSettings().value());
            int minY = generator.generatorSettings().value().noiseSettings().minY();
            int height = generator.generatorSettings().value().noiseSettings().height();
            NekoDistantTerrain.Sampler sampler = NekoDistantTerrain.snapshot(generator, random, minY, height).newSampler();
            NekoDistantTerrain.Snapshot snapshot = NekoDistantTerrain.snapshot(generator, random, minY, height);
            try (var batchWorker = Executors.newSingleThreadExecutor(); var biomeWorker = Executors.newSingleThreadExecutor()) {
            for (int stride : new int[] {2, 4, 8, 16}) {
                int batchSize = 16 / stride;
                int[] indices = java.util.stream.IntStream.of(0, 1, batchSize - 1, batchSize, batchSize + 1, 31, 32, 63).distinct().toArray();
                for (int[] center : new int[][] {POINTS[0], POINTS[1], {-17, -33}}) {
                    int[] origin = {center[0] - 32 * stride - stride / 2, center[1] - 32 * stride - stride / 2};
                    long start = System.nanoTime();
                    NekoDistantTerrain.SurfaceColumn[] tile = batchWorker.submit(() -> snapshot.newSampler().sampleAccurateSurfaceTile(origin[0], origin[1], stride)).get();
                    double batchSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
                    String[] expectedBiomes = biomeWorker.submit(() -> {
                        var resolver = generator.getBiomeSource().createResolver(random.createClimateSampler(SamplerContext.builder().enableCaches().build()));
                        String[] result = new String[4096];
                        for (int x = 0; x < 64; x++) {
                            for (int z = 0; z < 64; z++) {
                                int index = x * 64 + z;
                                result[index] = resolver.getNoiseBiome(QuartPos.fromBlock(origin[0] + x * stride + stride / 2),
                                    QuartPos.fromBlock(Math.max(tile[index].highestSolidY(), snapshot.seaLevel() - 1)),
                                    QuartPos.fromBlock(origin[1] + z * stride + stride / 2)).unwrapKey().orElseThrow().identifier().toString();
                            }
                        }
                        return result;
                    }).get();
                    for (int index = 0; index < 4096; index++) assertEquals(expectedBiomes[index], tile[index].surfaceBiome(), "Native biome traversal at index " + index);
                    System.out.printf("Accurate distant surface %s stride%d: %.3fs for 4096 centers near %d,%d%n", preset, stride, batchSeconds, center[0], center[1]);
                    assertEquals(4096, tile.length);
                    for (int x : indices) {
                        for (int z : indices) {
                            int blockX = origin[0] + x * stride + stride / 2;
                            int blockZ = origin[1] + z * stride + stride / 2;
                            assertAccurateSurfaceEqualsScalar(generator, random, sampler, minY, height, blockX, blockZ, tile[x * 64 + z]);
                        }
                    }
                    if (preset.equals("neko-mmo-tall") && center == POINTS[0]) {
                        assertTrue(tile[32 * 64 + 32].highestSolidY() < -64);
                        assertEquals("minecraft:water[level=0]", tile[32 * 64 + 32].fluidMaterial());
                    }
                    if (preset.equals("neko-mmo-tall") && center == POINTS[1]) {
                        assertTrue(tile[32 * 64 + 32].highestSolidY() > 319);
                        assertEquals("minecraft:air", tile[32 * 64 + 32].fluidMaterial());
                    }
                    // Compare every sparse center for the dense requested grid, independently of timing assertions.
                    if (stride == 2 && center == POINTS[0]) {
                        start = System.nanoTime();
                        for (int x = 0; x < 64; x++) {
                            for (int z = 0; z < 64; z++) {
                                assertAccurateSurfaceEqualsScalar(generator, random, sampler, minY, height,
                                    origin[0] + x * stride + stride / 2, origin[1] + z * stride + stride / 2, tile[x * 64 + z]);
                            }
                        }
                        System.out.printf("Accurate sparse %s stride2: batch %.3fs, scalar %.3fs (scalar includes equality checks)%n",
                            preset, batchSeconds, (System.nanoTime() - start) / 1_000_000_000.0);
                    }
                }
            }
            }
            assertThrows(IllegalArgumentException.class, () -> sampler.sampleAccurateSurfaceTile(0, 0, 3));
            assertThrows(IllegalArgumentException.class, () -> sampler.sampleAccurateSurfaceTile(29_999_000, 0, 16));
            try (var worker = Executors.newSingleThreadExecutor()) {
                worker.submit(() -> assertThrows(IllegalStateException.class, () -> sampler.sampleAccurateSurfaceTile(0, 0, 16))).get();
            }
            Thread.currentThread().interrupt();
            try {
                assertThrows(java.util.concurrent.CancellationException.class, () -> sampler.sampleAccurateSurfaceTile(0, 0, 16));
            } finally {
                Thread.interrupted();
            }
        }
    }

    private static void assertAccurateSurfaceEqualsScalar(NoiseBasedChunkGenerator generator, RandomState random,
        NekoDistantTerrain.Sampler sampler, int minY, int height, int blockX, int blockZ, NekoDistantTerrain.SurfaceColumn actual) {
        NekoDistantTerrain.Column nativeColumn = sampler.sampleColumn(blockX, blockZ);
        String coordinate = blockX + "," + blockZ;
        assertEquals(nativeColumn.minY(), actual.minY(), coordinate);
        assertEquals(nativeColumn.topY(), actual.topY(), "Actual top height at " + coordinate);
        assertEquals(nativeColumn.highestSolidY(), actual.highestSolidY(), coordinate);
        // Biomes are verified separately in identical traversal on clean worker threads: native climate ties retain the previous candidate.
        assertEquals(NekoDistantTerrain.Sampler.estimatedCover(actual.surfaceBiome(), nativeColumn.highestSolidY(),
            nativeColumn.blockState(nativeColumn.highestSolidY())), actual.surfaceMaterial(), coordinate);
        var expected = generator.getBaseColumn(blockX, blockZ, LevelHeightAccessor.create(minY, height), random);
        var top = expected.getBlock(actual.topY());
        assertEquals(top.getFluidState().isEmpty() ? "minecraft:air" : top.asBlockData().getAsString(), actual.fluidMaterial(), coordinate);
    }

}
