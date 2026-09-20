package com.demo.amps.connectors.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One command from the control channel, as JSON on the wire:
 *
 * <pre>{@code
 * {"command":"reload","target":"instruments","to":"instrument-enricher",
 *  "requestId":"r-1","args":{}}
 * }</pre>
 *
 * <p>{@code command} is the only required field: it picks the {@link CommandHandler}. The
 * rest are conventions every handler reads the same way -- {@code target} is what the
 * command is about (a resource name, or {@code all}), {@code to} is which instance should
 * act on it (absent or {@code all} for every instance; see {@link CommandDispatcher}),
 * {@code requestId} is whatever the sender wants echoed in the log and the alerts, and
 * {@code args} carries anything a custom handler needs, as strings. Fields this record does
 * not know are ignored rather than refused, so a newer sender and an older application can
 * still talk.
 *
 * @param command the handler's name, e.g. {@code reload}; never blank
 * @param target what the command is about, or {@code null}
 * @param to which instance it is for, or {@code null} for every instance
 * @param requestId the sender's correlation id, or {@code null}
 * @param args handler-specific arguments, in wire order; never {@code null}
 */
public record ControlCommand(
        String command, String target, String to, String requestId, Map<String, String> args) {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    public ControlCommand {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("a command needs a command");
        }
        command = command.trim();
        args = args == null || args.isEmpty()
                ? Map.of()
                // Not Map.copyOf: an argument is allowed to be null, and wire order is what
                // makes a log line read the way the command was written.
                : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }

    /**
     * Parse one command off the wire.
     *
     * <p>Lenient about what it does not need and strict about what it does: unknown fields
     * are ignored, a missing {@code args} is an empty map, a scalar argument is taken as
     * text -- but the payload has to be a JSON object with a non-blank {@code command}, and
     * anything else is refused with a message that says what was wrong with it.
     *
     * @param json the payload
     * @return the command
     * @throws IllegalArgumentException if the payload is not JSON, not an object, has no
     *     {@code command}, or its {@code args} is not an object of scalars
     */
    public static ControlCommand parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("a command is a JSON object, and this is empty");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "a command is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("a command is a JSON object, not "
                    + (root == null ? "nothing" : kind(root)));
        }
        String command = text(root, "command");
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("a command needs a \"command\" field");
        }
        Map<String, String> args = new LinkedHashMap<>();
        JsonNode argsNode = root.get("args");
        if (argsNode != null && !argsNode.isNull()) {
            if (!argsNode.isObject()) {
                throw new IllegalArgumentException("\"args\" is an object of scalars, not "
                        + kind(argsNode));
            }
            for (Map.Entry<String, JsonNode> arg : argsNode.properties()) {
                JsonNode value = arg.getValue();
                if (value.isContainerNode()) {
                    throw new IllegalArgumentException("\"args\"." + arg.getKey()
                            + " is " + kind(value) + ", and an argument is a scalar");
                }
                args.put(arg.getKey(), value.isNull() ? null : value.asText());
            }
        }
        return new ControlCommand(
                command, text(root, "target"), text(root, "to"), text(root, "requestId"), args);
    }

    /** A field as text, or {@code null} when it is absent or null; a scalar is taken as text. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** What a node is, for a message: {@code an array}, {@code a string}... */
    private static String kind(JsonNode node) {
        String type = node.getNodeType().name().toLowerCase(Locale.ROOT);
        return ("aeiou".indexOf(type.charAt(0)) >= 0 ? "an " : "a ") + type;
    }

    /** {@code true} for a command addressed to no instance in particular. */
    public boolean isBroadcast() {
        return to == null || to.isBlank() || "all".equalsIgnoreCase(to.trim());
    }

    /**
     * The command as a log line names it.
     *
     * @return e.g. {@code reload target=instruments requestId=r-1}
     */
    public String describe() {
        StringBuilder text = new StringBuilder(command);
        if (target != null && !target.isBlank()) {
            text.append(" target=").append(target);
        }
        if (to != null && !to.isBlank()) {
            text.append(" to=").append(to);
        }
        if (requestId != null && !requestId.isBlank()) {
            text.append(" requestId=").append(requestId);
        }
        if (!args.isEmpty()) {
            text.append(" args=").append(args);
        }
        return text.toString();
    }
}
