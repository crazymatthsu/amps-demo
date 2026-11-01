# 9. Architecture review, round 2: the revision reviewed

The second independent adversarial review, run against docs 01–08 as revised
for round 1 (commit `72cfffb`). The reviewer was asked to do three things in
order: verify that each round-1 disposition in [08](08-review-findings.md)
actually landed, consistently across the documents; attack the new mechanisms
the revision introduced; and sweep the documents for contradictions. This is
the register of what it found, with a disposition against each item and where
it landed. Docs 02–07 have been revised for everything below.

Dispositions as in 08: **accept, rule** / **accept, design** / **partly** /
**noted** (a statement added, no rule change).

**Tag numbers in this register follow the scheme adopted after both review
rounds**: 13011 for the key (RootClOrdID) and 15011/15035/15038/15044 for the
pending family. The drafts the review actually read used 9020 and 9010–9013;
quotations have been renumbered so the register reads against the current
documents.

---

## 1. What the review found, in short

The revision is sound on its headline: the two-cancels property holds in both
orders, 17 of the 22 round-1 findings landed consistently, and the seven
walkthroughs the reviewer recomputed by hand agree with the rules. Three
things it found were serious, and all three were introduced or exposed by
the round-1 fixes themselves:

1. **Decision 2's collision rule could never run.** It checked at *mint*
   time, but with one index for ClOrdIDs and OrderIDs a report whose OrderID
   equals another order's root ClOrdID resolves to that order at step 1,
   before any minting (S1). The index is now two maps.
2. **The publish-failure repair would have destroyed records.** It
   republished the whole record from the cache, and the cache deliberately
   does not hold the order's descriptive terms, so the repair would have
   erased the account, symbol and side the exposure views group by (S2). The
   repair is a delta of every cached field; a rejected creation re-sends its
   kept payload; the handler queues work for the publishing thread instead
   of touching entries.
3. **`sow/fix42/execs` broke the design's own rule.** Every report was
   published to it through the publish store, and it was the one topic not
   journalled (S3). It is journalled now, with a test that the store drains.

The medium findings tightened the TransactTime tiebreak for venues that send
whole seconds and for IOC/FOK orders (S4), closed a hole where a redelivered
old bust was exempt from every guard (S5), stopped a `150=8` for a request
the tap never saw from marking a working order rejected (S6), handled pending
acknowledgements that name the working id (S7), fixed the daily reset's
interaction with open orders and with the audit topics (S8), stopped evicted
entries being rebuilt from a bust (S9), let a 17-less status report reach the
blotter (S10), and removed a contradiction about unknown 150 values (S11).

## 2. Round-1 dispositions, as they landed

| finding | status before this round | what was wrong | fixed by |
| --- | --- | --- | --- |
| R1 | closed | — ; the NEW rule landed broader than 08's wording (any report answers a NEW), which is safe and now stated in 08 §8 | — |
| R2 | closed | — ; its tiebreak attacked in S4 | S4 |
| R3 | closed | — ; adjacent no-match case open | S6 |
| R4 | closed | — ; new gaps exposed on a hit without a record and on recreation | S8, S9 |
| R5 | **regressed** | the repair path (dirty-all `publish`), the cross-thread write, `execs` unjournalled | S2, S3 |
| R6, R7, R9, R11, R14, R15, R16, R17, R19, R20, R22 | closed | nits only (R7: a late D did not convert an inferred NEW; R15: 9028 after zeroing unstated; R20: stripping not stated for MIDSTREAM creation) | S7, S22, S18 |
| R8 | closed | 06 E10 labelled handled/limited where 08 §3 said policy; the rule is unconditional | 08 §3 corrected |
| R10 | **partial** | 06 E58 said "37 wins for the G" while 03 §3.1 resolves a G by 41 first | 06 E58 corrected |
| R12 | **not addressed** for the headline case | collision detection unreachable behind a single index | S1 |
| R13 | **partial** | unknown 150 values specified two ways | S11 |
| R18 | **partial** | a `20=3` without 17 never reached the machine: the audit key check diverted it | S10 |
| R21 | **partial** | same side effect; `17=0`/`NONE` collapsed `execs_audit` onto one key | S10 |

Disputed verdicts (08 §3): E29, E10 and E32 carried labels in 08 §3 that did
not match what the revision built (handled, not policy); 08 §3 now says what
06 says. 08 §8's claim that every disputed row was marked **(revised)** was
false for rows whose text had not changed; the claim now says so. Ambiguity
4 (9014 on a no-match `150=0`) and ambiguity 10 (14/151/6 on a pending
acknowledgement) were only partly resolved; S15 settles the first, and the
second is now stated as adopted (03 §6.4 adopts 14/151/6 from every report;
a venue that echoes a proposed quantity in 151 on a `150=E` is a dialect for
a `beforeApply` hook).

## 3. New findings

### S1 — Collision detection never runs for the R12 case — High — **accept, rule**

- **Sequence**: `D 11=123456` opens order A (key `123456`). Order B, first
  seen mid-stream: `8 150=0 39=0 11=B-1 37=123456 14=0 151=500`.
- **Docs said**: 03 §3.1 resolves a report by 37 first over an index of
  "every ClOrdID and OrderID seen → OrderKey"; the collision check was in the
  creation table only.
- **Went wrong**: `37=123456` was indexed as A's ClOrdID, so B resolved to A
  at step 1 and every B report merged into A's record. The mint-time check
  caught only a report with an unindexed 37 and no indexed 11/41 — not R12's
  case.
- **Landed**: `OrderKeyIndex` keeps two maps; the 37 step consults only the
  OrderID map, the 11/41 steps only the ClOrdID map; binding goes to the
  matching map; the rebuild fills them from 9021 and 37 separately. The
  same-order case (a venue whose OrderID equals the ClOrdID) resolves through
  11 and is not a collision. [03 §3.1–3.3](03-state-machine.md#31-resolution),
  [03 §7.21](03-state-machine.md#721-an-orderid-that-equals-another-orders-clordid),
  [02 §2](02-architecture.md#2-components), [04 §2.1](04-recovery-and-operations.md#21-rebuild-from-the-sow-recovery-sow-the-default),
  05 phase 2, 06 E57.

### S2 — The dirty-all repair republished a record the cache cannot reconstruct — High — **accept, design**

- **Condition**: any server-side rejection of a blotter delta, then the next
  outcome for that order.
- **Docs said**: 04 §6 "republishes the whole record with `publish`"; 02 §3.2
  keeps the descriptive terms out of the cache; the handler wrote `dirtyAll`
  into an unsynchronised entry from the client's receive thread; no repair
  ever happened if no further message arrived for the order.
- **Went wrong**: a `publish` built from the cache would have replaced the
  record without 1/55/54/40/59/…, dropping the order out of its exposure
  group; a rejected terminal delta on a quiet order stayed wrong for ever; a
  cross-thread write.
- **Landed**: the repair is a `delta_publish` of every cached field, never a
  `publish`; a rejected creation re-sends the kept `createdPayload` or
  rebuilds it from the D on `orders_audit` by 13011; a rejected audit publish
  goes to `fix42/unrouted` with `auditPublishFailures`; the handler enqueues
  the key and the publishing thread drains the queue before each message, on
  an idle timer and at shutdown. [04 §6](04-recovery-and-operations.md#6-the-publish-path),
  [02 §2](02-architecture.md#2-components), [02 §3.1](02-architecture.md#31-one-entry-per-order),
  [02 §7](02-architecture.md#7-threading-and-ordering), 05 phases 2–3, 06
  E70–E70b.

### S3 — `sow/fix42/execs` published through the store but not journalled — High — **accept, design**

- **Condition**: every `35=8` goes to `sow/fix42/execs` through the one
  `HAClient`.
- **Docs said**: 04 §6 "every topic this client publishes to is journalled …
  a message to an unjournalled topic is never acknowledged and the store
  fills"; 02 §1 and 05 phase 3 journalled everything except `execs`.
- **Went wrong**: by the design's own premise, one unacknowledged store
  entry per report for ever; a `MemoryPublishStore` fills and blocks or
  throws, a file store grows without bound.
- **Landed**: `execs` is journalled; a deployment that does not want the
  second copy of every report drops the route, since `execs_audit` filtered
  by 13011 or 37 answers the same question; `PublishPathIT` asserts
  `publishStoreDepth` returns to zero with every route live, which is also the
  test of the premise — if this build acknowledges unjournalled publishes
  after all, the rule becomes a recommendation. [02 §1](02-architecture.md#1-topology),
  [04 §6](04-recovery-and-operations.md#6-the-publish-path), 05 phase 3 and
  risks, 06 E70d.

### S4 — The TransactTime tiebreak with second-resolution or absent 60 — Medium — **accept, rule**

- **Sequences**: an IOC's `150=4` and its overtaken `150=0` in the same
  second on a venue sending `60` without milliseconds; a drop copy with no 60;
  a broker tap merging two matching engines.
- **Docs said**: "equal timestamps count as not-earlier" — so the same-second
  late ack reopened the IOC for good, and the same rule made the
  cancel-then-new walkthrough work.
- **Landed**: a venue-asserted reopen needs either an answer to a pending
  request (the cancel-then-new `150=0` names the pending REPLACE, so it needs
  no time at all) or a TransactTime later than 9029 — strictly later by a
  whole second when either timestamp lacks milliseconds; never on an IOC/FOK
  (`59 ∈ {3, 4}`) that answers nothing (`lateIocAck`); a report with no 60 is
  judged by 52, and with neither is not believed (`reopenUndecidable`). The
  one-clock precondition is stated in 07 §1 with the knobs for a tap that
  does not meet it. [03 §1](03-state-machine.md#1-vocabulary),
  [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision),
  [07 §1](07-extension-points.md#1-tier-one-policies), 05 decisions and
  risks, 06 E37.

### S5 — Reopening reports were exempt from every guard — Medium — **accept, rule**

- **Sequence**: a bust applied, nine reports later a session resend
  redelivers it from outside the ExecID ring.
- **Went wrong**: 14/151/6 regressed to the old bust's restated values.
- **Landed**: a reopening report older than 9029 is `IGNORED(late)` whatever
  its CumQty (a bust the venue issued is newer than every report before it);
  and a small per-order **busted set** (9031: every bust's and correct's 19,
  every correct's own 17) makes a bust or correct naming one again a
  duplicate however old. [03 §5](03-state-machine.md#5-arbitration-on-execution-reports)
  steps 2 and 4, [03 §6.4](03-state-machine.md#64-358-executionreport),
  [02 §4](02-architecture.md#4-the-record), 06 E31a.

### S6 — A no-match `150=8` on a working order marked it rejected — Medium — **accept, rule**

- **Sequence**: the G is missed by the tap; `8 150=8 39=8 11=A-2 41=A-1 …`
  names no pending request.
- **Landed**: a `150=8` is an *order* reject only when it names a pending
  NEW or the chain has never been acked; otherwise — whatever it names,
  including nothing — a request reject: 38/44 and `39=8` not adopted,
  `requestRejectsAsReports`, `unmatchedRejects`. [03 §6.4](03-state-machine.md#64-358-executionreport),
  [03 §7.22](03-state-machine.md#722-a-1508-for-a-request-the-tap-never-saw),
  06 E16a.

### S7 — Inferred entries from a pending acknowledgement that names the working id — Medium — **accept, rule**

- **Sequence**: `8 150=E 11=M-1` (first seen; the venue acknowledges a
  replace under the working id) ; `G 11=M-2 41=M-1` ; `8 150=5 11=M-2`.
- **Went wrong**: an inferred REPLACE under M-1 that nothing ever answered
  stayed for the life of the order; a late D left an inferred NEW flagged
  inferred.
- **Landed**: a G or F whose 41 names an inferred entry of the same action
  re-keys it to the request's 11 and marks it requested; a late D converts an
  inferred NEW; 15038/15044 read 0 for an inferred REPLACE, and 9015 writes
  empty sub-fields for terms that were never proposed. [03 §6.1–6.2](03-state-machine.md#61-35d-newordersingle),
  [03 §7.23](03-state-machine.md#723-a-pending-acknowledgement-that-names-the-working-id),
  [02 §4](02-architecture.md#4-the-record), [02 §4.1](02-architecture.md#41-several-requests-in-flight-tag-9015),
  06 E17a, E41.

### S8 — A lookup hit whose record is gone; the reset and the audit topics — Medium — **accept, design**

- **Sequences**: after the daily reset, a GTC's next-day fill or a late bust
  for yesterday's order; a ClOrdID reused today against yesterday's
  `orders_audit` record.
- **Landed**: a lookup hit whose `order_state` record is gone creates the
  entry `MIDSTREAM` under the found key, `missLookupOrphans`; the reset
  deletes only `9017 = 'Y'` records so open orders keep theirs; 04 §9 states
  that a reused ClOrdID overwrites yesterday's audit SOW record and that the
  per-day history is the journal. [04 §3](04-recovery-and-operations.md#3-cache-misses),
  [04 §9](04-recovery-and-operations.md#9-the-daily-reset), 06 E51, E77, 05
  `ResetIT`.

### S9 — Rebuilding a cached entry from a lagging SOW; recreating an evicted entry from a bust — Medium — **accept, design**

- **Landed**: a lookup hit for a key that is cached binds the id only; for a
  key that is not cached, `publishFlush` precedes the keyed read so the SOW
  cannot lag the store; an evicted entry is recreated by the same path, never
  from the report, so 9021/9023 are not overwritten. Ids found on
  `orders_audit` that are not in 9015 are marked answered (S21).
  [04 §3–4](04-recovery-and-operations.md#3-cache-misses), 05 `EvictionIT`,
  06 E47.

### S10 — 17-less status reports never reached the machine — Medium — **accept, design**

- **Landed**: the audit key check is per topic: a message lacking one topic's
  key skips that topic and goes whole to `fix42/unrouted`, is published to
  the topics whose key it carries, and is applied to the blotter regardless;
  `key-guard` lists per topic (`37=NONE` on `execs`, `17 ∈ {0, NONE}` on
  `execs_audit`). [02 §5–6](02-architecture.md#5-publish-modes),
  [03 §9](03-state-machine.md#9-unrouted-and-unparseable-messages), 05
  `PublishPathIT`, 06 E76a.

### S11 — Unknown 150 values specified two ways — Medium — **accept, rule**

- **Landed**: a 150 value no rule names classifies as *fill* when 32 is
  present and *other* when not, is applied like any report, touches no
  pending REPLACE or CANCEL, and goes into the per-150 histogram; never
  unrouted. [03 §1](03-state-machine.md#1-vocabulary), [03 §5](03-state-machine.md#5-arbitration-on-execution-reports)
  step 1, [03 §9](03-state-machine.md#9-unrouted-and-unparseable-messages),
  [04 §8](04-recovery-and-operations.md#8-failure-matrix), 06 E76b.

### S12 — Precedence between "any report answers a NEW" and "never touch the set" — Medium/Low — **accept, rule**

- **Landed**: the NEW rule takes precedence; the "never touch" row applies to
  REPLACE and CANCEL entries. A `150=C` for a FOK that never worked answers
  its NEW. [03 §1](03-state-machine.md#1-vocabulary), [03 §6.4](03-state-machine.md#64-358-executionreport).

### S13 — Deleting the SOW file of a journalled topic — Medium/Low — **accept, design**

- **Landed**: `sow_delete` by filter is the only reset method; the failure
  matrix says the file may come back from the journal (verify on build);
  `PublishPathIT` pins which. [04 §8–9](04-recovery-and-operations.md#8-failure-matrix),
  05 phase 3.

### S14 — Two publishes per message are not atomic — Low/Medium — **noted**

- **Landed**: the order is plan → blotter publish → audit publishes → commit;
  a publish that throws after earlier ones were stored leaves those to be
  sent, idempotent on keyed topics and a possible duplicate on the non-SOW
  catch-all topics; the `HAClient` stores and returns while disconnected
  (verify on build); a QuickFIX/J `fromApp` exception is a session event with
  a resend, not an in-place retry (assumption, phase 4). [02 §5](02-architecture.md#5-publish-modes),
  [02 §7](02-architecture.md#7-threading-and-ordering), [04 §6](04-recovery-and-operations.md#6-the-publish-path),
  06 E70c.

### Low findings

| # | finding | landed |
| --- | --- | --- |
| S15 | 9014 on a no-match `150=0` unspecified; "adopting its terms" ambiguous | only on a match; untouched otherwise; set at `MIDSTREAM` creation from a `150=0`/`150=5`; the *report's* 38/44 — [03 §6.4](03-state-machine.md#64-358-executionreport) |
| S16 | the no-match `150=4` removes every pending CANCEL, including one the cancel-then-new leg should not | noted under [03 §7.16](03-state-machine.md#716-a-replace-reported-as-cancel-then-new); `unmatchedRejects`' healthy value qualified in 04 §7 |
| S17 | 9015's `0` sentinel ambiguous with a genuine zero price | empty sub-fields for terms never proposed — [02 §4.1](02-architecture.md#41-several-requests-in-flight-tag-9015); every walkthrough encoding updated |
| S18 | stripping stated for the D only | every payload the machine builds from a message — [03 §6.1](03-state-machine.md#61-35d-newordersingle) |
| S19 | 9029 excluded a `35=9`'s 60 | a reject updates 9029 — [03 §6.5](03-state-machine.md#65-359-ordercancelreject), [02 §4](02-architecture.md#4-the-record) |
| S20 | trailer unconditional vs "when it resolves"; the diagram published audit before plan | omitted when unresolved; the order stated once — [02 §1](02-architecture.md#1-topology), [02 §5](02-architecture.md#5-publish-modes), [03 §9](03-state-machine.md#9-unrouted-and-unparseable-messages) |
| S21 | a PossDup resend of a request answered before the 9021 cap re-added as pending | ids found on `orders_audit` and not in 9015 are marked answered — [04 §3](04-recovery-and-operations.md#3-cache-misses) |
| S22 | 9028 after zeroing; which ExecID a bust names | 9028 keeps the busted id; 19 is compared against the last fill's original and latest correction — [03 §6.4](03-state-machine.md#64-358-executionreport) |

## 4. Walkthroughs recomputed by the reviewer

7.1, 7.4, 7.14, 7.16, 7.17, 7.18, 7.19, 7.6, 7.7 and 7.20 agree with the
rules cell for cell. The one discrepancy was the "wire" column of 7.1, which
listed 38 without 44 and 15035 inconsistently; corrected.

## 5. Consistency sweep

Twenty-one items, each fixed where it was found: the resolution order quoted
wrongly in 06 E58; unknown 150 values in 03 §9 vs 04 §8; `execs` outside the
journal rule; recreation "from the report" in 04 §4 vs "from the record" in
04 §3; the repair `publish` vs the cache's contents; key-tag-missing messages
diverted vs applied; the two §6.4 rows without precedence; E51 vs the reset;
counters named but absent from 04 §7 (`missLookups`, the per-150 histogram,
and the new `missLookupOrphans`, `lateIocAck`, `reopenUndecidable`,
`auditPublishFailures`, `repairs`); knobs absent from 07 §1
(`miss-lookup-by-order-id` and the operations group) and the unstated rule
that the two key tags must be equal; the `load-open-orders-only` comment; the
diagram's publish order; 08 §8's over-claims; the mock switches 05 phase 1
needed but did not list (`rejectWithoutOrderId`, `bustAfterCloseOut`, a 39
parameter on `cancelReject`, `restateWhilePending`,
`amendNamingAnotherOrder`, `pendingAcksEchoWorkingId`,
`transactTimeInSeconds`, `orderIdEqualsClOrdId`); the `PublishPathIT` case
the router would have diverted; the runner's profile; 15038/15044 for an
inferred REPLACE; 02 §4.1's missing policy caveat; E41's 38/44 wording; the
late guard's precedence over the terminal-after-terminal row; the unused
9031 (now the busted set) and the non-record tags 9032/9033; the `44=0`
consumer note.

**Still to verify on a build**, carried from both rounds: AMPS's persisted
acknowledgement for unjournalled topics (S3); AMPS rebuilding a journalled
topic's missing SOW file at startup (S13); `HAClient.publish` while
disconnected (S14); a `delta_publish` with no existing record (04 §3); the
fix parser's indifference to a missing tag 35 and to trailer fields after
tag 10; a regex bookmark subscription's ordering across topics (04 §2.2).
Phase 3's `PublishPathIT` pins each.
