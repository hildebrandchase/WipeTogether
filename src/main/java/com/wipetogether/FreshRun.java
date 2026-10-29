package com.wipetogether;


import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.*;
import net.kyori.adventure.util.TriState;

record FreshRun(String name, long seed, World overworld, World nether, World end) {
    private static final SecureRandom RANDOM = new SecureRandom();

    static FreshRun create(WipeTogether plugin, String prefix, int cycle, World previous) {
        if (prefix == null || !prefix.matches("[A-Za-z0-9_-]+") || prefix.length() > 80)
            throw new IllegalArgumentException("Invalid standby-prefix; use up to 80 letters, numbers, underscores or hyphens.");
        String base;
        do {
            base = prefix + cycle + "_" + UUID.randomUUID().toString().replace("-", "");
        } while (!unused(plugin, base));
        List<String> used = new ArrayList<>(plugin.getConfig().getStringList("state.used-seeds"));
        if (previous != null && !used.contains(Long.toString(previous.getSeed())))
            used.add(Long.toString(previous.getSeed()));
        long seed;
        do {
            seed = RANDOM.nextLong();
        } while (used.contains(Long.toString(seed)));
        used.add(Long.toString(seed));
        plugin.getConfig().set("state.used-seeds", used);
        plugin.saveConfig();
        try {
            World normal = createOne(plugin, base, World.Environment.NORMAL, seed, previous);
            World nether = createOne(plugin, base + "_nether", World.Environment.NETHER, seed, previous);
            World end = createOne(plugin, base + "_the_end", World.Environment.THE_END, seed, previous);
            return new FreshRun(base, seed, normal, nether, end);
        } catch (RuntimeException error) {
            // Only these freshly reserved names belong to this failed preparation.
            for (String name : new String[]{base, base + "_nether", base + "_the_end"})
                plugin.disposeOldWorldSafely(name);
            throw error;
        }
    }

    private static boolean unused(WipeTogether plugin, String base) {
        for (String name : new String[]{base, base + "_nether", base + "_the_end"}) {
            if (Bukkit.getWorld(name) != null || plugin.worldStorage().exists(name)) return false;
        }
        return true;
    }

    private static World createOne(WipeTogether plugin, String name, World.Environment environment, long seed, World previous) {
        // WorldCreator.seed does not override an existing world's level.dat.
        if (Bukkit.getWorld(name) != null || plugin.worldStorage().exists(name))
            throw new IllegalStateException("Refusing to reuse existing world: " + name);
        World world = new WorldCreator(name).environment(environment).type(WorldType.NORMAL)
                .seed(seed).hardcore(true).keepSpawnLoaded(TriState.FALSE).createWorld();
        if (world == null || world.getSeed() != seed || world.getEnvironment() != environment)
            throw new IllegalStateException("World creation or seed validation failed: " + name);
        world.setAutoSave(true);
        world.setHardcore(true);
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, false);
        world.setGameRule(GameRule.NATURAL_REGENERATION, true);
        world.setDifficulty(Difficulty.HARD);
        // Paper 26.3 dimensions such as the Nether have no world clock.
        // Only the Overworld needs a fresh day/night and weather state.
        if (environment == World.Environment.NORMAL) {
            world.setFullTime(0L);
            world.setStorm(false);
            world.setThundering(false);
        }
        // Persist generation settings before config can commit this as the active run.
        world.save();
        return world;
    }
}
