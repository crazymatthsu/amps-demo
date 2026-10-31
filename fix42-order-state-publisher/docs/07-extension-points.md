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
| `stale-guard` | `true` (default), `false` | drop a non-reopening report whose CumQty went backwards |
| `exec-id-history` | integer, default 8 | depth of the per-order ExecID ring |
| `terminal-retention` | duration, default never | when a terminal order leaves the cache |
| `recovery` | `SOW` (default), `NONE`, later `JOURNAL` | how the cache is rebuilt on start |

A `VenueDialect` is a named bundle of these (`default`, plus whatever a
deployment adds in yml under `dialects:`), selected per publisher. It costs
nothing in the machine — it is a different set of constructor arguments — and
it makes "we are connecting to venue Z" a one-line change rather than six.

## 2. Tier two: hooks

For behaviour the policies do not cover, the machine exposes a small SPI.
Hooks are ordinary Spring beans implementing `OrderStateHook`; every bean
found is called in `@Order` sequence. Each method has a no-op default, so a
hook implements only what it needs.

```java
public interface OrderStateHook {

    /** Before resolution. Return a key to override identity, or null to let the machine resolve. */
    default String resolveKey(FixFrame message) { return null; }

    /** Before the rules run. May veto the message or hand back a rewritten one. */
    default Decision beforeApply(FixFrame message, OrderStateView state) { return Decision.proceed(); }

    /** After the rules ran and the dirty mask is final. May add or override record fields. */
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
- `Decision` is `proceed()`, `ignore(reason)`, or `proceedWith(FixFrame)` for
  a message the hook has normalised (a venue that puts LastShares in a custom
  tag, say);
- `RecordFields` is a sink for extra tags on the outgoing publish. Hooks write
  in the user-defined range **9100 and above**; the machine owns 9000–9099.
  A field written here is delta-published with the rest, lands on the record,
  and — because it is on the record — survives a restart like everything
  else, though the machine does not read it back;
- `Outcome` and `IgnoreReason` are the machine's own types.

What the SPI is for, by example:

| need | hook |
| --- | --- |
| a venue whose `35=9` semantics match none of the three policies | `beforeApply` on `35=9`: rewrite or veto tag 39 |
| a desk-specific status (`WORKING`, `CLOSED`, `BROKEN`) derived from 39 and the pending set | `afterApply`: write it to 9100 |
| mark an order whose reports are inconsistent (`38 ≠ 14 + 151`) | `afterApply`: write 9101=`Y` when the machine's `inconsistentReports` moved |
| ignore a test-flow account entirely | `beforeApply`: `ignore("test account")` on `1 = 'TEST'` |
| a venue that reuses OrderID across days | `resolveKey`: prefix the key with the trading date from tag 60 |
| alert on a late execution | `afterApply`: if 9026 moved, call out |
| audit every ignored message to a side topic | `onIgnored`: publish the frame to `sow/fix42/ignored` |

What hooks must **not** do, and the machine assumes they do not:

- **touch the fields the machine owns.** 9000–9099, 11/41/37, 38/44, 39,
  14/151/6, 17/150. A hook that rewrites 39 after the fact reintroduces the
  last-writer-wins problem this module exists to remove. `RecordFields`
  refuses tags in that range;
- **keep state of their own.** A hook is a pure function of the message and
  the state view. Replay ([04 §2.2](04-recovery-and-operations.md#22-rebuild-by-replay-recovery-journal-later-phase))
  converges only because the machine is deterministic, and a hook with memory
  breaks that silently. A hook that needs state puts it on the record, in its
  own tags, and reads it back from the view;
- **block.** Hooks run on the publishing thread. A lookup against another
  system belongs upstream of the publisher, or in a subscriber of the record.

## 3. Tier three: replace a component

The components in [02 §2](02-architecture.md#2-components) are constructor-injected
interfaces, so a deployment that needs more than a hook replaces one:

| replace | to |
| --- | --- |
| `AuditRouter` | route pass-through topics by something the yml cannot express (per account, per venue) |
| `RecordWriter` | publish the record as JSON instead of FIX, for a consumer that prefers it — the machine does not care what the record looks like on the wire |
| `SowCacheLoader` | rebuild the cache from somewhere other than the SOW record |
| `OrderKeyIndex` | an off-heap or persistent index for a flow the heap cannot hold |
| `OrderStateMachine` | the whole rule set — at which point this is a different publisher, and the rest of the module is a shell for it |

## 4. What is not extensible, on purpose

- **The record's core schema** (02 §4). Consumers, the exposure views, and the
  recovery loader all read it; a deployment that changes what 39 means has
  changed the product.
- **The order of the arbitration steps** (03 §5). Duplicate, then stale, then
  after-terminal. A hook sees the message before and after them, not between.
- **Determinism.** Everything above is constrained so that the same feed
  through the same configuration produces the same records, which is the
  property every recovery path in [04](04-recovery-and-operations.md) stands on.

## 5. Where it lands in the plan

Tier one is phase 2 (the policies are constructor options of the machine)
and phase 3 (the yml binding and `VenueDialect`). Tier two is phase 3: the
SPI, the Spring collection of hook beans, and one shipped example hook (the
derived desk status above) with a test that the machine's own fields cannot
be written through `RecordFields`. Tier three is a property of the design
rather than a deliverable.
