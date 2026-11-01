# 1. The problem: two cancels, one confirmed, one rejected

`fix42-publisher` is stateless on purpose. It reads one message, selects the
configured tags, stamps a few literals, and lets the AMPS chaining key
generator decide which record the tags belong to. That design was argued for
in [docs/fix42-view/04](../../docs/fix42-view/04-pending-state-without-a-state-machine.md),
built, and verified — and the same document ends by naming what it cannot do:
anything that needs **conditional** apply. This document is the write-up of
the first production-shaped case where that limit bites, and the reason this
module exists.

---

## 1. The scenario

A drop-copy feed shows both sides of the conversation. An order is working and
partially filled. The desk sends a cancel; before the venue answers, a second
cancel goes out for the same order (an impatient trader, a second OMS screen,
an automated retry — the cause does not matter, the feed is what it is).
The venue confirms **one** of them and rejects the **other**, in that order.

```
35=D  11=X-1                          new order, 1000 @ 50.00
35=8  150=0 39=0  11=X-1              acked
35=8  150=1 39=1  14=300 151=700      partial fill
35=F  11=X-2 41=X-1                   cancel request #1
35=F  11=X-3 41=X-1                   cancel request #2
35=8  150=4 39=4  11=X-2 41=X-1 151=0 cancel #1 CONFIRMED  -> the order is canceled
35=9  11=X-3 41=X-1 434=1 39=...      cancel #2 REJECTED   -> "already canceled"
```

The order is canceled. That is the fact a blotter has to show.

## 2. What the shipped rulebook does with it

Walking the seven messages through
[`application.yml`](../../fix42-publisher/src/main/resources/application.yml),
reading the `sow/fix42/orders` record after each one (only the fields that
matter here):

| after | route | record reads | right? |
| --- | --- | --- | --- |
| `35=D` | `new-order`, FULL | `35=D 11=X-1 38=1000 44=50 9013=NEW 9014=X-1` | yes |
| ack | `exec-new-ack` | `39=0 150=0 14=0 151=1000 9013=NONE 9014=X-1` | yes |
| partial | `exec-partial-fill` | `39=1 150=1 14=300 151=700` | yes |
| `35=F` X-2 | `cancel-request` | `35=F 11=X-2 41=X-1 9012=X-2 9013=CANCEL` | yes |
| `35=F` X-3 | `cancel-request` | `35=F 11=X-3 41=X-1 9012=X-3 9013=CANCEL` | **X-2 is forgotten.** 9012 holds one id |
| `150=4` for X-2 | `exec-cancel-or-done` | `39=4 150=4 151=0 9013=NONE 9012=NONE` | **says nothing is pending** while X-3 is still unanswered |
| `35=9` for X-3 | `cancel-reject` | `35=9 11=X-3 41=X-1 39=<as sent> 60=<newest> 9013=NONE` | **wrong.** The last message wins |

The final record's tag 35 is `9`, its tag 60 is the reject's, its tag 39 is
whatever the venue put on the reject. FIX 4.2 defines OrdStatus on a `35=9` as
"the order's status after this reject is applied", so a careful venue sends
`39=4` here — but many send the status the order had when the rejected request
was received (`39=6`, Pending Cancel), or `39=8`, which in some dialects means
"the request was rejected". Any of those, next to `35=9`, reads as **cancel
rejected** on a blotter, and the one piece of information that matters — the
venue confirmed a cancel four messages ago, `150=4` is still on the record to
prove it — has been overwritten by a message about a *different request*.

Reverse the last two messages (reject first, confirm second) and the record
ends correctly. The result depends on arrival order, which is the signature of
a last-writer-wins store being asked a question that needs a state machine.

## 3. Why no rulebook edit fixes it

Three ways to try, each of which the stateless design cannot express:

- **"Do not let a `35=9` write tag 39."** Then a *legitimate* reject — cancel
  refused, order still working — never restores the status the pending family
  obscured, and an amend reject that carries the venue's `39=1` loses that
  truth. The right answer is "write 39 unless the order is already terminal",
  and *already terminal* is a fact about the stored record, which the publisher
  never sees.
- **"Only clear the pending family if the reject's tag 11 is the pending
  ClOrdID."** The comparison is between a field on the message and a field on
  the record. A delta merge has no comparison; it has overwrite and
  leave-alone.
- **"Keep both requests in 9012."** One tag holds one value, and the cancel
  route cannot append to a value it cannot read. The pending family was always
  documented as holding **one** request
  ([04 §4](../../docs/fix42-view/04-pending-state-without-a-state-machine.md#4-what-this-does-and-does-not-close):
  "a G and an F in flight together still collapse to whichever arrived last").

Underneath all three: the decision needs the record's current state, and the
only component that has it is the AMPS server, which offers no
compare-and-swap on a SOW record and no conditional merge. The alternative
proposed in 04 §1 — query the record, decide, publish back — was measured and
rejected: a round-trip in the hot path, racing every fill that arrives in the
window, and it corrupted the record's `35`, `60` and `41` on the way.

So the state has to live where the decision is made. That is a cache in the
publisher.

## 4. The class of failure

The two cancels are the first case; they are not the only one. Everything in
this list is already recorded as "outside a merge's reach" in
[docs/fix42-view/02 §4.2](../../docs/fix42-view/02-amps-view-feasibility.md#42-what-the-chained-blotter-still-gets-wrong),
[04 §4](../../docs/fix42-view/04-pending-state-without-a-state-machine.md#4-what-this-does-and-does-not-close)
and [10](../../docs/fix42-view/10-expired-rejected-restated-reports.md#still-outside-a-merges-reach),
and every one is "apply this message **only if** something about the current
record holds":

| case | what the record must know | contract rule |
| --- | --- | --- |
| two requests in flight, answered separately (the case above; also G then F, 9 for one, 8 for the other) | the *set* of unanswered requests, by ClOrdID | §7 edge 4 |
| a request after the order is terminal (a third cancel arriving after `150=4`) | the order is terminal, so a request changes nothing but the pending set | §5 rule 7 |
| a `35=9` after a terminal report | terminal status is left only by a bust, correct or restatement | §5 rule 5, by implication |
| duplicate execution report (PossDup resend, same ExecID) | the ExecID was already applied | §5 rule 2, §7 edge 3 |
| stale report (lower CumQty, not a bust/correct) | the current CumQty | §5 rule 2, §7 edge 12 |
| execution report on a child chain without tag 9000 | the chain's scope, from its `35=D` | [publisher README](../../fix42-publisher/README.md#parent-and-child-orders) |
| `35=8` before `35=D` (mid-stream start), then the late D | the chain already exists; the D fills gaps, clobbers nothing | §7 edge 9 |
| late `35=D` duplicate (resent) | the chain exists; a FULL publish would erase every delta since | — |

[03](03-state-machine.md) walks each of these through the new machine and
shows the record after every message.

## 5. What the stateless design got right, and must survive

The cache is added to fix conditional apply. Everything else about
`fix42-publisher` is kept, deliberately, and the new module is judged against
it:

- **Full publish on `35=D`, delta publish for everything after.** The order's
  terms are written once and never re-sent; a fill is a handful of tags on the
  wire; delta subscribers see exactly what changed. The new module sends
  *smaller* deltas, because it knows what actually changed rather than what a
  route lists.
- **Acked terms and proposed terms in separate tags.** 38/44 are written only
  by the venue's own reports; the pending family holds an in-flight amend. The
  family keeps its meaning under the deployment's tag scheme — 15038, 15044,
  15011 and 15035 are the pending 38, 44, 11 and 35, where the old blotter
  used 9010–9013 — and the working ClOrdID, 9014 there, is 12014 here.
- **`151=0` stamped on every terminal projection**, so the exposure views
  over the blotter stop counting a cancelled, expired or rejected order
  ([10](../../docs/fix42-view/10-expired-rejected-restated-reports.md)).
- **The audit topics record the message as received**, journalled, and are the
  system of record. The blotter is derived and rebuildable. This module makes
  the audit copies *whole* messages rather than route-selected subsets, filed
  under the key the machine gave them (tag 13011 appended as a trailer), which
  turns the journal into a replay source for the machine and the audit topics
  into a per-order history ([04](04-recovery-and-operations.md)).
- **The venue's cumulative fields are adopted, never computed.** 14/151/6/39
  come from the report. A bust or correct restates them as absolutes and the
  machine copies them, which is why no per-fill arithmetic exists anywhere in
  it ([docs/src/fix-order-state.md](../../docs/src/fix-order-state.md)).
- **Configuration decides which tags leave the process** for the pass-through
  topics; the rulebook shape is kept. What moves into code is only what *was*
  code-shaped all along: the per-message `projection` blocks, which were a
  state machine written as tag rewrites.
- **The publish path is lean.** `fix42-publisher`'s path allocates nothing
  after warm-up. A cache keyed by strings cannot promise that, and does not;
  phase 5 of the [plan](05-implementation-plan.md) measures what it costs and
  bounds it.

One thing the stateless design did **not** get right is kept out: it
published through a plain AMPS client that reports no failure and replays
nothing after a disconnect, which is tolerable for a publisher whose record is
rebuilt from the next message and not for one whose record is the truth. This
module publishes through a client with a publish store
([04 §6](04-recovery-and-operations.md#6-the-publish-path)).

## 6. What this module is not

It is not the full [contract](../../docs/fix42/01-fix42-messages-and-state-machine.md)
state machine of [docs/fix42-view/03](../../docs/fix42-view/03-proposed-architecture.md):
no `executions` or `order_events` topics, no per-execution `FillStatus`
re-emit, no `35=Q`. Those are additive on top of the cache this module
introduces and are listed as follow-ups in [05](05-implementation-plan.md),
not as goals. The goal here is narrower: the same blotter as before, with the
order-level edge cases closed, and a cache small enough that the module is
still a publisher rather than an OMS.
