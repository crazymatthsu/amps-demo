# 6. Case coverage

Every lifecycle and edge case the design was checked against, with the rule
that handles it and a verdict. The verdicts are design verdicts: each row is
to become a unit test in phase 2 of the [plan](05-implementation-plan.md), and
the rows marked **policy** are where a venue's dialect decides and a knob in
[02 §6](02-architecture.md#6-configuration) chooses.

Verdicts: **handled** — one outcome, no configuration; **policy** — handled,
with the default named and the alternatives a knob away; **limited** —
handled with a stated consequence; **out of scope** — deliberately not
addressed, with the reason.

Reading the record columns: 39 is the venue status, `pending` the set of
unanswered requests (tag 9015), the rest as [02 §4](02-architecture.md#4-the-record).

---

## 1. Normal lifecycles

| # | sequence | record at the end | rule | verdict |
| --- | --- | --- | --- | --- |
| N1 | D, ack, partial, full fill | `39=2 14=38 151=0 6=vwap 9017=Y pending={}` | [03 §6.4](03-state-machine.md#64-358-executionreport) | handled |
| N2 | D, ack, partial, F, `150=6`, `150=4` | `39=4 151=0 9017=Y pending={}`; 39 reads `6` between the pending ack and the confirm | 6.3, 6.4 | handled |
| N3 | D, ack, G, `150=E`, `150=5`, fills | 38/44 move only on the `150=5`; 9014 rotates to the G's id; 9010/9011 hold the proposal until then | 6.2, 6.4 | handled |
| N4 | D, ack, partial, G, `35=9 434=2` | 38/44 untouched, `pending={}`, 39 from the 9 if not terminal and not `8` | 6.5, [§4 policy](03-state-machine.md#4-status-venue-truth-and-one-policy-decision) | policy: `cancel-reject-status`, default `WHEN_NOT_TERMINAL` |
| N5 | D, ack, F, `35=9 434=1` (too late / unknown) | order keeps working: `pending={}`, 39 per policy | 6.5 | policy, as N4 |
| N6 | D, `150=8 39=8` | `39=8 151=0 9017=Y pending={}` | 6.4 answering NEW | handled |
| N7 | D, ack, partial, `150=3` | `39=3 151=0 9017=Y`; 14/6 as the fills left them | 6.4 | handled |
| N8 | GTD D, ack, partial, `150=C` | `39=C 151=0 9017=Y` | 6.4 | handled |
| N9 | D, ack, partial, `150=D 378=5` (OrderQty cut) | 38 restated, 151 recomputed by the venue and adopted; pending untouched | 6.4 | handled |
| N10 | D, ack, two partials, bust of the first (`20=1`) | 14/151/6/39 restated absolutes; no 32/31 change | 6.4; reopening exempt from guards | handled |
| N11 | D, ack, partial, correct of it (`20=2`) | restated 14/151/6 plus the correcting 32/31 | 6.4 | handled |
| N12 | child D with 9000, its reports, parent separate | two records; 9000 on the child's; views split on it | 3.2, 6.1 | handled |
| N13 | D, ack, `150=4` with no F (unsolicited cancel) | `39=4 151=0`; no pending match, nothing to remove | 6.4 | handled |
| N14 | D, `150=2 39=2` as the **first** report (IOC / market filled at once) | the fill answers the NEW because its 11 is the D's; `pending={} 9014=D's id` | 6.4 answering report, generalized | handled |
| N15 | D, `150=4 39=4` as the first report (IOC not filled) | answers the NEW; canceled | 6.4 | handled |
| N16 | D, `150=A`, `150=0` | `39=A` → `A` (venue's) → `0`; NEW pending until the `150=0` | 6.4 pending ack | handled |
| N17 | the ten `MockFixFlow` chains | each equals its `OrderChain`'s final state | all | handled; the phase 3 parity test |

## 2. Requests racing their answers

| # | sequence | record at the end | rule | verdict |
| --- | --- | --- | --- | --- |
| E1 | two F, confirm for #1, reject for #2 | `39=4 pending={}` | 6.4 match by 11; §4 terminal guard on the 9 | handled — [03 §7.1](03-state-machine.md#71-two-cancels-confirm-then-reject-the-motivating-case) |
| E2 | two F, reject for #2, confirm for #1 | same record as E1 | same | handled — §7.2 |
| E3 | F after `150=4`, then its 9 | `39=4` throughout; `pending` shows the request until the 9 | §4: requests never move 39 | handled — §7.3 |
| E4 | G then F in flight; 9 for the F; `150=5` for the G | the amend survives the cancel's reject and is adopted | 6.5 removes only its own; 6.4 | handled — §7.4 |
| E5 | two G in flight (G2's 41 = X-1 or = G1's id); venue answers each | each answered by its own 11; the second `150=5`'s 38/44 win, being later | 6.2, 6.4 | handled |
| E6 | `150=5` arrives **before** its G (two sessions merged on a tap) | the 5 resolves via 41, adopts terms, binds the G's id; the late G is already bound → ignored, nothing left pending | 3.1, 6.2 late-request rule | handled |
| E7 | F's `150=4` arrives before the F | as E6: canceled; the late F ignored | 6.2/6.3 | handled |
| E8 | `150=5` missed; a fill arrives under the amended id | the fill answers the REPLACE (its 11 is the G's), 38/44 adopted from it, 9014 rotates | 6.4 generalized answering | handled |
| E9 | `150=4` whose 11 is the **original** id, not the cancel's (venue echoes 41 as 11) | no match → every pending CANCEL removed; `39=4` | 6.4 no-match rule | handled |
| E10 | `150=5` whose 11 matches no pending amend | terms adopted (venue truth); pending untouched; if an amend was pending it stays until its own answer | 6.4 | limited: an unmatched confirm cannot know which amend it answers; `pendingOnTerminal` / `9016` surface the leftover |
| E11 | `35=9` for a request already answered, or never seen | `unmatchedRejects` += 1; nothing changes | 6.5 | handled |
| E12 | `35=9` with `37=NONE` | resolved via 11 → 41; 37 skipped | 3.1 | handled — §7.11 |
| E13 | G or F on a terminal order (amend after full fill) | joins pending; 39 stays; the venue's 9 clears it | §4 | handled |
| E14 | venue never answers a request | `9016 > 0` on a finished order; `pendingOnTerminal` | — | limited: no timeout by design; the filter `/9016 > 0 AND /9017 = 'Y'` is the operational answer |

## 3. Reports racing each other

| # | sequence | record at the end | rule | verdict |
| --- | --- | --- | --- | --- |
| E20 | cancel confirmed, then a fill with **higher** CumQty (a late execution) | 14/6/32/31/30 adopted; `39=4 151=0` kept; `9026=1` | §4 after-terminal table | **policy**: `late-fill-after-terminal`, default `APPLY_ECONOMICS`; `ADOPT_VENUE` reopens as the venue says; `IGNORE` drops — [03 §7.12](03-state-machine.md#712-cancel-confirmed-then-a-late-execution) |
| E21 | cancel confirmed, then the report of a fill it already counted (equal CumQty, new ExecID) | 17/150/32/31/30 adopted, nothing else | §4 detail-only row | handled |
| E22 | cancel confirmed, then an older report (lower CumQty) | ignored, `9025` | §5 stale | handled |
| E23 | cancel confirmed, then a `150=6` that overtook it | ignored (absorbed) | §4 | handled |
| E24 | full fill, then a bust of one fill | reopens: 39/14/151 as restated, `9017=N` | §4 reopening | handled — §7.9 |
| E25 | full fill, then a correct (price only) | 14 unchanged, 6 restated, `39=2` stays | §4 reopening | handled |
| E26 | canceled, then a bust of an earlier fill | 14/6 restated down; `39=4` (the venue's 39 on a bust mirrors the current status), `151=0` | §4 reopening | handled — §7.13 |
| E27 | canceled, then a correct | as E26 with the new 32/31 | §4 | handled — §7.13 |
| E28 | terminal, then `150=D` | applied as stated: a restatement is a reopening report | §4 | handled |
| E29 | `150=3` after `150=4` (or any terminal after terminal) | applied: the venue's business | §4 | handled |
| E30 | duplicate ExecID (PossDup resend) | ignored, `9025` | §5 | handled — §7.6 |
| E31 | an ExecID older than the ring (`exec-id-history`) resent | caught by the stale guard if its CumQty is lower; **applied as a late execution if equal** — no: equal CumQty with a new-to-the-ring ExecID on a *working* order is applied in full and its fields are the same values, so the record does not change | §5 | limited: a resend older than the ring on a working order costs a no-op delta; deepen the ring or set `exec-id-history` to the venue's resend window |
| E32 | fill while PENDING_CANCEL / PENDING_REPLACE | 39 follows the fill's 39; pending stays | 6.4 | handled (contract edge 10) |
| E33 | `20=3` status report | treated as `20=0`, subject to the same guards | §5 | handled |
| E34 | a report with `38 ≠ 14 + 151` | applied as sent; `inconsistentReports` += 1 | §5 | limited: the venue's numbers are adopted, the inconsistency is counted, not corrected |

## 4. Mid-stream, duplicates and identity

| # | sequence | record at the end | rule | verdict |
| --- | --- | --- | --- | --- |
| E40 | first message is a report | `9018=MIDSTREAM`, key = OrderID, terms as far as reports carry them | 3.2 | handled — §7.7 |
| E41 | the D arrives after reports | `9018=D_LATE`; descriptive terms filled; 38/44/39/14/151/6 untouched; no NEW pending | 6.1 | handled — §7.7 |
| E42 | the D is resent | ignored | 6.1 | handled |
| E43 | a report whose 11 and 41 are unknown but 37 is known (an intermediate replace missed) | resolved via 37; the new id bound | 3.1 | handled |
| E44 | the venue **rotates OrderID** across replaces | the new 37 is unknown, resolution falls to 11, finds the chain, binds the new 37 to it | 3.1, 3.3 | handled, though the contract lists it as a non-goal |
| E45 | a report for a child chain without tag 9000 | scope from the cache; routes to the child blotter under `{scope}` topics | [02 §3.1](02-architecture.md#31-one-entry-per-order) | handled — §7.10 |
| E46 | ClOrdID reused on a later day | a message resolves to yesterday's order | — | out of scope: the cache is session-scoped; a daily restart against a reset `order_state` SOW, or a date-prefixed key, is the deployment's answer |
| E47 | a message for an order evicted from the cache (late bust) | tombstone resolves 37 → key; entry recreated from the report; delta lands on the right record | [04 §4](04-recovery-and-operations.md#4-eviction) | handled |
| E48 | a late *request* under a new id for an evicted order | a second `MIDSTREAM` record | 04 §3 | limited, visible via `/9018 = 'MIDSTREAM'`; `miss-lookup` closes it |
| E49 | publisher restart mid-flow | cache rebuilt from the SOW; the feed's resend redelivers the gap; redeliveries are idempotent | [04 §2](04-recovery-and-operations.md#2-restart) | handled |
| E50 | `35=Q` DontKnowTrade | no route matches; the planner raises, as today's publisher does for an unrouted type | — | out of scope: a follow-up in [05](05-implementation-plan.md#follow-ups-deliberately-out-of-scope) |
| E51 | GTC order carried across a day boundary, `150=3` then working again | `150=3` is terminal here; the next day's reports arrive as late executions or reopenings | §4 | out of scope, as the contract: day-boundary resets are not modelled |
| E52 | multi-leg or list orders, allocations | — | — | out of scope, as the contract |

## 5. What the matrix changed in the design

Three rules in [03](03-state-machine.md) were tightened while filling this
matrix in, and are recorded here so the reasoning is not lost:

- **E20** showed the first draft's "terminal absorption" — drop every
  non-reopening report after a terminal status — would have dropped a real
  trade. It became the after-terminal table in 03 §4: late executions are
  applied by policy, equal-CumQty reports contribute their fill details,
  and only pending acknowledgements are absorbed.
- **N14** and **E8** showed that matching an answer by its ExecType *and* the
  request's action was too narrow: a fill can be the first report, and a fill
  under an amended id is proof the amend was accepted. The answering rule
  became "any non-pending-acknowledgement report answers the request its
  tag 11 names".
- **E6** and **E7** showed that deduplicating a request against the pending
  set alone would re-add a request the venue had already answered when the
  answer overtook it. Requests are now checked against the chain's bound ids.

## 6. Extension

Rows marked **policy** are decided by knobs; a venue whose behaviour matches
none of the options, or a deployment that needs a rule the matrix does not
have, uses the hooks in [07](07-extension-points.md) rather than a fork of the
machine.
