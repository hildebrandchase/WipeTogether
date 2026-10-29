package com.wipetogether;

import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.advancement.AdvancementProgress;

import java.util.ArrayList;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

public class WipeTogether
        extends JavaPlugin
        implements Listener,
        CommandExecutor,
        TabCompleter {
    private final AtomicBoolean isResetting = new AtomicBoolean(false);
    private final AtomicBoolean isPreparingStandby = new AtomicBoolean(false);
    private String activeWorldName;
    private String standbyWorldName;
    private int currentCycle = 1;
    private int countdownSeconds = 3;
    private int prepCooldownSeconds = 15;
    private String worldPrefix = "wipetogether_world_";
    private String standbyPrefix = "wipetogether_standby_";
    private String activeNetherName;
    private String activeEndName;
    private String serverDefaultName;
    private WorldStorage storage;
    private FreshRun standby;
    private boolean migrating;
    private boolean manualReset;
    private final Set<UUID> migratedPlayers = new HashSet<>();
    private ScoreboardManager scoreboardManager;
    private Scoreboard sharedScoreboard;
    private Objective deathsObjective;
    private YamlConfiguration configuration;
    private Messages messages;
    private boolean initialized;

    public void onEnable() {
        long startTime = System.currentTimeMillis();
        this.getLogger().info("=================================================");
        this.getLogger().info("[WipeTogether] Starting fresh-world reset engine / Java 21");
        this.getLogger().info("=================================================");
        serverDefaultName = Bukkit.getWorlds().getFirst().getName();
        try {
            if (getServer().getPluginManager().getPlugin("HardcoreReset") != null)
                throw new IllegalStateException("Remove the old HardcoreReset JAR before installing WipeTogether.");
            ConfigStore.migrateLegacy(getDataFolder().toPath());
            messages = Messages.load(getDataFolder().toPath().resolve("messages.yml"), getLogger()::warning);
            this.setupConfigurationSafely();
            this.initializeWorldState();
        } catch (Exception error) {
            getLogger().log(Level.SEVERE, "Cannot initialize WipeTogether. Saved configuration and worlds will be retained.", error);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        initialized = true;
        this.setupScoreboard();
        this.getServer().getPluginManager().registerEvents(this, this);
        for (String name : List.of("resetdeaths", "resetruns", "setdeaths", "setrun", "resetworld")) {
            var command = getCommand(name);
            if (command != null) {
                command.setExecutor(this);
                command.setTabCompleter(this);
                command.permissionMessage(messages().component("commands.no-permission", java.util.Map.of()));
            }
        }
        getServer().getPluginManager().registerEvents(new VictoryEvents(this), this);
        Bukkit.getScheduler().runTaskLater(this, this::checkAndPrepareStandbyWorld, 100L);
        this.getLogger().info("[WipeTogether] Plugin loaded successfully in " + (System.currentTimeMillis() - startTime) + "ms.");
        this.updateScoreboardHeader();
    }

    public void onDisable() {
        if (!initialized) return;
        initialized = false;
        this.getLogger().info("[WipeTogether] Saving state and shutting down...");
        try {
            this.saveConfig();
        } catch (UncheckedIOException error) {
            getLogger().log(Level.SEVERE, "Could not save shutdown state; last committed configuration retained.", error);
        }
    }

    @Override
    public FileConfiguration getConfig() {
        if (configuration == null) throw new IllegalStateException("Configuration has not loaded successfully");
        return configuration;
    }

    Messages messages() {
        return messages == null ? Messages.defaults() : messages;
    }

    @Override
    public void reloadConfig() {
        try {
            configuration = ConfigStore.load(getDataFolder().toPath().resolve("config.yml"), getResource("config.yml"));
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read config.yml", error);
        } catch (InvalidConfigurationException error) {
            throw new IllegalStateException("Invalid config.yml; original file retained", error);
        }
    }

    @Override
    public void saveConfig() {
        try {
            ConfigStore.save(getDataFolder().toPath().resolve("config.yml"), getConfig().saveToString());
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot commit config.yml", error);
        }
    }

    private void setupConfigurationSafely() {
        boolean fresh = !Files.exists(getDataFolder().toPath().resolve("config.yml"));
        reloadConfig();
        if (fresh) {
            getConfig().options().copyDefaults(true);
            getConfig().set("state.active-world", serverDefaultName);
        }
        this.worldPrefix = this.getConfig().getString("settings.world-prefix", "wipetogether_world_");
        this.standbyPrefix = this.getConfig().getString("settings.standby-prefix", "wipetogether_standby_");
        this.countdownSeconds = Math.max(1, this.getConfig().getInt("settings.countdown-seconds", 3));
        this.prepCooldownSeconds = Math.max(5, this.getConfig().getInt("settings.cooldown-before-standby-prep-seconds", 15));
        this.currentCycle = Math.max(1, this.getConfig().getInt("state.current-cycle", 1));
        this.activeWorldName = this.getConfig().getString("state.active-world", serverDefaultName);
        this.standbyWorldName = this.getConfig().getString("state.standby-world", this.standbyPrefix + this.currentCycle);
        if (standbyPrefix == null || !standbyPrefix.matches("[A-Za-z0-9_-]{1,80}")
                || worldPrefix == null || !worldPrefix.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("World prefixes must contain 1–80 letters, numbers, underscores or hyphens.");
        worldStorage().path(activeWorldName);
        if (fresh) saveConfig();
    }

    private void initializeWorldState() {
        serverDefaultName = Bukkit.getWorlds().getFirst().getName();
        // Login sends the spawn world's hardcore flag before PlayerJoinEvent.
        // Repair retained default worlds too: offline players can fall back to them.
        for (World world : Bukkit.getWorlds()) {
            String name = world.getName();
            if (name.equals(serverDefaultName) || name.equals(serverDefaultName + "_nether")
                    || name.equals(serverDefaultName + "_the_end")) configureHardcoreWorld(world);
        }
        World active = Bukkit.getWorld(activeWorldName);
        if (active == null) {
            if (!worldStorage().hasSavedWorld(activeWorldName)) {
                getLogger().warning("Saved active world is unavailable: " + activeWorldName + ". Starting a fresh random run at the same run number.");
                FreshRun replacement = FreshRun.create(this, standbyPrefix, currentCycle, Bukkit.getWorlds().getFirst());
                activeWorldName = replacement.name();
                activeNetherName = activeWorldName + "_nether";
                activeEndName = activeWorldName + "_the_end";
                getConfig().set("state.active-world", activeWorldName);
                getConfig().set("state.active-nether", activeNetherName);
                getConfig().set("state.active-end", activeEndName);
                getConfig().set("state.completed-world", null);
                getConfig().set("state.completion-millis", null);
                getConfig().set("state.dragon-defeated-world", null);
                getConfig().set("state.run-team", null);
                getConfig().set("state.run-elapsed-millis", 0L);
                saveConfig();
                active = replacement.overworld();
            } else {
                active = new WorldCreator(activeWorldName).environment(World.Environment.NORMAL).hardcore(true).createWorld();
            }
        }
        if (active == null || active.getEnvironment() != World.Environment.NORMAL) {
            throw new IllegalStateException("Invalid active Overworld: " + activeWorldName);
        }
        activeNetherName = getConfig().getString("state.active-nether", serverDefaultName + "_nether");
        activeEndName = getConfig().getString("state.active-end", serverDefaultName + "_the_end");
        restoreDimension(activeNetherName, World.Environment.NETHER, active.getSeed());
        restoreDimension(activeEndName, World.Environment.THE_END, active.getSeed());
        configureHardcoreWorld(active);
        // Never trust an old numeric standby name after a restart or older plugin installation.
        // New run directories are chosen before creation and never reused for a later run.
        String abandoned = getConfig().getString("state.standby-world");
        standbyWorldName = null;
        if (abandoned != null && !abandoned.equals(activeWorldName)) {
            disposeOldWorldSafely(abandoned);
            disposeOldWorldSafely(abandoned + "_nether");
            disposeOldWorldSafely(abandoned + "_the_end");
        }
        for (Player player : Bukkit.getOnlinePlayers()) routeJoiningPlayer(player);
        getLogger().info("Restored active run " + currentCycle + ": " + activeWorldName + " (seed " + active.getSeed() + ")");
    }

    private Path worldPath(String name) {
        return worldStorage().path(name);
    }

    WorldStorage worldStorage() {
        if (storage == null)
            storage = WorldStorage.detect(Bukkit.getWorldContainer().toPath(), Bukkit.getWorlds().getFirst());
        return storage;
    }

    private void restoreDimension(String name, World.Environment environment, long seed) {
        worldPath(name); // Reject paths outside the server world container.
        World world = Bukkit.getWorld(name);
        if (world == null) world = new WorldCreator(name).environment(environment).seed(seed).hardcore(true).createWorld();
        if (world == null || world.getEnvironment() != environment) {
            throw new IllegalStateException("Cannot restore dimension " + name);
        }
        configureHardcoreWorld(world);
    }

    private void configureHardcoreWorld(World world) {
        boolean changed = !world.isHardcore() || world.getDifficulty() != org.bukkit.Difficulty.HARD
                || !Boolean.FALSE.equals(world.getGameRuleValue(GameRule.DO_IMMEDIATE_RESPAWN));
        if (changed) {
            world.setHardcore(true);
            world.setDifficulty(org.bukkit.Difficulty.HARD);
        }
        // Keep the vanilla hardcore death screen and its Spectate/Title Screen choices.
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, false);
        if (changed) world.save();
    }

    private void checkAndPrepareStandbyWorld() {
        if (standby == null && !isPreparingStandby.get()) prepareStandbyWorld();
    }

    private void prepareStandbyWorld() {
        if (standby != null || !isPreparingStandby.compareAndSet(false, true)) return;
        try {
            World active = Bukkit.getWorld(activeWorldName);
            FreshRun candidate = FreshRun.create(this, standbyPrefix, currentCycle, active);
            standby = candidate;
            standbyWorldName = candidate.name();
            getConfig().set("state.standby-world", standbyWorldName);
            saveConfig();
            getLogger().info("Prepared fresh standby " + candidate.name() + " with seed " + candidate.seed());
        } catch (Exception error) {
            getLogger().log(Level.SEVERE, "Could not prepare a fresh run. Existing run retained.", error);
        } finally {
            isPreparingStandby.set(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        if (!manualReset) this.recordPlayerDeath(victim);
        if (!this.isResetting.compareAndSet(false, true)) {
            return;
        }
        manualReset = false;
        this.startResetCountdownSequence(victim);
    }

    private void recordPlayerDeath(Player victim) {
        String uuidStr = victim.getUniqueId().toString();
        int previousDeaths = this.getConfig().getInt("deaths." + uuidStr + ".count", 0);
        int currentDeaths = previousDeaths == Integer.MAX_VALUE ? previousDeaths : previousDeaths + 1;
        this.getConfig().set("deaths." + uuidStr + ".count", currentDeaths);
        this.getConfig().set("deaths." + uuidStr + ".name", victim.getName());
        this.saveConfig();
        this.updatePlayerScore(victim.getName(), currentDeaths);
    }

    private void startResetCountdownSequence(Player victim) {
        String victimName = victim == null ? "" : victim.getName();
        messages().broadcast(manualReset ? "chat.manual-reset" : "chat.death",
                java.util.Map.of("victim", victimName, "seconds", countdownSeconds, "run", currentCycle));
        new BukkitRunnable() {
            int remaining = countdownSeconds;

            public void run() {
                if (remaining > 0) {
                    NamedTextColor color = remaining == 1 ? NamedTextColor.RED
                            : remaining == 2 ? NamedTextColor.GOLD : NamedTextColor.YELLOW;
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        String title = manualReset ? "countdown-manual"
                                : player.getUniqueId().equals(victim.getUniqueId()) ? "countdown-victim" : "countdown-teammate";
                        messages().showTitle(player, title, java.util.Map.of("victim", victimName,
                                "seconds", remaining, "run", currentCycle, "countdown_color", color));
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f,
                                0.8f + 0.3f * (countdownSeconds - remaining));
                    }
                    --remaining;
                } else {
                    cancel();
                    executeHotWorldMigration();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void executeHotWorldMigration() {
        migrating = true;
        if (standby == null) prepareStandbyWorld();
        if (standby == null) {
            Bukkit.getScheduler().runTaskLater(this, this::executeHotWorldMigration, 100L);
            return;
        }
        World targetWorld = standby.overworld();
        Location newSpawn = targetWorld.getSpawnLocation().clone().add(0.5, 1.0, 0.5);
        boolean allMoved = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (migratedPlayers.contains(player.getUniqueId()) && player.getWorld().equals(targetWorld)) continue;
            try {
                if (player.isDead()) player.spigot().respawn();
                if (player.isDead()) {
                    allMoved = false;
                    continue;
                }
                player.closeInventory();
                if (!player.teleport(newSpawn)) {
                    allMoved = false;
                    continue;
                }
                wipePlayerProfileCompletely(player, targetWorld, newSpawn);
                migratedPlayers.add(player.getUniqueId());
            } catch (Exception error) {
                allMoved = false;
                getLogger().log(Level.WARNING, "Could not move " + player.getName() + "; retrying before retiring old worlds.", error);
            }
        }
        if (!allMoved) {
            Bukkit.getScheduler().runTaskLater(this, this::executeHotWorldMigration, 20L);
            return;
        }
        String oldOverworld = activeWorldName;
        String oldNether = activeNetherName;
        String oldEnd = activeEndName;
        int nextCycle = !manualReset && currentCycle < Integer.MAX_VALUE ? currentCycle + 1 : currentCycle;
        World newWorld = standby.overworld();
        newWorld.setFullTime(0L);
        newWorld.setStorm(false);
        newWorld.setThundering(false);
        String previousConfig = getConfig().saveToString();
        getConfig().set("state.current-cycle", nextCycle);
        getConfig().set("state.active-world", standby.name());
        getConfig().set("state.active-nether", standby.name() + "_nether");
        getConfig().set("state.active-end", standby.name() + "_the_end");
        getConfig().set("state.standby-world", null);
        getConfig().set("state.dragon-defeated-world", null);
        getConfig().set("state.completed-world", null);
        getConfig().set("state.completion-millis", null);
        getConfig().set("state.run-team", null);
        getConfig().set("state.run-elapsed-millis", 0L);
        for (Player player : Bukkit.getOnlinePlayers()) {
            getConfig().set("players." + player.getUniqueId() + ".run-world", standby.name());
            trackParticipant(player);
        }
        try {
            saveConfig();
        } catch (RuntimeException error) {
            try {
                getConfig().loadFromString(previousConfig);
            } catch (InvalidConfigurationException impossible) {
                throw new IllegalStateException("Cannot restore serialized configuration", impossible);
            }
            getLogger().log(Level.SEVERE, "Cannot commit the new run. All worlds retained; retrying in 5 seconds.", error);
            Bukkit.getScheduler().runTaskLater(this, this::executeHotWorldMigration, 100L);
            return;
        }
        // Publish runtime identity and retire old worlds only after durable configuration succeeds.
        currentCycle = nextCycle;
        activeWorldName = standby.name();
        activeNetherName = activeWorldName + "_nether";
        activeEndName = activeWorldName + "_the_end";
        standby = null;
        standbyWorldName = null;
        WipeTogetherTimerIntegrated.resetForNewWorld(this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            messages().showTitle(player, "welcome", java.util.Map.of("run", currentCycle));
            player.playSound(newSpawn, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }
        updateScoreboardHeader();
        migrating = false;
        migratedPlayers.clear();
        disposeOldWorldSafely(oldOverworld);
        disposeOldWorldSafely(oldNether);
        disposeOldWorldSafely(oldEnd);
        isResetting.set(false);
        manualReset = false;
        getLogger().info("Started run " + currentCycle + ": " + activeWorldName + " (seed " + newWorld.getSeed() + ")");
        Bukkit.getScheduler().runTaskLater(this, this::checkAndPrepareStandbyWorld, 20L * prepCooldownSeconds);
    }

    private void wipePlayerProfileCompletely(Player player, World newWorld, Location newSpawn) {
        resetAdvancements(player);
        player.setGameMode(GameMode.SURVIVAL);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setFallDistance(0.0f);
        // Closing first returns cursor/crafting items before the inventory is wiped.
        player.closeInventory();
        player.setItemOnCursor(null);
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.getEnderChest().clear();
        player.setExp(0.0f);
        player.setLevel(0);
        player.setTotalExperience(0);
        for (PotionEffect effect : player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        AttributeInstance maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxHealthAttr == null ? 20.0 : maxHealthAttr.getValue();
        player.setHealth(maxHealth);
        player.setFoodLevel(20);
        player.setSaturation(5.0f);
        player.setExhaustion(0.0f);
        player.setFireTicks(0);
        player.setRemainingAir(player.getMaximumAir());
        player.setFreezeTicks(0);
        player.setNoDamageTicks(60);
        player.setAbsorptionAmount(0);
        player.setRespawnLocation(newSpawn, true);
    }

    void disposeOldWorldSafely(String worldName) {
        if (worldName == null || worldName.equals(activeWorldName) || worldName.equals(activeNetherName)
                || worldName.equals(activeEndName) || worldName.equals(standbyWorldName)) return;
        // Only delete worlds recorded as ours. Never delete the default world or unrelated plugin worlds.
        if (!RunFiles.isOwnedName(worldName, standbyPrefix, worldPrefix)
                || worldName.equals(serverDefaultName) || worldName.equals(serverDefaultName + "_nether")
                || worldName.equals(serverDefaultName + "_the_end")) return;
        try {
            World old = Bukkit.getWorld(worldName);
            Path folder = old == null || old.getWorldFolder() == null ? null : old.getWorldFolder().toPath();
            if (old != null) {
                if (!old.getPlayers().isEmpty() || !Bukkit.unloadWorld(old, false)) {
                    getLogger().warning("Retained world because unload was refused: " + worldName);
                    return;
                }
            }
            // Retire first so deletion cannot race creation or target a world loaded later.
            Path retired = worldStorage().retire(worldName, folder);
            if (retired != null) {
                Path container = retired.getParent();
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    try {
                        RunFiles.deleteRetired(container, retired);
                    } catch (IOException error) {
                        getLogger().log(Level.WARNING, "Retired-world cleanup failed: " + retired, error);
                    }
                });
            }
        } catch (Exception error) {
            getLogger().log(Level.WARNING, "Retained old world after cleanup failure: " + worldName, error);
        }
    }

    private void setupScoreboard() {
        this.scoreboardManager = Bukkit.getScoreboardManager();
        if (this.scoreboardManager == null) {
            return;
        }
        this.sharedScoreboard = this.scoreboardManager.getNewScoreboard();
        this.deathsObjective = this.sharedScoreboard.registerNewObjective("hardcore_deaths", Criteria.DUMMY,
                messages().component("scoreboard.title", java.util.Map.of("run", currentCycle)));
        this.deathsObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
        this.loadScoresFromConfig();
    }

    private void loadScoresFromConfig() {
        if (this.deathsObjective == null) {
            return;
        }
        ConfigurationSection deathsSection = this.getConfig().getConfigurationSection("deaths");
        if (deathsSection != null) {
            for (String uuidStr : deathsSection.getKeys(false)) {
                String name = deathsSection.getString(uuidStr + ".name", "Desconocido");
                int count = deathsSection.getInt(uuidStr + ".count", 0);
                Score score = this.deathsObjective.getScore("\u00a7e" + name);
                score.setScore(count);
            }
        }
    }

    private void updatePlayerScore(String playerName, int deaths) {
        if (this.deathsObjective != null) {
            Score score = this.deathsObjective.getScore("\u00a7e" + playerName);
            score.setScore(deaths);
        }
    }

    private void updateScoreboardHeader() {
        if (this.deathsObjective != null) {
            this.deathsObjective.displayName(messages().component("scoreboard.title", java.util.Map.of("run", currentCycle)));
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        routeJoiningPlayer(player);
        if (this.sharedScoreboard != null) {
            player.setScoreboard(this.sharedScoreboard);
        }
        String uuidStr = player.getUniqueId().toString();
        if (!this.getConfig().contains("deaths." + uuidStr)) {
            this.getConfig().set("deaths." + uuidStr + ".count", 0);
            this.getConfig().set("deaths." + uuidStr + ".name", player.getName());
            this.saveConfig();
            this.updatePlayerScore(player.getName(), 0);
        }
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("resetdeaths")) {
            return AdminCommands.execute(this, sender, command.getName(), args);
        }
        if (!hasAdminPermission(sender)) {
            messages().send(sender, "commands.no-permission", java.util.Map.of());
            return true;
        }
        this.getConfig().set("deaths", null);
        this.saveConfig();
        if (this.deathsObjective != null) {
            this.deathsObjective.unregister();
            this.deathsObjective = this.sharedScoreboard.registerNewObjective("hardcore_deaths", Criteria.DUMMY,
                    messages().component("scoreboard.title", java.util.Map.of("run", currentCycle)));
            this.deathsObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                String uuidStr = onlinePlayer.getUniqueId().toString();
                this.getConfig().set("deaths." + uuidStr + ".count", 0);
                this.getConfig().set("deaths." + uuidStr + ".name", onlinePlayer.getName());
                this.updatePlayerScore(onlinePlayer.getName(), 0);
            }
            this.saveConfig();
        }
        updateScoreboardHeader();
        messages().broadcast("commands.deaths-reset-broadcast", java.util.Map.of());
        messages().send(sender, "commands.deaths-reset-success", java.util.Map.of());
        return true;
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return AdminCommands.complete(this, sender, command.getName(), args);
    }

    static boolean hasAdminPermission(CommandSender sender) {
        return sender.hasPermission("wipetogether.admin") || sender.hasPermission("hardcorereset.admin");
    }

    private boolean isActiveWorld(World world) {
        String name = world.getName();
        return name.equals(activeWorldName) || name.equals(activeNetherName) || name.equals(activeEndName);
    }

    private void routeJoiningPlayer(Player player) {
        String key = "players." + player.getUniqueId() + ".run-world";
        String lastRun = getConfig().getString(key);
        World active = Bukkit.getWorld(activeWorldName);
        boolean stale = lastRun != null ? !lastRun.equals(activeWorldName)
                : !isActiveWorld(player.getWorld());
        if (stale || !isActiveWorld(player.getWorld())) {
            Location spawn = active.getSpawnLocation().clone().add(0.5, 1.0, 0.5);
            if (player.isDead()) player.spigot().respawn();
            if (player.isDead() || !player.teleport(spawn)) {
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (player.isOnline()) routeJoiningPlayer(player);
                }, 20L);
                return;
            }
            if (stale) wipePlayerProfileCompletely(player, active, spawn);
        }
        getConfig().set(key, activeWorldName);
        trackParticipant(player);
        saveConfig();
    }

    private void resetAdvancements(Player player) {
        var advancements = Bukkit.advancementIterator();
        while (advancements.hasNext()) {
            AdvancementProgress progress = player.getAdvancementProgress(advancements.next());
            for (String criterion : new ArrayList<>(progress.getAwardedCriteria())) progress.revokeCriteria(criterion);
        }
        player.undiscoverRecipes(new ArrayList<>(player.getDiscoveredRecipes()));
    }

    void trackParticipant(Player player) {
        if (player.getGameMode() != GameMode.SPECTATOR)
            getConfig().set("state.run-team." + player.getUniqueId(), player.getName());
    }

    String activeWorldName() {
        return activeWorldName;
    }

    String activeEndName() {
        return activeEndName;
    }

    int currentCycle() {
        return currentCycle;
    }

    boolean resetInProgress() {
        return isResetting.get();
    }

    void setCurrentCycle(int value) {
        currentCycle = value;
        getConfig().set("state.current-cycle", value);
        updateScoreboardHeader();
        saveConfig();
    }

    boolean beginManualReset() {
        if (!isResetting.compareAndSet(false, true)) return false;
        manualReset = true;
        startResetCountdownSequence(null);
        return true;
    }

    void setDeathCount(UUID id, String name, int count) {
        String oldName = getConfig().getString("deaths." + id + ".name");
        if (oldName != null && !oldName.equals(name) && sharedScoreboard != null)
            sharedScoreboard.resetScores("\u00a7e" + oldName);
        getConfig().set("deaths." + id + ".name", name);
        getConfig().set("deaths." + id + ".count", count);
        updatePlayerScore(name, count);
        saveConfig();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        World target = migrating && standby != null ? standby.overworld() : Bukkit.getWorld(activeWorldName);
        if (isResetting.get() || !isActiveWorld(event.getRespawnLocation().getWorld())) {
            event.setRespawnLocation(target.getSpawnLocation().clone().add(0.5, 1, 0.5));
        }
    }

    private Location portalTarget(Location from, PlayerTeleportEvent.TeleportCause cause) {
        if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            boolean leavingNether = from.getWorld().getEnvironment() == World.Environment.NETHER;
            World to = Bukkit.getWorld(leavingNether ? activeWorldName : activeNetherName);
            double scale = leavingNether ? 8.0 : 0.125;
            double edge = Math.min(29999800, to.getWorldBorder().getSize() / 2 - 16);
            double x = Math.clamp(from.getX() * scale, to.getWorldBorder().getCenter().getX() - edge, to.getWorldBorder().getCenter().getX() + edge);
            double z = Math.clamp(from.getZ() * scale, to.getWorldBorder().getCenter().getZ() - edge, to.getWorldBorder().getCenter().getZ() + edge);
            return new Location(to, x, Math.min(from.getY(), to.getMaxHeight() - 2), z, from.getYaw(), from.getPitch());
        }
        if (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            if (from.getWorld().getEnvironment() == World.Environment.THE_END)
                return Bukkit.getWorld(activeWorldName).getSpawnLocation();
            return new Location(Bukkit.getWorld(activeEndName), 100.5, 50, 0.5, 90, 0);
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (!isActiveWorld(event.getFrom().getWorld())) return;
        if (isResetting.get()) {
            event.setCancelled(true);
            return;
        }
        Location target = portalTarget(event.getFrom(), event.getCause());
        if (target != null) {
            event.setTo(target);
            if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
                event.setSearchRadius(target.getWorld().getEnvironment() == World.Environment.NETHER ? 16 : 128);
                event.setCanCreatePortal(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (!isActiveWorld(event.getFrom().getWorld())) return;
        if (isResetting.get()) {
            event.setCancelled(true);
            return;
        }
        Location target = portalTarget(event.getFrom(), event.getPortalType() == org.bukkit.PortalType.NETHER
                ? PlayerTeleportEvent.TeleportCause.NETHER_PORTAL : PlayerTeleportEvent.TeleportCause.END_PORTAL);
        if (target != null) event.setTo(target);
    }
}

