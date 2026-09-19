package com.demo.amps.connectors.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.TestConnectors;
import com.demo.amps.connectors.config.AmpsTargetProperties;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.FilterProperties;
import com.demo.amps.connectors.config.FilterRule;
import com.demo.amps.connectors.config.KeyProperties;
import com.demo.amps.connectors.config.SourceFormat;
import com.demo.amps.connectors.config.TransformStep;
import com.demo.amps.connectors.source.SourceRecord;
import com.demo.amps.connectors.transform.TransformRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordPipelineTest {

    private static final char SOH = TestConnectors.SOH;
    private static final TransformRegistry NO_TRANSFORMS = new TransformRegistry(Map.of());

    private static RecordPipeline pipeline(ConnectorProperties connector) {
        return new RecordPipeline(connector, NO_TRANSFORMS);
    }

    private static ConnectorProperties json(String name) {
        return TestConnectors.withFormat(TestConnectors.simulated(name), SourceFormat.JSON);
    }

    private static ConnectorProperties fix(String name) {
        return TestConnectors.withFormat(TestConnectors.simulated(name), SourceFormat.FIX);
    }

    @Test
    @DisplayName("AUTO publishes the original bytes when the formats match and nothing changed")
    void passesTheOriginalPayloadThroughWhenNothingTouchedIt() {
        RecordPipeline pipeline = pipeline(json("ticks"));
        String payload = "{\"id\":\"C-1\",\"price\":185.50}";
        PublishRequest request = pipeline.apply(SourceRecord.of(payload));
        assertThat(request).isNotNull();
        assertThat(request.data()).isSameAs(payload);
        assertThat(request.command()).isEqualTo(Command.PUBLISH);
        assertThat(request.topic()).isEqualTo("test/ticks");
        assertThat(pipeline.published()).isEqualTo(1);
    }

    @Test
    @DisplayName("any transform at all turns AUTO passthrough off")
    void aTransformDisablesAutoPassthrough() {
        ConnectorProperties connector = json("ticks");
        TransformStep set = new TransformStep();
        set.setSet(Map.of("source", "tcp"));
        connector.setTransforms(List.of(set));

        PublishRequest request = pipeline(connector).apply(
                SourceRecord.of("{\"id\":\"C-1\"}"));
        assertThat(request.data()).isEqualTo("{\"id\":\"C-1\",\"source\":\"tcp\"}");
    }

    @Test
    @DisplayName("NEVER encodes even when the bytes would have survived")
    void passthroughNeverAlwaysEncodes() {
        ConnectorProperties connector = json("ticks");
        connector.getAmps().setPassthrough(AmpsTargetProperties.Passthrough.NEVER);
        PublishRequest request = pipeline(connector).apply(
                SourceRecord.of("{ \"id\" : \"C-1\" }"));
        assertThat(request.data()).isEqualTo("{\"id\":\"C-1\"}");
    }

    @Test
    void passthroughAlwaysKeepsTheBytesDespiteTransforms() {
        ConnectorProperties connector = json("ticks");
        connector.getAmps().setPassthrough(AmpsTargetProperties.Passthrough.ALWAYS);
        TransformStep set = new TransformStep();
        set.setSet(Map.of("source", "tcp"));
        connector.setTransforms(List.of(set));
        assertThat(pipeline(connector).apply(SourceRecord.of("{\"id\":\"C-1\"}")).data())
                .isEqualTo("{\"id\":\"C-1\"}");
    }

    @Test
    @DisplayName("a FIX feed onto a json topic is translated, never passed through")
    void translatesFixToJson() {
        ConnectorProperties connector = fix("orders");
        connector.getAmps().setMessageType("json");
        PublishRequest request = pipeline(connector).apply(SourceRecord.of(
                TestConnectors.delimited("11", "ORD-1", "55", "AAPL")));
        assertThat(request.data()).isEqualTo("{\"11\":\"ORD-1\",\"55\":\"AAPL\"}");
    }

    @Test
    @DisplayName("a TEXT feed can never pass through: `text` is a field the decoder invented")
    void textIsAlwaysEncoded() {
        ConnectorProperties connector =
                TestConnectors.withFormat(TestConnectors.simulated("logs"), SourceFormat.TEXT);
        assertThat(pipeline(connector).apply(SourceRecord.of("a log line")).data())
                .isEqualTo("{\"text\":\"a log line\"}");
    }

    @Test
    void sendsTheSowKeyInPublisherMode() {
        ConnectorProperties connector = TestConnectors.withKey(
                fix("orders"), KeyProperties.Mode.PUBLISHER, "11");
        PublishRequest request = pipeline(connector).apply(SourceRecord.of(
                TestConnectors.delimited("11", "ORD-1", "55", "AAPL")));
        assertThat(request.sowKey()).isEqualTo("ORD-1");
    }

    @Test
    @DisplayName("SERVER mode sends no key, but rejects a payload missing one of its fields")
    void serverModeChecksTheKeyFields() {
        ConnectorProperties connector = TestConnectors.withKey(
                fix("orders"), KeyProperties.Mode.SERVER, "11");
        RecordPipeline pipeline = pipeline(connector);

        PublishRequest request = pipeline.apply(SourceRecord.of(
                TestConnectors.delimited("11", "ORD-1")));
        assertThat(request.sowKey()).isNull();

        assertThat(pipeline.apply(SourceRecord.of(TestConnectors.delimited("55", "AAPL"))))
                .isNull();
        assertThat(pipeline.rejected()).isEqualTo(1);
    }

    @Test
    void deltaPublishIsCarriedThrough() {
        ConnectorProperties connector = TestConnectors.withKey(
                json("positions"), KeyProperties.Mode.PUBLISHER, "id");
        connector.getAmps().setCommand(AmpsTargetProperties.Command.DELTA_PUBLISH);
        assertThat(pipeline(connector).apply(SourceRecord.of("{\"id\":\"P-1\"}")).command())
                .isEqualTo(Command.DELTA_PUBLISH);
    }

    @Test
    @DisplayName("a tombstone becomes a delete by key in PUBLISHER mode")
    void deletesByKey() {
        ConnectorProperties connector = TestConnectors.withKey(
                json("positions"), KeyProperties.Mode.PUBLISHER, "id");
        PublishRequest request = pipeline(connector).apply(SourceRecord.delete("", "P-1"));
        assertThat(request.command()).isEqualTo(Command.SOW_DELETE);
        assertThat(request.sowKey()).isEqualTo("P-1");
        assertThat(request.deleteFilter()).isNull();
    }

    @Test
    @DisplayName("on a server-keyed topic the same delete has to be a filter")
    void deletesByFilter() {
        ConnectorProperties connector = TestConnectors.withKey(
                json("events"), KeyProperties.Mode.SERVER, "id");
        PublishRequest request = pipeline(connector)
                .apply(SourceRecord.delete("{\"id\":\"e1\"}", "e1"));
        assertThat(request.command()).isEqualTo(Command.SOW_DELETE);
        assertThat(request.deleteFilter()).isEqualTo("/id = 'e1'");
        assertThat(request.sowKey()).isNull();
    }

    @Test
    @DisplayName("a server-keyed delete with no payload has nothing to address, so it is dropped")
    void dropsAnUnaddressableDelete() {
        ConnectorProperties connector = TestConnectors.withKey(
                json("events"), KeyProperties.Mode.SERVER, "id");
        RecordPipeline pipeline = pipeline(connector);
        assertThat(pipeline.apply(SourceRecord.delete("", "e1"))).isNull();
        assertThat(pipeline.dropped()).isEqualTo(1);
    }

    @Test
    void ignoresRemovalsOnAJournalConnector() {
        ConnectorProperties connector = json("ticks");
        connector.getAmps().setOnDelete(AmpsTargetProperties.OnDelete.IGNORE);
        RecordPipeline pipeline = pipeline(connector);
        assertThat(pipeline.apply(SourceRecord.delete("", "K-1"))).isNull();
        assertThat(pipeline.ignoredDeletes()).isEqualTo(1);
        assertThat(pipeline.published()).isZero();
    }

    @Test
    void countsFilteredRecordsApartFromRejectedOnes() {
        ConnectorProperties connector = fix("orders");
        FilterProperties filter = new FilterProperties();
        FilterRule rule = new FilterRule();
        rule.setField("35");
        rule.setEquals("D");
        filter.setRules(List.of(rule));
        connector.setFilter(filter);

        RecordPipeline pipeline = pipeline(connector);
        assertThat(pipeline.apply(SourceRecord.of(TestConnectors.delimited("35", "8"))))
                .isNull();
        assertThat(pipeline.apply(SourceRecord.of(TestConnectors.delimited("35", "D"))))
                .isNotNull();
        assertThat(pipeline.filtered()).isEqualTo(1);
        assertThat(pipeline.rejected()).isZero();
        assertThat(pipeline.received()).isEqualTo(2);
    }

    @Test
    @DisplayName("a tombstone skips the filter, or the record it removes would live forever")
    void aPayloadFreeDeleteIsNotFiltered() {
        ConnectorProperties connector = TestConnectors.withKey(
                json("positions"), KeyProperties.Mode.PUBLISHER, "id");
        FilterProperties filter = new FilterProperties();
        FilterRule rule = new FilterRule();
        rule.setField("status");
        rule.setEquals("OPEN");
        filter.setRules(List.of(rule));
        connector.setFilter(filter);

        RecordPipeline pipeline = pipeline(connector);
        assertThat(pipeline.apply(SourceRecord.delete("", "P-1"))).isNotNull();
        assertThat(pipeline.filtered()).isZero();
    }

    @Test
    void countsDroppedRecordsApartFromFilteredOnes() {
        ConnectorProperties connector = fix("orders");
        TransformRegistry registry = new TransformRegistry(
                Map.of("dropper", (record, fields) -> null));
        TransformStep bean = new TransformStep();
        bean.setBean("dropper");
        connector.setTransforms(List.of(bean));

        RecordPipeline pipeline = new RecordPipeline(connector, registry);
        assertThat(pipeline.apply(SourceRecord.of(TestConnectors.delimited("35", "D"))))
                .isNull();
        assertThat(pipeline.dropped()).isEqualTo(1);
        assertThat(pipeline.filtered()).isZero();
    }

    @Test
    @DisplayName("a payload the decoder cannot read is rejected and the pipeline carries on")
    void countsUndecodableRecordsAsRejected() {
        RecordPipeline pipeline = pipeline(json("ticks"));
        assertThat(pipeline.apply(SourceRecord.of("not json at all"))).isNull();
        assertThat(pipeline.apply(SourceRecord.of("{\"id\":\"C-1\"}"))).isNotNull();
        assertThat(pipeline.rejected()).isEqualTo(1);
        assertThat(pipeline.published()).isEqualTo(1);
    }

    @Test
    @DisplayName("a field that is not a tag number is rejected on the way into a fix topic")
    void countsUnencodableRecordsAsRejected() {
        ConnectorProperties connector = json("orders");
        connector.getAmps().setMessageType("fix");
        connector.getAmps().setPassthrough(AmpsTargetProperties.Passthrough.NEVER);
        RecordPipeline pipeline = pipeline(connector);
        assertThat(pipeline.apply(SourceRecord.of("{\"symbol\":\"AAPL\"}"))).isNull();
        assertThat(pipeline.rejected()).isEqualTo(1);
    }

    @Test
    void theRecordRidesAlongForItsAcknowledgment() {
        SourceRecord record = SourceRecord.of("{\"id\":\"C-1\"}");
        assertThat(pipeline(json("ticks")).apply(record).record()).isSameAs(record);
    }
}
