package org.nekopur.worldgen;

import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.BlockColumnFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.ReplaceBlobsFeature;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.CountOnEveryLayerPlacement;
import net.minecraft.world.level.levelgen.placement.OffsetPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.jspecify.annotations.NullMarked;

/** Uses vanilla feature codecs: saved chunks and registry packets need no custom client code. */
@NullMarked
public final class NekoNetherFeatures {
    public static final ResourceKey<PlacedFeature> SOULWOOD_BONE_SPIRES = placedKey("soulwood_bone_spires");
    public static final ResourceKey<PlacedFeature> ASHEN_BASALT_STUMPS = placedKey("ashen_basalt_stumps");
    public static final ResourceKey<PlacedFeature> SHORE_OBSIDIAN_PATCHES = placedKey("shore_obsidian_patches");

    private NekoNetherFeatures() {
    }

    public static void register(WritableRegistry<Feature> features, WritableRegistry<PlacedFeature> placedFeatures) {
        Holder<Feature> bone = features.register(featureKey("soulwood_bone_spires"),
            BlockColumnFeature.simple(UniformInt.of(2, 5), BlockStateProvider.of(Blocks.BONE_BLOCK)), RegistrationInfo.BUILT_IN);
        placedFeatures.register(SOULWOOD_BONE_SPIRES, new PlacedFeature(bone, List.of(
            CountOnEveryLayerPlacement.of(2),
            BlockPredicateFilter.forPredicate(BlockPredicate.allOf(BlockPredicate.ONLY_IN_AIR_PREDICATE,
                BlockPredicate.matchesBlocks(Direction.DOWN, Blocks.SOUL_SOIL, Blocks.SOUL_SAND, Blocks.WARPED_NYLIUM))),
            BiomeFilter.biome())), RegistrationInfo.BUILT_IN);

        Holder<Feature> basalt = features.register(featureKey("ashen_basalt_stumps"),
            BlockColumnFeature.simple(UniformInt.of(1, 4), BlockStateProvider.of(Blocks.BASALT)), RegistrationInfo.BUILT_IN);
        placedFeatures.register(ASHEN_BASALT_STUMPS, new PlacedFeature(basalt, List.of(
            CountOnEveryLayerPlacement.of(3),
            BlockPredicateFilter.forPredicate(BlockPredicate.allOf(BlockPredicate.ONLY_IN_AIR_PREDICATE,
                BlockPredicate.matchesBlocks(Direction.DOWN, Blocks.BASALT, Blocks.BLACKSTONE, Blocks.NETHERRACK))),
            BiomeFilter.biome())), RegistrationInfo.BUILT_IN);

        Holder<Feature> obsidian = features.register(featureKey("shore_obsidian_patches"),
            new ReplaceBlobsFeature(Blocks.BLACKSTONE.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState(), UniformInt.of(2, 4)),
            RegistrationInfo.BUILT_IN);
        placedFeatures.register(SHORE_OBSIDIAN_PATCHES, new PlacedFeature(obsidian, List.of(
            CountOnEveryLayerPlacement.of(2),
            BlockPredicateFilter.forPredicate(BlockPredicate.matchesBlocks(Direction.DOWN, Blocks.BLACKSTONE)),
            OffsetPlacement.of(0, -1, 0), BiomeFilter.biome())), RegistrationInfo.BUILT_IN);
    }

    private static ResourceKey<Feature> featureKey(String name) {
        return ResourceKey.create(Registries.FEATURE, Identifier.fromNamespaceAndPath("nekopur", name));
    }

    private static ResourceKey<PlacedFeature> placedKey(String name) {
        return ResourceKey.create(Registries.PLACED_FEATURE, Identifier.fromNamespaceAndPath("nekopur", name));
    }
}
