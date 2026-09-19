package com.demo.amps.connectors.encode;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;

/**
 * Builds the {@link PayloadEncoder} a connector's {@code amps.message-type} selects.
 *
 * <p>The message type is written the way AMPS spells it, because the same string goes into the
 * client URI ({@code /amps/fix}): one word, one encoder, and no chance of a connector whose
 * connection speaks a different type from its payloads. Anything outside {@code json},
 * {@code fix} and {@code nvfix} is refused here as well as by the validator -- the validator
 * to stop the application with a readable list, this to stop a hand-built pipeline in a test
 * from quietly producing nothing.
 */
public final class PayloadEncoderFactory {

    /** Shared, and never reconfigured: {@link JsonEncoder} only serialises maps. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PayloadEncoderFactory() {
    }

    /**
     * @param messageType {@code json}, {@code fix} or {@code nvfix}
     * @param separator the field separator for the delimited types, normally SOH
     * @return the encoder for that message type
     * @throws IllegalArgumentException if the message type is not one AMPS knows here
     */
    public static PayloadEncoder create(String messageType, char separator) {
        String type = messageType == null ? "" : messageType.toLowerCase(Locale.ROOT).trim();
        return switch (type) {
            case "json" -> new JsonEncoder(MAPPER);
            case "fix" -> new FixEncoder(separator);
            case "nvfix" -> new NvfixEncoder(separator);
            default -> throw new IllegalArgumentException("message-type '" + messageType
                    + "' is not one of json/fix/nvfix");
        };
    }
}
