# fix42-publisher

A Spring Boot application that publishes realistic FIX 4.2 order flow into
AMPS as **field-level deltas**, against SOW topics whose keys are computed by
the **chaining key generator** — so a cancel/replace chain collapses to one
record without the publisher tracking any chain state.

```bash
# 1. AMPS on the flow that declares the topics and loads the module
AMPS_FLOW=fix42-chaining ./server/scripts/amps.sh start

# 2. publish the scripted flow
./gradlew :fix42-publisher:bootRun
```

Then look at the result in the admin SQL console at http://127.0.0.1:8085/.

## What it demonstrates

Nine parent ClOrdIDs go in; five records come out — one per order chain, each
carrying the newest amend *and* the original order's untouched terms:

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
loads the module and declares seven topics, in pairs:

| topic | key | one record per |
| --- | --- | --- |
| `sow/parent/orders` | key-chaining `/11` + `/41` | order **chain** |
| `sow/parent/orders_audit` | `/11` | message |
| `sow/child/orders` | key-chaining `/11` + `/41` | child order **chain** |
| `sow/child/orders_audit` | `/11` | message |
| `sow/parent/execs` | `/37` | order (latest report) |
| `sow/parent/execs_audit` | `/17` | execution report |
| `sow/parent/rejects` | `/11` | rejected request |

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
  generates seven order chains covering D/G/F/8/9, both order scopes, and
  every execution outcome. Deterministic, and internally consistent by
  construction — `OrderChain` holds the economic state and derives every
  report from it, so `38 = 14 + 151` and AvgPx matching its own fills are
  properties of the generator rather than numbers someone typed.

## Parent and child orders

Tag **9000** (`ParentOrderID`, user-defined range — FIX 4.2 has no standard
parent/child field) decides the topic family: present means a child slice,
absent means a parent order.

The mock feed stamps 9000 on **every request a child chain originates** (D, G
and F), not only on the `35=D`. That is a deliberate constraint: a stateless
router cannot recover the association later, and reintroducing a chain→scope
map in the publisher would put back exactly the client-side state this design
removes. Execution reports carry no 9000 and route to the parent exec topics
regardless — a venue has no reason to echo a client's custom tag back.

## Two places this reads the spec rather than transcribing it

Both are visible in [`application.yml`](src/main/resources/application.yml) and
easy to change back if you meant the literal version:

1. **`35=G` and `35=F` route by scope, not always to `sow/parent/*`.** The spec
   lists the parent topics for D, G and F, but also declares
   `sow/child/orders{,_audit}` — which nothing would ever write to if amends
   and cancels on a child slice went to the parent topics. So the route topic
   is `sow/{scope}/orders`, resolved per message from tag 9000. For a parent
   order it *is* `sow/parent/orders`, exactly as written.

2. **Execution reports also carry tags 37 and 17.** The spec's field list for
   `35=8` names 11, 41, 39, 150, 60 (plus the per-variant economics), but the
   destination topics are keyed `/37` and `/17`, and **a SOW publish that lacks
   its key field is rejected by AMPS**. Sending the listed fields alone would
   have produced messages the server silently refuses to store. Both tags are
   in every exec route, and `Fix42Properties.validate()` fails startup if a
   route ever drops one again.

Execution reports and cancel rejects go to the parent topics regardless of
scope, as written — there are no child exec topics to route to.

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

## What this still does *not* give you

A stale or duplicate execution report merges unconditionally: nothing here
expresses "ignore this message if its CumQty went backwards". Nor do busts and
corrects, ExecID dedupe, or per-request status snapshots for two simultaneous
in-flight requests — 9012/9013 hold one. Those need conditional apply, which no
merge can express, and they are what a state machine outside AMPS is for. The
full accounting is [docs/fix42-view/](../docs/fix42-view/README.md), with the
measurements behind this section in
[04](../docs/fix42-view/04-pending-state-without-a-state-machine.md).

## Tests

```bash
./gradlew :fix42-publisher:test              # 71 unit tests, no server needed
AMPS_IMAGE=<your-image> \
  ./gradlew :fix42-publisher:integrationTest # 18 tests against a real container
```

The integration suites use **Testcontainers** to start their own AMPS instance
per class, with the config copied into the container and the data directory on
a tmpfs — so no run can inherit the last one's SOW or chain map, and there is
no host state to clean up. They **skip** rather than fail when `AMPS_IMAGE` is
unset, so `./gradlew build` stays green on a machine that has never seen AMPS.

Testcontainers needs a **Docker-API-compatible socket**. With Docker that is
automatic; with podman, either `/var/run/docker.sock` already points at the
podman socket or you export one:

```bash
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
```

Three things the harness gets right that are easy to get wrong:

- it waits for the server's `initialization completed` log line, not an open
  port. The container engine's port forwarder accepts connections as soon as
  the container exists, so a client that races it connects and is then dropped
  mid-logon;
- it binds this module's **own** `application.yml` rather than restating the
  rules, so a change to the shipped configuration is exercised;
- `AMPS_IMAGE` reaches the test JVM through a Gradle *provider*, not
  `System.getenv` at configuration time. A test task inherits the long-lived
  Gradle **daemon's** environment, so the eager form captures the value once
  and a later run with a different setting silently reuses the stale one —
  which showed up as BUILD SUCCESSFUL with every integration test skipped.

A green build is not by itself proof the integration tests ran; check for
`SKIPPED` if it matters.
