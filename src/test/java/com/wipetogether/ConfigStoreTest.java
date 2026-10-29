package com.wipetogether;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {
    @TempDir
    Path root;

    @Test void freshConfigurationRetainsTheBundledComments() throws Exception {
        String defaults = "# Game settings\nsettings:\n  # Seconds before a reset\n  countdown-seconds: 3\n";
        var stream = new ByteArrayInputStream(defaults.getBytes(StandardCharsets.UTF_8));
        var config = ConfigStore.load(root.resolve("new-config.yml"), stream);
        assertEquals(3, config.getInt("settings.countdown-seconds"));
        assertTrue(config.saveToString().contains("# Game settings"));
        assertTrue(config.saveToString().contains("# Seconds before a reset"));
    }

    @Test
    void malformedYamlIsRejectedWithoutChangingOriginal() throws Exception {
        Path file = root.resolve("config.yml");
        String broken = "state: [unclosed\n";
        Files.writeString(file, broken);
        assertThrows(InvalidConfigurationException.class, () -> ConfigStore.load(file, null));
        assertEquals(broken, Files.readString(file));
    }

    @Test
    void atomicSaveReplacesWholeFileAndRoundTripsRecords() throws Exception {
        Path file = root.resolve("WipeTogether/config.yml");
        ConfigStore.save(file, "state:\n  active-world: old\n");
        var config = ConfigStore.load(file, null);
        config.set("state.active-world", "wipetogether_standby_2");
        config.set("wins.first.seed", Long.MAX_VALUE);
        ConfigStore.save(file, config.saveToString());
        var reloaded = ConfigStore.load(file, null);
        assertEquals("wipetogether_standby_2", reloaded.getString("state.active-world"));
        assertEquals(Long.MAX_VALUE, reloaded.getLong("wins.first.seed"));
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void failedReplacementRetainsOriginalDestinationAndCleansTemporaryFile() throws Exception {
        Path file = Files.createDirectory(root.resolve("config.yml"));
        Files.writeString(file.resolve("keep"), "original");
        assertThrows(IOException.class, () -> ConfigStore.save(file, "state: new\n"));
        assertEquals("original", Files.readString(file.resolve("keep")));
        try (var files = Files.list(root)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void defaultsDoNotReplaceSavedState() throws Exception {
        Path file = root.resolve("config.yml");
        Files.writeString(file, "state:\n  active-world: existing_run\n");
        var defaults = new ByteArrayInputStream("state:\n  active-world: world\nsettings:\n  countdown-seconds: 3\n".getBytes(StandardCharsets.UTF_8));
        var config = ConfigStore.load(file, defaults);
        assertEquals("existing_run", config.getString("state.active-world"));
        assertEquals(3, config.getInt("settings.countdown-seconds"));
    }

    @Test
    void legacyMigrationPreservesAllData() throws Exception {
        Path legacy = Files.createDirectory(root.resolve("HardcoreReset"));
        Files.writeString(legacy.resolve("config.yml"), "wins: {first: 1}\n");
        Path destination = root.resolve("WipeTogether");
        ConfigStore.migrateLegacy(destination);
        assertFalse(Files.exists(legacy));
        assertEquals("wins: {first: 1}\n", Files.readString(destination.resolve("config.yml")));
        ConfigStore.migrateLegacy(destination);
    }

    @Test
    void ambiguousMigrationDoesNotOverwriteEitherFolder() throws Exception {
        Files.createDirectory(root.resolve("HardcoreReset"));
        Path destination = Files.createDirectory(root.resolve("WipeTogether"));
        assertThrows(IOException.class, () -> ConfigStore.migrateLegacy(destination));
        assertTrue(Files.isDirectory(root.resolve("HardcoreReset")));
        assertTrue(Files.isDirectory(destination));
    }
}
