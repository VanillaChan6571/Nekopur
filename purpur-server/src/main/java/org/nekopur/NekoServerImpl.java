package org.nekopur;

import io.papermc.paper.util.MCUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.craftbukkit.CraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.nekopur.world.AsyncWorldCreationTask;
import org.nekopur.world.WorldPoolImpl;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Implementation of NekoServer that wraps CraftServer.
 */
public class NekoServerImpl implements NekoServer {

    public static final ThreadLocal<Boolean> SKIP_SPAWN_PREP = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<@Nullable AsyncWorldOptions> ASYNC_WORLD_OPTIONS = new ThreadLocal<>();
    private static final ThreadLocal<@Nullable CompletableFuture<Void>> ASYNC_SPAWN_FUTURE = new ThreadLocal<>();

    private final CraftServer craftServer;
    private final Map<String, WorldPool> worldPools = new ConcurrentHashMap<>();

    public NekoServerImpl(@NotNull CraftServer craftServer) {
        this.craftServer = craftServer;
    }

    @Override
    @NotNull
    public CompletableFuture<World> createWorldAsync(@NotNull WorldCreator creator, @NotNull AsyncWorldOptions options) {
        if (creator == null) {
            throw new IllegalArgumentException("WorldCreator cannot be null");
        }
        if (options == null) {
            throw new IllegalArgumentException("AsyncWorldOptions cannot be null");
        }
        return new AsyncWorldCreationTask(craftServer, creator, options).start();
    }

    public static void setAsyncWorldOptions(@Nullable AsyncWorldOptions options) {
        ASYNC_WORLD_OPTIONS.set(options);
        SKIP_SPAWN_PREP.set(Boolean.TRUE);
    }

    public static void clearAsyncWorldOptions() {
        ASYNC_WORLD_OPTIONS.remove();
        SKIP_SPAWN_PREP.set(Boolean.FALSE);
    }

    public static @Nullable AsyncWorldOptions getAsyncWorldOptions() {
        return ASYNC_WORLD_OPTIONS.get();
    }

    public static void setAsyncSpawnFuture(@Nullable CompletableFuture<Void> future) {
        ASYNC_SPAWN_FUTURE.set(future);
    }

    public static @Nullable CompletableFuture<Void> consumeAsyncSpawnFuture() {

        CompletableFuture<Void> future = ASYNC_SPAWN_FUTURE.get();
        ASYNC_SPAWN_FUTURE.remove();
        return future;
    }

    // ===========================================
    // Async World Unloading Implementation
    // ===========================================

    @Override
    @NotNull
    public CompletableFuture<Boolean> unloadWorldAsync(@NotNull World world, @NotNull UnloadOptions options) {
        if (world == null) {
            throw new IllegalArgumentException("World cannot be null");
        }
        if (options == null) {
            throw new IllegalArgumentException("UnloadOptions cannot be null");
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        File worldFolder = world.getWorldFolder();

        // Schedule unload on main thread
        if (Bukkit.isPrimaryThread()) {
            performUnload(world, options, worldFolder, future);
        } else {
            craftServer.getServer().execute(() -> performUnload(world, options, worldFolder, future));
        }

        return future;
    }

    /**
     * Performs the world unload on the main thread.
     */
    private void performUnload(World world, UnloadOptions options, File worldFolder, CompletableFuture<Boolean> future) {
        try {
            String worldName = world.getName();

            // Check if world is still loaded
            if (Bukkit.getWorld(worldName) == null) {
                // World already unloaded
                if (options.shouldDeleteWorldFolder()) {
                    deleteWorldFolderAsync(worldFolder).thenRun(() -> {
                        notifyCallback(options.getCompletionCallback(), true);
                        future.complete(true);
                    });
                } else {
                    notifyCallback(options.getCompletionCallback(), true);
                    future.complete(true);
                }
                return;
            }

            // Unload the world
            boolean success = craftServer.unloadWorld(world, options.shouldSaveChunks());

            if (!success) {
                notifyCallback(options.getCompletionCallback(), false);
                future.complete(false);
                return;
            }

            // Delete world folder if requested
            if (options.shouldDeleteWorldFolder()) {
                deleteWorldFolderAsync(worldFolder).thenRun(() -> {
                    notifyCallback(options.getCompletionCallback(), true);
                    future.complete(true);
                }).exceptionally(t -> {
                    craftServer.getLogger().warning("Failed to delete world folder: " + worldFolder);
                    notifyCallback(options.getCompletionCallback(), true); // Still successful unload
                    future.complete(true);
                    return null;
                });
            } else {
                notifyCallback(options.getCompletionCallback(), true);
                future.complete(true);
            }

        } catch (Exception e) {
            craftServer.getLogger().warning("Failed to unload world: " + e.getMessage());
            notifyCallback(options.getCompletionCallback(), false);
            future.completeExceptionally(e);
        }
    }

    /**
     * Deletes a world folder asynchronously.
     */
    private CompletableFuture<Void> deleteWorldFolderAsync(File worldFolder) {
        return CompletableFuture.runAsync(() -> {
            try {
                deleteDirectoryRecursively(worldFolder.toPath());
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete world folder: " + worldFolder, e);
            }
        }, MCUtil.ASYNC_EXECUTOR);
    }

    /**
     * Recursively deletes a directory.
     */
    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Notifies the completion callback if present.
     */
    private void notifyCallback(@Nullable Consumer<Boolean> callback, boolean success) {
        if (callback != null) {
            try {
                if (Bukkit.isPrimaryThread()) {
                    callback.accept(success);
                } else {
                    craftServer.getServer().execute(() -> callback.accept(success));
                }
            } catch (Exception ignored) {
                // Don't let callback errors break unloading
            }
        }
    }

    // ===========================================
    // World Pool Implementation
    // ===========================================

    @Override
    @NotNull
    public WorldPool getOrCreatePool(@NotNull String poolName, @NotNull WorldPoolOptions options) {
        if (poolName == null || poolName.isEmpty()) {
            throw new IllegalArgumentException("Pool name cannot be null or empty");
        }
        if (options == null) {
            throw new IllegalArgumentException("WorldPoolOptions cannot be null");
        }

        return worldPools.computeIfAbsent(poolName, name -> new WorldPoolImpl(name, options, craftServer));
    }

    @Override
    @Nullable
    public WorldPool getPool(@NotNull String poolName) {
        if (poolName == null) {
            return null;
        }
        return worldPools.get(poolName);
    }

    @Override
    @NotNull
    public Collection<WorldPool> getPools() {
        return Collections.unmodifiableCollection(worldPools.values());
    }

    @Override
    @NotNull
    public CompletableFuture<Void> shutdownPool(@NotNull String poolName, boolean deleteWorlds) {
        WorldPool pool = worldPools.remove(poolName);
        if (pool == null) {
            return CompletableFuture.completedFuture(null);
        }
        return pool.shutdown(deleteWorlds);
    }

    @Override
    @NotNull
    public CompletableFuture<Void> shutdownAllPools(boolean deleteWorlds) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String poolName : new ArrayList<>(worldPools.keySet())) {
            futures.add(shutdownPool(poolName, deleteWorlds));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
}
