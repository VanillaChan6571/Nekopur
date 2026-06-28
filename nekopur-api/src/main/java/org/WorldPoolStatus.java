package org.nekopur;

import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;

import java.util.List;

/**
 * Snapshot of a world pool's current status.
 */
@NullMarked
public final class WorldPoolStatus {
    private final List<String> activeWorlds;
    private final List<String> availableWorlds;
    private final List<String> creatingWorlds;
    private final int waitingAcquireCount;
    private final List<String> deletedWorlds;
    private final boolean shutdown;

    public WorldPoolStatus(@NotNull List<String> activeWorlds,
                           @NotNull List<String> availableWorlds,
                           @NotNull List<String> creatingWorlds,
                           int waitingAcquireCount,
                           @NotNull List<String> deletedWorlds,
                           boolean shutdown) {
        this.activeWorlds = List.copyOf(activeWorlds);
        this.availableWorlds = List.copyOf(availableWorlds);
        this.creatingWorlds = List.copyOf(creatingWorlds);
        this.waitingAcquireCount = waitingAcquireCount;
        this.deletedWorlds = List.copyOf(deletedWorlds);
        this.shutdown = shutdown;
    }

    /**
     * Worlds currently in use (acquired).
     */
    @NotNull
    public List<String> getActiveWorlds() {
        return activeWorlds;
    }

    /**
     * Worlds ready to be acquired.
     */
    @NotNull
    public List<String> getAvailableWorlds() {
        return availableWorlds;
    }

    /**
     * Worlds currently being created.
     */
    @NotNull
    public List<String> getCreatingWorlds() {
        return creatingWorlds;
    }

    /**
     * Number of acquires waiting for a world.
     */
    public int getWaitingAcquireCount() {
        return waitingAcquireCount;
    }

    /**
     * Recently deleted worlds (best-effort history).
     */
    @NotNull
    public List<String> getDeletedWorlds() {
        return deletedWorlds;
    }

    /**
     * Whether the pool is shut down.
     */
    public boolean isShutdown() {
        return shutdown;
    }
}
