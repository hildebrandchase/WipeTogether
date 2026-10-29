package com.wipetogether;

import java.time.Instant;
import java.util.*;
import java.util.logging.Level;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;

final class VictoryEvents implements Listener {
    static final NamespacedKey FIREWORK_TAG = new NamespacedKey("wipetogether", "victory_firework");
    private final WipeTogether plugin;

    VictoryEvents(WipeTogether plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon && !plugin.resetInProgress()
                && event.getEntity().getWorld().getName().equals(plugin.activeEndName())) {
            plugin.getConfig().set("state.dragon-defeated-world", plugin.activeEndName());
            plugin.saveConfig();
        }
    }

    private boolean dragonDefeated() {
        if (plugin.activeEndName().equals(plugin.getConfig().getString("state.dragon-defeated-world"))) return true;
        World end = Bukkit.getWorld(plugin.activeEndName());
        // Also recognizes a dragon killed before this version was installed.
        return end != null && end.getEnderDragonBattle() != null && end.getEnderDragonBattle().hasBeenPreviouslyKilled();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExitPortalEnter(EntityPortalEnterEvent event) {
        if (event.getEntity() instanceof Player player && event.getPortalType() == PortalType.ENDER
                && event.getLocation().getWorld().getName().equals(plugin.activeEndName())) {
            complete(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExitPortalTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL
                && event.getFrom().getWorld().getName().equals(plugin.activeEndName())
                && event.getTo() != null && event.getTo().getWorld().getName().equals(plugin.activeWorldName())) {
            complete(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEndReturn(PlayerRespawnEvent event) {
        if (event.getRespawnReason() == PlayerRespawnEvent.RespawnReason.END_PORTAL
                && event.getPlayer().getWorld().getName().equals(plugin.activeEndName())) complete(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReturnedToOverworld(PlayerChangedWorldEvent event) {
        if (event.getFrom().getName().equals(plugin.activeEndName())
                && event.getPlayer().getWorld().getName().equals(plugin.activeWorldName()) && completed()) {
            // The first return may show credits. Repeat the title once the player is back.
            String world = plugin.activeWorldName();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (event.getPlayer().isOnline() && world.equals(plugin.activeWorldName()) && !plugin.resetInProgress())
                    showVictory(event.getPlayer(), plugin.getConfig().getLong("state.completion-millis"));
            }, 20L);
        }
    }

    private boolean completed() {
        return plugin.activeWorldName().equals(plugin.getConfig().getString("state.completed-world"));
    }

    void complete(Player firstReturner) {
        if (plugin.resetInProgress() || completed() || !dragonDefeated()) return;
        String world = plugin.activeWorldName();
        long elapsed = WipeTogetherTimerIntegrated.freeze(plugin);
        plugin.getConfig().set("state.completed-world", world);
        plugin.getConfig().set("state.completion-millis", elapsed);
        plugin.getConfig().set("state.run-elapsed-millis", elapsed);
        for (Player player : Bukkit.getOnlinePlayers()) plugin.trackParticipant(player);
        plugin.trackParticipant(firstReturner);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("run", plugin.currentCycle());
        record.put("world", world);
        record.put("seed", Bukkit.getWorld(world).getSeed());
        record.put("duration-millis", elapsed);
        record.put("completed-at", Instant.now().toString());
        record.put("first-returner", firstReturner.getUniqueId().toString());
        Map<String, Object> team = new LinkedHashMap<>();
        var section = plugin.getConfig().getConfigurationSection("state.run-team");
        if (section != null) team.putAll(section.getValues(false));
        record.put("team", team);
        // World identity stays unique even after /resetruns, /setrun or /resetworld.
        plugin.getConfig().createSection("wins." + world, record);
        plugin.saveConfig();
        String time = WipeTogetherTimerIntegrated.formatTime(elapsed / 1000L);
        String names = team.isEmpty() ? firstReturner.getName() : String.join(", ", team.values().stream().map(Object::toString).toList());
        plugin.messages().broadcast("chat.victory", Map.of("run", plugin.currentCycle(), "time", time,
                "team", names, "seed", record.get("seed")));
        for (Player player : Bukkit.getOnlinePlayers()) showVictory(player, elapsed);
        for (int burst = 0; burst < 3; burst++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!world.equals(plugin.activeWorldName()) || plugin.resetInProgress()) return;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.isDead()) continue;
                    try {
                        launchFirework(player.getLocation().clone().add(0, 2, 0));
                    } catch (RuntimeException error) {
                        plugin.getLogger().log(Level.WARNING, "Could not launch victory firework", error);
                    }
                }
            }, burst * 20L);
        }
    }

    private void showVictory(Player player, long millis) {
        plugin.messages().showTitle(player, "victory", Map.of("run", plugin.currentCycle(),
                "time", WipeTogetherTimerIntegrated.formatTime(millis / 1000L)));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    private void launchFirework(Location location) {
        location.getWorld().spawn(location, Firework.class, firework -> {
            firework.getPersistentDataContainer().set(FIREWORK_TAG, PersistentDataType.BYTE, (byte) 1);
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(Color.LIME, Color.YELLOW).withFade(Color.WHITE).trail(true).flicker(true).build());
            meta.setPower(1);
            firework.setFireworkMeta(meta);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework firework
                && firework.getPersistentDataContainer().has(FIREWORK_TAG, PersistentDataType.BYTE))
            event.setCancelled(true);
    }
}
