package org.nekopur.worldgen.preview;

import com.google.gson.GsonBuilder;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.nekopur.worldgen.NekoWorldgenBootstrap;
import org.jspecify.annotations.NullMarked;

/** Offline overview of native noise terrain; never opens or writes a Minecraft world. */
@NullMarked
public final class NekoSeedPreview {
    private NekoSeedPreview() {}

    public record Options(long seed, int size, int step, int x, int z, int threads, boolean detailed, Path output, String preset) {
        public Options(long seed, int size, int step, int x, int z, int threads, boolean detailed, Path output) {
            this(seed, size, step, x, z, threads, detailed, output, NekoWorldgenBootstrap.ID.toString());
        }
        public Options {
            preset = normalizePreset(preset);
            if (size < 8 || size > 1024) throw new IllegalArgumentException("size must be 8..1024");
            if (step < 1 || step > 4096) throw new IllegalArgumentException("step must be 1..4096 blocks");
            if (threads < 1 || threads > 32) throw new IllegalArgumentException("threads must be 1..32");
            long half = (long) size * step / 2;
            if (Math.abs((long) x) + half > 29_999_984 || Math.abs((long) z) + half > 29_999_984) {
                throw new IllegalArgumentException("Preview bounds must stay inside Minecraft's world border");
            }
        }

        public int blockX(int pixel) { return x - size * step / 2 + pixel * step; }
        public int blockZ(int pixel) { return z - size * step / 2 + pixel * step; }

        public static Options parse(String[] arguments) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < arguments.length; i += 2) {
                if (i + 1 >= arguments.length || !arguments[i].startsWith("--")) throw new IllegalArgumentException("Use --option value pairs");
                String name = arguments[i].substring(2);
                if (!List.of("seed", "size", "step", "x", "z", "threads", "mode", "output", "preset").contains(name)) throw new IllegalArgumentException("Unknown option: " + name);
                if (values.put(name, arguments[i + 1]) != null) throw new IllegalArgumentException("Duplicate option: " + name);
            }
            String mode = values.getOrDefault("mode", "fast");
            if (!mode.equals("fast") && !mode.equals("detailed")) throw new IllegalArgumentException("mode must be fast or detailed");
            long seed = WorldOptions.parseSeed(values.getOrDefault("seed", "6571")).orElseThrow(() -> new IllegalArgumentException("Specify a nonempty seed"));
            return new Options(seed, Integer.parseInt(values.getOrDefault("size", "256")), Integer.parseInt(values.getOrDefault("step", "128")),
                Integer.parseInt(values.getOrDefault("x", "0")), Integer.parseInt(values.getOrDefault("z", "0")),
                Integer.parseInt(values.getOrDefault("threads", "4")), mode.equals("detailed"), Path.of(values.getOrDefault("output", "seed-previews")), values.getOrDefault("preset", "neko"));
        }
    }

    public record Sample(int elevation, String biome) {}

    public static String normalizePreset(String preset) {
        String normalized = NekoWorldgenBootstrap.normalizeLevelType(preset);
        if (!List.of(NekoWorldgenBootstrap.ID.toString(), NekoWorldgenBootstrap.TALL_ID.toString()).contains(normalized)) {
            throw new IllegalArgumentException("preset must be neko or neko-mmo-tall");
        }
        return normalized;
    }

    public static NoiseBasedChunkGenerator generator(RegistryAccess registries, String preset) {
        return (NoiseBasedChunkGenerator) registries.lookupOrThrow(Registries.WORLD_PRESET)
            .getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse(normalizePreset(preset))))
            .value().overworld().orElseThrow().generator();
    }

    public static Sample sample(RegistryAccess registries, NoiseBasedChunkGenerator generator, RandomState random,
                                SamplerContext context, int x, int z, boolean detailed) {
        int elevation;
        if (detailed) {
            elevation = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG,
                LevelHeightAccessor.create(generator.generatorSettings().value().noiseSettings().minY(), generator.generatorSettings().value().noiseSettings().height()), random) - 1;
        } else {
            // Tectonic's preliminary height is an estimate, before final density/caves/aquifers.
            float value = random.samplersWithContext(context).sampleValue(registries.lookupOrThrow(Registries.DENSITY_FUNCTION)
                .getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse(generator.generatorSettings().is(NekoWorldgenBootstrap.TALL_NOISE_SETTINGS)
                    ? "nekopur:tectonic_tall/minecraft/overworld/preliminary_surface_level" : "nekopur:tectonic/minecraft/overworld/preliminary_surface_level"))).value(), x, 0, z);
            if (!Float.isFinite(value)) throw new IllegalStateException("Nonfinite height at " + x + "," + z);
            elevation = Math.round(value);
        }
        int surfaceY = Math.max(elevation, generator.generatorSettings().value().seaLevel() - 1);
        String biome = generator.getBiomeSource().createResolver(random.createClimateSampler(context))
            .getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(surfaceY), QuartPos.fromBlock(z)).unwrapKey().orElseThrow().identifier().toString();
        return new Sample(elevation, biome);
    }

    public static RegistryAccess loadRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Bootstrap.validate();
        try (java.util.concurrent.ExecutorService loader = Executors.newFixedThreadPool(4)) {
            net.minecraft.server.packs.repository.PackRepository packs = ServerPacksSource.createVanillaTrustedRepository();
            MinecraftServer.configurePackRepository(packs, new WorldDataConfiguration(new DataPackConfig(
                FeatureFlags.REGISTRY.toNames(FeatureFlags.DEFAULT_FLAGS).stream().map(Identifier::getPath).toList(), List.of()), FeatureFlags.DEFAULT_FLAGS), true, false);
            try (MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs.openAllSelected())) {
                net.minecraft.core.LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
                List<net.minecraft.core.Registry.PendingTags<?>> tags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
                RegistryAccess.Frozen world = RegistryDataLoader.load(resources,
                    TagLoader.buildUpdatedLookups(layers.getAccessForLoading(RegistryLayer.WORLD), tags), RegistryDataLoader.WORLD_REGISTRIES, loader).join();
                // Column height predicates use block tags at runtime, not only the pending codec lookups.
                tags.forEach(net.minecraft.core.Registry.PendingTags::apply);
                return layers.replaceFrom(RegistryLayer.WORLD, world).compositeAccess().freeze();
            }
        }
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 1 && arguments[0].equals("--help")) {
            System.out.println("NekoSeedPreview --seed <number/text> --size <8..1024> --step <blocks/pixel> --x <center> --z <center> --threads <1..32> --mode <fast/detailed> --output <directory>");
            return;
        }
        Options options = Options.parse(arguments);
        System.out.println("Loading native Neko preset for seed " + options.seed());
        RegistryAccess registries = loadRegistries();
        NoiseBasedChunkGenerator generator = generator(registries, options.preset());
        RandomState random = RandomState.create(registries.lookupOrThrow(Registries.NOISE), options.seed(), generator.generatorSettings().value());
        int[] elevations = new int[options.size() * options.size()];
        String[] biomes = new String[elevations.length];
        AtomicInteger completed = new AtomicInteger();
        long start = System.nanoTime();
        try (java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(options.threads())) {
            List<java.util.concurrent.Callable<Void>> tasks = new ArrayList<>();
            for (int row = 0; row < options.size(); row++) {
                int currentRow = row;
                tasks.add(() -> {
                    SamplerContext context = SamplerContext.builder().enableCaches().build();
                    for (int column = 0; column < options.size(); column++) {
                        Sample point = sample(registries, generator, random, context, options.blockX(column), options.blockZ(currentRow), options.detailed());
                        int index = currentRow * options.size() + column;
                        elevations[index] = point.elevation();
                        biomes[index] = point.biome();
                    }
                    int done = completed.incrementAndGet();
                    if (done % Math.max(1, options.size() / 8) == 0) System.out.println("Sampled " + done + "/" + options.size() + " rows");
                    return null;
                });
            }
            for (java.util.concurrent.Future<Void> task : workers.invokeAll(tasks)) task.get();
        }
        Path folder = options.output().resolve((options.preset().equals(NekoWorldgenBootstrap.TALL_ID.toString()) ? "tall-" : "") + "seed-" + options.seed() + "-" + options.x() + "-" + options.z() + "-" + options.size() + "x" + options.step() + "-" + (options.detailed() ? "detailed" : "fast"));
        writePreview(options, elevations, biomes, generator.generatorSettings().value().seaLevel(), folder);
        System.out.println("Preview ready: " + folder.resolve("index.html").toAbsolutePath() + " (sampling " + (System.nanoTime() - start) / 1_000_000_000.0 + " seconds)");
    }

    public static void writePreview(Options options, int[] elevations, String[] biomes, int seaLevel, Path folder) throws IOException {
        Files.createDirectories(folder);
        Map<String, Object> data = previewData(options, elevations, biomes, seaLevel);
        @SuppressWarnings("unchecked") Map<String, String> images = (Map<String, String>) data.get("images");
        for (Map.Entry<String, String> image : images.entrySet()) {
            Files.write(folder.resolve(image.getKey() + ".png"), Base64.getDecoder().decode(image.getValue().substring("data:image/png;base64,".length())));
        }
        String json = new GsonBuilder().create().toJson(data);
        Files.writeString(folder.resolve("samples.json"), json, StandardCharsets.UTF_8);
        try (java.io.InputStream template = NekoSeedPreview.class.getResourceAsStream("/org/nekopur/worldgen/preview.html")) {
            if (template == null) throw new IOException("Missing preview HTML template");
            Files.writeString(folder.resolve("index.html"), new String(template.readAllBytes(), StandardCharsets.UTF_8).replace("__PREVIEW_DATA__", json), StandardCharsets.UTF_8);
        }
    }

    /** Render a frame entirely in memory, shared by static exports and the live viewer. */
    public static Map<String, Object> previewData(Options options, int[] elevations, String[] biomes, int seaLevel) throws IOException {
        if (elevations.length != options.size() * options.size() || biomes.length != elevations.length) throw new IllegalArgumentException("Sample dimensions do not match size");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seed", Long.toString(options.seed())); // JavaScript numbers cannot represent all 64-bit seeds.
        data.put("version", SharedConstants.getCurrentVersion().name());
        data.put("preset", options.preset());
        data.put("mode", options.detailed() ? "detailed" : "fast");
        data.put("size", options.size()); data.put("step", options.step());
        data.put("minX", options.blockX(0)); data.put("minZ", options.blockZ(0));
        data.put("seaLevel", seaLevel); data.put("elevation", elevations);
        List<String> palette = List.of(biomes).stream().distinct().sorted().toList();
        Map<String, Integer> ids = new LinkedHashMap<>();
        for (int i = 0; i < palette.size(); i++) ids.put(palette.get(i), i);
        int[] biomeIds = new int[biomes.length];
        for (int i = 0; i < biomes.length; i++) biomeIds[i] = ids.get(biomes[i]);
        data.put("biomeNames", palette); data.put("biomes", biomeIds);
        data.put("biomeColors", palette.stream().map(biome -> String.format("#%06x", biomeColor(biome))).toList());
        Map<String, String> images = new LinkedHashMap<>();
        for (String layer : List.of("terrain", "elevation", "biomes")) {
            BufferedImage image = new BufferedImage(options.size(), options.size(), BufferedImage.TYPE_INT_RGB);
            for (int i = 0; i < elevations.length; i++) {
                int color = layer.equals("biomes") ? biomeColor(biomes[i]) : terrainColor(elevations[i], seaLevel, layer.equals("elevation"));
                image.setRGB(i % options.size(), i / options.size(), color);
            }
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            ImageIO.write(image, "PNG", png);
            images.put(layer, "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray()));
        }
        data.put("images", images);
        return data;
    }

    private static int terrainColor(int y, int sea, boolean elevation) {
        if (y < sea - 1) return blend(0x123458, 0x4bb7ce, (y + 64) / (float) (sea + 63));
        if (elevation) return blend(0xe7d4a3, 0x9b3d70, (y - sea + 1) / 200F);
        if (y < sea + 5) return 0xdcc893;
        if (y < 130) return blend(0x547d48, 0x91a568, (y - sea) / 67F);
        return blend(0x8c8275, 0xf1f0ed, (y - 130) / 140F);
    }

    public static int biomeColor(String biome) {
        if (biome.contains("ocean")) return 0x286b99;
        if (biome.contains("river")) return 0x49a5c6;
        if (biome.contains("cherry")) return 0xec9ab1;
        if (biome.contains("desert") || biome.contains("beach")) return 0xe6cf8a;
        if (biome.contains("snow") || biome.contains("frozen") || biome.contains("ice")) return 0xe4edf3;
        if (biome.contains("badlands")) return 0xb66b44;
        if (biome.contains("swamp")) return 0x63764a;
        if (biome.contains("jungle")) return 0x2c7048;
        if (biome.contains("forest") || biome.contains("taiga")) return 0x426852;
        if (biome.contains("peak") || biome.contains("hill")) return 0x99928b;
        return java.awt.Color.HSBtoRGB(Math.floorMod(biome.hashCode(), 360) / 360F, 0.35F, 0.72F) & 0xffffff;
    }

    private static int blend(int a, int b, float value) {
        float t = Math.max(0, Math.min(1, value));
        int result = 0;
        for (int shift : new int[]{16, 8, 0}) result |= Math.round(((a >> shift) & 255) * (1 - t) + ((b >> shift) & 255) * t) << shift;
        return result;
    }
}
