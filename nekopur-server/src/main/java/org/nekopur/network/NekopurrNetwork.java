package org.nekopur.network;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Main-thread world lifecycle with an independent control-plane worker. */
@NullMarked
public final class NekopurrNetwork implements AutoCloseable {
    private final MinecraftServer server;
    private final NetworkConfig config;
    private final PurroxyConnection connection;
    private final BackendLifecycle lifecycle;
    public final @Nullable BackendHandoff handoff;
    private boolean prepared;
    private @Nullable World destination;
    private @Nullable CompletableFuture<Void> preparation;
    private final java.util.concurrent.atomic.AtomicInteger playerIds = new java.util.concurrent.atomic.AtomicInteger();
    private long preparationStarted;
    private boolean preparationStallReported;
    private boolean destinationReplacedReported;

    private NekopurrNetwork(MinecraftServer server, NetworkConfig config) throws Exception {
        this.server = server;
        this.config = config;
        this.lifecycle = new BackendLifecycle(config.startSleeping(), config.authentication().equals("pairing"));
        this.connection = new PurroxyConnection(config, server.getPort(), server.getPlayerList().getMaxPlayers(),
            () -> this.lifecycle.heartbeat(System.nanoTime()), request -> this.lifecycle.wake(), server.server.getLogger());
        this.lifecycle.onTransition(message -> server.server.getLogger().info(message));
        this.connection.enableSleep(request -> this.lifecycle.requestSleep());
        this.handoff = BackendHandoff.create(server, config, () -> {
            if (!this.prepared || this.destination == null) {
                throw new IllegalStateException("Handoff world is not ready");
            }
            return this.destination;
        });
        if (this.handoff != null) {
            this.connection.enableHandoff(this.handoff.capabilities(), this.handoff::handle);
            this.handoff.onReserveEntityId(this::reserveEntityId);
            this.connection.onAssignedName(name -> {
                this.handoff.assignedName(name);
                // A first enrolment learns its range only now; raising is monotonic, so applying it
                // late is safe and the persisted value applies from the next startup onwards.
                applyEntityIdBase(this.connection.entityIdBase());
            });
        }
    }

    public static @Nullable NekopurrNetwork start(MinecraftServer server) {
        try {
            NetworkConfig config = NetworkConfig.load(Path.of("Nekopurr.yaml"));
            if (!config.enabled()) {
                return null;
            }
            NekopurrNetwork network = new NekopurrNetwork(server, config);
            // Runs after worlds and plugins, so entities already present keep their low ids and only
            // later allocations (players above all) move clear of them. Purroxy is the authority for
            // the range; the configured value is a floor for backends that are not paired.
            network.applyEntityIdBase(Math.max(config.entityIdBase(), network.connection.entityIdBase()));
            network.prepareDestination();
            network.connection.start();
            return network;
        } catch (Exception failure) {
            server.server.getLogger().severe("Nekopurr network registration disabled: " + failure.getMessage());
            // Handoff journals carry ownership fences; never silently bypass them after an init failure.
            if (Files.isDirectory(Path.of("nekopurr-handoffs"))) {
                throw new IllegalStateException("Cannot start with an unreadable handoff integration", failure);
            }
            return null;
        }
    }

    private void prepareDestination() {
        try {
            World world;
            if (this.config.map().equalsIgnoreCase("none")) {
                world = this.server.overworld().getWorld();
            } else {
                Path container = this.server.server.getWorldContainer().toPath().toRealPath();
                Path folder = container.resolve(this.config.map());
                // Existing level metadata is required: WorldCreator must never generate a missing map.
                if (!Files.isDirectory(folder) || !folder.toRealPath().startsWith(container)
                    || !Files.isRegularFile(folder.resolve("level.dat"))) {
                    throw new IllegalArgumentException("Configured map does not contain an existing world: " + this.config.map());
                }
                world = this.server.server.getWorld(this.config.map());
                if (world == null) {
                    if (NbtIo.readCompressed(folder.resolve("level.dat"), NbtAccounter.create(64L * 1024 * 1024))
                        .getCompound("Data").isEmpty()) {
                        throw new IllegalArgumentException("Configured map has invalid level.dat: " + this.config.map());
                    }
                    world = this.server.server.createWorld(new WorldCreator(this.config.map()));
                }
                if (world == null) {
                    throw new IllegalStateException("Could not load map: " + this.config.map());
                }
            }
            this.destination = world;
            Location spawn = world.getSpawnLocation();
            List<CompletableFuture<?>> chunks = new ArrayList<>();
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    chunks.add(world.getChunkAtAsync((spawn.getBlockX() >> 4) + x, (spawn.getBlockZ() >> 4) + z, true));
                }
            }
            this.preparation = CompletableFuture.allOf(chunks.toArray(new CompletableFuture<?>[0]));
            this.preparationStarted = System.nanoTime();
            this.preparationStallReported = false;
            this.server.server.getLogger().info("Nekopurr is preparing " + world.getName()
                + " (" + chunks.size() + " spawn chunks) before advertising READY");
        } catch (Exception failure) {
            this.preparation = CompletableFuture.failedFuture(failure);
            this.preparationStarted = System.nanoTime();
            this.preparationStallReported = false;
        }
    }

    private void applyEntityIdBase(int base) {
        if (base > 0) {
            this.playerIds.updateAndGet(current -> Math.max(current, base));
        }
    }

    /**
     * Allocates an entity id for an arriving player from this backend's own range. World entities keep
     * the ordinary low ids; only players are lifted clear, so a player id minted here cannot collide
     * with another backend's entities, and with distinct bases cannot collide with its players either.
     * Returns 0 when no range is assigned, leaving the normal allocator in charge.
     */
    public int nextPlayerEntityId(net.minecraft.server.level.ServerLevel level) {
        if (this.playerIds.get() <= 0) {
            return 0;
        }
        int id;
        do {
            id = this.playerIds.incrementAndGet();
        } while (level.getChunkSource().hasEntityWithId(id));
        return id;
    }

    /**
     * Decides at stage time which entity id an arriving player will use, so the proxy knows before it
     * opens the connection whether the client keeps its id or must be reset.
     */
    private int reserveEntityId(int requested) {
        World target = this.destination;
        net.minecraft.server.level.ServerLevel level = target == null ? this.server.overworld()
            : ((org.bukkit.craftbukkit.CraftWorld) target).getHandle();
        if (claims(requested) && !level.getChunkSource().hasEntityWithId(requested)) {
            return requested;
        }
        return nextPlayerEntityId(level);
    }

    /** True when this backend may keep an id a client already holds, rather than minting a new one. */
    public boolean claims(int requestedEntityId) {
        return requestedEntityId > 0 && this.playerIds.get() > 0;
    }

    /** Called before vanilla's pause decision. A READY network server must keep world ticks running. */
    public int tick(boolean canSleep) {
        int players = this.server.getPlayerList().getPlayerCount();
        if (this.lifecycle.beginTick(players)) {
            this.prepared = false;
            prepareDestination();
        }
        CompletableFuture<Void> pending = this.preparation;
        if (pending != null && pending.isDone()) {
            this.preparation = null;
            try {
                pending.join();
                this.prepared = true;
                this.server.server.getLogger().info("Nekopurr destination prepared in "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - this.preparationStarted) + "ms");
            } catch (RuntimeException failure) {
                this.server.server.getLogger().severe("Nekopurr map unavailable; remaining REGISTERING: " + failure.getMessage());
            }
        } else if (pending != null && !this.preparationStallReported
            && System.nanoTime() - this.preparationStarted > TimeUnit.SECONDS.toNanos(30)) {
            // A future that never completes is otherwise invisible: the backend just stays REGISTERING.
            this.preparationStallReported = true;
            this.server.server.getLogger().warning("Nekopurr has been preparing "
                + (this.destination == null ? this.config.map() : this.destination.getName())
                + " for over 30 seconds; the backend stays REGISTERING until its spawn chunks load.");
        }
        World world = this.destination;
        if (world != null && this.server.server.getWorld(world.getUID()) != world) {
            if (!this.destinationReplacedReported) {
                this.destinationReplacedReported = true;
                this.server.server.getLogger().severe("Nekopurr destination world " + world.getName()
                    + " is no longer the world registered under its UID; the backend cannot become READY."
                    + " A plugin has replaced or reloaded it.");
            }
            this.prepared = false;
        }
        return this.lifecycle.tick(canSleep && (this.handoff == null || !this.handoff.hasPending()),
            this.prepared, players, System.nanoTime());
    }

    /** Applied before loading player spawn chunks; map=none leaves plugin spawn decisions intact. */
    public @Nullable Location arrivalLocation() {
        World world = this.destination;
        if (this.config.map().equalsIgnoreCase("none") || !this.prepared || world == null) {
            return null;
        }
        return world.getSpawnLocation();
    }

    @Override
    public void close() {
        this.connection.close();
        if (this.handoff != null) {
            this.handoff.close();
        }
    }
}
