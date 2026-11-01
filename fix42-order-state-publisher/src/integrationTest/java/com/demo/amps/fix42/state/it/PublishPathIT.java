package com.demo.amps.fix42.state.it;

import static com.demo.amps.fix42.state.it.OrderStateFixture.BLOTTER;
import static com.demo.amps.fix42.state.it.OrderStateFixture.EXECS_AUDIT;
import static com.demo.amps.fix42.state.it.OrderStateFixture.MALFORMED;
import static com.demo.amps.fix42.state.it.OrderStateFixture.UNROUTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.crankuptheamps.client.Client;
import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The publish path against a real AMPS, in lenient mode: what the server
 * does with the payloads the design's "verify on build" notes were unsure of,
 * pinned so a later build that behaves differently fails here first
 * (docs/05 phase 3, docs/11 §2).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PublishPathIT {

    private OrderStateFixture fixture;

    @BeforeAll
    void start() throws Exception {
        fixture = OrderStateFixture.start("fix42os-publish-path", null);
        send("35=D|11=P-1|38=1000|44=50|1=ACC|55=SYM|54=1|40=2|59=0|21=1|60=20261031-10:00:00.000|");
        send("35=8|150=0|39=0|11=P-1|37=ORD-P|17=P-E1|38=1000|44=50|14=0|151=1000|6=0|60=20261031-10:00:01.000|");
        fixture.flush();
    }

    @AfterAll
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private void send(String raw) throws Exception {
        fixture.publisher.send(FixMessage.parse(raw.replace('|', '\u0001')));
    }

    private FixMessage record(String key) throws Exception {
        return fixture.sow.record(BLOTTER, FixTags.Record.ROOT_CL_ORD_ID, key);
    }

    private static byte[] bytes(String raw) {
        return raw.replace('|', '\u0001').getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    @Order(1)
    @DisplayName("pin: a payload without its key, or with it empty, is filed under ONE empty-key record, never refused")
    void keylessPayloadsCollapseOntoTheEmptyKey() throws Exception {
        long refusedBefore = fixture.repairs.refused();
        fixture.publisher.publishRaw("P-1", false, bytes("39=4|151=0|"));
        fixture.publisher.publishRaw("P-1", true, bytes("39=8|55=X|"));
        fixture.flush();
        Thread.sleep(1_000);
        fixture.publisher.drainRepairs();
        // docs/11 F5: acknowledged as persisted, no FailedWriteHandler call --
        // and stored, on a record keyed by the empty value...
        assertThat(fixture.repairs.refused()).isEqualTo(refusedBefore);
        assertThat(fixture.publisher.publishStoreDepth()).isZero();
        List<FixMessage> keyless = fixture.sow.records(BLOTTER, "/13011 IS NULL OR /13011 = ''");
        assertThat(keyless).singleElement().satisfies(r -> assertThat(r.value(FixTags.SYMBOL)).isEqualTo("X"));
        // ... which an empty 13011 lands on too: every keyless payload is one record.
        fixture.publisher.publishRaw("", true, bytes("13011=|39=8|55=Y|"));
        fixture.flush();
        assertThat(fixture.sow.records(BLOTTER, "/39 = '8'")).singleElement()
                .satisfies(r -> assertThat(r.value(FixTags.SYMBOL)).isEqualTo("Y"));
        // The real order was never touched.
        FixMessage record = record("P-1");
        assertThat(record.value(FixTags.ORD_STATUS)).isEqualTo("0");
        assertThat(record.value(FixTags.SYMBOL)).isEqualTo("SYM");
        fixture.reader.sowDelete(BLOTTER, "/39 = '8'", fixture.properties.amps().timeoutMs());
        assertThat(fixture.sow.records(BLOTTER, "/39 = '8'")).isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("a 17-less 20=3 reaches the blotter and fix42/unrouted, and not execs_audit (S10)")
    void statusReportWithoutExecId() throws Exception {
        send("35=8|20=3|150=1|39=1|11=P-1|37=ORD-P|38=1000|44=50|14=100|151=900|6=50|60=20261031-10:00:02.000|");
        fixture.flush();
        assertThat(record("P-1").value(FixTags.CUM_QTY)).isEqualTo("100");
        assertThat(fixture.sow.records(EXECS_AUDIT, "/13011 = 'P-1' AND /20 = '3'")).isEmpty();
        List<FixMessage> unrouted = fixture.sow.journal(UNROUTED, 2_000);
        assertThat(unrouted).anySatisfy(message -> {
            assertThat(message.value(FixTags.EXEC_TRANS_TYPE)).isEqualTo("3");
            assertThat(message.value(FixTags.Record.ROOT_CL_ORD_ID)).isEqualTo("P-1");
        });
    }

    @Test
    @Order(3)
    @DisplayName("lenient catch-alls: an unrouted type and unparseable bytes land, journalled, on their topics")
    void catchAllTopics() throws Exception {
        send("35=Q|37=ORD-P|17=P-E1|11=P-1|58=dont know trade|");
        byte[] junk = bytes("35=8|=oops|");
        fixture.publisher.onMessage(junk, 0, junk.length);
        fixture.flush();
        assertThat(fixture.sow.journal(UNROUTED, 2_000)).anySatisfy(message ->
                assertThat(message.msgType()).isEqualTo("Q"));
        List<FixMessage> malformed = fixture.sow.journal(MALFORMED, 2_000);
        assertThat(malformed).singleElement().satisfies(message ->
                assertThat(java.util.Base64.getDecoder().decode(message.value(FixTags.Record.MALFORMED_BYTES)))
                        .isEqualTo(junk));
    }

    @Test
    @Order(4)
    @DisplayName("S3: with every route live, catch-alls included, the publish store drains to zero")
    void storeDrainsWithEveryRouteLive() {
        assertThat(fixture.publisher.publishStoreDepth()).isZero();
    }

    @Test
    @Order(5)
    @DisplayName("pin: a delta_publish to a key with no record is stored as the record")
    void deltaToANewKeyCreatesTheRecord() throws Exception {
        fixture.publisher.publishRaw("NEW-1", false, bytes("13011=NEW-1|39=0|55=NEW|"));
        fixture.flush();
        FixMessage created = record("NEW-1");
        assertThat(created).isNotNull();
        assertThat(created.value(FixTags.SYMBOL)).isEqualTo("NEW");
        assertThat(created.has(FixTags.MSG_TYPE)).as("a record without tag 35 is stored").isFalse();
    }

    @Test
    @Order(6)
    @DisplayName("pin: what a delta carrying an empty value does to the field")
    void emptyValueInADelta() throws Exception {
        fixture.publisher.publishRaw("NEW-1", false, bytes("13011=NEW-1|55=|58=note|"));
        fixture.flush();
        fixture.publisher.drainRepairs();
        FixMessage after = record("NEW-1");
        // Pinned from 5.3.5.135; the machine never writes an empty value, so
        // this matters only to a hand-written payload. See docs/11 §2.
        assertThat(after.value(FixTags.TEXT)).isEqualTo("note");
        // The empty value overwrote the field: present, and empty. A delta CAN
        // blank a field, which the machine never does (RecordWriter writes no
        // empty value) -- the cleared pending set is the literal NONE.
        assertThat(after.has(FixTags.SYMBOL)).as("55 is still on the record").isTrue();
        assertThat(after.value(FixTags.SYMBOL)).as("55 sent empty").isEmpty();
    }

    @Test
    @Order(7)
    @DisplayName("pin: a second logon under the publisher's name is NOT refused -- it forces the first off (docs/11 F6)")
    void sameNameForcesTheFirstSessionOff() throws Exception {
        Client second = new Client(fixture.properties.amps().clientName());
        try {
            second.connect(fixture.server.uri());
            second.logon(fixture.properties.amps().timeoutMs());
            await().atMost(Duration.ofSeconds(20)).until(() ->
                    fixture.server.logs().contains("'in use' client name for the same user id forced a disconnect"));
        } finally {
            second.close();
        }
        // The HAClient reconnects and takes its name back; it still publishes.
        send("35=8|150=1|39=1|11=P-1|37=ORD-P|17=P-E9|38=1000|44=50|14=200|151=800|6=50|32=100|31=50"
                + "|60=20261031-10:00:09.000|");
        fixture.flush();
        assertThat(record("P-1").value(FixTags.CUM_QTY)).isEqualTo("200");
    }
}
