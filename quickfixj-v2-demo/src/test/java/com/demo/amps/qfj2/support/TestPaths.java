package com.demo.amps.qfj2.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Where things are, whichever directory the test task runs in: the unit
 * tests run in the module folder, the integration tests in the repository
 * root (the container harness needs that).
 */
public final class TestPaths {

    private TestPaths() {
    }

    /** The module directory. */
    public static Path moduleDir() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isDirectory(cwd.resolve("config/dictionary"))) {
            return cwd;
        }
        Path nested = cwd.resolve("quickfixj-v2-demo");
        if (Files.isDirectory(nested.resolve("config/dictionary"))) {
            return nested;
        }
        throw new IllegalStateException("cannot find quickfixj-v2-demo/config/dictionary from " + cwd);
    }

    public static Path dictionary() {
        return moduleDir().resolve("config/dictionary/FIX42.xml");
    }

    /** A fresh, empty working directory under build/ for one test. */
    public static Path freshWorkDir(String name) throws IOException {
        String base = System.getProperty("qfj2.test.workdir",
                moduleDir().resolve("build/test-work").toString());
        Path dir = Path.of(base).resolve(name + "-" + System.nanoTime());
        deleteRecursively(dir);
        Files.createDirectories(dir);
        return dir;
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("cannot delete " + path, e);
                }
            });
        }
    }
}
