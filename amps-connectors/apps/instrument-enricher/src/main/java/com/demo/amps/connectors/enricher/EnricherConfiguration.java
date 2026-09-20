package com.demo.amps.connectors.enricher;

import com.demo.amps.connectors.alert.Alerts;
import com.demo.amps.connectors.resource.ResourceRegistry;
import com.demo.amps.connectors.resource.jdbc.JdbcLookupTable;
import com.demo.amps.connectors.transform.RecordTransform;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one bean this application adds to the framework: the transform a connector names
 * with {@code transforms: [ { bean: instrumentEnricher } ]}.
 *
 * <p>The bean <em>name</em> is the contract. {@code TransformRegistry} collects every
 * {@link RecordTransform} in the context by bean name, and the configuration's
 * {@code bean:} step is looked up in that map -- so {@code instrumentEnricher} here and in
 * {@code config/local/streams/instrument-enricher/application.yml} are the same word, and
 * a typo in either is a startup failure that lists the names that do exist.
 *
 * <p>The table is resolved here, eagerly, rather than by the transform per record:
 * {@link ResourceRegistry#lookup} throws for a name that is not registered or a resource of
 * another type, and this method runs while the context is being built, so the application
 * refuses to start with a message naming the resources it does have. The registry has not
 * <em>started</em> the table yet at that point -- it does so before the connectors, in its
 * own lifecycle phase -- which is fine: the transform holds the table and asks it whether it
 * is available on every record.
 *
 * <p>A dependency rule worth knowing: this bean is instantiated while the transform
 * registry is built, and it depends on the resource registry and the alerts. A resource or
 * an alert sink that depended on the transform registry would close that into a cycle, so
 * a resource that wants to raise takes {@link Alerts}, never a transform.
 */
@Configuration(proxyBeanMethods = false)
public class EnricherConfiguration {

    /**
     * @param registry the application's resources, for the table named by
     *     {@code enricher.resource}
     * @param alerts where the enricher reports a miss and an unavailable table
     * @param properties the {@code enricher:} block
     * @return the transform, under the name the configuration's {@code bean:} step uses
     * @throws IllegalArgumentException if no resource has that name, or it is not a
     *     {@link JdbcLookupTable}
     */
    @Bean(name = "instrumentEnricher")
    public RecordTransform instrumentEnricher(
            ResourceRegistry registry, Alerts alerts, EnricherProperties properties) {
        JdbcLookupTable table = registry.lookup(properties.getResource(), JdbcLookupTable.class);
        return new InstrumentEnricher(table, alerts, properties);
    }
}
