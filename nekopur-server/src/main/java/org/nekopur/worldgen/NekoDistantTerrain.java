package org.nekopur.worldgen;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.SharedConstants;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Chunk-free native NOISE terrain for distant renderers. This does not run surface
 * rules, carvers, structures, features, lighting or old-chunk blending. A renderer
 * must prefer authoritative chunk snapshots over these base-noise predictions.
 */
@NullMarked
public final class NekoDistantTerrain {
    private NekoDistantTerrain() {
    }

    /** Capture the actual saved generator on the primary thread before dispatching work. */
    public static Snapshot capture(World world) {
        Objects.requireNonNull(world, "world");
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Capture distant terrain on the server primary thread");
        }
        if (!(world instanceof CraftWorld craftWorld)) {
            throw new IllegalArgumentException("Distant terrain requires a native Nekopur world");
        }
        ServerLevel level = craftWorld.getHandle();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (generator.getClass() != NoiseBasedChunkGenerator.class) {
            throw new IllegalArgumentException("Distant terrain does not support custom or plugin chunk generators");
        }
        return snapshot((NoiseBasedChunkGenerator) generator, level.getChunkSource().randomState(), level.getMinY(), level.getHeight());
    }

    // Package-private native factory for offline validation without constructing or loading a world.
    static Snapshot snapshot(NoiseBasedChunkGenerator generator, RandomState random, int minY, int height) {
        if (!generator.generatorSettings().is(NekoWorldgenBootstrap.NOISE_SETTINGS)
            && !generator.generatorSettings().is(NekoWorldgenBootstrap.TALL_NOISE_SETTINGS)) {
            throw new IllegalArgumentException("Distant terrain supports only the named Neko and Neko MMO Tall noise settings");
        }
        if (height <= 0 || minY != generator.generatorSettings().value().noiseSettings().minY()
            || height != generator.generatorSettings().value().noiseSettings().height()) {
            throw new IllegalArgumentException("World height must match its native Neko noise settings");
        }
        return new Snapshot(generator, random, LevelHeightAccessor.create(minY, height));
    }

    /** Immutable generator snapshot; share this, but never share a Sampler between workers. */
    public static final class Snapshot {
        private final NoiseBasedChunkGenerator generator;
        private final RandomState random;
        private final LevelHeightAccessor height;
        private final Aquifer.FluidPicker fluids;

        private Snapshot(NoiseBasedChunkGenerator generator, RandomState random, LevelHeightAccessor height) {
            this.generator = generator;
            this.random = random;
            this.height = height;
            // Match NoiseBasedChunkGenerator's native Neko fluid picker, including debug generation.
            int lavaY = generator.generatorSettings().is(NekoWorldgenBootstrap.TALL_NOISE_SETTINGS) ? -118 : -54;
            Aquifer.FluidStatus lava = new Aquifer.FluidStatus(lavaY, Blocks.LAVA.defaultBlockState());
            Aquifer.FluidStatus sea = new Aquifer.FluidStatus(generator.getSeaLevel(), generator.generatorSettings().value().defaultFluid());
            Aquifer.FluidStatus empty = new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
            this.fluids = (x, y, z) -> SharedConstants.DEBUG_DISABLE_FLUID_GENERATION ? empty
                : y < Math.min(lavaY, generator.getSeaLevel()) ? lava : sea;
        }

        public int minY() { return height.getMinY(); }
        public int maxY() { return minY() + height.getHeight() - 1; }
        public int seaLevel() { return generator.getSeaLevel(); }
        public String preset() { return generator.generatorSettings().unwrapKey().orElseThrow().identifier().toString(); }

        /** Call on the worker that will sample; its mutable climate caches belong to that worker. */
        public Sampler newSampler() { return new Sampler(this); }
    }

    public static final class Sampler {
        private final Snapshot snapshot;
        private final Thread owner = Thread.currentThread();
        private final BiomeResolver biomes;
        private final DensitySampler.@Nullable Bound estimatedSurface;
        private final Map<BlockState, String> states = new HashMap<>();

        private Sampler(Snapshot snapshot) {
            this.snapshot = snapshot;
            SamplerContext context = SamplerContext.builder().enableCaches().build();
            this.biomes = snapshot.generator.getBiomeSource().createResolver(snapshot.random.createClimateSampler(context));
            this.estimatedSurface = snapshot.generator.generatorSettings().value().aquifers()
                .map(config -> snapshot.random.samplersWithContext(context).get(config.surfaceLevel())).orElse(null);
        }

        /** Sample one complete base column without reading or writing any world chunks. */
        public Column sampleColumn(int blockX, int blockZ) {
            checkOwner();
            checkBounds(blockX, blockZ, 1);
            NoiseColumn nativeColumn = snapshot.generator.getBaseColumn(blockX, blockZ, snapshot.height, snapshot.random);
            String[] blocks = new String[snapshot.height.getHeight()];
            int topY = snapshot.minY() - 1;
            int highestSolidY = topY;
            for (int y = snapshot.minY(); y <= snapshot.maxY(); y++) {
                BlockState state = nativeColumn.getBlock(y);
                blocks[y - snapshot.minY()] = states.computeIfAbsent(state, value -> value.asBlockData().getAsString());
                if (!state.isAir()) topY = y;
                if (Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(state)) highestSolidY = y;
            }
            return column(blockX, blockZ, topY, highestSolidY, blocks);
        }

        /**
         * Sample a 16- or 64-block square, indexed {@code x * width + z}. Density and
         * aquifer setup is shared within 16x16 batches; only one such native buffer
         * is live at a time. Unlike surface generation this never creates chunks.
         */
        public Column[] sampleTile(int minBlockX, int minBlockZ, int width) {
            checkOwner();
            if (width != 16 && width != 64) {
                throw new IllegalArgumentException("Distant terrain tile width must be 16 or 64 blocks");
            }
            checkBounds(minBlockX, minBlockZ, width);
            Column[] columns = new Column[width * width];
            for (int batchX = 0; batchX < width; batchX += 16) {
                for (int batchZ = 0; batchZ < width; batchZ += 16) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new java.util.concurrent.CancellationException("Distant terrain sampling was cancelled");
                    }
                    DensityVolume volume = new DensityVolume(16, snapshot.height.getHeight(), 16,
                        minBlockX + batchX, snapshot.minY(), minBlockZ + batchZ);
                    NoiseGeneratorSettings settings = snapshot.generator.generatorSettings().value();
                    try (NoiseChunk noise = new NoiseChunk(snapshot.random, null, settings, snapshot.fluids, Blender.empty(), volume);
                         ScopedDensityBuffer densities = noise.cachingSamplers().get(settings.noiseRouter().finalDensity()).sampleVolume(volume)) {
                        Aquifer aquifer = noise.aquifer();
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                String[] blocks = new String[volume.sizeY()];
                                int topY = snapshot.minY() - 1;
                                int highestSolidY = topY;
                                // Preserve native scalar traversal order for aquifer caches.
                                for (int y = volume.sizeY() - 1; y >= 0; y--) {
                                    int absoluteY = volume.blockY(y);
                                    BlockState state = aquifer.computeSubstance(volume.blockX(x), absoluteY, volume.blockZ(z),
                                        densities.get(volume.indexUnchecked(x, y, z)));
                                    if (state == null) state = settings.defaultBlock();
                                    blocks[y] = states.computeIfAbsent(state, value -> value.asBlockData().getAsString());
                                    if (topY < snapshot.minY() && !state.isAir()) topY = absoluteY;
                                    if (highestSolidY < snapshot.minY() && Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(state)) highestSolidY = absoluteY;
                                }
                                columns[(batchX + x) * width + batchZ + z] = column(volume.blockX(x), volume.blockZ(z), topY, highestSolidY, blocks);
                            }
                        }
                    }
                }
            }
            return columns;
        }

        /**
         * Estimated distant surfaces for 64x64 cells at strides 1, 2, 4, 8 or 16.
         * Each cell samples its center block and output is indexed x*64+z. This
         * evaluates the actual saved aquifer surface graph, not final density or
         * completed terrain. Its native eight-block search and omitted terrain
         * noise/caves mean no fixed height bias can make these predictions exact.
         * Authoritative terrain must supersede them as the player approaches.
         */
        public SurfaceColumn[] sampleSurfaceTile(int minBlockX, int minBlockZ, int stride) {
            checkOwner();
            if (stride != 1 && stride != 2 && stride != 4 && stride != 8 && stride != 16) {
                throw new IllegalArgumentException("Distant surface stride must be 1, 2, 4, 8 or 16 blocks");
            }
            checkBounds(minBlockX, minBlockZ, 64 * stride);
            DensitySampler.Bound surface = estimatedSurface;
            if (surface == null) {
                throw new IllegalStateException("Native Neko surface estimates require the saved aquifer surface graph");
            }
            NoiseGeneratorSettings settings = snapshot.generator.generatorSettings().value();
            String solidState = settings.defaultBlock().asBlockData().getAsString();
            String fluidState = settings.defaultFluid().asBlockData().getAsString();
            SurfaceColumn[] result = new SurfaceColumn[4096];
            for (int x = 0; x < 64; x++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("Distant surface sampling was cancelled");
                }
                int blockX = minBlockX + x * stride + stride / 2;
                for (int z = 0; z < 64; z++) {
                    int blockZ = minBlockZ + z * stride + stride / 2;
                    float prediction = surface.sampleValue(blockX, 0, blockZ);
                    if (!Float.isFinite(prediction)) {
                        throw new IllegalStateException("Nonfinite native surface estimate at " + blockX + "," + blockZ);
                    }
                    int solidY = Math.clamp(Math.round(prediction), snapshot.minY(), snapshot.maxY());
                    boolean fluid = !SharedConstants.DEBUG_DISABLE_FLUID_GENERATION && solidY < snapshot.seaLevel() - 1;
                    int topY = fluid ? Math.min(snapshot.maxY(), snapshot.seaLevel() - 1) : solidY;
                    String biome = biomes.getNoiseBiome(QuartPos.fromBlock(blockX), QuartPos.fromBlock(Math.max(solidY, snapshot.seaLevel() - 1)), QuartPos.fromBlock(blockZ))
                        .unwrapKey().orElseThrow().identifier().toString();
                    result[x * 64 + z] = new SurfaceColumn(snapshot.minY(), topY, solidY, biome,
                        estimatedCover(biome, solidY, solidState), fluid ? fluidState : "minecraft:air");
                }
            }
            return result;
        }

        /**
         * Final NOISE surfaces at the same 4096 cell centers as sampleSurfaceTile.
         * Heights, top fluids and biome altitude come from native base columns;
         * only biome cover remains an approximation. This omits final surface
         * rules, features, structures and blending, and never loads or saves
         * playable chunks. Sparse sampling avoids generating the intervening
         * columns. Bounded sparse density volumes share native setup across nearby
         * centers; unlike preliminary heights this evaluates final density and aquifers.
         */
        public SurfaceColumn[] sampleAccurateSurfaceTile(int minBlockX, int minBlockZ, int stride) {
            checkOwner();
            if (stride != 1 && stride != 2 && stride != 4 && stride != 8 && stride != 16) {
                throw new IllegalArgumentException("Distant surface stride must be 1, 2, 4, 8 or 16 blocks");
            }
            checkBounds(minBlockX, minBlockZ, 64 * stride);
            SurfaceColumn[] result = new SurfaceColumn[4096];
            int[] tops = new int[4096];
            int[] solids = new int[4096];
            BlockState[] topStates = new BlockState[4096];
            BlockState[] solidStates = new BlockState[4096];
            NoiseGeneratorSettings settings = snapshot.generator.generatorSettings().value();
            // Horizontal steps preserve the exact requested centers without generating intervening columns.
            // A physical 16-block footprint bounds each context and its density/aquifer working buffers.
            int batchSize = stride <= 4 ? 16 / stride : 1;
            int volumeStep = batchSize == 1 ? 1 : stride;
            for (int batchX = 0; batchX < 64; batchX += batchSize) {
                for (int batchZ = 0; batchZ < 64; batchZ += batchSize) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new java.util.concurrent.CancellationException("Accurate distant surface sampling was cancelled");
                    }
                    DensityVolume volume = new DensityVolume(batchSize, snapshot.height.getHeight(), batchSize,
                        minBlockX + batchX * stride + stride / 2, snapshot.minY(),
                        minBlockZ + batchZ * stride + stride / 2, volumeStep, 1, volumeStep);
                    try (NoiseChunk noise = new NoiseChunk(snapshot.random, null, settings, snapshot.fluids, Blender.empty(), volume);
                         ScopedDensityBuffer densities = noise.cachingSamplers().get(settings.noiseRouter().finalDensity()).sampleVolume(volume)) {
                        Aquifer aquifer = noise.aquifer();
                        for (int x = 0; x < batchSize; x++) {
                            for (int z = 0; z < batchSize; z++) {
                                if (Thread.currentThread().isInterrupted()) {
                                    throw new java.util.concurrent.CancellationException("Accurate distant surface sampling was cancelled");
                                }
                                int blockX = volume.blockX(x);
                                int blockZ = volume.blockZ(z);
                                int topY = snapshot.minY() - 1;
                                int solidY = topY;
                                BlockState top = Blocks.AIR.defaultBlockState();
                                BlockState solid = top;
                                for (int y = volume.sizeY() - 1; y >= 0; y--) {
                                    int absoluteY = volume.blockY(y);
                                    BlockState state = aquifer.computeSubstance(blockX, absoluteY, blockZ,
                                        densities.get(volume.indexUnchecked(x, y, z)));
                                    if (state == null) state = settings.defaultBlock();
                                    if (topY < snapshot.minY() && !state.isAir()) {
                                        topY = absoluteY;
                                        top = state;
                                    }
                                    if (Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(state)) {
                                        solidY = absoluteY;
                                        solid = state;
                                        break;
                                    }
                                }
                                int index = (batchX + x) * 64 + batchZ + z;
                                tops[index] = topY;
                                solids[index] = solidY;
                                topStates[index] = top;
                                solidStates[index] = solid;
                            }
                        }
                    }
                }
            }
            // Native climate search retains a last candidate: preserve the scalar x/z traversal at equal-distance biome boundaries.
            for (int x = 0; x < 64; x++) {
                for (int z = 0; z < 64; z++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new java.util.concurrent.CancellationException("Accurate distant surface sampling was cancelled");
                    }
                    int index = x * 64 + z;
                    int blockX = minBlockX + x * stride + stride / 2;
                    int blockZ = minBlockZ + z * stride + stride / 2;
                    String biome = biomes.getNoiseBiome(QuartPos.fromBlock(blockX), QuartPos.fromBlock(Math.max(solids[index], snapshot.seaLevel() - 1)), QuartPos.fromBlock(blockZ))
                        .unwrapKey().orElseThrow().identifier().toString();
                    String nativeSolid = states.computeIfAbsent(solidStates[index], value -> value.asBlockData().getAsString());
                    BlockState top = topStates[index];
                    String fluid = top.getFluidState().isEmpty() ? "minecraft:air"
                        : states.computeIfAbsent(top, value -> value.asBlockData().getAsString());
                    result[index] = new SurfaceColumn(snapshot.minY(), tops[index], solids[index], biome,
                        estimatedCover(biome, solids[index], nativeSolid), fluid);
                }
            }
            return result;
        }

        // Approximate biome cover matches the plugin's native preview columns; subsurface fill stays native stone.
        static String estimatedCover(String biome, int solidY, String nativeState) {
            if (solidY < 0 || !nativeState.equals("minecraft:stone")) return nativeState;
            if (biome.contains("ocean") || biome.contains("beach") || biome.contains("desert")) return "minecraft:sand";
            if (biome.contains("badlands")) return "minecraft:red_sand";
            if (biome.contains("snow") || biome.contains("frozen") || biome.contains("ice_spikes")) return "minecraft:snow_block";
            if (!biome.contains("peak") && !biome.contains("stony") && !biome.contains("windswept")) return "minecraft:grass_block";
            return nativeState;
        }

        private Column column(int blockX, int blockZ, int topY, int highestSolidY, String[] blocks) {
            int biomeY = Math.max(highestSolidY, snapshot.seaLevel() - 1);
            String biome = biomes.getNoiseBiome(QuartPos.fromBlock(blockX), QuartPos.fromBlock(biomeY), QuartPos.fromBlock(blockZ))
                .unwrapKey().orElseThrow().identifier().toString();
            return new Column(snapshot.minY(), topY, highestSolidY, biome, blocks);
        }

        private void checkOwner() {
            if (owner != Thread.currentThread()) {
                throw new IllegalStateException("Create a separate distant terrain sampler on each worker thread");
            }
        }

        private static void checkBounds(int minX, int minZ, int width) {
            if (minX < -29_999_984 || (long) minX + width - 1 > 29_999_984
                || minZ < -29_999_984 || (long) minZ + width - 1 > 29_999_984) {
                throw new IllegalArgumentException("Distant terrain coordinates exceed Minecraft's world bounds");
            }
        }
    }

    /** Surface summary: preliminary or final NOISE heights depending on sampler; cover is approximate. */
    public record SurfaceColumn(int minY, int topY, int highestSolidY, String surfaceBiome, String surfaceMaterial, String fluidMaterial) {
    }

    /** Inclusive absolute heights; blockStates are indexed from minY and copied defensively. */
    public record Column(int minY, int topY, int highestSolidY, String surfaceBiome, String[] blockStates) {
        public Column {
            Objects.requireNonNull(surfaceBiome, "surfaceBiome");
            blockStates = blockStates.clone();
        }

        @Override
        public String[] blockStates() { return blockStates.clone(); }

        public String blockState(int absoluteY) {
            long index = (long) absoluteY - minY;
            return index >= 0 && index < blockStates.length ? blockStates[(int) index] : "minecraft:air";
        }
    }
}
