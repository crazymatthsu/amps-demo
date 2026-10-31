# 4. Recovery and operations

A publisher with a cache has to answer four questions the stateless one never
did: what happens when it restarts, what happens when the cache does not have
an order the feed is talking about, what happens when a publish fails, and
what stops two of them from running at once. The design answer to the first
two rests on one property, stated first; the answer to the third is the AMPS
client's publish store, which the first version of this design left out and
the review ([08 R5](08-review-findings.md#r5--publish-failures-disconnects-and-the-asynchronous-send-buffer-are-invisible-cache-and-sow-diverge-silently--high--accept-design))
put back.

---

## 1. The SOW record is the checkpoint

Every field of the cache entry is on the `sow/fix42/order_state` record
([02 §4](02-architecture.md#4-the-record)), so the record *is* a checkpoint of
the machine's state for that order, maintained for free by the deltas the
machine already publishes:

| cache field | record tag |
| --- | --- |
| `orderKey`, `keyKind` | 9020, 9019 |
| `clOrdIds` (→ the index entries for this order) | 9021 holds the last `chain-ids-published`; 9030 the count; older ids are on `orders_audit` under this 9020 |
| `workingClOrdId` | 9014 |
| `orderIds` (→ index entries; the tombstone) | 37 holds the latest; earlier ones are on `execs_audit` under this 9020 |
| `parentOrderId`, `linkedClOrdId` | 9000, 9027 |
| `ackedQty`, `ackedPx` | 38, 44 |
| `venueStatus`, `lastExecType` | 39, 150 |
| `cumQty`, `leavesQty`, `avgPx` | 14, 151, 6 |
| `lastExecId` | 17 (the ring behind it is **not** on the record — see §2) |
| `lastFillExecId`, `lastReportTime` | 9028, 9029 |
| `pending`, with each entry's requested-or-inferred flag | 9015 |
| `msgCount`, `ignoredCount`, `lateFills` | 9023, 9025, 9026 |
| `origin` | 9018 |
| the schema the record was written with | 9024 |
| **answered ids** (bound, not pending) | derived: every id in 9021 that is not in 9015 |

Nothing in the machine depends on state that is not on the record. The
contract's per-request status snapshots would have broken this (they are
exactly the kind of private state [docs/fix42-view/03 §3](../../docs/fix42-view/03-proposed-architecture.md#3-recovery-restarts-and-rule-changes)
flagged as unrecoverable) and are one more reason [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision)
does without them.

Two consequences follow:

- **the cache is an accelerator, not the store.** It exists so a decision can
  be made without a round-trip; the SOW holds the truth. Losing the cache
  loses nothing a `sow` query cannot return;
- **a message for an order the cache lacks is not an error.** The lookups of
  §3 find the record in almost every case; when they do not, the machine
  creates a `MIDSTREAM` entry and delta-publishes what it knows, and the
  publisher has behaved — for that one message — like the stateless publisher
  did for every message.

## 2. Restart

### 2.1 Rebuild from the SOW (`recovery: SOW`, the default)

On start, before the first feed message is accepted:

1. connect with the configured client name, waiting out `NameInUse` until the
   previous session is reaped (§5); the `HAClient` replays whatever its
   publish store still holds (§6) and `publishFlush` waits for it;
2. `sow` on `sow/fix42/order_state`, **every** record;
3. for each record, check 9024; rebuild the cache entry from the tags in §1
   and the index from 9021 and 37; with `load-open-orders-only: true` keep
   the body only for `9017 = 'N'` records and a **tombstone** (every 37 →
   9020) for the rest; a record whose 9015 or 9021 does not parse is loaded
   with an empty set, `9018=UNRECOVERED`, and counted;
4. mark the cache **complete** (every record the blotter holds is known);
5. start consuming the feed.

Cost is one query and O(records) of parsing: a 100,000-record SOW is a few
seconds and ~80 MB. What is *not* recovered: the ExecID ring beyond the last
ExecID (17), so for the first few reports after a restart duplicate detection
is depth one — the stale and late guards still hold, and a PossDup resend of
the last report is still caught. Older ids past the 9021 cap are not in the
index either; a message under one of them takes the §3 lookup and finds its
record through `orders_audit`.

**Schema.** 9024 is the record's schema version. A missing tag of the current
schema takes its default and `recoveredWithDefaults` counts; a record with an
unknown major version, or without 9020 at all (the publisher pointed at the
wrong topic), refuses the start (R17).

**What a restart does not cover:** messages the feed delivered while the
publisher was down. That is the FIX session's problem — a resend request on
logon, which a QuickFIX/J engine does by itself — which is why the adapter
acknowledges a message to the engine only after it is in the publish store
([02 §7](02-architecture.md#7-threading-and-ordering)). The publisher is
*idempotent under redelivery* (duplicates, stale and late reports are
ignored, requests already in the set are ignored), so a resend that overlaps
what was already applied is harmless.

**`recovery: NONE`** starts with an empty cache and is refused against a
non-empty blotter unless `allow-incomplete-cache: true`: with an empty cache
every D for an existing order would otherwise be a new chain, and every
report a `MIDSTREAM` one (R4). When allowed, the cache is marked
**incomplete**, which turns on the keyed check before any FULL publish
([03 §6.1](03-state-machine.md#61-35d-newordersingle)) and makes §3's lookups
the common path rather than the rare one.

### 2.2 Rebuild by replay (`recovery: JOURNAL`, later phase)

The three audit topics are journalled and hold the **whole message as
received**, plus 9020. A bookmark subscription on the topic regex
`^sow/fix42/(orders_audit|execs_audit|rejects)$` from the epoch (or from a
timestamp) therefore delivers the feed again in journal order, and running it
through a fresh machine reproduces every record, because the machine is
deterministic. The order across topics within one transaction log is the
publish order — the same property [docs/fix42-view/03 §1](../../docs/fix42-view/03-proposed-architecture.md#1-topology)
leans on; **verify on build** that a regex bookmark subscription across
three SOW topics preserves it. The id index alone can be rebuilt more cheaply
still: `orders_audit` records carry `11 → 9020` directly.

Replay is slower than the SOW rebuild (O(messages), not O(orders)) and is the
tool for a different job: rebuilding the blotter **from scratch** after a rule
change or a suspected bug (wipe `sow/fix42/order_state`, replay, compare), or
proving on a quiet instance that the SOW rebuild and the replay agree. While
replaying, the publisher publishes; a delta that changes nothing on the record
is a no-op for the SOW — **verify on build** whether delta subscribers see it.

## 3. Cache misses

A non-D message whose ids resolve to nothing in the index, or a D whose id is
unknown while the cache is incomplete. Causes: `recovery: NONE`, a partial
load, an id older than the 9021 cap, an evicted order (§4), or a feed that
genuinely started mid-stream.

With `miss-lookup: true` (the default), `MissResolver` tries, in order, each a
single keyed read:

| lookup | finds | why it is cheap |
| --- | --- | --- |
| `sow/fix42/orders_audit` by SOW key = the message's **41**, then **11** | the audit record of the request that id names, and its 9020 | 11 is that topic's SOW key; every order has a D there, and every G/F names a predecessor (decision 3) |
| `sow/fix42/rejects` by SOW key = 11 | a rejected request's 9020 | same |
| `sow/fix42/order_state` by SOW key = the message's 11 (a D) | the record itself | the check that keeps a resent D from erasing a record (R4c) |
| `sow/fix42/order_state` filtered `/37 = '<OrderID>'` | the record, when the message carries 37 and none of its ClOrdIDs is known | needs a hash index on /37 on the server to be a lookup rather than a scan — **verify on build** the element name; optional, `miss-lookup-by-order-id` |

A hit rebuilds the entry from the `order_state` record (one more keyed read by
9020) and the message is applied to it; the delta lands on the right record.
Only when every lookup misses is a `MIDSTREAM` entry created. After a complete
load a miss is rare, so none of this is in the hot path for a known order.

Then three cases for what is already in the SOW:

| in the SOW | result |
| --- | --- |
| nothing under that key | AMPS stores the delta as the record. **Verify on build**: the user guide describes a `delta_publish` with no existing record as being stored as a `publish`; if a build rejects it instead, the `FailedWriteHandler` reports it and the publisher falls back to `publish` for `CREATED(MIDSTREAM)`, and nothing is lost because nothing was there |
| a record under that key | the delta merges into it; the record keeps what the message did not carry. Correct, by the same merge property the whole design rests on |
| a record under a **different** key — every lookup missed and the key was minted from a different id | a second record, `9018=MIDSTREAM`, for the same order; `midstreamChains` counts it, and `midstreamWithKnownOrderId` when the message carried a 37. Visible with `/9018 = 'MIDSTREAM'`, and inflating the exposure views until cleaned up by a replay |

The third case was common in the first version of this design (R4) and is now
confined to a message that names no id the audit topics have ever seen.

## 4. Eviction

`terminal-retention` is how long a terminal order's entry stays in the cache
after it went terminal. Default `PT0S` means never evict: at the sizes in
[02 §3.3](02-architecture.md#33-size) a day's flow fits comfortably and a daily
restart with `recovery: SOW` is the natural bound. For a flow that does not
fit, eviction removes the entry's **body** and its ClOrdID index entries but
keeps a **tombstone**: every OrderID the order used → its OrderKey, about 40
bytes each. A partial load (§2.1) builds the same tombstones for the records
it does not keep, so "not loaded" and "evicted" behave alike (R4b).

The tombstone is what makes eviction safe. The messages that legitimately
arrive for a terminal order late are venue messages — a bust, a correct, a
restatement — and every one carries 37. They resolve through the tombstone to
the right key, the entry is recreated from the report (which, being a
restatement, carries exactly the absolutes the entry needs), and the delta
lands on the right record. A late *request* under a new ClOrdID carries no
37, and its 41 resolves through the `orders_audit` lookup of §3 instead.

An order with requests still unanswered when it goes terminal (the venue
never sent the 9) is evicted like any other; its record keeps `9016 > 0`,
which is the right place for the fact to live.

## 5. One writer

Exactly one publisher instance writes `sow/fix42/order_state`. Two machines
delta-publishing from two caches would interleave, and a record is only as
coherent as the single sequence of applies behind it. Active/passive is the
shape, and the passive **starts cold** on promotion with `recovery: SOW`
rather than running the feed with publishing disabled: a cache built from the
feed and one built from the SOW can legitimately differ (ring depth, 9025),
and only the SOW is authoritative. The rebuild is seconds, and the publish
store plus the feed's own resend cover the gap. External leader election; the
publisher does not elect. AMPS's replication story for the topics themselves
is independent and is the [`amps-ha-demo`](../../amps-ha-demo/README.md)
module's subject.

The client name is part of this: a transaction-logged instance allows one
connection per name, so a restarted publisher with the same name waits for
the old session to be reaped (the `NameInUse` handling `fix-pub-seqno`
documents) rather than running beside it — and a second instance started by
mistake is refused by the server for the same reason. The new instance
queries the SOW only after its logon succeeds, so the old instance's bytes
cannot still be in flight behind the snapshot.

## 6. The publish path

The first version of this design published through a plain `Client`, as
`fix42-publisher` does, and mutated the cache before publishing. The review
(R5) showed what that hides: a plain client sends no sequence numbers, does
not reconnect, reports no server-side rejection, and loses whatever is in its
socket buffer on a crash — while the FIX engine, having acknowledged the
message, will never resend it. Cache and SOW diverge with nothing observing
it. Decision 4 is the remedy, in four parts.

**`HAClient` with a publish store.** `publish-store: FILE | MEMORY | NONE`.
With a store, every publish is written to the store before it is sent and
returns at once; the server acknowledges it as persisted; the acknowledgement
discards it; after a reconnect the client replays what is still in the store
and the server drops duplicates by client sequence number. `publish` stays
asynchronous — it returns after the store write, not after the server's
acknowledgement — so the per-message-flush collapse measured in
[docs/fix42-view/06](../../docs/fix42-view/06-publish-throughput.md) does not
apply. The store holds only unacknowledged messages: kilobytes while AMPS is
up, growing only during an outage (a 100 msg/s feed for ten minutes is about
18 MB). Everything in this paragraph is read from the client source in
[fix-pub-seqno/docs/02](../../fix-pub-seqno/docs/02-what-amps-already-does.md).

| | `FILE` (production) | `MEMORY` (demo, tests) | `NONE` |
| --- | --- | --- | --- |
| AMPS restart or network drop, process alive | replayed, nothing lost | same | lost, nothing reported |
| server-side rejection | reported through the `FailedWriteHandler` | same | silent |
| publisher process crash | replayed from the file on restart | the unacknowledged window is lost; the FIX engine has already acknowledged those messages, so the loss is real and bounded by the window | as `MEMORY`, plus the socket buffer |
| disk | small; bounded by the longest outage | none | none |
| per publish | a memory-mapped write | a heap copy | — |

**Every topic this client publishes to is journalled.** AMPS acknowledges a
publish as persisted only once it is in the transaction log
([fix-pub-seqno/docs/02](../../fix-pub-seqno/docs/02-what-amps-already-does.md),
[amps-ha-demo/docs](../../amps-ha-demo/docs/replication-and-the-transaction-log.md));
a message to an unjournalled topic is never acknowledged and the store fills.
So `sow/fix42/order_state` joins the transaction log beside the audit topics
and the two catch-all topics ([02 §1](02-architecture.md#1-topology)).

**A `FailedWriteHandler`.** Every rejection the server reports — a payload it
cannot parse, a missing key, a duplicate after a replay — reaches the handler
with its reason. A duplicate is logged as the expected answer it is. Anything
else counts `publishFailures`, logs the payload, and marks the order's entry
**dirty-all**, so the next outcome for that order republishes the whole
record with `publish`, which repairs whatever the rejected delta failed to
apply. Assumption to verify on build: the Java client reports server-side
rejections only through this handler, which requires a store.

**Two-phase apply.** `OrderStateMachine.plan` stages the changes without
touching the entry; the publisher writes the audit publishes and the blotter
publish through the store; only then `commit()` applies the staged changes
and `FeedAdapter.onMessage` returns, letting the FIX engine acknowledge. A
publish that throws (a store that cannot accept, a client that is closed)
propagates before `commit`, so the cache is unchanged and the message is
redelivered. With `FILE`, a message the engine has acknowledged is on disk;
with `MEMORY`, the unacknowledged window is the loss bound, and §8 states it.

## 7. Observability

Counters on `OrderStatePublisher`, logged as a summary at the end of a run
and on request, with the per-publish INFO line guarded exactly as the old
publisher's is (`WARN` turns it off, and it is the one thing on the path that
allocates):

| counter | meaning | healthy value |
| --- | --- | --- |
| `applied` / `created` / `changed` | outcomes | — |
| `ignoredDuplicate`, `ignoredStale`, `ignoredLate`, `ignoredAbsorbed`, `ignoredLateFill` | reports dropped by [03 §4–5](03-state-machine.md#4-status-venue-truth-and-one-policy-decision) | low; a spike is a feed replaying |
| `ignoredRequest` | duplicate or late requests | low |
| `ignoredUnrouted`, `malformed`, and a histogram per 35 value | lenient mode's catch-all | zero on a feed the rules cover |
| `midstreamChains`, `midstreamWithKnownOrderId` | entries created from a non-D; of those, ones that carried a 37 and still missed | zero on a feed the publisher saw from the start |
| `lateDs` | D's that arrived after their order's reports | — |
| `unmatchedRejects`, `unmatchedConfirms` | 9s and `150=5`s that named no pending request | zero |
| `inferredReplaceAnswers`, `inferredRequests` | a `150=5` resolved by the single-pending rule; pending entries created from acknowledgements | low; a steady rate names a venue convention |
| `requestRejectsAsReports` | `150=8` naming a pending REPLACE or CANCEL | zero; otherwise the venue's dialect |
| `lateFills`, `reopenedByVenue`, `terminalFromReject` | the after-terminal rows and the 9 policy | low; also on the record as 9026 for late fills |
| `inconsistentReports` | reports with `38 ≠ 14 + 151` on a working order | zero; otherwise the venue |
| `pendingOnTerminal` | orders that went terminal with requests unanswered | zero; otherwise a venue that does not reject late requests |
| `idConflicts`, `keyCollisions`, `strippedTags`, `auditKeyTagConflict` | identity and payload hygiene | zero, except `strippedTags` on a framed feed |
| `publishFailures`, `publishDuplicatesOnReplay` | the `FailedWriteHandler` | zero; duplicates after a reconnect are expected |
| `recoveredWithDefaults`, `unrecovered` | the SOW rebuild | zero after the first run on a new schema |
| `fullPublishes`, `deltaPublishes`, `bytesPublished` | wire | — |
| `cacheEntries`, `indexEntries`, `tombstones`, `publishStoreDepth` | size | tracks the day; the store depth is near zero while AMPS is up |

On the record, the same signals per order: `9025` (ignored count), `9026`
(late fills), `9018` (origin), `9016` with `9017` (unanswered on a terminal
order). All are plain SOW filters. Alert thresholds belong to the deployment;
the ones worth setting first are `publishFailures > 0`,
`midstreamWithKnownOrderId > 0`, `idConflicts > 0` and
`pendingOnTerminal` rising.

## 8. Failure matrix

| failure | what the record shows | how it is found | recovery |
| --- | --- | --- | --- |
| publisher crashes mid-flow, `publish-store: FILE` | every message the engine acknowledged is on the record or in the store | the store replays on restart; the feed's resend redelivers what the engine had not acknowledged | restart; `recovery: SOW` rebuilds the cache; redelivered messages are applied once |
| publisher crashes mid-flow, `publish-store: MEMORY` | messages in the unacknowledged window are missing from the record **and the engine will not resend them** | `publishStoreDepth` at the crash is the bound | accept the bound, or run `FILE`; `recovery: JOURNAL` cannot help because the audit publishes were in the same window |
| publisher restarts on another host | as above; with `FILE` the store file must travel | — | same |
| AMPS restarts, or the network drops | the record is behind by the unacknowledged window until reconnect | the `HAClient` reconnects and replays | nothing to do; duplicates on replay are logged as expected |
| AMPS rejects a publish (unparseable payload, missing key) | that delta is missing | `publishFailures`; the handler logs the payload | the entry is dirty-all; the next outcome republishes the whole record |
| `sow/fix42/order_state` SOW file lost, journal intact | empty blotter | the rebuild finds nothing; `midstreamChains` climbs | `recovery: JOURNAL` replays the audit topics and rebuilds the records |
| two cancels race, either order | `39=4`, `9013=NONE` | — | n/a: [03 §7.1–7.2](03-state-machine.md#71-two-cancels-confirm-then-reject-the-motivating-case) |
| feed replays a PossDup range | unchanged; `9025` counts | `ignoredDuplicate` | n/a |
| an old status report is redelivered | unchanged; `9025` counts | `ignoredLate` | n/a (decision 1) |
| venue reports a trade after confirming the cancel | 14/6 updated, `39=4` kept, `9026=1` | `lateFills` | n/a by default; `late-fill-after-terminal` if the venue means it as a reopen |
| venue reports a replace as cancel-then-new | one message reading canceled, then working with the new terms | `reopenedByVenue` | n/a; a hook suppresses the cancel leg |
| venue busts or corrects a fill on a closed-out order | restated 14/6 on the same record; `32=0 31=0` if it was the last fill | — | n/a: reopening reports are always applied |
| venue rejects an amend with a `150=8` | the amend removed, 38/44 untouched | `requestRejectsAsReports` | n/a |
| feed starts mid-stream | `9018=MIDSTREAM`, terms as far as the reports give them | `midstreamChains` | the late D, if it ever comes, fills the terms (`D_LATE`) |
| cache evicted or did not load an order; a late bust | the right record, restated | the tombstone resolves it | n/a |
| cache lacks an order; a late request under a new ClOrdID | the right record, via the `orders_audit` lookup | `missLookups` | n/a; without `miss-lookup`, a second `MIDSTREAM` record |
| a D is resent, or a day replayed, against a populated SOW with an empty cache | the record is kept | the keyed check before a FULL publish | n/a (R4c) |
| venue never answers a request | `9016 > 0` on an otherwise finished order | `pendingOnTerminal`; filter `/9016 > 0 AND /9017 = 'Y'` | operational: the venue, not the publisher |
| a request rejected by `35=j` instead of `35=9` | the request stays pending | `9016 > 0`; the j on `fix42/unrouted` | a follow-up (the MsgSeqNum ring); until then, operational |
| a second publisher instance starts by mistake | nothing: `NameInUse` | the server | it retries until told to stop |
| a hook throws | nothing changes; the feed stops at that message | the exception | fix the hook; redeliver |
| a new 150 value appears | applied with the venue's 39; it touches no pending entry | the per-150 histogram | add it to the answering or reopening sets if it should be |
| an unknown 35 type appears | recorded on `fix42/unrouted` | the per-35 histogram | a later version replays the topic |

## 9. The daily reset

Decision 2 keeps the drop copy's own ids as keys, so a ClOrdID reused on a
later day would resolve to the previous day's record if that record were
still there. The reset is therefore part of the design, not housekeeping:

1. after the session closes and the publisher has flushed and stopped,
   export the records a desk may want to investigate — `sow` with
   `/9016 > 0`, `/9018 = 'MIDSTREAM'`, `/9025 > 0`, or simply everything —
   to a file;
2. delete `sow/fix42/order_state`'s records (`sow_delete` by filter, or the
   SOW file while AMPS is down); **never** the journalled audit topics, which
   are the history and the replay source, nor `sow/fix42/execs`, which is
   keyed by OrderID and does not collide;
3. start the publisher with `recovery: SOW` against the empty blotter.

A deployment that must keep several days on one blotter does not reuse
ClOrdIDs across days — most OMSs date-stamp them — and runs the reset weekly
or not at all; with reused ids it runs daily. The journal's ageing policy is
set independently ([docs/src/transaction-log-sizing.md](../../docs/src/transaction-log-sizing.md)).

## 10. Running it

Mirrors `fix42-publisher`:

```bash
export AMPS_IMAGE=localhost/amps-demo:5.3.5.135
AMPS_FLOW=fix42-order-state ./server/scripts/amps.sh start
./gradlew :fix42-order-state-publisher:bootRun
```

The runner publishes the scripted flow plus the edge-case scenarios from
phase 1 of the [plan](05-implementation-plan.md), with `publish-store: MEMORY`
and `mode: strict`, and exits; the admin console at `http://127.0.0.1:8085/`
can `SELECT` from `sow/fix42/order_state`, and the two-cancels order is the
one to look at first.
