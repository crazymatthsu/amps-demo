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
        │  raw messages, in session order, one thread
        ▼
 ┌─────────────────────────────────────────────────────────────────────────┐
 │ fix42-order-state-publisher                                             │
 │                                                                         │
 │   FixFrame ──► AuditRouter ─────────────────────────► publish (whole)   │
 │       │          per route: which pass-through topics  sow/fix42/orders_audit
 │       │                                                sow/fix42/execs_audit
 │       │                                                sow/fix42/execs
 │       │                                                sow/fix42/rejects
 │       ▼                                                                 │
 │   OrderStateMachine                                                     │
 │     OrderKeyIndex   11/41/37 ──► OrderKey                               │
 │     OrderState      one small entry per order (§3)                      │
 │     rules           docs/03: identity, pending set, arbitration, apply  │
 │       │                                                                 │
 │       ▼  Outcome: CREATED (full record) | CHANGED (dirty fields) | IGNORED
 │   RecordWriter ──► publish        (35=D opens a chain)    sow/fix42/order_state
 │                ──► delta_publish  (everything else)       key /9020 OrderKey
 │                                                                         │
 │   on start: SowCacheLoader  sow sow/fix42/order_state ──► rebuild cache │
 └─────────────────────────────────────────────────────────────────────────┘
        │
        ▼  AMPS, flow fix42-order-state (no modules)
   sow/fix42/order_state          plain SOW, Key /9020          the blotter
   sow/fix42/orders_audit  /11    journalled                    system of record
   sow/fix42/execs_audit   /17    journalled                    system of record
   sow/fix42/rejects       /11    journalled                    system of record
   sow/fix42/execs         /37    latest report per order       convenience
   view/fix42/exposure/*          json aggregations over order_state, unchanged shape
   view/fix42/recon/parent_vs_child
```

Compared with the `fix42-chaining` flow: `sow/fix42/orders` (chained) becomes
`sow/fix42/order_state` (plain key), the `<Modules>` block is gone, and the
four views point at the new topic. The audit and exec topics keep their names
and keys, so the `amps-cli` commands and the admin console queries in the
existing READMEs still work.

## 2. Components

| component | responsibility | state | where it comes from |
| --- | --- | --- | --- |
| `FixFrame` / `FixWriter` / `FixMessage` / `FixTags` / `Prices` | parse, index and render FIX; the tag vocabulary | none | copied from `fix42-publisher` into this module's package in phase 0; `FixTags` gains the 9015–9025 family |
| `OrderChain` / `MockFixFlow` / `Instrument` / `FixEvent` / `OrderScope` | the deterministic mock venue | per chain, test only | copied in phase 0; extended in phase 1 with multi-request scenarios |
| `AuditRouter` | which pass-through topics a message goes to, whole | none | new, a simplified `PublishPlanner`: no projection, FULL by default |
| `OrderKeyIndex` | every ClOrdID and OrderID seen → OrderKey | one map entry per id | new |
| `OrderState` | the cache entry (§3) | one per order | new |
| `PendingRequest` set | the in-flight D/G/F requests of one order | inside `OrderState` | new |
| `OrderStateMachine` | resolve, arbitrate, apply; report what changed | owns the two maps | new, rules in [03](03-state-machine.md) |
| `RecordWriter` | encode an `OrderState` as FIX tags: whole, or only the dirty fields | per-thread buffer | new |
| `OrderStatePublisher` | `send(FixFrame)`: router + machine + writer → AMPS client; counters | per-thread scratch | new, the shape of `AmpsDeltaPublisher` |
| `SowCacheLoader` | on start, rebuild the two maps from `sow/fix42/order_state` | none | new, [04](04-recovery-and-operations.md) |
| `OrderStateProperties` | the yml surface (§6) | none | new, the shape of `Fix42Properties` |
| `Fix42OrderStatePublisherApplication` + `MockFlowRunner` | the Spring Boot shell; publishes the scripted flow and exits | none | copied |

The split that matters for testing is the same as before: everything up to
`RecordWriter` is pure and runs without a server; only `OrderStatePublisher`
and `SowCacheLoader` touch an AMPS client.

## 3. The cache

### 3.1 One entry per order

The entry holds what conditional apply needs and nothing a subscriber could
get from the record instead. From the requirement: the id fields 11, 41, 37;
the order quantity and price from D/G/F; the confirmed status, CumQty and
AvgPx from `35=8`. Plus the few fields without which those cannot be kept
right:

| field | from | why it is in the cache |
| --- | --- | --- |
| `orderKey` | minted at creation (§4 of [03](03-state-machine.md)) | the SOW key; every publish needs it |
| `clOrdIds` | every 11 bound to this order, oldest first | published as 9021; lets the index be rebuilt from the record |
| `workingClOrdId` | 11 of the last `150=0` / `150=5` | 9014 |
| `orderId` | 37, first report | binds later reports that carry no known 11 |
| `parentOrderId` | 9000 on the D | routes a report that lacks 9000 to the right scoped topic |
| `ackedQty`, `ackedPx` | 38/44 from the D, then from the venue's reports only | "working at what" |
| `venueStatus` | 39 from the last applied `35=8`; `A` until the first | terminal absorption, and the 35=9 policy |
| `lastExecType` | 150 | diagnostics; published as 150 |
| `cumQty`, `leavesQty`, `avgPx` | 14/151/6 | the stale guard, and 151 forced to 0 on terminal |
| `lastExecId` (+ a short ring, default 8) | 17 | duplicate detection |
| `pending` | the set of unanswered D/G/F: `{clOrdId, action, origClOrdId, proposedQty, proposedPx}` | the whole point |
| `msgCount`, `ignoredCount` | bookkeeping | 9023 / 9025; a cheap liveness and health signal per order |
| `origin` | `D` or `MIDSTREAM` | whether the record's terms came from a D or were inferred from a report |

LeavesQty is on the list although the requirement did not name it: it is on
every report, it is the field the exposure views sum, and the machine has to
force it to zero on a terminal report whether or not the venue sent it.

### 3.2 What is deliberately not in it

- **The order's descriptive terms** (account, symbol, side, type, TIF,
  destination, …). They are on the record from the FULL publish of the D and
  nothing in the machine conditions on them. Scope (9000) is the one exception,
  because routing needs it when the venue does not echo it.
- **Every ExecID ever seen.** The contract's dedupe set grows with every fill.
  A PossDup resend repeats the *last* report or a short run of them, so a ring
  of the last N ExecIDs (configurable, default 8) catches the real-world case;
  the CumQty stale guard catches an older report regardless of its ExecID.
- **Per-request prior-status snapshots.** The contract reverts status on a 9
  from a snapshot taken when the request was sent. This machine does not move
  the status on a request at all (venue truth only, §4 of
  [03](03-state-machine.md)), so there is nothing to revert and nothing to
  snapshot. That removes the hardest bookkeeping from the cache and is what
  makes two concurrent requests trivial rather than special.
- **The last fill's 31/32/30.** They go straight to the record on each fill
  and nothing conditions on them.
- **A history.** The audit topics are the history.

### 3.3 Size

Per order, on a 64-bit JVM with compressed oops: the entry object with its
primitives and references ≈ 130 B; four or five short id strings ≈ 250 B; the
`clOrdIds` list with two or three entries ≈ 120 B; the pending set, allocated
lazily and empty most of the time ≈ 0–120 B; index entries at ≈ 40 B per bound
id (the strings are shared with the entry). Call it **0.6–0.8 KB per order
with two or three ClOrdIDs**. A desk doing 100,000 orders a day holds ~70 MB
with nothing evicted; a million-order day wants the terminal-eviction knob
(§6, [04 §4](04-recovery-and-operations.md#4-eviction)) or a bigger heap. The
figure is an estimate to be measured in phase 5, not a promise.

## 4. The record

`sow/fix42/order_state`, `MessageType fix`, one record per order, keyed by
tag 9020. Every field below is written by the machine; the record is the
machine's state made visible, which is also what makes it a sufficient
checkpoint ([04 §1](04-recovery-and-operations.md#1-the-sow-record-is-the-checkpoint)).

| tag | name | written when | value |
| --- | --- | --- | --- |
| **9020** | OrderKey | creation | the SOW key: root ClOrdID of a D-opened chain, else OrderID |
| 9021 | ClOrdIDChain | each new 11 bound | comma-joined, oldest first |
| 9014 | WorkingClOrdID | `150=0`, `150=5` | the id the venue currently recognises |
| 11, 41 | ClOrdID, OrigClOrdID | every applied message | ids of the *last applied* message — not the chain's identity, which is 9020/9021 |
| 37 | OrderID | first report, then every report | |
| 60 | TransactTime | every applied message | the last applied message's |
| 9022 | LastMsgType | every applied message | D/G/F/8/9 — the record itself carries **no tag 35** |
| 9023 | MsgCount | every applied message | messages applied to this order |
| 9025 | IgnoredCount | every ignored message | duplicates, stale and terminal-absorbed reports |
| 1, 55, 54, 40, 59, 15, 100, 21, 109, 110, 111, 47, 48, 22, 126 … | order terms | the D's FULL publish; 59 again on `150=5` | the D's whole message less 35 |
| 38, 44 | OrderQty, Price | the D; then venue reports only (`150=0`, `150=5`, `150=D`, fills, bust/correct) | **acked** terms |
| 39 | OrdStatus | `35=8` always; `35=9` per policy; `A` stamped at creation | venue truth |
| 150 | ExecType | every applied `35=8` | what the last report was |
| 17 | ExecID | every applied `35=8` | the last applied report |
| 14, 151, 6 | CumQty, LeavesQty, AvgPx | every applied `35=8`; 151 **forced to 0** when 39 is terminal | venue absolutes |
| 32, 31, 30 | LastShares, LastPx, LastMkt | fills and corrects | the most recent fill |
| 9010, 9011 | PendingOrderQty, PendingPrice | pending set changes | the **newest** in-flight REPLACE's terms, else 0 |
| 9012 | PendingClOrdID | pending set changes | the newest in-flight request's id, else `NONE` |
| 9013 | PendingAction | pending set changes | `NONE` / `NEW` / `REPLACE` / `CANCEL` of the newest in-flight request |
| 9015 | PendingRequests | pending set changes | every in-flight request, `id:ACTION[:qty:px]` joined by `;`, or `NONE` |
| 9016 | PendingCount | pending set changes | size of the set |
| 9017 | Terminal | 39 changes | `Y` when 39 ∈ {2, 3, 4, 8, C}, else `N` |
| 9018 | ChainOrigin | creation | `D` or `MIDSTREAM` |
| 9000 | ParentOrderID | the D | child slices only, as today |

Three deliberate choices in that table:

**The record has no tag 35.** On the chained blotter tag 35 was the last
message's type, and a record reading `35=9` was half of the problem in
[01](01-problem-the-two-cancels.md). 9022 carries the same information under
a name that says what it is. (`FixMessage.msgType()` on a record returns the
empty string; nothing in the publisher reads it.)

**Tag 39 is the venue's.** It is written by execution reports, by a `35=9`
only under the policy in [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision),
and by nothing else. A request never moves it; the pending family says a
request is in flight. This is the same split the old blotter drew, kept, with
the terminal rule added.

**9010–9014 keep their old meaning**, summarising the *newest* in-flight
request, so a consumer written against `fix42-publisher`'s blotter needs no
change beyond the key field. 9015/9016 are the full set for a consumer that
wants it.

### 4.1 Several requests in flight: tag 9015

The pending set is one tag holding every unanswered request, and 9010–9013
are a summary of its newest member. Collected here because the question
"what if more than one request is in flight?" is the question this module
exists to answer.

**Encoding.** One element per request, `clOrdId:ACTION[:qty:px]`, joined by
`;`, **oldest first**. A REPLACE carries its proposed terms; a NEW or a CANCEL
has none. The empty set is the literal `NONE`, not an absent or empty tag: a
delta publish can overwrite a field but never remove it, so the cleared
state must be a written value. 9016 is the element count.

After an amend and then a cancel on the same order
([03 §7.4](03-state-machine.md#74-amend-and-cancel-both-in-flight-reject-for-the-cancel-contract-edge-4)):

```
9015=A-2:REPLACE:12000:185.75;A-3:CANCEL
9016=2
9013=CANCEL  9012=A-3  9010=0  9011=0      <- the newest member, a cancel
```

When the `35=9` for A-3 arrives it is removed and the summary falls back to
what remains:

```
9015=A-2:REPLACE:12000:185.75
9016=1
9013=REPLACE  9012=A-2  9010=12000  9011=185.75
```

**A set, not a stack.** Each answering message removes the request whose
ClOrdID equals its own tag 11: a `150=4` names the cancel it confirmed, a
`35=9` the request it rejected, a `150=5` the amend it accepted
([03 §6.4–6.5](03-state-machine.md#64-358-executionreport)). Answers may
therefore arrive in any order, which is what makes confirm-then-reject and
reject-then-confirm converge on the same record. A request the venue never
answers stays in the set; the filter `/9016 > 0 AND /9017 = 'Y'` finds such
orders.

**Size.** An array list inside the cache entry, allocated on the first
request after the D's own NEW is answered. In practice it holds zero to two
elements, three in the amend-plus-two-cancels case. There is no fixed upper
bound; a venue that leaves many requests unanswered grows the tag, and the
`pendingOnTerminal` counter ([04 §6](04-recovery-and-operations.md#6-observability))
is the signal.

**Recovery.** 9015 is parsed back into the set verbatim on a restart
([04 §1](04-recovery-and-operations.md#1-the-sow-record-is-the-checkpoint)),
so no in-flight request is lost. This is the state the contract's
per-request status snapshots would have made unrecoverable, and the reason
[03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision)
keeps none.

**Consumer caveat.** 9010–9013 describe the newest request only, kept so a
reader of the old blotter needs no code change. A consumer that cares about
more than one in-flight request must read 9015.

## 5. Publish modes

| message | to the blotter | why |
| --- | --- | --- |
| `35=D` for an unknown chain | **`publish`** of the whole record: the D's fields less 35, plus every state tag of §4 with its initial value | creates the record; every later delta relies on the terms being there |
| any other message that changes the state | **`delta_publish`** of the **dirty fields only**: whatever the machine changed, plus the last-message bookkeeping (11, 41, 60, 9022, 9023) | the merge preserves everything else — **verified** by `Fix42DeltaPublishIT` |
| a non-D for an unknown chain (mid-stream start) | `delta_publish` of the best-effort record, `9018=MIDSTREAM` | if a record exists from before a cache eviction the merge enriches it; if none exists AMPS stores the delta as the record — **verify on build**, see [04 §3](04-recovery-and-operations.md#3-cache-misses) |
| a `35=D` for a chain that already exists (late D after a mid-stream 8, or a resent D) | `delta_publish` of the D's fields **that the record lacks** (terms), never 38/44/39/14/151/6 | a FULL publish would erase every delta applied since |
| a message the machine ignores (duplicate, stale, absorbed) | nothing, unless `publish-unchanged: true`, then a delta of 9023/9025/60 | the audit topics have it; the blotter did not change |

"Dirty fields only" is computed, not configured: `OrderState` keeps a bitmask
of the fields each apply touched, and `RecordWriter` walks the mask. A fill
therefore puts roughly a dozen tags on the wire (ids, time, 17/150/39,
38/14/151/6, 32/31/30, 9023) and a pending ack (`150=6`) five. Smaller than
the old routes, which listed every tag a message *might* change.

The pass-through topics receive the message **whole** (`publish`), per the
`routes` in §6. That changes the audit topics from route-selected subsets to
the message as received, which is what an audit trail should be and what lets
[04 §2](04-recovery-and-operations.md#2-restart) replay them.

## 6. Configuration

Bound to `OrderStateProperties`, validated at startup the way `Fix42Properties`
is (a route naming a topic with no `topic-keys` entry, or whose selection
omits the key, refuses to boot).

```yaml
fix42-order-state:
  amps:
    uri: tcp://127.0.0.1:9007/amps/fix       # must end /fix, as before
    client-name: fix42-order-state-publisher
    timeout-ms: 10000

  order-state:
    topic: sow/fix42/order_state
    key-tag: 9020
    # What a 35=9's tag 39 may do to the record: WHEN_NOT_TERMINAL (default),
    # NEVER, ALWAYS. docs/03 §4.
    cancel-reject-status: WHEN_NOT_TERMINAL
    # Depth of the per-order ExecID ring used for duplicate detection; 0 = last only.
    exec-id-history: 8
    # Drop a 20=0 report whose CumQty is below the cached one. docs/03 §5.
    stale-guard: true
    # Evict terminal orders from the cache after this long; PT0S = never.
    terminal-retention: PT0S
    # Rebuild the cache at startup from the SOW record: SOW (default) or NONE.
    recovery: SOW
    # Publish a 9023/9025/60 delta for messages the machine ignored.
    publish-unchanged: false

  topic-keys:
    "[sow/fix42/order_state]": [9020]
    "[sow/fix42/orders_audit]": [11]
    "[sow/fix42/execs]": [37]
    "[sow/fix42/execs_audit]": [17]
    "[sow/fix42/rejects]": [11]

  # Pass-through routing: matched in order, first match wins, as before. A
  # route sends the message WHOLE unless it lists tags. There is no
  # projection block any more: the blotter is written by the state machine,
  # and `apply-state` (default true) says whether this route feeds it.
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
    - name: cancel-reject
      msg-types: ["9"]
      topics: ["sow/fix42/rejects"]
```

What left the yml, and why: the `projection` blocks (they *were* the state
machine), the per-`150` split of the `35=8` routes (the machine reads 150 and
20 itself; a deployment that wants different *pass-through* tags per exec type
can still split the route), and `changeable-tags` for the blotter (the machine
stages 38/44 from a G and adopts 38/44/59 from the confirming report; making
the staged set configurable is a listed follow-up). The audit topics' `mode`
defaults to FULL; `tags:` on a route turns it back into a selected delta for a
deployment that wants the old wire shape.

## 7. Threading and ordering

The machine is **single-threaded per order**, and in the shipped shape
single-threaded full stop: one FIX session delivers in order, one thread calls
`OrderStatePublisher.send`, and the AMPS client's publish path buffers
asynchronously behind it. `OrderState` and the index are not synchronised. A
deployment with several sessions runs one publisher per session when orders
never cross sessions (the drop-copy norm), or partitions by OrderKey ahead of
the machine when they do — the key is derivable from the message's 11/41/37
without the cache only for the first message of a chain, so partitioning has
to be by session or by a stable upstream id, not by ClOrdID.

Ordering is assumed within a session and not across sessions. A report that
arrives before its request (possible on a drop-copy tap that merges two
sessions) is handled as a mid-stream chain and the late request merges into
it; the machine does not reorder.

## 8. What changes for a consumer of the old blotter

| on `sow/fix42/orders` | on `sow/fix42/order_state` |
| --- | --- |
| key: the chaining module's generated key, read with `getSowKey()`, opaque | key: tag 9020, a readable id, also in the payload |
| tag 35 = last message's type | no tag 35; 9022 |
| tag 39 = venue's last status, but **absent** until the first report and **untouched** by pending acks (6/E stayed off the blotter) | 39 = `A` from creation; follows every report including 6/E/A; never written by a request; a `35=9` writes it only when the order is not terminal |
| 9010–9014: one in-flight request | same tags, same meaning for the **newest** request; 9015/9016 for the set |
| a request on a terminal order sets 9013 | the same, and 39 stays terminal |
| duplicate or stale reports regress the record | ignored; 9025 counts them |
| `sow/fix42/orders_audit` holds the tags each route selected | holds the message as received |
| the `{scope}` placeholder needs 9000 on every venue message | 9000 remembered from the D; a report without it still routes |
| exposure and recon views over `sow/fix42/orders` | the same four views, declared over `sow/fix42/order_state`; same fields, same filters (`/9000 IS NULL`) |
