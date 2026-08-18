# utils

Operator tools for talking to a running AMPS instance without writing an
application: get a file in, get data out, clear it down.

Distinct from `clients/`, which exists to *explain* AMPS. These just do a job,
so they are quiet, they put payloads in files rather than on your terminal, and
the destructive one refuses to run without being told twice.

```bash
utils/bin/fileToAMPS.sh          --topic orders --file orders.json
utils/bin/ampsToFileSOW.sh       --topic orders --out /tmp/orders.json
utils/bin/ampsToFileSOWByKey.sh  --topic orders --filter "/quantity > 3000" --out /tmp/big.json
utils/bin/ampsToFileTxLog.sh     --topic orders --out /tmp/journal.jsonl
utils/bin/truncateAMPS.sh        --topic orders          # dry run; --yes to delete
```

Each script builds the Java behind it on first use, so there is no setup step.
Add `--help` to any of them for the full option list. They read `AMPS_HOST` /
`AMPS_PORT` (or `-Damps.host` / `-Damps.port`) like everything else in this
repo.

## The tools

| script | what it does |
| --- | --- |
| `fileToAMPS.sh` | publishes a file to a topic, one message per line |
| `ampsToFileSOW.sh` | dumps a SOW topic to a file |
| `ampsToFileSOWByKey.sh` | dumps the part of a SOW topic matching a filter or SOW keys |
| `ampsToFileTxLog.sh` | dumps a journalled topic's history via bookmark replay |
| `truncateAMPS.sh` | deletes SOW records; dry run unless `--yes` |

## Two file formats, and why

Dumps round-trip: what comes out of `ampsToFileSOW.sh` goes back in through
`fileToAMPS.sh` unchanged.

- **`lines`** (default for the SOW tools) — one payload per line, exactly as it
  was on the wire. Greppable, diffable, republishable. JSON, FIX and NVFIX
  payloads never contain a newline, so it is lossless for them; if one ever
  does, the dump tools count it and tell you rather than leaving you with a file
  that silently reads back as more messages than it holds.
- **`jsonl`** (default for the transaction-log tool) — one JSON object per line,
  `{"topic":…,"bookmark":…,"sowKey":…,"data":…}`. The payload is a JSON string,
  so any bytes survive, and the envelope keeps the bookmark. Use it when you
  want the bookmarks, or when the payload is not known to be newline-free.

## Truncation: read this before using it

`truncateAMPS.sh` clears **the SOW**. It does not truncate the transaction log,
and no client can — AMPS exposes no command that removes journal entries.

That is a design decision rather than a gap. The journal is an ordered,
append-only record, and both bookmark replay and publisher deduplication depend
on it staying that way; cutting messages out of the middle would break the
guarantees it exists to provide. A `sow_delete` is itself a write, so clearing a
SOW makes the journal *larger*.

`truncateAMPS.sh --journal` prints the two things that do work:

1. **Age journal files out on a schedule** — the `amps-action-do-remove-journal`
   action, in place in
   [`server/config/amps-config-bounded-retention.xml`](../server/config/amps-config-bounded-retention.xml).
   This is the supported way to bound the journal of a running instance.
2. **Stop the instance and delete its data directory** —
   `./server/scripts/amps.sh reset`. The only route that returns the disk to the
   filesystem, and it takes the SOW with it.

Never prune journal files with an external `rm` while AMPS is running: it tracks
which files back which bookmark ranges, and removing them underneath it risks
failed replays and a SOW it cannot reconcile after an unclean shutdown.

Sizing guidance is in
[`docs/src/transaction-log-sizing.md`](../docs/src/transaction-log-sizing.md).

## Worked example

```bash
# snapshot a topic, then reload it somewhere else
utils/bin/ampsToFileSOW.sh --topic orders --out /tmp/orders.json
utils/bin/fileToAMPS.sh --topic desk.backup --file /tmp/orders.json

# pull one record out by business key
utils/bin/ampsToFileSOWByKey.sh --topic orders \
    --filter "/orderId = 'ORD-00007'" --out /tmp/one.json

# take the last 100 journal entries, with their bookmarks
utils/bin/ampsToFileTxLog.sh --topic orders --out /tmp/tail.jsonl --max 100

# see what a purge would remove, then do it
utils/bin/truncateAMPS.sh --topic desk.backup
utils/bin/truncateAMPS.sh --topic desk.backup --yes
```

## Running without the wrappers

The scripts are a front for one dispatcher, which you can call directly:

```bash
./gradlew :utils:installDist
./utils/build/install/amps-utils/bin/amps-utils            # catalogue
./utils/build/install/amps-utils/bin/amps-utils sow-to-file --topic orders --out /tmp/o.json

# or through Gradle
./gradlew :utils:run -q --args="truncate --topic orders"
```

Subcommands are `file-to-amps`, `sow-to-file`, `txlog-to-file` and `truncate`.
Run from the repository root — relative `--file` / `--out` paths resolve against
it.

## Deploying to a Linux box

The tools do not need the repository or Gradle at runtime: `installDist`
already produces a relocatable `bin/` + `lib/` tree, and `distTar` wraps it as
a versioned tarball that runs anywhere with a Java 21 JRE. The analysis of the
options — tarball, fat jar, jpackage, container, rpm/deb — and the recommended
path are in
[docs/src/deploying-utils-to-linux.md](../docs/src/deploying-utils-to-linux.md).

## Notes

- Undeclared topics keep no state, so `ampsToFileSOW.sh` correctly reports zero
  for them; only topics in `<SOW>` can be dumped.
- Likewise only topics in `<TransactionLog>` have history for
  `ampsToFileTxLog.sh`. Both tools say so when they come back empty, rather than
  leaving you guessing.
- A bookmark subscription never ends — it catches up and goes live — so the
  transaction-log dump stops after `--idle-ms` without a message rather than on
  an end marker.
- A misspelled option is an error, not something to ignore. `--dryrun` will not
  quietly become `--dry-run` on a tool that deletes data.
