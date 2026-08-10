# server

The AMPS instance: configuration, container recipe, lifecycle.

```
server/
  config/
    amps-config.xml                    the demo instance
    amps-config-bounded-retention.xml  minimum-persistent-footprint variant
  scripts/amps.sh                      start / stop / restart / reset / probe
  Containerfile                        build an image from an AMPS release tarball
  vendor/                              drop the tarball here (git-ignored)
  data/                                created at run time; bind-mounted into the
                                       container, so you can watch it grow
```

## Running it

```bash
./server/scripts/amps.sh start     # or: ./gradlew :server:serverStart
./server/scripts/amps.sh status
./server/scripts/amps.sh logs -f
```

`podman` is used when present, `docker` otherwise; force one with
`CONTAINER_ENGINE=docker`.

| endpoint | address |
| --- | --- |
| AMPS clients (JSON) | `tcp://127.0.0.1:9007/amps/json` |
| WebSocket | `ws://127.0.0.1:9008/amps/json` |
| Admin / monitoring UI | <http://127.0.0.1:8085/> |

## Which image?

The default is `docker.io/amps/ce:latest` — 60East's published AMPS Community
Edition image. It is the fastest way to get an instance up, but it is an old
build, and the container's internal layout may not match the default
`AMPS_BIN=/opt/amps/bin/ampServer`. If the container exits at once:

```bash
./server/scripts/amps.sh probe          # find the server binary in the image
AMPS_BIN=/whatever/it/said ./server/scripts/amps.sh start
```

To run a current AMPS instead, download the release tarball from 60East, drop it
in `server/vendor/`, and build the image from `Containerfile` — the instructions
are in that file's header. That is also the route to take on a network that
cannot reach Docker Hub.

## Check the config before trusting it

```bash
./server/scripts/amps.sh validate
./server/scripts/amps.sh validate amps-config-bounded-retention.xml
```

This runs the server's own config parser inside the container and starts nothing.
Two parts of the shipped config are flagged in comments as version-sensitive and
should be validated on your build before you depend on them:

- the **regex SOW topic** (`^desk\.[A-Za-z0-9_-]+$`) in `amps-config.xml`, which
  gives dynamic SOW topics;
- the **`<Actions>` block** in `amps-config-bounded-retention.xml`, which ages out
  journal files on a schedule.

Both are additive: delete either block and the instance still starts, you just
lose that feature. Everything else in both configs is used by the demos.

## The data directory is the point

`server/data/` is bind-mounted to `/amps/data` inside the container and the server
runs with that as its working directory, so:

- `server/data/sow/` holds one file per SOW topic — this is what survives a
  restart;
- `server/data/journal/` holds the transaction log — this is what makes replay
  possible;
- `restart` reuses both, which is exactly the recovery demo;
- `reset` deletes both, which is how you start a demo from a clean slate.

Because it is a host directory, `du -sh server/data/journal` is a real
measurement — the `journal-lab` demo in `clients/` relies on that.

## Topics

| topic | SOW key | journalled | why it exists |
| --- | --- | --- | --- |
| `orders` | `/orderId` | yes | the main SOW: query, delta, replay, recovery |
| `instruments` | `/symbol` | yes | large records, small changes — delta demos |
| `quote-cache` | `/symbol` | **no** | transient SOW with a 60s TTL |
| `desk.*` | `/orderId` | no | dynamic SOW topics (regex-matched) |
| `bench.full` / `bench.delta` | `/symbol` | yes | journal-size laboratory |
| `events.*` | — | no | undeclared dynamic pub/sub topics |
