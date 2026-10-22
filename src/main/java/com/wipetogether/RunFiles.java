package com.wipetogether;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * World paths and retirement are kept independent of Bukkit for regression tests.
 */
final class RunFiles {
    static Path worldPath(Path container, String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Unsafe world name: " + name);
        try {
            Path root = container.toRealPath();
            Path path = root.resolve(name);
            if (Files.isSymbolicLink(path) || (Files.exists(path) && !path.toRealPath().getParent().equals(root)))
                throw new IllegalArgumentException("World path is a link or outside world container: " + path);
            return path;
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot resolve world container", error);
        }
    }

    static boolean isOwnedName(String name, String standbyPrefix, String worldPrefix) {
        if (name == null) return false;
        for (String prefix : new String[]{standbyPrefix, worldPrefix}) {
            if (prefix == null || !prefix.matches("[A-Za-z0-9_-]+")) continue;
            if (name.matches(Pattern.quote(prefix) + "[0-9]+(?:_[0-9a-f]{32})?(?:_nether|_the_end)?")) return true;
        }
        return false;
    }

    static Path retire(Path container, Path folder) throws IOException {
        Path root = container.toRealPath();
        if (!folder.toAbsolutePath().normalize().getParent().equals(root) || Files.isSymbolicLink(folder))
            throw new IOException("Refusing to retire unsafe path: " + folder);
        if (!Files.exists(folder)) return null;
        if (!folder.toRealPath().getParent().equals(root)) throw new IOException("World is outside container");
        Path retired = root.resolve(".wipetogether-retired-" + UUID.randomUUID());
        return Files.move(folder, retired);
    }

    static void deleteRetired(Path container, Path retired) throws IOException {
        Path root = container.toRealPath();
        Path path = retired.toAbsolutePath().normalize();
        if (!path.getParent().equals(root) || !path.getFileName().toString().matches("\\.wipetogether-retired-[0-9a-f-]{36}")
                || Files.isSymbolicLink(path)) throw new IOException("Refusing to delete non-retired path: " + path);
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
