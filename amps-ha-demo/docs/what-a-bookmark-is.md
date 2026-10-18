# What a bookmark is

The question this document answers:

> The consumer resumes from a bookmark, the SOW record in
> `/ADMIN/bookmark_store` holds a bookmark, and the log shows values like
> `17434824616529380601|12345|`. What *is* that value? Is it the offset of the
> record in the journal file?

No. A bookmark is a **logical position** in an instance's transaction log: the
identity of the AMPS instance that first recorded the message, and the
message's sequence number in that instance's log. It is not a file, not an
offset, and not anything a client should compute or take apart.

A note on what is verified. The format and the special values below are read
from the AMPS Java client library sources (`amps-client` 5.3.5.3, class
`com.crankuptheamps.client.fields.BookmarkField` and the `Client.Bookmarks`
constants). The statements about how the server assigns, orders and resolves
bookmarks are 60East's documented behaviour, cited at the end; they were not
re-verified against a live instance in the session that wrote this.

---

## The value

A bookmark is assigned by AMPS to each message as the message is written to the
transaction log, and delivered to bookmark subscribers in the message's
`bookmark` header. The client library reads it as two unsigned 64-bit integers
separated by `|`, with a trailing `|`:

```
17434824616529380601|12345|
└────────┬─────────┘ └─┬─┘
   publisher id       sequence
```

| component | what it is | what it is not |
| --- | --- | --- |
| publisher id | the identity of the AMPS instance -- its transaction log -- that first accepted the message. Every message that entered the replicated group through the primary carries the primary's id, however many instances it is later replicated to | the name of the publishing *client*. The library's vocabulary is unfortunate here; see below |
| sequence | the message's position in that instance's transaction log, a counter that is monotonically increasing per instance and instance-wide: two consecutive messages on one topic need not have consecutive sequence numbers when other journaled topics are active | a byte offset, a per-topic count, or the publisher's own sequence number |

The documentation describes the value as an opaque identifier that applications
should pass back rather than interpret. The client library does parse the two
components, with `getPublisherId()` and `getSequenceNumber()`, but only for its
own bookkeeping (next section), and the string stays under about 40 bytes
(`MAX_BOOKMARK_LENGTH` is 42).

### Two different "sequence numbers"

This module leans on two mechanisms that both say "sequence", and they are not
the same number:

| | assigned by | scope | used for |
| --- | --- | --- | --- |
| the **publish store's** sequence | the client library, per publishing client | one client's publishes | duplicate detection on replay: the server drops a replayed publish whose (client name, sequence) it has seen. This is what the `FailedWriteHandler` reports as `Duplicate` after a failover |
| the **bookmark's** sequence | the server, per instance | every message in that instance's transaction log | positioning a bookmark subscription, and the bookmark store's filtering of redelivery |

The first travels *into* AMPS with a publish; the second comes *out* of AMPS
with a delivery. The publish store never sees a bookmark and the bookmark store
never sees a publish sequence.

---

## Why it cannot be an offset

Three facts about this pair rule it out, and each is something the failover
depends on:

1. **Replication preserves it.** A message replicated from the primary to the
   secondary is written at some different place in the secondary's journal, and
   keeps the bookmark the primary gave it. The consumer in this module moves
   from one instance to the other mid-stream and resubscribes from a bookmark
   issued by the instance that died; the survivor resolves it because the
   survivor holds the same message under the same `publisherId|sequence|`. A
   byte offset into the primary's journal file would mean nothing there.
2. **Journal files come and go.** Files are preallocated, roll at a fixed size,
   and are removed by retention (see
   [replication-and-the-transaction-log.md](replication-and-the-transaction-log.md)).
   An offset would not survive any of that; a sequence number does. Which file
   holds a given bookmark, and where in it, is an index the server maintains
   and never exposes.
3. **It has to be comparable.** Because the sequence is monotonically
   increasing per instance, "is this message before or after that one" is a
   comparison of two integers once the publisher ids match. That is what lets
   the server find a bookmark in the log quickly, and what lets the client
   library filter redelivery.

### What the client library does with the two components

The bookmark store keeps, per subscription, the highest sequence number it has
seen from each publisher id. When a message arrives, the store compares the
message's sequence with that high-water mark for the same publisher id;
anything at or below it has been seen and is dropped before the handler runs.
Sequences from different publisher ids are never compared with each other --
there is no order across instances -- which is why a consumer that has received
messages from both members of a pair holds **one position per instance**, and
why its resume bookmark, and the record the recovery point adapter writes to
`/ADMIN/bookmark_store`, can be a comma-separated list rather than a single
value.

---

## The special values

Not every bookmark string names a message. The forms the client library
recognises:

| value | meaning | notes |
| --- | --- | --- |
| `0` | the epoch: the start of the transaction log | `Client.Bookmarks.EPOCH`; `ha.bookmark=epoch` sends it, after purging the subscription's recovery point so that "epoch" means everything |
| `0|1|` | now: the tail of the log, new messages only | `Client.Bookmarks.NOW` is literally this string, publisher id 0 and sequence 1, a reserved sentinel; `ha.bookmark=now`, the demo's default |
| `recent` | the bookmark store's position | `Client.Bookmarks.MOST_RECENT`; never sent to the server. The library replaces it with the store's most recent bookmark (or list) before the subscribe goes out, which after a restart is what the recovery point topic held. `ha.bookmark=recent` |
| `20261018T120000Z` | a point in time | ISO-8601 `YYYYmmddTHHMMSS[Z]`; the subscription starts at the first message recorded at or after that moment. The library recognises the form by the `T` in the ninth position |
| `a,b` | a list: one bookmark per instance | what a store holds after consuming from both members of a pair; AMPS resumes each publisher id from its own position |
| `[a,b]`, `(a,b]`, ... | a range | a bounded replay between two bookmarks, inclusive or exclusive at either end |

A bookmark that names a message the transaction log no longer holds, because
the file was removed by retention, is handled by the server's
`bookmark_not_found` option. The default starts the subscription at the end of
the log, which silently skips everything in between; the option can choose the
epoch or a failure instead. The transaction log document covers why that makes
retention a consumer concern and not only a disk one.

---

## Where bookmarks appear in this module

| place | what you see |
| --- | --- |
| the consumer's log line at subscribe time | `subscribed to orders as 'ha-demo-consumer' from bookmark '17434824616529380601|12345|' (asked for 'recent', fully_durable)`: the literal value for `epoch` and `now`, the store's position for `recent` |
| the `/ADMIN/bookmark_store` record, via `--args="bookmarks"` or `ha-compose.sh bookmarks` | `client 'ha-demo-consumer' subscription 'ha-demo-consumer' resumes from 17434824616529380601|12345|`; a list after the consumer has been on both instances |
| the ledger's duplicate warning | `DUPLICATE run ... seq ... (bookmark ...)`: the `seq` is the demo's own 1..N numbering, the bookmark is the server's |
| the HA client's resubscribe after a failover | not logged by the demo, but it is the store's most recent position, the same value `recent` would send |
| the fourth stage of the integration test | asserts that the replacement consumer's start position is one of the bookmarks in the record its predecessor left, and that the record then moves |

The `fully_durable` option on the subscription (the module README's fourth
point) is what guarantees that every bookmark the consumer ever holds names a
message that exists on **both** instances, so that whichever instance it
resubscribes to can resolve it.

---

## Treat it as opaque

Application code should carry bookmarks as strings and hand them back:
to a subscribe, to a bookmark store, to a recovery point record. The format is
the server's to change, and the client library, which is versioned with the
server, is the only place that should read the components. Nothing in this
module parses a bookmark; `OrderConsumer` logs it, the store compares it, and
the `RecoveryPoints` helper splits a list on commas only to show one position
per line.

---

## Sources

60East documentation:

- [Using the Transaction Log and Bookmark Subscriptions](https://crankuptheamps.com/docs/amps-user-guide/txlog/transaction-log-basics)
- [Replaying Messages with Bookmark Subscription](https://crankuptheamps.com/docs/amps-user-guide/txlog/replaying-messages-with-bookmark-subscription)
- [Understanding Transaction Log Message Persistence](https://crankuptheamps.com/docs/amps-user-guide/txlog/understanding-message-persistence)
- [Details of High Availability](https://crankuptheamps.com/docs/amps-user-guide/ha/ha-details)
- [AMPS Protocol](https://crankuptheamps.com/docs/amps-command-reference/protocol/amps-protocol)
- [Bookmark State Without a Filesystem](https://crankuptheamps.com/blog/sow-recovery-point-adapter)

AMPS Java client library 5.3.5.3 sources: `fields/BookmarkField.java`
(`SEPARATOR_CHAR`, `getPublisherId`, `getSequenceNumber`, `isTimestamp`,
`isRange`, `isBookmarkList`, `parseBookmarkList`), `Client.java`
(`Bookmarks.EPOCH`, `Bookmarks.NOW`, `Bookmarks.MOST_RECENT`),
`MemoryBookmarkStore.java` (the per-publisher high-water marks in
`Subscription.isDiscarded`).

This repository:

- [replication-and-the-transaction-log.md](replication-and-the-transaction-log.md) -- why the journal exists on both instances, and what retention does to old bookmarks
- [the module README](../README.md) -- the five things that make the failover lossless, including `fully_durable`
- [fix-pub-seqno/docs/05-subscriber-bookmarks-and-continuity.md](../../fix-pub-seqno/docs/05-subscriber-bookmarks-and-continuity.md) -- bookmark resume in a FIX publisher's subscriber, and what a per-sender sequence check adds on top of it
