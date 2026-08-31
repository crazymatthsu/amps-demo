# amps-demo

A working demonstration of [60East AMPS](https://www.crankuptheamps.com/) — the
messaging features that make it different from a log-based broker, exercised
against a real instance running in podman.

JSON is the primary wire format, with **protobuf as the schema** and canonical
protobuf JSON as the encoding; the native `fix` and `nvfix` message types get
their own demos. Java and Gradle throughout.

```
amps-demo/
├── common/    protobuf schemas, JSON codec, delta computation, client factories
├── server/    AMPS config (per environment/flow), Containerfile, compose + lifecycle scripts
├── clients/   sixteen runnable feature demos behind one CLI
├── utils/     operator tools: load a file, dump the SOW or journal, clear down
├── amps-cli/  dump a FIX SOW: snapshot, subscribe, or query; raw or NVFIX
├── fix42-publisher/  Spring Boot: FIX 4.2 delta publishing onto chained SOW keys
├── cache-persistent-store/  a local Map cache with AMPS as its persistent store
├── hazelcast-persistent-store/  Hazelcast OSS persisting its IMaps in AMPS (MapStore SPI)
├── amps-test-harness/  starts a throwaway AMPS container for the integration suites
└── docs/      the written half, link-checked by the build
```

## Quick start

```bash
# one-time: build an image from an AMPS release tarball in server/vendor/
podman build --platform linux/amd64 -f server/Containerfile -t amps-demo:5.3 \
    --build-arg AMPS_TARBALL=AMPS-5.3.5.3-Release-Linux.tar.gz server
export AMPS_IMAGE=amps-demo:5.3

./server/scripts/amps.sh start                # AMPS in a container
./gradlew build                               # compile + unit tests
./gradlew :clients:run --args="sow-load"      # populate the SOW
./gradlew :clients:run --args="tour"          # the guided sequence
```

`podman` is used when present, `docker` otherwise. The AMPS distribution is
Linux x86_64 only, so on Apple Silicon the image runs emulated — `amps.sh`
passes `--platform linux/amd64` for you.

## What it demonstrates

| | demo | one-line version |
| --- | --- | --- |
| **Pub/sub** | `pubsub` | the baseline: no state, no history, no key |
| **Dynamic topics** | `dynamic-topics` | topics exist because someone published to them; one regex subscription catches a whole family |
| **SOW topics** | `sow-load`, `sow-query` | the broker keeps last-value-per-key, so "what is true now?" is a query, not a rebuild |
| **Snapshot** | `sow-and-subscribe` | current state plus live updates in one atomic command, with OOF when a record leaves your filter |
| **Query by key** | `sow-query-by-key` | business key vs server-assigned SOW key, and following one record live |
| **Delta updates** | `delta-publish`, `delta-subscribe` | update a 1.3 KB record with a 134 B message; receive only what changed |
| **Transaction log** | `bookmark-replay` | replay from the epoch, from a bookmark, or resume exactly where you stopped |
| **Recovery** | `recovery` | SOW returns current state instantly; the journal returns history on request |
| **Expiration** | `expiration` | TTL on SOW records, with expiry notifications to subscribers |
| **Truncation** | `truncate` | `sow_delete` by filter or by key; why it grows the journal rather than shrinking it |
| **FIX order state** | `fix-lifecycle` | derive 35=D/G/F/8/9 into a queryable order-state SOW; the thin state machine AMPS cannot replace |
| **Native FIX / NVFIX** | `fix-native`, `nvfix-native` | raw SOH-separated payloads on MessageType `fix`/`nvfix` topics; keys and filters on tags and names |
| **Journal sizing** | `journal-lab` | measures full-publish vs delta cost in the transaction log, on disk |
| **Chained SOW keys** | `fix42-publisher` | a whole FIX cancel/replace chain collapses to ONE record, keyed by the server rather than the client |

`./gradlew :clients:run --args="list"` for the catalogue.

The last row is a module rather than a demo, because it needs its own server
flow and a Spring Boot application:

```bash
AMPS_FLOW=fix42-chaining ./server/scripts/amps.sh start
./gradlew :fix42-publisher:bootRun
```

It publishes FIX 4.2 order flow -- parent and child orders, amends, cancels,
fills, rejects -- as field-level deltas onto SOW topics keyed by AMPS's
optional **chaining key generator**, which resolves the 11/41 ClOrdID chain
server-side. Nine ClOrdIDs across five chains store as five records, each
carrying the newest amend merged onto the original order's untouched terms,
with no chain state in the publisher at all.
-> [fix42-publisher/README.md](fix42-publisher/README.md), and
[docs/fix42-view/](docs/fix42-view/README.md) for where that stops being enough.

`cache-persistent-store` is a module with its own flow too: a cache library
whose local `java.util.Map` hydrates from an AMPS SOW at startup, writes
through on mutation, and reads through on a miss -- so a restarted or
failed-over process recovers its cache by asking AMPS. It also answers "how do
I store a `Map<String, Map<String, ?>>` in a keyed store?" two ways (nested
value vs. a composite-key flattening) and demonstrates both.

```bash
AMPS_FLOW=cache ./server/scripts/amps.sh start
./gradlew :cache-persistent-store:run
```

-> [cache-persistent-store/README.md](cache-persistent-store/README.md) for the
design, the map-of-maps trade-offs, and the integration tests.

`hazelcast-persistent-store` takes the same idea to a real cache product:
Hazelcast open source persisting its `IMap`s through the MapStore SPI (the
sanctioned persistence route in OSS -- hot-restart is Enterprise-only), with
AMPS as the store. Topics are grouped by persistence *policy* -- a composite
`(/map, /key)` SOW key lets any number of Hazelcast maps share one "tier"
topic -- so fifty caches need two topics, not fifty, and a replacement member
rehydrates every map from AMPS alone.

```bash
AMPS_FLOW=hazelcast ./server/scripts/amps.sh start
./gradlew :hazelcast-persistent-store:run
```

-> [hazelcast-persistent-store/README.md](hazelcast-persistent-store/README.md)
for the tier design, the Hazelcast semantics that bite (TTL resurrection,
`clear()` vs `evictAll()`), and the two-member integration tests.

## Operator tools

Separate from the demos: `utils/` holds shell tools for working against a real
instance without writing an application.

```bash
utils/bin/fileToAMPS.sh          --topic orders --file orders.json
utils/bin/ampsToFileSOW.sh       --topic orders --out /tmp/orders.json
utils/bin/ampsToFileSOWByKey.sh  --topic orders --filter "/quantity > 3000" --out /tmp/big.json
utils/bin/ampsToFileTxLog.sh     --topic orders --out /tmp/journal.jsonl
utils/bin/truncateAMPS.sh        --topic orders          # dry run; --yes to delete
```

Dumps round-trip — what `ampsToFileSOW.sh` writes, `fileToAMPS.sh` republishes.
Note that `truncateAMPS.sh` clears the SOW only: the transaction log cannot be
truncated by any client, and `--journal` explains what to do instead.
→ [utils/README.md](utils/README.md)

`amps-cli` dumps a SOW of native FIX messages from the Linux console: snapshot
only, snapshot then subscribe, or query by filter, as raw SOH-separated
`tag=value` or as NVFIX (FIX 4.2 tag names and enumerated meanings).

```bash
./gradlew :amps-cli:test
./gradlew :clients:run --args="fix-native"     # populate fix.native.* if needed
./gradlew :amps-cli:run --args="--mode snapshot --topic fix.native.orders --output raw"
./gradlew :amps-cli:run --args="--mode query --topic fix.native.orders --filter \"/39 = '2'\" --output nvfix"
./gradlew :amps-cli:run --args="--mode snapshot-subscribe --topic fix.native.orders --output nvfix --timeout-ms 15000"
```

`--url tcp://127.0.0.1:9007/amps/fix` (or `-Damps.host` / `AMPS_HOST`) matches
the rest of the repo. Flags and more examples: [amps-cli/README.md](amps-cli/README.md).

## The two questions this repo was built to answer

**"How do I keep the transaction log small when SOW records are large and
repetitive?"**
Four levers, in order of effect: don't journal topics you never replay; publish
deltas; age out old journal files; bound the SOW with `<Expiration>`. Measured, not
asserted — `journal-lab` runs the experiment.
→ [docs/src/transaction-log-sizing.md](docs/src/transaction-log-sizing.md)

**"How does this differ from Kafka?"**
Kafka's topic is a durable log and state is something consumers derive from it.
AMPS's topic is a stream the broker also indexes by key, so current state is a
query. Almost every other difference follows.
→ [docs/src/amps-vs-kafka.md](docs/src/amps-vs-kafka.md)

## Why protobuf schema but JSON payload

AMPS parses the payload — that is how content filters, SOW keys and delta merges
work. Binary protobuf would make the message opaque and every server-side feature
would stop working. Protobuf still earns its place as the schema: real types,
explicit evolution rules, generated code.

The combination imposes rules that are easy to get wrong and silent when you do —
`int64` becomes a *quoted string* in canonical protobuf JSON, which turns
`/quantity > 500` into a lexical comparison. Each rule has a test.
→ [docs/src/protobuf-json-and-amps.md](docs/src/protobuf-json-and-amps.md)

## Documentation

| | |
| --- | --- |
| [runbook.md](docs/src/runbook.md) | running everything, and troubleshooting |
| [amps-vs-kafka.md](docs/src/amps-vs-kafka.md) | the comparison, including where Kafka wins |
| [sow-and-recovery.md](docs/src/sow-and-recovery.md) | SOW, snapshots, restart survival |
| [transaction-log-sizing.md](docs/src/transaction-log-sizing.md) | journal size and retention |
| [high-volume-market-data.md](docs/src/high-volume-market-data.md) | worked case: 500 GB/day on a 100 GB disk |
| [delta-updates.md](docs/src/delta-updates.md) | delta semantics and traps |
| [protobuf-json-and-amps.md](docs/src/protobuf-json-and-amps.md) | schema and encoding design |
| [fix-order-state.md](docs/src/fix-order-state.md) | FIX 4.2 order state: the AMPS/gateway split |
| [native-fix-and-nvfix.md](docs/src/native-fix-and-nvfix.md) | raw FIX/NVFIX message types, natively parsed |
| [fix42-view/](docs/fix42-view/README.md) | can an AMPS *view* hold live FIX 4.2 order state? -- analysis, and what got built |
| [deploying-utils-to-linux.md](docs/src/deploying-utils-to-linux.md) | packaging the utils tools for deployment |
| [scheduled-maintenance.md](docs/src/scheduled-maintenance.md) | nightly SOW cleanup on a schedule, with `<Actions>` |
| [server-env-layering.md](docs/src/server-env-layering.md) | compose, environments and business flows, and how the `.env` layers stack |

## Requirements

JDK 21, podman or docker, and network access to Maven Central. Gradle comes from
the wrapper and `protoc` is fetched as a Maven artifact.

## Before you rely on it

Two things to know:

1. **You have to supply the AMPS server yourself.** There is no public AMPS
   server image: 60East distributes it as a release tarball behind the
   [evaluation sign-up](https://www.crankuptheamps.com/evaluate/). Drop the
   tarball in `server/vendor/`, build the image from
   [`server/Containerfile`](server/Containerfile), and point `AMPS_IMAGE` at it.
   (`docker.io/amps/ce` is *not* this AMPS — it is an unrelated Apache/MySQL/PHP
   product sharing the acronym, with no `ampServer` binary in it. `amps.sh`
   rejects it by name rather than let you find out the slow way.)

2. **Verified against AMPS 5.3.5.135.** Every config in `server/config/`
   validates with no warnings, and all sixteen demos run green against a live
   instance — see [VERIFICATION.md](VERIFICATION.md) for that run and the six
   defects it turned up. The two elements previously flagged as
   version-sensitive are now settled: a dynamic SOW topic needs its regex in
   `<Pattern>` (in `<Name>` it is taken literally and silently matches nothing),
   and the journal-ageing module is `amps-action-do-remove-journal`.

   Both take a second to re-check on your own build:

   ```bash
   ./server/scripts/amps.sh validate
   ./server/scripts/amps.sh validate bounded-retention
   ```

   (`validate` takes a *flow name* — a folder under `server/config/flows/` —
   not a filename; run `./server/scripts/amps.sh flows` to list them. See
   [server-env-layering.md](docs/src/server-env-layering.md) if that folder
   structure is new to you.)

   The one flow that has *not* been through that run is `maintenance`
   ([`server/config/flows/maintenance/amps-config.xml`](server/config/flows/maintenance/amps-config.xml)),
   which schedules a nightly SOW cleanup with `<Actions>`; its SOW-deleting
   module and wall-clock schedule option are flagged in the file itself.
   `./server/scripts/amps.sh modules` lists the action modules your build
   actually registers, which is the cheap way to settle any of this.

Everything else in the configuration is exercised by the demos.
