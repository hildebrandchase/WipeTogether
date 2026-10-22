package com.wipetogether;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;

class WorldStorageTest {
    @TempDir Path root;

    @Test void findsModernSavedDimensionMetadataAndMapsDefaultDimensions() throws Exception {
        Path main = Files.createDirectories(root.resolve("world"));
        WorldStorage storage = new WorldStorage(root, main, "world");
        Path saved = Files.createDirectories(main.resolve("dimensions/minecraft/hardcore_standby_6/data/minecraft"));
        Files.writeString(saved.resolve("world_gen_settings.dat"), "seed");
        assertTrue(storage.hasSavedWorld("hardcore_standby_6"));
        assertTrue(storage.exists("hardcore_standby_6"));
        assertEquals(main.resolve("dimensions/minecraft/overworld"), storage.path("world"));
        assertEquals(main.resolve("dimensions/minecraft/the_nether"), storage.path("world_nether"));
        assertEquals(main.resolve("dimensions/minecraft/the_end"), storage.path("world_the_end"));
    }

    @Test void acceptsUnmigratedLegacyWorldsOnModernServer() throws Exception {
        Path main = Files.createDirectories(root.resolve("world"));
        Path old = Files.createDirectories(root.resolve("hardcore_standby_2"));
        Files.writeString(old.resolve("level.dat"), "legacy");
        assertTrue(new WorldStorage(root, main, "world").hasSavedWorld("hardcore_standby_2"));
        assertTrue(new WorldStorage(root, null, "world").hasSavedWorld("hardcore_standby_2"));
    }

    @Test void modernCleanupDeletesOnlyTheRetiredDimension() throws Exception {
        Path main = Files.createDirectories(root.resolve("world"));
        WorldStorage storage = new WorldStorage(root, main, "world");
        Path active = Files.createDirectories(storage.path("hardcore_standby_7"));
        Path old = Files.createDirectories(storage.path("hardcore_standby_6"));
        Files.writeString(active.resolve("keep.dat"), "active world");
        Files.writeString(main.resolve("level.dat"), "shared server data");
        Path retired = storage.retire("hardcore_standby_6", old);
        assertFalse(Files.exists(old));
        RunFiles.deleteRetired(retired.getParent(), retired);
        assertEquals("active world", Files.readString(active.resolve("keep.dat")));
        assertEquals("shared server data", Files.readString(main.resolve("level.dat")));
    }

    @Test void rejectsParentOrUnrelatedFolderAsDimensionCleanupTarget() throws Exception {
        Path main = Files.createDirectories(root.resolve("world"));
        WorldStorage storage = new WorldStorage(root, main, "world");
        assertThrows(IOException.class, () -> storage.retire("hardcore_standby_2", main));
        assertThrows(IOException.class, () -> storage.retire("hardcore_standby_2", main.resolve("dimensions/minecraft/overworld")));
        assertThrows(IllegalArgumentException.class, () -> storage.path("../../world"));
    }

    @Test void emptyFolderDoesNotQualifyAsSavedWorld() throws Exception {
        Path main = Files.createDirectories(root.resolve("world"));
        WorldStorage storage = new WorldStorage(root, main, "world");
        Files.createDirectories(storage.path("hardcore_standby_4"));
        assertFalse(storage.hasSavedWorld("hardcore_standby_4"));
    }
}
