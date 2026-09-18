package com.demo.amps.qfj2.flow.rules;

import com.demo.amps.qfj2.engine.FixContext;
import com.demo.amps.qfj2.flow.EnrichmentRule;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import quickfix.Message;

/** Writes when the engine received the message, as a FIX UTC timestamp with milliseconds. */
public record ReceivedTimeRule(int tag) implements EnrichmentRule {

    static final DateTimeFormatter UTC_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    public ReceivedTimeRule {
        if (tag <= 0) {
            throw new IllegalArgumentException("tag must be positive: " + tag);
        }
    }

    @Override
    public void apply(Message message, FixContext context) {
        message.setString(tag, UTC_TIMESTAMP.format(context.receivedAt()));
    }

    @Override
    public String describe() {
        return "received-time -> " + tag;
    }
}
