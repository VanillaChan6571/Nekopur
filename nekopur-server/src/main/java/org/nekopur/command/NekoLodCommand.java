package org.nekopur.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.nekopur.worldgen.NekoDistantTerrain;
import org.nekopur.worldgen.NekoLodService;

/** Native controls work without a DH plugin; delivery still requires a protocol adapter. */
@NullMarked
public final class NekoLodCommand extends Command {
    private static @Nullable WarmJob job;

    public NekoLodCommand() {
        super("nekolod");
        setDescription("Control native distant terrain previews without generating playable chunks");
        setPermission("bukkit.command.nekolod");
        if (Bukkit.getPluginManager().getPermission("bukkit.command.nekolod") == null) {
            Bukkit.getPluginManager().addPermission(new Permission("bukkit.command.nekolod", PermissionDefault.OP));
        }
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermission(sender)) return true;
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        try {
            if (action.equals("status")) {
                say(sender, NekoLodService.status());
                WarmJob active = job;
                if (active != null) say(sender, "Prewarm " + active.world.getName() + ": " + active.done + "/" + active.tiles.size()
                    + (active.accurate ? " accurate tiles; " + active.reused + " reused" : " fast tiles; " + active.upgraded + "/" + active.tiles.size() + " upgrades") + "; detail " + active.detail + "; in-flight " + active.load());
                if (args.length > 1) {
                    World world = world(sender, args[1]);
                    say(sender, world.getName() + ": coarse previews " + (NekoLodService.isEnabled(world) ? "enabled" : "disabled/paused"));
                    try { say(sender, "Generator: " + NekoDistantTerrain.capture(world).preset()); }
                    catch (IllegalArgumentException unsupported) { say(sender, "Generator does not support native Neko previews."); }
                }
            } else if (action.equals("pause") || action.equals("resume")) {
                if (action.equals("pause")) cancelJob();
                NekoLodService.setPaused(action.equals("pause"));
                say(sender, "Native coarse previews " + (action.equals("pause") ? "paused." : "resumed."));
            } else if (action.equals("cancel")) {
                cancelJob();
                NekoLodService.clearRefinementRegions();
                say(sender, "Native prewarm and session refinement cancelled. Pending native sampling cancelled; completed corrections remain cached.");
            } else if (action.equals("enable") || action.equals("disable") || action.equals("clear")) {
                World world = world(sender, args.length > 1 ? args[1] : null);
                if (job != null && job.world == world) cancelJob();
                if (action.equals("clear")) {
                    NekoLodService.clear(world);
                    say(sender, "Cleared the native in-memory preview cache for " + world.getName() + ". The DH transport cache is separate.");
                } else {
                    NekoLodService.setEnabled(world, action.equals("enable"));
                    say(sender, "Native coarse previews " + action + "d for " + world.getName() + "; saved to purpur configuration.");
                }
            } else if (action.equals("sample")) {
                if (args.length != 5) throw new IllegalArgumentException("Usage: /nekolod sample <world> <block-x> <block-z> <detail 7..10>");
                World world = world(sender, args[1]);
                int detail = detail(args[4]);
                int width = 1 << detail;
                int x = Math.floorDiv(Integer.parseInt(args[2]), width) * width;
                int z = Math.floorDiv(Integer.parseInt(args[3]), width) * width;
                long start = System.nanoTime();
                NekoLodService.sampleSurfaceAsync(world, x, z, 1 << (detail - 6)).whenComplete((tile, error) -> main(() -> {
                    if (error != null) fail(sender, error);
                    else say(sender, "Preview ready: " + width + "x" + width + " blocks, 4096 samples, "
                        + (System.nanoTime() - start) / 1_000_000 + " ms. First height=" + tile[0].highestSolidY()
                        + ", biome=" + tile[0].surfaceBiome() + ". No playable chunks generated.");
                }));
            } else if (action.equals("prewarm")) {
                PrewarmOptions options = parsePrewarm(args, sender instanceof Player player ? player.getWorld().getName() : null);
                if (job != null) throw new IllegalArgumentException("A native prewarm is already running; use /nekolod cancel first.");
                World world = world(sender, options.worldName);
                NekoDistantTerrain.capture(world);
                if (!NekoLodService.isEnabled(world)) throw new IllegalArgumentException("Native coarse previews are disabled or paused.");
                List<Tile> tiles = plan(options.x, options.z, options.radius, options.detail);
                if (options.allowUpgrade) {
                    RefinementBounds bounds = refinementBounds(tiles, options.detail);
                    if (options.accurate) NekoLodService.registerAccuratePrewarmRegion(world, bounds.minX, bounds.minZ, bounds.maxXExclusive, bounds.maxZExclusive);
                    else NekoLodService.registerRefinementRegion(world, bounds.minX, bounds.minZ, bounds.maxXExclusive, bounds.maxZExclusive);
                }
                int concurrency = NekoLodService.prewarmConcurrency(options.accurate);
                say(sender, "Prewarming " + tiles.size() + " native coarse tiles at detail " + options.detail
                    + " in " + (options.accurate ? "accurate" : "fast estimated") + " mode, up to " + concurrency + " tiles concurrently. Cache is bounded; excess tiles evict older entries. Gameplay chunks are untouched.");
                if (options.accurate) say(sender, "Accurate mode samples final native terrain and aquifers immediately. Requested detail-7..10 tiles in this area also require accurate samples. Trees and structures are omitted; cached accurate tiles can finish quickly.");
                else if (options.allowUpgrade) say(sender, "allowUpgrade enabled for this region: requested detail-7..10 tiles can refine throughout this session, including after prewarm finishes. Cancel revokes refinement; trees and structures require detailed chunk data.");
                job = new WarmJob(world, sender, options.detail, tiles, options.allowUpgrade, options.accurate);
                job.start();
            } else {
                say(sender, "/nekolod status [world] | enable/disable/clear [world] | pause | resume | cancel");
                say(sender, "/nekolod sample <world> <x> <z> <detail 7..10>");
                say(sender, "/nekolod prewarm [world] <x> <z> <radius-chunks> [detail] [accurate] [allowUpgrade]");
            }
        } catch (Exception error) { fail(sender, error); }
        return true;
    }

    /** Players may omit world; consoles must name it. Numeric first arguments are coordinates. */
    public static PrewarmOptions parsePrewarm(String[] args, @Nullable String playerWorld) {
        String usage = "Usage: /nekolod prewarm [world] <block-x> <block-z> <radius-chunks 1..512> [detail 7..10] [accurate] [allowUpgrade]; console requires world";
        if (args.length < 4) throw new IllegalArgumentException(usage);
        int start = 1;
        String name;
        if (args[1].matches("[+-]?\\d+")) {
            if (playerWorld == null) throw new IllegalArgumentException(usage);
            name = playerWorld;
        } else {
            name = args[1];
            start++;
        }
        if (args.length < start + 3 || args.length > start + 6) throw new IllegalArgumentException(usage);
        int x = Integer.parseInt(args[start]), z = Integer.parseInt(args[start + 1]);
        int radius = Integer.parseInt(args[start + 2]);
        int detail = 10;
        boolean upgrade = false;
        boolean accurate = false;
        int option = start + 3;
        if (option < args.length && args[option].matches("[+-]?\\d+")) detail = detail(args[option++]);
        while (option < args.length) {
            String flag = args[option++];
            if (flag.equalsIgnoreCase("allowUpgrade") && !upgrade) upgrade = true;
            else if (flag.equalsIgnoreCase("accurate") && !accurate) accurate = true;
            else throw new IllegalArgumentException(usage);
        }
        if (option != args.length || radius < 1 || radius > 512) throw new IllegalArgumentException(usage);
        return new PrewarmOptions(name, x, z, radius, detail, upgrade || accurate, accurate);
    }

    public record PrewarmOptions(String worldName, int x, int z, int radius, int detail, boolean allowUpgrade, boolean accurate) { }

    /** Full planned tile footprint, allowing smaller client-requested tiles to refine at their actual detail. */
    public static RefinementBounds refinementBounds(List<Tile> tiles, int detail) {
        if (tiles.isEmpty() || detail < 7 || detail > 10) throw new IllegalArgumentException("Refinement requires planned detail-7..10 tiles");
        int width = 1 << detail;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        long maxX = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;
        for (Tile tile : tiles) {
            minX = Math.min(minX, tile.x);
            minZ = Math.min(minZ, tile.z);
            maxX = Math.max(maxX, (long) tile.x + width);
            maxZ = Math.max(maxZ, (long) tile.z + width);
        }
        if (minX < -29_999_984 || minZ < -29_999_984 || maxX > 29_999_985 || maxZ > 29_999_985) {
            throw new IllegalArgumentException("Refinement extends beyond supported world coordinates");
        }
        return new RefinementBounds(minX, minZ, (int) maxX, (int) maxZ);
    }

    public record RefinementBounds(int minX, int minZ, int maxXExclusive, int maxZExclusive) { }

    /** Bounded, negative-coordinate-safe plan with nearest tiles first. */
    public static List<Tile> plan(int x, int z, int radiusChunks, int detail) {
        if (radiusChunks < 1 || radiusChunks > 512 || detail < 7 || detail > 10) throw new IllegalArgumentException("Radius must be 1..512 chunks; detail must be 7..10");
        long width = 1L << detail, radius = radiusChunks * 16L;
        long left = Math.floorDiv(x - radius, width), right = Math.floorDiv(x + radius - 1, width);
        long top = Math.floorDiv(z - radius, width), bottom = Math.floorDiv(z + radius - 1, width);
        if ((right - left + 1) * (bottom - top + 1) > 1024) throw new IllegalArgumentException("Prewarm is limited to 1024 tiles; choose a coarser detail or smaller radius");
        List<Tile> tiles = new ArrayList<>();
        for (long sx = left; sx <= right; sx++) for (long sz = top; sz <= bottom; sz++) {
            long tx = sx * width, tz = sz * width;
            if (tx < -29_999_984 || tz < -29_999_984 || tx + width - 1 > 29_999_984 || tz + width - 1 > 29_999_984) {
                throw new IllegalArgumentException("Prewarm extends beyond supported world coordinates");
            }
            tiles.add(new Tile((int) tx, (int) tz));
        }
        tiles.sort(java.util.Comparator.comparingLong(tile -> Math.abs((long) tile.x + width / 2 - x) + Math.abs((long) tile.z + width / 2 - z)));
        return List.copyOf(tiles);
    }

    private static int detail(String value) {
        int result = Integer.parseInt(value);
        if (result < 7 || result > 10) throw new IllegalArgumentException("Detail must be 7..10 (7 is finer; 10 is coarser)");
        return result;
    }

    private static World world(CommandSender sender, @Nullable String name) {
        World world = name == null && sender instanceof Player player ? player.getWorld() : name == null ? null : Bukkit.getWorld(name);
        if (world == null) throw new IllegalArgumentException("Specify a loaded world name.");
        return world;
    }

    private static void cancelJob() { if (job != null) { job.cancel(); job = null; } }
    private static void main(Runnable action) { MinecraftServer.getServer().execute(action); }
    private static void say(CommandSender sender, String text) { sender.sendMessage(Component.text(text, NamedTextColor.AQUA)); }
    private static void fail(CommandSender sender, Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        sender.sendMessage(Component.text("LOD preview: " + cause.getMessage(), NamedTextColor.RED));
    }

    public record Tile(int x, int z) { }

    private static final class WarmJob {
        final World world;
        final CommandSender sender;
        final int detail;
        final List<Tile> tiles;
        final boolean allowUpgrade;
        final boolean accurate;
        final long started = System.nanoTime();
        int done;
        int upgraded;
        int reused;
        long lastProgress = started;
        boolean cancelled;
        boolean upgrading;
        @Nullable NekoPrewarmBatch<Tile, Boolean> batch;

        WarmJob(World world, CommandSender sender, int detail, List<Tile> tiles, boolean allowUpgrade, boolean accurate) {
            this.world = world; this.sender = sender; this.detail = detail; this.tiles = tiles; this.allowUpgrade = allowUpgrade; this.accurate = accurate;
        }

        void start() {
            try {
                guard();
                int concurrency = NekoLodService.prewarmConcurrency(accurate || upgrading);
                batch = new NekoPrewarmBatch<>(tiles, concurrency, this::submit,
                    action -> MinecraftServer.getServer().scheduleOnMain(action), this::completed,
                    error -> {
                        if (cancelled || job != this) return;
                        fail(sender, error);
                        finish("Native prewarm stopped.");
                    }, this::phaseFinished);
                batch.start();
            } catch (RuntimeException error) {
                if (cancelled || job != this) return;
                fail(sender, error);
                finish("Native prewarm stopped.");
            }
        }

        java.util.concurrent.CompletableFuture<Boolean> submit(Tile tile) {
            guard();
            int stride = 1 << (detail - 6);
            boolean reuse = (accurate || upgrading) && NekoLodService.isSurfaceAccurate(world, tile.x, tile.z, stride);
            java.util.concurrent.CompletableFuture<NekoDistantTerrain.SurfaceColumn[]> sampling = accurate || upgrading
                ? NekoLodService.sampleAccurateSurfaceAsync(world, tile.x, tile.z, stride)
                : NekoLodService.sampleSurfaceAsync(world, tile.x, tile.z, stride);
            return sampling.thenApply(result -> reuse);
        }

        void completed(Tile tile, boolean reuse) {
            guard();
            if (upgrading) upgraded++;
            else { done++; if (reuse) reused++; }
            long now = System.nanoTime();
            if (now - lastProgress >= 10_000_000_000L) {
                lastProgress = now;
                int complete = upgrading ? upgraded : done;
                String progress = (upgrading ? "Terrain upgrade " : accurate ? "Accurate prewarm " : "Fast prewarm ")
                    + world.getName() + ": " + complete + "/" + tiles.size() + " tiles ("
                    + String.format(Locale.ROOT, "%.1f", 100.0 * complete / tiles.size()) + "%); "
                    + reused + " reused; elapsed " + (now - started) / 1_000_000_000 + " s; detail " + detail
                    + "; in-flight " + load();
                say(sender, progress);
                if (sender instanceof Player) Bukkit.getLogger().info("[NekoLOD] " + progress);
            }
        }

        void phaseFinished() {
            guard();
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            if (accurate) {
                finish("Accurate native prewarm finished: " + done + " tiles (" + (done - reused) + " sampled, " + reused
                    + " reused accurate tiles) in " + elapsed + " ms. Final base terrain and aquifers sampled; trees and structures omitted.");
            } else if (upgrading) {
                finish("Native prewarm and terrain upgrade finished: " + upgraded + " tiles in " + elapsed
                    + " ms. Base terrain heights refined; decoration remains approximate.");
            } else if (!allowUpgrade) {
                finish("Native prewarm finished: " + done + " tiles in " + elapsed + " ms.");
            } else {
                say(sender, "Fast prewarm finished: " + done + " tiles in " + elapsed + " ms. Background terrain upgrades starting.");
                upgrading = true;
                start();
            }
        }

        String load() { return batch == null ? "0/0" : batch.inFlight() + "/" + batch.limit(); }
        void guard() {
            if (cancelled || job != this) throw new CancellationException("Prewarm cancelled");
            if (Bukkit.getWorld(world.getUID()) != world || !NekoLodService.isEnabled(world)) {
                throw new CancellationException("World unloaded or previews disabled");
            }
        }
        void cancel() { cancelled = true; if (batch != null) batch.cancel(); }
        void finish(String text) { cancel(); if (job == this) job = null; say(sender, text); }
    }
}
