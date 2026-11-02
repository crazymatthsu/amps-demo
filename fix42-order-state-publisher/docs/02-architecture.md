# 2. Architecture

The module is `fix42-publisher` with one component replaced. Where the old
publisher handed every message to a *planner* that selected tags by route and
let the server resolve the record, this one hands it to a *state machine* that
resolves the record itself, applies the message to a cached order state, and
emits the fields that changed. Everything around that — the FIX parsing, the
mock venue, the audit routing, the Spring Boot shell — is **copied** into this
module's own package with its shape intact. The two modules share no code and
neither depends on the other: each builds, tests and demos on its own, so
either can be lifted out of this repository as a standalone project without
taking the other along. The only thing they have in common is the
`amps-test-harness` module that starts a container for their integration
suites, which every module in the repository already uses.

---

## 1. Topology

```
 FIX 4.2 drop-copy feed (mock venue in the demo; a QuickFIX/J session in life)
        │  FeedAdapter: raw messages, in session order, one thread;
        │  acknowledges a message to the session only after it is in the publish store
        ▼
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │ fix42-order-state-publisher                                                  │
 │                                                                              │
 │   FixFrame ──► OrderStateMachine.plan(frame)   pure: reads the entry, stages │
 │                  OrderKeyIndex   11/41 ──► ClOrdID map; 37 ──► OrderID map   │
 │                  MissResolver    keyed lookups on a miss (docs/04 §3)        │
 │                  OrderState      one small entry per order (§3)              │
 │                  rules           docs/03: identity, pending set, arbitration │
 │       │                                                                      │
 │       ▼  Outcome: CREATED (full record) | CHANGED (dirty fields) | IGNORED  │
 │   RecordWriter ──► publish        (35=D opens a chain)    sow/fix42/order_state
 │                ──► delta_publish  (everything else)       key /13011 OrderKey
 │       │                                                                      │
 │       ▼                                                                      │
 │   AuditRouter ──► publish (whole message + 13011 trailer) ─►  sow/fix42/orders_audit
 │                     per route; key check per topic;          sow/fix42/execs_audit
 │                     catch-all in lenient mode                sow/fix42/execs
 │       │                                                      sow/fix42/rejects
 │       │                                                      fix42/unrouted, fix42/malformed
 │       ▼  every publish accepted into the HAClient's publish store            │
 │   OrderStateMachine.commit()         the entry changes; the adapter acks     │
 │                                                                              │
 │   FailedWriteHandler: a server-side rejection queues a repair for the        │
 │   publishing thread (docs/04 §6)                                             │
 │   on start: SowCacheLoader  sow sow/fix42/order_state ──► rebuild cache      │
 └──────────────────────────────────────────────────────────────────────────────┘
        │
        ▼  AMPS, flow fix42-order-state (no modules)
   sow/fix42/order_state          plain SOW, Key /13011, journalled      the blotter
   sow/fix42/orders_audit  /11    journalled                            system of record
   sow/fix42/execs_audit   /17    journalled                            system of record
   sow/fix42/rejects       /11    journalled                            system of record
   sow/fix42/execs         /37    journalled; latest report per order   convenience
   fix42/unrouted, fix42/malformed   journalled, not SOW                lenient mode's catch-all
   view/fix42/exposure/*          json aggregations over order_state, unchanged shape
   view/fix42/recon/parent_vs_child
```

Compared with the `fix42-chaining` flow: `sow/fix42/orders` (chained) becomes
`sow/fix42/order_state` (plain key), the `<Modules>` block is gone, the four
views point at the new topic, and two journalled non-SOW topics catch what no
route claims. The audit and exec topics keep their names and keys, so the
`amps-cli` commands and the admin console queries in the existing READMEs
still work.

**Every topic this client publishes to is journalled** — the blotter and
`sow/fix42/execs` included, which the first version left out as "derivable".
Decision 4 puts the publisher on a publish store, and AMPS acknowledges a
publish as persisted only for a journalled topic
([04 §6](04-recovery-and-operations.md#6-the-publish-path)); a store that is
never acknowledged never drains. The cost is the blotter's deltas, about
200 MB for a million-message day, and a second copy of every report for
`execs`, beside the audit topics' 300 MB, all under the same ageing policy. A
deployment that does not want the second copy drops the `execs` route; the
same question is answered by `execs_audit` filtered by `/13011` or `/37`.

## 2. Components

| component | responsibility | state | where it comes from |
| --- | --- | --- | --- |
| `FixFrame` / `FixWriter` / `FixMessage` / `FixTags` / `Prices` | parse, index and render FIX; the tag vocabulary | none | copied from `fix42-publisher` into this module's package in phase 0; `FixTags` gains 13011, the 15011/15035/15038/15044 pending family and 12014–12033 |
| `OrderChain` / `MockFixFlow` / `Instrument` / `FixEvent` / `OrderScope` | the deterministic mock venue | per chain, test only | copied in phase 0; extended in phase 1 with multi-request scenarios and venue-dialect switches |
| `FeedAdapter` | hands each received message to the publisher and acknowledges it to the session only after `commit()` | none | new; `MockFlowRunner` implements it for the demo, a QuickFIX/J `fromApp` adapter in phase 4 |
| `AuditRouter` | which pass-through topics a message goes to, whole, plus tag 13011; the per-payload key check; the lenient-mode catch-all | none | new, a simplified `PublishPlanner`: no projection, FULL by default |
| `OrderKeyIndex` | two maps — ClOrdID → OrderKey and OrderID → OrderKey, never conflated ([03 §3.1](03-state-machine.md#31-resolution)); tombstones; answered ids | one map entry per id | new |
| `OrderState` | the cache entry (§3) | one per order | new |
| `PendingRequest` set | the in-flight D/G/F requests of one order, requested or inferred | inside `OrderState` | new |
| `OrderStateMachine` | `plan`: resolve, arbitrate, stage; `commit`: apply. Reports what changed | owns the two maps | new, rules in [03](03-state-machine.md) |
| `RecordWriter` | encode an `OrderState` as FIX tags: whole, or only the dirty fields; the 12015/12021 encodings and their parsers | per-thread buffer | new |
| `OrderStatePublisher` | `send(FixFrame)`: plan → blotter publish → audit publishes → `commit`, all through a `PublishSink`; drains the `RepairQueue` before each message, on an idle timer and at shutdown; counters | per-thread scratch | new, the shape of `AmpsDeltaPublisher` with a store ([04 §6](04-recovery-and-operations.md#6-the-publish-path)) |
| `PublishSink` / `amps.AmpsPublishSink` | the core's output: `publish`, `deltaPublish`, `flush`; over AMPS, the `HAClient` and its publish store | none | new; the contract a non-AMPS sink keeps is [07 §3.1](07-extension-points.md#31-a-feed-and-a-sink-other-than-amps) |
| `amps.AmpsClientConfiguration` | an `HAClient` with a `PublishStore` (`FILE`, `MEMORY` or `NONE`) and an `amps.AmpsFailedWriteHandler` that copies a rejected publish into the core's `RepairQueue` — it never touches an entry itself | none | new, after `amps-connectors` and `fix-pub-seqno` |
| `SowCacheLoader` (`recovery`) | on start, rebuild the maps from every record a `RecordSource` reads back — over AMPS, `amps.SowRecordSource`'s `sow` of `sow/fix42/order_state`; tombstones for records not kept; schema version check | none | new, [04](04-recovery-and-operations.md) |
| `amps.MissResolver` | on a cache miss, the keyed lookups of [04 §3](04-recovery-and-operations.md#3-cache-misses) before a `MIDSTREAM` entry is created; the AMPS implementation of the core's `MissLookup` | none | new |
| `OrderStateProperties` | the yml surface (§6) | none | new, the shape of `Fix42Properties` |
| `OrderStateHook` (SPI) + `VenueDialect` | per-venue policy bundles, and hooks called before and after each apply | none, by contract | new, [07](07-extension-points.md) |
| `Fix42OrderStatePublisherApplication` + `MockFlowRunner` | the Spring Boot shell; publishes the scripted and edge-case flows and exits | none | copied |

The split that matters for testing is the same as before: everything up to
`RecordWriter` is pure and runs without a server, and the publisher runs
against a recording `PublishSink` in the unit tests. Only the `amps`
package touches an AMPS client: `AmpsPublishSink`, `AmpsFailedWriteHandler`,
`SowRecordSource` and `MissResolver`, connected by `AmpsClientConfiguration`.
The rest is the core, which imports neither AMPS nor Spring
([11 §7](11-build-findings.md#7-the-core-as-a-library-amps-and-spring-behind-the-seams)).

## 3. The cache

### 3.1 One entry per order

The entry holds what conditional apply needs and nothing a subscriber could
get from the record instead. From the requirement: the id fields 11, 41, 37;
the order quantity and price from D/G/F; the confirmed status, CumQty and
AvgPx from `35=8`. Plus the fields without which those cannot be kept right:

| field | from | why it is in the cache |
| --- | --- | --- |
| `orderKey`, `keyKind` | minted at creation ([03 §3.2](03-state-machine.md#32-creation)) | the SOW key; 12019 |
| `clOrdIds` | every 11 bound to this order, oldest first | the index; published as 12021 (last N) and 12030 (count) |
| `workingClOrdId` | the id of the last answered NEW or REPLACE | 12014 |
| `orderIds` | every 37 seen, latest first | binds later reports; the record shows the latest; the tombstone keeps them all |
| `parentOrderId` | 9000 | routes a report that lacks 9000 to the right scoped topic |
| `linkedClOrdId` | 41 on the D | 12027, lineage only |
| `ackedQty`, `ackedPx` | 38/44 from the D, then from the venue's reports only | "working at what"; absent on a chain created from a request |
| `venueStatus` | 39 from the last applied report; `A` until the first on a D-opened chain | terminal rules, and the 35=9 policy |
| `lastExecType` | 150 | published as 150 |
| `cumQty`, `leavesQty`, `avgPx` | 14/151/6, held as numbers | the stale guard, and 151 forced to 0 on terminal |
| `lastExecId` (+ a ring, default 8) | 17 | duplicate detection |
| `lastFillExecId`, `lastFillCorrectionExecId` | the fill's 17; a correct's 17 | 12028; which execution a bust's 19 may name when it busts the last fill |
| `bustedExecIds` | 19 of every bust and correct, 17 of every correct | 12031; a redelivered bust is a duplicate however old |
| `createdPayload` | the FULL publish's bytes | kept until its persisted acknowledgement, so a rejected creation can be re-sent whole ([04 §6](04-recovery-and-operations.md#6-the-publish-path)) |
| `lastReportTime` | max 60 over applied reports | 12029; the late guard and the after-terminal tiebreak |
| `pending` | the set of unanswered requests: `{clOrdId, action, requested-or-inferred, proposedQty, proposedPx}` | the whole point |
| `msgCount`, `ignoredCount`, `lateFills` | bookkeeping | 12023 / 12025 / 12026 |
| `origin` | `D`, `MIDSTREAM`, `D_LATE`, `UNRECOVERED` | 12018 |
| `dirty` | a bitmask over the record fields | what the next delta carries; `dirtyAll` after a publish failure |

LeavesQty is on the list although the requirement did not name it: it is on
every report, it is the field the exposure views sum, and the machine has to
force it to zero on a terminal report whether or not the venue sent it. The
quantities are held as numbers, never as the venue's text, so `300`, `300.0`
and `0300` compare equal; the record keeps the venue's text as sent.

### 3.2 What is deliberately not in it

- **The order's descriptive terms** (account, symbol, side, type, TIF,
  destination, …). They are on the record from the FULL publish of the D and
  nothing in the machine conditions on them. Scope (9000) is the one exception,
  because routing needs it when the venue does not echo it.
- **Every ExecID ever seen.** The contract's dedupe set grows with every fill.
  A PossDup resend repeats the *last* report or a short run of them, so a ring
  of the last N ExecIDs catches the real-world case; the CumQty stale guard
  and the TransactTime late guard catch an older report regardless of its
  ExecID.
- **Per-request prior-status snapshots.** The contract reverts status on a 9
  from a snapshot taken when the request was sent. This machine does not move
  the status on a request at all ([03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision)),
  so there is nothing to revert and nothing to snapshot. That removes the
  hardest bookkeeping from the cache and is what makes two concurrent requests
  trivial rather than special.
- **The last fill's 31/32/30.** They go straight to the record on each fill;
  only the fill's ExecID and its latest correction's (12028, 12031) are kept, so
  a bust of the last fill can zero them.
- **A history.** The audit topics are the history, filterable by 13011.

### 3.3 Size

Per order, on a 64-bit JVM with compressed oops: the entry object with its
primitives and references ≈ 150 B; five or six short id strings ≈ 300 B; the
`clOrdIds` list with two or three entries ≈ 120 B; the pending set, allocated
lazily and empty most of the time ≈ 0–120 B; index entries at ≈ 40 B per bound
id (the strings are shared with the entry). Call it **0.7–0.9 KB per order
with two or three ClOrdIDs**. A desk doing 100,000 orders a day holds ~80 MB
with nothing evicted; a million-order day wants the terminal-eviction knob
(§6, [04 §4](04-recovery-and-operations.md#4-eviction)) or a bigger heap. An
algo child re-priced a thousand times holds a thousand ids: ~60 KB for that
one order, and 12021 on its record is capped so the deltas do not grow with
it. The figures are estimates to be measured in phase 5, not promises.

## 4. The record

`sow/fix42/order_state`, `MessageType fix`, one record per order, keyed by
tag 13011. Every field below is written by the machine; the record is the
machine's state made visible, which is also what makes it a sufficient
checkpoint ([04 §1](04-recovery-and-operations.md#1-the-sow-record-is-the-checkpoint)).

| tag | name | written when | value |
| --- | --- | --- | --- |
| **13011** | RootClOrdID | the FULL publish of the `35=D` | the SOW key: the D's own tag 11, set once and never changed; for a chain first seen mid-stream, the ClOrdID closest to the root that its first message carries (41, else 11), and only when it carries neither, the OrderID — always one of the drop copy's own ids, unprefixed (decision 2) |
| 12019 | KeyKind | creation | `C` (a ClOrdID, the normal case) or `O` (an OrderID, the degenerate one); metadata only, the key is never altered |
| 12021 | ClOrdIDChain | each new 11 bound | the last `chain-ids-published` (default 50) ids, oldest first, comma-joined, `%`-escaped |
| 12030 | ClOrdIDCount | each new 11 bound | how many ids the chain has used; when it exceeds 12021's length the rest are on `orders_audit` under this 13011 |
| 12014 | WorkingClOrdID | a NEW or REPLACE is answered | the request id the venue last accepted — what the next request's 41 should be |
| 11, 41 | ClOrdID, OrigClOrdID | every applied message | ids of the *last applied* message — not the chain's identity, which is 13011/12021 |
| 37 | OrderID | every report carrying a real one | the latest non-`NONE` OrderID; `NONE` is never written |
| 12027 | LinkedClOrdID | the D | a 41 carried on the D (a cancel-and-new OMS): lineage, not followed |
| 60 | TransactTime | every applied message | the last applied message's — a request's is the client's clock, a report's the venue's |
| 12029 | LastReportTime | every applied report and reject | the latest TransactTime of any applied venue message; the tiebreak of [03 §4–5](03-state-machine.md#4-status-venue-truth-and-one-policy-decision) |
| 12022 | LastMsgType | every applied message | D/G/F/8/9 — the record itself carries **no tag 35** |
| 12023 | MsgCount | every applied message | messages applied to this order |
| 12025 | IgnoredCount | every ignored message | duplicates, stale, late and absorbed reports, duplicate and late requests; marked dirty so the next delta carries it |
| 12026 | LateFills | a late execution applied | [03 §7.12](03-state-machine.md#712-cancel-confirmed-then-a-late-execution) |
| 12024 | SchemaVersion | creation; bumped by a newer writer | `2`, since the move from 9xxx to 12000–12999 ([11 §6](11-build-findings.md#6-renumbering-the-machines-tags-move-to-1200012999-schema-2)); the loader refuses an unknown major version, and a record of the 9xxx scheme |
| 1, 55, 54, 40, 59, 15, 100, 21, 109, 110, 111, 47, 48, 22, 126 … | order terms | the D's FULL publish, less the stripped tags; `order-term-tags` again on `150 ∈ {0, 5, D}` | the D's whole message less the session header and trailer and less anything in the machine's own tag ranges |
| 38, 44 | OrderQty, Price | the D; then venue reports only — never a pending acknowledgement, never a `150=8` | **acked** terms; `44=0` once the order is a market order |
| 39 | OrdStatus | `35=8` always; `35=9` per policy; `A` stamped at creation from a D; absent on a chain created from a request | venue truth |
| 150 | ExecType | every applied `35=8` | what the last report was |
| 17 | ExecID | every applied `35=8` with a real one | the last applied report's |
| 14, 151, 6 | CumQty, LeavesQty, AvgPx | every applied `35=8`; 151 **forced to 0** when 39 is terminal | venue absolutes |
| 32, 31, 30 | LastShares, LastPx, LastMkt | fills and corrects; `32=0 31=0` on a bust of the last fill | the most recent fill |
| 12028 | LastFillExecID | fills and corrects | which execution 32/31 describe; kept after a bust zeroes them, so a reader sees why |
| 12031 | BustedExecIDs | busts and corrects | the 19 of every bust and correct and the 17 of every correct, escaped like 12021; a bust or correct naming one again is a duplicate |
| 15038, 15044 | PendingOrderQty, PendingPrice (the pending 38 and 44) | pending set changes | the terms of the **newest pending REPLACE**, else 0 — even when a cancel is the newest request; also 0 while the REPLACE is only inferred, or for a term it did not propose (a replace to market has no price) |
| 15011 | PendingClOrdID (the pending 11) | pending set changes | the newest in-flight request's id, else `NONE` |
| 15035 | PendingAction (the pending 35) | pending set changes | `NONE` / `NEW` / `REPLACE` / `CANCEL` of the newest in-flight request |
| 12015 | PendingRequests | pending set changes | every in-flight request, §4.1, or `NONE` |
| 12016 | PendingCount | pending set changes | size of the set |
| 12017 | Terminal | 39 changes | `Y` when 39 ∈ {2, 3, 4, 8, C}, else `N` |
| 12018 | ChainOrigin | creation; a late D; a failed rebuild | `D`, `MIDSTREAM`, `D_LATE`, `UNRECOVERED` |
| 9000 | ParentOrderID | the D | child slices only, as today |
| 12100–12999 | — | hooks | reserved for deployment-specific fields written through the SPI ([07 §2](07-extension-points.md#2-tier-two-hooks)); the machine never writes or reads them |

**The tag scheme.** Ranges in FIX's internal-use block (10000–19999), each
with one meaning:
**13xxx is the root value of standard tag xxx** (13011, the root ClOrdID);
**15xxx is the pending value of standard tag xxx** (15011 the pending ClOrdID,
15038 the pending OrderQty, 15044 the pending Price, 15035 the pending
action — the message type of the request in flight); **12000–12099** is the
machine's bookkeeping. The user-defined 9xxx range is left to the deployment:
the machine writes nothing there, and a 9xxx tag on a message passes through
to the record like any descriptive field (9000, ParentOrderID, is one; until
schema 2 the bookkeeping was 9014–9033 and the hooks 9100–9999, docs/11 §6).
A future staged term takes its number from the scheme — a pending
TimeInForce would be 15059 — and hooks write in 12100–12999. Tags 12032 and 12033
are not record tags: they wrap unparseable bytes on `fix42/malformed`
([03 §9](03-state-machine.md#9-unrouted-and-unparseable-messages)).

Three deliberate choices in that table:

**The record has no tag 35.** On the chained blotter tag 35 was the last
message's type, and a record reading `35=9` was half of the problem in
[01](01-problem-the-two-cancels.md). 12022 carries the same information under
a name that says what it is. AMPS's fix parser does not need a 35 (verify on
build); FIX-dictionary tooling fed the record will object, and the record is
not a session message in any case.

**Tag 39 is the venue's.** It is written by execution reports, by a `35=9`
only under the policy in [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision),
and by nothing else. A request never moves it; the pending family says a
request is in flight.

**The pending family keeps the old blotter's meaning under new numbers.**
`fix42-publisher` published it as 9010–9013; here it is 15038, 15044, 15011
and 15035 (the deployment's scheme: 15xxx is the pending value of tag xxx),
and 12014 is unchanged. A consumer of the old blotter changes four tag numbers
and the key field. 12015/12016 are the full set for a consumer that wants it.

### 4.1 Several requests in flight: tag 12015

The pending set is one tag holding every unanswered request, and 15038/15044/15011/15035
are a summary of its newest member. Collected here because the question
"what if more than one request is in flight?" is the question this module
exists to answer.

**Encoding.** One element per request, **five fields in a fixed order with
the id last**: `ACTION:flag:qty:px:id`, elements joined by `;`, **oldest
first**. `ACTION` is `NEW`, `REPLACE` or `CANCEL`; `flag` is `R` (the request
itself was seen) or `I` (inferred from the venue's pending acknowledgement,
[03 §6.4](03-state-machine.md#64-358-executionreport)); `qty` and `px` are
the proposed terms of a REPLACE and **empty** where nothing was proposed — a
NEW, a CANCEL, an inferred REPLACE, a replace to market with no 44 — so a
genuine price of zero and "no proposal" never read the same (S17); the tag
value itself is never empty. Any `%`, `:`, `;` or `,`
inside an id is `%`-escaped (`%25`, `%3A`, `%3B`, `%2C`), in 12015 and in 12021
alike, so an id such as `20261031:DESK7:000123` round-trips (R11). The empty
set is the literal `NONE`, not an absent or empty tag: a delta publish can
overwrite a field but never remove it, so the cleared state must be a written
value. 12016 is the element count.

After an amend and then a cancel on the same order
([03 §7.4](03-state-machine.md#74-amend-and-cancel-both-in-flight-reject-for-the-cancel-contract-edge-4)):

```
12015=REPLACE:R:12000:185.75:A-2;CANCEL:R:::A-3
12016=2
15035=CANCEL  15011=A-3  15038=12000  15044=185.75   <- newest request is the cancel;
                                                      15038/15044 still describe the pending amend
```

When the `35=9` for A-3 arrives it is removed and the summary falls back to
what remains:

```
12015=REPLACE:R:12000:185.75:A-2
12016=1
15035=REPLACE  15011=A-2  15038=12000  15044=185.75
```

**A set, not a stack.** Each answering message removes the request whose
ClOrdID equals its own tag 11 and whose kind its ExecType answers: a `150=4`
names the cancel it confirmed, a `35=9` the request it rejected, a `150=5` the
amend it accepted ([03 §6.4–6.5](03-state-machine.md#64-358-executionreport)).
Answers may therefore arrive in any order, which is what makes
confirm-then-reject and reject-then-confirm converge on the same record. A
fill that carries a pending request's id is **not** an answer and leaves the
set alone, unless `fill-under-pending-id: ANSWERS_REPLACE_IF_TERMS_MATCH` says
a fill at the proposed terms is. A request the venue never answers stays in the set; the filter
`/12016 > 0 AND /12017 = 'Y'` finds such orders.

**Size.** An array list inside the cache entry, allocated on the first
request after the D's own NEW is answered. In practice it holds zero to two
elements, three in the amend-plus-two-cancels case. There is no fixed upper
bound; a venue that leaves many requests unanswered grows the tag, and the
`pendingOnTerminal` counter ([04 §7](04-recovery-and-operations.md#7-observability))
is the signal.

**Recovery.** 12015 is parsed back into the set on a restart
([04 §1](04-recovery-and-operations.md#1-the-sow-record-is-the-checkpoint)),
so no in-flight request is lost. A record whose 12015 or 12021 does not parse
is loaded with an empty set, marked `12018=UNRECOVERED`, and counted; never
silently.

**Consumer caveat.** 15038/15044/15011/15035 describe the newest request
only, as the old blotter's 15038–15035 did. A consumer that cares about
more than one in-flight request must read 12015.

## 5. Publish modes

| message | to the blotter | why |
| --- | --- | --- |
| `35=D` for an unknown chain, cache complete | **`publish`** of the whole record: the D's fields less the stripped tags, plus every state tag of §4 with its initial value; the payload is kept until AMPS acknowledges it | creates the record; every later delta relies on the terms being there, which is also why a rejected creation is re-sent whole rather than rebuilt from the cache ([04 §6](04-recovery-and-operations.md#6-the-publish-path)) |
| `35=D` for an unknown chain, cache **incomplete** (`recovery: NONE`, a partial load) | a keyed `sow` by `13011 =` 11 first; a hit makes it the late-D or resent-D case below | a FULL publish over a record the cache merely did not know about would erase every delta since (R4c) |
| any other message that changes the state | **`delta_publish`** of the **dirty fields only**: whatever the machine changed, plus the last-message bookkeeping (11, 41, 60, 12022, 12023) | the merge preserves everything else — **verified** by `Fix42DeltaPublishIT` |
| a non-D for an unknown chain, after the miss lookups found nothing | `delta_publish` of the best-effort record, `12018=MIDSTREAM` | if a record exists the merge enriches it; if none exists AMPS stores the delta as the record — **verify on build**, with a `publish` fallback ([04 §3](04-recovery-and-operations.md#3-cache-misses)) |
| a `35=D` for a chain that already exists (late D after a mid-stream report, or a resent D) | `delta_publish` of the D's descriptive terms, never 39/14/151/6, and 38/44 only when the record has neither and no report has been seen; or nothing for a resend | [03 §6.1](03-state-machine.md#61-35d-newordersingle) |
| a message the machine ignores | nothing, except a `delta_publish` of 12021/12030 when the message bound a new id; `12025` is marked dirty for the next delta; `publish-unchanged: true` sends 12025 immediately | the audit topics have the message; the index must stay recoverable (R16) |

"Dirty fields only" is computed, not configured: `OrderState` keeps a bitmask
of the fields each apply touched, and `RecordWriter` walks the mask. "Touched"
rather than "changed": a fill re-sends 38 even when it did not move, which
keeps the writer simple and the record checkable against itself. A fill puts
roughly a dozen tags on the wire and a pending ack (`150=6`) five.

**Order of operations, once:** `plan` (including any miss lookup) → the
blotter publish → the audit publishes → `commit`. The audit publishes come
after `plan` because their 13011 trailer needs the resolved key (S20), and
after the blotter publish because that is the one that must not be lost; the
audit copies are keyed, so a redelivery after a failure between the two
overwrites them idempotently (two journal entries, one SOW record). The
non-SOW `fix42/unrouted` and `fix42/malformed` can carry a duplicate after
such a redelivery; stated in [04 §8](04-recovery-and-operations.md#8-failure-matrix),
not prevented (S14).

The pass-through topics receive the message **whole** (`publish`) **plus tag
13011** appended as a trailer, after any tag 10 the message carries, so a
framed message's BodyLength and CheckSum stay valid for the bytes they cover
(decision 3, [08 §7.1](08-review-findings.md#71-tag-13011-on-the-audit-publishes)).
The trailer is omitted when the message resolved to no chain (an unmatched
`35=9`, an unrouted type). A message that already carries 13011 is published
as it is and `auditKeyTagConflict` counts. The audit topics thereby hold the message as
received, filed under the key the machine gave it: one `sow` with
`/13011 = '<key>'` per audit topic is the order's complete history, and a
keyed read of `orders_audit` by any ClOrdID is the cheap cache-miss lookup
of [04 §3](04-recovery-and-operations.md#3-cache-misses). `AuditRouter` keeps
the stateless planner's per-payload key check, **per topic**: a message that
lacks one topic's key tag — a `20=3` status report with no 17, say — skips
that topic and goes whole to `fix42/unrouted` instead, is still published to
the topics whose key it carries, and is still applied to the blotter
(R21, S10). A `key-guard` on a route does the same for sentinel values
(`37=NONE`, `17=0`).

## 6. Configuration

Bound to `OrderStateProperties`, validated at startup the way `Fix42Properties`
is (a route naming a topic with no `topic-keys` entry, or whose selection
omits the key, refuses to boot; `audit.key-tag` must equal
`order-state.key-tag`; `recovery: NONE` against a non-empty blotter refuses
to start unless told otherwise).

```yaml
fix42-order-state:
  # Profiles: the shipped defaults below are production's. The `demo`
  # profile (bootRun, the integration tests) overrides publish-store: MEMORY
  # and mode: strict.
  amps:
    uri: tcp://127.0.0.1:9007/amps/fix       # must end /fix, as before
    client-name: fix42-order-state-publisher
    timeout-ms: 10000
    # The HAClient's publish store: FILE (production), MEMORY (demo, tests),
    # NONE (the stateless publisher's at-most-once). docs/04 §6.
    publish-store: FILE
    publish-store-dir: build/client-state/fix42-order-state
    reconnect-delay-ms: 1000

  # strict: an unrouted or unparseable message raises (tests, development).
  # lenient: it is recorded on fix42/unrouted or fix42/malformed and the
  # feed continues (production). docs/03 §9.
  mode: lenient

  order-state:
    topic: sow/fix42/order_state
    key-tag: 13011                 # RootClOrdID; 13xxx = the root value of tag xxx
    schema-version: 2              # 12024; 2 since the record left 9xxx (docs/11 §6)
    # What a 35=9's tag 39 may do to the record. docs/03 §4.
    cancel-reject-status: WHEN_NOT_TERMINAL          # NEVER | ALWAYS
    # A fill with higher CumQty after a terminal report. docs/03 §4, §7.12.
    late-fill-after-terminal: APPLY_ECONOMICS        # ADOPT_VENUE | IGNORE
    # A non-fill report with a non-terminal 39 after a terminal report:
    # a venue-asserted reopen if its TransactTime is not older. docs/03 §4, §7.16.
    non-fill-after-terminal: ADOPT_VENUE_IF_LATER    # ADOPT_VENUE | IGNORE
    # A fill carrying a pending amend's ClOrdID. docs/03 §6.4.
    fill-under-pending-id: IGNORE                    # ANSWERS_REPLACE_IF_TERMS_MATCH
    # Drop a non-reopening report whose CumQty went backwards.
    stale-guard: true
    # Drop a non-reopening report with equal CumQty and an older TransactTime
    # than the last applied report's. docs/03 §5.
    transact-time-guard: true
    # Depth of the per-order ExecID ring used for duplicate detection.
    exec-id-history: 8
    # Order-term tags adopted from a 150=0/5/D report when present.
    order-term-tags: [18, 40, 44, 59, 99, 100, 110, 111, 126]
    # Session header/trailer tags removed from the D's FULL publish; the
    # internal ranges 12000–12999, 13000–13999 and 15000–15999 are always removed. docs/03 §6.1.
    strip-tags: [8, 9, 10, 34, 43, 49, 50, 52, 56, 57, 97, 115, 116, 122, 128, 129]
    # How many ClOrdIDs 12021 carries; 12030 has the count. docs/02 §4.
    chain-ids-published: 50
    # Evict terminal orders from the cache after this long; PT0S = never.
    terminal-retention: PT0S
    # Rebuild the cache at startup: SOW (default), NONE, later JOURNAL. docs/04 §2.
    recovery: SOW
    # Keep a cache body only for open orders; terminal records yield a
    # tombstone (every 37 -> 13011) instead. docs/04 §2.1.
    load-open-orders-only: false
    # Let recovery NONE start against a non-empty blotter.
    allow-incomplete-cache: false
    # On a cache miss, look the order up through the audit topics before
    # creating a MIDSTREAM entry. docs/04 §3.
    miss-lookup: true
    # Also try sow/fix42/order_state by /37 (needs a server-side index on 37).
    miss-lookup-by-order-id: false
    # Publish 12025 at once for messages the machine ignored.
    publish-unchanged: false
    # Named bundles of the policy knobs above, one per venue. docs/07 §1.
    dialect: default
    dialects:
      venue-z:
        cancel-reject-status: NEVER
        late-fill-after-terminal: ADOPT_VENUE
        fill-under-pending-id: ANSWERS_REPLACE_IF_TERMS_MATCH

  audit:
    # Appended to every pass-through publish as a trailer; 0 disables; must
    # equal order-state.key-tag or validate() refuses to boot. docs/02 §5.
    key-tag: 13011

  topic-keys:
    "[sow/fix42/order_state]": [13011]
    "[sow/fix42/orders_audit]": [11]
    "[sow/fix42/execs]": [37]
    "[sow/fix42/execs_audit]": [17]
    "[sow/fix42/rejects]": [11]
    "[fix42/unrouted]": []          # not a SOW topic: no key
    "[fix42/malformed]": []

  # Pass-through routing: matched in order, first match wins. A route sends
  # the message WHOLE unless it lists tags. `apply-state` (default true) says
  # whether a route's messages feed the state machine.
  routes:
    - name: new-order
      msg-types: ["D"]
      topics: ["sow/fix42/orders_audit"]
    - name: amend
      msg-types: ["G"]
      topics: ["sow/fix42/orders_audit"]
    - name: cancel-request
      msg-types: ["F"]
      topics: ["sow/fix42/orders_audit"]
    - name: execution-report
      msg-types: ["8"]
      topics: ["sow/fix42/execs", "sow/fix42/execs_audit"]
      # a report whose key tag holds a sentinel skips the topic keyed by it:
      # 37=NONE would collapse execs onto one record, 17=0 execs_audit.
      key-guard:
        "sow/fix42/execs": { "37": ["NONE"] }
        "sow/fix42/execs_audit": { "17": ["0", "NONE"] }
    - name: cancel-reject
      msg-types: ["9"]
      topics: ["sow/fix42/rejects"]
    # Lenient mode only; strict mode raises before reaching it.
    - name: unrouted
      msg-types: ["*"]
      topics: ["fix42/unrouted"]
      apply-state: false
```

What left the yml, and why: the `projection` blocks (they *were* the state
machine), the per-`150` split of the `35=8` routes (the machine reads 150 and
20 itself; a deployment that wants different *pass-through* tags per exec type
can still split the route), and `changeable-tags` for the blotter (the machine
stages 38/44 from a G and adopts `order-term-tags` from the confirming
report). The audit topics' `mode` defaults to FULL; `tags:` on a route turns
it back into a selected delta for a deployment that wants the old wire shape.

## 7. Threading and ordering

The machine is **single-threaded per order**, and in the shipped shape
single-threaded full stop: one FIX session delivers in order, one thread calls
`OrderStatePublisher.send`, and the AMPS client's publish path buffers
asynchronously behind it. `OrderState` and the index are not synchronised. A
deployment with several sessions runs one publisher per session when orders
never cross sessions (the drop-copy norm), or partitions by OrderKey ahead of
the machine when they do — the key is derivable from the message's 11/41/37
without the cache only for the first message of a chain, so partitioning has
to be by session or by a stable upstream id, not by ClOrdID. Several sessions
onto one blotter topic also share one ClOrdID namespace; the collision rule of
[03 §3.2](03-state-machine.md#32-creation) detects, but a deployment should
not rely on it across sessions that may reuse ids.

**Acknowledging the session.** `FeedAdapter.onMessage` returns only after
`send` has `commit`ted, which is after the `HAClient` has accepted every
publish for the message into its publish store. A QuickFIX/J engine advances
its incoming sequence number when `fromApp` returns, so with `publish-store:
FILE` a message the engine has acknowledged is on disk in the store and
survives a process crash; with `MEMORY` the window between the store write
and the server's acknowledgement is the loss bound, stated in
[04 §6](04-recovery-and-operations.md#6-the-publish-path). A publish that
throws propagates out of `onMessage` before `commit`, so the cache is
unchanged and the engine does not acknowledge.

The `FailedWriteHandler` runs on the AMPS client's receive thread. It never
touches an entry: it enqueues the rejected publish's key, and the publishing
thread drains the queue before the next message, on an idle timer, and at
shutdown ([04 §6](04-recovery-and-operations.md#6-the-publish-path)). An
exception out of `onMessage` is, for QuickFIX/J, a session-level event — the
engine does not retry in place; it disconnects and the counterparty resends
on the next logon — which is the redelivery the two-phase apply relies on
(assumption about the engine, to verify in phase 4).

Ordering is assumed within a session and not across sessions. A report that
arrives before its request (possible on a drop-copy tap that merges two
sessions) is handled as a mid-stream chain or an inferred pending entry, and
the late request merges into it; the machine does not reorder.

## 8. Extension

Three tiers, in [07](07-extension-points.md): the policy knobs above, bundled
per venue as a `VenueDialect`; an `OrderStateHook` SPI called before and after
each apply, which may veto or normalise a message and add record fields in the
12100–12999 range but never touch the machine's own; and constructor-injected
components (`AuditRouter`, `RecordWriter`, `PublishSink`, `RecordSource`,
`MissLookup`, `OrderKeyIndex`, `FeedAdapter`) a deployment can replace. Hooks are stateless
by contract and fail fast, so every recovery path in
[04](04-recovery-and-operations.md) still holds with them in place.

## 9. What changes for a consumer of the old blotter

| on `sow/fix42/orders` | on `sow/fix42/order_state` |
| --- | --- |
| key: the chaining module's generated key, read with `getSowKey()`, opaque | key: tag 13011 RootClOrdID, the chain's root ClOrdID (one of the drop copy's own ids), also in the payload |
| tag 35 = last message's type | no tag 35; 12022. FIX-dictionary tooling fed the record will flag the missing 35 and the user-defined tags |
| tag 39 = venue's last status, but **absent** until the first report and **untouched** by pending acks | 39 = `A` from creation on a D-opened chain; follows every report including 6/E/A; never written by a request; a `35=9` writes it only under the policy, and a terminal value from a 9 zeroes 151 |
| 9010–9014: one in-flight request | 15038/15044/15011/15035 and 12014: the same meaning for the **newest** request, under the deployment's tag scheme; 15038/15044 describe the newest pending *amend*; 9015/9016 for the set |
| a request on a terminal order sets 9013 | the same, in 15035, and 39 stays terminal |
| duplicate or stale reports regress the record | ignored; 12025 counts them; an old status report is caught by its TransactTime |
| a fill after a cancel confirm overwrites 39/151 with the fill's | the economics land, 39/151 stay terminal, 12026 counts (default policy) |
| 60 = the last message's TransactTime | the same; 12029 is the latest *report's*, monotonic |
| 32/31 keep the last fill's values after a bust of that fill | `32=0 31=0`; 12028 says which execution they described; 12031 lists what was busted |
| 44 is the last price any message carried | 44 is the acked limit price, and `44=0` once the order became a market order (`40=1`) |
| `sow/fix42/orders_audit` holds the tags each route selected | holds the message as received plus 13011 as a trailer |
| the `{scope}` placeholder needs 9000 on every venue message | 9000 remembered from the D; a report without it still routes |
| exposure and recon views over `sow/fix42/orders` | the same four views, declared over `sow/fix42/order_state`; same fields, same filters (`/9000 IS NULL`); a `/12018 = 'MIDSTREAM'` record contributes what its reports carried |
