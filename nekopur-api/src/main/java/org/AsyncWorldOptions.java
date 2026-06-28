package org.nekopur;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

import java.util.function.Consumer;

/**
 * Options for customizing async world creation behavior.
 * <p>
 * Use {@link #builder()} to create new instances.
 * <p>
 * <h2>Basic Usage</h2>
 * <pre>{@code
 * AsyncWorldOptions options = AsyncWorldOptions.builder()
 *     .spawnChunks(SpawnChunkBehavior.ASYNC_WAIT)
 *     .build();
 * }</pre>
 *
 * <h2>With Pre-generation (UHC)</h2>
 * <pre>{@code
 * AsyncWorldOptions options = AsyncWorldOptions.builder()
 *     .preGenerateRadius(32)  // Pre-generate 32 chunk radius (~4000 chunks)
 *     .preGenerateCallback(progress -> {
 *         broadcast("World gen: " + (int)(progress.getProgress() * 100) + "%");
 *     })
 *     .spawnChunks(SpawnChunkBehavior.ASYNC_WAIT)
 *     .build();
 * }</pre>
 */
@NullMarked
public final class AsyncWorldOptions {

    /**
     * Defines how spawn chunks are handled during async world creation.
     */
    public enum SpawnChunkBehavior {
        /**
         * Don't load spawn chunks at all.
         * <p>
         * This is the fastest option. The world will be usable immediately
         * but spawn chunks won't be preloaded.
         */
        SKIP,

        /**
         * Load spawn chunks asynchronously and complete the future immediately.
         * <p>
         * The world will be returned as soon as it's registered, while spawn
         * chunks continue loading in the background. This is the default behavior.
         */
        ASYNC_FIRE_AND_FORGET,

        /**
         * Load spawn chunks asynchronously and wait for completion.
         * <p>
         * The future will only complete once all spawn chunks are loaded.
         * This takes longer but ensures spawn chunks are ready when the
         * future completes.
         */
        ASYNC_WAIT
    }

    private final SpawnChunkBehavior spawnChunkBehavior;
    private final @Nullable Consumer<AsyncWorldProgress> progressCallback;
    private final boolean generateSpawn;
    private final @Nullable WorldTemplate template;
    private final int preGenerateRadius;
    private final @Nullable Consumer<AsyncWorldProgress> preGenerateCallback;

    private AsyncWorldOptions(Builder builder) {
        this.spawnChunkBehavior = builder.spawnChunkBehavior;
        this.progressCallback = builder.progressCallback;
        this.generateSpawn = builder.generateSpawn;
        this.template = builder.template;
        this.preGenerateRadius = builder.preGenerateRadius;
        this.preGenerateCallback = builder.preGenerateCallback;
    }

    /**
     * Gets the spawn chunk loading behavior.
     *
     * @return the spawn chunk behavior
     */
    @NotNull
    public SpawnChunkBehavior getSpawnChunkBehavior() {
        return spawnChunkBehavior;
    }

    /**
     * Gets the progress callback, if one was set.
     *
     * @return the progress callback, or null if none was set
     */
    @Nullable
    public Consumer<AsyncWorldProgress> getProgressCallback() {
        return progressCallback;
    }

    /**
     * Whether to generate spawn point for the world.
     *
     * @return true if spawn should be generated
     */
    public boolean isGenerateSpawn() {
        return generateSpawn;
    }

    /**
     * Gets the world template, if one was set.
     * <p>
     * When a template is set, the world creation process will copy files
     * from the template instead of generating them from scratch, which
     * is significantly faster.
     *
     * @return the world template, or null if none was set
     */
    @Nullable
    public WorldTemplate getTemplate() {
        return template;
    }

    /**
     * Gets the pre-generation radius in chunks.
     * <p>
     * If greater than 0, chunks within this radius from spawn will be
     * pre-generated asynchronously using parallel workers.
     *
     * @return the pre-generation radius in chunks, or 0 if disabled
     */
    public int getPreGenerateRadius() {
        return preGenerateRadius;
    }

    /**
     * Gets the pre-generation progress callback, if one was set.
     * <p>
     * This callback is invoked periodically during chunk pre-generation
     * to report progress.
     *
     * @return the callback, or null if none was set
     */
    @Nullable
    public Consumer<AsyncWorldProgress> getPreGenerateCallback() {
        return preGenerateCallback;
    }

    /**
     * Creates a new builder with default options.
     *
     * @return a new builder
     */
    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the default options.
     *
     * @return default options instance
     */
    @NotNull
    public static AsyncWorldOptions defaults() {
        return new Builder().build();
    }

    /**
     * Builder for creating {@link AsyncWorldOptions} instances.
     */
    public static final class Builder {
        private SpawnChunkBehavior spawnChunkBehavior = SpawnChunkBehavior.SKIP;
        private @Nullable Consumer<AsyncWorldProgress> progressCallback = null;
        private boolean generateSpawn = false;
        private @Nullable WorldTemplate template = null;
        private int preGenerateRadius = 0;
        private @Nullable Consumer<AsyncWorldProgress> preGenerateCallback = null;

        private Builder() {}

        /**
         * Sets how spawn chunks should be handled.
         *
         * @param behavior the spawn chunk behavior
         * @return this builder
         */
        @NotNull
        public Builder spawnChunks(@NotNull SpawnChunkBehavior behavior) {
            this.spawnChunkBehavior = behavior;
            return this;
        }

        /**
         * Sets how spawn chunks should be handled.
         * <p>
         * Alias for {@link #spawnChunks(SpawnChunkBehavior)}.
         *
         * @param behavior the spawn chunk behavior
         * @return this builder
         */
        @NotNull
        public Builder spawnChunkBehavior(@NotNull SpawnChunkBehavior behavior) {
            return spawnChunks(behavior);
        }

        /**
         * Sets a callback to receive progress updates during world creation.
         * <p>
         * The callback will be invoked on various threads, so ensure any
         * UI updates are properly synchronized to the main thread.
         *
         * @param callback the progress callback
         * @return this builder
         */
        @NotNull
        public Builder progressCallback(@Nullable Consumer<AsyncWorldProgress> callback) {
            this.progressCallback = callback;
            return this;
        }

        /**
         * Sets whether to generate a spawn point for the world.
         * <p>
         * Defaults to true.
         *
         * @param generateSpawn true to generate spawn
         * @return this builder
         */
        @NotNull
        public Builder generateSpawn(boolean generateSpawn) {
            this.generateSpawn = generateSpawn;
            return this;
        }

        /**
         * Sets a world template to use for fast world creation.
         * <p>
         * When a template is set, the async preparation phase will copy
         * files from the template instead of relying on the server to
         * generate them. This is significantly faster, especially for:
         * <ul>
         *   <li>Minigame arenas with pre-built terrain</li>
         *   <li>Void worlds with just level.dat</li>
         *   <li>Any world where you want consistent starting state</li>
         * </ul>
         *
         * @param template the world template, or null to not use a template
         * @return this builder
         * @see WorldTemplate
         */
        @NotNull
        public Builder template(@Nullable WorldTemplate template) {
            this.template = template;
            return this;
        }

        /**
         * Sets the pre-generation radius in chunks.
         * <p>
         * When set to a value greater than 0, chunks within this radius
         * from spawn will be pre-generated asynchronously using parallel
         * workers (Moonrise's WORKER_POOL).
         * <p>
         * This is critical for UHC-style games where players need a large
         * playable area immediately. Common values:
         * <ul>
         *   <li>16 chunks = 256 blocks radius (~1000 chunks)</li>
         *   <li>32 chunks = 512 blocks radius (~4000 chunks)</li>
         *   <li>48 chunks = 768 blocks radius (~9000 chunks)</li>
         * </ul>
         * <p>
         * Pre-generation runs in the background while returning the world
         * immediately (unless spawnChunks is ASYNC_WAIT).
         *
         * @param radiusInChunks the radius in chunks, or 0 to disable
         * @return this builder
         */
        @NotNull
        public Builder preGenerateRadius(int radiusInChunks) {
            this.preGenerateRadius = Math.max(0, radiusInChunks);
            return this;
        }

        /**
         * Sets a callback to receive pre-generation progress updates.
         * <p>
         * The callback is invoked periodically during chunk generation
         * with progress information. Use this to show loading screens
         * or progress bars to players.
         * <p>
         * The callback may be invoked from any thread, so ensure any
         * UI updates are properly synchronized.
         *
         * @param callback the progress callback
         * @return this builder
         */
        @NotNull
        public Builder preGenerateCallback(@Nullable Consumer<AsyncWorldProgress> callback) {
            this.preGenerateCallback = callback;
            return this;
        }

        /**
         * Builds the options instance.
         *
         * @return the built options
         */
        @NotNull
        public AsyncWorldOptions build() {
            return new AsyncWorldOptions(this);
        }
    }
}
