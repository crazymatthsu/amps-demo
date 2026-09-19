/**
 * The JDBC driver for {@code amps-connectors}.
 *
 * <p>{@code JdbcRecordSource} implements
 * {@link com.demo.amps.connectors.source.RecordSource} as a polled query,
 * {@code JdbcSourceFactory} claims the connectors that configure {@code source.jdbc}, and
 * {@code JdbcSourceAutoConfiguration} registers it.
 *
 * <p>A query is a snapshot, not a stream, so this source is the one that has to invent both
 * the change events and the wire format. Each row becomes a flat JSON object keyed by
 * result-set column label -- which is why a connector on this transport must declare
 * {@code format: JSON} -- and a {@code SNAPSHOT} poll with key columns also emits a
 * {@code DELETE} for every key that stopped appearing, carrying just those columns.
 * {@code INCREMENTAL} reads forward from a watermark and persists it on acknowledgment.
 *
 * <p>The rules it obeys live with the configuration it binds
 * ({@link com.demo.amps.connectors.config.JdbcSourceProperties}).
 */
package com.demo.amps.connectors.jdbc;
