package com.demo.amps.qfj2.config;

import com.demo.amps.qfj2.seqno.AmpsReplicatedFileStoreFactory;
import com.demo.amps.qfj2.seqno.AmpsSeqnoReplicator;
import com.demo.amps.qfj2.seqno.SeqnoAdmin;
import com.demo.amps.qfj2.seqno.SeqnoReplicator;
import com.demo.amps.qfj2.seqno.WriteBehindPublisher;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import quickfix.FileStoreFactory;
import quickfix.MessageStoreFactory;
import quickfix.SessionSettings;

/**
 * Wires the sequence-number store: the AMPS replicator, the write-behind
 * publisher, and the factory QuickFIX/J is handed.
 *
 * <p>The replicator is its own bean so a test can replace it (an in-memory
 * one) and the store, the admin tool and the engine all see the same
 * instance. With {@code qfj.seqno.enabled=false} it is
 * {@link SeqnoReplicator#NONE} and QuickFIX/J gets a plain file store.
 */
@Configuration
public class SeqnoStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SeqnoStoreConfiguration.class);

    @Bean(destroyMethod = "close")
    public SeqnoReplicator seqnoReplicator(QfjProperties properties) {
        QfjProperties.Seqno seqno = properties.seqno();
        if (!seqno.enabled()) {
            return SeqnoReplicator.NONE;
        }
        return new AmpsSeqnoReplicator(new AmpsSeqnoReplicator.Settings(seqno.uri(), seqno.topic(),
                seqno.clientName(), seqno.timeoutMs(), Duration.ofMillis(properties.amps().connectWaitMs())));
    }

    @Bean
    public MessageStoreFactory messageStoreFactory(QfjProperties properties, SessionSettings settings,
                                                   SeqnoReplicator replicator) {
        QfjProperties.Seqno seqno = properties.seqno();
        if (!seqno.enabled()) {
            log.warn("qfj.seqno.enabled=false: sequence numbers are kept in the file store only and NOT "
                    + "replicated; a failover to another host needs a manual resequence");
            return new FileStoreFactory(settings);
        }
        WriteBehindPublisher publisher = new WriteBehindPublisher(replicator,
                Duration.ofMillis(seqno.minIntervalMs()), Duration.ofMillis(seqno.retryBackoffMs()),
                Duration.ofSeconds(10));
        String source = seqno.sourceOrHostname();
        log.info("sequence numbers replicated to {} on {} (policy {}, require-amps {}, source '{}')",
                seqno.uri(), seqno.topic(), seqno.recovery(), seqno.requireAmps(), source);
        return new AmpsReplicatedFileStoreFactory(settings, replicator, publisher, seqno.recovery(),
                seqno.requireAmps(), source);
    }

    @Bean
    public SeqnoAdmin seqnoAdmin(SessionSettings settings, SeqnoReplicator replicator, QfjProperties properties) {
        return new SeqnoAdmin(settings, replicator, "admin@" + properties.seqno().sourceOrHostname());
    }
}
