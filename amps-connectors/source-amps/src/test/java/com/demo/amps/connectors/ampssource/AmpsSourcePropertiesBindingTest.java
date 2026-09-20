package com.demo.amps.connectors.ampssource;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.config.AmpsSourceProperties;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.ConnectorValidator;
import com.demo.amps.connectors.config.ConnectorsProperties;
import com.demo.amps.connectors.config.SourceFormat;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The {@code source.amps} block as an operator writes it: bound through Boot's {@link Binder}
 * from relaxed keys, counted as the connector's one transport, labelled for the logs, and
 * checked by the bean validation the application runs at startup.
 */
class AmpsSourcePropertiesBindingTest {

    private static ConnectorsProperties bind(Map<String, String> properties) {
        Binder binder = new Binder(new MapConfigurationPropertySource(properties));
        return binder.bind("amps-connectors", Bindable.of(ConnectorsProperties.class)).get();
    }

    private static Map<String, String> bridge() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("amps-connectors.connectors[0].name", "orders-bridge");
        properties.put("amps-connectors.connectors[0].format", "FIX");
        properties.put("amps-connectors.connectors[0].source.amps.topic", "sow/connectors/orders");
        properties.put("amps-connectors.connectors[0].amps.topic", "sow/connectors/orders-copy");
        properties.put("amps-connectors.connectors[0].amps.message-type", "fix");
        return properties;
    }

    @Test
    @DisplayName("the minimal block is a topic; everything else has a default")
    void bindsTheMinimalBlock() {
        ConnectorProperties connector = bind(bridge()).getConnectors().get(0);

        AmpsSourceProperties amps = connector.getSource().getAmps();
        assertThat(amps).isNotNull();
        assertThat(amps.getTopic()).isEqualTo("sow/connectors/orders");
        assertThat(amps.getMode()).isEqualTo(AmpsSourceProperties.Mode.SUBSCRIBE);
        assertThat(amps.getBookmark()).isEqualTo(AmpsSourceProperties.Bookmark.MOST_RECENT);
        assertThat(amps.getFilter()).isNull();
        assertThat(amps.getOptions()).isNull();
        assertThat(amps.getServer()).isNull();
        assertThat(amps.getTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(amps.getReconnectDelay()).isEqualTo(Duration.ofSeconds(5));
        assertThat(connector.getFormat()).isEqualTo(SourceFormat.FIX);
    }

    @Test
    @DisplayName("every field binds from its relaxed key, the server override included")
    void bindsEveryField() {
        Map<String, String> properties = bridge();
        String prefix = "amps-connectors.connectors[0].source.amps.";
        properties.put(prefix + "mode", "sow_and_subscribe");
        properties.put(prefix + "bookmark", "epoch");
        properties.put(prefix + "filter", "/35 = 'D'");
        properties.put(prefix + "options", "conflation=250ms");
        properties.put(prefix + "server.host", "amps-2");
        properties.put(prefix + "server.port", "9107");
        properties.put(prefix + "server.transport", "tcps");
        properties.put(prefix + "timeout", "3s");
        properties.put(prefix + "reconnect-delay", "750ms");

        AmpsSourceProperties amps = bind(properties).getConnectors().get(0).getSource().getAmps();

        assertThat(amps.getMode()).isEqualTo(AmpsSourceProperties.Mode.SOW_AND_SUBSCRIBE);
        assertThat(amps.getBookmark()).isEqualTo(AmpsSourceProperties.Bookmark.EPOCH);
        assertThat(amps.getFilter()).isEqualTo("/35 = 'D'");
        assertThat(amps.getOptions()).isEqualTo("conflation=250ms");
        assertThat(amps.getServer().getHost()).isEqualTo("amps-2");
        assertThat(amps.getServer().getPort()).isEqualTo(9107);
        assertThat(amps.getServer().getTransport()).isEqualTo("tcps");
        assertThat(amps.getServer().uri("fix")).isEqualTo("tcps://amps-2:9107/amps/fix");
        assertThat(amps.getTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(amps.getReconnectDelay()).isEqualTo(Duration.ofMillis(750));
    }

    @Test
    @DisplayName("naming only the server's host keeps the port and transport of a default AMPS")
    void serverOverrideDefaultsThePortAndTransport() {
        Map<String, String> properties = bridge();
        properties.put("amps-connectors.connectors[0].source.amps.server.host", "amps-2");

        AmpsSourceProperties.Endpoint server =
                bind(properties).getConnectors().get(0).getSource().getAmps().getServer();

        assertThat(server.getPort()).isEqualTo(9007);
        assertThat(server.getTransport()).isEqualTo("tcp");
        assertThat(server.uri("json")).isEqualTo("tcp://amps-2:9007/amps/json");
    }

    @Test
    @DisplayName("the block counts as the connector's one transport, and labels it for the logs")
    void isATransportBlock() {
        ConnectorsProperties properties = bind(bridge());
        ConnectorProperties connector = properties.getConnectors().get(0);

        assertThat(connector.getSource().configuredBlocks()).containsExactly("amps");
        assertThat(connector.getSource().describe()).isEqualTo("amps:sow/connectors/orders");
        assertThat(connector.toString()).contains("amps:sow/connectors/orders");
        // One block is what the validator asks of a source, so an AMPS-only connector is a
        // complete one as far as the transport rule is concerned.
        assertThat(ConnectorValidator.validate(properties)).isEmpty();
    }

    @Test
    @DisplayName("an AMPS block beside another transport is two feeds, which the validator refuses")
    void twoBlocksAreRefused() {
        Map<String, String> properties = bridge();
        properties.put("amps-connectors.connectors[0].source.tcp.port", "5001");

        ConnectorsProperties bound = bind(properties);

        assertThat(bound.getConnectors().get(0).getSource().configuredBlocks())
                .containsExactly("tcp", "amps");
        assertThat(ConnectorValidator.validate(bound)).isNotEmpty();
    }

    @Test
    @DisplayName("bean validation requires the topic and a sane server port")
    void beanValidationGuardsTheBlock() {
        Map<String, String> properties = bridge();
        properties.put("amps-connectors.connectors[0].source.amps.topic", " ");
        properties.put("amps-connectors.connectors[0].source.amps.server.host", "amps-2");
        properties.put("amps-connectors.connectors[0].source.amps.server.port", "0");

        ConnectorsProperties bound = bind(properties);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Set<ConstraintViolation<ConnectorsProperties>> violations =
                    factory.getValidator().validate(bound);
            assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                    .containsExactlyInAnyOrder(
                            "connectors[0].source.amps.topic",
                            "connectors[0].source.amps.server.port");
        }
    }
}
