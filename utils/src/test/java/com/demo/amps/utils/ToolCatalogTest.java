package com.demo.amps.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wrappers in {@code utils/bin} are the documented way in, so the thing most
 * worth testing without a server is that they still line up with the code: a
 * renamed subcommand that nobody notices turns every script into an error
 * message.
 */
class ToolCatalogTest {

    // Indented in ampsToFileSOWByKey.sh, which guards its arguments first.
    private static final Pattern LAUNCH =
            Pattern.compile("^\\s*launch\\s+(\\S+)", Pattern.MULTILINE);

    @Test
    @DisplayName("every tool has a name, a summary and usage text")
    void toolsAreDescribed() {
        for (Tool tool : UtilsRunner.tools().values()) {
            assertFalse(tool.name().isBlank(), "tool with no name");
            assertFalse(tool.summary().isBlank(), tool.name() + " has no summary");
            assertFalse(tool.usage().isBlank(), tool.name() + " has no usage");
            assertTrue(tool.usage().contains("--"),
                    tool.name() + " usage lists no options");
        }
    }

    @Test
    @DisplayName("every shell wrapper dispatches to a tool that exists")
    void wrappersMatchTheRegistry() throws IOException {
        Map<String, Tool> tools = UtilsRunner.tools();
        List<String> problems = new ArrayList<>();
        List<String> dispatched = new ArrayList<>();

        for (Path script : wrappers()) {
            String body = Files.readString(script);
            Matcher matcher = LAUNCH.matcher(body);
            boolean found = false;
            while (matcher.find()) {
                found = true;
                String subcommand = matcher.group(1);
                dispatched.add(subcommand);
                if (!tools.containsKey(subcommand)) {
                    problems.add(script.getFileName() + " dispatches to unknown tool '"
                            + subcommand + "'");
                }
            }
            if (!found) {
                problems.add(script.getFileName() + " never calls launch");
            }
        }

        if (!problems.isEmpty()) {
            fail(String.join("\n", problems));
        }

        // And the other direction: a tool nobody can reach from bin/ is a tool
        // the README promises and the shell cannot deliver.
        for (String name : tools.keySet()) {
            assertTrue(dispatched.contains(name),
                    "no wrapper in utils/bin dispatches to '" + name + "'");
        }
    }

    @Test
    @DisplayName("the five wrappers the TODO asked for are present and executable")
    void theFiveToolsExist() throws IOException {
        List<String> expected = List.of(
                "fileToAMPS.sh",
                "ampsToFileTxLog.sh",
                "ampsToFileSOW.sh",
                "ampsToFileSOWByKey.sh",
                "truncateAMPS.sh");
        for (String name : expected) {
            Path script = binDir().resolve(name);
            assertTrue(Files.isRegularFile(script), "missing " + name);
            assertTrue(Files.isExecutable(script), name + " is not executable");
        }
    }

    @Test
    @DisplayName("the destructive tool documents that it is a dry run by default")
    void truncateAdvertisesItsSafetyCatch() throws IOException {
        String usage = UtilsRunner.tools().get("truncate").usage();
        assertTrue(usage.contains("--yes"), "truncate usage does not mention --yes");
        assertTrue(usage.toLowerCase().contains("dry run"),
                "truncate usage does not say it is a dry run by default");

        // And the wrapper says the same, since that is what people read first.
        String script = Files.readString(binDir().resolve("truncateAMPS.sh"));
        assertTrue(script.contains("DRY RUN BY DEFAULT"),
                "truncateAMPS.sh does not warn that it is a dry run by default");
        assertTrue(script.contains("does NOT truncate the transaction log"),
                "truncateAMPS.sh does not state the transaction-log limitation");
    }

    private static List<Path> wrappers() throws IOException {
        try (Stream<Path> files = Files.list(binDir())) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".sh"))
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .sorted()
                    .toList();
        }
    }

    private static Path binDir() {
        return repoRoot().resolve("utils/bin");
    }

    /** Tests run with the module directory as cwd; walk up to the build root. */
    private static Path repoRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("could not locate the repository root");
        }
        return candidate;
    }
}
