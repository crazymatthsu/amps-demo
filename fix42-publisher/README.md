# fix42-publisher

A Spring Boot application that publishes realistic FIX 4.2 order flow into
AMPS as **field-level deltas**, against SOW topics whose keys are computed by
the **chaining key generator** — so a cancel/replace chain collapses to one
record without the publisher tracking any chain state.

```bash
# 0. local image 
export AMPS_IMAGE=localhost/amps-demo:5.3.5.135

# 1. AMPS on the flow that declares the topics and loads the module
AMPS_FLOW=fix42-chaining ./server/scripts/amps.sh start

# 2. publish the scripted flow
./gradlew :fix42-publisher:bootRun
```

Then look at the result in the admin SQL console at http://127.0.0.1:8085/.

## What it demonstrates

Eleven parent ClOrdIDs go in; seven records come out — one per order chain,
each carrying the newest amend *and* the original order's untouched terms:

```
35=G|11=PARENT-AAPL-2|41=PARENT-AAPL-1|60=…|38=12000|44=185.75|59=0|      <- from the amend
     1=ACC-INSTL-01|109=TRADER-AH|21=1|55=AAPL|48=US0378331005|22=4|
     54=1|40=2|15=USD|100=XNAS|110=0|111=1000|47=A|                        <- still from the 35=D
```

The amend published seven tags. Everything else in that record has been there
since the `35=D` and was never re-sent — that is the delta merge, and the
single record is the chaining key generator resolving `41 → 11`.

## The two halves

**Server** — [`server/config/flows/fix42-chaining/amps-config.xml`](../server/config/flows/fix42-chaining/amps-config.xml)
loads the module and declares five topics, in pairs, and three views above
them:

| topic | key | one record per |
| --- | --- | --- |
| `sow/fix42/orders` | key-chaining `/11` + `/41` | order **chain** (parent or child) |
| `sow/fix42/orders_audit` | `/11` | message |
| `sow/fix42/execs` | `/37` | order (latest report) |
| `sow/fix42/execs_audit` | `/17` | execution report |
| `sow/fix42/rejects` | `/11` | rejected request |
| `view/fix42/exposure/parent` | view over `sow/fix42/orders`, `/9000 IS NULL` | account × symbol × side, parent orders |
| `view/fix42/exposure/child` | view over `sow/fix42/orders`, `/9000 IS NOT NULL` | account × symbol × side, child slices |
| `view/fix42/exposure/children_by_parent` | view over `sow/fix42/orders`, grouped `/9000` | parent ClOrdID: its slices rolled up |

Every stream is a chained/derived topic **paired with an audit topic**, and
only the audit topics are journalled. The pairing is not decoration: per the
AMPS user guide the chaining module drops a message that resolves to two
different chains ("the module will not generate a SOW key, and the message is
not processed by AMPS"). Acceptable for a derived blotter, not for an audit
trail — so nothing is published only to a chained topic.

**Client** — this module. Three pieces worth knowing:

- [`application.yml`](src/main/resources/application.yml) is the rulebook.
  Which tags leave the process is configuration: a route says "for this
  message type, extract these fields, send them to these topics".
  `changeable-tags` is the knob a desk actually turns — "what may an amend
  alter?" Adding MinQty to that list is a config edit, not a recompile.
- [`PublishPlanner`](src/main/java/com/demo/amps/fix42/publish/PublishPlanner.java)
  is **stateless**. It never remembers that `PARENT-AAPL-3` continues
  `PARENT-AAPL-1`; it sends tags 11 and 41 and lets the server resolve the
  record. That is the whole point of delegating identity to AMPS.
- [`MockFixFlow`](src/main/java/com/demo/amps/fix42/mock/MockFixFlow.java)
  generates nine order chains covering D/G/F/8/9, both order scopes, and
  every execution outcome — trade busts and corrects (ExecTransType 20=1/2)
  included. Deterministic, and internally consistent by construction —
  `OrderChain` holds the economic state and derives every report from it, so
  `38 = 14 + 151` and AvgPx matching its own fills are properties of the
  generator rather than numbers someone typed.

## Parent and child orders

Tag **9000** (`ParentOrderID`, user-defined range — FIX 4.2 has no standard
parent/child field) decides the scope: present means a child slice, absent
means a parent order. The publisher reads it per message, with no chain
memory, and a topic pattern written as `sow/{scope}/orders` resolves to
`sow/parent/orders` or `sow/child/orders` accordingly.

The shipped rulebook does not use the placeholder. Parents and children share
`sow/fix42/orders`, and the chaining module keeps them apart on its own: a
child's first message carries no 41 pointing at the parent, so it opens a
chain of its own. `/9000 = 'PARENT-TSLA-1'` is how a filter walks from a
parent to its slices. Splitting the blotter again is a config change — declare
`sow/parent/*` and `sow/child/*` on the server, list them under `topic-keys`,
and write `sow/{scope}/orders` in the routes.

The mock feed stamps 9000 on **every request a child chain originates** (D, G
and F), not only on the `35=D`, and on the venue's reports for it too. On one
shared blotter that is a courtesy; with a scoped blotter it is a hard
dependency, because a stateless router cannot recover the association later
and an execution report has to reach the same blotter its request went to.

## Two places this reads the spec rather than transcribing it

Both are visible in [`application.yml`](src/main/resources/application.yml) and
easy to change back if you meant the literal version:

1. **One topic family, `sow/fix42/*`, instead of `sow/parent/*` and
   `sow/child/*`.** The spec declares the orders and audit topics twice, once
   per scope. Both copies are keyed the same way, and the chaining module
   already keeps a child's chain separate from its parent's, so the split
   bought nothing but a second set of SOW files and a dependency on execution
   reports echoing tag 9000. Five topics do the same job as seven; the
   `{scope}` placeholder stays available for a deployment that wants two.

2. **Execution reports also carry tags 37 and 17.** The spec's field list for
   `35=8` names 11, 41, 39, 150, 60 (plus the per-variant economics), but the
   destination topics are keyed `/37` and `/17`, and **a SOW publish that lacks
   its key field is rejected by AMPS**. Sending the listed fields alone would
   have produced messages the server silently refuses to store. Both tags are
   in every exec route, and `Fix42Properties.validate()` fails startup if a
   route ever drops one again.

## Acked terms vs terms in flight

FIX 4.2 stages an amend's terms until the venue confirms, and a delta merge has
no notion of staging — so writing a `35=G`'s quantity into tag 38 destroys the
quantity the venue acked, with no way back (a merge overwrites, it never
removes). The publisher avoids the conflict instead of repairing it: proposed
terms go to their own tags, and 38/44 are written only by the venue's own
reports.

```
in flight:  acked qty=9000  px=55.25   |  pending REPLACE qty=15000 px=55.80
rejected:   acked qty=4000  px=121.80  |  pending NONE      <- nothing to revert
confirmed:  acked qty=5000  px=121.95  |  pending NONE      <- venue's own numbers
```

| tag | holds |
| --- | --- |
| 38 / 44 | acked terms, written only by execution reports |
| 9010 / 9011 | proposed terms of an in-flight amend |
| 9012 | ClOrdID of the in-flight request |
| 9013 | `NONE` / `NEW` / `REPLACE` / `CANCEL` |
| 9014 | working ClOrdID — the id the venue currently recognises |

It needs no memory because of two things: a `150=5` carries the venue's own
38/44, so the publisher never has to recall what it asked for; and "leave
unchanged" is free in a delta merge, so a reject clears the proposal by simply
not publishing tag 9014.

**One hard constraint:** tags 11 and 41 must pass through a projection
untouched. They are the chaining key generator's own inputs, and rewriting
either hides the linkage — an early version of this made tag 11 mean "working
id" and orders silently split into two records. Anything else the blotter wants
to say about identity needs its own tag, which is what 9014 is for.

## Busts and corrects

A FIX 4.2 trade bust (`20=1`) or trade correct (`20=2`) carries an ordinary
tag 150 mirroring the restated status — the semantics ride on ExecTransType,
so the `exec-bust`/`exec-correct` routes match on tag 20 and are declared
ahead of the fill routes that would otherwise swallow them. The venue restates
`14/151/6/39` as absolutes, which is exactly what lets the blotter adopt them:
the same merge that applied a fill applies its reversal, no state machine
required at order level. Tags 19/20 ride to the exec topics so a reader can
tell which execution was undone; the blotter projection deliberately omits
them (they describe a *prior* execution and would sit stale on the merged
record) along with 31/32 on a bust (a bust reports no new trade). The change
record, with the worked SOW examples, is
[docs/fix42-view/07](../docs/fix42-view/07-trade-busts-and-corrects.md).

## Exposure views

The three `view/fix42/exposure/*` views are json-typed aggregations of the
blotter by account (1), symbol (55) and side (54): `Orders` (`COUNT(/11)`),
`OrderQty`, `LeavesQty`, `CumQty` (`SUM` of 38/151/14) and `AvgPx` as the VWAP
`SUM(/14 * /6) / SUM(/14)`. They read the blotter rather than the execs topics
because the blotter record already carries the account/symbol/side of the
`35=D` beside the venue's restated absolutes, so a bust or correct is absorbed
before a view sees it. Parents and children are split on tag 9000 and never
summed together — a child's fills are already inside its parent's 14/151 —
and `children_by_parent` rolls the slices up under their parent's ClOrdID as
the consistency check against the parent's own record. Being json, the
views are read over `/amps/json`, on a separate connection from the fix-typed
blotter.

```
view/fix42/exposure/parent   {"Account":"ACC-INSTL-02","AvgPx":242.0931,"CumQty":16000.0,"LeavesQty":0.0,
                              "OrderQty":20000.0,"Orders":1,"Side":"1","Symbol":"TSLA"}
view/fix42/exposure/child    {"Account":"ACC-INSTL-02","AvgPx":242.0931,"CumQty":16000.0,"LeavesQty":0.0,
                              "OrderQty":20000.0,"Orders":2,"Side":"1","Symbol":"TSLA"}
```

(As the server sends them: a `SUM` over the fix topic's text values comes back
as a double, `COUNT` as an integer, and the grouping fields as the strings
they were.)

Two things about them are not obvious from the vendor documentation and are
recorded in [docs/fix42-view/08](../docs/fix42-view/08-exposure-views.md): a
json view over a fix topic must qualify every reference as
`[fix].[sow/fix42/orders]./14`, and on this AMPS build an aggregate nested in
`IF()` drifts on live updates and only corrects itself on restart — so the
aggregates are bare, an unfilled group's `AvgPx` is simply `null`, and
`ExposureViewIT` asserts the values without restarting the container.

## What this still does *not* give you

A stale or duplicate execution report merges unconditionally: nothing here
expresses "ignore this message if its CumQty went backwards". Nor do
per-execution disposition (marking the ExecID named by tag 19 as BUSTED on the
execs topics would rewrite a *different* record than the one being published),
ExecID dedupe, or per-request status snapshots for two simultaneous in-flight
requests — 9012/9013 hold one. Those need conditional apply, which no merge
can express, and they are what a state machine outside AMPS is for. The full
accounting is [docs/fix42-view/](../docs/fix42-view/README.md), with the
measurements behind this section in
[04](../docs/fix42-view/04-pending-state-without-a-state-machine.md).

## Tests

```bash
./gradlew :fix42-publisher:test              # 102 unit tests, no server needed
AMPS_IMAGE=<your-image> \
  ./gradlew :fix42-publisher:integrationTest # 38 tests against a real container
```

Each integration test class starts its own AMPS instance. They **skip** rather
than fail when `AMPS_IMAGE` is unset, so `./gradlew build` stays green on a
machine that has never seen AMPS.

### Two harnesses, one switch

`AMPS_TEST_HARNESS` picks how the container is started. Both run the same
tests and expose the same `AmpsTestServer` surface:

| value | backend | needs | good for |
| --- | --- | --- | --- |
| `cli` *(default)* — aliases `podman`, `docker` | runs `podman run` as a subprocess | the engine binary on `PATH` | a laptop: nothing to set up beyond `AMPS_IMAGE` |
| `testcontainers` — alias `tc` | the engine's Docker API | a Docker-API-compatible socket | CI: the socket is there by default, and the reaper cleans up after a killed JVM |

An unrecognised value fails loudly rather than falling back — silently running
the other backend would be the same class of mistake as a stale cached result.

```bash
# default: podman directly, no socket needed
AMPS_IMAGE=<your-image> ./gradlew :fix42-publisher:integrationTest

# via the Docker API instead
AMPS_IMAGE=<your-image> AMPS_TEST_HARNESS=testcontainers \
  ./gradlew :fix42-publisher:integrationTest
```

The `cli` backend takes `CONTAINER_ENGINE` (default `podman`) and
`AMPS_PLATFORM` (default `linux/amd64` — the AMPS image is amd64, so an Apple
Silicon machine emulates it). Its data directory is a fresh
`build/fix42-it/<name>` per instance, which `gradle clean` disposes of.

The `testcontainers` backend needs a **Docker-API-compatible socket**. With
Docker that is automatic; with podman, either `/var/run/docker.sock` already
points at the podman socket or you export one:

```bash
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
```

It copies the config into the container and leaves the data directory in the
container's own writable layer, so no run inherits the last one's SOW or chain
map (every run builds a new container), state still survives a deliberate
`restart()`, and there is no host state to clean up. In CI also set
`TESTCONTAINERS_RYUK_DISABLED=true`: the runner is ephemeral so there is
nothing to reap, and it avoids pulling `testcontainers/ryuk` from Docker Hub,
whose anonymous rate limits are shared across runner IPs.

### Three things both harnesses get right that are easy to get wrong

- it waits for the server's `initialization completed` log line, not an open
  port. The container engine's port forwarder accepts connections as soon as
  the container exists, so a client that races it connects and is then dropped
  mid-logon;
- it binds this module's **own** `application.yml` rather than restating the
  rules, so a change to the shipped configuration is exercised;
- `AMPS_IMAGE` and `AMPS_TEST_HARNESS` are declared `inputs.property` of the
  task, not merely forwarded to it. This repo sets `org.gradle.caching=true`,
  and a variable that is only forwarded forms no part of the **build cache
  key** — so a run without the image caches an all-skipped result and a later
  run *with* it restores that entry instead of executing, reporting
  `FROM-CACHE` and BUILD SUCCESSFUL with every integration test silently
  skipped. The same trap would otherwise let a `cli` result be restored for a
  run that asked for `testcontainers`.

A green build is not by itself proof the integration tests ran; check for
`SKIPPED` if it matters.
