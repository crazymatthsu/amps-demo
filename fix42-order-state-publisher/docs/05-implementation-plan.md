# 5. Implementation plan

Six phases, each leaving the build green and each with a test that proves its
own claim. Phases 0–2 need no AMPS instance; 3–5 start containers the way
`fix42-publisher`'s suite does and skip without `AMPS_IMAGE`. Nothing here is
written yet; this is the order to write it in.

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
  new 9015–9025 constants, and nothing else changes in the copy;
- a `ScriptedFlowPinTest` recording a hash of `MockFixFlow`'s rendered
  messages as a literal, taken from `fix42-publisher`'s flow at copy time.
  This is what keeps the two mock venues producing the **same scripted flow**
  without a dependency between them, so the end state of the ordinary ten
  chains can be compared across the two blotters by eye or by script;
- `fix42-publisher` is not touched.

**Exit criteria.** `./gradlew :fix42-order-state-publisher:build` green on its
own; `./gradlew build` green; the pin test passes; `fix42-publisher`'s suites
unchanged in count.

## Phase 1 — Scenarios the stateless publisher cannot pass

**Why before the machine.** The scenarios are the specification. Writing them
as mock-venue scripts first fixes the exact message sequences the machine is
tested against, and they double as the demo the runner publishes.

**Deliverables**

- `OrderChain` learns to hold **more than one request in flight**: a second
  `cancelRequest()` while one is outstanding; `cancelConfirmed(requestClOrdId)`
  and `cancelReject(requestClOrdId, …)` naming which request they answer;
  `amendReject` likewise. The existing single-request methods keep their
  behaviour so this module's `MockFixFlow` still renders the same messages as
  `fix42-publisher`'s (the phase 0 pin test keeps passing);
- new venue behaviours: `resendLastReport()` (PossDup duplicate),
  `redeliver(reportOrdinal)` (stale), `cancelRequest()` after close-out (late
  request on a terminal order), `withoutNewOrder()` (drop the D from the
  emitted events for a mid-stream start), `lateNewOrder()` (emit it after the
  first report);
- `EdgeCaseFlow` in the new module: one chain per walkthrough in
  [03 §7](03-state-machine.md#7-walkthroughs) — two cancels confirm-then-reject,
  reject-then-confirm, third cancel after terminal, amend+cancel in flight,
  duplicate and stale reports, report-before-D, reject-before-ack, bust after
  fill — plus the ten `MockFixFlow` chains, so the end state of the ordinary
  flow is asserted on the new blotter too.

**Exit criteria.** `FillArithmeticTest`'s invariants (`38 = 14 + 151`, AvgPx
consistent with the fills) hold for every new chain; the scripted-flow pin
still passes.

## Phase 2 — The state machine, with no server

**Deliverables** (package `com.demo.amps.fix42.state`)

- `OrderKeyIndex` — the two maps and the resolution order of
  [03 §3](03-state-machine.md#3-identity); tombstones;
- `OrderState` — the entry of [02 §3.1](02-architecture.md#31-one-entry-per-order),
  with a dirty bitmask per record field;
- `PendingRequest` and the small set that holds them (an array list; two
  elements is the common upper bound);
- `OrderStateMachine.apply(FixFrame) → Outcome` — the rules of
  [03 §4–6](03-state-machine.md#4-status-venue-truth-and-one-policy-decision);
  `cancel-reject-status`, `exec-id-history`, `stale-guard` as constructor
  options;
- `RecordWriter` — `OrderState` → FIX bytes, whole or dirty-only, into a
  `FixWriter`; the `9015` encoding and its parser (needed again by the
  rebuild in phase 4).

**Tests**

- one test class per message type with one test per rule row in
  [03 §6](03-state-machine.md#6-transition-rules);
- one test per walkthrough in [03 §7](03-state-machine.md#7-walkthroughs),
  asserting the record **after every message** as the tables state it — these
  are the module's specification and should read like it;
- a round-trip test: every state the machine can reach, written whole by
  `RecordWriter` and parsed back, rebuilds an equal `OrderState` (this is
  what makes the SOW rebuild of phase 4 trustworthy before it exists);
- a property-style test over `EdgeCaseFlow`: for every chain, applying its
  events in order ends with 14/151/6/39 equal to the `OrderChain`'s own final
  state, which the mock derives from its fills.

**Exit criteria.** All of [03](03-state-machine.md) is executable. No AMPS
dependency in the package.

## Phase 3 — Server flow, publisher, application

**Deliverables**

- `server/config/flows/fix42-order-state/amps-config.xml`: the topics and
  views of [02 §1](02-architecture.md#1-topology); no `<Modules>`; the
  three audit topics journalled; `./server/scripts/amps.sh validate` passes
  and `:server:checkConfigXml` is green;
- `AmpsFlow.FIX42_ORDER_STATE` in `amps-test-harness`;
- `OrderStateProperties` (+ `validate()`), `AuditRouter`,
  `OrderStatePublisher`, `AmpsClientConfiguration`,
  `Fix42OrderStatePublisherApplication`, `MockFlowRunner`;
  `application.yml` as in [02 §6](02-architecture.md#6-configuration);
- the integration-test plumbing in `build.gradle.kts` from phase 0 put to
  use: the `integrationTest` task's environment-variable inputs (the
  build-cache trap documented in `fix42-publisher` applies unchanged).

**Integration tests** (each starts its own instance, as the existing suites do)

- `TwoCancelsIT`: publish [03 §7.1](03-state-machine.md#71-two-cancels-confirm-then-reject-the-motivating-case)
  and §7.2, read `sow/fix42/order_state` back, assert `39=4 151=0 9013=NONE
  9016=0` for both orders. The headline test;
- `OrderStateEndStateIT`: the ten scripted chains plus every edge-case chain;
  one record per order; venue-sourced fields equal each `OrderChain`'s final
  state; the merged record still carries the D's terms; `sow/fix42/orders_audit`
  holds one whole message per ClOrdID;
- `PendingStateIT` counterpart: stop mid-amend and mid-cancel; both truths on
  the record, as today;
- `ExposureViewIT` counterpart: the four views over the new topic agree with
  the old suite's expected numbers for the scripted flow, and go to zero on
  the terminal reports.

**Exit criteria.** `AMPS_IMAGE=… ./gradlew :fix42-order-state-publisher:integrationTest`
green with zero skipped; `bootRun` against the flow shows the two-cancels
order as canceled in the admin console.

## Phase 4 — Recovery, cache misses, eviction

**Deliverables**

- `SowCacheLoader` and `recovery: SOW | NONE`; the `9015` parser from phase 2
  closes the loop;
- the `MIDSTREAM` creation path's publish fallback, decided by the verify-on-build
  result for a `delta_publish` with no record;
- `terminal-retention` with tombstones ([04 §4](04-recovery-and-operations.md#4-eviction));
- the counters of [04 §6](04-recovery-and-operations.md#6-observability).

**Integration tests**

- `RecoveryIT`: publish the first half of every chain, close the publisher,
  start a new one against the same instance with `recovery: SOW`, publish the
  second half; every record equals the uninterrupted run's (phase 3's end
  state, read from a second instance in the same test or pinned as fixtures);
- `CacheMissIT`: `recovery: NONE`, publish the second half only; records are
  `9018=MIDSTREAM` and carry the venue's absolutes; then a late D produces
  `D_LATE` and the terms, with 38/44/39 untouched;
- `EvictionIT`: `terminal-retention` of a second; a bust after eviction lands
  on the original record via the tombstone; a late request under a new
  ClOrdID produces the documented second record and the `midstreamChains`
  count.

**Exit criteria.** A publisher restart mid-flow is invisible in the end state.

## Phase 5 — Measure, harden, document

**Deliverables**

- an allocation test in the style of `PublishPathAllocationTest`: not zero
  (the cache's strings make that a non-goal) but **bounded and stated** —
  measure bytes per message over the edge-case flow, pin a ceiling, and record
  the number in the module README beside `fix42-publisher`'s;
- a benchmark variant alongside `PublishThroughputBenchmark`: the same load
  through both publishers on one machine, so the cost of the cache is a
  number rather than an argument;
- the module `README.md` proper (replacing the stub), a row in the root
  README's module table, and a pointer from
  [docs/fix42-view/README.md](../../docs/fix42-view/README.md);
- optional, if phase 4 showed the need: `miss-lookup` with the server-side
  index, and `recovery: JOURNAL`.

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
    MockFlowRunner.java
    fix/      FixFrame FixWriter FixMessage FixTags Prices         copied, phase 0
    mock/     OrderChain MockFixFlow Instrument FixEvent OrderScope copied, phase 0; EdgeCaseFlow, phase 1
    config/   OrderStateProperties  AmpsClientConfiguration  CancelRejectStatusPolicy
    machine/  OrderKeyIndex  OrderState  PendingRequest  OrderStateMachine  Outcome  RecordWriter
    publish/  AuditRouter  OrderStatePublisher
    recovery/ SowCacheLoader  (JournalReplayer, later)
  src/test/java/…/fix/, …/mock/                  copied unit tests + ScriptedFlowPinTest, phase 0
  src/test/java/…/machine/                       phase 2: per-rule and per-walkthrough tests, round-trip
  src/test/java/…/config/                        properties validation
  src/test/java/…/it/OrderStateConfigurations    binds the shipped application.yml, as Fix42Configurations does
  src/integrationTest/java/…/it/                 TwoCancelsIT OrderStateEndStateIT PendingStateIT
                                                 ExposureViewIT RecoveryIT CacheMissIT EvictionIT SowReader

server/config/flows/fix42-order-state/amps-config.xml   phase 3
amps-test-harness/…/AmpsFlow.java                        + FIX42_ORDER_STATE
```

## Decisions still open

Each with the recommendation the documents assume. None blocks phase 0–2.

| decision | options | recommended | why |
| --- | --- | --- | --- |
| how the new module gets the FIX layer | copy / shared module / depend on `fix42-publisher` | **copy** (decided) | the two publishers stay independent, each testable and demoable alone and extractable as a standalone project; the pin test keeps the two mock flows identical |
| audit topics receive | the whole message / route-selected tags | **whole** | an audit trail, and the replay source for `recovery: JOURNAL`; `tags:` on a route restores the old shape |
| `35=9`'s tag 39 | `WHEN_NOT_TERMINAL` / `NEVER` / `ALWAYS` | **`WHEN_NOT_TERMINAL`** | [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision); it is a per-venue fact, so it is a knob |
| duplicate detection | last ExecID + ring / unbounded set | **ring, depth 8** | the cache stays small; the stale guard covers the rest |
| key tag | new 9020 / reuse 9014 | **9020** | 9014 means "working id" and rotates; a key must not |
| keep `sow/fix42/execs` (latest report per OrderID) | keep / drop | **keep** | existing tooling reads it; it costs one route line |
| eviction default | never / N minutes | **never** | daily restart with `recovery: SOW` bounds the day; eviction is for the flow that does not fit |
| 39 at creation from a D | `A` / absent | **`A`** | the contract's rule 1, and a record whose status is never empty |
| record carries tag 35 | yes / no | **no** | `35=9` on the record was half the bug; 9022 says the same thing honestly |
| threading | single / partitioned | **single** | one session, one thread; partitioning is the deployer's, keyed by session |

## Risks

| risk | mitigation |
| --- | --- |
| `delta_publish` with no existing record behaves differently on this build | phase 3 verifies on the container before phase 4 relies on it; the `publish` fallback for `CREATED(MIDSTREAM)` is one branch |
| a bookmark subscription across three topics does not preserve journal order | `recovery: JOURNAL` is optional and last; the SOW rebuild does not need it |
| the cache's allocations move the tail latency the old publisher was tuned for | phase 5 measures; ids can be canonicalised through the index (the key strings already live there) before anything else is tried |
| the mock venue's new multi-request methods disturb the scripted flow | the pin test from phase 0 |
| the two copies of the FIX layer and the mock venue drift apart | by design they are allowed to: the new module owns its copy. The pin test guards the one thing that must stay equal, the scripted flow; a fix found in one `FixFrame` is ported to the other by hand, and the two unit-test suites are the check |
| a venue dialect's `35=9` semantics differ from all three policies | the policy is an enum behind one switch; a fourth value is a small change, and the record's 9025/9016 make the symptom visible first |
| `OrderChain`'s single-request model resists a multi-request extension | model the outstanding requests as a list from the start of phase 1 and derive `currentClOrdId` from it; the existing methods become the one-element case |

## Follow-ups, deliberately out of scope

The cache makes each of these additive; none is needed for the two cancels:

- the contract's §6 outputs: `executions` (per-ExecID, with `FillStatus`
  re-emitted on a bust/correct) and `order_events`;
- `35=Q` DontKnowTrade;
- configurable staged tags on a G beyond 38/44 (MinQty, TIF);
- `miss-lookup` and `recovery: JOURNAL`, unless phase 4 shows the need;
- running both publishers on one feed against two instances and diffing the
  venue-sourced fields of `sow/fix42/orders` against `sow/fix42/order_state`
  — the continuous audit [docs/fix42-view/03 §5(a)](../../docs/fix42-view/03-proposed-architecture.md#5-where-the-chaining-key-generator-fits)
  describes, and a cheap way to catch a regression in either.
