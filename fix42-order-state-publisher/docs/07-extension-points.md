# 7. Extension points

The machine's rules are code, deliberately ([01 §5](01-problem-the-two-cancels.md#5-what-the-stateless-design-got-right-and-must-survive)):
the stateless publisher's `projection` blocks were a state machine written as
configuration, and the result was a rulebook that could not express a
condition. But venues differ, and so do desks. This document says what can be
changed without forking the machine, in three tiers of increasing reach.

---

## 1. Tier one: policies

Decisions the matrix in [06](06-case-coverage.md) marks **policy** are enums
bound from `application.yml` and switched on inside the machine. They are the
venue-dialect knobs:

| knob | values | decides |
| --- | --- | --- |
| `cancel-reject-status` | `WHEN_NOT_TERMINAL` (default), `NEVER`, `ALWAYS` | what a `35=9`'s tag 39 may do to the record ([03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision)) |
| `late-fill-after-terminal` | `APPLY_ECONOMICS` (default), `ADOPT_VENUE`, `IGNORE` | a fill with higher CumQty after a terminal report ([03 §7.12](03-state-machine.md#712-cancel-confirmed-then-a-late-execution)) |
| `non-fill-after-terminal` | `ADOPT_VENUE_IF_LATER` (default), `ADOPT_VENUE`, `IGNORE` | a non-fill report with a non-terminal 39 after a terminal report: a venue-asserted reopen, or a late ack ([03 §7.16](03-state-machine.md#716-a-replace-reported-as-cancel-then-new)) |
| `fill-under-pending-id` | `IGNORE` (default), `ANSWERS_REPLACE_IF_TERMS_MATCH` | whether a fill carrying a pending amend's ClOrdID answers it ([03 §6.4](03-state-machine.md#64-358-executionreport)) |
| `stale-guard` | `true` (default), `false` | drop a non-reopening report whose CumQty went backwards |
| `transact-time-guard` | `true` (default), `false` | drop a non-reopening report with equal CumQty and an older TransactTime |
| `exec-id-history` | integer, default 8 | depth of the per-order ExecID ring |
| `order-term-tags` | a tag list | which order terms a `150=0/5/D` report refreshes |
| `strip-tags` | a tag list | the session header and trailer tags removed from every payload the machine builds; 9000–9099, 13xxx or 15xxx are always removed |
| `chain-ids-published` | integer, default 50 | how many ClOrdIDs 9021 carries |
| `terminal-retention` | duration, default never | when a terminal order leaves the cache |

The remaining knobs are operations rather than dialect, all in
[02 §6](02-architecture.md#6-configuration): `mode`, `publish-store`,
`publish-store-dir`, `recovery`, `load-open-orders-only`,
`allow-incomplete-cache`, `miss-lookup`, `miss-lookup-by-order-id`,
`publish-unchanged`, `schema-version`, `dialect`, `audit.key-tag`.

A `VenueDialect` is a named bundle of the dialect knobs (`default`, plus
whatever a deployment adds in yml under `dialects:`), selected per publisher.
It costs nothing in the machine — it is a different set of constructor
arguments — and it makes "we are connecting to venue Z" a one-line change
rather than ten.

**One precondition every dialect shares.** The TransactTime rules
([03 §4–5](03-state-machine.md#4-status-venue-truth-and-one-policy-decision))
assume the reports of one order come from **one clock**: one matching engine,
or engines whose clocks agree to within the resolution of tag 60. A broker tap
that merges several venues' reports for one order, or a venue whose `60` is
not monotonic within a session, does not meet it; such a deployment runs
`transact-time-guard: false` and `non-fill-after-terminal: IGNORE`, and
accepts that an old status report redelivered out of order is caught only by
its CumQty.

## 2. Tier two: hooks

For behaviour the policies do not cover, the machine exposes a small SPI.
Hooks are ordinary Spring beans implementing `OrderStateHook`; every bean
found is called in `@Order` sequence. Each method has a no-op default, so a
hook implements only what it needs.

```java
public interface OrderStateHook {

    /** Before resolution. Return a key to override identity, or null to let the machine resolve. */
    default String resolveKey(FixFrame message, IndexView index) { return null; }

    /** Before the rules run. May veto the message or hand back a normalised one. */
    default Decision beforeApply(FixFrame message, OrderStateView state) { return Decision.proceed(); }

    /** After the rules ran and the dirty mask is final. May add record fields. */
    default void afterApply(FixFrame message, OrderStateView before, OrderStateView after,
                            Outcome outcome, RecordFields extra) { }

    /** When the machine ignored a message, with the reason. */
    default void onIgnored(FixFrame message, OrderStateView state, IgnoreReason reason) { }

    /** When a chain is created, before the first publish. May seed record fields. */
    default void onCreate(FixFrame message, OrderStateView state, RecordFields extra) { }
}
```

Where:

- `OrderStateView` is a read-only view of the cache entry (ids, acked terms,
  venue status, cumulative snapshot, the pending set, counters);
  `IndexView` answers "which key is this id bound to", so a `resolveKey`
  override can still find the chain a later message belongs to (R20b);
- `Decision` is `proceed()`, `ignore(reason)`, or `proceedWith(FixFrame)` for
  a message the hook has normalised (a venue that puts LastShares in a custom
  tag, say). **The audit topics always receive the original frame**; the
  normalised one feeds the machine only (R20c);
- `RecordFields` is a sink for extra tags on the outgoing publish. Hooks write
  in the user-defined range **9100–9999**; the machine owns 9000–9099,
  13000–13999 (root values) and 15000–15999 (pending values), and
  `RecordFields` refuses a tag outside 9100–9999. A field written here is
  delta-published with the rest, lands on the record, and — because it is on
  the record — survives a restart like everything else, though the machine
  does not read it back;
- `Outcome` and `IgnoreReason` are the machine's own types.

What the SPI is for, by example:

| need | hook |
| --- | --- |
| a venue that reports a replace as cancel-then-new, and should not read as canceled even for one message | `beforeApply` on a `150=4` whose 11 is the working id while a REPLACE is pending: `ignore("replace cancel leg")` |
| a venue that answers a TIF-only amend with `150=D` | `beforeApply`: `proceedWith` the frame with `150=5` |
| a venue whose `35=9` semantics match none of the three policies | `beforeApply` on `35=9`: rewrite or remove tag 39 |
| a desk-specific status (`WORKING`, `CLOSED`, `BROKEN`) derived from 39 and the pending set | `afterApply`: write it to 9100 |
| mark an order whose reports are inconsistent (`38 ≠ 14 + 151`) | `afterApply`: write 9101=`Y` when the machine's `inconsistentReports` moved |
| ignore a test-flow account entirely | `beforeApply`: `ignore("test account")` on `1 = 'TEST'` |
| alert on a late execution or a venue-asserted reopen | `afterApply`: if 9026 moved, or `reopenedByVenue` did, call out |
| record every ignored message for a desk | `onIgnored`: write the reason to a queue a *separate* thread publishes to `sow/fix42/ignored` |

What hooks must **not** do, and the machine enforces where it can:

- **touch the fields the machine owns.** 9000–9099, 13000–13999,
  15000–15999, 11/41/37, 38/44, 39, 14/151/6, 17/150. A hook that rewrites 39 after the fact reintroduces the
  last-writer-wins problem this module exists to remove. `RecordFields`
  refuses tags in that range;
- **keep state of their own.** A hook is a pure function of the message and
  the state view. Replay ([04 §2.2](04-recovery-and-operations.md#22-rebuild-by-replay-recovery-journal-later-phase))
  converges only because the machine is deterministic, and a hook with memory
  breaks that silently. A hook that needs state puts it on the record, in its
  own tags, and reads it back from the view;
- **block.** Hooks run on the publishing thread. A lookup against another
  system, or a publish of its own, belongs upstream of the publisher, on a
  queue drained by another thread, or in a subscriber of the record;
- **throw.** A hook that throws is a bug, and the machine treats it as one:
  the message is held, nothing is committed, the exception propagates out of
  `FeedAdapter.onMessage`, and the feed stops at that message until the hook
  is fixed (R20a). Deterministic on replay, and loud. A hook that wants to
  skip a message returns `ignore(reason)`.

The `before`/`after` views are copies made per message, so a deployment with
hooks pays an allocation the hookless path does not; phase 5 of the
[plan](05-implementation-plan.md) measures it (R20d).

## 3. Tier three: replace a component

The components in [02 §2](02-architecture.md#2-components) are constructor-injected
interfaces, so a deployment that needs more than a hook replaces one:

| replace | to |
| --- | --- |
| `FeedAdapter` | sit the publisher behind a different FIX engine, a message bus, or a file replay |
| `AuditRouter` | route pass-through topics by something the yml cannot express (per account, per venue) |
| `RecordWriter` | publish the record as JSON instead of FIX, for a consumer that prefers it — the machine does not care what the record looks like on the wire |
| `SowCacheLoader`, `MissResolver` | rebuild or look up the cache from somewhere other than the SOW and audit topics |
| `OrderKeyIndex` | an off-heap or persistent index for a flow the heap cannot hold |
| `OrderStateMachine` | the whole rule set — at which point this is a different publisher, and the rest of the module is a shell for it |

## 4. What is not extensible, on purpose

- **The record's core schema** (02 §4). Consumers, the exposure views, and the
  recovery loader all read it; a deployment that changes what 39 means has
  changed the product.
- **The order of the arbitration steps** (03 §5). Classify, duplicate, stale,
  late, then after-terminal. A hook sees the message before and after them,
  not between.
- **Determinism.** Everything above is constrained so that the same feed
  through the same configuration produces the same records, which is the
  property every recovery path in [04](04-recovery-and-operations.md) stands on.

## 5. Where it lands in the plan

Tier one is phase 2 (the policies are constructor options of the machine)
and phase 3 (the yml binding and `VenueDialect`). Tier two is phase 3: the
SPI, the Spring collection of hook beans, the fail-fast semantics, and one
shipped example hook (the derived desk status above) with a test that the
machine's own fields cannot be written through `RecordFields`. Tier three is a
property of the design rather than a deliverable.
