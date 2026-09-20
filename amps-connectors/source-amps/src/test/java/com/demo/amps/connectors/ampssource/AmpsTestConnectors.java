package com.demo.amps.connectors.ampssource;

import com.demo.amps.connectors.config.AmpsSourceProperties;
import com.demo.amps.connectors.config.AmpsTargetProperties;
import com.demo.amps.connectors.config.ConnectorProperties;
import com.demo.amps.connectors.config.SourceFormat;
import com.demo.amps.connectors.config.SourceProperties;

/**
 * The one builder this module's tests need beyond core's {@code TestConnectors}: a connector
 * whose source is an AMPS topic. Lives here rather than in the shared fixtures so the
 * driver's tests do not reach into a file another module's change is editing.
 */
final class AmpsTestConnectors {

    private AmpsTestConnectors() {
    }

    /** A JSON connector subscribing to {@code topic}, publishing onto {@code test/<name>}. */
    static ConnectorProperties amps(String name, String topic) {
        ConnectorProperties connector = new ConnectorProperties();
        connector.setName(name);
        connector.setFormat(SourceFormat.JSON);
        connector.setSource(new SourceProperties());
        AmpsSourceProperties source = new AmpsSourceProperties();
        source.setTopic(topic);
        connector.getSource().setAmps(source);
        AmpsTargetProperties target = new AmpsTargetProperties();
        target.setTopic("test/" + name);
        target.setMessageType("json");
        connector.setAmps(target);
        return connector;
    }
}
