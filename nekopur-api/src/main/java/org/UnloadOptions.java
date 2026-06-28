package org.nekopur;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

import java.util.function.Consumer;

/**
 * Options for customizing async world unloading behavior.
 * <p>
 * Use {@link #builder()} to create new instances.
 * <p>
 * <h2>Quick Discard (Minigame end)</h2>
 * <pre>{@code
 * Neko.getServer().unloadWorldAsync(world, UnloadOptions.builder()
 *     .saveChunks(false)        // Don't save - world will be deleted
 *     .deleteWorldFolder(true)  // Clean up files async
 *     .build());
 * }</pre>
 *
 * <h2>Safe Unload (Preserve world)</h2>
 * <pre>{@code
 * Neko.getServer().unloadWorldAsync(world, UnloadOptions.builder()
 *     .saveChunks(true)
 *     .deleteWorldFolder(false)
 *     .build());
 * }</pre>
 */
@NullMarked
public final class UnloadOptions {

    private final boolean saveChunks;
    private final boolean deleteWorldFolder;
    private final @Nullable Consumer<Boolean> completionCallback;

    private UnloadOptions(Builder builder) {
        this.saveChunks = builder.saveChunks;
        this.deleteWorldFolder = builder.deleteWorldFolder;
        this.completionCallback = builder.completionCallback;
    }

    /**
     * Whether to save chunks before unloading.
     *
     * @return true if chunks should be saved
     */
    public boolean shouldSaveChunks() {
        return saveChunks;
    }

    /**
     * Whether to delete the world folder after unloading.
     *
     * @return true if the world folder should be deleted
     */
    public boolean shouldDeleteWorldFolder() {
        return deleteWorldFolder;
    }

    /**
     * Gets the completion callback, if one was set.
     *
     * @return the completion callback, or null if none was set
     */
    @Nullable
    public Consumer<Boolean> getCompletionCallback() {
        return completionCallback;
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
     * Returns options for quick discard (no save, delete files).
     *
     * @return discard options
     */
    @NotNull
    public static UnloadOptions discard() {
        return builder()
                .saveChunks(false)
                .deleteWorldFolder(true)
                .build();
    }

    /**
     * Returns options for safe unload (save chunks, keep files).
     *
     * @return safe unload options
     */
    @NotNull
    public static UnloadOptions safe() {
        return builder()
                .saveChunks(true)
                .deleteWorldFolder(false)
                .build();
    }

    /**
     * Builder for creating {@link UnloadOptions} instances.
     */
    public static final class Builder {
        private boolean saveChunks = true;
        private boolean deleteWorldFolder = false;
        private @Nullable Consumer<Boolean> completionCallback = null;

        private Builder() {}

        /**
         * Sets whether to save chunks before unloading.
         * <p>
         * Set to false when the world will be deleted anyway for faster unloading.
         * <p>
         * Default: true
         *
         * @param save true to save chunks
         * @return this builder
         */
        @NotNull
        public Builder saveChunks(boolean save) {
            this.saveChunks = save;
            return this;
        }

        /**
         * Sets whether to delete the world folder after unloading.
         * <p>
         * The deletion happens asynchronously after the world is unloaded.
         * <p>
         * Default: false
         *
         * @param delete true to delete the world folder
         * @return this builder
         */
        @NotNull
        public Builder deleteWorldFolder(boolean delete) {
            this.deleteWorldFolder = delete;
            return this;
        }

        /**
         * Sets a callback to be invoked when unloading completes.
         * <p>
         * The callback receives true if unloading was successful, false otherwise.
         * The callback is invoked on the main thread.
         *
         * @param callback the completion callback
         * @return this builder
         */
        @NotNull
        public Builder completionCallback(@Nullable Consumer<Boolean> callback) {
            this.completionCallback = callback;
            return this;
        }

        /**
         * Builds the UnloadOptions.
         *
         * @return the built options
         */
        @NotNull
        public UnloadOptions build() {
            return new UnloadOptions(this);
        }
    }
}
