package org.nekopur.worldgen;

import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.jspecify.annotations.NullMarked;

/** Expands biome climate regions without changing any terrain density function. */
@NullMarked
final class NekoBiomeClimate {
    private static final int HORIZONTAL_FACTOR = 2;

    private NekoBiomeClimate() {
    }

    static Climate.Sampler broaden(Climate.Sampler original) {
        return new Climate.Sampler(broaden(original.temperature()), broaden(original.humidity()),
            original.continentalness(), original.erosion(), original.depth(), original.weirdness());
    }

    private static DensitySampler.Bound broaden(DensitySampler.Bound original) {
        return new HorizontalClimate(original.sampler()).bind(original.context());
    }

    private record HorizontalClimate(DensitySampler original) implements DensitySampler {
        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
            DensitySampler.sampleVolumeNaive(context, outputBuffer, volume, this);
        }

        @Override
        public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
            return original.sampleValue(context, Math.floorDiv(blockX, HORIZONTAL_FACTOR), blockY,
                Math.floorDiv(blockZ, HORIZONTAL_FACTOR));
        }
    }
}
