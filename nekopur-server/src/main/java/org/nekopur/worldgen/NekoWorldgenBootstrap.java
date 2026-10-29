package org.nekopur.worldgen;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.jspecify.annotations.NullMarked;

/** Installs native worldgen after resource entries are bound and before registry freezing. */
@NullMarked
public final class NekoWorldgenBootstrap {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("nekopur", "neko");
    public static final ResourceKey<NoiseGeneratorSettings> NOISE_SETTINGS = ResourceKey.create(Registries.NOISE_SETTINGS, ID);
    public static final ResourceKey<WorldPreset> PRESET = ResourceKey.create(Registries.WORLD_PRESET, ID);
    public static final Identifier TALL_ID = Identifier.fromNamespaceAndPath("nekopur", "neko-mmo-tall");
    public static final ResourceKey<NoiseGeneratorSettings> TALL_NOISE_SETTINGS = ResourceKey.create(Registries.NOISE_SETTINGS, TALL_ID);
    public static final ResourceKey<WorldPreset> TALL_PRESET = ResourceKey.create(Registries.WORLD_PRESET, TALL_ID);
    public static final ResourceKey<DimensionType> TALL_DIMENSION = ResourceKey.create(Registries.DIMENSION_TYPE, TALL_ID);
    public static final ResourceKey<WorldPreset> NETHER_PRESET = ResourceKey.create(Registries.WORLD_PRESET, NekoNetherWorldgen.ID);
    public static final Identifier TALL_NETHER_ID = Identifier.fromNamespaceAndPath("nekopur", "neko-mmo-nether-tall");
    public static final ResourceKey<WorldPreset> TALL_NETHER_PRESET = ResourceKey.create(Registries.WORLD_PRESET, TALL_NETHER_ID);

    private NekoWorldgenBootstrap() {
    }

    public static String normalizeLevelType(String levelType) {
        if ("neko".equalsIgnoreCase(levelType)) return ID.toString();
        if ("neko-nether".equalsIgnoreCase(levelType)) return NekoNetherWorldgen.ID.toString();
        if ("neko-mmo-nether-tall".equalsIgnoreCase(levelType)) return TALL_NETHER_ID.toString();
        return "neko-mmo-tall".equalsIgnoreCase(levelType) ? TALL_ID.toString() : levelType;
    }

    public static void register(RegistryOps.RegistryInfoLookup context, List<? extends WritableRegistry<?>> registries) {
        WritableRegistry<NoiseGeneratorSettings> settings = null;
        WritableRegistry<WorldPreset> presets = null;
        WritableRegistry<DensityFunction> functions = null;
        WritableRegistry<NormalNoise> noises = null;
        WritableRegistry<DimensionType> dimensions = null;
        WritableRegistry<Biome> biomes = null;
        WritableRegistry<Feature> features = null;
        WritableRegistry<PlacedFeature> placedFeatures = null;
        for (WritableRegistry<?> registry : registries) {
            if (registry.key().equals(Registries.NOISE_SETTINGS)) {
                settings = castRegistry(registry);
            } else if (registry.key().equals(Registries.WORLD_PRESET)) {
                presets = castRegistry(registry);
            } else if (registry.key().equals(Registries.DENSITY_FUNCTION)) {
                functions = castRegistry(registry);
            } else if (registry.key().equals(Registries.NOISE)) {
                noises = castRegistry(registry);
            } else if (registry.key().equals(Registries.DIMENSION_TYPE)) {
                dimensions = castRegistry(registry);
            } else if (registry.key().equals(Registries.BIOME)) {
                biomes = castRegistry(registry);
            } else if (registry.key().equals(Registries.FEATURE)) {
                features = castRegistry(registry);
            } else if (registry.key().equals(Registries.PLACED_FEATURE)) {
                placedFeatures = castRegistry(registry);
            }
        }
        // Dimension/network/partial registry loads do not install world generation.
        if (settings == null || presets == null || functions == null || noises == null || dimensions == null
            || biomes == null || features == null || placedFeatures == null) {
            return;
        }
        if (settings.containsKey(ID) || presets.containsKey(ID)) {
            throw new IllegalStateException("The built-in worldgen key nekopur:neko is reserved; remove the conflicting worldgen resource");
        }
        if (settings.containsKey(TALL_ID) || presets.containsKey(TALL_ID) || dimensions.containsKey(TALL_ID)) {
            throw new IllegalStateException("The built-in worldgen key nekopur:neko-mmo-tall is reserved; remove the conflicting worldgen resource");
        }
        if (settings.containsKey(NekoNetherWorldgen.ID) || presets.containsKey(NekoNetherWorldgen.ID) || presets.containsKey(TALL_NETHER_ID)) {
            throw new IllegalStateException("The built-in Neko Nether worldgen keys are reserved; remove the conflicting worldgen resource");
        }
        WorldDimensions normal = presets.getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions();
        NoiseGeneratorSettings nativeSettings = TectonicTerrainFactory.build(
            functions,
            noises,
            context.lookup(Registries.MATERIAL_RULE).orElseThrow()
        );
        Holder.Reference<NoiseGeneratorSettings> nativeHolder = settings.register(NOISE_SETTINGS, nativeSettings, RegistrationInfo.BUILT_IN);
        MultiNoiseBiomeSource biomeSource = MultiNoiseBiomeSource.createFromPreset(context.lookup(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
            .orElseThrow().getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(biomeSource, nativeHolder);
        WorldPreset preset = new WorldPreset(WorldDimensions.withOverworld(normal.dimensions(),
            context.lookup(Registries.DIMENSION_TYPE).orElseThrow().getOrThrow(BuiltinDimensionTypes.OVERWORLD), generator));
        presets.register(PRESET, preset, RegistrationInfo.BUILT_IN);
        DimensionType overworld = context.lookup(Registries.DIMENSION_TYPE).orElseThrow()
            .getOrThrow(BuiltinDimensionTypes.OVERWORLD).value();
        DimensionType tall = new DimensionType(overworld.hasFixedTime(), overworld.hasSkyLight(), overworld.hasCeiling(),
            overworld.hasEnderDragonFight(), overworld.coordinateScale(), -128, 640, 640, overworld.infiniburn(),
            overworld.ambientLight(), overworld.monsterSettings(), overworld.skybox(), overworld.cardinalLightType(),
            overworld.attributes(), overworld.timelines(), overworld.defaultClock());
        Holder.Reference<DimensionType> tallDimension = dimensions.register(TALL_DIMENSION, tall, RegistrationInfo.BUILT_IN);
        ((MappedRegistry<DimensionType>) dimensions).bindValuesForNativeBootstrap();
        NoiseGeneratorSettings tallSettings = TectonicTerrainFactory.buildTall(functions, noises,
            context.lookup(Registries.MATERIAL_RULE).orElseThrow());
        Holder.Reference<NoiseGeneratorSettings> tallHolder = settings.register(TALL_NOISE_SETTINGS, tallSettings, RegistrationInfo.BUILT_IN);
        WorldPreset tallPreset = new WorldPreset(WorldDimensions.withOverworld(normal.dimensions(), tallDimension,
            new NoiseBasedChunkGenerator(biomeSource, tallHolder)));
        presets.register(TALL_PRESET, tallPreset, RegistrationInfo.BUILT_IN);
        NekoNetherBiomes.register(context, biomes, features, placedFeatures);
        ((MappedRegistry<Biome>) biomes).bindValuesForNativeBootstrap();
        NekoNetherTags.register(biomes);
        NoiseGeneratorSettings netherSettings = NekoNetherWorldgen.settings(settings.getOrThrow(NoiseGeneratorSettings.NETHER).value(),
            context.lookup(Registries.MATERIAL_RULE).orElseThrow(), context.lookup(Registries.MATERIAL_CONDITION).orElseThrow(),
            context.lookup(Registries.BIOME).orElseThrow());
        Holder.Reference<NoiseGeneratorSettings> netherHolder = settings.register(NekoNetherWorldgen.NOISE_SETTINGS, netherSettings, RegistrationInfo.BUILT_IN);
        LevelStem vanillaNether = normal.get(LevelStem.NETHER).orElseThrow();
        LevelStem nekoNether = new LevelStem(vanillaNether.type(), new NoiseBasedChunkGenerator(
            NekoNetherWorldgen.createBiomeSource(context.lookup(Registries.BIOME).orElseThrow()), netherHolder));
        presets.register(NETHER_PRESET, withNether(preset, nekoNether), RegistrationInfo.BUILT_IN);
        presets.register(TALL_NETHER_PRESET, withNether(tallPreset, nekoNether), RegistrationInfo.BUILT_IN);
    }

    private static WorldPreset withNether(WorldPreset preset, LevelStem nether) {
        java.util.Map<ResourceKey<LevelStem>, LevelStem> stems = new java.util.LinkedHashMap<>(preset.createWorldDimensions().dimensions());
        stems.put(LevelStem.NETHER, nether);
        return new WorldPreset(java.util.Map.copyOf(stems));
    }

    @SuppressWarnings("unchecked")
    private static <T> WritableRegistry<T> castRegistry(WritableRegistry<?> registry) {
        return (WritableRegistry<T>) registry;
    }
}
