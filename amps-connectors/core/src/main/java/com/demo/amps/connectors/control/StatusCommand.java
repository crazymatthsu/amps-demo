package com.demo.amps.connectors.control;

import com.demo.amps.connectors.alert.Alert;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code status}: say how the connectors and the resources are doing, now rather than at
 * the next status tick.
 *
 * <pre>{@code
 * {"command":"status","requestId":"r-7"}
 * }</pre>
 *
 * <p>Two outputs, for two readers. The log gets the same lines the periodic status log
 * prints, so an operator at the console sees them where they expect to. The alerts channel
 * gets an {@code INFO} alert with code {@code STATUS} whose details carry the same lines as
 * two lists, {@code connectors} and {@code resources} -- which is how a status <em>reply</em>
 * reaches whoever sent the command over a topic: the alerts topic is the application's only
 * outbound channel, and an INFO alert is what a reply is. Nothing else rides in the details,
 * so a reader can count on the shape; the {@code requestId}, when there is one, is in the
 * message.
 */
public final class StatusCommand implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(StatusCommand.class);

    /** The command name, {@code status}. */
    public static final String NAME = "status";

    /** The code of the INFO alert the reply is. */
    public static final String STATUS = "STATUS";

    @Override
    public String command() {
        return NAME;
    }

    @Override
    public void handle(ControlCommand command, CommandContext context) {
        List<String> connectors = lines(
                context.connectors() == null ? "" : context.connectors().status());
        List<String> resources = lines(context.resources().status());
        log.info("[control] connector status:{}", indent(connectors));
        log.info("[control] resource status:{}", indent(resources));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("connectors", connectors);
        details.put("resources", resources);
        String requestId = command.requestId();
        context.alerts().raise(Alert.of(Alert.Severity.INFO, STATUS,
                        connectors.size() + " connector(s), " + resources.size() + " resource(s)"
                                + (requestId == null || requestId.isBlank()
                                        ? "" : " (requestId=" + requestId + ")"))
                .withDetails(details));
    }

    /** The status text as a list of its non-blank lines, trimmed. */
    private static List<String> lines(String status) {
        List<String> lines = new ArrayList<>();
        if (status == null) {
            return lines;
        }
        for (String line : status.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line.trim());
            }
        }
        return lines;
    }

    /** The lines back in the shape the periodic status log prints them. */
    private static String indent(List<String> lines) {
        if (lines.isEmpty()) {
            return " (none)";
        }
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(System.lineSeparator()).append("  ").append(line);
        }
        return text.toString();
    }
}
