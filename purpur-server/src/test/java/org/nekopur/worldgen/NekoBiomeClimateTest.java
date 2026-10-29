package org.nekopur.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.datafixers.util.Pair;
import java.util.List;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@NullMarked
class NekoBiomeClimateTest {
    @Test
    void independentClimateTablePreventsBroadLookupsFromChangingOriginalTies() {
        Climate.ParameterList<String> original = new Climate.ParameterList<>(List.of(
            Pair.of(Climate.parameters(-1.0F, 0, 0, 0, 0, 0, 0), "cold"),
            Pair.of(Climate.parameters(1.0F, 0, 0, 0, 0, 0, 0), "warm")
        ));
        Climate.TargetPoint cold = Climate.target(-1.0F, 0, 0, 0, 0, 0);
        Climate.TargetPoint warm = Climate.target(1.0F, 0, 0, 0, 0, 0);
        Climate.TargetPoint equalDistance = Climate.target(0, 0, 0, 0, 0, 0);

        // A shared RTree retains its cached leaf when both entries are equally close.
        assertEquals("cold", original.findValue(cold));
        assertEquals("cold", original.findValue(equalDistance));
        assertEquals("warm", original.findValue(warm));
        assertEquals("warm", original.findValue(equalDistance));

        // The broad climate table shares immutable entries, but owns its own index/cache.
        Climate.ParameterList<String> broad = new Climate.ParameterList<>(original.values());
        assertEquals("cold", original.findValue(cold));
        assertEquals("cold", original.findValue(equalDistance));
        assertEquals("warm", broad.findValue(warm));
        assertEquals("warm", broad.findValue(equalDistance));
        assertEquals("cold", original.findValue(equalDistance), "A broad lookup must not influence the original tied selection");
    }

    @Test
    void broadensOnlyClimateAndRetainsOriginalContexts() {
        SamplerContext temperatureContext = SamplerContext.builder().enableCaches().build();
        SamplerContext humidityContext = SamplerContext.builder().enableCaches().build();
        Climate.Sampler original = original(temperatureContext, humidityContext);
        Climate.Sampler broad = NekoBiomeClimate.broaden(original);
        assertNotSame(original.temperature(), broad.temperature());
        assertNotSame(original.humidity(), broad.humidity());
        assertSame(temperatureContext, broad.temperature().context());
        assertSame(humidityContext, broad.humidity().context());
        assertSame(original.continentalness(), broad.continentalness());
        assertSame(original.erosion(), broad.erosion());
        assertSame(original.depth(), broad.depth());
        assertSame(original.weirdness(), broad.weirdness());
        for (int x : new int[]{-7, -3, -1, 0, 1, 3, 7}) {
            for (int z : new int[]{-9, -5, -1, 0, 1, 5, 9}) {
                for (int y : new int[]{-128, -1, 0, 63, 511}) {
                    assertEquals(original.temperature().sampleValue(Math.floorDiv(x, 2), y, Math.floorDiv(z, 2)),
                        broad.temperature().sampleValue(x, y, z));
                    assertEquals(original.humidity().sampleValue(Math.floorDiv(x, 2), y, Math.floorDiv(z, 2)),
                        broad.humidity().sampleValue(x, y, z));
                }
            }
        }
    }

    @Test
    void bulkSamplingMatchesScalarAcrossNegativeAndPositiveOddCoordinates() {
        SamplerContext context = SamplerContext.builder().enableCaches().build();
        Climate.Sampler broad = NekoBiomeClimate.broaden(original(context, context));
        for (DensitySampler.Bound sampler : new DensitySampler.Bound[]{broad.temperature(), broad.humidity()}) {
            for (DensityVolume volume : new DensityVolume[]{new DensityVolume(9, 3, 7, -5, -7, -3, 1, 5, 1),
                new DensityVolume(5, 4, 6, -9, 64, -11, 3, 7, 5)}) {
                DensityBuffer bulk = DensityBuffer.createUnpooled(volume.size());
                sampler.sampleVolume(bulk, volume);
                for (int z = 0; z < volume.sizeZ(); z++) {
                    for (int x = 0; x < volume.sizeX(); x++) {
                        for (int y = 0; y < volume.sizeY(); y++) {
                            assertEquals(sampler.sampleValue(volume.blockX(x), volume.blockY(y), volume.blockZ(z)),
                                bulk.get(volume.indexUnchecked(x, y, z)));
                        }
                    }
                }
            }
        }
    }

    private static Climate.Sampler original(SamplerContext temperatureContext, SamplerContext humidityContext) {
        DensitySampler.Bound temperature = new PositionSampler(1.0F).bind(temperatureContext);
        DensitySampler.Bound humidity = new PositionSampler(-2.0F).bind(humidityContext);
        return new Climate.Sampler(temperature, humidity, new PositionSampler(3.0F).bind(temperatureContext),
            new PositionSampler(4.0F).bind(temperatureContext), new PositionSampler(5.0F).bind(temperatureContext),
            new PositionSampler(6.0F).bind(temperatureContext));
    }

    private record PositionSampler(float multiplier) implements DensitySampler {
        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
            DensitySampler.sampleVolumeNaive(context, outputBuffer, volume, this);
        }

        @Override
        public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
            return multiplier * (blockX * 10000.0F + blockY * 100.0F + blockZ);
        }
    }
}
