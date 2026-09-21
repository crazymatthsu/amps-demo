package com.demo.amps.connectors.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demo.amps.connectors.TestConnectors;
import com.demo.amps.connectors.codec.PayloadCodecRegistry;
import com.demo.amps.connectors.codec.PayloadType;
import com.demo.amps.connectors.codec.Payloads;
import com.demo.amps.connectors.codec.TestPojoCodec;
import com.demo.amps.connectors.config.AmpsTargetProperties;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.FilterProperties;
import com.demo.amps.connectors.config.FilterRule;
import com.demo.amps.connectors.config.KeyProperties;
import com.demo.amps.connectors.config.RuleProperties;
import com.demo.amps.connectors.config.SourceFormat;
import com.demo.amps.connectors.config.TransformStep;
import com.demo.amps.connectors.source.InboundRecord;
import com.demo.amps.connectors.transform.TransformContext;
import com.demo.amps.connectors.transform.TransformRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    private static OutboundRecord out(RecordPipeline pipeline, InboundRecord record) {
        MessageContext context = pipeline.apply(record);
        assertThat(context).as("the record was published").isNotNull();
        return context.out();
    }

    @Test
    @DisplayName("AUTO publishes the original bytes when the formats match and nothing changed")
    void passesTheOriginalPayloadThroughWhenNothingTouchedIt() {
        RecordPipeline pipeline = pipeline(json("ticks"));
        String payload = "{\"id\":\"C-1\",\"price\":185.50}";
        OutboundRecord request = out(pipeline, InboundRecord.of(payload));
        assertThat(request.data()).isSameAs(payload);
        assertThat(request.command()).isEqualTo(Command.PUBLISH);
        assertThat(request.topic()).isEqualTo("test/ticks");
        assertThat(request.type()).isEqualTo(PayloadType.UNSET);
        assertThat(pipeline.published()).isEqualTo(1);
    }

    @Test
    @DisplayName("any transform at all turns AUTO passthrough off")
    void aTransformDisablesAutoPassthrough() {
        ConnectorProperties connector = json("ticks");
        TransformStep set = new TransformStep();
        set.setSet(Map.of("source", "tcp"));
        connector.setTransforms(List.of(set));

        OutboundRecord request = out(pipeline(connector), InboundRecord.of("{\"id\":\"C-1\"}"));
        assertThat(request.data()).isEqualTo("{\"id\":\"C-1\",\"source\":\"tcp\"}");
    }

    @Test
    @DisplayName("NEVER encodes even when the bytes would have survived")
    void passthroughNeverAlwaysEncodes() {
        ConnectorProperties connector = json("ticks");
        connector.getAmps().setPassthrough(AmpsTargetProperties.Passthrough.NEVER);
        OutboundRecord request = out(pipeline(connector), InboundRecord.of("{ \"id\" : \"C-1\" }"));
        assertThat(request.data()).isEqualTo("{\"id\":\"C-1\"}");
    }

    @Test
    void passthroughAlwaysKeepsTheBytesDespiteTransforms() {
        ConnectorProperties connector = json("ticks");
        connector.getAmps().setPassthrough(AmpsTargetProperties.Passthrough.ALWAYS);
        TransformStep set = new TransformStep();
        set.setSet(Map.of("source", "tcp"));
        connector.setTransforms(List.of(set));
        assertThat(out(pipeline(connector), InboundRecord.of("{\"id\":\"C-1\"}")).data())
                .isEqualTo("{\"id\":\"C-1\"}");
    }

    @Test
    @DisplayName("a FIX feed onto a json topic is translated, never passed through")
    void translatesFixToJson() {
        ConnectorProperties connector = fix("orders");
        connector.getAmps().setMessageType("json");
        OutboundRecord request = out(pipeline(connector), InboundRecord.of(
                TestConnectors.delimited("11", "ORD-1", "55", "AAPL")));
        assertThat(request.data()).isEqualTo("{\"11\":\"ORD-1\",\"55\":\"AAPL\"}");
    }

    @Test
    @DisplayName("a TEXT feed can never pass through: `text` is a field the decoder invented")
    void textIsAlwaysEncoded() {
        ConnectorProperties connector =
                TestConnectors.withFormat(TestConnectors.simulated("logs"), SourceFormat.TEXT);
        assertThat(out(pipeline(connector), InboundRecord.of("a log line")).data())
                .isEqualTo("{\"text\":\"a log line\"}");
    }

    @Test
    @DisplayName("a byte[] payload of an UNSET type is text too: decoded as UTF-8 by the format")
    void bytesOfAnUnsetTypeAreTextByFormat() {
        RecordPipeline pipeline = pipeline(json("ticks"));
        byte[] payload = Payloads.bytes("{\"id\":\"C-1\"}");
        OutboundRecord request = out(pipeline, InboundRecord.of(payload));
        // Same wire format, nothing touched: the original bytes go out as they came.
        assertThat(request.data()).isSameAs(payload);

        ConnectorProperties encoding = json("ticks");
        encoding.getAmps().setPassthrough(AmpsTargetProperties.Passthrough.NEVER);
        assertThat(out(pipeline(encoding), InboundRecord.of(payload)).data())
                .isEqualTo("{\"id\":\"C-1\"}");
    }

    @Test
    void sendsTheSowKeyInPublisherMode() {
        ConnectorProperties connector = TestConnectors.withKey(
                fix("orders"), KeyProperties.Mode.PUBLISHER, "11");
        OutboundRecord request = out(pipeline(connector), InboundRecord.of(
                TestConnectors.delimited("11", "ORD-1", "55", "AAPL")));
        assertThat(request.sowKey()).isEqualTo("ORD-1");
    }

    @Test
    @DisplayName("SERVER mode sends no key, but rejects a payload missing one of its fields")
    void serverModeChecksTheKeyFields() {
        ConnectorProperties connector = TestConnectors.withKey(
                fix("orders"), KeyProperties.Mode.SERVER, "11");
        RecordPipeline pipeline = pipeline(connector);

        OutboundRecord request = out(pipeline, InboundRecord.of(
                TestConnectors.delimited("11", "ORD-1")));
        assertThat(request.sowKey()).isNull();

        assertThat(pipeline.apply(InboundRecord.of(TestConnectors.delimited("55", "AAPL"))))
                .isNull();
        assertThat(pipeline.rejected()).isEqualTo(1);
    }

    @Test
    void deltaPublishIsCarriedThrough() {
        ConnectorProperties connector = TestConnectors.withKey(
                json("positions"), KeyProperties.Mode.PUBLISHER, "id");
        connector.getAmps().setCommand(AmpsTargetProperties.Command.DELTA_PUBLISH);
        assertThat(out(pipeline(connector), InboundRecord.of("{\"id\":\"P-1\"}")).command())
                .isEqualTo(Command.DELTA_PUBLISH);
    }

    @Test
    @DisplayName("a tombstone becomes a delete by key in PUBLISHER mode")
    void deletesByKey() {
        ConnectorProperties connector = TestConnectors.withKey(
                json("positions"), KeyProperties.Mode.PUBLISHER, "id");
        OutboundRecord request = out(pipeline(connector), InboundRecord.delete("", "P-1"));
        assertThat(request.command()).isEqualTo(Command.SOW_DELETE);
        assertThat(request.sowKey()).isEqualTo("P-1");
        assertThat(request.deleteFilter()).isNull();
        assertThat(request.data()).isNull();
        assertThat(request.text()).isEmpty();
    }

    @Test
    @DisplayName("on a server-keyed topic the same delete has to be a filter")
    void deletesByFilter() {
        ConnectorProperties connector = TestConnectors.withKey(
                json("events"), KeyProperties.Mode.SERVER, "id");
        OutboundRecord request = out(pipeline(connector),
                InboundRecord.delete("{\"id\":\"e1\"}", "e1"));
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
        assertThat(pipeline.apply(InboundRecord.delete("", "e1"))).isNull();
        assertThat(pipeline.apply(InboundRecord.delete(null, "e1"))).isNull();
        assertThat(pipeline.dropped()).isEqualTo(2);
    }

    @Test
    void ignoresRemovalsOnAJournalConnector() {
        ConnectorProperties connector = json("ticks");
        connector.getAmps().setOnDelete(AmpsTargetProperties.OnDelete.IGNORE);
        RecordPipeline pipeline = pipeline(connector);
        assertThat(pipeline.apply(InboundRecord.delete("", "K-1"))).isNull();
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
        assertThat(pipeline.apply(InboundRecord.of(TestConnectors.delimited("35", "8"))))
                .isNull();
        assertThat(pipeline.apply(InboundRecord.of(TestConnectors.delimited("35", "D"))))
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
        assertThat(pipeline.apply(InboundRecord.delete("", "P-1"))).isNotNull();
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
        assertThat(pipeline.apply(InboundRecord.of(TestConnectors.delimited("35", "D"))))
                .isNull();
        assertThat(pipeline.dropped()).isEqualTo(1);
        assertThat(pipeline.filtered()).isZero();
    }

    @Test
    @DisplayName("a payload the decoder cannot read is rejected and the pipeline carries on")
    void countsUndecodableRecordsAsRejected() {
        RecordPipeline pipeline = pipeline(json("ticks"));
        assertThat(pipeline.apply(InboundRecord.of("not json at all"))).isNull();
        assertThat(pipeline.apply(InboundRecord.of("{\"id\":\"C-1\"}"))).isNotNull();
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
        assertThat(pipeline.apply(InboundRecord.of("{\"symbol\":\"AAPL\"}"))).isNull();
        assertThat(pipeline.rejected()).isEqualTo(1);
    }

    @Test
    @DisplayName("the context pairs the record with its command, with no out-side sequence yet")
    void theContextCarriesBothHalves() {
        InboundRecord record = InboundRecord.of("{\"id\":\"C-1\"}").withSeqno(9);
        MessageContext context = pipeline(json("ticks")).apply(record);
        assertThat(context.in()).isSameAs(record);
        assertThat(context.dataInSeqno()).isEqualTo(9);
        assertThat(context.out().topic()).isEqualTo("test/ticks");
        assertThat(context.dataOutSeqno()).isZero();
        assertThat(context.dataOutType()).isEqualTo(PayloadType.UNSET);
    }

    @Test
    @DisplayName("a record typed for a codec nobody registered is rejected, not published as text")
    void anUnregisteredTypeIsRejected() {
        RecordPipeline pipeline = pipeline(json("ticks"));
        assertThat(pipeline.apply(InboundRecord.of("{\"id\":\"C-1\"}").withType(PayloadType.of(7, 7))))
                .isNull();
        assertThat(pipeline.rejected()).isEqualTo(1);
        assertThat(pipeline.published()).isZero();
    }

    @Test
    @DisplayName("a target payload-type no codec encodes fails at construction, naming what is registered")
    void aTargetTypeWithoutACodecFailsAtStart() {
        ConnectorProperties connector = json("ticks");
        connector.getAmps().getPayloadType().setFactoryId(7);
        connector.getAmps().getPayloadType().setClassId(7);
        assertThatThrownBy(() -> pipeline(connector))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[ticks]")
                .hasMessageContaining("7/7")
                .hasMessageContaining("registered: []");
        assertThatThrownBy(() -> new RecordPipeline(connector, TransformContext.of(NO_TRANSFORMS),
                new PayloadCodecRegistry(List.of(new TestPojoCodec()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registered: [100/1]");
    }

    @Test
    @DisplayName("a message type with no text encoder and no payload-type has nothing to write with")
    void aCodecOnlyMessageTypeNeedsAPayloadType() {
        ConnectorProperties connector = json("ticks");
        connector.getAmps().setMessageType("protobuf");
        assertThatThrownBy(() -> pipeline(connector))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protobuf")
                .hasMessageContaining("payload-type");
        // A half-set payload type fails the same way a bad one does: at construction.
        ConnectorProperties halfSet = json("ticks");
        halfSet.getAmps().getPayloadType().setClassId(3);
        assertThatThrownBy(() -> pipeline(halfSet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0/3");
    }

    /** The seam under a real codec: a typed record through the same pipeline as a text one. */
    @Nested
    @DisplayName("with a registered codec")
    class WithACodec {

        private final TestPojoCodec codec = new TestPojoCodec();
        private final PayloadCodecRegistry codecs = new PayloadCodecRegistry(List.of(codec));

        private RecordPipeline typedPipeline(ConnectorProperties connector) {
            return new RecordPipeline(connector, TransformContext.of(NO_TRANSFORMS), codecs);
        }

        /** The connector, with the target's payload-type set to the codec's. */
        private ConnectorProperties typedOut(ConnectorProperties connector) {
            connector.getAmps().getPayloadType().setFactoryId(TestPojoCodec.TYPE.factoryId());
            connector.getAmps().getPayloadType().setClassId(TestPojoCodec.TYPE.classId());
            return connector;
        }

        private static TestPojoCodec.Order order() {
            return new TestPojoCodec.Order("O-1", 100, "185.50")
                    .party(new TestPojoCodec.Party("ACME", "buyer"))
                    .addLeg("L1").addLeg("L2");
        }

        private static InboundRecord typed(TestPojoCodec.Order order) {
            return InboundRecord.of(order, order.id()).withType(TestPojoCodec.TYPE);
        }

        @Test
        @DisplayName("the decoder is the record's: a typed record goes through the codec, an UNSET one through the format")
        void decodesByTheRecordsType() {
            ConnectorProperties connector = TestConnectors.withKey(
                    json("orders"), KeyProperties.Mode.PUBLISHER, "id");
            RecordPipeline pipeline = typedPipeline(connector);

            OutboundRecord typed = out(pipeline, typed(order()));
            // Typed in, text out: the JSON encoder wrote the view, nested party and legs included.
            assertThat(typed.data()).isInstanceOf(String.class).isEqualTo(
                    "{\"id\":\"O-1\",\"qty\":100,\"price\":185.50,"
                            + "\"party\":{\"name\":\"ACME\",\"role\":\"buyer\"},"
                            + "\"legs\":[\"L1\",\"L2\"]}");
            assertThat(typed.type()).isEqualTo(PayloadType.UNSET);
            assertThat(typed.sowKey()).isEqualTo("O-1");

            OutboundRecord text = out(pipeline, InboundRecord.of("{\"id\":\"T-1\"}"));
            assertThat(text.data()).isEqualTo("{\"id\":\"T-1\"}");
            assertThat(text.sowKey()).isEqualTo("T-1");
            assertThat(pipeline.published()).isEqualTo(2);
            assertThat(pipeline.rejected()).isZero();
        }

        @Test
        @DisplayName("the codec decodes its bytes as well as its object")
        void decodesTheCodecsBytes() {
            RecordPipeline pipeline = typedPipeline(json("orders"));
            byte[] wire = Payloads.bytes("{\"id\":\"O-2\",\"qty\":5}");
            OutboundRecord out = out(pipeline, InboundRecord.of(wire).withType(TestPojoCodec.TYPE));
            assertThat(out.data()).isEqualTo("{\"id\":\"O-2\",\"qty\":5}");
        }

        @Test
        @DisplayName("passthrough only when the type in is the type out and nothing touched the record")
        void passesThroughOnlyWhenTheTypesMatchAndNothingChanged() {
            TestPojoCodec.Order order = order();
            OutboundRecord same = out(typedPipeline(typedOut(json("orders"))), typed(order));
            // An object is not a wire form, whatever passthrough says: same type in and out
            // still goes through the codec's encoder, which builds the bytes from the object.
            assertThat(same.data()).as("typed in == typed out, no transforms: encoded, never the object")
                    .isInstanceOf(byte[].class);
            assertThat(Payloads.text(same.data())).contains("\"id\":\"O-1\"");
            assertThat(same.type()).isEqualTo(TestPojoCodec.TYPE);

            byte[] wire = Payloads.bytes("{\"id\":\"O-1\",\"qty\":100}");
            OutboundRecord bytes = out(typedPipeline(typedOut(json("orders"))),
                    InboundRecord.of(wire).withType(TestPojoCodec.TYPE));
            assertThat(bytes.data()).as("bytes ARE a wire form: handed through untouched").isSameAs(wire);

            OutboundRecord toText = out(typedPipeline(json("orders")), typed(order()));
            assertThat(toText.data()).as("typed in, text out: re-encoded").isInstanceOf(String.class);

            ConnectorProperties transformed = typedOut(json("orders"));
            TransformStep set = new TransformStep();
            set.setSet(Map.of("status", "SEEN"));
            transformed.setTransforms(List.of(set));
            OutboundRecord edited = out(typedPipeline(transformed), typed(order()));
            assertThat(edited.data()).as("a transform turns AUTO passthrough off")
                    .isInstanceOf(byte[].class);
            assertThat(Payloads.text(edited.data())).contains("\"status\":\"SEEN\"");
            assertThat(edited.type()).isEqualTo(TestPojoCodec.TYPE);

            OutboundRecord fromText = out(typedPipeline(typedOut(json("orders"))),
                    InboundRecord.of("{\"id\":\"T-1\",\"qty\":\"7\"}"));
            assertThat(fromText.data()).as("text in, typed out: rebuilt through the codec")
                    .isInstanceOf(byte[].class);
            assertThat(Payloads.text(fromText.data())).isEqualTo("{\"id\":\"T-1\",\"qty\":7}");
        }

        @Test
        @DisplayName("a rule setting one field reads only the fields it names, and the encoder writes the mutated builder")
        void aRuleReadsOnlyWhatItNamesAndTheEncoderSeesTheBuilder() {
            ConnectorProperties connector = typedOut(json("orders"));
            RuleProperties rule = new RuleProperties();
            rule.setName("large");
            rule.setWhen("#f['qty'] >= 100");
            rule.getThen().setSet(Map.of("status", "LARGE"));
            TransformStep rules = new TransformStep();
            rules.setRules(List.of(rule));
            connector.setTransforms(List.of(rules));
            RecordPipeline pipeline = typedPipeline(connector);

            TestPojoCodec.Order order = order();
            OutboundRecord out = out(pipeline, typed(order));

            assertThat(codec.readsByField()).as("only the field the rule named was read")
                    .containsOnlyKeys("qty");
            assertThat(codec.reads("qty")).isEqualTo(1);
            assertThat(codec.copies()).as("the set landed in a copy of the builder").isEqualTo(1);
            assertThat(order.status()).as("the record's own object is untouched").isNull();
            assertThat(Payloads.text(out.data()))
                    .contains("\"status\":\"LARGE\"")
                    .contains("\"party\":{\"name\":\"ACME\",\"role\":\"buyer\"}");
            assertThat(pipeline.ruleSets().get(0).summary()).isEqualTo("rules[large=1]");
        }

        @Test
        @DisplayName("keep yields a plain map, which the codec's encoder rebuilds the object from")
        void keepMaterialisesAndTheEncoderRebuilds() {
            ConnectorProperties connector = typedOut(json("orders"));
            TransformStep keep = new TransformStep();
            keep.setKeep(List.of("id", "qty"));
            connector.setTransforms(List.of(keep));

            OutboundRecord out = out(typedPipeline(connector), typed(order()));
            assertThat(out.data()).isInstanceOf(byte[].class);
            assertThat(Payloads.text(out.data())).isEqualTo("{\"id\":\"O-1\",\"qty\":100}");
        }

        @Test
        @DisplayName("a delete has no out-side payload, and carries the target's type")
        void aDeleteCarriesTheOutTypeAndNoData() {
            ConnectorProperties connector = TestConnectors.withKey(
                    typedOut(json("orders")), KeyProperties.Mode.PUBLISHER, "id");
            MessageContext context = typedPipeline(connector)
                    .apply(InboundRecord.delete(null, "O-9").withType(TestPojoCodec.TYPE));
            assertThat(context).isNotNull();
            assertThat(context.out().command()).isEqualTo(Command.SOW_DELETE);
            assertThat(context.out().data()).isNull();
            assertThat(context.dataOut()).isNull();
            assertThat(context.out().type()).isEqualTo(TestPojoCodec.TYPE);
            assertThat(context.out().sowKey()).isEqualTo("O-9");

            // A delete that carries the object is keyed from it, like an upsert.
            MessageContext bodied = typedPipeline(connector)
                    .apply(InboundRecord.delete(order(), null).withType(TestPojoCodec.TYPE));
            assertThat(bodied.out().sowKey()).isEqualTo("O-1");
        }

        @Test
        @DisplayName("a typed record that names a field its type does not have is rejected")
        void anUnknownFieldOnATypedRecordIsRejected() {
            ConnectorProperties connector = typedOut(json("orders"));
            TransformStep set = new TransformStep();
            set.setSet(Map.of("colour", "blue"));
            connector.setTransforms(List.of(set));
            RecordPipeline pipeline = typedPipeline(connector);
            assertThat(pipeline.apply(typed(order()))).isNull();
            assertThat(pipeline.rejected()).isEqualTo(1);
        }

        @Test
        @DisplayName("the pipeline knows what its out side writes")
        void exposesTheOutType() {
            assertThat(typedPipeline(typedOut(json("orders"))).outType())
                    .isEqualTo(TestPojoCodec.TYPE);
            assertThat(pipeline(json("orders")).outType()).isEqualTo(PayloadType.UNSET);
        }
    }
}
