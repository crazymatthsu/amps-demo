# 7. Extension points

The machine's rules are code, deliberately ([01 §5](01-problem-the-two-cancels.md#5-what-the-stateless-design-got-right-and-must-survive)):
the stateless publisher's `projection` blocks were a state machine written as
configuration, and the result was a rulebook that could not express a
condition. But venues differ, and so do desks. This document says what can be
changed without forking the machine, in three tiers of increasing reach, and
([§5](#5-which-tags-the-topics-and-the-record-carry)) which tags the topics
and the record carry: the rulebook's keys are back for that, on every tag the
machine does not own.

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
| `strip-tags` | a tag list | the session header and trailer tags removed from every payload the machine builds; 12000–12999, 13xxx or 15xxx are always removed |
| `chain-ids-published` | integer, default 50 | how many ClOrdIDs 12021 carries |
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
  in the hooks' range **12100–12999**; the machine owns 12000–12099,
  13000–13999 (root values) and 15000–15999 (pending values), and
  `RecordFields` refuses a tag outside 12100–12999. A field written here is
  delta-published with the rest, lands on the record, and — because it is on
  the record — survives a restart like everything else, though the machine
  does not read it back;
- `Outcome` and `IgnoreReason` are the machine's own types.

What the SPI is for, by example:

| need | hook |
| --- | --- |
| a venue that reports a replace as cancel-then-new, and should not read as canceled even for one message | `beforeApply` on a `150=4` whose 11 is the working id while a REPLACE is pending: `ignore("replace cancel leg")` |
| a venue that answers a TIF-only amend with `150=D` | `beforeApply`: `proceedWith` the frame with `150=5` |
| a venue whose `35=9` semantics match none of the three policies | `beforeApply` on `35=9`: rewrite or remove tag 39. One ships: `RejectStatusAtRequestTimeHook`, for rejects that carry the request-time `39=6`/`E` after a pending ack ([11 F2](11-build-findings.md#f2--a-pending-acknowledgements-39-outlives-the-request-it-acknowledged)) |
| a desk-specific status (`WORKING`, `CLOSED`, `BROKEN`) derived from 39 and the pending set | `afterApply`: write it to 12100. One ships: `DeskStatusHook` |
| mark an order whose reports are inconsistent (`38 ≠ 14 + 151`) | `afterApply`: write 12101=`Y` when the machine's `inconsistentReports` moved |
| ignore a test-flow account entirely | `beforeApply`: `ignore("test account")` on `1 = 'TEST'` |
| alert on a late execution or a venue-asserted reopen | `afterApply`: if 12026 moved, or `reopenedByVenue` did, call out |
| record every ignored message for a desk | `onIgnored`: write the reason to a queue a *separate* thread publishes to `sow/fix42/ignored` |

What hooks must **not** do, and the machine enforces where it can:

- **touch the fields the machine owns.** 12000–12099, 13000–13999,
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
| `PublishSink` | write the records and the audit copies somewhere other than AMPS |
| `AuditRouter` | route pass-through topics by something the yml cannot express (per account, per venue) |
| `RecordWriter` | publish the record as JSON instead of FIX, for a consumer that prefers it — the machine does not care what the record looks like on the wire |
| `RecordSource`, `MissLookup` | rebuild or look up the cache from somewhere other than the SOW and audit topics |
| `OrderKeyIndex` | an off-heap or persistent index for a flow the heap cannot hold |
| `OrderStateMachine` | the whole rule set — at which point this is a different publisher, and the rest of the module is a shell for it |

### 3.1 A feed and a sink other than AMPS

The core does not know where its FIX comes from or where its records go. It
is the packages `fix`, `machine`, `spi`, `hooks`, `publish` and `recovery`,
plus the policy enums in `config`. These import only the JDK, SLF4J and each
other, and `checkCoreSelfContained` fails the build if one imports anything
else. AMPS code lives in the `amps` package. Spring code lives in the
application class, in `MockFlowRunner`, in `amps.AmpsClientConfiguration` and
in `config/OrderStateProperties`. The core takes plain option records:
`MachineOptions`, `PublisherOptions`, `RoutingOptions` (with its `Route`s) and
`RecoveryOptions`. `OrderStateProperties` maps the yml onto each of them.

An adapter implements these:

| interface | what it does | the AMPS one |
| --- | --- | --- |
| `spi.FeedAdapter` | calls `MessageSink.onMessage(bytes, offset, length)` once per message, in session order | `MockFlowRunner` in the demo |
| `publish.PublishSink` | `publish` (a whole record or an audit copy), `deltaPublish`, `flush`, `unpersisted`, `lastPersisted` | `amps.AmpsPublishSink` |
| the failure callback into `publish.RepairQueue` | `onRefused(Failure)` for a write refused after `publish` returned, `onDuplicate(sequence)` for one dropped as already held; callable from any thread | `amps.AmpsFailedWriteHandler` |
| `recovery.RecordSource` | `name`, `isEmpty`, `forEach`: every record read back at startup | `amps.SowRecordSource` |
| `machine.MissLookup` | `keyForClOrdId`, `keyForOrderId`, `record`: the keyed reads on a cache miss | `amps.MissResolver` |

Wired by hand, without Spring:

```java
OrderStateMachine machine = new OrderStateMachine(machineOptions);
machine.setMissLookup(lookup);                       // or leave MissLookup.NONE
new SowCacheLoader(recoveryOptions).load(source, machine);
RepairQueue repairs = new RepairQueue();             // the sink reports into it
OrderStatePublisher publisher = new OrderStatePublisher(machine, new RecordWriter(machineOptions),
        new AuditRouter(routingOptions), sink, repairs, hooks, publisherOptions);
feed.run(publisher);
publisher.close();                                   // repairs what is queued, then flushes
```

A sink and a feed must keep three contracts. Over AMPS, the SOW's merge,
the publish store and the SOW's own reads keep them.

**1. The output is a delta against a keyed record.** The message that
creates an order's cache entry, normally its D, is a full publish of the
record. Every later message is a `deltaPublish` carrying only the fields that
changed, under the same key: tag 13011, the order's root ClOrdID. AMPS merges
each delta into the SOW record. Any other sink must do that merge itself,
keyed by 13011. If it cannot, its consumer must apply each delta over the
record it already holds. The fields only the full publish carries are the
D's descriptive terms (account, symbol, side and the like). A sink that
replaces the record with each delta, instead of merging, loses them, and the
cache cannot restore them because it does not keep them. `RecordWriter.writeAll`
writes every field the cache does hold, as the repair of a refused delta
does. A mode that publishes the full record every time, for a sink that
cannot merge, is possible on top of it. It is not built.

**2. `onMessage` returning means durably accepted.** The feed adapter
acknowledges a message to its source only after `onMessage` returns: the FIX
session's sequence number, a bookmark, or an offset. It must never acknowledge
when `onMessage` throws. The publisher returns only after every publish for
the message has returned from the sink and the cache is committed. So a
`publish` or `deltaPublish` that returns must survive whatever the deployment
needs it to survive; over AMPS with a `FILE` store, that is a process crash. A
sink that learns of a refusal later, on another thread, reports it through
`RepairQueue.onRefused`, and the publisher repairs it on its own thread before
the next message. The sequence the sink returned from `publish` goes in the
`Failure`: it is how a refused payload without its own 13011 finds its order.
A sink that does neither is at most once, as `publish-store: NONE` is.
`flush` waits until everything published is durable. `unpersisted` and
`lastPersisted` feed the metrics; a sink with no store returns 0 and
`Long.MAX_VALUE`.

**3. The records are the checkpoint.** With `recovery: SOW`, the cache is
rebuilt at startup by reading every record back through the `RecordSource`.
On a cache miss, the `MissLookup` reads the audit copies by ClOrdID, and the
record by 13011 once everything published so far is durable, so the record
cannot lag a delta still in flight. Both read what the sink wrote. So the
sink's own store must be readable, by key for the lookup, or a side store
beside it must be: a table, or a compacted topic, keyed by 13011. A
fire-and-forget output, such as a socket or a log, can only run with
`recovery: NONE` and `allow-incomplete-cache: true`. Its `RecordSource`
cannot know what downstream holds, so it must not answer empty. With no
lookup either (`miss-lookup: false`), every order the cache does not hold
becomes a new `MIDSTREAM` record. Eviction and `load-open-orders-only` are
then refused ([11 F11](11-build-findings.md#f11--eviction-without-the-miss-lookup-overwrites-the-record)).

Three more things hold for any sink:
- **Single thread, session order, one writer.** The feed calls `onMessage`
  from one thread, in the order the session delivered. One publisher writes a
  given blotter: two would each publish deltas against their own cache.
- **Names, not topics.** `AuditRouter` routes by topic name. The blotter's
  name and each route's `topics` are strings handed to the sink, and a
  non-AMPS sink maps them to whatever it writes, such as a table or a Kafka
  topic. `MissResolver.Options` names the audit topics it reads.
- **`topic-keys` and `key-guard` are SOW ideas.** They skip a topic whose key
  the message lacks, or whose key is a sentinel that would collapse SOW
  records onto one. A sink that keys nothing can declare the audit topics
  `[]` and drop the guards. The blotter stays `[13011]`, because the record is
  always keyed by it.

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

## 5. Which tags the topics and the record carry

The machine owns the order's state: 11, 41, 37, 38, 44, 60, 9000, 39, 150,
17, 14, 151, 6, the fill detail 32/31/30, 13011 and the 12xxx/15xxx fields.
Every other tag -- account, symbol, side, text, the upstream system's 9xxx --
is configuration, with `fix42-publisher`'s rulebook keys on the same
`routes`:

| key | acts on | what it does |
| --- | --- | --- |
| `msg-types`, `exec-types`, `exec-trans-types` | matching | 35, 150 and 20, first match wins. An empty `exec-types` or `exec-trans-types` takes any value; an absent tag reads as `""`. A `35=8` can be split per 150 as before |
| `tags`, `changeable-tags` | the route's `topics` | only these tags, in rule order, plus the 13011 trailer, as a `delta_publish` -- the old DELTA route. Both empty sends the message whole as a `publish`, the default. Every topic's key must be among them and 13011 must not |
| `projection.tags` | the blotter record | copied verbatim from the message. Non-empty **replaces** the machine's default pass-through for the route's messages (the D's descriptive tags, the `order-term-tags` of a `150=0/5/D`): the record gets exactly these beside the machine's fields. Empty keeps the default |
| `projection.copy-tags` | the blotter record | `source: destination`, the source's value under the destination's number |
| `projection.set-tags` | the blotter record | `tag: literal`; never empty, since a delta can overwrite a field but not remove one |

The precedence is the old planner's: a literal beats a copy, a copy beats the
verbatim field, and each tag is written once. There is one projected
destination, `order-state.topic`, so there is no `projected-topics`; and no
`mode`, because the tags imply it.

```yaml
routes:
  - name: new-order
    msg-types: ["D"]
    topics: ["sow/fix42/orders_audit"]          # whole: no tags listed
    projection:
      tags: [1, 54, 55, 40, 59, 9500]           # the record's terms: these, nothing else from the D
  - name: amend
    msg-types: ["G"]
    tags: [35, 11, 41, 60]
    changeable-tags: [38, 44, 59]               # orders_audit gets a selection, as a delta
    topics: ["sow/fix42/orders_audit"]
    projection:
      tags: [58]
      copy-tags: { "59": 9059 }                 # the proposed TimeInForce, beside the acked 59
  - name: exec-fill
    msg-types: ["8"]
    exec-types: ["1", "2"]
    topics: ["sow/fix42/execs", "sow/fix42/execs_audit"]
    projection:
      tags: [58, 9500]
      set-tags: { "9600": "FILL" }
  - name: execution-report                       # every other 150, the machine's default record
    msg-types: ["8"]
    topics: ["sow/fix42/execs", "sow/fix42/execs_audit"]
```

The test suite's [`projection-routes.yml`](../src/test/resources/projection-routes.yml)
is a complete worked set.

**What a projection cannot do**, each for the reason the module exists:

- **Write a field the machine owns**, the list above, or anything in
  12000-12999, 13000-13999 or 15000-15999. `validate()` refuses to boot.
  A projected 39 or 151 is last-writer-wins again, which is the two-cancels
  bug ([01](01-problem-the-two-cancels.md)); the machine publishes each of
  them whenever it changes. To keep a message's own value of one, copy it
  under another tag: `copy-tags: { "39": 9039 }`.
- **Write a tag `order-state.strip-tags` names.** Refused too: one list says
  copy, the other says never.
- **Apply to a message the machine ignored** -- a duplicate, a stale report,
  a late request. Its tags change nothing on the record, exactly as its
  quantities change nothing.
- **Write an `order-term-tags` tag from a message whose terms the machine
  does not adopt.** Terms come from a D (or a chain's first message) and from
  a `150=0/5/D` that is not a pending acknowledgement. A G's or F's 59 is a
  proposal, and a `150=A/6/E` echoes one; merged onto the record, it could
  never be taken back if the venue rejects the request. The test above lists a
  verbatim 59 on the amend route to pin that it is not written; `copy-tags`
  to a non-term tag is how a proposal reaches the record, as 38/44 reach
  15038/15044.

**What a projected tag is**, on the record:

- the value from the **last applied message** of a route that projects it;
  the machine's guards still decide which messages apply;
- **not in the cache**. A delta AMPS refuses is repaired from the cache
  ([04 §6](04-recovery-and-operations.md#6-the-publish-path)), so a projected
  tag that only the refused delta carried is missing until the next message
  that carries it (a refused D is re-sent whole and loses nothing). The
  default pass-through has always been in the same position;
- **never read back**: recovery, the miss lookup and the hooks see the
  machine's fields only, so a projection cannot change what the machine does;
- **permanent until overwritten**, as every delta-published field is.

**Porting a `fix42-publisher` route**: drop from `projection.tags` everything
the machine owns -- normally all of `[35, 11, 41, 39, 150, 60, 38, 14, 151, 6,
32, 31, 30]` -- and the `set-tags` of 9010-9014 (the pending family is the
machine's 15xxx now, [02 §4.1](02-architecture.md#41-several-requests-in-flight-tag-12015));
drop `projected-topics` and `mode`; keep `tags`, `changeable-tags`,
`msg-types`, `exec-types` and `exec-trans-types` as they are. What is left
in the projection is what the old blotter carried and the machine does not
compute: those tags then reach the record as before.

## 6. Where it lands in the plan

Tier one is phase 2 (the policies are constructor options of the machine)
and phase 3 (the yml binding and `VenueDialect`). Tier two is phase 3: the
SPI, the Spring collection of hook beans, the fail-fast semantics, and one
shipped example hook (the derived desk status above) with a test that the
machine's own fields cannot be written through `RecordFields`. Tier three is a
property of the design rather than a deliverable. The split of the core from
AMPS and Spring that §3.1 describes came later, and is recorded in
[11 §7](11-build-findings.md#7-the-core-as-a-library-amps-and-spring-behind-the-seams).
