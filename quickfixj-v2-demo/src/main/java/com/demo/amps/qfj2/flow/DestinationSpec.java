package com.demo.amps.qfj2.flow;

import java.util.List;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * One entry of {@code qfj.flow.destinations}.
 *
 * @param name     unique; appears in logs and counters
 * @param type     amps or fix
 * @param topic    the AMPS topic (type amps)
 * @param session  the session id, e.g. {@code FIX.4.2:DROPCOPY->DOWNSTREAM} (type fix)
 * @param msgTypes if given, only these 35 values are delivered here
 */
public record DestinationSpec(
        String name,
        DestinationType type,
        String topic,
        String session,
        @DefaultValue List<String> msgTypes) {
}
