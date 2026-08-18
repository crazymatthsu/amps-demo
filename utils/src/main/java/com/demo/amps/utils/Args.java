package com.demo.amps.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code --flag value} parsing for the tools.
 *
 * <p>Deliberately separate from the demos' parser in {@code clients}: these are
 * operator tools, so a missing or misspelled option has to fail loudly with a
 * usage message rather than fall back to a default and quietly do the wrong
 * thing to someone's data. Hence {@link #require} and {@link #reject}.
 */
public final class Args {

    /** Thrown when the command line is wrong; the runner turns it into usage text. */
    public static final class UsageException extends RuntimeException {
        public UsageException(String message) {
            super(message);
        }
    }

    private final Map<String, String> options = new LinkedHashMap<>();
    private final List<String> positional = new ArrayList<>();

    private Args() {
    }

    public static Args parse(String[] argv) {
        Args args = new Args();
        for (int i = 0; i < argv.length; i++) {
            String token = argv[i];
            if (!token.startsWith("--")) {
                args.positional.add(token);
                continue;
            }
            String name = token.substring(2);
            String value = "true";
            int equals = name.indexOf('=');
            if (equals >= 0) {
                value = name.substring(equals + 1);
                name = name.substring(0, equals);
            } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                value = argv[++i];
            }
            if (name.isEmpty()) {
                throw new UsageException("bare '--' is not an option");
            }
            args.options.put(name, value);
        }
        return args;
    }

    public String get(String name, String fallback) {
        String value = options.get(name);
        return (value == null || value.isEmpty()) ? fallback : value;
    }

    /** The value, or a usage error naming what was missing. */
    public String require(String name, String whatItIsFor) {
        String value = options.get(name);
        if (value == null || value.isEmpty()) {
            throw new UsageException("--" + name + " is required (" + whatItIsFor + ")");
        }
        return value;
    }

    public int getInt(String name, int fallback) {
        String value = options.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new UsageException("--" + name + " expects a number, got '" + value + "'");
        }
    }

    public boolean has(String name) {
        return options.containsKey(name);
    }

    public List<String> positional() {
        return List.copyOf(positional);
    }

    /** Comma-separated list option, trimmed and empties dropped. */
    public List<String> list(String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    /**
     * Fails if any option outside {@code allowed} was given. A typo in a tool
     * that deletes data should stop it, not be ignored -- {@code --dry-run}
     * silently misspelled is exactly the accident worth preventing.
     */
    public void reject(String... allowed) {
        List<String> known = List.of(allowed);
        for (String name : options.keySet()) {
            if (!known.contains(name)) {
                throw new UsageException("unknown option --" + name
                        + " (known: " + String.join(", ", known) + ")");
            }
        }
    }
}
