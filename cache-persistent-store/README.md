# cache-persistent-store

A small cache library: a local `java.util.Map` in front of
[60East AMPS](https://www.crankuptheamps.com/) acting as the **remote,
distributed persistent store**. If the process restarts — or fails over to a
different machine — the new process hydrates its cache from AMPS and carries
on. If the local cache doesn't have a key, it asks AMPS on demand.

This module implements [TODO.md](TODO.md):

| requirement | where it landed |
| --- | --- |
| simple Java `Map` as cache | [`PersistentCacheMap`](src/main/java/com/demo/amps/cache/PersistentCacheMap.java) implements `Map<String, V>` |
| support `Map<String, ?>` | `AmpsMapStore.untyped(...)` — values are anything JSON carries |
| support `Map<String, Map<String, ?>>` | [`NestedCacheMap`](src/main/java/com/demo/amps/cache/NestedCacheMap.java) + two representations, below |
| MapLoader: hydrate at startup | [`MapLoader`](src/main/java/com/demo/amps/cache/MapLoader.java) SPI; `PersistentCacheMap.hydrate(store)` |
| MapStore: persist to an AMPS SOW topic | [`MapStore`](src/main/java/com/demo/amps/cache/MapStore.java) SPI; [`AmpsMapStore`](src/main/java/com/demo/amps/cache/AmpsMapStore.java) |
| JSON on the wire | Gson; envelopes below |
| SOW topics as the key/value store | [`server/config/flows/cache/amps-config.xml`](../server/config/flows/cache/amps-config.xml) |
| map-of-map proposal + alternatives | [the analysis below](#the-map-of-maps-question) |
| amps-config example | same flow file — `./server/scripts/amps.sh validate cache` accepts it |
| script to run AMPS in podman | [`scripts/amps-cache.sh`](scripts/amps-cache.sh) (pins `AMPS_FLOW=cache` onto the standard `amps.sh`) |
| integration test, query-back, restart recovery | [`CachePersistentStoreIT`](src/integrationTest/java/com/demo/amps/cache/it/CachePersistentStoreIT.java) |

## Why AMPS needs no help to be a key/value store

A SOW topic *is* a server-maintained map: last value per key, queryable at any
time, persistent on disk if you say so. So the whole integration is choosing
keys and message shapes — there is no serialization framework, no compaction
to wait for, and `store()` is a plain publish, because on a SOW topic **a
publish is an upsert**.

The flat cache uses one record per entry on `cache.entries`, keyed
`<Key>/key</Key>`:

```json
{"key": "user-42", "value": {"name": "Ada", "level": 7}}
```

The `value` member is an envelope on purpose: a value can be a JSON object,
but also a bare string or number, which would have no field to carry the key.
The envelope gives every value shape the same address (`/value`) for content
filters, and the key field doubles as the SOW key so the server enforces
last-value-per-key.

Operations map one-to-one onto AMPS commands:

| cache operation | AMPS command |
| --- | --- |
| `put` / `putAll` | `publish` (+ `publishFlush` barrier) |
| hydrate at startup | `sow` query, no filter |
| `get` on local miss | `sow` query, `/key = '...'` |
| `remove` | `sow_delete` **by data** — the server recomputes the key from the message, so no filter quoting is involved |
| `clear` | `sow_delete` by filter `1=1` |

## The map-of-maps question

`Map<String, Map<String, ?>>` is the interesting one, because a SOW record has
exactly one key. Three honest options:

**Option A — nest it: one record per outer key, the inner map as the value.**
This is just the flat cache with map-valued entries
(`AmpsMapStore.ofMaps(...)`); it needs nothing new.

```json
{"key": "portfolio-1", "value": {"AAPL": {"qty": 250}, "MSFT": {"qty": 100}}}
```

- ✅ one atomic record per outer map: replace it and readers see old or new,
  never a mixture
- ✅ hydration is trivial; record count = outer keys
- ❌ changing one inner entry republishes (and journals) the whole outer map —
  the record grows with the inner map, and so does every update
- ❌ two processes updating *different* inner entries of the same outer key
  race: read-modify-write, last writer silently drops the other's change
- ❌ filters must address inner values through paths that embed the inner key
  (`/value/AAPL/qty`), so "any position over 1000" is not one filter

**Option B — flatten it: one record per (outerKey, innerKey) pair,** on a
**composite SOW key**. This is the **proposed** solution and what
[`AmpsNestedMapStore`](src/main/java/com/demo/amps/cache/AmpsNestedMapStore.java)
implements, on `cache.nested.entries` with `<Key>/outerKey</Key><Key>/innerKey</Key>`:

```json
{"outerKey": "portfolio-1", "innerKey": "AAPL", "value": {"qty": 250, "px": 187.5}}
```

- ✅ updating one inner entry is one small record — no read-modify-write, and
  concurrent writers to different inner entries cannot overwrite each other
  (the integration test pins this: the sibling record stays byte-identical)
- ✅ the journal records what changed, not the outer map re-serialized around it
- ✅ one inner map is a server-side query (`/outerKey = 'portfolio-1'`); inner
  values sit at a fixed depth, so `/value/qty > 1000` works across all keys
- ❌ an outer map is no longer atomic: replacing one is several
  publishes/deletes and a reader can see it mid-replace
- ❌ record count = total inner entries; "empty inner map" cannot be
  represented (no records ⇒ absent — the library folds the two together)

**Pick by write pattern.** Fine-grained updates and multiple writers →
Option B. Small inner maps replaced wholesale, atomicity first → Option A.
Both are live in this module; `NestedCacheMap` exposes B's granularity as
first-class `putEntry`/`removeEntry` operations.

**Alternatives considered and set aside:**

- *A dynamic SOW topic per outer key* (`<Pattern>^cache\.map\..*$</Pattern>`,
  keyed on `/innerKey` — the technique the default flow's `desk` topic
  demonstrates). Real per-outer-key subscription granularity, but hydration
  becomes topic discovery, and an unbounded outer-key set means an unbounded
  topic set. Reasonable when outer keys are few and long-lived (per-desk,
  per-region); wrong for arbitrary map keys.
- *Option A + `delta_publish`* to merge inner-entry changes into the nested
  record server-side. Cuts the republish cost, but the delta message still
  addresses fields under `/value/<innerKey>/...`, concurrent same-record
  deltas still interleave field-by-field, and inner-entry *deletion* is not
  expressible as a merge. Option B gets all of that from the key design
  instead of the merge engine.

## Semantics, stated exactly

- **Hydration**: `PersistentCacheMap.hydrate(store)` /
  `NestedCacheMap.hydrate(store)` load everything before returning.
- **Write-through, store first**: every mutation hits AMPS before the local
  map; if AMPS fails, the local map is untouched and the exception propagates.
- **Read-through**: `get` is the only operation that reaches past the local
  map. `size`, `containsKey`, iteration and the views describe the **local
  replica** — cheap, never blocking on the network.
- **Read-your-writes across processes**: every write ends with a server
  round trip (`publishFlush`, or the delete acknowledgement), so when `put`
  returns, a hydrating process elsewhere sees it. The cost is a round trip
  per mutating call; `putAll` pays it once per batch.
- **No nulls**, like `ConcurrentHashMap`. `null` from `get` means absent.
- **Views are read-only** — a mutation smuggled through an iterator would
  bypass write-through.
- **Key constraint**: AMPS filter literals have no quote-escape, so a key may
  contain `'` or `"` but not both; such keys are rejected at `put` time
  (single-entry `remove` doesn't need filters — delete-by-data — but `get`
  on a miss does).
- **Not a coherence protocol**: another process's writes appear on `get` of a
  locally-absent key, on `refresh()`, or on restart — not spontaneously. The
  natural extension is `sow_and_subscribe` (atomic snapshot + live updates on
  one command), which would make the local map continuously coherent; it is
  left out to keep this the smallest honest version of the idea.

## Running it

There is no public AMPS image — build one from a 60East release tarball first
(see the [repository README](../README.md)), then:

```bash
export AMPS_IMAGE=amps-demo:5.3          # your image tag

./cache-persistent-store/scripts/amps-cache.sh start    # AMPS in podman, cache flow
./gradlew :cache-persistent-store:run                   # the guided demo
./cache-persistent-store/scripts/amps-cache.sh restart  # then run the demo again:
                                                        # the SOW survived
./cache-persistent-store/scripts/amps-cache.sh stop
```

The demo writes through both cache shapes, dumps the raw SOW records, builds
fresh instances that hydrate from AMPS alone, evicts a key to force a
read-through, and updates one inner entry of a nested map without touching its
siblings. `SELECT * FROM "cache.entries"` in the admin UI
(http://localhost:8085) shows the store live.

## Tests

```bash
./gradlew :cache-persistent-store:test              # unit: cache semantics, JSON
                                                    # round-trips, filter quoting
AMPS_IMAGE=amps-demo:5.3 \
./gradlew :cache-persistent-store:integrationTest   # starts a container on the cache
                                                    # flow; skipped without an image
```

The integration suite covers the TODO's demo-testing list: records verified by
querying AMPS directly, read-through of a key another client wrote, a fresh
cache instance recovering the full map (the process-restart story), and — last
— a **server** restart after which everything hydrates from the recovered SOW.
It also pins two things worth pinning: integral values hydrate as `Long`
(not `42.0`), and updating one nested inner entry leaves its sibling record
byte-identical.
