package org.nekopur.worldgen;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Bounded native surface previews shared by DH delivery and admin prewarming. */
@NullMarked
public final class NekoLodService {
    private static final Object LOCK = new Object();
    private static final Map<UUID, WorldState> WORLDS = new java.util.HashMap<>();
    private static final LinkedHashMap<Key, CachedTile> CACHE = new LinkedHashMap<>(16, .75f, true);
    private static final Map<Key, CompletableFuture<NekoDistantTerrain.SurfaceColumn[]>> PENDING = new java.util.HashMap<>();
    private static final Map<Key, CompletableFuture<NekoDistantTerrain.SurfaceColumn[]>> UPGRADING = new java.util.HashMap<>();
    private static final RefinementPolicy REFINEMENT = new RefinementPolicy(128);
    private static final java.util.Set<Consumer<SurfaceUpdate>> UPGRADE_LISTENERS = new java.util.concurrent.CopyOnWriteArraySet<>();
    private static final AtomicLong BUILT = new AtomicLong();
    private static final AtomicLong UPGRADED = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private static volatile int capacity = 128;
    private static volatile boolean enabled = true;
    private static volatile boolean paused;
    private static @Nullable ThreadPoolExecutor executor;
    private static @Nullable ThreadPoolExecutor upgradeExecutor;
    private static @Nullable YamlConfiguration config;
    private static @Nullable File configFile;

    private NekoLodService() { }

    public static void configure(YamlConfiguration yaml, File file) {
        primary();
        config = yaml;
        configFile = file;
        yaml.addDefault("nekopur.lod-preview.enabled", true);
        yaml.addDefault("nekopur.lod-preview.workers", 2);
        yaml.addDefault("nekopur.lod-preview.cache-tiles", 128);
        enabled = yaml.getBoolean("nekopur.lod-preview.enabled", true);
        capacity = Math.clamp(yaml.getInt("nekopur.lod-preview.cache-tiles", 128), 1, 4096);
        int workers = Math.clamp(yaml.getInt("nekopur.lod-preview.workers", 2), 1, 8);
        yaml.addDefault("nekopur.lod-preview.upgrade-workers", workers);
        int upgradeWorkers = Math.clamp(yaml.getInt("nekopur.lod-preview.upgrade-workers", workers), 1, 8);
        java.util.List<CompletableFuture<NekoDistantTerrain.SurfaceColumn[]>> removed;
        synchronized (LOCK) {
            if (upgradeExecutor == null) {
                upgradeExecutor = new ThreadPoolExecutor(upgradeWorkers, upgradeWorkers, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), task -> {
                    Thread thread = new Thread(task, "Neko-LOD-Upgrade");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                });
                upgradeExecutor.allowCoreThreadTimeOut(true);
            } else {
                resize(upgradeExecutor, upgradeWorkers);
            }
            if (executor == null) {
                executor = new ThreadPoolExecutor(workers, workers, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), task -> {
                    Thread thread = new Thread(task, "Neko-LOD-Preview");
                    thread.setDaemon(true);
                    return thread;
                });
                executor.allowCoreThreadTimeOut(true);
            } else {
                resize(executor, workers);
            }
            // A reload may change settings or controls: old pending work cannot repopulate the cache.
            WORLDS.values().forEach(state -> state.epoch++);
            CACHE.clear();
            removed = new java.util.ArrayList<>(PENDING.values());
            removed.addAll(UPGRADING.values());
            PENDING.clear();
            UPGRADING.clear();
        }
        cancel(removed);
    }

    private static void resize(ThreadPoolExecutor pool, int workers) {
        if (workers > pool.getMaximumPoolSize()) pool.setMaximumPoolSize(workers);
        pool.setCorePoolSize(workers);
        pool.setMaximumPoolSize(workers);
    }

    /** Bound an admin batch to the corresponding shared pool, without filling its queue. */
    public static int prewarmConcurrency(boolean accurate) {
        primary();
        synchronized (LOCK) {
            ThreadPoolExecutor pool = accurate ? upgradeExecutor : executor;
            if (pool == null) throw new IllegalStateException("Native preview workers are not configured");
            return pool.getMaximumPoolSize();
        }
    }

    public static boolean isEnabled(World world) {
        primary();
        return enabled && !paused && (config == null || config.getBoolean("nekopur.lod-preview.worlds." + world.getName() + ".enabled", true));
    }

    public static void setEnabled(World world, boolean value) throws IOException {
        primary();
        YamlConfiguration yaml = config;
        File file = configFile;
        if (yaml == null || file == null) throw new IllegalStateException("LOD preview configuration is not initialized");
        String path = "nekopur.lod-preview.worlds." + world.getName() + ".enabled";
        Object previous = yaml.get(path);
        yaml.set(path, value);
        try { yaml.save(file); }
        catch (IOException error) { yaml.set(path, previous); throw error; }
        clear(world);
    }

    public static void setPaused(boolean value) {
        primary();
        paused = value;
        java.util.List<CompletableFuture<NekoDistantTerrain.SurfaceColumn[]>> removed;
        synchronized (LOCK) {
            WORLDS.values().forEach(state -> state.epoch++);
            // Completed tiles remain reusable after unpausing.
            removed = java.util.List.copyOf(PENDING.values());
            PENDING.clear();
            removed = new java.util.ArrayList<>(removed);
            removed.addAll(UPGRADING.values());
            UPGRADING.clear();
        }
        cancel(removed);
    }

    public static void clear(World world) {
        primary();
        java.util.List<CompletableFuture<NekoDistantTerrain.SurfaceColumn[]>> removed = new java.util.ArrayList<>();
        synchronized (LOCK) {
            WorldState state = WORLDS.get(world.getUID());
            if (state != null) state.epoch++;
            REFINEMENT.clear(world.getUID());
            CACHE.keySet().removeIf(key -> key.world.equals(world.getUID()));
            PENDING.entrySet().removeIf(entry -> {
                if (!entry.getKey().world.equals(world.getUID())) return false;
                removed.add(entry.getValue());
                return true;
            });
            UPGRADING.entrySet().removeIf(entry -> {
                if (!entry.getKey().world.equals(world.getUID())) return false;
                removed.add(entry.getValue());
                return true;
            });
        }
        cancel(removed);
    }

    public static void release(World world) {
        primary();
        clear(world);
        synchronized (LOCK) { WORLDS.remove(world.getUID()); }
    }

    /** Also safe when the watchdog initiates server shutdown on a different thread. */
    public static void shutdown() {
        java.util.List<CompletableFuture<NekoDistantTerrain.SurfaceColumn[]>> pending;
        ThreadPoolExecutor workers;
        ThreadPoolExecutor upgrades;
        synchronized (LOCK) {
            enabled = false;
            WORLDS.values().forEach(state -> state.epoch++);
            pending = new java.util.ArrayList<>(PENDING.values());
            pending.addAll(UPGRADING.values());
            PENDING.clear();
            UPGRADING.clear();
            CACHE.clear();
            WORLDS.clear();
            REFINEMENT.clear();
            workers = executor;
            executor = null;
            upgrades = upgradeExecutor;
            upgradeExecutor = null;
        }
        if (workers != null) workers.shutdownNow();
        if (upgrades != null) upgrades.shutdownNow();
        UPGRADE_LISTENERS.clear();
        cancel(pending);
    }

    /** Seed predictions stay approximate; invalidate intersecting cached tiles on authoritative updates. */
    public static void invalidate(World world, int blockX, int blockZ) {
        primary();
        java.util.List<CompletableFuture<NekoDistantTerrain.SurfaceColumn[]>> removed = new java.util.ArrayList<>();
        synchronized (LOCK) {
            CACHE.keySet().removeIf(key -> key.world.equals(world.getUID()) && key.contains(blockX, blockZ));
            PENDING.entrySet().removeIf(entry -> {
                if (!entry.getKey().world.equals(world.getUID()) || !entry.getKey().contains(blockX, blockZ)) return false;
                removed.add(entry.getValue());
                return true;
            });
            UPGRADING.entrySet().removeIf(entry -> {
                if (!entry.getKey().world.equals(world.getUID()) || !entry.getKey().contains(blockX, blockZ)) return false;
                removed.add(entry.getValue());
                return true;
            });
        }
        cancel(removed);
    }

    /** Call on the primary thread. Native density sampling happens exclusively on bounded workers. */
    public static CompletableFuture<NekoDistantTerrain.SurfaceColumn[]> sampleSurfaceAsync(World world, int minX, int minZ, int stride) {
        try { prepare(world, minX, minZ, stride); }
        catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
        Key key = new Key(world.getUID(), minX, minZ, stride);
        boolean accurateRequired;
        synchronized (LOCK) {
            accurateRequired = REFINEMENT.accurate(key.world, minX, minZ, stride) || UPGRADING.containsKey(key);
        }
        if (accurateRequired) return sampleAccurateSurfaceAsync(world, minX, minZ, stride);
        WorldState captured;
        long epoch;
        ThreadPoolExecutor workers;
        CompletableFuture<NekoDistantTerrain.SurfaceColumn[]> result;
        synchronized (LOCK) {
            captured = WORLDS.get(world.getUID());
            if (captured == null) return CompletableFuture.failedFuture(new IllegalStateException("World preview state unavailable"));
            CachedTile cached = CACHE.get(key);
            if (cached != null) { HITS.incrementAndGet(); return CompletableFuture.completedFuture(cached.columns.clone()); }
            CompletableFuture<NekoDistantTerrain.SurfaceColumn[]> pending = PENDING.get(key);
            if (pending != null) return pending.thenApply(NekoDistantTerrain.SurfaceColumn[]::clone);
            workers = executor;
            if (workers == null) return CompletableFuture.failedFuture(new IllegalStateException("Native preview workers are not configured"));
            result = new CompletableFuture<>();
            PENDING.put(key, result);
            epoch = captured.epoch;
        }
        try {
            workers.execute(() -> {
                try {
                    synchronized (LOCK) {
                        checkRough(captured, epoch, key, result);
                        if (redirectRough(world, captured, epoch, key, result)) return;
                    }
                    NekoDistantTerrain.SurfaceColumn[] tile = captured.snapshot.newSampler().sampleSurfaceTile(minX, minZ, stride);
                    // Order delivery with primary-thread policy changes; no preliminary result can slip past a new accurate requirement.
                    MinecraftServer.getServer().scheduleOnMain(() -> publishRough(world, captured, epoch, key, result, tile));
                } catch (Throwable error) {
                    boolean owns;
                    synchronized (LOCK) { owns = PENDING.remove(key, result); }
                    if (owns) result.completeExceptionally(error);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            synchronized (LOCK) { PENDING.remove(key, result); }
            result.completeExceptionally(new IllegalStateException("Native preview queue is full; retry later", error));
        }
        return result.thenApply(NekoDistantTerrain.SurfaceColumn[]::clone);
    }

    private static void checkRough(WorldState state, long epoch, Key key, CompletableFuture<?> result) {
        if (state.epoch != epoch || paused || !enabled || PENDING.get(key) != result) {
            throw new java.util.concurrent.CancellationException("Native preview invalidated");
        }
    }

    private static void publishRough(World world, WorldState state, long epoch, Key key,
                                     CompletableFuture<NekoDistantTerrain.SurfaceColumn[]> result,
                                     NekoDistantTerrain.SurfaceColumn[] tile) {
        try {
            synchronized (LOCK) {
                checkRough(state, epoch, key, result);
                if (redirectRough(world, state, epoch, key, result)) return;
                CachedTile current = CACHE.get(key);
                if (current != null && current.accurate) tile = current.columns;
                else CACHE.put(key, new CachedTile(tile, false));
                while (CACHE.size() > capacity) CACHE.remove(CACHE.keySet().iterator().next());
                PENDING.remove(key, result);
            }
            BUILT.incrementAndGet();
            result.complete(tile);
        } catch (Throwable error) {
            boolean owns;
            synchronized (LOCK) { owns = PENDING.remove(key, result); }
            if (owns) result.completeExceptionally(error);
        }
    }

    /** Called with LOCK held. Keep the old waiter tracked until the primary thread transfers it. */
    private static boolean redirectRough(World world, WorldState state, long epoch, Key key,
                                         CompletableFuture<NekoDistantTerrain.SurfaceColumn[]> result) {
        if (!REFINEMENT.accurate(key.world, key.x, key.z, key.stride) && !UPGRADING.containsKey(key)) return false;
        MinecraftServer.getServer().scheduleOnMain(() -> {
            boolean revoked;
            synchronized (LOCK) {
                if (state.epoch != epoch || PENDING.get(key) != result) return;
                revoked = !REFINEMENT.accurate(key.world, key.x, key.z, key.stride) && !UPGRADING.containsKey(key);
                if (revoked) PENDING.remove(key, result);
            }
            if (revoked) {
                result.completeExceptionally(new java.util.concurrent.CancellationException("Accurate preview policy revoked"));
                return;
            }
            sampleAccurateSurfaceAsync(world, key.x, key.z, key.stride).whenComplete((accurate, error) -> {
                synchronized (LOCK) { PENDING.remove(key, result); }
                if (error != null) result.completeExceptionally(error);
                else result.complete(accurate);
            });
        });
        return true;
    }

    private static void prepare(World world, int minX, int minZ, int stride) {
        primary();
        if (Bukkit.getWorld(world.getUID()) != world) throw new IllegalStateException("World is unloaded or replaced");
        // Drop generators from unloaded/replaced pooled worlds before accepting new work.
        java.util.List<CompletableFuture<NekoDistantTerrain.SurfaceColumn[]>> removed = new java.util.ArrayList<>();
        synchronized (LOCK) {
            java.util.List<UUID> unloaded = WORLDS.entrySet().stream()
                .filter(entry -> entry.getValue().world.get() == null || Bukkit.getWorld(entry.getKey()) != entry.getValue().world.get())
                .map(Map.Entry::getKey).toList();
            for (UUID id : unloaded) {
                WorldState stale = WORLDS.remove(id);
                if (stale != null) stale.epoch++;
                REFINEMENT.clear(id);
                CACHE.keySet().removeIf(key -> key.world.equals(id));
                PENDING.entrySet().removeIf(entry -> {
                    if (!entry.getKey().world.equals(id)) return false;
                    removed.add(entry.getValue());
                    return true;
                });
                UPGRADING.entrySet().removeIf(entry -> {
                    if (!entry.getKey().world.equals(id)) return false;
                    removed.add(entry.getValue());
                    return true;
                });
            }
        }
        cancel(removed);
        if (!isEnabled(world)) throw new IllegalStateException("Native LOD previews are disabled or paused");
        if (stride < 2 || stride > 16 || Integer.bitCount(stride) != 1) {
            throw new IllegalArgumentException("Coarse preview stride must be 2, 4, 8 or 16");
        }
        int width = 64 * stride;
        if (Math.floorMod(minX, width) != 0 || Math.floorMod(minZ, width) != 0) {
            throw new IllegalArgumentException("Coarse preview tiles must be aligned to their width");
        }
        synchronized (LOCK) {
            WorldState state = WORLDS.get(world.getUID());
            if (state == null || state.world.get() != world) {
                CACHE.keySet().removeIf(old -> old.world.equals(world.getUID()));
                WORLDS.put(world.getUID(), new WorldState(world, NekoDistantTerrain.capture(world)));
            }
        }
    }

    /**
     * Register a session-long footprint, independent of tile detail and cache
     * residency. Bounds are absolute blocks with exclusive maxima. The most
     * recent 128 regions are retained; pause preserves them, while clear,
     * disable, unload, cancel and shutdown revoke the applicable policy.
     */
    public static void registerRefinementRegion(World world, int minX, int minZ, int maxXExclusive, int maxZExclusive) {
        primary();
        if (Bukkit.getWorld(world.getUID()) != world) throw new IllegalStateException("World is unloaded or replaced");
        if (!isEnabled(world)) throw new IllegalStateException("Native LOD previews are disabled or paused");
        NekoDistantTerrain.capture(world);
        synchronized (LOCK) {
            REFINEMENT.register(world.getUID(), minX, minZ, maxXExclusive, maxZExclusive);
        }
    }

    /** Require final native density for every requested coarse tile intersecting this session footprint. */
    public static void registerAccuratePrewarmRegion(World world, int minX, int minZ, int maxXExclusive, int maxZExclusive) {
        primary();
        if (Bukkit.getWorld(world.getUID()) != world) throw new IllegalStateException("World is unloaded or replaced");
        if (!isEnabled(world)) throw new IllegalStateException("Native LOD previews are disabled or paused");
        NekoDistantTerrain.capture(world);
        synchronized (LOCK) {
            REFINEMENT.register(world.getUID(), minX, minZ, maxXExclusive, maxZExclusive, true);
        }
    }

    public static boolean isAccuratePrewarmEnabled(World world, int minX, int minZ, int stride) {
        primary();
        if (Bukkit.getWorld(world.getUID()) != world || !isEnabled(world)) return false;
        synchronized (LOCK) { return REFINEMENT.accurate(world.getUID(), minX, minZ, stride); }
    }

    /** Whether this actual requested detail-7..10 tile intersects an authorized refinement footprint. */
    public static boolean isRefinementEnabled(World world, int minX, int minZ, int stride) {
        primary();
        if (Bukkit.getWorld(world.getUID()) != world || !isEnabled(world)) return false;
        synchronized (LOCK) {
            return REFINEMENT.enabled(world.getUID(), minX, minZ, stride);
        }
    }

    /** Cancel session refinement authorization and outstanding accurate work, preserving completed cache entries. */
    public static void clearRefinementRegions() {
        primary();
        java.util.List<CompletableFuture<NekoDistantTerrain.SurfaceColumn[]>> removed;
        synchronized (LOCK) {
            REFINEMENT.clear();
            removed = java.util.List.copyOf(UPGRADING.values());
            UPGRADING.clear();
        }
        cancel(removed);
    }

    /** Upgrade the same sparse cells from final native density, without creating gameplay chunks. */
    public static CompletableFuture<NekoDistantTerrain.SurfaceColumn[]> upgradeSurfaceAsync(World world, int minX, int minZ, int stride) {
        return sampleAccurateSurfaceAsync(world, minX, minZ, stride);
    }

    /** Accurate-first sparse native NOISE sampling; never queues a preliminary surface pass. */
    public static CompletableFuture<NekoDistantTerrain.SurfaceColumn[]> sampleAccurateSurfaceAsync(World world, int minX, int minZ, int stride) {
        try { prepare(world, minX, minZ, stride); }
        catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
        Key key = new Key(world.getUID(), minX, minZ, stride);
        WorldState captured;
        long epoch;
        ThreadPoolExecutor workers;
        CompletableFuture<NekoDistantTerrain.SurfaceColumn[]> result;
        CompletableFuture<NekoDistantTerrain.SurfaceColumn[]> rough;
        CachedTile cached;
        synchronized (LOCK) {
            captured = WORLDS.get(world.getUID());
            if (captured == null) return CompletableFuture.failedFuture(new IllegalStateException("World preview state unavailable"));
            epoch = captured.epoch;
            cached = CACHE.get(key);
            CompletableFuture<NekoDistantTerrain.SurfaceColumn[]> pending = UPGRADING.get(key);
            if (pending != null) return pending.thenApply(NekoDistantTerrain.SurfaceColumn[]::clone);
            workers = upgradeExecutor;
            if (workers == null) return CompletableFuture.failedFuture(new IllegalStateException("Native upgrade worker is not configured"));
            result = new CompletableFuture<>();
            UPGRADING.put(key, result);
            // Transfer old preliminary waiters; detached workers cannot publish or fail these waiters.
            rough = PENDING.remove(key);
        }
        if (rough != null) result.whenComplete((tile, error) -> {
            if (error != null) rough.completeExceptionally(error);
            else rough.complete(tile);
        });
        if (cached != null && cached.accurate) {
            HITS.incrementAndGet();
            MinecraftServer.getServer().scheduleOnMain(() -> publishAccurate(world, captured, epoch, key, result, cached.columns));
        } else {
            try {
                workers.execute(() -> {
                    try {
                        synchronized (LOCK) { checkUpgrade(captured, epoch, key, result); }
                        NekoDistantTerrain.SurfaceColumn[] tile = captured.snapshot.newSampler().sampleAccurateSurfaceTile(minX, minZ, stride);
                        MinecraftServer.getServer().scheduleOnMain(() -> publishAccurate(world, captured, epoch, key, result, tile));
                    } catch (Throwable error) {
                        synchronized (LOCK) { UPGRADING.remove(key, result); }
                        result.completeExceptionally(error);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException error) {
                synchronized (LOCK) { UPGRADING.remove(key, result); }
                result.completeExceptionally(new IllegalStateException("Native upgrade queue is full; retry later", error));
            }
        }
        return result.thenApply(NekoDistantTerrain.SurfaceColumn[]::clone);
    }

    private static void publishAccurate(World world, WorldState state, long epoch, Key key,
                                       CompletableFuture<NekoDistantTerrain.SurfaceColumn[]> result,
                                       NekoDistantTerrain.SurfaceColumn[] tile) {
        try {
            synchronized (LOCK) {
                checkUpgrade(state, epoch, key, result);
                CACHE.put(key, new CachedTile(tile, true));
                while (CACHE.size() > capacity) CACHE.remove(CACHE.keySet().iterator().next());
                UPGRADING.remove(key, result);
            }
            UPGRADED.incrementAndGet();
            notifyUpgrade(new SurfaceUpdate(world, key.x, key.z, key.stride, tile));
            result.complete(tile);
        } catch (Throwable error) {
            synchronized (LOCK) { UPGRADING.remove(key, result); }
            result.completeExceptionally(error);
        }
    }

    private static void checkUpgrade(WorldState state, long epoch, Key key, CompletableFuture<?> result) {
        if (state.epoch != epoch || paused || !enabled || UPGRADING.get(key) != result) {
            throw new java.util.concurrent.CancellationException("Native upgrade invalidated");
        }
    }

    private static void notifyUpgrade(SurfaceUpdate update) {
        for (Consumer<SurfaceUpdate> listener : UPGRADE_LISTENERS) {
            try { listener.accept(update); }
            catch (RuntimeException error) { Bukkit.getLogger().log(java.util.logging.Level.WARNING, "Native LOD upgrade delivery failed", error); }
        }
    }

    public static void addUpgradeListener(Consumer<SurfaceUpdate> listener) { primary(); UPGRADE_LISTENERS.add(java.util.Objects.requireNonNull(listener)); }
    public static void removeUpgradeListener(Consumer<SurfaceUpdate> listener) { primary(); UPGRADE_LISTENERS.remove(listener); }

    public static boolean isSurfaceAccurate(World world, int minX, int minZ, int stride) {
        primary();
        synchronized (LOCK) {
            CachedTile tile = CACHE.get(new Key(world.getUID(), minX, minZ, stride));
            return tile != null && tile.accurate;
        }
    }

    /** Callback payload survives native memory eviction. Heights are base noise; cover is approximate. */
    public record SurfaceUpdate(World world, int minX, int minZ, int stride, NekoDistantTerrain.SurfaceColumn[] columns) {
        public SurfaceUpdate { columns = columns.clone(); }
        @Override public NekoDistantTerrain.SurfaceColumn[] columns() { return columns.clone(); }
    }

    private record CachedTile(NekoDistantTerrain.SurfaceColumn[] columns, boolean accurate) { }

    public static String status() {
        synchronized (LOCK) {
            ThreadPoolExecutor workers = executor;
            return "enabled=" + enabled + " paused=" + paused + " cache=" + CACHE.size() + "/" + capacity
                + " built=" + BUILT.get() + " hits=" + HITS.get() + " pending=" + PENDING.size()
                + " workers=" + (workers == null ? 0 : workers.getMaximumPoolSize())
                + " active=" + (workers == null ? 0 : workers.getActiveCount())
                + " queued=" + (workers == null ? 0 : workers.getQueue().size())
                + " upgraded=" + UPGRADED.get() + " upgrading=" + UPGRADING.size()
                + " upgrade-workers=" + (upgradeExecutor == null ? 0 : upgradeExecutor.getMaximumPoolSize())
                + " upgrade-active=" + (upgradeExecutor == null ? 0 : upgradeExecutor.getActiveCount())
                + " upgrade-queued=" + (upgradeExecutor == null ? 0 : upgradeExecutor.getQueue().size())
                + " refinement-regions=" + REFINEMENT.size()
                + " upgrade-listeners=" + UPGRADE_LISTENERS.size();
        }
    }

    private static void primary() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Native preview controls require the primary server thread");
    }

    private static void cancel(java.util.List<CompletableFuture<NekoDistantTerrain.SurfaceColumn[]>> futures) {
        futures.forEach(future -> future.completeExceptionally(new java.util.concurrent.CancellationException("Native preview invalidated")));
    }

    private record Key(UUID world, int x, int z, int stride) {
        boolean contains(int blockX, int blockZ) {
            return blockX >= x && (long) blockX < (long) x + 64 * stride
                && blockZ >= z && (long) blockZ < (long) z + 64 * stride;
        }
    }

    /** Pure region policy, exercised independently of Bukkit and native sampling. Guard production access with LOCK. */
    static final class RefinementPolicy {
        private final int limit;
        private final LinkedHashMap<RefinementRegion, Boolean> regions = new LinkedHashMap<>();

        RefinementPolicy(int limit) {
            if (limit < 1) throw new IllegalArgumentException("Refinement region limit must be positive");
            this.limit = limit;
        }

        void register(UUID world, int minX, int minZ, int maxXExclusive, int maxZExclusive) {
            register(world, minX, minZ, maxXExclusive, maxZExclusive, false);
        }

        void register(UUID world, int minX, int minZ, int maxXExclusive, int maxZExclusive, boolean accurate) {
            if (minX >= maxXExclusive || minZ >= maxZExclusive || minX < -29_999_984 || minZ < -29_999_984
                || maxXExclusive > 29_999_985 || maxZExclusive > 29_999_985) {
                throw new IllegalArgumentException("Refinement bounds must be a nonempty rectangle within supported world coordinates");
            }
            RefinementRegion region = new RefinementRegion(world, minX, minZ, maxXExclusive, maxZExclusive);
            Boolean previous = regions.remove(region);
            boolean strongest = accurate || Boolean.TRUE.equals(previous);
            regions.put(region, strongest);
            while (regions.size() > limit) regions.remove(regions.keySet().iterator().next());
        }

        boolean enabled(UUID world, int minX, int minZ, int stride) {
            return intersects(world, minX, minZ, stride, false);
        }

        boolean accurate(UUID world, int minX, int minZ, int stride) {
            return intersects(world, minX, minZ, stride, true);
        }

        private boolean intersects(UUID world, int minX, int minZ, int stride, boolean accurateOnly) {
            if (stride != 2 && stride != 4 && stride != 8 && stride != 16) return false;
            int width = 64 * stride;
            if (Math.floorMod(minX, width) != 0 || Math.floorMod(minZ, width) != 0
                || minX < -29_999_984 || minZ < -29_999_984
                || (long) minX + width > 29_999_985 || (long) minZ + width > 29_999_985) return false;
            for (Map.Entry<RefinementRegion, Boolean> entry : regions.entrySet()) {
                if (accurateOnly && !entry.getValue()) continue;
                RefinementRegion region = entry.getKey();
                if (region.world.equals(world) && minX < region.maxXExclusive && (long) minX + width > region.minX
                    && minZ < region.maxZExclusive && (long) minZ + width > region.minZ) return true;
            }
            return false;
        }

        void clear(UUID world) { regions.keySet().removeIf(region -> region.world.equals(world)); }
        void clear() { regions.clear(); }
        int size() { return regions.size(); }
    }

    private record RefinementRegion(UUID world, int minX, int minZ, int maxXExclusive, int maxZExclusive) { }

    private static final class WorldState {
        final java.lang.ref.WeakReference<World> world;
        final NekoDistantTerrain.Snapshot snapshot;
        long epoch;
        WorldState(World world, NekoDistantTerrain.Snapshot snapshot) { this.world = new java.lang.ref.WeakReference<>(world); this.snapshot = snapshot; }
    }
}
