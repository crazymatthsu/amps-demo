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

## Component architecture

```
 application code
    │ java.util.Map<String, V>                │ Map<String, Map<String, V>>
    │ put · get · remove · putAll · clear     │ + putEntry · removeEntry · getEntry
┌───▼─────────────────────────────┐  ┌────────▼──────────────────────────────┐
│ PersistentCacheMap<V>           │  │ NestedCacheMap<V>                     │
│   local ConcurrentHashMap =     │  │   local map of IMMUTABLE inner-map    │
│   the read replica              │  │   snapshots, replaced whole on write  │
│   hydrate at construction       │  │   empty inner map ⇒ absent outer key  │
│   get() = the one read-through  │  │   fine-grained entry ops first-class  │
│   every write store-FIRST       │  │                                       │
└───┬─────────────────────────────┘  └────────┬──────────────────────────────┘
    │ MapStore<V> SPI                         │ NestedMapStore<V> SPI
    │ (MapLoader<V> is its read half)         │
┌───▼─────────────────────────────┐  ┌────────▼──────────────────────────────┐
│ AmpsMapStore<V>                 │  │ AmpsNestedMapStore<V>                 │
│   {"key": …, "value": …}        │  │   {"outerKey": …, "innerKey": …,      │
│   ONE record per cache entry    │  │    "value": …}                        │
│                                 │  │   ONE record per (outer, inner) PAIR  │
└───┬─────────────────────────────┘  └────────┬──────────────────────────────┘
    └────────────────┬────────────────────────┘
                     │  shared plumbing (public: hazelcast-persistent-store
                     │  builds its tier store on exactly these classes)
                     ├─ AmpsFilters   filter literals, quote rules, expressibility
                     ├─ JsonValues    the one Gson: LONG_OR_DOUBLE, no HTML escaping
                     └─ SowOps        publish (+ flush barrier) · sow query ·
                                      sow_delete by data · sow_delete by filter
                     │
                     │  com.crankuptheamps Client — INJECTED. The library opens
                     │  no connections; callers choose plain Client, HAClient,
                     │  publish stores (see common/AmpsConnections).
                     │  tcp://…/amps/json
┌────────────────────▼──────────── AMPS (flow `cache`) ───────────────────────┐
│  cache.entries            <Key>/key</Key>                                   │
│  cache.nested.entries     <Key>/outerKey</Key><Key>/innerKey</Key>          │
│  Durability persistent · both journalled · ./sow/%n.sow                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

The division of labor, stated once:

- **The cache classes own the `Map` contract**: the local replica, hydration,
  the store-first write ordering, read-through on `get`, the read-only views.
  They never build JSON or filters.
- **The store classes own the wire**: record shapes, which AMPS command each
  SPI call becomes, and the key rules. They hold no cache state at all — a
  store is safely shared by any number of cache instances (that is what the
  restart tests do).
- **AMPS owns identity and last-value-per-key**: the SOW key means a publish
  *is* an upsert, so `store()` never needs read-modify-write; declaring the
  topic is the entire server-side integration.

## Data flow, path by path

Every cache operation reduces to at most a couple of AMPS commands:

| operation | local replica effect | AMPS command(s) |
| --- | --- | --- |
| `hydrate(store)` (construction) | fills the map | `sow`, no filter |
| `get` — local hit | — | — |
| `get` — local miss | caches a hit | `sow` filter `/key = 'k'` |
| `put` | updated after the store returns | `publish` + `publish_flush` barrier |
| `putAll` | updated after the batch | N × `publish` + ONE `publish_flush` |
| `remove` | removed after the delete | `sow_delete` **by data** `{"key"}` |
| `clear` | cleared after | `sow_delete` by filter `1=1` |
| `evictLocal` | local only — store untouched | — |
| `refresh` | replaced from the store | `sow`, no filter |
| `putEntry(o, i, v)` (nested) | copy-on-write inner snapshot | `publish` one record + barrier |
| `removeEntry(o, i)` (nested) | snapshot shrinks; empty ⇒ outer gone | `sow_delete` by data `{"outerKey","innerKey"}` |
| `put(o, wholeMap)` (nested) | snapshot replaced | N × `publish` + barrier, then per-stale `sow_delete` by data |
| `remove(o)` (nested) | outer key removed | `sow_delete` by filter `/outerKey = 'o'` |
| `get(o)` — nested miss | snapshot cached | `sow` filter `/outerKey = 'o'` |

### Write-through put

```
put(k, v) ─► store.store(k, v) ─► checkKey ─► Gson encode ─► {"key","value"} envelope
                                                                    │
                                                        publish ─► publish_flush
                                                                    │ barrier: AMPS
                                                                    │ has PROCESSED it
              local ConcurrentHashMap update ◄── only after ◄───────┘
```

1. `checkKey` rejects the one impossible key shape (both quote characters —
   no filter literal could ever name it) *before anything leaves the JVM*.
2. The value is encoded and wrapped; the envelope's `key` field is also the
   SOW key, so the server enforces last-value-per-key — the publish is the
   upsert.
3. `publish_flush` returns only when AMPS has processed the message. AMPS
   publishes are otherwise asynchronous; the barrier is what makes a
   completed `put` immediately visible to a *different* process hydrating or
   reading through — read-your-writes across processes, at the cost of one
   round trip per mutating call (batches pay it once).
4. The local map updates last. If the store throws, the local map is
   untouched and the exception propagates — local and remote never disagree
   about a write that "worked".

### Hydration and read-through

```
hydrate(store):  sow cache.entries (no filter) ─► decode each record ─► local map ─► ready

get(k):  local hit ──────────────────────────────────────────────────► return
         local miss ─► sow /key = 'k' ─► 0..1 record ─► putIfAbsent ─► return
```

`get` is the **only** operation that reaches past the local replica.
`size`, `containsKey`, iteration and the views all describe local state, so
the Map surface stays cheap and never blocks on the network — the rule is
crisp enough to rely on: *miss ⇒ one filtered query; everything else ⇒
memory*. The read-through result lands via `putIfAbsent`, so a concurrent
`put` (which is newer than what the query returned) wins the race. A miss on
both sides is answered `null` and cached nowhere — there is no negative
cache, and the next `get` asks again.

`refresh()` re-runs the hydration query and then drops local keys the store
no longer has — the manual way to pick up another process's writes and
deletes without a restart.

### Deletes: two shapes on purpose

```
remove(k)          ─► sow_delete BY DATA   {"key": "k"}
removeEntry(o, i)  ─► sow_delete BY DATA   {"outerKey": "o", "innerKey": "i"}

remove(o) (nested) ─► sow_delete BY FILTER /outerKey = 'o'
clear()            ─► sow_delete BY FILTER 1=1
```

Single-record deletes go **by data**: the client sends just the key fields
and AMPS recomputes the SOW key from the message exactly as it did on
publish — no filter expression exists, so no quoting rules apply, and the
delete is exact by construction. Group deletes (an outer key's records,
everything) are the filter's job. Deletes are naturally synchronous — the
acknowledgement is the barrier — and deleting an absent key is a no-op on
both sides, matching the `MapStore` contract.

### The nested paths

```
putEntry(o, i, v)  ─► publish {"outerKey","innerKey","value"} + barrier
                   ─► local: copy inner snapshot, add entry, swap in

removeEntry(o, i)  ─► sow_delete by data ─► local snapshot shrinks
                                             last entry gone ⇒ outer key removed
                                             (no records ⇒ absent, mirrored locally)

put(o, newMap)     ─► publish EVERY new entry (one barrier)
                   ─► stale = previousKeys − newKeys
                      │  previous from the local snapshot when it knows o,
                      │  from loadOuter(o) when it does not (evicted / fresh instance)
                   ─► sow_delete by data, per stale entry
                   ─► local snapshot replaced
```

`putEntry` is the whole point of the flattened representation: one small
record, no read-modify-write of the outer map, and a second process updating
a *different* inner entry of the same outer key cannot be overwritten —
the `(outerKey, innerKey)` pair is the record's identity, so the writes land
on different records. The integration suite pins this at the byte level: after
updating one inner entry, the sibling record is asserted byte-identical.

Whole-map replacement is deliberately ordered *new entries first, stale
deletes second*, so a concurrent reader never sees the outer map emptier
than either version — but it is not atomic, and the stale set consults the
store when the local replica has been evicted, so replacement is correct
even from an instance that never saw the previous value. Inner maps handed
out by `get` are immutable snapshots: a mutation smuggled into one would
have no way to write through, so it is impossible instead of silently local.

## Why AMPS needs no help to be a key/value store

A SOW topic *is* a server-maintained map: last value per key, queryable at
any time, persistent on disk if you say so. So the whole integration is
choosing keys and message shapes — there is no serialization framework, no
compaction to wait for, and `store()` is a plain publish, because on a SOW
topic **a publish is an upsert**.

The flat cache uses one record per entry on `cache.entries`, keyed
`<Key>/key</Key>`:

```json
{"key": "user-42", "value": {"name": "Ada", "level": 7}}
```

The `value` member is an envelope on purpose: a value can be a JSON object,
but also a bare string or number, which would have no field to carry the key.
The envelope gives every value shape the same address (`/value`) for content
filters, and the key field doubles as the SOW key so the server enforces
last-value-per-key. The JSON itself comes from one shared Gson configured so
values survive the round trip *typed*: integral numbers hydrate as `Long`
(default Gson would return `42.0` for a stored `42` — a different object
that fails `equals()`, silently breaking "recovered cache equals original").

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

This analysis has a sequel:
[hazelcast-persistent-store](../hazelcast-persistent-store/README.md) applies
Option B one level up — the "outer key" becomes the *cache name*, which is
how any number of Hazelcast maps share one SOW topic — and reuses this
module's plumbing to do it.

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
