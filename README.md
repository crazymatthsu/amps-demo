# amps-demo

A working demonstration of [60East AMPS](https://www.crankuptheamps.com/) — the
messaging features that make it different from a log-based broker, exercised
against a real instance running in podman.

Everything is JSON on the wire, with **protobuf as the schema** and JSON as the
encoding. Java and Gradle throughout.

```
amps-demo/
├── common/    protobuf schemas, JSON codec, delta computation, client factories
├── server/    AMPS config, Containerfile, podman lifecycle scripts
├── clients/   twelve runnable feature demos behind one CLI
└── docs/      the written half, link-checked by the build
```

## Quick start

```bash
./server/scripts/amps.sh start                # AMPS in a container
./gradlew build                               # compile + 26 unit tests
./gradlew :clients:run --args="sow-load"      # populate the SOW
./gradlew :clients:run --args="tour"          # the guided sequence
```

`podman` is used when present, `docker` otherwise.

## What it demonstrates

| | demo | one-line version |
| --- | --- | --- |
| **Pub/sub** | `pubsub` | the baseline: no state, no history, no key |
| **Dynamic topics** | `dynamic-topics` | topics exist because someone published to them; one regex subscription catches a whole family |
| **SOW topics** | `sow-load`, `sow-query` | the broker keeps last-value-per-key, so "what is true now?" is a query, not a rebuild |
| **Snapshot** | `sow-and-subscribe` | current state plus live updates in one atomic command, with OOF when a record leaves your filter |
| **Query by key** | `sow-query-by-key` | business key vs server-assigned SOW key, and following one record live |
| **Delta updates** | `delta-publish`, `delta-subscribe` | update a 1.5 KB record with a 90 B message; receive only what changed |
| **Transaction log** | `bookmark-replay` | replay from the epoch, from a bookmark, or resume exactly where you stopped |
| **Recovery** | `recovery` | SOW returns current state instantly; the journal returns history on request |
| **Expiration** | `expiration` | TTL on SOW records, with expiry notifications to subscribers |
| **Journal sizing** | `journal-lab` | measures full-publish vs delta cost in the transaction log, on disk |

`./gradlew :clients:run --args="list"` for the catalogue.

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
| [delta-updates.md](docs/src/delta-updates.md) | delta semantics and traps |
| [protobuf-json-and-amps.md](docs/src/protobuf-json-and-amps.md) | schema and encoding design |

## Requirements

JDK 21, podman or docker, and network access to Maven Central. Gradle comes from
the wrapper and `protoc` is fetched as a Maven artifact.

## Before you rely on it

Two things to know:

1. **The demo defaults to `docker.io/amps/ce:latest`**, 60East's published AMPS
   Community Edition image. It is the fastest way to get an instance up but is an
   old build. To run a current AMPS, drop a release tarball in `server/vendor/`
   and build the image from [`server/Containerfile`](server/Containerfile).

2. **Two config elements are version-sensitive and are flagged in place**: the
   regex SOW topic that gives dynamic SOW topics, and the `<Actions>` block that
   ages out journal files. Check them against your build before depending on
   them:

   ```bash
   ./server/scripts/amps.sh validate
   ./server/scripts/amps.sh validate amps-config-bounded-retention.xml
   ```

   Both are additive — delete either and the instance still starts.

Everything else in the configuration is exercised by the demos.
