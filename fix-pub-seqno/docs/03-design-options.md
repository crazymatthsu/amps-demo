# 3. Design options

Two independent decisions: **how the publisher learns L**, the last tag 8888
AMPS recorded for it, and **how messages get onto the wire** (with or
without the client's publish store). They combine freely; the comparison
tables at the end are what [04](04-chosen-design-and-failure-matrix.md)
chooses from.

## A. Finding L

### A1. Ask the SOW: key the topic on the sender

Declare the topic as a SOW keyed on tag 49:

```xml
<Topic>
  <Name>fix/seqno/orders</Name>
  <MessageType>fix</MessageType>
  <Key>/49</Key>
  <FileName>./sow/fix-seqno-orders.sow</FileName>
  <Durability>persistent</Durability>
</Topic>
```

A SOW keeps the **last message per key**. With the key on the sender, the SOW
holds exactly one record per sender: its most recent message, and therefore
-- by the prefix invariant in [01](01-problem-and-invariants.md) -- the
message with the highest 8888. Finding L is one `sow` query with the filter
`/49 = 'SENDER'`, returning zero or one record, answered from the SOW index
without touching the journal.

Nothing about this changes what subscribers see. Bookmark subscriptions are
served from the transaction log, which still holds every message; the SOW is
an additional index that costs one record per sender. Note what the SOW on
this topic is *not*: it is not an order blotter. Keyed on the sender it is a
publisher checkpoint table, and the message itself is the checkpoint, which
is what makes it atomic -- there is no second write that could be lost
separately.

Cost: O(1) per lookup, one small record per sender. Constraint: the topic
must be declared this way. Caveat: "last written" equals "highest 8888" only
while every publisher respects the invariant; a publisher that resends an
*older* message would regress the SOW record while the journal still holds
the newer one. The journal scan below is immune to that, which is why the
chosen design uses it as the verification.

### A2. Scan the transaction log

Any journalled topic supports a bookmark subscription starting from the
epoch, from a bookmark, or from a **timestamp** (`YYYYMMDDTHHMMSS`). With
the filter `/49 = 'SENDER'`, replaying from a timestamp shortly before the
outage to the end of the journal yields every message of the sender in that
window, and the maximum 8888 among them is L. The same pass verifies the
prefix invariant for free: the 8888 values it sees should be contiguous
with no repeats.

Cost: O(messages from the sender since the lookback), so the lookback is a
knob: long enough to cover the longest outage the publisher may have had,
short enough to be cheap. From the epoch is always correct and always the
slowest. Constraint: journal retention must cover the lookback -- a
transaction log that has aged out the window returns nothing, which reads
as L = 0 and must be treated as "unknown", not as "start from 1".

One mechanical point: a bookmark subscription has no end marker (it turns
live when it reaches the head of the journal), so "finished scanning" is
"nothing arrived for a while". The repository already relies on that idle
timeout in its recovery demo; the scan here does the same.

### A3. Let the logon ack answer

The logon acknowledgement carries the last persisted client sequence number
for the client name ([02](02-what-amps-already-does.md)). It is free, exact
and needs no query -- **if the AMPS sequence number and tag 8888 are the
same number.** They are not, by default: the stock publish store seeds its
numbering from the clock.

Making them the same means implementing the `Store` interface so that
`store(Message)` assigns the message's own 8888 as its sequence number, with
the outbox as the backing file. It is a clean idea -- the outbox becomes the
publish store, the library replays from it, and the server rejects on 8888
-- but it has to satisfy the library's contract: contiguous numbers, a last
persisted value that is never zero after logon (so 8888 must start above 1,
or be offset by a constant the recovery subtracts back out), and thread
safety across the client's send and receive threads. None of that is
exotic, but none of it can be validated without a live server, which this
session did not have. It is described here as the natural next step and
left unbuilt; the two server-side lookups need no such alignment.

### A4. Trust the publisher's own records

Keep "last acknowledged 8888" in the outbox and resume from there. Rejected
in [01](01-problem-and-invariants.md): an ack lost after the write causes a
duplicate, an ack lost before it causes nothing worse, but a crash before
the ack arrives cannot be told apart from a message that never arrived. The
outbox is the right record of what was *sent*; it is the wrong authority on
what was *recorded*.

### Comparison

| | A1 SOW lookup | A2 journal scan | A3 logon ack | A4 own records |
| --- | --- | --- | --- | --- |
| answers in tag-8888 space | yes | yes | only if aligned | yes |
| cost per recovery | one keyed read | proportional to lookback | none | none |
| needs on the server | SOW keyed on the sender | journal retention covering the lookback | nothing extra | nothing |
| survives loss of client-side files | yes | yes | only the aligned form, and only if the outbox survives | no |
| detects a broken invariant | no (last write wins) | yes (sees gaps and repeats) | no | no |
| correct under a misbehaving publisher | can regress | yes | yes (server rejects) | no |

## B. Getting messages onto the wire

### B1. Plain client, application-level recovery

No publish store. Tag 8888 is the only sequence number; the outbox is the
only publisher-side record; recovery is "find L (A1, verified by A2), then
republish `(L, lastInOutbox]` from the outbox". No duplicates are produced
because nothing at or below L is ever resent, and L is the server's own
answer. In-flight messages lost at a disconnect are recovered the same way
as messages never sent.

Simple, no client-side state beyond the outbox, and every step is a
standard command the repository already exercises against a live instance
(`sow` with a filter, `subscribe` with a bookmark). Its weakness is what it
lacks: the server cannot reject a duplicate, so a bug in the recovery would
land twice in the journal and only the subscribers' 8888 check would notice.

### B2. HAClient with a publish store

The library mechanism from [02](02-what-amps-already-does.md): automatic
replay of the in-flight window on reconnect, server-side duplicate rejection
by client sequence number, `PublishGapException` on failover to a lagging
server. Covers transient disconnects and AMPS restarts without any
application code. Does not cover loss of the store file, and its sequence
numbers are not 8888.

### B3. Both: publish store for the in-flight window, 8888 recovery for everything else

Set the publish store *and* run the application-level recovery after every
logon. Ordering matters: `logon()` replays the store first; the recovery
then waits for those replays to be acknowledged (`publishFlush`), asks for
L, and republishes only what is still missing. If the store survived, the
recovery finds nothing to do. If it was lost, the recovery does what the
library could not. The server rejects any overlap, and the failed-write
handler reports it, so a mistake in either layer is visible instead of
silent.

### Comparison

| | B1 plain client | B2 publish store | B3 both |
| --- | --- | --- | --- |
| transient disconnect, store intact | recovered by the application | recovered by the library | library |
| publisher crash, outbox intact, store lost | recovered | **silently lost** | recovered by the application |
| server rejects a duplicate resend | no | yes | yes |
| sequence number the server dedups on | none | clock-seeded | clock-seeded (8888 rides in the payload) |
| moving parts | outbox | store file | outbox + store file |
| what the demo can show without a live server | every step is a plain command | library internals | both |

[04](04-chosen-design-and-failure-matrix.md) picks A1 + A2 for finding L
and B1 for the demo publisher, and explains why B3 is the production
recommendation.
