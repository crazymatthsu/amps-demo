package com.demo.amps.qfj2.engine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import quickfix.ConfigError;
import quickfix.SessionSettings;

/**
 * Loads a QuickFIX/J settings file with {@code ${NAME}} and
 * {@code ${NAME:default}} placeholders resolved first -- from a system
 * property, then an environment variable, then the default.
 *
 * <p>QuickFIX/J's own interpolation knows system properties only and has no
 * default syntax, which is not enough for one settings file to serve both a
 * laptop (venue at 127.0.0.1) and a compose network (venue at
 * {@code venue}). A placeholder with no value and no default is an error
 * that names itself, rather than a host name of {@code ${QFJ_VENUE_HOST}}.
 */
public final class SessionSettingsLoader {

    static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+)(?::([^}]*))?}");

    private SessionSettingsLoader() {
    }

    public static SessionSettings load(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the QuickFIX/J settings file " + file.toAbsolutePath()
                    + ": " + e.getMessage(), e);
        }
        String resolved = resolve(text, System.getenv(), System.getProperties());
        try {
            return new SessionSettings(new ByteArrayInputStream(resolved.getBytes(StandardCharsets.UTF_8)));
        } catch (ConfigError e) {
            throw new IllegalStateException("invalid QuickFIX/J settings in " + file.toAbsolutePath() + ": "
                    + e.getMessage(), e);
        }
    }

    /** Package-visible for the unit test; the lookup order is the contract. */
    static String resolve(String text, Map<String, String> environment, Properties systemProperties) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String fallback = matcher.group(2);
            String value = systemProperties.getProperty(name);
            if (value == null) {
                value = environment.get(name);
            }
            if (value == null) {
                value = fallback;
            }
            if (value == null) {
                throw new IllegalStateException("unresolved ${" + name + "} in the QuickFIX/J settings: set the "
                        + "environment variable or system property, or write it as ${" + name + ":default}");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
