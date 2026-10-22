package com.wipetogether;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;

class RunFilesTest {
    @TempDir Path root;

    @Test void rejectsTraversalAndAbsolutePaths() {
        for (String name : new String[]{"../world", "..", "C:\\server", "foo/bar", "foo\\bar", "", "."})
            assertThrows(IllegalArgumentException.class, () -> RunFiles.worldPath(root, name));
    }

    @Test void cleanupOnlyAcceptsPluginOwnedNames() {
        assertTrue(RunFiles.isOwnedName("hardcore_standby_2", "hardcore_standby_", "hardcore_world_"));
        assertTrue(RunFiles.isOwnedName("hardcore_standby_2_" + "a".repeat(32) + "_nether", "hardcore_standby_", "hardcore_world_"));
        for (String name : new String[]{"world", "world_nether", "another_plugin_world", "hardcore_standby_backup", "../hardcore_standby_1"})
            assertFalse(RunFiles.isOwnedName(name, "hardcore_standby_", "hardcore_world_"));
    }

    @Test void retirementIsolatesDeletionFromRecreatedWorld() throws Exception {
        Path original = Files.createDirectory(root.resolve("hardcore_standby_2"));
        Files.createDirectories(original.resolve("region"));
        Files.writeString(original.resolve("region/r.0.0.mca"), "old terrain");
        Path retired = RunFiles.retire(root, original);
        Files.createDirectory(original);
        Files.writeString(original.resolve("level.dat"), "new run");
        RunFiles.deleteRetired(root, retired);
        assertFalse(Files.exists(retired));
        assertEquals("new run", Files.readString(original.resolve("level.dat")));
    }

    @Test void refusesDeletingActiveWorldOrContainer() throws Exception {
        Path world = Files.createDirectory(root.resolve("world"));
        assertThrows(IOException.class, () -> RunFiles.deleteRetired(root, world));
        assertThrows(IOException.class, () -> RunFiles.deleteRetired(root, root));
        assertThrows(IOException.class, () -> RunFiles.retire(root, root));
        assertTrue(Files.exists(world));
    }
}
