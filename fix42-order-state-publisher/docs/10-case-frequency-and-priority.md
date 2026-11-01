# 10. Case frequency and implementation priority

Every row of the coverage matrix in [06](06-case-coverage.md), sorted by how
often a drop copy actually produces it, so the rows can be built in an order
that pays off soonest. Frequency is judged for an active equities desk on a
single FIX session; where a case is routine on some venues and absent on
others it is marked **dialect** and placed by its default policy.

Three buckets:

| bucket | meaning |
| --- | --- |
| **Normal** | every session, on many orders. The machine's first cut must get these right, and the scripted mock flow must contain them |
| **Sometimes** | most days on an active desk, or on every restart or reconnect, or routinely on venues with a given convention. In the first release, each with its own test |
| **Rare** | monthly or less; or only on a tap that merges sessions, a venue with an unusual quirk, or an operator error. Built when cheap or when the consequence is severe; otherwise after the first release |

Frequency is not priority. A rare case that corrupts a record and costs ten
lines (a numeric OrderID equal to another order's ClOrdID) is built before a
frequent case that is only configuration (journalling one more topic). So
each row also carries a build slot:

| slot | when | what it covers |
| --- | --- | --- |
| **P1** | phase 2, the machine's first cut, no server | the rules of [03](03-state-machine.md) that cannot be added later without reworking the core: identity, the pending set, arbitration, the after-terminal table, the record encoding |
| **P2** | phases 3–4, before the first production run | the publish path, recovery, lookups, the venue-dialect knobs, operations |
| **P3** | after the first production run, or a listed follow-up | hooks for specific venues, eviction, `35=Q`, `35=j`, cases that are documented consequences rather than code |

Row ids and short titles are those of [06](06-case-coverage.md); the
rule references are to [03](03-state-machine.md).

---

## 1. Normal — 16 rows

| row | case | why it is normal | slot |
| --- | --- | --- | --- |
| N1 | new, ack, partial, full fill | the lifecycle of most orders | P1 |
| N2 | new, ack, partial, cancel, pending cancel, canceled | the other common ending | P1 |
| N3 | new, ack, amend, pending replace, replaced, fills | every re-priced order | P1 |
| N4 | amend rejected by `35=9` | daily: too late, already filled, risk limit | P1 |
| N5 | cancel rejected by `35=9`, order keeps working | daily: too late to cancel | P1 |
| N6 | rejected before working (`150=8`) | daily: fat finger, risk check, unknown symbol | P1 |
| N12 | child slices with tag 9000 beside their parent | every algo parent is sliced | P1 |
| N14 | an IOC or market order whose first report is the fill | IOC flow is a large share of algo child orders | P1 |
| N15 | an IOC whose first report is `150=4` | the other half of IOC flow | P1 |
| N17 | the ten scripted chains end as the mock venue says | the regression test for everything above | P1 |
| E15 | a fill while a cancel is pending, under the cancel's id | any fill during a pending cancel; frequent on active orders, and the shape the mock emits | P1 |
| E32 | a fill while a cancel or amend is pending | same event as E15 from the other side | P1 |
| E60 (header half) | a `35=D` carrying FIX header and trailer tags | every message on a real session carries 8, 9, 10, 34, 49, 52, 56 | P1 |
| E61 | an algo child re-priced hundreds of times | normal for pegged and VWAP children | P1 — a cap on 12021 is one parameter |
| E70d | every `35=8` published to `sow/fix42/execs` through the store | structural: every message, every day | P2 — one line of server config, but it must be there before the first run |
| E77 | the daily reset | every day the deployment runs | P2 — a script, phase 4 |

## 2. Sometimes — 35 rows

| row | case | why it is sometimes | slot |
| --- | --- | --- | --- |
| N7 | done for day | broker-dependent; same terminal path as a cancel | P1 — free with the terminal rule |
| N8 | expired (`150=C`) | GTD and IOC flows; same terminal path | P1 — free |
| N10 | a trade bust | weekly on a large desk; money, so it must be right | P1 |
| N11 | a trade correct | as N10 | P1 |
| N13 | unsolicited cancel (`150=4` with no F) | venues cancel day orders at the close, self-trade prevention, halts | P1 — free with the no-match rule |
| N16 | `150=A` pending new, then the ack | venue-dependent; trivial | P1 |
| N18 | `35=H` and its `150=I` answer | start-of-day reconciliation on some OMSs | P1 — the status-restatement classification is a few lines |
| E1 | two cancels, confirm then reject | the motivating case; a few times a day on an impatient desk | P1 |
| E2 | two cancels, reject then confirm | same | P1 |
| E3 | a third cancel after the order is canceled | retries | P1 — free with the set |
| E4 | amend and cancel both in flight | an algo re-prices then pulls | P1 — free with the set |
| E5 | two amends in flight | pegged algos; normal for them | P1 |
| E9 | `150=4` echoing the original id (**dialect**) | every cancel on venues with that convention | P1 — the no-match rule |
| E10 | `150=5` echoing the original id (**dialect**) | every replace on the same venues | P2 — the single-pending inference |
| E12 | `35=9` with `37=NONE` | a cancel sent before the ack arrived | P1 — skipping `NONE` must be in resolution from day one |
| E13 | a request on a terminal order | an amend sent as the fill arrives | P1 — free |
| E17a | a pending ack naming the working id (**dialect**) | every pending ack on venues with that convention | P2 — re-keying inferred entries |
| E20 | a fill after the cancel confirm | the classic race; a few a day on an active desk | P1 — money |
| E30 | a duplicate ExecID (PossDup) | every reconnect and every resend | P1 |
| E33 | `20=3` status reports | venues and OMSs that send them do so routinely | P1 — classification |
| E39 | a report without 14/151/6 | acks and cancels on some venues omit them | P1 — the machine must not fail on an absent tag |
| E40 | the first message of an order is a report | every go-live mid-session, and every order opened while the publisher was down | P1 — `MIDSTREAM` creation |
| E42 | a resent `35=D` | PossDup on reconnect | P1 — ignoring it is trivial; applying it would erase the record |
| E45 | a child's report without tag 9000 (**dialect**) | most venues do not echo custom tags; matters only for scoped topics | P3 — the shipped layout does not need it |
| E46 | ClOrdID reused on a later day | OMSs that reset sequences daily | P2 — the reset procedure |
| E49 | a publisher restart mid-flow | every deploy | P2 — `recovery: SOW`, phase 4 |
| E51 | a GTC across the day boundary | GTC-heavy desks | P3 — a documented consequence |
| E53 | a miss under `recovery: NONE` or a partial load | whenever those modes are used | P2 — the lookups, phase 4 |
| E59 | ClOrdIDs containing `:`, `;`, `,` or `%` | OMSs that build ids from date, desk and sequence | P1 — escaping in the encoder from day one |
| E62 | a SOW written by an older schema | every upgrade | P2 — the loader; writing 12024 is P1 |
| E71 | an AMPS restart or a network drop | maintenance windows, blips | P2 — the `HAClient`, phase 3 |
| E76a | a status report with no ExecID (**dialect**) | venues whose `20=3` carries no 17 | P2 — the per-topic key check in the router |
| E31 | an old status report resent from outside the ring | resends after a long outage | P1 — the TransactTime guard is core arbitration |
| E37 | an overtaken IOC ack on a venue sending whole seconds | a single session preserves order, so this needs a tap or a slow venue | P1 — one condition in the after-terminal table |
| N9 | a restatement (`150=D`) | GT renewals, exchange partial declines; rare on day-order flows, routine on GTC ones | P1 — free with the reopening rule |

## 3. Rare — 47 rows

| row | case | why it is rare | slot |
| --- | --- | --- | --- |
| N19 | an amend from limit to market, dropping MinQty | order type changes mid-life are unusual | P2 — the `order-term-tags` list |
| E6, E7 | an answer overtaking its own request | only on a tap that merges two sessions | P2 — answered-id tracking |
| E8 | a `150=5` missed, the fill under the amended id | needs a lost message | P3 — the policy knob; the default (`IGNORE`) is P1 |
| E11 | an unmatched `35=9` | a reject for a request never seen | P1 — count and do nothing |
| E14 | a request the venue never answers | a venue fault | P2 — the counter and filter |
| E16 | an amend rejected by `150=8` (**dialect**) | a minority convention | P2 |
| E16a | a `150=8` for a request the tap never saw | needs a lost message | P2 — part of the `150=8` rule |
| E17 | a pending ack overtaking its request | merged tap | P2 — inferred entries |
| E18 | a `35=9` carrying `39=4` when the `150=4` was lost | needs a lost message | P1 — forcing `151=0` on an adopted terminal status is one line |
| E19 | a request rejected by `35=j` or `35=3` | session-level rejects are rare on a healthy link | P3 — the MsgSeqNum ring, a follow-up |
| E21 | a late report of a fill already counted | out-of-order delivery after a close-out | P1 — the fills-only detail row, part of the table |
| E22 | an older report after the close-out | resends | P1 — the stale guard |
| E23 | a `150=6` overtaking a `150=4` | merged tap | P1 — the absorbed row |
| E24, E25 | a bust or correct after a full fill | busts are rare; after a full fill rarer | P1 — the reopening rows; money |
| E26, E27 | a bust or correct after a cancel | as above | P1 |
| E28 | a `150=D` after a terminal report | a GT renewal of a closed order | P1 — free |
| E29 | a terminal report after a terminal report | `150=3` after `150=4` | P1 — free |
| E31a | a redelivered old bust | a resend that reaches back past a bust | P2 — the busted set, 12031 |
| E34 | a report with `38 ≠ 14 + 151` | a venue bug | P2 — a counter |
| E35 | a replace reported as cancel-then-new (**dialect**) | a minority convention | P1 for the reopen rule in the table; P3 for the hook that hides the cancel leg |
| E36 | a re-ack after an erroneous terminal report | a venue correcting itself | P1 — same table row |
| E38 | a `150=6` with a higher CumQty on a terminal order | contradictory report | P1 — row ordering |
| E41 | the `35=D` arriving after its reports | a tap that saw the venue before the client | P2 — the late-D rules |
| E43 | a report whose 11 and 41 are unknown but 37 is known | a missed replace | P1 — 37-first resolution is core |
| E44 | a venue rotating OrderID across replaces (**dialect**) | a minority convention | P1 for resolution; P3 for tombstones of every 37 |
| E47, E48 | messages for an evicted order | eviction is off by default | P3 — phase 4, with eviction |
| E50 | `35=Q` DontKnowTrade | rare on a drop copy | P3 — recorded by lenient mode; a follow-up |
| E52 | multi-leg and list orders | out of scope | — |
| E54 | a late bust for an order the partial load did not keep | needs the partial load and a bust | P2 — tombstones in the loader |
| E55 | a resent D against a populated SOW with an empty cache | needs `recovery: NONE` and a resend | P2 — the keyed check |
| E56 | `recovery: NONE` against a non-empty blotter | operator error | P2 — a startup refusal, trivial |
| E57 | a numeric OrderID equal to another order's ClOrdID | a coincidence of id schemes | **P1** — the typed index; rare, catastrophic, cheap |
| E58 | a G whose 37 and 41 disagree; a D carrying 41 | operator error or a cancel-and-new OMS | P1 — resolution order and no-rebind are core |
| E60 (venue half) | venue custom tags in 12000–12999, 13xxx or 15xxx on a D | a collision of tag ranges | P1 — same strip list as the header half |
| E70, E70a, E70b | AMPS rejects a delta, a creation, an audit publish | a payload bug or a missing key | P2 — the `FailedWriteHandler` and repair queue |
| E70c | a publish throwing after another was stored | a client fault mid-message | P3 — documented, no code |
| E72 | a publisher crash after the FIX engine acknowledged | process crashes are rare; the consequence is the reason for the file store | P2 — `publish-store: FILE` |
| E73 | a publish throwing before `commit` | as E72 | P1 — the plan/commit split is the shape of the machine and cannot be retrofitted |
| E74 | a hook throwing | a bug in deployment code | P2 — fail-fast in the SPI |
| E75 | a second instance with the same client name | operator error | P2 — `NameInUse` retry |
| E76 | bytes that do not parse as FIX | a feed fault | P2 — the malformed route |
| E76b | a report with a FIX 4.4 ExecType | a venue leaking 4.4 values | P1 — classification, trivial |

## 4. Rare but first

Six rare rows go into the first cut regardless of frequency, because each is
either the shape of the core or a cheap guard against a severe outcome:

| row | why first |
| --- | --- |
| E57 | one id map instead of two silently merges two orders' records; the typed index costs nothing extra |
| E58 | the per-type resolution order and no-rebind are the identity core; retrofitting them means re-testing everything |
| E73 | plan/commit is the machine's shape; a machine that mutates on apply cannot be made two-phase later without a rewrite |
| E18 | a terminal status without `151=0` is exposure the views count for ever; one line |
| E24–E27 | busts and corrects are money; the reopening rows are the same code as the fills |
| E22, E23, E31 | the three guards are the arbitration order of [03 §5](03-state-machine.md#5-arbitration-on-execution-reports); every later rule assumes they ran |

## 5. Frequent but later

Three rows are normal or common and still wait, because they are
configuration or operations rather than machine logic:

| row | why later |
| --- | --- |
| E70d | one `<TransactionLog>` entry in the server flow, phase 3 |
| E77 | the reset is a script against a running instance, phase 4 |
| E49 | restart recovery is phase 4 by design; the record carries everything from day one, so the loader is additive |

## 6. By the numbers

| bucket | rows | of which P1 | P2 | P3 |
| --- | --- | --- | --- | --- |
| Normal | 16 | 14 | 2 | 0 |
| Sometimes | 35 | 22 | 10 | 3 |
| Rare | 47 | 21 | 19 | 6 (one out of scope) |
| **total** | **98** | **57** | **31** | **9** |

Fifty-seven rows are in the machine's first cut, and most of them come free
with eleven rules: the typed index with its per-type resolution order, the
pending set with match-by-ExecType, the three arbitration guards, the
after-terminal table, the reopening rows, the forced `151=0`, the strip list,
the escaped encodings, and the plan/commit split. Phase 2 of
[05](05-implementation-plan.md) builds exactly those, and the mock venue's
`EdgeCaseFlow` in phase 1 should carry every Normal and Sometimes row before
any Rare one.
