# Can an AMPS view maintain the live FIX 4.2 order state?

**Not the full contract — but one of the four blockers identified by the first
pass of this analysis has an off-the-shelf server-side answer.** AMPS ships an
optional **chaining key generator** module
([user guide: optional modules](https://crankuptheamps.com/docs/amps-user-guide/optional-modules/chaining-key-generator))
whose documented example is literally FIX tags 11/41: it computes the
transitive closure of ClOrdID chains inside the broker and keys the SOW on the
resulting chain identity. §4 analyses it in depth — what it repairs, what it
merely relocates, and what it leaves untouched. The revised verdict (§7): chain
identity is solvable inside AMPS; latest-*valid* selection, pending-request
correlation, and sequence-dependent status are not, so the full docs/fix42
contract still needs a state machine outside — but the pure-AMPS option is
upgraded from "impossible" to "a legitimate monitoring-grade blotter", and the
machine gets thinner.

Throughout, "the contract" means
[docs/fix42/01-fix42-messages-and-state-machine.md](../fix42/01-fix42-messages-and-state-machine.md),
and "edge case N" means §7 of it.

## 1. What the AMPS toolbox is — and what the question needs

An AMPS view is a **declarative projection over the current records of SOW
topics**, maintained incrementally by the server and itself queryable and
subscribable like any SOW topic. The vocabulary (verify exact function set
against your AMPS version): field expressions, grouped aggregation
(`SUM`/`COUNT`/`AVG`/`MIN`/`MAX`-style over a `Grouping` key), and equality
joins across underlying topics. Around views sit the SOW itself (last-value or
delta-merged record per key), per-message enrichment, and pluggable
**key generators** — the module of §4 is one. Two properties define the
boundary for all of it:

- Everything is a function of **what the SOW currently holds** (plus, for a
  key generator, its own persistent id store), not of the message sequence
  that produced it. History is invisible; arrival order is invisible.
- No component keeps **conditional per-message memory** — no "remember this
  until that arrives, then revert".

The requested output is one live record per order carrying: order status,
CumQty, AvgPx, acked qty, acked price, pending qty change, pending price
change — under new/amend/cancel request → pending → ack/reject transitions and
fill bust/correct. Restated as the contract does: the OrderState row of §4 of
the contract, maintained by rules §5.

## 2. Field-by-field: where each requested value comes from

FIX 4.2 does most of the arithmetic before AMPS ever sees the message — an
ExecutionReport is a cumulative snapshot, not an increment
([fix-order-state.md](../src/fix-order-state.md) develops this). That is why
the table below has so many "on the latest valid 8" rows — and why the whole
problem compresses into *selecting and filing* reports rather than computing:

| requested field | FIX source | derivable inside AMPS? |
| --- | --- | --- |
| order status | tag 39 on latest valid 8 — **except** the locally-imposed `PENDING_NEW` / `PENDING_CANCEL` / `PENDING_REPLACE` windows | venue part yes (with §4's module + delta merge); the pending windows no — §3.4 |
| CumQty | tag 14 on latest valid 8 | yes under clean ordering; "latest **valid**" is not enforceable — §3.2 |
| AvgPx | tag 6 on latest valid 8, venue-computed (already bust/correct-adjusted) | same |
| LeavesQty | tag 151 on latest valid 8 | same |
| acked qty / acked price | tags 38/44 as of the last ack (150=0/5) — a G's new terms count only after its confirming 8 | **no** — a merge applies the G's terms immediately; the staging rule (contract §5 rule 3) has no home — §4.2 |
| pending qty change / pending price change | tags 38/44 of an **in-flight** G, cleared by 8(150=5) or 9(434=2) | no — request/response correlation (§3.3) |
| pending action / pending ClOrdID | existence of an unresolved F/G | no — same |
| fill bust / correct | 8 with 20=1/2 + 19; venue restates 14/151/6/39 absolutely | order-level absolutes merge in correctly; per-exec disposition (`FillStatus`) does not — §4.2 |

The one honest "almost": if messages never arrived out of order or twice and
nothing was ever pending, *the latest 8's venue fields are the state* — and
with §4's module the chain-fragmentation objection to storing exactly that
disappears. Every remaining "no" above is a departure from that happy path —
and the contract exists precisely because the departures are the job.

## 3. The constructions that almost work — without the chaining module

These three failures motivated the module analysis; the parts the module does
*not* repair are called out as they occur.

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
5. **Chain identity is only as good as tag 37 stability.** *(This failure —
   and only this one — is what §4's module addresses.)*

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
- The grouping key `/37` inherits failures 1 and 2 of §3.1. (Grouping on the
  *chained* key instead is not available to a view: the generated SowKey is
  record identity, not a message field — verify on your build whether your
  AMPS version exposes it to projections; the analysis below does not assume
  it.)

### 3.3 Joined views for the pending fields

"Pending replace" means: a G exists whose resolution has not arrived. Try to
say that relationally:

- "Unresolved" = no 8 with 150=5 for this G's ClOrdID **and** no 9 with
  `11 = <that ClOrdID>` — a NOT EXISTS anti-join, not in the view vocabulary.
- Even granting that: edge case 4 has an F and a G in flight **simultaneously**,
  and the 9 for the F must revert only the cancel, restoring a status
  snapshotted *when the F arrived* (`prior_status[ClOrdID]`, contract §5
  rule 5). That snapshot is per-request memory of a past state — no record
  currently in any SOW contains it. A view cannot produce a value that exists
  nowhere in its inputs.
- (The first pass of this analysis also listed the 41→11 transitive closure
  here — a one-hop equality join cannot follow chain C1→C2→C3. That objection
  is retired by §4; the two above stand on their own.)

### 3.4 The residue: status is a function of sequence

Even with identity, selection, and correlation granted by magic, `OrdStatus`
still is not a projection: `PENDING_NEW` exists because a D arrived and its 8
has not (rule 1); `PENDING_CANCEL`/`PENDING_REPLACE` are imposed by F/G arrival
and *lifted* by whichever of 8 or 9 comes first (rules 3–5); a fill landing
mid-pending updates quantities while the pending flags survive (edge case 10);
a late D must not clobber venue state already built from an early 8 (edge case
9). Each rule reads "when X arrives *after* Y, do Z" — conditional logic over
arrival history, the one thing a function of current records cannot see.

## 4. The chaining key generator: the blocker that falls

The optional module `libamps_id_chaining_key_generator.so` attaches to a SOW
topic as its key generator. The user guide's own FIX example is this exact
problem:

```xml
<Module>
    <Name>key-chaining</Name>
    <Library>libamps_id_chaining_key_generator.so</Library>
</Module>

<Topic>
    <Name>ExternalOrders</Name>
    <MessageType>fix</MessageType>
    <KeyGenerator>
        <Module>key-chaining</Module>
        <Options>
            <!-- /11 is the primary field -->
            <Key>/11</Key>
            <Key>/41</Key>
            <FileName>./sow/ExternalOrders.chain</FileName>
        </Options>
    </KeyGenerator>
    <FileName>./sow/%n.sow</FileName>
</Topic>
```

The first `Key` is the primary (the current message's identity; an unseen value
opens a new chain); subsequent `Key`s are parent references. The module traces
every reference back to the chain root and assigns the **same SowKey to every
message in the chain** — the transitive closure of 41→11, computed server-side
and **persisted across restarts** via `FileName`. This is `key_by_clordid`
from contract §3, relocated into the broker as vendor-shipped code. Failure 5
of §3.1 and the closure objection of §3.3 are gone: an amend chain C1→C2→C3
(edge case 2) collapses to one record with no client-side machinery.

### 4.1 What a chained topic actually stores

The module chooses the *key*; it does not change *merge semantics*. With plain
`publish`, one-record-per-chain means "the latest message, whatever it was" —
after a G arrives, the record **is the G**, and CumQty/AvgPx vanish until the
next 8. The construction only becomes useful with **`delta_publish`**, so the
record accumulates tags with newest-wins-per-tag (flat-tag delta is supported
on the `fix` type — [native-fix-and-nvfix.md](../src/native-fix-and-nvfix.md)).
Call this the **chained blotter**: every D/G/F/8/9 delta-published into one
chained topic.

What the merged record then gets right, under clean in-order delivery:

- `/39`, `/14`, `/151`, `/6` — from the latest 8 (or 9: tag 39 is on
  cancel-rejects too), i.e. the venue's absolute snapshots, **including after
  busts and corrects**, since 20=1/2 reports restate absolutes and
  newest-wins adopts them. This is contract rule 2's "adopt verbatim",
  performed by the merge itself.
- Chain identity, `/37`, latest `/11`/`/41`, `/58`, `/102`, `/103` — all file
  correctly onto one record.

### 4.2 What the chained blotter still gets wrong

Each of these maps to a contract rule that remains homeless:

1. **Acked terms are clobbered by proposals.** A G's 38/44 overwrite the
   record's applied terms the moment the G arrives — rule 3 requires staging
   them until the confirming 8 (`150=5`) and discarding them on a 9. After an
   amend-reject, the record permanently shows terms the venue never accepted.
   This alone disqualifies it for "acked qty / acked price" and "pending qty
   change / pending price change" — the exact fields the question names.
2. **No pending representation.** D/G/F carry no tag 39, so `/39` shows the
   last *venue* status; `PENDING_NEW/CANCEL/REPLACE` never appear. Inferring
   pending from `/35` ("last message was a G") breaks the moment a fill lands
   mid-pending (edge case 10) and cannot represent two in-flight requests
   (edge case 4).
3. **No arbitration.** The merge is unconditional: stale out-of-order 8s
   regress the record (edge case 12), and there is no ExecID-level dedupe
   beyond byte-identical overwrites (edge case 3's *economic* no-op is not
   expressible).
4. **Order-level only.** One record per chain cannot carry the per-execution
   `FillStatus` (busted/corrected/DK'd) re-emit, nor the `executions` and
   `order_events` histories of contract §6.
5. **It must not be the system of record.** Verbatim from the user guide: *"It
   is an error for a publisher to publish a message that resolves to two
   different message chains. If the module receives such a message, the module
   will not generate a SOW key, and the message is not processed by AMPS."* An
   audit stream must never drop a message, so the chained topic is a derived
   convenience **alongside** the journalled plain ingress topics, never instead
   of them. (The optional `Validation` flag adds detection of chains that
   would have merged had messages arrived in a different order — worth
   enabling; it converts silent mis-chaining into a visible error.)

### 4.3 Verified on AMPS 5.3.5.135

The items below were open questions when this analysis was written. They have
since been settled by building the thing — `fix42-publisher/` and the
`fix42-chaining` server flow, with an integration test that starts a real
container and reads the SOW back:

- **The module ships and loads.** `libamps_id_chaining_key_generator.so` is
  present in the 5.3.5.135 image; the `<Modules>` / `<KeyGenerator>` /
  `<Options>` config below validates with `amps.sh validate`.
- **Chain collapse works as documented.** Eleven parent ClOrdIDs across seven
  chains produce seven records on the chained topic and eleven on its audit
  twin.
  An amend chain C1 -> C2 -> C3 resolves to one record.
- **Delta merge preserves untouched fields.** After a `35=G` carrying seven
  tags, the record still holds the symbol, side, account, ord type, currency
  and destination from the original `35=D` — nothing re-sent them.
- **§4.2 item 1 is real, and is the binding limit.** A rejected amend leaves
  its *proposed* terms on the chained record: the merge applied them when the
  request was published and the `35=9` does not retract them. The integration
  test asserts this rather than hiding it.
- **`%n` is unusable in `<FileName>`** when topic names contain slashes
  (`sow/parent/orders` would ask for `./sow/sow/parent/orders.sow`). Name the
  SOW file explicitly.
- **XML comments may not contain a double hyphen**, which rejects the whole
  config — so a pasted shell flag (`--rm`) in an explanatory comment is enough
  to stop the server booting.

### 4.4 Composition constraints and remaining open items

- **One topic, not five.** A key generator is configured per SOW topic and
  chains only among that topic's messages. The `algo/D`…`algo/9` split defeats
  it: D and G in different topics can never share a chain. The chained blotter
  needs all types in one topic — either adopt the single-`algo/fix` ingress
  layout ([01-ingress-fix42-into-amps.md](01-ingress-fix42-into-amps.md) §3)
  or add a dedicated chained topic the gateway also publishes everything into.
- **Key roster beyond 11/41.** Not exercised by the implementation, which
  configures only `/11` + `/41`. The contract binds 37, 11, 41, 17 (§3),
  preferring 37 because ClOrdID chains break if an intermediate replace is
  missed. Configuring `/37` and `/17` as additional secondary keys should
  reproduce that healing — the guide documents multiple secondaries, but this
  exact roster is untested here. Related unknown: behaviour when the
  **primary is absent** (35=Q carries no tag 11) is not documented — test
  before routing Q through a chained topic.
- **Mid-stream starts** (edge cases 1, 9) work in principle — the first
  message seen opens the chain, later references join it — but this is exactly
  where the two-chains error and the `Validation` findings will surface if the
  feed has gaps. Decide the operational response (alert + replay) before
  relying on it.
- **Shipping and version.** It is an *optional module*: confirm the `.so` is
  in your AMPS 5.3 tarball image, that the `<Module>` block loads at startup,
  and `./server/scripts/amps.sh validate` accepts the `KeyGenerator` element.
  Nothing in this repo has run it.

## 5. The rest of the server-side toolbox, considered and declined

For completeness — the other places AMPS can run logic, and why none closes the
remaining gap:

- **Enrichment / preprocessing** on a topic: per-message field computation at
  publish time. Stateless with respect to other messages — can stamp, rename,
  compute within one message; cannot correlate two. (Tempting hybrid: enrich
  G-messages to write their 38/44 under different names, so §4.2 item 1's
  clobbering becomes two field families. That fixes the overwrite but not the
  correlation — clearing the pending fields on ack/reject and reverting status
  are still cross-message.)
- **Conflated topics**: rate control (latest-per-key at an interval). Changes
  delivery cadence, not semantics.
- **A custom server module (C++ SDK)**: could, in principle, host the entire
  state machine inside the broker process — and §4's module is proof that the
  extension point is real and production-grade. The difference: the chaining
  generator is *vendor-shipped and vendor-maintained*, while a full
  order-state module would be the most test-hungry code in the system welded
  into the message path, with its release cycle coupled to server upgrades and
  its replay-through-new-rules deployment story (see
  [03-proposed-architecture.md](03-proposed-architecture.md)) made awkward.
  Buy the identity module; do not build the state-machine module.

## 6. What views are the right tool for — above the machine, not below it

Every remaining blocker is about *deriving* order state from raw FIX. Once the
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

## 7. Verdict — revised for the chaining key generator

Of the four load-bearing behaviours, **chain identity now has a server-side
answer**: the chaining key generator computes the 41→11 transitive closure
inside AMPS, persistently, per topic — provided all message types share one
chained topic and its error/edge behaviours check out on your build (§4.3).
The other three do not move: latest-*valid* selection (conditional apply /
argmax), pending-request correlation (anti-join plus per-request snapshots),
and sequence-dependent status remain the definition of a state machine, and
contract §5 remains its specification.

Practical reading:

- **If "monitoring-grade" is enough** — latest venue truth per chain, no
  pending windows, no staged-terms correctness, trusting feed order — the
  chained blotter (§4.1) is a legitimate zero-code design that the first pass
  of this analysis wrongly ruled out entirely.
- **For the full contract** — every field the question names, including acked
  vs pending terms and reject-revert behaviour — build the machine outside
  AMPS. The module still pays its way there: it can carry the identity slice
  and a raw-FIX debugging blotter, shrinking what the machine must own — see
  [03-proposed-architecture.md](03-proposed-architecture.md) §5.
