package org.nekopur.network;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Per-player write-ahead records. Call mutations on the dedicated handoff I/O executor. */
@NullMarked
final class HandoffJournal {
    enum Role { SOURCE, DESTINATION }
    enum Phase { EXPORTED, STAGED, FENCED, COMMITTED, ACTIVATED, RELEASED, ABORTED }

    record Entry(UUID transfer, UUID player, long generation, String source, String destination,
                 Role role, Phase phase, HubSnapshot snapshot, long expiresAtMillis) {
        Entry {
            if (transfer == null || player == null || generation < 1 || source == null || destination == null
                || !source.matches("[a-z0-9][a-z0-9_-]{0,63}")
                || !destination.matches("[a-z0-9][a-z0-9_-]{0,63}") || source.equals(destination)
                || role == null || phase == null || snapshot == null) {
                throw new IllegalArgumentException("Invalid handoff record");
            }
            if (role == Role.SOURCE && (phase == Phase.STAGED || phase == Phase.COMMITTED || phase == Phase.ACTIVATED)
                || role == Role.DESTINATION && (phase == Phase.EXPORTED || phase == Phase.FENCED || phase == Phase.RELEASED)) {
                throw new IllegalArgumentException("Handoff phase does not match its role");
            }
        }

        Entry withPhase(Phase next) {
            return new Entry(transfer, player, generation, source, destination, role, next, snapshot, expiresAtMillis);
        }
    }

    private static final Gson GSON = new Gson();
    private final Path directory;
    private final Map<UUID, Entry> entries = new java.util.concurrent.ConcurrentHashMap<>();

    HandoffJournal(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                if (Files.size(file) > 8192) {
                    throw new IOException("Oversized handoff journal record: " + file.getFileName());
                }
                try {
                    Entry entry = GSON.fromJson(Files.readString(file), Entry.class);
                    if (entry == null || !file.getFileName().toString().equals(entry.player() + ".json")) {
                        throw new IllegalArgumentException("Journal identity mismatch");
                    }
                    this.entries.put(entry.player(), entry);
                } catch (RuntimeException failure) {
                    throw new IOException("Invalid handoff journal record: " + file.getFileName(), failure);
                }
            }
        }
    }

    @Nullable Entry get(UUID player) {
        return this.entries.get(player);
    }

    Map<UUID, Entry> snapshot() {
        return Map.copyOf(this.entries);
    }

    synchronized Entry begin(Entry proposed) throws IOException {
        Entry old = this.entries.get(proposed.player());
        if (old != null) {
            if (old.transfer().equals(proposed.transfer())) {
                if (!old.withPhase(proposed.phase()).equals(proposed)) {
                    throw new IllegalStateException("Transfer ID reused with different metadata");
                }
                return old;
            }
            if (proposed.generation() <= old.generation()) {
                throw new IllegalStateException("Stale ownership generation");
            }
            if (old.phase() != Phase.ACTIVATED && old.phase() != Phase.RELEASED && old.phase() != Phase.ABORTED) {
                throw new IllegalStateException("Another handoff is unresolved");
            }
        }
        if (proposed.phase() != Phase.EXPORTED && proposed.phase() != Phase.STAGED) {
            throw new IllegalStateException("Invalid initial handoff phase");
        }
        persist(proposed);
        return proposed;
    }

    synchronized Entry transition(UUID player, UUID transfer, long generation, Phase next) throws IOException {
        Entry entry = this.entries.get(player);
        if (entry == null || !entry.transfer().equals(transfer) || entry.generation() != generation) {
            throw new IllegalStateException("Unknown or superseded handoff");
        }
        if (entry.phase() == next) {
            return entry;
        }
        boolean valid = switch (entry.phase()) {
            case EXPORTED -> next == Phase.FENCED || next == Phase.ABORTED;
            case STAGED -> next == Phase.COMMITTED || next == Phase.ABORTED;
            case FENCED -> next == Phase.RELEASED || next == Phase.ABORTED;
            case COMMITTED -> next == Phase.ACTIVATED;
            default -> false;
        };
        if (!valid) {
            throw new IllegalStateException("Invalid handoff transition: " + entry.phase() + " -> " + next);
        }
        Entry updated = entry.withPhase(next);
        persist(updated);
        return updated;
    }

    private void persist(Entry entry) throws IOException {
        Path target = this.directory.resolve(entry.player() + ".json");
        Path temporary = this.directory.resolve(entry.player() + ".tmp");
        byte[] bytes = GSON.toJson(entry).getBytes(StandardCharsets.UTF_8);
        try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                file.write(buffer);
            }
            file.force(true);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        this.entries.put(entry.player(), entry);
    }
}
