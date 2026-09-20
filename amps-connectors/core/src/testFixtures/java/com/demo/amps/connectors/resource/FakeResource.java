package com.demo.amps.connectors.resource;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link AppResource} the tests drive by hand: counts its lifecycle calls, is available
 * when told to be, and fails to start or reload when told to.
 *
 * <p>Stands in for every kind of resource at once, which is the point of the SPI: a test
 * about the registry's start order, or about a transform's behaviour when its table is
 * unavailable, has no business loading a database. Not reloadable unless {@link #reloadable}
 * says so, because that is the interface's default and the case most resources are.
 */
public class FakeResource implements AppResource {

    private final String name;
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger stops = new AtomicInteger();
    private final AtomicInteger reloads = new AtomicInteger();

    private volatile boolean reloadable;
    private volatile boolean available;
    private volatile Exception startFailure;
    private volatile Exception reloadFailure;

    public FakeResource(String name) {
        this.name = name;
    }

    /** Make {@link #reload()} supported (and counted). */
    public FakeResource reloadable() {
        this.reloadable = true;
        return this;
    }

    /** Make every {@link #start()} throw {@code failure} and leave the resource unavailable. */
    public FakeResource failStartWith(Exception failure) {
        this.startFailure = failure;
        return this;
    }

    /** Make every {@link #reload()} throw {@code failure}; what was loaded stays. */
    public FakeResource failReloadWith(Exception failure) {
        this.reloadFailure = failure;
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void start() throws Exception {
        starts.incrementAndGet();
        Exception failing = startFailure;
        if (failing != null) {
            throw failing;
        }
        available = true;
    }

    @Override
    public void stop() {
        stops.incrementAndGet();
        available = false;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    /** Fake the backing store going away, or coming back, without a lifecycle call. */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    @Override
    public boolean isReloadable() {
        return reloadable;
    }

    @Override
    public void reload() throws Exception {
        if (!reloadable) {
            throw new UnsupportedOperationException("resource '" + name + "' is not reloadable");
        }
        reloads.incrementAndGet();
        Exception failing = reloadFailure;
        if (failing != null) {
            throw failing;
        }
    }

    @Override
    public String status() {
        return name + (available ? " AVAILABLE" : " UNAVAILABLE")
                + " starts=" + starts.get() + " reloads=" + reloads.get();
    }

    /** How many times the registry started this resource; once is the contract. */
    public int startCount() {
        return starts.get();
    }

    /** How many times it was stopped; once is the contract. */
    public int stopCount() {
        return stops.get();
    }

    /** How many reloads were attempted, failed ones included. */
    public int reloadCount() {
        return reloads.get();
    }
}
