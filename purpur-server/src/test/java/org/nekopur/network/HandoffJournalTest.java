package org.nekopur.network;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@NullMarked
class HandoffJournalTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();
    private final UUID transfer = UUID.randomUUID();
    private final HubSnapshot snapshot = new HubSnapshot("hub", "christmas-v1", 12.5, 80, -20, 45, 10, 0, 0, 0);

    private HandoffJournal.Entry entry(HandoffJournal.Role role, HandoffJournal.Phase phase, long generation) {
        return new HandoffJournal.Entry(this.transfer, this.player, generation, "hub-1", "hub-2", role, phase, this.snapshot, 123456);
    }

    @Test
    void fencedSourceSurvivesRestartAndDuplicateFence() throws Exception {
        HandoffJournal journal = new HandoffJournal(this.directory);
        journal.begin(entry(HandoffJournal.Role.SOURCE, HandoffJournal.Phase.EXPORTED, 1));
        journal.transition(this.player, this.transfer, 1, HandoffJournal.Phase.FENCED);
        HandoffJournal restarted = new HandoffJournal(this.directory);
        assertEquals(HandoffJournal.Phase.FENCED, restarted.get(this.player).phase());
        assertEquals(HandoffJournal.Phase.FENCED, restarted.transition(this.player, this.transfer, 1, HandoffJournal.Phase.FENCED).phase());
    }

    @Test
    void committedDestinationCannotBeAbortedOrReplaced() throws Exception {
        HandoffJournal journal = new HandoffJournal(this.directory);
        journal.begin(entry(HandoffJournal.Role.DESTINATION, HandoffJournal.Phase.STAGED, 1));
        journal.transition(this.player, this.transfer, 1, HandoffJournal.Phase.COMMITTED);
        HandoffJournal restarted = new HandoffJournal(this.directory);
        assertThrows(IllegalStateException.class, () -> restarted.transition(this.player, this.transfer, 1, HandoffJournal.Phase.ABORTED));
        HandoffJournal.Entry replacement = new HandoffJournal.Entry(UUID.randomUUID(), this.player, 2, "hub-1", "hub-2",
            HandoffJournal.Role.DESTINATION, HandoffJournal.Phase.STAGED, this.snapshot, 999999);
        assertThrows(IllegalStateException.class, () -> restarted.begin(replacement));
        assertEquals(HandoffJournal.Phase.ACTIVATED, restarted.transition(this.player, this.transfer, 1, HandoffJournal.Phase.ACTIVATED).phase());
    }

    @Test
    void retriesCannotReplaceSnapshotUnderSameTransferId() throws Exception {
        HandoffJournal journal = new HandoffJournal(this.directory);
        HandoffJournal.Entry original = entry(HandoffJournal.Role.DESTINATION, HandoffJournal.Phase.STAGED, 1);
        journal.begin(original);
        assertEquals(original, journal.begin(original));
        HubSnapshot altered = new HubSnapshot("hub", "christmas-v1", 90, 80, -20, 45, 10, 0, 0, 0);
        HandoffJournal.Entry replay = new HandoffJournal.Entry(this.transfer, this.player, 1, "hub-1", "hub-2",
            original.role(), original.phase(), altered, original.expiresAtMillis());
        assertThrows(IllegalStateException.class, () -> journal.begin(replay));
    }

    @Test
    void staleGenerationsAndUnknownTransactionsAreRejected() throws Exception {
        HandoffJournal journal = new HandoffJournal(this.directory);
        journal.begin(entry(HandoffJournal.Role.SOURCE, HandoffJournal.Phase.EXPORTED, 4));
        journal.transition(this.player, this.transfer, 4, HandoffJournal.Phase.ABORTED);
        HandoffJournal.Entry stale = new HandoffJournal.Entry(UUID.randomUUID(), this.player, 3, "hub-1", "hub-2",
            HandoffJournal.Role.SOURCE, HandoffJournal.Phase.EXPORTED, this.snapshot, 999999);
        assertThrows(IllegalStateException.class, () -> journal.begin(stale));
        assertThrows(IllegalStateException.class, () -> journal.transition(this.player, UUID.randomUUID(), 4, HandoffJournal.Phase.FENCED));
    }

    @Test
    void corruptJournalDoesNotSilentlyForgetOwnership() throws Exception {
        Files.writeString(this.directory.resolve(this.player + ".json"), "{broken");
        assertThrows(java.io.IOException.class, () -> new HandoffJournal(this.directory));
    }

    @Test
    void snapshotRejectsNonFiniteValuesAndMissingReplicaIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new HubSnapshot("hub", "v1", Double.NaN, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new HubSnapshot("", "v1", 0, 0, 0, 0, 0, 0, 0, 0));
    }
}
