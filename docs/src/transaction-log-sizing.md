# Keeping the transaction log small

The problem this document is about:

> A SOW topic holds large records. Each record changes constantly, but each change
> touches a few fields. Every update is journalled in full, so the transaction log
> grows at *record size × update rate* — mostly re-writing bytes that never
> changed. How do I stop that, and how little do I need to keep for a restart to
> be safe?

Run [`journal-lab`](../../clients/src/main/java/com/demo/amps/clients/demos/JournalLabDemo.java)
to measure it on your own machine:

```bash
./gradlew :clients:run --args="journal-lab --symbols 10 --updates 500"
```

It publishes the same 5,000 updates twice — once as whole records, once as deltas
— and reports bytes published and journal growth for each.

---

## First, know what each store costs you

Two independent budgets, often confused:

| | SOW | transaction log |
| --- | --- | --- |
| holds | one record per key | every message published |
| grows with | number of distinct keys | number of messages × message size |
| bounded by | `<Expiration>`, `sow_delete` | retention policy, delta size |
| needed for | current state after restart | replay, resume-from-bookmark, dedup |
| cost of losing it | rebuild from the journal or upstream | subscribers cannot resume; must re-bootstrap from SOW |

A SOW of 100,000 instruments is 100,000 records whatever the update rate. A
transaction log for those instruments at 10 updates/second is ~86M messages a day.
The journal is where the money goes.

---

## Lever 1 — do not journal what you never replay

Nothing else comes close. A topic absent from `<TransactionLog>` costs zero
journal bytes.

Ask of each topic: *if the instance restarted and this topic's history were gone,
what would break?* If the answer is "nothing, the SOW has current state and
upstream can refresh it", leave it out.

In [`amps-config.xml`](../../server/config/amps-config.xml), `quote-cache` is a
SOW topic that is deliberately **not** journalled:

```xml
<SOW>
  <Topic>
    <Name>quote-cache</Name>
    <MessageType>json</MessageType>
    <Key>/symbol</Key>
    <Durability>transient</Durability>
    <Expiration>60s</Expiration>
  </Topic>
</SOW>
<!-- and no matching entry under <TransactionLog> -->
```

Volatile cache, reconstructible in seconds, 60-second TTL. Journalling it would
be the single largest line item in the log and would buy nothing.

Note that `<TransactionLog>` topic entries accept patterns. A catch-all
`<Name>.*</Name>` is convenient and is how instances quietly end up journalling
everything — list topics explicitly and the cost stays visible.

## Lever 2 — publish deltas

The journal records **what was published**. Publish 1.3 KB, journal 1.3 KB.
Publish a 134-byte delta, journal 134 bytes, and the SOW still ends up holding the
same complete record because AMPS merges server-side.

```java
DeltaBuilder.Delta delta = DeltaBuilder.between(previous, next, List.of("symbol"));
client.deltaPublish("instruments", delta.json());
```

For the instrument shape in this repo that is roughly a **10x smaller journal for
the same updates**, and the same reduction in network bytes on the way in.
[`delta-updates.md`](delta-updates.md) covers the semantics and the two traps
(arrays are replaced, not merged; a cleared field needs a full publish).

This is the answer to "large repeated data" specifically: the repeated part is
exactly the part a delta omits.

## Lever 3 — age out old journal files

The SOW already holds current state, so the journal does not need to reach back to
the beginning of time. It needs to cover **the longest outage a subscriber can
have and still resume from its bookmark**. Past that window, a subscriber has to
re-bootstrap from the SOW anyway, so older journal files are pure cost.

Size the window off consumer behaviour, not disk capacity:

| consumer profile | reasonable window |
| --- | --- |
| always-on, restarts in seconds | hours |
| batch job that runs nightly | 36–48 hours |
| DR replica that can be offline for a weekend | 3–4 days |

AMPS ages journal files with a scheduled action.
[`amps-config-bounded-retention.xml`](../../server/config/amps-config-bounded-retention.xml)
carries a worked example:

```xml
<Actions>
  <Action>
    <On>
      <Module>amps-action-on-schedule</Module>
      <Options><Every>1h</Every></Options>
    </On>
    <Do>
      <Module>amps-action-do-delete-old-journal-files</Module>
      <Options><Age>1d</Age></Options>
    </Do>
  </Action>
</Actions>
```

> **Verify this block against your AMPS version before relying on it.** Action
> module names and their options have moved between releases, and this repository
> was authored without a running server to check them against. The surrounding
> structure (`<Actions><Action><On>`/`<Do>`) is stable. Check yours with:
>
> ```bash
> ./server/scripts/amps.sh validate amps-config-bounded-retention.xml
> ```
>
> and look up "Actions" in the AMPS User Guide for your build if a module name is
> rejected. Delete the block and the instance still starts — you just lose
> automatic ageing, and can prune with an external cron job in the meantime.

## Lever 4 — bound the SOW with expiration

This one protects the *other* budget, but unbounded key growth eventually shows up
everywhere, including recovery time.

```xml
<Topic>
  <Name>orders</Name>
  <Key>/orderId</Key>
  <Expiration>24h</Expiration>
</Topic>
```

Per-message TTL overrides the topic default:

```java
client.executeAsync(new Command("publish")
        .setTopic("quote-cache")
        .setData(json)
        .setExpiration(30), message -> { });
```

Expiring records generate OOF messages with reason `expired`, so subscribers drop
them from their views rather than displaying rows that no longer exist. The
[`expiration`](../../clients/src/main/java/com/demo/amps/clients/demos/ExpirationDemo.java)
demo shows this happening live.

---

## Sizing knobs, and what they do

| setting | effect | demo default | production shape |
| --- | --- | --- | --- |
| `<MinJournalSize>` | size of each preallocated journal file | `4MB` — small so files roll during a short demo | 100MB–1GB; fewer rollovers |
| `<PreallocatedJournalFiles>` | files created up front | `2` | enough to absorb a burst without allocating on the hot path |
| `<Expiration>` (SOW topic) | default record TTL | `60s` on `quote-cache` | match the business lifetime of a key |
| `<Durability>` (SOW topic) | `persistent` or `transient` | `transient` for the cache | `transient` for anything reconstructible |
| journal ageing action | deletes files past a horizon | `1d` | worst-case consumer outage, plus margin |

`MinJournalSize` is why on-disk growth in `journal-lab` moves in steps: AMPS
preallocates whole files, so a delta workload that fits inside an already-allocated
file can show **zero** growth. That is the result, not a measurement failure.

---

## What is the minimum to keep for a safe restart?

Work down from what must survive:

1. **Current state must survive.** → SOW topics that matter are
   `<Durability>persistent</Durability>`. This alone gets you a restart where
   every client can query current state immediately.

2. **In-flight publishes must not be lost.** → Publishers use a persistent publish
   store (`AmpsConnections.connectWithPublishStore`) and AMPS deduplicates on
   replay using the publisher's sequence numbers. The journal is what makes that
   dedup possible.

3. **Subscribers must resume without gaps.** → The topic is journalled, and the
   journal retains at least the longest outage you are willing to tolerate.
   Subscribers keep a durable bookmark store
   (`AmpsConnections.connectWithBookmarkStore`).

4. **Everything else is optional.** Older journal, non-critical topics, caches:
   delete freely.

A defensible floor for this demo's workload:

- `orders` and `instruments`: persistent SOW, journalled, journal aged at 24h.
- `quote-cache`: transient SOW, 60s TTL, not journalled.
- `events.*`: no SOW, no journal — pure fan-out.
- Publishers: persistent publish store. Subscribers that must not miss anything:
  durable bookmark store.

Everything above is what [`amps-config.xml`](../../server/config/amps-config.xml)
and its bounded-retention sibling implement, and what
[`sow-and-recovery.md`](sow-and-recovery.md) walks through end to end.

---

## Can I just set a maximum transaction log size?

Not as a single number, and the distinction matters.

There is no `MaxTransactionLogSize` that AMPS enforces by refusing writes or
overwriting old entries. What exists is a set of controls whose *product* is the
cap you get:

| setting | what it actually controls |
| --- | --- |
| `<MinJournalSize>` | the size of **each** journal file — not a total |
| `<PreallocatedJournalFiles>` | how many files exist before any message arrives |
| journal ageing action | which old files get **deleted**, on a schedule |

So the effective ceiling is:

```
max journal bytes  ~=  MinJournalSize  x  (files retained by the ageing policy + preallocated)
```

You bound the journal by **deleting old files**, not by capping a counter. Three
consequences worth internalising:

1. **It is a lagging control.** Files are removed on a schedule, so usage
   oscillates: it climbs until the next run, then drops. Size the check interval
   so the overshoot between runs is acceptable — at 356 MB/min, an hourly check
   can add 21 GB before it fires.

2. **Granularity is one file.** With 1 GB files and a 10 GB target, real usage
   swings between roughly 9 and 10 GB. Smaller files track the target more
   tightly and roll over more often.

3. **Nothing stops the disk filling.** If the ingest rate outruns the ageing
   policy, AMPS keeps writing until the filesystem says no, and an instance that
   cannot write its journal is in trouble. Leave real headroom and alert on free
   disk, not only on journal bytes.

If your version's ageing action accepts a **size or total-bytes** option as well
as an age, prefer it: it targets the thing you actually care about and does not
need re-tuning when volumes change. An age-based policy is a proxy that assumes a
stable message rate, which market data is not. Check with:

```bash
./server/scripts/amps.sh validate amps-config-bounded-retention.xml
```

### What will not bound it

- **`sow_delete`** removes SOW records and is journalled as a write. It makes the
  transaction log bigger. See
  [sow-and-recovery.md](sow-and-recovery.md#truncating-a-topic).
- **`<Expiration>`** bounds the SOW, not the journal.
- **Deleting journal files with `rm`** is not a supported control. AMPS tracks
  which files back which bookmark ranges; removing them underneath it risks failed
  replays and a SOW it cannot reconcile after an unclean shutdown.

The one control that reliably bounds the journal to zero is not putting the topic
in `<TransactionLog>` at all.

## A worked case

[high-volume-market-data.md](high-volume-market-data.md) applies all of this to a
specific set of numbers — 500 GB/day of market data, 100 GB of disk, a 500 MB SOW
snapshot — including the arithmetic for how much replay window a given journal cap
actually buys, and why "compact the journal" is the wrong question.

## Diagnosing a journal that is too big

1. `du -sh server/data/journal` — how big, in how many files.
2. Which topics are in `<TransactionLog>`? Remove any whose history you never
   replay. This is usually the whole answer.
3. Are publishers sending whole records where a delta would do? Compare average
   published size against average changed size — `journal-lab` is that experiment.
4. Is anything ageing files out? An instance with no retention action keeps
   everything forever.
5. Only then reach for bigger disks.
