package com.demo.amps.connectors.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.TestConnectors;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectorValidatorTest {

    private static ConnectorsProperties properties(ConnectorProperties... connectors) {
        ConnectorsProperties properties = new ConnectorsProperties();
        properties.setConnectors(List.of(connectors));
        return properties;
    }

    @Test
    void acceptsAWellFormedConnector() {
        assertThat(ConnectorValidator.validate(TestConnectors.tcp("ticks", 5001))).isEmpty();
    }

    @Test
    void refusesASourceWithNoTransportBlock() {
        ConnectorProperties connector = TestConnectors.simulated("nothing");
        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("has none"));
    }

    @Test
    @DisplayName("a connector feeds one topic from one feed, simulated or not")
    void refusesTwoTransportBlocks() {
        ConnectorProperties connector = TestConnectors.tcp("both", 5001);
        KafkaSourceProperties kafka = new KafkaSourceProperties();
        kafka.setBootstrapServers("localhost:9092");
        kafka.setTopic("orders");
        kafka.setGroupId("connectors");
        connector.getSource().setKafka(kafka);
        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("exactly one"));
    }

    @Test
    void refusesDuplicateConnectorNames() {
        assertThat(ConnectorValidator.validate(properties(
                TestConnectors.tcp("ticks", 5001), TestConnectors.tcp("ticks", 5002))))
                .anySatisfy(error -> assertThat(error).contains("duplicate connector name"));
    }

    @Test
    void refusesABlankTopicAndAnUnknownMessageType() {
        ConnectorProperties connector = TestConnectors.tcp("ticks", 5001);
        connector.getAmps().setTopic("  ");
        connector.getAmps().setMessageType("xml");
        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("amps.topic is required"))
                .anySatisfy(error -> assertThat(error).contains("json/fix/nvfix"));
    }

    @Test
    void refusesNonsensicalBatching() {
        ConnectorProperties connector = TestConnectors.tcp("ticks", 5001);
        connector.getAmps().getBatch().setMaxMessages(0);
        connector.getAmps().getBatch().setFlushInterval(Duration.ZERO);
        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("max-messages"))
                .anySatisfy(error -> assertThat(error).contains("flush-interval"));
    }

    @Test
    void refusesAFilterRuleThatNamesNoOperatorOrSeveral() {
        ConnectorProperties connector = TestConnectors.tcp("ticks", 5001);
        FilterProperties filter = new FilterProperties();
        FilterRule empty = new FilterRule();
        empty.setField("35");
        FilterRule twice = new FilterRule();
        twice.setField("55");
        twice.setEquals("AAPL");
        twice.setIn(List.of("AAPL"));
        filter.setRules(List.of(empty, twice));
        connector.setFilter(filter);

        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("names no operator"))
                .anySatisfy(error -> assertThat(error).contains("exactly one operator"));
    }

    @Test
    void refusesANumericOperandAndARegexThatDoNotParse() {
        ConnectorProperties connector = TestConnectors.tcp("ticks", 5001);
        FilterProperties filter = new FilterProperties();
        FilterRule numeric = new FilterRule();
        numeric.setField("38");
        numeric.setGt("lots");
        FilterRule regex = new FilterRule();
        regex.setField("55");
        regex.setMatches("[unclosed");
        filter.setRules(List.of(numeric, regex));
        connector.setFilter(filter);

        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("is not a number"))
                .anySatisfy(error -> assertThat(error).contains("regular expression"));
    }

    @Test
    @DisplayName("a SpEL expression that does not parse is a startup failure, not a surprise")
    void refusesAnExpressionThatDoesNotParse() {
        ConnectorProperties connector = TestConnectors.tcp("ticks", 5001);
        FilterProperties filter = new FilterProperties();
        filter.setExpression("#f['35' == ");
        connector.setFilter(filter);
        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("filter.expression"));

        ConnectorProperties derived = TestConnectors.tcp("ticks", 5001);
        TransformStep derive = new TransformStep();
        derive.setDerive(Map.of("notional", "#num(#f['38'] *"));
        derived.setTransforms(List.of(derive));
        assertThat(ConnectorValidator.validate(derived))
                .anySatisfy(error -> assertThat(error).contains("derive.notional"));
    }

    @Test
    void refusesATransformStepThatNamesNoKindOrSeveral() {
        ConnectorProperties connector = TestConnectors.tcp("ticks", 5001);
        TransformStep empty = new TransformStep();
        TransformStep twice = new TransformStep();
        twice.setKeep(List.of("55"));
        twice.setDrop(List.of("11"));
        connector.setTransforms(List.of(empty, twice));
        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("names no kind"))
                .anySatisfy(error -> assertThat(error).contains("exactly one kind"));
    }

    @Test
    @DisplayName("jdbc synthesises JSON rows, so any other format has nothing to parse")
    void refusesJdbcWithoutJsonFormat() {
        ConnectorProperties connector = TestConnectors.jdbc(
                "positions", "jdbc:h2:mem:t", "select 1");
        connector.setFormat(SourceFormat.FIX);
        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("requires format: JSON"));
    }

    @Test
    void refusesIncrementalJdbcWithNoIncrementalColumn() {
        ConnectorProperties connector = TestConnectors.jdbc(
                "positions", "jdbc:h2:mem:t", "select 1");
        connector.getSource().getJdbc().setMode(JdbcSourceProperties.Mode.INCREMENTAL);
        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("incremental-column"));
    }

    @Test
    @DisplayName("a snapshot that is meant to delete needs a key to notice what vanished")
    void refusesSnapshotDeletesWithNoKeyColumns() {
        ConnectorProperties connector = TestConnectors.jdbc(
                "positions", "jdbc:h2:mem:t", "select 1");
        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("key-columns"));

        connector.getSource().getJdbc().setKeyColumns(List.of("id"));
        assertThat(ConnectorValidator.validate(connector)).isEmpty();
    }

    @Test
    @DisplayName("a topic and a map are different feeds, so hazelcast names exactly one")
    void refusesHazelcastWithBothStructuresOrNeither() {
        ConnectorProperties both = TestConnectors.hazelcast("events", "connector.events");
        both.getSource().getHazelcast().setMap("positions");
        assertThat(ConnectorValidator.validate(both))
                .anySatisfy(error -> assertThat(error).contains("exactly one of topic/map"))
                .anySatisfy(error -> assertThat(error).contains("both"));

        ConnectorProperties neither = TestConnectors.hazelcast("events", "connector.events");
        neither.getSource().getHazelcast().setTopic("  ");
        assertThat(ConnectorValidator.validate(neither))
                .anySatisfy(error -> assertThat(error).contains("neither"));
    }

    @Test
    @DisplayName("reliable/reliable-from name a ringbuffer's replay, which a map does not have")
    void refusesReliableSettingsOnAHazelcastMap() {
        ConnectorProperties connector = TestConnectors.hazelcastMap("positions", "positions");
        connector.getSource().getHazelcast().setReliable(true);
        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("reliable/reliable-from"));

        connector.getSource().getHazelcast().setReliable(false);
        connector.getSource().getHazelcast()
                .setReliableFrom(HazelcastSourceProperties.ReliableFrom.OLDEST);
        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("a map recovers by being read"));
    }

    @Test
    @DisplayName("snapshot and predicate read a map's contents, and a topic has none")
    void refusesMapSettingsOnAHazelcastTopic() {
        ConnectorProperties snapshot = TestConnectors.hazelcast("events", "connector.events");
        snapshot.getSource().getHazelcast().setSnapshot(true);
        assertThat(ConnectorValidator.validate(snapshot))
                .anySatisfy(error -> assertThat(error).contains("snapshot is only meaningful"));

        ConnectorProperties predicate = TestConnectors.hazelcast("events", "connector.events");
        predicate.getSource().getHazelcast().setPredicate("status = 'OPEN'");
        assertThat(ConnectorValidator.validate(predicate))
                .anySatisfy(error -> assertThat(error).contains("predicate is only meaningful"));
    }

    @Test
    @DisplayName("a map entry has a key of its own, so PUBLISHER mode needs no key fields")
    void acceptsAHazelcastMapAsItsOwnKeySource() {
        ConnectorProperties map = TestConnectors.withKey(
                TestConnectors.hazelcastMap("positions", "positions"),
                KeyProperties.Mode.PUBLISHER);
        assertThat(ConnectorValidator.validate(map)).isEmpty();

        // The topic half of the same driver keys nothing: a message is a payload and no more.
        ConnectorProperties topic = TestConnectors.withKey(
                TestConnectors.hazelcast("events", "connector.events"),
                KeyProperties.Mode.PUBLISHER);
        assertThat(ConnectorValidator.validate(topic))
                .anySatisfy(error -> assertThat(error).contains("hazelcast:topic:connector.events"));
    }

    @Test
    void refusesKafkaWithNoGroupId() {
        ConnectorProperties connector = TestConnectors.kafka("orders", "orders", "g");
        connector.getSource().getKafka().setGroupId("  ");
        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("group-id"));
    }

    @Test
    void refusesServerKeyModeWithNoFields() {
        ConnectorProperties connector = TestConnectors.withKey(
                TestConnectors.tcp("orders", 5001), KeyProperties.Mode.SERVER);
        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("SERVER needs amps.key.fields"));
    }

    @Test
    @DisplayName("PUBLISHER with no key would collapse the feed onto one sentinel-keyed record")
    void refusesPublisherKeyModeWithNothingToKeyWith() {
        ConnectorProperties tcp = TestConnectors.withKey(
                TestConnectors.tcp("ticks", 5001), KeyProperties.Mode.PUBLISHER);
        assertThat(ConnectorValidator.validate(tcp))
                .anySatisfy(error -> assertThat(error).contains("sentinel"));

        ConnectorProperties kafka = TestConnectors.withKey(
                TestConnectors.kafka("orders", "orders", "g"), KeyProperties.Mode.PUBLISHER);
        assertThat(ConnectorValidator.validate(kafka)).isEmpty();

        ConnectorProperties jdbc = TestConnectors.withKey(
                TestConnectors.jdbc("positions", "jdbc:h2:mem:t", "select 1"),
                KeyProperties.Mode.PUBLISHER);
        jdbc.getSource().getJdbc().setKeyColumns(List.of("id"));
        assertThat(ConnectorValidator.validate(jdbc)).isEmpty();
    }

    @Test
    void refusesDeltaPublishWithNoKey() {
        ConnectorProperties connector = TestConnectors.tcp("ticks", 5001);
        connector.getAmps().setCommand(AmpsTargetProperties.Command.DELTA_PUBLISH);
        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("DELTA_PUBLISH requires"));
    }

    @Test
    @DisplayName("a keep that drops a key field leaves the record unkeyable")
    void refusesAKeepThatDropsAKeyField() {
        ConnectorProperties connector = TestConnectors.withKey(
                TestConnectors.tcp("orders", 5001), KeyProperties.Mode.SERVER, "11");
        TransformStep keep = new TransformStep();
        keep.setKeep(List.of("55", "38"));
        connector.setTransforms(List.of(keep));
        assertThat(ConnectorValidator.validate(connector))
                .anySatisfy(error -> assertThat(error).contains("drops key field '11'"));

        keep.setKeep(List.of("11", "55"));
        connector.setTransforms(List.of(keep));
        assertThat(ConnectorValidator.validate(connector)).isEmpty();
    }

    @Test
    void everyMessageIsPrefixedWithTheConnectorName() {
        ConnectorProperties connector = TestConnectors.simulated("named");
        assertThat(ConnectorValidator.validate(connector))
                .isNotEmpty()
                .allSatisfy(error -> assertThat(error).startsWith("connector 'named': "));
    }
}
