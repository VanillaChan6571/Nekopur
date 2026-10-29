package org.nekopur.worldgen;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Moves the vanilla fixed underground bands into the tall preset's additional depth. */
@NullMarked
public final class NekoUndergroundStructures {
    private NekoUndergroundStructures() {
    }

    public static int adjustStartHeight(Structure.GenerationContext context, Structure structure,
                                       HeightProvider provider, boolean projected, int sampledHeight) {
        if (projected || !isTall(context.chunkGenerator())) return sampledHeight;
        ResourceKey<Structure> key = context.registryAccess().lookupOrThrow(Registries.STRUCTURE)
            .getResourceKey(structure).orElse(null);
        return adjustStartHeight(context.chunkGenerator(), key, provider, projected, sampledHeight);
    }

    public static int adjustStartHeight(ChunkGenerator generator, @Nullable ResourceKey<Structure> key,
                                       HeightProvider provider, boolean projected, int sampledHeight) {
        if (projected || !isTall(generator)) return sampledHeight;
        boolean vanillaCity = BuiltinStructures.ANCIENT_CITY.equals(key)
            && provider instanceof ConstantHeight constant
            && VerticalAnchor.absolute(-27).equals(constant.getValue());
        boolean vanillaChambers = BuiltinStructures.TRIAL_CHAMBERS.equals(key)
            && provider instanceof UniformHeight uniform
            && VerticalAnchor.absolute(-40).equals(uniform.getMinInclusive())
            && VerticalAnchor.absolute(-20).equals(uniform.getMaxInclusive());
        return vanillaCity || vanillaChambers ? sampledHeight - 64 : sampledHeight;
    }

    private static boolean isTall(ChunkGenerator generator) {
        return generator instanceof NoiseBasedChunkGenerator noise
            && noise.generatorSettings().is(NekoWorldgenBootstrap.TALL_NOISE_SETTINGS);
    }
}
