package com.demo.amps.clients;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny {@code --flag value} parser, so the demos stay free of framework noise. */
public final class Args {

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

    public boolean getBoolean(String name, boolean fallback) {
        String value = options.get(name);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    public List<String> positional() {
        return List.copyOf(positional);
    }

    public String positional(int index, String fallback) {
        return index < positional.size() ? positional.get(index) : fallback;
    }
}
