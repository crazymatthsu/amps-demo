# Running AMPS via compose, and layering env files by environment and flow

The question this document answers:

> The lifecycle script relies on one flat `AMPS_CONFIG` filename and a raw
> `podman run`. How should that become a `docker-compose.yml`-driven setup
> where different environments and different business flows each get their
> own config, sourced from a default plus an override?

Two independent axes, four layered `.env` files, and a compose file that
never runs on its own. The rest of this document is what each piece is for
and the traps in getting there.

```bash
AMPS_ENV=dev AMPS_FLOW=market-data ./server/scripts/amps-compose.sh start
```

## The two axes

**Business flow** is *what the instance is for* — which `amps-config.xml`,
i.e. which topics, journal settings and `<Actions>`. This repo already had
four of these before this document existed; they were just flat files
selected by an `AMPS_CONFIG=<filename>.xml` env var:

| flow | was | is |
| --- | --- | --- |
| the baseline demo | `amps-config.xml` | `server/config/flows/default/amps-config.xml` |
| minimum footprint | `amps-config-bounded-retention.xml` | `server/config/flows/bounded-retention/amps-config.xml` |
| 500 GB/day worked case | `amps-config-market-data.xml` | `server/config/flows/market-data/amps-config.xml` |
| nightly SOW cleanup | `amps-config-maintenance.xml` | `server/config/flows/maintenance/amps-config.xml` |

**Environment** is *where it runs* — local laptop, a shared dev box, staging,
prod — and is new. It carries infrastructure concerns a business flow's XML
has no business knowing about: which host ports, which image, which
timezone the container's clock runs on, whether the platform needs emulating.

```
server/config/
├── default.env                 layer 1 -- repo defaults, every env, every flow
├── envs/
│   ├── local/local.env         layer 2 -- one instance at a time, default ports
│   ├── dev/dev.env             layer 2 -- shared box, ports moved to avoid collisions
│   ├── staging/staging.env     layer 2
│   └── prod/prod.env           layer 2
└── flows/
    ├── default/
    │   ├── amps-config.xml
    │   └── flow.env            layer 3 -- business-flow overrides (often empty)
    ├── bounded-retention/{amps-config.xml, flow.env}
    ├── market-data/{amps-config.xml, flow.env}
    └── maintenance/{amps-config.xml, flow.env}
```

Neither axis nests inside the other — `envs/` and `flows/` are two flat
lists, combined at run time by `AMPS_ENV` × `AMPS_FLOW` rather than by a
folder for every combination. Four environments and four flows is eight
folders to maintain, not sixteen.

## The four layers, in order

```
1. server/config/default.env
2. server/config/envs/<AMPS_ENV>/<AMPS_ENV>.env
3. server/config/flows/<AMPS_FLOW>/flow.env
4. server/config/envs/<AMPS_ENV>/<AMPS_FLOW>.local.env     optional, gitignored
```

Each layer overrides the same-named keys of the one before it. Layer 4 is
the *instance* override the axes' names suggest: an environment, a flow, and
one specific box running that combination — for a secret, or a port taken by
something else on this exact machine. It is never committed
(`server/config/envs/*/*.local.env` is in `.gitignore`); if it is absent,
the wrapper script skips it silently.

A variable you already exported before running the script beats every file
layer, regardless of order — the same rule `amps.sh` has always applied to
its own `${VAR:-default}` overrides. `printenv` (below) is how you check
which layer actually won.

**Worked example**, `AMPS_ENV=dev AMPS_FLOW=maintenance`:

| variable | default.env | dev.env | maintenance/flow.env | resolved |
| --- | --- | --- | --- | --- |
| `AMPS_PORT` | `9007` | `19007` | — | `19007` |
| `AMPS_TZ` | *(empty)* | `UTC` | `America/New_York` | `America/New_York` |

The port comes from the environment layer because the flow layer has no
opinion on it. The timezone is the interesting case, and it is why flow can
override environment rather than the reverse: "every night at 20:30" in
`amps-config-maintenance.xml` is a business rule about the close of a
trading day in a specific timezone, not "whatever timezone this environment
happens to run in" — so the flow's `flow.env` pins it, on top of whatever
the environment defaulted to.

### Why this is four small files instead of one generated one

Docker/Podman Compose only reads a *single* file for `${VAR}` substitution
inside the compose YAML itself (the project's `.env`, or one file via
`--env-file`) — a *list* of files under a service's `env_file:` only affects
variables injected into the *container*, not the ones compose substitutes
into `ports:`/`image:`/`volumes:` before the container exists. Fighting that
by hand-generating a merged file on every run would need its own cleanup and
staleness story for no real benefit here.

So `amps-compose.sh` does the layering itself, in its own shell process —
`source`s each layer with `set -a` (auto-exporting every assignment, so a
later `source` naturally overwrites an earlier one's value for the same
key), snapshotting and restoring whatever the operator had already exported
so real exports always win regardless of file order. By the time it invokes
`podman compose`/`docker compose`, every variable is already a real
environment variable in that process — which both mechanisms (compose-file
substitution and the container's own `environment:` block) read from the
same place, with no generated file to go stale.

Check the result before starting anything:

```bash
AMPS_ENV=dev AMPS_FLOW=maintenance ./server/scripts/amps-compose.sh printenv
```

## Why `AMPS_IMAGE` is in none of these files

There is no public AMPS server image — see `server/Containerfile` and the
`require_image` guidance in `amps.sh`. Defaulting it anywhere, even in
`envs/prod/prod.env`, would silently paper over "I forgot to set the image"
with whatever was last committed, which is a worse failure than refusing to
start. Every layer — including `prod.env` — leaves it unset; the compose
file's `${AMPS_IMAGE:?message}` and `amps-compose.sh`'s own check both fail
fast with the reason rather than falling through. A real deployment with its
own internal registry is the one place this invariant should bend, and only
by setting it explicitly in that environment's own file.

## Two scripts, two shapes of problem

`amps.sh` and `amps-compose.sh` are not the same tool wearing two skins —
they solve different problems and both stay:

| | `amps.sh` | `amps-compose.sh` |
| --- | --- | --- |
| selector | `AMPS_FLOW` only | `AMPS_ENV` × `AMPS_FLOW` |
| config source | one exported var each | four layered `.env` files |
| data directory | `server/data/` (flat, shared) | `server/data/<env>/<flow>/` (scoped) |
| concurrent instances | one at a time | one per (env, flow) pair |
| extra commands | `probe`, `modules`, `validate`, `reset` | `config`, `printenv` |

`amps.sh`'s data directory is deliberately still flat and unscoped — that
was true before this reorganisation (every flow already shared one
`server/data/`) and every doc, and `JournalLabDemo`'s own messages, point at
`server/data/sow` and `server/data/journal` as fixed paths. Changing that
was not this task; `amps.sh` still runs one flow at a time, the way it
always has, just addressed by folder (`AMPS_FLOW=market-data`) instead of
filename (`AMPS_CONFIG=amps-config-market-data.xml`).

`amps-compose.sh` is new, so it had no existing paths to preserve, and its
whole reason to exist is running more than one (env, flow) pair alongside
each other — hence the scoped data directory and the container name that
folds in both selectors (`amps-demo-dev-market-data`).

Reach for `amps.sh` for `probe`/`modules`/`validate`/`reset`, or a quick run
that does not need the layering. Reach for `amps-compose.sh` for anything
env- or flow-shaped, or for running more than one instance at once.

### Do not run `podman compose up` on `server/docker-compose.yml` directly

Every `${VAR}` in that file is resolved from the *calling shell's*
environment. `amps-compose.sh` is what populates that environment correctly,
including the two derived, unlayered values (`AMPS_CONFIG_VOLUME` and
`AMPS_DATA_VOLUME`, each a full `host:container[:z]` string — built by the
script so the SELinux `:z` suffix stays Linux-only, exactly as `amps.sh`'s
own `mount_suffix` does for its raw `podman run`). Invoked without that
setup, compose will fail on the unset `AMPS_IMAGE` guard at best, or resolve
every port to its bare default at worst.

## Adding a new environment or business flow

An environment needs one file: `server/config/envs/<name>/<name>.env`
(create it — it can be comment-only if there is nothing to override, like
`envs/local/local.env`). A business flow needs a folder with both
`amps-config.xml` and `flow.env` (same rule — empty is fine). Nothing else
has to change: both scripts discover flows and environments by listing
directories, and `amps.sh flows` / the error message from an unknown
`AMPS_ENV` or `AMPS_FLOW` both print what is actually there rather than a
list that can drift out of sync.

## Verification

```bash
./server/scripts/amps.sh flows                                     # what flows exist
AMPS_ENV=dev AMPS_FLOW=maintenance ./server/scripts/amps-compose.sh printenv   # what layering resolves to
./server/scripts/amps-compose.sh config                            # compose's own resolved YAML (needs a compose tool)
./gradlew :server:checkConfigXml                                   # every flow's XML is still well-formed
```

The layering logic above was exercised end-to-end (`printenv` across all
four environments, an operator-exported override beating every file layer,
and the "no such environment/flow" error paths) but never against a live
AMPS instance — no container runtime was available while writing this.
`amps-compose.sh start` itself needs your own podman/docker-compose
installation to confirm.
