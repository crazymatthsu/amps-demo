/**
 * The Hazelcast driver for {@code amps-connectors}.
 *
 * <p>{@code HazelcastRecordSource} implements
 * {@link com.demo.amps.connectors.source.RecordSource} over a Hazelcast topic,
 * {@code HazelcastSourceFactory} claims the connectors that configure
 * {@code source.hazelcast}, and {@code HazelcastSourceAutoConfiguration} registers it.
 *
 * <p>Always {@code HazelcastClient.newHazelcastClient}, never an embedded member: a connector
 * that joined the cluster would own a share of its partitions, so restarting the connector
 * would migrate data and a connector bug would become a cluster bug.
 *
 * <p>A plain topic is fire-and-forget -- a subscriber sees only what is published after it
 * subscribes -- while a reliable topic is ringbuffer-backed, so {@code reliable-from: OLDEST}
 * replays whatever the buffer still holds. That is the only way this transport recovers
 * messages published while the connector was down; there is still nothing to acknowledge, so
 * it attaches no {@link com.demo.amps.connectors.source.Acknowledgment}.
 */
package com.demo.amps.connectors.hazelcast;
