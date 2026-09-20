package com.demo.amps.connectors.amps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crankuptheamps.client.exception.ConnectionException;
import com.demo.amps.connectors.config.AmpsServerProperties;
import java.time.Duration;
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
}
