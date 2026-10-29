package org.nekopur.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import java.util.ArrayDeque;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
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
class NekoForestBiomesTest {
    @Test
    void nativeCodecRetainsExpansionAndLegacyTallPresetsUpgradeOnce() {
        var registries = RegistryHelper.registryAccess();
        var vanilla = MultiNoiseBiomeSource.createFromPreset(registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
            .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        var tall = NekoSeedPreview.generator(registries, "neko-mmo-tall");
        var normal = NekoSeedPreview.generator(registries, "neko");
        assertSame(vanilla, NekoForestBiomes.adapt(vanilla, normal.generatorSettings()));
        assertInstanceOf(NekoForestBiomes.class, tall.getBiomeSource());
        var upgraded = new NoiseBasedChunkGenerator(vanilla, tall.generatorSettings());
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        var saved = BiomeSource.CODEC.encodeStart(ops, upgraded.getBiomeSource()).getOrThrow();
        assertEquals("nekopur:forest_regions", saved.getAsJsonObject().get("type").getAsString());
        BiomeSource decoded = BiomeSource.CODEC.parse(ops, saved).getOrThrow();
        assertSame(decoded, NekoForestBiomes.adapt(decoded, tall.generatorSettings()));
        assertEquals(saved, BiomeSource.CODEC.encodeStart(ops, decoded).getOrThrow());
        RandomState random = RandomState.create(registries.lookupOrThrow(Registries.NOISE), 6571L, tall.generatorSettings().value());
        var sampler = random.createClimateSampler(SamplerContext.builder().enableCaches().build());
        var direct = decoded.createResolver(sampler);
        // Check the production chunk resolver throughout the tall vertical range.
        for (int qx : new int[]{-257, -1, 0, 257}) {
            var bulk = decoded.createResolverForChunk(sampler, qx, -32, qx, 4, 160, 4);
            for (int y = -32; y < 128; y++) {
                for (int x = qx; x < qx + 4; x++) {
                    for (int z = qx; z < qx + 4; z++) {
                        assertEquals(direct.getNoiseBiome(x, y, z), bulk.getNoiseBiome(x, y, z));
                    }
                }
            }
        }
    }

    @Test
    void forestCoverageAndConnectedRegionsGrowAcrossSeedsWithoutTakingOceansOrCaves() {
        var registries = RegistryHelper.registryAccess();
        var generator = NekoSeedPreview.generator(registries, "neko-mmo-tall");
        var baseline = MultiNoiseBiomeSource.createFromPreset(registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
            .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        long[] counts = new long[6];
        int[] largest = new int[6];
        long savannaToDappled = 0, darkToPale = 0, retainedSavanna = 0, retainedDark = 0;
        double oldClimateDelta = 0, broadClimateDelta = 0;
        long started = System.nanoTime();
        for (long seed : new long[]{6571L, 42L, -987654321L, 6449995202838594431L}) {
            RandomState random = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, generator.generatorSettings().value());
            SamplerContext context = SamplerContext.builder().enableCaches().build();
            var sampler = random.createClimateSampler(context);
            var broad = NekoBiomeClimate.broaden(sampler);
            var oldResolver = baseline.createResolver(sampler);
            var broadResolver = MultiNoiseBiomeSource.createFromList(new net.minecraft.world.level.biome.Climate.ParameterList<>(
                baseline.parameters().values())).createResolver(broad);
            var newResolver = generator.getBiomeSource().createResolver(sampler);
            int size = 256;
            boolean[][] maps = new boolean[6][size * size];
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    int bx = -8192 + x * 64, bz = -8192 + z * 64;
                    int elevation = NekoSeedPreview.sample(registries, generator, random, context, bx, bz, false).elevation();
                    int qy = Math.max(62, elevation) >> 2;
                    var oldBiome = oldResolver.getNoiseBiome(bx >> 2, qy, bz >> 2);
                    var broadBiome = broadResolver.getNoiseBiome(bx >> 2, qy, bz >> 2);
                    var newBiome = newResolver.getNoiseBiome(bx >> 2, qy, bz >> 2);
                    // Freeze the previous narrow-band rules as the comparison fixture.
                    float temperature = sampler.temperature().sampleValue(bx, qy << 2, bz);
                    boolean legacyDappled = oldBiome.is(Biomes.DAPPLED_FOREST)
                        || oldBiome.is(Biomes.FOREST) && temperature >= -0.15F && temperature <= -0.09F;
                    boolean legacyPale = oldBiome.is(Biomes.PALE_GARDEN)
                        || oldBiome.is(Biomes.DARK_FOREST) && temperature >= 0.08F && temperature <= 0.13F;
                    maps[0][z * size + x] = oldBiome.is(Biomes.DAPPLED_FOREST);
                    maps[1][z * size + x] = legacyDappled;
                    maps[2][z * size + x] = newBiome.is(Biomes.DAPPLED_FOREST);
                    maps[3][z * size + x] = oldBiome.is(Biomes.PALE_GARDEN);
                    maps[4][z * size + x] = legacyPale;
                    maps[5][z * size + x] = newBiome.is(Biomes.PALE_GARDEN);
                    if (isWater(oldBiome) || isCave(oldBiome)) {
                        assertEquals(oldBiome, newBiome, "Preserved placement at " + bx + "," + bz);
                    }
                    assertEquals(isWater(oldBiome), isWater(newBiome), "Water footprint must be unchanged");
                    assertEquals(isCave(oldBiome), isCave(newBiome), "Cave footprint must be unchanged");
                    if (isSavanna(broadBiome) && newBiome.is(Biomes.DAPPLED_FOREST)) savannaToDappled++;
                    if (broadBiome.is(Biomes.DARK_FOREST) && newBiome.is(Biomes.PALE_GARDEN)) darkToPale++;
                    if (isSavanna(newBiome)) retainedSavanna++;
                    if (newBiome.is(Biomes.DARK_FOREST)) retainedDark++;
                    for (var pair : new net.minecraft.world.level.levelgen.densityfunction.DensitySampler.Bound[][]{
                        {sampler.temperature(), broad.temperature()}, {sampler.humidity(), broad.humidity()}}) {
                        oldClimateDelta += Math.abs(pair[0].sampleValue(bx, 0, bz) - pair[0].sampleValue(bx + 64, 0, bz))
                            + Math.abs(pair[0].sampleValue(bx, 0, bz) - pair[0].sampleValue(bx, 0, bz + 64));
                        broadClimateDelta += Math.abs(pair[1].sampleValue(bx, 0, bz) - pair[1].sampleValue(bx + 64, 0, bz))
                            + Math.abs(pair[1].sampleValue(bx, 0, bz) - pair[1].sampleValue(bx, 0, bz + 64));
                    }
                    if (x % 8 == 0 && z % 8 == 0) {
                        for (int y : new int[]{-112, -80, -48, 0}) {
                            var oldCave = oldResolver.getNoiseBiome(bx >> 2, y >> 2, bz >> 2);
                            var newCave = newResolver.getNoiseBiome(bx >> 2, y >> 2, bz >> 2);
                            assertEquals(isCave(oldCave), isCave(newCave));
                            if (isCave(oldCave)) assertEquals(oldCave, newCave);
                        }
                    }
                }
            }
            for (int i = 0; i < 6; i++) {
                for (boolean present : maps[i]) if (present) counts[i]++;
                largest[i] = Math.max(largest[i], largestRegion(maps[i], size));
            }
        }
        System.out.println("Forest samples vanilla/previous/new: Dappled=" + counts[0] + "/" + counts[1] + "/" + counts[2]
            + ", Pale=" + counts[3] + "/" + counts[4] + "/" + counts[5]
            + "; largest connected 64-block sample regions=" + java.util.Arrays.toString(largest)
            + "; savanna replacements=" + savannaToDappled + "; dark forest replacements=" + darkToPale
            + "; retained savanna/dark forest=" + retainedSavanna + "/" + retainedDark
            + "; climate delta ratio=" + broadClimateDelta / oldClimateDelta
            + "; measurement seconds=" + (System.nanoTime() - started) / 1.0E9);
        assertTrue(counts[2] > counts[1], "Dappled gains area over the previous narrow rules");
        assertTrue(counts[5] > counts[4], "Pale Garden gains area over the previous narrow rules");
        assertTrue(largest[2] > largest[1], "Dappled gains larger contiguous regions");
        assertTrue(largest[5] > largest[4], "Pale Garden gains larger contiguous regions");
        assertTrue(counts[2] < 262144 / 5, "Dappled must not dominate the world");
        assertTrue(counts[5] < 262144 / 5, "Pale Garden must not dominate the world");
        assertTrue(savannaToDappled > 0 && darkToPale > 0, "Both requested replacements must occur");
        assertTrue(retainedSavanna > 0 && retainedDark > 0, "Retain acacia and dark forest variety");
        assertTrue(broadClimateDelta / oldClimateDelta < 0.85, "Reduce abrupt temperature/humidity variation");
    }

    private static boolean isWater(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER);
    }

    private static boolean isSavanna(Holder<Biome> biome) {
        return biome.is(Biomes.SAVANNA) || biome.is(Biomes.SAVANNA_PLATEAU);
    }

    private static boolean isCave(Holder<Biome> biome) {
        return biome.is(Biomes.DEEP_DARK) || biome.is(Biomes.LUSH_CAVES) || biome.is(Biomes.DRIPSTONE_CAVES) || biome.is(Biomes.SULFUR_CAVES);
    }

    private static int largestRegion(boolean[] map, int size) {
        boolean[] seen = new boolean[map.length];
        int largest = 0;
        for (int start = 0; start < map.length; start++) {
            if (!map[start] || seen[start]) continue;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            seen[start] = true;
            int area = 0;
            while (!queue.isEmpty()) {
                int index = queue.removeFirst();
                area++;
                int x = index % size, z = index / size;
                for (int next : new int[]{x > 0 ? index - 1 : -1, x + 1 < size ? index + 1 : -1,
                    z > 0 ? index - size : -1, z + 1 < size ? index + size : -1}) {
                    if (next >= 0 && map[next] && !seen[next]) {
                        seen[next] = true;
                        queue.addLast(next);
                    }
                }
            }
            largest = Math.max(largest, area);
        }
        return largest;
    }
}
