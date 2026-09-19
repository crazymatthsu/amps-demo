package com.demo.amps.connectors.tcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.demo.amps.connectors.TestConnectors;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.TcpSourceProperties;
import com.demo.amps.connectors.source.SourceRecord;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The TCP source against loopback sockets the test itself owns.
 *
 * <p>Self-contained on purpose: a socket needs no broker, so the framing rules -- the part
 * that actually goes wrong -- can be exercised against real bytes on a real connection instead
 * of a mock stream. Both directions are covered here, because {@code CONNECT} and
 * {@code LISTEN} fail in different places: one redials, the other accepts.
 */
class TcpRecordSourceTest {

    /** The connector name every test uses; also the prefix of the threads it must not leak. */
    private static final String NAME = "ticks-tcp";

    /** One connection's worth of output. */
    @FunctionalInterface
    private interface Session {
        void serve(OutputStream out) throws IOException;
    }

    /**
     * A loopback server that serves each scripted session on its own connection and then
     * hangs up -- which is what gives the reconnect test something to reconnect to.
     */
    private static final class Feed implements AutoCloseable {

        private final ServerSocket server;
        private final AtomicInteger served = new AtomicInteger();

        Feed(Session... sessions) throws IOException {
            this.server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
            Thread thread = new Thread(() -> {
                for (Session session : sessions) {
                    try (Socket client = server.accept()) {
                        OutputStream out = client.getOutputStream();
                        session.serve(out);
                        out.flush();
                        served.incrementAndGet();
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "loopback-feed");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    // ---- fixtures ------------------------------------------------------------------

    /**
     * A port nothing is listening on right now.
     *
     * <p>Inherently a probe rather than a reservation -- the OS could hand it to somebody else
     * between the close and the bind -- which is exactly why the source rebinds with backoff
     * instead of failing its start.
     */
    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    /** A CONNECT connector dialling loopback:{@code port}, reading newline-delimited frames. */
    private static ConnectorProperties dialling(int port) {
        return impatient(TestConnectors.connect(NAME, "127.0.0.1", port));
    }

    /** A LISTEN connector bound to loopback:{@code port}. */
    private static ConnectorProperties listening(int port) {
        return impatient(TestConnectors.tcp(NAME, port));
    }

    /** Production backoffs are seconds; a test that waited them out would be a slow test. */
    private static ConnectorProperties impatient(ConnectorProperties connector) {
        TcpSourceProperties tcp = connector.getSource().getTcp();
        tcp.setConnectTimeout(Duration.ofSeconds(2));
        tcp.setReconnectDelay(Duration.ofMillis(100));
        return connector;
    }

    private static ConnectorProperties framed(
            ConnectorProperties connector, TcpSourceProperties.Framing framing, String delimiter) {
        TcpSourceProperties tcp = connector.getSource().getTcp();
        tcp.setFraming(framing);
        tcp.setDelimiter(delimiter);
        return connector;
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Frame each payload as a 4-byte big-endian length followed by its bytes. */
    private static byte[] lengthPrefixed(String... frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String frame : frames) {
            byte[] payload = frame.getBytes(StandardCharsets.UTF_8);
            out.writeBytes(ByteBuffer.allocate(Integer.BYTES)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(payload.length)
                    .array());
            out.writeBytes(payload);
        }
        return out.toByteArray();
    }

    private static void awaitRecords(List<SourceRecord> received, int count) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> received.size() >= count);
    }

    /** Thread names this source owns: {@code <name>-tcp}, {@code -tcp-accept}, {@code -tcp-N}. */
    private static List<String> connectorThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.startsWith(NAME + "-tcp"))
                .toList();
    }

    // ---- CONNECT: delimited framing ---------------------------------------------------

    @Test
    @DisplayName("delimited frames arrive as keyless upserts tagged with the remote peer")
    void delimitedFramesBecomeKeylessUpserts() throws Exception {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        try (Feed feed = new Feed(out -> write(out, "{\"px\":1}\n{\"px\":2}\n"));
                TcpRecordSource source = new TcpRecordSource(dialling(feed.port()))) {
            source.start(received::add);
            awaitRecords(received, 2);

            assertThat(received).extracting(SourceRecord::data)
                    .containsExactly("{\"px\":1}", "{\"px\":2}");
            // No key, no ack and never a DELETE: the three things this transport cannot
            // express, because a socket has neither a key space nor a position to rewind to.
            assertThat(received).extracting(SourceRecord::key).containsOnlyNulls();
            assertThat(received).extracting(SourceRecord::ack).containsOnlyNulls();
            assertThat(received).extracting(SourceRecord::action)
                    .containsOnly(SourceRecord.Action.UPSERT);
            assertThat(received).extracting(r -> r.attributes().get("remote"))
                    .containsOnly("127.0.0.1:" + feed.port());
        }
    }

    @Test
    @DisplayName("a frame split across two writes is reassembled, not delivered in halves")
    void aFrameSplitAcrossWritesIsReassembled() throws Exception {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        Session split = out -> {
            write(out, "{\"px\":");
            // Long enough that the reader really does return the partial frame first.
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            write(out, "42}\n");
        };
        try (Feed feed = new Feed(split);
                TcpRecordSource source = new TcpRecordSource(dialling(feed.port()))) {
            source.start(received::add);
            awaitRecords(received, 1);

            assertThat(received).extracting(SourceRecord::data).containsExactly("{\"px\":42}");
        }
    }

    @Test
    @DisplayName("a multi-byte delimiter (CRLF) is matched as a byte sequence")
    void aMultiByteDelimiterSplitsFrames() throws Exception {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        try (Feed feed = new Feed(out -> write(out, "one\r\ntwo\r\nthree\r\n"))) {
            ConnectorProperties connector = framed(dialling(feed.port()),
                    TcpSourceProperties.Framing.DELIMITED, "\r\n");
            try (TcpRecordSource source = new TcpRecordSource(connector)) {
                source.start(received::add);
                awaitRecords(received, 3);

                // Not split on the bare CR or LF, and not carrying either of them along.
                assertThat(received).extracting(SourceRecord::data)
                        .containsExactly("one", "two", "three");
            }
        }
    }

    @Test
    @DisplayName("an empty frame is skipped -- a trailing delimiter is not a message")
    void emptyFramesAreSkipped() throws Exception {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        try (Feed feed = new Feed(out -> write(out, "alpha\n\nbravo\n"));
                TcpRecordSource source = new TcpRecordSource(dialling(feed.port()))) {
            source.start(received::add);
            awaitRecords(received, 2);

            assertThat(received).extracting(SourceRecord::data).containsExactly("alpha", "bravo");
        }
    }

    // ---- CONNECT: length-prefixed framing ---------------------------------------------

    @Test
    @DisplayName("length-prefixed frames are read by their 4-byte big-endian length")
    void lengthPrefixedFramesAreRead() throws Exception {
        byte[] wire = lengthPrefixed("hello", "{\"px\":7}");
        // The prefix really is big-endian: 5 lands in the LAST of the four bytes.
        assertThat(wire[0]).isEqualTo((byte) 0);
        assertThat(wire[1]).isEqualTo((byte) 0);
        assertThat(wire[2]).isEqualTo((byte) 0);
        assertThat(wire[3]).isEqualTo((byte) 5);

        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        try (Feed feed = new Feed(out -> {
            out.write(wire);
            out.flush();
        })) {
            ConnectorProperties connector = framed(dialling(feed.port()),
                    TcpSourceProperties.Framing.LENGTH_PREFIXED, "\n");
            try (TcpRecordSource source = new TcpRecordSource(connector)) {
                source.start(received::add);
                awaitRecords(received, 2);

                assertThat(received).extracting(SourceRecord::data)
                        .containsExactly("hello", "{\"px\":7}");
            }
        }
    }

    // ---- CONNECT: lifecycle ------------------------------------------------------------

    @Test
    @DisplayName("a dropped connection is redialled and the feed resumes")
    void aDroppedConnectionIsRedialled() throws Exception {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        // Two sessions: the server hangs up after the first, which is the EOF the source
        // has to treat as "redial", not as "done".
        try (Feed feed = new Feed(
                out -> write(out, "before\n"),
                out -> write(out, "after\n"));
                TcpRecordSource source = new TcpRecordSource(dialling(feed.port()))) {
            source.start(received::add);
            awaitRecords(received, 2);

            assertThat(received).extracting(SourceRecord::data)
                    .containsExactly("before", "after");
            // Two connections, not one that happened to carry both frames: the redial is the
            // thing under test.
            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> feed.served.get() == 2);
        }
    }

    @Test
    @DisplayName("close() unblocks a blocked read and stops the thread well inside 5s")
    void closeStopsTheReaderThread() throws Exception {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        // The feed sends one frame and then holds the connection open, so the reader is
        // parked inside a read() with no timeout when close() arrives.
        CountDownLatch hangUp = new CountDownLatch(1);
        Session hold = out -> {
            write(out, "alpha\n");
            try {
                hangUp.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try (Feed feed = new Feed(hold)) {
            TcpRecordSource source = new TcpRecordSource(dialling(feed.port()));
            source.start(received::add);
            awaitRecords(received, 1);
            assertThat(source.isConnected()).isTrue();

            long startedAt = System.nanoTime();
            source.close();
            Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(took).as("close() joins within its 5s budget").isLessThan(
                    Duration.ofSeconds(5));
            assertThat(source.isConnected()).isFalse();
            assertThat(connectorThreads()).as("no connector thread is left behind").isEmpty();
            // Idempotent, the way ConnectorManager calls it.
            source.close();
        } finally {
            hangUp.countDown();
        }
    }

    // ---- LISTEN --------------------------------------------------------------------------

    @Test
    @DisplayName("LISTEN is connected once it is bound, before any client has dialled in")
    void listenIsConnectedBeforeAnyClientArrives() throws Exception {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        try (TcpRecordSource source = new TcpRecordSource(listening(freePort()))) {
            source.start(received::add);

            // A feed nobody has dialled yet is healthy, not down: the connector's health is
            // "am I bound", because whether a client shows up is the client's business.
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(source::isConnected);
            assertThat(received).isEmpty();
        }
    }

    @Test
    @DisplayName("LISTEN reads two concurrent clients and tags each frame with its sender")
    void listenReadsTwoConcurrentClients() throws Exception {
        List<SourceRecord> received = new CopyOnWriteArrayList<>();
        int port = freePort();
        try (TcpRecordSource source = new TcpRecordSource(listening(port))) {
            source.start(received::add);
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(source::isConnected);

            try (Socket one = new Socket(InetAddress.getLoopbackAddress(), port);
                    Socket two = new Socket(InetAddress.getLoopbackAddress(), port)) {
                write(one.getOutputStream(), "from-one\n");
                write(two.getOutputStream(), "from-two\n");
                awaitRecords(received, 2);

                assertThat(received).extracting(SourceRecord::data)
                        .containsExactlyInAnyOrder("from-one", "from-two");
                // Each client is read on its own thread, and `remote` is the only thing that
                // says which of them a frame came from once they are interleaved.
                assertThat(received).extracting(r -> r.attributes().get("remote"))
                        .containsExactlyInAnyOrder(
                                "127.0.0.1:" + one.getLocalPort(),
                                "127.0.0.1:" + two.getLocalPort());
            }
        }
        // A reader whose client hung up first leaves on its own, so this is "gone shortly
        // after close()", not "joined by it" -- what matters is that nothing is left behind.
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> connectorThreads().isEmpty());
    }
}
