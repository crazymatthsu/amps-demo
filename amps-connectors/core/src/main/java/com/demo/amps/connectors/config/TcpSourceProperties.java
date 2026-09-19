package com.demo.amps.connectors.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

/**
 * The raw-TCP side of one connector: a socket, and how to find the message boundaries in the
 * bytes it delivers.
 *
 * <p>Lives under {@code source.tcp}; its presence is what selects the TCP source
 * ({@link SourceProperties}).
 *
 * <p>A socket has no history and no keys: there is nothing to replay on connect, nothing to
 * acknowledge, and no message key, so a TCP connector's records reach AMPS with whatever key
 * the payload itself carries. {@link Mode#LISTEN} accepts any number of clients and reads each
 * on its own thread; {@link Mode#CONNECT} redials with backoff and replays nothing.
 */
public class TcpSourceProperties {

    /** Which end of the socket this connector is. */
    public enum Mode {
        /** Bind {@link #getHost()}:{@link #getPort()} and read every client that connects. */
        LISTEN,
        /** Dial {@link #getHost()}:{@link #getPort()}, redialling after a drop. */
        CONNECT
    }

    /** How one message is delimited from the next on the wire. */
    public enum Framing {
        /** Messages are separated by {@link #getDelimiter()}. */
        DELIMITED,
        /** Each message is preceded by a 4-byte big-endian length. */
        LENGTH_PREFIXED
    }

    /** Whether this connector dials out or is dialled. */
    @NotNull
    private Mode mode = Mode.CONNECT;

    /** The peer to dial ({@link Mode#CONNECT}) or the address to bind ({@link Mode#LISTEN}). */
    @NotBlank
    private String host = "0.0.0.0";

    /** The port to dial or bind. */
    @Min(1)
    @Max(65535)
    private int port;

    /** How to split the byte stream into messages. */
    @NotNull
    private Framing framing = Framing.DELIMITED;

    /**
     * Message separator for {@link Framing#DELIMITED}. A feed that separates on a control
     * character names it with the YAML escape for that code point, never with the byte itself:
     * a literal control character in a config file does not survive an editor, a diff or a
     * copy-paste.
     *
     * <p>{@code @NotEmpty}, deliberately not {@code @NotBlank}: the commonest delimiter of all
     * is a newline, and a newline <em>is</em> blank. The rule that matters is that the
     * separator exists -- an empty one would frame every byte as its own message.
     */
    @NotEmpty
    private String delimiter = "\n";

    /** Charset the payload bytes are decoded with. */
    @NotBlank
    private String charset = "UTF-8";

    /** Timeout for establishing the socket ({@link Mode#CONNECT} only). */
    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** How long to wait before redialling after a failed or dropped connection. */
    @NotNull
    private Duration reconnectDelay = Duration.ofSeconds(5);

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public Framing getFraming() {
        return framing;
    }

    public void setFraming(Framing framing) {
        this.framing = framing;
    }

    public String getDelimiter() {
        return delimiter;
    }

    public void setDelimiter(String delimiter) {
        this.delimiter = delimiter;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReconnectDelay() {
        return reconnectDelay;
    }

    public void setReconnectDelay(Duration reconnectDelay) {
        this.reconnectDelay = reconnectDelay;
    }
}
