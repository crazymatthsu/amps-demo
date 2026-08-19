# server

The AMPS instance: configuration, container recipe, lifecycle.

```
server/
  config/
    default.env                        env layer 1 -- repo defaults, every env/flow
    envs/{local,dev,staging,prod}/      env layer 2 -- ports, TZ, image platform
    flows/
      default/            {amps-config.xml, flow.env}   the demo instance
      bounded-retention/  {amps-config.xml, flow.env}   minimum-persistent-footprint
      market-data/        {amps-config.xml, flow.env}   500 GB/day on a 100 GB disk
      maintenance/        {amps-config.xml, flow.env}   nightly scheduled SOW cleanup
  docker-compose.yml                   the compose alternative to a raw `podman run`
  scripts/
    amps.sh                            start / stop / restart / reset / probe / validate
    amps-compose.sh                    same, via compose, layering env + flow
  Containerfile                        build an image from an AMPS release tarball
  vendor/                              drop the tarball here (git-ignored)
  data/                                created at run time; bind-mounted into the
                                       container, so you can watch it grow
```

Two selectors, not one: `AMPS_FLOW` picks *what* runs (a folder under
`config/flows/` — the business logic, the config XML), `AMPS_ENV` picks
*where* (a folder under `config/envs/` — ports, timezone, platform).
`amps.sh` only ever knew about the first; `amps-compose.sh` adds the second
and the `.env` layering. Full explanation:
→ [docs/src/server-env-layering.md](../docs/src/server-env-layering.md)

## Running it

```bash
./server/scripts/amps.sh start     # or: ./gradlew :server:serverStart
./server/scripts/amps.sh status
./server/scripts/amps.sh logs -f
```

`podman` is used when present, `docker` otherwise; force one with
`CONTAINER_ENGINE=docker`. This runs one flow at a time
(`AMPS_FLOW=market-data ./server/scripts/amps.sh start`, default `default`)
against the flat `server/data/` directory, same as it always has.

For more than one flow or environment at once, `amps-compose.sh` is the
alternative — same commands, plus the environment axis, via
`docker-compose.yml`:

```bash
AMPS_ENV=dev AMPS_FLOW=market-data ./server/scripts/amps-compose.sh start
./server/scripts/amps-compose.sh printenv    # check the env layering resolved as expected
```

| endpoint | address |
| --- | --- |
| AMPS clients (JSON) | `tcp://127.0.0.1:9007/amps/json` |
| WebSocket | `ws://127.0.0.1:9008/amps/json` |
| Admin / monitoring UI | <http://127.0.0.1:8085/> |
| SQL console | in the admin UI — see below |

## Querying from the admin UI

The admin UI (Galvanometer) can run queries and subscriptions against the SOW
topics from the browser — the quickest way to look at state without writing a
client. It is off until `<Admin>` names a transport for it to use, which every
flow's config now does:

```xml
<SQLTransport>amps-websocket</SQLTransport>
```

The value names a `<Transport>`, not a port. The UI itself is served over HTTP
on 8085, but the queries it submits travel over that WebSocket transport, **so
the browser has to reach 9008 as well as 8085** — worth knowing if you publish
the admin port through a tunnel and wonder why the query page does nothing.

Remove the line and the UI still loads, just without those capabilities: the
page reports `__sql=false` instead of `__sql=true`, which is a quick way to
check the setting took effect:

```bash
curl -s http://127.0.0.1:8085/ | grep -o "__sql=[a-z]*"
```

Filters use the same `/field` paths as everything else in this repo — the
expressions in `clients/` transfer to the query page unchanged.

## Which image?

There is no default, because there is no public AMPS server image to default to.
60East distributes the server as a release tarball behind the [evaluation
sign-up](https://www.crankuptheamps.com/evaluate/); build your own image from it:

```bash
cp AMPS-5.3.5.3-Release-Linux.tar.gz server/vendor/
podman build --platform linux/amd64 -f server/Containerfile -t amps-demo:5.3 \
    --build-arg AMPS_TARBALL=AMPS-5.3.5.3-Release-Linux.tar.gz server
export AMPS_IMAGE=amps-demo:5.3
```

`amps.sh` refuses to run without `AMPS_IMAGE` set.

> **Not `docker.io/amps/ce`.** Despite being described as "AMPS Community
> Edition", that Docker Hub account publishes an unrelated Apache/MySQL/PHP
> product that shares the acronym — the image contains no `ampServer` binary at
> any path. `amps.sh` rejects it by name so you do not spend an afternoon
> probing for a binary that was never there.

The AMPS distribution is Linux x86_64 only, so `amps.sh` passes
`--platform linux/amd64` on every container it runs; on Apple Silicon that means
emulation, which the podman machine provides. Override with `AMPS_PLATFORM`, or
set it empty to let the engine choose.

If the container exits at once, the binary is not at the default
`AMPS_BIN=/opt/amps/bin/ampServer`:

```bash
./server/scripts/amps.sh probe          # find the server binary in the image
AMPS_BIN=/whatever/it/said ./server/scripts/amps.sh start
```

## Check the config before trusting it

```bash
./server/scripts/amps.sh validate
./server/scripts/amps.sh validate bounded-retention
./server/scripts/amps.sh flows              # list what's available
```

This runs the server's own config parser inside the container and starts
nothing. `validate` takes a *flow name*, not a filename — the reorganisation
that added `envs/`/`flows/` moved every config one level deeper; see
[server-env-layering.md](../docs/src/server-env-layering.md) if this is new.

The version-sensitive part of any config is the `<Actions>` block: module names
have moved between AMPS releases, and a wrong one is rejected at startup without
suggesting the right one. This repo shipped a nonexistent journal-ageing module
until a real server said otherwise. So rather than guess, ask the binary:

```bash
./server/scripts/amps.sh modules
```

which reads the registered `amps-action-*` names out of the server itself.
Option names (`<Age>`, `<Every>`, `<Filter>`) are too generic to extract that
way — for those, `validate` names the one it rejected.

Every `<Actions>` block is additive: delete it and the instance still starts,
you just lose the automation.

## Scheduled maintenance

The `maintenance` flow ([`flows/maintenance/amps-config.xml`](config/flows/maintenance/amps-config.xml)) is the worked example of the `<On>`/`<Do>` pair:
a nightly window that clears SOW records on a wall-clock schedule, with no cron
job and no client involved.

| when | what |
| --- | --- |
| 20:30 | delete every record in `quote-cache` |
| 20:30 | delete `orders` whose `/status` is filled, cancelled or rejected |
| 20:35 | remove journal files older than a day |

```bash
AMPS_TZ=America/New_York AMPS_FLOW=maintenance ./server/scripts/amps.sh start

# or, via compose, where the maintenance flow already pins this TZ for you
# regardless of environment (see flows/maintenance/flow.env):
AMPS_FLOW=maintenance ./server/scripts/amps-compose.sh start
```

**`AMPS_TZ` is not decoration.** Actions fire on the server's clock, and the
container is UTC unless told otherwise, so "20:30" means 20:30 UTC by default —
`amps.sh status` prints the server clock so you can see which one you got.

Two things worth knowing before scheduling a wipe of a real topic: deleting SOW
records is a *write*, so on a journalled topic it grows the transaction log
before anything shrinks; and if the rule you want is really about record age,
`<Expiration>` does it continuously for free.
→ [docs/src/scheduled-maintenance.md](../docs/src/scheduled-maintenance.md)

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

(`amps-compose.sh` nests this one level deeper, `server/data/<env>/<flow>/`,
because it is designed to run more than one flow or environment at once and
a shared directory would let them silently overwrite each other's SOW files.
`amps.sh` stays flat and single-instance, as above.)

## Topics

| topic | SOW key | journalled | why it exists |
| --- | --- | --- | --- |
| `orders` | `/orderId` | yes | the main SOW: query, delta, replay, recovery |
| `instruments` | `/symbol` | yes | large records, small changes — delta demos |
| `quote-cache` | `/symbol` | **no** | transient SOW with a 60s TTL |
| `desk.*` | `/orderId` | no | dynamic SOW topics — one physical `desk` topic with a `<Pattern>`, many logical ones |
| `bench.full` / `bench.delta` | `/symbol` | yes | journal-size laboratory |
| `events.*` | — | no | undeclared dynamic pub/sub topics |
