package org.nekopur.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NullMarked;

@NullMarked
class BackendHandoffPredictionTest {
    private static final HubSnapshot SNAPSHOT = new HubSnapshot(
        "hub", "revision-1", 100.0D, 64.0D, -50.0D, 0.0F, 0.0F, 0.0D, 0.0D, 0.0D);

    @Test
    void acceptsNormalPredictionWithinTheTransferEnvelope() {
        assertTrue(BackendHandoff.withinPredictionBounds(SNAPSHOT, 112.0D, 65.0D, -42.0D));
        assertTrue(BackendHandoff.withinPredictionBounds(SNAPSHOT, 164.0D, 64.0D, -50.0D));
    }

    @Test
    void rejectsAFirstPacketThatWouldBecomeATransferTeleport() {
        assertFalse(BackendHandoff.withinPredictionBounds(SNAPSHOT, 164.01D, 64.0D, -50.0D));
        assertFalse(BackendHandoff.withinPredictionBounds(SNAPSHOT, 1000.0D, 80.0D, 1000.0D));
    }
}
