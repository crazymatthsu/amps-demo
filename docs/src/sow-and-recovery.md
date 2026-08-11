# SOW, snapshots and recovery

## What the SOW is

The State of the World is a keyed, last-value store the AMPS server maintains as
messages flow through a topic. Declaring a topic in `<SOW>` with a `<Key>` is the
whole setup:

```xml
<Topic>
  <Name>orders</Name>
  <MessageType>json</MessageType>
  <Key>/orderId</Key>
  <FileName>./sow/%n.sow</FileName>
  <Durability>persistent</Durability>
</Topic>
```

There is no separate write path. Publishing to `orders` both delivers to
subscribers and updates the stored record for that key. `<Key>` is a JSON pointer
into the payload — `/orderId` means "the top-level member named `orderId`", which
is what protobuf's canonical JSON produces for the proto field `order_id`. Get
those two out of step and every record lands under the same empty key; the test
`JsonCodecTest.fieldNamesMatchSowKey` exists to catch exactly that.

Declaring the SOW unlocks, for that topic:

| command | what it does |
| --- | --- |
| `sow` | point-in-time query; the stream ends when the snapshot is complete |
| `sow_and_subscribe` | snapshot then live, atomically — no gap, no duplicates |
| `delta_publish` | merge changed fields into the stored record |
| `sow_and_delta_subscribe` | snapshot once, then only what changed |
| `sow_delete` | remove records by filter or by key |
| OOF | "this record left your filter" — deleted, expired, or no longer matching |
| `<Expiration>` | per-record TTL |

A topic **not** in `<SOW>` still works. It is a dynamic pub/sub topic: no state, no
query, no delta. That is the baseline the `pubsub` demo establishes.

## Two keys, do not confuse them

**Business key** — the JSON field named in `<Key>`. Query it with an ordinary
filter and AMPS uses the key index rather than scanning:

```java
new Command("sow").setTopic("orders").setFilter("/orderId = 'ORD-00007'")
```

**SOW key** — an opaque identifier the server assigns to each record and reports
on every message (`message.getSowKey()`). Hand a set back for the cheapest
possible lookup:

```java
new Command("sow").setTopic("orders").setSowKeys("1234567890,1234567891")
```

You cannot compute a SOW key client-side; you learn it from a message. It is
stable for the life of the record, which makes it the right handle for a GUI
refreshing rows it is already displaying. Both forms are in the
[`sow-query-by-key`](../../clients/src/main/java/com/demo/amps/clients/demos/SowQueryByKeyDemo.java)
demo.

## Snapshot and live, without a seam

The command AMPS applications are built on:

```java
Command command = new Command("sow_and_subscribe")
        .setTopic("orders")
        .setFilter("/status = 'ORDER_STATUS_NEW' AND /quantity > 3000")
        .setOptions(Message.Options.OOF + Message.Options.SendKeys);
```

AMPS sends the matching SOW contents bracketed by `group_begin` / `group_end`,
then keeps the subscription open. Nothing is missed between the two phases and
nothing arrives twice — the server holds the boundary, not the client.

The equivalent on a log-based broker is: start consuming live into a buffer, read
the compacted topic to its end, merge, then switch over — and get that right while
messages keep arriving. Here it is one command.

### OOF: why filtered views stay correct

With `Options.OOF`, when a record stops matching your filter AMPS tells you, with
a reason:

- `deleted` — removed via `sow_delete`
- `expired` — TTL elapsed
- `no-longer-matches-filter` — still exists, but your predicate no longer holds

Without it, a filtered view accumulates rows that should have vanished, and the
only fix is to re-query periodically and diff. This is a small feature that
removes a whole class of bug.

## Recovery: two independent mechanisms

AMPS recovers from two things. Being precise about which does what is most of
understanding AMPS operationally.

### The SOW gives back current state — immediately

A persistent SOW is a store on disk, not a projection rebuilt at startup. When the
instance comes back it is queryable as soon as it is listening, regardless of how
much history exists. Restart cost does not grow with age.

### The transaction log gives back history — on request

A journalled topic lets any subscriber resume from a bookmark and receive
everything it missed. Nothing is replayed unless a client asks.

|  | SOW | transaction log |
| --- | --- | --- |
| answers | what is true now | what happened |
| available | instantly on startup | when a subscriber requests replay |
| size grows with | key count | message count |
| lost if deleted | current state | ability to resume; clients re-bootstrap from SOW |

### Seeing it

```bash
./gradlew :clients:run --args="recovery --phase snapshot"
./server/scripts/amps.sh restart
./gradlew :clients:run --args="recovery --phase verify"
```

Phase one publishes marker records, counts the SOW, captures the last bookmark and
writes it to `build/client-state/recovery-state.properties`. Phase two re-counts
after the restart and replays from the saved bookmark. Both survive because
`server/data/` is a host directory bind-mounted into the container — stopping the
container does not touch it.

For a harsher test, kill rather than stop:

```bash
podman kill amps-demo && ./server/scripts/amps.sh start
```

The SOW is reconciled against the transaction log on the way back up; that
reconciliation is a large part of what the journal is for.

## Client-side durability

Server-side persistence is only half of it. Two client stores complete the
picture, both in
[`AmpsConnections`](../../common/src/main/java/com/demo/amps/common/AmpsConnections.java):

**Publish store** — records publishes locally and discards them only once AMPS
acknowledges them as persisted. On reconnect, anything in doubt is replayed;
AMPS deduplicates using the publisher's sequence numbers. This is the AMPS
equivalent of an idempotent producer.

```java
HAClient client = AmpsConnections.connectWithPublishStore("order-gateway");
```

**Bookmark store** — records the last bookmark this named subscriber has
*discarded*. Resume with `Client.Bookmarks.MOST_RECENT` and you continue exactly
where you stopped, across a process restart.

```java
HAClient client = AmpsConnections.connectWithBookmarkStore("risk-consumer");
...
client.getBookmarkStore().discard(message);   // after processing, not before
```

Discarding *after* processing is what makes delivery at-least-once. Discard first
and a crash mid-processing loses the message.

The client name is the identity both stores key on, so it must be stable across
runs. A random or hostname-derived name resumes nothing — a mistake that only
shows up the first time something restarts in production.

## Truncating a topic

Yes, a client can delete a topic's data at runtime. `sow_delete` is the command,
and it comes in three forms:

```java
// by content filter -- the synchronous form returns the server's own count
Message ack = client.sowDelete("orders", "/status = 'ORDER_STATUS_CANCELLED'", timeout);
ack.getRecordsDeleted();

// by server-assigned SOW key, for deleting exactly the rows you already have
client.sowDeleteByKeys(handler, "orders", "1234567890,1234567891", timeout);

// by matching data
client.sowDeleteByData(handler, "orders", json, timeout);
```

To purge a whole topic, use a filter that matches everything:

```java
client.sowDelete("orders", "1=1", timeout);
```

(If your version rejects the constant form, any always-true predicate over a field
the records carry does the same job, e.g. `/orderId != ''`.)

The [`truncate`](../../clients/src/main/java/com/demo/amps/clients/demos/TruncateDemo.java)
demo runs all three and reports what each cost.

### Four things to know before using it

1. **It is durable and global.** The records are gone for every client, not hidden
   from one view. Subscribers with `Options.OOF` receive an OOF with reason
   `deleted` for each one, so live views correct themselves.

2. **It does not shrink the transaction log — it grows it.** A delete is a write
   like any other and is journalled as one. Purging a large SOW is one of the
   faster ways to *add* to your journal. If your goal was disk, this is the wrong
   tool; see [transaction-log-sizing.md](transaction-log-sizing.md).

3. **It does not return SOW disk to the filesystem.** The freed space is reused
   for new records inside the existing file rather than shrinking it. Deleting
   reduces what queries return and what the SOW needs in memory. The only way to
   actually reclaim the file is to stop the instance and remove it — which
   discards the topic's state entirely.

4. **`<Expiration>` is usually better.** A TTL removes records with nothing to
   run, no command to schedule and no bulk write. Reach for `sow_delete` when the
   removal is event-driven — an order is cancelled, a session ends — and for
   expiry when it is time-driven.

## Practical notes

- **Recovery is not a rebuild.** If you find yourself replaying the whole journal
  at startup to reconstruct state, you are using AMPS as a log and paying for the
  SOW without using it. Query it instead.
- **A bookmark outside the retained journal cannot resume.** The subscriber must
  re-bootstrap from the SOW. Size retention accordingly —
  [transaction-log-sizing.md](transaction-log-sizing.md).
- **Transient SOW topics recover as empty.** That is the point; do not put
  anything you need on one.
- **`sow_delete` is durable.** It removes the record and notifies subscribers with
  OOF `deleted`; it is not a client-side filter.
