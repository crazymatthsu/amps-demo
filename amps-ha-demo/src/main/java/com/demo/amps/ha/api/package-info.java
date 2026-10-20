/**
 * Reusable AMPS high-availability clients: a publisher and a bookmark
 * subscriber that each survive losing the instance they are connected to,
 * the subscriber able to resume on another machine from a SOW topic.
 *
 * <ul>
 *   <li>{@link com.demo.amps.ha.api.HaPublisher}: an HA client with a publish
 *       store, replayed after a reconnect, with {@code flush} meaning "every
 *       message acknowledged as persisted".</li>
 *   <li>{@link com.demo.amps.ha.api.HaBookmarkSubscriber}: an HA client with
 *       a bookmark store, re-subscribed from its last position after a
 *       reconnect, and -- given a {@link com.demo.amps.ha.api.RecoveryPointTopic}
 *       -- that position replicated to the pair so a restart anywhere under
 *       the same client name resumes from it.</li>
 *   <li>{@link com.demo.amps.ha.api.BookmarkStart},
 *       {@link com.demo.amps.ha.api.HaConnectionSettings},
 *       {@link com.demo.amps.ha.api.RecoveryPointTopic}: the values the two
 *       builders take.</li>
 *   <li>{@link com.demo.amps.ha.api.ConnectionJournal}: every connection
 *       state change a client announced, with the server it concerned, so a
 *       failover is a list rather than an impression.</li>
 * </ul>
 *
 * <h2>Copying this package into another project</h2>
 *
 * <p>The package is self-contained on purpose: it refers to nothing else in
 * this repository, and the module's build fails if it ever does
 * ({@code checkApiSelfContained}). Copy the directory
 * {@code src/main/java/com/demo/amps/ha/api} as it is (rename the package if
 * you like; nothing depends on the name) and add two dependencies:
 *
 * <pre>
 *   com.crankuptheamps:amps-client   (written against 5.3.5.3)
 *   org.slf4j:slf4j-api              (plus a binding of your choice at runtime)
 * </pre>
 *
 * <p>It needs Java 17 or later (records). The unit tests under
 * {@code src/test/java/com/demo/amps/ha/api} are self-contained as well and
 * can travel with it; the proof that the clients survive a real failover is
 * this module's integration test, which stays here with its containers.
 *
 * <p>On the server side, a bookmark subscription needs its topic in the
 * {@code <TransactionLog>}, and a recovery point topic needs the SOW topic
 * described on {@link com.demo.amps.ha.api.RecoveryPointTopic} on every
 * instance the subscriber may connect to.
 */
package com.demo.amps.ha.api;
