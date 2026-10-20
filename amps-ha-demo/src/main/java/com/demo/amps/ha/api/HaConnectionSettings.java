package com.demo.amps.ha.api;

import java.time.Duration;
import java.util.Objects;

/**
 * How an HA client connects and reconnects: the knobs that are the same for
 * a publisher and a subscriber, with defaults that suit a pair of instances
 * on one network.
 *
 * @param logonTimeout       how long a logon may take before the client gives up on
 *                           that attempt and tries the next server
 * @param reconnectDelay     pause between redial attempts, first connect and
 *                           reconnects alike
 * @param firstConnectBudget how long the FIRST connect keeps trying before
 *                           {@code connect()} throws. Against a pair that is not
 *                           there at all the client would otherwise block forever.
 *                           Once connected, reconnects never give up: an outage is
 *                           what an HA client is for
 * @param heartbeat          heartbeat interval. A killed server does not always
 *                           close the TCP connection in a way the client notices at
 *                           once (a port forwarder in the middle may hold it open),
 *                           and the heartbeat is what bounds the time to notice: the
 *                           client declares the connection dead after two missed
 *                           intervals. {@link Duration#ZERO} disables it
 * @param memoryStoreBlocks  blocks an in-memory publish store starts with; it grows
 *                           on demand. Only used when no state directory is given
 */
public record HaConnectionSettings(Duration logonTimeout, Duration reconnectDelay, Duration firstConnectBudget,
                                   Duration heartbeat, int memoryStoreBlocks) {

    public static final Duration DEFAULT_LOGON_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_RECONNECT_DELAY = Duration.ofMillis(250);
    public static final Duration DEFAULT_FIRST_CONNECT_BUDGET = Duration.ofSeconds(30);
    public static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(1);
    public static final int DEFAULT_MEMORY_STORE_BLOCKS = 10_000;

    public HaConnectionSettings {
        Objects.requireNonNull(logonTimeout, "logonTimeout");
        Objects.requireNonNull(reconnectDelay, "reconnectDelay");
        Objects.requireNonNull(firstConnectBudget, "firstConnectBudget");
        Objects.requireNonNull(heartbeat, "heartbeat");
        if (logonTimeout.isNegative() || logonTimeout.isZero()) {
            throw new IllegalArgumentException("logonTimeout must be positive, not " + logonTimeout);
        }
        if (reconnectDelay.isNegative() || reconnectDelay.isZero()) {
            throw new IllegalArgumentException("reconnectDelay must be positive, not " + reconnectDelay);
        }
        if (firstConnectBudget.isNegative() || firstConnectBudget.isZero()) {
            throw new IllegalArgumentException("firstConnectBudget must be positive, not " + firstConnectBudget);
        }
        if (heartbeat.isNegative()) {
            throw new IllegalArgumentException("heartbeat must not be negative, not " + heartbeat);
        }
        if (memoryStoreBlocks <= 0) {
            throw new IllegalArgumentException("memoryStoreBlocks must be positive, not " + memoryStoreBlocks);
        }
    }

    /** The defaults named on each field. */
    public static HaConnectionSettings defaults() {
        return new HaConnectionSettings(DEFAULT_LOGON_TIMEOUT, DEFAULT_RECONNECT_DELAY, DEFAULT_FIRST_CONNECT_BUDGET,
                DEFAULT_HEARTBEAT, DEFAULT_MEMORY_STORE_BLOCKS);
    }

    public HaConnectionSettings withLogonTimeout(Duration value) {
        return new HaConnectionSettings(value, reconnectDelay, firstConnectBudget, heartbeat, memoryStoreBlocks);
    }

    public HaConnectionSettings withReconnectDelay(Duration value) {
        return new HaConnectionSettings(logonTimeout, value, firstConnectBudget, heartbeat, memoryStoreBlocks);
    }

    public HaConnectionSettings withFirstConnectBudget(Duration value) {
        return new HaConnectionSettings(logonTimeout, reconnectDelay, value, heartbeat, memoryStoreBlocks);
    }

    public HaConnectionSettings withHeartbeat(Duration value) {
        return new HaConnectionSettings(logonTimeout, reconnectDelay, firstConnectBudget, value, memoryStoreBlocks);
    }

    public HaConnectionSettings withMemoryStoreBlocks(int value) {
        return new HaConnectionSettings(logonTimeout, reconnectDelay, firstConnectBudget, heartbeat, value);
    }

    /** The heartbeat in whole seconds, which is the unit the AMPS client takes; {@code 0} when disabled. */
    public int heartbeatSeconds() {
        return (int) Math.max(0, heartbeat.toSeconds());
    }
}
