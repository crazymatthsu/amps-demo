package com.demo.amps.connectors.ampssource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.JSONMessage;
import com.crankuptheamps.client.Message;
import com.demo.amps.connectors.config.AmpsServerProperties;
import com.demo.amps.connectors.config.AmpsSourceProperties;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.SourceFormat;
import com.demo.amps.connectors.source.Acknowledger;
import com.demo.amps.connectors.source.InboundRecord;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * The AMPS source without an AMPS: what it asks the server for, what it makes of what the
 * server sends, and where it dials.
 *
 * <p>The client's own behaviour -- redialling, resubscribing, the bookmark store -- is
 * 60East's to test. What is worth asserting here is everything the connector decides for
 * itself: the command each mode issues, that {@code oof} rides along with a SOW subscription
 * so removals become deletes, that the message type follows the connector's {@code format},
 * that the subscriber's client name cannot collide with the publisher's, and that the source
 * starts without a server and stops promptly without one.
 */
class AmpsRecordSourceTest {

    private static final String NAME = "orders-bridge";
    private static final String TOPIC = "sow/connectors/orders";

    private static AmpsServerProperties defaults() {
        return new AmpsServerProperties();
    }

    private static ConnectorProperties connector(AmpsSourceProperties.Mode mode) {
        ConnectorProperties connector = AmpsTestConnectors.amps(NAME, TOPIC);
        connector.getSource().getAmps().setMode(mode);
        return connector;
    }

    private static AmpsRecordSource source(ConnectorProperties connector) {
        return new AmpsRecordSource(connector, defaults());
    }

    // ---- the command each mode issues ----------------------------------------------

    @Nested
    @DisplayName("buildCommand")
    class BuildCommand {

        @Test
        @DisplayName("SUBSCRIBE is a plain subscribe: no bookmark, no options, the topic and the timeout")
        void subscribeIsPlain() {
            ConnectorProperties connector = connector(AmpsSourceProperties.Mode.SUBSCRIBE);
            connector.getSource().getAmps().setTimeout(Duration.ofSeconds(7));

            Command command = source(connector).buildCommand();

            assertThat(command.getCommand()).isEqualTo(Message.Command.Subscribe);
            assertThat(command.getTopic()).isEqualTo(TOPIC);
            assertThat(command.getTimeout()).isEqualTo(7_000L);
            assertThat(command.getBookmark()).isNull();
            assertThat(command.getOptions()).isNull();
            assertThat(command.getFilter()).isNull();
        }

        @Test
        @DisplayName("SOW_AND_SUBSCRIBE asks for out-of-focus messages, so a removal becomes a DELETE")
        void sowAndSubscribeAsksForOof() {
            Command command = source(connector(AmpsSourceProperties.Mode.SOW_AND_SUBSCRIBE))
                    .buildCommand();

            assertThat(command.getCommand()).isEqualTo(Message.Command.SOWAndSubscribe);
            assertThat(command.getOptions()).isEqualTo("oof");
            assertThat(command.getBookmark()).isNull();
            // The server's default is one record per round trip.
            assertThat(command.getBatchSize()).isEqualTo(AmpsRecordSource.SOW_BATCH_SIZE);
        }

        @ParameterizedTest(name = "{0} -> bookmark {1}")
        @CsvSource({
            "EPOCH, 0",
            "MOST_RECENT, recent",
            "NOW, 0|1|",
        })
        @DisplayName("BOOKMARK is a subscribe carrying the configured bookmark")
        void bookmarkSubscribesFromTheConfiguredPoint(
                AmpsSourceProperties.Bookmark bookmark, String expected) {
            ConnectorProperties connector = connector(AmpsSourceProperties.Mode.BOOKMARK);
            connector.getSource().getAmps().setBookmark(bookmark);

            Command command = source(connector).buildCommand();

            assertThat(command.getCommand()).isEqualTo(Message.Command.Subscribe);
            assertThat(command.getBookmark()).isEqualTo(expected);
            assertThat(command.getOptions()).isNull();
        }

        @Test
        @DisplayName("the client's own spellings, so a resubscribe resolves them the way the client expects")
        void bookmarksAreTheClientsConstants() {
            assertThat(AmpsRecordSource.bookmarkOf(AmpsSourceProperties.Bookmark.EPOCH))
                    .isEqualTo(Client.Bookmarks.EPOCH);
            assertThat(AmpsRecordSource.bookmarkOf(AmpsSourceProperties.Bookmark.MOST_RECENT))
                    .isEqualTo(Client.Bookmarks.MOST_RECENT);
            assertThat(AmpsRecordSource.bookmarkOf(AmpsSourceProperties.Bookmark.NOW))
                    .isEqualTo(Client.Bookmarks.NOW);
        }

        @Test
        @DisplayName("the default mode is SUBSCRIBE and the default bookmark MOST_RECENT")
        void defaultsAreSubscribeAndMostRecent() {
            AmpsSourceProperties fresh = new AmpsSourceProperties();
            assertThat(fresh.getMode()).isEqualTo(AmpsSourceProperties.Mode.SUBSCRIBE);
            assertThat(fresh.getBookmark()).isEqualTo(AmpsSourceProperties.Bookmark.MOST_RECENT);
            assertThat(fresh.getTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(fresh.getReconnectDelay()).isEqualTo(Duration.ofSeconds(5));
            assertThat(fresh.getServer()).isNull();
        }

        @Test
        @DisplayName("a filter is sent to the server, trimmed, whatever the mode")
        void filterIsApplied() {
            ConnectorProperties connector = connector(AmpsSourceProperties.Mode.BOOKMARK);
            connector.getSource().getAmps().setFilter("  /35 = 'D'  ");

            assertThat(source(connector).buildCommand().getFilter()).isEqualTo("/35 = 'D'");
        }

        @Test
        @DisplayName("a blank filter is no filter")
        void blankFilterIsNoFilter() {
            ConnectorProperties connector = connector(AmpsSourceProperties.Mode.SUBSCRIBE);
            connector.getSource().getAmps().setFilter("   ");

            assertThat(source(connector).buildCommand().getFilter()).isNull();
        }

        @Test
        @DisplayName("configured options are appended verbatim after the mode's own")
        void optionsAreAppendedAfterOof() {
            ConnectorProperties connector = connector(AmpsSourceProperties.Mode.SOW_AND_SUBSCRIBE);
            connector.getSource().getAmps().setOptions("conflation=250ms, no_empties");

            assertThat(source(connector).buildCommand().getOptions())
                    .isEqualTo("oof,conflation=250ms,no_empties");
        }

        @Test
        @DisplayName("options on a plain subscribe are just the options")
        void optionsAloneOnSubscribe() {
            ConnectorProperties connector = connector(AmpsSourceProperties.Mode.SUBSCRIBE);
            connector.getSource().getAmps().setOptions("oof");

            assertThat(source(connector).buildCommand().getOptions()).isEqualTo("oof");
        }

        @Test
        @DisplayName("an operator who spells oof themselves does not get it twice")
        void oofIsNotDuplicated() {
            ConnectorProperties connector = connector(AmpsSourceProperties.Mode.SOW_AND_SUBSCRIBE);
            connector.getSource().getAmps().setOptions("oof,timestamp");

            assertThat(source(connector).buildCommand().getOptions()).isEqualTo("oof,timestamp");
        }
    }

    // ---- what a message becomes ----------------------------------------------------

    @Nested
    @DisplayName("toRecord")
    class ToRecord {

        @ParameterizedTest(name = "command {0}")
        @ValueSource(ints = {Message.Command.SOW, Message.Command.Publish, Message.Command.DeltaPublish})
        @DisplayName("sow, publish and delta_publish are upserts keyed by the SowKey")
        void dataCommandsAreUpserts(int command) {
            InboundRecord record = AmpsRecordSource.toRecord(
                    command, "{\"id\":\"ORD-1\"}", "12345", null, TOPIC);

            assertThat(record).isNotNull();
            assertThat(record.action()).isEqualTo(InboundRecord.Action.UPSERT);
            assertThat(record.data()).isEqualTo("{\"id\":\"ORD-1\"}");
            assertThat(record.key()).isEqualTo("12345");
            assertThat(record.acknowledger()).isSameAs(Acknowledger.NONE);
        }

        @ParameterizedTest(name = "command {0}")
        @ValueSource(ints = {Message.Command.OOF, Message.Command.SOWDelete})
        @DisplayName("oof and sow_delete are deletes that keep the payload and the SowKey")
        void removalCommandsAreDeletes(int command) {
            InboundRecord record = AmpsRecordSource.toRecord(
                    command, "{\"id\":\"ORD-1\"}", "12345", null, TOPIC);

            assertThat(record).isNotNull();
            assertThat(record.action()).isEqualTo(InboundRecord.Action.DELETE);
            // A target that deletes by filter needs the key fields, and the last state of
            // the record is exactly what an out-of-focus message carries.
            assertThat(record.data()).isEqualTo("{\"id\":\"ORD-1\"}");
            assertThat(record.key()).isEqualTo("12345");
        }

        @ParameterizedTest(name = "command {0}")
        @ValueSource(ints = {Message.Command.GroupBegin, Message.Command.GroupEnd,
            Message.Command.Ack, Message.Command.Heartbeat, Message.Command.Unknown})
        @DisplayName("group markers, acks and heartbeats carry no record")
        void controlCommandsAreNothing(int command) {
            assertThat(AmpsRecordSource.toRecord(command, "", null, null, TOPIC)).isNull();
        }

        @Test
        @DisplayName("the topic and the command ride along as attributes; a bookmark only when there is one")
        void attributesNameTheTopicTheCommandAndTheBookmark() {
            InboundRecord live = AmpsRecordSource.toRecord(
                    Message.Command.Publish, "{}", null, null, TOPIC);
            assertThat(live.attributes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    AmpsRecordSource.ATTRIBUTE_TOPIC, TOPIC,
                    AmpsRecordSource.ATTRIBUTE_COMMAND, "publish"));

            InboundRecord replayed = AmpsRecordSource.toRecord(
                    Message.Command.SOWDelete, "{}", "k", "1|9|", TOPIC);
            assertThat(replayed.attributes())
                    .containsEntry(AmpsRecordSource.ATTRIBUTE_COMMAND, "sow_delete")
                    .containsEntry(AmpsRecordSource.ATTRIBUTE_BOOKMARK, "1|9|");

            assertThat(AmpsRecordSource.toRecord(Message.Command.SOW, "{}", "k", "", TOPIC)
                    .attributes()).doesNotContainKey(AmpsRecordSource.ATTRIBUTE_BOOKMARK);
            assertThat(AmpsRecordSource.toRecord(Message.Command.OOF, "{}", "k", null, TOPIC)
                    .attributes()).containsEntry(AmpsRecordSource.ATTRIBUTE_COMMAND, "oof");
            assertThat(AmpsRecordSource.toRecord(Message.Command.DeltaPublish, "{}", "k", null, TOPIC)
                    .attributes()).containsEntry(AmpsRecordSource.ATTRIBUTE_COMMAND, "delta_publish");
        }

        @Test
        @DisplayName("a topic without a SowKey yields an unkeyed record, and a null payload an empty one")
        void journalTopicsHaveNoKey() {
            InboundRecord blank = AmpsRecordSource.toRecord(Message.Command.Publish, null, "  ", null, TOPIC);
            assertThat(blank.key()).isNull();
            assertThat(blank.text()).isEmpty();

            InboundRecord absent = AmpsRecordSource.toRecord(Message.Command.Publish, "{}", null, null, null);
            assertThat(absent.key()).isNull();
            assertThat(absent.attributes()).doesNotContainKey(AmpsRecordSource.ATTRIBUTE_TOPIC);
        }
    }

    // ---- dispatching a message -----------------------------------------------------

    @Nested
    @DisplayName("dispatch")
    class Dispatch {

        private Message message(int command, String data) {
            return new JSONMessage(StandardCharsets.UTF_8.newEncoder(), StandardCharsets.UTF_8.newDecoder())
                    .setCommand(command).setData(data).setTopic(TOPIC).setSowKey("7");
        }

        @Test
        @DisplayName("a handler that throws costs one record, counted, and the next one is delivered")
        void handlerFailureIsCountedAndSurvived() {
            AmpsRecordSource source = source(connector(AmpsSourceProperties.Mode.SUBSCRIBE));
            List<InboundRecord> delivered = new CopyOnWriteArrayList<>();
            AtomicInteger calls = new AtomicInteger();

            source.dispatch(message(Message.Command.Publish, "{\"n\":1}"), record -> {
                if (calls.getAndIncrement() == 0) {
                    throw new IllegalStateException("pipeline rejected it");
                }
                delivered.add(record);
            });
            source.dispatch(message(Message.Command.Publish, "{\"n\":2}"), record -> {
                calls.getAndIncrement();
                delivered.add(record);
            });

            assertThat(source.handlerFailures()).isEqualTo(1);
            assertThat(delivered).singleElement().satisfies(record -> {
                assertThat(record.data()).isEqualTo("{\"n\":2}");
                assertThat(record.key()).isEqualTo("7");
                assertThat(record.attributes()).containsEntry(AmpsRecordSource.ATTRIBUTE_TOPIC, TOPIC);
            });
        }

        @Test
        @DisplayName("group markers never reach the handler")
        void groupMarkersAreDropped() {
            AmpsRecordSource source = source(connector(AmpsSourceProperties.Mode.SOW_AND_SUBSCRIBE));
            List<InboundRecord> delivered = new CopyOnWriteArrayList<>();

            source.dispatch(message(Message.Command.GroupBegin, ""), delivered::add);
            source.dispatch(message(Message.Command.SOW, "{\"n\":1}"), delivered::add);
            source.dispatch(message(Message.Command.GroupEnd, ""), delivered::add);

            assertThat(delivered).singleElement()
                    .satisfies(record -> assertThat(record.attributes())
                            .containsEntry(AmpsRecordSource.ATTRIBUTE_COMMAND, "sow"));
            assertThat(source.handlerFailures()).isZero();
        }

        @Test
        @DisplayName("a message with no topic of its own is attributed to the configured one")
        void topicFallsBackToTheConfiguredOne() {
            AmpsRecordSource source = source(connector(AmpsSourceProperties.Mode.SUBSCRIBE));
            List<InboundRecord> delivered = new CopyOnWriteArrayList<>();
            Message message = new JSONMessage(
                    StandardCharsets.UTF_8.newEncoder(), StandardCharsets.UTF_8.newDecoder())
                    .setCommand(Message.Command.Publish).setData("{}");

            source.dispatch(message, delivered::add);

            assertThat(delivered).singleElement().satisfies(record -> {
                assertThat(record.attributes()).containsEntry(AmpsRecordSource.ATTRIBUTE_TOPIC, TOPIC);
                assertThat(record.key()).isNull();
            });
        }
    }

    // ---- where it dials, and as whom -------------------------------------------------

    @Nested
    @DisplayName("connection")
    class Connection {

        @ParameterizedTest(name = "{0} -> /amps/{1}")
        @CsvSource({"JSON, json", "FIX, fix", "NVFIX, nvfix"})
        @DisplayName("the message type on the URI follows the connector's format")
        void messageTypeFollowsTheFormat(SourceFormat format, String messageType) {
            ConnectorProperties connector = connector(AmpsSourceProperties.Mode.SUBSCRIBE);
            connector.setFormat(format);

            AmpsRecordSource source = source(connector);

            assertThat(source.messageType()).isEqualTo(messageType);
            assertThat(source.uri()).isEqualTo("tcp://localhost:9007/amps/" + messageType);
        }

        @Test
        @DisplayName("TEXT has no AMPS message type and is refused at construction")
        void textIsRefused() {
            ConnectorProperties connector = connector(AmpsSourceProperties.Mode.SUBSCRIBE);
            connector.setFormat(SourceFormat.TEXT);

            assertThatThrownBy(() -> source(connector))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(NAME)
                    .hasMessageContaining("TEXT");
            assertThatThrownBy(() -> AmpsRecordSource.messageTypeOf(SourceFormat.TEXT))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("without a server of its own, the source dials the application's shared block")
        void defaultsToTheSharedServer() {
            AmpsServerProperties defaults = defaults();
            defaults.setHost("amps-1");
            defaults.setPort(9107);
            defaults.setTransport("tcps");

            AmpsRecordSource source = new AmpsRecordSource(
                    connector(AmpsSourceProperties.Mode.SUBSCRIBE), defaults);

            assertThat(source.uri()).isEqualTo("tcps://amps-1:9107/amps/json");
        }

        @Test
        @DisplayName("source.amps.server overrides the address, so a connector can bridge from another instance")
        void serverOverridesTheSharedBlock() {
            AmpsServerProperties defaults = defaults();
            defaults.setHost("amps-1");
            ConnectorProperties connector = connector(AmpsSourceProperties.Mode.SUBSCRIBE);
            connector.setFormat(SourceFormat.FIX);
            AmpsSourceProperties.Endpoint server = new AmpsSourceProperties.Endpoint();
            server.setHost("amps-2");
            server.setPort(9207);
            connector.getSource().getAmps().setServer(server);

            AmpsRecordSource source = new AmpsRecordSource(connector, defaults);

            assertThat(source.uri()).isEqualTo("tcp://amps-2:9207/amps/fix");
            // The endpoint's defaults are the shared block's, so naming just a host works.
            assertThat(new AmpsSourceProperties.Endpoint().uri("json"))
                    .isEqualTo("tcp://localhost:9007/amps/json");
        }

        @Test
        @DisplayName("the subscriber's client name cannot collide with the publisher's on the same instance")
        void clientNameIsDistinctFromThePublishers() {
            AmpsServerProperties defaults = defaults();
            defaults.setClientNamePrefix("bridge");

            AmpsRecordSource source = new AmpsRecordSource(
                    connector(AmpsSourceProperties.Mode.SUBSCRIBE), defaults);

            // AMPS refuses a logon whose name is in use, and the publisher already holds
            // `<prefix>-<connector>` -- a same-instance bridge would never come up.
            assertThat(source.clientName()).isEqualTo("bridge-orders-bridge-source")
                    .isNotEqualTo(defaults.clientName(NAME))
                    .startsWith(defaults.clientName(NAME));
        }

        @Test
        @DisplayName("a connector without a source.amps block cannot be built into this source")
        void requiresTheBlock() {
            ConnectorProperties connector = connector(AmpsSourceProperties.Mode.SUBSCRIBE);
            connector.getSource().setAmps(null);

            assertThatThrownBy(() -> source(connector))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("source.amps");
        }
    }

    // ---- lifecycle without a server ----------------------------------------------------

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        private static int freePort() throws IOException {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            }
        }

        private static Thread connectThread() {
            return Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> (NAME + "-amps").equals(thread.getName()))
                    .findFirst().orElse(null);
        }

        @Test
        @DisplayName("start never throws when AMPS is down, and close stops the retrying promptly")
        void startsWithoutAServerAndClosesCleanly() throws Exception {
            AmpsServerProperties defaults = defaults();
            defaults.setHost("127.0.0.1");
            defaults.setPort(freePort());
            ConnectorProperties connector = connector(AmpsSourceProperties.Mode.SUBSCRIBE);
            // Production backs off for seconds; a test that waited that out would be a slow test.
            connector.getSource().getAmps().setReconnectDelay(Duration.ofMillis(50));
            connector.getSource().getAmps().setTimeout(Duration.ofMillis(500));
            AmpsRecordSource source = new AmpsRecordSource(connector, defaults);
            List<InboundRecord> received = new CopyOnWriteArrayList<>();

            ListAppender<ILoggingEvent> warnings = capture();
            try {
                source.start(received::add);

                assertThat(source.isConnected()).isFalse();
                Awaitility.await("the connect thread is running").atMost(Duration.ofSeconds(5))
                        .until(() -> connectThread() != null);
                // The HA client's own connect loop is silent, so the source has to say it:
                // one warning per attempt, the way the other drivers do.
                Awaitility.await("a warning per failed attempt").atMost(Duration.ofSeconds(5))
                        .until(() -> warnings.list.stream()
                                .filter(event -> event.getLevel() == Level.WARN
                                        && event.getFormattedMessage().contains("cannot reach AMPS"))
                                .count() >= 2);

                source.close();

                Awaitility.await("the connect thread has stopped").atMost(Duration.ofSeconds(10))
                        .until(() -> connectThread() == null);
                assertThat(source.isConnected()).isFalse();
                assertThat(received).isEmpty();
                // Idempotent, like every other driver's.
                source.close();
            } finally {
                release(warnings);
            }
        }

        /** Logback's list appender on the source's logger, so the test can read what it said. */
        private static ListAppender<ILoggingEvent> capture() {
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AmpsRecordSource.class))
                    .addAppender(appender);
            return appender;
        }

        private static void release(ListAppender<ILoggingEvent> appender) {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AmpsRecordSource.class))
                    .detachAppender(appender);
            appender.stop();
        }

        @Test
        @DisplayName("close before start is harmless")
        void closeBeforeStart() {
            AmpsRecordSource source = source(connector(AmpsSourceProperties.Mode.BOOKMARK));
            source.close();
            assertThat(source.isConnected()).isFalse();
        }
    }
}
