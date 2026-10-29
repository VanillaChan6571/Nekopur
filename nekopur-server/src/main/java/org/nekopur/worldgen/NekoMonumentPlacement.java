package org.nekopur.worldgen;

import java.util.Arrays;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.jspecify.annotations.NullMarked;

/** Seabed placement for monuments in the deeper tall-profile oceans. */
@NullMarked
public final class NekoMonumentPlacement {
    private NekoMonumentPlacement() {
    }

    public static int baseY(ChunkGenerator generator, LevelHeightAccessor height, RandomState random, int west, int north) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)
            || !noise.generatorSettings().is(NekoWorldgenBootstrap.TALL_NOISE_SETTINGS)) return 39;
        int[] floors = new int[9];
        int index = 0;
        // Median of the footprint avoids one ravine or shoreline spike moving
        // the entire building; its existing supports reach uneven ground.
        for (int z : new int[]{0, 29, 57}) {
            for (int x : new int[]{0, 29, 57}) {
                floors[index++] = generator.getBaseHeight(west + x, north + z, Heightmap.Types.OCEAN_FLOOR_WG, height, random) - 1;
            }
        }
        Arrays.sort(floors);
        // The 23-block building remains submerged and above bottom bedrock.
        return Math.clamp(floors[4], height.getMinY() + 8, generator.getSeaLevel() - 24);
    }
}
