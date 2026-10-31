# 8. Architecture review, round 1: findings and dispositions

An independent adversarial review of docs 01–07 as they stood at commit
`2b38402`, read against the [contract](../../docs/fix42/01-fix42-messages-and-state-machine.md),
the mock venue (`OrderChain`), the stateless publisher's rulebook, the
`fix42-chaining` server flow and the AMPS delta-merge notes. The reviewer was
asked to find cases the design does not cover, covers wrongly, or leaves
ambiguous, and to challenge the verdicts in [06](06-case-coverage.md) rather
than take them as given.

This document is the register of what it found, condensed but complete, with a
disposition against each finding. Docs 01–07 **have been revised** for every
finding, under the six decisions in §6; §8 says where each finding landed.

Dispositions: **accept, rule** — a rule in [03](03-state-machine.md) changes
and [06](06-case-coverage.md) gets rows; **accept, design** — an architecture,
operations or plan addition; **decide** — accepted in substance, but the
remedy changes something a consumer or operator sees and needs a call;
**partly** — accepted with a narrower remedy than proposed.

---

## 1. What the review found, in short

The headline case holds: two cancels converge on `39=4 9013=NONE` in either
arrival order, and "venue truth for 39, requests only in the pending set" is
the right split. But three rules tightened while filling in the coverage
matrix were tightened against the design's *own* assumptions about venue
dialects, and break on message shapes that are routine on real drop copies.
One of those shapes is produced by this repository's own mock venue. The
operations design also assumes an AMPS client that reports failures and
reconnects, which the plain `Client` the architecture names does not.

The five that matter most:

1. **A fill reported while a cancel or amend is pending carries the pending
   request's ClOrdID** (the FIX 4.4 state matrices say so; `OrderChain`
   does so). The "any non-pending-ack report answers the request its tag 11
   names" rule therefore resolves the request on the fill, wrongly (R1), and a
   `150=8` echoing a pending amend writes the *rejected* terms into 38/44
   (R3).
2. **The after-terminal "detail-only" row swallows every non-fill report with
   a non-terminal 39**, so a venue that reports a replace as cancel-then-new,
   or re-acks after an erroneous terminal report, leaves a working order
   permanently `39=4 151=0` (R2).
3. **The "second record" outcome is common, not rare**: `recovery: NONE`, the
   documented `/9017 = 'N'` partial load, and a D redelivered against a
   populated SOW each duplicate or destroy records for ordinary D-opened
   orders, and duplicates double-count in the exposure views (R4).
4. **Publish failures are invisible**: a plain `Client`, no publish store, no
   `FailedWriteHandler`, apply-before-publish with no rollback, and a FIX
   engine that acknowledges before AMPS has the bytes. Cache and SOW diverge
   silently (R5).
5. The 9015/9021 encodings are unparseable for ids containing their
   delimiters (R11), the record has no schema version (R17), and the
   documents give three different id-resolution orders (§4).

## 2. Findings

### R1 — A report echoing a pending request's ClOrdID is treated as its answer — High — **accept, rule**

Sequence, which `OrderChain.execution()` emits after `cancelRequest()` or
`amend()` because both advance the id every report carries:

```
D 11=X-1 ; 8 150=0 39=0 11=X-1 37=ORD
F 11=X-2 41=X-1                                    pending {X-2:CANCEL}
8 150=1 39=1 11=X-2 41=X-1 17=E-2 14=300 151=700    a fill while the cancel is pending
9 11=X-2 41=X-1 434=1 39=2                          too late: filled
```

and the amend variant: `G 11=A-2 …` ; `8 150=1 39=1 11=A-2 41=A-1 38=10000 44=185.5 …` (venue has **not** accepted) ; `9 11=A-2 434=2`.

- **Docs say**: 03 §1 "answering report: any report that is not a pending
  acknowledgement … answers the pending request named by its own tag 11,
  whatever that request's action"; 03 §6.4 pending-set and 9014 rows; 06 E8,
  N14, E32 ("fill while PENDING_CANCEL … pending stays — handled").
- **Goes wrong**: the fill removes X-2; the record reads `9013=NONE 9016=0`
  while the cancel is unanswered; the later 9 is "unmatched" on a healthy
  flow. In the amend variant the fill removes A-2 and **rotates 9014 to
  A-2, an id the venue then rejects**, for the life of the order. A `20=3`
  status report echoing the id does the same.
- **Disposition**: answering is explicit by ExecType again: `150=0`/`8`
  answer a NEW, `150=5` a REPLACE, `150=4` a CANCEL, matched by exact 11
  with the no-match fallbacks. One addition keeps N14 (an IOC or market
  order whose first report is the fill): **any report whose 11 is the D's
  own id and whose 150 is not `A` answers the NEW**, because the venue
  reporting on the order means it accepted it. Fills never answer a CANCEL.
  Fills under a pending amend's id answer the REPLACE only under a dialect
  knob, `fill-under-pending-id: IGNORE (default) | ANSWERS_REPLACE_IF_TERMS_MATCH`,
  the latter removing the REPLACE only when the fill's 38/44 equal the
  *proposed* terms (which is the evidence E8 actually relied on). `20=3`
  never touches the set or 9014. E8 and E32 become **policy** rows.

### R2 — The after-terminal "detail-only" row swallows non-fill reports; a venue reopen without a bust is unrepresentable — High — **accept, rule**, with a tiebreak to decide (§6)

Sequence (replace reported as cancel + new, a real 4.2 dialect):

```
D 11=A-1 38=10000 ; ack ; 8 150=1 14=2000 151=8000
G 11=A-2 41=A-1 38=12000
8 150=4 39=4 11=A-1 37=ORD 14=2000 151=0                 the old leg canceled
8 150=0 39=0 11=A-2 41=A-1 37=ORD 17=E-9 14=2000 151=10000 38=12000   the new leg acked
8 150=1 39=1 11=A-2 17=E-10 14=3000 151=9000 32=1000
```

- **Docs say**: 03 §4 after-terminal table, row "39 not terminal, 14 equal
  to the record's, 17 new: 17, 150, 32, 31, 30 are adopted, nothing else
  moves"; 06 E21, E29 "handled".
- **Goes wrong**: the `150=0` for A-2 has equal CumQty and a new ExecID, so
  it is detail-only: 39 stays `4`, 151 stays `0`, 38 is not adopted, the
  REPLACE stays pending; every later fill is a "late execution" with 39 kept
  at `4`. The exposure view shows zero leaves for a live 9,000-share order.
  No value of `late-fill-after-terminal` helps, because `ADOPT_VENUE` keys
  on a *higher* CumQty. The same row swallows a re-ack or a status snapshot
  that says the order is working after an erroneous terminal report.
- **Disposition**: the detail-only row is restricted to fills
  (`150 ∈ {1, 2}`). A non-fill, non-pending-ack report whose own 39 is
  non-terminal, arriving on a terminal order, is either a **venue-asserted
  reopen** (apply in full, recompute 9017, count `reopenedByVenue`) or an
  **out-of-order late report** (an ack overtaken by the cancel confirm of an
  IOC, say — applying it would wrongly reopen). The two are
  indistinguishable by content; the design author proposes TransactTime as
  the tiebreak: a report whose 60 is earlier than the last applied report's
  is late and absorbed, otherwise it is a reopen — under a knob,
  `non-fill-after-terminal: ADOPT_VENUE_IF_LATER (default) | ADOPT_VENUE | IGNORE`.
  Whether 60 is trusted for this is a decision (§6). A `150=0` whose 11
  names a pending REPLACE also answers it (the cancel+new dialect's confirm).
  The dialect goes into 06 as **policy/hook**, with a `beforeApply` recipe
  for suppressing the cancel leg.

### R3 — A `150=8` naming a pending REPLACE adopts the rejected proposal into 38/44 and may mark the order terminal — High — **accept, rule**

Sequence (gateways that reject a cancel/replace with an ExecutionReport
rather than a `35=9`, echoing the request's terms):

```
D 11=A-1 38=10000 44=185.5 ; ack ; partial 2000
G 11=A-2 41=A-1 38=12000 44=185.75
8 150=8 39=1 11=A-2 41=A-1 37=ORD 38=12000 44=185.75 14=2000 151=8000 103=0   (or 39=8)
```

- **Docs say**: 03 §6.4 "when that request was a REPLACE, the report's 38/44
  are adopted even if it is not a 150=5"; 38/44 "adopted when the report
  carries them and 150 ∉ {A, 6, E}".
- **Goes wrong**: 38/44 become `12000/185.75`, the rejected proposal — the
  exact failure [docs/fix42-view/02 §4.2](../../docs/fix42-view/02-amps-view-feasibility.md#42-what-the-chained-blotter-still-gets-wrong)
  item 1 describes and this module exists to prevent. 9014 rotates to A-2.
  With `39=8` the order is also marked terminal while it works.
- **Disposition**: 38/44 are adopted only from `150 ∈ {0, 5, D}` and from
  fills, busts and corrects (which restate the current terms); never from
  `150 ∈ {8, 3, C}`. A `150=8` whose 11 matches a pending REPLACE or CANCEL
  (not the NEW) is a *request* reject: remove the request, treat 39 under
  `cancel-reject-status`, count `requestRejectsAsReports`.

### R4 — The "second record" is the common case under `recovery: NONE`, the `/9017 = 'N'` partial load, and a D replayed against a populated SOW; duplicates double-count in the views — High — **accept, design**

Sequences:

- (a) `recovery: NONE` restart with order X working: `8 150=1 11=X-1 37=ORD
  14=500 151=500` → cache miss → `MIDSTREAM` keyed `ORD`. The SOW now holds
  `9020=X-1` frozen at its last 151 **and** `9020=ORD` live; the exposure
  views sum both.
- (b) Partial load; a D-opened order canceled earlier; `8 20=1 19=E-2 150=4
  39=4 37=ORD 14=300` (late bust) → miss; no tombstone, because the order was
  *not loaded* rather than *evicted* → second record.
- (c) A D whose key already has a SOW record but no cache entry (a PossDup
  resend after a `NONE` restart; a day replayed into an unwiped SOW; a reused
  ClOrdID against a `persistent` SOW): `CREATED` → **`publish` of the whole
  record replaces it**, erasing every fill and status since.

- **Docs say**: 04 §3 case 2 "a record under that key (… its key was its
  OrderID)" — false for D-opened orders, whose key is the root ClOrdID;
  04 §2.1 presents the partial load as a benign trade-off; 02 §5 publishes a
  D whole whenever "the chain is unknown"; 06 E47 "handled", E48 "limited".
- **Goes wrong**: wrong exposure totals, two records for one order, and in
  (c) a destroyed record. `CacheMissIT` in 05 never checks for a pre-existing
  D-keyed record.
- **Disposition**: (1) the keyed lookup on a miss becomes the default for any
  message carrying 37 (one `sow` by `/37`, hash-indexed) rather than a
  later-phase option — cheap, because after a complete load a miss is
  genuinely rare; (2) a partial load still reads every record and builds
  tombstones (`37 → 9020`, 40 B each) for the ones it does not keep, which
  is what tombstones are for; (3) a D on a cache miss does a keyed `sow` by
  `9020 = 11` before a FULL publish **only when the cache is known
  incomplete** (`recovery: NONE` or a partial load) — after a complete load
  a D miss is a new order and the hot path stays query-free; (4)
  `recovery: NONE` refuses to start against a non-empty
  `sow/fix42/order_state` without an explicit override; (5) 04 documents
  that `/9018 = 'MIDSTREAM'` records inflate the views, with a
  `midstreamWithKnownOrderId` counter.

### R5 — Publish failures, disconnects and the asynchronous send buffer are invisible; cache and SOW diverge silently — High — **accept, design**

- **Condition**: `OrderStatePublisher` is "the shape of `AmpsDeltaPublisher`"
  (02 §2): a plain `Client`, fire-and-forget `publish`/`deltaPublish`, no
  publish store, no `FailedWriteHandler`, `flush()` only at the end of a run;
  the machine mutates the entry *before* the publish is attempted.
- **Sequences**: `8 150=4 …` applied → cache `39=4`; `deltaPublish` throws
  `DisconnectedException` (a plain `Client` does not reconnect, as
  [docs/fix42-view/05 §6](../../docs/fix42-view/05-the-generated-sow-key.md#6-where-this-is-pinned)
  records), or is accepted into the socket and rejected server-side (an
  unparseable payload; a `delta_publish` with no record if the
  verify-on-build is negative). The FIX engine has already acknowledged the
  message; the record never shows the cancel; nothing observes it. On a
  crash, everything in the client's outbound buffer is lost, not "the
  message in hand" (04 §7), and because QuickFIX/J advances its incoming
  sequence number when `fromApp` returns, the feed's resend does **not**
  cover those messages — nor does `recovery: JOURNAL`, since the audit
  publishes were in the same buffer.
- **Disposition**: `HAClient` with a publish store (memory or file) and a
  `FailedWriteHandler` counting `publishFailures`, logging the payload and
  marking the entry dirty-all so the next outcome republishes the whole
  record; a two-phase machine (`plan(frame) → Outcome` pure, `commit()`
  mutates only after the client accepted the publish into its store) so an
  exception leaves the cache unchanged and the FIX message can be
  redelivered; the FIX engine's sequence-number persistence coupled to a
  bounded `publishFlush` window, or the loss bound stated plainly in 04 §7.
  This is the shape `fix-pub-seqno` ([its docs/04](../../fix-pub-seqno/docs/04-chosen-design-and-failure-matrix.md))
  and `amps-connectors` already use. Assumption to verify: the Java client
  reports server-side publish rejections only through `FailedWriteHandler`,
  which requires a publish store.

### R6 — A `35=9`'s tag 39 adopted without the consequences a report would carry — Medium — **accept, rule**

- **Sequences**: `F 11=X-2 41=X-1 ; 9 11=X-2 434=1 39=4 102=0` (too late,
  already canceled; the `150=4` never seen on this tap): `WHEN_NOT_TERMINAL`
  adopts `39=4` → `9017=Y` but **151 stays 700**, a terminal record with
  live exposure. And `F ; 9 434=1 39=6` from a venue that sends
  status-at-request-time: `39=6` "pending cancel" with `9016=0` forever.
- **Docs say**: 03 §4 policy table excludes only `8`; 03 §6.5 CHANGED list
  names 39/9017 but not 151; 03 §7.2 shows `39=6` adopted and calls it right.
- **Disposition**: when a 39 adopted from a 9 is terminal, force `151=0`
  exactly as a report would and count `terminalFromReject`; never adopt
  `6`/`E`/`A` from a 9 unless a request of that kind remains in the set.

### R7 — A MIDSTREAM chain created from a G or F adopts the *proposed* 38/44 as acked; the late-D rule is contradictory — Medium — **accept, rule**

- **Sequence** (any cache miss): `G 11=N-2 41=N-1 38=6000 44=122.1` → record
  `38=6000 44=122.1 39=A 9018=MIDSTREAM` ; `9 11=N-2 434=2` → nothing
  reverts.
- **Docs say**: 03 §3.2 creation table "whatever the message carries:
  38/44 …"; 02 §4 calls 38/44 "acked terms". On the late D, 02 §5 says
  "never 38/44" while 03 §6.1 says "38/44 only if no report has supplied
  them yet"; and a request-created chain sits at `39=A` though the order was
  plainly acked before it was amended.
- **Disposition**: request-created MIDSTREAM chains write only the pending
  set and 9010/9011; 38/44 and 39 stay absent until a report, `9017=N`. A
  late D may supply 38/44 only when the record has none *and* no report has
  been seen. 02 §5 and 03 §6.1 are reconciled to one sentence.

### R8 — A `150=5` whose 11 is the original id leaves the REPLACE pending forever and sets 9014 wrongly — Medium — **accept, rule**

- **Sequence**: `G 11=A-2 41=A-1 38=12000 ; 8 150=5 39=0 11=A-1 37=ORD 38=12000`
  (the venue echoes 41 as 11 — the convention 06 E9 already assumes on
  `150=4`).
- **Docs say**: 03 §6.4 no-match rule "anything else removes nothing"; 9014
  "on 150=0 / 150=5 regardless of a match … the report's 11"; 06 E10
  "limited".
- **Goes wrong**: terms adopted, but `{A-2:REPLACE}` stays for the life of
  the order and **9014 = A-1** while the venue works A-2. For this venue it
  happens on every amend.
- **Disposition**: on a no-match `150=5`, remove the pending REPLACE when
  exactly one is pending (mirroring the `150=4` rule) and set 9014 to *that
  request's* 11; count `inferredReplaceAnswers`. E10 becomes **policy**.

### R9 — A pending acknowledgement that overtakes its request keeps the request out of the set — Medium — **accept, rule**

- **Sequence** (merged tap): `8 150=6 39=6 11=X-2 41=X-1 37=ORD` binds X-2;
  then `F 11=X-2 41=X-1` → "already bound → IGNORED(late or duplicate)".
- **Goes wrong**: `9016=0` while the venue itself says pending cancel; the
  later 9 or `150=4` finds nothing to remove.
- **Disposition**: the test becomes "already *answered*", not "already
  bound": one bit per bound request id, set by answering reports and 9s. A
  request bound only by a pending acknowledgement is added to the set.

### R10 — Index-binding conflicts are unspecified; a D carrying 41 is dropped — Medium — **accept, rule**

- **Sequences**: `G 11=B-2 41=A-1 37=ORD-B` (operator error, or a
  cancel-and-new OMS): resolution picks 37 → order B, then "bind 41 → key"
  either rebinds A-1 to B or silently keeps A — 03 §3.3 says only "each
  idempotent". `D 11=C-1 41=A-1` resolves via 41 to A and is
  `IGNORED(duplicate request)` under 03 §6.1 — a genuine new order never
  reaches the blotter.
- **Disposition**: a D resolves by its own 11 only and always opens a chain
  (41 on a D is lineage: recorded as `9027 LinkedClOrdID`, not followed).
  A bound id is never rebound to a different key; `idConflicts` counts and
  logs both keys. One resolution order per message type, stated once (§4.1).

### R11 — 9015 and 9021 are unparseable for ids containing the delimiters — Medium — **accept, rule**

- **Condition**: ClOrdIDs such as `20261031:DESK7:000123` or `ALGO;VWAP;17`
  (FIX allows any byte but SOH). `9015=20261031:DESK7:000123:REPLACE:12000:185.75`
  has no unique parse; a REPLACE without 44 has an unspecified shape; 9021
  is comma-joined.
- **Goes wrong**: the SOW rebuild reconstructs a wrong or empty pending set;
  behaviour on a parse failure is unspecified.
- **Disposition**: fixed field count with the id last (`ACTION:qty:px:id`),
  `%`-escaping of `;`, `:`, `,` and `%` in ids for both tags; a parse failure
  on rebuild sets `9018=UNRECOVERED` and a counter, never silently.

### R12 — One SOW-key namespace for root ClOrdIDs and OrderIDs — Medium — **decide** (§6)

- **Condition**: order A opened with `D 11=123456`; unrelated order B first
  seen mid-stream with `37=123456` → MIDSTREAM keyed `123456` → B's deltas
  merge into A's record. Numeric ClOrdIDs and numeric OrderIDs are both
  common; and 02 §7's "one publisher per session" onto one topic puts
  several sessions' ClOrdID namespaces in one key space.
- **Disposition**: a key-kind prefix (`C:X-1`, `O:ORD-7`) or a `9019 KeyKind`
  tag plus a dialect-supplied session prefix. The prefix changes what a
  consumer filters on, so it is a decision.

### R13 — Unrouted or unparseable messages halt the feed — Medium — **accept, design**, as a mode

- **Condition**: a drop copy carries `35=Q`, `H`, `j`, `R`/`S`, `AB`, and
  occasionally a malformed field. "No route matches → the planner raises"
  (06 E50, inherited from the stateless publisher, which fails loudly on
  purpose).
- **Disposition**: a `strict | lenient` mode. Lenient (the production
  default): a catch-all route to `sow/fix42/other` with `apply-state: false`
  (02 §6 names `apply-state` once and the yml does not show it), malformed
  bytes to `sow/fix42/malformed` with a counter, the machine returning
  `IGNORED(unrouted)`. Strict (the test default): raise, as today.

### R14 — Order terms other than 38/44/59 drift after a replace; market orders keep a stale 44 — Medium — **accept, rule**

- **Sequence**: `D 40=2 44=50 110=100 ; G 11=X-2 41=X-1 40=1 110=0` (limit →
  market, drop MinQty, no 44) ; `8 150=5 39=0 11=X-2 40=1 110=0` → the record
  still reads `40=2 44=50 110=100`.
- **Disposition**: on `150=5` and `150=D` adopt every order-term tag the
  report carries, from a configurable list defaulting to the D's term set
  (18, 40, 44, 59, 99, 100, 110, 111, 126); when the report says `40=1` and
  carries no 44, write `44=0` (the 9011 convention) and document "44 is
  meaningful only when 40=2".

### R15 — Fields a merge cannot remove go stale after busts; 60 is not monotonic — Medium — **partly**

- **Sequences**: `8 150=1 17=E-3 14=800 32=500 31=50.1 ; 8 20=1 19=E-3 150=1
  39=1 14=300` → the record still reads `32=500 31=50.1`, a trade that never
  happened. A late execution or detail-only report sets 60 earlier than the
  cancel's.
- **Disposition**: on a bust whose 19 equals the last fill's ExecID, write
  `32=0 31=0`, and carry `9028 LastFillExecID` so a consumer can tell. For 60
  the record keeps "the last applied message's time" as defined, and gains
  `9029 LastReportTime` as the running maximum; the consumer rule is stated
  in 02 §9. The repository's earlier objection to 60 moving backwards
  ([docs/fix42-view/04 §1](../../docs/fix42-view/04-pending-state-without-a-state-machine.md#1-the-proposal-that-prompted-this))
  was about a *revert* rewriting history; here the field means what it says.

### R16 — 9025/9026/9021 are stale on the record by default; `publish-unchanged` is mis-specified — Medium — **accept, rule**

- **Condition**: CHANGED bookkeeping is "11, 41, 60, 9022, 9023" (02 §5);
  9025 is published "only with publish-unchanged: true" (03 §2) yet 04 §1
  recovers `ignoredCount ← 9025` and 02 §4 calls it a health signal. 9026 is
  on the record but absent from the cache list (02 §3.1) and the recovery
  mapping (04 §1). Ids bound by IGNORED outcomes never reach 9021, so a
  later message under such an id opens a MIDSTREAM record after a restart.
  `publish-unchanged: true` is described as "a delta of 9023/9025/60", but
  9023 must not move on an ignored message and a stale report's 60 moves 60
  backwards.
- **Disposition**: 9025 is marked dirty whenever `ignoredCount` changes, so
  the next delta carries it; `publish-unchanged` sends 9025 and, when a new
  id was bound, 9021 — not 9023 or 60; 9021 is published whenever a new id
  is bound regardless of outcome; `lateFills` joins 02 §3.1 and 04 §1.

### R17 — No schema version on the record; loader tolerance unspecified — Medium — **accept, design**

- **Condition**: a SOW written by version N loaded by N+1; records seeded by
  a hook-enabled instance loaded by a hookless one; the module pointed at
  `sow/fix42/orders` by mistake (no 9020 anywhere). 04 §2.1 step 3 says
  nothing about missing or unknown tags.
- **Disposition**: `9024 SchemaVersion`, written at creation and bumped by
  the writer; the loader fills missing tags with defaults and counts
  `recoveredWithDefaults`, refuses an unknown major version, and refuses a
  record without 9020.

### R18 — `20=3` status reports, absent ExecIDs, and OrdStatus values outside the contract's vocabulary — Medium/Low — **accept, rule**

- **Condition**: 03 §5 treats `20=3` as `20=0`, so a status report echoing a
  pending request's 11 answers it (R1), and its 17 (often `0`, reused, or
  absent) collides with dedupe — two reports with 17 absent compare equal
  and every later 17-less report is `IGNORED(duplicate)`. 39 ∈ {`7`, `9`,
  `B`, `D`} and `150=I` are unmentioned.
- **Disposition**: `20=3` never touches the set or 9014 and is dedupe-exempt
  when 17 is absent, `0` or `NONE`; dedupe only on a present, non-`0`,
  non-`NONE` 17; the extra 39 values are listed in 03 §1 as non-terminal,
  with the venue's 151 adopted.

### R19 — 9021 growth and per-request re-send on algo child orders — Medium/Low — **decide** (§6)

- **Condition**: 02 §3.3 sizes an entry at "two or three ClOrdIDs"; a pegged
  child re-priced every few seconds has a thousand amends a day. 9021 is
  re-sent whole on every request delta (tens of KB per amend by afternoon)
  and the cache entry grows to megabytes for hot orders.
- **Disposition**: cap 9021 at the last N ids plus a count. The reviewer's
  further proposal — **stamp 9020 on the pass-through audit publishes** so
  the chain history is one `/9020 = …` query across the three audit topics,
  the index can be rebuilt from `orders_audit`, and `miss-lookup` becomes
  cheap — departs from "the audit topics hold the message as received" by
  exactly one tag, and is a decision.

### R20 — Hooks SPI: failure semantics, key override, frame rewriting, hot-path cost, tag collisions — Medium — **accept, design**

- **Conditions** (07 §2): (a) a hook that throws in `afterApply` after the
  cache mutated — publish skipped? feed halted? undefined, and either breaks
  the determinism 07 §4 promises; (b) `resolveKey(FixFrame)` returns a key
  with no access to the index, so the advertised "prefix the key with the
  trading date" cannot resolve a later message carrying only an amended
  request's 11; (c) `Decision.proceedWith(FixFrame)`: do the audit topics
  receive the original or the rewritten frame? (d) the `before`/`after`
  views imply a per-message copy, absent from the phase 5 budget, and the
  `onIgnored → publish` example does I/O on the thread 07 says must not
  block; (e) **the D's FULL publish ("the whole message less 35") carries
  any venue custom tags in 9000–9099 — including 9010–9026 — onto the
  record, overwriting machine state at creation**, and framing tags (8, 9,
  10, 34, 49, 52, 56) land on the record for ever.
- **Disposition**: hook failure is fail-fast with the message held
  (deterministic on replay); `resolveKey(FixFrame, IndexView)` or a
  `KeyDecorator`; the audit topics always receive the original frame; the
  view copies are budgeted in phase 5; the FULL publish strips 8, 9, 10, 34,
  49, 52, 56 and every tag in 9010–9099 that the machine owns, counting
  `strippedTags`.

### R21 — `37=NONE` on rejects, rotated OrderIDs, absent 17 — Low/Medium — **accept, rule**

- **Conditions**: `8 150=8 39=8 11=R-1 37=NONE` is common; 03 §6.4 "37
  adopted on the first report that carries it" vs 02 §4 "first report, then
  every report" — is `NONE` written? `sow/fix42/execs` (keyed `/37`)
  collapses every such reject onto the key `NONE`. A rotated 37 with 41
  absent and a missed replace's 11 splits the chain; the tombstone holds one
  `orderId`. A report lacking 17 published whole to `execs_audit` is refused
  by AMPS.
- **Disposition**: the record's 37 is the latest non-`NONE` value; the
  tombstone holds every 37 seen; the execs route is guarded on `37 ≠ NONE`;
  `AuditRouter` keeps the stateless planner's per-payload key check.

### R22 — The daily reset and the persistent SOW are undefined operationally — Low — **accept, design**

- **Condition**: 06 E46 and 05 rely on "a daily restart against a reset
  `order_state` SOW", but nothing says which files are wiped (never the
  journalled audit topics?), when, and how `9017='Y'` records with
  `9016 > 0` are preserved for investigation. With a persistent SOW and a
  reused ClOrdID, the first D of the new day replaces yesterday's record
  (R4c).
- **Disposition**: a reset procedure in 04 §8: which SOW files, at what
  point in the session, and an export of open-request records before the
  wipe.

## 3. Verdicts in 06 the review disputes

| rows | verdict | dispute | resolution |
| --- | --- | --- | --- |
| E32 | handled | not when fills echo the pending request's id, which the mock itself does (R1) | **policy** |
| E8, N14 | handled | the generalized answering rule is dialect-dependent (R1) | N14 handled by the NEW rule; E8 **policy** |
| E21, E29 | handled | detail-only plus terminal-after-terminal cannot tell a late fill detail from a venue reopen (R2) | E21 restricted to fills; E29 **policy** |
| E6, E7 | handled | only answers overtaking requests; a pending ack overtaking keeps the request out (R9) | handled after R9 |
| E9, E10 | handled, limited | the convention that makes E9 handled breaks every replace via E10 (R8) | E10 **policy** |
| E31 | limited | the prose is incoherent, and wrong: an equal-CumQty report with an ExecID outside the ring on a *working* order is applied in full, so `8 150=0 39=0 17=E-1 14=0` resent after nine pending/status reports takes 39 from `6` back to `0` and 60 backwards | rewrite; status-bearing reports with equal CumQty need the TransactTime guard of §6 |
| E40, E47, E48, E49 | handled, limited | all assume the missing order's key was its OrderID; for D-opened orders it is the root ClOrdID (R4); E49 ignores the async buffer (R5) | per R4, R5 |
| N4, N5 | policy | the default adopts `6`/`E`/`A` and terminal values from a 9 without a report's consequences (R6) | per R6 |
| E42 | handled | against a populated SOW after a `NONE` restart the resent D destroys the record (R4c) | per R4 |
| E43, E44 | handled | 02 §4 and 03 §6.4 disagree on which 37 the record shows; with 41 absent the chain still splits (R21) | per R21 |
| E50 | out of scope | "the planner raises" is a feed halt (R13) | lenient mode |
| N9 | handled | some venues answer a TIF-only amend with `150=D`, leaving the REPLACE pending | dialect note |
| E51 | out of scope | unacknowledged consequence: forcing `151=0` on `150=3` zeroes exposure for a GTC that is live tomorrow | say so under E51 |

## 4. Ambiguities to resolve in the revision

1. **Resolution order** differs: 03 §3.1 (37 → 11 → 41), §6.2 (41 → 37 →
   11), §6.5 (11 → 41 → 37). State one order per message type, once.
2. **Late D bookkeeping**: 03 §6.1 excludes 11/41/60 from what the D applies;
   03 §6.6 and 02 §5 say every applied message sets them.
3. **9010/9011**: 02 §4 "the newest in-flight REPLACE's terms, else 0";
   02 §4.1 and 03 §7.4 show `0/0` when the newest request is a CANCEL while
   a REPLACE is still pending.
4. **9014**: 02 §4 says `150=0`/`150=5`; 03 §6.4 also when a fill answers.
5. **After-terminal precedence**: a `150=6` with a higher 14 matches both the
   late-execution and the absorbed rows; absorbed first.
6. **Numeric representation**: are 14/151/6/38/44 compared as numbers
   (`"300"` vs `"300.0"`)? Does the record keep the venue's text verbatim?
   The dirty mask is "touched", not "changed", so 38 is re-sent on every
   fill — fine, but say so.
7. **Empty values**: `RecordWriter` must never emit `44=` with an empty value;
   AMPS fix-type delta semantics for an empty value are unverified.
8. **No tag 35**: AMPS should not care (verify); FIX-dictionary tooling fed
   the record will reject it — a consumer note for 02 §9.
9. **MIDSTREAM from a stray 35=9** (03 §6.5): a record with no terms, no 39,
   grouped under a null account in the views. Audit-only plus a counter is
   better.
10. **14/151/6 on `150=A/6/E`** are adopted while 38/44 are not; some venues
    echo the proposed quantity in 151 on a `150=E`. Decide.
11. **Absent 14/151/6** on a working report: adopt-if-present, and the stale
    guard with 14 absent is undefined.
12. **Active/passive** (04 §5): a passive that consumes the feed with
    publishing off has a cache built from the feed, not the SOW; say which is
    authoritative on promotion (recommend: the passive starts cold with
    `recovery: SOW`).
13. **Rebuild race**: the new instance's `sow` snapshot against the old
    instance's bytes still in flight on another connection; wait for
    `NameInUse` to clear before querying.
14. **Two chained REPLACEs** (G2's 41 = G1's 11, 06 E5): 9014 rotation when
    only G2's `150=5` arrives, and whether G1 is then answered, is unstated.
15. **`apply-state`** appears only in 02 §6 prose; **`miss-lookup`** needs an
    index whose element name is unverified (acknowledged).
16. **60 on requests is the client's clock, on reports the venue's**; any
    ordering across the two is meaningless — a consumer caveat.

## 5. Gaps in the implementation plan

**Components**: the FIX-session adapter (QuickFIX/J `fromApp` or
equivalent) — the class layout has only `MockFlowRunner`, yet 04's recovery
story depends on where the publisher sits and when the engine acknowledges
(R5); `HAClient` + `PublishStore` + `FailedWriteHandler` in
`AmpsClientConfiguration`, a `publishFailures` counter, and the two-phase
`plan/commit` machine (R5); the catch-all and malformed routes and
`apply-state` in `validate()` (R13); `9024 SchemaVersion` and loader
tolerance (R17); the key-kind prefix or `9019` (R12); the 9015/9021 escaping
(R11); `OrderChain` switches for venue conventions — `fillsEchoPendingId`,
`confirmsEchoOriginalId`, `replaceAsCancelThenNew`,
`rejectReplaceWithExecReport` — because phase 1's scenarios are "the
specification", so the dialects must be expressible there.

**Phase 1 scenarios to add**: a fill while a cancel or replace is pending
under *both* ClOrdID conventions (R1); `150=5`/`150=4` echoing the original
id (R8); the cancel+new replace (R2); `150=8` naming a pending REPLACE with
`39=8` and with `39=1` (R3); a pending ack overtaking its request (R9); a 9
carrying `39=4` and one carrying `39=6` (R6); `20=3` reports with `17=0` and
echoing a pending id (R18); `150=8` with `37=NONE` (R21); a G changing 40/110
with no 44 (R14); ids containing `:`, `;`, `,` (R11); a D carrying 41, and a
G whose 37 and 41 name different orders (R10); a venue tag in 9010–9099 and
framing tags on a D (R20); a bust of the *last* fill (R15); a thousand-amend
algo child (R19).

**Phase 2 tests to add**: numeric comparison of text values; 9015/9021 round
trip with hostile ids; round-trip equality "modulo the ExecID ring", and
either encode the pending request's `origClOrdId` in 9015 or drop it from
02 §3.1 (no rule uses it); id-conflict behaviour; the `publish-unchanged`
payload shape; 9025/9026 dirty semantics; late-D pending logic for
request-created chains; detail-only restricted to fills.

**Phase 3 verifications to add**: a rejected publish is *observed* (publish
a report without 17 to `execs_audit`, assert the handler fires); AMPS
restart mid-flow → reconnect with no cache/SOW divergence; `NameInUse`
retry; AMPS accepts a fix-typed record without 35 and (negative) an empty
value; duplicate tags on a FULL publish; the blotter writer always carries
9020 (the yml `topic-keys` check cannot see the `RecordWriter`); view totals
equal the mock's per-account sums *including* a MIDSTREAM record.

**Phase 4 tests to add**: `recovery: NONE` against a populated SOW — one
record per order or the documented second record *and* the view totals
(R4a, R4c); the partial load plus a late bust (R4b); a resent D after a
`NONE` restart does not destroy the record; schema-version loading (records
missing 9026, unknown 9024); replay determinism with a hook installed and
with it removed.

**Phase 5**: the allocation budget includes the hooks' before/after views and
the 9015/9021 string building; memory for a thousand-ClOrdID chain.

**Operations**: the daily reset procedure; the FIX-engine sequence-number
and flush coupling with its loss bound; alert thresholds for
`midstreamChains`, `unmatchedRejects`, `publishFailures`, `idConflicts`,
`reopenedByVenue`; counters missing from 04 §6: `ignoredLateFill`,
`idConflicts`, `publishFailures`, `reopenedByVenue`, `terminalFromReject`,
and the `exec-type` histogram 04 §7 relies on.

## 6. Decisions

Six were put to the design owner after the review; all six are decided. §7
keeps the elaborations that preceded decisions 3, 4 and 5.

| # | decision | options | recommended | **decided** |
| --- | --- | --- | --- | --- |
| 1 | TransactTime (60) as the tiebreak between a venue-asserted reopen and an out-of-order late report (R2), and as the staleness guard for equal-CumQty status reports (E31) | trust 60 within a venue / treat every such report as a reopen / treat every such report as late | trust 60 | **yes: trust 60.** Equal timestamps count as not-earlier; `non-fill-after-terminal` keeps the other two behaviours available |
| 2 | key format (R12) | raw id / kind-prefixed `C:`/`O:` / raw id plus `9019 KeyKind` | kind-prefixed | **no: keep the drop copy's own ids as the key.** R12's disposition changes to collision *detection*: when a `MIDSTREAM` chain would be keyed by an OrderID that is already another chain's key (a root ClOrdID of the same text) and that chain's own OrderID is different or unknown, the next candidate id is used instead (41, then 11), `keyCollisions` counts it, and the record carries `9019 KeyKind` (`C` or `O`) as metadata only. If every candidate collides the message is `IGNORED(keyConflict)` and logged with both keys |
| 3 | tag 9020 on the pass-through audit publishes (R19) | message as received / message plus 9020 / CorrelationId header / side-index topic | plus 9020 | **yes: plus 9020**, appended as a trailer — §7.1 |
| 4 | AMPS client (R5) | plain `Client` with apply-then-publish / `HAClient` with a publish store, `FailedWriteHandler`, two-phase machine | the latter | **yes: `HAClient` with a store**, `FILE` in production, `MEMORY` for demo and tests; the blotter topic journalled — §7.2 |
| 5 | unrouted message types (R13) | raise / catch-all route | a mode | **yes: a mode**, `strict` for tests, `lenient` for production — §7.3 |
| 6 | 32/31 after a bust of the last fill (R15) | leave as the stateless blotter does / write `32=0 31=0` plus `9028` | write zeros plus 9028 | **yes** |

## 7. Elaborations for the open decisions

### 7.1 Tag 9020 on the audit publishes

**What it is.** Every message is published whole to its pass-through topic
(`orders_audit` keyed /11, `execs_audit` keyed /17, `rejects` keyed /11).
The proposal appends one field, `9020=<OrderKey>`, to each of those payloads
before it is published — the key the machine filed the message under, in a
tag the machine owns. Nothing else about the payload changes; the blotter
publish is unaffected.

**What it buys.**

1. *Chain history in one query.* `sow` on each audit topic with filter
   `/9020 = 'X-1'` returns every message of the order — D, every G and F,
   every report, every reject — without walking 41 links or trusting 9021,
   and so lets 9021 be capped at the last N ids (R19) without losing the
   history.
2. *Cache-miss lookup through primary keys only.* On a miss, almost every
   message names a prior request id: a G or F carries 41, a report carries
   11 and usually 41, a 9 carries 11 and 41, and every order has a D whose
   11 is on `orders_audit`. A keyed `sow` on `orders_audit` by that id (its
   SOW key, O(1), no secondary index) returns the audit record and its 9020:
   the OrderKey. R4's remedy then needs no hash index on /37 and no
   "verify on build"; the /37 lookup becomes the fallback for the one case
   where 11 and 41 are both unknown.
3. *An index rebuild without running the machine.* A bookmark replay of
   `orders_audit` yields `11 → 9020` pairs directly, which is a cheaper
   `recovery: JOURNAL` for the id index than re-deriving it.
4. *Consumers of the audit topics group by order* without knowing the
   chaining rules — the same reason the stateless design wanted the chaining
   module's key visible to subscribers
   ([docs/fix42-view/05](../../docs/fix42-view/05-the-generated-sow-key.md)).

**What it costs.**

- About 30 bytes per audit publish. No extra publish, no extra topic.
- *Fidelity.* The audit record is no longer byte-identical to the wire
  message. If the drop copy carries framing (8, 9, 10), appending a field
  before 10 would invalidate BodyLength and CheckSum; appending it **after**
  10, as a trailer, leaves both valid for the bytes they cover. AMPS's fix
  parser does not care about field order (verify on build); a
  FIX-dictionary validator fed the record would flag the trailer, which it
  would flag anyway for any stored copy that is not a session message.
- *A venue that uses tag 9020 itself.* The stamp is skipped and
  `auditKeyTagConflict` counts it; the tag number is configurable.
- The principle "the audit topics hold the message as received" is one this
  design introduced (the stateless publisher stores route-selected subsets),
  so changing it to "as received, plus the key it was filed under" is a
  refinement of a new rule, not a break with practice. The repository's
  QuickFIX/J drop-copy demo already enriches its stored copies with four
  tags (5001–5004) for the same kind of reason.

**Alternatives.**

| option | fidelity | filterable by order | cost |
| --- | --- | --- | --- |
| A. stamp 9020 in the payload (trailer) | changed by one field | yes | ~30 B per publish |
| B. carry the key in the AMPS `CorrelationId` header | untouched | no — content filters evaluate the body (verify on build); a reader sees the key on each record but cannot select by it | nil |
| C. a side topic `sow/fix42/order_index` keyed /11 holding `11, 41, 37, 9020` | untouched | yes, via the side topic | one extra ~60 B publish per D/G/F/9 (not per report) |
| D. nothing: uncapped 9021 plus a hash-indexed /37 lookup | untouched | partly | 9021 grows with every amend; the index is verify-on-build |

**Recommendation.** A, with the trailer placement, unless a compliance rule
requires the stored copy to be byte-identical to the wire, in which case C.
B solves only half the problem; D is what R19 showed to be unbounded.

### 7.2 The AMPS client: publish store, speed and disk

**What a publish store does.** With a store set, `publish` writes the
message into the store *before* sending it and returns at once; the server
acknowledges each message as persisted; the ack discards it from the store;
after a reconnect the client replays whatever is still there and the server
drops any it already has as duplicates. A `FailedWriteHandler` receives every
rejection with its reason. Without a store, none of that exists: no sequence
numbers, no replay, no rejection report, and a disconnect loses what was in
flight — read from the client source in
[fix-pub-seqno/docs/02](../../fix-pub-seqno/docs/02-what-amps-already-does.md).

**Does it slow the publisher?** Not in the way that matters. `publish` stays
asynchronous — it returns after the store write, not after the server's
acknowledgement — so the 5.3× collapse measured in
[docs/fix42-view/06](../../docs/fix42-view/06-publish-throughput.md) for a
flush per message does not apply. The per-publish cost is a copy into the
store: for `MemoryPublishStore` a heap buffer; for the file-backed
`PublishStore` a write into a memory-mapped file, not a forced disk sync per
message. The connectors module describes the file store as "a synchronous
write per publish" and still ships it as an option behind a `MEMORY`
default. Neither has been measured in this repository against the 14,000
msg/s baseline; phase 5 measures both. Expectation: single-digit
microseconds per publish, within the noise of the INFO log line the baseline
already pays for.

**Does it need a lot of disk?** No. The store holds only messages the
server has *not yet acknowledged*: with AMPS up, the in-flight window —
milliseconds of flow, kilobytes. It grows only while AMPS is unreachable, at
the rate of the flow: a 100 msg/s feed at ~300 bytes for ten minutes is
about 18 MB. The file is preallocated at a configurable initial size (verify
the 5.3.5.3 default) and grows beyond it only if an outage outlasts it.

**The constraint that matters more than either.** The server sends
persisted acknowledgements only for topics in the transaction log
([fix-pub-seqno/docs/02](../../fix-pub-seqno/docs/02-what-amps-already-does.md),
[amps-ha-demo/docs](../../amps-ha-demo/docs/replication-and-the-transaction-log.md)).
A message published with a store to a topic that is *not* journalled is
never acknowledged and never discarded, and the store fills. The design has
`sow/fix42/order_state` deliberately **not** journalled (02 §1, "derivable").
With a publish store on this client, that must change: **the blotter topic
joins the transaction log.** Cost: the deltas themselves, roughly 150–300
bytes each — about 200 MB for a million-message day, beside the audit
topics' ~300 MB the design already journals, and bounded by the same journal
ageing policy ([docs/src/transaction-log-sizing.md](../../docs/src/transaction-log-sizing.md),
the `bounded-retention` flow). It also gives the blotter bookmark-replayable
history for free. The alternative — a second, plain client for the blotter
topic — would leave exactly the publishes the module exists for without
failure reporting, so it is not an alternative.

**Memory or file.** The two cover different failures:

| | `MemoryPublishStore` | file `PublishStore` |
| --- | --- | --- |
| AMPS restart or network drop, process alive | replayed, nothing lost | same |
| server-side rejection (bad payload, missing key) | reported through `FailedWriteHandler` | same |
| publisher process crash | the un-acknowledged window is lost (the socket buffer: milliseconds of flow), **and the FIX engine has already acknowledged those messages**, so the feed does not resend them | replayed from the file on restart; nothing lost if the file survives |
| disk | none | small; bounded by the longest outage |
| per publish | a heap copy | a memory-mapped write |

`fix-pub-seqno`'s docs/04 recommends the file store *plus* application-level
recovery for production, and the connectors module defaults to memory.

**Recommendation.** `HAClient` with the store type configurable
(`publish-store: MEMORY | FILE | NONE`), **`FILE` in production, `MEMORY` for
the demo and tests**; `FailedWriteHandler` wired to `publishFailures` and a
dirty-all republish; the two-phase `plan/commit` so a rejected `publish`
leaves the cache unchanged; and `sow/fix42/order_state` added to the
transaction log. `NONE` stays available for a deployment that accepts the
stateless publisher's at-most-once semantics, with the failure matrix in 04
§7 saying so.

### 7.3 Unrouted message types

**What a drop copy carries beyond D/G/F/8/9**, and what each means here:

| 35 | message | on an order drop copy | effect on the design if unhandled |
| --- | --- | --- | --- |
| `Q` | DontKnowTrade | the client disowns a fill; references 37 and 17 | in the contract (rule 6); out of scope for v1; **no order-level change** is needed, so nothing on the blotter is wrong without it |
| `H` | OrderStatusRequest | a client request for a status snapshot; the answer is a `35=8` with `150=I` | the H itself changes nothing; the `150=I` *is* routed as an 8 and must be treated as a status restatement — adopt the venue's absolutes if not stale, never touch the pending set or 9014 (joins R18's list) |
| `j` | BusinessMessageReject | the venue could not process an application message; `45 RefSeqNum`, `372 RefMsgType`, `58` | **a G or F rejected this way instead of by a `35=9` leaves its request pending forever.** Handling it needs a map from the request's MsgSeqNum (34) to its ClOrdID — a small ring, since the reject follows within seconds — which is a follow-up |
| `3` | session Reject | as `j`, at the session level | same |
| `E`, `N`, `L` | list orders and their status | out of scope by the contract | nothing |
| `AB`, `AC`, `AE`, `AD` | multileg, trade capture (FIX 4.4) | not 4.2; a 4.4 venue leaking | nothing |
| `0`, `1`, `2`, `4`, `5`, `A` | heartbeat, test request, resend, sequence reset, logout, logon | consumed by the FIX engine (`fromAdmin`); seen only by a raw tap | nothing |
| `R`, `S`, `b`, `6`, `7`, `BE`… | quotes, IOIs, user requests | irrelevant to orders | nothing |

And the shapes *within* the five known types that still cannot be routed: a
`35=8` with a 150 value no rule names (`I`, or FIX 4.4's `F`/`G`/`H` from a
leaking venue); a message with no tag 35; a message missing the topic's key
(a G without 11, an 8 without 17 — AMPS rejects the audit publish, R21); and
bytes that do not parse as FIX at all.

**Two modes.**

| | `strict` | `lenient` |
| --- | --- | --- |
| unrouted type | the machine raises; the publisher stops. The stateless publisher's stance ("a silent drop looks like an AMPS bug from the far side") | published whole to `fix42/unrouted`, a **journalled, non-SOW topic** (no key needed; every message kept in arrival order; replayable by bookmark); `IGNORED(unrouted)`; a counter per 35 value; a rate-limited WARN |
| unparseable bytes | raise | wrapped as a fix message (`9032=<base64 of the bytes>`, `9033=<reason>`) on `fix42/malformed`, same topic shape; counter |
| a known type the rules cannot place (`150` unknown, key tag missing) | raise | audit as far as the key allows; `IGNORED(unrouted)` for the blotter; counter |
| default | tests and development: a missing rule fails the first run that hits it | production: the feed never stops because of a message type, and nothing received is lost |

What lenient mode guarantees is the property the strict mode was protecting:
**every byte received lands somewhere journalled**, so the day can be
replayed into a later version that handles `35=Q` or `35=j`, and the
counters make the gap visible rather than silent. What it does not do is
make the blotter right for a request rejected by `35=j`; that needs the
MsgSeqNum ring above, listed as a follow-up with a hook recipe in 07 until
it is built.

**Recommendation.** Both modes, `strict` the default in `application.yml`
for the test profile and `lenient` for the production profile; `150=I` added
to 03 §1's vocabulary as a status restatement; `35=j`/`35=3` request rejects
as a named follow-up.

## 8. Where each finding landed

The revision of docs 01–07 that followed the decisions, finding by finding.

| finding | landed in |
| --- | --- |
| R1 answering on fills | [03 §1](03-state-machine.md#1-vocabulary) *answering report*; [03 §6.4](03-state-machine.md#64-358-executionreport) pending-set rows; `fill-under-pending-id`; [03 §7.14](03-state-machine.md#714-a-fill-while-a-cancel-is-pending-under-the-cancels-id); 06 E8, E15, E32, N14 |
| R2 detail-only row | [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision) after-terminal table (fills only; venue-asserted reopen with the TransactTime tiebreak); `non-fill-after-terminal`; [03 §7.16](03-state-machine.md#716-a-replace-reported-as-cancel-then-new); 06 E21, E35–E37 |
| R3 `150=8` adopting proposals | [03 §6.4](03-state-machine.md#64-358-executionreport) 38/44 row and `150=8` row; [03 §7.15](03-state-machine.md#715-an-amend-rejected-by-an-execution-report); 06 E16 |
| R4 second records | [04 §2–4](04-recovery-and-operations.md#2-restart): `MissResolver`, the D keyed check, tombstones from every record, `recovery: NONE` refused; [03 §6.1](03-state-machine.md#61-35d-newordersingle); 06 E48, E53–E56 |
| R5 publish failures | [04 §6](04-recovery-and-operations.md#6-the-publish-path); [03 §2](03-state-machine.md#2-outcomes) plan/commit; [02 §7](02-architecture.md#7-threading-and-ordering); 06 E70–E73; the blotter topic journalled |
| R6 a 9's terminal 39 | [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision) policy table and the 151 rule; [03 §7.19](03-state-machine.md#719-a-cancel-reject-that-carries-a-terminal-status); 06 N4, N5, E18 |
| R7 MIDSTREAM from a request | [03 §3.2](03-state-machine.md#32-creation); [03 §6.1](03-state-machine.md#61-35d-newordersingle) late-D row; [02 §5](02-architecture.md#5-publish-modes) |
| R8 `150=5` echoing the original id | [03 §6.4](03-state-machine.md#64-358-executionreport) `150=5` row; [03 §7.17](03-state-machine.md#717-a-replace-confirm-that-echoes-the-original-id); 06 E10 |
| R9 pending ack overtaking | inferred pending entries, [03 §1](03-state-machine.md#1-vocabulary), §6.2, §6.4; [03 §7.18](03-state-machine.md#718-a-pending-acknowledgement-that-overtakes-its-request); 06 E17 |
| R10 binding conflicts, D with 41 | [03 §3.1](03-state-machine.md#31-resolution), §3.3; 9027; 06 E58 |
| R11 delimiters in ids | [02 §4.1](02-architecture.md#41-several-requests-in-flight-tag-9015) encoding; `UNRECOVERED`; 06 E59 |
| R12 key namespace | decision 2: collision detection at mint time, 9019 — [03 §3.2](03-state-machine.md#32-creation); 06 E57 |
| R13 unrouted messages | decision 5: [03 §9](03-state-machine.md#9-unrouted-and-unparseable-messages); `mode`; `fix42/unrouted`, `fix42/malformed`; 06 E50, E76 |
| R14 term drift | `order-term-tags`, `44=0` — [03 §6.4](03-state-machine.md#64-358-executionreport); 06 N19 |
| R15 stale 32/31, 60 | decision 6: `32=0 31=0` with 9028; 9029 LastReportTime — [02 §4](02-architecture.md#4-the-record), [03 §7.13](03-state-machine.md#713-bust-and-correct-after-the-order-is-closed-out) |
| R16 stale bookkeeping | [03 §2](03-state-machine.md#2-outcomes), §6.6; [02 §5](02-architecture.md#5-publish-modes) last row; 9026 in [02 §3.1](02-architecture.md#31-one-entry-per-order) and [04 §1](04-recovery-and-operations.md#1-the-sow-record-is-the-checkpoint) |
| R17 schema version | 9024 — [02 §4](02-architecture.md#4-the-record); [04 §2.1](04-recovery-and-operations.md#21-rebuild-from-the-sow-recovery-sow-the-default); 06 E62 |
| R18 `20=3`, absent 17, extra 39 values | [03 §1](03-state-machine.md#1-vocabulary) *status restatement* and *terminal*; §5 steps 1–2; 06 E33, N18 |
| R19 9021 growth | `chain-ids-published`, 9030; decision 3 — [02 §4](02-architecture.md#4-the-record), [02 §5](02-architecture.md#5-publish-modes); 06 E61 |
| R20 hooks | [07 §2](07-extension-points.md#2-tier-two-hooks): fail-fast, `IndexView`, original frame to audit, the view copies budgeted; `strip-tags` in [03 §6.1](03-state-machine.md#61-35d-newordersingle); 06 E60, E74 |
| R21 `37=NONE`, rotated 37, absent 17 | [03 §6.4](03-state-machine.md#64-358-executionreport) 37 row; the `key-guard`; tombstones of every 37 — [04 §4](04-recovery-and-operations.md#4-eviction); 06 E44 |
| R22 daily reset | [04 §9](04-recovery-and-operations.md#9-the-daily-reset); 06 E77 |
| §3 disputed verdicts | every row revised in [06](06-case-coverage.md), marked **(revised)** |
| §4 ambiguities | 1: [03 §3.1](03-state-machine.md#31-resolution); 2: [03 §6.1](03-state-machine.md#61-35d-newordersingle); 3: 9010/9011 in [02 §4](02-architecture.md#4-the-record); 4: 9014 in [03 §6.4](03-state-machine.md#64-358-executionreport); 5: row order in [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision); 6–7: [02 §3.1](02-architecture.md#31-one-entry-per-order), `RecordWriter`; 8: [02 §9](02-architecture.md#9-what-changes-for-a-consumer-of-the-old-blotter); 9: [03 §3.2](03-state-machine.md#32-creation); 10–11: [03 §5](03-state-machine.md#5-arbitration-on-execution-reports), §6.4; 12–13: [04 §5](04-recovery-and-operations.md#5-one-writer); 14: 06 E5; 15: [02 §6](02-architecture.md#6-configuration); 16: [03 §6.6](03-state-machine.md#66-every-message) |
| §5 plan gaps | [05](05-implementation-plan.md), marked *(added)* and *(changed)* |
