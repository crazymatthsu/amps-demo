package com.demo.amps.common;

import java.util.List;
import java.util.Locale;

/** Minimal console formatting so demo output reads as an explanation, not a log. */
public final class Console {

    private static final int WIDTH = 78;

    private Console() {
    }

    /** Section banner. */
    public static void title(String text) {
        System.out.println();
        System.out.println("=".repeat(WIDTH));
        System.out.println("  " + text);
        System.out.println("=".repeat(WIDTH));
    }

    /** Sub-section heading. */
    public static void step(String text) {
        System.out.println();
        System.out.println("-- " + text + " " + "-".repeat(Math.max(0, WIDTH - text.length() - 4)));
    }

    /** Explanatory prose: what the next block of output is meant to show. */
    public static void note(String text) {
        for (String line : wrap(text, WIDTH - 4)) {
            System.out.println("   " + line);
        }
    }

    public static void info(String format, Object... args) {
        System.out.println(args.length == 0 ? format : String.format(Locale.ROOT, format, args));
    }

    public static void kv(String key, Object value) {
        System.out.printf(Locale.ROOT, "   %-28s %s%n", key + ":", value);
    }

    public static void bullet(String format, Object... args) {
        System.out.println("   * " + (args.length == 0 ? format : String.format(Locale.ROOT, format, args)));
    }

    /** Human-readable byte count. */
    public static String bytes(long count) {
        if (count < 1024) {
            return count + " B";
        }
        if (count < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", count / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", count / (1024.0 * 1024.0));
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (current.length() > 0 && current.length() + 1 + word.length() > width) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }
}
