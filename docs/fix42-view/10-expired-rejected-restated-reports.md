# Expired, rejected and restated reports: the catch-all was a gap

Change record, 2026-09-16. The question that prompted it: *why does an
expired order still show LeavesQty on `sow/fix42/orders` and in the exposure
views, when `sow/fix42/execs` already says it expired?* The answer was a
rulebook gap, not a merge limit, and the fix is two routes and one stamp in
[`application.yml`](../../fix42-publisher/src/main/resources/application.yml).
The views themselves are unchanged; for what they compute and the trap they
avoid, see [08](08-exposure-views.md) and [09](09-parent-child-reconciliation.md).

## What the investigation found

`exec-other`, the catch-all at the end of the `35=8` block, matched ExecType
A, 6, E, 8, D and C. It publishes to `sow/fix42/execs` and
`sow/fix42/execs_audit` and has **no `projected-topics`**, which is right for
the pending acknowledgements (150=A/6/E acknowledge a request without
resolving it, so the pending family must not move) and wrong for the other
three:

| 150 | what the venue is saying | what the blotter kept |
| --- | --- | --- |
| `C` Expired | the unfilled balance stopped working; `151=0` | the last working 151 |
| `8` Rejected | the order never worked; `151=0`, `39=8` | `39=0` from the ack, or `9013=NEW` forever on a reject-before-ack |
| `D` Restated | the venue changed 38/44 on its own; 151 restated against the new 38 | the `35=D`'s 38, and a 151 that no longer adds up |

Reproduced on `localhost/amps-demo:5.3.5.135`, on a throwaway container with
the scripted flow published: a `150=C` for `PARENT-GOOG` (800 acked, nothing
filled) published the way `exec-other` delivers it left `sow/fix42/execs`
reading `151=0` while the blotter record and the
`view/fix42/exposure/parent` row for `ACC-HEDGE-07 / GOOG / buy` still read
`151=800`. Projecting the same report onto the blotter took the view to 0.
Nothing else in the flow would ever have corrected it: the order was
terminal, so no later report was coming, and a merge can only be told.

The exposure views made the gap visible where the blotter alone would not
have. They `SUM` the blotter's 151 per account × symbol × side, so an
expired order is not one stale record but live exposure on a desk's row,
indefinitely.

## What changed

### Two routes ahead of the catch-all

Both are declared before `exec-other`, so first-match-wins settles it; the
catch-all now sees only 150=A/6/E, and says so in its comment.

**`exec-expired-or-rejected`** (`150=C`, `150=8`) has the shape of
`exec-cancel-or-done`: identity and status to the exec topics, with 103
(OrdRejReason) and 58 (Text) for why; onto the blotter, 11, 41, 39, 150, 60,
14, 151, 6 and the pending family cleared — an expiry or a reject resolves
whatever was in flight, by making it moot. 103/58 stay off the blotter for
the reason 19/20 do on a bust: they describe *this* report and would sit
stale on the merged record.

**`exec-restated`** (`150=D`) projects the restated absolutes — 11, 41, 39,
150, 60, 38, 44, 14, 151, 6 — and **touches no pending tag**. A restatement
answers no request, and an amend may be in flight at the same moment; its
proposal has to survive until its own `150=5` or `35=9`. 378
(ExecRestatementReason, new in 4.2 alongside ExecType D) goes to the exec
topics only. Unlike a bust, 150 does not mirror 39 here: `D` is what the
report *is*, and 39 is the status the order still has.

On both, 11 and 41 pass through untouched. They are the chaining key
generator's inputs, and a terminal report on an amended chain has to land on
the chain's record, not open one of its own.

### `151=0` stamped on every terminal projection

`exec-cancel-or-done` and the new C/8 route both carry

```yaml
set-tags:
  "151": "0"
```

alongside a projection that also selects 151. `PublishPlanner.project` applies
verbatim tags first, then copies, then literals, so the literal wins: a venue
that echoes the last working balance on the expiry, or omits LeavesQty on a
cancel altogether (the balance is gone, so some do not report it), can no
longer leave stale exposure on the record. `Fix42Properties.validate()`
needed no change — `Projection.producedTags()` already counts a set-tag as a
produced tag for the key check — and the audit topics are unaffected, since
only the projected payload is stamped. `PublishPlannerTest` pins both halves:
a `150=C` carrying `151=800` reaches the blotter as `151=0` and the exec
topics as `151=800`; a `150=4` with no 151 at all reaches the blotter as
`151=0` and the exec topics with no 151 invented.

### The mock venue: one chain that is cut down, then expires

`OrderChain` gained `expire()` (150=C/39=C, LeavesQty to zero, terminal) and
`restateOrderQty(newOrderQty, reason)` (150=D with tag 378; 38 restated, 151
recomputed against it, 14/6 untouched; refused below CumQty, while an amend is
in flight, or after close-out — a real venue may do any of those, but the mock
would then be guessing which terms the venue meant to keep). One scenario
uses both, appended after the nine existing chains so every previously
scripted timestamp is unchanged:

| chain | script | what it pins |
| --- | --- | --- |
| `PARENT-NFLX` | GTD (59=6, so the `35=D` carries an ExpireTime) 2500 @ 1200.00: fill 1000 @ 1199.50; `restateOrderQty(2000, "5")` — an exchange-initiated partial decline; `expire()` | the blotter ends `38=2000 / 14=1000 / 151=0 / 39=C / 9013=NONE`. Without the restated route it reads `38=2500`; without the expiry route, `151=1000` — and the parent view's `ACC-INSTL-02 / NFLX / buy` row carries those 1000 shares as exposure |

## What the tests pin

Unit, without a server:

- `PublishPlannerTest`: the three ExecTypes route ahead of the catch-all
  while A/6/E still fall through to it and stay off the blotter; the exact
  projected payload of an expiry; a reject's 103/58 on the exec topics and
  `39=8` on the blotter; 151 forced to zero whatever the venue sent or
  omitted; a restatement's terms adopted with the pending family untouched;
  11/41 intact on every payload of every new route.
- `Fix42PublisherContextTest`, against the shipped `application.yml`: the
  same four properties through the real rulebook, plus `150=A` added to the
  pending acknowledgements that must never reach the blotter.
- `Fix42PropertiesTest`: a set-tag over a projected tag validates.
- `MockFixFlowTest` / `FillArithmeticTest`: the NFLX chain's invariants,
  `expire()` beside cancel and done-for-day, `restateOrderQty` and its guards.

Integration, on a throwaway container, live and without a restart:

- `Fix42DeltaPublishIT.expiredOrderEndsWithNothingWorkingOnTheBlotter`: the
  stored NFLX record reads `151=0 / 39=C / 150=C / 14=1000 / 6=1199.5`, pending
  cleared, working id still `PARENT-NFLX-1`, the `35=D`'s 59/126 intact, and
  agrees with `sow/fix42/execs`.
- `Fix42DeltaPublishIT.restatedTermsReachTheBlotter`: `38=2000` on the
  blotter, 378 only on `execs_audit`.
- `ExposureViewIT`: the NFLX row reads `OrderQty 2000 / LeavesQty 0 /
  CumQty 1000 / AvgPx 1199.5` — it is now the flow's last message, so it is
  also what the suite waits on before reading anything. And
  `expiryDropsLeavesQtyLive`, ordered last because it adds a group: a fresh
  order is worked and part-filled, its row read at `LeavesQty 2500`, the
  expiry alone published, and the same row read at `0` — the transition,
  on the live update path, with no restart between the two reads.
- `ReconViewIT`: one more unsliced row, `NFLX`, with null child columns; the
  break test still finds exactly one break.

The views' expressions stay bare throughout, for the reason measured in
[08](08-exposure-views.md): on this build an aggregate inside `IF()` drifts
on exactly the live path these tests read.

## Verified

Against a real AMPS container (5.3.5.135 under podman, cli harness):

```bash
./gradlew :fix42-publisher:test              # 121 unit tests (was 103)
AMPS_IMAGE=localhost/amps-demo:5.3.5.135 \
  ./gradlew :fix42-publisher:integrationTest # 43 tests (was 40), 0 skipped
```

The server config was not touched, so `amps.sh validate` was not needed.

## Still outside a merge's reach

Unchanged, and restated in the
[publisher README](../../fix42-publisher/README.md#what-this-still-does-not-give-you):
stale/duplicate arbitration, ExecID dedupe, per-execution disposition, and
per-request pending snapshots for two simultaneous in-flight requests. Each
needs *conditional* apply, which is what separates them from this change:
here the venue stated `151=0` and the restated 38 outright, and the merge
only had to be told to carry them.
