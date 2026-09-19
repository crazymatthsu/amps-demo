/**
 * The raw-TCP driver for {@code amps-connectors}.
 *
 * <p>{@code TcpRecordSource} implements
 * {@link com.demo.amps.connectors.source.RecordSource} over a framed socket,
 * {@code TcpSourceFactory} claims the connectors that configure {@code source.tcp}, and
 * {@code TcpSourceAutoConfiguration} registers it -- the shape every source module has, minus
 * the client library: a framed reader is {@code java.net} plus the framing rules in
 * {@link com.demo.amps.connectors.config.TcpSourceProperties}.
 *
 * <p>A socket has no history and no keys, so this source replays nothing on connect and
 * attaches no {@link com.demo.amps.connectors.source.Acknowledgment}: there is no position to
 * commit and nothing to re-read. {@code LISTEN} accepts any number of clients and reads each
 * on its own thread; {@code CONNECT} redials with backoff.
 */
package com.demo.amps.connectors.tcp;
