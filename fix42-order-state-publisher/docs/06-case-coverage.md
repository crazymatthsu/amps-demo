# 6. Case coverage

Every lifecycle and edge case the design was checked against, with the rule
that handles it and a verdict. The verdicts are design verdicts: each row is
to become a unit test in phase 2 of the [plan](05-implementation-plan.md),
named by its row id, and the rows marked **policy** are where a venue's
dialect decides and a knob in [02 §6](02-architecture.md#6-configuration)
chooses.

This is the third version of the matrix. The first was reviewed in
[08](08-review-findings.md) and fourteen of its verdicts were disputed; the
second in [09](09-review-round-2.md). A row whose text changed in a revision
is marked **(revised)** with the finding that changed it; rows added for a
review's cases are marked **(new)**; a disputed row whose text did not need to
change is left unmarked.

Verdicts: **handled** — one outcome, no configuration; **policy** — handled,
with the default named and the alternatives a knob away; **limited** —
handled with a stated consequence; **out of scope** — deliberately not
addressed, with the reason.

Reading the record columns: 39 is the venue status, `pending` the set of
unanswered requests (tag 12015), the rest as [02 §4](02-architecture.md#4-the-record).
[10](10-case-frequency-and-priority.md) sorts every row here by how often a
drop copy produces it and by the build slot it belongs in.

---

## 1. Normal lifecycles

| # | sequence | record at the end | rule | verdict |
| --- | --- | --- | --- | --- |
| N1 | D, ack, partial, full fill | `39=2 14=38 151=0 6=vwap 12017=Y pending={}` | [03 §6.4](03-state-machine.md#64-358-executionreport) | handled |
| N2 | D, ack, partial, F, `150=6`, `150=4` | `39=4 151=0 12017=Y pending={}`; 39 reads `6` between the pending ack and the confirm | 6.3, 6.4 | handled |
| N3 | D, ack, G, `150=E`, `150=5`, fills | 38/44 move only on the `150=5`; 12014 rotates to the G's id; 15038/15044 hold the proposal until then | 6.2, 6.4 | handled |
| N4 | D, ack, partial, G, `35=9 434=2` | 38/44 untouched, `pending={}`, 39 from the 9 if not terminal, not `8`, not a pending status with nothing pending; a terminal 39 forces `151=0` | 6.5, [§4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision) | policy: `cancel-reject-status`, default `WHEN_NOT_TERMINAL` **(revised, R6)** |
| N5 | D, ack, F, `35=9 434=1` (too late / unknown) | order keeps working: `pending={}`, 39 per policy; `39=6` on the 9 is **not** adopted once no cancel is pending | 6.5 | policy, as N4 **(revised, R6)** |
| N6 | D, `150=8 39=8` | `39=8 151=0 12017=Y pending={}` | 6.4: `150=8` names the NEW | handled |
| N7 | D, ack, partial, `150=3` | `39=3 151=0 12017=Y`; 14/6 as the fills left them | 6.4 | handled |
| N8 | GTD D, ack, partial, `150=C` | `39=C 151=0 12017=Y` | 6.4 | handled |
| N9 | D, ack, partial, `150=D 378=5` (OrderQty cut) | 38 restated, 151 adopted; pending untouched | 6.4 | handled; dialect note: a venue that answers a TIF-only amend with `150=D` leaves that REPLACE pending — a `beforeApply` hook maps it to `150=5` ([07](07-extension-points.md)) |
| N10 | D, ack, two partials, bust of the first (`20=1`) | 14/151/6/39 restated; 32/31 untouched (not the last fill) | 6.4 | handled |
| N11 | D, ack, partial, correct of it (`20=2`) | restated 14/151/6 plus the correcting 32/31; 12028 = the correct's 17 | 6.4 | handled |
| N12 | child D with 9000, its reports, parent separate | two records; 9000 on the child's; views split on it | 3.2, 6.1 | handled |
| N13 | D, ack, `150=4` with no F (unsolicited cancel) | `39=4 151=0`; no pending CANCEL to remove | 6.4 | handled |
| N14 | D, `150=2 39=2` as the **first** report (IOC / market filled at once) | the fill answers the NEW: any non-pending-ack report does; `pending={} 12014=D's id` | 6.4 NEW rule — [03 §7.20](03-state-machine.md#720-a-fill-as-the-first-report-ioc-filled-at-once) | handled **(revised, R1: by the NEW rule, not by a generalized answering rule)** |
| N15 | D, `150=4 39=4` as the first report (IOC not filled) | answers the NEW; canceled | 6.4 | handled |
| N16 | D, `150=A`, `150=0` | `39=A` → `A` → `0`; NEW pending until the `150=0` | 6.4 | handled |
| N17 | the ten `MockFixFlow` chains | each equals its `OrderChain`'s final state | all | handled; the phase 3 parity test |
| N18 **(new)** | `35=H` status request, answered by `8 150=I 39=1 14=… 151=…` | the H is unrouted (§9); the `150=I` is a status restatement: absolutes adopted if not stale or late, pending and 12014 untouched | [03 §1](03-state-machine.md#1-vocabulary), §5 | handled (R18, 08 §7.3) |
| N19 **(new)** | G changing a limit order to market (`40=1`, no 44) and dropping MinQty, then `150=5 40=1 110=0` | 40 and 110 adopted from the confirm; `44=0` written because the confirm carries none | 6.4 order terms | handled (R14) |

## 2. Requests racing their answers

| # | sequence | record at the end | rule | verdict |
| --- | --- | --- | --- | --- |
| E1 | two F, confirm for #1, reject for #2 | `39=4 pending={}` | 6.4 match by 11; §4 terminal guard on the 9 | handled — [03 §7.1](03-state-machine.md#71-two-cancels-confirm-then-reject-the-motivating-case) |
| E2 | two F, reject for #2, confirm for #1 | same record as E1 | same | handled — §7.2 |
| E3 | F after `150=4`, then its 9 | `39=4` throughout; `pending` shows the request until the 9 | §4: requests never move 39 | handled — §7.3 |
| E4 | G then F in flight; 9 for the F; `150=5` for the G | the amend survives the cancel's reject and is adopted; 15038/15044 describe it throughout | 6.5, 6.4 | handled — §7.4 |
| E5 | two G in flight (G2's 41 = X-1 or = G1's id); venue answers each | each answered by its own 11; the second `150=5`'s terms win, being later | 6.2, 6.4 | handled; with only G2's `150=5` arriving, G1 stays pending until its own answer, 12014 = G2; a `150=5` naming neither removes nothing (two pending) |
| E6 | `150=5` arrives **before** its G (two sessions merged on a tap) | the 5 resolves via 41, adopts terms, binds the G's id; the late G is an answered id → ignored | 3.1, 6.2 | handled |
| E7 | F's `150=4` arrives before the F | as E6 | 6.2/6.3 | handled |
| E8 | `150=5` missed; a fill arrives under the amended id | by default the REPLACE stays pending (a fill under a pending id is not an answer); `ANSWERS_REPLACE_IF_TERMS_MATCH` removes it when the fill's 38/44 equal the proposal — never an inferred REPLACE, which has no terms | 6.4 `fill-under-pending-id` | **policy**, default `IGNORE` **(revised, R1)** |
| E9 | `150=4` whose 11 is the **original** id, not the cancel's (venue echoes 41 as 11) | no match → every pending CANCEL removed; `39=4` | 6.4 no-match rule | handled |
| E10 | `150=5` whose 11 is the original id (same venue convention) | exactly one REPLACE pending → removed, 12014 = that request's id, `inferredReplaceAnswers`; two pending → nothing removed, `unmatchedConfirms` | 6.4 — [03 §7.17](03-state-machine.md#717-a-replace-confirm-that-echoes-the-original-id) | handled for one pending amend, limited for two; the rule is unconditional, no knob **(revised, R8)** |
| E11 | `35=9` for a request already answered, or never seen | `unmatchedRejects` += 1; nothing changes; no chain is created | 6.5 | handled |
| E12 | `35=9` with `37=NONE` | resolved via 11 → 41; 37 skipped | 3.1 | handled — §7.11 |
| E13 | G or F on a terminal order (amend after full fill) | joins pending; 39 stays; the venue's 9 clears it | §4 | handled |
| E14 | venue never answers a request | `12016 > 0` on a finished order; `pendingOnTerminal` | — | limited: no timeout by design; the filter `/12016 > 0 AND /12017 = 'Y'` is the operational answer |
| E15 **(new)** | a fill while a cancel is pending, carrying the **cancel's** ClOrdID (the FIX matrices' shape; `OrderChain` emits it) | the cancel stays pending; 39 follows the fill; the later 9 or `150=4` answers it | 6.4 — [03 §7.14](03-state-machine.md#714-a-fill-while-a-cancel-is-pending-under-the-cancels-id) | handled (R1) |
| E16 **(new)** | a `150=8` naming a pending REPLACE, echoing the proposed terms (`39=8` or `39=1`) | the REPLACE removed; 38/44 **not** adopted; 39 per `cancel-reject-status`; not terminal | 6.4 — [03 §7.15](03-state-machine.md#715-an-amend-rejected-by-an-execution-report) | handled (R3) |
| E16a **(new)** | a `150=8` for an amend the tap never saw (names no pending request, order acked and working) | a request reject all the same: nothing removed, 38/44 and `39=8` not adopted, `unmatchedRejects` | 6.4 — [03 §7.22](03-state-machine.md#722-a-1508-for-a-request-the-tap-never-saw) | handled (S6) |
| E17 **(new)** | a `150=6`/`E` overtakes its F/G | an inferred pending entry is added; the request fills it in when it arrives | 6.4, 6.2 — [03 §7.18](03-state-machine.md#718-a-pending-acknowledgement-that-overtakes-its-request) | handled (R9) |
| E17a **(new)** | a `150=6`/`E` that names the **working** id, not the request's | an inferred entry under the working id; the request arriving with that id in 41 re-keys it rather than adding a second | 6.2 — [03 §7.23](03-state-machine.md#723-a-pending-acknowledgement-that-names-the-working-id) | handled (S7) |
| E18 **(new)** | a `35=9` carrying `39=4` ("too late, already canceled") when the `150=4` was never seen | `39=4 151=0 12017=Y`; `terminalFromReject` | 6.5 — [03 §7.19](03-state-machine.md#719-a-cancel-reject-that-carries-a-terminal-status) | handled (R6) |
| E19 **(new)** | a request rejected by `35=j` or `35=3` instead of `35=9` | the request stays pending; the j/3 goes to `fix42/unrouted` | §9 | **limited**: needs a MsgSeqNum-to-ClOrdID ring — a follow-up in [05](05-implementation-plan.md); until then `12016 > 0` shows it |

## 3. Reports racing each other

| # | sequence | record at the end | rule | verdict |
| --- | --- | --- | --- | --- |
| E20 | cancel confirmed, then a fill with **higher** CumQty (a late execution) | 14/6/32/31/30 adopted; `39=4 151=0` kept; `12026=1` | §4 after-terminal table | **policy**: `late-fill-after-terminal`, default `APPLY_ECONOMICS` — [03 §7.12](03-state-machine.md#712-cancel-confirmed-then-a-late-execution) |
| E21 | cancel confirmed, then the report of a **fill** it already counted (equal CumQty, new ExecID) | 17/150/32/31/30/12028 adopted, nothing else | §4 detail-only row, fills only | handled **(revised, R2: the row no longer applies to non-fills)** |
| E22 | cancel confirmed, then an older report (lower CumQty) | ignored, `12025` | §5 stale | handled |
| E23 | cancel confirmed, then a `150=6` that overtook it | ignored (absorbed) | §4 | handled |
| E24 | full fill, then a bust of one fill | reopens: 39/14/151 as restated, `12017=N` | §4 reopening | handled — §7.9 |
| E25 | full fill, then a correct (price only) | 14 unchanged, 6 restated, `39=2` stays | §4 reopening | handled |
| E26 | canceled, then a bust of an earlier fill | 14/6 restated down; `39=4`, `151=0`; 32/31 untouched unless the busted fill is the last one | §4 reopening | handled — §7.13 |
| E27 | canceled, then a correct | as E26 with the new 32/31 | §4 | handled — §7.13 |
| E28 | terminal, then `150=D` | applied as stated: a restatement is a reopening report | §4 | handled |
| E29 | `150=3` after `150=4` (any terminal after terminal) | applied: the venue's business — unless its CumQty is equal and its TransactTime older than 12029, when §5 step 4 drops it as late first | §4, §5 | handled, subject to the late guard |
| E30 | duplicate ExecID (PossDup resend) | ignored, `12025` | §5 | handled — §7.6 |
| E31 | an old status-bearing report resent from outside the ExecID ring (an ack after a pending cancel) | lower CumQty → stale; equal CumQty and earlier 60 → `IGNORED(late)`; 39 does not regress | §5 steps 3–4 — [03 §7.6](03-state-machine.md#76-duplicate-stale-and-late-reports) | handled **(revised, 08 §3: the first version's prose was wrong and the record regressed)** |
| E31a **(new)** | an old **bust** or restatement redelivered from outside the ring | its 60 is older than 12029 → `IGNORED(late)`; its 19 is in 12031 → `IGNORED(duplicate)` | §5 steps 2 and 4 | handled (S5) |
| E32 | fill while PENDING_CANCEL / PENDING_REPLACE, under the **pending request's** id | 39 follows the fill; pending stays | 6.4 — §7.14 | handled for a cancel; **policy** for an amend (`fill-under-pending-id`) **(revised, R1)** |
| E33 | `20=3` status report, with or without a 17 | a status restatement: absolutes adopted if not stale or late; never touches pending or 12014; not deduplicated when 17 is absent/`0`/`NONE` | §1, §5 | handled **(revised, R18)** |
| E34 | a report with `38 ≠ 14 + 151` | applied as sent; `inconsistentReports` += 1 | §5 | limited: the venue's numbers are adopted, the inconsistency is counted |
| E35 **(new)** | a replace reported as **cancel-then-new** (`150=4` for the old id, `150=0` for the new) | one message reading canceled, then a venue-asserted reopen: `39=0 151=…`, the REPLACE answered by the `150=0`; `reopenedByVenue` | §4 — [03 §7.16](03-state-machine.md#716-a-replace-reported-as-cancel-then-new) | **policy**: `non-fill-after-terminal`, default `ADOPT_VENUE_IF_LATER`; a hook suppresses the cancel leg (R2) |
| E36 **(new)** | a re-ack (`150=0 39=0`) after an erroneous terminal report, newer TransactTime | venue-asserted reopen: applied in full | §4 | policy as E35 (R2) |
| E37 **(new)** | a `150=0` that was overtaken by an IOC's `150=4`, older TransactTime — or the **same second** on a venue sending `60` without milliseconds, or with no `60` at all | `IGNORED(late)`: an IOC/FOK never reopens on an ack that answers nothing, a same-second report is not "later" without milliseconds, and an undatable one is not believed; `lateIocAck` / `reopenUndecidable` | §4 last rows, decision 1 | handled (S4) |
| E38 **(new)** | a `150=6` with a **higher** 14 than the record's on a terminal order | absorbed: the pending-acknowledgement row precedes the late-execution row (08 §4 item 5) | §4 | handled |
| E39 **(new)** | a report lacking 14/151/6 on a working order | what it carries is adopted; the stale and late guards treat absent 14 as equal | §5 | handled (08 §4 item 11) |

## 4. Mid-stream, duplicates and identity

| # | sequence | record at the end | rule | verdict |
| --- | --- | --- | --- | --- |
| E40 | first message is a report | `12018=MIDSTREAM 12019=C`, key = the report's 41 or 11 (the OrderID only if it carries neither), terms as far as reports carry them | 3.2 | handled — §7.7 |
| E41 | the D arrives after reports | `12018=D_LATE`; descriptive terms filled; 39/14/151/6 untouched; 38/44 untouched once a report has stated them (filled only on a request-created chain with no report yet); no NEW added, an inferred NEW becomes requested | 6.1 | handled — §7.7 **(revised, S7)** |
| E42 | the D is resent while the cache knows the chain | ignored | 6.1 | handled |
| E43 | a report whose 11 and 41 are unknown but 37 is known (an intermediate replace missed) | resolved via 37; the new id bound | 3.1 | handled |
| E44 | the venue **rotates OrderID** across replaces | the new 37 is unknown in the OrderID map, resolution falls to 11, finds the chain, binds the new 37 to the same key; the record shows the latest; every 37 goes into the tombstone on eviction | 3.1, 3.3, 6.4 | handled **(revised, R21)** |
| E45 | a report for a child chain without tag 9000 | scope from the cache | [02 §3.1](02-architecture.md#31-one-entry-per-order) | handled — §7.10 |
| E46 | ClOrdID reused on a later day, against the previous day's `order_state` | a resent-looking D is ignored if the cache knows the id; against an unwiped SOW with an empty cache the keyed check finds the old record | 6.1, [04 §9](04-recovery-and-operations.md#9-the-daily-reset) | out of scope beyond the daily reset procedure in 04 §9; a hook can date-prefix nothing (decision 2 keeps raw ids), so the reset is the answer |
| E47 | a message for an order **evicted** from the cache (late bust) | tombstone resolves 37 → key; the entry is recreated from the `order_state` record by the lookup path, not from the bust; the delta lands on the right record with 12021/12023 intact | [04 §4](04-recovery-and-operations.md#4-eviction) | handled **(revised, S9)** |
| E48 | a late *request* under a new id for an evicted order | `miss-lookup` resolves its 41 through `orders_audit` to the key; without a hit, a second `MIDSTREAM` record, visible via `/12018 = 'MIDSTREAM'` | 04 §3 | handled by the lookup; limited without it **(revised, R4)** |
| E49 | publisher restart mid-flow | cache rebuilt from the SOW; the publish store replays what AMPS had not acknowledged; the FIX session resends what the engine had not acknowledged; redeliveries are idempotent | [04 §2](04-recovery-and-operations.md#2-restart), [04 §6](04-recovery-and-operations.md#6-the-publish-path) | handled **(revised, R5: the first version ignored the client's send buffer)** |
| E50 | `35=Q` DontKnowTrade | lenient: recorded whole on `fix42/unrouted`, `IGNORED(unrouted)`; strict: raise | [03 §9](03-state-machine.md#9-unrouted-and-unparseable-messages) | out of scope for the blotter, **recorded** for a later version; no order-level change is needed, so nothing on the blotter is wrong without it **(revised, R13)** |
| E51 | GTC order carried across a day boundary, `150=3` then working again | `150=3` is terminal here and forces `151=0`; a deployment that resets daily keeps the open (`12017='N'`) records, but a `150=3` order is terminal and is removed — its next-day reports then create a `MIDSTREAM` record under its old key via the audit lookup | §4, [04 §9](04-recovery-and-operations.md#9-the-daily-reset) | out of scope, as the contract — with two acknowledged consequences: a GTC's `150=3` zeroes its exposure overnight, and across a reset it continues as a `MIDSTREAM` record (S8) |
| E52 | multi-leg or list orders, allocations | — | — | out of scope, as the contract |
| E53 **(new)** | a report for an order the cache lacks under `recovery: NONE` or a partial load, for a **D-opened** order | `miss-lookup`: `orders_audit` by 41 or 11 returns the audit record and its 13011; the entry is rebuilt from the SOW record and the report merges into it | 04 §3 | handled (R4a) |
| E54 **(new)** | a late bust for a terminal order the partial load did not keep | the partial load built a tombstone from every record, so 37 resolves | 04 §4 | handled (R4b) |
| E55 **(new)** | a D resent, or a day replayed, against a populated SOW with an empty cache | the keyed `sow` by `13011 = 11` finds the record; the D is handled as a resend, never a FULL publish | 6.1 second row | handled (R4c) |
| E56 **(new)** | `recovery: NONE` started against a non-empty `sow/fix42/order_state` | refused unless `allow-incomplete-cache: true` | 04 §2 | handled (R4) |
| E57 **(new)** | a numeric ClOrdID equal to another order's numeric OrderID | the OrderID is looked up in the OrderID map only, so it does not resolve to the ClOrdID's chain; the new chain is keyed by its own ClOrdID, and only a message with no ClOrdID at all reaches the OrderID candidate, where the collision check skips it and `keyCollisions` counts | 3.1, 3.2 — [03 §7.21](03-state-machine.md#721-an-orderid-that-equals-another-orders-clordid) | handled (R12, decision 2) **(revised, S1: the first version checked only at mint time, after a single map had already resolved the report to the wrong chain)** |
| E58 **(new)** | a G whose 37 and 41 name different orders; a D carrying 41 | the G resolves by **41** first (03 §3.1), so it lands on 41's order; its 37 is already bound to the other and is not rebound, `idConflicts` counts. The D opens its own chain; its 41 becomes 12027 | 3.1, 3.3 | handled (R10) **(revised, round-2 sweep item 1)** |
| E59 **(new)** | ClOrdIDs containing `:`, `;`, `,` or `%` | escaped in 12015 and 12021; a record that fails to parse on rebuild is `12018=UNRECOVERED` and counted | [02 §4.1](02-architecture.md#41-several-requests-in-flight-tag-12015) | handled (R11) |
| E60 **(new)** | a D carrying venue custom tags in 12000–12999, 13xxx or 15xxx, or FIX header tags | stripped from the FULL publish; `strippedTags` counts | 6.1 | handled (R20e) |
| E61 **(new)** | a pegged algo child with a thousand amends in a day | 12021 holds the last 50 ids and 12030 the count; the full history is `orders_audit` filtered by 13011 | 3.3, [04 §3](04-recovery-and-operations.md#3-cache-misses) | handled (R19, decision 3) |
| E62 **(new)** | a SOW written by an older publisher version loaded by a newer one | 12024 is read; missing tags take defaults and `recoveredWithDefaults` counts; an unknown major version refuses to start | 04 §2 | handled (R17) |

## 5. Durability and operations

| # | sequence | record at the end | rule | verdict |
| --- | --- | --- | --- | --- |
| E70 **(new)** | AMPS rejects a blotter delta (unparseable payload, missing key) | the `FailedWriteHandler` reports it, `publishFailures` counts, the key is queued, and the publishing thread re-sends every cached field as a **delta** — never a `publish`, which would erase the D's terms the cache does not hold | 04 §6 | handled (R5) **(revised, S2)** |
| E70a **(new)** | AMPS rejects the FULL publish that created a record | the kept creation payload is re-sent whole | 04 §6 | handled (S2) |
| E70b **(new)** | AMPS rejects an audit publish | `auditPublishFailures`; the payload goes to `fix42/unrouted` in lenient mode | 04 §6 | handled (S2) |
| E70c **(new)** | a publish throws after an earlier publish for the same message was stored | the message is redelivered; keyed audit topics overwrite idempotently; `fix42/unrouted` may hold a duplicate | 04 §6, 02 §5 | limited, stated (S14) |
| E70d **(new)** | every `35=8` published to `sow/fix42/execs` through the store | the topic is journalled like every other this client writes, so the store drains; `publishStoreDepth` returns to zero | 02 §1, 04 §6 | handled (S3) |
| E71 **(new)** | AMPS restarts or the network drops while the publisher runs | `HAClient` reconnects; the publish store replays what was not acknowledged; the server drops duplicates by sequence number | 04 §6 | handled (R5) |
| E72 **(new)** | the publisher process crashes after the FIX engine acknowledged a message | with `publish-store: FILE` the store replays it on restart; with `MEMORY` the un-acknowledged window is lost and the loss is bounded and stated | 04 §6 | **policy**: `publish-store`, `FILE` in production (decision 4) |
| E73 **(new)** | a publish throws before `commit()` | the cache is unchanged; the exception reaches the feed adapter, which does not acknowledge the message | [03 §2](03-state-machine.md#2-outcomes), 04 §6 | handled (R5) |
| E74 **(new)** | a hook throws | fail-fast: the message is held, the feed stops, the cache is unchanged | [07 §2](07-extension-points.md#2-tier-two-hooks) | handled (R20a) |
| E75 **(new)** | a second publisher instance starts with the same client name | `NameInUse`; it retries until the first is gone | 04 §5 | handled |
| E76 **(new)** | bytes that do not parse as FIX | lenient: wrapped on `fix42/malformed`; strict: raise | 03 §9 | handled (R13) |
| E76a **(new)** | a `20=3` status report with no 17, or `17=0` | the message is applied to the blotter; `execs_audit`, whose key it lacks, is skipped and the whole message goes to `fix42/unrouted` instead | 03 §9, 02 §5 | handled (S10) **(new)** |
| E76b **(new)** | a report with a 150 value no rule names (`F`, `G`, `H` from a FIX 4.4 venue) | applied as *other* or *fill*; absolutes adopted; no pending REPLACE or CANCEL touched; the value in the per-150 histogram | 03 §5 step 1, §9 | handled (S11) |
| E77 **(new)** | the daily reset | `sow_delete` of the terminal records only, with the open orders kept; the audit topics untouched, with the consequence that a reused ClOrdID overwrites yesterday's audit SOW record | 04 §9 | handled (R22) **(revised, S8, S13)** |

## 6. What the matrix changed in the design

The first version of this matrix tightened three rules; the review of that
version ([08](08-review-findings.md)) showed two of the three were tightened
the wrong way, and the second version records both passes:

- **E20** showed that dropping every non-reopening report after a terminal
  status would drop a real trade. It became the after-terminal table in
  [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision).
  The review (R2) then showed the table's detail-only row swallowed a venue
  re-ack; it now applies to fills only, and a non-fill report with a newer
  TransactTime is a venue-asserted reopen.
- **N14** and **E8** led the first version to let *any* report answer the
  request its tag 11 names. The review (R1) showed fills reported during a
  pending request carry that request's id, so the rule cleared cancels on
  fills. Answering is by ExecType again; a NEW is answered by any report; a
  fill answers a REPLACE only under a policy.
- **E6** and **E7** led the first version to ignore a request whose id was
  already bound. The review (R9) showed a pending acknowledgement binds the
  id before the request arrives. The test is now "already answered", and a
  pending acknowledgement for an unknown request adds an inferred entry.
- **R3**, **R6**, **R7**, **R8**, **R10**, **R12**, **R14**, **R18** and
  **R21** each added or changed a row in [03 §6](03-state-machine.md#6-transition-rules)
  without a predecessor in the first version; §§1–5 above cite them where
  they land.
- The second review ([09](09-review-round-2.md)) found the collision rule
  unreachable behind a single id map (S1), the publish-failure repair
  destructive (S2), and `sow/fix42/execs` outside the design's own journal
  rule (S3); and tightened the TransactTime tiebreak for second-resolution
  venues and IOC/FOK orders (S4), the guards on redelivered busts (S5), the
  no-match `150=8` (S6), inferred entries under the working id (S7), the
  reset's interaction with open orders and the audit topics (S8), and
  recreation of evicted or looked-up entries (S9). Rows E16a, E17a, E31a,
  E70a–d, E76a–b are theirs.

## 7. Extension

Rows marked **policy** are decided by knobs; a venue whose behaviour matches
none of the options, or a deployment that needs a rule the matrix does not
have, uses the hooks in [07](07-extension-points.md) rather than a fork of the
machine.
