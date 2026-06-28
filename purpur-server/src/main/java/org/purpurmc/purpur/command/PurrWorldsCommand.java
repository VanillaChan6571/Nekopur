package org.purpurmc.purpur.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.nekopur.Neko;
import org.nekopur.WorldPool;
import org.nekopur.world.WorldPoolImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class PurrWorldsCommand extends Command {
    public PurrWorldsCommand(String name) {
        super(name);
        this.description = "Nekopur world pool status and teleport";
        this.usageMessage = "/purrworlds [list | hop <world>]";
        this.setPermission("bukkit.command.purrworlds");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args, Location location)
            throws IllegalArgumentException {
        if (args.length == 1) {
            return filterCompletions(List.of("list", "hop"), args[0]);
        }
        if (args.length == 2 && "hop".equalsIgnoreCase(args[0])) {
            List<String> worlds = Bukkit.getWorlds().stream()
                    .map(World::getName)
                    .sorted()
                    .toList();
            return filterCompletions(worlds, args[1]);
        }
        return Collections.emptyList();
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!testPermission(sender)) return true;

        if (args.length == 0 || "list".equalsIgnoreCase(args[0])) {
            sendWorldStatus(sender);
            return true;
        }

        if ("hop".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                sender.sendMessage(Component.text("Usage: " + usageMessage, NamedTextColor.RED));
                return true;
            }
            return handleHop(sender, args[1]);
        }

        sender.sendMessage(Component.text("Usage: " + usageMessage, NamedTextColor.RED));
        return true;
    }

    private boolean handleHop(CommandSender sender, String worldName) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
            return true;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            if (isWorldCreating(worldName)) {
                sender.sendMessage(Component.text("World is still being created: " + worldName, NamedTextColor.YELLOW));
                return true;
            }
            sender.sendMessage(Component.text("World not loaded: " + worldName, NamedTextColor.RED));
            return true;
        }

        player.teleport(world.getSpawnLocation());
        sender.sendMessage(Component.text("Teleported to world: " + world.getName(), NamedTextColor.GREEN));
        return true;
    }

    private void sendWorldStatus(CommandSender sender) {
        List<WorldPool> pools = new ArrayList<>(Neko.getServer().getPools());

        sender.sendMessage(Component.text("PurrWorlds", NamedTextColor.GOLD));

        if (pools.isEmpty()) {
            sender.sendMessage(Component.text("No world pools found.", NamedTextColor.YELLOW));
            listUnpooledWorlds(sender, Set.of());
            return;
        }

        Set<String> pooledWorldNames = new HashSet<>();
        for (WorldPool pool : pools) {
            PoolSnapshot snapshot = snapshot(pool);
            pooledWorldNames.addAll(snapshot.active);
            pooledWorldNames.addAll(snapshot.available);
            pooledWorldNames.addAll(snapshot.creating);

            String status = pool.isShutdown() ? "disabled" : "active";
            sender.sendMessage(Component.text("Pool: " + pool.getName() + " (" + status + ")", NamedTextColor.AQUA));
            sender.sendMessage(Component.text("  Active: " + formatList(snapshot.active), NamedTextColor.GREEN));
            sender.sendMessage(Component.text("  Waiting: " + formatWaiting(snapshot), NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("  Deleted/Disabled: " + formatDeleted(snapshot, pool.isShutdown()), NamedTextColor.RED));
        }

        listUnpooledWorlds(sender, pooledWorldNames);
    }

    private void listUnpooledWorlds(CommandSender sender, Set<String> pooledWorldNames) {
        List<String> unpooled = Bukkit.getWorlds().stream()
                .map(World::getName)
                .filter(name -> !pooledWorldNames.contains(name))
                .sorted()
                .toList();

        if (!unpooled.isEmpty()) {
            sender.sendMessage(Component.text("Active (unpooled): " + formatList(unpooled), NamedTextColor.GREEN));
        }
    }

    private PoolSnapshot snapshot(WorldPool pool) {
        if (pool instanceof WorldPoolImpl impl) {
            return new PoolSnapshot(
                    impl.getInUseWorldNames(),
                    impl.getAvailableWorldNames(),
                    impl.getCreatingWorldNames(),
                    impl.getWaitingAcquireCount(),
                    impl.getDeletedWorldNames()
            );
        }
        return new PoolSnapshot(
                pool.getStatus().getActiveWorlds(),
                pool.getStatus().getAvailableWorlds(),
                pool.getStatus().getCreatingWorlds(),
                pool.getStatus().getWaitingAcquireCount(),
                pool.getStatus().getDeletedWorlds()
        );
    }

    private boolean isWorldCreating(String worldName) {
        for (WorldPool pool : Neko.getServer().getPools()) {
            if (pool instanceof WorldPoolImpl impl && impl.getCreatingWorldNames().contains(worldName)) {
                return true;
            }
        }
        return false;
    }

    private String formatWaiting(PoolSnapshot snapshot) {
        List<String> parts = new ArrayList<>();
        if (!snapshot.available.isEmpty()) {
            parts.add("available=" + formatList(snapshot.available));
        }
        if (!snapshot.creating.isEmpty()) {
            parts.add("creating=" + formatList(snapshot.creating));
        }
        if (snapshot.waitingCount > 0) {
            parts.add("queued=" + snapshot.waitingCount);
        }
        if (parts.isEmpty()) {
            return "-";
        }
        return String.join(" | ", parts);
    }

    private String formatDeleted(PoolSnapshot snapshot, boolean disabled) {
        List<String> parts = new ArrayList<>();
        if (!snapshot.deleted.isEmpty()) {
            parts.add("deleted=" + formatList(snapshot.deleted));
        }
        if (disabled) {
            parts.add("disabled");
        }
        if (parts.isEmpty()) {
            return "-";
        }
        return String.join(" | ", parts);
    }

    private List<String> filterCompletions(List<String> values, String prefix) {
        String lower = prefix.toLowerCase();
        return values.stream()
                .filter(value -> value.toLowerCase().startsWith(lower))
                .collect(Collectors.toList());
    }

    private String formatList(List<String> values) {
        if (values.isEmpty()) {
            return "-";
        }
        return String.join(", ", values);
    }

    private record PoolSnapshot(
            List<String> active,
            List<String> available,
            List<String> creating,
            int waitingCount,
            List<String> deleted
    ) {}
}
