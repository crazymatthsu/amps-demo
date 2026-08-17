# FIX 4.2 order state in AMPS

The question this document answers:

> I send FIX 4.2 messages — 35=D (new order), 35=G (cancel/replace), 35=F
> (cancel), 35=8 (execution report), 35=9 (cancel reject) — into AMPS. Can AMPS
> derive the latest order state: OrdStatus, CumQty, LeavesQty, the latest
> acknowledged quantity and price, pending quantity and pending changes, and the
> average execution price? Or do I need to build the order state machine outside
> AMPS?

The short answer: **you need a state machine outside AMPS, but a far thinner one
than the question implies — because FIX 4.2 already ran most of it for you.**

## The insight that changes the size of the problem

A FIX 4.2 execution report is not an increment. It is a **cumulative state
snapshot**, computed by the venue's own matching engine and carried on every
35=8:

| you asked for | FIX tag | on every 35=8? |
| --- | --- | --- |
| current order status | 39 `OrdStatus` | yes |
| CumQty | 14 `CumQty` | yes |
| LeavesQty | 151 `LeavesQty` | yes |
| average price of executions | 6 `AvgPx` | yes — venue-computed |
| latest acknowledged order qty | 38 `OrderQty` | yes (as of that report) |
| order price | 44 `Price` | yes |
| pending order qty / pending changes | — | **no — this is yours** |

So for one order, "current state" is very nearly **"the latest authoritative
execution report"**. Nothing needs to sum fills: AvgPx arrives on tag 6, CumQty
on tag 14, and both already reflect execution corrections and busts
(ExecTransType 20=1/2 in 4.2), which per-fill aggregation would otherwise have
to handle itself. The counterparty is the state machine of record; your side
mostly needs to *file the reports correctly*.

That reframing is what makes the AMPS split clean.

## What AMPS gives you with no code

**Last-report-per-order, queryable.** A SOW topic keyed on the order identifier
keeps exactly the latest record per key, so `sow` / `sow_and_subscribe` answer
"current state of every order" the moment the config declares the topic. All the
machinery in this repo — content filters, atomic snapshot+live, OOF, deltas,
expiration on terminal orders — applies.

**Native FIX parsing, if you want it.** AMPS speaks `fix` and `nvfix` message
types, so it can key and filter on tag values directly; you do not have to
convert to JSON. (This repo normalizes to JSON at the gateway instead, which
keeps every demo and doc here applicable and makes the records readable — check
the User Guide for your version for the exact key/filter syntax on FIX tags if
you prefer raw FIX in the SOW.)

**Aggregation, within limits.** AMPS Views can compute grouped aggregates over a
SOW topic server-side — a per-order `SUM(LastShares)`, a
`SUM(LastPx*LastShares)/SUM(LastShares)` style AvgPx from per-fill records. If
your counterparty did *not* populate tag 6 reliably, a view is a legitimate way
to compute it — but verify the view/aggregation syntax against your AMPS
version, and remember the correction caveat: a view summing raw fills is wrong
the moment a bust or correction arrives unless the underlying records are
maintained accordingly. Trusting the report's own tag 6 avoids that class of
problem entirely.

## What AMPS cannot express — the actual state machine

Three things, and they are all identity-and-sequence problems, not quantity
math:

**1. Chain identity across cancel/replace.** A 35=G moves the order to a new
ClOrdID (11), linked backwards by OrigClOrdID (41). A SOW keyed on `/clOrdId`
fragments one order across records as it is replaced; there is no way to tell a
SOW key to follow a moving identifier, and no view computes the transitive
closure of 41→11 chains. Something must remember "B1 continues A1" and keep the
record under one stable key. (Keying on OrderID (37) helps only if your venue
keeps 37 stable across replaces and you never need pre-ack state — and you still
cannot key the events that precede the first ack.)

**2. Pending-request bookkeeping.** "Acknowledged at 500, replace to 800 in
flight" requires correlating an outstanding request (35=G/F — different message
types from the reports) with its eventual ack (39=5 / 39=4) or reject (35=9),
and clearing state on whichever arrives. That is sequential, conditional logic
across message types; SOW merge and views are declarative over current records
and cannot express it. FIX helps once more here: a 35=9 carries OrdStatus (39)
as a required tag, so the reject *tells you* what the order still is — there is
nothing to remember and revert.

**3. Arbitration.** Resends with PossDup (43=Y) carry the same ExecID (17) and
must not double-apply; recovery can deliver reports out of order, and a stale
report must not regress the record. CumQty is monotonic per chain in 4.2, which
gives a cheap staleness test — but *applying* that test is per-event conditional
logic, which is again not a merge.

Everything else the question lists falls out of tags on the latest report.

## The shape that works

```
FIX session ──> gateway (thin state machine) ──┬──> fix.events  SOW /eventId, journalled
                                               └──> fix.orders  SOW /chainId, not journalled
                                                          │
                                            consumers: sow_and_subscribe, filters,
                                            OOF, deltas — no FIX knowledge needed
```

- **`fix.events`** — every message, wire-faithful, one record per event
  (keying on the unique event id turns the last-value store into a keyed event
  log). The gateway stamps each event with the resolved chain id, so one
  order's full history is a filtered query. Journalled: this stream is the
  system of record.
- **`fix.orders`** — one consumer-friendly record per order *chain*, produced by
  the state machine. Keyed on the chain id that never moves. Deliberately **not**
  journalled: it is derivable from `fix.events` by replay, so journalling it
  would record the same information twice — the same reasoning as everywhere
  else in [transaction-log-sizing.md](transaction-log-sizing.md).

Recovery composes accordingly: `fix.orders` is a persistent SOW, so current
state survives a restart on its own; and if it were ever lost or the machine's
rules change, a fresh machine replays `fix.events` from the journal and
republishes.

This split is implemented in this repo:

| piece | where |
| --- | --- |
| normalized event + derived state schemas | [`fix_order.proto`](../../common/src/main/proto/com/demo/amps/market/v1/fix_order.proto) |
| the state machine (~150 lines of logic) | [`FixOrderStateMachine`](../../common/src/main/java/com/demo/amps/common/fix/FixOrderStateMachine.java) |
| the lifecycle, event by event, as tests | [`FixOrderStateMachineTest`](../../common/src/test/java/com/demo/amps/common/fix/FixOrderStateMachineTest.java) |
| end-to-end against the server | `fix-lifecycle` demo ([`FixLifecycleDemo`](../../clients/src/main/java/com/demo/amps/clients/demos/FixLifecycleDemo.java)) |
| topic declarations | [`amps-config.xml`](../../server/config/amps-config.xml) |

The machine's whole rulebook: **D** opens a chain as pending-new. **G/F** record
a pending request and alias the new ClOrdID into the chain. **8** is arbitrated
(duplicate ExecID, CumQty regression), then its cumulative tags are copied onto
the record; an ack (39=0/5) updates the acknowledged terms, moves the working
ClOrdID and clears its pending; terminal statuses clear any pending. **9** clears
the pending and takes the surviving status from the reject's own tag 39. Run
`./gradlew :clients:run --args="fix-lifecycle"` to watch a scripted
new→ack→fill→replace→fill-while-pending→replace-ack→cancel-reject→resend→filled
sequence flow through it into the two topics.

## Deliberate boundaries

Honest list of what the shipped machine does **not** handle, so nobody mistakes
a demo for an OMS: multi-leg and list orders; allocations; GTC orders across day
boundaries; `Done for Day` nuances; persistent ExecID dedup across gateway
restarts (the in-memory set is rebuilt by replaying `fix.events`, which works
precisely because that topic is journalled); status-request reconciliation
(35=H); and venue quirks where OrderID (37) changes across replaces. Each is an
extension of the same pattern, not a different architecture.

Also worth stating: if you have a **drop-copy** feed rather than your own order
flow, the machine still works — a chain can be created from a report alone,
since reports are complete snapshots — but pending-request fields will only be
as good as the requests you actually see.

## So: inside or outside?

| responsibility | lives in | why |
| --- | --- | --- |
| OrdStatus, CumQty, LeavesQty, AvgPx, acked qty/price | the venue's 35=8 | FIX 4.2 reports are cumulative snapshots |
| keeping latest-per-order, snapshots, filtered live views, durability | AMPS | that is literally what a SOW is |
| chain identity, pending requests, duplicate/stale arbitration | a thin gateway you write | sequential, conditional, identity-tracking logic — outside AMPS's declarative model |
| AvgPx if your venue's tag 6 is unreliable | AMPS view (verify syntax), or the gateway | aggregation is expressible; corrections are the trap |

You do build a state machine outside AMPS — but it is identifier bookkeeping
measured in a couple hundred lines, not a reimplementation of order math, and
AMPS turns its output into the queryable, subscribable, durable order book every
downstream consumer actually wanted.
