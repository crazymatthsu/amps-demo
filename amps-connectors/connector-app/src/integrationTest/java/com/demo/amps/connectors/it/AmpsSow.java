package com.demo.amps.connectors.it;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.crankuptheamps.client.exception.AMPSException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Reading AMPS back the way a consumer would -- with a plain {@link Client}, no framework
 * code involved -- so what these suites assert is what AMPS actually holds rather than what
 * the connector believes it published.
 *
 * <p>Two reads, because the two kinds of topic answer different questions. A SOW query returns
 * the current record per key, with {@link Message#getSowKey()} on each one, which is how a
 * test can tell a {@code PUBLISHER}-keyed record from one the server keyed -- and how it can
 * catch the sentinel-key collapse, where a whole feed ends up as one record. A journal topic
 * has no SOW to query at all: the only way to see what reached it is to subscribe from the
 * {@code epoch} bookmark and let the server replay the transaction log.
 */
final class AmpsSow {

    /** How long a read waits for the server, and how long a replay waits for the next message. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private AmpsSow() {
    }

    /** One SOW record: the key AMPS filed it under, and its payload. */
    record Record(String sowKey, String data) {
    }

    /**
     * Every record of a SOW topic matching a filter.
     *
     * @param client a logged-on client whose URI names the topic's message type
     * @param topic the SOW topic
     * @param filter an AMPS filter; {@code 1=1} for everything
     * @return the records, in the order the server returned them
     * @throws AMPSException if the query could not be issued
     */
    static List<Record> records(Client client, String topic, String filter) throws AMPSException {
        List<Record> records = new ArrayList<>();
        Command query = new Command("sow").setTopic(topic).setFilter(filter)
                .setTimeout(TIMEOUT.toMillis());
        try (MessageStream stream = client.execute(query)) {
            stream.timeout((int) TIMEOUT.toMillis());
            for (Message message : stream) {
                // A timed-out iteration yields null rather than ending: the stream is still
                // open, the server simply has nothing more to say.
                if (message == null || message.getCommand() == Message.Command.GroupEnd) {
                    break;
                }
                if (message.getCommand() == Message.Command.SOW && !message.isDataNull()) {
                    records.add(new Record(message.getSowKey(), message.getData()));
                }
            }
        }
        return records;
    }

    /**
     * Everything a journal-only topic has ever carried, replayed from the epoch bookmark.
     *
     * <p>A bookmark subscription never ends on its own -- that is the point of it -- so the
     * stream is given an idle timeout and drained until the replay goes quiet. A timed-out
     * iteration hands back a {@code null} message rather than ending the loop, because the
     * subscription is still perfectly alive; that {@code null} is what "the replay is done"
     * looks like here. Closing the stream unsubscribes, which is what lets a test call this
     * repeatedly while records are still arriving.
     *
     * @param client a logged-on client
     * @param topic the journal topic
     * @param idle how long to wait for the next message before deciding the replay is done
     * @return the payloads, in journal order
     * @throws AMPSException if the subscription could not be issued
     */
    static List<String> replay(Client client, String topic, Duration idle) throws AMPSException {
        List<String> payloads = new ArrayList<>();
        Command subscribe = new Command("subscribe").setTopic(topic)
                .setBookmark(Client.Bookmarks.EPOCH);
        try (MessageStream stream = client.execute(subscribe)) {
            stream.timeout((int) idle.toMillis());
            for (Message message : stream) {
                if (message == null) {
                    break;
                }
                if (message.getCommand() == Message.Command.Ack || message.isDataNull()) {
                    continue;
                }
                payloads.add(message.getData());
            }
        }
        return payloads;
    }

    /**
     * A logged-on client for one message type.
     *
     * @param port the host port of the throwaway container
     * @param messageType {@code json} or {@code fix} -- it belongs to the URI, so a suite that
     *     reads both opens two clients
     * @param name the client name AMPS will log
     * @return the connected client; the caller closes it
     * @throws AMPSException if the connection or the logon failed
     */
    static Client connect(int port, String messageType, String name) throws AMPSException {
        Client client = new Client(name);
        try {
            client.connect("tcp://127.0.0.1:" + port + "/amps/" + messageType);
            client.logon(TIMEOUT.toMillis());
        } catch (RuntimeException | AMPSException e) {
            client.close();
            throw e;
        }
        return client;
    }
}
