# 5. Implementation plan

Six phases, each leaving the build green and each with a test that proves its
own claim. Phases 0–2 need no AMPS instance; 3–5 start containers the way
`fix42-publisher`'s suite does and skip without `AMPS_IMAGE`. Nothing here is
written yet; this is the order to write it in. The plan was revised after the
round-1 review ([08 §5](08-review-findings.md#5-gaps-in-the-implementation-plan));
the additions are marked.

---

## Phase 0 — Stand the module up, with its own copy of the FIX layer and mock venue

**Why first.** The new module needs `FixFrame`, `FixWriter`, `FixMessage`,
`FixTags`, `Prices`, and the mock venue (`OrderChain`, `MockFixFlow`,
`Instrument`, `FixEvent`, `OrderScope`). It gets them as a **copy**, not a
dependency: the two publishers are to stay independent of each other, so that
each builds, tests and demos alone and either can later be extracted from this
repository as a standalone project. A shared module was considered and
rejected for that reason; so was depending on `fix42-publisher`'s jar, which
would drag a second Spring Boot application onto the classpath.

**Deliverables**

- the Gradle module `fix42-order-state-publisher`, included in
  `settings.gradle.kts`, with `build.gradle.kts` copied from `fix42-publisher`
  (same `integrationTest` source set, same environment-variable inputs for the
  build cache, `bootJar` off); the version catalog needs nothing new;
- `fix42-publisher`'s `fix/` and `mock/` packages copied to
  `com.demo.amps.fix42.state.fix` and `com.demo.amps.fix42.state.mock`, with
  their unit tests (`FixFrameTest`, `FixMessageTest`, `FixWriterTest`,
  `PricesTest`, `FillArithmeticTest`, `MockFixFlowTest`); `FixTags` gains the
  constants for 13011, the 15011/15035/15038/15044 pending family and 12014–12033, and nothing else changes in the copy;
- a `ScriptedFlowPinTest` recording a hash of `MockFixFlow`'s rendered
  messages as a literal, taken from `fix42-publisher`'s flow at copy time.
  This is what keeps the two mock venues producing the **same scripted flow**
  without a dependency between them, so the end state of the ordinary ten
  chains can be compared across the two blotters;
- `fix42-publisher` is not touched.

**Exit criteria.** `./gradlew :fix42-order-state-publisher:build` green on its
own; `./gradlew build` green; the pin test passes; `fix42-publisher`'s suites
unchanged in count.

## Phase 1 — Scenarios the stateless publisher cannot pass

**Why before the machine.** The scenarios are the specification. Writing them
as mock-venue scripts first fixes the exact message sequences the machine is
tested against, and they double as the demo the runner publishes. The review
showed the venue's *dialect* is part of the specification, so the mock venue
has to be able to speak more than one.

**Deliverables**

- `OrderChain` holds **more than one request in flight**: outstanding
  requests are a list from the start, `currentClOrdId` is derived from it,
  and the existing single-request methods are the one-element case so the
  scripted flow renders byte-identically (the pin test keeps passing). New:
  a second `cancelRequest()` while one is outstanding; `cancelConfirmed(id)`,
  `cancelReject(id, …)` and `amendReject(id, …)` naming the request they
  answer;
- **venue-dialect switches** on `OrderChain` *(added)*: `fillsEchoPendingId`
  (on by default — it is what the chain already does, and what the FIX
  matrices prescribe), `confirmsEchoOriginalId` (`150=4`/`150=5` carry the
  original ClOrdID), `pendingAcksEchoWorkingId` (`150=6`/`E` carry the
  working id), `replaceAsCancelThenNew`, `rejectReplaceWithExecReport`
  (`150=8` with `39=8` or `39=1`), `rejectStatusAtRequestTime` (a 9 carries
  `39=6`, or a given 39), `statusReportsWithoutExecId` (`20=3` with `17=0` or
  none), `rejectWithoutOrderId` (`150=8` with `37=NONE`),
  `transactTimeInSeconds` (`60` without milliseconds), `orderIdEqualsClOrdId`;
  and the guards the chain has today become switches: `bustAfterCloseOut`
  (a bust or correct after a cancel), `restateWhilePending`, so the
  walkthroughs of [03 §7](03-state-machine.md#7-walkthroughs) are all
  expressible (round-2 sweep item 14);
- new venue behaviours: `resendLastReport()` (PossDup duplicate),
  `redeliver(reportOrdinal)` (stale), `redeliverStatus(reportOrdinal)` (an
  old ack or `20=3` with equal CumQty), `lateFill(shares, px)` after a
  close-out, `cancelRequest()` after close-out, `withoutNewOrder()`,
  `lateNewOrder()`, `pendingAckBeforeRequest()`, `newOrderLinkedTo(id)` (a D
  carrying 41), `amendNamingAnotherOrder(id)` (a G whose 37 and 41 disagree),
  `amendToMarket()` (`40=1`, no 44, `110=0`), `redeliverBust(ordinal)`, ids
  containing `:`, `;`, `,` and `%`, a D carrying tags in 12000–12999, 13xxx or 15xxx and FIX
  header tags, `thousandAmends()`, and a second chain whose OrderID equals the
  first chain's root ClOrdID;
- `EdgeCaseFlow` in the new module: one chain per walkthrough in
  [03 §7](03-state-machine.md#7-walkthroughs) and one per row of
  [06](06-case-coverage.md) that a mock venue can express, plus the ten
  `MockFixFlow` chains, so the end state of the ordinary flow is asserted on
  the new blotter too.

**Exit criteria.** `FillArithmeticTest`'s invariants (`38 = 14 + 151`, AvgPx
consistent with the fills) hold for every new chain under every dialect; the
scripted-flow pin still passes.

## Phase 2 — The state machine, with no server

**Deliverables** (package `com.demo.amps.fix42.state`)

- `OrderKeyIndex` — **two maps** (ClOrdID → key, OrderID → key), the per-type
  resolution order of [03 §3](03-state-machine.md#3-identity) consulting the
  right map at each step, no-rebind with `idConflicts`, answered ids,
  tombstones (every 37), key-collision detection at mint time against the set
  of keys (decision 2, S1);
- `OrderState` — the entry of [02 §3.1](02-architecture.md#31-one-entry-per-order),
  quantities as numbers, a dirty bitmask per record field, `dirtyAll`, the
  busted set (12031), the kept creation payload;
- `PendingRequest` and the small set that holds them, with the
  requested/inferred flag;
- `OrderStateMachine.plan(FixFrame) → Outcome` and `commit()` *(two-phase,
  added)* — the rules of [03 §4–6](03-state-machine.md#4-status-venue-truth-and-one-policy-decision);
  `cancel-reject-status`, `late-fill-after-terminal`,
  `non-fill-after-terminal`, `fill-under-pending-id`, `stale-guard`,
  `transact-time-guard`, `exec-id-history`, `order-term-tags`, `strip-tags`,
  `chain-ids-published` as constructor options (tier one of
  [07](07-extension-points.md));
- `RecordWriter` — `OrderState` → FIX bytes, whole or dirty-only, into a
  `FixWriter`; never an empty tag value; the 12015, 12021 and 12031 encodings
  with `%`-escaping and empty sub-fields for "no proposal", and their parsers
  (needed again by the rebuild in phase 4);
- the repair queue: a single-consumer queue of keys the `FailedWriteHandler`
  fills and the publishing thread drains (S2).

**Tests**

- one test class per message type with one test per rule row in
  [03 §6](03-state-machine.md#6-transition-rules);
- one test per walkthrough in [03 §7](03-state-machine.md#7-walkthroughs),
  asserting the record **after every message** as the tables state it;
- one test per row of the coverage matrix in [06](06-case-coverage.md), named
  by its row id (`N14`, `E20`, …), each **policy** row once per policy value;
- a round-trip test: every state the machine can reach, written whole by
  `RecordWriter` and parsed back, rebuilds an `OrderState` equal modulo the
  ExecID ring; with hostile ids (`:`, `;`, `,`, `%`) in 12015 and 12021;
- numeric comparison of text quantities (`300` vs `300.0` vs `0300`);
- `plan` leaves the entry untouched until `commit`; a second `plan` on the
  same message without a `commit` returns the same outcome;
- a repair after a rejected delta is a delta of every cached field and never
  a `publish`; a repair after a rejected creation re-sends the kept payload
  (S2); the typed index resolves `8 … 37=123456` to nothing when `123456` is
  another order's root ClOrdID (S1);
- the `publish-unchanged` payload shape (12025, and 12021/12030 when an id was
  bound; never 12023 or 60);
- a property-style test over `EdgeCaseFlow`: for every chain under every
  dialect, applying its events in order ends with 14/151/6/39 equal to the
  `OrderChain`'s own final state.

**Exit criteria.** All of [03](03-state-machine.md) is executable. No AMPS
dependency in the package.

## Phase 3 — Server flow, publisher, application

**Deliverables**

- `server/config/flows/fix42-order-state/amps-config.xml`: the topics and
  views of [02 §1](02-architecture.md#1-topology); no `<Modules>`; **every
  topic the publisher writes** journalled — `sow/fix42/order_state`,
  `sow/fix42/execs`, the three audit topics and the two catch-all topics
  *(changed: decision 4, and S3 for `execs`)*; `./server/scripts/amps.sh
  validate` passes and `:server:checkConfigXml` is green;
- `AmpsFlow.FIX42_ORDER_STATE` in `amps-test-harness`;
- `AmpsClientConfiguration`: an `HAClient` with `publish-store: FILE | MEMORY
  | NONE`, a `FailedWriteHandler` that counts `publishFailures` and enqueues
  repairs, `NameInUse` retry *(added, decision 4; the repair shape from S2)*;
- `OrderStateProperties` (+ `validate()`, including `apply-state`, the
  `mode`, `audit.key-tag = order-state.key-tag`, and the refusal of
  `recovery: NONE` against a non-empty blotter), `AuditRouter` (whole message
  plus 13011 as a trailer when resolved, the **per-topic** key check that
  skips one topic rather than the message, the `key-guard` lists, the
  lenient-mode catch-all) *(changed, decisions 3 and 5, S10)*,
  `OrderStatePublisher` (plan → blotter publish → audit publishes → commit;
  the repair queue drained before each message, on an idle timer and at
  shutdown),
  `FeedAdapter` with `MockFlowRunner` implementing it,
  `Fix42OrderStatePublisherApplication`; `application.yml` as in
  [02 §6](02-architecture.md#6-configuration);
- `VenueDialect` binding, the `OrderStateHook` SPI with Spring collecting
  every hook bean in `@Order`, fail-fast semantics, `RecordFields` refusing
  tags below 12100, the audit topics receiving the original frame, and one
  shipped example hook (a derived desk status in 12100) with its test
  ([07 §2](07-extension-points.md#2-tier-two-hooks)).

**Integration tests** (each starts its own instance, as the existing suites do)

- `TwoCancelsIT`: publish [03 §7.1](03-state-machine.md#71-two-cancels-confirm-then-reject-the-motivating-case)
  and §7.2, read `sow/fix42/order_state` back, assert `39=4 151=0 15035=NONE
  12016=0` for both orders. The headline test;
- `OrderStateEndStateIT`: the ten scripted chains plus every edge-case chain
  under the default dialect; one record per order; venue-sourced fields equal
  each `OrderChain`'s final state; the merged record still carries the D's
  terms; `sow/fix42/orders_audit` holds one whole message per ClOrdID, each
  with 13011; the blotter writer always carries 13011;
- `DialectIT` *(added)*: the edge-case chains under each venue-dialect
  switch, with the matching policy knob;
- `PendingStateIT` counterpart: stop mid-amend and mid-cancel; both truths on
  the record;
- `ExposureViewIT` counterpart: the four views over the new topic agree with
  the old suite's expected numbers for the scripted flow, go to zero on the
  terminal reports, and include a `MIDSTREAM` record's contribution;
- `PublishPathIT` *(added)*: a payload lacking its key, published through the
  **raw client** (the router would divert it), is refused by AMPS and the
  `FailedWriteHandler` fires, and the repair that follows is a delta of every
  cached field that leaves the D's terms intact (S2); a 17-less `20=3` goes
  through the router to the blotter and to `fix42/unrouted`, not to
  `execs_audit` (S10); `publishStoreDepth` returns to zero after a quiet flush
  with every route live, which is the test that every published topic is
  journalled (S3); AMPS is restarted mid-flow and the end state is unchanged;
  a second client with the same name gets `NameInUse`; a record without tag
  35 is stored; an empty value in a delta is rejected or ignored (whichever
  the build does, pinned); `delta_publish` to a key with no record is stored
  as a publish, or the fallback engages; `sow_delete` by filter removes
  records durably and a deleted SOW file of a journalled topic does or does
  not come back at restart (pinned, S13).

**Exit criteria.** `AMPS_IMAGE=… ./gradlew :fix42-order-state-publisher:integrationTest`
green with zero skipped; `bootRun` against the flow shows the two-cancels
order as canceled in the admin console.

## Phase 4 — Recovery, cache misses, eviction, the feed

**Deliverables**

- `SowCacheLoader`: `recovery: SOW | NONE`, `load-open-orders-only` with
  tombstones for every record, 12024 handling, `UNRECOVERED`;
- `MissResolver`: the lookups of [04 §3](04-recovery-and-operations.md#3-cache-misses),
  default on; the D keyed check when the cache is incomplete *(added, R4)*;
- `terminal-retention` with tombstones holding every 37, and recreation of
  an evicted entry through the `MissResolver` path, never from the report
  ([04 §4](04-recovery-and-operations.md#4-eviction), S9);
- a QuickFIX/J `FeedAdapter` *(added)*: `fromApp` → `send` → `commit` → return,
  so the engine's sequence-number persistence follows the publish store
  write; the loss bound of [04 §6](04-recovery-and-operations.md#6-the-publish-path)
  measured and stated;
- the counters of [04 §7](04-recovery-and-operations.md#7-observability);
- the daily reset procedure of [04 §9](04-recovery-and-operations.md#9-the-daily-reset)
  as a script.

**Integration tests**

- `RecoveryIT`: publish the first half of every chain, close the publisher,
  start a new one against the same instance with `recovery: SOW`, publish the
  second half; every record equals the uninterrupted run's;
- `CacheMissIT`: `recovery: NONE` with `allow-incomplete-cache`, publish the
  second half only: a D-opened order's report resolves through `orders_audit`
  to its existing record and **no second record appears**; the view totals
  are unchanged; a report naming no known id produces the documented
  `MIDSTREAM` record; a resent D does not destroy the record *(changed, R4)*;
- `PartialLoadIT` *(added)*: `load-open-orders-only`, then a late bust for a
  terminal order lands on its record via the tombstone;
- `EvictionIT`: `terminal-retention` of a second; a bust after eviction lands
  on the original record **and leaves 12021/12023 intact** (the entry came back
  from the record, not from the bust); a late request under a new ClOrdID
  resolves through the audit lookup;
- `ResetIT` *(added)*: the §9 reset keeps `/12017 = 'N'` records; a next-day
  bust for a removed order becomes a `MIDSTREAM` record under its old key and
  counts `missLookupOrphans` (S8);
- `SchemaIT` *(added)*: records missing 12026, records with an unknown 12024;
- `StoreCrashIT` *(added)*: kill the publisher between the store write and the
  acknowledgement with `FILE`; restart; nothing missing. With `MEMORY`, the
  measured loss equals the window;
- `ReplayDeterminismIT` *(added, if `recovery: JOURNAL` is built)*: the same
  journal through a fresh machine, with and without a hook installed,
  reproduces the records.

**Exit criteria.** A publisher restart mid-flow is invisible in the end state;
a `recovery: NONE` restart creates no second record for any D-opened order.

## Phase 5 — Measure, harden, document

**Deliverables**

- an allocation test in the style of `PublishPathAllocationTest`: not zero
  (the cache's strings make that a non-goal) but **bounded and stated** —
  bytes per message over the edge-case flow including the hooks' views and
  the 12015/12021 string building, a pinned ceiling, the number in the README
  beside `fix42-publisher`'s;
- a benchmark variant alongside `PublishThroughputBenchmark`: the same load
  through both publishers on one machine, and through this one with each
  `publish-store` value, so the cost of the cache and of the store are
  numbers rather than arguments;
- memory for a thousand-ClOrdID chain measured;
- the module `README.md` proper (replacing the stub), a row in the root
  README's module table, and a pointer from
  [docs/fix42-view/README.md](../../docs/fix42-view/README.md);
- optional: `miss-lookup-by-order-id` with the server-side index, and
  `recovery: JOURNAL`.

**Exit criteria.** The README says what it costs and what it closes, with
numbers from a run.

## Class layout

Self-contained: nothing under `fix42-order-state-publisher/` imports from
`fix42-publisher`, and the only in-repository dependency is the test-scope
`amps-test-harness`, which every container-backed suite here uses.

```
fix42-order-state-publisher/
  build.gradle.kts                              phase 0
  README.md
  docs/                                         these notes
  src/main/resources/application.yml            02 §6
  src/main/java/com/demo/amps/fix42/state/
    Fix42OrderStatePublisherApplication.java
    MockFlowRunner.java                         implements FeedAdapter
    fix/      FixFrame FixWriter FixMessage FixTags Prices         copied, phase 0
    mock/     OrderChain MockFixFlow Instrument FixEvent OrderScope copied, phase 0; dialect switches, EdgeCaseFlow, phase 1
    config/   OrderStateProperties  AmpsClientConfiguration  VenueDialect  Mode
              CancelRejectStatusPolicy  LateFillPolicy  NonFillAfterTerminalPolicy  FillUnderPendingIdPolicy
    machine/  OrderKeyIndex  OrderState  PendingRequest  OrderStateMachine  Outcome  RecordWriter
    spi/      OrderStateHook  OrderStateView  IndexView  Decision  RecordFields  IgnoreReason  FeedAdapter
    hooks/    DeskStatusHook (the shipped example)  RejectStatusAtRequestTimeHook (docs/11 F2)
    publish/  AuditRouter  OrderStatePublisher
    recovery/ SowCacheLoader  MissResolver  (JournalReplayer, later)
    feed/     QuickFixJFeedAdapter                                 phase 4
  src/test/java/…/fix/, …/mock/                  copied unit tests + ScriptedFlowPinTest, phase 0
  src/test/java/…/machine/                       phase 2: per-rule, per-walkthrough, per-matrix-row, round-trip
  src/test/java/…/config/                        properties validation
  src/test/java/…/it/OrderStateConfigurations    binds the shipped application.yml, as Fix42Configurations does
  src/integrationTest/java/…/it/                 TwoCancelsIT OrderStateEndStateIT DialectIT PendingStateIT
                                                 ExposureViewIT PublishPathIT RecoveryIT CacheMissIT
                                                 PartialLoadIT EvictionIT SchemaIT StoreCrashIT SowReader

server/config/flows/fix42-order-state/amps-config.xml   phase 3
amps-test-harness/…/AmpsFlow.java                        + FIX42_ORDER_STATE
```

## Decisions

Decided, with where each landed. The six put to the design owner after the
review are marked (decision n); the rest were settled in the documents.

| decision | choice | why / where |
| --- | --- | --- |
| how the new module gets the FIX layer | **copy**; no shared module, no dependency | each publisher stands alone and can be extracted; the pin test keeps the two mock flows identical |
| audit topics receive | **the whole message plus 13011** as a trailer (decision 3) | an audit trail filed under its key: one query per order's history, cheap cache-miss lookups, a cap on 12021 — [02 §5](02-architecture.md#5-publish-modes) |
| `35=9`'s tag 39 | **`WHEN_NOT_TERMINAL`**, excluding `8` and stale pending statuses; a terminal value zeroes 151 | [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision) |
| a fill after a terminal report | **`APPLY_ECONOMICS`** | a trade is money; the status is the venue's to reopen — [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision) |
| a non-fill report after a terminal report | **`ADOPT_VENUE_IF_LATER`**, TransactTime as the tiebreak (decision 1): newer by a whole second when either timestamp lacks milliseconds, or answering a pending request; never an IOC/FOK that answers nothing | a venue saying the order is working is believed when its statement is newer or answers this side's request — [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision), S4 |
| a fill carrying a pending amend's id | **`IGNORE`**: not an answer | the FIX matrices' shape; `ANSWERS_REPLACE_IF_TERMS_MATCH` for a venue that never sends `150=5` — [03 §6.4](03-state-machine.md#64-358-executionreport) |
| which report answers which request | **by ExecType**, matched on tag 11; a NEW by any non-pending-ack report | [03 §1](03-state-machine.md#1-vocabulary), R1 |
| duplicate detection | ring, depth 8, plus the CumQty stale guard and the TransactTime late guard | the cache stays small; status-only resends are caught by time — [03 §5](03-state-machine.md#5-arbitration-on-execution-reports) |
| key tag and format | **13011, one of the drop copy's own ids, unprefixed** (decision 2); a **typed index** (ClOrdIDs and OrderIDs in separate maps) so an OrderID equal to another order's ClOrdID cannot resolve to it; collisions detected at mint time; 12019 says the kind | [03 §3.1–3.2](03-state-machine.md#31-resolution), S1 |
| AMPS client | **`HAClient` with a publish store** (decision 4), `FILE` in production, `MEMORY` for demo and tests; `FailedWriteHandler`; two-phase plan/commit; the blotter topic journalled | [04 §6](04-recovery-and-operations.md#6-the-publish-path) |
| unrouted and unparseable messages | **a mode** (decision 5): `strict` for tests, `lenient` for production with `fix42/unrouted` and `fix42/malformed` | [03 §9](03-state-machine.md#9-unrouted-and-unparseable-messages) |
| 32/31 after a bust of the last fill | **zeroed**, with 12028 naming the fill (decision 6) | [03 §6.4](03-state-machine.md#64-358-executionreport) |
| custom logic | hooks SPI, stateless, fail-fast, own tag range 12100–12999 | [07](07-extension-points.md) |
| cache misses | keyed lookups through the audit topics, on by default | [04 §3](04-recovery-and-operations.md#3-cache-misses) |
| keep `sow/fix42/execs` | keep, guarded against `37=NONE` | existing tooling reads it |
| eviction default | never; tombstones hold every 37 | [04 §4](04-recovery-and-operations.md#4-eviction) |
| 39 at creation from a D | `A`; absent on a chain created from a request | the contract's rule 1; a request's terms are proposals |
| record carries tag 35 | no; 12022 | `35=9` on the record was half the bug |
| threading | single; the adapter acknowledges after `commit` | [02 §7](02-architecture.md#7-threading-and-ordering) |

Still open, none blocking: the default `order-term-tags` list; the 12021 cap
(50); whether `miss-lookup-by-order-id` and its server-side index are worth
building after phase 4's measurements.

## Risks

| risk | mitigation |
| --- | --- |
| `delta_publish` with no existing record behaves differently on this build | `PublishPathIT` pins the behaviour before phase 4 relies on it; the `publish` fallback for `CREATED(MIDSTREAM)` is one branch, and the `FailedWriteHandler` would report a rejection |
| a bookmark subscription across three topics does not preserve journal order | `recovery: JOURNAL` is optional and last; the SOW rebuild does not need it |
| the publish store's per-publish cost moves the tail latency | phase 5 measures each store type against the 14k msg/s baseline; `MEMORY` is a configuration change |
| the cache's allocations move the tail latency the old publisher was tuned for | phase 5 measures; ids can be canonicalised through the index before anything else is tried |
| TransactTime is unreliable on some venue (coarse, or not monotonic within a session) | second-resolution `60` is handled by the whole-second rule and the answers-a-request rule; a tap that merges clocks runs `non-fill-after-terminal: IGNORE` and `transact-time-guard: false` ([07 §1](07-extension-points.md#1-tier-one-policies) states the one-clock precondition) |
| AMPS acknowledges unjournalled publishes on this build after all | the `publishStoreDepth` assertion in `PublishPathIT` tells either way; the journal entries stay useful for replay, and the rule in 04 §6 becomes a recommendation |
| the mock venue's dialect switches disturb the scripted flow | the pin test from phase 0; the default dialect is the one the chain already spoke |
| the two copies of the FIX layer and the mock venue drift apart | by design they are allowed to; the pin test guards the scripted flow; a fix found in one `FixFrame` is ported by hand |
| a venue dialect matches none of the policies | the policy enums are behind one switch each, and a `beforeApply` hook can normalise the message; 12025/12016/counters make the symptom visible first |
| a venue uses tags in 12000–12999, 13xxx or 15xxx or 13011 for its own purposes | stripped from the D's FULL publish and counted; the audit key tag is configurable and skipped when present |

## Follow-ups, deliberately out of scope

The cache makes each of these additive; none is needed for the two cancels:

- a request rejected by `35=j` or `35=3`: a small MsgSeqNum (34) → ClOrdID
  ring over outgoing requests, so the reject's `45 RefSeqNum` finds the
  pending entry (06 E19);
- the contract's §6 outputs: `executions` (per-ExecID, with `FillStatus`
  re-emitted on a bust/correct) and `order_events`;
- `35=Q` DontKnowTrade, replayable from `fix42/unrouted` once built;
- `recovery: JOURNAL`, and `miss-lookup-by-order-id` with its index;
- running both publishers on one feed against two instances and diffing the
  venue-sourced fields of `sow/fix42/orders` against `sow/fix42/order_state`
  — the continuous audit [docs/fix42-view/03 §5(a)](../../docs/fix42-view/03-proposed-architecture.md#5-where-the-chaining-key-generator-fits)
  describes, and a cheap way to catch a regression in either.
