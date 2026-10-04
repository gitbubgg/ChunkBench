package dev.chunkbench;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /chunkbench start [intervalSeconds=10] [radius=3000] [y=250]
 * Starts the Spark profiler, teleports the player to 8 fixed points around the origin (same order every run),
 * waits, then stops the profiler so the report link lands in chat. Run it on a freshly deleted world with the same
 * seed each time so every run generates identical terrain.
 *
 * Folia-safe: all player work uses the player's EntityScheduler and teleportAsync, no BukkitScheduler.
 */
public final class ChunkBenchPlugin extends JavaPlugin {
    /** Compass order: E, SE, S, SW, W, NW, N, NE. Multiplied by the radius. */
    private static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
    private static final long TAIL_TICKS = 30 * 20L;   // settle time after the last teleport before stopping

    private record Run(boolean wasInvulnerable) {}
    private final Map<UUID, Run> running = new ConcurrentHashMap<>();

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Run this in-game."); return true; }
        if (!p.hasPermission("chunkbench.run")) { p.sendMessage("No permission."); return true; }
        String sub = a.length == 0 ? "" : a[0].toLowerCase();

        if (sub.equals("stop")) {
            if (running.containsKey(p.getUniqueId())) finish(p, "Stopped early.");
            else p.sendMessage("No benchmark running.");
            return true;
        }
        if (!sub.equals("start")) { p.sendMessage("Usage: /" + label + " start [intervalSeconds] [radius] [y] | stop"); return true; }
        if (Bukkit.getPluginManager().getPlugin("spark") == null) { p.sendMessage("Spark is not installed."); return true; }
        if (running.containsKey(p.getUniqueId())) { p.sendMessage("Already running. Use /" + label + " stop."); return true; }

        int parsedInterval, parsedRadius, parsedY;
        try {
            parsedInterval = a.length > 1 ? Integer.parseInt(a[1]) : 10;
            parsedRadius = a.length > 2 ? Integer.parseInt(a[2]) : 3000;
            parsedY = a.length > 3 ? Integer.parseInt(a[3]) : 250;
        } catch (NumberFormatException e) { p.sendMessage("Numbers only."); return true; }

        // Final copies, because lambdas can only capture variables that are never reassigned.
        final int every = Math.max(3, parsedInterval);
        final int rad = parsedRadius;
        final int yy = parsedY;

        running.put(p.getUniqueId(), new Run(p.isInvulnerable()));
        p.setInvulnerable(true);                       // landing in terrain or falling should not kill the test
        p.sendMessage("Benchmark starting: 8 stops, " + every + "s apart, radius " + rad + ", y " + yy
                + ". Total about " + (8 * every + 32) + "s.");
        runCommand(p, "spark profiler start");
        schedule(p, 40, () -> step(p, 0, every, rad, yy));   // 2s head start so the profiler is running
        return true;
    }

    private void step(Player p, int i, int interval, int radius, int y) {
        if (!running.containsKey(p.getUniqueId())) return;
        if (i >= DIRS.length) {                        // all stops done: let the server settle, then stop
            p.sendMessage("All stops done. Settling for 30s.");
            schedule(p, TAIL_TICKS, () -> finish(p, "Benchmark complete."));
            return;
        }
        World w = Bukkit.getWorlds().getFirst();      // overworld, fixed so every run matches
        Location to = new Location(w, DIRS[i][0] * (double) radius + 0.5, y, DIRS[i][1] * (double) radius + 0.5);
        p.teleportAsync(to);                           // loads and generates the destination off the main thread
        p.sendMessage("Stop " + (i + 1) + "/8: " + (int) to.getX() + ", " + y + ", " + (int) to.getZ());
        schedule(p, interval * 20L, () -> step(p, i + 1, interval, radius, y));
    }

    private void finish(Player p, String msg) {
        Run r = running.remove(p.getUniqueId());
        if (r == null) return;
        p.setInvulnerable(r.wasInvulnerable());
        p.sendMessage(msg + " Stopping the profiler, the report link will appear in chat.");
        runCommand(p, "spark profiler stop");
        runCommand(p, "spark tps");
        if (Bukkit.getPluginManager().getPlugin("ChunkGuard") != null) runCommand(p, "chunkguard status");
    }

    /** Runs a command as the player on their own region thread, so Spark's reply and link go to their chat. */
    private void runCommand(Player p, String command) {
        p.getScheduler().run(this, t -> Bukkit.dispatchCommand(p, command), null);
    }

    private void schedule(Player p, long ticks, Runnable r) {
        p.getScheduler().runDelayed(this, t -> r.run(), () -> {   // retired callback: player left, clean up
            Run run = running.remove(p.getUniqueId());
            if (run != null) getLogger().warning(p.getName() + " left mid-benchmark; run aborted.");
        }, ticks);
    }
}
