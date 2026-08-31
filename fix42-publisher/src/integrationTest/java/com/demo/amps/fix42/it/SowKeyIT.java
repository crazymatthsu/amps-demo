package com.demo.amps.fix42.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixTags;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * What a subscriber can learn about the key the chaining key generator made.
 *
 * <p>The publisher never sees this key -- it sends tags 11 and 41 and lets the
 * server resolve the record. A consumer is in a different position: it can read
 * the generated key off any delivery, which means it can follow one order chain
 * without resolving 11/41 itself. That is the subscriber-side half of pushing
 * identity into the server, and this class pins the properties that make it
 * usable.
 *
 * <p>Two of them are stronger than the module's documentation promises, so they
 * are asserted rather than assumed -- an AMPS upgrade that changed either would
 * otherwise break consumers silently:
 *
 * <ul>
 *   <li>the key survives a restart, which the persisted chain map is for;</li>
 *   <li>the key is <b>deterministic</b>: the same chain published into a
 *       completely fresh instance produces the same key, so it is derived from
 *       the chain's root identifier rather than being a counter. That makes it
 *       viable as a correlation id across systems, not just a local handle.</li>
 * </ul>
 *
 * <p>Its own server, so the chains here cannot perturb the record counts other
 * suites assert.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SowKeyIT {

    private static final String ORDERS = "sow/parent/orders";
    private static final long TIMEOUT_MS = 10_000;

    private AmpsTestServer server;
    private Client client;
    private SowReader sow;

    @BeforeAll
    void startServer() throws Exception {
        Optional<String> unavailable = AmpsTestServer.unavailableReason();
        assumeThat(unavailable)
                .as("integration test prerequisites: %s", unavailable.orElse(""))
                .isEmpty();

        server = AmpsTestServer.start();
        client = connect(server, "sow-key-it");
        sow = new SowReader(client, TIMEOUT_MS);
    }

    @AfterAll
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("every message of a chain is delivered under ONE generated key")
    void oneKeyPerChainOnLiveDeliveries() throws Exception {
        List<Delivery> deliveries = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch seen = new CountDownLatch(3);

        client.executeAsync(new Command("sow_and_subscribe").setTopic(ORDERS)
                .setFilter("/55 = 'CHAINKEY'"), message -> {
            if (!message.isDataNull()) {
                deliveries.add(new Delivery(message.getSowKey(),
                        FixMessage.parse(message.getData()).value(FixTags.CL_ORD_ID)));
                seen.countDown();
            }
        });
        Thread.sleep(500);

        publishChain("CHAINKEY", "CK-1", "CK-2", "CK-3");
        assertThat(seen.await(20, TimeUnit.SECONDS))
                .as("three deliveries for the three messages").isTrue();

        // Three different ClOrdIDs...
        assertThat(deliveries).extracting(Delivery::clOrdId)
                .containsExactly("CK-1", "CK-2", "CK-3");
        // ...one key, on every one of them.
        assertThat(deliveries).extracting(Delivery::sowKey)
                .as("the chain resolves to a single record identity")
                .containsOnly(deliveries.getFirst().sowKey());
        assertThat(deliveries.getFirst().sowKey()).isNotBlank();

        // And the FIRST delivery already carried it: the key belongs to the
        // chain's root, not to whichever message happened to arrive last.
        assertThat(deliveries.getFirst().sowKey())
                .isEqualTo(deliveries.getLast().sowKey());
    }

    @Test
    @DisplayName("the key is present on sow, subscribe and delta-subscribe alike")
    void keyIsPresentOnEveryCommandForm() throws Exception {
        List<String> plain = Collections.synchronizedList(new ArrayList<>());
        List<String> delta = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch seen = new CountDownLatch(2);

        // A plain subscribe gets live messages with no snapshot...
        client.executeAsync(new Command("subscribe").setTopic(ORDERS)
                .setFilter("/55 = 'ALLFORMS'"), message -> {
            if (!message.isDataNull()) {
                plain.add(message.getSowKey());
                seen.countDown();
            }
        });
        // ...and a delta subscriber receives only the fields that changed, yet
        // still needs to know which record they belong to.
        client.executeAsync(new Command("sow_and_delta_subscribe").setTopic(ORDERS)
                .setFilter("/55 = 'ALLFORMS'"), message -> {
            if (!message.isDataNull() && message.getCommand() == Message.Command.Publish) {
                delta.add(message.getSowKey());
            }
        });
        Thread.sleep(500);

        publishChain("ALLFORMS", "AF-1", "AF-2", null);
        assertThat(seen.await(20, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(700);

        String fromQuery = keyOf("ALLFORMS");
        assertThat(plain).as("plain subscribe").isNotEmpty().containsOnly(fromQuery);
        assertThat(delta).as("delta subscribe").isNotEmpty().containsOnly(fromQuery);
    }

    @Test
    @DisplayName("the key is an opaque token: unsigned 64-bit, so never parse it as a long")
    void keyIsAnOpaqueStringNotALong() throws Exception {
        publishChain("OPAQUE", "OP-1", "OP-2", null);
        String key = keyOf("OPAQUE");

        assertThat(key).isNotBlank().containsOnlyDigits();

        // The value is UNSIGNED 64-bit. Observed in an ordinary run:
        // 13877596816621223565, which is larger than Long.MAX_VALUE, so
        // Long.parseLong on it throws NumberFormatException. Whether any given
        // key overflows depends on the hash, which is exactly why a consumer
        // must not gamble on it -- carry the key as a String end to end.
        java.math.BigInteger value = new java.math.BigInteger(key);
        assertThat(value.signum()).isNotNegative();
        assertThat(value.bitLength())
                .as("a 64-bit unsigned value: representable as BigInteger or a string, "
                        + "not reliably as a signed long")
                .isLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("the key is a usable handle: a query by it returns that record")
    void keyCanBeQueriedBack() throws Exception {
        publishChain("HANDLE", "HD-1", "HD-2", null);
        String key = keyOf("HANDLE");

        List<FixMessage> found = new ArrayList<>();
        Command byKey = new Command("sow").setTopic(ORDERS).setSowKeys(key).setTimeout(TIMEOUT_MS);
        try (MessageStream stream = client.execute(byKey)) {
            for (Message message : stream) {
                if (message.getCommand() == Message.Command.GroupEnd) {
                    break;
                }
                if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                    found.add(FixMessage.parse(message.getData()));
                }
            }
        }

        assertThat(found).hasSize(1);
        // The latest ClOrdID of the chain, reached without naming any ClOrdID.
        assertThat(found.getFirst().value(FixTags.CL_ORD_ID)).isEqualTo("HD-2");
        assertThat(found.getFirst().value(FixTags.SYMBOL)).isEqualTo("HANDLE");
    }

    @Test
    @DisplayName("different chains get different keys")
    void distinctChainsGetDistinctKeys() throws Exception {
        publishChain("DISTINCTA", "DA-1", "DA-2", null);
        publishChain("DISTINCTB", "DB-1", "DB-2", null);

        assertThat(keyOf("DISTINCTA")).isNotEqualTo(keyOf("DISTINCTB"));
    }

    @Test
    @DisplayName("the key survives a server restart")
    void keySurvivesRestart() throws Exception {
        publishChain("RESTARTED", "RS-1", "RS-2", null);
        String before = keyOf("RESTARTED");

        server.restart();

        // A restart drops every connection, this class's shared client
        // included, and the AMPS client does not silently reconnect a plain
        // Client. Rebuilding it here rather than in the assertion keeps the
        // shared fixture usable for whatever test JUnit runs next -- the
        // alternative is a failure in an unrelated test whose only symptom is
        // a DisconnectedException.
        client.close();
        client = connect(server, "sow-key-it");
        sow = new SowReader(client, TIMEOUT_MS);

        assertThat(keyOf("RESTARTED"))
                .as("the persisted chain map is what keeps the identity, not just the record")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("the key is deterministic: a fresh instance derives the same one")
    void keyIsDeterministicAcrossInstances() throws Exception {
        publishChain("DETERMINISM", "DT-1", "DT-2", null);
        String here = keyOf("DETERMINISM");

        // A second server: new container, empty data directory, no chain map.
        try (AmpsTestServer other = AmpsTestServer.start();
             Client otherClient = connect(other, "sow-key-it-second-instance")) {

            publishChain(otherClient, "DETERMINISM", "DT-1", "DT-2", null);
            String there = keyOf(new SowReader(otherClient, TIMEOUT_MS), "DETERMINISM");

            assertThat(there)
                    .as("the key is derived from the chain's root id, not assigned by a counter")
                    .isEqualTo(here);
        }
    }

    // ---- helpers ------------------------------------------------------------

    private record Delivery(String sowKey, String clOrdId) {
    }

    private static Client connect(AmpsTestServer target, String name) throws Exception {
        Client created = new Client(name);
        try {
            created.connect(target.uri());
            created.logon(TIMEOUT_MS);
        } catch (RuntimeException e) {
            created.close();
            throw e;
        }
        return created;
    }

    /** The generated key of the one record carrying {@code symbol}. */
    private String keyOf(String symbol) throws Exception {
        return keyOf(sow, symbol);
    }

    private String keyOf(SowReader reader, String symbol) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            List<String> keys = reader.sowKeys(ORDERS, "/55 = '" + symbol + "'");
            if (keys.size() == 1) {
                return keys.getFirst();
            }
            assertThat(keys).as("%s must not split across records", symbol).hasSizeLessThan(2);
            Thread.sleep(200);
        }
        throw new AssertionError("no record for " + symbol + " within 8s");
    }

    private void publishChain(String symbol, String first, String second, String third)
            throws Exception {
        publishChain(client, symbol, first, second, third);
    }

    /** A 35=D, then a 35=G and optionally a 35=F, each chaining to the last. */
    private static void publishChain(Client publisher, String symbol, String first, String second,
                                     String third) throws Exception {
        publisher.publish(ORDERS, FixMessage.ofType(FixTags.MsgType.NEW_ORDER_SINGLE)
                .set(FixTags.CL_ORD_ID, first)
                .set(FixTags.SYMBOL, symbol)
                .set(FixTags.SIDE, "1")
                .set(FixTags.ORDER_QTY, 1_000)
                .setDecimal(FixTags.PRICE, 100.00)
                .set(FixTags.TRANSACT_TIME, "20260822-10:00:00.000")
                .build().render());
        publisher.publishFlush(TIMEOUT_MS);

        publisher.deltaPublish(ORDERS, FixMessage.ofType(FixTags.MsgType.ORDER_CANCEL_REPLACE_REQUEST)
                .set(FixTags.CL_ORD_ID, second)
                .set(FixTags.ORIG_CL_ORD_ID, first)
                .set(FixTags.ORDER_QTY, 1_500)
                .set(FixTags.TRANSACT_TIME, "20260822-10:01:00.000")
                .build().render());
        publisher.publishFlush(TIMEOUT_MS);

        if (third != null) {
            publisher.deltaPublish(ORDERS, FixMessage.ofType(FixTags.MsgType.ORDER_CANCEL_REQUEST)
                    .set(FixTags.CL_ORD_ID, third)
                    .set(FixTags.ORIG_CL_ORD_ID, second)
                    .set(FixTags.TRANSACT_TIME, "20260822-10:02:00.000")
                    .build().render());
            publisher.publishFlush(TIMEOUT_MS);
        }
    }
}
