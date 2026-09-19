package com.demo.amps.connectors.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

/**
 * Asserts things about the CONFIG TREE IN THIS REPO ({@code amps-connectors/config/}), not
 * about the code: every deployable application configuration binds, validates and layers the
 * way it will at runtime -- without booting anything, without an AMPS server and without a
 * broker. With one application per directory and potentially dozens of them, the expensive
 * failures are all configuration mistakes that produce a perfectly healthy-looking process;
 * this test is where they fail loudly instead.
 *
 * <p>Each instance is bound exactly as the container layers it: baked {@code application.yml}
 * underneath, then {@code config/<env>/common/}, then {@code config/<env>/<flow>/<app>/} on
 * top (first source wins in a {@link Binder}, so they are stacked in reverse).
 */
class ConfigTreeTest {

    /**
     * The tree, found from wherever the test happens to run.
     *
     * <p>Gradle gives a {@code Test} task the module directory as its working directory, but
     * the {@code integrationTest} task in the same module runs from the repository root, and
     * an IDE will pick whichever it prefers. Looking for the tree rather than assuming one of
     * them is the difference between a failure and a test that silently walks nothing.
     */
    private static final Path CONFIG_ROOT = configRoot();

    private static Path configRoot() {
        for (Path candidate : List.of(
                Path.of("..", "config"),                      // the module directory
                Path.of("amps-connectors", "config"),         // the repository root
                Path.of("config"))) {                         // amps-connectors/ itself
            if (Files.isDirectory(candidate)) {
                return candidate.normalize();
            }
        }
        throw new IllegalStateException("cannot find amps-connectors/config from "
                + Path.of("").toAbsolutePath());
    }

    record Instance(String env, String flow, String app, Path file) {
        @Override
        public String toString() {
            return env + "/" + flow + "/" + app;
        }
    }

    // ---- discovery ---------------------------------------------------------------

    private static List<Path> envDirs() throws IOException {
        try (Stream<Path> envs = Files.list(CONFIG_ROOT)) {
            return envs.filter(Files::isDirectory).sorted().toList();
        }
    }

    static List<Instance> instances() throws IOException {
        List<Instance> out = new ArrayList<>();
        for (Path env : envDirs()) {
            try (Stream<Path> flows = Files.list(env)) {
                for (Path flow : flows.filter(Files::isDirectory).sorted().toList()) {
                    if (flow.getFileName().toString().equals("common")) {
                        continue;
                    }
                    try (Stream<Path> apps = Files.list(flow)) {
                        for (Path app : apps.filter(Files::isDirectory).sorted().toList()) {
                            out.add(new Instance(env.getFileName().toString(),
                                    flow.getFileName().toString(),
                                    app.getFileName().toString(),
                                    app.resolve("application.yml")));
                        }
                    }
                }
            }
        }
        return out;
    }

    // ---- binding, layered the way the container layers it ------------------------

    private static ConnectorsProperties bind(Instance instance) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        MutablePropertySources sources = new MutablePropertySources();
        for (PropertySource<?> source : loader.load(instance.toString(),
                new FileSystemResource(instance.file()))) {
            sources.addLast(source);
        }
        Path common = CONFIG_ROOT.resolve(instance.env()).resolve("common")
                .resolve("application.yml");
        if (Files.exists(common)) {
            for (PropertySource<?> source : loader.load(instance.env() + "/common",
                    new FileSystemResource(common))) {
                sources.addLast(source);
            }
        }
        // The baked layer underneath, from the jar's own resources: it is what supplies
        // source-driver, so an instance's ${source-driver:REAL} binds to an enum rather than
        // staying a literal -- the failure that only shows up in the container.
        for (PropertySource<?> source : loader.load("baked",
                new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("amps-connectors", Bindable.of(ConnectorsProperties.class)).get();
    }

    // ---- per-instance rules --------------------------------------------------------

    @ParameterizedTest
    @MethodSource("instances")
    void everyInstanceBindsValidatesAndDefinesConnectors(Instance instance) throws IOException {
        ConnectorsProperties properties = bind(instance);
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                    .as("%s: jakarta constraint violations", instance).isEmpty();
        }
        assertThat(ConnectorValidator.validate(properties))
                .as("%s: connector validation", instance).isEmpty();
        assertThat(properties.getConnectors())
                .as("%s: an instance with no connectors deploys a process that does nothing",
                        instance)
                .isNotEmpty();
    }

    @ParameterizedTest
    @MethodSource("instances")
    @DisplayName("every instance names exactly one transport, and the driver placeholder resolves")
    void everyInstanceNamesOneTransportAndABoundDriver(Instance instance) throws IOException {
        for (ConnectorProperties connector : bind(instance).getConnectors()) {
            assertThat(connector.getSource().configuredBlocks())
                    .as("%s/%s: exactly one of tcp/kafka/jdbc/hazelcast",
                            instance, connector.getName())
                    .hasSize(1);
            assertThat(connector.getSource().getDriver())
                    .as("%s/%s: source.driver resolved", instance, connector.getName())
                    .isEqualTo(SourceProperties.Driver.REAL);
        }
    }

    @ParameterizedTest
    @MethodSource("instances")
    @DisplayName("every instance inherits the common AMPS endpoint, defaulted for an IDE run")
    void everyInstanceInheritsTheSharedServerBlock(Instance instance) throws IOException {
        // The instance files never name a server: that is the common layer's job, and the
        // placeholders in it are what let the SAME files serve an IDE run and a container.
        AmpsServerProperties amps = bind(instance).getAmps();
        assertThat(amps.getHost()).as("%s: AMPS host", instance).isEqualTo("localhost");
        assertThat(amps.getPort()).as("%s: AMPS port", instance).isEqualTo(9007);
        assertThat(amps.getClientNamePrefix()).as("%s: client name prefix", instance).isNotBlank();
    }

    // ---- cross-file rules ----------------------------------------------------------

    @Test
    void theTreeIsEnvFlowApp() throws IOException {
        // Every application.yml sits at exactly config/<env>/<flow>/<app>/application.yml
        // (or <env>/common/application.yml). A file at the wrong depth is silently never
        // mounted, which is the worst kind of missing.
        try (Stream<Path> files = Files.walk(CONFIG_ROOT)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path rel = CONFIG_ROOT.relativize(file);
                assertThat(rel.getFileName().toString())
                        .as("%s: only application.yml belongs in the tree", rel)
                        .isEqualTo("application.yml");
                boolean common = rel.getNameCount() == 3
                        && rel.getName(1).toString().equals("common");
                boolean instance = rel.getNameCount() == 4
                        && !rel.getName(1).toString().equals("common");
                assertThat(common || instance)
                        .as("%s: expected <env>/common/application.yml or "
                                + "<env>/<flow>/<app>/application.yml", rel)
                        .isTrue();
            }
        }
    }

    @Test
    void commonNeverDefinesConnectors() throws IOException {
        // Two amps-connectors.connectors lists merge BY INDEX: a common entry would bleed
        // through underneath every app's own list. The file still parses, the app still
        // starts, and connector [0] is quietly not the one the instance file defines.
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (Path env : envDirs()) {
            Path common = env.resolve("common").resolve("application.yml");
            if (!Files.exists(common)) {
                continue;
            }
            for (PropertySource<?> source : loader.load(env.toString(),
                    new FileSystemResource(common))) {
                assertThat(((EnumerablePropertySource<?>) source).getPropertyNames())
                        .as("%s: amps-connectors.connectors must live in the instance files",
                                common)
                        .noneMatch(name -> name.startsWith("amps-connectors.connectors"));
            }
        }
    }

    @Test
    @DisplayName("an instance file owns its whole connector list, and names it after the app")
    void oneConnectorListPerApplication() throws IOException {
        // One directory is one deployable process, and the list in it is the process's whole
        // definition. The name rule is not cosmetic: a connector's name IS its AMPS client
        // name (<prefix>-<name>) and its publish store's identity, so two apps sharing one
        // would be two publishers fighting over one store.
        Map<String, String> owners = new HashMap<>();
        for (Instance instance : instances()) {
            List<ConnectorProperties> connectors = bind(instance).getConnectors();
            assertThat(connectors).extracting(ConnectorProperties::getName)
                    .as("%s: every connector is named", instance)
                    .doesNotContainNull();
            for (ConnectorProperties connector : connectors) {
                String scoped = instance.env() + "/" + connector.getName();
                String previous = owners.put(scoped, instance.toString());
                assertThat(previous)
                        .as("connector name %s is used by both %s and %s -- the AMPS client "
                                + "name would collide", scoped, previous, instance)
                        .isNull();
            }
        }
    }

    /**
     * A key whose <em>name</em> contains a credential word, and everything that follows it on
     * the line. Only the first match on a line is examined, because the placeholder that
     * follows a credential key routinely names the same word again
     * ({@code password: "${JDBC_PASSWORD:}"}).
     */
    private static final Pattern CREDENTIAL_KEY = Pattern.compile(
            "(?i)[A-Za-z0-9_.-]*(?:password|passwd|secret|token)[A-Za-z0-9_.-]*\\s*:\\s*(.*)$");

    /** One {@code ${...}} placeholder and nothing else, with or without the YAML quotes. */
    private static final Pattern PLACEHOLDER_ONLY = Pattern.compile("\"?\\$\\{[^{}]*}\"?");

    @Test
    @DisplayName("a credential key carries one ${...} placeholder, or no value at all")
    void credentialKeysCarryOnlyEnvironmentPlaceholders() throws IOException {
        // These files are plaintext in git, so a secret may be NAMED here but never written.
        // A lone `${VAR:}` is a name: the container resolves it, nothing sensitive is
        // committed, and the connector's block keeps the key that documents it exists.
        // Anything else on the line is the literal this rule exists to keep out of the repo.
        try (Stream<Path> files = Files.walk(CONFIG_ROOT)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index).replaceAll("#.*", "");
                    Matcher key = CREDENTIAL_KEY.matcher(line);
                    if (!key.find()) {
                        continue;
                    }
                    String value = key.group(1).strip();
                    assertThat(value.isEmpty() || PLACEHOLDER_ONLY.matcher(value).matches())
                            .as("%s:%d: a credential key may only carry a single ${...} "
                                    + "placeholder (quoted or not) -- these files are plaintext "
                                    + "in git, so a literal here is a secret in the repository: "
                                    + "%s", file, index + 1, lines.get(index).strip())
                            .isTrue();
                }
            }
        }
    }

    @Test
    void theLocalEnvironmentExists() throws IOException {
        // Guards the discovery itself: if the tree moves, every walking test above would
        // pass vacuously on an empty list.
        assertThat(instances()).extracting(Instance::env).contains("local");
        assertThat(instances()).extracting(Instance::app)
                .contains("ticks-tcp", "orders-kafka", "positions-jdbc", "events-hazelcast");
    }
}
