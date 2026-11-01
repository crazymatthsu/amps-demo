# fix42-order-state-publisher

The stateful successor to [`fix42-publisher`](../fix42-publisher/README.md).
It takes the same FIX 4.2 drop-copy flow and writes the same audit topics,
with a full publish on `35=D` and delta publishes after it. The difference is
who keys the order blotter, `sow/fix42/order_state`. Here the **publisher**
keys it, by tag 13011 (the order's root ClOrdID), from a small in-memory cache
holding each order's ids, acknowledged terms, venue status, cumulative
quantities, and the *set* of requests still in flight. `fix42-publisher`
leaves that to the AMPS chaining key generator module.

All six phases of [docs/05](docs/05-implementation-plan.md) are built.
[docs/11](docs/11-build-findings.md) records what building it found, and
[what is left](#status-and-what-is-left) is at the end of this page.

## What it fixes

The motivating case is two cancels for one order: the venue confirms one and
rejects the other. The stateless publisher's last-writer-wins merge leaves
that order reading cancel-rejected. This one leaves it canceled with nothing
pending, whichever answer arrives first
([docs/01](docs/01-problem-the-two-cancels.md),
[docs/03 §7.1](docs/03-state-machine.md#71-two-cancels-confirm-then-reject-the-motivating-case)).
`TwoCancelsIT` proves both arrival orders against a real AMPS.

That case is one row of a larger matrix. [docs/06](docs/06-case-coverage.md)
lists every lifecycle and edge case with the rule that handles it, and every
row has a test:

- `WalkthroughTest` checks each walkthrough in docs/03 §7, asserting the
  record after every message.
- `CoverageMatrixTest` checks the matrix rows by id.
- `EdgeCaseFlowPropertyTest` runs every scripted order under 14 venue dialects
  and checks it ends where the venue says. There is one accepted divergence,
  docs/11 F3.
  - Under the dialect whose rejects carry the status from request time, F2
    also diverges, unless the shipped hook that closes it is on.
- The integration suites run against AMPS 5.3.5.135. They cover the end state,
  the publish path, an AMPS restart, cache recovery, eviction with tombstones,
  and the exposure views.

It does not fix two cases, both open in [docs/11](docs/11-build-findings.md):
- **F4:** on a feed that merges sessions, a cancel confirm stamped earlier
  than a reject that already arrived is dropped.
- **F6:** AMPS lets a second publisher under the same name force the first
  off, so keeping a single writer needs coordination from outside.

## What it costs

These numbers were measured on 2026-11-01:
- **Hardware:** an Apple M4 Pro, with the podman machine given 7 CPUs and
  6 GiB.
- **Server:** AMPS 5.3.5.135 from its linux/amd64 image, running emulated.

They belong to that machine, so re-run them where you publish. The tests named
below pin each figure under a ceiling about 25% above it.

### Throughput

The load is `fix42-publisher`'s benchmark load: 1,500 orders of 6 messages
each (the order, its ack, a partial fill, an amend, the amend's ack, and the
final fill). Each publisher turns those 9,000 messages into 24,000 publishes:
the blotter plus the audit copies. Each time below runs to a flush that returns
once the server has everything.

| run | time | messages/s | publishes/s |
| --- | ---: | ---: | ---: |
| `fix42-publisher`, its publisher, logging at WARN | 2.4 s | 3,700 | 10,000 |
| this module's state machine alone, no server | 13 ms | 700,000 | — |
| this module, `publish-store: NONE` | 6.0 s | 1,500 | 4,000 |
| the same publishes replayed through a bare AMPS `Client` | 6.1 s | — | 3,900 |
| this module, `publish-store: MEMORY` | 7.0 s | 1,285 | 3,400 |
| this module, `publish-store: FILE` | 7.0 s | 1,285 | 3,400 |

What the table shows:

- **The cache costs nothing you can see.** The machine handles the 9,000
  messages in 13 ms, about 0.2% of a run.
- **The server sets the rate.** Replaying this publisher's own publishes
  through a bare client, with no publisher in the loop, takes as long as the
  publisher does. This flow journals all seven of its topics, because the
  publish store needs a persisted acknowledgement from every topic it writes
  to. `fix42-chaining`'s flow journals three. That is the likely reason this
  module reaches 4,000 publishes/s where `fix42-publisher` reaches 10,000,
  though the journal's cost wasn't measured separately.
- **A publish store adds about a second, at the flush.** Every run with a
  store, MEMORY or FILE, finished at 7,005 ms ±1 ms, against 6.0 s without
  one. A total that lands on the same whole second every time points to when
  the last persisted acknowledgement arrives, not to work done per publish. On
  a steady stream the store's cost is within noise, and a flush can wait up to
  a second.

Run the benchmarks yourself:

```bash
./gradlew :fix42-order-state-publisher:publishBenchmark
```

```bash
./gradlew :fix42-publisher:publishBenchmark
```

### Allocation

| path | bytes per message, once warm |
| --- | ---: |
| `fix42-publisher`, byte path | 0 |
| `fix42-publisher`, readable path | about 3,600 (its README) |
| this module, through `onMessage` with the desk-status hook, logging at WARN | 10,151 |
| this module, the state machine alone (plan, write the record, commit) | 8,801 |

Zero isn't a goal here. These figures are averaged over the scripted flow and
every edge case. The bytes go to:

- **A copy of the order's cache entry per message,** id lists included. Each
  message is planned on that copy, and the result is committed only after the
  publish (the plan-then-commit design of
  [docs/04 §6](docs/04-recovery-and-operations.md#6-the-publish-path)).
- **New strings for changed fields.** The cache keeps the venue's text, so a
  report that changes a field brings a new string.
- **New cache and index entries** when an order is created.
- **Record numbers formatted as text.**
- **The audit copies:** each audit publish copies the message and its key
  trailer into a new array.

All of it is short-lived: nothing allocated for a message outlives the
message. `PublishPathAllocationTest` pins both figures and runs as a unit test,
with no server needed.

### Memory

| what | bytes |
| --- | ---: |
| cache memory held by a typical order (the order, its ack, two fills) | 1,545 |
| cache memory held by an order amended 999 times, so 1,000 ClOrdIDs | 102,888 |
| allocation per fill, on a 1-id order | 3,840 |
| allocation per fill, on the 1,000-id order | 7,808 |

For sizing:
- **Orders:** 100,000 typical orders hold about 150 MB of cache.
- **Eviction:** the shipped default `terminal-retention: PT0S` never evicts a
  finished order. Set a retention to bound the cache; [Eviction](#eviction)
  says how it works and what it costs.
- **Long chains:** the record publishes only the newest 50 ClOrdIDs (9021),
  but the cache keeps every one, so a report quoting any of them still
  resolves. Every message also copies that list, adding 4 bytes per id to each
  message on a long chain ([docs/11 F9](docs/11-build-findings.md)).

`ChainMemoryTest` measures these with JOL, OpenJDK's object-layout tool, as a
unit test.

### Heap for a day's flow

The `scaleMemory` task measured these at 1,000,000 orders per scenario. Each
figure is the live heap after full collections: entries, both index maps and
their tables, the key set, and every string. The ids are a desk's length:
21-character ClOrdIDs and 14-character OrderIDs.

| per order | heap up to 32 GB | heap over 32 GB |
| --- | ---: | ---: |
| order kept in the cache, 1 ClOrdID, finished or open | 1,568 B | 1,942 B |
| each extra ClOrdID (an amend) | about 200 B | about 240 B |
| evicted order: its tombstone | 276 B | 359 B |

Above 32 GB, Java turns off compressed pointers, so every object reference
doubles in size.

For 50 million ClOrdIDs in a day:

| scenario | live | heap to set |
| --- | ---: | ---: |
| 50M orders of 1 ClOrdID, nothing evicted (the default) | about 97 GB | about 150 GB |
| 10M orders of 5 ClOrdIDs, nothing evicted | about 29 GB | about 45 GB |
| 50M orders of 1 ClOrdID, eviction on | about 15 GB | 24 GB |
| 10M orders of 5 ClOrdIDs, eviction on | about 5 GB | 8 GB |

"Heap to set" is live ÷ 0.65, leaving the G1 collector its usual headroom. The
eviction rows assume up to a million orders in the cache at the busiest
moment: the open orders plus those finished within the retention window.
Replace that with your own peak.

- **Avoid heaps between 32 and about 40 GB.** Losing compressed pointers costs
  about 24% per order, so a 36 GB heap holds less than a 31 GB one.
- **`-XX:+UseStringDeduplication` saves about 24% on kept orders** (1,568 →
  1,189 bytes, measured up to 32 GB) and nothing on tombstones.
- **These figures are slightly conservative at 50M.** The hash tables grow in
  powers of two and are fuller at 50 million entries than at a million. At
  200,000 orders the figures read about 4% higher.

To measure on your own JVM and ids:

```bash
./gradlew :fix42-order-state-publisher:scaleMemory
```

```bash
./gradlew :fix42-order-state-publisher:scaleMemory -PscaleJvmArgs=-XX:-UseCompressedOops
```

### Eviction

**How it works.** With `terminal-retention` above zero:
- **Scheduling.** When a message leaves an order finished (filled, canceled,
  rejected, expired), the machine schedules that order's eviction for
  retention later.
- **When it runs.** Due evictions are checked after every message and on a
  one-second timer while the feed is quiet.
- **What it removes.** At the deadline, if the order is still finished, its
  cache entry goes and its ClOrdIDs leave the index. A venue reopen in the
  meantime cancels the eviction.
- **What it keeps.** A **tombstone** stays behind: the order's key and its
  OrderIDs, bound to the key.
- **The SOW is untouched.** Eviction is cache-only; the record stays on the
  blotter, and subscribers see nothing.

**A late message for an evicted order** ([docs/04 §4](docs/04-recovery-and-operations.md#4-eviction)):
- **A venue report** (a bust, a correction, a restatement) carries its 37 and
  resolves through the tombstone. The publisher flushes, reads the record back
  with one keyed `sow` query, rebuilds the entry from it, applies the report,
  and schedules eviction again.
- **A late request under a new ClOrdID** names the old one in 41, and that id
  is found through `orders_audit`.

**What it costs, and where it can hurt:**

| issue | effect | what to do |
| --- | --- | --- |
| tombstones are never removed ([docs/11 F10](docs/11-build-findings.md)) | 276 bytes per evicted order for the rest of the day: about 14 GB at 50M orders, the bulk of the 24 GB plan | size for it; the daily restart clears it |
| a late message costs a round trip | the publishing thread waits for every publish in flight to be acknowledged, then for the query, so a burst of busts after eviction stalls the feed for as long as those round trips take (not measured under load) | keep the retention longer than your venues' usual delay for busts and corrections |
| the rebuilt entry has lost its ExecID ring | duplicate detection is one deep for the first reports after a rebuild; the CumQty and TransactTime guards still apply, and busted ExecIDs (9031) are recovered from the record | nothing; stated for completeness |
| eviction with `miss-lookup: false` ([docs/11 F11](docs/11-build-findings.md)) | the record can't be read back, so a late venue report builds a mid-stream record from itself and delta-publishes it over the real one, overwriting the chain list (9021, 9030), the message count (9023) and the origin (9018); nothing refuses this configuration yet | always run eviction with `miss-lookup: true` |
| orders loaded at startup are never scheduled ([docs/11 F12](docs/11-build-findings.md)) | with `recovery: SOW` and `load-open-orders-only: false`, a restart puts every finished order back in the cache in full, and none of them is evicted | with eviction on, set `load-open-orders-only: true`, which loads finished orders as tombstones |

## Run it

```bash
export AMPS_IMAGE=localhost/amps-demo:5.3.5.135
```

```bash
AMPS_FLOW=fix42-order-state ./server/scripts/amps.sh start
```

```bash
./gradlew :fix42-order-state-publisher:bootRun
```

`bootRun` uses the `demo` profile, which keeps the publish store in memory and
runs in strict mode. It publishes the scripted flow, then every coverage-matrix
case. Point it at another server with
`-Dfix42-order-state.amps.uri=tcp://host:9007/amps/fix`.

## Configuration

[`application.yml`](src/main/resources/application.yml) documents every key;
its defaults are production's. The ones that change behaviour most:

| key under `fix42-order-state.` | default | what it decides |
| --- | --- | --- |
| `amps.publish-store` | `FILE` | `FILE` survives a crash, `MEMORY` survives a reconnect, `NONE` is at most once ([docs/04 §6](docs/04-recovery-and-operations.md#6-the-publish-path)) |
| `mode` | `lenient` | an unrouted or unparseable message goes to `fix42/unrouted` or `fix42/malformed`; `strict` raises instead |
| `order-state.recovery` | `SOW` | rebuild the cache from the blotter at startup, or start empty (`NONE`) |
| `order-state.miss-lookup` | `true` | before creating a mid-stream order, look an unknown id up on the audit topics |
| `order-state.terminal-retention` | `PT0S` | how long a finished order stays in the cache; `PT0S` keeps it forever |
| `order-state.cancel-reject-status` and the other policies | per venue | what a reject's 39, a late fill or a late report may do ([docs/07 §1](docs/07-extension-points.md#1-tier-one-policies)) |
| `hooks.desk-status` | `false` | a one-word desk status in tag 9100 |
| `hooks.reject-status-at-request-time` | `false` | for a venue whose rejects carry the request-time status (docs/11 F2) |

Custom behaviour goes in hooks: Spring beans implementing `OrderStateHook`,
writing in tags 9100–9999 ([docs/07 §2](docs/07-extension-points.md#2-tier-two-hooks)).

## Tests

```bash
./gradlew :fix42-order-state-publisher:test
```

That runs about 2,600 unit tests with no server. 2,358 of them are
`EdgeCaseFlowTest`'s checks of the mock venue itself.

```bash
AMPS_IMAGE=localhost/amps-demo:5.3.5.135 ./gradlew :fix42-order-state-publisher:integrationTest
```

That runs 35 tests against a real AMPS, each class on its own throwaway
container. Without `AMPS_IMAGE` they skip, which is what CI does.
`checkModuleDocs` fails the build if a relative link in this README or in
`docs/` points at nothing.

## Status and what is left

Built: phases 0–5 of [docs/05](docs/05-implementation-plan.md). That covers
the module and its mock venue, the state machine, the server flow and
publisher, recovery, cache misses, eviction, and the measurements above.

Not built yet:
- the QuickFIX/J `FeedAdapter`, and a measured bound on what a `MEMORY` store
  loses in a crash;
- the daily reset as a script;
- `StoreCrashIT`, a crash test for the `FILE` store;
- `recovery: JOURNAL`, rebuilding the cache from the transaction log;
- a server-side index on `/37`, so `miss-lookup-by-order-id` stops scanning
  the blotter.

Open for the design owner: F4, F6 and F9 in
[docs/11](docs/11-build-findings.md).

## Documents

| document | what it covers |
| --- | --- |
| [docs/01](docs/01-problem-the-two-cancels.md) | the failure, traced through the shipped rulebook; the class of edge cases it belongs to |
| [docs/02](docs/02-architecture.md) | topology, the cache, the record schema, publish modes, server flow, configuration |
| [docs/03](docs/03-state-machine.md) | the rules, and the walkthroughs showing the record after every message |
| [docs/04](docs/04-recovery-and-operations.md) | restart, cache misses, eviction, single writer, metrics, failure matrix |
| [docs/05](docs/05-implementation-plan.md) | the six phases, class layout, decisions, risks |
| [docs/06](docs/06-case-coverage.md) | the case matrix: every lifecycle and edge case, the rule that handles it, and a verdict |
| [docs/07](docs/07-extension-points.md) | policies per venue, the hook SPI, replaceable components |
| [docs/08](docs/08-review-findings.md) | round-1 review findings, the six decisions taken on them, where each landed |
| [docs/09](docs/09-review-round-2.md) | round-2 review of the revised docs: round-1 dispositions verified, twenty-two new findings and where each landed |
| [docs/10](docs/10-case-frequency-and-priority.md) | every case sorted by frequency (normal, sometimes, rare) and build slot, for implementation order |
| [docs/11](docs/11-build-findings.md) | what the build found: findings on the rules, on AMPS 5.3.5.135, on recovery and from the measurements, the verify-on-build answers, and what is not built yet |
