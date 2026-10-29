package com.wipetogether;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Strict reads and atomic replacement: a failed write leaves the last committed file intact.
 */
final class ConfigStore {
    static YamlConfiguration load(Path file, InputStream defaults) throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        try (defaults) {
            boolean existing = Files.exists(file);
            if (existing) config.load(file.toFile());
            if (defaults != null) {
                var reader = new InputStreamReader(defaults, StandardCharsets.UTF_8);
                YamlConfiguration bundled = new YamlConfiguration();
                bundled.load(reader);
                if (!existing) config.loadFromString(bundled.saveToString());
                config.setDefaults(bundled);
            }
        }
        return config;
    }

    static void save(Path file, String yaml) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".wipetogether-config-", ".tmp");
        try {
            Files.writeString(temporary, yaml, StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // Fail closed if the filesystem cannot replace atomically; never truncate the committed file.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void migrateLegacy(Path destination) throws IOException {
        Path legacy = destination.resolveSibling("HardcoreReset");
        if (!Files.isDirectory(legacy)) return;
        if (Files.exists(destination)) {
            throw new IOException("Both plugins/HardcoreReset and plugins/WipeTogether exist. Back up both and select one data folder before starting.");
        }
        Files.move(legacy, destination, StandardCopyOption.ATOMIC_MOVE);
    }
}
