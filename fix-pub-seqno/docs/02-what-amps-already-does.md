# 2. What AMPS already does about this

Before designing anything, it is worth being exact about the machinery AMPS
and its Java client already have for reliable publishing, because the
question "what was the last message you got from me?" has a first-class
answer in the protocol -- with conditions. Everything in this document was
read from the AMPS 5.3.5.3 Java client source (`Client`, `BlockPublishStore`,
`HAClient`) and from 60East's user guide. The client-side statements are
verified against that source; the server-side statements are 60East's
documented behaviour and were not re-verified against a live instance in
this session.

## Client sequence numbers and the transaction log

Every `publish` a client sends to a transaction-logged topic *may* carry a
client **sequence number** (the `SequenceNumber` header). AMPS records, per
client name, the highest sequence number it has persisted, and:

- acknowledges each message with a **persisted** ack carrying that sequence
  number once the message is in the transaction log;
- treats a message whose sequence number is **at or below** the highest it
  has already recorded for that client name as a **duplicate**: it is
  discarded -- not journalled, not delivered -- and acknowledged with reason
  `duplicate`;
- on **logon**, returns in the logon acknowledgement the last sequence
  number it has persisted for that client name.

That last point is the protocol-level version of the whole requirement:
reconnect, and the server tells you where it is. The catch is the sequence
space it answers in, which is the subject of the rest of this document.

Because sequence numbers are tracked per client name, a transaction-logged
instance **allows only one connection per client name at a time**. A
publisher that crashes and restarts must use the *same* name (or the server
has no record to answer from) and must expect `NameInUse` until the server
has noticed the old connection is dead -- which is what heartbeats are for.

## What the Java client does with them

The behaviour depends entirely on whether a **publish store** is set.

### No publish store: no sequence numbers at all

A plain `Client` with no store sends publishes **without** a sequence
number. In `Client.execute`, the `setClientSequenceNumber` call sits inside
the `isPublishStore` branch and nowhere else, and `publish(...)` returns 0
("0 if no store is configured"). Consequently:

- AMPS cannot deduplicate anything this client sends;
- the logon ack's sequence number is read but only used to bump an internal
  counter; nothing observable happens;
- a disconnect loses whatever was in flight, and nothing replays it.

Reliable publishing from a plain client is therefore entirely the
application's job. That is not a defect -- it is the cheap mode, for
publishers whose data is regenerable or whose own recovery is better placed.

### With a publish store: the library owns the problem

Set a `PublishStore` (file-backed) or `MemoryPublishStore` and the library
does the following, in this order:

1. **`store()` assigns the sequence number** and writes the message to the
   store *before* it is sent. `publish()` returns the number assigned.
2. Each **persisted ack** calls `discardUpTo(seq)` on the store, freeing
   everything at or below it.
3. On **logon**, the client calls `discardUpTo(lastPersistedFromServer)`
   with the value from the logon ack, then **replays** every message still
   in the store (`publishStore.replay(replayer)`, inside `logon()` itself),
   re-sending them with the same sequence numbers. The server persists the
   ones it lacks and rejects the rest as duplicates.
4. If a **failed-write handler** is installed, every rejected message is
   handed to it with the reason (`Message.Reason.Duplicate`, `NotEntitled`,
   or a failure status). Without a handler the rejections are silent.

Three details of the implementation matter for anyone who wants to build
on this rather than around it. All three are in `BlockPublishStore` and
`Client.sendInternal`.

**Sequence numbers are seeded from the wall clock, not from 1.** A brand-new
store's first call to `getLastPersisted()` sets its origin to
`System.currentTimeMillis() * 1_000_000` and numbers messages from there.
This is deliberate: if the store file is lost and recreated, the new numbers
are still higher than anything the server has seen from this client name,
so nothing new is mistaken for a duplicate. The price is the flip side of
the same coin -- see the next section.

**Sequence numbers must be contiguous.** `sendInternal` refuses to send a
message whose sequence number is not exactly one above the last one sent;
it tries to replay the missing ones from the store first
(`replaySingle`), and waits if they are not there. A store that assigned
numbers with gaps would stall the client.

**The client will not send while its last-sent sequence is zero.** The same
method returns without sending when `lastSentSequenceNumber == 0`. After a
logon that number is set to `max(server's last persisted, store's last
persisted)`, so it is zero only if *both* are zero -- impossible with the
stock store because of the clock seed. It becomes possible the moment
someone supplies a store whose numbering starts at 1, which is precisely
what "tag 8888 starts at 1" would ask for. Any design that makes the AMPS
sequence number equal to tag 8888 has to start above 1 or offset the two.

### What happens when the publish store is lost

This is the scenario the application-level mechanism exists for, so it is
worth tracing exactly.

The publisher had sent 8888 = 1..10; AMPS persisted 1..8; the process died
and came back **without its publish store file** (different host, ephemeral
disk, a cleanup job). On logon the server reports its last persisted
sequence, S8. The fresh store's `discardUpTo(S8)` compares S8 against its
own newly clock-seeded origin, which is later and therefore larger, and
adopts nothing. There is nothing in the store to replay. The next publish
gets a fresh, higher number and is accepted. **Messages 9 and 10 are gone,
and nothing on either side reports a gap:** the server never saw them, and
the client's store never knew them. The only party that knows they existed
is the publisher's own outbox -- which the library does not know about.

The client does have a `setErrorOnPublishGap` option, but it detects the
*opposite* problem: a server that reports a lower sequence than the store
has already discarded (a failover to a lagging replica), raised as
`PublishGapException`. It cannot detect a store that is behind the server.

## What this means for the design

| you want | AMPS gives you | condition |
| --- | --- | --- |
| automatic replay of in-flight messages after a reconnect | yes, from the publish store, inside `logon()` | the store file survives |
| server-side rejection of anything you send twice | yes, by client sequence number | the message carries one, i.e. a publish store is set |
| "what was the last thing you persisted from me?" | yes, in the logon ack, and observable as `getPublishStore().getLastPersisted()` after logon | in the **AMPS sequence space**, and only when the store survived so that the value was adopted |
| the same answer in the **tag 8888** space | not directly | either make the AMPS sequence number *be* tag 8888 (a custom `Store`, with the contiguity and start-above-1 constraints above) or ask the server a different way ([03](03-design-options.md)) |
| recovery when the store is lost | no | the application's own record is the only source of the missing messages |

So the client library solves the common case completely and the rare case
not at all. The design in [04](04-chosen-design-and-failure-matrix.md) keeps
the library's mechanism where it helps and adds an application-level answer
to "what does AMPS have?" that does not depend on any client-side file.
