package com.demo.amps.connectors.resource;

import com.demo.amps.connectors.alert.Alert;
import com.demo.amps.connectors.alert.Alerts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Every {@link AppResource} in the application, by name: started before the connectors,
 * stopped after them, and answering "the resource called X, as a Y" to the transforms that
 * hold one.
 *
 * <p>A {@link SmartLifecycle} at phase {@code MAX - 2000}: after the alert manager, so a
 * resource can raise from its own start, and before {@code ConnectorManager} at
 * {@code MAX - 1000}, so a connector's first record finds its lookup table loaded -- and, in
 * reverse, the last record is enriched before the table goes away. The list is fixed at
 * construction; a name that appears twice is refused then rather than resolved by whichever
 * registered last, because a transform holding the wrong {@code instruments} would enrich
 * every record wrongly and no counter would say so.
 *
 * <p>Start is not fail-fast. A resource whose {@link AppResource#start()} throws is logged,
 * alerted as {@code RESOURCE_START_FAILED}, and left to its own retry -- the same rule as a
 * {@code RecordSource} -- while the resources after it still start, because one database
 * being down is no reason for the KDB client beside it to stay unconnected. What the failure
 * <em>does</em> mean is the transform's business: {@link AppResource#isAvailable()} says so,
 * and the status line says so every minute.
 */
public final class ResourceRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ResourceRegistry.class);

    /** Raised, at ERROR, when a resource's start throws; the others still start. */
    public static final String RESOURCE_START_FAILED = "RESOURCE_START_FAILED";

    private final Map<String, AppResource> resources;
    private final Alerts alerts;
    private final Map<String, String> startFailures = new ConcurrentHashMap<>();

    private volatile boolean running;

    /**
     * @param resources every resource, in start order; stop order is the reverse
     * @param alerts where a failed start is reported
     * @throws IllegalStateException if two resources share a name, or one has none
     */
    public ResourceRegistry(List<AppResource> resources, Alerts alerts) {
        Map<String, AppResource> byName = new LinkedHashMap<>();
        for (AppResource resource : resources) {
            String name = resource.name();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("a resource of type "
                        + resource.getClass().getName() + " has no name");
            }
            AppResource other = byName.putIfAbsent(name, resource);
            if (other != null) {
                throw new IllegalStateException("duplicate resource name '" + name + "': "
                        + other.getClass().getName() + " and " + resource.getClass().getName()
                        + " -- a transform asking for it could not know which one it got");
            }
        }
        this.resources = Collections.unmodifiableMap(byName);
        this.alerts = alerts;
    }

    /**
     * The resource called {@code name}, as a {@code type}.
     *
     * <p>Meant to be called once, when the transform that needs it is constructed: both
     * failures are configuration mistakes, and a transform that looks its table up per
     * record would turn a typo into a per-record exception.
     *
     * @param name the resource's name
     * @param type the type the caller needs it as
     * @param <T> that type
     * @return the resource
     * @throws IllegalArgumentException if no resource has that name (the message lists the
     *     ones that exist) or the one that does is not a {@code type}
     */
    public <T> T lookup(String name, Class<T> type) {
        AppResource resource = required(name);
        if (!type.isInstance(resource)) {
            throw new IllegalArgumentException("resource '" + name + "' is a "
                    + resource.getClass().getName() + ", not a " + type.getName());
        }
        return type.cast(resource);
    }

    /**
     * @param name the resource's name
     * @return the resource, if one has that name
     */
    public Optional<AppResource> find(String name) {
        return Optional.ofNullable(resources.get(name));
    }

    /** Every resource's name, in start order. */
    public Set<String> names() {
        return resources.keySet();
    }

    /** Every resource, in start order. */
    public List<AppResource> resources() {
        return List.copyOf(resources.values());
    }

    /**
     * Reload one resource, now, on the calling thread -- what a {@code reload} command with
     * a target does.
     *
     * @param name the resource's name
     * @throws IllegalArgumentException if no resource has that name
     * @throws UnsupportedOperationException if it is not reloadable
     * @throws IllegalStateException if the reload threw; the resource keeps what it had
     */
    public void reload(String name) {
        AppResource resource = required(name);
        if (!resource.isReloadable()) {
            throw new UnsupportedOperationException(
                    "resource '" + name + "' is not reloadable");
        }
        try {
            resource.reload();
            log.info("[{}] reloaded: {}", name, safeStatus(resource));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("resource '" + name + "' failed to reload: " + e, e);
        }
    }

    /**
     * Reload every reloadable resource, in start order, each one whether or not the one
     * before it failed -- what a {@code reload} command with target {@code all} does.
     *
     * @return the names that were reloaded successfully
     * @throws IllegalStateException after the last one, if any of them threw; the message
     *     names each failure
     */
    public List<String> reloadAll() {
        List<String> reloaded = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (AppResource resource : resources.values()) {
            if (!resource.isReloadable()) {
                continue;
            }
            try {
                reload(resource.name());
                reloaded.add(resource.name());
            } catch (RuntimeException e) {
                failures.add(e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(String.join("; ", failures));
        }
        return reloaded;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        if (resources.isEmpty()) {
            return;
        }
        log.info("starting {} resource(s): {}", resources.size(), names());
        for (AppResource resource : resources.values()) {
            try {
                resource.start();
                startFailures.remove(resource.name());
                log.info("[{}] started: {}", resource.name(), safeStatus(resource));
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                startFailures.put(resource.name(), e.toString());
                log.warn("[{}] start failed; the resource stays registered and unavailable",
                        resource.name(), e);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("resource", resource.name());
                details.put("error", e.toString());
                alerts.raise(Alert.of(Alert.Severity.ERROR, RESOURCE_START_FAILED,
                                "resource '" + resource.name() + "' failed to start: " + e)
                        .withDetails(details));
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        List<AppResource> reversed = new ArrayList<>(resources.values());
        Collections.reverse(reversed);
        for (AppResource resource : reversed) {
            try {
                resource.stop();
                log.info("[{}] stopped", resource.name());
            } catch (RuntimeException e) {
                log.warn("[{}] stop failed", resource.name(), e);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** After the alert manager, before the connectors; stop is the reverse. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 2_000;
    }

    /**
     * One line per resource, for the periodic status log; the resource's own line, plus the
     * start failure if it never got going.
     *
     * @return the lines, each on its own line and indented; empty when there are no
     *     resources
     */
    public String status() {
        StringBuilder text = new StringBuilder();
        for (AppResource resource : resources.values()) {
            text.append(System.lineSeparator()).append("  ").append(safeStatus(resource));
            String failure = startFailures.get(resource.name());
            if (failure != null) {
                text.append(" start-failed=\"").append(failure).append('"');
            }
        }
        return text.toString();
    }

    private AppResource required(String name) {
        AppResource resource = resources.get(name);
        if (resource == null) {
            throw new IllegalArgumentException("no resource named '" + name + "'; "
                    + (resources.isEmpty()
                            ? "none are registered"
                            : "the registered ones are " + names()));
        }
        return resource;
    }

    /** A status line that cannot take the status log down with it. */
    private static String safeStatus(AppResource resource) {
        try {
            return resource.status();
        } catch (RuntimeException e) {
            return resource.name() + " status failed: " + e;
        }
    }
}
