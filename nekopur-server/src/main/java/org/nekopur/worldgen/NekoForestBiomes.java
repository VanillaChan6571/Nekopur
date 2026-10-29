package org.nekopur.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.jspecify.annotations.NullMarked;

/** Broader surface climate regions and forest variants, preserving water and cave placement. */
@NullMarked
public final class NekoForestBiomes extends BiomeSource {
    public static final MapCodec<NekoForestBiomes> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
        BiomeSource.CODEC.fieldOf("source").forGetter(value -> value.source),
        Biome.CODEC.fieldOf("dappled").forGetter(value -> value.dappled),
        Biome.CODEC.fieldOf("pale").forGetter(value -> value.pale)
    ).apply(instance, NekoForestBiomes::new));
    private final BiomeSource source;
    private final BiomeSource surfaceSource;
    private final Holder<Biome> dappled;
    private final Holder<Biome> pale;

    private NekoForestBiomes(BiomeSource source, Holder<Biome> dappled, Holder<Biome> pale) {
        this.source = source;
        // ParameterList caches the previous nearest leaf. Broad climate lookups
        // must not influence vanilla water/cave tie selection on the same thread.
        this.surfaceSource = source instanceof MultiNoiseBiomeSource multi
            ? MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(multi.parameters().values())) : source;
        this.dappled = dappled;
        this.pale = pale;
    }

    public static BiomeSource adapt(BiomeSource source, Holder<NoiseGeneratorSettings> settings) {
        if (!settings.is(NekoWorldgenBootstrap.TALL_NOISE_SETTINGS)
            || !(source instanceof MultiNoiseBiomeSource multi)
            || !multi.stable(MultiNoiseBiomeSourceParameterLists.OVERWORLD)) return source;
        Optional<Holder<Biome>> dappled = source.possibleBiomes().stream().filter(value -> value.is(Biomes.DAPPLED_FOREST)).findFirst();
        Optional<Holder<Biome>> pale = source.possibleBiomes().stream().filter(value -> value.is(Biomes.PALE_GARDEN)).findFirst();
        if (dappled.isEmpty() || pale.isEmpty()) return source;
        return new NekoForestBiomes(source, dappled.orElseThrow(), pale.orElseThrow());
    }

    @Override
    protected MapCodec<NekoForestBiomes> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.concat(source.possibleBiomes().stream(), Stream.of(dappled, pale));
    }

    @Override
    public BiomeResolver createResolver(Climate.Sampler sampler) {
        Climate.Sampler broad = NekoBiomeClimate.broaden(sampler);
        return decorate(source.createResolver(sampler), surfaceSource.createResolver(broad), broad);
    }

    @Override
    public BiomeResolver createResolverForChunk(Climate.Sampler sampler, int x, int y, int z, int sizeX, int sizeY, int sizeZ) {
        Climate.Sampler broad = NekoBiomeClimate.broaden(sampler);
        return decorate(source.createResolverForChunk(sampler, x, y, z, sizeX, sizeY, sizeZ),
            surfaceSource.createResolverForChunk(broad, x, y, z, sizeX, sizeY, sizeZ), broad);
    }

    private BiomeResolver decorate(BiomeResolver original, BiomeResolver surface, Climate.Sampler broad) {
        return (x, y, z) -> {
            Holder<Biome> unchanged = original.getNoiseBiome(x, y, z);
            if (preservePlacement(unchanged)) return unchanged;
            Holder<Biome> biome = surface.getNoiseBiome(x, y, z);
            // A surface climate change must not introduce new caves or water biomes.
            if (preservePlacement(biome)) return unchanged;
            boolean dappledCandidate = biome.is(Biomes.FOREST) || biome.is(Biomes.SAVANNA) || biome.is(Biomes.SAVANNA_PLATEAU);
            if (!dappledCandidate && !biome.is(Biomes.DARK_FOREST)) return biome;
            // Shift a slower seeded field away from the local humidity window so
            // dry savannas and humid dark forests can both gain large territories.
            // Four times the original horizontal wavelength; independent of Y.
            float region = broad.humidity().sampleValue(Math.floorDiv(QuartPos.toBlock(x), 2) + 10000, 0,
                Math.floorDiv(QuartPos.toBlock(z), 2) - 10000);
            if (dappledCandidate && region >= 0.0F) return dappled;
            if (biome.is(Biomes.DARK_FOREST) && region >= -0.08F) return pale;
            return biome;
        };
    }

    private static boolean preservePlacement(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)
            || biome.is(Biomes.DEEP_DARK) || biome.is(Biomes.LUSH_CAVES)
            || biome.is(Biomes.DRIPSTONE_CAVES) || biome.is(Biomes.SULFUR_CAVES);
    }
}
