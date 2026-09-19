package com.demo.amps.connectors.hazelcast;

import com.hazelcast.client.HazelcastClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Contributes the Hazelcast driver to any application that depends on this module.
 *
 * <p>An application gets Hazelcast support by adding the dependency and nothing else: the
 * factory joins the {@code SourceResolver}'s list, and a connector selects it by configuring
 * {@code source.hazelcast}. Guarded on the client being on the classpath so that a runner
 * shipping every source module still starts when one of their clients has been excluded.
 */
@AutoConfiguration
@ConditionalOnClass(HazelcastClient.class)
public class HazelcastSourceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HazelcastSourceFactory hazelcastSourceFactory() {
        return new HazelcastSourceFactory();
    }
}
