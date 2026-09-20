package com.demo.amps.connectors.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.config.AmpsSourceProperties;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.ConnectorsProperties;
import com.demo.amps.connectors.config.ControlProperties;
import com.demo.amps.connectors.config.SourceFormat;
import com.demo.amps.connectors.resource.FakeResource;
import com.demo.amps.connectors.resource.ResourceRegistry;
import com.demo.amps.connectors.runtime.ConnectorManager;
import com.demo.amps.connectors.source.FakeRecordSource;
import com.demo.amps.connectors.source.FakeSourceFactory;
import com.demo.amps.connectors.source.SourceRecord;
import com.demo.amps.connectors.source.SourceResolver;
import com.demo.amps.connectors.transform.TransformRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dispatcher with a fake control source: commands are JSON records the test emits, the
 * resources are fakes behind a real {@link ResourceRegistry}, and the alerts land in a list.
 */
class CommandDispatcherTest {

    private static final String APPLICATION = "instrument-enricher";

    private final List<Alert> alerts = Collections.synchronizedList(new ArrayList<>());
    private final FakeRecordSource source = new FakeRecordSource();
    private final List<ConnectorProperties> resolved = new ArrayList<>();
    private final FakeResource instruments = new FakeResource("instruments").reloadable();
    private final FakeResource rics = new FakeResource("rics");
    private final ResourceRegistry registry =
            new ResourceRegistry(List.of(instruments, rics), alerts::add);
    private final ConnectorManager connectors = new ConnectorManager(
            new ConnectorsProperties(), new TransformRegistry(Map.of()), null, null, null,
            registry, alerts::add);
    private final ControlProperties control = new ControlProperties();

    @BeforeEach
    void startTheResources() {
        registry.start();
        control.setEnabled(true);
        AmpsSourceProperties amps = new AmpsSourceProperties();
        amps.setTopic("connectors/control");
        control.getSource().setAmps(amps);
    }

    /** A factory that claims only the synthetic control connector, and remembers it. */
    private SourceResolver resolver() {
        return new SourceResolver(List.of(new FakeSourceFactory(source, connector -> {
            resolved.add(connector);
            return connector.getName().equals(APPLICATION + CommandDispatcher.CONNECTOR_SUFFIX);
        })));
    }

    private CommandDispatcher dispatcher(CommandHandler... extra) {
        return new CommandDispatcher(control, APPLICATION, resolver(),
                new CommandContext(APPLICATION, registry, connectors, alerts::add),
                List.of(extra));
    }

    private CommandDispatcher started(CommandHandler... extra) {
        CommandDispatcher dispatcher = dispatcher(extra);
        dispatcher.start();
        return dispatcher;
    }

    private static SourceRecord command(String json, AtomicInteger acks) {
        return SourceRecord.of(json).withAck(acks::incrementAndGet);
    }

    private List<Alert> alerts(String code) {
        return alerts.stream().filter(alert -> alert.code().equals(code)).toList();
    }

    @Test
    @DisplayName("disabled control starts nothing, and says so")
    void disabledControlStartsNothing() {
        control.setEnabled(false);
        CommandDispatcher dispatcher = dispatcher();
        dispatcher.start();
        assertThat(dispatcher.isRunning()).isFalse();
        assertThat(source.startCount()).isZero();
        assertThat(resolved).isEmpty();
        assertThat(dispatcher.status()).isEqualTo("control: disabled");
        dispatcher.stop();
        assertThat(source.closeCount()).isZero();
    }

    @Test
    @DisplayName("the control source is resolved as the synthetic <application>-control connector")
    void resolvesTheSyntheticConnector() {
        CommandDispatcher dispatcher = started();
        assertThat(dispatcher.isRunning()).isTrue();
        assertThat(dispatcher.isConnected()).isTrue();
        assertThat(source.startCount()).isEqualTo(1);
        assertThat(resolved).singleElement().satisfies(connector -> {
            assertThat(connector.getName()).isEqualTo("instrument-enricher-control");
            assertThat(connector.getFormat()).isEqualTo(SourceFormat.JSON);
            assertThat(connector.getSource()).isSameAs(control.getSource());
        });
        assertThat(dispatcher.connector()).isSameAs(resolved.get(0));
        assertThat(dispatcher.target()).isEqualTo(APPLICATION);
        assertThat(dispatcher.commands()).containsExactlyInAnyOrder("reload", "status");
        assertThat(dispatcher.status())
                .startsWith("control: amps:connectors/control target=instrument-enricher LISTENING")
                .endsWith("received=0 succeeded=0 failed=0 ignored=0");
        assertThat(dispatcher.getPhase()).isEqualTo(Integer.MAX_VALUE - 500);

        dispatcher.stop();
        assertThat(dispatcher.isRunning()).isFalse();
        assertThat(source.closeCount()).isEqualTo(1);
        assertThat(dispatcher.status()).contains("STOPPED");
    }

    @Test
    @DisplayName("nothing on the classpath reads the control source: a startup failure, not a retry")
    void failsToStartWhenNoFactoryClaimsTheControlSource() {
        CommandDispatcher dispatcher = new CommandDispatcher(control, APPLICATION,
                new SourceResolver(List.of()),
                new CommandContext(APPLICATION, registry, connectors, alerts::add), List.of());
        assertThatThrownBy(dispatcher::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("instrument-enricher-control")
                .hasMessageContaining("source-amps");
        assertThat(dispatcher.isRunning()).isFalse();
    }

    @Test
    @DisplayName("reload with a target reloads that resource through the registry")
    void reloadsAResourceByName() {
        CommandDispatcher dispatcher = started();
        AtomicInteger acks = new AtomicInteger();
        source.emit(command("{\"command\":\"reload\",\"target\":\"instruments\",\"requestId\":\"r-1\"}",
                acks));
        assertThat(instruments.reloadCount()).isEqualTo(1);
        assertThat(acks.get()).isEqualTo(1);
        assertThat(dispatcher.received()).isEqualTo(1);
        assertThat(dispatcher.succeeded()).isEqualTo(1);
        assertThat(dispatcher.failed()).isZero();
        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("reload with target all, or none, reloads every reloadable resource")
    void reloadsEverythingForAllOrNoTarget() {
        CommandDispatcher dispatcher = started();
        source.emit(SourceRecord.of("{\"command\":\"reload\",\"target\":\"all\"}"));
        source.emit(SourceRecord.of("{\"command\":\"reload\"}"));
        source.emit(SourceRecord.of("{\"command\":\"reload\",\"target\":\" \"}"));
        assertThat(instruments.reloadCount()).isEqualTo(3);
        assertThat(rics.reloadCount()).isZero();
        assertThat(dispatcher.succeeded()).isEqualTo(3);
        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("a reload that cannot happen is COMMAND_FAILED, with the registry's reason")
    void aFailedReloadIsCommandFailed() {
        CommandDispatcher dispatcher = started();
        AtomicInteger acks = new AtomicInteger();
        source.emit(command("{\"command\":\"reload\",\"target\":\"typo\",\"requestId\":\"r-2\"}", acks));
        source.emit(command("{\"command\":\"reload\",\"target\":\"rics\"}", acks));
        instruments.failReloadWith(new IllegalStateException("database is away"));
        source.emit(command("{\"command\":\"reload\",\"target\":\"instruments\"}", acks));
        source.emit(command("{\"command\":\"reload\",\"target\":\"all\"}", acks));

        assertThat(acks.get()).isEqualTo(4);
        assertThat(dispatcher.failed()).isEqualTo(4);
        assertThat(dispatcher.succeeded()).isZero();
        List<Alert> failed = alerts(CommandDispatcher.COMMAND_FAILED);
        assertThat(failed).hasSize(4);
        assertThat(failed).allSatisfy(alert -> {
            assertThat(alert.severity()).isEqualTo(Alert.Severity.WARN);
            assertThat(alert.connector()).isNull();
            assertThat(alert.details()).containsKeys("command", "target", "requestId", "error");
            assertThat(alert.details()).containsEntry("command", "reload");
        });
        assertThat(failed.get(0).details()).containsEntry("target", "typo")
                .containsEntry("requestId", "r-2");
        assertThat(failed.get(0).message()).contains("no resource named 'typo'")
                .contains("instruments");
        assertThat(failed.get(1).message()).contains("not reloadable");
        assertThat(failed.get(2).message()).contains("database is away");
        assertThat(failed.get(3).message()).contains("database is away");
        assertThat(failed.get(3).details()).containsEntry("requestId", null);
    }

    @Test
    @DisplayName("status logs the connectors and the resources, and answers with a STATUS alert")
    void statusRaisesAnInfoAlert() {
        CommandDispatcher dispatcher = started();
        source.emit(SourceRecord.of("{\"command\":\"status\",\"requestId\":\"r-9\"}"));

        assertThat(dispatcher.succeeded()).isEqualTo(1);
        Alert status = alerts(StatusCommand.STATUS).get(0);
        assertThat(status.severity()).isEqualTo(Alert.Severity.INFO);
        assertThat(status.message()).contains("0 connector(s), 2 resource(s)").contains("r-9");
        assertThat(status.details().keySet()).containsExactly("connectors", "resources");
        assertThat(status.details().get("connectors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        List<String> resources = (List<String>) status.details().get("resources");
        assertThat(resources).hasSize(2);
        assertThat(resources.get(0)).startsWith("instruments AVAILABLE");
        assertThat(resources.get(1)).startsWith("rics AVAILABLE");
    }

    @Test
    @DisplayName("a command nobody handles is COMMAND_UNKNOWN, naming what is handled")
    void anUnknownCommandIsAlerted() {
        CommandDispatcher dispatcher = started();
        AtomicInteger acks = new AtomicInteger();
        source.emit(command("{\"command\":\"pause\",\"target\":\"orders\",\"requestId\":\"r-3\"}", acks));
        assertThat(acks.get()).isEqualTo(1);
        assertThat(dispatcher.failed()).isEqualTo(1);
        Alert unknown = alerts(CommandDispatcher.COMMAND_UNKNOWN).get(0);
        assertThat(unknown.severity()).isEqualTo(Alert.Severity.WARN);
        assertThat(unknown.message()).contains("'pause'").contains("reload").contains("status");
        assertThat(unknown.details()).containsEntry("command", "pause")
                .containsEntry("target", "orders").containsEntry("requestId", "r-3")
                .doesNotContainKey("error");
    }

    @Test
    @DisplayName("a payload that is not a command is COMMAND_INVALID, quoted, and acknowledged")
    void anInvalidPayloadIsAlerted() {
        CommandDispatcher dispatcher = started();
        AtomicInteger acks = new AtomicInteger();
        source.emit(command("this is not json", acks));
        source.emit(command("{\"target\":\"instruments\"}", acks));
        assertThat(acks.get()).isEqualTo(2);
        assertThat(dispatcher.failed()).isEqualTo(2);
        List<Alert> invalid = alerts(CommandDispatcher.COMMAND_INVALID);
        assertThat(invalid).hasSize(2);
        assertThat(invalid.get(0).details()).containsEntry("payload", "this is not json")
                .containsKey("error");
        assertThat(invalid.get(1).message()).contains("\"command\"");
    }

    @Test
    @DisplayName("to: absent, all, the target, or an accepted group is for this instance; anything else is not")
    void addressesCommandsByTo() {
        control.setAcceptTargets(List.of("all", "enrichers"));
        CommandDispatcher dispatcher = started();
        AtomicInteger acks = new AtomicInteger();
        source.emit(command("{\"command\":\"reload\",\"target\":\"instruments\"}", acks));
        source.emit(command("{\"command\":\"reload\",\"target\":\"instruments\",\"to\":\"all\"}", acks));
        source.emit(command("{\"command\":\"reload\",\"target\":\"instruments\",\"to\":\"instrument-enricher\"}",
                acks));
        source.emit(command("{\"command\":\"reload\",\"target\":\"instruments\",\"to\":\"enrichers\"}",
                acks));
        source.emit(command("{\"command\":\"reload\",\"target\":\"instruments\",\"to\":\"other-app\"}",
                acks));
        source.emit(command("{\"command\":\"nonsense\",\"to\":\"other-app\"}", acks));

        assertThat(instruments.reloadCount()).isEqualTo(4);
        assertThat(acks.get()).isEqualTo(6);
        assertThat(dispatcher.received()).isEqualTo(6);
        assertThat(dispatcher.succeeded()).isEqualTo(4);
        assertThat(dispatcher.ignored()).isEqualTo(2);
        assertThat(dispatcher.failed()).isZero();
        // Ignored is ignored: not even an unknown command for someone else is an alert.
        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("an explicit target replaces the application name in the address check")
    void anExplicitTargetIsWhatToIsComparedWith() {
        control.setTarget(" enricher-2 ");
        control.setAcceptTargets(List.of());
        CommandDispatcher dispatcher = started();
        assertThat(dispatcher.target()).isEqualTo("enricher-2");
        source.emit(SourceRecord.of("{\"command\":\"reload\",\"target\":\"instruments\",\"to\":\"enricher-2\"}"));
        source.emit(SourceRecord.of("{\"command\":\"reload\",\"target\":\"instruments\",\"to\":\"" + APPLICATION + "\"}"));
        // With no accept-targets at all, `all` is still everyone.
        source.emit(SourceRecord.of("{\"command\":\"reload\",\"target\":\"instruments\",\"to\":\"all\"}"));
        assertThat(instruments.reloadCount()).isEqualTo(2);
        assertThat(dispatcher.ignored()).isEqualTo(1);
        // The synthetic connector is still named after the application, not the target.
        assertThat(dispatcher.connector().getName()).isEqualTo("instrument-enricher-control");
    }

    @Test
    @DisplayName("a removal carries no command and is ignored")
    void ignoresDeleteRecords() {
        CommandDispatcher dispatcher = started();
        AtomicInteger acks = new AtomicInteger();
        source.emit(SourceRecord.delete("", "k").withAck(acks::incrementAndGet));
        assertThat(dispatcher.ignored()).isEqualTo(1);
        assertThat(acks.get()).isEqualTo(1);
        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("an application handler is dispatched by its name, and may replace a built-in")
    void extraHandlersAreDispatchedAndMayOverrideBuiltIns() {
        List<ControlCommand> seen = new ArrayList<>();
        CommandHandler flush = new CommandHandler() {
            @Override
            public String command() {
                return "flush";
            }

            @Override
            public void handle(ControlCommand command, CommandContext context) {
                assertThat(context.application()).isEqualTo(APPLICATION);
                assertThat(context.resources()).isSameAs(registry);
                assertThat(context.connectors()).isSameAs(connectors);
                seen.add(command);
            }
        };
        CommandHandler reload = new CommandHandler() {
            @Override
            public String command() {
                return "reload";
            }

            @Override
            public void handle(ControlCommand command, CommandContext context) {
                seen.add(command);
            }
        };
        CommandDispatcher dispatcher = started(flush, reload);
        assertThat(dispatcher.commands()).containsExactlyInAnyOrder("reload", "status", "flush");

        source.emit(SourceRecord.of("{\"command\":\"flush\",\"args\":{\"cache\":\"rics\"}}"));
        source.emit(SourceRecord.of("{\"command\":\"reload\",\"target\":\"instruments\"}"));
        assertThat(seen).extracting(ControlCommand::command).containsExactly("flush", "reload");
        assertThat(seen.get(0).args()).containsEntry("cache", "rics");
        // The built-in reload was replaced, so the registry was never asked.
        assertThat(instruments.reloadCount()).isZero();
        assertThat(dispatcher.succeeded()).isEqualTo(2);
    }

    @Test
    @DisplayName("a handler that throws is COMMAND_FAILED, and the next command still runs")
    void aThrowingHandlerIsCommandFailed() {
        CommandHandler broken = new CommandHandler() {
            @Override
            public String command() {
                return "broken";
            }

            @Override
            public void handle(ControlCommand command, CommandContext context) throws Exception {
                throw new Exception("boom");
            }
        };
        CommandDispatcher dispatcher = started(broken);
        AtomicInteger acks = new AtomicInteger();
        source.emit(command("{\"command\":\"broken\",\"requestId\":\"r-4\"}", acks));
        source.emit(command("{\"command\":\"reload\",\"target\":\"instruments\"}", acks));

        assertThat(acks.get()).isEqualTo(2);
        assertThat(dispatcher.failed()).isEqualTo(1);
        assertThat(dispatcher.succeeded()).isEqualTo(1);
        Alert failed = alerts(CommandDispatcher.COMMAND_FAILED).get(0);
        assertThat(failed.message()).isEqualTo("broken requestId=r-4 failed: boom");
        assertThat(failed.details()).containsEntry("command", "broken")
                .containsEntry("target", null).containsEntry("requestId", "r-4")
                .containsEntry("error", "java.lang.Exception: boom");
    }

    @Test
    @DisplayName("a handler with no command name is refused at construction")
    void refusesANamelessHandler() {
        CommandHandler nameless = new CommandHandler() {
            @Override
            public String command() {
                return " ";
            }

            @Override
            public void handle(ControlCommand command, CommandContext context) {
            }
        };
        assertThatThrownBy(() -> dispatcher(nameless))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no command name");
    }

    @Test
    @DisplayName("every record is acknowledged, whatever became of it")
    void acknowledgesEveryRecord() {
        CommandDispatcher dispatcher = started();
        AtomicInteger acks = new AtomicInteger();
        source.emit(command("{\"command\":\"reload\",\"target\":\"instruments\"}", acks));
        source.emit(command("{\"command\":\"reload\",\"target\":\"typo\"}", acks));
        source.emit(command("{\"command\":\"pause\"}", acks));
        source.emit(command("garbage", acks));
        source.emit(command("{\"command\":\"reload\",\"to\":\"someone-else\"}", acks));
        assertThat(acks.get()).isEqualTo(5);
        assertThat(dispatcher.status()).endsWith("received=5 succeeded=1 failed=3 ignored=1");
    }
}
