package org.nekopur.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Authenticated, journalled hub-position handoff. World access stays on the server thread. */
@NullMarked
public final class BackendHandoff implements AutoCloseable {
    public static final String GENERATION_KEY = "nekopurr:handoff_generation";
    private static final NamespacedKey GENERATION = new NamespacedKey("nekopurr", "handoff_generation");
    private static final Gson GSON = new Gson();
    public record Arrival(long generation, HubSnapshot snapshot, World world) {
        public Location location() {
            return new Location(world, snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch());
        }
    }

    private final MinecraftServer server;
    private volatile String serverId;
    private final String worldIdentity;
    private final String mapRevision;
    private final Supplier<World> world;
    private final HandoffJournal journal;
    private final ConcurrentHashMap<UUID, UUID> exporting = new ConcurrentHashMap<>();
    private final Semaphore pending = new Semaphore(64);
    private final ExecutorService io = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Nekopurr-handoff-journal");
        thread.setDaemon(true);
        return thread;
    });

    static @Nullable BackendHandoff create(MinecraftServer server, NetworkConfig config, Supplier<World> world) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(Path.of("Nekopurr.yaml").toFile());
        if (!yaml.getBoolean("transfers.enabled", yaml.getBoolean("handoff.enabled", false))) {
            return null;
        }
        if (!yaml.getString("transfers.profile", yaml.getString("handoff.profile", "hub-position")).equals("hub-position")) {
            throw new IllegalArgumentException("Only the explicit hub-position handoff profile is supported");
        }
        return new BackendHandoff(server, config.serverId(), yaml.getString("transfers.map-id", yaml.getString("handoff.world-identity", "")),
            yaml.getString("transfers.map-revision", yaml.getString("handoff.map-revision", "")), world);
    }

    void assignedName(String name) {
        this.serverId = name;
    }

    private BackendHandoff(MinecraftServer server, String serverId, String worldIdentity,
                           String mapRevision, Supplier<World> world) throws Exception {
        new HubSnapshot(worldIdentity, mapRevision, 0, 0, 0, 0, 0, 0, 0, 0);
        this.server = server;
        this.serverId = serverId;
        this.worldIdentity = worldIdentity;
        this.mapRevision = mapRevision;
        this.world = world;
        this.journal = new HandoffJournal(Path.of("nekopurr-handoffs"));
    }

    JsonObject capabilities() {
        JsonObject result = new JsonObject();
        result.addProperty("version", 1);
        result.addProperty("profile", "hub-position");
        result.addProperty("worldIdentity", this.worldIdentity);
        result.addProperty("mapRevision", this.mapRevision);
        result.addProperty("seamless", false);
        return result;
    }

    CompletableFuture<JsonObject> handle(JsonObject message) {
        if (!this.pending.tryAcquire()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Too many pending handoff operations"));
        }
        CompletableFuture<HandoffJournal.Entry> operation;
        try {
            UUID player = UUID.fromString(message.get("player").getAsString());
            UUID transfer = UUID.fromString(message.get("transfer").getAsString());
            long generation = message.get("generation").getAsLong();
            String action = message.get("action").getAsString();
            if (action.equals("status")) {
                HandoffJournal.Entry known = this.journal.get(player);
                if (known == null || !known.transfer().equals(transfer) || known.generation() != generation) {
                    JsonObject missing = new JsonObject();
                    missing.addProperty("status", "NOT_FOUND");
                    this.pending.release();
                    return CompletableFuture.completedFuture(missing);
                }
            }
            operation = switch (action) {
                case "export" -> {
                    UUID claim = UUID.randomUUID();
                    yield onMain(() -> export(message, player, transfer, generation, claim)).thenCompose(entry ->
                        onIo(() -> this.journal.begin(entry))).whenComplete((entry, failure) -> this.exporting.remove(player, claim));
                }
                case "stage" -> onMain(() -> stage(message, player, transfer, generation)).thenCompose(future -> future)
                    .thenCompose(entry -> onIo(() -> this.journal.begin(entry)));
                case "fence" -> {
                    UUID claim = UUID.randomUUID();
                    yield onMain(() -> {
                        HandoffJournal.Entry entry = require(player, transfer, generation, HandoffJournal.Role.SOURCE);
                        if (entry.phase() == HandoffJournal.Phase.EXPORTED && System.currentTimeMillis() >= entry.expiresAtMillis()) {
                            throw new IllegalStateException("Source snapshot expired before fencing");
                        }
                        if (this.exporting.putIfAbsent(player, claim) != null) {
                            throw new IllegalStateException("A source journal write is already pending");
                        }
                        return entry;
                    }).thenCompose(entry -> onIo(() -> {
                        if (entry.phase() == HandoffJournal.Phase.EXPORTED && System.currentTimeMillis() >= entry.expiresAtMillis()) {
                            throw new IllegalStateException("Source snapshot expired before fencing");
                        }
                        return this.journal.transition(player, transfer, generation, HandoffJournal.Phase.FENCED);
                    })).whenComplete((entry, failure) -> this.exporting.remove(player, claim));
                }
                case "commit" -> onIo(() -> {
                    require(player, transfer, generation, HandoffJournal.Role.DESTINATION);
                    HandoffJournal.Entry current = this.journal.get(player);
                    if (current.phase() == HandoffJournal.Phase.ACTIVATED) return current;
                    return this.journal.transition(player, transfer, generation, HandoffJournal.Phase.COMMITTED);
                });
                case "abort" -> onIo(() -> this.journal.transition(player, transfer, generation, HandoffJournal.Phase.ABORTED));
                case "release" -> onIo(() -> {
                    require(player, transfer, generation, HandoffJournal.Role.SOURCE);
                    return this.journal.transition(player, transfer, generation, HandoffJournal.Phase.RELEASED);
                });
                case "status" -> onIo(() -> {
                    HandoffJournal.Entry entry = this.journal.get(player);
                    if (entry == null || !entry.transfer().equals(transfer) || entry.generation() != generation) {
                        throw new IllegalStateException("Unknown handoff");
                    }
                    return entry;
                });
                default -> throw new IllegalArgumentException("Unknown handoff operation");
            };
        } catch (Exception failure) {
            this.pending.release();
            return CompletableFuture.failedFuture(failure);
        }
        return operation.thenApply(entry -> {
            JsonObject reply = new JsonObject();
            reply.addProperty("status", "OK");
            reply.add("entry", GSON.toJsonTree(entry));
            return reply;
        }).whenComplete((result, failure) -> this.pending.release());
    }

    private HandoffJournal.Entry export(JsonObject request, UUID player, UUID transfer, long generation, UUID claim) {
        HandoffJournal.Entry old = this.journal.get(player);
        if (old != null && old.transfer().equals(transfer)) {
            return require(player, transfer, generation, HandoffJournal.Role.SOURCE);
        }
        ServerPlayer live = this.server.getPlayerList().getPlayer(player);
        if (live == null || !live.isAlive() || live.isPassenger() || live.isVehicle()
            || live.containerMenu != live.inventoryMenu || live.level().getWorld() != this.world.get()) {
            throw new IllegalStateException("Player is not in a transferable hub state");
        }
        if (this.exporting.containsKey(player) || isFrozen(player)) {
            throw new IllegalStateException("Player already has a handoff in progress");
        }
        HubSnapshot snapshot = new HubSnapshot(this.worldIdentity, this.mapRevision, live.getX(), live.getY(), live.getZ(),
            live.getYRot(), live.getXRot(), live.getDeltaMovement().x, live.getDeltaMovement().y, live.getDeltaMovement().z);
        HandoffJournal.Entry proposed = entry(request, player, transfer, generation, HandoffJournal.Role.SOURCE,
            HandoffJournal.Phase.EXPORTED, snapshot);
        this.exporting.put(player, claim);
        return proposed;
    }

    private CompletableFuture<HandoffJournal.Entry> stage(JsonObject request, UUID player, UUID transfer, long generation) {
        HandoffJournal.Entry old = this.journal.get(player);
        HubSnapshot snapshot = GSON.fromJson(request.get("snapshot"), HubSnapshot.class);
        HandoffJournal.Entry proposed = entry(request, player, transfer, generation, HandoffJournal.Role.DESTINATION,
            HandoffJournal.Phase.STAGED, snapshot);
        if (old != null && old.transfer().equals(transfer)) {
            return CompletableFuture.completedFuture(proposed);
        }
        if (this.server.getPlayerList().getPlayer(player) != null) {
            throw new IllegalStateException("Player is already present at destination");
        }
        World target = this.world.get();
        Location location = new Location(target, snapshot.x(), snapshot.y(), snapshot.z());
        if (!target.getWorldBorder().isInside(location) || snapshot.y() < target.getMinHeight()
            || snapshot.y() >= target.getMaxHeight()) {
            throw new IllegalArgumentException("Landing position is outside the destination world");
        }
        java.util.List<CompletableFuture<?>> chunks = new java.util.ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                chunks.add(target.getChunkAtAsync((location.getBlockX() >> 4) + x, (location.getBlockZ() >> 4) + z, true));
            }
        }
        return CompletableFuture.allOf(chunks.toArray(new CompletableFuture<?>[0])).thenApply(ignored -> proposed);
    }

    private HandoffJournal.Entry entry(JsonObject request, UUID player, UUID transfer, long generation,
                                      HandoffJournal.Role role, HandoffJournal.Phase phase, HubSnapshot snapshot) {
        String source = request.get("source").getAsString();
        String destination = request.get("destination").getAsString();
        long expires = request.get("expiresAtMillis").getAsLong();
        if (!request.get("profile").getAsString().equals("hub-position")
            || !this.worldIdentity.equals(snapshot.worldIdentity()) || !this.mapRevision.equals(snapshot.mapRevision())
            || !(role == HandoffJournal.Role.SOURCE ? source : destination).equals(this.serverId)
            || expires <= System.currentTimeMillis() || expires - System.currentTimeMillis() > 60000) {
            throw new IllegalArgumentException("Incompatible or expired hub handoff");
        }
        return new HandoffJournal.Entry(transfer, player, generation, source, destination, role, phase, snapshot, expires);
    }

    private HandoffJournal.Entry require(UUID player, UUID transfer, long generation, HandoffJournal.Role role) {
        HandoffJournal.Entry entry = this.journal.get(player);
        if (entry == null || !entry.transfer().equals(transfer) || entry.generation() != generation || entry.role() != role) {
            throw new IllegalStateException("Unknown or superseded handoff");
        }
        return entry;
    }

    public boolean isFrozen(UUID player) {
        HandoffJournal.Entry entry = this.journal.get(player);
        return this.exporting.containsKey(player) || entry != null && entry.role() == HandoffJournal.Role.SOURCE
            && (entry.phase() == HandoffJournal.Phase.FENCED
            || entry.phase() == HandoffJournal.Phase.EXPORTED && System.currentTimeMillis() < entry.expiresAtMillis());
    }

    boolean hasPending() {
        return !this.exporting.isEmpty() || this.journal.snapshot().values().stream().anyMatch(entry -> switch (entry.phase()) {
            case EXPORTED, STAGED, FENCED, COMMITTED -> true;
            default -> false;
        });
    }

    public CompletableFuture<@Nullable Arrival> prepareArrival(UUID player, long savedGeneration) {
        HandoffJournal.Entry entry = this.journal.get(player);
        if (entry == null || entry.phase() == HandoffJournal.Phase.ABORTED || entry.phase() == HandoffJournal.Phase.RELEASED) {
            return CompletableFuture.completedFuture(null);
        }
        if (entry.role() == HandoffJournal.Role.SOURCE || entry.phase() == HandoffJournal.Phase.STAGED) {
            return CompletableFuture.failedFuture(new IllegalStateException("Player ownership is held by a handoff"));
        }
        if (entry.phase() == HandoffJournal.Phase.ACTIVATED && savedGeneration >= entry.generation()) {
            return CompletableFuture.completedFuture(null);
        }
        World target = this.world.get();
        return onIo(() -> this.journal.transition(player, entry.transfer(), entry.generation(), HandoffJournal.Phase.ACTIVATED))
            .thenApply(active -> new Arrival(active.generation(), active.snapshot(), target));
    }

    public void applyArrival(ServerPlayer player, Arrival arrival) {
        player.getBukkitEntity().getPersistentDataContainer().set(GENERATION, PersistentDataType.LONG, arrival.generation());
        player.setDeltaMovement(arrival.snapshot().velocityX(), arrival.snapshot().velocityY(), arrival.snapshot().velocityZ());
    }

    private <T> CompletableFuture<T> onMain(Supplier<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        this.server.execute(() -> {
            try { result.complete(operation.get()); }
            catch (Exception failure) { result.completeExceptionally(failure); }
        });
        return result;
    }

    private <T> CompletableFuture<T> onIo(IoSupplier<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try { return operation.get(); }
            catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, this.io);
    }

    @FunctionalInterface
    private interface IoSupplier<T> { T get() throws Exception; }

    @Override
    public void close() {
        this.io.shutdown();
    }
}
