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
                .anySatisfy(error -> assertThat(error).contains("json/fix/nvfix/protobuf/binary"));
    }

    @Test
    @DisplayName("a payload type is both ids or neither, and never negative")
    void refusesAHalfSetOrNegativePayloadType() {
        ConnectorProperties halfSet = TestConnectors.tcp("ticks", 5001);
        halfSet.getAmps().getPayloadType().setClassId(3);
        assertThat(ConnectorValidator.validate(halfSet))
                .singleElement().asString()
                .contains("amps.payload-type 0/3").contains("names nothing");

        ConnectorProperties otherHalf = TestConnectors.tcp("ticks", 5001);
        otherHalf.getAmps().getPayloadType().setFactoryId(100);
        assertThat(ConnectorValidator.validate(otherHalf))
                .singleElement().asString().contains("amps.payload-type 100/0");

        ConnectorProperties negative = TestConnectors.tcp("ticks", 5001);
        negative.getAmps().getPayloadType().setFactoryId(-1);
        negative.getAmps().getPayloadType().setClassId(1);
        assertThat(ConnectorValidator.validate(negative))
                .singleElement().asString().contains("non-negative");

        ConnectorProperties typed = TestConnectors.tcp("ticks", 5001);
        typed.getAmps().getPayloadType().setFactoryId(100);
        typed.getAmps().getPayloadType().setClassId(1);
        assertThat(ConnectorValidator.validate(typed))
                .as("whether a codec is registered is the pipeline's question, not this one's")
                .isEmpty();
    }

    @Test
    @DisplayName("protobuf and binary have no text encoder, so they need a payload-type naming the codec")
    void codecOnlyMessageTypesNeedAPayloadType() {
        for (String type : List.of("protobuf", "binary")) {
            ConnectorProperties untyped = TestConnectors.tcp("ticks", 5001);
            untyped.getAmps().setMessageType(type);
            assertThat(ConnectorValidator.validate(untyped)).as(type)
                    .singleElement().asString()
                    .contains("amps.message-type '" + type + "' has no text encoder")
                    .contains("amps.payload-type");

            ConnectorProperties typed = TestConnectors.tcp("ticks", 5001);
            typed.getAmps().setMessageType(type);
            typed.getAmps().getPayloadType().setFactoryId(100);
            typed.getAmps().getPayloadType().setClassId(1);
            assertThat(ConnectorValidator.validate(typed)).as(type + " with a codec").isEmpty();
        }
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
    @DisplayName("PERSISTED acknowledgment needs a publish store to observe the acks through")
    void refusesPersistedAckModeWithoutAPublishStore() {
        ConnectorProperties persisted = TestConnectors.tcp("ticks", 5001);
        persisted.getAmps().setAckMode(AmpsTargetProperties.AckMode.PERSISTED);
        ConnectorsProperties storeless = properties(persisted);
        storeless.getAmps().setPublishStore(AmpsServerProperties.PublishStore.NONE);

        assertThat(ConnectorValidator.validate(storeless))
                .singleElement().asString()
                .contains("connector 'ticks'")
                .contains("amps.ack-mode: PERSISTED")
                .contains("amps-connectors.amps.publish-store is NONE");

        // The same connector with a store, and a flushing connector without one, are fine.
        assertThat(ConnectorValidator.validate(properties(persisted)))
                .as("MEMORY is the default store").isEmpty();
        ConnectorsProperties file = properties(persisted);
        file.getAmps().setPublishStore(AmpsServerProperties.PublishStore.FILE);
        assertThat(ConnectorValidator.validate(file)).isEmpty();

        ConnectorProperties flushing = TestConnectors.tcp("ticks", 5001);
        ConnectorsProperties flushingStoreless = properties(flushing);
        flushingStoreless.getAmps().setPublishStore(AmpsServerProperties.PublishStore.NONE);
        assertThat(ConnectorValidator.validate(flushingStoreless))
                .as("FLUSH with NONE is fire-and-forget, deliberately").isEmpty();
    }

    @Test
    void refusesANonPositiveMaxPending() {
        ConnectorProperties connector = TestConnectors.tcp("ticks", 5001);
        connector.getAmps().getBatch().setMaxPending(0);
        assertThat(ConnectorValidator.validate(properties(connector)))
                .singleElement().asString().contains("amps.batch.max-pending must be at least 1");

        connector.getAmps().getBatch().setMaxPending(1);
        assertThat(ConnectorValidator.validate(properties(connector))).isEmpty();
        assertThat(new BatchProperties().getMaxPending()).isEqualTo(10_000);
        assertThat(new AmpsTargetProperties().getAckMode())
                .isEqualTo(AmpsTargetProperties.AckMode.FLUSH);
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
    @DisplayName("an AMPS source has no message type for TEXT, and a bookmark replay needs a bookmark")
    void refusesAmpsSourceRules() {
        ConnectorProperties text = TestConnectors.amps("bridge", "sow/connectors/orders");
        text.setFormat(SourceFormat.TEXT);
        assertThat(ConnectorValidator.validate(text))
                .anySatisfy(error -> assertThat(error).contains("cannot read format: TEXT"));

        ConnectorProperties bookmark = TestConnectors.amps("bridge", "sow/connectors/orders");
        bookmark.getSource().getAmps().setMode(AmpsSourceProperties.Mode.BOOKMARK);
        bookmark.getSource().getAmps().setBookmark(null);
        assertThat(ConnectorValidator.validate(bookmark))
                .anySatisfy(error -> assertThat(error).contains("BOOKMARK requires"));
        bookmark.getSource().getAmps().setBookmark(AmpsSourceProperties.Bookmark.EPOCH);
        assertThat(ConnectorValidator.validate(bookmark)).isEmpty();

        ConnectorProperties blank = TestConnectors.amps("bridge", " ");
        assertThat(ConnectorValidator.validate(blank))
                .anySatisfy(error -> assertThat(error).contains("source.amps.topic is required"));

        // The block list names every transport, amps included.
        assertThat(ConnectorValidator.validate(TestConnectors.simulated("nothing")))
                .anySatisfy(error -> assertThat(error).contains("tcp/kafka/jdbc/hazelcast/amps"));
    }

    @Test
    @DisplayName("a SOW record has a SowKey, so sow_and_subscribe keys its own records; a journal does not")
    void acceptsAnAmpsSowSubscriptionAsItsOwnKeySource() {
        ConnectorProperties sow = TestConnectors.withKey(
                TestConnectors.amps("bridge", "sow/connectors/orders"), KeyProperties.Mode.PUBLISHER);
        sow.getSource().getAmps().setMode(AmpsSourceProperties.Mode.SOW_AND_SUBSCRIBE);
        assertThat(ConnectorValidator.validate(sow)).isEmpty();

        ConnectorProperties journal = TestConnectors.withKey(
                TestConnectors.amps("bridge", "connectors/ticks"), KeyProperties.Mode.PUBLISHER);
        assertThat(ConnectorValidator.validate(journal))
                .anySatisfy(error -> assertThat(error).contains("amps:connectors/ticks"))
                .anySatisfy(error -> assertThat(error).contains("SOW_AND_SUBSCRIBE"));
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

    // ---- rules ------------------------------------------------------------------

    private static RuleProperties rule(String name, String when) {
        RuleProperties rule = new RuleProperties();
        rule.setName(name);
        rule.setWhen(when);
        rule.getThen().setSet(Map.of("flag", "on"));
        return rule;
    }

    private static ConnectorProperties withRules(RuleProperties... rules) {
        ConnectorProperties connector = TestConnectors.tcp("orders", 5001);
        TransformStep step = new TransformStep();
        step.setRules(List.of(rules));
        connector.setTransforms(List.of(step));
        return connector;
    }

    @Test
    @DisplayName("a well-formed rules step is accepted, and is one kind like any other")
    void acceptsAWellFormedRulesStep() {
        RuleProperties alerting = rule("limit-without-price", "#f['40'] == '2' && !#f.containsKey('44')");
        alerting.getThen().setSet(null);
        RuleAlert alert = new RuleAlert();
        alert.setCode("LIMIT_WITHOUT_PRICE");
        alert.setMessage("limit order #{#f['11']} has no price");
        alerting.getThen().setAlert(alert);
        RuleProperties dropping = rule("cancel", "#r.action.name() == 'DELETE'");
        dropping.getThen().setDrop(true);
        ConnectorProperties connector = withRules(alerting, dropping);
        assertThat(ConnectorValidator.validate(connector)).isEmpty();
        assertThat(connector.getTransforms().get(0).configuredKinds()).containsExactly("rules");

        TransformStep twice = new TransformStep();
        twice.setRules(List.of(rule("a", "true")));
        twice.setBean("x");
        assertThat(twice.configuredKinds()).containsExactly("bean", "rules");
    }

    @Test
    @DisplayName("a rules step lists rules, each named once, each with a when that parses")
    void refusesRulesWithoutNamesOrConditions() {
        assertThat(ConnectorValidator.validate(withRules()))
                .anySatisfy(error -> assertThat(error).contains("lists no rules"));

        RuleProperties blank = rule(" ", "true");
        RuleProperties noWhen = rule("no-when", " ");
        RuleProperties badWhen = rule("bad-when", "#f['11' ==");
        assertThat(ConnectorValidator.validate(withRules(blank, noWhen, badWhen, rule("no-when", "true"))))
                .anySatisfy(error -> assertThat(error).contains("a rule has no name"))
                .anySatisfy(error -> assertThat(error).contains("rule 'no-when' has no when"))
                .anySatisfy(error -> assertThat(error).contains("rule 'bad-when'.when"))
                .anySatisfy(error -> assertThat(error).contains("duplicate rule name 'no-when'"));
    }

    @Test
    @DisplayName("a then does something, an alert has a code, and a message is a template")
    void refusesRulesWhoseThenIsIncomplete() {
        RuleProperties nothing = rule("nothing", "true");
        nothing.getThen().setSet(null);
        RuleProperties blankBean = rule("blank-bean", "true");
        blankBean.getThen().setBean(" ");
        RuleProperties noCode = rule("no-code", "true");
        noCode.getThen().setAlert(new RuleAlert());
        RuleProperties badTemplate = rule("bad-template", "true");
        RuleAlert alert = new RuleAlert();
        alert.setCode("X");
        alert.setMessage("open #{#f['11']");
        alert.setSeverity(null);
        badTemplate.getThen().setAlert(alert);

        assertThat(ConnectorValidator.validate(withRules(nothing, blankBean, noCode, badTemplate)))
                .allSatisfy(error -> assertThat(error).startsWith("connector 'orders': "))
                .anySatisfy(error -> assertThat(error).contains("rule 'nothing' names no action"))
                .anySatisfy(error -> assertThat(error).contains("rule 'blank-bean' names a blank bean"))
                .anySatisfy(error -> assertThat(error).contains("rule 'no-code'.alert needs a code"))
                .anySatisfy(error -> assertThat(error).contains("rule 'bad-template'.alert.message"))
                .anySatisfy(error -> assertThat(error).contains("rule 'bad-template'.alert.severity"));
    }

    // ---- control ----------------------------------------------------------------

    private static ConnectorsProperties withControl(ControlProperties control) {
        ConnectorsProperties properties = new ConnectorsProperties();
        properties.setControl(control);
        return properties;
    }

    private static ControlProperties ampsControl() {
        ControlProperties control = new ControlProperties();
        control.setEnabled(true);
        AmpsSourceProperties amps = new AmpsSourceProperties();
        amps.setTopic("connectors/control");
        control.getSource().setAmps(amps);
        return control;
    }

    @Test
    @DisplayName("control is off by default and valid; on, it is valid with one source block")
    void acceptsDefaultAndWellFormedControl() {
        assertThat(ConnectorValidator.validateControl(new ConnectorsProperties())).isEmpty();
        assertThat(new ConnectorsProperties().getControl().isEnabled()).isFalse();
        assertThat(new ConnectorsProperties().getControl().getAcceptTargets()).containsExactly("all");
        assertThat(ConnectorValidator.validateControl(withControl(ampsControl()))).isEmpty();
        assertThat(ConnectorValidator.validate(withControl(ampsControl()))).isEmpty();
    }

    @Test
    @DisplayName("enabled control needs exactly one source block, and the block's own rules apply")
    void refusesEnabledControlWithoutOneSource() {
        ControlProperties none = new ControlProperties();
        none.setEnabled(true);
        assertThat(ConnectorValidator.validateControl(withControl(none)))
                .allSatisfy(error -> assertThat(error).startsWith("control: "))
                .anySatisfy(error -> assertThat(error).contains("exactly one"))
                .anySatisfy(error -> assertThat(error).contains("has none"));

        ControlProperties two = ampsControl();
        KafkaSourceProperties kafka = new KafkaSourceProperties();
        kafka.setBootstrapServers("localhost:9092");
        kafka.setTopic("connectors.control");
        kafka.setGroupId("g");
        two.getSource().setKafka(kafka);
        assertThat(ConnectorValidator.validate(withControl(two)))
                .anySatisfy(error -> assertThat(error).contains("control: source configures"));

        ControlProperties noGroup = new ControlProperties();
        noGroup.setEnabled(true);
        KafkaSourceProperties unnamed = new KafkaSourceProperties();
        unnamed.setBootstrapServers("localhost:9092");
        unnamed.setTopic("connectors.control");
        noGroup.getSource().setKafka(unnamed);
        assertThat(ConnectorValidator.validateControl(withControl(noGroup)))
                .anySatisfy(error -> assertThat(error).contains("control: source.kafka.group-id"));

        // Disabled, a half-written block is not a mistake yet.
        none.setEnabled(false);
        assertThat(ConnectorValidator.validateControl(withControl(none))).isEmpty();
    }

    @Test
    void refusesBlankAcceptTargets() {
        ControlProperties control = ampsControl();
        control.setAcceptTargets(List.of("all", " "));
        assertThat(ConnectorValidator.validateControl(withControl(control)))
                .anySatisfy(error -> assertThat(error).contains("accept-targets contains a blank"));
    }

    // ---- resources --------------------------------------------------------------

    private static ResourceProperties jdbcResource(String name) {
        ResourceProperties resource = new ResourceProperties();
        resource.setName(name);
        JdbcResourceProperties jdbc = new JdbcResourceProperties();
        jdbc.setUrl("jdbc:h2:mem:refdata");
        jdbc.setQuery("SELECT symbol, sedol FROM instruments");
        jdbc.setKeyColumns(List.of("symbol"));
        resource.setJdbc(jdbc);
        return resource;
    }

    private static ConnectorsProperties withResources(ResourceProperties... resources) {
        ConnectorsProperties properties = new ConnectorsProperties();
        properties.setResources(List.of(resources));
        return properties;
    }

    @Test
    @DisplayName("a well-formed jdbc resource is accepted, through the full validation too")
    void acceptsAWellFormedResource() {
        ConnectorsProperties properties = withResources(jdbcResource("instruments"));
        assertThat(ConnectorValidator.validateResources(properties)).isEmpty();
        assertThat(ConnectorValidator.validate(properties)).isEmpty();
        assertThat(jdbcResource("instruments").configuredKinds()).containsExactly("jdbc");
    }

    @Test
    @DisplayName("a resource needs a unique name: transforms and reload commands address it by it")
    void refusesBlankAndDuplicateResourceNames() {
        ResourceProperties blank = jdbcResource("  ");
        assertThat(ConnectorValidator.validateResources(withResources(blank)))
                .anySatisfy(error -> assertThat(error).contains("name is required"));

        assertThat(ConnectorValidator.validate(withResources(
                jdbcResource("instruments"), jdbcResource("instruments"))))
                .anySatisfy(error -> assertThat(error).contains(
                        "duplicate resource name: instruments"));
    }

    @Test
    @DisplayName("an entry names exactly one kind, or nothing can build it")
    void refusesAResourceWithNoKind() {
        ResourceProperties none = new ResourceProperties();
        none.setName("instruments");
        assertThat(ConnectorValidator.validateResources(withResources(none)))
                .anySatisfy(error -> assertThat(error)
                        .startsWith("resource 'instruments': ")
                        .contains("names no kind"));
        assertThat(none.configuredKinds()).isEmpty();
    }

    @Test
    @DisplayName("a jdbc resource is a query, a way to key its rows, and timings that make sense")
    void refusesAJdbcResourceMissingItsEssentials() {
        ResourceProperties resource = jdbcResource("instruments");
        resource.getJdbc().setUrl(" ");
        resource.getJdbc().setQuery(null);
        resource.getJdbc().setKeyColumns(List.of());
        resource.getJdbc().setReloadInterval(Duration.ofSeconds(-1));
        resource.getJdbc().setReconnectDelay(Duration.ZERO);
        resource.getJdbc().setFetchSize(0);
        resource.getJdbc().setKeySeparator("");

        assertThat(ConnectorValidator.validateResources(withResources(resource)))
                .allSatisfy(error -> assertThat(error).startsWith("resource 'instruments': "))
                .anySatisfy(error -> assertThat(error).contains("jdbc.url is required"))
                .anySatisfy(error -> assertThat(error).contains("jdbc.query is required"))
                .anySatisfy(error -> assertThat(error).contains("jdbc.key-columns is required"))
                .anySatisfy(error -> assertThat(error).contains("jdbc.reload-interval"))
                .anySatisfy(error -> assertThat(error).contains("jdbc.reconnect-delay"))
                .anySatisfy(error -> assertThat(error).contains("jdbc.fetch-size"))
                .anySatisfy(error -> assertThat(error).contains("jdbc.key-separator"));

        ResourceProperties blankColumn = jdbcResource("instruments");
        blankColumn.getJdbc().setKeyColumns(List.of("symbol", " "));
        assertThat(ConnectorValidator.validateResources(withResources(blankColumn)))
                .anySatisfy(error -> assertThat(error).contains("blank column name"));

        // Zero is "on demand only", and is fine.
        ResourceProperties onDemand = jdbcResource("instruments");
        onDemand.getJdbc().setReloadInterval(Duration.ZERO);
        assertThat(ConnectorValidator.validateResources(withResources(onDemand))).isEmpty();
    }

    // ---- alerts -----------------------------------------------------------------

    private static ConnectorsProperties withAlerts(AlertProperties alerts) {
        ConnectorsProperties properties = new ConnectorsProperties();
        properties.setAlerts(alerts);
        return properties;
    }

    @Test
    @DisplayName("the default alerts block -- enabled, log only -- is valid")
    void acceptsDefaultAlerts() {
        assertThat(ConnectorValidator.validateAlerts(new ConnectorsProperties())).isEmpty();
        AlertProperties both = new AlertProperties();
        both.setAmps(new AlertProperties.Amps());
        both.getAmps().setTopic("connectors/alerts");
        both.setKafka(new AlertProperties.Kafka());
        both.getKafka().setTopic("connectors.alerts");
        both.getKafka().setBootstrapServers("localhost:9092");
        assertThat(ConnectorValidator.validate(withAlerts(both))).isEmpty();
    }

    @Test
    @DisplayName("a queue of nothing would drop every alert")
    void refusesAnEmptyAlertQueue() {
        AlertProperties alerts = new AlertProperties();
        alerts.setQueueSize(0);
        alerts.setSuppressRepeats(Duration.ofSeconds(-5));
        assertThat(ConnectorValidator.validateAlerts(withAlerts(alerts)))
                .anySatisfy(error -> assertThat(error).contains("alerts.queue-size"))
                .anySatisfy(error -> assertThat(error).contains("alerts.suppress-repeats"));
    }

    @Test
    @DisplayName("a sink block names its topic, and a Kafka topic names its cluster")
    void refusesASinkMissingWhatItNeeds() {
        AlertProperties amps = new AlertProperties();
        amps.setAmps(new AlertProperties.Amps());
        assertThat(ConnectorValidator.validateAlerts(withAlerts(amps)))
                .anySatisfy(error -> assertThat(error).contains("alerts.amps.topic is required"));

        AlertProperties kafka = new AlertProperties();
        kafka.setKafka(new AlertProperties.Kafka());
        kafka.getKafka().setTopic("connectors.alerts");
        assertThat(ConnectorValidator.validateAlerts(withAlerts(kafka)))
                .anySatisfy(error -> assertThat(error).contains("bootstrap-servers"));

        kafka.getKafka().setTopic(" ");
        assertThat(ConnectorValidator.validateAlerts(withAlerts(kafka)))
                .anySatisfy(error -> assertThat(error).contains("alerts.kafka.topic is required"));
    }
}
