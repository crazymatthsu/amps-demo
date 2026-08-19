# Scheduled maintenance with AMPS Actions

The question this document answers:

> Delete the SOW every night at 8:30pm. Can the server do that itself?

Yes — through `<Actions>`, and the worked example is
[`amps-config-maintenance.xml`](../../server/config/amps-config-maintenance.xml).
The mechanism takes about ten lines of XML. Most of this document is about the
things that are easy to get wrong once it works: what a scheduled delete costs,
which clock it fires on, and which of the names involved are actually real.

```bash
AMPS_CONFIG=amps-config-maintenance.xml ./server/scripts/amps.sh start
```

## The mechanism

An `<Action>` pairs a condition (`<On>`) with something to do (`<Do>`). Each
half names a module and gives it options:

```xml
<Actions>
  <Action>
    <On>
      <Module>amps-action-on-schedule</Module>
      <Options><Crontab>30 20 * * *</Crontab></Options>
    </On>
    <Do>
      <Module>amps-action-do-delete-sow</Module>
      <Options>
        <Topic>orders</Topic>
        <MessageType>json</MessageType>
        <Filter>/status = 'ORDER_STATUS_FILLED' OR /status = 'ORDER_STATUS_CANCELLED' OR /status = 'ORDER_STATUS_REJECTED'</Filter>
      </Options>
    </Do>
  </Action>
</Actions>
```

Nothing correlates one `<Action>` with another beyond the order they appear in,
so several actions at the same time are several independent schedules that
happen to agree.

## The maintenance window in the example config

| when | what | costs |
| --- | --- | --- |
| 20:30 | delete every record in `quote-cache` | nothing |
| 20:30 | delete `orders` whose status is filled, cancelled or rejected | one journal record per key |
| 20:35 | remove journal files older than a day | reclaims disk |

Two deletes at the same time rather than one, because they are the same command
with very different bills, and seeing them next to each other is the point.

`quote-cache` is `<Durability>transient</Durability>` and absent from
`<TransactionLog>`, so wiping it writes nothing anywhere — the "delete the SOW
nightly" request in its cheapest possible form. `orders` is persistent and
journalled, so its sweep is a *write*: see below. The journal ageing runs five
minutes later so that tonight's delete records are minutes old and survive,
while yesterday's traffic goes.

## Deleting SOW records is a write

The single most important thing to know before scheduling one. A `sow_delete` —
however it is triggered, by a client or by an action — is journalled like any
other message. On a journalled topic, a nightly sweep of *n* keys **adds** *n*
delete records to the transaction log.

So a scheduled SOW delete:

- reduces what queries return, and what the SOW holds in memory;
- does **not** return SOW file space to the filesystem (freed space is reused
  inside the existing file);
- does **not** shrink the transaction log — it grows it, then the journal-ageing
  action shrinks it later;
- does **not** erase history: a bookmark replay from before 20:30 still produces
  every deleted record, because the journal still holds the publishes.

If the goal was disk, the lever is journal retention, not SOW deletion.
See [transaction-log-sizing.md](transaction-log-sizing.md).

## Choosing between the three ways to remove SOW records

| mechanism | triggered by | reach for it when |
| --- | --- | --- |
| `<Expiration>` | record age | the rule *is* an age — "quotes are stale after 60s". Continuous, no schedule, nothing to run |
| `sow_delete` from a client | an event | the removal is caused by something — an order is cancelled, a session ends |
| scheduled `<Actions>` | wall-clock time | the rule is about business time — "clear the day's working orders after the close" |

A TTL cannot express 20:30, because 20:30 is not an age. That is the whole case
for the third row; if your requirement *can* be stated as an age, prefer
`<Expiration>` — it is less machinery and cannot fire at the wrong moment.

## The filter is the entire safety mechanism

Omit `<Filter>` and the action empties the topic. That is correct for
`quote-cache` and catastrophic for `orders`, where 20:30 falls in the middle of
the life of anything still working.

A scheduled delete gets no chance to reconsider, so "finished" has to be
expressed in the filter, not in a comment next to it. Which makes the filter
worth testing as a query first — the same expression, through `sow` instead of
a delete, shows you exactly which records tonight's run would take:

```bash
utils/bin/ampsToFileSOWByKey.sh --topic orders \
    --filter "/status = 'ORDER_STATUS_FILLED'" --out /tmp/tonight.json
```

**Filter values must match what publishers actually write.** This project's
records carry protobuf enum names — `ORDER_STATUS_FILLED`, not `FILLED` — so a
filter over the shorter spelling parses cleanly, runs every night, and matches
nothing, which looks identical to a schedule that is not firing. The example
config uses the `=`/`OR` form the demos already exercise against a live server.

## The clock is the server's

Actions fire on the server's clock, and the container runs UTC unless told
otherwise — so `30 20 * * *` means 20:30 **UTC**, not 8:30pm where you are.
`amps.sh` passes `AMPS_TZ` through as `TZ`:

```bash
AMPS_TZ=America/New_York AMPS_CONFIG=amps-config-maintenance.xml \
    ./server/scripts/amps.sh start

./server/scripts/amps.sh status      # prints the server's clock
```

Crontab fields are `minute hour day-of-month month day-of-week`, so
`30 20 * * *` is 20:30 daily and `30 20 * * 1-5` is weeknights only.

**An interval cannot express a time of day.** `<Every>1d</Every>` fires 24 hours
after the server started, so the maintenance window drifts to whenever you last
restarted — a restart at lunchtime moves tonight's sweep to lunchtime. This is
why the example uses a crontab-style option rather than the `<Every>` that the
journal-ageing config uses.

## Which of these names are real

Action module names have moved between AMPS releases, and a wrong one is
rejected at startup without any hint as to the right one. This repository has
already paid for that lesson: it shipped `amps-action-do-delete-old-journal-files`
in three places, a module that exists in no build, and only found out when a
real server rejected the config outright ([VERIFICATION.md](../../VERIFICATION.md)).

Current status:

| name | status |
| --- | --- |
| `amps-action-on-schedule` | verified on 5.3.5.135 |
| `amps-action-do-remove-journal`, `<Age>` | verified on 5.3.5.135 |
| `amps-action-do-delete-sow` and its options | **not verified** — inferred |
| `<Crontab>` as the wall-clock schedule option | **not verified** — inferred |

The structure around them (`<Actions>`, `<Action>`, `<On>`/`<Do>`, `<Module>`,
`<Options>`) is stable across releases; only the identifiers move.

Rather than guess a second time, ask the binary — the module names are
registered inside the server, so they can be read out of it:

```bash
./server/scripts/amps.sh modules                            # what this build registers
./server/scripts/amps.sh validate amps-config-maintenance.xml
```

`modules` settles module names outright. Option names (`<Age>`, `<Crontab>`,
`<Filter>`) are too generic to extract that way — for those, `validate` names
the one it rejected. If `<Crontab>` is not the spelling your build wants, the
usual alternative is the same expression inside `<Every>`.

Every `<Actions>` block is additive: delete it and the instance still starts,
you just lose the automation.

## Checking it works without waiting until 20:30

Actions log when they fire, at `info` level. So:

1. Set the crontab expression a couple of minutes out (`*/1 * * * *` while
   testing, if your build accepts it).
2. `./server/scripts/amps.sh logs -f` and watch for the module name.
3. Query the topic before and after — `utils/bin/ampsToFileSOW.sh --topic
   quote-cache --out /tmp/before.json` — and compare record counts.

A subscriber with `Options.OOF` receives an OOF with reason `deleted` for every
record the action removes, which is the other way to see it happen live and the
reason live views correct themselves rather than showing records that are gone.
→ [sow-and-recovery.md](sow-and-recovery.md)
