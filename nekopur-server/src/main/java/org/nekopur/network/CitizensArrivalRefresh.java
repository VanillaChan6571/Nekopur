package org.nekopur.network;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Re-tracks the player-type entities Citizens manages, once, for one client that arrived seamlessly.
 *
 * <p>A client resolves a player entity's profile from its player list at the moment the spawn
 * arrives. Citizens adds that entry and takes it away again on a delayed task - measured here at
 * forty to sixty milliseconds - while the entity itself is sent by the tracker. Where the spawn
 * lands on the wrong side of that window the client has nothing to resolve the entity against. A
 * visible switch hides this because JoinGame rebuilds the table afterwards; a seamless one keeps
 * the table, so the loss lasts until the player reconnects.
 *
 * <p><b>This is a recovery pass, not a fix for that race.</b> It does not change when Citizens
 * withdraws a profile, and the same misordering still happens on the arrival itself - including on
 * ordinary logins, where it has also been seen. All this does is give the client a second,
 * correctly ordered chance afterwards. The timing fault remains open.
 *
 * <p><b>Citizens is optional and is never linked against.</b> Everything here is reflective and
 * resolved once; a server without the plugin, or one whose skin or scheduler API differs, settles
 * into {@link State#ABSENT} and every later call returns immediately. Nothing here is allowed to
 * reach the arrival: a missing NPC is cosmetic, and refusing a player because a decoration could
 * not be redrawn would be far the worse outcome.
 */
@NullMarked
public final class CitizensArrivalRefresh {

    /** NPCs re-tracked for one arrival. Far above any plausible hub, and stops a pathological world. */
    private static final int LIMIT = 128;

    /**
     * Ticks to wait after arrival before re-tracking.
     *
     * <p>Citizens spawns its NPCs on its own schedule rather than as part of the arrival, so a pass
     * that runs at arrival can find nothing to re-track. This waits past that. It is a workaround
     * chosen to sit clear of the observed spread, not a tuned value and not a remedy for the race.
     */
    private static final int RETRACK_DELAY_TICKS = 60;

    private enum State {
        UNRESOLVED,
        ABSENT,
        READY
    }

    private final Logger logger;
    private final boolean enabled;
    private volatile State state = State.UNRESOLVED;
    private @Nullable Class<?> skinnable;
    private @Nullable Method getSkinTracker;
    private @Nullable Method updateViewer;
    private @Nullable Method getScheduler;
    private @Nullable Method runEntityTaskLater;

    /** The most recent arrival each player scheduled, so an older pass can tell it was replaced. */
    private final Map<UUID, UUID> pending = new ConcurrentHashMap<>();

    CitizensArrivalRefresh(Logger logger, boolean enabled) {
        this.logger = logger;
        this.enabled = enabled;
    }

    /**
     * Binds to Citizens once, or decides it is not there.
     *
     * <p>Deliberately pessimistic: the state is set to {@link State#ABSENT} before anything is
     * attempted, so every failure path - plugin missing, class missing, method renamed, a linkage
     * error from a partially loaded plugin - leaves the refresh off without needing its own catch.
     */
    private synchronized boolean ready() {
        if (this.state != State.UNRESOLVED) {
            return this.state == State.READY;
        }
        this.state = State.ABSENT;
        if (!this.enabled) {
            this.logger.info("Citizens NPC resync is switched off"
                + " (plugin-fixes.citizens-npc-resync: false in Nekopurr.yaml).");
            return false;
        }
        org.bukkit.plugin.Plugin citizens;
        try {
            citizens = org.bukkit.Bukkit.getPluginManager().getPlugin("Citizens");
        } catch (RuntimeException | LinkageError unavailable) {
            return false; // No plugin manager yet, or one that cannot answer. Stay off.
        }
        if (citizens == null) {
            this.logger.info("Lurked but couldn't find Citizens... carrying on."
                + " Seamless arrivals will not resync NPC player entities.");
            return false;
        }
        try {
            ClassLoader loader = citizens.getClass().getClassLoader();
            Class<?> skinnableType = Class.forName("net.citizensnpcs.npc.skin.SkinnableEntity", false, loader);
            Class<?> trackerType = Class.forName("net.citizensnpcs.npc.skin.SkinPacketTracker", false, loader);
            Class<?> apiType = Class.forName("net.citizensnpcs.api.CitizensAPI", false, loader);
            Class<?> schedulerType =
                Class.forName("net.citizensnpcs.api.util.schedulers.SchedulerAdapter", false, loader);
            this.getSkinTracker = skinnableType.getMethod("getSkinTracker");
            this.updateViewer = trackerType.getMethod("updateViewer", org.bukkit.entity.Player.class);
            this.getScheduler = apiType.getMethod("getScheduler");
            // Citizens' own scheduler is the only correct way to ask for a tick delay here: this
            // server has no plugin of its own to schedule against, and that adapter is Folia-aware.
            this.runEntityTaskLater = schedulerType.getMethod("runEntityTaskLater",
                org.bukkit.entity.Entity.class, Runnable.class, long.class);
            this.skinnable = skinnableType;
            this.state = State.READY;
            this.logger.info("Purring on Citizens " + citizens.getDescription().getVersion()
                + "! NPC resync is armed for seamless arrivals.");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError mismatch) {
            // A Citizens whose API this build does not know. Reported once, then left alone:
            // retrying per arrival would turn one incompatibility into a per-switch log flood.
            this.logger.log(Level.WARNING, "Found Citizens, but its skin or scheduler API would not"
                + " answer to its name. Seamless arrivals will not resync NPC player entities.", mismatch);
            return false;
        }
    }

    /** Schedules one re-track pass, superseding any pass this player still has queued. */
    public void refresh(ServerPlayer viewer) {
        if (!ready()) {
            return;
        }
        Method scheduler = this.getScheduler;
        Method later = this.runEntityTaskLater;
        if (scheduler == null || later == null) {
            return;
        }
        UUID arrival = UUID.randomUUID();
        UUID player = viewer.getUUID();
        ServerGamePacketListenerImpl connection = viewer.connection;
        ServerLevel level = viewer.level();
        this.pending.put(player, arrival);
        try {
            later.invoke(scheduler.invoke(null), viewer.getBukkitEntity(),
                (Runnable) () -> retrack(viewer, arrival, connection, level), (long) RETRACK_DELAY_TICKS);
            this.logger.info("Citizens re-track scheduled for " + viewer.getGameProfile().name()
                + " in " + RETRACK_DELAY_TICKS + " ticks (arrival " + arrival + ", world "
                + level.getWorld().getName() + ").");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            this.pending.remove(player, arrival);
            this.logger.log(Level.WARNING, "Could not schedule a Citizens re-track after a seamless"
                + " arrival; NPCs will not be resynced for this switch.", failure);
        }
    }

    /**
     * Re-tracks every Citizens NPC in this world for one client, profile first.
     *
     * <p>Deliberately not limited to what the client is currently shown. Server-side tracking state
     * says only that this server sent a spawn, never that the client accepted one - a client that
     * dropped an entity for want of a profile leaves the tracking entry in place - so filtering on
     * it would skip exactly the entities worth repairing.
     */
    private void retrack(ServerPlayer viewer, UUID arrival, ServerGamePacketListenerImpl connection,
                         ServerLevel level) {
        UUID player = viewer.getUUID();
        String name = viewer.getGameProfile().name();
        if (!arrival.equals(this.pending.get(player))) {
            this.logger.info("Citizens re-track " + arrival + " for " + name
                + " skipped: a later arrival replaced it.");
            return;
        }
        this.pending.remove(player, arrival);
        if (viewer.hasDisconnected() || viewer.connection != connection) {
            this.logger.info("Citizens re-track " + arrival + " for " + name
                + " skipped: the connection it was scheduled for is gone.");
            return;
        }
        if (viewer.level() != level) {
            this.logger.info("Citizens re-track " + arrival + " for " + name
                + " skipped: the player left the world it was scheduled for.");
            return;
        }
        Class<?> skinnableType = this.skinnable;
        Method tracker = this.getSkinTracker;
        Method viewerMethod = this.updateViewer;
        if (skinnableType == null || tracker == null || viewerMethod == null) {
            return;
        }
        int candidates = 0;
        int paired = 0;
        try {
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            List<ChunkMap.TrackedEntity> repair = new ArrayList<>();
            for (var entry : chunkMap.entityMap.int2ObjectEntrySet()) {
                if (repair.size() >= LIMIT) {
                    break;
                }
                if (entry.getIntKey() == viewer.getId()) {
                    continue;
                }
                net.minecraft.world.entity.Entity entity = level.getEntity(entry.getIntKey());
                if (entity == null || !skinnableType.isInstance(entity)) {
                    continue;
                }
                candidates++;
                // Citizens first, so the player-list entry exists before the spawn below. Whether
                // it reaches the wire ahead of that spawn is not provable from here - the proxy
                // packet trace is what shows the actual order.
                viewerMethod.invoke(tracker.invoke(entity), viewer.getBukkitEntity());
                repair.add(entry.getValue());
            }
            for (ChunkMap.TrackedEntity tracked : repair) {
                tracked.removePlayer(viewer);
                tracked.updatePlayer(viewer);
                if (tracked.seenBy.contains(connection)) {
                    paired++;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            this.logger.log(Level.WARNING,
                "Could not resync Citizens NPCs after a seamless arrival.", failure);
            return;
        }
        // Logged even at zero. A pass that found nothing is the most useful thing to know when an
        // NPC is still missing, and the first version of this reported nothing in that case.
        this.logger.info("Citizens re-track " + arrival + " for " + name + ": " + candidates
            + " candidate(s), " + paired + " re-paired server-side. Whether the client rendered them"
            + " is only visible in the proxy packet trace.");
    }
}
