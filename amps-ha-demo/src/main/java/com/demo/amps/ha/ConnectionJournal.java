package com.demo.amps.ha;

import com.crankuptheamps.client.ConnectionStateListener;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every connection state change an {@link com.crankuptheamps.client.HAClient}
 * announces, with the server it concerned.
 *
 * <p>The client reports its states through a listener that receives an int
 * and nothing else. Recording them with a timestamp and the URI the client was
 * on turns "the failover happened" from an impression into a list a test can
 * assert on: disconnected from the primary, logged on to the secondary,
 * publish store replayed, subscriptions re-issued.
 *
 * <p>The listener runs on the client's own threads, sometimes with the
 * client's lock held, so it does nothing but append and log.
 */
public final class ConnectionJournal implements ConnectionStateListener {

    private static final Logger log = LoggerFactory.getLogger(ConnectionJournal.class);

    /** One state change. {@code uri} is set for Connected and LoggedOn only. */
    public record Event(Instant at, int state, String uri) {
        public String name() {
            return ConnectionJournal.name(state);
        }

        @Override
        public String toString() {
            return at + " " + name() + (uri == null ? "" : " " + uri);
        }
    }

    private final String owner;
    private final Supplier<URI> currentUri;
    private final List<Event> events = new ArrayList<>();

    /**
     * @param owner      the client's name, for the log
     * @param currentUri where the client is connected, read at Connected and
     *                   LoggedOn (the client's own {@code getURI})
     */
    public ConnectionJournal(String owner, Supplier<URI> currentUri) {
        this.owner = owner;
        this.currentUri = currentUri;
    }

    @Override
    public void connectionStateChanged(int state) {
        String uri = null;
        if (state == Connected || state == LoggedOn) {
            try {
                URI current = currentUri.get();
                uri = current == null ? null : current.toString();
            } catch (RuntimeException e) {
                uri = null;
            }
        }
        Event event = new Event(Instant.now(), state, uri);
        synchronized (events) {
            events.add(event);
            events.notifyAll();
        }
        log.info("[{}] connection {}{}", owner, event.name(), uri == null ? "" : " " + uri);
    }

    /** A copy of everything recorded so far. */
    public List<Event> events() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    /** How many times {@code state} was announced. */
    public long count(int state) {
        synchronized (events) {
            return events.stream().filter(e -> e.state() == state).count();
        }
    }

    /** The servers logged on to, in order, one entry per LoggedOn event. */
    public List<String> logonUris() {
        synchronized (events) {
            return events.stream()
                    .filter(e -> e.state() == LoggedOn && e.uri() != null)
                    .map(Event::uri)
                    .toList();
        }
    }

    /** The last server logged on to, or {@code null} before the first logon. */
    public String lastLogonUri() {
        List<String> uris = logonUris();
        return uris.isEmpty() ? null : uris.get(uris.size() - 1);
    }

    /**
     * Blocks until a LoggedOn event for a server at {@code hostAndPort}
     * ({@code host:port}) has been recorded -- including one recorded before
     * the call -- or the timeout passes.
     */
    public boolean awaitLoggedOnTo(String hostAndPort, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (events) {
            while (!loggedOnTo(hostAndPort)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                events.wait(Math.max(1, remaining / 1_000_000));
            }
            return true;
        }
    }

    private boolean loggedOnTo(String hostAndPort) {
        return events.stream().anyMatch(e -> e.state() == LoggedOn && e.uri() != null
                && hostAndPort(e.uri()).equals(hostAndPort));
    }

    /** {@code host:port} of a client URI, the part that identifies the instance. */
    public static String hostAndPort(String uri) {
        URI parsed = URI.create(uri);
        return parsed.getHost() + ":" + parsed.getPort();
    }

    /** The listener constants as words. */
    public static String name(int state) {
        return switch (state) {
            case Disconnected -> "Disconnected";
            case Shutdown -> "Shutdown";
            case Connected -> "Connected";
            case LoggedOn -> "LoggedOn";
            case PublishReplayed -> "PublishReplayed";
            case HeartbeatInitiated -> "HeartbeatInitiated";
            case Resubscribed -> "Resubscribed";
            default -> "Unknown(" + state + ")";
        };
    }

    /** The journal as lines, for a report. */
    public String describe() {
        StringBuilder out = new StringBuilder();
        for (Event event : events()) {
            out.append("  ").append(event).append('\n');
        }
        return out.toString();
    }
}
