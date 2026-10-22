package com.wipetogether;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

import org.bukkit.World;

/**
 * Supports the legacy separate folders and Paper 26.1+ per-key dimension folders.
 */
final class WorldStorage {
    private final Path container;
    private final Path levelDirectory;
    private final String defaultName;

    WorldStorage(Path container, Path levelDirectory, String defaultName) {
        this.container = container.toAbsolutePath().normalize();
        this.levelDirectory = levelDirectory == null ? null : levelDirectory.toAbsolutePath().normalize();
        this.defaultName = defaultName;
    }

    static WorldStorage detect(Path container, World mainWorld) {
        Path level = null;
        if (mainWorld.getWorldFolder() != null) {
            Path folder = mainWorld.getWorldFolder().toPath().toAbsolutePath().normalize();
            if (folder.endsWith(Path.of("dimensions", "minecraft", "overworld")))
                level = folder.getParent().getParent().getParent();
        }
        return new WorldStorage(container, level, mainWorld.getName());
    }

    Path path(String name) {
        Path legacy = RunFiles.worldPath(container, name); // validates the name
        if (levelDirectory == null) return legacy;
        String key = name.equals(defaultName) ? "overworld" : name.equals(defaultName + "_nether") ? "the_nether"
                : name.equals(defaultName + "_the_end") ? "the_end" : name.toLowerCase(Locale.ROOT);
        try {
            Path root = container.toRealPath();
            Path target = levelDirectory.resolve("dimensions/minecraft").resolve(key).normalize();
            if (!target.startsWith(root) || target.equals(root)) throw new IOException("Dimension outside container");
            for (Path part = target; !part.equals(root); part = part.getParent()) {
                if (Files.isSymbolicLink(part) || (Files.exists(part) && !part.toRealPath().startsWith(root)))
                    throw new IOException("Linked dimension path: " + part);
            }
            return target;
        } catch (IOException error) {
            throw new IllegalArgumentException("Unsafe dimension path for " + name, error);
        }
    }

    boolean exists(String name) {
        return Files.exists(path(name)) || Files.exists(RunFiles.worldPath(container, name));
    }

    boolean hasSavedWorld(String name) {
        Path target = path(name);
        // The dimension's seed/generation settings live here on modern Paper; it has no level.dat.
        if (levelDirectory != null && Files.isRegularFile(target.resolve("data/minecraft/world_gen_settings.dat")))
            return true;
        return Files.isRegularFile(RunFiles.worldPath(container, name).resolve("level.dat"));
    }

    Path retire(String name, Path loadedFolder) throws IOException {
        Path expected = path(name);
        Path legacy = RunFiles.worldPath(container, name);
        Path actual = loadedFolder == null ? (Files.exists(expected) ? expected : legacy) : loadedFolder.toAbsolutePath().normalize();
        if (!actual.equals(expected) && !actual.equals(legacy))
            throw new IOException("Unexpected world folder: " + actual);
        if (!Files.exists(actual)) return null;
        // Retirement/deletion stays within this exact parent: never the shared level directory.
        return RunFiles.retire(actual.getParent(), actual);
    }
}
