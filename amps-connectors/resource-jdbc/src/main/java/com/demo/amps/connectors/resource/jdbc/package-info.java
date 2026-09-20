/**
 * The JDBC resource for {@code amps-connectors}: a query held in memory as a lookup table.
 *
 * <p>{@code JdbcLookupTable} implements
 * {@link com.demo.amps.connectors.resource.AppResource} as one query loaded whole, keyed by
 * its key columns and swapped atomically on every reload; {@code JdbcResourceFactory} claims
 * the {@code resources:} entries that configure {@code jdbc}, and
 * {@code JdbcResourceAutoConfiguration} registers it.
 *
 * <p>This is the resource a code transform enriches from: an {@code instruments} table looked
 * up by the FIX symbol going past, a currency by an account. What makes it a resource rather
 * than a source is the direction of the data -- nothing here flows to a topic; a record from
 * some other feed is what asks -- and that is why the table is a snapshot with a
 * {@code reload}, not a poll with a delete: a failed reload keeps the last good copy rather
 * than turning every record into a miss.
 *
 * <p>The values in a row are the JDBC source's values, converted by the same
 * {@link com.demo.amps.connectors.jdbc.JdbcValues}, which is the reason this module depends
 * on {@code :amps-connectors:source-jdbc}. The rules it obeys live with the configuration it
 * binds ({@link com.demo.amps.connectors.config.JdbcResourceProperties}).
 */
package com.demo.amps.connectors.resource.jdbc;
