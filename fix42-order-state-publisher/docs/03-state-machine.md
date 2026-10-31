# 3. The state machine

Everything the publisher decides about a message, as rules, followed by the
cases from [01 §4](01-problem-the-two-cancels.md#4-the-class-of-failure) walked
through one message at a time. Each row of the walkthroughs becomes a unit
test in phase 2 of the [plan](05-implementation-plan.md); the machine is pure
(a `FixFrame` in, an outcome out) so none of them needs a server.

The rules follow the [contract](../../docs/fix42/01-fix42-messages-and-state-machine.md)
§3 and §5 where the contract speaks, and say so where they depart from it. The
departures are two, both simplifications: the status is never moved by a
request (§4 below), and duplicate detection is bounded (§5).

---

## 1. Vocabulary

| term | meaning |
| --- | --- |
| **OrderKey** | the SOW key of one order's record, tag 9020. Minted once, never changes |
| **chain** | every message that resolves to one OrderKey |
| **request** | a `35=D`, `35=G` or `35=F`: something the client asked for, identified by its own tag 11 |
| **report** | a `35=8`; a **reject** is a `35=9` |
| **pending set** | the requests of one order the venue has not answered yet |
| **venue status** | tag 39 as the venue last stated it; `A` (PendingNew) for a D nobody has answered |
| **terminal** | venue status ∈ {`2` Filled, `3` DoneForDay, `4` Canceled, `8` Rejected, `C` Expired} |
| **reopening report** | a report that may legitimately move a terminal order: a bust (`20=1`), a correct (`20=2`), or a restatement (`150=D`) |
| **answering report** | a report whose ExecType answers a request: `150=0` and `150=8` answer a NEW, `150=5` a REPLACE, `150=4` a CANCEL |

## 2. Outcomes

`OrderStateMachine.apply(FixFrame)` returns one of:

| outcome | meaning | what the publisher does |
| --- | --- | --- |
| `CREATED` | a new chain; the entry is complete | `publish` the whole record (D), or `delta_publish` the best-effort record (`MIDSTREAM`) |
| `CHANGED(dirty)` | an existing chain changed; `dirty` is the set of record fields touched | `delta_publish` those fields plus the last-message bookkeeping |
| `IGNORED(reason)` | duplicate, stale, absorbed, or a duplicate request | nothing to the blotter (the audit topics still get the message); `9025` incremented, published only with `publish-unchanged: true` |

Every outcome, including `IGNORED`, **binds the message's ids** (§3): a
duplicate still tells the index which chain its ids belong to.

## 3. Identity

### 3.1 Resolution

The contract's §3, with one addition for the value `NONE`:

```
resolve(msg):
  1. tag 37 present, not "NONE", indexed   -> its OrderKey
  2. tag 11 present, indexed               -> its OrderKey
  3. tag 41 present, indexed               -> its OrderKey
  4. otherwise                             -> unknown
```

OrderID first because it heals a chain whose intermediate replace was never
seen: the next report carries 37 and lands on the right record even though its
11 is new. `37=NONE` is what a FIX 4.2 cancel reject carries when the target
was never acked, and must not become a key.

### 3.2 Creation

| first message of the chain | OrderKey | `9018` | initial state |
| --- | --- | --- | --- |
| `35=D` | its tag 11 | `D` | terms from the D; venue status `A`; CumQty 0, LeavesQty = 38, AvgPx 0; pending `{11: NEW}` |
| anything else | 37 if present and not `NONE`, else 41 if present, else 11 | `MIDSTREAM` | whatever the message carries: 38/44, 39/150/14/151/6 from a report; pending `{11: REPLACE}` for a G, `{11: CANCEL}` for an F, empty for an 8 or 9 |

A D's own ClOrdID is the key because it exists before the venue has said
anything, and because the chaining module's deterministic key
([docs/fix42-view/05](../../docs/fix42-view/05-the-generated-sow-key.md)) was
likewise a function of the root. For a mid-stream chain the OrderID is the
most stable id available; 41 is preferred to 11 because on a G or F it is
closer to the root.

### 3.3 Binding

After every message, whatever the outcome: 11 → key, 41 → key, 37 → key
(unless `NONE`), each idempotent. A new 11 is also appended to the chain list
(tag 9021). The index therefore knows every id the order has ever used, which
is what lets a `35=9` for an old request, or a report under a rotated id,
find the record.

## 4. Status: venue truth, and one policy decision

**Tag 39 on the record is written by execution reports.** A request does not
move it; the pending set says a request is in flight. This departs from the
contract's rules 3–5, which set `OrdStatus = PENDING_REPLACE / PENDING_CANCEL`
on the request and revert from a per-request snapshot on the reject — and the
departure is the point:

- there is nothing to revert, so there are no snapshots, so two requests in
  flight need no special handling: each is an element of a set;
- the venue's own `150=6` / `150=E` pending acknowledgements *do* move 39 to
  `6` / `E`, so a consumer that wants "pending cancel" as a status still gets
  it — from the party that decides it;
- the only local status is `A` at creation from a D, because until the venue
  speaks there is no venue truth, and the contract's rule 1 agrees.

A consumer that wants the contract's blended status derives it from the record
in one expression: `39` if terminal, else the newest pending action's status
if `9016 > 0`, else `39`.

**What a `35=9`'s tag 39 may do** is the one policy decision, configured as
`cancel-reject-status`:

| policy | effect of the 9's tag 39 on the record | when to choose it |
| --- | --- | --- |
| `WHEN_NOT_TERMINAL` (default) | adopted unless the order is terminal or the value is `8` | the default venue: 39 on a 9 is the order's status after the reject, which is useful information when the order is still working and noise when it is already canceled; `8` on a 9 means "request rejected" in enough dialects that adopting it as *order* rejected is wrong more often than right |
| `NEVER` | ignored | a venue whose 9s carry the status *at the time the request was received* — stale by definition |
| `ALWAYS` | adopted as sent | a venue known to send the spec's meaning precisely, including `4` on a too-late-to-cancel |

[01 §2](01-problem-the-two-cancels.md#2-what-the-shipped-rulebook-does-with-it)
is the default policy's reason for existing: the terminal guard is what keeps
a canceled order canceled.

**Terminal absorption.** Once the venue status is terminal, the only reports
that change the record's status or economics are reopening reports (a bust,
a correct, a restatement) and reports whose own 39 is terminal (a `150=3`
after a `150=4` — the venue's business, adopted). Any other report — a late
fill, a late pending ack — is `IGNORED(absorbed)`. A request on a terminal
order joins the pending set (the venue will reject it, and the record should
show it is unanswered) and changes nothing else.

## 5. Arbitration on execution reports

Applied in this order, before any field is touched:

1. **Duplicate.** Tag 17 equals the last applied ExecID, or is in the
   per-order ring of the last `exec-id-history` ExecIDs → `IGNORED(duplicate)`.
   Bounded on purpose: a PossDup resend repeats the tail of the stream, and the
   stale guard below catches anything older. The contract's unbounded set is
   a listed option, not the default.
2. **Stale.** `stale-guard: true`, the report is not reopening, and its 14 is
   lower than the cached CumQty → `IGNORED(stale)`. CumQty is monotonic per
   chain in FIX 4.2 except across a bust or correct, which restate it
   explicitly and are exempt.
3. **Absorbed.** The order is terminal, the report is not reopening, and its
   39 is not terminal → `IGNORED(absorbed)` (§4).

A report that passes all three is applied in full. A report with
`20=3` (status) is treated as `20=0`: it restates the current state and is
subject to the same guards.

## 6. Transition rules

### 6.1 `35=D` NewOrderSingle

| situation | effect |
| --- | --- |
| chain unknown | **create** (§3.2). `CREATED` → FULL publish: the D's fields less tag 35, plus every state tag with its initial value (`39=A`, `9013=NEW`, `9012=`11, `9010=0`, `9011=0`, `9014=`11, `9015=`11`:NEW`, `9016=1`, `9017=N`, `9018=D`, `9020`, `9021`, `9022=D`, `9023=1`, `9025=0`, `14=0`, `151=`38, `6=0`) |
| chain exists, origin `MIDSTREAM` (a late D) | the D's **descriptive** terms are applied (everything but 35, 11, 41, 60, 38, 44 and the state tags); 38/44 only if no report has supplied them yet; `9018=D_LATE`; no pending entry is added when the venue status is already past `A`. `CHANGED` |
| chain exists, origin `D` or `D_LATE` (a resent D) | `IGNORED(duplicate request)` — a FULL publish here would erase every delta since the original |

### 6.2 `35=G` OrderCancelReplaceRequest

| step | effect |
| --- | --- |
| resolve via 41, then 37, then 11; unknown → create `MIDSTREAM` keyed per §3.2 | |
| 11 already in the pending set | `IGNORED(duplicate request)` |
| otherwise add `{11: REPLACE, orig=41, qty=38, px=44}` to the set; bind 11 | `CHANGED`: 11, 41, 60, 9021, 9010 (=38), 9011 (=44), 9012 (=11), 9013=`REPLACE`, 9015, 9016, 9022, 9023 |
| 38/44 on the record | **untouched** — they are the acked terms |
| 39 | untouched |

### 6.3 `35=F` OrderCancelRequest

As 6.2 with action `CANCEL`, `9010=0`, `9011=0`.

### 6.4 `35=8` ExecutionReport

After §5's arbitration:

| field | rule |
| --- | --- |
| 39 (venue status), 150, 17 | adopted from the report, always |
| 14, 151, 6 | adopted from the report; **151 forced to `0`** when the report's 39 is terminal, whether or not the venue sent 151 and whatever it sent |
| 38, 44 | adopted when the report carries them and `150 ∉ {A, 6, E}` — pending acknowledgements may echo proposed terms, and change nothing yet |
| 59 | adopted on `150=5` and `150=D` when present |
| 32, 31, 30 | adopted on fills (`150 ∈ {1, 2}`, `20 ∈ {0, 3}`) and corrects (`20=2`); never on a bust (`20=1`), which reports no trade |
| 37 | adopted on the first report that carries it; bound |
| 9014 (working id) | set to the report's 11 on `150=0` and `150=5` |
| 9017 | `Y` if the new 39 is terminal, else `N` — a bust can take it back to `N` |
| pending set | an **answering report** removes the request whose ClOrdID equals the report's tag 11 **and** whose action it answers (`0`/`8` → NEW, `5` → REPLACE, `4` → CANCEL). No match (an unsolicited cancel, a mid-stream start) removes nothing. `150=3`, `C`, `D`, `A`, `6`, `E` and busts/corrects touch the set not at all |
| 9010–9013, 9015, 9016 | recomputed from the set when it changed |

Matching by the report's **own** tag 11 is what distinguishes the two cancels:
the confirm names X-2, so X-3 stays in the set; the reject then names X-3.
Removing "the pending cancel" without looking at 11 would have taken X-3 out
on the confirm and left the later reject unmatched — harmless to the status,
but the window between would have read `9013=NONE` with a request still
outstanding.

### 6.5 `35=9` OrderCancelReject

| step | effect |
| --- | --- |
| resolve via 11 (the rejected request's id, bound when the request was seen), then 41, then 37 (skip `NONE`); unknown → create `MIDSTREAM` with ids only | |
| remove the pending request whose ClOrdID equals tag 11, whatever its action. Cross-check 434 (`1` → CANCEL, `2` → REPLACE) and log a warning on a mismatch | 9010–9013, 9015, 9016 recomputed |
| no such request (a reject for a request this publisher never saw, or already answered) | counted (`unmatchedRejects`), nothing removed |
| 39 | per `cancel-reject-status` (§4) |
| 38, 44 | **untouched** — nothing to revert, because a G never wrote them |
| 102, 58 | not on the record; the reject goes whole to `sow/fix42/rejects` |

`CHANGED`: 11, 41, 60, 9022, 9023, the pending family, and 39/9017 if the
policy moved them.

### 6.6 Every message

Bind ids (§3.3); `9023` += 1 on an applied message, `9025` += 1 on an ignored
one; 11, 41, 60 and `9022` on the record become the applied message's.

## 7. Walkthroughs

Each table is the `sow/fix42/order_state` record after the message, showing
only the fields that move. `pending` is the set; the `9013/9012` columns are
what a consumer of the old blotter reads. "wire" is what the delta carries
beyond the always-sent bookkeeping (11, 41, 60, 9022, 9023).

### 7.1 Two cancels: confirm, then reject (the motivating case)

Order `X`, 1000 @ 50.00, partially filled 300.

| message | pending | 39 | 151 | 9017 | 9013 | 9012 | 9016 | wire |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `D 11=X-1` | {X-1:NEW} | A | 1000 | N | NEW | X-1 | 1 | **publish**, whole record |
| `8 150=0 39=0 11=X-1 37=ORD` | {} | 0 | 1000 | N | NONE | NONE | 0 | 37 17 150 39 38 44 14 151 6 9014=X-1 9010–9016 |
| `8 150=1 39=1 14=300 151=700` | {} | 1 | 700 | N | NONE | NONE | 0 | 17 150 39 38 14 151 6 32 31 30 |
| `F 11=X-2 41=X-1` | {X-2:CANCEL} | 1 | 700 | N | CANCEL | X-2 | 1 | 9021 9010=0 9011=0 9012 9013 9015=`X-2:CANCEL` 9016 |
| `F 11=X-3 41=X-1` | {X-2, X-3} | 1 | 700 | N | CANCEL | X-3 | 2 | 9021 9012 9015=`X-2:CANCEL;X-3:CANCEL` 9016 |
| `8 150=4 39=4 11=X-2 41=X-1` | {X-3} | **4** | **0** | **Y** | CANCEL | X-3 | 1 | 17 150 39 14 151=0 6 9017 9012 9015=`X-3:CANCEL` 9016 |
| `9 11=X-3 41=X-1 434=1 39=6` | {} | **4** | 0 | Y | **NONE** | NONE | 0 | 9010–9016 (39 untouched: terminal) |

Final: `39=4 150=4 151=0 9017=Y 9013=NONE 9016=0 9022=9`. Canceled, nothing
pending, and the record says the last thing that happened was a reject —
which is true. Compare [01 §2](01-problem-the-two-cancels.md#2-what-the-shipped-rulebook-does-with-it).

### 7.2 Two cancels: reject, then confirm

Same order, the venue answers in the other sequence.

| message | pending | 39 | 151 | 9017 | 9013 | 9012 | note |
| --- | --- | --- | --- | --- | --- | --- | --- |
| … as 7.1 through the second F | {X-2, X-3} | 1 | 700 | N | CANCEL | X-3 | |
| `9 11=X-3 434=1 39=6` | {X-2} | 6 | 700 | N | CANCEL | X-2 | 39=6 adopted: not terminal, not 8. 9012 falls back to the remaining request |
| `8 150=4 39=4 11=X-2` | {} | 4 | 0 | Y | NONE | NONE | |

Both arrival orders converge on the same record. That is the property the
stateless design lacked.

### 7.3 A third cancel after the order is canceled

| message | pending | 39 | 9017 | 9013 | note |
| --- | --- | --- | --- | --- | --- |
| … 7.1 complete | {} | 4 | Y | NONE | |
| `F 11=X-4 41=X-1` | {X-4:CANCEL} | 4 | Y | CANCEL | status untouched: a request never moves 39 |
| `9 11=X-4 434=1 39=4` | {} | 4 | Y | NONE | |

The old blotter would have shown `9013=CANCEL` on the terminal order too, but
also `35=F`; here the record says canceled, with an unanswered request, then
canceled.

### 7.4 Amend and cancel both in flight, reject for the cancel (contract edge 4)

| message | pending | 39 | 38 | 44 | 9013 | 9012 | 9010 | 9011 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `D 11=A-1 38=10000 44=185.5` + ack | {} | 0 | 10000 | 185.5 | NONE | NONE | 0 | 0 |
| `G 11=A-2 41=A-1 38=12000 44=185.75` | {A-2:REPLACE} | 0 | 10000 | 185.5 | REPLACE | A-2 | 12000 | 185.75 |
| `F 11=A-3 41=A-2` | {A-2, A-3} | 0 | 10000 | 185.5 | CANCEL | A-3 | 0 | 0 |
| `9 11=A-3 434=1 39=0` | {A-2} | 0 | 10000 | 185.5 | **REPLACE** | **A-2** | **12000** | **185.75** |
| `8 150=5 39=0 11=A-2 38=12000 44=185.75` | {} | 0 | **12000** | **185.75** | NONE | NONE | 0 | 0 |

The reject removed only its own request; the amend's proposal survived it and
the confirm adopted the venue's numbers. 9014 moves to `A-2` on the confirm.
The old blotter's single 9012 would have held `A-3` after the F and `NONE`
after the 9, forgetting the amend.

### 7.5 Amend rejected (the NVDA scenario)

| message | pending | 39 | 38 | 44 | 9013 | 9010 | 9011 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D 4000 @ 121.80, ack, partial 1000 | {} | 1 | 4000 | 121.8 | NONE | 0 | 0 |
| `G 11=N-2 41=N-1 38=6000 44=122.1` | {N-2:REPLACE} | 1 | 4000 | 121.8 | REPLACE | 6000 | 122.1 |
| `9 11=N-2 434=2 39=1` | {} | 1 | **4000** | **121.8** | NONE | 0 | 0 |

Identical to the old blotter's verified result
([04 §2](../../docs/fix42-view/04-pending-state-without-a-state-machine.md#measured-result)):
the acked terms were never touched, so nothing is reverted.

### 7.6 Duplicate and stale reports

| message | outcome | 14 | 9023 | 9025 |
| --- | --- | --- | --- | --- |
| `8 150=1 17=E-3 14=800` | applied | 800 | 3 | 0 |
| `8 150=1 17=E-3 14=800` (PossDup resend) | `IGNORED(duplicate)` | 800 | 3 | 1 |
| `8 150=1 17=E-2 14=500` (an old report redelivered) | `IGNORED(stale)` | 800 | 3 | 2 |
| `8 20=1 19=E-3 17=E-4 150=1 39=1 14=500 151=…` (a bust of E-3) | applied — reopening reports are exempt from the stale guard | 500 | 4 | 2 |

### 7.7 Report before request (mid-stream start), then the late D

| message | key | 9018 | 39 | 38 | 1/55/54 | 21/40/59/100 | pending |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `8 150=0 39=0 11=M-1 37=ORD-M 38=500 44=10 1=ACC 55=SYM 54=1` | **ORD-M** | MIDSTREAM | 0 | 500 | from the 8 | absent | {} |
| `D 11=M-1 38=500 44=10 21=1 40=2 59=0 100=XNAS …` | ORD-M | D_LATE | 0 | 500 | unchanged | **from the D** | {} — the venue already acked, so no NEW is pending |

The chain is keyed by the OrderID because that was the best id available when
it opened; the D joins it through 11. The D's 38/44 are not applied (a report
has already stated them) and its arrival is a delta, not a FULL publish.

### 7.8 Rejected before ever working

| message | pending | 39 | 151 | 9017 |
| --- | --- | --- | --- | --- |
| `D 11=R-1 38=200` | {R-1:NEW} | A | 200 | N |
| `8 150=8 39=8 11=R-1 103=1 151=0` | {} | 8 | 0 | Y |

`150=8` answers the NEW; 103/58 are on `sow/fix42/execs_audit`, not the record.

### 7.9 Bust after a full fill reopens the order (contract edge 6)

| message | 39 | 14 | 151 | 9017 |
| --- | --- | --- | --- | --- |
| `8 150=2 39=2 14=3000 151=0` | 2 | 3000 | 0 | Y |
| `8 20=1 19=… 150=1 39=1 14=1800 151=1200` | **1** | 1800 | 1200 | **N** |

A bust is a reopening report, so terminal absorption does not apply; the
venue's restated absolutes are adopted, exactly as the old blotter did
([docs/fix42-view/07](../../docs/fix42-view/07-trade-busts-and-corrects.md)).

### 7.10 A report on a child chain without tag 9000

With the shipped topics nothing changes: parents and children share the
blotter and the record still carries 9000 from the D. With `sow/{scope}/…`
topics, the old publisher needed every venue message to echo 9000 to pick the
topic; here `OrderState.parentOrderId` supplies it, so the report routes to
the child blotter whether or not the venue echoed the tag.

### 7.11 A cancel reject with `37=NONE`

`9 11=U-2 41=U-1 37=NONE 434=1` for an order whose D was never acked:
resolution skips 37, finds U-2 in the index (bound when the F was seen), removes
the request. Venue status stays `A`; the NEW request is still in the set.

## 8. What the machine does not do

- It does not compute a quantity. Every 14/151/6/38/44 on the record is a
  value the venue sent; the one write of the machine's own is `151=0` on a
  terminal report.
- It does not reorder. A session's order is trusted; the guards in §5 make
  out-of-order *redelivery* harmless, they do not repair a feed that
  interleaves two sessions for one order.
- It does not time anything out. A request the venue never answers stays in
  the pending set until the order is evicted ([04 §4](04-recovery-and-operations.md#4-eviction));
  the `9016 > 0 AND 9017 = 'Y'` filter on the blotter finds them, which is the
  operational answer rather than a guess at a timeout.
- It does not handle `35=Q`, nor publish per-execution dispositions or
  lifecycle events. Those are the contract's §6 outputs and are follow-ups in
  [05](05-implementation-plan.md), additive on this cache.
