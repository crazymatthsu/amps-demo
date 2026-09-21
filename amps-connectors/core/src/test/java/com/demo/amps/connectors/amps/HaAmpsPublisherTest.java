package com.demo.amps.connectors.amps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crankuptheamps.client.exception.ConnectionException;
import com.demo.amps.connectors.config.AmpsServerProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one thing about the real client that can be asserted without an AMPS: that a server
 * which is not there makes {@code connect()} throw rather than block. Port 1 is refused by
 * the kernel at once, so this never waits on a network.
 */
class HaAmpsPublisherTest {

    @Test
    @DisplayName("the first connect gives up after about logon-timeout and throws; it does not block forever")
    void connectGivesUpAgainstARefusedPort() {
        AmpsServerProperties server = new AmpsServerProperties();
        server.setPort(1);
        server.setLogonTimeout(Duration.ofMillis(200));
        server.setReconnectDelay(Duration.ofMillis(50));
        HaAmpsPublisher publisher = new HaAmpsPublisher(server, "nobody", "json");

        long started = System.nanoTime();
        assertThatThrownBy(publisher::connect)
                .isInstanceOf(ConnectionException.class)
                // The client's own "gave up" message, carrying the last real failure --
                // which the stock server chooser would have left blank.
                .hasMessageContaining("no more reconnect attempts")
                .hasMessageContaining("ConnectionRefusedException");
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(Duration.ofSeconds(10));
        assertThat(publisher.isConnected()).isFalse();
        assertThat(publisher.flush(Duration.ofMillis(1))).isFalse();
        assertThatThrownBy(() -> publisher.publish("t", "{}", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not connected to tcp://localhost:1/amps/json");
        publisher.close();
    }

    /**
     * A listener set before {@code connect()} is wired into the client as it is built --
     * the store wrapped, the failed-write handler installed -- which is as far as a refused
     * port lets this go: the connect still fails the same way, with the listener in place,
     * and never calls it, because nothing was persisted and nothing was refused.
     */
    @Test
    @DisplayName("a listener set before connect is accepted with every kind of store, and a refused port calls it never")
    void acceptsAListenerBeforeConnectWithEveryStore() {
        for (AmpsServerProperties.PublishStore kind : AmpsServerProperties.PublishStore.values()) {
            AmpsServerProperties server = new AmpsServerProperties();
            server.setPort(1);
            server.setLogonTimeout(Duration.ofMillis(200));
            server.setReconnectDelay(Duration.ofMillis(50));
            server.setPublishStore(kind);
            server.setPublishStoreDir("build/tmp/ha-amps-publisher-test");
            HaAmpsPublisher publisher = new HaAmpsPublisher(server, "listened-" + kind, "json");
            List<String> heard = new ArrayList<>();
            publisher.setPublishListener(new PublishListener() {
                @Override
                public void persistedUpTo(long seqno) {
                    heard.add("persisted " + seqno);
                }

                @Override
                public void failedWrite(long seqno, int reason) {
                    heard.add("failed " + seqno);
                }
            });

            assertThatThrownBy(publisher::connect).as(kind.name())
                    .isInstanceOf(ConnectionException.class)
                    .hasMessageContaining("no more reconnect attempts");

            assertThat(heard).as(kind.name()).isEmpty();
            assertThat(publisher.isConnected()).isFalse();
            publisher.close();
        }
    }

    @Test
    @DisplayName("before connect, the listener can be set, replaced and cleared")
    void acceptsSettingReplacingAndClearingTheListenerBeforeConnect() {
        // The other half of the contract -- a listener set AFTER connect is refused -- needs
        // a connected client, i.e. a server; PersistedAckIT covers the wiring end to end.
        AmpsServerProperties server = new AmpsServerProperties();
        HaAmpsPublisher publisher = new HaAmpsPublisher(server, "nobody", "json");
        PublishListener listener = new PublishListener() {
            @Override
            public void persistedUpTo(long seqno) {
            }

            @Override
            public void failedWrite(long seqno, int reason) {
            }
        };
        publisher.setPublishListener(listener);
        publisher.setPublishListener(listener);
        publisher.setPublishListener(null);
        publisher.close();
    }
}
