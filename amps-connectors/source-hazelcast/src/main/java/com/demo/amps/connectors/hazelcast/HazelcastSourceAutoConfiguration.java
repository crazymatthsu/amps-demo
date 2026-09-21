package com.demo.amps.connectors.hazelcast;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.nio.serialization.DataSerializableFactory;
import org.springframework.beans.factory.BeanFactory;
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
 *
 * <p>The source factory is handed the bean factory so that a connector's
 * {@code serialization-factories} -- bean names, by factory id -- can be resolved to the
 * application's {@code DataSerializableFactory} beans when the source is built. Looked up by
 * name and type at that point rather than injected as a collection, because the
 * configuration names them and an application may well register factories no connector reads.
 */
@AutoConfiguration
@ConditionalOnClass(HazelcastClient.class)
public class HazelcastSourceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HazelcastSourceFactory hazelcastSourceFactory(BeanFactory beans) {
        return new HazelcastSourceFactory(
                name -> beans.getBean(name, DataSerializableFactory.class));
    }
}
