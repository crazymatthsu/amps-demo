/**
 * The AMPS driver for {@code amps-connectors}: an AMPS topic read as a feed.
 *
 * <p>{@code AmpsRecordSource} implements
 * {@link com.demo.amps.connectors.source.RecordSource} over the 60East {@code HAClient},
 * {@code AmpsSourceFactory} claims the connectors that configure {@code source.amps}, and
 * {@code AmpsSourceAutoConfiguration} registers it.
 *
 * <p>It is the transport that turns the framework back on itself: a connector whose source
 * and target are both AMPS is a bridge -- between two instances, or between two topics of one
 * -- and the framework's own control channel is a connector of this kind with no target at
 * all. Which of the three subscription shapes a connector uses ({@code subscribe},
 * {@code sow_and_subscribe} with out-of-focus deletes, or a bookmark replay of the
 * transaction log) is configuration, and the rules for each live with it
 * ({@link com.demo.amps.connectors.config.AmpsSourceProperties}).
 *
 * <p>The package is {@code ampssource} rather than {@code amps} because core already owns
 * {@code com.demo.amps.connectors.amps} for the publish side, and a package split across two
 * jars is the kind of thing that works until the day it does not.
 */
package com.demo.amps.connectors.ampssource;
