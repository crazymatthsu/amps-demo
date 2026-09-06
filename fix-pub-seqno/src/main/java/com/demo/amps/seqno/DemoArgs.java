package com.demo.amps.seqno;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The demo's command line: positional words and {@code --key value} pairs.
 *
 * <p>{@code --key} with no value is a flag ({@code "true"}). Nothing here is
 * clever on purpose; the phases take two or three settings each.
 */
public final class DemoArgs {

    private final List<String> positional = new ArrayList<>();
    private final Map<String, String> options = new LinkedHashMap<>();

    public static DemoArgs parse(String... args) {
        DemoArgs parsed = new DemoArgs();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String key = arg.substring(2);
                int eq = key.indexOf('=');
                if (eq >= 0) {
                    parsed.options.put(key.substring(0, eq), key.substring(eq + 1));
                } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    parsed.options.put(key, args[++i]);
                } else {
                    parsed.options.put(key, "true");
                }
            } else {
                parsed.positional.add(arg);
            }
        }
        return parsed;
    }

    public String positional(int index, String fallback) {
        return index < positional.size() ? positional.get(index) : fallback;
    }

    public String get(String key, String fallback) {
        return options.getOrDefault(key, fallback);
    }

    public int getInt(String key, int fallback) {
        String value = options.get(key);
        return value == null ? fallback : Integer.parseInt(value);
    }

    public boolean has(String key) {
        return options.containsKey(key);
    }
}
