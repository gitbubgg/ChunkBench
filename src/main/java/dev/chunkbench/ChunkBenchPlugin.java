package dev.chunkbench;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /chunkbench start [intervalSeconds=10] [radius=3000] [y=250]
 *     Solo run: profiles and teleports only the player who typed it.
 *
 * /chunkbench group [prefix=Bot] [intervalSeconds=10] [radius=3000] [y=250]
 *     Group run: ONE profiler start, then every online player whose name starts with the prefix is teleported at the
 *     same instant, ONE profiler stop at the end. Run it from the console or as an op. Each bot gets its own ring
 *     (radius + index * 500 blocks) and a rotated compass order, so every bot generates unique terrain and the result
 *     is identical from run to run (bots are sorted by name).
 *
 * /chunkbench stop   aborts whichever run is active.
 *
 * Folia-safe: player work uses EntityScheduler, timing uses GlobalRegionScheduler, teleports use teleportAsync.
 */
public final class ChunkBenchPlugin extends JavaPlugin {
    /** Compass order: E, SE, S, SW, W, NW, N, NE. Multiplied by the radius. */
    private static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
    private static final long TAIL_TICKS = 30 * 20L;   // settle time after the last teleport before stopping
    private static final double RING_SPACING = 500;    // blocks between bots, far more than view distance

    private record Run(boolean wasInvulnerable) {}
    private final Map<UUID, Run> running = new ConcurrentHashMap<>();   // solo runs

    private static final class Group {
        final CommandSender sender;
        final List<UUID> ids;
        final Map<UUID, Boolean> wasInvulnerable = new ConcurrentHashMap<>();
        Group(CommandSender sender, List<UUID> ids) { this.sender = sender; this.ids = ids; }
    }
    private volatile Group group;                                       // at most one group run at a time

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!sender.hasPermission("chunkbench.run")) { sender.sendMessage("No permission."); return true; }
        String sub = a.length == 0 ? "" : a[0].toLowerCase();

        switch (sub) {
            case "stop" -> {
                Group g = group;
                if (g != null) finishGroup(g, "Stopped early.");
                else if (sender instanceof Player p && running.containsKey(p.getUniqueId())) finish(p, "Stopped early.");
                else sender.sendMessage("No benchmark running.");
            }
            case "group" -> startGroup(sender, a, label);
            case "start" -> {
                if (sender instanceof Player p) startSolo(p, a, label);
                else sender.sendMessage("Run this in-game, or use /" + label + " group from the console.");
            }
            default -> sender.sendMessage("Usage: /" + label + " <start | group | stop>");
        }
        return true;
    }

    private boolean hasCommand(String name) { return Bukkit.getCommandMap().getCommand(name) != null; }

    // ---------------------------------------------------------------- group run

    private void startGroup(CommandSender sender, String[] a, String label) {
        if (!hasCommand("spark")) { sender.sendMessage("The /spark command was not found."); return; }
        if (group != null) { sender.sendMessage("A group run is already active. Use /" + label + " stop."); return; }

        String prefix = a.length > 1 ? a[1] : "Bot";
        int parsedInterval, parsedRadius, parsedY;
        try {
            parsedInterval = a.length > 2 ? Integer.parseInt(a[2]) : 10;
            parsedRadius = a.length > 3 ? Integer.parseInt(a[3]) : 3000;
            parsedY = a.length > 4 ? Integer.parseInt(a[4]) : 250;
        } catch (NumberFormatException e) { sender.sendMessage("Interval, radius and y must be numbers."); return; }
        final int every = Math.max(3, parsedInterval);
        final int rad = parsedRadius;
        final int yy = parsedY;

        // Snapshot the bots, sorted by name so Bot01 always gets the same ring and route.
        List<Player> bots = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(prefix.toLowerCase())) bots.add(p);
        }
        bots.sort(Comparator.comparing(Player::getName));
        if (bots.isEmpty()) { sender.sendMessage("No online players start with '" + prefix + "'."); return; }

        List<UUID> ids = new ArrayList<>();
        Group g = new Group(sender, ids);
        for (Player p : bots) {
            ids.add(p.getUniqueId());
            // Invulnerable so falling or landing in terrain cannot kill a bot mid-test. Set on the player's own thread.
            p.getScheduler().run(this, t -> {
                g.wasInvulnerable.put(p.getUniqueId(), p.isInvulnerable());
                p.setInvulnerable(true);
            }, null);
        }
        group = g;

        sender.sendMessage("Group benchmark: " + ids.size() + " bots, 8 stops, " + every + "s apart, radius " + rad
                + " (+" + (int) RING_SPACING + " per bot), y " + yy + ". Total about " + (8 * every + 32) + "s.");
        dispatchAs(sender, "spark profiler start");                      // exactly one profiler start
        Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> groupStep(g, 0, every, rad, yy), 40);
    }

    private void groupStep(Group g, int stop, int interval, int radius, int y) {
        if (group != g) return;                                         // aborted
        if (stop >= DIRS.length) {
            g.sender.sendMessage("All stops done. Settling for 30s.");
            Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> finishGroup(g, "Group benchmark complete."), TAIL_TICKS);
            return;
        }
        World w = Bukkit.getWorlds().getFirst();                        // overworld, fixed so every run matches
        int sent = 0;
        for (int k = 0; k < g.ids.size(); k++) {
            Player p = Bukkit.getPlayer(g.ids.get(k));
            if (p == null) continue;                                    // bot disconnected, skip it
            int[] d = DIRS[(stop + k) % DIRS.length];                   // rotate compass per bot
            double ring = radius + k * RING_SPACING;                    // unique ring per bot, no shared chunks
            p.teleportAsync(new Location(w, d[0] * ring + 0.5, y, d[1] * ring + 0.5));
            sent++;
        }
        g.sender.sendMessage("Stop " + (stop + 1) + "/8 sent to " + sent + " bots.");
        Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> groupStep(g, stop + 1, interval, radius, y), interval * 20L);
    }

    private void finishGroup(Group g, String msg) {
        if (group != g) return;
        group = null;
        for (UUID id : g.ids) {
            final Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            final boolean was = g.wasInvulnerable.getOrDefault(id, false);
            p.getScheduler().run(this, t -> p.setInvulnerable(was), null);
        }
        g.sender.sendMessage(msg + " Stopping the profiler, the report link will appear below.");
        dispatchAs(g.sender, "spark profiler stop");                    // exactly one profiler stop
        dispatchAs(g.sender, "spark tps");
        if (hasCommand("chunkguard")) dispatchAs(g.sender, "chunkguard status");
    }

    // ---------------------------------------------------------------- solo run

    private void startSolo(Player p, String[] a, String label) {
        if (!hasCommand("spark")) { p.sendMessage("The /spark command was not found."); return; }
        if (running.containsKey(p.getUniqueId())) { p.sendMessage("Already running. Use /" + label + " stop."); return; }

        int parsedInterval, parsedRadius, parsedY;
        try {
            parsedInterval = a.length > 1 ? Integer.parseInt(a[1]) : 10;
            parsedRadius = a.length > 2 ? Integer.parseInt(a[2]) : 3000;
            parsedY = a.length > 3 ? Integer.parseInt(a[3]) : 250;
        } catch (NumberFormatException e) { p.sendMessage("Numbers only."); return; }
        final int every = Math.max(3, parsedInterval);
        final int rad = parsedRadius;
        final int yy = parsedY;

        running.put(p.getUniqueId(), new Run(p.isInvulnerable()));
        p.setInvulnerable(true);
        p.sendMessage("Benchmark starting: 8 stops, " + every + "s apart, radius " + rad + ", y " + yy
                + ". Total about " + (8 * every + 32) + "s.");
        dispatchAs(p, "spark profiler start");
        schedule(p, 40, () -> step(p, 0, every, rad, yy));
    }

    private void step(Player p, int i, int interval, int radius, int y) {
        if (!running.containsKey(p.getUniqueId())) return;
        if (i >= DIRS.length) {
            p.sendMessage("All stops done. Settling for 30s.");
            schedule(p, TAIL_TICKS, () -> finish(p, "Benchmark complete."));
            return;
        }
        World w = Bukkit.getWorlds().getFirst();
        Location to = new Location(w, DIRS[i][0] * (double) radius + 0.5, y, DIRS[i][1] * (double) radius + 0.5);
        p.teleportAsync(to);
        p.sendMessage("Stop " + (i + 1) + "/8: " + (int) to.getX() + ", " + y + ", " + (int) to.getZ());
        schedule(p, interval * 20L, () -> step(p, i + 1, interval, radius, y));
    }

    private void finish(Player p, String msg) {
        Run r = running.remove(p.getUniqueId());
        if (r == null) return;
        p.setInvulnerable(r.wasInvulnerable());
        p.sendMessage(msg + " Stopping the profiler, the report link will appear in chat.");
        dispatchAs(p, "spark profiler stop");
        dispatchAs(p, "spark tps");
        if (hasCommand("chunkguard")) dispatchAs(p, "chunkguard status");
    }

    // ---------------------------------------------------------------- helpers

    /** Players run commands on their own region thread, console and others on the global thread. */
    private void dispatchAs(CommandSender s, String command) {
        if (s instanceof Player p) p.getScheduler().run(this, t -> Bukkit.dispatchCommand(p, command), null);
        else Bukkit.getGlobalRegionScheduler().run(this, t -> Bukkit.dispatchCommand(s, command));
    }

    private void schedule(Player p, long ticks, Runnable r) {
        p.getScheduler().runDelayed(this, t -> r.run(), () -> {   // retired callback: player left, clean up
            Run run = running.remove(p.getUniqueId());
            if (run != null) getLogger().warning(p.getName() + " left mid-benchmark; run aborted.");
        }, ticks);
    }
}
