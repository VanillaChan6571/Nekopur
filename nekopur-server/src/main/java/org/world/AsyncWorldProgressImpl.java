package org.nekopur.world;

import org.jetbrains.annotations.NotNull;
import org.nekopur.AsyncWorldProgress;

/**
 * Implementation of {@link AsyncWorldProgress} for tracking world creation progress.
 */
public class AsyncWorldProgressImpl implements AsyncWorldProgress {

    private final String worldName;
    private Stage stage;
    private double progress;
    private String details;

    public AsyncWorldProgressImpl(@NotNull String worldName) {
        this.worldName = worldName;
        this.stage = Stage.CREATING_DIRECTORY;
        this.progress = -1;
        this.details = "";
    }

    @Override
    @NotNull
    public Stage getStage() {
        return stage;
    }

    @Override
    @NotNull
    public String getWorldName() {
        return worldName;
    }

    @Override
    public double getProgress() {
        return progress;
    }

    @Override
    @NotNull
    public String getDetails() {
        return details;
    }

    /**
     * Updates the current stage.
     *
     * @param stage the new stage
     */
    public void setStage(@NotNull Stage stage) {
        this.stage = stage;
        this.progress = -1;
        this.details = "";
    }

    /**
     * Updates the current stage with progress.
     *
     * @param stage the new stage
     * @param progress the progress percentage (0.0 to 1.0)
     */
    public void setStage(@NotNull Stage stage, double progress) {
        this.stage = stage;
        this.progress = progress;
        this.details = "";
    }

    /**
     * Updates the current stage with details.
     *
     * @param stage the new stage
     * @param details additional details
     */
    public void setStage(@NotNull Stage stage, @NotNull String details) {
        this.stage = stage;
        this.progress = -1;
        this.details = details;
    }

    /**
     * Updates the progress percentage.
     *
     * @param progress the progress percentage (0.0 to 1.0)
     */
    public void setProgress(double progress) {
        this.progress = progress;
    }

    /**
     * Updates the details.
     *
     * @param details the details string
     */
    public void setDetails(@NotNull String details) {
        this.details = details;
    }
}
