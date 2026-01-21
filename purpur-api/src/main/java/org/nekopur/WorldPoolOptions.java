package org.nekopur;

import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Options for configuring a {@link WorldPool}.
 * <p>
 * <h2>Template-based Pool (Manhunt, Arena games)</h2>
 * <pre>{@code
 * WorldPoolOptions options = WorldPoolOptions.builder()
 *     .template(WorldTemplate.fromWorld(new File("templates/arena")))
 *     .minSize(3)
 *     .maxSize(10)
 *     .asyncOptions(AsyncWorldOptions.builder()
 *         .spawnChunks(SpawnChunkBehavior.SKIP)
 *         .build())
 *     .build();
 * }</pre>
 *
 * <h2>Fresh-seed Pool (UHC)</h2>
 * <pre>{@code
 * WorldPoolOptions options = WorldPoolOptions.builder()
 *     .worldCreatorSupplier(() -> new WorldCreator("uhc_" + UUID.randomUUID())
 *         .seed(ThreadLocalRandom.current().nextLong()))
 *     .minSize(1)
 *     .maxSize(3)
 *     .asyncOptions(AsyncWorldOptions.builder()
 *         .preGenerateRadius(32)
 *         .spawnChunks(SpawnChunkBehavior.ASYNC_WAIT)
 *         .build())
 *     .build();
 * }</pre>
 *
 * @see WorldPool
 */
@NullMarked
public final class WorldPoolOptions {

    /**
     * Defines how worlds are reset when released back to the pool.
     */
    public enum ResetStrategy {
        /**
         * Don't reset - just make the world available again.
         * <p>
         * Fastest option, but world state persists between uses.
         */
        NONE,

        /**
         * Discard the world and create a fresh one.
         * <p>
         * Most thorough reset, but slower. Good for template-based
         * worlds where you want a clean slate each time.
         */
        RECREATE,

        /**
         * Restore the world from the template.
         * <p>
         * Unloads the world, copies template files again, then reloads.
         * Faster than RECREATE for template-based worlds.
         * Only works if a template is configured.
         */
        RESTORE_TEMPLATE
    }

    private final @Nullable WorldTemplate template;
    private final @Nullable Supplier<WorldCreator> worldCreatorSupplier;
    private final @Nullable String worldNamePrefix;
    private final int minSize;
    private final int maxSize;
    private final int parallelCreation;
    private final AsyncWorldOptions asyncOptions;
    private final ResetStrategy resetStrategy;
    private final @Nullable Consumer<World> worldInitializer;

    private WorldPoolOptions(Builder builder) {
        this.template = builder.template;
        this.worldCreatorSupplier = builder.worldCreatorSupplier;
        this.worldNamePrefix = builder.worldNamePrefix;
        this.minSize = builder.minSize;
        this.maxSize = builder.maxSize;
        this.parallelCreation = builder.parallelCreation;
        this.asyncOptions = builder.asyncOptions;
        this.resetStrategy = builder.resetStrategy;
        this.worldInitializer = builder.worldInitializer;
    }

    /**
     * Gets the world template, if configured.
     *
     * @return the template, or null if using worldCreatorSupplier instead
     */
    @Nullable
    public WorldTemplate getTemplate() {
        return template;
    }

    /**
     * Gets the world creator supplier.
     * <p>
     * If a template is configured, this returns a supplier that creates
     * a WorldCreator with the world name prefix and applies the template.
     *
     * @return the world creator supplier
     */
    @NotNull
    public Supplier<WorldCreator> getWorldCreatorSupplier() {
        if (worldCreatorSupplier != null) {
            return worldCreatorSupplier;
        }
        // Default: create a WorldCreator with the name prefix
        return () -> new WorldCreator(generateWorldName());
    }

    /**
     * Generates a unique world name using the prefix.
     *
     * @return a unique world name
     */
    @NotNull
    public String generateWorldName() {
        String prefix = worldNamePrefix != null ? worldNamePrefix : "pool_world_";
        return prefix + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(10000);
    }

    /**
     * Gets the world name prefix.
     *
     * @return the prefix, or null if using a custom worldCreatorSupplier
     */
    @Nullable
    public String getWorldNamePrefix() {
        return worldNamePrefix;
    }

    /**
     * Gets the minimum pool size.
     * <p>
     * The pool will try to maintain at least this many ready worlds.
     *
     * @return the minimum size
     */
    public int getMinSize() {
        return minSize;
    }

    /**
     * Gets the maximum pool size.
     * <p>
     * The pool will not create more than this many worlds total.
     *
     * @return the maximum size
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * Gets the number of worlds to create in parallel during warmup.
     *
     * @return the parallel creation count
     */
    public int getParallelCreation() {
        return parallelCreation;
    }

    /**
     * Gets the async world options used when creating worlds.
     *
     * @return the async options
     */
    @NotNull
    public AsyncWorldOptions getAsyncOptions() {
        return asyncOptions;
    }

    /**
     * Gets the reset strategy used when worlds are released back to the pool.
     *
     * @return the reset strategy
     */
    @NotNull
    public ResetStrategy getResetStrategy() {
        return resetStrategy;
    }

    /**
     * Gets the world initializer callback, if configured.
     * <p>
     * This callback is invoked on each newly created world before it's
     * made available in the pool. Useful for setting game rules, spawn
     * locations, etc.
     *
     * @return the initializer, or null if none configured
     */
    @Nullable
    public Consumer<World> getWorldInitializer() {
        return worldInitializer;
    }

    /**
     * Creates a new builder for WorldPoolOptions.
     *
     * @return a new builder
     */
    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating {@link WorldPoolOptions} instances.
     */
    public static final class Builder {
        private @Nullable WorldTemplate template = null;
        private @Nullable Supplier<WorldCreator> worldCreatorSupplier = null;
        private @Nullable String worldNamePrefix = null;
        private int minSize = 1;
        private int maxSize = 5;
        private int parallelCreation = 2;
        private AsyncWorldOptions asyncOptions = AsyncWorldOptions.defaults();
        private ResetStrategy resetStrategy = ResetStrategy.RECREATE;
        private @Nullable Consumer<World> worldInitializer = null;

        private Builder() {}

        /**
         * Sets the world template for creating pool worlds.
         * <p>
         * When using a template, each pool world is created by copying
         * the template files and then loading the world.
         *
         * @param template the world template
         * @return this builder
         */
        @NotNull
        public Builder template(@NotNull WorldTemplate template) {
            this.template = template;
            return this;
        }

        /**
         * Sets a supplier that provides WorldCreator instances for new worlds.
         * <p>
         * This is useful for creating fresh-seed worlds where each world
         * needs a unique seed. The supplier is called each time a new
         * world needs to be created.
         * <p>
         * Example for UHC:
         * <pre>{@code
         * .worldCreatorSupplier(() -> new WorldCreator("uhc_" + UUID.randomUUID())
         *     .seed(ThreadLocalRandom.current().nextLong())
         *     .environment(World.Environment.NORMAL))
         * }</pre>
         *
         * @param supplier the WorldCreator supplier
         * @return this builder
         */
        @NotNull
        public Builder worldCreatorSupplier(@NotNull Supplier<WorldCreator> supplier) {
            this.worldCreatorSupplier = supplier;
            return this;
        }

        /**
         * Sets the prefix used when generating world names.
         * <p>
         * Only used if worldCreatorSupplier is not set. Each world will be
         * named "{prefix}{timestamp}_{random}".
         *
         * @param prefix the world name prefix
         * @return this builder
         */
        @NotNull
        public Builder worldNamePrefix(@NotNull String prefix) {
            this.worldNamePrefix = prefix;
            return this;
        }

        /**
         * Sets the minimum pool size.
         * <p>
         * The pool will try to maintain at least this many ready worlds.
         * After warmup() is called, this many worlds will be created.
         * When a world is discarded, a new one is created to replace it
         * if the pool drops below this size.
         * <p>
         * Default: 1
         *
         * @param minSize the minimum size (must be >= 1)
         * @return this builder
         */
        @NotNull
        public Builder minSize(int minSize) {
            if (minSize < 1) {
                throw new IllegalArgumentException("minSize must be at least 1");
            }
            this.minSize = minSize;
            return this;
        }

        /**
         * Sets the maximum pool size.
         * <p>
         * The pool will not create more than this many worlds total
         * (available + in use). If all worlds are in use and a new
         * acquire() is called, it will wait until a world is released
         * or discarded.
         * <p>
         * Default: 5
         *
         * @param maxSize the maximum size (must be >= minSize)
         * @return this builder
         */
        @NotNull
        public Builder maxSize(int maxSize) {
            if (maxSize < 1) {
                throw new IllegalArgumentException("maxSize must be at least 1");
            }
            this.maxSize = maxSize;
            return this;
        }

        /**
         * Sets how many worlds to create in parallel during warmup.
         * <p>
         * Higher values warm up faster but use more resources.
         * Default: 2
         *
         * @param parallelCreation the parallel creation count
         * @return this builder
         */
        @NotNull
        public Builder parallelCreation(int parallelCreation) {
            if (parallelCreation < 1) {
                throw new IllegalArgumentException("parallelCreation must be at least 1");
            }
            this.parallelCreation = parallelCreation;
            return this;
        }

        /**
         * Sets the async world options used when creating worlds.
         *
         * @param options the async options
         * @return this builder
         */
        @NotNull
        public Builder asyncOptions(@NotNull AsyncWorldOptions options) {
            this.asyncOptions = options;
            return this;
        }

        /**
         * Sets the reset strategy used when worlds are released back to the pool.
         * <p>
         * Default: {@link ResetStrategy#RECREATE}
         *
         * @param strategy the reset strategy
         * @return this builder
         */
        @NotNull
        public Builder resetStrategy(@NotNull ResetStrategy strategy) {
            this.resetStrategy = strategy;
            return this;
        }

        /**
         * Sets a callback to initialize newly created worlds.
         * <p>
         * This callback is invoked on the main thread after each world is
         * created but before it's made available in the pool. Use it to
         * set game rules, world border, spawn location, etc.
         *
         * @param initializer the initializer callback
         * @return this builder
         */
        @NotNull
        public Builder worldInitializer(@NotNull Consumer<World> initializer) {
            this.worldInitializer = initializer;
            return this;
        }

        /**
         * Builds the WorldPoolOptions.
         *
         * @return the built options
         * @throws IllegalArgumentException if configuration is invalid
         */
        @NotNull
        public WorldPoolOptions build() {
            if (maxSize < minSize) {
                throw new IllegalArgumentException("maxSize must be >= minSize");
            }
            if (template == null && worldCreatorSupplier == null && worldNamePrefix == null) {
                worldNamePrefix = "pool_world_";
            }
            return new WorldPoolOptions(this);
        }
    }
}
