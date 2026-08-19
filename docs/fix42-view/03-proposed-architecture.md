# Proposed architecture: the FIX 4.2 state machine outside AMPS

[02-amps-view-feasibility.md](02-amps-view-feasibility.md) established that no
view derives the required order state. This document proposes the architecture
that follows — and the headline is how little "outside AMPS" means. The state
machine is identifier bookkeeping and report filing, specified completely by
the docs/fix42 contract
([01-fix42-messages-and-state-machine.md](../fix42/01-fix42-messages-and-state-machine.md));
AMPS keeps every other responsibility: durable ordered ingress, the replay
substrate, the queryable/subscribable state store, deltas, filters, OOF, and
the aggregation layer above the derived state.

| responsibility | lives in | why |
| --- | --- | --- |
| CumQty, AvgPx, LeavesQty, OrdStatus-on-reports, acked terms | the venue's 35=8 | FIX 4.2 reports are cumulative snapshots — nothing to compute |
| ordered durable ingress, replay from any point | AMPS transaction log | one journal, total order across the `algo/*` family |
| chain identity, pending correlation, dedupe/stale arbitration, bust/correct filing, status sequencing | **the state machine (this proposal)** | the behaviours no view expresses — chain identity alone is optionally delegable to the chaining key generator module (§5) |
| latest-per-order queryable state, snapshot+live, deltas, OOF, TTL | AMPS SOW (`algo/orders` etc.) | that is what a SOW is |
| exposure/count aggregations, blotter filters | AMPS views + filtered subscriptions **over the derived topics** | keys stable, no sequence logic left |

## 1. Topology

```
 FIX 4.2 sessions / drop-copy tap
        │  gateway: shred leniently (ChecksumOk, never reject), stamp nothing,
        │  publish per-session in wire order over ONE connection
        ▼
  algo/D  algo/G  algo/F  algo/8  algo/9  algo/Q        MessageType fix
  ──────────────── journalled: THE SYSTEM OF RECORD ────────────────
        │
        │  bookmark subscription, topic regex ^algo/(D|G|F|8|9|Q)$
        │  (delivery in journal order = publish order; resumable)
        ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  fix42 state machine — one process, single-threaded core     │
  │  contract §3: OrderKey resolution (key_by_order_id/clordid/  │
  │               execid maps, idempotent binding)               │
  │  contract §5: pending bookkeeping, staged G terms,           │
  │               per-request prior_status, ExecID dedupe,       │
  │               stale guard, bust/correct, revert-on-9         │
  └──────────────────────────────────────────────────────────────┘
        │  publishes JSON snapshots after every applied message
        ├─►  algo/orders        SOW key /OrderKey    contract §4 OrderState
        ├─►  algo/executions    SOW key /ExecID      contract §6 executions
        └─►  algo/order_events  SOW key /EventId     contract §6 order_events
             (all three derivable ⇒ deliberately NOT journalled)
                    │
        consumers: sow / sow_and_subscribe (+ content filters, OOF),
        delta_subscribe, admin SQL console; views for aggregation
        (exposure by account/symbol, counts by status — see 02 §6)

  optional, beside the machine (§5):
  gateway also delta-publishes every message into
  algo/chain — SOW, KeyGenerator key-chaining (/11, /41):
  the raw chained blotter: latest venue truth per chain, zero code
```

The same split as this repo's shipped `fix.events`/`fix.orders` pattern
([fix-order-state.md](../src/fix-order-state.md)), scaled up to the full
contract: ingress topics play the journalled system-of-record role, the three
derived topics are pure functions of them.

## 2. The derived topics, mapped to the contract

All three are `MessageType json` (protobuf-schema'd per this repo's
conventions — readability, nested delta merge, unambiguous numerics for
filters/views, admin console). None is journalled: each is derivable from the
`algo/*` journal by replay, so journalling would record the same information
twice ([transaction-log-sizing.md](../src/transaction-log-sizing.md)).

**`algo/orders`** — one record per order chain, key `/OrderKey`, exactly the §4
OrderState row. The SOW *is* the contract's "`last_by(OrderKey)`" (where the
contract's Deephaven consumer keeps latest-per-key itself, AMPS holds that
role server-side — same core, different sink). Answering the fields the
question asked for by name:

| asked for | OrderState column(s) |
| --- | --- |
| order status | `OrdStatus` (+ `Terminal`, `LastExecType`) |
| CumQty / AvgPrice | `CumQty`, `AvgPx` (+ `LeavesQty`) |
| acked qty / acked price | `OrderQty`, `Price` — current *applied* terms; a G's terms land here only on its confirming 8 (rule 2, `150=5`) |
| pending qty change / pending price change | `PendingAction` + `PendingClOrdID` say *that* and *which*; the staged 38/44 values are held by the machine but **not published in §4 as written** — see the contract delta below |

> **Proposed contract extension (the one addition this analysis needs):** two
> columns in §4, `PendingOrderQty` and `PendingPrice` — the staged terms of the
> in-flight G (empty/NaN when `PendingAction ≠ REPLACE`), populated by rule 3
> alongside staging and cleared by rules 2/5. Without them, "pending qty
> change" is answerable only by joining back to `algo/G`; with them, the
> OrderState row alone answers every field in the question. Cheap, and worth
> doing in the contract rather than ad hoc.

**`algo/executions`** — key `/ExecID`, the §6 executions row. The contract's
re-emit rule does the heavy lifting: a bust/correct/DK re-publishes the
*referenced* exec's row with its new `FillStatus`, so last-value-per-ExecID —
which is what a SOW does — always shows each execution's current disposition.
A filtered `sow_and_subscribe` (`/OrderKey = '…'`, or `/IsFill = true`) is the
executions blotter.

**`algo/order_events`** — key `/EventId` (unique per event, minted by the
machine), the §6 lifecycle rows. Keying on a unique id turns the last-value
store into a queryable event log — the `fix.events` trick. One order's history
is `sow` with `/OrderKey = '…'` ordered by `IngestTs`. This topic is also where
every ask in the question's scenario list lands as an explicit row:
`NEW_REQUEST/NEW_ACK/NEW_REJECT`, `AMEND_REQUEST/AMEND_ACK/AMEND_REJECT`,
`CANCEL_REQUEST/CANCEL_ACK/CANCEL_REJECT`, `PENDING_*`,
`PARTIAL_FILL/FULL_FILL`, `FILL_BUST/FILL_CORRECT`, `DK_TRADE` — the §6
derivation table covers the full list.

Scenario coverage against the question, by contract rule: new
pending/ack/reject → rules 1–2 (`PENDING_NEW` → `150=0` ack / `150=8`
terminal reject); amend pending/ack/reject → rules 3, 2, 5 (staged terms,
`150=5` applies + rotates ClOrdID, `9/434=2` reverts to the per-request
snapshot); cancel pending/ack/reject → rules 4, 2, 5; fill bust/amend →
rule 2's 20=1/20=2 branches (adopt restated absolutes — including the
reopen-after-FILLED case, edge case 6); in-flight collisions → edge cases 4
and 10. Nothing in the question falls outside the contract as written except
the two published columns above.

## 3. Recovery, restarts, and rule changes

The machine is in-memory and deterministic; the journal makes that a feature.

- **Cold start / crash:** bookmark-subscribe the `algo/*` regex from `EPOCH`
  with republish to the derived SOWs. Determinism plus SOW upsert makes the
  republish idempotent — replaying twice converges (contract §3: binding is
  idempotent; replays converge). Derived-topic subscribers are insulated by
  the SOW: re-published identical records are suppressed for
  delta-subscribers and harmless for the rest.
- **Fast resume** (attractive at scale): persist the last-processed bookmark
  and resume from it. Honesty about what that requires: the §4 row does not
  carry the machine's full internal state. The seen-ExecID set *is*
  recoverable (it is the key set of `algo/executions` — one SOW query), and
  the id maps are recoverable from `ClOrdIDChain`/`OrderID` on `algo/orders`
  rows — but the per-request `prior_status` snapshots and staged G terms are
  not published anywhere (`PendingOrderQty`/`PendingPrice` above fixes half of
  that). Either publish a small machine-checkpoint record alongside, or don't
  bother: at order-flow volumes (thousands to low millions of messages/day,
  nothing like market data) **replay-from-epoch is the recommended default** —
  simplest, provably convergent, and it doubles as the deployment path for
  rule changes: stand up a machine with the new rules, replay, cut over.
- **Journal retention bounds replay depth.** Ageing out `algo/*` journal files
  ([the bounded-retention flow](../../server/config/flows/bounded-retention/amps-config.xml)
  pattern) trades replay horizon for disk; if the feed is session-scoped
  (daily), retention past a few sessions buys little. Decide retention and the
  recovery mode together.

**HA:** exactly one machine instance publishes (a dual-active pair would
interleave republishes and fight over SOW records). Active/passive with the
passive running the same subscription but publishing nowhere, promoted by
external leader election, is the simple shape; failover cost is a resume or
replay, both covered above. AMPS itself gets the standard replication story
independently of this design.

## 4. Consumer surface — what downstream gets for free

Everything this repo demonstrates applies to the derived topics with no FIX
knowledge in any consumer: atomic snapshot+live (`sow_and_subscribe` — no gap,
no dupes); content-filtered order books (`/Account = 'ACC1' AND /Terminal =
false`) with **OOF** notifying when an order leaves the filter (an order going
terminal literally falls out of an open-orders blotter); `delta_subscribe` for
changed-fields-only updates (an OrderState row is wide; a fill touches ~6
fields); optional `<Expiration>` on terminal rows if the SOW should stay
bounded intraday; the admin SQL console for eyeballing state; and the §6
aggregation views from
[02-amps-view-feasibility.md](02-amps-view-feasibility.md) — the one place a
view belongs in this design.

`delta_publish` from the machine is worth using for `algo/orders` updates
(less wire, and delta-subscribers then receive exactly the changed fields),
with the usual trap list in [delta-updates.md](../src/delta-updates.md).

## 5. Where the chaining key generator fits

The optional `key-chaining` module — analysed in depth in
[02-amps-view-feasibility.md](02-amps-view-feasibility.md) §4 — can play three
roles in this architecture, in increasing order of commitment:

**(a) The raw chained blotter, beside the machine — recommended.** A seventh
topic (`algo/chain`, keys `/11` + `/41`) that the gateway delta-publishes every
message into. Zero code, and it earns its config twice: as a
monitoring/debugging surface ("show me the raw venue truth for this chain,
unfiltered by our rules"), and as a **bring-up consistency check** — the
machine's `algo/orders` venue-sourced fields (39/14/151/6) must agree with the
chained record for every quiet chain, and a diff between the two topics is a
cheap continuous audit of both the machine and the feed.

**(b) Identity delegation to the module.** In principle the machine could
subscribe to the chained topic and adopt the server-assigned SowKey as its
`OrderKey`, dropping the `key_by_order_id`/`key_by_clordid` maps of contract
§3. Declined as the default, for three reasons: the machine must consume from
the plain journalled topics anyway (the module *drops* a message that resolves
to two chains — acceptable for a blotter, not for the system of record);
SowKey visibility on live deliveries and especially on bookmark **replay** is
unverified, and replay is the recovery path; and the maps are a few dozen
fully unit-tested lines whose ownership keeps the machine deterministic and
self-contained. Revisit only if id-map memory ever becomes a measured cost.

**(c) The module instead of the machine.** Legitimate when monitoring-grade
state is enough — no pending windows, no staged-terms correctness, trust the
feed order. That option, its exact field-by-field quality, and its
disqualifiers for this contract are
[02-amps-view-feasibility.md](02-amps-view-feasibility.md) §§4.1–4.2 and §7.

## 6. Relationship to what this repo already ships

The shipped
[FixOrderStateMachine](../../common/src/main/java/com/demo/amps/common/fix/FixOrderStateMachine.java)
(~150 lines of logic, exercised by the `fix-lifecycle` demo) is this
architecture at demo scale and proves the shape end-to-end. Implementing the
full contract on top of it is additive, not architectural. The gap list, for
whenever implementation is green-lit:

- 35=Q DontKnowTrade handling (rule 6) and `algo/Q` ingress;
- bust/correct filing (rule 2's 20=1/2 branches) and the `FillStatus`
  re-emit on `algo/executions`;
- per-request `prior_status` snapshots (the shipped machine reverts from the
  9's own tag 39 — the contract keeps both: snapshot, venue 39 wins if
  present);
- the `executions` and `order_events` output rows (§6) as first-class topics;
- `ClOrdIDChain` / `RootClOrdID` bookkeeping and the §4 audit columns;
- the two proposed columns `PendingOrderQty` / `PendingPrice`;
- native-`fix` ingress parsing at the subscription boundary (the shipped demo
  publishes normalized JSON events; here the gateway forwards raw FIX and the
  machine shreds it — `FixWire`'s shredder already does the parsing half).

Deliberate non-goals inherited from both the contract and
[fix-order-state.md](../src/fix-order-state.md): multi-leg/list orders,
allocations, GTC day boundaries, 35=H reconciliation, venues that rotate
tag 37 across replaces.

## 7. Verify on your build

The checklist from
[01-ingress-fix42-into-amps.md](01-ingress-fix42-into-amps.md) §4 (fix-type
key syntax, regex bookmark subscriptions, text-valued fix fields, slashes in
topic names, and — if `algo/chain` is adopted — the chaining module's
presence, load, missing-primary and two-chains behaviour), plus, for this
document: view/aggregation syntax for the §4 aggregations if you adopt them,
and delta semantics on wide JSON rows against
[delta-updates.md](../src/delta-updates.md)'s trap list. `./server/scripts/amps.sh validate`
remains the one-second first check for any config change.
