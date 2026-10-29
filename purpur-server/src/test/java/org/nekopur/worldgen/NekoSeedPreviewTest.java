package org.nekopur.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nekopur.worldgen.preview.NekoSeedPreview;

@VanillaFeature
class NekoSeedPreviewTest {
    @TempDir Path folder;

    @Test void coordinatesAndSeedParsingMatchMinecraft() {
        NekoSeedPreview.Options options = NekoSeedPreview.Options.parse(new String[]{"--seed", "neko", "--size", "8", "--step", "16", "--x", "-17", "--z", "2"});
        assertEquals("neko".hashCode(), options.seed());
        assertEquals(-81, options.blockX(0)); assertEquals(31, options.blockX(7));
        assertEquals(-62, options.blockZ(0)); assertEquals(50, options.blockZ(7));
        assertThrows(IllegalArgumentException.class, () -> NekoSeedPreview.Options.parse(new String[]{"--mode", "other"}));
        assertThrows(IllegalArgumentException.class, () -> NekoSeedPreview.Options.parse(new String[]{"--seed", " "}));
        assertThrows(IllegalArgumentException.class, () -> NekoSeedPreview.Options.parse(new String[]{"--x", "30000000"}));
    }

    @Test void detailedSamplesMatchNativeColumnsAndBiomes() {
        var registries = RegistryHelper.registryAccess();
        NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(NekoWorldgenBootstrap.PRESET).value().overworld().orElseThrow().generator();
        RandomState random = RandomState.create(registries.lookupOrThrow(Registries.NOISE), 6571L, generator.generatorSettings().value());
        for (int x : new int[]{-10001, -1, 0, 9997}) {
            int z = -2317;
            var point = NekoSeedPreview.sample(registries, generator, random, SamplerContext.builder().enableCaches().build(), x, z, true);
            assertEquals(generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, LevelHeightAccessor.create(-64, 384), random) - 1, point.elevation());
            assertEquals(generator.getBiomeSource().createUncachedResolver(random).getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(Math.max(point.elevation(), 62)), QuartPos.fromBlock(z)).unwrapKey().orElseThrow().identifier().toString(), point.biome());
            assertEquals(point, NekoSeedPreview.sample(registries, generator, random, SamplerContext.builder().enableCaches().build(), x, z, true));
            var fast = NekoSeedPreview.sample(registries, generator, random, SamplerContext.builder().enableCaches().build(), x, z, false);
            var preliminary = registries.lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DENSITY_FUNCTION,
                net.minecraft.resources.Identifier.parse("nekopur:tectonic/minecraft/overworld/preliminary_surface_level"))).value();
            assertEquals(Math.round(random.samplersWithContext(SamplerContext.EMPTY_UNCACHED).sampleValue(preliminary, x, 0, z)), fast.elevation());
        }
    }

    @Test void exportsSelfContainedMapsWithoutLosingSeedPrecision() throws Exception {
        var options = new NekoSeedPreview.Options(Long.MAX_VALUE, 8, 16, 0, 0, 1, false, folder);
        int[] elevations = new int[64]; String[] biomes = new String[64];
        for (int i = 0; i < 64; i++) { elevations[i] = i + 32; biomes[i] = i < 32 ? "minecraft:ocean" : "minecraft:plains"; }
        NekoSeedPreview.writePreview(options, elevations, biomes, 63, folder);
        String html = Files.readString(folder.resolve("index.html"));
        assertTrue(html.contains("\"seed\":\"9223372036854775807\""));
        assertFalse(html.contains("__PREVIEW_DATA__"));
        assertTrue(html.contains("data:image/png;base64,"));
        for (String layer : new String[]{"terrain", "elevation", "biomes"}) assertEquals(8, javax.imageio.ImageIO.read(folder.resolve(layer + ".png").toFile()).getWidth());
        assertTrue(Files.readString(folder.resolve("samples.json")).contains("minecraft:ocean"));
    }
}
