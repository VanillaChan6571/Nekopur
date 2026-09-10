package org.nekopur.network;

import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NullMarked
class BackendLifecycleTest {
    @Test
    void repeatedWakeRestartsPreparationForABackendStrandedShortOfReady() {
        BackendLifecycle lifecycle = new BackendLifecycle(true, true);
        lifecycle.wake();
        assertTrue(lifecycle.beginTick(0), "The first wake clears the sleep request and starts preparation");
        lifecycle.tick(true, false, 0, 1);
        assertEquals("REGISTERING", lifecycle.heartbeat(1).state());
        // Preparation never completes, so the proxy sends another wake a minute later.
        assertFalse(lifecycle.beginTick(0), "Nothing restarts preparation without a further wake");
        lifecycle.wake();
        assertTrue(lifecycle.beginTick(0), "A repeated wake must restart preparation");
        lifecycle.tick(true, true, 0, 2);
        assertEquals("WAKING", lifecycle.heartbeat(2).state());
        lifecycle.tick(true, true, 0, 3);
        assertEquals("READY", lifecycle.heartbeat(3).state());
    }

    @Test
    void repeatedWakeIsIgnoredOnceTheBackendIsReady() {
        BackendLifecycle lifecycle = new BackendLifecycle(false, true);
        lifecycle.tick(true, true, 0, 1);
        assertEquals("READY", lifecycle.heartbeat(1).state());
        lifecycle.wake();
        assertFalse(lifecycle.beginTick(0), "A ready backend must not re-prepare on a duplicate wake");
        lifecycle.tick(true, true, 0, 2);
        assertEquals("READY", lifecycle.heartbeat(2).state());
    }

    @Test
    void proxySleepRequestIsAcknowledgedOnlyAfterMainThreadSafetyCheck() {
        BackendLifecycle lifecycle = new BackendLifecycle(false, true);
        lifecycle.tick(true, true, 0, 1);
        java.util.concurrent.CompletableFuture<Boolean> refused = lifecycle.requestSleep();
        assertFalse(refused.isDone());
        lifecycle.tick(false, true, 0, 2);
        assertFalse(refused.join());
        assertEquals("READY", lifecycle.heartbeat(2).state());
        java.util.concurrent.CompletableFuture<Boolean> accepted = lifecycle.requestSleep();
        assertEquals(1, lifecycle.tick(true, true, 0, 3));
        assertTrue(accepted.join());
        assertEquals("SLEEPING", lifecycle.heartbeat(3).state());
    }

    @Test
    void automaticStartupSleepVetoPublishesReadyAndWakeCancelsPendingSleep() {
        BackendLifecycle lifecycle = new BackendLifecycle(true, true);
        assertEquals(0, lifecycle.tick(false, true, 0, 1));
        assertEquals("READY", lifecycle.heartbeat(1).state());
        java.util.concurrent.CompletableFuture<Boolean> pending = lifecycle.requestSleep();
        lifecycle.wake();
        assertFalse(pending.join());
        lifecycle.beginTick(0);
        assertEquals(0, lifecycle.tick(true, true, 0, 2));
    }

    @Test
    void spareRequiresPreparedMapAndPermissionToSleep() {
        BackendLifecycle lifecycle = new BackendLifecycle(true);
        assertEquals(0, lifecycle.tick(true, false, 0, 1));
        assertEquals("REGISTERING", lifecycle.heartbeat(1).state());
        assertEquals(0, lifecycle.tick(false, true, 0, 2));
        assertEquals("REGISTERING", lifecycle.heartbeat(2).state());
        assertEquals(1, lifecycle.tick(true, true, 0, 3));
        assertEquals("SLEEPING", lifecycle.heartbeat(3).state());
    }

    @Test
    void wakeIsPublishedBeforeReadyAndDuplicatesDoNotResetReadiness() {
        BackendLifecycle lifecycle = new BackendLifecycle(true);
        lifecycle.tick(true, true, 0, 1);
        lifecycle.wake();
        assertTrue(lifecycle.beginTick(0));
        assertEquals(0, lifecycle.tick(true, true, 0, 2));
        assertFalse(lifecycle.beginTick(0));
        lifecycle.tick(true, true, 0, 3);
        assertEquals("WAKING", lifecycle.heartbeat(3).state());
        lifecycle.tick(true, true, 0, 4);
        assertEquals("READY", lifecycle.heartbeat(4).state());
        lifecycle.wake();
        assertFalse(lifecycle.beginTick(0));
        assertEquals(0, lifecycle.tick(true, true, 0, 5));
        assertEquals("READY", lifecycle.heartbeat(5).state());
    }

    @Test
    void occupiedServerNeverSleepsEvenIfCallerClaimsItCan() {
        BackendLifecycle lifecycle = new BackendLifecycle(true);
        assertTrue(lifecycle.beginTick(1));
        assertEquals(0, lifecycle.tick(true, true, 1, 1));
        assertEquals(1, lifecycle.heartbeat(1).players());
        assertEquals("WAKING", lifecycle.heartbeat(1).state());
        lifecycle.tick(true, true, 0, 2);
        assertEquals("READY", lifecycle.heartbeat(2).state());
    }

    @Test
    void stalledMainThreadWithdrawsReadinessWithoutLosingPlayerCount() {
        BackendLifecycle lifecycle = new BackendLifecycle(false);
        lifecycle.tick(false, true, 17, 1);
        assertEquals("READY", lifecycle.heartbeat(1).state());
        PurroxyConnection.Snapshot stale = lifecycle.heartbeat(TimeUnit.SECONDS.toNanos(11));
        assertEquals("REGISTERING", stale.state());
        assertEquals(17, stale.players());
    }

    @Test
    void unavailableMapCannotBecomeReadyAfterWake() {
        BackendLifecycle lifecycle = new BackendLifecycle(true);
        lifecycle.wake();
        assertTrue(lifecycle.beginTick(0));
        lifecycle.tick(true, false, 0, 1);
        assertEquals("REGISTERING", lifecycle.heartbeat(1).state());
        lifecycle.tick(true, true, 0, 2);
        assertEquals("WAKING", lifecycle.heartbeat(2).state());
    }
}
