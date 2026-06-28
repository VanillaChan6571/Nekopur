package org.nekopur;

import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;

/**
 * Represents progress information during async world creation.
 * <p>
 * Implementations of this interface are passed to progress callbacks
 * during the world creation process.
 */
@NullMarked
public interface AsyncWorldProgress {

    /**
     * Represents the current stage of async world creation.
     */
    enum Stage {
        /**
         * Creating world directory structure
         */
        CREATING_DIRECTORY("Creating world directory"),

        /**
         * Copying template files (if using a template)
         */
        COPYING_TEMPLATE("Copying template files"),

        /**
         * Creating level storage access (file locks)
         */
        CREATING_STORAGE("Creating storage access"),

        /**
         * Loading or creating world data (level.dat) - runs async
         */
        LOADING_WORLD_DATA("Loading world data"),

        /**
         * Parsing world data NBT - runs async
         */
        PARSING_WORLD_DATA("Parsing world data"),

        /**
         * Creating level stem and chunk generator
         */
        CREATING_GENERATOR("Creating chunk generator"),

        /**
         * Constructing ServerLevel on main thread
         */
        CONSTRUCTING_LEVEL("Constructing server level"),

        /**
         * Registering world with the server
         */
        REGISTERING_WORLD("Registering world"),

        /**
         * Loading spawn chunks asynchronously
         */
        LOADING_SPAWN_CHUNKS("Loading spawn chunks"),

        /**
         * Pre-generating chunks asynchronously
         */
        PRE_GENERATING_CHUNKS("Pre-generating chunks"),

        /**
         * World creation completed
         */
        COMPLETED("Completed"),

        /**
         * World creation failed
         */
        FAILED("Failed");

        private final String description;

        Stage(String description) {
            this.description = description;
        }

        /**
         * Gets the human-readable description of this stage.
         *
         * @return the stage description
         */
        @NotNull
        public String getDescription() {
            return description;
        }
    }

    /**
     * Gets the current stage of world creation.
     *
     * @return the current stage
     */
    @NotNull
    Stage getStage();

    /**
     * Gets the name of the world being created.
     *
     * @return the world name
     */
    @NotNull
    String getWorldName();

    /**
     * Gets the progress percentage (0.0 to 1.0) if applicable.
     * <p>
     * Returns -1 if progress percentage is not applicable for the current stage.
     *
     * @return the progress percentage, or -1 if not applicable
     */
    double getProgress();

    /**
     * Gets additional details about the current stage.
     * <p>
     * May return an empty string if no additional details are available.
     *
     * @return additional details string
     */
    @NotNull
    String getDetails();
}
