package com.demo.amps.fix42.state.publish;

import com.demo.amps.fix42.state.config.Mode;

/**
 * The {@link OrderStatePublisher}'s constructor options, plain, so the
 * publisher needs no configuration binder.
 * {@code OrderStateProperties.publisherOptions()} maps the shipped
 * {@code application.yml} onto it.
 *
 * @param mode           {@code STRICT} raises on bytes that do not parse;
 *                       {@code LENIENT} records them on {@code malformedTopic}
 * @param blotter        the record's topic: the name the full and delta
 *                       publishes are sent under, and the one a refused
 *                       publish is repaired on
 * @param malformedTopic where lenient mode records unparseable bytes; null
 *                       raises as strict mode does
 * @param flushTimeoutMs how long {@link OrderStatePublisher#flush()} waits for
 *                       the sink's {@link PublishSink#flush}
 */
public record PublisherOptions(Mode mode, String blotter, String malformedTopic, long flushTimeoutMs) {
}
