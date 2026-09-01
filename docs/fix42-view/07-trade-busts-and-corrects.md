# Trade busts and corrects: the restatement is the merge

Change record for crazymatthsu/amps-demo#22 (commit `88a42e6`, 2026-08-31).
The question that prompted it: *does the delta publish really keep CumQty (14),
LeavesQty (151) and AvgPx (6) correct on `sow/{scope}/orders` for execution
amendment and execution cancel?* The answer split cleanly in two — and the
second half is what this change closed.

## What the investigation found

**Order-level amend (150=5) and cancel (150=4): already correct.** The
projection selects its tags from the full source message, the mock venue
stamps absolute 14/151/6 on every report, and the cancel delta *re-writes*
14/6 with identical values rather than preserving them by omission — so the
blotter stayed right even if an earlier fill delta had been lost.

**Execution-level trade correct (20=2) and trade bust (20=1): not implemented
at all.** No route read tag 20; tags 19/20 appeared in no route's tag lists,
so both were stripped from every published message; `EXEC_REF_ID` was a
declared-but-dead constant; the mock venue hardcoded `20=0` and could not even
express a bust (`fillInternal` rejects `shares <= 0`). Two failure shapes
followed:

1. A bust shaped the way [the contract](../fix42/01-fix42-messages-and-state-machine.md)
   describes — `20=1`, `19=<busted ExecID>`, restated absolutes, tag 150
   still `1`/`2` — would be **swallowed by the fill routes**: numerically
   correct on the blotter by luck of newest-wins, but indistinguishable from a
   fill, with the 19/20 reference lost everywhere.
2. A report shaped `150=D` (or a 4.3-style `G`/`H`) would fall into the
   `exec-other` catch-all, which has **no blotter projection** — leaving
   `sow/{scope}/orders` with stale CumQty/LeavesQty/AvgPx indefinitely.

The publisher README declared busts and corrects out of scope; the test
oracles were strictly accumulating and would have failed on any restatement
fixture. There was no latent coverage to build on.

## Why FIX 4.2 makes a bust look like a fill

FIX 4.2 has no bust or correct ExecType — `150=H`/`150=G` arrived in 4.3 when
ExecTransType was deprecated. In 4.2 the semantics ride **entirely on tag
20**, with tag 150 mirroring the restated OrdStatus (39). So a bust that takes
an order from FILLED back to PARTIALLY_FILLED arrives as `35=8|150=1|39=1|20=1`
— byte-for-byte a partial fill except for tags 19 and 20. Any routing scheme
keyed on 35+150 alone cannot see the difference, which is exactly what the old
rulebook was.

## What changed

### Routing: tag 20 became a match dimension

`Fix42Properties.Route` gained `exec-trans-types` (empty = any, mirroring
`exec-types`), and two routes in
[`application.yml`](../../fix42-publisher/src/main/resources/application.yml)
— `exec-bust` (`20=1`) and `exec-correct` (`20=2`) — are declared **ahead of
the 150-based rules**, so first-match-wins settles the collision. No existing
route was annotated: a `20=0` (or tag-20-absent) report fails the new routes'
match and falls through to the old rules unchanged. A `validate()` rule
refuses `exec-trans-types` on anything but a `35=8` route, since tag 20 exists
only on execution reports and such a rule could never match as written.

### The blotter projection: adopt the absolutes, drop the reference

The venue restates 14/151/6/39 as absolutes — which is precisely what lets the
blotter handle a bust with **no state machine at order level**: the same
newest-wins merge that applied a fill applies its reversal. The projections
are deliberate about what they leave out:

| tag | on the blotter? | why |
| --- | --- | --- |
| 14 / 151 / 6 / 39 / 38 | yes | the venue's restated absolutes — adopted wholesale |
| 19 / 20 | **no** | they describe a *prior* execution; merged onto the record they would sit stale the moment the next fill arrives |
| 31 / 32 / 30 | bust: **no** · correct: yes | a bust reports no new trade, so the blotter keeps the last real fill's values; a correct's 32/31 *are* the execution's new values |
| 9010–9014 | untouched | a bust or correct answers no outstanding request |

The exec topics (`sow/parent/execs`, `sow/parent/execs_audit`) **do** carry
19/20 — there the reference is the point. On the latest-per-order execs record
they persist until something overwrites them, the same "most recent report,
not this report" caveat 31/32 have always had.

### The mock venue: replay, don't reverse

`OrderChain` now keeps a per-fill history (busted entries stay in the list,
flagged, so fill ordinals never shift) and gained `bust(fillOrdinal)` and
`correct(fillOrdinal, newShares, newLastPx)`. The restated trio is recomputed
by **replaying the surviving fills through `Prices.averagePrice` from zero**,
not by subtracting from the running totals: the running AvgPx is rounded to
four decimals at every step, and a reversal would leak that rounding into the
restated value, while a replay produces exactly what a venue that never saw
the busted fill would have published.

Semantics follow [the contract](../fix42/01-fix42-messages-and-state-machine.md):
tag 150 mirrors the restated 39; busting the last fill of a FILLED order
reopens it (edge case 6); and a bust or correct after the order closed out
(cancel / done-for-day / reject) throws — the stopped balance must not
resurrect, and the mock does not model that venue-specific ambiguity. Guards
also reject double busts, out-of-range ordinals, and corrects that would push
CumQty past OrderQty.

### Two new scenario chains

| chain | script | what it pins |
| --- | --- | --- |
| `PARENT-AMZN` | 6000 @ 210.00: fills 2000 @ 209.95 and 1000 @ 210.05, then `bust(1)` — and the chain is **left working** | the bust is the last blotter-touching event, so the stored record is the restatement itself: `14=1000`, `151=5000`, `6=210.05`, `39=1` |
| `PARENT-META` | sell 3000 @ 512.00: fill 1200 @ 512.10, `correct(1, 1200, 511.95)`, then fill out 1800 @ 512.00 | the terminal AvgPx is `(1200×511.95 + 1800×512.00)/3000 = 511.98` **only if the correction applied** — it would read 512.04 otherwise |

Both were appended after the existing seven chains, so every previously
scripted timestamp is unchanged.

### The oracles had to learn subtraction

`MockFixFlowTest.averagePriceMatchesItsOwnFills` now replays the history **per
ExecID** — a fill adds an entry, `20=1` removes the one tag 19 names, `20=2`
replaces its economics in place — and recomputes the VWAP fresh after every
report. `Fix42DeltaPublishIT.storedExecutionsAreSelfConsistent` separates
genuine fills from restatements by tag 20 (stored fill records carry no tag 20
at all, since the fill routes never selected it) and asserts the restatement
invariants: tag 19 present, `38 = 14 + 151`, no 32 on a bust, positive 32 on a
correct.

## Verified

All of it against a real AMPS container (5.3.5.135 under podman, cli
harness):

```bash
./gradlew :fix42-publisher:test              # 102 unit tests (was 82)
AMPS_IMAGE=<your-image> \
  ./gradlew :fix42-publisher:integrationTest # 32 tests (was 29), 0 skipped
```

Repo-wide with the image set: **301 tests, 0 failed, 0 skipped** (baseline
278). The three new integration tests are worth naming, because two of them
are the first stored-blotter AvgPx assertions in the entire suite:

- `blotterAdoptsRestatedTotalsAfterBust` — the stored AMZN record reads
  `14=1000 / 151=5000 / 6=210.05 / 39=1`, keeps the last real fill's 32/31,
  and carries no 19/20.
- `blotterReflectsCorrectedFillEconomics` — the stored META record ends
  `14=3000 / 151=0 / 6=511.98 / 39=2`.
- `execsAuditKeepsBustAndCorrectWithReferences` — the audit keeps the bust
  *beside* its target (per-ExecID keying), reference pair intact, while the
  busted fill's own record still shows its original 32/31 untouched.

## Still outside a merge's reach

Unchanged, and now restated in the
[publisher README](../../fix42-publisher/README.md):

- **Per-execution disposition.** Marking the ExecID named by tag 19 as BUSTED
  on the execs topics would rewrite a *different* record than the one being
  published — state-machine work no merge expresses
  ([02, §3.2](02-amps-view-feasibility.md)).
- **Stale/duplicate arbitration and ExecID dedupe** — the merge is still
  unconditional.
- **`FixOrderStateMachine`'s stale guard** (`common` module) still ignores any
  report whose CumQty went backwards, including a legitimate bust — the
  contract's `20=1/2` exemption is not implemented there. A different
  consumer, deliberately left out of this change.
