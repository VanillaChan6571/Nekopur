package org.nekopur.world;

import io.papermc.paper.util.MCUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.craftbukkit.CraftServer;
import org.jetbrains.annotations.NotNull;
import org.nekopur.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Implementation of {@link WorldPool} for managing pre-created worlds.
 */
public class WorldPoolImpl implements WorldPool {

    private final String name;
    private final WorldPoolOptions options;
    private final CraftServer craftServer;

    // Pool state
    private final Queue<World> availableWorlds = new ConcurrentLinkedQueue<>();
    private final Set<World> inUseWorlds = ConcurrentHashMap.newKeySet();
    private final Set<String> creatingWorldNames = ConcurrentHashMap.newKeySet();
    private final Queue<CompletableFuture<World>> waitingAcquires = new ConcurrentLinkedQueue<>();
    private final Deque<String> deletedWorldNames = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicInteger totalCreated = new AtomicInteger(0);
    private static final int DELETED_HISTORY_LIMIT = 100;

    public WorldPoolImpl(@NotNull String name, @NotNull WorldPoolOptions options, @NotNull CraftServer craftServer) {
        this.name = name;
        this.options = options;
        this.craftServer = craftServer;
    }

    @Override
    @NotNull
    public String getName() {
        return name;
    }

    @Override
    @NotNull
    public WorldPoolOptions getOptions() {
        return options;
    }

    @Override
    public int getAvailableCount() {
        return availableWorlds.size();
    }

    @Override
    public int getInUseCount() {
        return inUseWorlds.size();
    }

    @Override
    @NotNull
    public CompletableFuture<Void> warmup() {
        if (shutdown.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Pool is shut down"));
        }

        int toCreate = options.getMinSize() - getTotalCount();
        if (toCreate <= 0) {
            return CompletableFuture.completedFuture(null);
        }

        // Create worlds in parallel batches
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        int parallelism = Math.min(toCreate, options.getParallelCreation());

        for (int i = 0; i < toCreate; i++) {
            // Stagger creation to avoid overwhelming the server
            int delay = (i / parallelism) * 100; // 100ms between batches
            final int index = i;

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, MCUtil.ASYNC_EXECUTOR).thenCompose(v -> createPoolWorld().thenAccept(w -> {}));

            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    @Override
    @NotNull
    public CompletableFuture<World> acquire() {
        if (shutdown.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Pool is shut down"));
        }

        // Try to get an available world
        World world = availableWorlds.poll();
        if (world != null) {
            inUseWorlds.add(world);
            return CompletableFuture.completedFuture(world);
        }

        // Check if we can create a new world
        if (getTotalCount() + creatingWorldNames.size() < options.getMaxSize()) {
            return createPoolWorld().thenApply(w -> {
                if (w == null) {
                    throw new RuntimeException("World creation returned null");
                }
                // Move from available to in-use
                availableWorlds.remove(w);
                inUseWorlds.add(w);
                return w;
            });
        }

        // Pool is full, wait for a world to become available
        CompletableFuture<World> future = new CompletableFuture<>();
        waitingAcquires.add(future);
        return future;
    }

    @Override
    @NotNull
    public CompletableFuture<Void> release(@NotNull World world) {
        if (!inUseWorlds.remove(world)) {
            // World was not from this pool
            return CompletableFuture.completedFuture(null);
        }

        if (shutdown.get()) {
            // Pool is shutting down, discard instead
            return discardInternal(world);
        }

        return resetWorld(world).thenRun(() -> {
            // Check if anyone is waiting
            CompletableFuture<World> waiting = waitingAcquires.poll();
            if (waiting != null) {
                inUseWorlds.add(world);
                waiting.complete(world);
            } else {
                availableWorlds.add(world);
            }
        });
    }

    @Override
    @NotNull
    public CompletableFuture<Void> discard(@NotNull World world) {
        if (!inUseWorlds.remove(world)) {
            // World was not from this pool
            return CompletableFuture.completedFuture(null);
        }

        return discardInternal(world).thenRun(() -> {
            // Replenish if below min size
            if (!shutdown.get() && getTotalCount() < options.getMinSize()) {
                createPoolWorld();
            }
        });
    }

    @Override
    @NotNull
    public CompletableFuture<Void> shutdown(boolean deleteWorlds) {
        if (!shutdown.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }

        // Cancel waiting acquires
        CompletableFuture<World> waiting;
        while ((waiting = waitingAcquires.poll()) != null) {
            waiting.completeExceptionally(new IllegalStateException("Pool is shutting down"));
        }

        // Collect all worlds to unload
        List<World> allWorlds = new ArrayList<>();
        allWorlds.addAll(availableWorlds);
        allWorlds.addAll(inUseWorlds);

        availableWorlds.clear();
        inUseWorlds.clear();

        // Unload all worlds
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (World world : allWorlds) {
            futures.add(discardInternal(world));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    @Override
    public @NotNull WorldPoolStatus getStatus() {
        return new WorldPoolStatus(
                getInUseWorldNames(),
                getAvailableWorldNames(),
                getCreatingWorldNames(),
                getWaitingAcquireCount(),
                getDeletedWorldNames(),
                shutdown.get()
        );
    }

    public @NotNull List<String> getAvailableWorldNames() {
        return availableWorlds.stream()
                .map(World::getName)
                .sorted()
                .toList();
    }

    public @NotNull List<String> getInUseWorldNames() {
        return inUseWorlds.stream()
                .map(World::getName)
                .sorted()
                .toList();
    }

    public @NotNull List<String> getCreatingWorldNames() {
        return creatingWorldNames.stream()
                .sorted()
                .toList();
    }

    public int getWaitingAcquireCount() {
        return waitingAcquires.size();
    }

    public @NotNull List<String> getDeletedWorldNames() {
        return deletedWorldNames.stream()
                .distinct()
                .toList();
    }

    /**
     * Creates a new world for the pool.
     */
    private CompletableFuture<World> createPoolWorld() {
        if (shutdown.get()) {
            return CompletableFuture.completedFuture(null);
        }

        WorldCreator creator = options.getWorldCreatorSupplier().get();
        String worldName = creator.name();
        creatingWorldNames.add(worldName);

        // Apply template to async options if we have one
        AsyncWorldOptions asyncOptions = options.getAsyncOptions();
        if (options.getTemplate() != null) {
            asyncOptions = AsyncWorldOptions.builder()
                    .spawnChunks(asyncOptions.getSpawnChunkBehavior())
                    .progressCallback(asyncOptions.getProgressCallback())
                    .generateSpawn(asyncOptions.isGenerateSpawn())
                    .template(options.getTemplate())
                    .preGenerateRadius(asyncOptions.getPreGenerateRadius())
                    .preGenerateCallback(asyncOptions.getPreGenerateCallback())
                    .build();
        }

        return new AsyncWorldCreationTask(craftServer, creator, asyncOptions)
                .start()
                .whenComplete((world, t) -> creatingWorldNames.remove(worldName))
                .thenApply(world -> {
                    if (world == null) {
                        throw new RuntimeException("World creation returned null: " + worldName);
                    }

                    totalCreated.incrementAndGet();

                    // Run initializer if present
                    Consumer<World> initializer = options.getWorldInitializer();
                    if (initializer != null) {
                        try {
                            if (Bukkit.isPrimaryThread()) {
                                initializer.accept(world);
                            } else {
                                craftServer.getServer().execute(() -> initializer.accept(world));
                            }
                        } catch (Exception e) {
                            craftServer.getLogger().log(Level.WARNING,
                                    "[WorldPool:" + name + "] World initializer threw exception", e);
                        }
                    }

                    // Check if anyone is waiting
                    CompletableFuture<World> waiting = waitingAcquires.poll();
                    if (waiting != null) {
                        inUseWorlds.add(world);
                        waiting.complete(world);
                    } else {
                        availableWorlds.add(world);
                    }

                    return world;
                })
                .exceptionally(t -> {
                    craftServer.getLogger().log(Level.WARNING,
                            "[WorldPool:" + name + "] Failed to create world: " + worldName, t);
                    return null;
                });
    }

    /**
     * Resets a world according to the reset strategy.
     */
    private CompletableFuture<Void> resetWorld(@NotNull World world) {
        WorldPoolOptions.ResetStrategy strategy = options.getResetStrategy();

        switch (strategy) {
            case NONE:
                return CompletableFuture.completedFuture(null);

            case RECREATE:
                // Discard and create fresh
                return discardInternal(world).thenCompose(v -> createPoolWorld().thenAccept(w -> {}));

            case RESTORE_TEMPLATE:
                if (options.getTemplate() == null) {
                    // Fall back to RECREATE if no template
                    return discardInternal(world).thenCompose(v -> createPoolWorld().thenAccept(w -> {}));
                }
                // Unload, restore template, reload
                return restoreFromTemplate(world);

            default:
                return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Restores a world from template.
     */
    private CompletableFuture<Void> restoreFromTemplate(@NotNull World world) {
        String worldName = world.getName();
        WorldCreator creator = new WorldCreator(worldName);

        // Copy relevant settings from the original world
        creator.environment(world.getEnvironment());
        creator.seed(world.getSeed());

        // Unload the world first
        return Neko.getServer().unloadWorldAsync(world, UnloadOptions.builder()
                        .saveChunks(false)
                        .deleteWorldFolder(false)
                        .build())
                .thenCompose(success -> {
                    if (!success) {
                        return CompletableFuture.failedFuture(
                                new RuntimeException("Failed to unload world for reset: " + worldName));
                    }

                    // Copy template files async
                    return CompletableFuture.runAsync(() -> {
                        try {
                            java.nio.file.Path worldFolder = craftServer.getWorldContainer().toPath().resolve(worldName);
                            copyTemplateFiles(options.getTemplate(), worldFolder);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to copy template files", e);
                        }
                    }, MCUtil.ASYNC_EXECUTOR);
                })
                .thenCompose(v -> {
                    // Reload the world
                    AsyncWorldOptions asyncOptions = options.getAsyncOptions();
                    if (options.getTemplate() != null) {
                        asyncOptions = AsyncWorldOptions.builder()
                                .spawnChunks(asyncOptions.getSpawnChunkBehavior())
                                .progressCallback(asyncOptions.getProgressCallback())
                                .generateSpawn(asyncOptions.isGenerateSpawn())
                                .template(options.getTemplate())
                                .preGenerateRadius(asyncOptions.getPreGenerateRadius())
                                .preGenerateCallback(asyncOptions.getPreGenerateCallback())
                                .build();
                    }
                    return new AsyncWorldCreationTask(craftServer, creator, asyncOptions).start();
                })
                .thenAccept(newWorld -> {
                    if (newWorld != null) {
                        // Run initializer if present
                        Consumer<World> initializer = options.getWorldInitializer();
                        if (initializer != null) {
                            try {
                                if (Bukkit.isPrimaryThread()) {
                                    initializer.accept(newWorld);
                                } else {
                                    craftServer.getServer().execute(() -> initializer.accept(newWorld));
                                }
                            } catch (Exception e) {
                                craftServer.getLogger().log(Level.WARNING,
                                        "[WorldPool:" + name + "] World initializer threw exception", e);
                            }
                        }
                        availableWorlds.add(newWorld);
                    }
                });
    }

    /**
     * Copies template files to target directory.
     */
    private void copyTemplateFiles(WorldTemplate template, java.nio.file.Path targetDir) throws java.io.IOException {
        java.nio.file.Path templatePath = template.getTemplatePath();

        // Always copy level.dat if it exists
        java.nio.file.Path levelDat = templatePath.resolve("level.dat");
        if (java.nio.file.Files.exists(levelDat)) {
            java.nio.file.Files.copy(levelDat, targetDir.resolve("level.dat"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
    }

    /**
     * Recursively copies a directory.
     */
    private void copyDirectory(java.nio.file.Path source, java.nio.file.Path target) throws java.io.IOException {
        if (!java.nio.file.Files.exists(source)) {
            return;
        }

        java.nio.file.Files.createDirectories(target);

        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(source)) {
            stream.forEach(sourcePath -> {
                try {
                    java.nio.file.Path targetPath = target.resolve(source.relativize(sourcePath));
                    if (java.nio.file.Files.isDirectory(sourcePath)) {
                        java.nio.file.Files.createDirectories(targetPath);
                    } else {
                        java.nio.file.Files.copy(sourcePath, targetPath,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (java.io.IOException e) {
                    throw new RuntimeException("Failed to copy: " + sourcePath, e);
                }
            });
        }
    }

    /**
     * Discards a world internally (unload + delete).
     */
    private CompletableFuture<Void> discardInternal(@NotNull World world) {
        String worldName = world.getName();
        return Neko.getServer().unloadWorldAsync(world, UnloadOptions.discard())
                .whenComplete((success, t) -> {
                    if (Boolean.TRUE.equals(success)) {
                        recordDeletedWorld(worldName);
                    }
                })
                .thenApply(success -> null);
    }

    private void recordDeletedWorld(@NotNull String worldName) {
        deletedWorldNames.addFirst(worldName);
        while (deletedWorldNames.size() > DELETED_HISTORY_LIMIT) {
            deletedWorldNames.pollLast();
        }
    }
}
