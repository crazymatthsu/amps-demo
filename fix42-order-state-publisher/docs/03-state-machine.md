# 3. The state machine

Everything the publisher decides about a message, as rules, followed by the
cases from [01 §4](01-problem-the-two-cancels.md#4-the-class-of-failure) and
the review in [08](08-review-findings.md) walked through one message at a
time. Each row of the walkthroughs becomes a unit test in phase 2 of the
[plan](05-implementation-plan.md); the machine is pure (a `FixFrame` in, an
outcome out) so none of them needs a server.

The rules follow the [contract](../../docs/fix42/01-fix42-messages-and-state-machine.md)
§3 and §5 where the contract speaks, and say so where they depart from it. The
departures are two, both simplifications: the status is never moved by a
request (§4), and duplicate detection is bounded (§5).

This is the second version of these rules. The first was reviewed against
real drop-copy dialects ([08](08-review-findings.md)) and three of its rules
were found to break on message shapes the repository's own mock venue
produces; the changes are marked where they land and summarised in
[08 §8](08-review-findings.md#8-where-each-finding-landed).

---

## 1. Vocabulary

| term | meaning |
| --- | --- |
| **OrderKey** | the SOW key of one order's record, tag 13011 **RootClOrdID**: the `35=D`'s own tag 11, written in the D's FULL publish and never changed — the root ClOrdID in all but the degenerate case |
| **chain** | every message that resolves to one OrderKey |
| **root** | the ClOrdID of the chain's `35=D`, when the chain opened with one |
| **request** | a `35=D`, `35=G` or `35=F`: something the client asked for, identified by its own tag 11 |
| **report** | a `35=8`; a **reject** is a `35=9` |
| **pending set** | the requests of one order the venue has not answered. Each entry is **requested** (the request itself was seen) or **inferred** (only the venue's pending acknowledgement of it was seen) |
| **answered id** | a request id bound to the chain that is not in the pending set: a report or reject has already dealt with it |
| **venue status** | tag 39 as the venue last stated it; `A` (PendingNew) for a D nobody has answered; absent on a chain created from a request |
| **terminal** | venue status ∈ {`2` Filled, `3` DoneForDay, `4` Canceled, `8` Rejected, `C` Expired}. Every other value — including `5` Replaced, `6`, `A`, `E`, `7` Stopped, `9` Suspended, `B` Calculated, `D` AcceptedForBidding — is non-terminal, and the venue's 151 is adopted as sent |
| **reopening report** | a report that may legitimately move a terminal order: a bust (`20=1`), a correct (`20=2`), or a restatement (`150=D`) |
| **pending acknowledgement** | a report with `150 ∈ {A, 6, E}`: the venue has received a request and not yet decided |
| **status restatement** | a report with `20=3`, or `150=I` (the answer to a `35=H`): the venue restating current state. It is applied like any report, never touches the pending set or 9014, and is exempt from duplicate detection when its 17 is absent, `0` or `NONE` |
| **fill** | a report with `150 ∈ {1, 2}` and `20 ∈ {0, absent}`; also a report with a 150 value the rules do not name when it carries 32 — an unknown 150 is classified as *other* or *fill* and applied, never treated as unrouted (S11, §9) |
| **answering report** | a report that resolves a pending request, by ExecType (§6.4): `150=5` a REPLACE, `150=4` a CANCEL, `150=0`/`8` a NEW — and a NEW is also answered by *any* report for the order that is not a pending acknowledgement, because the venue reporting on the order means it has accepted or rejected it. **A fill never answers a CANCEL and, by default, never answers a REPLACE**: fills reported while a request is pending carry the *request's* ClOrdID, which is not an answer (R1). The NEW rule takes precedence over every "never touches the set" rule in §6.4: a `150=C` for a FOK that never worked answers its NEW (S12) |
| **late execution** | a fill on a terminal order whose CumQty is **higher** than the record's: a trade the venue reports after it closed the order out |
| **venue-asserted reopen** | a non-fill, non-pending-acknowledgement report with a non-terminal 39 on a terminal order that either **answers a pending request** (a `150=0` naming a pending REPLACE — the cancel-then-new dialect) or is **newer** than the last applied venue message: later than 9029, and strictly later by a whole second when either timestamp lacks milliseconds (S4). Never on an order with `59 ∈ {3, 4}` (IOC, FOK) unless it answers a request: those cannot come back, and their overtaken ack is the common false positive (R2, S4) |

## 2. Outcomes

`OrderStateMachine.plan(FixFrame)` returns one of:

| outcome | meaning | what the publisher does |
| --- | --- | --- |
| `CREATED` | a new chain; the entry is complete | `publish` the whole record (D), or `delta_publish` the best-effort record (`MIDSTREAM`) |
| `CHANGED(dirty)` | an existing chain changed; `dirty` is the set of record fields touched | `delta_publish` those fields plus the last-message bookkeeping |
| `IGNORED(reason)` | duplicate, stale, late, absorbed, unrouted, a duplicate or late request, an unmatched reject | nothing to the blotter, with two exceptions: a newly **bound id** is published (9021/9030) so the index stays recoverable, and `9025` is marked dirty so the next delta carries it (or this one does, with `publish-unchanged: true`) |

`plan` is pure: it reads the entry and returns the outcome with the changes
staged. `commit()` applies them, and the publisher calls it only after the
AMPS client has accepted the publish into its store
([04 §6](04-recovery-and-operations.md#6-the-publish-path)). A publish that
fails leaves the cache exactly as it was, so the message can be redelivered.

Every outcome binds the message's ids (§3.3): a duplicate still tells the
index which chain its ids belong to.

## 3. Identity

### 3.1 Resolution

One order per message type, stated once (the first version gave three
different orders in three sections):

| message | resolution order | why |
| --- | --- | --- |
| `35=D` | **11 only** | a D opens a chain. Its 11 is bound only if this D was seen before (a resend) or reports under this id opened the chain mid-stream (a late D). A 41 on a D is lineage — a cancel-and-new OMS naming the order it replaces — and is recorded as `9027 LinkedClOrdID`, never followed (R10) |
| `35=G`, `35=F` | 41 → 37 (not `NONE`) → 11 | the request names its predecessor; 37 heals a chain whose intermediate replace was missed; 11 only when the request was already seen or inferred |
| `35=8` | 37 (not `NONE`) → 11 → 41 | the contract's order: OrderID first because it heals a broken ClOrdID chain |
| `35=9` | 11 → 41 → 37 (not `NONE`) | the rejected request's id was bound when the request was seen; `37=NONE` is what a reject carries for a never-acked order |

`unknown` when none of the candidates is indexed.

**Two maps, not one (S1).** `OrderKeyIndex` keeps ClOrdIDs and OrderIDs in
separate maps. The "37 →" step consults only the OrderID map; the "11 →" and
"41 →" steps only the ClOrdID map. Numeric ids are common on both sides, and
with a single map order B's first report `8 … 37=123456` would resolve to order
A's chain `D 11=123456` at step 1, before any collision check could run; with
two maps it misses, and creation (§3.2) sees that `123456` is already a key.

### 3.2 Creation

| first message of the chain | OrderKey | `9019` | `9018` | initial state |
| --- | --- | --- | --- | --- |
| `35=D` | its tag 11 | `C` | `D` | terms from the D (less the stripped tags, §6.1); venue status `A`; CumQty 0, LeavesQty = 38, AvgPx 0; pending `{11: NEW, requested}` |
| a report or a pending acknowledgement | 41 if present, else 11, else 37 (not `NONE`) | `C`, or `O` only when the message carried no ClOrdID | `MIDSTREAM` | 39/150/14/151/6/38/44 and any term tags the report carries; for a pending acknowledgement, an **inferred** pending entry for its 11 (`A` → NEW, `6` → CANCEL, `E` → REPLACE) |
| a `35=G` or `35=F` | 41 if present, else 11, else 37 (not `NONE`) | `C`, or `O` only when the message carried no ClOrdID | `MIDSTREAM` | **only** the pending entry `{11: REPLACE/CANCEL, requested}` and 15038/15044 for a G. **No 38/44, no 39** (R7): a request's terms are proposals and the order's status is unknown; `9017=N` |
| a `35=9` | — | — | — | **never creates a chain.** It goes to `sow/fix42/rejects` whole, counts `unmatchedRejects`, and is `IGNORED(unmatched)` (08 §4 item 9) |

**Collisions (decision 2).** The key is always one of the drop copy's own
ids, unprefixed. A candidate that is already **another chain's key** — the set
of keys spans both kinds of id — is skipped for the next candidate,
`keyCollisions` counts it, and 9019 says which kind of id the key is. If every
candidate collides the message is `IGNORED(keyConflict)` and logged with both
keys (R12). With ClOrdIDs preferred as keys a collision is nearly always a
genuine reuse of a ClOrdID; the OrderID fallback is where an unrelated order's
number could equal a root ClOrdID. The typed maps of §3.1 are what make the
check reachable at all: resolution cannot land on the wrong chain first. A venue that sets OrderID equal to the
order's own ClOrdID is not a collision — the report resolves through 11 before
37 is bound, and 37 then goes into the OrderID map harmlessly
([7.21](#721-an-orderid-that-equals-another-orders-clordid)).

A D's own ClOrdID is the key because it exists before the venue has said
anything, and because the tag that carries the key is named for it: 13011 is
the **root ClOrdID**, set in the D's FULL publish from the D's tag 11. For a
chain first seen mid-stream the message's 41 is the ClOrdID closest to the
root it names (the predecessor of a request, the original of a report after
an amend), then its 11; the OrderID is the key only for a message carrying
neither, which no request and almost no report does. Stability is not a
reason to prefer the OrderID: once minted, the key is reached through the
typed index by every id the order ever uses.

### 3.3 Binding

After every message, whatever the outcome: 11 and 41 → key in the ClOrdID
map, 37 (unless `NONE`) → key in the OrderID map, **never rebinding an id
already bound to a different key**
— the existing binding stands, `idConflicts` counts it, and both keys are
logged (R10). A new 11 is appended to the chain list (9021, the last
`chain-ids-published` of them on the record; 9030 the full count). The index
knows every id the order has ever used, which is what lets a `35=9` for an old
request, or a report under a rotated id, find the record.

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

**What a `35=9`'s tag 39 may do** is the one policy decision about status,
configured as `cancel-reject-status`:

| policy | effect of the 9's tag 39 on the record | when to choose it |
| --- | --- | --- |
| `WHEN_NOT_TERMINAL` (default) | adopted unless the order is terminal, or the value is `8`, or the value is a pending status (`A`, `6`, `E`) with no request of that kind left in the set | 39 on a 9 is the order's status after the reject: useful while the order works, noise once it is canceled. `8` on a 9 means "request rejected" in enough dialects that adopting it as *order* rejected is wrong more often than right; a pending status with nothing pending is the venue's status *at the time the request was received*, stale by definition (R6) |
| `NEVER` | ignored | a venue whose 9s always carry the status at request time |
| `ALWAYS` | adopted as sent | a venue known to send the spec's meaning precisely |

Under every policy, **a terminal 39 adopted from a 9 carries a report's
consequences**: `151=0`, `9017=Y`, and `terminalFromReject` counts it. A
record that reads canceled with live LeavesQty would be counted by the
exposure views for ever (R6).

**After a terminal status.** Once the venue status is terminal, a report is
handled by what it is, in this order (R2):

| report on a terminal order | effect |
| --- | --- |
| reopening (bust, correct, `150=D`) | applied in full: restated absolutes, 39 as the venue states it, 9017 recomputed. A bust after `150=2` reopens the order; a bust after `150=4` restates 14/6 downward and the order stays canceled |
| its own 39 terminal (a `150=3` after a `150=4`) | applied in full: the venue's business |
| a pending acknowledgement (`150 ∈ {A, 6, E}`) | `IGNORED(absorbed)`: it acknowledges a request on an order the venue has since closed |
| a **fill**, 14 **higher** than the record's — a **late execution** | the trade is real money and is never dropped. Per `late-fill-after-terminal`: **`APPLY_ECONOMICS`** (default) adopts 14, 6, 38, 17, 150, 32, 31, 30 and 9028, keeps 39 terminal and 151 at 0, counts it in 9026; `ADOPT_VENUE` applies the whole report including 39 and 151, for a venue whose late fill genuinely reopens the order; `IGNORE` drops it (`ignoredLateFill`), for a venue known to resend fills already inside the cancel's CumQty under a new ExecID |
| a **fill**, 14 **equal**, 17 new | the report of a fill the terminal report already counted, arriving late: 17, 150, 32, 31, 30 and 9028 are adopted, nothing else moves. This row is for fills **only**; the first version applied it to every non-fill report too, and a venue re-acking a working order was swallowed by it |
| a fill, 14 **lower** | `IGNORED(stale)` — §5 |
| any report, 17 already seen | `IGNORED(duplicate)` — §5 |
| a **non-fill** report with a non-terminal 39 (a `150=0`, a `150=5`, a status restatement) that **answers a pending request**, or is **newer** than 9029 — strictly, by a whole second, when either timestamp lacks milliseconds — and the order is not IOC/FOK unless it answers a request | a **venue-asserted reopen**. Per `non-fill-after-terminal`: **`ADOPT_VENUE_IF_LATER`** (default) applies it in full — 39, 151, 38/44, terms, 9017 recomputed, and whatever request it answers — and counts `reopenedByVenue`; `ADOPT_VENUE` applies it without the time test (IOC/FOK still excluded); `IGNORE` drops it |
| the same, not newer than 9029, or an IOC/FOK answering nothing | an ack or status that was overtaken by the terminal report: `IGNORED(late)`; `lateIocAck` counts the IOC/FOK case |
| the same, with no 60 and no 52 to judge by | indistinguishable from an overtaken ack: `IGNORED(late)`, `reopenUndecidable` counts — the conservative side, because a wrong reopen is live exposure the views never stop counting |

A request on a terminal order joins the pending set (the venue will reject
it, and the record should show it is unanswered) and changes nothing else.

The two policies read as a pair. The venue that closed an order and then
reports a trade has not reopened it, so the late execution's economics land
and the status stays. The venue that closed an order and then *says it is
working* — the cancel-then-new replace dialect of [7.16](#716-a-replace-reported-as-cancel-then-new),
or a correction of an erroneous terminal report — is believed, provided its
statement answers a request this side made or is newer than the report it
contradicts. TransactTime is the tiebreak because within one venue it is one
clock (decision 1). FIX 4.2 allows `60` without milliseconds, and a venue
that sends seconds makes an overtaken ack and a genuine re-ack
indistinguishable within one second — so without milliseconds a reopen needs
a *later second*, and a report that answers a pending request needs no time
at all. A broker tap that merges several matching engines has several clocks;
`non-fill-after-terminal: IGNORE` is for that deployment, and
[07 §1](07-extension-points.md#1-tier-one-policies) states the one-clock
precondition. The "its own 39 terminal" row is reached only after §5: a
`150=3` with equal CumQty and an older TransactTime is `IGNORED(late)` first.

## 5. Arbitration on execution reports

Applied in this order, before any field is touched:

1. **Classify.** Reopening, pending acknowledgement, status restatement, fill,
   or other, per §1. `20=3` is a status restatement and is **never** treated
   as a fill, whatever its 150 (R18). A 150 value the rules do not name
   (`F`, `G`, `H` from a FIX 4.4 venue, say) is *fill* when 32 is present and
   *other* when not; it is applied like any report, touches no pending entry,
   and its value goes into the per-150 histogram (S11).
2. **Duplicate.** Tag 17 is present, not `0`, not `NONE`, and equals the last
   applied ExecID or is in the per-order ring of the last `exec-id-history`
   ExecIDs → `IGNORED(duplicate)`. A report without a usable 17 is not
   deduplicated (status restatements often carry none). A bust or correct
   whose 19 is already in the order's busted set (9031) is a duplicate too,
   however old (S5). Bounded on purpose: a PossDup resend repeats the tail of
   the stream, and the next two guards catch anything older.
3. **Stale.** `stale-guard: true`, the report is not reopening, it carries 14,
   and 14 is lower than the cached CumQty → `IGNORED(stale)`. CumQty is
   monotonic per chain except across a bust or correct, which restate it
   explicitly and are exempt. Comparison is **numeric**, never textual
   (`300`, `300.0` and `0300` are equal).
4. **Late.** `transact-time-guard: true` (decision 1). A non-reopening report
   whose 14 is equal to the cached CumQty or absent, and whose 60 is
   **earlier** than 9029 (the latest TransactTime of any applied venue
   message — reports and rejects alike) → `IGNORED(late)`. A **reopening**
   report whose 60 is earlier than 9029 → `IGNORED(late)` too, whatever its 14
   (S5): a bust or restatement the venue issued is newer than every report
   before it, so an older one is a redelivery. A report with no 60 is judged
   by 52; with neither, this step is skipped. This is what stops a resent old
   ack (`150=0 39=0 14=0`) from taking a working order's status back from `6`
   to `0` (08's E31): CumQty alone cannot tell two status-bearing reports
   apart, their timestamps can. A fill with a higher 14 never reaches this
   step.
5. **After a terminal status.** The order is terminal and the report is not
   reopening → the table in §4 decides.

A report that passes is applied in full (§6.4). A report whose own fields
disagree (`38 ≠ 14 + 151` on a working order) is still applied — the venue's
numbers are the venue's — and counted in `inconsistentReports`. A report that
carries no 14/151/6 adopts what it carries and leaves the rest; the stale and
late guards treat an absent 14 as equal.

## 6. Transition rules

### 6.1 `35=D` NewOrderSingle

| situation | effect |
| --- | --- |
| 11 unknown, cache complete | **create** (§3.2). `CREATED` → FULL publish: the D's fields less the **stripped tags**, plus every state tag with its initial value (`39=A`, `15035=NEW`, `15011=`11, `15038=0`, `15044=0`, `9014=`11, `9015=`NEW:R:::11, `9016=1`, `9017=N`, `9018=D`, `9019=C`, `13011`, `9021`, `9022=D`, `9023=1`, `9024=1`, `9025=0`, `9026=0`, `9030=1`, `14=0`, `151=`38, `6=0`; `9027=`41 if the D carried one; `9000` if it carried one) |
| 11 unknown, cache **incomplete** (`recovery: NONE` or a partial load, [04 §3](04-recovery-and-operations.md#3-cache-misses)) | a keyed `sow` on `sow/fix42/order_state` by `13011 =` 11 first. A record found → the entry is rebuilt from it and the D is handled as the next row says; none → create as above. Never a FULL publish over a record the cache merely did not know about (R4c) |
| 11 bound, origin `MIDSTREAM` (a late D) | the D's **descriptive** terms are applied — its message less the stripped tags, less 38/44; 38/44 too only when the record has neither and no report has been seen. `9018=D_LATE`. **Never adds a NEW** to the pending set: the venue has evidently seen the order; an **inferred** NEW already in the set (from a `150=A`) becomes requested (S7). Bookkeeping as for every applied message (§6.6). `CHANGED` |
| 11 bound, origin `D` or `D_LATE` (a resent D) | `IGNORED(duplicate request)` — a FULL publish here would erase every delta since the original |

**Stripped tags.** Every payload the machine builds from a message — the
FULL publish, the late-D delta, a `MIDSTREAM` creation from a report — omits
the FIX session header and trailer (`strip-tags`, default 8, 9, 10, 34, 43,
49, 50, 52, 56, 57, 97, 115, 116, 122, 128, 129) and **every tag in the
machine's own ranges** — 9000–9099 except 9000, 13000–13999 and 15000–15999 —
that the message carries, because a venue or OMS custom tag there would
overwrite state (R20e, S18). 9000 is
kept. The audit publishes strip nothing. `strippedTags` counts.

### 6.2 `35=G` OrderCancelReplaceRequest

| step | effect |
| --- | --- |
| resolve (41 → 37 → 11); unknown → create `MIDSTREAM` from the request (§3.2) | |
| 11 is in the pending set as **inferred** (the venue's `150=E` overtook this G on a tap that merges two sessions) | the entry becomes **requested** and takes the G's proposed 38/44 (R9). `CHANGED` |
| 11 is in the pending set as **requested** | `IGNORED(duplicate request)` |
| 41 is in the pending set as an **inferred** entry of the same action — the venue's acknowledgement named the *working* id rather than the request's, a common 4.2 shape | the entry is re-keyed to this 11, becomes **requested**, and takes the G's proposed terms (S7). `CHANGED` |
| 11 is bound but not pending — an **answered id** (its `150=5` or `35=9` overtook it) | `IGNORED(late request)`. Adding it would leave pending a request the venue has already answered |
| otherwise | add `{11: REPLACE, requested, qty=38, px=44}`; bind 11. `CHANGED`: 11, 41, 60, 9021, 9030, 15038 (=38), 15044 (=44), 15011 (=11), 15035=`REPLACE`, 9015, 9016, 9022, 9023 |
| 38/44 on the record | **untouched** — they are the acked terms |
| 39 | untouched |

### 6.3 `35=F` OrderCancelRequest

As 6.2 with action `CANCEL` and no proposed terms. 15038/15044 are **not** reset
to 0 by a cancel: they describe the newest pending REPLACE, if any (08 §4
item 3).

### 6.4 `35=8` ExecutionReport

After §5's arbitration:

| field | rule |
| --- | --- |
| 39 (venue status), 150 | adopted from the report, always |
| 17 | adopted when present; if it is `0` or `NONE` the record keeps the previous value |
| 14, 151, 6 | adopted when present; **151 forced to `0`** when the report's 39 is terminal, whether or not the venue sent 151 and whatever it sent |
| 38, 44 | adopted when present from every report **except** pending acknowledgements (`A`, `6`, `E` — they may echo proposed terms) and `150=8` (a reject echoes the *rejected* request's terms, R3) |
| order terms (`order-term-tags`, default 18, 40, 44, 59, 99, 100, 110, 111, 126) | adopted when present on `150 ∈ {0, 5, D}`. A `40=1` arriving with no 44 writes `44=0`: a market order has no price, and a merge cannot remove the limit it replaced (R14) |
| 32, 31, 30, 9028 | adopted on fills and corrects (`20=2`); 9028 = the fill's 17, and a correct's own 17 is remembered beside it. On a bust whose 19 names the last fill — its original ExecID or its latest correction's, a dialect question, so both are compared — `32=0`, `31=0` (decision 6), and 9028 **keeps** the busted ExecID so a reader can see why they are zero (S22); otherwise a bust leaves them, as it reports no trade |
| 9031 (busted set) | a bust or correct adds its 19, and a correct its own 17 (a later bust may name either); small, escaped like 9021; a bust or correct whose 19 is already there is a duplicate (S5) |
| 37 | adopted when present and not `NONE` (the record shows the latest real OrderID); bound; a changed 37 is bound to the same key (R21) |
| 9029 | `max(9029, 60)`; a `35=9` updates it too (§6.5) — a reject is the venue's clock as well (S19) |
| 9017 | `Y` if the new 39 is terminal, else `N` — a bust can take it back to `N` |
| **pending set** | by the report's ExecType, matched on the report's own tag 11 (R1): |
| — `150=5` | removes the REPLACE named by 11; 9014 = 11. **No match**: when exactly one REPLACE is pending it is removed, 9014 = *that request's* id, `inferredReplaceAnswers` counts (the venue echoes the original id, R8); otherwise nothing is removed and `unmatchedConfirms` counts |
| — `150=4` | removes the CANCEL named by 11. **No match**: removes **every** pending CANCEL (the order is canceled; a venue need not answer each cancel separately, and some echo the original id) |
| — `150=0` | removes the NEW named by 11 and sets 9014 = 11; or the REPLACE named by 11 — the cancel-then-new dialect's new-leg ack, [7.16](#716-a-replace-reported-as-cancel-then-new) — adopting the **report's** 38/44 (never the proposal's) and setting 9014 = 11. **No match**: 9014 is untouched — a venue that re-acks after a replace must not drag 9014 back to the old id (S15). At `MIDSTREAM` creation from a `150=0` or `150=5`, 9014 = 11 |
| — `150=8` | an **order** reject only when it names a pending NEW, or when the chain has never been acked (venue status `A` or absent): 39 as sent, terminal. Otherwise — it names a pending REPLACE or CANCEL, or nothing at all because the tap missed the request (S6) — a **request** reject reported as an execution report: the named request is removed if any, 38/44 are **not** adopted, 39 is handled as a `35=9`'s would be under `cancel-reject-status` (so `8` is not adopted), `requestRejectsAsReports` counts, and `unmatchedRejects` when nothing matched (R3, S6) |
| — any report that is not a pending acknowledgement | removes a pending **NEW** whatever its 11 (a venue reporting on the order has accepted or rejected it — an IOC filled on its first report, N14); 9014 = the root when that happens. This row takes precedence over the "never touch the set" row below (S12) |
| — a **fill** naming a pending REPLACE | by `fill-under-pending-id`: **`IGNORE`** (default) leaves the set alone — the fill was reported under the pending request's id, as the FIX state matrices say it should be; `ANSWERS_REPLACE_IF_TERMS_MATCH` removes the REPLACE when the fill's 38/44 equal the *proposed* terms, which is evidence the amend took effect and its `150=5` was missed (08's E8) |
| — a fill naming a pending CANCEL | nothing. A fill never answers a cancel |
| — pending acknowledgements | never remove. A `150=6`/`E`/`A` whose 11 is **not** pending and not an answered id adds an **inferred** entry (`6` → CANCEL, `E` → REPLACE, `A` → NEW), so a request whose acknowledgement overtook it is not lost (R9). The entry's id is the acknowledgement's 11, which some venues set to the *working* id rather than the request's; §6.2 re-keys it when the request arrives naming it in 41 (S7). On a D-opened chain a `150=A` for the root finds the NEW already pending and adds nothing |
| — status restatements, `150=3`, `150=C`, `150=D`, busts, corrects, reports with an unknown 150 | never touch REPLACE or CANCEL entries or 9014; a pending NEW is answered by any of them (above) |
| the pending family, 9015, 9016 | recomputed from the set when it changed |

Matching by the report's **own** tag 11 is what distinguishes the two cancels:
the confirm names X-2, so X-3 stays in the set; the reject then names X-3.
Matching by ExecType is what distinguishes an answer from a fill that merely
carries a pending request's id ([7.14](#714-a-fill-while-a-cancel-is-pending-under-the-cancels-id)).

### 6.5 `35=9` OrderCancelReject

| step | effect |
| --- | --- |
| resolve (11 → 41 → 37); unknown → `IGNORED(unmatched)`, `unmatchedRejects`; no chain is created | |
| remove the pending request named by 11, whatever its action; cross-check 434 (`1` → CANCEL, `2` → REPLACE) and log a warning on a mismatch | the pending family, 9015, 9016 recomputed |
| no such request (already answered, or never seen) | `unmatchedRejects`, nothing removed |
| 39 | per `cancel-reject-status` (§4); a terminal value adopted forces `151=0`, `9017=Y` |
| 38, 44 | **untouched** — nothing to revert, because a G never wrote them |
| 102, 58 | not on the record; the reject goes whole to `sow/fix42/rejects` |
| 9029 | `max(9029, 60)`: the reject is the venue's statement, and a re-ack older than it must not reopen an order the venue said was canceled (S19) |

`CHANGED`: 11, 41, 60, 9022, 9023, 9029, the pending family, and 39/151/9017
if the policy moved them.

### 6.6 Every message

Bind ids (§3.3). On an applied message: `9023` += 1; 11, 41, 60 and `9022`
become the message's. On an ignored message: `9025` += 1, marked dirty so the
next delta carries it. A newly bound id is published (9021, 9030) whatever the
outcome. 60 on a request is the client's clock and on a report the venue's; no
ordering logic compares the two, and 9029 holds only reports' times.

## 7. Walkthroughs

Each table is the `sow/fix42/order_state` record after the message, showing
only the fields that move. `pending` is the set; the `15035/15011` columns are
what a consumer of the old blotter reads. "wire" is what the delta carries
beyond the always-sent bookkeeping (11, 41, 60, 9022, 9023). Pending entries
are shown as `id:ACTION`, with `~` for an inferred one.

### 7.1 Two cancels: confirm, then reject (the motivating case)

Order `X`, 1000 @ 50.00, partially filled 300.

| message | pending | 39 | 151 | 9017 | 15035 | 15011 | 9016 | wire |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `D 11=X-1` | {X-1:NEW} | A | 1000 | N | NEW | X-1 | 1 | **publish**, whole record |
| `8 150=0 39=0 11=X-1 37=ORD` | {} | 0 | 1000 | N | NONE | NONE | 0 | 37 17 150 39 38 44 14 151 6 9014=X-1 pending family 9015 9016 9029 |
| `8 150=1 39=1 11=X-1 14=300 151=700` | {} | 1 | 700 | N | NONE | NONE | 0 | 17 150 39 38 44 14 151 6 32 31 30 9028 9029 |
| `F 11=X-2 41=X-1` | {X-2:CANCEL} | 1 | 700 | N | CANCEL | X-2 | 1 | 9021 9030 15011 15035 9015=`CANCEL:R:::X-2` 9016 |
| `F 11=X-3 41=X-1` | {X-2, X-3} | 1 | 700 | N | CANCEL | X-3 | 2 | 9021 9030 15011 15035 9015=`CANCEL:R:::X-2;CANCEL:R:::X-3` 9016 |
| `8 150=4 39=4 11=X-2 41=X-1` | {X-3} | **4** | **0** | **Y** | CANCEL | X-3 | 1 | 17 150 39 38 44 14 151=0 6 9017 15011 15035 9015=`CANCEL:R:::X-3` 9016 9029 |
| `9 11=X-3 41=X-1 434=1 39=6` | {} | **4** | 0 | Y | **NONE** | NONE | 0 | pending family 9015 9016 9029 (39 untouched: terminal) |

Final: `39=4 150=4 151=0 9017=Y 15035=NONE 9016=0 9022=9`. Canceled, nothing
pending, and the record says the last thing that happened was a reject —
which is true. Compare [01 §2](01-problem-the-two-cancels.md#2-what-the-shipped-rulebook-does-with-it).

### 7.2 Two cancels: reject, then confirm

Same order, the venue answers in the other sequence.

| message | pending | 39 | 151 | 9017 | 15035 | 15011 | note |
| --- | --- | --- | --- | --- | --- | --- | --- |
| … as 7.1 through the second F | {X-2, X-3} | 1 | 700 | N | CANCEL | X-3 | |
| `9 11=X-3 434=1 39=6` | {X-2} | 6 | 700 | N | CANCEL | X-2 | `39=6` adopted: not terminal, not 8, and a CANCEL is still pending. 15011 falls back to the remaining request |
| `8 150=4 39=4 11=X-2` | {} | 4 | 0 | Y | NONE | NONE | |

Both arrival orders converge on the same record. That is the property the
stateless design lacked.

### 7.3 A third cancel after the order is canceled

| message | pending | 39 | 9017 | 15035 | note |
| --- | --- | --- | --- | --- | --- |
| … 7.1 complete | {} | 4 | Y | NONE | |
| `F 11=X-4 41=X-1` | {X-4:CANCEL} | 4 | Y | CANCEL | status untouched: a request never moves 39 |
| `9 11=X-4 434=1 39=4` | {} | 4 | Y | NONE | |

### 7.4 Amend and cancel both in flight, reject for the cancel (contract edge 4)

| message | pending | 39 | 38 | 44 | 15035 | 15011 | 15038 | 15044 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `D 11=A-1 38=10000 44=185.5` + ack | {} | 0 | 10000 | 185.5 | NONE | NONE | 0 | 0 |
| `G 11=A-2 41=A-1 38=12000 44=185.75` | {A-2:REPLACE} | 0 | 10000 | 185.5 | REPLACE | A-2 | 12000 | 185.75 |
| `F 11=A-3 41=A-2` | {A-2, A-3} | 0 | 10000 | 185.5 | CANCEL | A-3 | **12000** | **185.75** |
| `9 11=A-3 434=1 39=0` | {A-2} | 0 | 10000 | 185.5 | REPLACE | A-2 | 12000 | 185.75 |
| `8 150=5 39=0 11=A-2 38=12000 44=185.75` | {} | 0 | **12000** | **185.75** | NONE | NONE | 0 | 0 |

15038/15044 keep describing the pending amend while the cancel is the newest
request (08 §4 item 3). The reject removed only its own request; the confirm
adopted the venue's numbers and moved 9014 to `A-2`.

### 7.5 Amend rejected (the NVDA scenario)

| message | pending | 39 | 38 | 44 | 15035 | 15038 | 15044 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D 4000 @ 121.80, ack, partial 1000 | {} | 1 | 4000 | 121.8 | NONE | 0 | 0 |
| `G 11=N-2 41=N-1 38=6000 44=122.1` | {N-2:REPLACE} | 1 | 4000 | 121.8 | REPLACE | 6000 | 122.1 |
| `9 11=N-2 434=2 39=1` | {} | 1 | **4000** | **121.8** | NONE | 0 | 0 |

Identical to the old blotter's verified result
([04 §2](../../docs/fix42-view/04-pending-state-without-a-state-machine.md#measured-result)):
the acked terms were never touched, so nothing is reverted.

### 7.6 Duplicate, stale and late reports

| message | outcome | 39 | 14 | 9023 | 9025 |
| --- | --- | --- | --- | --- | --- |
| `8 150=1 39=1 17=E-3 14=800 60=T3` | applied | 1 | 800 | 3 | 0 |
| `8 150=1 17=E-3 14=800` (PossDup resend) | `IGNORED(duplicate)` | 1 | 800 | 3 | 1 |
| `8 150=1 17=E-2 14=500` (an old fill redelivered) | `IGNORED(stale)` | 1 | 800 | 3 | 2 |
| `8 150=6 39=6 17=E-4 14=800 60=T4` | applied: pending cancel | 6 | 800 | 4 | 2 |
| `8 150=0 39=0 17=E-1 14=0 60=T1` (an old ack, outside the ring) | `IGNORED(stale)`: 0 < 800 | 6 | 800 | 4 | 3 |
| `8 20=3 39=1 14=800 60=T2` (an old status snapshot, no 17) | `IGNORED(late)`: 14 equal, T2 < 9029 = T4 | 6 | 800 | 4 | 4 |
| `8 20=1 19=E-3 17=E-5 150=1 39=1 14=500 60=T5` (a bust of E-3) | applied — reopening reports are exempt from every guard | 1 | 500 | 5 | 4 |

### 7.7 Report before request (mid-stream start), then the late D

| message | key | 9018 | 39 | 38 | 1/55/54 | 21/40/59/100 | pending |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `8 150=0 39=0 11=M-1 37=ORD-M 38=500 44=10 1=ACC 55=SYM 54=1` | **M-1** (9019=C) | MIDSTREAM | 0 | 500 | from the 8 | absent | {} |
| `D 11=M-1 38=500 44=10 21=1 40=2 59=0 100=XNAS …` | M-1 | D_LATE | 0 | 500 | unchanged | **from the D** | {} — never a NEW on a late D |

The chain is keyed by the report's 11 — which is the root, as it usually is
when the tap merely missed the D — and the D joins it through the same id.
The D's 38/44 are not applied (a report has already stated them) and its
arrival is a delta, not a FULL publish.

### 7.8 Rejected before ever working

| message | pending | 39 | 151 | 9017 |
| --- | --- | --- | --- | --- |
| `D 11=R-1 38=200` | {R-1:NEW} | A | 200 | N |
| `8 150=8 39=8 11=R-1 37=NONE 103=1 151=0` | {} | 8 | 0 | Y |

`150=8` naming the NEW is an order reject; 37 stays absent on the record
because `NONE` is never written; 103/58 are on `sow/fix42/execs_audit`.

### 7.9 Bust after a full fill reopens the order (contract edge 6)

| message | 39 | 14 | 151 | 9017 |
| --- | --- | --- | --- | --- |
| `8 150=2 39=2 14=3000 151=0` | 2 | 3000 | 0 | Y |
| `8 20=1 19=… 150=1 39=1 14=1800 151=1200` | **1** | 1800 | 1200 | **N** |

### 7.10 A report on a child chain without tag 9000

With the shipped topics nothing changes: parents and children share the
blotter and the record still carries 9000 from the D. With `sow/{scope}/…`
topics, the old publisher needed every venue message to echo 9000 to pick the
topic; here `OrderState.parentOrderId` supplies it.

### 7.11 A cancel reject with `37=NONE`

`9 11=U-2 41=U-1 37=NONE 434=1` for an order whose D was never acked:
resolution skips 37, finds U-2 in the index (bound when the F was seen), removes
the request. Venue status stays `A`; the NEW request is still in the set.

### 7.12 Cancel confirmed, then a late execution

Order 1000 @ 50, 300 filled, canceled at `60=T9`. The venue then reports a
trade of 200 it had matched before the cancel took effect, with the default
`late-fill-after-terminal: APPLY_ECONOMICS`.

| message | 39 | 14 | 151 | 6 | 32 | 9017 | 9026 | note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `8 150=4 39=4 11=X-2 14=300 151=0 6=49.9 60=T9` | 4 | 300 | 0 | 49.9 | — | Y | 0 | canceled; 9029=T9 |
| `8 150=1 39=1 17=E-9 14=500 151=500 6=49.94 32=200 31=50 60=T10` | **4** | **500** | **0** | **49.94** | **200** | Y | **1** | late execution: economics adopted, status kept, 151 kept at 0 |
| `8 150=1 39=1 17=E-9 …` (resent) | 4 | 500 | 0 | 49.94 | 200 | Y | 1 | duplicate |
| `8 150=1 39=1 17=E-8 14=300 …` (the fill the cancel already counted) | 4 | 500 | 0 | 49.94 | 200 | Y | 1 | stale: 300 < 500 |

Had the 300-share fill's report arrived late **before** the 200-share one,
its 14 would have equalled the record's and only its 17/32/31/30/9028 would
have been adopted. With `ADOPT_VENUE` the second row would read `39=1 151=500
9017=N`: the venue's statement, taken at face value.

### 7.13 Bust and correct after the order is closed out

| message | 39 | 14 | 151 | 6 | 32 | 9017 | note |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `8 150=1 17=E-2 14=800 32=300 31=50.2` ; `8 150=4 39=4 14=800 151=0 6=50.1` | 4 | 800 | 0 | 50.1 | 300 | Y | canceled with two fills behind it; 9028=E-2 |
| `8 20=1 19=E-2 150=4 39=4 14=500 151=0 6=50.0` | 4 | **500** | 0 | **50.0** | **0** | Y | bust of the **last** fill: restated absolutes adopted, `32=0 31=0` because 19 = 9028 (decision 6); the venue's 39 on a bust mirrors the current status, so the order stays canceled |
| `8 20=2 19=E-1 150=4 39=4 14=500 6=49.95 32=500 31=49.95` | 4 | 500 | 0 | **49.95** | 500 | Y | the surviving fill repriced |
| `8 150=2 39=2 14=1000 151=0` *(a different order)* | 2 | 1000 | 0 | — | — | Y | filled |
| `8 20=1 19=… 150=1 39=1 14=600 151=400` | **1** | 600 | **400** | — | — | **N** | bust after a full fill reopens it, as [7.9](#79-bust-after-a-full-fill-reopens-the-order-contract-edge-6) |

### 7.14 A fill while a cancel is pending, under the cancel's id

The shape the FIX state matrices prescribe and `OrderChain` emits (R1): once
a request is acknowledged, the venue's reports carry the request's ClOrdID.

| message | pending | 39 | 14 | 15035 | 15011 | note |
| --- | --- | --- | --- | --- | --- | --- |
| `D 11=X-1` ; `8 150=0 39=0 11=X-1` | {} | 0 | 0 | NONE | NONE | |
| `F 11=X-2 41=X-1` | {X-2:CANCEL} | 0 | 0 | CANCEL | X-2 | |
| `8 150=6 39=6 11=X-2 41=X-1` | {X-2:CANCEL} | 6 | 0 | CANCEL | X-2 | pending ack; the set is untouched |
| `8 150=1 39=6 11=X-2 41=X-1 17=E-2 14=300 151=700 32=300` | **{X-2:CANCEL}** | 6 | 300 | **CANCEL** | **X-2** | a fill carrying the cancel's id is **not** an answer: the cancel stays pending |
| `9 11=X-2 41=X-1 434=1 39=1` | {} | 1 | 300 | NONE | NONE | too late to cancel; `39=1` adopted (not terminal, not 8, not a pending status) |

The first version's rule — any non-pending-ack report answers the request its
11 names — would have cleared the cancel on the fill and left the reject
unmatched. The same sequence with a G in place of the F keeps the REPLACE
pending through the fill under `fill-under-pending-id: IGNORE`, and a later
`150=5` or `35=9` answers it.

### 7.15 An amend rejected by an execution report

Some gateways reject a cancel/replace with a `150=8` that echoes the
request's terms (R3).

| message | pending | 39 | 38 | 44 | 9014 | 9017 | note |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D 10000 @ 185.5, ack, partial 2000 | {} | 1 | 10000 | 185.5 | A-1 | N | |
| `G 11=A-2 41=A-1 38=12000 44=185.75` | {A-2:REPLACE} | 1 | 10000 | 185.5 | A-1 | N | |
| `8 150=8 39=8 11=A-2 41=A-1 38=12000 44=185.75 14=2000 151=8000 103=0` | {} | **1** | **10000** | **185.5** | **A-1** | **N** | a request reject in report form: the REPLACE is removed; 38/44 are not adopted from a `150=8`; `39=8` is treated as a 9's would be — not adopted (the `8` exclusion); 9014 unchanged. `requestRejectsAsReports=1` |

With the mild form (`39=1`), 39 is adopted and the result is the same record.
The first version's rule adopted `12000/185.75` into 38/44 — the exact
failure the module exists to prevent — and marked the order terminal.

### 7.16 A replace reported as cancel-then-new

A real FIX 4.2 dialect: the venue cancels the old leg and acks the new one
instead of sending `150=5` (R2).

| message | pending | 39 | 38 | 151 | 9017 | 9014 | note |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D 10000, ack, partial 2000 | {} | 1 | 10000 | 8000 | N | A-1 | |
| `G 11=A-2 41=A-1 38=12000` | {A-2:REPLACE} | 1 | 10000 | 8000 | N | A-1 | |
| `8 150=4 39=4 11=A-1 37=ORD 14=2000 151=0 60=T5` | {A-2:REPLACE} | **4** | 10000 | **0** | **Y** | A-1 | the old leg canceled. 11 names no pending CANCEL, and the no-match rule removes only CANCELs, so the REPLACE stays |
| `8 150=0 39=0 11=A-2 41=A-1 37=ORD 17=E-9 14=2000 151=10000 38=12000 60=T6` | **{}** | **0** | **12000** | **10000** | **N** | **A-2** | a non-fill report with a non-terminal 39 on a terminal order, `T6 ≥ 9029`: a venue-asserted reopen, applied in full; its `150=0` names the pending REPLACE and answers it. `reopenedByVenue=1` |
| `8 150=1 39=1 11=A-2 17=E-10 14=3000 151=9000` | {} | 1 | 12000 | 9000 | N | A-2 | an ordinary fill |

The record reads canceled for one message. A deployment that connects to
such a venue suppresses even that with a `beforeApply` hook that recognises
the cancel leg — a `150=4` whose 11 is the working id while a REPLACE is
pending — and turns it into a no-op ([07 §2](07-extension-points.md#2-tier-two-hooks)).
The first version's detail-only row swallowed the `150=0` and left a live
order permanently canceled. Two edges of this dialect: the `150=0` reopens
because it **answers the pending REPLACE**, so a venue sending `60` in whole
seconds with T5 = T6 still gets it right (S4); and had a CANCEL also been
pending, the cancel leg's no-match rule would have removed it — transient,
and the reason `unmatchedRejects` is not zero on such a venue (S16).

### 7.17 A replace confirm that echoes the original id

| message | pending | 38 | 9014 | note |
| --- | --- | --- | --- | --- |
| `G 11=A-2 41=A-1 38=12000` | {A-2:REPLACE} | 10000 | A-1 | |
| `8 150=5 39=0 11=A-1 37=ORD 38=12000` | **{}** | **12000** | **A-2** | 11 names no pending request; exactly one REPLACE is pending, so it is the one confirmed (R8). 9014 is the *request's* id, the one the venue will expect as the next 41. `inferredReplaceAnswers=1` |

With two REPLACEs pending nothing is removed and `unmatchedConfirms` counts;
the terms are still adopted.

### 7.18 A pending acknowledgement that overtakes its request

| message | pending | 39 | note |
| --- | --- | --- | --- |
| `8 150=6 39=6 11=X-2 41=X-1 37=ORD` | {X-2:CANCEL~} | 6 | X-2 is unknown: an **inferred** CANCEL is added (R9) |
| `F 11=X-2 41=X-1` | {X-2:CANCEL} | 6 | the request fills in its inferred entry; not a duplicate, not late |
| `8 150=4 39=4 11=X-2` | {} | 4 | |

The first version ignored the F as "already bound" and left the venue's
pending cancel unrepresented.

### 7.19 A cancel reject that carries a terminal status

| message | pending | 39 | 151 | 9017 | note |
| --- | --- | --- | --- | --- | --- |
| D 1000, ack, partial 300 | {} | 1 | 700 | N | |
| `F 11=X-2 41=X-1` | {X-2:CANCEL} | 1 | 700 | N | |
| `9 11=X-2 434=1 39=4 102=0` ("too late — already canceled"; the `150=4` never reached this tap) | {} | **4** | **0** | **Y** | the adopted 39 is terminal, so 151 is forced to 0 as a report would (R6). `terminalFromReject=1` |

### 7.20 A fill as the first report (IOC filled at once)

| message | pending | 39 | 14 | 9014 | note |
| --- | --- | --- | --- | --- | --- |
| `D 11=I-1 38=500 59=3` | {I-1:NEW} | A | 0 | I-1 | |
| `8 150=2 39=2 11=I-1 37=ORD 14=500 151=0` | **{}** | 2 | 500 | I-1 | any non-pending-ack report answers the NEW (N14); here it is the fill |

An IOC that misses — `8 150=4 39=4 11=I-1 151=0` as the first report — ends
the same way with `39=4`.

### 7.21 An OrderID that equals another order's ClOrdID

| message | resolves to | key | note |
| --- | --- | --- | --- |
| `D 11=123456 38=100` | — | **123456** (9019=C) | order A opens |
| `8 150=0 39=0 11=B-1 37=123456 14=0 151=500` (order B, first seen mid-stream) | nothing: `123456` is in the ClOrdID map, not the OrderID map | **B-1** (9019=C): the report's 11, which the mint order prefers; 37 is bound into the OrderID map | B's reports never touch A (S1). Had the report carried no 11, candidate 37 = `123456` would equal A's key and be skipped, `keyCollisions=1` |
| `8 150=0 39=0 11=X-1 37=X-1` (a venue whose OrderID equals the ClOrdID) | X-1's chain, via 11 | unchanged | 37 bound into the OrderID map; not a collision |

### 7.22 A `150=8` for a request the tap never saw

| message | pending | 39 | 38 | 9017 | note |
| --- | --- | --- | --- | --- | --- |
| D 10000 @ 185.5, ack, partial 2000 | {} | 1 | 10000 | N | the G `11=A-2` goes out on a session this tap does not see |
| `8 150=8 39=8 11=A-2 41=A-1 37=ORD 38=12000 44=185.75 14=2000 151=8000` | {} | **1** | **10000** | **N** | no NEW is pending and the chain is acked, so this is a request reject whatever it names: 38/44 not adopted, `39=8` not adopted, `requestRejectsAsReports=1 unmatchedRejects=1` (S6) |

### 7.23 A pending acknowledgement that names the working id

| message | pending | note |
| --- | --- | --- |
| `8 150=E 39=E 11=M-1 37=ORD` (first message seen; the venue acknowledges a replace under the *working* id) | {M-1:REPLACE~} | an inferred REPLACE under the working id |
| `G 11=M-2 41=M-1 38=12000` | {M-2:REPLACE} | 41 names the inferred entry: re-keyed to M-2, requested, terms filled in (S7) — not a second entry |
| `8 150=5 39=0 11=M-2 38=12000` | {} | answered by match; 9014 = M-2 |

## 8. What the machine does not do

- It does not compute a quantity. Every 14/151/6/38/44 on the record is a
  value the venue sent; the machine's own writes are `151=0` on a terminal
  status, `32=0 31=0` on a bust of the last fill, and `44=0` on a change to a
  market order.
- It does not reorder. A session's order is trusted; the guards in §5 make
  out-of-order *redelivery* harmless, they do not repair a feed that
  interleaves two sessions for one order beyond the inferred-entry rule.
- It does not time anything out. A request the venue never answers stays in
  the pending set until the order is evicted ([04 §4](04-recovery-and-operations.md#4-eviction));
  the `9016 > 0 AND 9017 = 'Y'` filter on the blotter finds them.
- It does not handle `35=Q`, a request rejected by `35=j` or `35=3` (which
  needs a MsgSeqNum-to-ClOrdID ring, a listed follow-up), nor publish
  per-execution dispositions or lifecycle events. Those are additive on this
  cache and are follow-ups in [05](05-implementation-plan.md).

## 9. Unrouted and unparseable messages

Decision 5. A message no route claims — `35=Q`, `H`, `j`, `3`, list and
multileg types — a message missing a topic's key tag, or bytes that do not
parse as FIX, are handled by `mode`. The 13011 trailer on a catch-all publish
is present when the message resolved to a chain and omitted when it did not
(S20):

| | `strict` | `lenient` |
| --- | --- | --- |
| unrouted type | the machine raises; the publisher stops (the stateless publisher's stance, right for tests: a missing rule fails the first run that hits it) | published whole, with 13011 when it resolves, to `fix42/unrouted` — a journalled, non-SOW topic (no key needed, every message kept in arrival order, replayable by bookmark); `IGNORED(unrouted)`; a counter per 35 value; a rate-limited WARN |
| unparseable bytes | raise | wrapped as a fix message (`9032=<base64 of the bytes>`, `9033=<reason>`) on `fix42/malformed`, same topic shape; `malformed` counts |
| a known type missing a topic's key tag (a `20=3` with no 17; a G with no 11) | raise | **routing only**: the topic whose key is missing is skipped and the message goes whole to `fix42/unrouted` instead; the machine applies it regardless, so a 17-less status restatement still reaches the blotter (S10) |
| a report with a 150 value no rule names | applied as *other* or *fill* (§5 step 1); the value counted in the per-150 histogram — **not** unrouted (S11) | the same |
| default | the test profile | the production profile |

Lenient mode keeps the property strict mode was protecting: every byte
received lands somewhere journalled, so the day can be replayed into a later
version that handles `35=Q` or `35=j`, and the counters make the gap visible
rather than silent. Admin messages (`0`, `1`, `2`, `4`, `5`, `A`) are the FIX
engine's and never reach the publisher on a real session.
