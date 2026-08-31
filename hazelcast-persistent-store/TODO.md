
### this submodule will let Hazelcast open source use AMPS as its cache persistence store
- Hazelcast OSS has no native Persistence (hot-restart is Enterprise-only); the sanctioned
  persistence mechanism is the MapStore SPI -- so this module is a MapStore/MapLoader
  implementation backed by AMPS SOW topics
- build on cache-persistent-store: reuse its envelope/JSON/filter/SowOps machinery rather
  than re-implementing it; the composite-key layout there (`cache.nested.entries`) is the
  proven shape this module generalizes
- if Hazelcast restarts (one member or the whole cluster), maps rehydrate from AMPS

#### Hazelcast adapter features
- `AmpsHazelcastMapStore` implementing `com.hazelcast.map.MapStore<String, V>` and
  `MapLoaderLifecycleSupport`
  - `init(instance, properties, mapName)` binds the map name to its topic/tier and
    resolves the shared AMPS client; `destroy()` releases it
- `AmpsMapStoreFactory` implementing `MapStoreFactory`: one factory declared once in
  hazelcast.yaml serves every map -- zero code per cache; map name + per-map
  `MapStoreConfig` properties (uri, tier/topic, timeout) drive everything
- ONE AMPS `HAClient` per Hazelcast member, shared across all maps' stores (many caches
  must not mean many TCP connections); use a publish store for at-least-once across
  AMPS reconnects
- pluggable value codec (JSON via Gson by default) so IMap values can be POJOs, not just
  Map<String, ?>; keys are String in v1 (stable string key = SOW key field); keep the
  "no key with both quote characters" rule from cache-persistent-store
- methods the cache module does not have yet, required by Hazelcast's partitioned load
  protocol:
  - `loadAllKeys()` -- keys-only SOW query for one map
  - `loadAll(Collection<String> keys)` -- bulk load of the keys a member owns; prefer
    one filtered query per member + client-side key intersection over thousands of
    per-key OR filters on an EAGER hydrate
  - `deleteAll(Collection<String> keys)` -- batched deletes (chunked delete-by-data)
- write-behind support: `writeDelaySeconds > 0` funnels into `storeAll`; batch publishes
  with a single `publishFlush` per batch, and allow relaxing the per-write flush barrier
  in write-behind mode (Hazelcast has already decoupled the caller)

#### topic layout : DECIDED -- group by persistence policy, not by cache count
- one SOW topic can serve many Hazelcast caches; do NOT create one topic per cache
- primary layout ("tier topics"): a composite-key topic per persistence policy, records
  shaped `{"map": "<mapName>", "key": "...", "value": ...}` with
  `<Key>/map</Key><Key>/key</Key>`
  - (map, key) is the record identity: entries of different caches cannot collide, and
    hydrating one cache is a server-side filtered query on /map
  - example tiers: `hz.persistent` (Durability persistent + journalled) and
    `hz.volatile` (Durability transient + `<Expiration>` matching the maps' TTL)
  - 50 caches ~= 2-3 topics; a new cache at runtime needs no server config change
  - declare a hash index on /map so per-map hydration does not scan at scale
- alternative (implement or document, not both required): dynamic pattern topic family
  `<Pattern>^hz\.cache\.[A-Za-z0-9_-]+$</Pattern>` with `<Key>/key</Key>` -- one config
  element, each cache gets its own LOGICAL topic name; choose when per-cache
  subscriptions/monitoring by topic name matter; regex goes in `<Pattern>`, never
  `<Name>` (verified repo-wide against 5.3.5.135)
- explicit one-topic-per-cache remains only for a cache whose retention/durability
  policy no tier covers
- journal: there is NO per-topic journal store in AMPS -- one TransactionLog per
  instance, and topics are merely listed in it; grouping therefore only affects config
  entries and SOW files. Journal the persistent tier (crash-consistency for unclean
  server stops); client rehydration reads the SOW and never replays the journal

#### restart / rehydration
- cluster restart: Hazelcast calls `loadAllKeys()` once, distributes keys to partition
  owners, each member `loadAll`s its share; support both `InitialLoadMode` EAGER
  (hydrate at startup) and LAZY (first touch)
- single-member failure needs no AMPS involvement (partition migration is in-memory);
  only partition owners invoke store() -- one writer per key in normal operation
- known semantic gaps to handle or document explicitly:
  - TTL resurrection: Hazelcast does NOT call delete() on expiry/eviction, so expired
    entries persist in AMPS and return on restart -- fix via the volatile tier's AMPS
    `<Expiration>`, or implement Hazelcast's `EntryStore` (persists expiry metadata)
  - `IMap.clear()` calls deleteAll and wipes AMPS; `evictAll()` does not -- document
  - MapLoader is pull-only: external AMPS publishers' updates are invisible to resident
    keys; if AMPS is also written by non-Hazelcast systems, add an optional
    `sow_and_subscribe` bridge feeding external updates into the IMap, else state that
    the cluster is the sole writer

#### amps and hazelcast config examples
- new server flow `hazelcast` (server/config/flows/hazelcast/) declaring the tier
  topics; must pass `./server/scripts/amps.sh validate hazelcast`
- hazelcast.yaml example wiring `AmpsMapStoreFactory` into two maps on different tiers,
  with write-through and write-behind variants
- script to run AMPS in podman: thin wrapper pinning AMPS_FLOW=hazelcast, same as
  cache-persistent-store/scripts/amps-cache.sh

#### demo testing
- unit tests against in-memory fakes: adapter contract (loadAllKeys/loadAll batching,
  deleteAll chunking), codec round-trips, tier routing by map name
- integration test with a real AMPS container (reuse the AmpsTestServer pattern; skip
  without AMPS_IMAGE) and an EMBEDDED Hazelcast member:
  - write through IMap, query AMPS directly to see the tier-topic records
  - shut the member down, start a fresh one, assert the IMap rehydrated from AMPS
  - restart the AMPS container, assert the persistent tier survived and hydrates
  - two-member cluster: partition-scoped loading, stores invoked on owners only
  - one map's clear() must not disturb another map sharing the same tier topic
