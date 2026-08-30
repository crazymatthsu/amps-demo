# hazelcast-persistent-store

[Hazelcast open source](https://hazelcast.com/) persisting its `IMap`s in
[60East AMPS](https://www.crankuptheamps.com/). Hazelcast OSS has no native
Persistence (hot-restart is Enterprise-only) — the **MapStore SPI is its
sanctioned persistence mechanism** — so this module implements that SPI on
AMPS SOW topics. Restart a member, or the whole cluster, and the maps
rehydrate from AMPS; restart AMPS itself and the durable tier comes back too.

This module implements [TODO.md](TODO.md), which records the design analysis.
It builds on [cache-persistent-store](../cache-persistent-store/README.md)'s
plumbing rather than re-implementing it.

## The design in one picture

```
IMap "orders"  ─┐                          ┌────────────────────────────────────┐
IMap "audit"   ─┼─ MapStore SPI ─ AMPS ──► │ topic hz.persistent (journalled)   │
                │   (this module)          │   {"map":"orders","key":"ord-1",…} │
                │                          │   {"map":"audit","key":"evt-1",…}  │
IMap "sessions"─┘                          ├────────────────────────────────────┤
                                           │ topic hz.volatile (TTL, transient) │
                                           │   {"map":"sessions","key":…}       │
                                           └────────────────────────────────────┘
```

**Topics are grouped by persistence policy — a "tier" — not created per
cache.** A record carries its map's name, and the composite SOW key
`<Key>/map</Key><Key>/key</Key>` makes `(map, key)` the record identity, so
any number of maps share a tier without colliding. 50 caches ≈ 2 topics.
Everything on one tier shares its `Durability`/`Expiration`/journal policy;
add a tier when you need a different *policy*, never merely a new map. (There
is also no per-cache journal to worry about: AMPS has **one transaction log
per instance** — topics are merely listed in it.)

A hash index on `/map` (syntax verified against 5.3.5.135:
`<HashIndex><Key>/map</Key></HashIndex>`) keeps per-map hydration from
scanning the tier.

## Wiring a map — configuration only, no code

```yaml
map:
  orders:
    map-store:
      enabled: true
      initial-mode: EAGER                # hydrate at startup
      factory-class-name: com.demo.amps.hazelcast.AmpsMapStoreFactory
      properties:
        amps.topic: hz.persistent        # the tier: the only required property
```

[`src/main/resources/hazelcast-example.yaml`](src/main/resources/hazelcast-example.yaml)
is the full three-map example (two tiers, write-through and write-behind); the
demo boots from it. Properties: `amps.topic` (required), `amps.uri`,
`amps.timeoutMs`, `amps.flushPerWrite` (set `"false"` on write-behind maps to
skip the per-store flush round trip), `amps.clientName` (a stable name enables
publish-store replay across member restarts). Subclass
`AmpsMapStoreFactory.codecFor` to give a map a typed POJO codec instead of
untyped JSON values.

Per member there is **one AMPS connection**, shared by every map's store
(refcounted; closes when the member shuts down). It is an `HAClient` with a
file-backed publish store, so if AMPS restarts under a running member it
reconnects and replays unacknowledged publishes.

## How the pieces divide the work

| concern | owner |
| --- | --- |
| partition ownership, initial-load distribution, write-behind queueing, offloading store calls | Hazelcast |
| routing SPI calls to the tier under the map's name | [`AmpsHazelcastMapStore`](src/main/java/com/demo/amps/hazelcast/AmpsHazelcastMapStore.java) |
| records, filters, bulk-load strategy, deletes | [`AmpsTierStore`](src/main/java/com/demo/amps/hazelcast/AmpsTierStore.java) |
| publish/query/delete plumbing, JSON typing, filter quoting | cache-persistent-store (`SowOps`, `AmpsFilters`, `JsonValues`) |

Restart recovery is just the SPI contract: Hazelcast calls `loadAllKeys()`
once, distributes the keys, and each partition owner bulk-loads its share
(`initial-mode: EAGER` for hydrate-at-startup). Bulk loads use chunked `OR`
filters for small key sets and switch to one `/map` scan with client-side
intersection above 256 keys — an EAGER hydrate asks for every key anyway.
`store()` runs on a key's partition owner only, so one writer per key; the
integration suite counts stored entries across a two-member cluster to pin
that.

## Semantics worth knowing before relying on it

- **TTL resurrection** — Hazelcast never calls `delete()` for entries *it*
  expires or evicts, so a TTL'd map on a plain topic would resurrect every
  expired entry on restart. That is what `hz.volatile`'s server-side
  `<Expiration>` is for: match it to the maps' `time-to-live-seconds`.
  (The alternative is Hazelcast's `EntryStore`, which persists expiry
  metadata — not implemented here.)
- **`clear()` vs `evictAll()`** — `clear()` deletes this map's records from
  AMPS (scoped by `/map`; tier-mates untouched); `evictAll()` only drops
  memory and the next load brings everything back.
- **Pull-only loader** — once a key is resident, Hazelcast never re-asks AMPS,
  so updates published by *other* systems are invisible until eviction or
  restart. If AMPS is not solely Hazelcast's store, add a
  `sow_and_subscribe` bridge feeding external updates into the IMap; this
  module deliberately ships without one.
- **Graceful vs terminated shutdown** — `shutdown()` drains write-behind
  queues into AMPS; `terminate()` loses them.
- Keys are Strings, values must survive the codec round trip (and be
  Java-serializable for Hazelcast itself); a key containing both quote
  characters is rejected at store time, same rule as the sibling module.

## Running it

```bash
export AMPS_IMAGE=amps-demo:5.3          # your image tag (see repository README)

./hazelcast-persistent-store/scripts/amps-hazelcast.sh start
./gradlew :hazelcast-persistent-store:run
./hazelcast-persistent-store/scripts/amps-hazelcast.sh restart   # then run again:
                                                                 # hz.persistent survived
./hazelcast-persistent-store/scripts/amps-hazelcast.sh stop
```

The demo starts a real member from the example YAML, writes through three
maps, dumps the raw tier records, replaces the member with a fresh one that
hydrates from AMPS alone, forces a read-through with `evict()`, and shows
`audit.clear()` leaving `orders` untouched on their shared topic.

## Tests

```bash
./gradlew :hazelcast-persistent-store:test              # unit: adapter routing, filter
                                                        # chunking, codec round trips
AMPS_IMAGE=amps-demo:5.3 \
./gradlew :hazelcast-persistent-store:integrationTest   # AMPS container + real embedded
                                                        # members; skipped without an image
```

The integration suite covers the TODO's list end to end: IMap writes verified
by querying AMPS directly, a replacement member rehydrating every map, a
two-member cluster with partition-scoped loads and exactly-once stores
(counted), `clear()` scoping on a shared tier, and an AMPS restart after which
the durable tier hydrates while the volatile tier is — correctly — gone.
`./server/scripts/amps.sh validate hazelcast` accepts the flow config.
