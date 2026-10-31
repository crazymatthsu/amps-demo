# 4. Recovery and operations

A publisher with a cache has to answer three questions the stateless one never
did: what happens when it restarts, what happens when the cache does not have
an order the feed is talking about, and what stops two of them from running at
once. The design answer to all three rests on one property, stated first.

---

## 1. The SOW record is the checkpoint

Every field of the cache entry is on the `sow/fix42/order_state` record
([02 §4](02-architecture.md#4-the-record)), so the record *is* a checkpoint of
the machine's state for that order, maintained for free by the deltas the
machine already publishes:

| cache field | record tag |
| --- | --- |
| `orderKey` | 9020 |
| `clOrdIds` (→ the index entries for this order) | 9021 |
| `workingClOrdId` | 9014 |
| `orderId` (→ an index entry) | 37 |
| `parentOrderId` | 9000 |
| `ackedQty`, `ackedPx` | 38, 44 |
| `venueStatus`, `lastExecType` | 39, 150 |
| `cumQty`, `leavesQty`, `avgPx` | 14, 151, 6 |
| `lastExecId` | 17 (the ring behind it is **not** on the record — see §2) |
| `pending` | 9015 |
| `msgCount`, `ignoredCount` | 9023, 9025 |
| `origin` | 9018 |

Nothing in the machine depends on state that is not on the record. The
contract's per-request status snapshots would have broken this (they are
exactly the kind of private state [docs/fix42-view/03 §3](../../docs/fix42-view/03-proposed-architecture.md#3-recovery-restarts-and-rule-changes)
flagged as unrecoverable) and are one more reason [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision)
does without them.

Two consequences follow, and the rest of this document is working them out:

- **the cache is an accelerator, not the store.** It exists so a decision can
  be made without a round-trip; the SOW holds the truth. Losing the cache
  loses nothing a `sow` query cannot return;
- **a message for an order the cache lacks is not an error.** The machine
  creates a `MIDSTREAM` entry and delta-publishes what it knows; the merge
  lands on the existing record, if there is one, and the publisher has
  behaved — for that one message — exactly like the stateless publisher did
  for every message.

## 2. Restart

### 2.1 Rebuild from the SOW (`recovery: SOW`, the default)

On start, before the first feed message is accepted:

1. connect and log on with the configured client name;
2. `sow` on `sow/fix42/order_state`, no filter (or `/9017 = 'N'` to load only
   open orders — a trade-off discussed below);
3. for each record, rebuild the cache entry from the tags in §1 and the index
   from 9021 and 37;
4. start consuming the feed.

Cost is one query and O(records) of parsing: a 100,000-record SOW is a few
seconds and ~70 MB. What is *not* recovered: the ExecID ring beyond the last
ExecID (17), so for the first few reports after a restart duplicate detection
is depth one — the stale guard still holds, and a PossDup resend of the last
report is still caught.

Loading only open orders (`/9017 = 'N'`) keeps the cache at the working set
and makes a late message for a terminal order take the cache-miss path of §3
rather than find its entry. With `terminal-retention: PT0S` (the default,
never evict) the default is to load everything, so the two settings agree:
either the cache holds every order the SOW holds, or it holds the open ones
and relies on §3 for the rest.

**What a restart does not cover:** messages the feed delivered while the
publisher was down. That is the FIX session's problem — a resend request on
logon, which a QuickFIX/J engine does by itself — and the reason the publisher
should sit directly behind the session rather than behind a buffer that
discards. The design here makes the publisher *idempotent under redelivery*
(duplicates and stale reports are ignored, requests already in the set are
ignored), so a resend that overlaps what was already applied is harmless.

### 2.2 Rebuild by replay (`recovery: JOURNAL`, later phase)

The three audit topics are journalled and, in this module, hold the **whole
message as received** — which the old publisher's route-selected subsets did
not. A bookmark subscription on the topic regex
`^sow/fix42/(orders_audit|execs_audit|rejects)$` from the epoch (or from a
timestamp) therefore delivers the feed again in journal order, and running it
through a fresh machine reproduces every record, because the machine is
deterministic. The order across topics within one transaction log is the
publish order — the same property [docs/fix42-view/03 §1](../../docs/fix42-view/03-proposed-architecture.md#1-topology)
leans on; **verify on build** that a regex bookmark subscription across
three SOW topics preserves it.

Replay is slower than the SOW rebuild (O(messages), not O(orders)) and is the
tool for a different job: rebuilding the blotter **from scratch** after a rule
change or a suspected bug (wipe `sow/fix42/order_state`, replay, compare), or
proving on a quiet instance that the SOW rebuild and the replay agree. While
replaying, the publisher publishes; a delta that changes nothing on the record
is a no-op for the SOW — **verify on build** whether delta subscribers see it
— and a record that already matches is left as it was.

The `fix-pub-seqno` module's locator pattern
([its docs/04](../../fix-pub-seqno/docs/04-chosen-design-and-failure-matrix.md))
is the precedent for "the SOW answers, the journal verifies"; here the SOW
rebuild is the answer and replay the verification.

## 3. Cache misses

A non-D message whose ids resolve to nothing in the index. Causes: the
publisher was started after the order opened (`recovery: NONE`, or a SOW
rebuild that loaded open orders only), the order was evicted (§4), or the feed
genuinely started mid-stream.

The machine creates a `MIDSTREAM` entry keyed per [03 §3.2](03-state-machine.md#32-creation)
and **delta-publishes** it. Three cases for what is already in the SOW:

| in the SOW | result |
| --- | --- |
| nothing under that key | AMPS stores the delta as the record. **Verify on build**: the user guide describes a `delta_publish` with no existing record as being stored as a `publish`; if a build rejects it instead, the publisher falls back to `publish` for `CREATED(MIDSTREAM)`, configurable, and nothing is lost because nothing was there |
| a record under that key (the order was evicted or not loaded; its key was its OrderID) | the delta merges into it; the entry is rebuilt from the message, and the record keeps what the message did not carry. Correct, by the same merge property the whole design rests on |
| a record under a **different** key — the chain opened with a D (key = root ClOrdID) and the late message's ids were not in the index | a second record, `9018=MIDSTREAM`, for the same order. The one genuinely bad outcome |

The third case is why eviction is designed as it is (§4), and why a SOW
rebuild loads 9021 (the whole chain) and 37: after a rebuild, every id the
order ever used is indexed again, so a late `35=9` for an old request finds
it. The remaining exposure is an order evicted *and* then referenced by an id
the tombstone (§4) does not hold — a late request under a brand-new ClOrdID
whose 41 points at a forgotten one. Counted (`midstreamChains`), visible
(`/9018 = 'MIDSTREAM'`), and if it matters, closed by the optional keyed
lookup below.

**Optional keyed lookup on miss** (`miss-lookup: true`, a later phase): before
creating a `MIDSTREAM` entry for a message carrying 37, query
`sow/fix42/order_state` with `/37 = '<OrderID>'`; a hit rebuilds the entry
from the record and the message merges into it under the right key. One query
per miss, never in the hot path for a known order. Needs the server to index
tag 37 on the topic for the query to be a lookup rather than a scan — AMPS
SOW topics take a hash index declaration for exactly this; **verify on build**
the element name and that it accepts a FIX tag.

## 4. Eviction

`terminal-retention` is how long a terminal order's entry stays in the cache
after it went terminal. Default `PT0S` means never evict: at the sizes in
[02 §3.3](02-architecture.md#33-size) a day's flow fits comfortably and a daily
restart with `recovery: SOW` is the natural bound. For a flow that does not
fit, eviction removes the entry's **body** and its ClOrdID index entries but
keeps a **tombstone**: `orderId → orderKey`, about 40 bytes.

The tombstone is what makes eviction safe against §3's third case. The
messages that legitimately arrive for a terminal order late are venue
messages — a bust, a correct, a restatement — and every one carries 37. They
resolve through the tombstone to the right key, the entry is recreated
`MIDSTREAM` from the report (which, being a restatement, carries exactly the
absolutes the entry needs), and the delta lands on the right record. What a
tombstone cannot route is a late *request* under a new ClOrdID (§3), which on
a terminal order is rare and, when it happens, produces a visible second
record rather than a corrupted first one.

An order with requests still unanswered when it goes terminal (the venue
never sent the 9) is evicted like any other; its record keeps `9016 > 0`,
which is the right place for the fact to live.

## 5. One writer

Exactly one publisher instance writes `sow/fix42/order_state`. Two machines
delta-publishing from two caches would interleave, and a record is only as
coherent as the single sequence of applies behind it. Active/passive is the
shape: the passive instance may run the same subscription with publishing
disabled, or may simply start cold on promotion with `recovery: SOW` — the
rebuild is seconds, and the feed's own resend covers the gap. External leader
election; the publisher does not elect. AMPS's replication story for the
topics themselves is independent and is the [`amps-ha-demo`](../../amps-ha-demo/README.md)
module's subject.

The client name is part of this: a transaction-logged instance allows one
connection per name, so a restarted publisher with the same name waits for
the old session to be reaped (the `NameInUse` handling `fix-pub-seqno`
documents) rather than running beside it.

## 6. Observability

Counters on `OrderStatePublisher`, logged as a summary at the end of a run
and on request, with the per-publish INFO line guarded exactly as the old
publisher's is (`WARN` turns it off, and it is the one thing on the path that
allocates):

| counter | meaning | healthy value |
| --- | --- | --- |
| `applied` / `created` / `changed` | outcomes | — |
| `ignoredDuplicate`, `ignoredStale`, `ignoredAbsorbed`, `ignoredRequest` | §5 of [03](03-state-machine.md) | low; a spike is a feed replaying |
| `midstreamChains` | entries created from a non-D | zero on a feed the publisher saw from the start |
| `unmatchedRejects` | 9s that named no pending request | zero |
| `pendingOnTerminal` | orders that went terminal with requests unanswered | zero; otherwise a venue that does not reject late requests |
| `fullPublishes`, `deltaPublishes`, `bytesPublished` | wire | — |
| `cacheEntries`, `indexEntries`, `tombstones` | size | tracks the day |

On the record, the same signals per order: `9025` (ignored count), `9018`
(origin), `9016` with `9017` (unanswered on a terminal order). All three are
plain SOW filters.

## 7. Failure matrix

| failure | what the record shows | how it is found | recovery |
| --- | --- | --- | --- |
| publisher crashes mid-flow | every applied message is on the record; the message in hand may not be | the feed's resend on reconnect redelivers from its last ack | restart; `recovery: SOW` rebuilds the cache; redelivered messages are applied once (duplicate/stale guards) |
| publisher restarts on another host | as above | — | same; nothing is on local disk |
| AMPS restarts | the SOW file is `persistent`; the record survives | reconnect | the client reconnects (or the process restarts); nothing to rebuild on the server |
| `sow/fix42/order_state` SOW file lost, journal intact | empty blotter | the rebuild finds nothing; `midstreamChains` climbs | `recovery: JOURNAL` replays the audit topics and rebuilds the records |
| two cancels race (the motivating case), either order | `39=4`, `9013=NONE` | — | n/a: [03 §7.1–7.2](03-state-machine.md#71-two-cancels-confirm-then-reject-the-motivating-case) |
| feed replays a PossDup range | unchanged; `9025` counts | `ignoredDuplicate` | n/a |
| feed starts mid-stream | `9018=MIDSTREAM`, terms as far as the reports give them | `midstreamChains` | the late D, if it ever comes, fills the terms (`D_LATE`) |
| cache evicted an order, venue sends a late bust | the right record, restated | the tombstone resolves it | n/a |
| cache evicted an order, a late request under a new ClOrdID | a second `MIDSTREAM` record | `/9018 = 'MIDSTREAM'` filter | enable `miss-lookup`, or accept and clean up by replay |
| venue never answers a request | `9016 > 0` on an otherwise finished order | `pendingOnTerminal`; filter `/9016 > 0 AND /9017 = 'Y'` | operational: the venue, not the publisher |
| a second publisher instance starts by mistake | interleaved deltas; records may read oddly until it stops | `NameInUse` if the same client name; otherwise only by the operator | stop it; replay if the damage matters |
| a new 150 value appears | applied with the venue's 39; if neither answering nor reopening it touches no pending entry | `exec-type` histogram in the logs | add it to the answering/reopening sets if it should be |

## 8. Running it

Mirrors `fix42-publisher`:

```bash
export AMPS_IMAGE=localhost/amps-demo:5.3.5.135
AMPS_FLOW=fix42-order-state ./server/scripts/amps.sh start
./gradlew :fix42-order-state-publisher:bootRun
```

The runner publishes the scripted flow plus the edge-case scenarios from
phase 1 of the [plan](05-implementation-plan.md) and exits; the admin console
at `http://127.0.0.1:8085/` can `SELECT` from `sow/fix42/order_state`, and the
two-cancels order is the one to look at first.
