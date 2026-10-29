package org.nekopur.worldgen;

import com.mojang.datafixers.util.Pair;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.material.VanillaMaterialConditions;
import net.minecraft.data.worldgen.material.VanillaMaterialRules;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.material.MaterialRules;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.jspecify.annotations.NullMarked;

/** Custom Nether regions and surfaces inside the unchanged vanilla terrain volume. */
@NullMarked
public final class NekoNetherWorldgen {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("nekopur", "neko-nether");
    public static final ResourceKey<NoiseGeneratorSettings> NOISE_SETTINGS = ResourceKey.create(Registries.NOISE_SETTINGS, ID);

    private NekoNetherWorldgen() { }

    /** Native climate fields determine contiguous regions; there is no per-chunk random replacement. */
    public static BiomeSource createBiomeSource(HolderGetter<Biome> biomes) {
        return MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(List.of(
            point(biomes, NekoNetherBiomes.ASHEN_BARRENS, -0.23F, -0.23F, 0.0F),
            point(biomes, NekoNetherBiomes.EMBER_GROVE, 0.23F, -0.23F, 0.0F),
            point(biomes, NekoNetherBiomes.SOULWOOD_HOLLOW, 0.23F, 0.23F, 0.0F),
            point(biomes, NekoNetherBiomes.OBSIDIAN_SHORES, -0.23F, 0.23F, 0.0F),
            point(biomes, Biomes.NETHER_WASTES, 0.0F, 0.0F, 0.0F),
            point(biomes, Biomes.SOUL_SAND_VALLEY, 0.0F, -0.65F, 0.075F),
            point(biomes, Biomes.CRIMSON_FOREST, 0.65F, 0.0F, 0.075F),
            point(biomes, Biomes.WARPED_FOREST, 0.0F, 0.65F, 0.075F),
            point(biomes, Biomes.BASALT_DELTAS, -0.65F, 0.0F, 0.075F)
        )));
    }

    private static Pair<Climate.ParameterPoint, Holder<Biome>> point(HolderGetter<Biome> biomes,
        ResourceKey<Biome> key, float temperature, float humidity, float offset) {
        return Pair.of(Climate.parameters(temperature, humidity, 0, 0, 0, 0, offset), biomes.getOrThrow(key));
    }

    public static NoiseGeneratorSettings settings(NoiseGeneratorSettings vanilla, HolderGetter<MaterialRule> rules,
        HolderGetter<MaterialCondition> conditions, HolderGetter<Biome> biomes) {
        MaterialCondition floor = MaterialRules.getCondition(conditions, VanillaMaterialConditions.ON_FLOOR);
        MaterialCondition underFloor = MaterialRules.getCondition(conditions, VanillaMaterialConditions.UNDER_FLOOR);
        MaterialCondition ceiling = MaterialRules.getCondition(conditions, VanillaMaterialConditions.UNDER_CEILING);
        MaterialCondition aboveLava = MaterialRules.yBlockCheck(VerticalAnchor.absolute(vanilla.seaLevel()), 0);
        MaterialCondition nearLava = MaterialRules.not(MaterialRules.yBlockCheck(VerticalAnchor.absolute(vanilla.seaLevel() + 16), 0));
        MaterialCondition patch = MaterialRules.noiseCondition2d(Noises.NETHER_STATE_SELECTOR, 0.15);
        MaterialCondition nylium = MaterialRules.noiseCondition2d(Noises.NETHERRACK, -0.20);
        MaterialRule ash = MaterialRules.sequence(
            MaterialRules.ifTrue(ceiling, state(Blocks.BASALT)),
            MaterialRules.ifTrue(floor, MaterialRules.sequence(MaterialRules.ifTrue(patch, state(Blocks.GRAVEL)), state(Blocks.BASALT))),
            MaterialRules.ifTrue(underFloor, state(Blocks.BLACKSTONE))
        );
        MaterialRule ember = MaterialRules.sequence(
            MaterialRules.ifTrue(floor, MaterialRules.ifTrue(aboveLava,
                MaterialRules.sequence(MaterialRules.ifTrue(patch, state(Blocks.MAGMA_BLOCK)), state(Blocks.CRIMSON_NYLIUM))))
        );
        MaterialRule soulwood = MaterialRules.sequence(
            MaterialRules.ifTrue(floor, MaterialRules.sequence(
                MaterialRules.ifTrue(aboveLava, MaterialRules.ifTrue(nylium, state(Blocks.WARPED_NYLIUM))), state(Blocks.SOUL_SOIL))),
            MaterialRules.ifTrue(underFloor, MaterialRules.sequence(MaterialRules.ifTrue(patch, state(Blocks.SOUL_SAND)), state(Blocks.SOUL_SOIL)))
        );
        MaterialRule shores = MaterialRules.sequence(
            MaterialRules.ifTrue(ceiling, state(Blocks.BASALT)),
            MaterialRules.ifTrue(floor, MaterialRules.sequence(
                MaterialRules.ifTrue(nearLava, MaterialRules.ifTrue(patch, state(Blocks.OBSIDIAN))), state(Blocks.BLACKSTONE))),
            MaterialRules.ifTrue(underFloor, state(Blocks.BLACKSTONE))
        );
        MaterialRule material = MaterialRules.sequence(
            // The original bedrock gradients run before any custom surface treatment.
            MaterialRules.getRule(rules, VanillaMaterialRules.BEDROCK_FLOOR),
            MaterialRules.getRule(rules, VanillaMaterialRules.BEDROCK_ROOF),
            MaterialRules.ifTrue(MaterialRules.yBlockCheck(VerticalAnchor.belowTop(5), 0), state(Blocks.NETHERRACK)),
            // Preserve vanilla lava pockets in exposed floors below the lava surface.
            MaterialRules.ifTrue(MaterialRules.isBiome(biomes, NekoNetherBiomes.ASHEN_BARRENS, NekoNetherBiomes.EMBER_GROVE,
                NekoNetherBiomes.SOULWOOD_HOLLOW, NekoNetherBiomes.OBSIDIAN_SHORES),
                MaterialRules.ifTrue(floor, MaterialRules.ifTrue(MaterialRules.not(aboveLava),
                    MaterialRules.ifTrue(MaterialRules.hole(), state(Blocks.LAVA))))),
            MaterialRules.ifTrue(MaterialRules.isBiome(biomes, NekoNetherBiomes.ASHEN_BARRENS), ash),
            MaterialRules.ifTrue(MaterialRules.isBiome(biomes, NekoNetherBiomes.EMBER_GROVE), ember),
            MaterialRules.ifTrue(MaterialRules.isBiome(biomes, NekoNetherBiomes.SOULWOOD_HOLLOW), soulwood),
            MaterialRules.ifTrue(MaterialRules.isBiome(biomes, NekoNetherBiomes.OBSIDIAN_SHORES), shores),
            new MaterialRule.HolderHolder(vanilla.materialRule())
        );
        // Density, lava, aquifers, randomness and vertical extent are copied exactly.
        return new NoiseGeneratorSettings(vanilla.noiseSettings(), vanilla.defaultBlock(), vanilla.defaultFluid(),
            vanilla.noiseRouter(), Holder.direct(material), vanilla.spawnTarget(), vanilla.seaLevel(), vanilla.disableMobGeneration(),
            vanilla.aquifers(), vanilla.useLegacyRandomSource(), vanilla.debugFunctions());
    }

    private static MaterialRule state(Block block) {
        return MaterialRules.state(block.defaultBlockState());
    }
}
