package com.demo.amps.connectors.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * Cross-field configuration rules that bean-validation annotations cannot express.
 *
 * <p>These are the combinations that would otherwise fail late and confusingly -- or worse,
 * succeed while quietly publishing the wrong thing. The worst of them is
 * {@code key.mode: PUBLISHER} with no way to produce a key: AMPS does <em>not</em> reject a
 * publish with no SowKey onto a topic declared without a {@code <Key>}. It files the record
 * under a sentinel key and the next keyless publish overwrites it, so an entire feed collapses
 * onto one SOW record and everything downstream looks healthy. That cannot be discovered by
 * reading a log, so it is refused here.
 *
 * <p>Checked once at startup by {@code ConnectorManager.validate()}, so a bad
 * {@code application.yml} stops the application with a readable list instead of a stack trace
 * half an hour later.
 */
public final class ConnectorValidator {

    /** The message types AMPS knows here; the same word goes into the client URI. */
    private static final Set<String> MESSAGE_TYPES = Set.of("json", "fix", "nvfix");

    private ConnectorValidator() {
    }

    /**
     * Validate every connector, plus the rules that span connectors.
     *
     * @param properties the bound configuration
     * @return human-readable problems, empty when the configuration is sound
     */
    public static List<String> validate(ConnectorsProperties properties) {
        List<String> errors = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ConnectorProperties connector : properties.getConnectors()) {
            if (connector.getName() != null && !names.add(connector.getName())) {
                // Names are the AMPS client name and the flow registration id, so a duplicate
                // is two connectors fighting over one publish store and one flow.
                errors.add("duplicate connector name: " + connector.getName());
            }
            errors.addAll(validate(connector));
        }
        return errors;
    }

    /**
     * Validate a single connector.
     *
     * @param connector the connector to check
     * @return human-readable problems, each already prefixed with the connector name
     */
    public static List<String> validate(ConnectorProperties connector) {
        List<String> errors = new ArrayList<>();
        String id = "connector '" + connector.getName() + "': ";
        errors.addAll(validateSource(id, connector));
        errors.addAll(validateTarget(id, connector));
        errors.addAll(validateFilter(id, connector));
        errors.addAll(validateTransforms(id, connector));
        errors.addAll(validateKey(id, connector));
        return errors;
    }

    /**
     * Exactly one transport block, and the rules each transport imposes on the rest of the
     * connector.
     *
     * <p>One block is the rule for {@code SIMULATED} too: the block says what the connector
     * stands in for, so dropping it under a demo profile would make the demo validate
     * configurations the real deployment rejects.
     */
    private static List<String> validateSource(String id, ConnectorProperties connector) {
        List<String> errors = new ArrayList<>();
        SourceProperties source = connector.getSource();
        List<String> blocks = source.configuredBlocks();
        if (blocks.isEmpty()) {
            errors.add(id + "source needs exactly one of tcp/kafka/jdbc/hazelcast, and has none");
            return errors;
        }
        if (blocks.size() > 1) {
            errors.add(id + "source configures " + blocks + ", but a connector feeds one topic "
                    + "from one feed: keep exactly one of tcp/kafka/jdbc/hazelcast");
            return errors;
        }
        KafkaSourceProperties kafka = source.getKafka();
        if (kafka != null && isBlank(kafka.getGroupId())) {
            errors.add(id + "source.kafka.group-id is required: the consumer group is where "
                    + "this connector's position lives, and an unnamed one would re-publish "
                    + "the topic from scratch on every restart");
        }
        errors.addAll(validateJdbc(id, connector));
        return errors;
    }

    /**
     * The {@code source.jdbc} rules: the format the synthesised payload forces, and the two
     * settings that only one poll mode can mean anything to.
     */
    private static List<String> validateJdbc(String id, ConnectorProperties connector) {
        JdbcSourceProperties jdbc = connector.getSource().getJdbc();
        if (jdbc == null) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        if (connector.getFormat() != SourceFormat.JSON) {
            errors.add(id + "source.jdbc requires format: JSON -- a result-set row has no wire "
                    + "format of its own, so the source serialises each one as a JSON object "
                    + "keyed by column label, and " + connector.getFormat() + " has nothing to "
                    + "parse");
        }
        if (jdbc.getMode() == JdbcSourceProperties.Mode.INCREMENTAL) {
            if (isBlank(jdbc.getIncrementalColumn())) {
                errors.add(id + "source.jdbc.mode: INCREMENTAL requires "
                        + "source.jdbc.incremental-column: without a column that says what is "
                        + "new, every poll would re-emit the entire query");
            }
            if (!jdbc.getKeyColumns().isEmpty()) {
                errors.add(id + "source.jdbc.key-columns is only meaningful for mode: SNAPSHOT "
                        + "-- an incremental poll never selects the rows that did not change, "
                        + "so it cannot tell a deleted row from an untouched one");
            }
        } else if (!isBlank(jdbc.getIncrementalColumn())) {
            errors.add(id + "source.jdbc.incremental-column is only meaningful for "
                    + "mode: INCREMENTAL -- a snapshot poll re-runs the whole query and emits "
                    + "every row it returns");
        } else if (jdbc.getKeyColumns().isEmpty()
                && connector.getAmps().getOnDelete() == AmpsTargetProperties.OnDelete.SOW_DELETE) {
            errors.add(id + "source.jdbc.mode: SNAPSHOT with amps.on-delete: SOW_DELETE needs "
                    + "source.jdbc.key-columns: without a stable key the source cannot notice "
                    + "that a row stopped appearing, so nothing would ever be deleted -- set "
                    + "the key columns, or say amps.on-delete: IGNORE and mean it");
        }
        return errors;
    }

    /** The {@code amps:} block: the topic, the message type and the batching. */
    private static List<String> validateTarget(String id, ConnectorProperties connector) {
        List<String> errors = new ArrayList<>();
        AmpsTargetProperties target = connector.getAmps();
        if (isBlank(target.getTopic())) {
            errors.add(id + "amps.topic is required");
        }
        String type = target.getMessageType() == null
                ? ""
                : target.getMessageType().toLowerCase(Locale.ROOT);
        if (!MESSAGE_TYPES.contains(type)) {
            errors.add(id + "amps.message-type '" + target.getMessageType()
                    + "' is not one of json/fix/nvfix -- it names the client URI (/amps/<type>) "
                    + "as well as the encoder, so it has to be spelled the way AMPS does");
        }
        BatchProperties batch = target.getBatch();
        if (batch.getMaxMessages() < 1) {
            errors.add(id + "amps.batch.max-messages must be at least 1");
        }
        if (batch.getFlushInterval() == null || batch.getFlushInterval().isNegative()
                || batch.getFlushInterval().isZero()) {
            errors.add(id + "amps.batch.flush-interval must be positive: it is how long the "
                    + "last record of a quiet feed waits before its partial batch is published");
        }
        return errors;
    }

    /** Each rule names exactly one operator, its operands parse, and the expression compiles. */
    private static List<String> validateFilter(String id, ConnectorProperties connector) {
        FilterProperties filter = connector.getFilter();
        if (filter == null) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        for (FilterRule rule : filter.getRules()) {
            Set<String> operators = rule.configuredOperators();
            if (operators.isEmpty()) {
                errors.add(id + "filter rule on field '" + rule.getField()
                        + "' names no operator, so it says nothing");
            } else if (operators.size() > 1) {
                errors.add(id + "filter rule on field '" + rule.getField() + "' names "
                        + operators + ", but a rule names exactly one operator: which one "
                        + "applied would otherwise depend on an evaluation order nobody can see");
            }
            errors.addAll(numericOperands(id, rule));
            if (rule.getMatches() != null) {
                try {
                    Pattern.compile(rule.getMatches());
                } catch (PatternSyntaxException e) {
                    errors.add(id + "filter rule on field '" + rule.getField()
                            + "': matches '" + rule.getMatches() + "' is not a valid regular "
                            + "expression");
                }
            }
        }
        errors.addAll(expression(id, "filter.expression", filter.getExpression()));
        return errors;
    }

    /** {@code gt/gte/lt/lte} operands are compared as numbers, so they have to be numbers. */
    private static List<String> numericOperands(String id, FilterRule rule) {
        List<String> errors = new ArrayList<>();
        numericOperand(errors, id, rule, "gt", rule.getGt());
        numericOperand(errors, id, rule, "gte", rule.getGte());
        numericOperand(errors, id, rule, "lt", rule.getLt());
        numericOperand(errors, id, rule, "lte", rule.getLte());
        return errors;
    }

    private static void numericOperand(
            List<String> errors, String id, FilterRule rule, String operator, String operand) {
        if (isBlank(operand)) {
            return;
        }
        try {
            Double.parseDouble(operand.trim());
        } catch (NumberFormatException e) {
            errors.add(id + "filter rule on field '" + rule.getField() + "': " + operator
                    + " operand '" + operand + "' is not a number");
        }
    }

    /** Each step names exactly one kind, and every {@code derive} expression parses. */
    private static List<String> validateTransforms(String id, ConnectorProperties connector) {
        List<String> errors = new ArrayList<>();
        for (TransformStep step : connector.getTransforms()) {
            Set<String> kinds = step.configuredKinds();
            if (kinds.isEmpty()) {
                errors.add(id + "a transform step names no kind, so it does nothing");
            } else if (kinds.size() > 1) {
                errors.add(id + "a transform step names " + kinds + ", but a step names exactly "
                        + "one kind: a rename before a keep and a keep before a rename are "
                        + "different programs, and merging them would hide which ran first");
            }
            if (step.getDerive() != null) {
                for (Map.Entry<String, String> derive : step.getDerive().entrySet()) {
                    errors.addAll(expression(
                            id, "derive." + derive.getKey(), derive.getValue()));
                }
            }
            if (step.getBean() != null && step.getBean().isBlank()) {
                errors.add(id + "a transform step names a blank bean");
            }
        }
        return errors;
    }

    /**
     * The key rules -- the ones that decide whether a record can be addressed at all.
     *
     * <p>{@code SERVER} needs fields because they are the only thing it checks: the server
     * derives the key from the payload, and the connector's job is to refuse a payload that
     * does not carry them. {@code PUBLISHER} needs either fields or a source that keys its own
     * messages, because a publish with no SowKey onto a topic declared without a {@code <Key>}
     * is accepted by AMPS and filed under a sentinel key -- every such record overwriting the
     * one before it.
     */
    private static List<String> validateKey(String id, ConnectorProperties connector) {
        List<String> errors = new ArrayList<>();
        AmpsTargetProperties target = connector.getAmps();
        KeyProperties key = target.getKey();
        if (key == null) {
            if (target.getCommand() == AmpsTargetProperties.Command.DELTA_PUBLISH) {
                errors.add(id + "amps.command: DELTA_PUBLISH requires amps.key: there is "
                        + "nothing to merge a partial record onto without one");
            }
            return errors;
        }
        if (key.getMode() == KeyProperties.Mode.SERVER && key.getFields().isEmpty()) {
            errors.add(id + "amps.key.mode: SERVER needs amps.key.fields to know what to "
                    + "reject: the server derives the key from the payload, and without the "
                    + "field names the connector cannot tell that a payload is missing it");
        }
        if (key.getMode() == KeyProperties.Mode.PUBLISHER && key.getFields().isEmpty()
                && !sourceSuppliesKeys(connector.getSource())) {
            errors.add(id + "amps.key.mode: PUBLISHER with no amps.key.fields needs a source "
                    + "that keys its own messages (kafka, or jdbc with key-columns), and "
                    + connector.getSource().describe() + " does not. AMPS accepts a publish "
                    + "with no SowKey onto an unkeyed SOW topic and files it under a sentinel "
                    + "key, so every record would overwrite the one before it");
        }
        for (TransformStep step : connector.getTransforms()) {
            if (step.getKeep() == null) {
                continue;
            }
            Set<String> kept = new LinkedHashSet<>(step.getKeep());
            for (String field : key.getFields()) {
                if (!kept.contains(field)) {
                    errors.add(id + "a keep transform drops key field '" + field
                            + "', so the record could not be keyed afterwards -- add it to keep");
                }
            }
        }
        return errors;
    }

    /** Whether the configured transport attaches a key to each record it delivers. */
    private static boolean sourceSuppliesKeys(SourceProperties source) {
        if (source.getKafka() != null) {
            return true;
        }
        return source.getJdbc() != null && !source.getJdbc().getKeyColumns().isEmpty();
    }

    /** A SpEL expression that does not parse is a startup failure, not a per-record surprise. */
    private static List<String> expression(String id, String where, String text) {
        if (isBlank(text)) {
            return List.of();
        }
        try {
            new SpelExpressionParser().parseExpression(text);
            return List.of();
        } catch (RuntimeException e) {
            return List.of(id + where + " [" + text + "] does not parse: " + e.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
