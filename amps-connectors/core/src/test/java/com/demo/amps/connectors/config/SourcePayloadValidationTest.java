package com.demo.amps.connectors.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.TestConnectors;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The validator's rules for the settings that type a source's payloads: Kafka's
 * {@code payload-type}, and Hazelcast's {@code serialization-factories} and
 * {@code typed-values}.
 *
 * <p>Shape only, on purpose. Whether a codec is registered for a pair or a bean exists under
 * a name is the application's business, and the pipeline and the source factory report it
 * when they are built; what belongs here is every spelling that could never work whatever
 * the application registers.
 */
class SourcePayloadValidationTest {

    @Nested
    @DisplayName("source.kafka.payload-type")
    class KafkaPayloadType {

        private ConnectorProperties kafka(int factoryId, int classId) {
            ConnectorProperties connector = TestConnectors.kafka("orders", "orders", "connectors");
            connector.getSource().getKafka().getPayloadType().setFactoryId(factoryId);
            connector.getSource().getKafka().getPayloadType().setClassId(classId);
            return connector;
        }

        @Test
        @DisplayName("unset, and set as a whole pair, are both fine")
        void acceptsUnsetAndWhole() {
            assertThat(ConnectorValidator.validate(kafka(0, 0))).isEmpty();
            assertThat(ConnectorValidator.validate(kafka(200, 3))).isEmpty();
        }

        @Test
        @DisplayName("a half-set pair names nothing, so it is refused either way round")
        void refusesAHalfSetPair() {
            assertThat(ConnectorValidator.validate(kafka(0, 3)))
                    .singleElement().asString()
                    .contains("source.kafka.payload-type 0/3").contains("names nothing");
            assertThat(ConnectorValidator.validate(kafka(200, 0)))
                    .singleElement().asString()
                    .contains("source.kafka.payload-type 200/0");
        }

        @Test
        @DisplayName("a negative id is refused")
        void refusesANegativeId() {
            assertThat(ConnectorValidator.validate(kafka(-1, 3)))
                    .singleElement().asString()
                    .contains("source.kafka.payload-type -1/3").contains("non-negative");
        }

        @Test
        @DisplayName("the source's type is judged apart from the target's: both can be wrong at once")
        void reportsTheSourceAndTheTargetSeparately() {
            ConnectorProperties connector = kafka(200, 0);
            connector.getAmps().getPayloadType().setClassId(5);
            assertThat(ConnectorValidator.validate(connector))
                    .anySatisfy(error -> assertThat(error).contains("source.kafka.payload-type 200/0"))
                    .anySatisfy(error -> assertThat(error).contains("amps.payload-type 0/5"));
        }
    }

    @Nested
    @DisplayName("source.hazelcast.serialization-factories and typed-values")
    class HazelcastTypedValues {

        private ConnectorProperties map() {
            return TestConnectors.hazelcastMap("positions", "positions");
        }

        private ConnectorProperties withFactories(
                ConnectorProperties connector, Map<Integer, String> factories) {
            connector.getSource().getHazelcast()
                    .setSerializationFactories(new LinkedHashMap<>(factories));
            return connector;
        }

        @Test
        @DisplayName("the defaults -- no factories, JSON -- validate, as does OBJECT with a factory")
        void acceptsTheDefaultsAndAWholeConfiguration() {
            assertThat(ConnectorValidator.validate(map())).isEmpty();

            ConnectorProperties typed = withFactories(map(), Map.of(1000, "positionFactory"));
            typed.getSource().getHazelcast()
                    .setTypedValues(HazelcastSourceProperties.TypedValues.OBJECT);
            assertThat(ConnectorValidator.validate(typed)).isEmpty();

            // A topic can carry typed values just as a map can.
            ConnectorProperties topic = withFactories(
                    TestConnectors.hazelcast("events", "events"), Map.of(1000, "eventFactory"));
            topic.getSource().getHazelcast()
                    .setTypedValues(HazelcastSourceProperties.TypedValues.OBJECT);
            assertThat(ConnectorValidator.validate(topic)).isEmpty();
        }

        @Test
        @DisplayName("a factory id of zero or less can never spell a payload type")
        void refusesANonPositiveFactoryId() {
            assertThat(ConnectorValidator.validate(withFactories(map(), Map.of(0, "factory"))))
                    .singleElement().asString()
                    .contains("serialization-factories names factory id 0").contains("positive");
            assertThat(ConnectorValidator.validate(withFactories(map(), Map.of(-7, "factory"))))
                    .singleElement().asString()
                    .contains("serialization-factories names factory id -7");
        }

        @Test
        @DisplayName("a blank bean name is refused, and says which factory id it was under")
        void refusesABlankBeanName() {
            assertThat(ConnectorValidator.validate(withFactories(map(), Map.of(1000, "  "))))
                    .singleElement().asString()
                    .contains("serialization-factories[1000]").contains("blank bean");
        }

        @Test
        @DisplayName("typed-values: OBJECT with no factory is a mode that could never engage")
        void refusesObjectWithoutAFactory() {
            ConnectorProperties connector = map();
            connector.getSource().getHazelcast()
                    .setTypedValues(HazelcastSourceProperties.TypedValues.OBJECT);
            assertThat(ConnectorValidator.validate(connector))
                    .singleElement().asString()
                    .contains("typed-values: OBJECT needs source.hazelcast.serialization-factories");
        }

        @Test
        @DisplayName("typed-values has to be one of the two modes")
        void refusesANullMode() {
            ConnectorProperties connector = map();
            connector.getSource().getHazelcast().setTypedValues(null);
            assertThat(ConnectorValidator.validate(connector))
                    .singleElement().asString().contains("typed-values must be one of JSON/OBJECT");
        }

        @Test
        @DisplayName("the factories are registered on the client, so JSON mode may name them too")
        void factoriesWithoutObjectModeAreFine() {
            // A cache of IdentifiedDataSerializable values rendered as JSON still needs the
            // client to deserialize them first.
            assertThat(ConnectorValidator.validate(
                    withFactories(map(), Map.of(1000, "positionFactory")))).isEmpty();
        }
    }
}
