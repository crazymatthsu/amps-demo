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

## Component architecture

```
 application code
      │  put / get / remove / getAll / clear …
┌─────▼────────────────────── Hazelcast member (one JVM) ──────────────────────┐
│                                                                              │
│  IMap proxy ──► partition routing: hash(serialized key) → partition (of 271) │
│                 → the partition's OWNER member executes the operation        │
│                                                                              │
│  on the owner:                                                               │
│    in-memory record store (owner copy + backup replication to other members) │
│    MapStore offload executor        write-behind queue (per partition,       │
│    (AMPS round trips never block     drained every writeDelaySeconds,        │
│     partition threads)               coalesced, batched)                     │
│         │                                 │                                  │
│  ┌──────▼─────────────────────────────────▼──────────── this module ──────┐  │
│  │  AmpsHazelcastMapStore          one instance PER MAP per member;       │  │
│  │   (MapStore + lifecycle SPI)    routes every call under its map's name │  │
│  │        │                                                               │  │
│  │  AmpsTierStore                  records, filters, bulk-load strategy,  │  │
│  │        │                        chunked deletes                        │  │
│  │        ├── ValueCodec           IMap value ⇄ JSON  (GsonValueCodec)    │  │
│  │        ├── AmpsFilters          literal quoting, expressibility        │  │
│  │        └── SowOps               publish / flush barrier / sow query /  │  │
│  │                                 sow_delete   (from cache-persistent-   │  │
│  │                                 store, shared plumbing)                │  │
│  │  SharedAmpsClients              ONE refcounted HAClient per member per │  │
│  │                                 URI, shared by every map's store, with │  │
│  │                                 a publish store for reconnect replay   │  │
│  └───────────────────────────────────┬────────────────────────────────────┘  │
└──────────────────────────────────────┼───────────────────────────────────────┘
                                       │  tcp://…/amps/json
                                       │  publish · publish_flush · sow · sow_delete
┌──────────────────────────────────────▼─── AMPS ──────────────────────────────┐
│  parse JSON → composite SOW key from /map + /key → upsert into the tier      │
│                                                                              │
│  topic hz.persistent               topic hz.volatile                         │
│    Durability persistent             Durability transient                    │
│    TransactionLog entry              <Expiration>60s</Expiration>            │
│    HashIndex on /map                 HashIndex on /map                       │
│    ./sow/hz.persistent.sow           (memory only)                           │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Topics are grouped by persistence policy — a "tier" — not created per
cache.** A record carries its map's name, and the composite SOW key
`<Key>/map</Key><Key>/key</Key>` makes `(map, key)` the record identity, so
any number of maps share a tier without colliding. 50 caches ≈ 2 topics.
Everything on one tier shares its `Durability`/`Expiration`/journal policy;
add a tier when you need a different *policy*, never merely a new map. (There
is also no per-cache journal to worry about: AMPS keeps **one transaction log
per instance** — topics are merely listed in it.)

## Data flow, path by path

Every SPI call reduces to one or a few AMPS commands. The full mapping:

| Hazelcast operation | SPI call on this module | AMPS command(s) |
| --- | --- | --- |
| `put`/`set`/`replace`, EntryProcessor write (write-through) | `store(k, v)` | `publish` + `publish_flush` barrier |
| write-behind queue drain, `map.flush()` | `storeAll(batch)` | N × `publish` + ONE `publish_flush` |
| `get` on a memory miss | `load(k)` | `sow` filter `/map = 'm' AND /key = 'k'` |
| `getAll` misses, partition bulk load | `loadAll(keys)` | chunked `sow` OR-filters, or one `/map` scan (see below) |
| initial load, step 1 | `loadAllKeys()` | `sow` filter `/map = 'm'` (keys extracted, values not decoded) |
| `remove`/`delete`/`removeAll` | `delete(k)` | `sow_delete` **by data** `{"map","key"}` |
| `clear()` | `deleteAll(keys)` | chunked `sow_delete` by filter |
| `evict`/`evictAll`/TTL expiry | — nothing — | — (see [semantics](#semantics-worth-knowing-before-relying-on-it)) |

### Write-through (`write-delay-seconds: 0`)

```
put(k,v) ─► owner member ─► offload executor ─► store(k,v) ─► encode ─► publish ─► flush barrier
                                                                                        │
             in-memory update + backup replication ◄── only after the store returns ◄───┘
```

1. The caller's `put` routes to the key's partition owner; the MapStore call
   is offloaded from the partition thread (Hazelcast 5.x default), so the
   AMPS round trip never stalls other keys in the partition.
2. The adapter validates the key (both-quote-character keys are rejected
   here, before anything is published), encodes the value through the codec,
   and builds the envelope `{"map","key","value"}`.
3. `SowOps.publish` writes it on the member's shared connection and then
   issues `publish_flush`, which returns only when AMPS has **processed** the
   message. That barrier is what makes a completed `put` immediately visible
   to a hydrating process elsewhere — AMPS publishes are otherwise
   asynchronous.
4. Only if the store returns does the entry commit to the owner's memory and
   replicate to backups. A store failure propagates to the caller and leaves
   memory untouched — local and remote never disagree about a write that
   "worked".

### Write-behind (`write-delay-seconds > 0`)

```
put(k,v) ─► owner ─► in-memory update (immediately) ─► write-behind queue
                                                            │  every delay:
                                                            │  coalesce (last value per key)
                                                            ▼  batch (write-batch-size)
                                              storeAll(batch) ─► N × publish ─► ONE flush
```

Memory first, AMPS later: the queue coalesces repeated writes to one key
(`write-coalescing: true`, the default) and drains in batches, so a hot key
updated 1,000 times in the delay window costs one record. Set
`amps.flushPerWrite: "false"` on write-behind maps — the queue has already
decoupled the caller, so per-store barriers buy nothing; batches still flush
once at the end. Graceful `shutdown()` drains the queues into AMPS;
`terminate()` loses them.

### Read-through and hydration

```
get(k) miss ─► load(k) ─► sow /map='m' AND /key='k' ─► decode ─► cache in memory ─► return

member/cluster start, initial-mode EAGER:
  getMap() ─► ONE member: loadAllKeys() ─► sow /map='m'   (keys only; values not decoded)
           ─► keys hashed to partitions ─► EACH owner: loadAll(its keys)
           ─► entries populate owners + backups
```

`loadAll` picks its strategy by size: up to 256 requested keys it issues
chunked OR filters (`/map = 'm' AND (/key = 'a' OR /key = 'b' …)`, 32 keys
per query); above that it runs one `/map`-filtered scan and intersects
client-side — an EAGER hydrate asks for every key anyway, and thousands of
per-key filters would be slower than one indexed scan. The `HashIndex` on
`/map` (syntax verified against 5.3.5.135:
`<HashIndex><Key>/map</Key></HashIndex>`) is what keeps all of these
per-map operations from scanning the whole tier as it grows.

`initial-mode: LAZY` (the Hazelcast default) defers the same sequence until
the map is first touched. A single member failing needs no AMPS involvement
at all — partition migration promotes in-memory backups; the loader is for
process death, not partition failover.

### Deletes

Single-key deletes go **by data**: the adapter sends `{"map": "m", "key":
"k"}` and AMPS recomputes the composite SOW key from the message exactly as
it did on publish — no filter expression is involved, so no quoting rules
apply. `clear()` arrives as `deleteAll(keys)` and becomes chunked
`sow_delete`-by-filter calls scoped by `/map`, which is why one map's
`clear()` cannot touch a tier-mate (pinned by the integration suite).

### The connection underneath

All of a member's maps share **one** AMPS connection (`SharedAmpsClients`,
refcounted; closes when the member shuts down): an `HAClient` with a
file-backed publish store, so if AMPS restarts under a running member the
client reconnects and replays unacknowledged publishes — at-least-once
across the reconnect, deduplicated server-side by sequence number. Give the
map-store a stable `amps.clientName` if you want that replay to work across
*member* restarts too; the generated default is unique per process.

## Supported Hazelcast cache types

The MapStore SPI is an **IMap** feature, and IMap is what this module
supports — in every persistence mode the SPI defines:

| IMap feature | supported | notes |
| --- | --- | --- |
| write-through (`write-delay-seconds: 0`) | ✅ | store-first: a failed publish fails the `put` and leaves memory unchanged |
| write-behind (`write-delay-seconds > 0`) | ✅ | coalesced + batched into `storeAll`; set `amps.flushPerWrite: "false"` |
| read-through (`get` on miss) | ✅ | one filtered `sow` query |
| initial load EAGER / LAZY | ✅ | `loadAllKeys` + partition-scoped `loadAll` |
| `getAll` / bulk read-through | ✅ | arrives as `loadAll`; chunked filters |
| EntryProcessor mutations | ✅ | Hazelcast persists them through `store()` like any write |
| TTL / max-idle maps | ⚠️ | works, but pair with the volatile tier — expiry does **not** call `delete()` (see semantics) |
| Near Cache on the map | ✅ | orthogonal: it caches the IMap, never talks to the store |
| `TransactionalMap` | ❌ | Hazelcast does not bring MapStores into its transactions — keep persisted maps out of them |

Key and value constraints: **keys are `String`** (the key doubles as the
`/key` SOW field, so it must be a stable string; a key containing both `'`
and `"` is rejected at store time because no AMPS filter literal could ever
name it). **Values** must round-trip the codec (details below) and be
Java-serializable, because Hazelcast itself moves them between members.

Other Hazelcast structures, for completeness:

- **`ReplicatedMap`, `MultiMap`, `ISet`, `IList`** — no store SPI exists for
  them in Hazelcast OSS; nothing to implement against. A `MultiMap`-shaped
  need (one key, many values) fits an IMap whose value is a list, or — for
  per-inner-entry records — the flattening pattern below.
- **`IQueue` / `Ringbuffer`** — have their own SPIs (`QueueStore`,
  `RingbufferStore`, both keyed by a `Long` sequence). The same tier-topic
  approach would work (sequence number as `/key`); not implemented here.
- **`ICache` (JCache)** — uses the standard `javax.cache` CacheLoader /
  CacheWriter SPI. An adapter over the same `TierStore` would be ~100 lines;
  not implemented here.

## Persisting composite data types

### The composite SOW key

The record's identity is the **pair** of fields the topic declares:

```xml
<Topic>
    <Name>hz.persistent</Name>
    <MessageType>json</MessageType>
    <Key>/map</Key>
    <Key>/key</Key>
    ...
</Topic>
```

Both fields must be present on every publish (the adapter always sets both;
a record missing a key field would be rejected by AMPS). Two consequences
worth internalizing:

- a publish **is** an upsert on the pair — `store()` never needs
  read-modify-write, and two maps sharing the tier cannot collide;
- delete-by-data needs only the key fields — `{"map":"orders","key":"ord-1"}`
  deletes exactly that record, because AMPS recomputes the same composite
  key from the message body.

### Value shapes on the wire

Everything the codec produces lands under `/value`, giving every value shape
the same address for content filters. With the default untyped codec:

| IMap value (Java) | record on the wire |
| --- | --- |
| `"plain string"` | `{"map":"m","key":"k","value":"plain string"}` |
| `42L` | `{"map":"m","key":"k","value":42}` |
| `List.of("a", 1L)` | `{"map":"m","key":"k","value":["a",1]}` |
| `Map.of("qty", 250L)` | `{"map":"m","key":"k","value":{"qty":250}}` |
| nested `Map<String, Map<String, ?>>` | `{"…","value":{"AAPL":{"qty":250},"MSFT":{"qty":100}}}` |
| POJO via typed codec | `{"…","value":{"symbol":"AAPL","quantity":250,"price":"187.50"}}` |

Because values sit at a fixed depth, server-side content filters address
them uniformly across every map on the tier: `/map = 'orders' AND
/value/qty > 1000` finds big orders without loading anything into a JVM.

### Type fidelity rules (what survives the round trip)

The codec shares `cache-persistent-store`'s Gson configuration, and these
rules are what make "recovered map `equals` original" true rather than
approximately true:

- **Integral numbers hydrate as `Long`**, fractional as `Double`
  (`LONG_OR_DOUBLE` policy). Default Gson would turn a stored `42` into
  `42.0` — a different object that fails `equals()` — which is exactly the
  silent corruption the unit tests pin against.
- **`BigDecimal` needs a decision.** Encoding preserves the exact digits,
  but the *untyped* codec decodes JSON numbers to `Long`/`Double`, so
  `new BigDecimal("187.50")` comes back as `187.5d`. Either store exact
  decimals as strings (the demo's `"price": "187.50"` does this
  deliberately) or use a typed codec — a POJO field declared `BigDecimal`
  decodes losslessly from the JSON number text.
- **Enums** encode as their name string; a typed codec decodes them back.
- **Dates/times** have no special handling: store epoch millis (`Long`) or
  ISO-8601 strings. Don't store `java.util.Date` through the untyped codec
  and expect a `Date` back.
- **`null` values are rejected** at the API (as in `ConcurrentHashMap`);
  `null` *fields inside* a POJO are simply **omitted** from `/value` (Gson's
  default), so "field absent" and "field was null" are the same thing after
  recovery — make POJO fields optional-tolerant or avoid nullable fields.
- Decoded untyped objects are Gson maps/lists — `java.io.Serializable`, so
  Hazelcast can replicate and store them; equality with the originals is by
  contents, per the `Map`/`List` contracts.

### Typed POJO codecs

Subclass the factory and route by map name:

```java
public class TradingMapStoreFactory extends AmpsMapStoreFactory {
    @Override
    protected ValueCodec<Object> codecFor(String mapName, Properties properties) {
        if (mapName.equals("positions")) {
            @SuppressWarnings("unchecked")
            ValueCodec<Object> codec =
                    (ValueCodec<Object>) (ValueCodec<?>) new GsonValueCodec<>(Position.class);
            return codec;
        }
        return super.codecFor(mapName, properties); // untyped JSON
    }
}
```

then declare `factory-class-name: …TradingMapStoreFactory` on the map.
Anything Gson maps works — plain classes, and Java records (Gson ≥ 2.10;
this repo ships 2.11). For fully custom encodings (protobuf-JSON, say),
implement `ValueCodec` directly; the contract is one line:
`decode(encode(v)).equals(v)`.

### Composite business keys

The SOW key fields are fixed per topic, so a business key like
`(account, symbol)` cannot become a third `<Key>` without a new tier.
Serialize it into the one string key and, if you query by its parts, carry
them in the value too:

```java
positions.put(account + "|" + symbol,                  // identity: the string key
        Map.of("account", account, "symbol", symbol,   // queryability: the fields
               "qty", 250L));
```

Identity comes from the key (`get`, `remove`, upserts); per-component
queries are content filters on the value (`/map = 'positions' AND
/value/account = 'ACC-1'`). Pick a separator that cannot appear in the
components, and remember the quoting rule applies to the whole composed
string.

### One entry vs. many records — the nesting decision

A `Map<String, Map<String, ?>>` **value** persists as one nested record: one
IMap entry, one publish, atomic replacement, exactly what most caches want.
If instead you need *per-inner-entry* records — fine-grained updates,
concurrent writers to different inner keys, per-inner-entry queries — that
is a data-model decision, not a codec one: flatten the inner key into the
IMap key (`outer + "|" + inner`, as above) so each inner entry is its own
`(map, key)` record. The full trade-off analysis lives in
[cache-persistent-store's README](../cache-persistent-store/README.md#the-map-of-maps-question);
this module's tier layout is that analysis' "Option B" applied one level up,
with the map name as the outer key.

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
- **Pull-only loader** — once a key is resident, Hazelcast never re-asks
  AMPS, so updates published by *other* systems are invisible until eviction
  or restart. If AMPS is not solely Hazelcast's store, add a
  `sow_and_subscribe` bridge feeding external updates into the IMap; this
  module deliberately ships without one.
- **Store calls are at-least-once under migration** — a put retried across
  an in-flight partition migration can invoke `store()` twice. Harmless
  here (a SOW publish is an idempotent upsert), and exactly-once holds in a
  migration-quiet cluster — the two-member integration test waits for
  `isClusterSafe()` and then asserts it from a per-entry store log.
- **Graceful vs terminated shutdown** — `shutdown()` drains write-behind
  queues into AMPS; `terminate()` loses them.

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
is the full three-map example (two tiers, write-through and write-behind);
the demo boots from it. Properties: `amps.topic` (required), `amps.uri`,
`amps.timeoutMs`, `amps.flushPerWrite` (`"false"` on write-behind maps),
`amps.clientName` (stable name → publish-store replay across member
restarts).

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
