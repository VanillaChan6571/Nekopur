package org.nekopur;

import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * Server interface for Neko-specific functionality.
 * <p>
 * This interface is implemented by the server implementation and provides
 * access to Nekopur-specific features that bypass standard Bukkit APIs.
 */
@NullMarked
public interface NekoServer {

    // ===========================================
    // Async World Creation
    // ===========================================

    /**
     * Creates a world asynchronously, performing all heavy operations off the main thread.
     * <p>
     * Unlike {@link org.bukkit.Bukkit#createWorldAsync(WorldCreator)}, this method performs
     * the actual heavy lifting (LevelStorageSource I/O, level.dat parsing, etc.) on async
     * threads, only synchronizing to the main thread for the minimal required ServerLevel
     * construction and registration.
     * <p>
     * The method completely bypasses {@code prepareLevel()} and instead loads spawn chunks
     * asynchronously based on the provided options.
     *
     * @param creator the WorldCreator with world configuration
     * @param options the async creation options
     * @return a CompletableFuture that completes with the created world
     * @throws IllegalArgumentException if creator is null
     */
    @NotNull
    CompletableFuture<@Nullable World> createWorldAsync(@NotNull WorldCreator creator, @NotNull AsyncWorldOptions options);

    /**
     * Creates a world asynchronously with default options.
     * <p>
     * This is equivalent to calling {@link #createWorldAsync(WorldCreator, AsyncWorldOptions)}
     * with {@link AsyncWorldOptions#defaults()}.
     *
     * @param creator the WorldCreator with world configuration
     * @return a CompletableFuture that completes with the created world
     * @throws IllegalArgumentException if creator is null
     * @see #createWorldAsync(WorldCreator, AsyncWorldOptions)
     */
    @NotNull
    default CompletableFuture<@Nullable World> createWorldAsync(@NotNull WorldCreator creator) {
        return createWorldAsync(creator, AsyncWorldOptions.defaults());
    }

    // ===========================================
    // Async World Unloading
    // ===========================================

    /**
     * Unloads a world asynchronously with the specified options.
     * <p>
     * This method provides optimized world unloading for minigame scenarios:
     * <ul>
     *   <li>Skip chunk saving when the world will be deleted</li>
     *   <li>Delete world files asynchronously after unloading</li>
     *   <li>Minimal main thread impact</li>
     * </ul>
     *
     * @param world the world to unload
     * @param options the unload options
     * @return a CompletableFuture that completes with true if successful
     */
    @NotNull
    CompletableFuture<Boolean> unloadWorldAsync(@NotNull World world, @NotNull UnloadOptions options);

    /**
     * Unloads a world asynchronously with default options (save chunks, keep files).
     *
     * @param world the world to unload
     * @return a CompletableFuture that completes with true if successful
     */
    @NotNull
    default CompletableFuture<Boolean> unloadWorldAsync(@NotNull World world) {
        return unloadWorldAsync(world, UnloadOptions.safe());
    }

    // ===========================================
    // World Pool Management
    // ===========================================

    /**
     * Gets or creates a named world pool.
     * <p>
     * If a pool with the given name already exists, it is returned.
     * Otherwise, a new pool is created with the provided options.
     * <p>
     * Example:
     * <pre>{@code
     * WorldPool pool = Neko.getServer().getOrCreatePool("uhc", WorldPoolOptions.builder()
     *     .template(WorldTemplate.fromWorld(new File("templates/uhc")))
     *     .minSize(2)
     *     .maxSize(5)
     *     .build());
     * }</pre>
     *
     * @param poolName the unique name for this pool
     * @param options the pool configuration options
     * @return the world pool
     * @throws IllegalArgumentException if poolName is empty
     */
    @NotNull
    WorldPool getOrCreatePool(@NotNull String poolName, @NotNull WorldPoolOptions options);

    /**
     * Gets an existing world pool by name.
     *
     * @param poolName the pool name
     * @return the pool, or null if no pool exists with that name
     */
    @Nullable
    WorldPool getPool(@NotNull String poolName);

    /**
     * Gets all active world pools.
     *
     * @return an unmodifiable collection of all pools
     */
    @NotNull
    Collection<WorldPool> getPools();

    /**
     * Shuts down and removes a world pool.
     * <p>
     * This unloads all worlds managed by the pool and optionally deletes them.
     *
     * @param poolName the pool name
     * @param deleteWorlds true to delete world files, false to just unload
     * @return a CompletableFuture that completes when shutdown is finished
     */
    @NotNull
    CompletableFuture<Void> shutdownPool(@NotNull String poolName, boolean deleteWorlds);

    /**
     * Shuts down all world pools.
     *
     * @param deleteWorlds true to delete world files, false to just unload
     * @return a CompletableFuture that completes when all pools are shut down
     */
    @NotNull
    CompletableFuture<Void> shutdownAllPools(boolean deleteWorlds);
}
