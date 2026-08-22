# amps-cli

Command-line reader for an AMPS SOW of FIX messages. Snapshot, snapshot-then-
subscribe, or query by filter; print raw SOH-separated FIX or expand to NVFIX
(FIX 4.2 tag names and enumerated values).

This is an operator tool, not a teaching demo. The `fix-native` /
`nvfix-native` demos in `clients/` explain the wire format; this module just
dumps what is in the topic.

## Run (Linux)

From the repository root, with AMPS already listening (defaults match
`DemoConfig`: `127.0.0.1:9007`):

```bash
# compile + unit tests (no live AMPS required)
./gradlew :amps-cli:test

# snapshot the native FIX orders SOW, raw tag=value with SOH separators
./gradlew :amps-cli:run --args="--mode snapshot --topic fix.native.orders --output raw"

# same snapshot, NVFIX: tag names and decoded enums (Side=Buy, OrdStatus=Filled, ...)
./gradlew :amps-cli:run --args="--mode snapshot --topic fix.native.orders --output nvfix"

# query by a server-side content filter (FIX topics use tag numbers)
./gradlew :amps-cli:run --args="--mode query --topic fix.native.orders --filter \"/39 = '2'\" --output nvfix"

# snapshot then stay subscribed until idle timeout or --max
./gradlew :amps-cli:run --args="--mode snapshot-subscribe --topic fix.native.orders --output nvfix --timeout-ms 15000 --max 50"
```

Point at a different instance without editing anything:

```bash
./gradlew :amps-cli:run --args="--url tcp://127.0.0.1:9007/amps/fix --topic fix.native.orders --mode snapshot"
# or the same knobs the rest of the repo uses:
./gradlew :amps-cli:run -Damps.host=127.0.0.1 -Damps.port=9007 --args="--message-type fix --topic fix.native.orders"
```

For an NVFIX topic, log on with that message type (the path on the URI is what
selects the payload format):

```bash
./gradlew :amps-cli:run --args="--url tcp://127.0.0.1:9007/amps/nvfix --topic nvfix.native.orders --output nvfix"
```

## Flags

| flag | default | meaning |
| --- | --- | --- |
| `--url` | `tcp://$AMPS_HOST:$AMPS_PORT/amps/<message-type>` | AMPS client URI |
| `--message-type` | `fix` | URI path when `--url` is omitted (`fix` or `nvfix`) |
| `--topic` | `fix.native.orders` | SOW topic |
| `--filter` | (none) | AMPS content filter; **required** for `--mode query` |
| `--mode` | `snapshot` | `snapshot` / `snapshot-subscribe` / `query` |
| `--output` | `raw` | `raw` (payload as-is) or `nvfix` (FIX 4.2 names + enum meanings) |
| `--max` | `0` (unlimited) | stop after this many data messages |
| `--timeout-ms` | `10000` (or `AMPS_TIMEOUT_MS`) | command timeout; also the idle timeout on subscribe |
| `--client-name` | `amps-cli` | AMPS client name |
| `--help` | | print usage and exit |

Message payloads go to stdout (one FIX message per line of fields, still SOH-
separated). Counts and errors go to stderr.

`--output nvfix` is spec-driven FIX 4.2: numeric tags become names (`39` →
`OrdStatus`) and encoded values become their meanings (`2` → `Filled`). Unknown
tags and free-text fields are left unchanged. It does not require the topic
itself to be MessageType `nvfix`.
