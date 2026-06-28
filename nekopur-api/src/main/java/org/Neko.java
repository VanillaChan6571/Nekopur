package org.nekopur;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

import java.util.concurrent.CompletableFuture;

/**
 * Main entry point for Nekopur-specific APIs.
 * <p>
 * This class provides static access to Nekopur features that extend beyond
 * standard Bukkit/Purpur functionality, particularly for truly asynchronous
 * world creation that minimizes main thread impact.
 *
 * <h2>Basic Usage</h2>
 * <pre>{@code
 * // Simple async world creation
 * Neko.createWorldAsync(new WorldCreator("my_world"))
 *     .thenAccept(world -> {
 *         if (world != null) {
 *             getLogger().info("World created: " + world.getName());
 *         }
 *     });
 * }</pre>
 *
 * <h2>With Template (Recommended for Minigames)</h2>
 * <pre>{@code
 * // Fast world creation from a template
 * Neko.createWorldAsync(new WorldCreator("arena_1"), AsyncWorldOptions.builder()
 *     .template(WorldTemplate.fromWorld(new File("templates/arena")))
 *     .spawnChunks(AsyncWorldOptions.SpawnChunkBehavior.SKIP)
 *     .progressCallback(progress -> getLogger().info(progress.getStage().getDescription()))
 *     .build())
 *     .thenAccept(world -> {
 *         // World is ready, teleport players
 *         for (Player player : gamePlayers) {
 *             player.teleport(world.getSpawnLocation());
 *         }
 *     });
 * }</pre>
 *
 * <h2>Comparison with Bukkit Methods</h2>
 * <table border="1">
 *   <tr><th>Method</th><th>Main Thread Impact</th></tr>
 *   <tr><td>{@code Bukkit.createWorld()}</td><td>Blocks entirely - causes lag spikes</td></tr>
 *   <tr><td>{@code Bukkit.createWorldAsync()}</td><td>Still blocks for I/O operations</td></tr>
 *   <tr><td>{@code Neko.createWorldAsync()}</td><td>Minimal - only essential registration</td></tr>
 * </table>
 *
 * @see AsyncWorldOptions
 * @see WorldTemplate
 * @see AsyncWorldProgress
 */
@NullMarked
public final class Neko {

    private static volatile @Nullable NekoServer server;
    private static final Object lock = new Object();

    private Neko() {
        throw new UnsupportedOperationException("Neko is a static utility class");
    }

    /**
     * Sets the NekoServer implementation.
     * <p>
     * This method is called internally by the server and should not be called by plugins.
     *
     * @param server the NekoServer implementation
     * @throws UnsupportedOperationException if the server has already been set
     */
    public static void setServer(@NotNull NekoServer server) {
        synchronized (lock) {
            if (Neko.server != null) {
                throw new UnsupportedOperationException("Cannot redefine singleton NekoServer");
            }
            Neko.server = server;
        }
    }

    /**
     * Gets the NekoServer implementation.
     * <p>
     * If the server hasn't been explicitly set, this will attempt to create
     * a default implementation using the current Bukkit server.
     *
     * @return the NekoServer instance
     * @throws IllegalStateException if called before the server is initialized
     */
    @NotNull
    public static NekoServer getServer() {
        NekoServer result = server;
        if (result == null) {
            synchronized (lock) {
                result = server;
                if (result == null) {
                    // Try to auto-initialize from Bukkit
                    if (Bukkit.getServer() != null) {
                        try {
                            Class<?> implClass = Class.forName("org.nekopur.NekoServerImpl");
                            Object craftServer = Bukkit.getServer();
                            result = (NekoServer) implClass.getConstructor(
                                    Class.forName("org.bukkit.craftbukkit.CraftServer")
                            ).newInstance(craftServer);
                            server = result;
                        } catch (Exception e) {
                            throw new IllegalStateException("Failed to initialize NekoServer", e);
                        }
                    } else {
                        throw new IllegalStateException("NekoServer has not been initialized yet");
                    }
                }
            }
        }
        return result;
    }

    /**
     * Checks if the NekoServer has been initialized.
     *
     * @return true if the server is available
     */
    public static boolean isAvailable() {
        if (server != null) {
            return true;
        }
        // Check if we can auto-initialize
        return Bukkit.getServer() != null;
    }

    /**
     * Creates a world asynchronously, performing all heavy operations off the main thread.
     * <p>
     * This method provides truly asynchronous world creation by:
     * <ul>
     *   <li>Creating world directory structure on async thread</li>
     *   <li>Creating LevelStorageAccess (file locks) on async thread</li>
     *   <li>Loading/creating world data (level.dat) on async thread</li>
     *   <li>Creating LevelStem and ChunkGenerator on async thread</li>
     *   <li>Constructing ServerLevel on main thread (minimal, fast)</li>
     *   <li>Registering world on main thread (minimal, fast)</li>
     *   <li>Skipping synchronous prepareLevel() entirely</li>
     *   <li>Loading spawn chunks asynchronously based on options</li>
     * </ul>
     * <p>
     * This approach significantly reduces TPS impact compared to
     * {@link org.bukkit.Bukkit#createWorld(WorldCreator)} or even
     * {@link org.bukkit.Bukkit#createWorldAsync(WorldCreator)}.
     *
     * @param creator the WorldCreator with world configuration
     * @param options the async creation options controlling spawn chunk behavior
     * @return a CompletableFuture that completes with the created world, or null if creation failed
     * @throws IllegalArgumentException if creator is null
     * @throws IllegalStateException if called before server initialization
     */
    @NotNull
    public static CompletableFuture<@Nullable World> createWorldAsync(@NotNull WorldCreator creator, @NotNull AsyncWorldOptions options) {
        return getServer().createWorldAsync(creator, options);
    }

    /**
     * Creates a world asynchronously with default options.
     * <p>
     * Equivalent to calling {@link #createWorldAsync(WorldCreator, AsyncWorldOptions)}
     * with {@link AsyncWorldOptions#defaults()}, which uses:
     * <ul>
     *   <li>Spawn chunk behavior: {@link AsyncWorldOptions.SpawnChunkBehavior#ASYNC_FIRE_AND_FORGET}</li>
     *   <li>Generate spawn: true</li>
     * </ul>
     *
     * @param creator the WorldCreator with world configuration
     * @return a CompletableFuture that completes with the created world, or null if creation failed
     * @throws IllegalArgumentException if creator is null
     * @throws IllegalStateException if called before server initialization
     * @see #createWorldAsync(WorldCreator, AsyncWorldOptions)
     */
    @NotNull
    public static CompletableFuture<@Nullable World> createWorldAsync(@NotNull WorldCreator creator) {
        return getServer().createWorldAsync(creator);
    }

    // ===========================================
    // World Pool Convenience Methods
    // ===========================================

    /**
     * Gets or creates a named world pool.
     * <p>
     * Convenience method for {@link NekoServer#getOrCreatePool(String, WorldPoolOptions)}.
     *
     * @param poolName the unique name for this pool
     * @param options the pool configuration options
     * @return the world pool
     * @see NekoServer#getOrCreatePool(String, WorldPoolOptions)
     */
    @NotNull
    public static WorldPool getOrCreatePool(@NotNull String poolName, @NotNull WorldPoolOptions options) {
        return getServer().getOrCreatePool(poolName, options);
    }

    /**
     * Gets an existing world pool by name.
     * <p>
     * Convenience method for {@link NekoServer#getPool(String)}.
     *
     * @param poolName the pool name
     * @return the pool, or null if no pool exists with that name
     */
    @Nullable
    public static WorldPool getPool(@NotNull String poolName) {
        return getServer().getPool(poolName);
    }

    // ===========================================
    // World Unload Convenience Methods
    // ===========================================

    /**
     * Unloads a world asynchronously with the specified options.
     * <p>
     * Convenience method for {@link NekoServer#unloadWorldAsync(World, UnloadOptions)}.
     *
     * @param world the world to unload
     * @param options the unload options
     * @return a CompletableFuture that completes with true if successful
     */
    @NotNull
    public static CompletableFuture<Boolean> unloadWorldAsync(@NotNull World world, @NotNull UnloadOptions options) {
        return getServer().unloadWorldAsync(world, options);
    }

    /**
     * Unloads a world asynchronously with default options (save chunks, keep files).
     * <p>
     * Convenience method for {@link NekoServer#unloadWorldAsync(World)}.
     *
     * @param world the world to unload
     * @return a CompletableFuture that completes with true if successful
     */
    @NotNull
    public static CompletableFuture<Boolean> unloadWorldAsync(@NotNull World world) {
        return getServer().unloadWorldAsync(world);
    }

    /**
     * Quickly discards a world by unloading without saving and deleting files.
     * <p>
     * This is the fastest way to remove a world, useful for minigame scenarios
     * where the world state doesn't need to be preserved.
     *
     * @param world the world to discard
     * @return a CompletableFuture that completes with true if successful
     */
    @NotNull
    public static CompletableFuture<Boolean> discardWorld(@NotNull World world) {
        return getServer().unloadWorldAsync(world, UnloadOptions.discard());
    }
}
