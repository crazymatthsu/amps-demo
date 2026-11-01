# 11. Build findings

What building the design found that reading it did not: places where the
rules of [03](03-state-machine.md), executed, do something the documents
did not say, and the answers to the **verify on build** questions as the
integration suites pin them. Each finding names the test that pins it, so
a change of mind is a change to one test, made on purpose.

A finding is not a bug report against the code. The code does what the
documents say; a finding is where what they say has a consequence they did
not state.

---

## 1. The state machine (phase 2)

### F1 — The late guard runs before the after-terminal table, and shadows two of its rows

[03 §5](03-state-machine.md#5-arbitration-on-execution-reports) fixes the
arbitration order: classify, duplicate, stale, **late**, then **after a
terminal status**, and [07 §4](07-extension-points.md#4-what-is-not-extensible-on-purpose)
makes that order non-negotiable. Step 4 drops a non-reopening report whose
CumQty equals the record's and whose `60` is older than 9029. Two rows of the
after-terminal table in [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision)
are written as if a report reaches them regardless of its time, and with
`transact-time-guard: true` (the default) neither is reached by an older
report:

| row | what the table says | what happens to a report with an older `60` |
| --- | --- | --- |
| a fill, 14 **equal**, 17 new (06 E21) | 17, 150, 32, 31, 30, 9028 adopted | `IGNORED(late)` at step 4; the record keeps the previous fill's 17/32/31/30/9028 |
| `non-fill-after-terminal: ADOPT_VENUE` | "applies it without the time test" | an equal-CumQty re-ack older than 9029 is `IGNORED(late)` at step 4 |

Neither costs money: the economics are equal by construction in the first row,
and the second is the conservative side. But the documents read as if both rows
work by default, and they do not:

- the detail-only row fires only for a counted fill stamped **no earlier** than
  the terminal report (a venue that stamps 60 at send time, or a fill with no
  60 and no 52);
- `ADOPT_VENUE` differs from `ADOPT_VENUE_IF_LATER` only for a re-ack whose
  time **equals** 9029 or that has none. A deployment that wants it to believe
  an older re-ack needs `transact-time-guard: false` as well, which is the
  pairing [07 §1](07-extension-points.md#1-tier-one-policies) already
  recommends for a tap merging clocks.

**Disposition:** the order of the steps stands. Recommended doc change: one
sentence under each of the two rows saying it is reached only past step 4.
Pinned by `WalkthroughTest` 7.12
(`aCountedFillWithAnOlderTimeIsCaughtByTheLateGuardFirst`) and
`CoverageMatrixTest.e36`.

### F2 — A pending acknowledgement's 39 outlives the request it acknowledged

A `150=6` (or `150=E`) moves the record's 39 to `6` (`E`): that is the venue's
own status, and [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision)
adopts it. When the venue then rejects the cancel with a `35=9` whose 39 is the
**stale pending status** — `39=6` with no CANCEL left in the set — the
`WHEN_NOT_TERMINAL` policy rightly refuses it (R6). But nothing else touches the
record's 39, so it goes on reading `6`, pending cancel, with `9016=0`, until the
next report. A venue whose rejects carry the status at request time produces
this on every rejected cancel that was acknowledged first (06 E15 and E31 under
the `reject-status-at-request-time` mock dialect).

The design has no snapshot to revert to, by intent: that is what makes several
requests in flight trivial. Options:

| option | effect | cost |
| --- | --- | --- |
| **leave it** (current) | `39=6 9016=0` until the next report; a reader can derive "not really pending" from `9016=0` | the record contradicts itself for a while |
| derive on the reject | when a reject removes the last request of the kind the record's pending 39 stands for, and its own 39 is not adopted, set 39 from the cumulative state: `0` if `14 = 0`, else `1` | the machine computes a status, which [03 §8](03-state-machine.md#8-what-the-machine-does-not-do) says it does not |
| a `beforeApply` hook | the deployment that connects to such a venue rewrites the 9's 39 to the derived value ([07 §2](07-extension-points.md#2-tier-two-hooks)) | per venue |

**Disposition: the hook, built.** It is one venue convention, and the hook SPI
exists for exactly this; deriving the status in the machine stays the fallback
if more than one venue turns out to do it. `RejectStatusAtRequestTimeHook`
ships with the module, off unless
`fix42-order-state.hooks.reject-status-at-request-time: true`. Its
`beforeApply` rewrites a `35=9`'s 39 to `0` (no fill) or `1` only in the shape
above:
- the 9's 39 is a pending status, `6` or `E`;
- the record reads that same status;
- no request of that kind is left once the one the 9 names by 11 is gone;
- the order is not terminal.

Every other reject reaches the machine as sent, and `sow/fix42/rejects` keeps
the venue's 39 (R20c). It needs a `cancel-reject-status` that adopts a reject's
39, so it does nothing under `NEVER`.

The machine alone still does what the table's first row says. That stays pinned
as a known divergence in `EdgeCaseFlowPropertyTest` (`KNOWN`), so a change to
the machine fails the entry on purpose. The same test runs every dialect again
with the hook on: under `reject-status-at-request-time-6` the F2 entries are
gone, and nothing moves under any other dialect.

Pinned by:
- `RejectStatusAtRequestTimeHookTest`: a partly filled order, one with no fill,
  a rejected replace, a second cancel still in flight, a record reading `E`, and
  the shapes left alone;
- `OrderStatePublisherTest.requestTimeRejectHook`;
- `RequestTimeRejectIT`: E15 and E31 on AMPS, without and with the hook.

A rebuild by replay (`recovery: JOURNAL`, not built) must run with the same
hook on. The audit topics hold the venue's 39, not the rewritten one.

### F3 — Whole-second TransactTime keeps a same-second re-ack out

Under `60` without milliseconds, a venue's re-ack 250 ms after an erroneous
`150=4` (06 E36) is not "later by a whole second", and an order not answering a
request cannot be reopened by it: the record stays canceled. This is the S4 rule
working as designed — a wrong reopen is live exposure the views never stop
counting, so the conservative side wins — and is recorded here only because the
property test shows it end to end. Pinned as a known divergence in
`EdgeCaseFlowPropertyTest`.

### Mock-venue combinations that contradict their own case

Three edge cases pin a dialect switch rather than run under every one, because
the switch removes the case's premise: E18 is a reject that *carries* `39=4`, so
it cannot also speak the request-time-status dialect; E43 resolves by a stable
37, so it cannot also rotate OrderIDs across the replaces the tap missed (with
both missed and 37 rotated, nothing links the confirm to the order, and the
documented second `MIDSTREAM` record is the result); E57 is two orders with two
OrderIDs, so it cannot also make each OrderID its ClOrdID. Recorded in
`EdgeCaseFlow` beside each case.

## 2. The publish path, against AMPS 5.3.5.135 (phase 3)

Measured with the integration suites on `localhost/amps-demo:5.3.5.135`,
each against its own throwaway instance of the `fix42-order-state` flow.

### F4 — A confirm overtaken by a later-stamped reject is dropped, and the order stays live

A `35=9` moves 9029 (S19: a reject is the venue's clock too). On a tap that
merges two sessions, the venue's `150=4` for X-2 stamped T5 can arrive
**after** its `35=9` for X-3 stamped T6. The confirm then has equal CumQty and
a 60 older than 9029, and §5 step 4 drops it as late: the record keeps
`39=6` (or 1) and **live LeavesQty** while the venue has canceled the order --
exposure the views count until the next report, which for a canceled order
may never come. On a single session the venue's clock and the arrival order
agree and this cannot happen (03 §7.2 is that case, and passes on AMPS).

**Disposition: open, for the design owner.** The design already says ordering
across sessions is not assumed ([02 §7](02-architecture.md#7-threading-and-ordering)),
so the first option is to say so here too. If merged taps are in scope, the
narrowest change is to exempt from step 4 a report whose own 39 is terminal
while the record is **not** -- a venue never closes an order by mistake in the
past tense -- at the cost of a redelivered old `150=4` re-closing an order a
later re-ack reopened (06 E36). Pinned by `WalkthroughTest`
(`ConfirmOvertakenByReject`); `TwoCancelsIT` stamps its answers in arrival
order, as one session would.

### F5 — A blotter payload without its key is neither refused nor dropped: it lands on one shared record

[04 §6](04-recovery-and-operations.md#6-the-publish-path) and the phase 3 plan
expect "a payload lacking its key ... is refused by AMPS and the
`FailedWriteHandler` fires". On this build it is not refused. A publish or
delta to `sow/fix42/order_state` **without** tag 13011 is acknowledged as
persisted and filed under the **empty** key; one **with** `13011=` (empty) lands
on the same record. Every keyless payload, whatever order it was for, merges
into that one record, and nothing reports it -- the same failure mode as a
key-less JSON topic's sentinel key elsewhere in this repository.

Consequences, both built:

- the publisher **refuses to send** a blotter payload without a non-empty key
  (`OrderStatePublisher.send` throws before publishing, so nothing is
  committed and the feed stops loudly, as for a hook that throws). The machine
  never produces one -- `RecordWriter` writes 13011 first on every payload --
  so this is a guard against a future bug, not a path the flow takes;
- the repair queue is still built and unit-tested (`OrderStatePublisherTest`),
  but **no payload the suites tried makes AMPS call the
  `FailedWriteHandler`** other than a `Duplicate` after a replay. Which
  server-side rejections this build reports for a publish remains open; the
  handler is the right place for them when one is found.

Pinned by `PublishPathIT.keylessPayloadsCollapseOntoTheEmptyKey`.

### F6 — A second logon under the publisher's name is not refused: it forces the first off

[04 §5](04-recovery-and-operations.md#5-one-writer) says a transaction-logged
instance allows one connection per client name, so "a second instance started
by mistake is refused by the server". On this build, for the **same user id**
(this flow has no authentication, so every client has the empty one), the
second logon **succeeds and the server disconnects the first**:

```
warning: 02-0025 A client logon with an 'in use' client name for the same user id
         forced a disconnect of client: client[fix42os-...]
info:    07-0013 client[...] disconnected: name in use.
```

Two `HAClient`s under one name therefore take turns kicking each other off,
indefinitely -- each reconnecting, each replaying its store, both publishing
deltas from their own caches onto the same records. That is exactly the
interleaving 04 §5 exists to prevent, and the server does not prevent it.

- the good half: a **restarted** publisher does not wait for its old session
  to be reaped; it takes the name over at once. `AmpsClientConfiguration`
  still retries `NameInUse`, which a deployment with per-user entitlements may
  see;
- the gap: single-writer has to come from outside the client name -- leader
  election, or an exclusive lease the publisher holds before it logs on (an
  AMPS SOW record written with a conditional publish is one way, and a
  follow-up). 04 §5 already says "external leader election; the publisher does
  not elect"; it should not also say the server refuses the second instance.

**Disposition: open.** Recommended doc change in 04 §5 and 04 §8's failure
matrix ("a second publisher instance starts by mistake | nothing: NameInUse"),
and the lease as a phase 4-5 follow-up. Pinned by
`PublishPathIT.sameNameForcesTheFirstSessionOff`.

### Verify on build: the answers

| question (where it was asked) | answer on 5.3.5.135 | pinned by |
| --- | --- | --- |
| a `delta_publish` to a key with no record is stored as a publish (02 §5, 04 §3) | **yes**: the delta becomes the record; the `MIDSTREAM` creation path needs no `publish` fallback | `PublishPathIT.deltaToANewKeyCreatesTheRecord`, `OrderStateEndStateIT.midstreamThenLateD` |
| AMPS's fix parser needs no tag 35 on a record (02 §4) | **yes**, stored and queryable without it | `PublishPathIT`, `OrderStateEndStateIT.dsTermsSurviveEveryDelta` |
| every topic the client writes journalled → the store drains (S3) | **yes**: `publishStoreDepth` is 0 after a flush with every route live, catch-alls and `fix42/malformed` included | `OrderStateEndStateIT.storeDrains`, `PublishPathIT.storeDrainsWithEveryRouteLive` |
| a payload lacking its key is refused and the handler fires (04 §6) | **no**: F5 | `PublishPathIT.keylessPayloadsCollapseOntoTheEmptyKey` |
| an empty value in a delta is rejected or ignored (05 phase 3) | **neither**: it overwrites the field with an empty value (present, empty). The machine never sends one | `PublishPathIT.emptyValueInADelta` |
| a second client with the same name gets `NameInUse` (04 §5) | **no**: F6 | `PublishPathIT.sameNameForcesTheFirstSessionOff` |
| AMPS restarted mid-flow; the end state is unchanged (E71) | **yes**: the `HAClient` reconnects, the store replays, every record equals the venue's | `RestartIT.restartMidFlow` |
| `sow_delete` by filter is durable; a journalled topic does not bring deleted records back at restart (S13) | **yes** for `sow_delete`; deleting the SOW *file* was not tried | `RestartIT.resetIsDurable` |
| a 17-less `20=3` reaches the blotter and `fix42/unrouted`, not `execs_audit` (S10) | **yes** | `PublishPathIT.statusReportWithoutExecId` |
| the four views over the new topic | agree with the venue's totals per account × symbol × side, terminal orders contributing no LeavesQty, `MIDSTREAM` records contributing what their reports carried | `ExposureViewIT` |
| `bootRun` shows the two-cancels orders as canceled | **yes**: `EC-E1-1` and `EC-E2-1` read `39=4 151=0 15035=NONE 9016=0 9017=Y` (and `9100=CLOSED` from the shipped hook) | run by hand against a throwaway instance |

Still unverified: whether AMPS acknowledges a publish to an **unjournalled**
topic (every topic is journalled, so the question never arises), and whether
the `HAClient` blocks the calling thread while disconnected (the restart test
publishes after the instance is back).

## 3. Recovery and cache misses (phase 4)

### F7 — An audit copy that carried its own 13011 names a key that is no chain's

Decision 3 publishes the audit copy as received, plus the 13011 trailer --
and a message that already carries 13011 is published as it is, counted as
`auditKeyTagConflict` (02 §5). The miss lookup of 04 §3 then reads that
copy's 13011 as the order's key. E60's D carries `13011=BOGUS` (a venue tag
in the machine's range); after a `recovery: NONE` restart its first report
looked the order up on `orders_audit`, found `BOGUS`, found no record under
it, and created an orphan `MIDSTREAM` record keyed `BOGUS` (the S8 path).

**Fixed.** `MissResolver` accepts an audit hit only when the key it names is a
chain's key: the request names itself (it is the root), the key has its own
root on `orders_audit`, or it has a blotter record. Anything else is a miss, and
the next rule (below) finds the real record. Pinned by `RecoveryIT.noneWithLookups`,
which ran a `BOGUS` record before the fix.

### F8 — With an incomplete cache, the lookups must run in resolution order

04 §3 looks an order up "when its ids resolve to nothing in the index". With an
incomplete cache, an index miss is not conclusive, and looking up only after
**every** candidate missed lets a later candidate win that the index happens to
know: E58's G names its order in 41 and **another** order in 37; once the other
order had been looked up (and its 37 bound), the G resolved by 37 -- 41's miss
had not been looked up yet -- and landed on the wrong order.

**Fixed.** A ClOrdID candidate the index does not know is looked up on the audit
topics **in its turn** (41 → 37 → 11 for a request), before the next candidate.
Same number of reads on a real miss; one more read only when an earlier ClOrdID
misses and a later OrderID would hit. Pinned by
`CacheMissTest.lookupsInResolutionOrder` and `RecoveryIT.noneWithLookups`.

### Two more fixes the recovery suites forced

- **9030 outlives 9021's cap.** The first version derived 9030 from the chain
  list, which a rebuild holds only as the last `chain-ids-published` ids: E61's
  thousand-amend order came back from a restart counting from 50. The entry now
  carries the count, loaded from 9030. Pinned by `RecoveryIT.sowRecovery` (tag
  for tag against the uninterrupted run).
- **A key minted while the cache is incomplete is checked against the blotter.**
  An order first seen mid-stream before a `recovery: NONE` restart has no audit
  record naming its ids (its first message was a report), so every lookup misses
  and the machine mints its key again -- the same key, whose record exists. The
  first version then delta-published a fresh entry over it, resetting 9023, 9021
  and the counters. Now, while the cache is incomplete, a minted key is read from
  the blotter first and the entry rebuilt from the record (R4c, S9, extended
  from the D to every message). One keyed read per new chain, only in that mode.
  Pinned by `CacheMissTest.midstreamOrderAfterAnIncompleteRestart`.

### What the recovery suites pin

| case | result on 5.3.5.135 | pinned by |
| --- | --- | --- |
| E49 a publisher restart mid-flow, `recovery: SOW` | every record equal, tag for tag, to the uninterrupted run's; no `MIDSTREAM` chain | `RecoveryIT.sowRecovery` |
| E53/E55 `recovery: NONE` with `allow-incomplete-cache` | one record per order, venue fields right; a resent D keeps the record; a stranger's report is a `MIDSTREAM` record. E43 (two replaces missed, known only by 37) needs `miss-lookup-by-order-id`, now built; without it, the documented `MIDSTREAM` twin | `RecoveryIT.noneWithLookups`, `CacheMissTest.knownOnlyByOrderId` |
| E56 `recovery: NONE` against a non-empty blotter | refused, naming `allow-incomplete-cache` | `RecoveryIT.noneRefused` |
| E54 `load-open-orders-only`, then a late bust | lands on the terminal order's record through the tombstone; 9021 kept, 9023 counts on | `TombstoneIT.partialLoad` |
| E47/E48 `terminal-retention`, then a late bust and a late cancel | both find the record again, the cancel through `orders_audit` by its 41 | `TombstoneIT.eviction` |
| E62 a record missing 9026; a record of schema 2 | loads with the default and counts `recoveredWithDefaults`; refuses the start | `TombstoneIT.schema` |

## 4. Measurements (phase 5)

The README's "What it costs" has the numbers, measured on 2026-11-01 against
an emulated linux/amd64 AMPS 5.3.5.135 under podman on an Apple M4 Pro. Each
is pinned under a ceiling about 25% above it:

| what | measured | pinned by |
| --- | --- | --- |
| bytes allocated per message, through `onMessage` (desk-status hook, WARN) | 10,151 | `PublishPathAllocationTest` |
| bytes allocated per message, the machine alone | 8,801 | `PublishPathAllocationTest` |
| cache memory held by a typical order | 1,545 bytes | `ChainMemoryTest` |
| cache memory held by a 1,000-ClOrdID chain | 102,888 bytes | `ChainMemoryTest` |
| a fill's allocation, on a 1-id order vs the 1,000-id one | 3,840 vs 7,808 bytes | `ChainMemoryTest` |
| an evicted order's tombstone, 1,000 orders | 238 bytes | `ChainMemoryTest` |

`ScaleMemoryMeasurement` (`scaleMemory`, not a test) measured the heap at a
million orders per scenario, as live heap after full collections:

| per order | heap up to 32 GB | heap over 32 GB |
| --- | ---: | ---: |
| kept in the cache, 1 ClOrdID | 1,568 B | 1,942 B |
| kept, 5 ClOrdIDs | 2,379 B | 2,909 B |
| an evicted order's tombstone | 276 B | 359 B |

The README turns these into a heap plan for 50 million ClOrdIDs.

`OrderStateThroughputBenchmark` (`publishBenchmark`, not a test) gave the
throughput figures. On `fix42-publisher`'s load, the state machine handles
9,000 messages in 13 ms. A run to the flush takes 6.0 s with no publish store
and 7.0 s with either store, against `fix42-publisher`'s 2.4 s on the same
machine. Replaying this publisher's own 24,000 publishes through a bare AMPS
`Client` takes the same 6.0 s, so the time is the server's, not the
publisher's.

### F9 — Every message copies its order's whole ClOrdID list

[04 §6](04-recovery-and-operations.md#6-the-publish-path)'s two-phase apply
plans each message on a copy of its order's cache entry. The copy includes
`clOrdIds`, which holds every ClOrdID the chain has had: 9021 publishes only
the newest 50, but the cache keeps all of them, and so does the index, so a
report quoting any of them resolves ([03 §3](03-state-machine.md#3-identity)).
On an order amended 999 times, each later fill allocates 7,808 bytes against
3,840 on a fresh order: 4 bytes per id, per message. The entry holds about 103
bytes per id (102,888 for the chain).

At normal chain lengths, a handful of ids, this is noise. It matters only for
a pathological chain: an algorithm that amends one order thousands of times
in a day pays it on every report.

| option | effect | cost |
| --- | --- | --- |
| **leave it** (current) | linear in chain length, per message | none; stated in the README |
| a copy-on-write id list | the plan shares the list until a message adds an id | a list type of the machine's own; every rule that edits the list goes through it |
| cap the entry's list, keep the index | the copy is bounded; the index still resolves any id | eviction unbinds an order's ids by walking its list, so it would need the index to enumerate by key instead |

**Disposition: open, for the design owner.** Recommendation: leave it, unless
a venue's flow shows chains in the thousands. Pinned by
`ChainMemoryTest.thousandClOrdIdChain`, whose ceiling fails if the cost grows.

### F10 — A tombstone costs about 276 bytes, not 40, and none is ever removed

[04 §4](04-recovery-and-operations.md#4-eviction) puts a tombstone at "about
40 bytes" per OrderID. The figure counted the map entry alone. Measured, an
evicted order with one OrderID holds 276 bytes at a million orders (238 at a
thousand, where the tables are sized differently):
- the key string, in the key set;
- the OrderID string and its entry in the OrderID map;
- each map's share of its table.

Without compressed pointers it holds 359 bytes. Nothing removes a tombstone:
eviction makes them, and only a restart clears them. So with eviction on, the
cache still grows by that much per order all day. At 50 million orders that
is about 14 GB, most of the README's 24 GB plan.

| option | effect | cost |
| --- | --- | --- |
| **leave it** (current) | grows until the daily restart | sized for; stated in the README |
| expire tombstones after a second window | bounded | a report after that window resolves only through the miss lookup by its 11 on `orders_audit` (or by 37, with `miss-lookup-by-order-id`), one server query each |
| keep only the OrderID map | smaller (not measured) | the key set is how a new order's minted key is checked for collisions (03 §3.2), so an evicted order's key could be taken again |

**Disposition: open, for the design owner.** Recommendation: leave it and
size for it; the daily restart is already the bound docs/04 relies on. 04 §4
now gives the measured figure. Pinned by `ChainMemoryTest.tombstone`.

### F11 — Eviction without the miss lookup overwrites the record

A late venue report for an evicted order resolves through its tombstone to the
right key. The entry is then rebuilt from the blotter record, but only through
the miss lookup's `record` read. With `miss-lookup: false`, that read returns
nothing, so the machine takes the S8 path meant for a record that is truly
gone: a `MIDSTREAM` chain under the key, built from the report alone and
delta-published over the real record. Measured on a filled order, a late bust
overwrote:
- 9021 `X-1,X-2` with `X-2`;
- 9030 `2` with `1`;
- 9023 `6` with `1`;
- 9018 `D` with `MIDSTREAM`.

That is the S9 overwrite 04 §4 says eviction never does. Until this fix,
nothing refused the combination: `terminal-retention` and
`load-open-orders-only` (which makes the same tombstones at load) were
validated independently of `miss-lookup`.

**Disposition: fixed.** `OrderStateProperties.validate()` now refuses the start
when `terminal-retention` is above zero, or `load-open-orders-only` is true,
while `miss-lookup` is false. The message names the setting and this finding.
This is the same kind of refusal `recovery: NONE` already gives a non-empty
blotter without `allow-incomplete-cache`. The machine still takes the S8 path
when a record is truly gone; only the configuration that made it the normal
path is refused. Pinned by `OrderStatePropertiesTest.tombstonesNeedTheMissLookup`
and `tombstonesWithTheMissLookupAreAccepted`.

### F12 — Orders loaded at startup are never scheduled for eviction

Eviction is scheduled when a commit leaves an order finished. The SOW loader
puts entries straight into the cache, without a commit. So after a restart
with `recovery: SOW` and `load-open-orders-only: false`, every order that was
already finished comes back as a full entry and stays for the rest of the day,
unless another message for it happens to arrive. A mid-day restart therefore
reloads the morning's whole flow, sized as if eviction were off.

| option | effect | cost |
| --- | --- | --- |
| schedule finished entries at load, due one retention after the restart | eviction covers the reloaded orders too | none beyond the queue entries |
| require `load-open-orders-only: true` with eviction | finished orders load as tombstones | a refused start for a configuration that works today |

**Disposition: fixed, by the first option.** `OrderStateMachine.load` now
schedules a terminal entry one retention after it is loaded, through the same
helper `commit` uses. The deadline counts from the restart, not from when the
order finished, because the record does not say when that was. A reloaded
order therefore stays in the cache at most one retention longer than it would
have without the restart. `load-open-orders-only: true` still gives the lower
peak right after a restart, with tombstones from the start. Pinned by
`CacheMissTest.loadedTerminalOrdersAreEvicted`, which fails without the fix,
and against AMPS by `TombstoneIT.reloadedFinishedOrdersAreEvicted`: the
finished orders reloaded in full are evicted, the open ones stay, and a late
bust then lands on its record through the tombstone.

## 5. Not built

Listed for the next session:
- the QuickFIX/J `FeedAdapter`, and the measured loss bound of a `MEMORY`
  store;
- the daily reset as a script (its `sow_delete` is pinned durable by
  `RestartIT`);
- `StoreCrashIT` for a `FILE` store;
- `recovery: JOURNAL`;
- phase 5's optional server-side index on `/37` for
  `miss-lookup-by-order-id`, which today scans the blotter.
