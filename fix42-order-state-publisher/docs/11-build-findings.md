# 11. Build findings

What building the design found that reading it did not: places where the
rules of [03](03-state-machine.md), executed, do something the documents
did not say, and the answers to the **verify on build** questions as the
integration suites pin them. Each finding names the test that pins it, so
a change of mind is a change to one test, made on purpose.

A finding is not a bug report against the code. The code does what the
documents say; a finding is where what they say has a consequence they did
not state.

---

## 1. The state machine (phase 2)

### F1 — The late guard runs before the after-terminal table, and shadows two of its rows

[03 §5](03-state-machine.md#5-arbitration-on-execution-reports) fixes the
arbitration order: classify, duplicate, stale, **late**, then **after a
terminal status**, and [07 §4](07-extension-points.md#4-what-is-not-extensible-on-purpose)
makes that order non-negotiable. Step 4 drops a non-reopening report whose
CumQty equals the record's and whose `60` is older than 9029. Two rows of the
after-terminal table in [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision)
are written as if a report reaches them regardless of its time, and with
`transact-time-guard: true` (the default) neither is reached by an older
report:

| row | what the table says | what happens to a report with an older `60` |
| --- | --- | --- |
| a fill, 14 **equal**, 17 new (06 E21) | 17, 150, 32, 31, 30, 9028 adopted | `IGNORED(late)` at step 4; the record keeps the previous fill's 17/32/31/30/9028 |
| `non-fill-after-terminal: ADOPT_VENUE` | "applies it without the time test" | an equal-CumQty re-ack older than 9029 is `IGNORED(late)` at step 4 |

Neither costs money: the economics are equal by construction in the first row,
and the second is the conservative side. But the documents read as if both rows
work by default, and they do not:

- the detail-only row fires only for a counted fill stamped **no earlier** than
  the terminal report (a venue that stamps 60 at send time, or a fill with no
  60 and no 52);
- `ADOPT_VENUE` differs from `ADOPT_VENUE_IF_LATER` only for a re-ack whose
  time **equals** 9029 or that has none. A deployment that wants it to believe
  an older re-ack needs `transact-time-guard: false` as well, which is the
  pairing [07 §1](07-extension-points.md#1-tier-one-policies) already
  recommends for a tap merging clocks.

**Disposition:** the order of the steps stands. Recommended doc change: one
sentence under each of the two rows saying it is reached only past step 4.
Pinned by `WalkthroughTest` 7.12
(`aCountedFillWithAnOlderTimeIsCaughtByTheLateGuardFirst`) and
`CoverageMatrixTest.e36`.

### F2 — A pending acknowledgement's 39 outlives the request it acknowledged

A `150=6` (or `150=E`) moves the record's 39 to `6` (`E`): that is the venue's
own status, and [03 §4](03-state-machine.md#4-status-venue-truth-and-one-policy-decision)
adopts it. When the venue then rejects the cancel with a `35=9` whose 39 is the
**stale pending status** — `39=6` with no CANCEL left in the set — the
`WHEN_NOT_TERMINAL` policy rightly refuses it (R6). But nothing else touches the
record's 39, so it goes on reading `6`, pending cancel, with `9016=0`, until the
next report. A venue whose rejects carry the status at request time produces
this on every rejected cancel that was acknowledged first (06 E15 and E31 under
the `reject-status-at-request-time` mock dialect).

The design has no snapshot to revert to, by intent: that is what makes several
requests in flight trivial. Options:

| option | effect | cost |
| --- | --- | --- |
| **leave it** (current) | `39=6 9016=0` until the next report; a reader can derive "not really pending" from `9016=0` | the record contradicts itself for a while |
| derive on the reject | when a reject removes the last request of the kind the record's pending 39 stands for, and its own 39 is not adopted, set 39 from the cumulative state: `0` if `14 = 0`, else `1` | the machine computes a status, which [03 §8](03-state-machine.md#8-what-the-machine-does-not-do) says it does not |
| a `beforeApply` hook | the deployment that connects to such a venue rewrites the 9's 39 to the derived value ([07 §2](07-extension-points.md#2-tier-two-hooks)) | per venue |

**Disposition: open, for the design owner.** Recommendation: the hook, since
it is one venue convention and the hook SPI exists for exactly this; the
derivation is the fallback if more than one venue does it. Pinned as a known
divergence in `EdgeCaseFlowPropertyTest` (`KNOWN`), so a fix fails the entry
and it is removed on purpose.

### F3 — Whole-second TransactTime keeps a same-second re-ack out

Under `60` without milliseconds, a venue's re-ack 250 ms after an erroneous
`150=4` (06 E36) is not "later by a whole second", and an order not answering a
request cannot be reopened by it: the record stays canceled. This is the S4 rule
working as designed — a wrong reopen is live exposure the views never stop
counting, so the conservative side wins — and is recorded here only because the
property test shows it end to end. Pinned as a known divergence in
`EdgeCaseFlowPropertyTest`.

### Mock-venue combinations that contradict their own case

Three edge cases pin a dialect switch rather than run under every one, because
the switch removes the case's premise: E18 is a reject that *carries* `39=4`, so
it cannot also speak the request-time-status dialect; E43 resolves by a stable
37, so it cannot also rotate OrderIDs across the replaces the tap missed (with
both missed and 37 rotated, nothing links the confirm to the order, and the
documented second `MIDSTREAM` record is the result); E57 is two orders with two
OrderIDs, so it cannot also make each OrderID its ClOrdID. Recorded in
`EdgeCaseFlow` beside each case.
