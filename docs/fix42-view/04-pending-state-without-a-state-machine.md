# Pending state on a chained blotter, without a state machine

How far a delta-merged AMPS record can go toward "acked terms **and** in-flight
terms, at the same time" — and where it still stops.

This document is the write-up of a specific proposal and the experiment that
tested it. The starting point was the limitation recorded in
[02, §4.2 item 1](02-amps-view-feasibility.md): publishing a `35=G`'s proposed
quantity into tag 38 overwrites the quantity the venue actually acked, and if
the amend is then rejected the record is permanently wrong. The question was
whether a compensating write could repair it.

The answer turned out to be: **it can, badly — and it does not need to,
because the conflict can be avoided rather than repaired.** The avoidance is
implemented in [`fix42-publisher/`](../../fix42-publisher/README.md).

---

## 1. The proposal that prompted this

> On an amend reject, query `orders_audit` by tag 11 using the reject's tag 41
> (OrigClOrdID) to find the previous order state, then delta-publish that back
> to `orders` to revert.

Run against a live instance (AMPS 5.3.5.135), on a chain that went
`4000 @ 121.80` → amend to `6000 @ 122.10` → rejected:

```
2. AFTER THE REJECT, BEFORE ANY REVERT
   35=G|11=N-2|41=N-1|60=...13:33:01.000|38=6000|44=122.10|...
3. LOOKUP orders_audit WHERE /11 = 'N-1'
   35=D|11=N-1|...|38=4000|44=121.80|...
4. AFTER DELTA-PUBLISHING THAT RECORD BACK
   35=D|11=N-1|...|60=...13:33:00.250|...|38=4000|...|44=121.80|...|41=N-1|
```

**It works economically.** Quantity, price and tag 11 all revert, and — the
part that was genuinely uncertain — republishing `11=N-1` resolves to the
**same chain**. No split.

**It corrupts everything else.** Three artifacts, all visible in step 4:

| artifact | consequence |
| --- | --- |
| `35=D` | the record claims to be a NewOrderSingle; the amend and its rejection have vanished from it |
| `60` moves **backwards** | the record is stamped earlier than the reject that caused the write, so any staleness or ordering logic reading tag 60 is misled |
| `41=N-1` is stuck, now equal to tag 11 | **a delta merge can overwrite but never remove**, so a self-referencing link cannot be cleaned up |

And four problems the artifacts do not show:

1. **The in-flight window is untouched.** Between sending the G and receiving
   the reject, the record reads `38=6000` — indistinguishable from an accepted
   amend. The revert repairs history, not the present. If the venue never
   sends the reject, the record stays wrong forever.
2. **Acked and pending are still never simultaneous.** One tag 38 holds one
   number. This was the original requirement, and a revert does not deliver it.
3. **Read-modify-write with no compare-and-swap.** Query → build → publish is a
   round-trip in the hot path, and AMPS offers no CAS on a SOW record. A fill
   arriving between the query and the publish is clobbered by the stale
   snapshot being written back.
4. **The audit record is the previous *message*, not the previous *state*.**
   It worked above only because the previous ClOrdID's record was the `35=D`,
   published in full. In `D → G1(acked) → G2(rejected)`, reverting G2 requires
   restoring what G1 established — and G1's audit record is a delta subset
   containing only the tags its route selected.

Underneath all four: deciding that `434=2` means "revert", knowing which
ClOrdID to look up, and knowing which fields constitute state **is**
pending-request bookkeeping. Having built it, the in-memory version is
strictly cheaper and more correct — the pre-amend values were in hand when the
G was published, so no query and no race.

---

## 2. The avoidance: give the proposal its own fields

The conflict exists only because one tag is being asked to hold two facts.
Stop doing that.

| tag | holds |
| --- | --- |
| 38 / 44 | **acked** terms — what the venue confirmed. Written only by the venue's own reports |
| 9010 / 9011 | **proposed** terms — what an in-flight `35=G` asked for |
| 9012 | ClOrdID of the in-flight request |
| 9013 | `NONE` / `NEW` / `REPLACE` / `CANCEL` |
| 9014 | working ClOrdID — the id the venue currently recognises |

Rules, all expressible as per-message field selection:

| message | effect on the blotter |
| --- | --- |
| `35=D` | full publish; seed `9013=NEW`, `9014=`ClOrdID |
| `35=G` | proposed terms → 9010/9011, request id → 9012, `9013=REPLACE`. **38/44 untouched** |
| `35=F` | request id → 9012, `9013=CANCEL`. Nothing else |
| `8` `150=0` ack | `9013=NONE`, `9014=`ClOrdID |
| `8` `150=5` replace confirm | adopt the venue's **own** 38/44, clear the pending family, `9014=`ClOrdID |
| `8` `150=4`/`3` | clear the pending family |
| `9` reject | clear the pending family. **Nothing to revert** |
| `8` `150=6`/`E` | *nothing* — these acknowledge a request, they do not resolve it |
| fills | *nothing* — quantities live on the execs topic |

Two properties make this work without any memory:

**The venue tells you what it accepted.** A `150=5` carries its own 38/44, so
the publisher never has to remember what it asked for. This is the same
principle the whole design runs on: a FIX 4.2 report is a cumulative snapshot.

**"Leave unchanged" is free in a delta merge.** A rejected amend must not move
the working ClOrdID — so the reject simply does not publish tag 9014, and the
merge leaves the stored value alone. The absence of a field is the instruction.

### Measured result

In flight, on a live instance — both truths in one record:

```
acked qty=9000  px=55.25   |  pending REPLACE qty=15000 px=55.80  (id INFLIGHT-2)
```

After the reject, and after a confirm:

```
rejected:   acked qty=4000  px=121.80  |  pending NONE     <- 6000 never touched 38
confirmed:  acked qty=5000  px=121.95  |  pending NONE     <- venue's own numbers
```

---

## 3. The constraint that cost a rewrite

The first implementation rewrote tag 11 on the blotter to mean "the working
ClOrdID", which seemed tidier than adding tag 9014. **It broke the chaining**,
and the failure is worth recording because nothing in the module's
documentation warns about it.

Tags 11 and 41 are the chaining key generator's *own inputs*. It binds a new
ClOrdID into an existing chain by seeing `11=<new>` next to `41=<known>`.
Suppressing the new value of tag 11 on the amend — and omitting 41 from the
subsequent confirm — meant the module never saw the linkage, so the confirm's
ClOrdID looked like the first message of a **new** chain. Five orders became
seven records; two child slices became four.

> **Rule: a projection may add fields and rewrite fields into *other* tags, but
> tags 11 and 41 must pass through exactly as FIX sent them.** Anything else
> the blotter wants to say about identity needs a tag of its own.

This is the practical edge of delegating identity to the server: the server
owns those two fields, and the price of not maintaining a chain map is not
being allowed to edit them.

---

## 4. What this does and does not close

Restating the four blockers from [02, §7](02-amps-view-feasibility.md):

| blocker | status |
| --- | --- |
| chain identity across cancel/replace | **closed** by the chaining key generator |
| pending-request correlation | **closed for the common cases** by the pending-tag family: acked vs proposed simultaneously, cleared on ack/reject, no revert, no query, no race |
| latest-*valid* report selection | **open.** A stale or duplicate report still merges unconditionally. Needs conditional apply, which no merge expresses |
| sequence-dependent status | **partly closed.** `PENDING_NEW/REPLACE/CANCEL` are now represented. What is not: reverting to a *snapshot* of prior status, and multiple simultaneous in-flight requests — 9012/9013 hold one request, so a G and an F in flight together (contract §7 edge case 4) still collapse to whichever arrived last |

Three further limits worth naming plainly:

- **Clearing is a write, not a removal.** `9013=NONE` and `9010=0` are
  sentinels, because a delta publish cannot delete a field. Consumers must
  read the sentinel, not test for absence.
- **Venue-originated messages must carry tag 9000 for child orders.** A report
  that resolves a pending request has to reach the same blotter the request
  went to, and a stateless router picks that topic from tag 9000. Venues
  commonly echo a client-supplied custom tag on execution reports — it is a
  standard onboarding request — but where one will not, the OMS must stamp it
  inbound. That is the one piece of chain state this design cannot delegate.
- **It is still not the contract.** Busts and corrects, ExecID dedupe, the
  stale guard and per-request status snapshots
  ([the contract](../fix42/01-fix42-messages-and-state-machine.md) §5) remain
  outside. What has changed is the *size* of what is left: the remaining work
  is arbitration, not bookkeeping.

## 5. Where it is implemented

| piece | where |
| --- | --- |
| the tag family and why each exists | [`FixTags`](../../fix42-publisher/src/main/java/com/demo/amps/fix42/fix/FixTags.java) |
| the rules, as configuration | [`application.yml`](../../fix42-publisher/src/main/resources/application.yml) — the `projection` block on each route |
| how a projected payload is built | [`PublishPlanner`](../../fix42-publisher/src/main/java/com/demo/amps/fix42/publish/PublishPlanner.java) |
| in-flight behaviour against a real server | [`PendingStateIT`](../../fix42-publisher/src/integrationTest/java/com/demo/amps/fix42/it/PendingStateIT.java) |
| end-state behaviour | [`Fix42DeltaPublishIT`](../../fix42-publisher/src/integrationTest/java/com/demo/amps/fix42/it/Fix42DeltaPublishIT.java) |

The audit topics keep receiving the **unprojected** payload throughout: an
audit trail should record the message that was sent, not a rewrite of it.
