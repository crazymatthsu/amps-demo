# Can an AMPS view maintain the live FIX 4.2 order state?

**No — and the failures are structural, not a syntax problem to engineer
around.** This document earns that verdict the honest way: it maps every
requested output field to its FIX source, builds the three view constructions
that come closest, and shows the specific input sequence from the docs/fix42
contract that breaks each one. It closes with what views *are* the right tool
for in this pipeline, because the answer is not "nothing".

Throughout, "the contract" means
[docs/fix42/01-fix42-messages-and-state-machine.md](../fix42/01-fix42-messages-and-state-machine.md),
and "edge case N" means §7 of it.

## 1. What an AMPS view is — and what the question needs

An AMPS view is a **declarative projection over the current records of SOW
topics**, maintained incrementally by the server and itself queryable and
subscribable like any SOW topic. The vocabulary available (verify exact
function set against your AMPS version): field expressions, grouped aggregation
(`SUM`/`COUNT`/`AVG`/`MIN`/`MAX`-style over a `Grouping` key), and equality
joins across underlying topics. Two properties define the boundary:

- A view is a function of **what the SOW currently holds**, not of the message
  sequence that produced it. History is invisible; arrival order is invisible.
- A view keeps **no memory of its own** beyond the aggregates it declares. No
  lookup tables, no per-key scratch state, no "remember this until that
  arrives".

The requested output is one live record per order carrying: order status,
CumQty, AvgPx, acked qty, acked price, pending qty change, pending price
change — under new/amend/cancel request → pending → ack/reject transitions and
fill bust/correct. Restated as the contract does: the OrderState row of §4,
maintained by rules §5.

## 2. Field-by-field: where each requested value comes from

FIX 4.2 does most of the arithmetic before AMPS ever sees the message — an
ExecutionReport is a cumulative snapshot, not an increment
([fix-order-state.md](../src/fix-order-state.md) develops this). That is why
the table below has so many "on the latest valid 8" rows — and why the whole
problem compresses into *selecting and filing* reports rather than computing:

| requested field | FIX source | derivable by a view? |
| --- | --- | --- |
| order status | tag 39 on latest valid 8 — **except** the locally-imposed `PENDING_NEW` / `PENDING_CANCEL` / `PENDING_REPLACE` windows and the revert-on-9 | no — sequence-dependent (§3.3, §3.4) |
| CumQty | tag 14 on latest valid 8 | only if "latest valid" were selectable — §3.2 |
| AvgPx | tag 6 on latest valid 8, venue-computed (already bust/correct-adjusted) | same |
| LeavesQty | tag 151 on latest valid 8 | same |
| acked qty / acked price | tags 38/44 as of the last ack (150=0/5) — a G's new terms count only after its confirming 8 | no — requires the staged-terms rule (§5 rule 3 of the contract) |
| pending qty change / pending price change | tags 38/44 of an **in-flight** G, cleared by 8(150=5) or 9(434=2) | no — request/response correlation (§3.3) |
| pending action / pending ClOrdID | existence of an unresolved F/G | no — same |
| fill bust / correct | 8 with 20=1/2 + 19; venue restates 14/151/6/39 absolutely | adopting restated values needs "latest valid" selection; and busts break every aggregation shortcut (§3.2) |

The one honest "almost": if a chain never replaced, never had anything pending,
and messages never arrived out of order or twice, *the latest 8 is the state*
and a SOW keyed on `/37` delivers it with zero code. Every "no" above is a
departure from that happy path — and the contract exists precisely because the
departures are the job.

## 3. The three constructions that almost work

### 3.1 Last report per order: SOW `algo/8` keyed `/37`

No view at all — just the key. What it gets right: for a quiet chain, tags
39/14/151/6/38/44 of the stored record *are* the current status, CumQty,
LeavesQty, AvgPx, and acked terms. Five independent failures:

1. **The pre-ack window.** D, G, F carry no tag 37, so nothing before the first
   8 exists in this topic. `PENDING_NEW` is unrepresentable; edge case 5
   (reject-before-ack) at least surfaces as the reject 8, but edge case 1
   (search by ClOrdID before 37 exists) has nothing to find.
2. **A 9 can carry `37=NONE`** (contract §2) — cancel-rejects for never-acked
   orders either key to the junk value `NONE` (all of them, one record) or
   cannot be filed at all.
3. **No pending fields, ever.** G and F are different message types in
   different topics; nothing in this construction reads them.
4. **Last-arrived is not last-valid.** SOW upsert is unconditional: a PossDup
   resend or an out-of-order recovery report overwrites a newer record. Edge
   case 12 (stale lower-CumQty 8 must be ignored) is violated by construction —
   the SOW has no conditional apply.
5. **Chain identity is only as good as tag 37 stability** — the contract keys
   chains on OrderKey with 37 *preferred* but resolved through 11/41 binding
   (§3), exactly because 37 alone cannot cover the whole lifecycle.

### 3.2 Aggregated view over per-exec records: GROUP BY `/37` on `algo/8` keyed `/17`

Keying `algo/8` on ExecID keeps every report (and absorbs identical PossDup
resends at the store — a genuine, if partial, dedupe). Now aggregate per order:

- `CumQty = MAX(/14)`? **Wrong after a bust.** Edge case 6: FILLED, then
  20=1 bust restates CumQty *down* and status to PARTIALLY_FILLED. CumQty is
  monotonic per chain in 4.2 *except* under busts — `MAX` freezes the
  pre-bust value forever. The contract's rule is "adopt the restated absolute
  from the latest valid report", which is a selection, not an aggregate.
- `CumQty = SUM(/32)` (sum the fills)? Wrong the moment a bust or correct
  arrives, unless the *stored per-exec records* are rewritten to reflect their
  disposition — and knowing that a 20=1 report should mutate a *different*
  record (the one named by tag 19) is state-machine work; no view rewrites
  underlying records. The venue already did this sum correctly into tags 14/6;
  re-deriving it is both redundant and harder.
- `AvgPx`: needs tag 6 **from the same report** that supplied the winning
  CumQty. That is an argmax ("field B of the row maximising field A"), which
  grouped aggregation does not express — `MAX(/14)` and `MAX(/6)` can come
  from different reports (a correct can move AvgPx down). An arrival-order
  `LAST`-style aggregate, if your version has one, reintroduces failure 4 of
  §3.1 verbatim: arrival order is exactly what the stale guard says not to
  trust.
- `OrdStatus`: same argmax problem, plus §3.4 below.
- And the grouping key is still `/37` — failures 1, 2, 5 of §3.1 carry over
  untouched.

### 3.3 Joined views for the pending fields

"Pending replace" means: a G exists whose resolution has not arrived. Try to
say that relationally:

- Join `algo/G` to the order on `41 = <working ClOrdID>`? One equality hop.
  Edge case 2 (chain C1→C2→C3) needs the transitive closure of 41→11 — every
  ClOrdID ever seen resolving to one chain, which the contract implements as
  the `key_by_clordid` map (§3). Views have equality joins, not recursion; no
  view computes a closure of unbounded depth.
- "Unresolved" = no 8 with 150=5 for this G's ClOrdID **and** no 9 with
  `11 = <that ClOrdID>` — a NOT EXISTS anti-join, not in the view vocabulary.
- Even granting both: edge case 4 has an F and a G in flight **simultaneously**,
  and the 9 for the F must revert only the cancel, restoring a status
  snapshotted *when the F arrived* (`prior_status[ClOrdID]`, §5 rule 5). That
  snapshot is per-request memory of a past state — there is no record currently
  in any SOW that contains it. A view cannot produce a value that exists
  nowhere in its inputs.

### 3.4 The residue: status is a function of sequence

Even with identity, selection, and correlation granted by magic, `OrdStatus`
still is not a projection: `PENDING_NEW` exists because a D arrived and its 8
has not (rule 1); `PENDING_CANCEL`/`PENDING_REPLACE` are imposed by F/G arrival
and *lifted* by whichever of 8 or 9 comes first (rules 3–5); a fill landing
mid-pending updates quantities while the pending flags survive (edge case 10);
a late D must not clobber venue state already built from an early 8 (edge case
9). Each rule reads "when X arrives *after* Y, do Z" — conditional logic over
arrival history, the one thing a function of current records cannot see.

## 4. The rest of the server-side toolbox, considered and declined

For completeness — the other places AMPS can run logic, and why none closes the
gap:

- **Enrichment / preprocessing** on a topic: per-message field computation at
  publish time. Stateless with respect to other messages — can stamp, rename,
  compute within one message; cannot correlate two.
- **Conflated topics**: rate control (latest-per-key at an interval). Changes
  delivery cadence, not semantics.
- **A custom server module (C++ SDK)**: could, in principle, host the entire
  state machine inside the broker process. Declined on engineering grounds,
  not capability: the machine is the most test-hungry code in the system
  (every numbered contract rule demands a unit test; the existing
  [FixOrderStateMachineTest](../../common/src/test/java/com/demo/amps/common/fix/FixOrderStateMachineTest.java)
  shows the density), and welding it into the broker couples its release cycle
  to server upgrades, moves its failures into the message path, and makes the
  replay-through-new-rules deployment story (see
  [03-proposed-architecture.md](03-proposed-architecture.md)) awkward. A
  subscriber process gets the same inputs with none of that.

## 5. What views are the right tool for — above the machine, not below it

Every blocker above is about *deriving* order state from raw FIX. Once the
state machine (outside AMPS) publishes its OrderState rows keyed on the stable
`OrderKey`, the ground shifts: keys never move, records are self-contained
snapshots, and there is no sequence logic left. That is exactly the terrain
views were built for:

| view over `algo/orders` | expression sketch |
| --- | --- |
| open exposure per account/symbol | `SUM(/LeavesQty)` grouped by `/Account`, `/Symbol`, filtered `/Terminal = false` |
| order counts by status | `COUNT(*)` grouped by `/OrdStatus` |
| pending-amend blotter | no aggregation — a filtered subscription: `/PendingAction = 'REPLACE'` |
| per-order exec count / notional | over `algo/executions`, grouped by `/OrderKey` |

(Two practical notes for that stage: aggregate over the JSON-typed derived
topics, where numeric comparison is unambiguous — the fix-typed ingress topics
carry text values; and verify view syntax against your AMPS version, a
recurring caveat in this repo because none of it was written against the docs
of one pinned release.)

## 6. Verdict

The AMPS view layer can express none of the four load-bearing behaviours —
chain identity (a transitive closure), latest-*valid* selection (conditional
apply / argmax), pending-request correlation (anti-join plus per-request
snapshots), and sequence-dependent status. These are not gaps in view syntax;
they are the definition of a state machine, and the contract's §5 rulebook is
its specification. Build it outside AMPS — the proposal, including how thin
"outside" actually is and everything AMPS still does around it, is
[03-proposed-architecture.md](03-proposed-architecture.md).
