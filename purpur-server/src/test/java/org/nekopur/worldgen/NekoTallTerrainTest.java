package org.nekopur.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.nekopur.worldgen.preview.NekoSeedPreview;

@VanillaFeature
@NullMarked
class NekoTallTerrainTest {
    @Test
    void deepDarkMovesIntoTheLowerCavesWithoutChangingSurfaceClimate() {
        var registries = RegistryHelper.registryAccess();
        NoiseBasedChunkGenerator generator = NekoSeedPreview.generator(registries, "neko-mmo-tall");
        RandomState random = RandomState.create(registries.lookupOrThrow(Registries.NOISE), 6571L, generator.generatorSettings().value());
        SamplerContext context = SamplerContext.builder().enableCaches().build();
        int deepDarkCount = 0, highestDeepDark = Integer.MIN_VALUE;
        int ordinaryCaveCount = 0;
        for (long seed : List.of(6571L, 42L, -987654321L)) {
            RandomState seedRandom = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, generator.generatorSettings().value());
            var resolver = generator.getBiomeSource().createResolver(seedRandom.createClimateSampler(SamplerContext.builder().enableCaches().build()));
            for (int z = -8192; z <= 8192; z += 512) {
                for (int x = -8192; x <= 8192; x += 512) {
                    for (int y = -120; y <= 64; y += 8) {
                        var biome = resolver.getNoiseBiome(x >> 2, y >> 2, z >> 2);
                        if (biome.is(net.minecraft.world.level.biome.Biomes.DEEP_DARK)) {
                            deepDarkCount++;
                            highestDeepDark = Math.max(highestDeepDark, y);
                            assertTrue(y < -32, "Deep Dark must stay below the tall profile's upper deep-layer boundary");
                        }
                        if (y >= -32 && (biome.is(net.minecraft.world.level.biome.Biomes.LUSH_CAVES)
                            || biome.is(net.minecraft.world.level.biome.Biomes.DRIPSTONE_CAVES))) ordinaryCaveCount++;
                    }
                }
            }
        }
        assertTrue(deepDarkCount > 0, "The lower layer must still contain Deep Dark");
        assertTrue(ordinaryCaveCount > 0, "Ordinary cave biomes must remain above the deep layer");
        var samplers = random.samplersWithContext(context);
        var functions = registries.lookupOrThrow(Registries.DENSITY_FUNCTION);
        var depth = functions.getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DENSITY_FUNCTION,
            net.minecraft.resources.Identifier.parse("nekopur:tectonic_tall/minecraft/overworld/depth"))).value();
        var rawDepth = functions.getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DENSITY_FUNCTION,
            net.minecraft.resources.Identifier.parse("nekopur:tectonic_tall/tectonic/depth"))).value();
        for (int y : List.of(-120, -96, -64, -48, -32, 0, 64, 320)) {
            float cap = y <= -96 ? 1.3F : y >= -32 ? 0.8F : 1.3F - (y + 96) * (0.5F / 64);
            assertEquals(Math.min(samplers.sampleValue(rawDepth, 0, y, 0), cap), samplers.sampleValue(depth, 0, y, 0), 0.00001F);
        }
        System.out.println("Tall underground biomes: " + deepDarkCount + " Deep Dark samples, highest Y=" + highestDeepDark);
    }

    @Test
    void tallPresetHasIndependentBoundsAndRoundTripsThroughSaveCodec() {
        var registries = RegistryHelper.registryAccess();
        WorldPreset tall = registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(NekoWorldgenBootstrap.TALL_PRESET).value();
        var normal = registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(NekoWorldgenBootstrap.PRESET).value();
        assertEquals(-128, tall.overworld().orElseThrow().type().value().minY());
        assertEquals(640, tall.overworld().orElseThrow().type().value().height());
        assertEquals(640, tall.overworld().orElseThrow().type().value().logicalHeight());
        assertEquals(-64, normal.overworld().orElseThrow().type().value().minY());
        assertEquals(384, normal.overworld().orElseThrow().type().value().height());
        for (String name : List.of("neko-mmo-tall", "Neko-MMO-Tall", "nekopur:neko-mmo-tall")) {
            var selected = new DedicatedServerProperties.WorldDimensionData(new JsonObject(), name).create(registries);
            assertTrue(((NoiseBasedChunkGenerator) selected.overworld()).generatorSettings().is(NekoWorldgenBootstrap.TALL_NOISE_SETTINGS));
        }
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        var encoded = WorldPreset.DIRECT_CODEC.encodeStart(ops, tall).getOrThrow();
        WorldPreset decoded = WorldPreset.DIRECT_CODEC.parse(ops, encoded).getOrThrow();
        assertTrue(decoded.overworld().orElseThrow().type().is(NekoWorldgenBootstrap.TALL_DIMENSION));
        assertSame(normal.createWorldDimensions().get(LevelStem.NETHER).orElseThrow(), tall.createWorldDimensions().get(LevelStem.NETHER).orElseThrow());
        assertSame(normal.createWorldDimensions().get(LevelStem.END).orElseThrow(), tall.createWorldDimensions().get(LevelStem.END).orElseThrow());
    }

    @Test
    void solidBlockTagsAndTallTerrainUseExtraSpace() {
        var registries = RegistryHelper.registryAccess();
        assertTrue(Blocks.STONE.defaultBlockState().is(BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP), "Standalone column sampling requires applied block tags");
        NoiseBasedChunkGenerator generator = NekoSeedPreview.generator(registries, "neko-mmo-tall");
        assertEquals(-128, generator.generatorSettings().value().noiseSettings().minY());
        assertEquals(640, generator.generatorSettings().value().noiseSettings().height());
        assertEquals(63, generator.generatorSettings().value().seaLevel());
        RandomState random = RandomState.create(registries.lookupOrThrow(Registries.NOISE), 6571L, generator.generatorSettings().value());
        SamplerContext context = SamplerContext.builder().enableCaches().build();
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        int lowX = 0, lowZ = 0, highX = 0, highZ = 0;
        int actualLowest = Integer.MAX_VALUE, deepCandidates = 0;
        for (int z = -32768; z < 32768; z += 512) {
            for (int x = -32768; x < 32768; x += 512) {
                int y = NekoSeedPreview.sample(registries, generator, random, context, x, z, false).elevation();
                if (y < lowest) { lowest = y; }
                if (y > highest) { highest = y; highX = x; highZ = z; }
                if (y < -64 && deepCandidates < 256) {
                    deepCandidates++;
                    int actual = NekoSeedPreview.sample(registries, generator, random, context, x, z, true).elevation();
                    if (actual < actualLowest) { actualLowest = actual; lowX = x; lowZ = z; }
                }
            }
        }
        assertTrue(lowest < -64, "Tall ocean shaping must use the deeper range: " + lowest);
        assertTrue(highest > 319, "Tall mountains must use the higher range: " + highest);
        var deep = NekoSeedPreview.sample(registries, generator, random, context, lowX, lowZ, true);
        var peak = NekoSeedPreview.sample(registries, generator, random, context, highX, highZ, true);
        assertTrue(deep.elevation() >= -128 && deep.elevation() < -64, "Actual deep ocean column: " + deep.elevation() + "; estimated minimum=" + lowest + " at " + lowX + "," + lowZ);
        assertTrue(peak.elevation() > 319 && peak.elevation() < 512, "Actual tall mountain column: " + peak.elevation());
        var deepColumn = generator.getBaseColumn(lowX, lowZ, LevelHeightAccessor.create(-128, 640), random);
        assertTrue(deepColumn.getBlock(-60).is(Blocks.WATER), "Deep ocean must contain water below the old lava floor");
        System.out.println("Neko tall seed 6571: floor=" + deep.elevation() + " at " + lowX + "," + lowZ
            + "; peak=" + peak.elevation() + " at " + highX + "," + highZ);
    }
}
