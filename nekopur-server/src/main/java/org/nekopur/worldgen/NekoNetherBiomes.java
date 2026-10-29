package org.nekopur.worldgen;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BiomeDefaultFeatures;
import net.minecraft.data.worldgen.Carvers;
import net.minecraft.data.worldgen.placement.MiscOverworldPlacements;
import net.minecraft.data.worldgen.placement.NetherPlacements;
import net.minecraft.data.worldgen.placement.OrePlacements;
import net.minecraft.data.worldgen.placement.TreePlacements;
import net.minecraft.data.worldgen.placement.VegetationPlacements;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ARGB;
import net.minecraft.world.attribute.AmbientAdditionsSettings;
import net.minecraft.world.attribute.AmbientMoodSettings;
import net.minecraft.world.attribute.AmbientParticle;
import net.minecraft.world.attribute.AmbientSounds;
import net.minecraft.world.attribute.BackgroundMusic;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.attribute.modifier.ListModifier;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.jspecify.annotations.NullMarked;

/** Native Nether biomes, composed entirely of vanilla blocks, mobs, sounds and feature codecs. */
@NullMarked
public final class NekoNetherBiomes {
    public static final ResourceKey<Biome> ASHEN_BARRENS = key("ashen_barrens");
    public static final ResourceKey<Biome> EMBER_GROVE = key("ember_grove");
    public static final ResourceKey<Biome> SOULWOOD_HOLLOW = key("soulwood_hollow");
    public static final ResourceKey<Biome> OBSIDIAN_SHORES = key("obsidian_shores");

    private NekoNetherBiomes() {
    }

    public static void register(RegistryOps.RegistryInfoLookup context, WritableRegistry<Biome> biomes,
        WritableRegistry<Feature> features, WritableRegistry<PlacedFeature> placedFeatures) {
        NekoNetherFeatures.register(features, placedFeatures);
        HolderGetter<PlacedFeature> placements = placedFeatures.createRegistrationLookup();
        HolderGetter<WorldCarver> carvers = context.lookup(Registries.CARVER).orElseThrow();
        biomes.register(ASHEN_BARRENS, ashenBarrens(placements, carvers), RegistrationInfo.BUILT_IN);
        biomes.register(EMBER_GROVE, emberGrove(placements, carvers), RegistrationInfo.BUILT_IN);
        biomes.register(SOULWOOD_HOLLOW, soulwoodHollow(placements, carvers), RegistrationInfo.BUILT_IN);
        biomes.register(OBSIDIAN_SHORES, obsidianShores(placements, carvers), RegistrationInfo.BUILT_IN);
    }

    private static Biome.BiomeBuilder base(int fog) {
        return new Biome.BiomeBuilder().hasPrecipitation(false).temperature(2.0F).downfall(0.0F)
            .specialEffects(new BiomeSpecialEffects.Builder().waterColor(0x3f76e4).build())
            .setAttribute(EnvironmentAttributes.FOG_COLOR, ARGB.vector3fFromRGB24(fog));
    }

    private static BiomeGenerationSettings.Builder generation(HolderGetter<PlacedFeature> placements, HolderGetter<WorldCarver> carvers) {
        BiomeGenerationSettings.Builder generation = new BiomeGenerationSettings.Builder(placements, carvers)
            .addCarver(Carvers.NETHER_CAVE)
            .addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, MiscOverworldPlacements.SPRING_LAVA);
        BiomeDefaultFeatures.addDefaultMushrooms(generation);
        return generation;
    }

    /** Shared features follow the same order as vanilla Nether biomes to avoid feature sorting cycles. */
    private static void underground(BiomeGenerationSettings.Builder generation) {
        generation.addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, NetherPlacements.SPRING_OPEN)
            .addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, NetherPlacements.PATCH_FIRE)
            .addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, NetherPlacements.PATCH_SOUL_FIRE)
            .addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, NetherPlacements.GLOWSTONE_EXTRA)
            .addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, NetherPlacements.GLOWSTONE)
            .addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, VegetationPlacements.BROWN_MUSHROOM_NETHER)
            .addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, VegetationPlacements.RED_MUSHROOM_NETHER)
            .addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, OrePlacements.ORE_MAGMA)
            .addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, NetherPlacements.SPRING_CLOSED);
        BiomeDefaultFeatures.addNetherDefaultOres(generation);
    }

    private static Biome ashenBarrens(HolderGetter<PlacedFeature> placements, HolderGetter<WorldCarver> carvers) {
        BiomeGenerationSettings.Builder generation = generation(placements, carvers)
            .addFeature(GenerationStep.Decoration.LOCAL_MODIFICATIONS, NekoNetherFeatures.ASHEN_BASALT_STUMPS)
            .addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, NetherPlacements.BASALT_BLOBS)
            .addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, NetherPlacements.BLACKSTONE_BLOBS);
        underground(generation);
        return base(0x51474b)
            .modifyAttribute(EnvironmentAttributes.AMBIENT_PARTICLES, ListModifier.append(), AmbientParticle.of(ParticleTypes.WHITE_ASH, 0.06F))
            .setAttribute(EnvironmentAttributes.BACKGROUND_MUSIC, new BackgroundMusic(SoundEvents.MUSIC_BIOME_BASALT_DELTAS))
            .setAttribute(EnvironmentAttributes.AMBIENT_SOUNDS, new AmbientSounds(Optional.of(SoundEvents.AMBIENT_BASALT_DELTAS_LOOP),
                Optional.of(new AmbientMoodSettings(SoundEvents.AMBIENT_BASALT_DELTAS_MOOD, 6000, 8, 2.0)),
                List.of(new AmbientAdditionsSettings(SoundEvents.AMBIENT_BASALT_DELTAS_ADDITIONS, 0.0111))))
            .mobSpawnSettings(new MobSpawnSettings.Builder().addSpawn(EntityTypes.MAGMA_CUBE, 35, 1, 3)
                .addSpawn(EntityTypes.GHAST, 15, 1, 2).addSpawn(EntityTypes.SKELETON, 20, 1, 3)
                .addSpawn(EntityTypes.ZOMBIFIED_PIGLIN, 20, 2, 4).addSpawn(EntityTypes.STRIDER, 60, 1, 2).build())
            .generationSettings(generation.build()).build();
    }

    private static Biome emberGrove(HolderGetter<PlacedFeature> placements, HolderGetter<WorldCarver> carvers) {
        BiomeGenerationSettings.Builder generation = generation(placements, carvers);
        underground(generation);
        generation.addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, NetherPlacements.WEEPING_VINES)
            .addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, TreePlacements.CRIMSON_FUNGI)
            .addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, NetherPlacements.CRIMSON_FOREST_VEGETATION);
        return base(0x632619)
            .modifyAttribute(EnvironmentAttributes.AMBIENT_PARTICLES, ListModifier.append(), AmbientParticle.of(ParticleTypes.CRIMSON_SPORE, 0.035F))
            .setAttribute(EnvironmentAttributes.BACKGROUND_MUSIC, new BackgroundMusic(SoundEvents.MUSIC_BIOME_CRIMSON_FOREST))
            .setAttribute(EnvironmentAttributes.AMBIENT_SOUNDS, new AmbientSounds(Optional.of(SoundEvents.AMBIENT_CRIMSON_FOREST_LOOP),
                Optional.of(new AmbientMoodSettings(SoundEvents.AMBIENT_CRIMSON_FOREST_MOOD, 6000, 8, 2.0)),
                List.of(new AmbientAdditionsSettings(SoundEvents.AMBIENT_CRIMSON_FOREST_ADDITIONS, 0.0111))))
            .mobSpawnSettings(new MobSpawnSettings.Builder().addSpawn(EntityTypes.HOGLIN, 9, 3, 4)
                .addSpawn(EntityTypes.PIGLIN, 7, 2, 4).addSpawn(EntityTypes.ZOMBIFIED_PIGLIN, 2, 2, 4)
                .addSpawn(EntityTypes.MAGMA_CUBE, 3, 1, 2).addSpawn(EntityTypes.STRIDER, 60, 1, 2).build())
            .generationSettings(generation.build()).build();
    }

    private static Biome soulwoodHollow(HolderGetter<PlacedFeature> placements, HolderGetter<WorldCarver> carvers) {
        BiomeGenerationSettings.Builder generation = generation(placements, carvers)
            .addFeature(GenerationStep.Decoration.LOCAL_MODIFICATIONS, NekoNetherFeatures.SOULWOOD_BONE_SPIRES);
        underground(generation);
        generation.addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, TreePlacements.WARPED_FUNGI)
            .addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, NetherPlacements.WARPED_FOREST_VEGETATION)
            .addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, NetherPlacements.NETHER_SPROUTS)
            .addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, NetherPlacements.TWISTING_VINES);
        return base(0x234d54)
            .modifyAttribute(EnvironmentAttributes.AMBIENT_PARTICLES, ListModifier.append(), AmbientParticle.of(ParticleTypes.ASH, 0.015F))
            .setAttribute(EnvironmentAttributes.BACKGROUND_MUSIC, new BackgroundMusic(SoundEvents.MUSIC_BIOME_WARPED_FOREST))
            .setAttribute(EnvironmentAttributes.AMBIENT_SOUNDS, new AmbientSounds(Optional.of(SoundEvents.AMBIENT_SOUL_SAND_VALLEY_LOOP),
                Optional.of(new AmbientMoodSettings(SoundEvents.AMBIENT_SOUL_SAND_VALLEY_MOOD, 6000, 8, 2.0)),
                List.of(new AmbientAdditionsSettings(SoundEvents.AMBIENT_SOUL_SAND_VALLEY_ADDITIONS, 0.0111))))
            .mobSpawnSettings(new MobSpawnSettings.Builder().addSpawn(EntityTypes.SKELETON, 20, 2, 4)
                .addSpawn(EntityTypes.ENDERMAN, 8, 1, 3).addSpawn(EntityTypes.GHAST, 5, 1, 1)
                .addSpawn(EntityTypes.STRIDER, 60, 1, 2).addMobSpawnCost(EntityTypes.ENDERMAN, 0.7, 0.15)
                .addMobSpawnCost(EntityTypes.GHAST, 0.7, 0.15).build())
            .generationSettings(generation.build()).build();
    }

    private static Biome obsidianShores(HolderGetter<PlacedFeature> placements, HolderGetter<WorldCarver> carvers) {
        BiomeGenerationSettings.Builder generation = generation(placements, carvers)
            .addFeature(GenerationStep.Decoration.LOCAL_MODIFICATIONS, NekoNetherFeatures.SHORE_OBSIDIAN_PATCHES)
            .addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, NetherPlacements.BLACKSTONE_BLOBS);
        underground(generation);
        return base(0x302739)
            .modifyAttribute(EnvironmentAttributes.AMBIENT_PARTICLES, ListModifier.append(), AmbientParticle.of(ParticleTypes.ASH, 0.004F))
            .setAttribute(EnvironmentAttributes.BACKGROUND_MUSIC, new BackgroundMusic(SoundEvents.MUSIC_BIOME_NETHER_WASTES))
            .setAttribute(EnvironmentAttributes.AMBIENT_SOUNDS, new AmbientSounds(Optional.of(SoundEvents.AMBIENT_NETHER_WASTES_LOOP),
                Optional.of(new AmbientMoodSettings(SoundEvents.AMBIENT_NETHER_WASTES_MOOD, 6000, 8, 2.0)),
                List.of(new AmbientAdditionsSettings(SoundEvents.AMBIENT_NETHER_WASTES_ADDITIONS, 0.008))))
            .mobSpawnSettings(new MobSpawnSettings.Builder().addSpawn(EntityTypes.PIGLIN, 12, 2, 4)
                .addSpawn(EntityTypes.ZOMBIFIED_PIGLIN, 60, 2, 4).addSpawn(EntityTypes.MAGMA_CUBE, 6, 1, 2)
                .addSpawn(EntityTypes.GHAST, 10, 1, 2).addSpawn(EntityTypes.STRIDER, 90, 1, 3).build())
            .generationSettings(generation.build()).build();
    }

    private static ResourceKey<Biome> key(String name) {
        return ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("nekopur", name));
    }
}
