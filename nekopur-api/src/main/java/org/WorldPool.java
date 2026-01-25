package org.nekopur;

import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;

import java.util.concurrent.CompletableFuture;

/**
 * Represents a pool of pre-created worlds for instant acquisition.
 * <p>
 * World pools allow minigame servers to pre-warm worlds during downtime (e.g., lobby phase)
 * so that when a game starts, a world can be acquired almost instantly without any main
 * thread blocking.
 * <p>
 * <h2>Basic Usage</h2>
 * <pre>{@code
 * // Get the pool for UHC worlds
 * WorldPool pool = Neko.getServer().getOrCreatePool("uhc", WorldPoolOptions.builder()
 *     .template(WorldTemplate.fromWorld(new File("templates/uhc")))
 *     .minSize(2)
 *     .maxSize(5)
 *     .build());
 *
 * // Warm up the pool (creates minSize worlds in background)
 * pool.warmup();
 *
 * // Later, when game starts - instant world acquisition
 * pool.acquire().thenAccept(world -> {
 *     // World is ready to use immediately
 *     for (Player p : players) {
 *         p.teleport(world.getSpawnLocation());
 *     }
 * });
 *
 * // When game ends
 * pool.discard(world);  // Async delete
 * // OR
 * pool.release(world);  // Return to pool for reuse
 * }</pre>
 *
 * @see WorldPoolOptions
 * @see NekoServer#getOrCreatePool(String, WorldPoolOptions)
 */
@NullMarked
public interface WorldPool {

    /**
     * Gets the name/identifier of this pool.
     *
     * @return the pool name
     */
    @NotNull
    String getName();

    /**
     * Gets the options used to configure this pool.
     *
     * @return the pool options
     */
    @NotNull
    WorldPoolOptions getOptions();

    /**
     * Gets the number of worlds currently available in the pool.
     * <p>
     * This only counts worlds that are ready to be acquired, not worlds
     * currently in use or being created.
     *
     * @return the number of available worlds
     */
    int getAvailableCount();

    /**
     * Gets the number of worlds currently in use (acquired but not released/discarded).
     *
     * @return the number of worlds in use
     */
    int getInUseCount();

    /**
     * Gets the total number of worlds managed by this pool (available + in use).
     *
     * @return the total world count
     */
    default int getTotalCount() {
        return getAvailableCount() + getInUseCount();
    }

    /**
     * Warms up the pool by creating worlds up to the minimum size.
     * <p>
     * This operation runs asynchronously. Worlds are created in parallel
     * up to the configured parallelism limit.
     *
     * @return a CompletableFuture that completes when warmup is finished
     */
    @NotNull
    CompletableFuture<Void> warmup();

    /**
     * Acquires a world from the pool.
     * <p>
     * If a pre-created world is available, it's returned immediately.
     * If no worlds are available and the pool hasn't reached maxSize,
     * a new world is created.
     * If the pool is at maxSize and all worlds are in use, the future
     * will wait until a world becomes available.
     *
     * @return a CompletableFuture that completes with the acquired world
     */
    @NotNull
    CompletableFuture<World> acquire();

    /**
     * Releases a world back to the pool for reuse.
     * <p>
     * The world will be reset according to the pool's reset strategy
     * before being made available again.
     * <p>
     * If the world was not acquired from this pool, this method does nothing.
     *
     * @param world the world to release
     * @return a CompletableFuture that completes when the world is ready for reuse
     */
    @NotNull
    CompletableFuture<Void> release(@NotNull World world);

    /**
     * Discards a world from the pool entirely.
     * <p>
     * The world is unloaded and its files are deleted asynchronously.
     * If the pool drops below minSize, a new world will be created
     * to replace it.
     * <p>
     * If the world was not acquired from this pool, this method does nothing.
     *
     * @param world the world to discard
     * @return a CompletableFuture that completes when the world is fully removed
     */
    @NotNull
    CompletableFuture<Void> discard(@NotNull World world);

    /**
     * Shuts down this pool, unloading and optionally deleting all managed worlds.
     * <p>
     * After shutdown, the pool cannot be used. Any pending acquire operations
     * will be cancelled.
     *
     * @param deleteWorlds true to delete world files, false to just unload
     * @return a CompletableFuture that completes when shutdown is finished
     */
    @NotNull
    CompletableFuture<Void> shutdown(boolean deleteWorlds);

    /**
     * Checks if this pool has been shut down.
     *
     * @return true if the pool is shut down
     */
    boolean isShutdown();

    /**
     * Gets a snapshot of the pool's current status.
     *
     * @return a snapshot of pool state
     */
    @NotNull
    WorldPoolStatus getStatus();
}
