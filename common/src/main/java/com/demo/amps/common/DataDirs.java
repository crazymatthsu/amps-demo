package com.demo.amps.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Read-only view of the AMPS data directory, which the podman scripts bind-mount
 * onto the host so demos can observe the transaction log and SOW files directly.
 *
 * <p>The journal-size lab uses this to show what full publishes actually cost on
 * disk versus delta publishes. Numbers move in steps rather than smoothly because
 * AMPS preallocates journal files to {@code MinJournalSize} -- a fact worth seeing
 * rather than reading about.
 */
public final class DataDirs {

    private DataDirs() {
    }

    public static Path journalDir() {
        return DemoConfig.dataDir().resolve("journal");
    }

    public static Path sowDir() {
        return DemoConfig.dataDir().resolve("sow");
    }

    /** True when the data directory is visible from this process. */
    public static boolean visible() {
        return Files.isDirectory(DemoConfig.dataDir());
    }

    /** Total bytes of all regular files under {@code dir}; 0 if it does not exist. */
    public static long sizeOf(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile).mapToLong(DataDirs::sizeOfFile).sum();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot measure " + dir, e);
        }
    }

    /** File count under {@code dir}; 0 if it does not exist. */
    public static long fileCount(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + dir, e);
        }
    }

    /** Sorted "name  size" listing, for showing the journal rolling over. */
    public static List<String> listing(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(dir)) {
            List<String> out = new ArrayList<>();
            paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> out.add(String.format("%-40s %s",
                            p.getFileName(), Console.bytes(sizeOfFile(p)))));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + dir, e);
        }
    }

    private static long sizeOfFile(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }
}
