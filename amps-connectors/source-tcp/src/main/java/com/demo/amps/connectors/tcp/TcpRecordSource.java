package com.demo.amps.connectors.tcp;

import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.TcpSourceProperties;
import com.demo.amps.connectors.source.RecordHandler;
import com.demo.amps.connectors.source.RecordSource;
import com.demo.amps.connectors.source.SourceRecord;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RecordSource} over a raw TCP socket.
 *
 * <p>Reads its endpoint and framing from {@code source.tcp}; the wire format stays on the
 * connector, because the framing says where a message ends and the format says what is inside
 * it. Two framings cover the feeds that are worth dialling directly:
 *
 * <table border="1">
 *   <caption>Framing</caption>
 *   <tr><th>{@code framing}</th><th>on the wire</th></tr>
 *   <tr><td>{@code DELIMITED}</td>
 *       <td>messages separated by {@code delimiter}, in the configured charset -- a
 *           multi-byte separator is matched as a byte sequence, so CRLF works</td></tr>
 *   <tr><td>{@code LENGTH_PREFIXED}</td>
 *       <td>a 4-byte big-endian length, then that many payload bytes -- the same framing
 *           AMPS composite message parts use</td></tr>
 * </table>
 *
 * <p>Both directions of the socket are supported and they fail differently, which is why
 * {@code mode} exists at all:
 *
 * <table border="1">
 *   <caption>Mode</caption>
 *   <tr><th>{@code mode}</th><th>what this source does</th><th>{@link #isConnected()}</th></tr>
 *   <tr><td>{@code CONNECT}</td>
 *       <td>dials the peer on one thread and redials with backoff after every drop</td>
 *       <td>the socket is open</td></tr>
 *   <tr><td>{@code LISTEN}</td>
 *       <td>binds the address, accepts any number of clients and reads each on its own
 *           daemon thread</td>
 *       <td>the listener is bound -- a feed nobody has dialled yet is still healthy</td></tr>
 * </table>
 *
 * <h2>A socket has no state to replay</h2>
 *
 * <p>Every frame becomes an {@code UPSERT} {@link SourceRecord} with <strong>no key</strong>
 * and <strong>no {@link com.demo.amps.connectors.source.Acknowledgment}</strong>, and this
 * source never produces a {@code DELETE}. A raw feed carries no per-message key, no notion of
 * a record leaving it, and no position to commit: there is nothing a removal could address and
 * nothing an acknowledgment could rewind to. The only metadata worth carrying is who sent the
 * frame, which rides along as the {@code remote} attribute -- the one thing a {@code LISTEN}
 * connector reading several clients cannot reconstruct afterwards.
 *
 * <p>It follows that a reconnect replays nothing. Where the Kafka source re-seeks and a
 * reliable Hazelcast topic re-reads its ringbuffer, this one simply redials and picks up the
 * live stream: whatever the feed published while the socket was down is gone. A feed that must
 * survive a restart belongs behind a broker, not behind this source.
 *
 * <h2>The handler is allowed to block</h2>
 *
 * <p>Frames are handed to the {@link RecordHandler} on the thread that read them and nothing
 * reads ahead on another thread. That is deliberate: the pipeline runs inside
 * {@link RecordHandler#onRecord}, so a connector that cannot keep up stops reading its socket
 * and TCP's own window pushes back on the feed. Buffering ahead would trade that for an
 * unbounded queue and an eventual heap dump.
 *
 * <p>{@link #start} returns as soon as the reader thread is running, rather than dialling or
 * binding on the caller's thread. A feed that is not listening yet is the same event as one
 * that hung up, and a port that is not free yet is the same event as one that was taken away;
 * all of them belong in the same backoff -- so {@link #isConnected()}, not a thrown
 * {@code start}, is what reports the connection.
 */
public class TcpRecordSource implements RecordSource {

    private static final Logger log = LoggerFactory.getLogger(TcpRecordSource.class);

    /** Attribute carrying the far end of the socket a frame arrived on, as {@code host:port}. */
    public static final String ATTRIBUTE_REMOTE = "remote";

    /**
     * Sanity bound on a {@code LENGTH_PREFIXED} frame. A length this large means the stream
     * is misframed (a wrong endianness, a missed byte) rather than a genuinely huge message,
     * and allocating on it is how a misframe turns into an OutOfMemoryError.
     */
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    /** Read chunk; frames are assembled above it, so this only sets the syscall size. */
    private static final int READ_BUFFER_BYTES = 8192;

    /** How long {@link #close()} waits for the source's threads before giving up on them. */
    private static final long CLOSE_JOIN_MILLIS = 5_000;

    /** Pending connections the OS queues for us between two {@code accept()} calls. */
    private static final int ACCEPT_BACKLOG = 16;

    private final ConnectorProperties connector;
    private final TcpSourceProperties source;
    private final Charset charset;
    private final byte[] delimiter;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /** Monitor the reconnect backoff waits on, so {@link #close()} cuts it short. */
    private final Object backoff = new Object();

    /** {@code LISTEN}: the accepted sockets, so {@link #close()} can unblock every reader. */
    private final List<Socket> clients = new CopyOnWriteArrayList<>();

    /** {@code LISTEN}: the per-client reader threads, so {@link #close()} can join them. */
    private final List<Thread> readers = new CopyOnWriteArrayList<>();

    /** Numbers the reader threads, purely so a stack dump names which client is which. */
    private final AtomicInteger readerCount = new AtomicInteger();

    private volatile Socket socket;
    private volatile ServerSocket server;
    private volatile Thread thread;

    public TcpRecordSource(ConnectorProperties connector) {
        this.connector = connector;
        this.source = connector.getSource().getTcp();
        // Resolved once, here: an unknown charset is a configuration error, and failing at
        // construction reports it against the connector instead of once per redial.
        this.charset = Charset.forName(source.getCharset());
        this.delimiter = source.getDelimiter().getBytes(charset);
    }

    @Override
    public void start(RecordHandler handler) {
        boolean listening = source.getMode() == TcpSourceProperties.Mode.LISTEN;
        log.info("[{}] starting TCP source: {} {}:{} ({} framing, {})", connector.getName(),
                listening ? "listening on" : "dialling", source.getHost(), source.getPort(),
                source.getFraming(), charset.name());
        Thread runner = new Thread(
                listening ? () -> listen(handler) : () -> dial(handler),
                connector.getName() + (listening ? "-tcp-accept" : "-tcp"));
        runner.setDaemon(true);
        this.thread = runner;
        runner.start();
    }

    // ---- CONNECT: dial, read, back off, redial -----------------------------------------

    /**
     * Dial, read until the peer or {@link #close()} ends it, back off, dial again. One
     * iteration of the outer loop is one socket's lifetime.
     */
    private void dial(RecordHandler handler) {
        while (!closed.get()) {
            try (Socket client = new Socket()) {
                this.socket = client;
                if (closed.get()) {
                    // close() publishes `closed` and then reads `socket`; this reads them the
                    // other way round, so between the two at least one of us sees the other.
                    // Without the check, a close landing in this window would leave a socket
                    // nobody can unblock.
                    break;
                }
                client.connect(new InetSocketAddress(source.getHost(), source.getPort()),
                        (int) source.getConnectTimeout().toMillis());
                connected.set(true);
                log.info("[{}] connected to {}:{}",
                        connector.getName(), source.getHost(), source.getPort());
                read(client.getInputStream(), handler, attributesOf(client));
                if (!closed.get()) {
                    log.warn("[{}] {}:{} closed the connection",
                            connector.getName(), source.getHost(), source.getPort());
                }
            } catch (IOException e) {
                if (!closed.get()) {
                    log.error("[{}] TCP source failed on {}:{}", connector.getName(),
                            source.getHost(), source.getPort(), e);
                }
            } finally {
                connected.set(false);
                this.socket = null;
            }
            if (!closed.get()) {
                log.info("[{}] redialling {}:{} in {} (a raw feed replays nothing)",
                        connector.getName(), source.getHost(), source.getPort(),
                        source.getReconnectDelay());
                if (!sleep(source.getReconnectDelay())) {
                    break;
                }
            }
        }
        connected.set(false);
        log.info("[{}] TCP source stopped", connector.getName());
    }

    // ---- LISTEN: bind, accept, one reader thread per client -----------------------------

    /**
     * Bind, accept until {@link #close()} or the listener itself fails, back off, rebind.
     *
     * <p>Same loop as {@link #dial}, for the same reason: a port that is still held by the
     * previous process is a transient condition, not a configuration error, and a connector
     * that gave up on it would have to be restarted by hand just as the port came free.
     */
    private void listen(RecordHandler handler) {
        while (!closed.get()) {
            try (ServerSocket bound = new ServerSocket()) {
                this.server = bound;
                if (closed.get()) {
                    break;
                }
                bound.setReuseAddress(true);
                bound.bind(new InetSocketAddress(source.getHost(), source.getPort()),
                        ACCEPT_BACKLOG);
                connected.set(true);
                log.info("[{}] listening on {}:{}",
                        connector.getName(), source.getHost(), bound.getLocalPort());
                accept(bound, handler);
            } catch (IOException e) {
                if (!closed.get()) {
                    log.error("[{}] TCP listener failed on {}:{}", connector.getName(),
                            source.getHost(), source.getPort(), e);
                }
            } finally {
                connected.set(false);
                this.server = null;
            }
            if (!closed.get()) {
                log.info("[{}] rebinding {}:{} in {}", connector.getName(), source.getHost(),
                        source.getPort(), source.getReconnectDelay());
                if (!sleep(source.getReconnectDelay())) {
                    break;
                }
            }
        }
        connected.set(false);
        log.info("[{}] TCP listener stopped", connector.getName());
    }

    /** Accept forever; {@link #close()} closes the listener under us, which ends the loop. */
    private void accept(ServerSocket bound, RecordHandler handler) throws IOException {
        while (!closed.get()) {
            Socket client = bound.accept();
            if (closed.get()) {
                closeQuietly(client);
                return;
            }
            serve(client, handler);
        }
    }

    /**
     * Give one accepted client its own daemon reader thread.
     *
     * <p>A thread per client rather than a selector loop: the handler is allowed to block (it
     * runs the pipeline), so one slow consumer must not be able to stall the other clients --
     * and a connector reading a handful of feeds is not where NIO's complexity pays for
     * itself.
     */
    private void serve(Socket client, RecordHandler handler) {
        Map<String, String> attributes = attributesOf(client);
        String peer = attributes.get(ATTRIBUTE_REMOTE);
        clients.add(client);
        if (closed.get()) {
            // Accepted just as close() drained the list: nobody else will unblock this one.
            clients.remove(client);
            closeQuietly(client);
            return;
        }
        log.info("[{}] accepted {}", connector.getName(), peer);
        Thread reader = new Thread(() -> {
            try (client) {
                read(client.getInputStream(), handler, attributes);
                if (!closed.get()) {
                    log.info("[{}] {} closed the connection", connector.getName(), peer);
                }
            } catch (IOException e) {
                if (!closed.get()) {
                    log.warn("[{}] read from {} failed", connector.getName(), peer, e);
                }
            } finally {
                clients.remove(client);
                readers.remove(Thread.currentThread());
            }
        }, connector.getName() + "-tcp-" + readerCount.incrementAndGet());
        reader.setDaemon(true);
        readers.add(reader);
        reader.start();
    }

    // ---- framing -------------------------------------------------------------------------

    private void read(InputStream in, RecordHandler handler, Map<String, String> attributes)
            throws IOException {
        if (source.getFraming() == TcpSourceProperties.Framing.LENGTH_PREFIXED) {
            readLengthPrefixed(in, handler, attributes);
        } else {
            readDelimited(in, handler, attributes);
        }
    }

    /**
     * Split the stream on the delimiter's byte sequence.
     *
     * <p>Bytes are accumulated rather than scanned per read, because a frame boundary is not a
     * read boundary: a message arrives split across two reads as readily as three messages
     * arrive in one. The leftover after the last delimiter is carried into the next read.
     */
    private void readDelimited(InputStream in, RecordHandler handler,
            Map<String, String> attributes) throws IOException {
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        byte[] chunk = new byte[READ_BUFFER_BYTES];
        while (!closed.get()) {
            int read = in.read(chunk);
            if (read < 0) {
                return;
            }
            pending.write(chunk, 0, read);
            byte[] buffered = pending.toByteArray();
            int start = 0;
            int index;
            while ((index = indexOf(buffered, start, delimiter)) >= 0) {
                emit(buffered, start, index - start, handler, attributes);
                start = index + delimiter.length;
            }
            pending.reset();
            pending.write(buffered, start, buffered.length - start);
        }
    }

    /** First offset at or after {@code from} where {@code needle} appears, or {@code -1}. */
    private static int indexOf(byte[] haystack, int from, byte[] needle) {
        outer:
        for (int i = from; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Read 4-byte-length-prefixed frames.
     *
     * <p>A length outside {@code 0..}{@value #MAX_FRAME_BYTES} is treated as a protocol error
     * rather than a big message: once the stream is misframed every following length is
     * garbage too, and the only recovery is a fresh connection.
     */
    private void readLengthPrefixed(InputStream in, RecordHandler handler,
            Map<String, String> attributes) throws IOException {
        DataInputStream data = new DataInputStream(new BufferedInputStream(in));
        byte[] header = new byte[Integer.BYTES];
        while (!closed.get()) {
            try {
                data.readFully(header);
            } catch (EOFException e) {
                return;
            }
            int length = ByteBuffer.wrap(header).getInt();
            if (length < 0 || length > MAX_FRAME_BYTES) {
                throw new IOException("frame length " + length + " out of range (0.."
                        + MAX_FRAME_BYTES + "): the stream is misframed");
            }
            if (length == 0) {
                continue;
            }
            byte[] payload = new byte[length];
            data.readFully(payload);
            emit(payload, 0, length, handler, attributes);
        }
    }

    /** Hand one frame over, unless it is empty -- a trailing delimiter is not a message. */
    private void emit(byte[] buffer, int offset, int length, RecordHandler handler,
            Map<String, String> attributes) {
        if (length <= 0) {
            return;
        }
        try {
            handler.onRecord(SourceRecord.of(new String(buffer, offset, length, charset))
                    .withAttributes(attributes));
        } catch (RuntimeException e) {
            // One bad record is not a reason to drop the feed: the pipeline counts it, we
            // keep reading.
            log.error("[{}] failed to handle TCP frame", connector.getName(), e);
        }
    }

    /**
     * The attribute map every frame from one socket carries.
     *
     * <p>Built once per connection, not once per frame: it is the same map for the socket's
     * whole lifetime, and {@link SourceRecord} copies what it is given anyway.
     */
    private static Map<String, String> attributesOf(Socket client) {
        return Map.of(ATTRIBUTE_REMOTE, peerOf(client));
    }

    /** The far end as {@code host:port} -- readable, and stable across a {@code toString()}. */
    private static String peerOf(Socket client) {
        SocketAddress remote = client.getRemoteSocketAddress();
        if (remote instanceof InetSocketAddress inet) {
            String host = inet.getAddress() == null
                    ? inet.getHostString()
                    : inet.getAddress().getHostAddress();
            return host + ":" + inet.getPort();
        }
        return String.valueOf(remote);
    }

    // ---- lifecycle -------------------------------------------------------------------------

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void close() {
        closed.set(true);
        connected.set(false);

        Socket dialled = this.socket;
        this.socket = null;
        // The read blocks with no timeout; closing the socket under it is what unblocks it.
        closeQuietly(dialled);

        ServerSocket bound = this.server;
        this.server = null;
        // Likewise for a blocked accept().
        closeQuietly(bound);

        for (Socket client : clients) {
            closeQuietly(client);
        }
        clients.clear();

        synchronized (backoff) {
            backoff.notifyAll();
        }

        long deadline = System.currentTimeMillis() + CLOSE_JOIN_MILLIS;
        Thread runner = this.thread;
        this.thread = null;
        join(runner, deadline);
        for (Thread reader : readers) {
            join(reader, deadline);
        }
        readers.clear();
    }

    /** Join one thread within the shared close budget; a straggler is logged, not waited on. */
    private void join(Thread runner, long deadline) {
        if (runner == null || runner == Thread.currentThread()) {
            return;
        }
        long remaining = deadline - System.currentTimeMillis();
        try {
            runner.join(Math.max(1L, remaining));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (runner.isAlive()) {
            log.warn("[{}] TCP thread {} did not stop within {}ms",
                    connector.getName(), runner.getName(), CLOSE_JOIN_MILLIS);
        }
    }

    private void closeQuietly(Closeable open) {
        if (open == null) {
            return;
        }
        try {
            open.close();
        } catch (IOException e) {
            log.debug("[{}] TCP close failed", connector.getName(), e);
        }
    }

    /**
     * Wait out the reconnect backoff, returning early when {@link #close()} rings the monitor.
     *
     * @param delay the configured backoff
     * @return {@code false} if the source should stop instead of redialling
     */
    private boolean sleep(Duration delay) {
        synchronized (backoff) {
            if (closed.get()) {
                return false;
            }
            try {
                backoff.wait(Math.max(1L, delay.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !closed.get();
    }
}
