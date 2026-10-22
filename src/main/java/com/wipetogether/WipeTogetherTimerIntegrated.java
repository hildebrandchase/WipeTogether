package com.wipetogether;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Same TAB presentation, with typed scheduling and one task per plugin instance.
 */
public final class WipeTogetherTimerIntegrated {
    private static BukkitTask task;
    private static WipeTogether plugin;
    private static String observedWorld;
    private static long runStartedAt;
    private static long carriedMillis;
    private static long frozenMillis = -1;

    public static void bootstrap(WipeTogether owner) {
        shutdown();
        plugin = owner;
        observedWorld = owner.activeWorldName();
        carriedMillis = Math.max(0L, owner.getConfig().getLong("state.run-elapsed-millis", 0L));
        frozenMillis = observedWorld.equals(owner.getConfig().getString("state.completed-world"))
                ? Math.max(0L, owner.getConfig().getLong("state.completion-millis")) : -1L;
        runStartedAt = System.nanoTime();
        task = Bukkit.getScheduler().runTaskTimer(owner, WipeTogetherTimerIntegrated::tick, 0L, 20L);
    }

    private static void tick() {
        if (!plugin.activeWorldName().equals(observedWorld)) resetForNewWorld(plugin);
        long elapsed = elapsedMillis(plugin);
        plugin.getConfig().set("state.run-elapsed-millis", elapsed);
        int cycle = plugin.currentCycle();
        String header = "\u00a76\u00a7lHARDCORE RUN #" + cycle + "\n\u00a7fRun Time: \u00a7a" + formatTime(elapsed / 1000L);
        for (Player player : Bukkit.getOnlinePlayers()) {
            String footer = frozenMillis >= 0 ? "\u00a76\u00a7lHARDCORE COMPLETE!" : "\u00a77One death resets the run";
            player.sendPlayerListHeaderAndFooter(LegacyComponentSerializer.legacySection().deserialize(header),
                    LegacyComponentSerializer.legacySection().deserialize(footer));
        }
    }

    static long elapsedMillis(WipeTogether owner) {
        if (owner != plugin) return Math.max(0L, owner.getConfig().getLong("state.run-elapsed-millis", 0L));
        return frozenMillis >= 0 ? frozenMillis : carriedMillis + Math.max(0L, (System.nanoTime() - runStartedAt) / 1_000_000L);
    }

    static long freeze(WipeTogether owner) {
        long elapsed = elapsedMillis(owner);
        if (owner == plugin) frozenMillis = elapsed;
        return elapsed;
    }

    static void resetForNewWorld(WipeTogether owner) {
        if (owner != plugin) return;
        observedWorld = owner.activeWorldName();
        carriedMillis = 0L;
        frozenMillis = -1L;
        runStartedAt = System.nanoTime();
    }

    static String formatTime(long seconds) {
        long hours = seconds / 3600, minutes = seconds % 3600 / 60, remainder = seconds % 60;
        return hours > 0 ? String.format("%02d:%02d:%02d", hours, minutes, remainder)
                : String.format("%02d:%02d", minutes, remainder);
    }

    public static void shutdown() {
        if (plugin != null) plugin.getConfig().set("state.run-elapsed-millis", elapsedMillis(plugin));
        if (task != null) task.cancel();
        task = null;
        if (plugin != null) for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
        plugin = null;
    }
}
