package com.demo.amps.connectors;

import com.demo.amps.connectors.config.AmpsTargetProperties;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.HazelcastSourceProperties;
import com.demo.amps.connectors.config.JdbcSourceProperties;
import com.demo.amps.connectors.config.KafkaSourceProperties;
import com.demo.amps.connectors.config.KeyProperties;
import com.demo.amps.connectors.config.SourceFormat;
import com.demo.amps.connectors.config.SourceProperties;
import com.demo.amps.connectors.config.TcpSourceProperties;
import java.util.List;

/**
 * Builders for the connector configurations the tests exercise.
 *
 * <p>Every builder returns a connector that would actually <em>validate</em> -- one transport
 * block, a non-blank name, a real target topic -- because most tests are not about
 * configuration and should not have to know what a complete one looks like. A test that IS
 * about configuration takes one of these and breaks the one thing it means to assert on.
 */
public final class TestConnectors {

    /** SOH, the default FIX/NVFIX field separator. */
    public static final char SOH = (char) 0x01;

    private TestConnectors() {
    }

    /** A TCP connector that listens on {@code port} and reads newline-delimited JSON. */
    public static ConnectorProperties tcp(String name, int port) {
        ConnectorProperties connector = base(name);
        TcpSourceProperties tcp = new TcpSourceProperties();
        tcp.setMode(TcpSourceProperties.Mode.LISTEN);
        tcp.setHost("127.0.0.1");
        tcp.setPort(port);
        connector.getSource().setTcp(tcp);
        return connector;
    }

    /** A TCP connector that dials {@code host:port} instead of listening. */
    public static ConnectorProperties connect(String name, String host, int port) {
        ConnectorProperties connector = tcp(name, port);
        TcpSourceProperties tcp = connector.getSource().getTcp();
        tcp.setMode(TcpSourceProperties.Mode.CONNECT);
        tcp.setHost(host);
        return connector;
    }

    /** A Kafka connector on {@code topic}, reading from the beginning as {@code groupId}. */
    public static ConnectorProperties kafka(String name, String topic, String groupId) {
        ConnectorProperties connector = base(name);
        KafkaSourceProperties kafka = new KafkaSourceProperties();
        kafka.setBootstrapServers("localhost:9092");
        kafka.setTopic(topic);
        kafka.setGroupId(groupId);
        connector.getSource().setKafka(kafka);
        return connector;
    }

    /** A JDBC connector polling {@code query} against {@code url} in SNAPSHOT mode. */
    public static ConnectorProperties jdbc(String name, String url, String query) {
        ConnectorProperties connector = base(name);
        JdbcSourceProperties jdbc = new JdbcSourceProperties();
        jdbc.setUrl(url);
        jdbc.setQuery(query);
        connector.getSource().setJdbc(jdbc);
        return connector;
    }

    /** A Hazelcast connector on a plain topic of the default {@code dev} cluster. */
    public static ConnectorProperties hazelcast(String name, String topic) {
        ConnectorProperties connector = base(name);
        HazelcastSourceProperties hazelcast = new HazelcastSourceProperties();
        hazelcast.setTopic(topic);
        connector.getSource().setHazelcast(hazelcast);
        return connector;
    }

    /** A Hazelcast connector on an {@code IMap} of the default {@code dev} cluster. */
    public static ConnectorProperties hazelcastMap(String name, String map) {
        ConnectorProperties connector = base(name);
        HazelcastSourceProperties hazelcast = new HazelcastSourceProperties();
        hazelcast.setMap(map);
        connector.getSource().setHazelcast(hazelcast);
        return connector;
    }

    /**
     * A connector with no transport at all, driven by the in-process generator: the cheapest
     * way to put real records through the whole pipeline.
     */
    public static ConnectorProperties simulated(String name) {
        ConnectorProperties connector = base(name);
        connector.getSource().setDriver(SourceProperties.Driver.SIMULATED);
        return connector;
    }

    /** The same connector, keyed on {@code fields} in {@code mode}. */
    public static ConnectorProperties withKey(
            ConnectorProperties connector, KeyProperties.Mode mode, String... fields) {
        KeyProperties key = new KeyProperties();
        key.setMode(mode);
        key.setFields(List.of(fields));
        connector.getAmps().setKey(key);
        return connector;
    }

    /**
     * The same connector reading {@code format}, with the message type moved to match -- which
     * is what makes {@code passthrough: AUTO} actually pass through.
     */
    public static ConnectorProperties withFormat(
            ConnectorProperties connector, SourceFormat format) {
        connector.setFormat(format);
        connector.getAmps().setMessageType(switch (format) {
            case FIX -> "fix";
            case NVFIX -> "nvfix";
            // TEXT decodes to a single `text` field, so json is the only thing to encode it as.
            case JSON, TEXT -> "json";
        });
        return connector;
    }

    /** Render {@code tag=value} pairs as a SOH-delimited FIX/NVFIX payload. */
    public static String delimited(String... tagsAndValues) {
        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < tagsAndValues.length; i += 2) {
            payload.append(tagsAndValues[i]).append('=').append(tagsAndValues[i + 1]).append(SOH);
        }
        return payload.toString();
    }

    /** A named JSON connector publishing onto {@code test/<name>}, with no transport chosen yet. */
    private static ConnectorProperties base(String name) {
        ConnectorProperties connector = new ConnectorProperties();
        connector.setName(name);
        connector.setFormat(SourceFormat.JSON);
        connector.setSource(new SourceProperties());
        AmpsTargetProperties amps = new AmpsTargetProperties();
        amps.setTopic("test/" + name);
        amps.setMessageType("json");
        connector.setAmps(amps);
        return connector;
    }
}
