# High-volume market data on a small disk

The scenario this document solves:

> A market data SOW topic with a transaction log attached. Roughly **500 GB of
> messages a day**. **100 GB of disk**. The SOW snapshot itself is only **500 MB**.
> Keep the latest state, get the superseded same-key data off the disk, and hold
> total usage near **10 GB**.

## First, the correction that changes the whole approach

**AMPS has no log compaction for the transaction log.** There is no setting that
walks the journal and drops superseded messages for a key. The journal is an
ordered, append-only record of what was published; removing individual messages
from the middle of it would break the replay and bookmark semantics that are the
only reason it exists.

If you are coming from Kafka, the instinct is "turn on `cleanup.policy=compact`".
The AMPS equivalent is not a journal setting — **the SOW _is_ the compacted view**.
It is maintained continuously, holds exactly one record per key, and in your case
it is 500 MB. You already have the compacted topic. What you do not need is 500 GB
of history sitting next to it.

So the question is not "how do I compact the journal" but **"how much journal do I
actually need, and for whom?"**

## The arithmetic

At 500 GB/day, a journal cap buys this much replay history:

| journal cap | at average rate | at 3x peak (open/close) |
| --- | --- | --- |
| 1 GB | 2.9 min | ~1 min |
| **10 GB** | **28.8 min** | **~10 min** |
| 30 GB | 1.4 h | ~29 min |
| 50 GB | 2.4 h | ~48 min |
| 90 GB | 4.3 h | ~1.4 h |

Two things fall out of this table:

1. **Your 10 GB target buys about 29 minutes at average rate, and closer to 10
   minutes during the open.** That is a real constraint, not a formality: any
   subscriber offline longer than that cannot resume from its bookmark and must
   re-bootstrap from the SOW. Decide now whether that is acceptable — for market
   data it usually is, because a 30-minute-old quote has no value.

2. **A day of journal is impossible on this hardware and always will be.**
   500 GB does not fit in 100 GB. No configuration changes that. The only
   questions are how short the window is and whether you need one at all.

Also note the ratio: 500 GB of daily traffic against a 500 MB snapshot means each
key is rewritten on the order of a thousand times a day. Every one of those
rewrites is journal bytes whose only surviving value is the last one.

## Option A — do not journal the market data topic (recommended)

The cheapest transaction log is the one you do not write. Remove the topic from
`<TransactionLog>` and the journal cost goes to zero. The SOW stays persistent, so:

- current state still survives a restart, immediately, with no replay;
- clients still get snapshot-plus-live in one command via `sow_and_subscribe`;
- disk usage becomes ~500 MB plus whatever else is on the box.

What you give up is bookmark replay **for that topic**. A reconnecting subscriber
calls `sow_and_subscribe` instead of resuming from a bookmark, and gets current
state plus everything from that moment on. For market data that is not a
downgrade — it is what you want. Nobody needs the ticks they missed; they need
the current book.

```xml
<SOW>
  <Topic>
    <Name>market-data</Name>
    <MessageType>json</MessageType>
    <Key>/symbol</Key>
    <FileName>./sow/%n.sow</FileName>
    <Durability>persistent</Durability>
  </Topic>
</SOW>

<TransactionLog>
  <JournalDirectory>./journal</JournalDirectory>
  <MinJournalSize>1GB</MinJournalSize>
  <!-- market-data is deliberately NOT listed here. -->
  <Topic>
    <Name>trades</Name>
    <MessageType>json</MessageType>
  </Topic>
</TransactionLog>
```

**Split the topic by durability need.** The reason this works in practice is that
"market data" is usually two populations with different requirements:

| data | volume | needs replay? | journal it? |
| --- | --- | --- | --- |
| quotes / book updates | ~all of the 500 GB | no — stale quotes are worthless | no |
| trades / executions | small | yes — audit, recovery, reconciliation | yes |

Journalling only the second population typically removes 95%+ of the volume while
keeping every guarantee anyone actually asked for. If you take one thing from this
document, take this one.

**One caveat on the un-journalled topic.** A persistent SOW survives a restart on
its own — that is not in question. But the transaction log is also what AMPS
reconciles the SOW against after an *unclean* shutdown, so without one the
guarantee after a power loss is "whatever had been flushed". For quotes that is
irrelevant: the feed republishes and you are current within seconds. It is
precisely the argument for keeping `trades` journalled. See
[sow-and-recovery.md](sow-and-recovery.md#the-caveat-crash-consistency).

## Option B — keep a bounded replay window

If some consumer genuinely needs to resume from a bookmark, keep the topic
journalled and age files out on a schedule.

```xml
<TransactionLog>
  <JournalDirectory>./journal</JournalDirectory>
  <!-- 1 GB files: a 10 GB cap is ~10 files, so deletion granularity is 10%.
       Smaller files track the cap more tightly and roll over more often. -->
  <MinJournalSize>1GB</MinJournalSize>
  <PreallocatedJournalFiles>2</PreallocatedJournalFiles>

  <Topic>
    <Name>market-data</Name>
    <MessageType>json</MessageType>
  </Topic>
</TransactionLog>

<Actions>
  <Action>
    <On>
      <Module>amps-action-on-schedule</Module>
      <!-- At 356 MB/min, five minutes is ~1.8 GB. Check often enough that the
           cap cannot be overshot by more than one file between runs. -->
      <Options><Every>5m</Every></Options>
    </On>
    <Do>
      <Module>amps-action-do-delete-old-journal-files</Module>
      <Options>
        <!-- Sized for PEAK rate, not average: 10 GB is ~10 minutes at 3x. -->
        <Age>10m</Age>
      </Options>
    </Do>
  </Action>
</Actions>
```

> **Verify the action module and its options against your AMPS version.** Module
> names and option names have moved between releases, and this repository was
> written without a running server to check them against. The
> `<Actions><Action><On>`/`<Do>` structure is stable. Run
> `./server/scripts/amps.sh validate amps-config-market-data.xml`, and if a name
> is rejected, look up "Actions" in the AMPS User Guide for your build. In
> particular, **check whether your version's journal-ageing action takes a size
> or total-bytes option as well as an age** — a size threshold is a better fit for
> "keep it under 10 GB" than a time window, because it tracks the thing you
> actually care about and does not need re-tuning when volumes change.

### Sizing the window: use peak, not average

Market data is bursty. If your open is three times the daily average, a window
sized on the average is a third as long as you think it is exactly when you can
least afford the surprise. Either:

- size `<Age>` on peak rate (10 minutes here, not 29), or
- use a size-based threshold if your version supports one, which is self-correcting.

### Never delete journal files by hand

Do not point a cron job at `rm` on the journal directory. AMPS tracks which files
back which bookmark ranges; removing files underneath it risks failed replays and,
after an unclean shutdown, a SOW it cannot reconcile. Use the server's own ageing
mechanism. If your version has none, contact 60East before improvising — this is
one of the few places where the "just script it" answer is genuinely unsafe.

## Option C — put fewer bytes in the journal

Orthogonal to retention, and it multiplies whatever window you choose.

**Delta publishing.** If your market data records are wide but each tick changes a
few fields — a quote inside a record carrying instrument reference data, say —
publishing deltas cuts journal bytes by the same ratio it cuts message size. The
[`journal-lab`](../../clients/src/main/java/com/demo/amps/clients/demos/JournalLabDemo.java)
demo measures roughly 10x for that record shape. At a 10x reduction:

| journal cap | window before | window after |
| --- | --- | --- |
| 10 GB | 29 min | **4.8 h** |
| 30 GB | 1.4 h | **14.4 h** |

Same disk, same cap, a window that is now useful. See
[delta-updates.md](delta-updates.md).

If your records are *already* minimal — a bare quote with no static payload —
deltas will not help much, and Option A is the answer.

**Journal a conflated stream instead of the raw one.** If consumers replaying
history do not need every tick, publish raw market data to a non-journalled topic
and journal a conflated view of it at, say, one update per key per second. At
1,000 updates per key per day against a 500 MB snapshot, second-level conflation
is a very large reduction. AMPS supports conflated topics in configuration —
confirm the exact element names for your version before building on it.

## Option D — spend more of the disk than 10 GB

Worth stating plainly: 10 GB is a conservative target on a 100 GB disk when the
SOW is 500 MB. If the replay window matters, 30–50 GB is still leaving half the
disk free and buys 1.4–2.4 hours at average rate.

Reserve headroom deliberately: SOW files, the stats database, server logs, the OS,
and enough slack that a burst cannot fill the volume. A journal cap of 50 GB on a
100 GB disk is comfortable; 90 GB is not.

## What to do, in order

1. **Split the topic by durability need.** Journal trades, not quotes. This is
   usually the whole answer.
2. **Confirm nobody needs bookmark replay on the quote stream.** If nobody does,
   remove it from `<TransactionLog>` and you are finished — 500 MB total.
3. **If someone does, bound the journal** with a scheduled ageing action, sized on
   peak rate, and tell that consumer in writing what its resume window is.
4. **Reduce the input volume** with delta publishing or a conflated journalled
   topic, which stretches the window at no extra disk cost.
5. **Monitor it.** The admin interface at `http://host:8085/` reports journal
   statistics; alert on journal bytes and on free disk, not just on one of them.

## What each choice costs you

| | disk | replay window | reconnect path for a subscriber |
| --- | --- | --- | --- |
| No journal on market data | ~500 MB | none | `sow_and_subscribe` — instant, current |
| 10 GB bounded journal | ~10.5 GB | 10–29 min | bookmark if recent, else `sow_and_subscribe` |
| 50 GB bounded journal | ~50.5 GB | 48 min–2.4 h | as above |
| Deltas + 10 GB | ~10.5 GB | ~1.6–4.8 h | as above |

In every row the SOW is 500 MB and current state survives a restart. That part was
never the problem — and it is why the journal can be cut back this far without
losing anything a market data consumer would notice.

A worked configuration for this scenario is in
[`server/config/amps-config-market-data.xml`](../../server/config/amps-config-market-data.xml).
