package com.demo.amps.connectors.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The upstream side of one connector: which driver runs it, and the settings of whichever
 * transport it speaks.
 *
 * <p>The transport blocks are siblings and exactly one of them is configured (the validator
 * enforces it): the block that is present is what picks the {@code SourceFactory} that builds
 * the connector's source, so naming two would be naming two feeds for one topic.
 *
 * <pre>{@code
 * source:
 *   driver: REAL
 *   kafka: { bootstrap-servers: kafka:9092, topic: orders, group-id: connectors }
 * }</pre>
 *
 * <p>{@code driver: SIMULATED} keeps the block -- it still says what the connector stands in
 * for, and the validator's transport rules go on applying -- but nothing dials it.
 */
public class SourceProperties {

    /** Which implementation runs this source. */
    public enum Driver {
        /** The transport client for whichever block is configured. */
        REAL,
        /** An in-process generator; for demos and tests without a broker. */
        SIMULATED
    }

    @NotNull
    private Driver driver = Driver.REAL;

    /** Settings for the {@link Driver#SIMULATED} generator; ignored by {@link Driver#REAL}. */
    @Valid
    @NotNull
    private SimulatedProperties simulated = new SimulatedProperties();

    /** Raw TCP settings; non-null selects the TCP source. */
    @Valid
    private TcpSourceProperties tcp;

    /** Kafka settings; non-null selects the Kafka source. */
    @Valid
    private KafkaSourceProperties kafka;

    /** JDBC settings; non-null selects the JDBC source. */
    @Valid
    private JdbcSourceProperties jdbc;

    /** Hazelcast settings; non-null selects the Hazelcast source. */
    @Valid
    private HazelcastSourceProperties hazelcast;

    /**
     * Names of the transport blocks this source configures, in a stable order.
     *
     * <p>Used by the validator and by {@code SourceResolver}'s failure message: "which feed did
     * you mean" is the only useful thing to say when zero or several are present.
     *
     * @return the configured block names, e.g. {@code ["kafka"]}
     */
    public List<String> configuredBlocks() {
        List<String> blocks = new ArrayList<>(4);
        if (tcp != null) {
            blocks.add("tcp");
        }
        if (kafka != null) {
            blocks.add("kafka");
        }
        if (jdbc != null) {
            blocks.add("jdbc");
        }
        if (hazelcast != null) {
            blocks.add("hazelcast");
        }
        return blocks;
    }

    /**
     * A short label for this feed, for logs and error messages: the transport and whatever it
     * calls the thing being read.
     *
     * @return e.g. {@code kafka:orders}, {@code tcp:0.0.0.0:5001}, {@code jdbc:snapshot},
     *     {@code hazelcast:topic:events}, {@code hazelcast:map:positions}
     */
    public String describe() {
        if (tcp != null) {
            return "tcp:" + tcp.getHost() + ":" + tcp.getPort();
        }
        if (kafka != null) {
            return "kafka:" + kafka.getTopic();
        }
        if (jdbc != null) {
            // The poll mode rather than the query: a query is a paragraph, and the mode is
            // what actually distinguishes two JDBC connectors in a log line.
            return "jdbc:" + jdbc.getMode().name().toLowerCase(Locale.ROOT);
        }
        if (hazelcast != null) {
            // Which structure, not just which name: a topic and a map of the same name are
            // different feeds with different keys and different deletes, and the label is
            // what a "PUBLISHER mode needs a key" message points at.
            return hazelcast.getMap() != null
                    ? "hazelcast:map:" + hazelcast.getMap()
                    : "hazelcast:topic:" + hazelcast.getTopic();
        }
        return "<no source>";
    }

    public Driver getDriver() {
        return driver;
    }

    public void setDriver(Driver driver) {
        this.driver = driver;
    }

    public SimulatedProperties getSimulated() {
        return simulated;
    }

    public void setSimulated(SimulatedProperties simulated) {
        this.simulated = simulated == null ? new SimulatedProperties() : simulated;
    }

    public TcpSourceProperties getTcp() {
        return tcp;
    }

    public void setTcp(TcpSourceProperties tcp) {
        this.tcp = tcp;
    }

    public KafkaSourceProperties getKafka() {
        return kafka;
    }

    public void setKafka(KafkaSourceProperties kafka) {
        this.kafka = kafka;
    }

    public JdbcSourceProperties getJdbc() {
        return jdbc;
    }

    public void setJdbc(JdbcSourceProperties jdbc) {
        this.jdbc = jdbc;
    }

    public HazelcastSourceProperties getHazelcast() {
        return hazelcast;
    }

    public void setHazelcast(HazelcastSourceProperties hazelcast) {
        this.hazelcast = hazelcast;
    }
}
