package com.demo.amps.connectors.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.alert.Alerts;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResourceRegistryTest {

    private final List<Alert> raised = new ArrayList<>();
    private final Alerts alerts = raised::add;

    /** A fake that writes its lifecycle into a shared journal, for the order assertions. */
    private static final class Journaled extends FakeResource {

        private final List<String> journal;

        Journaled(String name, List<String> journal) {
            super(name);
            this.journal = journal;
        }

        @Override
        public void start() throws Exception {
            journal.add("start " + name());
            super.start();
        }

        @Override
        public void stop() {
            journal.add("stop " + name());
            super.stop();
        }
    }

    @Test
    @DisplayName("resources start in registration order and stop in reverse")
    void startsInOrderAndStopsInReverse() {
        List<String> journal = new ArrayList<>();
        ResourceRegistry registry = new ResourceRegistry(List.of(
                new Journaled("a", journal), new Journaled("b", journal),
                new Journaled("c", journal)), alerts);

        assertThat(registry.isRunning()).isFalse();
        registry.start();
        registry.start();
        assertThat(registry.isRunning()).isTrue();
        registry.stop();
        registry.stop();

        assertThat(journal).containsExactly(
                "start a", "start b", "start c", "stop c", "stop b", "stop a");
        assertThat(registry.isRunning()).isFalse();
        assertThat(registry.getPhase()).isEqualTo(Integer.MAX_VALUE - 2_000);
        assertThat(raised).isEmpty();
    }

    @Test
    @DisplayName("two resources with one name are refused at construction, not resolved by luck")
    void refusesDuplicateNames() {
        assertThatThrownBy(() -> new ResourceRegistry(List.of(
                new FakeResource("instruments"), new FakeResource("instruments")), alerts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate resource name 'instruments'");
        assertThatThrownBy(() -> new ResourceRegistry(List.of(new FakeResource(" ")), alerts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no name");
    }

    @Test
    @DisplayName("lookup answers by name and type, and says what exists when it cannot")
    void looksUpByNameAndType() {
        FakeResource instruments = new FakeResource("instruments");
        ResourceRegistry registry = new ResourceRegistry(
                List.of(instruments, new FakeResource("rics")), alerts);

        assertThat(registry.lookup("instruments", FakeResource.class)).isSameAs(instruments);
        assertThat(registry.lookup("instruments", AppResource.class)).isSameAs(instruments);
        assertThat(registry.find("rics")).isPresent();
        assertThat(registry.find("nope")).isEmpty();
        assertThat(registry.names()).containsExactly("instruments", "rics");
        assertThat(registry.resources()).containsExactly(instruments, registry.find("rics").get());

        assertThatThrownBy(() -> registry.lookup("instrument", FakeResource.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no resource named 'instrument'")
                .hasMessageContaining("[instruments, rics]");
        assertThatThrownBy(() -> registry.lookup("instruments", String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is a " + FakeResource.class.getName())
                .hasMessageContaining("not a java.lang.String");
        assertThatThrownBy(() -> new ResourceRegistry(List.of(), alerts).lookup("x", Object.class))
                .hasMessageContaining("none are registered");
    }

    @Test
    @DisplayName("a resource that fails to start is logged and alerted, and the others still start")
    void aFailedStartDoesNotStopTheOthers() {
        FakeResource broken = new FakeResource("broken")
                .failStartWith(new IOException("connection refused"));
        FakeResource fine = new FakeResource("fine");
        ResourceRegistry registry = new ResourceRegistry(List.of(broken, fine), alerts);

        registry.start();

        assertThat(fine.isAvailable()).isTrue();
        assertThat(broken.isAvailable()).isFalse();
        assertThat(registry.isRunning()).isTrue();
        assertThat(raised).hasSize(1);
        Alert alert = raised.get(0);
        assertThat(alert.code()).isEqualTo(ResourceRegistry.RESOURCE_START_FAILED);
        assertThat(alert.severity()).isEqualTo(Alert.Severity.ERROR);
        assertThat(alert.connector()).isNull();
        assertThat(alert.details())
                .containsEntry("resource", "broken")
                .containsEntry("error", "java.io.IOException: connection refused");
        assertThat(registry.status())
                .contains("broken UNAVAILABLE")
                .contains("start-failed=\"java.io.IOException: connection refused\"")
                .contains("fine AVAILABLE");

        // Stop still reaches the one that never started: stop is idempotent by contract.
        registry.stop();
        assertThat(broken.stopCount()).isEqualTo(1);
        assertThat(fine.stopCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("reload: unknown is an argument error, non-reloadable is unsupported, a throw is wrapped")
    void reloadsOneResource() {
        FakeResource table = new FakeResource("table").reloadable();
        FakeResource client = new FakeResource("client");
        FakeResource failing = new FakeResource("failing").reloadable()
                .failReloadWith(new IOException("query timed out"));
        ResourceRegistry registry = new ResourceRegistry(List.of(table, client, failing), alerts);
        registry.start();

        registry.reload("table");
        assertThat(table.reloadCount()).isEqualTo(1);

        assertThatThrownBy(() -> registry.reload("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no resource named 'nope'");
        assertThatThrownBy(() -> registry.reload("client"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("'client' is not reloadable");
        assertThat(client.reloadCount()).isZero();
        assertThatThrownBy(() -> registry.reload("failing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resource 'failing' failed to reload")
                .hasMessageContaining("query timed out")
                .hasCauseInstanceOf(IOException.class);
        // A failed reload changes nothing the registry can see: what was loaded stays.
        assertThat(failing.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("reload all reloads every reloadable resource, then reports the ones that failed")
    void reloadsEverything() {
        FakeResource first = new FakeResource("first").reloadable();
        FakeResource client = new FakeResource("client");
        FakeResource failing = new FakeResource("failing").reloadable()
                .failReloadWith(new IOException("query timed out"));
        FakeResource last = new FakeResource("last").reloadable();
        ResourceRegistry registry = new ResourceRegistry(
                List.of(first, client, failing, last), alerts);

        assertThatThrownBy(registry::reloadAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resource 'failing' failed to reload");
        // The failure did not stop the reload of the ones after it.
        assertThat(first.reloadCount()).isEqualTo(1);
        assertThat(last.reloadCount()).isEqualTo(1);
        assertThat(client.reloadCount()).isZero();

        failing.failReloadWith(null);
        assertThat(registry.reloadAll()).containsExactly("first", "failing", "last");
        assertThat(new ResourceRegistry(List.of(client), alerts).reloadAll()).isEmpty();
    }

    @Test
    @DisplayName("the status is one line per resource, in order, from the resource itself")
    void reportsStatus() {
        FakeResource a = new FakeResource("a");
        AppResource plain = new AppResource() {
            @Override
            public String name() {
                return "plain";
            }

            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
        ResourceRegistry registry = new ResourceRegistry(List.of(a, plain), alerts);
        assertThat(new ResourceRegistry(List.of(), alerts).status()).isEmpty();
        assertThat(registry.status()).isEqualTo(System.lineSeparator()
                + "  a UNAVAILABLE starts=0 reloads=0" + System.lineSeparator()
                + "  plain AVAILABLE");

        registry.start();
        assertThat(registry.status()).contains("a AVAILABLE starts=1");
        // The interface's defaults: not reloadable, and a status of name plus availability.
        assertThatThrownBy(plain::reload).isInstanceOf(UnsupportedOperationException.class);
        assertThat(plain.isReloadable()).isFalse();
    }
}
