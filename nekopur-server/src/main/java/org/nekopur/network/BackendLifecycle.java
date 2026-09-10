package org.nekopur.network;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Coordinates main-thread pause decisions with control-thread wake and heartbeat messages. */
@NullMarked
final class BackendLifecycle {
    private volatile PurroxyConnection.Snapshot snapshot = new PurroxyConnection.Snapshot("REGISTERING", 0);
    private volatile long lastTick;
    private final AtomicBoolean wakeRequested = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Boolean>> sleepRequest =
        new java.util.concurrent.atomic.AtomicReference<>();
    private volatile boolean wakingPublished;
    private boolean sleepingRequested;
    private boolean waking;
    private final boolean proxyManaged;
    private @Nullable Consumer<String> transitions;
    private boolean stallReported;

    /** Reports every published state change, so a backend short of READY explains itself. */
    void onTransition(Consumer<String> sink) {
        this.transitions = sink;
    }

    BackendLifecycle(boolean startSleeping) {
        this(startSleeping, false);
    }

    BackendLifecycle(boolean startSleeping, boolean proxyManaged) {
        this.sleepingRequested = startSleeping;
        this.proxyManaged = proxyManaged;
    }

    void wake() {
        java.util.concurrent.CompletableFuture<Boolean> pending = this.sleepRequest.getAndSet(null);
        if (pending != null) {
            pending.complete(false);
        }
        this.wakeRequested.set(true);
    }

    java.util.concurrent.CompletableFuture<Boolean> requestSleep() {
        java.util.concurrent.CompletableFuture<Boolean> request = new java.util.concurrent.CompletableFuture<>();
        if (!this.sleepRequest.compareAndSet(null, request)) {
            request.complete(false);
        }
        return request;
    }

    boolean beginTick(int players) {
        boolean requested = this.wakeRequested.getAndSet(false);
        if ((requested || players > 0) && this.sleepingRequested) {
            this.sleepingRequested = false;
            this.waking = true;
            this.wakingPublished = false;
            return true;
        }
        if (requested && this.waking) {
            // The first wake already cleared the sleep request. Without this a preparation that never
            // finished would strand the backend in REGISTERING, with no way left to restart it.
            this.wakingPublished = false;
            return true;
        }
        return false;
    }

    int tick(boolean canSleep, boolean prepared, int players, long now) {
        this.lastTick = now;
        if (this.proxyManaged && prepared && this.sleepingRequested && !canSleep) {
            this.sleepingRequested = false; // A plugin sleep veto must not strand an automatic backend in REGISTERING.
        }
        if (this.waking && this.wakingPublished && prepared) {
            this.waking = false;
        }
        java.util.concurrent.CompletableFuture<Boolean> requested = this.sleepRequest.getAndSet(null);
        boolean accepted = requested != null && canSleep && prepared && players == 0 && !this.waking;
        if (accepted) {
            this.sleepingRequested = true;
        }
        boolean sleep = this.sleepingRequested && prepared && canSleep && players == 0;
        String state = !prepared ? "REGISTERING" : this.waking ? "WAKING"
            : this.sleepingRequested ? (sleep ? "SLEEPING" : "REGISTERING") : "READY";
        PurroxyConnection.Snapshot previous = this.snapshot;
        this.snapshot = new PurroxyConnection.Snapshot(state, players);
        if (this.transitions != null && !previous.state().equals(state)) {
            this.transitions.accept("Nekopurr state " + previous.state() + " -> " + state
                + " (prepared=" + prepared + ", canSleep=" + canSleep + ", players=" + players
                + ", waking=" + this.waking + ", sleepRequested=" + this.sleepingRequested + ")");
        }
        if (requested != null) {
            requested.complete(accepted);
        }
        return sleep ? 1 : 0;
    }

    PurroxyConnection.Snapshot heartbeat(long now) {
        PurroxyConnection.Snapshot current = this.snapshot;
        if (now - this.lastTick > TimeUnit.SECONDS.toNanos(10)) {
            if (!this.stallReported && this.transitions != null) {
                // Otherwise a stalled main thread is indistinguishable from a backend that never prepared.
                this.stallReported = true;
                this.transitions.accept("Nekopurr main thread has not ticked for "
                    + TimeUnit.NANOSECONDS.toSeconds(now - this.lastTick)
                    + "s; heartbeating REGISTERING instead of " + current.state());
            }
            return new PurroxyConnection.Snapshot("REGISTERING", current.players());
        }
        this.stallReported = false;
        if (current.state().equals("WAKING")) {
            this.wakingPublished = true;
        }
        return current;
    }
}
