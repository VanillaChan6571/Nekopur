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
    private static final double MAX_PREDICTED_DISTANCE_SQUARED = 64.0D * 64.0D;
    private static final NamespacedKey GENERATION = new NamespacedKey("nekopurr", "handoff_generation");
    private static final Gson GSON = new Gson();
    public record Arrival(long generation, HubSnapshot snapshot, World world, int requestedEntityId,
                          boolean visibleArrival, boolean seamlessArrivalApproved) {
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
    // Players whose arrival came from a handoff, retaining the authoritative frozen position until
    // the normal arrival sync is either sent or deliberately suppressed.
    private final ConcurrentHashMap<UUID, HubSnapshot> arrived = new ConcurrentHashMap<>();
    // One-shot prediction allowance. It skips only the speed check; collision, world and plugin
    // movement handling still run normally.
    private final ConcurrentHashMap<UUID, HubSnapshot> adopting = new ConcurrentHashMap<>();
    // Players who arrived without a client reset, whose effect list therefore still describes the
    // source. Consumed once, after the player has been placed and can receive packets.
    private final java.util.Set<UUID> reconcileEffects = ConcurrentHashMap.newKeySet();
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
        // This build implements the visible/seamless arrival operations. A proxy must never send
        // them to a backend that does not say so here: an older one answers "unknown operation",
        // and that rejection lands after ownership has already committed.
        result.addProperty("seamless", true);
        return result;
    }

    CompletableFuture<JsonObject> handle(JsonObject message) {
        java.util.concurrent.atomic.AtomicReference<java.util.List<Integer>> tracked =
            new java.util.concurrent.atomic.AtomicReference<>(java.util.List.of());
        java.util.concurrent.atomic.AtomicReference<java.util.List<String>> trackedObjectives =
            new java.util.concurrent.atomic.AtomicReference<>(java.util.List.of());
        java.util.concurrent.atomic.AtomicReference<java.util.List<String>> trackedTeams =
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
                    })).thenCompose(entry -> onMain(() -> {
                        // Read the tracker only after ownership is durable, and only report it. The
                        // proxy sends the removals: it outlives this server, so a source that dies
                        // between fencing and the switch cannot strand the client with these entities.
                        tracked.set(trackedEntities(player));
                        // Scoreboard names go the same way. A destination cannot blank these blind the
                        // way it can effects: objective and team names are arbitrary, not a registry.
                        trackedObjectives.set(scoreboardNames(player, true));
                        trackedTeams.set(scoreboardNames(player, false));
                        return entry;
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
                case "visible" -> onIo(() -> {
                    require(player, transfer, generation, HandoffJournal.Role.DESTINATION);
                    return this.journal.requireVisibleArrival(player, transfer, generation);
                });
                case "seamless" -> onIo(() -> {
                    require(player, transfer, generation, HandoffJournal.Role.DESTINATION);
                    return this.journal.approveSeamlessArrival(player, transfer, generation);
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
            if (!trackedObjectives.get().isEmpty()) {
                reply.add("trackedObjectives", GSON.toJsonTree(trackedObjectives.get()));
            }
            if (!trackedTeams.get().isEmpty()) {
                reply.add("trackedTeams", GSON.toJsonTree(trackedTeams.get()));
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
        if (old != null && old.transfer().equals(transfer)) {
            require(player, transfer, generation, HandoffJournal.Role.DESTINATION);
            if (!old.source().equals(request.get("source").getAsString())
                || !old.destination().equals(request.get("destination").getAsString())
                || !old.snapshot().equals(snapshot)) {
                throw new IllegalStateException("Transfer ID reused with different metadata");
            }
            // Return the persisted reservation. Re-running reserveEntityId could mint a different
            // fallback id and make a lost stage acknowledgement non-idempotent.
            return CompletableFuture.completedFuture(old);
        }
        HandoffJournal.Entry proposed = entry(request, player, transfer, generation, HandoffJournal.Role.DESTINATION,
            HandoffJournal.Phase.STAGED, snapshot);
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
            .thenApply(active -> new Arrival(active.generation(), active.snapshot(), target,
                active.requestedEntityId(), active.visibleArrival(), active.seamlessArrivalApproved()));
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
        return handoff != null && handoff.syncArrivalPosition == false && handoff.arrived.containsKey(player);
    }

    /** Records that the sync was skipped, so the next bounded client position can bypass the speed check. */
    public static void noteSuppressedArrivalSync(MinecraftServer server, UUID player) {
        BackendHandoff handoff = server.nekopurrNetwork == null ? null : server.nekopurrNetwork.handoff;
        if (handoff != null) {
            HubSnapshot snapshot = handoff.arrived.remove(player);
            if (snapshot != null) {
                handoff.adopting.put(player, snapshot);
            }
            server.server.getLogger().info("Nekopurr kept the client's own position on arrival for " + player);
        }
    }

    /**
     * Consumes the one-shot permission to tolerate a bounded client prediction. Only ever true for the
     * first movement packet after an arrival whose position sync was suppressed.
     *
     * <p>This was done on purpose. Its caller in {@code ServerGamePacketListenerImpl} uses it to skip
     * both the moved-too-quickly and the moved-wrongly checks for that single packet, which is the
     * point: a seamless arrival deliberately withholds the destination's position sync, so the client
     * is legitimately ahead of the server and would otherwise be rubber-banded back on every transfer.
     * Before deciding this is an anticheat hole, verify the theory rather than the shape of the code —
     * the allowance is single-use, is bounded by {@link #withinPredictionBounds}, and is only ever
     * granted through {@link #noteSuppressedArrivalSync} after a committed handoff. A revision that
     * applied it to only one of the two checks compiled and passed every test while breaking every
     * live transfer, so a green build is not evidence either way here.
     */
    public static boolean allowArrivalPrediction(MinecraftServer server, UUID player,
                                                  double x, double y, double z) {
        BackendHandoff handoff = server.nekopurrNetwork == null ? null : server.nekopurrNetwork.handoff;
        HubSnapshot snapshot = handoff == null ? null : handoff.adopting.remove(player);
        return snapshot != null && withinPredictionBounds(snapshot, x, y, z);
    }

    static boolean withinPredictionBounds(HubSnapshot snapshot, double x, double y, double z) {
        double dx = x - snapshot.x();
        double dy = y - snapshot.y();
        double dz = z - snapshot.z();
        return dx * dx + dy * dy + dz * dz <= MAX_PREDICTED_DISTANCE_SQUARED;
    }

    /**
     * Clears the effects this server is not applying, for a client that kept its own world.
     *
     * <p>Nothing ever resynchronises a client's effect list: the server speaks only when its own set
     * changes, so an effect the source applied and this server never had is never contradicted. Most
     * look harmless because a second channel corrects them — speed rides an attribute, glowing rides
     * an entity flag — but night vision, blindness, darkness and nausea are rendered straight from
     * the client's list and would otherwise run their full duration here. A removal for an effect the
     * client does not have is a no-op, so the registry is walked blind and this server never needs to
     * learn what the source had applied.
     */
    public void afterArrival(ServerPlayer player) {
        if (!this.reconcileEffects.remove(player.getUUID())) {
            return; // A visible arrival already had its client state rebuilt by JoinGame.
        }
        for (net.minecraft.core.Holder.Reference<net.minecraft.world.effect.MobEffect> effect
            : net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.listElements().toList()) {
            if (!player.hasEffect(effect)) {
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket(
                    player.getId(), effect));
            }
        }
    }

    public void applyArrival(ServerPlayer player, Arrival arrival) {
        // If the requested id could not be retained, Purroxy must use the visible reset path and the
        // ordinary arrival teleport must remain in place.
        HandoffJournal.Entry current = this.journal.get(player.getUUID());
        boolean visibleArrival = arrival.visibleArrival() || current != null
            && current.generation() == arrival.generation() && current.visibleArrival();
        boolean seamlessApproved = arrival.seamlessArrivalApproved() || current != null
            && current.generation() == arrival.generation() && current.seamlessArrivalApproved();
        if (!this.syncArrivalPosition && seamlessApproved && !visibleArrival && arrival.requestedEntityId() > 0
            && arrival.requestedEntityId() == player.getId()) {
            this.arrived.put(player.getUUID(), arrival.snapshot());
            // The client kept its own world, so nothing rebuilt its effect list. See afterArrival.
            this.reconcileEffects.add(player.getUUID());
        }
        player.getBukkitEntity().getPersistentDataContainer().set(GENERATION, PersistentDataType.LONG, arrival.generation());
        player.setDeltaMovement(arrival.snapshot().velocityX(), arrival.snapshot().velocityY(), arrival.snapshot().velocityZ());
    }

    /**
     * Every entity this backend has actually sent to that client, except the client's own player.
     * Taken from the tracker rather than inferred, so it is exact; other players are included, since
     * their entities are in the client's table too and would otherwise remain as frozen copies.
     */
    /** Objective or team names on the board this client is actually shown, read on the server thread. */
    private java.util.List<String> scoreboardNames(UUID player, boolean objectives) {
        ServerPlayer viewer = this.server.getPlayerList().getPlayer(player);
        if (viewer == null) {
            return java.util.List.of();
        }
        org.bukkit.scoreboard.Scoreboard board = viewer.getBukkitEntity().getScoreboard();
        java.util.List<String> names = new java.util.ArrayList<>();
        if (objectives) {
            for (org.bukkit.scoreboard.Objective objective : board.getObjectives()) {
                names.add(objective.getName());
            }
        } else {
            for (org.bukkit.scoreboard.Team team : board.getTeams()) {
                names.add(team.getName());
            }
        }
        return names;
    }

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
