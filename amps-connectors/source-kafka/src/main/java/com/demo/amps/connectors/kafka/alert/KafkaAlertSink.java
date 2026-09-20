package com.demo.amps.connectors.kafka.alert;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.alert.AlertJson;
import com.demo.amps.connectors.alert.AlertSink;
import com.demo.amps.connectors.config.AlertProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces every alert, as JSON, onto one Kafka topic, keyed by the alert's code.
 *
 * <p>The key is the code and not the application or the connector, because the key is what
 * a compacted topic keeps and what a consumer groups by: with {@code code} as the key, a
 * dashboard reading the topic sees the latest of each <em>kind</em> of trouble, and all the
 * {@code UNKNOWN_SYMBOL}s land on one partition in the order they were raised. Everything
 * else -- the application, the connector, the details -- is in the JSON, which is exactly the
 * object {@link AlertJson} writes for the AMPS sink, so a reader of either topic parses one
 * shape.
 *
 * <p>The producer is tuned for an alerts channel rather than a data feed. {@code acks=1}:
 * the leader's word is enough for an alert, and waiting for the ISR would make a slow
 * cluster slow down the alerting about it. {@code max.block.ms=5000}: a producer with no
 * broker to talk to blocks {@code send()} on metadata, and the thread it would block is the
 * alert manager's one delivery thread -- five seconds bounds what a dead broker can cost the
 * AMPS sink beside this one. The {@code properties} passthrough is applied last, so an
 * operator can override any of it, the serialisers included, without a code change.
 *
 * <p>Delivery is asynchronous, and the {@link AlertSink} contract wants failures
 * <em>counted</em> rather than logged and forgotten. So a send that fails is reported twice:
 * at WARN from the producer's callback, with the alert it was carrying, and by the next
 * {@link #flush()} -- which waits for every send in flight and then throws if any of them
 * failed -- so the manager's {@code sink-failures} counter moves, which is how an operator
 * learns the alerts topic is unreachable without reading the log.
 *
 * <p>{@link #start()} constructs the producer; that neither dials a broker nor throws for a
 * broker that is down (only for a configuration that can never work, such as a bootstrap
 * list nothing resolves), and the manager keeps the sink either way, so a first
 * {@link #send} constructs it again lazily. One producer per application, because the
 * application is what the alerts are about.
 */
public final class KafkaAlertSink implements AlertSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaAlertSink.class);

    /** How long {@link #close()} lets the producer finish what it has in flight. */
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    /** How long a send may block on metadata before it fails instead; see the class note. */
    static final String MAX_BLOCK_MS = "5000";

    private final AlertProperties.Kafka kafka;
    private final String application;
    private final Supplier<Producer<String, String>> producerFactory;

    /** Sends that failed since the last {@link #flush()}, from the producer's callbacks. */
    private final AtomicInteger undelivered = new AtomicInteger();
    private final AtomicReference<Exception> lastFailure = new AtomicReference<>();

    private volatile Producer<String, String> producer;

    /**
     * @param kafka the {@code alerts.kafka} block: bootstrap servers, topic, extra properties
     * @param application the application name; makes the client id unique
     */
    public KafkaAlertSink(AlertProperties.Kafka kafka, String application) {
        this(kafka, application, null);
    }

    /**
     * Package-private seam: hands the sink a producer of the test's choosing (a
     * {@code MockProducer}) instead of building a {@link KafkaProducer}.
     *
     * @param kafka the {@code alerts.kafka} block
     * @param application the application name
     * @param producerFactory builds the producer, or {@code null} for a real
     *     {@link KafkaProducer} built from {@link #producerConfig()}
     */
    KafkaAlertSink(
            AlertProperties.Kafka kafka,
            String application,
            Supplier<Producer<String, String>> producerFactory) {
        this.kafka = kafka;
        this.application = application;
        this.producerFactory = producerFactory != null
                ? producerFactory
                : () -> new KafkaProducer<>(producerConfig());
    }

    /**
     * The producer configuration this sink builds its client from.
     *
     * <p>Package-private because it is the honest place to assert what the sink sends to the
     * broker; the {@code properties} passthrough is applied <em>last</em>, so it can override
     * anything here.
     *
     * @return the producer properties, in the order they are applied
     */
    Map<String, Object> producerConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName());
        // Names this application's alerts in the broker's own logs and metrics.
        config.put(ProducerConfig.CLIENT_ID_CONFIG, application + "-alerts");
        // The leader's word is enough for an alert; see the class note.
        config.put(ProducerConfig.ACKS_CONFIG, "1");
        // A dead broker may cost the delivery thread this long per send, and no longer.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS);
        config.putAll(kafka.getProperties());
        return config;
    }

    @Override
    public String name() {
        return "kafka:" + kafka.getTopic();
    }

    @Override
    public void start() {
        ensureProducer();
        log.info("alerts go to Kafka topic '{}' via {} as '{}'", kafka.getTopic(),
                kafka.getBootstrapServers(), application + "-alerts");
    }

    @Override
    public void send(Alert alert) {
        String code = alert.code();
        ensureProducer().send(
                new ProducerRecord<>(kafka.getTopic(), code, AlertJson.write(alert)),
                (metadata, exception) -> {
                    if (exception != null) {
                        undelivered.incrementAndGet();
                        lastFailure.set(exception);
                        log.warn("[{}] alert {} was not delivered: {}", name(), code,
                                exception.toString());
                    }
                });
    }

    /**
     * Wait for every send in flight, then report the ones that failed.
     *
     * @throws IllegalStateException if any send since the last flush failed; the count and
     *     the last cause are in the message, and the count starts over
     */
    @Override
    public void flush() {
        Producer<String, String> current = producer;
        if (current != null) {
            current.flush();
        }
        int failed = undelivered.getAndSet(0);
        if (failed > 0) {
            Exception cause = lastFailure.getAndSet(null);
            throw new IllegalStateException(failed + " alert(s) were not delivered to Kafka "
                    + "topic '" + kafka.getTopic() + "': " + cause, cause);
        }
    }

    @Override
    public void close() {
        Producer<String, String> current = producer;
        producer = null;
        if (current != null) {
            current.close(CLOSE_TIMEOUT);
        }
    }

    /** The producer, built on first use if {@link #start()} did not manage to. */
    private Producer<String, String> ensureProducer() {
        Producer<String, String> current = producer;
        if (current == null) {
            current = producerFactory.get();
            producer = current;
        }
        return current;
    }

    @Override
    public String toString() {
        return "KafkaAlertSink[" + kafka.getTopic() + " via " + kafka.getBootstrapServers() + "]";
    }
}
