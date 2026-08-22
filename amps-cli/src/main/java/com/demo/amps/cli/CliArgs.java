package com.demo.amps.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny {@code --flag value} parser, matching the demos in {@code clients/}. */
public final class CliArgs {

    private final Map<String, String> options = new LinkedHashMap<>();
    private final List<String> positional = new ArrayList<>();

    private CliArgs() {
    }

    public static CliArgs parse(String[] argv) {
        CliArgs args = new CliArgs();
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
            args.options.put(name, value);
        }
        return args;
    }

    public String get(String name, String fallback) {
        return options.getOrDefault(name, fallback);
    }

    public int getInt(String name, int fallback) {
        String value = options.get(name);
        return value == null ? fallback : Integer.parseInt(value);
    }

    public long getLong(String name, long fallback) {
        String value = options.get(name);
        return value == null ? fallback : Long.parseLong(value);
    }

    public boolean has(String name) {
        return options.containsKey(name);
    }

    public List<String> positional() {
        return List.copyOf(positional);
    }
}
