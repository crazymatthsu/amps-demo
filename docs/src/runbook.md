# Runbook

## Prerequisites

- **podman** (or docker — the scripts fall back automatically)
- **JDK 21**
- Network access to Maven Central for the build, and to your container registry
  for the image

Nothing else. Gradle comes from the wrapper; `protoc` is downloaded as a Maven
artifact by the protobuf plugin.

## Five minutes

```bash
./server/scripts/amps.sh start                       # 1. AMPS in a container
./gradlew build                                      # 2. compile + unit tests
./gradlew :clients:run --args="sow-load"             # 3. populate the SOW
./gradlew :clients:run --args="tour"                 # 4. the guided sequence
```

`tour` runs the core demos in the order that builds the argument: pub/sub on an
undeclared topic, what declaring a SOW adds, what adding a transaction log adds.

## The demos

```bash
./gradlew :clients:run --args="list"
```

| demo | shows | needs |
| --- | --- | --- |
| `pubsub` | publish/subscribe on an undeclared topic; latecomers see nothing | — |
| `dynamic-topics` | topics created by publishing; one regex subscription catches them all | — |
| `sow-load` | populating SOW topics — publishing *is* the write path | — |
| `sow-query` | filtered, ordered, top-N snapshot queries evaluated server-side | `sow-load` |
| `sow-query-by-key` | business key vs SOW key; query-and-subscribe on one record | `sow-load` |
| `sow-and-subscribe` | atomic snapshot + live, and OOF when a record leaves the filter | `sow-load` |
| `delta-publish` | update a 1.3 KB record with a 134 B message; verify the merge | — |
| `delta-subscribe` | receive the snapshot once, then only changes | — |
| `bookmark-replay` | replay from epoch, from a bookmark, and resume where you left off | — |
| `expiration` | TTL on SOW records, with expiry notifications | — |
| `truncate` | delete SOW records with `sow_delete`, by filter and by key | — |
| `fix-lifecycle` | FIX 4.2 messages through a state machine into an order-state SOW | — |
| `recovery` | SOW and journal surviving a restart | a restart between phases |
| `journal-lab` | transaction-log growth: whole records vs deltas | a few minutes |

Options go after the demo name:

```bash
./gradlew :clients:run --args="sow-query --filter \"/price > 300\" --topN 10"
./gradlew :clients:run --args="journal-lab --symbols 20 --updates 1000"
```

Or build a launcher script once and skip Gradle:

```bash
./gradlew :clients:installDist
./clients/build/install/amps-demo/bin/amps-demo sow-query --topN 3
```

## The recovery demo

Three steps, because a real restart happens in the middle:

```bash
./gradlew :clients:run --args="recovery --phase snapshot"
./server/scripts/amps.sh restart
./gradlew :clients:run --args="recovery --phase verify"
```

For an unclean shutdown instead of a graceful one:

```bash
podman kill amps-demo && ./server/scripts/amps.sh start
```

## Server lifecycle

```bash
./server/scripts/amps.sh start | stop | restart | status | logs -f
./server/scripts/amps.sh reset      # stop AND delete all SOW/journal data
./server/scripts/amps.sh probe      # find ampServer inside the image
./server/scripts/amps.sh validate   # parse the config without starting
```

Also available as Gradle tasks — `./gradlew :server:serverStart`, `serverStop`,
`serverRestart`, `serverStatus`, `serverReset`, `serverLogs`, `serverProbe`,
`serverValidateConfig`.

| endpoint | address |
| --- | --- |
| clients | `tcp://127.0.0.1:9007/amps/json` |
| websocket | `ws://127.0.0.1:9008/amps/json` |
| admin UI | <http://127.0.0.1:8085/> |

Point the demos elsewhere with `AMPS_HOST` / `AMPS_PORT`, or
`-Damps.host=... -Damps.port=...`.

## Troubleshooting

**The container exits immediately after `start`.**
The server binary is somewhere other than the default `/opt/amps/bin/ampServer`
in your image. Find it and set `AMPS_BIN`:

```bash
./server/scripts/amps.sh probe
AMPS_BIN=/path/it/reported ./server/scripts/amps.sh start
```

**"Could not reach AMPS at tcp://127.0.0.1:9007/amps/json".**
`./server/scripts/amps.sh status`. If it is running, check the port is published
and not taken by something else.

**A SOW query returns nothing.**
Run `sow-load` first. If it still returns nothing, the SOW key is not resolving:
check that `<Key>` matches the lowerCamelCase JSON member name, not the
snake_case proto field name — see
[protobuf-json-and-amps.md](protobuf-json-and-amps.md).

**A numeric filter returns the wrong rows.**
The field is probably `int64`, which protobuf JSON writes as a quoted string,
turning a numeric comparison into a lexical one. Same document, rule 1.

**The `desk.*` dynamic SOW query returns zero.**
The regex SOW topic syntax is the one part of the config that varies by AMPS
version. `./server/scripts/amps.sh validate` will say whether your build accepts
it; the comment in `server/config/amps-config.xml` explains the alternative.

**`journal-lab` reports zero on-disk growth for the delta phase.**
Expected. AMPS preallocates journal files at `MinJournalSize`, so a small workload
fits inside an already-allocated file. The bytes-published figure is the exact
comparison; the file count shows rollover.

**Starting over.**

```bash
./server/scripts/amps.sh reset
rm -rf build/client-state          # client bookmark and publish stores
```

## What this repo does not cover

Out of scope by design, all documented in the AMPS User Guide:

- non-JSON message types (FIX, NVFIX, XML, composite)
- authentication and entitlements
- HA: replication, failover, `<Replication>` configuration
- queues (`<Queue>`) and competing consumers
- aggregation, views and conflated topics beyond the projection example
- distributed deployments and client-side server chooser lists beyond one URI
