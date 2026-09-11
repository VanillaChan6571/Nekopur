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
    public record Arrival(long generation, HubSnapshot snapshot, World world, int requestedEntityId) {
        public Location location() {
            return new Location(world, snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch());
        }
    }

    private final MinecraftServer server;
    private volatile String serverId;
    private final String worldIdentity;
    private final String mapRevision;
    private final Supplier<World> world;
    // Given the id the client already holds, returns the id this backend will actually use: the same
    // one when it is free, otherwise a fresh one from this backend's player block.
    private java.util.function.IntUnaryOperator reserveEntityId = requested -> requested;
    // Players whose arrival came from a handoff, so their position sync can be skipped once.
    private final java.util.Set<UUID> arrived = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // One-shot: the next movement packet from these players is taken as authoritative.
    private final java.util.Set<UUID> adopting = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final boolean syncArrivalPosition;
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
        // Kept true by default: correcting the client's position on arrival is the ordinary, safe
        // behaviour. Set false to let a seamless arrival keep the position the client predicted.
        boolean syncArrivalPosition = yaml.getBoolean("transfers.sync-arrival-position", true);
        return new BackendHandoff(server, config.serverId(), yaml.getString("transfers.map-id", yaml.getString("handoff.world-identity", "")),
            yaml.getString("transfers.map-revision", yaml.getString("handoff.map-revision", "")), world, syncArrivalPosition);
    }

    void assignedName(String name) {
        this.serverId = name;
    }

    private BackendHandoff(MinecraftServer server, String serverId, String worldIdentity,
                           String mapRevision, Supplier<World> world, boolean syncArrivalPosition) throws Exception {
        new HubSnapshot(worldIdentity, mapRevision, 0, 0, 0, 0, 0, 0, 0, 0);
        this.syncArrivalPosition = syncArrivalPosition;
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
        java.util.concurrent.atomic.AtomicReference<java.util.List<Integer>> tracked =
            new java.util.concurrent.atomic.AtomicReference<>(java.util.List.of());
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
                        // The entity tracker is main-thread state, so read it here rather than when
                        // the reply is assembled on the IO thread.
                        tracked.set(clearTrackedEntities(player));
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
            if (!tracked.get().isEmpty()) {
                // The proxy removes these from the client itself, so a source that dies between here
                // and the switch cannot leave the client holding entities nothing will ever clear.
                reply.add("trackedEntities", GSON.toJsonTree(tracked.get()));
            }
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
        // Optional: an absent or zero id means the destination allocates its own, which is the
        // normal visible switch. Only a seamless transfer that keeps the client in PLAY asks for one.
        int requestedEntityId = request.has("requestedEntityId") ? request.get("requestedEntityId").getAsInt() : 0;
        if (requestedEntityId < 0) {
            throw new IllegalArgumentException("Invalid requested entity id");
        }
        if (role == HandoffJournal.Role.DESTINATION) {
            // Reserve now, before the client connects, so the reply tells the proxy which id it gets.
            requestedEntityId = this.reserveEntityId.applyAsInt(requestedEntityId);
        }
        return new HandoffJournal.Entry(transfer, player, generation, source, destination, role, phase, snapshot,
            expires, requestedEntityId);
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
            .thenApply(active -> new Arrival(active.generation(), active.snapshot(), target, active.requestedEntityId()));
    }

    /** Supplies the reservation strategy; the proxy learns the result from the stage reply. */
    public void onReserveEntityId(java.util.function.IntUnaryOperator reservation) {
        this.reserveEntityId = reservation;
    }

    /**
     * True when this player arrived through a seamless handoff and the arrival position sync should
     * be skipped, so a client that kept moving is not pulled back to where the source froze it.
     */
    public static boolean suppressArrivalSync(MinecraftServer server, UUID player) {
        BackendHandoff handoff = server.nekopurrNetwork == null ? null : server.nekopurrNetwork.handoff;
        return handoff != null && handoff.syncArrivalPosition == false && handoff.arrived.contains(player);
    }

    /** Records that the sync was skipped, so the next client position is adopted rather than rejected. */
    public static void noteSuppressedArrivalSync(MinecraftServer server, UUID player) {
        BackendHandoff handoff = server.nekopurrNetwork == null ? null : server.nekopurrNetwork.handoff;
        if (handoff != null) {
            handoff.arrived.remove(player);
            handoff.adopting.add(player);
            server.server.getLogger().info("Nekopurr kept the client's own position on arrival for " + player);
        }
    }

    /**
     * Consumes the one-shot permission to take the client's reported position verbatim. Only ever true
     * for the first movement packet after an arrival whose position sync was suppressed.
     */
    public static boolean adoptArrivalPosition(MinecraftServer server, UUID player) {
        BackendHandoff handoff = server.nekopurrNetwork == null ? null : server.nekopurrNetwork.handoff;
        return handoff != null && handoff.adopting.remove(player);
    }

    public void applyArrival(ServerPlayer player, Arrival arrival) {
        this.arrived.add(player.getUUID());
        player.getBukkitEntity().getPersistentDataContainer().set(GENERATION, PersistentDataType.LONG, arrival.generation());
        player.setDeltaMovement(arrival.snapshot().velocityX(), arrival.snapshot().velocityY(), arrival.snapshot().velocityZ());
    }

    /**
     * Every entity this backend has actually sent to that client, except the client's own player.
     * Taken from the tracker rather than inferred, so it is exact; other players are included, since
     * their entities are in the client's table too and would otherwise remain as frozen copies.
     */
    private java.util.List<Integer> trackedEntities(UUID player) {
        ServerPlayer viewer = this.server.getPlayerList().getPlayer(player);
        if (viewer == null) {
            return java.util.List.of();
        }
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (var tracked : viewer.level().getChunkSource().chunkMap.entityMap.int2ObjectEntrySet()) {
            if (tracked.getIntKey() != viewer.getId() && tracked.getValue().seenBy.contains(viewer.connection)) {
                ids.add(tracked.getIntKey());
            }
        }
        return ids;
    }

    /**
     * Removes from the client every entity this backend had shown it, and reports what was removed.
     * Done at fence, while the player is frozen and before the destination sends anything, so the
     * client's entity table is empty when the destination starts populating it. Without this the
     * client keeps this server's entities and their ids alias the destination's own, which leaves
     * NPCs visible but unclickable.
     */
    private java.util.List<Integer> clearTrackedEntities(UUID player) {
        ServerPlayer viewer = this.server.getPlayerList().getPlayer(player);
        java.util.List<Integer> ids = trackedEntities(player);
        if (viewer != null && !ids.isEmpty()) {
            int[] removed = new int[ids.size()];
            for (int index = 0; index < removed.length; index++) {
                removed[index] = ids.get(index);
            }
            viewer.connection.send(new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(removed));
            this.server.server.getLogger().info("Nekopurr cleared " + removed.length
                + " tracked entities from a handed-over client");
        }
        return ids;
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
