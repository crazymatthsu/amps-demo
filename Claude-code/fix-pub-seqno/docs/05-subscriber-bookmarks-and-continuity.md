# 5. The subscriber: bookmarks, and what tag 8888 adds

The publisher side guarantees the journal holds an unbroken prefix of each
sender's sequence. The subscriber side has a different job: process every
message once, resume after its own restarts, and notice if the publisher's
guarantee ever fails.

## Resuming with a bookmark store

```java
HAClient client = new HAClient("risk-consumer");             // stable name
client.setBookmarkStore(new LoggedBookmarkStore(pathForThisClient));
...
new Command("subscribe")
        .setTopic("fix/seqno/orders")
        .setSubId("risk-consumer-orders")                     // stable too
        .setBookmark(Client.Bookmarks.MOST_RECENT)
        .setOptions(Message.Options.Timestamp);
```

Three things about this, all of which the repository's
[`bookmark-replay`](../../../clients/src/main/java/com/demo/amps/clients/demos/BookmarkReplayDemo.java)
demo also relies on:

- **The position lives with the subscriber, not the server.** The
  `LoggedBookmarkStore` records, per subscription id, which bookmarks have
  been *discarded*. `MOST_RECENT` asks the store where to resume; on a store
  that has never seen this subscription it means the epoch. Nothing on the
  server needs to be told the subscriber exists.
- **Discard after processing, never before.** `bookmarkStore.discard(message)`
  is the statement "I am done with this". Doing it before the work makes a
  crash lose the message; doing it after makes a crash redeliver it. The
  second is the right failure, and it is why this is at-least-once.
- **The client name and the subscription id must both be stable.** The store
  is keyed on the subscription id, and AMPS correlates the subscription with
  the client name. A generated id resumes nothing.

`Options.Timestamp` costs nothing and puts the server's journal timestamp on
each message, which is what makes "how far behind am I?" answerable.

### When the bookmark is older than the journal

If retention aged out the position the subscriber holds, AMPS cannot resume
from it. The client's `Message.Options` offer `bookmark_not_found=epoch`,
`=now` and `=fail`. The default behaviour is worth checking on your version;
`=fail` is the one that makes an unrecoverable gap loud instead of a quiet
jump to the present.

## What tag 8888 adds

Bookmarks answer "where was I?". They do not answer "did I get everything
between there and here?" -- that is a statement about the *publisher's*
sequence, and only a per-sender counter can check it. So the subscriber
keeps, per sender (tag 49), the highest 8888 it has processed, and judges
each message against it:

| arriving 8888 vs. high-water mark | verdict | action |
| --- | --- | --- |
| `hwm + 1` | in sequence | process, advance |
| `<= hwm` | duplicate | skip. Either the publisher resent below L (a bug on its side) or this subscriber is being redelivered a message it processed before it crashed (normal, and exactly why the mark exists) |
| `> hwm + 1` | gap | alarm. The publisher's invariant failed, or this subscriber's own mark file is stale, or the journal was truncated under its bookmark. Adopt the new value and carry on, because stopping loses more than it saves -- but the alarm is real. |

The mark is written **after processing and before the bookmark is
discarded**. That ordering is what makes redelivery safe: a crash after the
mark is written but before the discard produces a redelivery the mark
rejects, and a crash before the mark is written produces a redelivery that
is processed again -- which is the at-least-once outcome that only an
idempotent or transactional consumer can improve on. In the demo processing
is a print, so the mark is the whole of exactly-once.

The first message a brand-new subscriber sees from a sender has no mark to
compare against. It is accepted as the starting point; the subscriber cannot
know what came before its own epoch, and the journal scan on the publisher
side is the tool for that question.

## Two things a subscriber should not do here

- **Do not use `sow_and_subscribe` on this topic to get history.** The SOW
  on `fix/seqno/orders` is keyed on the sender and holds one record per
  sender: the publisher's checkpoint, not the order flow. History is a
  bookmark subscription from the epoch or a timestamp.
- **Do not treat a duplicate as an error.** At-least-once delivery makes
  duplicates on resume a normal event. The sequence check exists to make them
  harmless, not to make them exceptions.
