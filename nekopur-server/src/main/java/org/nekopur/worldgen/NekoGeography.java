package org.nekopur.worldgen;

import org.jspecify.annotations.NullMarked;

/** Stable geography constants for the first Neko worldgen profile. */
@NullMarked
public final class NekoGeography {
    private static final float TECTONIC_OCEAN_OFFSET = -0.8F;
    private static final float NEKO_OCEAN_BIAS = -0.05F;

    private NekoGeography() {
    }

    /** Applied before continent-dependent terrain splines and biome climate sampling. */
    public static float oceanOffset() {
        return TECTONIC_OCEAN_OFFSET + NEKO_OCEAN_BIAS;
    }
}
