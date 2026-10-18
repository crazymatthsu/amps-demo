# Replication and the transaction log

Two questions that came up while adding the replicated bookmark topic to this
pair, answered from 60East's documentation and from this module's own configs:

1. **A SOW topic already holds current state. Does it need the transaction log
   to be replicated?**
2. **Then the extra disk is only for replication. Is the transaction log
   compacted once a message has been replicated?**

The short answers are *yes* and *no*. The rest of this document is why, what
follows from it for a replicated pair, and how to size and bound the journal
that results.

A note on what is verified. The statements about the AMPS **server** below are
60East's documented behaviour, cited at the end; they were not re-verified
against a live instance in the session that wrote this. The statements about
the AMPS **client** library are read from the `amps-client` 5.3.5.3 sources.
The configs this document describes are the ones the module's integration test
runs against, and `scripts/ha-compose.sh validate` runs the server's own config
check on them.

---

## 1. A replicated SOW topic must be journaled

### Replication is a replay of the transaction log

AMPS does not copy SOW files between instances, and the instances share no
state through the filesystem. What a replication Destination sends is the
stream of messages recorded in the local transaction log, in the order they
were recorded. 60East's user guide puts it flatly: AMPS replicates exactly the
information written to the local transaction log, and messages that are not
stored in the transaction log cannot be replicated. For a Destination
`<Topic>` with a literal name, a topic with that name and message type must be
captured in a transaction log; for a regex name, only the matching topics that
are in a transaction log replicate.

The receiving instance records what it receives in **its own** journal and
applies it to **its own** SOW: publishes, delta publishes and `sow_delete`s, in
journal order. That is how the two SOWs end up identical, and why a replicated
message keeps the bookmark of the instance that first accepted it -- the
bookmark is a journal position, and the journal entry travels with the message.

So the rule is simple: every topic that appears under
`<Replication><Destination><Topic>` must also appear under
`<TransactionLog><Topic>`, on every instance that replicates it. A SOW topic
is no exception, because the SOW is not what replication reads.

### What this pair does

Both instance configs carry the same three entries for each replicated topic:

| topic | `<SOW>` | `<TransactionLog>` | `<Replication><Destination>` |
| --- | --- | --- | --- |
| `orders` | the demo's records, keyed on `/id`, `persistent` | yes | yes |
| `/ADMIN/bookmark_store` | the consumers' recovery points, keyed on `/clientName` and `/subId`, `persistent` | yes | yes |

```xml
<TransactionLog>
    <JournalDirectory>./journal</JournalDirectory>
    <PreallocatedJournalFiles>2</PreallocatedJournalFiles>
    <MinJournalSize>10MB</MinJournalSize>
    <Topic>
        <Name>orders</Name>
        <MessageType>json</MessageType>
    </Topic>
    <Topic>
        <Name>/ADMIN/bookmark_store</Name>
        <MessageType>json</MessageType>
    </Topic>
</TransactionLog>
```

The second topic is small -- one record per consumer, rewritten as the
consumer's position moves -- but it is replicated for the same reason as the
first, so it is journaled for the same reason as the first.

### A SOW on its own does not need a transaction log

The user guide is explicit that a SOW topic can be used without any transaction
log: the SOW file is separate from the journal. What the journal adds is
everything that needs history rather than current state:

| needs the journal | does not |
| --- | --- |
| replication | `sow` queries and `sow_and_subscribe` snapshots |
| bookmark subscriptions (resume where you stopped) | plain subscriptions |
| the publisher's persisted acknowledgments, and the duplicate detection they rest on | |
| rebuilding the SOW when its file is lost or damaged | |

In this module the consumer's whole failover story is a bookmark subscription,
so the journal was required before replication entered the picture. Replication
adds the second topic to it, not the journal itself.

### Durability and the journal are two different switches

`<Durability>` says where the SOW's current state lives; the transaction log
says whether its history is kept. The four combinations behave differently at
startup:

| | journaled | not journaled |
| --- | --- | --- |
| `persistent` | recovers from the SOW file; if the file is missing or damaged, rebuilds it by replaying the journal from the topic's `RecoveryPoint`, which defaults to the epoch | recovers from the SOW file; a lost file is lost state |
| `transient` | starts empty and is rebuilt from the journal at every startup, from the `RecoveryPoint` | starts empty |

This pair uses `persistent` plus journaled on both topics: a revived instance
recovers its SOW from disk in well under a second, takes only the delta from its
peer, and could still rebuild everything from the journal if the SOW file were
gone.

### A fresh replica can only receive what the journal still holds

This is the first consequence of "replication is journal replay" that bites
operationally. If journal files have been removed (see section 2), the
messages in them are gone from the source's point of view, and a brand-new
destination -- or one that has been away longer than the retained window --
cannot be sent them. Records that exist only in the source's SOW, because their
journal entries have aged out, will not appear in the new replica's SOW.

The options are to keep the journal at least as long as the SOW history a
replica may need to rebuild, or to seed a new instance from a copy of the
source's SOW and journal files before connecting it.

### How to confirm it on your build

The documentation states the requirement. What it does not spell out, in the
pages consulted here, is whether a Destination topic missing from the
transaction log is rejected at startup or silently replicates nothing. The
server's own config check is where to find out:

```bash
export AMPS_IMAGE=amps-demo:5.3.5.135
./amps-ha-demo/scripts/ha-compose.sh validate
```

---

## 2. The journal is never compacted

### Files, not entries

An AMPS transaction log is a sequence of journal files. Each file is created at
`JournalSize` (`MinJournalSize` is accepted as a synonym; the default is 1 GB,
the floor 10 MB -- this module uses the floor so files roll during a short
demo), `PreallocatedJournalFiles` of them are created before the first message
arrives (default 2), and a file is written append-only and never grown, shrunk,
or rewritten. There is no operation that removes individual entries, merges
files, or keeps the last value per key inside the journal.

Disk comes back only when **whole journal files** are removed. AMPS provides
three ways to do that while running: a scheduled action, the admin console, and
the sibling actions that compress or archive old files instead of deleting them.

```xml
<Actions>
    <Action>
        <On>
            <Module>amps-action-on-schedule</Module>
            <Options><Every>1h</Every></Options>
        </On>
        <Do>
            <Module>amps-action-do-remove-journal</Module>
            <Options><Age>2d</Age></Options>
        </Do>
    </Action>
</Actions>
```

The module name is the one this repository verified against AMPS 5.3.5.135 in
[`bounded-retention`](../../server/config/flows/bounded-retention/amps-config.xml);
`./server/scripts/amps.sh modules` lists what your build registers.

### Replication delays removal; it never triggers it

Before AMPS removes a journal file it checks that the file is no longer in use,
and the documented conditions are that every replay from the file has
completed, every queue message in it has been delivered (and acknowledged,
where required), and every message in it has been **replicated to, and
acknowledged by, each replication destination**. The action marks a file older
than `Age`; a marked file that is still in use is removed once it no longer is.

Three things follow:

- **Replication is a veto, not a trigger.** A message having been replicated
  does not free anything. It merely stops being a reason to keep the file.
- **A peer outage pins the journal.** While the other instance is down, nothing
  it has not acknowledged can be removed, however old. The scheduled downgrade
  to `async` lets publishers be acknowledged without the peer; it does not
  change what the peer has acknowledged. The journal grows until the peer
  returns and catches up, or the destination is removed from the config.
- **Both instances pay.** The destination journals what it receives, so each
  side of this pair holds the full stream for both replicated topics. Budget
  the journal twice, once per instance.

### The SOW is the compacted view

"Compact the journal" is the wrong question for AMPS, because the compacted
view already exists: the SOW holds the last value per key, and the journal
holds the history that produced it. The two ways to carry less history are the
ones in [transaction-log-sizing.md](../../docs/src/transaction-log-sizing.md):

1. **Publish deltas.** The journal records what was published. A delta that
   carries the changed fields journals a fraction of the bytes of the full
   record, and the SOW still ends up holding the whole record because AMPS
   merges server-side.
2. **Age files out.** Keep the journal as long as the longest outage a
   subscriber or a replica can have and still resume or catch up, plus margin.

Both apply unchanged to a replicated pair. Neither needs anything the pair does
not already have.

### What the journal costs

Per instance, journal growth is

```
bytes per day  ~=  messages per day  x  (payload bytes + per-entry header)
```

summed over every journaled topic, and the pair as a whole holds twice that.
The bookmark topic adds one record per consumed message, since the consumer's
store writes its position on every discard that moves it (and on every
persisted acknowledgment that moves it), so its message count is the consumer's
message count and its payload is the small JSON record the adapter writes.

An illustration, on assumed sizes rather than measured ones: ten million orders
a day at 250 bytes of JSON is about 2.5 GB a day of payload per instance; ten
million bookmark updates at 130 bytes is about 1.3 GB more; a two-day `Age`
therefore wants roughly 8 GB per instance before the per-entry header, the
preallocated files, and headroom for the lag between removal runs. Measure
rather than assume: `./gradlew :clients:run --args="journal-lab"` publishes the
same updates as full records and as deltas and reports journal growth for each,
on your build.

The controls are a lagging, file-granular bound, not a cap: usage climbs until
the next scheduled run, drops by whole files, and nothing stops the disk
filling if ingest outruns the policy. Alert on free disk, not only on journal
bytes.

---

## 3. What it means for this pair

### The consumer's position lives in the journal

Two of this module's mechanisms resolve to journal positions:

- the consumer's bookmark subscription resumes from the most recent bookmark in
  its store;
- the record in `/ADMIN/bookmark_store`, from which a replacement consumer on
  another machine resumes, holds that same bookmark.

If the journal file containing that bookmark has been removed, the position
cannot be resumed from. The client library's documentation for
`LoggedBookmarkStore` describes the consequence: a `MOST_RECENT` subscription
whose bookmarks are no longer available begins at the **tail** of the log --
new messages only -- rather than at the start, and the messages in between are
skipped without an error. (The store's recovery-timestamp option exists to turn
that into a replay from the start of the log instead, which is the safer
failure.) So `Age` must exceed the longest outage any consumer may have, and a
consumer that has been away longer than that should be treated as one that must
re-bootstrap from the SOW rather than resume.

### A revived instance catches up from the files its absence pinned

The integration test kills an instance, revives it minutes later, and expects
it to catch up. That works with or without a retention action, and for the
reason in section 2: the files holding what the dead instance has not
acknowledged cannot be removed while it is dead. Retention does not threaten a
peer's resync. It threatens a **new** peer, which has acknowledged nothing and
can only be sent what remains.

### Why the demo configs keep everything

The instance configs in this module carry no removal action. The demo runs for
minutes, its journal is a handful of 10 MB files, and keeping everything means a
consumer started with `-Dha.bookmark=epoch` sees the whole history every time.
For anything that runs longer than a demonstration, add the block from section
2 to **both** configs with an `Age` chosen off the longest consumer or replica
outage you intend to tolerate, and prefer a size-based option over an age if
your build's action offers one -- an age assumes a stable message rate.

---

## Sources

60East documentation:

- [Replicating Messages Between Instances](https://crankuptheamps.com/docs/amps-user-guide/replication)
- [Replication Basics](https://docs.crankuptheamps.com/amps-user-guide/replication/replication-basics)
- [Configuring Outgoing Replication Destinations](https://crankuptheamps.com/docs/amps-user-guide/replication/config-outgoing-replication)
- [Configuring Topics in a SOW](https://crankuptheamps.com/docs/amps-user-guide/sow/configuring-topics-in-sow)
- [Configuring a Transaction Log](https://crankuptheamps.com/docs/amps-user-guide/txlog/configuring-a-transaction-log)
- [Managing Journal Files](https://docs.crankuptheamps.com/amps-user-guide/txlog/managing-journal-files)
- [Manage Transaction Log Journal Files](https://crankuptheamps.com/docs/amps-user-guide/actions/do-elements/do-manage-journal)
- [Capacity Planning](https://crankuptheamps.com/docs/amps-user-guide/operation/capacity-planning)
- [HISTORY notes for AMPS](https://crankuptheamps.com/history/amps)

This repository:

- [transaction-log-sizing.md](../../docs/src/transaction-log-sizing.md) -- the four levers, the sizing knobs, and why there is no single maximum
- [high-volume-market-data.md](../../docs/src/high-volume-market-data.md) -- a worked case on a disk budget
- [the bounded-retention flow](../../server/config/flows/bounded-retention/amps-config.xml) -- the removal action as verified on 5.3.5.135
- [the module README](../README.md) -- the five things that make the failover lossless, and the consumer's move between machines
