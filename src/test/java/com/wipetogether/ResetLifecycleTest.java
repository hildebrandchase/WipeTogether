package com.wipetogether;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Inventory;
import org.mockito.ArgumentCaptor;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Firework;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;

class ResetLifecycleTest {
    static RegistryTestSupport registry;

    @BeforeAll
    static void registrySetup() {
        registry = new RegistryTestSupport();
    }

    @AfterAll
    static void registryShutdown() {
        registry.close();
    }

    @TempDir
    Path root;
    MockedStatic<Bukkit> bukkit;
    WipeTogether plugin;
    YamlConfiguration config;
    Map<String, World> worlds;
    List<WorldCreator> creations;
    BukkitScheduler scheduler;
    World original;

    @BeforeEach
    void setup() {
        bukkit = mockStatic(Bukkit.class);
        worlds = new HashMap<>();
        creations = new ArrayList<>();
        config = new YamlConfiguration();
        plugin = mock(WipeTogether.class, CALLS_REAL_METHODS);
        doReturn(config).when(plugin).getConfig();
        doNothing().when(plugin).saveConfig();
        doReturn(Logger.getLogger("WipeTogetherTest")).when(plugin).getLogger();
        UnsafeValues unsafe = mock(UnsafeValues.class);
        when(unsafe.getMainLevelName()).thenReturn("world");
        bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
        bukkit.when(Bukkit::getWorldContainer).thenReturn(root.toFile());
        bukkit.when(() -> Bukkit.getWorld(anyString())).thenAnswer(i -> worlds.get(i.getArgument(0)));
        bukkit.when(() -> Bukkit.createWorld(any(WorldCreator.class))).thenAnswer(i -> {
            WorldCreator creator = i.getArgument(0);
            creations.add(creator);
            World world = world(creator.name(), creator.seed(), creator.environment());
            Files.createDirectories(root.resolve(creator.name()));
            worlds.put(creator.name(), world);
            return world;
        });
        scheduler = mock(BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        original = world("world", 12345L, World.Environment.NORMAL);
        worlds.put("world", original);
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of(original));
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        bukkit.when(Bukkit::advancementIterator).thenAnswer(i -> Collections.emptyIterator());
        set("activeWorldName", "world");
        set("activeNetherName", "world_nether");
        set("activeEndName", "world_the_end");
        set("serverDefaultName", "world");
        set("standbyPrefix", "hardcore_standby_");
        set("worldPrefix", "hardcore_world_");
        set("currentCycle", 1);
        set("prepCooldownSeconds", 15);
        set("isResetting", new AtomicBoolean(true));
        set("isPreparingStandby", new AtomicBoolean(false));
        set("migratedPlayers", new HashSet<UUID>());
    }

    @AfterEach
    void cleanup() {
        WipeTogetherTimerIntegrated.shutdown();
        bukkit.close();
    }

    World world(String name, long seed, World.Environment environment) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getSeed()).thenReturn(seed);
        when(world.getEnvironment()).thenReturn(environment);
        when(world.getDifficulty()).thenReturn(Difficulty.HARD);
        when(world.getSpawnLocation()).thenAnswer(i -> new Location(world, 0, 70, 0));
        when(world.getPlayers()).thenReturn(List.of());
        when(world.getMaxHeight()).thenReturn(320);
        WorldBorder border = mock(WorldBorder.class);
        when(border.getSize()).thenReturn(59999968d);
        when(border.getCenter()).thenReturn(new Location(world, 0, 0, 0));
        when(world.getWorldBorder()).thenReturn(border);
        // Paper 26.3 rejects time changes in dimensions without a world clock.
        if (environment != World.Environment.NORMAL) {
            doThrow(new IllegalArgumentException("Cannot set time in world without world clock"))
                    .when(world).setFullTime(anyLong());
        }
        return world;
    }

    @Test
    void startupPreparesAllDimensionsWithoutSettingClocklessDimensionTime() {
        call("checkAndPrepareStandbyWorld");
        FreshRun run = (FreshRun) get("standby");
        assertNotNull(run, "Clockless Nether and End must not abort standby preparation");
        assertEquals(run.name(), config.getString("state.standby-world"));
        verify(run.overworld()).setFullTime(0L);
        verify(run.nether(), never()).setFullTime(anyLong());
        verify(run.end(), never()).setFullTime(anyLong());
        assertEquals(3, creations.size());
    }

    @Test
    void repeatedResetsAlwaysReserveNewNamesAndSeedsForAllDimensions() {
        Set<Long> seeds = new HashSet<>();
        seeds.add(original.getSeed());
        Set<String> names = new HashSet<>();
        World previous = original;
        // Includes rapid resets in the same cycle, and a simulated plugin restart.
        for (int i = 0; i < 8; i++) {
            FreshRun run = FreshRun.create(plugin, "hardcore_standby_", 1, previous);
            assertTrue(seeds.add(run.seed()));
            assertTrue(names.add(run.name()));
            assertEquals(run.seed(), run.overworld().getSeed());
            assertEquals(run.seed(), run.nether().getSeed());
            assertEquals(run.seed(), run.end().getSeed());
            assertEquals(World.Environment.NORMAL, run.overworld().getEnvironment());
            assertEquals(World.Environment.NETHER, run.nether().getEnvironment());
            assertEquals(World.Environment.THE_END, run.end().getEnvironment());
            previous = run.overworld();
        }
        assertEquals(24, creations.size());
        assertEquals(9, config.getStringList("state.used-seeds").size());
    }

    @Test
    void oldNumericStandbyCannotBeReused() throws Exception {
        Files.createDirectory(root.resolve("hardcore_standby_1"));
        worlds.put("hardcore_standby_1", world("hardcore_standby_1", 12345, World.Environment.NORMAL));
        FreshRun run = FreshRun.create(plugin, "hardcore_standby_", 1, original);
        assertNotEquals("hardcore_standby_1", run.name());
        assertNotEquals(12345L, run.seed());
    }

    @Test
    void failedCreationDoesNotPublishStandbyOrChangeActiveRun() {
        bukkit.when(() -> Bukkit.createWorld(any(WorldCreator.class))).thenReturn(null);
        call("prepareStandbyWorld");
        assertNull(get("standby"));
        assertEquals("world", get("activeWorldName"));
        assertEquals(1, get("currentCycle"));
        assertFalse(((AtomicBoolean) get("isPreparingStandby")).get());
    }

    @Test
    void seedMismatchIsRejectedRatherThanAcceptingExistingTerrain() {
        bukkit.when(() -> Bukkit.createWorld(any(WorldCreator.class))).thenAnswer(i -> {
            WorldCreator creator = i.getArgument(0);
            return world(creator.name(), 12345, creator.environment());
        });
        call("prepareStandbyWorld");
        assertNull(get("standby"));
        assertEquals("world", get("activeWorldName"));
    }

    @Test
    void restartRestoresSavedRunInsteadOfFallingBackToDefault() throws Exception {
        String saved = "hardcore_standby_5_" + "b".repeat(32);
        set("activeWorldName", saved);
        Files.createDirectory(root.resolve(saved));
        Files.writeString(root.resolve(saved).resolve("level.dat"), "saved level metadata");
        call("initializeWorldState");
        assertEquals(saved, get("activeWorldName"));
        assertEquals(saved, creations.getFirst().name());
    }

    @Test
    void missingSavedRunStartsFreshRandomWorldAtSameRunNumber() {
        set("currentCycle", 6);
        set("activeWorldName", "missing_saved_run");
        call("initializeWorldState");
        assertTrue(plugin.activeWorldName().startsWith("hardcore_standby_6_"));
        assertEquals(6, plugin.currentCycle());
        assertEquals(3, creations.size());
        assertNotEquals(original.getSeed(), worlds.get(plugin.activeWorldName()).getSeed());
        assertEquals(plugin.activeWorldName(), config.getString("state.active-world"));
        assertEquals(plugin.activeWorldName() + "_nether", config.getString("state.active-nether"));
        assertEquals(plugin.activeWorldName() + "_the_end", config.getString("state.active-end"));
    }

    @Test
    void modernDimensionStorageRestoresSavedWorldWithoutLevelDat() throws Exception {
        String name = "hardcore_standby_6_" + "c".repeat(32);
        Path main = Files.createDirectories(root.resolve("world"));
        Path target = Files.createDirectories(main.resolve("dimensions/minecraft/" + name + "/data/minecraft"));
        Files.writeString(target.resolve("world_gen_settings.dat"), "saved generation settings");
        set("storage", new WorldStorage(root, main, "world"));
        set("activeWorldName", name);
        call("initializeWorldState");
        assertEquals(name, plugin.activeWorldName());
        assertEquals(name, creations.getFirst().name());
        assertFalse(Files.exists(root.resolve(name).resolve("level.dat")));
    }

    @Test
    void failedPlayerTeleportDefersCommitAndOldWorldCleanup() {
        FreshRun run = FreshRun.create(plugin, "hardcore_standby_", 1, original);
        set("standby", run);
        set("standbyWorldName", run.name());
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(original);
        when(player.teleport(any(Location.class))).thenReturn(false);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
        call("executeHotWorldMigration");
        assertEquals("world", get("activeWorldName"));
        assertEquals(1, get("currentCycle"));
        bukkit.verify(() -> Bukkit.unloadWorld(any(World.class), anyBoolean()), never());
        verify(scheduler).runTaskLater(eq(plugin), any(Runnable.class), eq(20L));
    }

    @Test
    void successfulMigrationCommitsSeedAndAllThreeWorlds() {
        FreshRun run = FreshRun.create(plugin, "hardcore_standby_", 1, original);
        set("standby", run);
        set("standbyWorldName", run.name());
        call("executeHotWorldMigration");
        assertEquals(run.name(), config.getString("state.active-world"));
        assertEquals(run.name() + "_nether", config.getString("state.active-nether"));
        assertEquals(run.name() + "_the_end", config.getString("state.active-end"));
        assertEquals(2, config.getInt("state.current-cycle"));
        assertNull(get("standby"));
        assertFalse(((AtomicBoolean) get("isResetting")).get());
        verify(run.overworld(), atLeastOnce()).setFullTime(0);
        // Default server worlds are retained rather than deleted.
        bukkit.verify(() -> Bukkit.unloadWorld(original, false), never());
    }

    Player player(World world) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Alex");
        when(player.getWorld()).thenReturn(world);
        when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
        when(player.getEnderChest()).thenReturn(mock(Inventory.class));
        when(player.getActivePotionEffects()).thenReturn(List.of());
        when(player.getLocation()).thenReturn(new Location(world, 0, 70, 0));
        when(player.teleport(any(Location.class))).thenReturn(true);
        return player;
    }

    @Test
    void successfulPlayerMoveWipesProfileAndRecordsRunForNextLogin() {
        FreshRun run = FreshRun.create(plugin, "hardcore_standby_", 1, original);
        set("standby", run);
        set("standbyWorldName", run.name());
        Player player = player(original);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
        call("executeHotWorldMigration");
        verify(player).teleport(argThat((Location l) -> l.getWorld().equals(run.overworld())));
        verify(player.getInventory()).clear();
        verify(player.getEnderChest()).clear();
        verify(player).setLevel(0);
        verify(player).setFoodLevel(20);
        verify(player).setFireTicks(0);
        assertEquals(run.name(), config.getString("players." + player.getUniqueId() + ".run-world"));
        assertEquals(2, get("currentCycle"));
        ArgumentCaptor<Title> titles = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(titles.capture());
        assertEquals(Component.text("NEW ATTEMPT!", NamedTextColor.GREEN, TextDecoration.BOLD), titles.getValue().title());
        assertEquals(Component.text("World generated. Don't die this time.", NamedTextColor.GRAY), titles.getValue().subtitle());
    }

    @Test
    void returningPlayerFromOldRunLosesOldInventory() {
        World current = world("current_run", 999, World.Environment.NORMAL);
        worlds.put("current_run", current);
        set("activeWorldName", "current_run");
        Player player = player(original);
        config.set("players." + player.getUniqueId() + ".run-world", "world");
        plugin.onPlayerJoin(new org.bukkit.event.player.PlayerJoinEvent(player, (Component) null));
        verify(player).teleport(argThat((Location l) -> l.getWorld().equals(current)));
        verify(player.getInventory()).clear();
        assertEquals("current_run", config.getString("players." + player.getUniqueId() + ".run-world"));
    }

    @Test
    void restartWithinSameRunKeepsInventory() {
        Player player = player(original);
        config.set("players." + player.getUniqueId() + ".run-world", "world");
        plugin.onPlayerJoin(new org.bukkit.event.player.PlayerJoinEvent(player, (Component) null));
        verify(player.getInventory(), never()).clear();
        verify(player, never()).teleport(any(Location.class));
    }

    @Test
    void countdownKeepsOriginalVisualsSoundPitchesAndPerPlayerTitles() throws Exception {
        set("countdownSeconds", 3);
        Player victim = player(original);
        Player teammate = player(original);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(victim, teammate));
        Method countdown = WipeTogether.class.getDeclaredMethod("startResetCountdownSequence", Player.class);
        countdown.setAccessible(true);
        countdown.invoke(plugin, victim);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskTimer(eq(plugin), task.capture(), eq(0L), eq(20L));
        for (int i = 0; i < 3; i++) task.getValue().run();
        ArgumentCaptor<Title> titles = ArgumentCaptor.forClass(Title.class);
        verify(victim, times(3)).showTitle(titles.capture());
        for (int i = 0; i < 3; i++) {
            Title title = titles.getAllValues().get(i);
            assertEquals(Component.text("YOU DIED!", NamedTextColor.DARK_RED, TextDecoration.BOLD), title.title());
            assertEquals(java.time.Duration.ofMillis(100), title.times().fadeIn());
            assertEquals(java.time.Duration.ofMillis(800), title.times().stay());
            assertEquals(java.time.Duration.ofMillis(100), title.times().fadeOut());
        }
        verify(victim).playSound(any(Location.class), eq(Sound.BLOCK_NOTE_BLOCK_PLING), eq(1f), eq(0.8f));
        verify(victim).playSound(any(Location.class), eq(Sound.BLOCK_NOTE_BLOCK_PLING), eq(1f), eq(1.1f));
        verify(victim).playSound(any(Location.class), eq(Sound.BLOCK_NOTE_BLOCK_PLING), eq(1f), eq(1.4000001f));
        ArgumentCaptor<Title> teammateTitles = ArgumentCaptor.forClass(Title.class);
        verify(teammate, times(3)).showTitle(teammateTitles.capture());
        for (int i = 0; i < 3; i++) {
            assertEquals("Alex DIED", plain(teammateTitles.getAllValues().get(i).title()));
            assertEquals("New world in " + (3 - i) + "...", plain(teammateTitles.getAllValues().get(i).subtitle()));
        }
        // Configured titles must not be overwritten by a hardcoded next-tick overlay.
        verify(scheduler, never()).runTaskLater(eq(plugin), any(Runnable.class), eq(1L));
    }

    @Test
    void portalRoutingUsesCurrentRunAndVanillaCoordinateScaling() {
        World nether = world("run_nether", 1, World.Environment.NETHER);
        worlds.put("run_nether", nether);
        set("activeNetherName", "run_nether");
        set("isResetting", new AtomicBoolean(false));
        PlayerPortalEvent event = new PlayerPortalEvent(mock(Player.class), new Location(original, 800, 70, -160),
                new Location(original, 0, 70, 0), TeleportCause.NETHER_PORTAL);
        plugin.onPortal(event);
        assertEquals(nether, event.getTo().getWorld());
        assertEquals(100, event.getTo().getX());
        assertEquals(-20, event.getTo().getZ());
        assertEquals(16, event.getSearchRadius());
    }

    CommandSender admin() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("hardcorereset.admin")).thenReturn(true);
        return sender;
    }

    void idle() {
        ((AtomicBoolean) get("isResetting")).set(false);
    }

    @Test
    void allNewCommandsEnforcePermission() {
        CommandSender stranger = mock(CommandSender.class);
        for (String command : List.of("resetruns", "setrun", "setdeaths", "resetworld"))
            assertTrue(AdminCommands.execute(plugin, stranger, command, new String[]{"Alex", "3"}));
        assertEquals(1, get("currentCycle"));
        assertFalse(config.contains("deaths"));
        assertFalse((boolean) get("manualReset"));
    }

    @Test
    void setrunAndResetrunsOnlyChangeCounterAndScoreboard() {
        idle();
        Objective objective = mock(Objective.class);
        set("deathsObjective", objective);
        AdminCommands.execute(plugin, admin(), "setrun", new String[]{"12"});
        assertEquals(12, get("currentCycle"));
        assertEquals(12, config.getInt("state.current-cycle"));
        verify(objective).displayName(argThat((Component text) -> plain(text).equals("DEATHS - RUN #12")));
        AdminCommands.execute(plugin, admin(), "resetruns", new String[]{});
        assertEquals(1, get("currentCycle"));
        assertEquals("world", get("activeWorldName"));
        assertFalse(config.contains("state.run-elapsed-millis"));
        verify(scheduler, never()).runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong());
    }

    @Test
    void invalidCounterArgumentsLeaveStateUntouched() {
        idle();
        for (String value : List.of("0", "-1", "2147483648", "abc"))
            AdminCommands.execute(plugin, admin(), "setrun", new String[]{value});
        AdminCommands.execute(plugin, admin(), "setrun", new String[]{});
        AdminCommands.execute(plugin, admin(), "resetruns", new String[]{"extra"});
        assertEquals(1, get("currentCycle"));
        assertFalse(config.contains("state.current-cycle"));
    }

    @Test
    void setdeathsUpdatesOnlinePlayerConfigAndScoreboard() {
        Player player = player(original);
        bukkit.when(() -> Bukkit.getPlayerExact("Alex")).thenReturn(player);
        Objective objective = mock(Objective.class);
        Score score = mock(Score.class);
        when(objective.getScore("\u00a7eAlex")).thenReturn(score);
        set("deathsObjective", objective);
        AdminCommands.execute(plugin, admin(), "setdeaths", new String[]{"Alex", "7"});
        assertEquals(7, config.getInt("deaths." + player.getUniqueId() + ".count"));
        verify(score).setScore(7);
        AdminCommands.execute(plugin, admin(), "setdeaths", new String[]{"Alex", "0"});
        verify(score).setScore(0);
    }

    @Test
    void setdeathsSupportsKnownOfflineNamesAndRejectsInvalidValues() {
        UUID id = UUID.randomUUID();
        config.set("deaths." + id + ".name", "OfflineAlex");
        config.set("deaths." + id + ".count", 4);
        AdminCommands.execute(plugin, admin(), "setdeaths", new String[]{"offlinealex", "9"});
        assertEquals(9, config.getInt("deaths." + id + ".count"));
        for (String value : List.of("-1", "bad", "2147483648"))
            AdminCommands.execute(plugin, admin(), "setdeaths", new String[]{"OfflineAlex", value});
        AdminCommands.execute(plugin, admin(), "setdeaths", new String[]{"Unknown", "3"});
        assertEquals(9, config.getInt("deaths." + id + ".count"));
        assertEquals(Set.of(id.toString()), config.getConfigurationSection("deaths").getKeys(false));
    }

    @Test
    void resetworldUsesCountdownWithoutKillingPlayerOrIncrementingCounters() {
        idle();
        set("countdownSeconds", 3);
        set("currentCycle", 9);
        UUID id = UUID.randomUUID();
        config.set("deaths." + id + ".count", 8);
        AdminCommands.execute(plugin, admin(), "resetworld", new String[]{});
        assertTrue((boolean) get("manualReset"));
        assertTrue(plugin.resetInProgress());
        ArgumentCaptor<Runnable> countdown = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskTimer(eq(plugin), countdown.capture(), eq(0L), eq(20L));
        countdown.getValue().run(); // Null victim is valid for a console reset.
        call("executeHotWorldMigration");
        assertEquals(9, get("currentCycle"));
        assertEquals(9, config.getInt("state.current-cycle"));
        assertEquals(8, config.getInt("deaths." + id + ".count"));
        assertNotEquals("world", get("activeWorldName"));
        assertFalse((boolean) get("manualReset"));
        assertFalse(plugin.resetInProgress());
        assertEquals(0L, config.getLong("state.run-elapsed-millis"));
    }

    @Test
    void conflictingAdminResetsAreRejected() {
        assertTrue(plugin.resetInProgress());
        AdminCommands.execute(plugin, admin(), "setrun", new String[]{"50"});
        AdminCommands.execute(plugin, admin(), "resetruns", new String[]{});
        AdminCommands.execute(plugin, admin(), "resetworld", new String[]{});
        assertEquals(1, get("currentCycle"));
        assertFalse((boolean) get("manualReset"));
        verify(scheduler, never()).runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong());
    }

    @Test
    void freshWorldRevokesAllAwardedAdvancementCriteriaAndDiscoveredRecipes() {
        FreshRun run = FreshRun.create(plugin, "hardcore_standby_", 1, original);
        set("standby", run);
        set("standbyWorldName", run.name());
        Player player = player(original);
        Advancement advancement = mock(Advancement.class);
        AdvancementProgress progress = mock(AdvancementProgress.class);
        when(player.getAdvancementProgress(advancement)).thenReturn(progress);
        when(progress.getAwardedCriteria()).thenReturn(List.of("dragon", "inventory_changed"));
        NamespacedKey recipe = NamespacedKey.minecraft("crafting_table");
        when(player.getDiscoveredRecipes()).thenReturn(Set.of(recipe));
        bukkit.when(Bukkit::advancementIterator).thenAnswer(i -> List.of(advancement).iterator());
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
        call("executeHotWorldMigration");
        verify(progress).revokeCriteria("dragon");
        verify(progress).revokeCriteria("inventory_changed");
        verify(player).undiscoverRecipes(List.of(recipe));
    }

    World activeEnd() {
        World end = world("world_the_end", 12345, World.Environment.THE_END);
        worlds.put("world_the_end", end);
        return end;
    }

    @Test
    void onlyActiveDragonDeathMarksRunAsDefeated() {
        idle();
        VictoryEvents victory = new VictoryEvents(plugin);
        EntityDeathEvent death = mock(EntityDeathEvent.class);
        EnderDragon dragon = mock(EnderDragon.class);
        when(death.getEntity()).thenReturn(dragon);
        World unrelated = world("unrelated_end", 1, World.Environment.THE_END);
        when(dragon.getWorld()).thenReturn(unrelated);
        victory.onDragonDeath(death);
        assertFalse(config.contains("state.dragon-defeated-world"));
        World end = activeEnd();
        when(dragon.getWorld()).thenReturn(end);
        victory.onDragonDeath(death);
        assertEquals("world_the_end", config.getString("state.dragon-defeated-world"));
        assertFalse(config.contains("wins")); // Killing dragon alone does not finish the run.
    }

    @Test
    void exitPortalAfterDragonDeathCelebratesAndRecordsVictoryExactlyOnce() {
        idle();
        World end = activeEnd();
        Player player = player(end);
        config.set("state.dragon-defeated-world", "world_the_end");
        config.set("state.run-elapsed-millis", 135000L);
        UUID offline = UUID.randomUUID();
        config.set("state.run-team." + offline, "OfflineTeammate");
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
        VictoryEvents victory = new VictoryEvents(plugin);
        EntityPortalEnterEvent entry = new EntityPortalEnterEvent(player, new Location(end, 0, 70, 0), PortalType.ENDER);
        victory.onExitPortalEnter(entry);
        victory.onExitPortalEnter(entry);
        assertEquals("world", config.getString("state.completed-world"));
        assertEquals(135000L, config.getLong("wins.world.duration-millis"));
        assertEquals(12345L, config.getLong("wins.world.seed"));
        assertEquals("OfflineTeammate", config.getString("wins.world.team." + offline));
        assertEquals("Alex", config.getString("wins.world.team." + player.getUniqueId()));
        assertEquals(1, config.getConfigurationSection("wins").getKeys(false).size());
        verify(player, times(1)).showTitle(any(Title.class));
        bukkit.verify(() -> Bukkit.broadcast(any(Component.class)), times(1));
        verify(scheduler).runTaskLater(eq(plugin), any(Runnable.class), eq(0L));
        verify(scheduler).runTaskLater(eq(plugin), any(Runnable.class), eq(20L));
        verify(scheduler).runTaskLater(eq(plugin), any(Runnable.class), eq(40L));
        assertEquals(1, get("currentCycle"));
    }

    @Test
    void wrongPortalsAndUnbeatenDragonCannotTriggerVictory() {
        idle();
        World end = activeEnd();
        Player player = player(end);
        VictoryEvents victory = new VictoryEvents(plugin);
        victory.onExitPortalEnter(new EntityPortalEnterEvent(player, new Location(end, 0, 70, 0), PortalType.ENDER));
        assertFalse(config.contains("wins"));
        config.set("state.dragon-defeated-world", "world_the_end");
        victory.onExitPortalEnter(new EntityPortalEnterEvent(player, new Location(end, 0, 70, 0), PortalType.END_GATEWAY));
        victory.onExitPortalEnter(new EntityPortalEnterEvent(player, new Location(original, 0, 70, 0), PortalType.ENDER));
        assertFalse(config.contains("wins"));
    }

    @Test
    void resetInProgressCannotBecomeAVictory() {
        World end = activeEnd();
        config.set("state.dragon-defeated-world", "world_the_end");
        new VictoryEvents(plugin).complete(player(end));
        assertFalse(config.contains("wins"));
    }

    @Test
    void endCreditsRespawnCanTriggerVictoryFallback() {
        idle();
        Player player = player(activeEnd());
        config.set("state.dragon-defeated-world", "world_the_end");
        PlayerRespawnEvent event = mock(PlayerRespawnEvent.class);
        when(event.getRespawnReason()).thenReturn(PlayerRespawnEvent.RespawnReason.END_PORTAL);
        when(event.getPlayer()).thenReturn(player);
        new VictoryEvents(plugin).onEndReturn(event);
        assertEquals("world", config.getString("state.completed-world"));
    }

    @Test
    void victoryFireworksHaveDamageCancelledButNormalFireworksDoNot() {
        Firework firework = mock(Firework.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(firework.getPersistentDataContainer()).thenReturn(data);
        EntityDamageByEntityEvent damage = mock(EntityDamageByEntityEvent.class);
        when(damage.getDamager()).thenReturn(firework);
        VictoryEvents victory = new VictoryEvents(plugin);
        victory.onFireworkDamage(damage);
        verify(damage, never()).setCancelled(true);
        when(data.has(VictoryEvents.FIREWORK_TAG, PersistentDataType.BYTE)).thenReturn(true);
        victory.onFireworkDamage(damage);
        verify(damage).setCancelled(true);
    }

    @Test
    void timerFreezesAndCounterEditsDoNotRestartIt() {
        idle();
        config.set("state.run-elapsed-millis", 120000L);
        WipeTogetherTimerIntegrated.bootstrap(plugin);
        long frozen = WipeTogetherTimerIntegrated.freeze(plugin);
        AdminCommands.execute(plugin, admin(), "setrun", new String[]{"25"});
        assertEquals(frozen, WipeTogetherTimerIntegrated.elapsedMillis(plugin));
        AdminCommands.execute(plugin, admin(), "resetruns", new String[]{});
        assertEquals(frozen, WipeTogetherTimerIntegrated.elapsedMillis(plugin));
        assertEquals(1, get("currentCycle"));
    }

    @Test
    void completedTimerSurvivesRestartAndManualResetUnfreezesIt() {
        config.set("state.completed-world", "world");
        config.set("state.completion-millis", 99000L);
        WipeTogetherTimerIntegrated.bootstrap(plugin);
        assertEquals(99000L, WipeTogetherTimerIntegrated.elapsedMillis(plugin));
        set("manualReset", true);
        call("executeHotWorldMigration");
        assertFalse(config.contains("state.completed-world"));
        assertTrue(WipeTogetherTimerIntegrated.elapsedMillis(plugin) < 10000L);
        assertEquals(1, get("currentCycle"));
    }

    @Test
    void completedWorldDoesNotAwardDuplicateWinAfterReloadOrRunEdits() {
        idle();
        config.set("state.completed-world", "world");
        config.set("state.dragon-defeated-world", "world_the_end");
        plugin.setCurrentCycle(40);
        new VictoryEvents(plugin).complete(player(activeEnd()));
        verify(scheduler, never()).runTaskLater(eq(plugin), any(Runnable.class), anyLong());
        bukkit.verify(() -> Bukkit.broadcast(any(Component.class)), never());
    }

    @Test
    void deathsDuringManualCountdownDoNotChangeCounters() {
        idle();
        Player player = player(original);
        config.set("deaths." + player.getUniqueId() + ".count", 3);
        plugin.beginManualReset();
        var death = mock(org.bukkit.event.entity.PlayerDeathEvent.class);
        when(death.getPlayer()).thenReturn(player);
        when(death.getDrops()).thenReturn(new ArrayList<>());
        plugin.onPlayerDeath(death);
        assertEquals(3, config.getInt("deaths." + player.getUniqueId() + ".count"));
        assertEquals(1, get("currentCycle"));
    }

    @Test
    void returningOfflinePlayerAlsoHasAdvancementsReset() {
        World current = world("new_world", 999L, World.Environment.NORMAL);
        worlds.put("new_world", current);
        set("activeWorldName", "new_world");
        Player player = player(original);
        config.set("players." + player.getUniqueId() + ".run-world", "world");
        Advancement advancement = mock(Advancement.class);
        AdvancementProgress progress = mock(AdvancementProgress.class);
        when(progress.getAwardedCriteria()).thenReturn(List.of("done"));
        when(player.getAdvancementProgress(advancement)).thenReturn(progress);
        bukkit.when(Bukkit::advancementIterator).thenAnswer(i -> List.of(advancement).iterator());
        plugin.onPlayerJoin(new org.bukkit.event.player.PlayerJoinEvent(player, (Component) null));
        verify(progress).revokeCriteria("done");
        assertEquals("new_world", config.getString("players." + player.getUniqueId() + ".run-world"));
    }

    @Test
    void victoryRecordIsReadableAfterSavingAndReloadingConfig() throws Exception {
        idle();
        config.set("state.dragon-defeated-world", "world_the_end");
        config.set("state.run-elapsed-millis", 66000L);
        Player player = player(activeEnd());
        new VictoryEvents(plugin).complete(player);
        YamlConfiguration loaded = new YamlConfiguration();
        loaded.loadFromString(config.saveToString());
        assertEquals(66000L, loaded.getLong("wins.world.duration-millis"));
        assertEquals(1, loaded.getInt("wins.world.run"));
        assertEquals("Alex", loaded.getString("wins.world.team." + player.getUniqueId()));
        assertEquals("world", loaded.getString("state.completed-world"));
    }

    @Test
    void newWorldClearsVictoryStatusButRetainsWinHistory() {
        config.set("wins.old_world.run", 3);
        config.set("state.completed-world", "world");
        config.set("state.dragon-defeated-world", "world_the_end");
        call("executeHotWorldMigration");
        assertFalse(config.contains("state.completed-world"));
        assertFalse(config.contains("state.dragon-defeated-world"));
        assertEquals(3, config.getInt("wins.old_world.run"));
        assertEquals(2, get("currentCycle"));
    }

    @Test
    void failedCommitRetainsWorldsAndRuntimeStateThenRetriesExactlyOnce() {
        config.set("state.active-world", "world");
        config.set("state.current-cycle", 1);
        config.set("state.run-elapsed-millis", 6000L);
        config.set("wins.previous.run", 8);
        call("checkAndPrepareStandbyWorld");
        FreshRun candidate = (FreshRun) get("standby");
        clearInvocations(plugin);
        doThrow(new java.io.UncheckedIOException(new java.io.IOException("disk full"))).when(plugin).saveConfig();
        call("executeHotWorldMigration");
        assertEquals("world", plugin.activeWorldName());
        assertEquals(1, plugin.currentCycle());
        assertSame(candidate, get("standby"));
        assertTrue(plugin.resetInProgress());
        assertEquals("world", config.getString("state.active-world"));
        assertEquals(6000L, config.getLong("state.run-elapsed-millis"));
        assertEquals(8, config.getInt("wins.previous.run"));
        verify(plugin, never()).disposeOldWorldSafely(anyString());
        verify(scheduler).runTaskLater(eq(plugin), any(Runnable.class), eq(100L));
        doNothing().when(plugin).saveConfig();
        call("executeHotWorldMigration");
        assertEquals(candidate.name(), plugin.activeWorldName());
        assertEquals(candidate.name(), config.getString("state.active-world"));
        assertEquals(2, plugin.currentCycle());
        assertFalse(plugin.resetInProgress());
        verify(plugin).disposeOldWorldSafely("world");
    }

    @Test
    void malformedStartupDisablesPluginWithoutSavingOrRegisteringGameplay() throws Exception {
        Path data = Files.createDirectory(root.resolve("WipeTogether"));
        Path file = data.resolve("config.yml");
        String broken = "state: [unclosed\n";
        Files.writeString(file, broken);
        doReturn(data.toFile()).when(plugin).getDataFolder();
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        org.bukkit.plugin.PluginManager manager = mock(org.bukkit.plugin.PluginManager.class);
        doReturn(server).when(plugin).getServer();
        when(server.getPluginManager()).thenReturn(manager);
        plugin.onEnable();
        plugin.onDisable();
        verify(manager).disablePlugin(plugin);
        verify(manager, never()).registerEvents(any(), any());
        verify(plugin, never()).saveConfig();
        assertEquals(broken, Files.readString(file));
        assertTrue(creations.isEmpty());
    }

    @Test
    void freshStartupUsesActualConfigurationAndCustomDefaultWorld() throws Exception {
        Path data = root.resolve("WipeTogether");
        when(original.getName()).thenReturn("custom_world");
        worlds.remove("world");
        worlds.put("custom_world", original);
        doReturn(data.toFile()).when(plugin).getDataFolder();
        doReturn(getClass().getResourceAsStream("/config.yml")).when(plugin).getResource("config.yml");
        doCallRealMethod().when(plugin).getConfig();
        doCallRealMethod().when(plugin).saveConfig();
        doReturn(null).when(plugin).getCommand(anyString());
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        org.bukkit.plugin.PluginManager manager = mock(org.bukkit.plugin.PluginManager.class);
        doReturn(server).when(plugin).getServer();
        when(server.getPluginManager()).thenReturn(manager);
        plugin.onEnable();
        assertEquals("custom_world", plugin.activeWorldName());
        assertEquals("wipetogether_standby_", plugin.getConfig().getString("settings.standby-prefix"));
        assertTrue((boolean)get("initialized"));
        verify(manager, never()).disablePlugin(plugin);
        plugin.onDisable();
        var saved = ConfigStore.load(data.resolve("config.yml"), null);
        assertEquals("custom_world", saved.getString("state.active-world"));
        assertEquals(1, saved.getInt("state.current-cycle"));
    }

    @Test
    void newPermissionAndLegacyPermissionBothAuthorizeCommands() {
        idle();
        CommandSender renamed = mock(CommandSender.class);
        when(renamed.hasPermission("wipetogether.admin")).thenReturn(true);
        assertTrue(AdminCommands.execute(plugin, renamed, "setrun", new String[]{"4"}));
        assertEquals(4, plugin.currentCycle());
        assertTrue(AdminCommands.execute(plugin, admin(), "setrun", new String[]{"5"}));
        assertEquals(5, plugin.currentCycle());
    }

    @Test
    void freshWorldsAreHardcoreEvenWhenPreviousWorldWasNot() {
        when(original.isHardcore()).thenReturn(false);
        when(original.getDifficulty()).thenReturn(Difficulty.NORMAL);
        FreshRun run = FreshRun.create(plugin, "hardcore_standby_", 1, original);
        assertEquals(3, creations.size());
        assertTrue(creations.stream().allMatch(WorldCreator::hardcore));
        for (World world : List.of(run.overworld(), run.nether(), run.end())) {
            verify(world).setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, false);
            verify(world, never()).setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
            var order = inOrder(world);
            order.verify(world).setHardcore(true);
            order.verify(world).setDifficulty(Difficulty.HARD);
            order.verify(world).save();
        }
    }

    @Test
    void startupRepairsExistingRunAndFallbackWorldsWithoutReplacingRun() {
        World active = world("saved_run", 987L, World.Environment.NORMAL);
        World nether = world("saved_run_nether", 987L, World.Environment.NETHER);
        World end = world("saved_run_the_end", 987L, World.Environment.THE_END);
        worlds.put(active.getName(), active);
        worlds.put(nether.getName(), nether);
        worlds.put(end.getName(), end);
        set("activeWorldName", active.getName());
        config.set("state.active-nether", nether.getName());
        config.set("state.active-end", end.getName());
        config.set("wins.old.run", 5);
        call("initializeWorldState");
        for (World world : List.of(original, active, nether, end)) {
            verify(world).setHardcore(true);
            verify(world).setDifficulty(Difficulty.HARD);
            verify(world).setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, false);
            verify(world).save();
        }
        assertEquals("saved_run", plugin.activeWorldName());
        assertEquals(987L, active.getSeed());
        assertEquals(5, config.getInt("wins.old.run"));
        assertTrue(creations.isEmpty());
    }

    @Test
    void deadHardcorePlayerRespawnsAndReturnsToSurvivalInNewRun() {
        when(original.isHardcore()).thenReturn(true);
        FreshRun run = FreshRun.create(plugin, "hardcore_standby_", 1, original);
        set("standby", run);
        set("standbyWorldName", run.name());
        Player victim = player(original);
        AtomicBoolean dead = new AtomicBoolean(true);
        when(victim.isDead()).thenAnswer(i -> dead.get());
        Player.Spigot spigot = mock(Player.Spigot.class);
        when(victim.spigot()).thenReturn(spigot);
        doAnswer(i -> { dead.set(false); return null; }).when(spigot).respawn();
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(victim));
        call("executeHotWorldMigration");
        var order = inOrder(spigot, victim);
        order.verify(spigot).respawn();
        order.verify(victim).teleport(argThat((Location location) -> location.getWorld().equals(run.overworld())));
        order.verify(victim).setGameMode(GameMode.SURVIVAL);
        assertEquals(run.name(), plugin.activeWorldName());
        assertFalse(plugin.resetInProgress());
        verify(victim.getInventory()).clear();
    }

    @Test
    void titleScreenDisconnectThenLaterJoinStartsCurrentRunInSurvival() throws Exception {
        checkOfflineHardcoreReturn(false);
    }

    @Test
    void spectateDisconnectThenLaterJoinStartsCurrentRunInSurvival() throws Exception {
        checkOfflineHardcoreReturn(true);
    }

    private void checkOfflineHardcoreReturn(boolean spectated) throws Exception {
        idle();
        Player victim = player(original);
        var currentWorld = new java.util.concurrent.atomic.AtomicReference<>(original);
        var mode = new java.util.concurrent.atomic.AtomicReference<>(GameMode.SURVIVAL);
        AtomicBoolean dead = new AtomicBoolean(true);
        AtomicBoolean online = new AtomicBoolean(true);
        when(victim.getWorld()).thenAnswer(i -> currentWorld.get());
        when(victim.getGameMode()).thenAnswer(i -> mode.get());
        when(victim.isDead()).thenAnswer(i -> dead.get());
        when(victim.isOnline()).thenAnswer(i -> online.get());
        doAnswer(i -> { mode.set(i.getArgument(0)); return null; }).when(victim).setGameMode(any(GameMode.class));
        when(victim.teleport(any(Location.class))).thenAnswer(i -> {
            currentWorld.set(((Location)i.getArgument(0)).getWorld());
            return true;
        });
        Player.Spigot spigot = mock(Player.Spigot.class);
        when(victim.spigot()).thenReturn(spigot);
        doAnswer(i -> { dead.set(false); return null; }).when(spigot).respawn();
        String marker = "players." + victim.getUniqueId() + ".run-world";
        config.set(marker, original.getName());
        org.bukkit.event.entity.PlayerDeathEvent death = mock(org.bukkit.event.entity.PlayerDeathEvent.class);
        when(death.getPlayer()).thenReturn(victim);
        when(death.getDrops()).thenReturn(new ArrayList<>());
        plugin.onPlayerDeath(death);

        if (spectated) {
            PlayerRespawnEvent respawn = mock(PlayerRespawnEvent.class);
            Location respawnLocation = original.getSpawnLocation();
            when(respawn.getRespawnLocation()).thenReturn(respawnLocation);
            plugin.onRespawn(respawn);
            // Vanilla completes the Spectate World choice after its respawn event.
            dead.set(false);
            victim.setGameMode(GameMode.SPECTATOR);
            assertEquals(GameMode.SPECTATOR, victim.getGameMode());
            verify(victim, never()).setGameMode(GameMode.SURVIVAL);
        }

        // Title Screen disconnects while still dead; Spectate -> disconnect leaves
        // a living spectator. Neither player is online when the next run commits.
        online.set(false);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        call("executeHotWorldMigration");
        String newRun = plugin.activeWorldName();
        assertNotEquals(original.getName(), newRun);
        assertEquals(original.getName(), config.getString(marker));
        verify(victim.getInventory(), never()).clear();
        verify(spigot, never()).respawn();

        // Reload real YAML to check that the old-run marker survives a later login,
        // including a server restart while the player is disconnected.
        Path saved = root.resolve("saved-player-state.yml");
        ConfigStore.save(saved, config.saveToString());
        config = ConfigStore.load(saved, null);
        doReturn(config).when(plugin).getConfig();
        online.set(true);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(victim));
        plugin.onPlayerJoin(new org.bukkit.event.player.PlayerJoinEvent(victim, (Component)null));

        var order = inOrder(victim);
        order.verify(victim).teleport(argThat((Location location) -> location.getWorld().getName().equals(newRun)));
        order.verify(victim).setGameMode(GameMode.SURVIVAL);
        assertEquals(GameMode.SURVIVAL, victim.getGameMode());
        assertFalse(victim.isDead());
        assertEquals(newRun, victim.getWorld().getName());
        assertEquals(newRun, config.getString(marker));
        assertEquals(1, config.getInt("deaths." + victim.getUniqueId() + ".count"));
        verify(spigot, times(spectated ? 0 : 1)).respawn();
        verify(victim.getInventory()).clear();
        verify(victim.getEnderChest()).clear();
        verify(victim).setLevel(0);

        // Another reconnect within the same new run must not reset progress again.
        plugin.onPlayerJoin(new org.bukkit.event.player.PlayerJoinEvent(victim, (Component)null));
        verify(victim.getInventory(), times(1)).clear();
    }

    @Test
    void spectatorRejoiningBeforeCountdownEndsWaitsForNewWorldToBecomeSurvival() {
        Player spectator = player(original);
        var currentWorld = new java.util.concurrent.atomic.AtomicReference<>(original);
        var mode = new java.util.concurrent.atomic.AtomicReference<>(GameMode.SPECTATOR);
        when(spectator.getWorld()).thenAnswer(i -> currentWorld.get());
        when(spectator.getGameMode()).thenAnswer(i -> mode.get());
        doAnswer(i -> { mode.set(i.getArgument(0)); return null; }).when(spectator).setGameMode(any(GameMode.class));
        when(spectator.teleport(any(Location.class))).thenAnswer(i -> {
            currentWorld.set(((Location)i.getArgument(0)).getWorld());
            return true;
        });
        config.set("players." + spectator.getUniqueId() + ".run-world", original.getName());
        plugin.onPlayerJoin(new org.bukkit.event.player.PlayerJoinEvent(spectator, (Component)null));
        assertEquals(GameMode.SPECTATOR, spectator.getGameMode());
        verify(spectator, never()).setGameMode(GameMode.SURVIVAL);
        verify(spectator.getInventory(), never()).clear();
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(spectator));
        call("executeHotWorldMigration");
        assertEquals(plugin.activeWorldName(), spectator.getWorld().getName());
        assertEquals(GameMode.SURVIVAL, spectator.getGameMode());
        var order = inOrder(spectator);
        order.verify(spectator).teleport(any(Location.class));
        order.verify(spectator).setGameMode(GameMode.SURVIVAL);
        verify(spectator.getInventory()).clear();
    }

    @Test
    void failedReturningSpectatorTeleportDoesNotClearOldRunMarkerOrInventory() {
        World active = world("new_run", 987L, World.Environment.NORMAL);
        worlds.put(active.getName(), active);
        set("activeWorldName", active.getName());
        Player spectator = player(original);
        when(spectator.getGameMode()).thenReturn(GameMode.SPECTATOR);
        when(spectator.teleport(any(Location.class))).thenReturn(false);
        String marker = "players." + spectator.getUniqueId() + ".run-world";
        config.set(marker, original.getName());
        plugin.onPlayerJoin(new org.bukkit.event.player.PlayerJoinEvent(spectator, (Component)null));
        assertEquals(original.getName(), config.getString(marker));
        verify(spectator, never()).setGameMode(GameMode.SURVIVAL);
        verify(spectator.getInventory(), never()).clear();
        verify(scheduler).runTaskLater(eq(plugin), any(Runnable.class), eq(20L));
        when(spectator.teleport(any(Location.class))).thenReturn(true);
        plugin.onPlayerJoin(new org.bukkit.event.player.PlayerJoinEvent(spectator, (Component)null));
        assertEquals(active.getName(), config.getString(marker));
        verify(spectator).setGameMode(GameMode.SURVIVAL);
        verify(spectator.getInventory()).clear();
    }

    @Test
    void customCountdownTitlesReachVictimTeammateAndManualReset() throws Exception {
        set("countdownSeconds", 3);
        YamlConfiguration text = new YamlConfiguration();
        text.set("titles.countdown-victim.title", "<aqua><victim> down!</aqua>");
        text.set("titles.countdown-teammate.title", "<green>Help <victim></green>");
        text.set("titles.countdown-manual.title", "<gold>New run <run></gold>");
        set("messages", Messages.from(text, warning -> fail(warning)));
        Player victim = player(original);
        Player teammate = player(original);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(victim, teammate));
        Method countdown = WipeTogether.class.getDeclaredMethod("startResetCountdownSequence", Player.class);
        countdown.setAccessible(true);
        countdown.invoke(plugin, victim);
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskTimer(eq(plugin), tasks.capture(), eq(0L), eq(20L));
        tasks.getValue().run();
        verify(victim).showTitle(argThat((Title title) -> plain(title.title()).equals("Alex down!")));
        verify(teammate).showTitle(argThat((Title title) -> plain(title.title()).equals("Help Alex")));
        idle();
        assertTrue(plugin.beginManualReset());
        verify(scheduler, times(2)).runTaskTimer(eq(plugin), tasks.capture(), eq(0L), eq(20L));
        tasks.getValue().run();
        verify(victim).showTitle(argThat((Title title) -> plain(title.title()).equals("New run 1")));
        verify(teammate).showTitle(argThat((Title title) -> plain(title.title()).equals("New run 1")));
    }

    @Test
    void disablingWelcomeTitleDoesNotPreventWorldMigrationOrInventoryReset() {
        YamlConfiguration text = new YamlConfiguration();
        text.set("titles.welcome.enabled", false);
        set("messages", Messages.from(text, warning -> fail(warning)));
        Player player = player(original);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
        call("executeHotWorldMigration");
        assertNotEquals("world", plugin.activeWorldName());
        assertFalse(plugin.resetInProgress());
        verify(player.getInventory()).clear();
        verify(player).setGameMode(GameMode.SURVIVAL);
        verify(player, never()).showTitle(any(Title.class));
    }

    @Test
    void disablingVictoryPresentationStillRecordsWinAndFinalTime() {
        idle();
        activeEnd();
        config.set("state.dragon-defeated-world", "world_the_end");
        config.set("state.run-elapsed-millis", 125000L);
        YamlConfiguration text = new YamlConfiguration();
        text.set("titles.victory.enabled", false);
        text.set("chat.victory", "");
        set("messages", Messages.from(text, warning -> fail(warning)));
        Player player = player(original);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
        new VictoryEvents(plugin).complete(player);
        assertEquals(125000L, config.getLong("wins.world.duration-millis"));
        assertEquals("world", config.getString("state.completed-world"));
        verify(player, never()).showTitle(any(Title.class));
        bukkit.verify(() -> Bukkit.broadcast(any(Component.class)), never());
        verify(scheduler).runTaskLater(eq(plugin), any(Runnable.class), eq(40L));
    }

    @Test
    void customCommandFeedbackDoesNotChangePermissionsOrCommandBehavior() {
        idle();
        YamlConfiguration text = new YamlConfiguration();
        text.set("commands.run-set", "<gold>Attempt <run></gold>");
        text.set("commands.no-permission", "<red>Denied by owner</red>");
        set("messages", Messages.from(text, warning -> fail(warning)));
        CommandSender sender = admin();
        AdminCommands.execute(plugin, sender, "setrun", new String[]{"8"});
        assertEquals(8, plugin.currentCycle());
        verify(sender).sendMessage(argThat((Component message) -> plain(message).equals("Attempt 8")));
        CommandSender stranger = mock(CommandSender.class);
        AdminCommands.execute(plugin, stranger, "setrun", new String[]{"99"});
        assertEquals(8, plugin.currentCycle());
        verify(stranger).sendMessage(argThat((Component message) -> plain(message).equals("Denied by owner")));
    }

    @Test
    void customTabMessagesUseFrozenTimeAndCompletionFooter() {
        YamlConfiguration text = new YamlConfiguration();
        text.set("tab.header", "<aqua>Run <run>: <time></aqua>");
        text.set("tab.footer-complete", "<green>Finished!</green>");
        set("messages", Messages.from(text, warning -> fail(warning)));
        config.set("state.completed-world", "world");
        config.set("state.completion-millis", 123000L);
        Player player = player(original);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
        WipeTogetherTimerIntegrated.bootstrap(plugin);
        ArgumentCaptor<Runnable> timer = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskTimer(eq(plugin), timer.capture(), eq(0L), eq(20L));
        timer.getValue().run();
        verify(player).sendPlayerListHeaderAndFooter(
                argThat(header -> plain(header).equals("Run 1: 02:03")),
                argThat(footer -> plain(footer).equals("Finished!")));
    }

    @Test
    void malformedMessagesStopsStartupWithoutTouchingSavedGameData() throws Exception {
        Path data = Files.createDirectory(root.resolve("WipeTogether"));
        String broken = "titles: [unterminated\n";
        Files.writeString(data.resolve("messages.yml"), broken);
        String saved = "state:\n  active-world: important_run\n";
        Files.writeString(data.resolve("config.yml"), saved);
        doReturn(data.toFile()).when(plugin).getDataFolder();
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        org.bukkit.plugin.PluginManager manager = mock(org.bukkit.plugin.PluginManager.class);
        doReturn(server).when(plugin).getServer();
        when(server.getPluginManager()).thenReturn(manager);
        plugin.onEnable();
        plugin.onDisable();
        verify(manager).disablePlugin(plugin);
        verify(manager, never()).registerEvents(any(), any());
        verify(plugin, never()).saveConfig();
        assertTrue(creations.isEmpty());
        assertEquals(saved, Files.readString(data.resolve("config.yml")));
        assertEquals(broken, Files.readString(data.resolve("messages.yml")));
    }

    @Test
    void startupAppliesConfiguredPermissionMessageToBukkitCommands() throws Exception {
        Path data = Files.createDirectory(root.resolve("WipeTogether"));
        Files.writeString(data.resolve("messages.yml"), "commands:\n  no-permission: '<red>Team admins only</red>'\n");
        doReturn(data.toFile()).when(plugin).getDataFolder();
        doReturn(getClass().getResourceAsStream("/config.yml")).when(plugin).getResource("config.yml");
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        org.bukkit.plugin.PluginManager manager = mock(org.bukkit.plugin.PluginManager.class);
        org.bukkit.command.PluginCommand command = mock(org.bukkit.command.PluginCommand.class);
        doReturn(server).when(plugin).getServer();
        when(server.getPluginManager()).thenReturn(manager);
        doReturn(command).when(plugin).getCommand(anyString());
        plugin.onEnable();
        verify(manager, never()).disablePlugin(plugin);
        verify(command, times(5)).permissionMessage(argThat(message -> plain(message).equals("Team admins only")));
    }

    private static String plain(Component text) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(text);
    }

    void set(String name, Object value) {
        try {
            Field f = WipeTogether.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(plugin, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    Object get(String name) {
        try {
            Field f = WipeTogether.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(plugin);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    void call(String name) {
        try {
            Method m = WipeTogether.class.getDeclaredMethod(name);
            m.setAccessible(true);
            m.invoke(plugin);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
