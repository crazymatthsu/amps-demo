package com.demo.amps.connectors.resource.jdbc;

import com.demo.amps.connectors.alert.Alerts;
import com.demo.amps.connectors.config.ResourceProperties;
import com.demo.amps.connectors.resource.AppResource;
import com.demo.amps.connectors.resource.ResourceFactory;

/**
 * Claims the {@code resources:} entries that configure {@code jdbc}.
 *
 * <p>The presence of the block is the whole test: an entry cannot configure two kinds (the
 * validator refuses that), so no further disambiguation is needed or wanted. The resource it
 * builds is named after the entry, which is the contract the auto-configuration checks.
 */
public class JdbcResourceFactory implements ResourceFactory {

    @Override
    public boolean supports(ResourceProperties resource) {
        return resource.getJdbc() != null;
    }

    @Override
    public AppResource create(ResourceProperties resource, Alerts alerts) {
        return new JdbcLookupTable(resource, alerts);
    }
}
