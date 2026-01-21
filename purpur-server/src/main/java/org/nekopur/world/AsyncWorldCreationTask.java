package org.nekopur.world;

import com.google.common.base.Preconditions;
import io.papermc.paper.util.MCUtil;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.craftbukkit.CraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.nekopur.AsyncWorldOptions;
import org.nekopur.AsyncWorldProgress;
import org.nekopur.WorldTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Handles asynchronous world creation, performing file operations off the main thread.
 * <p>
 * This implementation moves these operations to async threads:
 * <ul>
 *   <li>Directory creation - async</li>
 *   <li>Template file copying - async</li>
 *   <li>ServerLevel construction - main thread (required by Minecraft)</li>
 *   <li>World registration - main thread (required by Minecraft)</li>
 *   <li>Spawn chunk loading - async via Paper's API</li>
 * </ul>
 */
public class AsyncWorldCreationTask {

    // Batch size for parallel chunk generation to avoid overwhelming the scheduler
    private static final int CHUNK_BATCH_SIZE = 64;
    // Delay between batches in milliseconds
    private static final int BATCH_DELAY_MS = 50;

    private final CraftServer craftServer;
    private final WorldCreator creator;
    private final AsyncWorldOptions options;
    private final CompletableFuture<World> future;
    private final AsyncWorldProgressImpl progress;
    private final @Nullable Consumer<AsyncWorldProgress> progressCallback;
    private final @Nullable Consumer<AsyncWorldProgress> preGenerateCallback;

    public AsyncWorldCreationTask(
            @NotNull CraftServer craftServer,
            @NotNull WorldCreator creator,
            @NotNull AsyncWorldOptions options) {
        Preconditions.checkArgument(craftServer != null, "CraftServer cannot be null");
        Preconditions.checkArgument(creator != null, "WorldCreator cannot be null");
        Preconditions.checkArgument(options != null, "AsyncWorldOptions cannot be null");

        this.craftServer = craftServer;
        this.creator = creator;
        this.options = options;
        this.future = new CompletableFuture<>();
        this.progress = new AsyncWorldProgressImpl(creator.name());
        this.progressCallback = options.getProgressCallback();
        this.preGenerateCallback = options.getPreGenerateCallback();
    }

    /**
     * Starts the async world creation process.
     *
     * @return a CompletableFuture that completes with the created world
     */
    @NotNull
    public CompletableFuture<World> start() {
        craftServer.getLogger().info("[NekoAsync] AsyncWorldCreationTask.start() called for: " + creator.name());
        craftServer.getLogger().info("[NekoAsync] SpawnChunkBehavior: " + options.getSpawnChunkBehavior());
        craftServer.getLogger().info("[NekoAsync] PreGenerateRadius: " + options.getPreGenerateRadius());

        // Check if world already exists
        World existingWorld = craftServer.getWorld(creator.name());
        if (existingWorld != null) {
            craftServer.getLogger().info("[NekoAsync] World already exists, returning existing");
            reportProgress(AsyncWorldProgress.Stage.COMPLETED);
            future.complete(existingWorld);
            return future;
        }

        // Start async preparation
        craftServer.getLogger().info("[NekoAsync] Starting async preparation...");
        CompletableFuture.runAsync(this::prepareAsync, MCUtil.ASYNC_EXECUTOR)
                .exceptionally(throwable -> {
                    craftServer.getLogger().warning("[NekoAsync] Async preparation failed: " + throwable.getMessage());
                    reportProgress(AsyncWorldProgress.Stage.FAILED);
                    future.completeExceptionally(throwable);
                    return null;
                });

        return future;
    }

    /**
     * Performs async preparation work (off main thread).
     */
    private void prepareAsync() {
        try {
            String name = creator.name();
            craftServer.getLogger().info("[NekoAsync] prepareAsync() running on thread: " + Thread.currentThread().getName());

            Path worldContainer = craftServer.getWorldContainer().toPath();
            Path worldFolder = worldContainer.resolve(name);

            // Phase 1: Create world directory
            reportProgress(AsyncWorldProgress.Stage.CREATING_DIRECTORY);
            if (!Files.exists(worldFolder)) {
                Files.createDirectories(worldFolder);
                craftServer.getLogger().info("[NekoAsync] Created directory: " + worldFolder);
            }

            // Phase 2: Copy template files if template is set
            WorldTemplate template = options.getTemplate();
            if (template != null) {
                reportProgress(AsyncWorldProgress.Stage.COPYING_TEMPLATE);
                copyTemplateFiles(template, worldFolder);
                craftServer.getLogger().info("[NekoAsync] Copied template files");
            }

            // Phase 3: Schedule main thread world creation
            reportProgress(AsyncWorldProgress.Stage.CREATING_STORAGE);
            craftServer.getLogger().info("[NekoAsync] Scheduling createOnMainThread()");
            craftServer.getServer().execute(this::createOnMainThread);

        } catch (Throwable t) {
            craftServer.getLogger().warning("[NekoAsync] prepareAsync() failed: " + t.getMessage());
            reportProgress(AsyncWorldProgress.Stage.FAILED);
            future.completeExceptionally(t);
        }
    }

    /**
     * Creates the world on the main thread.
     */
    private void createOnMainThread() {
        craftServer.getLogger().info("[NekoAsync] createOnMainThread() called on thread: " + Thread.currentThread().getName());

        try {
            // Safety check
            if (craftServer.getServer().isIteratingOverLevels) {
                craftServer.getLogger().warning("[NekoAsync] Cannot create world - server is iterating over levels");
                future.completeExceptionally(
                        new IllegalStateException("Cannot create world while server is iterating over levels"));
                return;
            }

            // Double check world doesn't exist
            World existingWorld = craftServer.getWorld(creator.name());
            if (existingWorld != null) {
                craftServer.getLogger().info("[NekoAsync] World already exists (race condition), returning existing");
                reportProgress(AsyncWorldProgress.Stage.COMPLETED);
                future.complete(existingWorld);
                return;
            }

            reportProgress(AsyncWorldProgress.Stage.CONSTRUCTING_LEVEL);
            craftServer.getLogger().info("[NekoAsync] Calling craftServer.createWorld() with async options set");

            // Create the world using the creator
            org.nekopur.NekoServerImpl.setAsyncWorldOptions(options);
            World world;
            try {
                long startTime = System.currentTimeMillis();
                world = craftServer.createWorld(creator);
                long elapsed = System.currentTimeMillis() - startTime;
                craftServer.getLogger().info("[NekoAsync] craftServer.createWorld() completed in " + elapsed + "ms");
            } finally {
                org.nekopur.NekoServerImpl.clearAsyncWorldOptions();
            }

            if (world == null) {
                reportProgress(AsyncWorldProgress.Stage.FAILED);
                future.completeExceptionally(new RuntimeException("World creation returned null"));
                return;
            }

            reportProgress(AsyncWorldProgress.Stage.REGISTERING_WORLD);

            // Handle spawn chunks based on options
            CompletableFuture<Void> spawnFuture = org.nekopur.NekoServerImpl.consumeAsyncSpawnFuture();
            if (spawnFuture != null) {
                spawnFuture.whenComplete((unused, throwable) -> {
                    handleSpawnChunks(world);
                });
            } else {
                handleSpawnChunks(world);
            }

        } catch (Throwable t) {
            reportProgress(AsyncWorldProgress.Stage.FAILED);
            future.completeExceptionally(t);
        }
    }

    /**
     * Copies template files to the target world directory.
     */
    private void copyTemplateFiles(@NotNull WorldTemplate template, @NotNull Path targetDir) throws IOException {
        Path templatePath = template.getTemplatePath();

        // Always copy level.dat if it exists
        Path levelDat = templatePath.resolve("level.dat");
        if (Files.exists(levelDat)) {
            Files.copy(levelDat, targetDir.resolve("level.dat"), StandardCopyOption.REPLACE_EXISTING);
        }

        // Copy level.dat_old if it exists
        Path levelDatOld = templatePath.resolve("level.dat_old");
        if (Files.exists(levelDatOld)) {
            Files.copy(levelDatOld, targetDir.resolve("level.dat_old"), StandardCopyOption.REPLACE_EXISTING);
        }

        // Copy region files if requested
        if (template.shouldCopyRegionFiles()) {
            copyDirectory(templatePath.resolve("region"), targetDir.resolve("region"));
        }

        // Copy entity files if requested
        if (template.shouldCopyEntities()) {
            copyDirectory(templatePath.resolve("entities"), targetDir.resolve("entities"));
        }

        // Copy POI files if requested
        if (template.shouldCopyPoi()) {
            copyDirectory(templatePath.resolve("poi"), targetDir.resolve("poi"));
        }

        // Copy datapacks if requested
        if (template.shouldCopyDatapacks()) {
            copyDirectory(templatePath.resolve("datapacks"), targetDir.resolve("datapacks"));
        }

        // Copy DIM-1 (nether) and DIM1 (end) if we're copying regions
        if (template.shouldCopyRegionFiles()) {
            Path dimMinus1 = templatePath.resolve("DIM-1");
            if (Files.exists(dimMinus1)) {
                copyDirectory(dimMinus1, targetDir.resolve("DIM-1"));
            }

            Path dim1 = templatePath.resolve("DIM1");
            if (Files.exists(dim1)) {
                copyDirectory(dim1, targetDir.resolve("DIM1"));
            }
        }
    }

    /**
     * Recursively copies a directory.
     */
    private void copyDirectory(@NotNull Path source, @NotNull Path target) throws IOException {
        if (!Files.exists(source)) {
            return;
        }

        Files.createDirectories(target);

        try (Stream<Path> stream = Files.walk(source)) {
            stream.forEach(sourcePath -> {
                try {
                    Path targetPath = target.resolve(source.relativize(sourcePath));
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to copy: " + sourcePath, e);
                }
            });
        }
    }

    /**
     * Handles spawn chunk loading and optional pre-generation based on options.
     * <p>
     * Uses batched chunk generation for all modes to avoid TPS drops.
     * Even "spawn chunk loading" uses the pregen approach with a small radius.
     */
    private void handleSpawnChunks(@NotNull World world) {
        AsyncWorldOptions.SpawnChunkBehavior behavior = options.getSpawnChunkBehavior();
        int preGenRadius = options.getPreGenerateRadius();

        // SKIP with no pregen = done immediately
        if (behavior == AsyncWorldOptions.SpawnChunkBehavior.SKIP && preGenRadius <= 0) {
            reportProgress(AsyncWorldProgress.Stage.COMPLETED);
            future.complete(world);
            return;
        }

        // Get spawn location
        Location spawnLoc = world.getSpawnLocation();
        int spawnChunkX = spawnLoc.getBlockX() >> 4;
        int spawnChunkZ = spawnLoc.getBlockZ() >> 4;

        // Determine radius: use preGenRadius if set, otherwise default spawn chunk radius
        int radius = preGenRadius > 0 ? preGenRadius : 2;

        // Use batched pregen for ALL chunk loading (avoids TPS drops)
        handlePreGeneration(world, spawnChunkX, spawnChunkZ, radius, behavior);
    }

    /**
     * Handles parallel chunk pre-generation using batched async loading.
     * <p>
     * This uses Paper's async chunk API to generate chunks in parallel batches,
     * which leverages Moonrise's worker pool for maximum throughput while
     * giving the main thread time to tick between batches.
     *
     * @param world the world to generate chunks in
     * @param centerX the center chunk X coordinate
     * @param centerZ the center chunk Z coordinate
     * @param radius the radius in chunks to generate
     * @param behavior the spawn chunk behavior
     */
    private void handlePreGeneration(@NotNull World world, int centerX, int centerZ, int radius,
                                      AsyncWorldOptions.SpawnChunkBehavior behavior) {
        // Use appropriate stage based on radius (spawn chunks vs full pregen)
        AsyncWorldProgress.Stage stage = radius <= 2
                ? AsyncWorldProgress.Stage.LOADING_SPAWN_CHUNKS
                : AsyncWorldProgress.Stage.PRE_GENERATING_CHUNKS;
        reportProgress(stage);

        // Calculate total chunks to generate (circular area)
        List<int[]> chunksToGenerate = new ArrayList<>();
        int radiusSquared = radius * radius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                // Use circular pattern for more natural area
                if (dx * dx + dz * dz <= radiusSquared) {
                    chunksToGenerate.add(new int[]{centerX + dx, centerZ + dz});
                }
            }
        }

        int totalChunks = chunksToGenerate.size();
        if (totalChunks == 0) {
            reportProgress(AsyncWorldProgress.Stage.COMPLETED);
            future.complete(world);
            return;
        }

        // Report initial progress
        progress.setStage(stage, 0.0);
        progress.setDetails("0/" + totalChunks + " chunks");
        reportPreGenProgress();

        // Generate chunks in batches to avoid overwhelming the scheduler
        CompletableFuture<Void> genFuture = generateChunkBatches(world, chunksToGenerate, totalChunks, stage);

        if (behavior == AsyncWorldOptions.SpawnChunkBehavior.ASYNC_FIRE_AND_FORGET) {
            // Complete immediately, let generation continue in background
            reportProgress(AsyncWorldProgress.Stage.COMPLETED);
            future.complete(world);
        } else {
            // ASYNC_WAIT - wait for all chunks to generate
            final World finalWorld = world;
            genFuture
                    .thenRun(() -> {
                        reportProgress(AsyncWorldProgress.Stage.COMPLETED);
                        future.complete(finalWorld);
                    })
                    .exceptionally(t -> {
                        // Even if some chunks fail, complete with the world
                        craftServer.getLogger().warning("Some chunks failed to pre-generate: " + t.getMessage());
                        reportProgress(AsyncWorldProgress.Stage.COMPLETED);
                        future.complete(finalWorld);
                        return null;
                    });
        }
    }

    /**
     * Generates chunks in batches using Paper's async chunk API.
     */
    private CompletableFuture<Void> generateChunkBatches(@NotNull World world, List<int[]> chunks,
                                                          int totalChunks, AsyncWorldProgress.Stage stage) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        int[] completedCount = {0};

        // Process in batches
        generateNextBatch(world, chunks, 0, totalChunks, completedCount, result, stage);

        return result;
    }

    /**
     * Recursively generates batches of chunks.
     */
    private void generateNextBatch(@NotNull World world, List<int[]> chunks, int startIndex,
                                    int totalChunks, int[] completedCount, CompletableFuture<Void> result,
                                    AsyncWorldProgress.Stage stage) {
        if (startIndex >= chunks.size()) {
            result.complete(null);
            return;
        }

        int endIndex = Math.min(startIndex + CHUNK_BATCH_SIZE, chunks.size());
        List<CompletableFuture<Chunk>> batchFutures = new ArrayList<>();

        for (int i = startIndex; i < endIndex; i++) {
            int[] coords = chunks.get(i);
            batchFutures.add(world.getChunkAtAsync(coords[0], coords[1], true));
        }

        CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                .whenComplete((v, t) -> {
                    // Update progress
                    completedCount[0] += batchFutures.size();
                    double progressPercent = (double) completedCount[0] / totalChunks;
                    progress.setStage(stage, progressPercent);
                    progress.setDetails(completedCount[0] + "/" + totalChunks + " chunks");
                    reportPreGenProgress();

                    if (t != null) {
                        craftServer.getLogger().warning("Batch chunk generation error: " + t.getMessage());
                    }

                    // Schedule next batch with a small delay to prevent overwhelming the server
                    if (endIndex < chunks.size()) {
                        craftServer.getServer().execute(() ->
                                generateNextBatch(world, chunks, endIndex, totalChunks, completedCount, result, stage)
                        );
                    } else {
                        result.complete(null);
                    }
                });
    }

    /**
     * Reports pre-generation progress to the callback if one was set.
     */
    private void reportPreGenProgress() {
        if (preGenerateCallback != null) {
            try {
                preGenerateCallback.accept(progress);
            } catch (Throwable ignored) {
                // Don't let callback errors break generation
            }
        }
        // Also notify regular progress callback
        if (progressCallback != null) {
            try {
                progressCallback.accept(progress);
            } catch (Throwable ignored) {
                // Don't let callback errors break generation
            }
        }
    }

    /**
     * Reports progress to the callback if one was set.
     */
    private void reportProgress(@NotNull AsyncWorldProgress.Stage stage) {
        progress.setStage(stage);
        if (progressCallback != null) {
            try {
                progressCallback.accept(progress);
            } catch (Throwable ignored) {
                // Don't let callback errors break world creation
            }
        }
    }
}
