package org.nekopur;

import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

import java.io.File;
import java.nio.file.Path;

/**
 * Represents a world template for fast world creation.
 * <p>
 * Templates allow you to pre-configure world settings (level.dat) and optionally
 * include pre-built terrain (region files) that can be quickly copied when creating
 * new worlds. This is significantly faster than generating worlds from scratch.
 * <p>
 * Common use cases:
 * <ul>
 *   <li>Minigame arenas - copy a pre-built arena template</li>
 *   <li>Void worlds - copy a minimal level.dat without terrain</li>
 *   <li>Lobby worlds - duplicate a decorated spawn area</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>{@code
 * // Create a template from an existing world folder
 * WorldTemplate template = WorldTemplate.fromWorld(new File("templates/arena"));
 *
 * // Use template when creating worlds
 * Neko.createWorldAsync(creator, AsyncWorldOptions.builder()
 *     .template(template)
 *     .spawnChunks(SpawnChunkBehavior.SKIP)
 *     .build());
 * }</pre>
 */
@NullMarked
public final class WorldTemplate {

    private final Path templatePath;
    private final boolean copyRegionFiles;
    private final boolean copyEntities;
    private final boolean copyPoi;
    private final boolean copyDatapacks;

    private WorldTemplate(Builder builder) {
        this.templatePath = builder.templatePath;
        this.copyRegionFiles = builder.copyRegionFiles;
        this.copyEntities = builder.copyEntities;
        this.copyPoi = builder.copyPoi;
        this.copyDatapacks = builder.copyDatapacks;
    }

    /**
     * Gets the path to the template world folder.
     *
     * @return the template path
     */
    @NotNull
    public Path getTemplatePath() {
        return templatePath;
    }

    /**
     * Whether to copy region files (terrain data).
     *
     * @return true if region files should be copied
     */
    public boolean shouldCopyRegionFiles() {
        return copyRegionFiles;
    }

    /**
     * Whether to copy entity files.
     *
     * @return true if entity files should be copied
     */
    public boolean shouldCopyEntities() {
        return copyEntities;
    }

    /**
     * Whether to copy POI (point of interest) files.
     *
     * @return true if POI files should be copied
     */
    public boolean shouldCopyPoi() {
        return copyPoi;
    }

    /**
     * Whether to copy datapacks.
     *
     * @return true if datapacks should be copied
     */
    public boolean shouldCopyDatapacks() {
        return copyDatapacks;
    }

    /**
     * Creates a template from an existing world folder.
     * <p>
     * This will copy both level.dat and region files by default.
     *
     * @param worldFolder the world folder to use as template
     * @return a new WorldTemplate
     * @throws IllegalArgumentException if the folder doesn't exist or isn't a valid world
     */
    @NotNull
    public static WorldTemplate fromWorld(@NotNull File worldFolder) {
        return builder(worldFolder.toPath()).build();
    }

    /**
     * Creates a template from an existing world folder.
     * <p>
     * This will copy both level.dat and region files by default.
     *
     * @param worldFolder the world folder to use as template
     * @return a new WorldTemplate
     * @throws IllegalArgumentException if the folder doesn't exist or isn't a valid world
     */
    @NotNull
    public static WorldTemplate fromWorld(@NotNull Path worldFolder) {
        return builder(worldFolder).build();
    }

    /**
     * Creates a template from a loaded world.
     * <p>
     * This will copy both level.dat and region files by default.
     *
     * @param world the world to use as template
     * @return a new WorldTemplate
     */
    @NotNull
    public static WorldTemplate fromWorld(@NotNull World world) {
        return builder(world.getWorldFolder().toPath()).build();
    }

    /**
     * Creates a template that only copies level.dat (no terrain).
     * <p>
     * Useful for void worlds or when you want fresh terrain generation
     * but with pre-configured world settings.
     *
     * @param worldFolder the world folder containing the level.dat
     * @return a new WorldTemplate with only level.dat
     */
    @NotNull
    public static WorldTemplate levelDatOnly(@NotNull File worldFolder) {
        return builder(worldFolder.toPath())
                .copyRegionFiles(false)
                .copyEntities(false)
                .copyPoi(false)
                .build();
    }

    /**
     * Creates a template that only copies level.dat (no terrain).
     *
     * @param worldFolder the world folder containing the level.dat
     * @return a new WorldTemplate with only level.dat
     */
    @NotNull
    public static WorldTemplate levelDatOnly(@NotNull Path worldFolder) {
        return builder(worldFolder)
                .copyRegionFiles(false)
                .copyEntities(false)
                .copyPoi(false)
                .build();
    }

    /**
     * Creates a new builder for a WorldTemplate.
     *
     * @param templatePath the path to the template world folder
     * @return a new builder
     */
    @NotNull
    public static Builder builder(@NotNull Path templatePath) {
        return new Builder(templatePath);
    }

    /**
     * Creates a new builder for a WorldTemplate.
     *
     * @param templateFolder the template world folder
     * @return a new builder
     */
    @NotNull
    public static Builder builder(@NotNull File templateFolder) {
        return new Builder(templateFolder.toPath());
    }

    /**
     * Builder for creating {@link WorldTemplate} instances.
     */
    public static final class Builder {
        private final Path templatePath;
        private boolean copyRegionFiles = true;
        private boolean copyEntities = true;
        private boolean copyPoi = true;
        private boolean copyDatapacks = false;

        private Builder(@NotNull Path templatePath) {
            this.templatePath = templatePath;
        }

        /**
         * Sets whether to copy region files (terrain data).
         * <p>
         * Default: true
         *
         * @param copy true to copy region files
         * @return this builder
         */
        @NotNull
        public Builder copyRegionFiles(boolean copy) {
            this.copyRegionFiles = copy;
            return this;
        }

        /**
         * Sets whether to copy entity files.
         * <p>
         * Default: true
         *
         * @param copy true to copy entity files
         * @return this builder
         */
        @NotNull
        public Builder copyEntities(boolean copy) {
            this.copyEntities = copy;
            return this;
        }

        /**
         * Sets whether to copy POI (point of interest) files.
         * <p>
         * Default: true
         *
         * @param copy true to copy POI files
         * @return this builder
         */
        @NotNull
        public Builder copyPoi(boolean copy) {
            this.copyPoi = copy;
            return this;
        }

        /**
         * Sets whether to copy datapacks.
         * <p>
         * Default: false
         *
         * @param copy true to copy datapacks
         * @return this builder
         */
        @NotNull
        public Builder copyDatapacks(boolean copy) {
            this.copyDatapacks = copy;
            return this;
        }

        /**
         * Builds the WorldTemplate.
         *
         * @return the built template
         * @throws IllegalArgumentException if the template path doesn't exist
         */
        @NotNull
        public WorldTemplate build() {
            if (!templatePath.toFile().exists()) {
                throw new IllegalArgumentException("Template path does not exist: " + templatePath);
            }
            return new WorldTemplate(this);
        }
    }
}
